> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# Job Scheduler Operational Contract（T-Review-4）

- 状态：**实现完成，待独立复核**（对象：本契约 + `AiAnalysisJobLeaseService`/`AiSchedulerPolicy`/`AiAnalysisRunRecoveryScheduler`/`AiAnalysisSchedulerMetricsService` + `AiJobSchedulerIntegrationTest`）。
- 范围：持久调度器的运营契约——领取（claim）、租约（lease）、恢复（recovery）、停机（graceful shutdown）、取消、指标与可重放性。M0 的第四道硬门禁；不通过不得进入 T06 生产迁移。

## 1. 领取（claimNextJob）

- **事务方案（第三十轮补审 C1 后更新）**：CAS 主语句 + **项目行锁短事务**：
  0. 在短事务内先 `SELECT id FROM projects WHERE id=? FOR UPDATE`（跨进程串行化同项目领取，关闭「门判定与 CAS 非原子」的双发放窗口——补审实测自然竞态 21/21 双发放）；
  1. `UPDATE ai_analysis_worker_leases SET owner_id=?, lease_until=?, fencing_token=fencing_token+1, updated_at=? WHERE run_id=? AND (lease_until < ? OR owner_id=?)`；
  2. 影响 0 行且不存在本人持有时 `INSERT`（主键冲突回落为「被他人有效持有」= `RUN_BUSY`）。
  事务仅覆盖上述语句序列（毫秒级），**不跨任何模型调用**——「无长事务」语义不变（§7）。事务传播固定为 `REQUIRES_NEW`（第三十轮补审 2 C4-3：若被外部事务包裹，默认传播会静默 join——内层 READ COMMITTED 失效且行锁持有到外层提交）；业务路径的锁等待/查询异常优雅拒绝（返回 null，不抛 `CannotAcquireLockException`，第三十轮补审 2 C4-2）。**边界（第三十轮补审 5 C-2）**：`REQUIRES_NEW` 需另取连接，若连接池被抢占（如外部事务占满池=1 的极端场景）会在 try 之外抛 `CannotCreateTransactionException`——生产调用方均在事务外且池=10，属不可达潜伏边界。
- **生产路径接线（第三十轮 C1/C2 修复）**：生产派发（dispatcher → `worker.processRun(runId)`）、操作员 `/resume`、轮询 `claimNextJob` **三条路径统一经 `claim(runId)`**，在其中强制：
  - **状态守卫**：`cancelled`/`succeeded` 一律拒绝（不可被任何路径复活）；`failed` 默认拒绝（自动路径语义），仅 `claim(runId, true)`（`/resume` 人工通道）放行——守卫在**项目行锁事务内复核**（第三十轮补审 7/8：关闭 guard 读后翻转窗口）；不存在的运行在 claim 层拒绝，worker 入口（`processRun`/`resumeRun`）返回结构化 `missing` 结果（不抛 500，第三十轮补审 C5）；
  - **停机守卫**：`stopClaiming()` 后一律拒绝（含 `/resume`）；
  - **项目门（第三十轮补审 C1 原子化）**：同项目存在「`worker_status='running'` 或持有**新鲜租约**（`lease_until >= now`）」的其它任务时拒绝——门判定与 CAS 在同一项目行锁事务内，并发同项目 claim 至多一个胜者（锁测 `concurrentSameProjectClaimsGrantAtMostOneLease`）；新鲜租约判据关闭了「已领取但未及标记 running」的绕过窗口（第三十轮 C2 实测复现的 bypass）。
  - **claim→running 窗口（第三十轮补审 C2）**：`markRunRunning` 带围栏 + `status not in ('cancelled','succeeded')` 条件；窗口内落下的取消/接管使该 UPDATE 影响 0 行，worker 即释放租约并以 `cancelled`/`preempted` 退出——取消不再被静默吞掉。
- **排序优先级**：FIFO —— `order by created_at asc, id asc`；操作员手动 resume（`POST .../resume`）绕过 FIFO 直达指定任务（但同样受上述守卫约束）。
- **候选过滤**：`status='queued' AND worker_status='idle'`；排除本人已持有租约的任务；项目级并发门见下。
- **并发默认值**：
  - **global=1**：单调度器线程串行（`AiAnalysisRunDispatcher` 单线程执行器）；多进程部署时由租约保证同一任务只有一个 owner，全局并发=进程数×1；
  - **per-project=1**（`app.ai.scheduler.max-active-runs-per-project`，上限 4）：领取时跳过「同项目已有 `worker_status='running'` 任务」的候选；
  - **actor 维度**：需求分析运行无 actor 级配额，actor 字段仅审计用途（留待 T06 若引入任务级配额再扩展）。

## 2. 策略值（AiSchedulerPolicy）

| 值 | 默认 | 平台上限 | 选择理由 |
|---|---|---|---|
| lease TTL | 60s | [30, 300]s | 覆盖「一次心跳丢失 + 重试窗口」；远小于人工介入耗时，崩溃后 ≤1 分钟可被接管 |
| renew interval | 15s | [5, TTL/2]s | TTL/4 是通用起点；必须严格小于 TTL，上限 TTL/2 |
| recovery interval | 30s | [10, 300]s | 独立于 TTL 的扫描节奏；启动时立即执行一次 |
| 心跳容错 | 连续 2 次续租失败才判失联 | — | 第三十轮 C5：瞬时数据库故障不得立刻把整条运行判死 |
| graceful shutdown deadline | 30s | [10, 120]s | 停止领取后等待在飞任务的自然结束窗口；超时强杀，未完成任务由租约过期 + 恢复扫描接管 |
| per-project 并发 | 1 | [1, 4] | 单项目串行避免同项目文档互相污染分析快照；上限 4 供后续调优 |

所有值可由配置覆盖，但**越界即启动失败**（fail-closed），不允许静默夹取——见 `AiSchedulerPolicy.validate()` 与 `AiSchedulerPolicyTest`。

## 3. 围栏与「旧 token 写入被拒」（第三十轮 C3 精确化）

- 每次领取/接管 `fencing_token+1`；租约身份 = `(owner_id, fencing_token)` 组合。
- **带围栏的写入（影响 0 行即旧 token 被拒）**：分片状态（succeeded/failed）、运行的终态（failed/succeeded）、completed 计数、`current_chunk_id`、run 心跳；运行的成功收尾（origin 同步 / summary / `run_succeeded` 事件）以 `markRunSucceeded` 的围栏结果为准，仅提交成功（1 行）后才执行（第三十轮 C3 修复：旧 owner 无法补写收尾）。`markRunSucceeded` 另带 `status <> 'cancelled'` 条件（第三十轮补审 2 C4-1：收尾窗口内落下的取消不得被覆盖为 succeeded；0 行时按真实状态以 `cancelled`/`preempted` 退出且不写成功尾巴）。
- **无围栏、有意保留的观测/派生写入**（append-only 审计与可再生成数据，旧 owner 的迟到写入不改变终态语义）：事件流、call records（审计轨迹）；draft merge 由写入前的 `ensureLeaseHeld` 守卫（存在毫秒级检查-写入窗口，已在集成测试的接管场景验证其效果为零重放）。**coverage 扫描已移至围栏提交（`markRunSucceeded`=1 行）之后**（第三十轮补审 C3：此前它在提交前执行、stale owner 实测可写 `coverage_status`）；成功路径的 origin/summary/事件同样以围栏结果为门。失败/超时路径的 summary 写入紧跟 `ensureLeaseHeld` 之后（毫秒级窗口，与 draft merge 同级；deadline 分支另有 `isLeaseHeld` 早退）。
- 旧 owner 检测到失围栏后：不发 `run_failed`、不改运行状态、不写收尾，直接以 `preempted` 退出（结果只进返回值与日志）。
- 正常完成的释放 = 删除本人名下（owner+token 匹配）的租约行——这是一次干净释放执行者的最后动作，之后不存在该身份的迟到写入者（删除即失去身份来源）。

## 4. 恢复扫描（recovery job）

- **触发**：应用就绪（`ApplicationReadyEvent`）立即一次，之后按 `recovery interval` 周期执行（`AiAnalysisRunRecoveryScheduler`）。
- **判定与复核**：`status='running' AND worker_status='running'` 且（租约已过期 OR 租约行缺失）→ 重新排队：`status='queued', worker_status='idle'`，`resume_from_chunk_id` 取原续跑位（缺失时回落到 `current_chunk_id`）。**重排 UPDATE 内再次复核租约仍过期/缺失**（第三十轮 C7：select 与 update 之间被续租的任务不会被误重排）。
- **有效租约不受影响**；恢复动作留下 `lease_recovered`（warn）事件，可重放、可用于计数。
- **重排后自动派发（第三十轮补审 C4）**：`recoverExpiredRuns` 产出的每个 runId 随即经 `dispatcher.dispatchRecovered(runId)` 派发（受停机守卫约束）——崩溃/强杀后的重排闭环到执行，不再依赖人工 `/resume`。
- **孤儿重派（第三十轮补审 2 判定级 2；第三十轮补审 4 C-1、5 C-1 收敛）**：恢复扫描第二相把「仍 `queued/idle` 且**无新鲜租约**」的两类任务重新纳入派发清单——(a) 存在 `lease_recovered` 事件者（上次派发被项目门/停机拒绝）；(b) 无事件但创建后超过一个恢复周期仍未领取者（首派发被门静默拒绝的常规新任务）。谓词按「无新鲜租约」（`lease_until < now` 的可残留）判定，覆盖正常崩溃态留下的过期租约行；任务不会永久 stranded（按恢复间隔自然退避，不重复发事件；claim 的 CAS 会替换过期租约行）。
- **至少一次语义（第三十轮补审 2 判定级 1 边界声明）**：租约过期即视为 owner 失联并允许接管；此时**进行中**（未提交完成）的分片可能被接管方重新执行一次模型调用——这是 at-least-once 的固有边界（心跳存活时租约不会被判过期；失联判据为连续续租失败）。**已完成分片绝不重复**（§5 谓词保证）。
- **停机**：`stopClaiming()` 后领取立即返回 `STOPPING`——轮询、direct claim、`/resume` 三条路径一致拒绝；调度器不再派发新任务（`reject` 窗口内 executor 拒绝也静默保留任务，第三十轮 C6）；在飞任务继续（受围栏保护），超过 graceful deadline 强杀后由恢复接管。

## 5. 取消与不重复

- **取消阻止新尝试（第三十轮 C1 修复，三路径一致）**：领取只匹配可领取状态且**显式拒绝 `cancelled`**；已取消任务经生产派发、`/resume`、轮询领取任一入口都返回 `cancelled` 且不产生租约（集成测试 `cancelledRunIsRefusedByEveryClaimingPath`）；在飞任务在每个分片边界检查取消状态。
- **已完成分片不重复**：续跑查询谓词 = `status in ('planned','failed')`（`AiAnalysisJobLeaseService.processableChunks`，与 Worker 共用同一来源），`succeeded`/`skipped` 分片在恢复后不会再产生模型调用；失败分片允许重试。
- 集成测试 `crashTakeoverRejectsStaleOwnerWritesAndNeverReprocessesCompletedChunks` 以调用计数证明：分片 1 在崩溃+接管+续跑全程只产生 1 次模型调用。

## 6. 指标与告警阈值（GET /api/ai/scheduler-metrics）

| 指标 | 来源 | 口径 | 告警阈值（建议） |
|---|---|---|---|
| queue wait | `ai_analysis_runs.started_at - created_at`（最近 100 条） | avg / max | avg > 120s 或 max > 600s |
| lease age / remaining | `ai_analysis_worker_leases.updated_at / lease_until` | 当前租约 avg/max 年龄、最小剩余 | 剩余 < renew interval ×2（≈30s） |
| attempt duration | `ai_call_records.duration_ms`（最近 100 条） | avg / max | max > per-call timeout（120s） |
| TTFB | `ai_runtime_vertical_attempts.ttfb_ms`（最近 100 条） | avg / max | max > 30s（响应头到达） |
| blocked count | `ai_analysis_runs.result_origin='BLOCKED'` | 计数 | > 0 需人工关注 |
| recovery count | `ai_run_events.stage='lease_recovered'` | 计数（随 `projectId` 过滤） | > 0 需排查（每次恢复代表一次失联） |

- 无数据一律返回 **null，不伪装 0**；TTFB 仅由 Runtime 传输层（`AiProtocolTransportClient`，响应头到达计时，非流式请求下即首字节上界）写入，旧网关路径（`OpenAiCompatibleAdapter`）尚未计测，迁移由 T05/T06 收口。
- 响应同时携带 `policy` 块（TTL/续租/恢复/停机窗口/项目并发）便于线上核对。

## 7. 可重放性与事务边界

- **状态与事件可重放**：`ai_run_events.id` 单调递增，`listByRun` 按插入顺序返回；运行/分片状态任意时刻可从表直接读取，断线/刷新不丢语义（集成测试断言事件 id 有序且 `lease_recovered` 恰一条）。
- **无数据库长事务等待上游**：领取是**毫秒级短事务**（项目行锁 + RC + REQUIRES_NEW，§1）；模型调用期间不持有任何数据库事务（集成测试在持有租约 2.5s 后用 `information_schema.processlist` 断言无 `>2s` 的 Query）。
- **心跳**：按 renew interval 续租 + 维护 `heartbeat_at` + 发出 `run_heartbeat` 事件；续租**连续失败 2 次**置位 `leaseLost`（瞬时故障容忍），worker 在下一个检查点停止。允许「过期但未被接管」的租约继续续租（单一 owner 语义不受影响；被接管后因 (owner,token) 不匹配而失败）。

## 8. 验证与证据

- **集成测试（真实 MySQL 8.4，专用库 `ai_scheduler_it`）**：`AiJobSchedulerIntegrationTest`（**15 用例**）——双 worker 竞争单 owner、到期接管 fencing+1、崩溃后恢复重排 + 旧 owner 写入被拒 + 已完成分片零重放、分片续跑谓词、停机停领（direct claim + 锁测 `/resume` 共享路径）、取消不可领（生产派发 / resume / 轮询三路径 + `started_at`/事件级断言）、**并发同项目 claim 至多一条租约**、**门拒绝后的恢复孤儿重派**、**failed 自动拒/人工放行**、项目级串行（含 claim→markRunning 窗口）、无长事务。
  - 运行方式：`server/platform$ ./mvnw -Dtest=AiJobSchedulerIntegrationTest test`（需 docker compose 的 mysql 服务在线；连接参数见 `src/test/resources/application-mysql-it.yml`，可用 `AI_SCHED_IT_DB_URL` 等环境变量覆盖）。
- **单元/切片测试**：`AiSchedulerPolicyTest`（上限 fail-closed）、`AiAnalysisSchedulerMetricsServiceTest`（聚合口径、项目过滤（含 recoveredRuns）、零分母 null）、`AiAnalysisWorkerTest`/`AiAnalysisRunObservabilityTest`（围栏路径回归，含既有场景加固）。
- **第三十轮复核（对象 `2743519`）**：判定 **FAIL**，确认 4 项发现——C1 取消可被生产派发 / `/resume` 复活、C2 契约运营机制（门/停机/状态）未接生产路径且存在领取窗口绕过、C3「全部写入被围栏」过度声明、C4 metrics 测试 FK 清理顺序（已先修于 `14e6012`，复核方复跑 582/0/0/5 确认）→ **修复于 `186dede`**（C1/C2/C5-C8）。
- **第三十轮补审（对象 `186dede`）**：判定 **FAIL（修复不完整）**——上轮 4 项缺陷复测均不可复现（对照 2743519 3/3 FAIL vs 186dede 3/3 PASS）、C5–C8 复核通过、全量 585/0/0/5 连跑一致；但对新实现自身发现：C1 并发同项目双发放（门与 CAS 非原子，21/21 复现，medium）、C2 claim→markRunRunning 窗口的取消被吞（medium）、C3 §3 收尾声明仍过度（coverage 在围栏提交前执行，medium）、C4 恢复重排无自动派发（medium-low）、C5 不存在 run 抛 500（low）、C6 锁测强度缺口（low）→ **修复于 `3ce7fd7`**：项目行锁短事务（原子门）+ 事务 READ COMMITTED（消除等锁方旧快照盲判）、markRunRunning 围栏+条件、coverage 移至提交后、deadline 分支租约早退、恢复闭环派发、缺失 run 结构化返回、三条锁测增强（含并发同项目至多一租约）。
- **第三十轮补审 2（对象 `3ce7fd7`）**：在**干净 worktree 全量**抓到真实干扰（Obs 1F+3E / Controller 9E / Quality 4E）——测试上下文共享内存库而各上下文恢复调度器各持时钟，某 tick 把进行中任务误判重排，`3ce7fd7` 的自动派发随即启动真实竞争者（测试内 worker 被围栏挡下 + 租约泄漏 + resetData 外键级联）⇒ **修复于 `d265866`**：调度器加 `app.ai.scheduler.recovery-enabled` 开关（测试/IT profile 关闭、生产默认开）+ `recoverExpiredRuns`/心跳空时钟防御；聚焦复跑 43/43、**干净树全量 587/0/0/5 BUILD SUCCESS** 复跑通过；本轮补审复验（30d）进行中。
- **第三十轮补审 3（独立复核，对象 `3ce7fd7`）**：判定 **FAIL（验收证据链不成立）**——30b 六项缺陷全部实证关闭（C1 并发 12 轮 0/12；并以其反射实验把隔离级别切回 REPEATABLE READ 复现 20/20 双发放，证明 READ COMMITTED 修复必要且正确；C2/C3/C4/C5/C6 逐条复测通过），但对象提交的门禁**非确定**（干净树 5 连跑 1 红，即上述调度器干扰，修复于 `d265866`）；另发现：判定级 1「租约过期接管 ⇒ 进行中分片可重跑一次」的 at-least-once 边界需契约明示（§4 已补）；判定级 2 恢复派发被项目门/停机拒绝后永久 stranded（本提交修复：恢复扫描第二相孤儿重派 + IT 用例）；C4-1 收尾窗口取消可被 `markRunSucceeded` 覆盖（本提交修复：状态条件）；C4-2 项目行锁等待异常抛 500（本提交修复：优雅拒绝）；C4-3 外部事务包裹时静默 join（本提交修复：`REQUIRES_NEW`）；§7 陈旧措辞（本提交修正）。
- **第三十轮补审 4（独立复核，对象 `b2ce8a6`+`d265866`）**：判定 **FAIL（判定级 2 修复不完整）**——C4-1/C4-2/C4-3 与 30c 基线（C1–C6）全部实证通过、IT 14/14、干净树全量 588/0/0/5 两跑逐字一致、runner 不变量均通过；但孤儿重派谓词要求「无任何租约行」，漏掉「残留过期租约」的常规崩溃态（30c E1 逐字场景仍永久 stranded）⇒ **修复（本提交）**：谓词放宽为「无新鲜租约（`lease_until >= now` 不存在）」+ IT 用例补「残留过期租约」变体；另记 low/informational 残留（C-2 池=1 外层占连时的 `CannotCreateTransactionException` 属不可达潜伏边界；C-3 拒绝原因仅日志可辨——已加 debug 日志；C-4 `markRunSucceeded` 在 queued 接管窗口可提交属良性；C-5 伪造 `lease_recovered` 事件可入派发清单但受 claim 守卫约束）。
- **第三十轮补审 5（独立复核，对象 `c9a6550`）**：判定 **PASS** ✓——30d C-1 逐字关闭（E1a：c2/c3/c4 false→true、queued→succeeded、CAS 接管 token 4→5、租约干净释放）；谓词边界攻击全部未击穿（30 例谓词/CAS 互补零失配、伪造新鲜租约/无事件新任务/已领取任务均不误入册、等值点两侧同向保守、10 轮并发接管单胜者）；IT 14/14、干净树全量 ×2 = 588/0/0/5 逐字一致且与 30d 归一化序列相同；30d 通过项与 runner 不变量无回归；契约 §4/§8 与实现一致。C 段：相邻残余 C-1（medium，预存）——**新 run 首派发被门静默拒绝后无重试**（无 `lease_recovered` 事件 ⇒ 第二相旧谓词永不匹配）⇒ **本提交补强**：第二相增加分支 (b)「无事件但创建后超过一个恢复周期仍未领取」；C-2 措辞边界（连接获取失败属不可达潜伏）与 C-3（门/停机拒绝日志）一并收敛。
- **第三十轮补审 6（独立复核，对象 `97946dd`）**：判定 **PASS**——(b) 分支逐字关闭（[false×6]→持续入册、无人工 /resume 自动闭环 succeeded、真实 30s 间隔版、阈值两侧等值保守、停机守卫压制）；C-3 六类拒绝 debug 行逐字可辨；IT 14/14、干净树 588/0/0/5、谓词边界 36 例零失配。C 段相邻残余 C-1（failed run 被残留重复派发任务自动重跑）⇒ **修复于 `a6e49e8`**：dispatcher 在飞去重（PENDING 先占位 + swap 守卫，同时关闭 30c 发现 6 的 remove-先于-put 窗口）、processRun 自动路径拒 failed（仅 /resume 重跑）、WARN 文案覆盖两类重派。
- **第三十轮补审 7（独立复核，对象 `a6e49e8`）**：判定 **PASS**——30f C-1 逐字关闭（第二次 processRun=REFUSED、run_failed 稳定 1、含模型调用的场景 gatewayCalls 不增）；去重机制攻击全过（16 线程入队恰一次、PENDING 窗口 cancel 语义、拒绝提交零残留、1500 轮瞬时完成零泄漏、gracefulShutdown 等待计入占位）；/resume 人工通道保留；IT 14/14、干净树 588/0/0/5。C 段残余（本提交收敛）：C-1 failed-guard 读后翻转窗口（claim 增加 `allowFailed`，默认自动语义在锁内复核拒绝 failed，/resume 显式放行——IT 用例 `failedRunIsRefusedForAutomaticClaimButAllowedForManualResume`）；C-2 cancel「已提交未启动」任务后注册表条目残留（cancel=true 时同步清理，避免后续派发被去重吞掉）；C-3 repair 记录对 null content 的 NPE 兜底（生产不可达）。
- **第三十轮补审 8（独立复核，对象 `7032239`）**：判定 **PASS**——30g C-1/C-2 逐字关闭（claim 锁内复核拦截 13 轮交错攻击：T-first/U-first/释放后立即发射/围栏型全拦、零执行零调用；cancel 未启动任务后条目移除、重派不再被吞、执行时被终态守卫拒绝）；allowFailed 三调用方语义全集核对通过；IT 15/15、干净树 601/0/0/5（+13 逐类归因至 T03 祖先，无未解释增量）。C 段残余（修复于 `c4f6838`）：C-1 实测 `FutureTask.cancel(true)` 对运行中任务同样可能返回 true（推翻「未启动⇔true」推论）⇒ 改 started 标记 + 身份校验；C-2 契约 §1 补 allowFailed 语义；C-3 理论窗口记录为已知残余。
- **第三十轮补审 9（独立复核，对象 `c4f6838`）**：判定 **PASS（附返工项）**——运行中 cancel 条目保留至 finally 身份清理（重放 30h 两窗口，invocations 2→1）、未启动 cancel 无回归、启动竞态 1240 轮 0 违例、IT 15/15、干净树 605/0/0/5。但指出本提交**新引入的 medium 回归**：派发线程被抢占 + 任务瞬时完成时，回填把已完成 future 放回注册表（幽灵条目 ⇒ 后续派发永久被吞；双树对照旧绿新红）⇒ **修复（本提交）**：占位改为每次派发唯一对象，回填与清理全部在锁内按身份判定（self 优先、未回填时比占位），完成先于回填不再回流、取消落在占位窗口不回填 cancelled future；未启动 cancel 同步清理 startedFlags（1500 轮泄漏修复）；契约 §8 §1 措辞按边界修正（「注册表反映在飞真相」标注瞬时窗口边界）。
- **待办**：本文档标记「验收通过」（30e/f/g/h 四轮 PASS + 30i PASS 附返工项，返工于本提交），据此放行 T06 迁移。

## 9. 与既有实现的关系

- 复用 T-R3 时代的 `ai_analysis_worker_leases`（V30）基础；**未新增迁移**（V38 仅为 Runtime attempt 增加 `duration_ms/ttfb_ms` 供 TTFB 指标）。
- 本文档的 claim/围栏语义即现有 Worker 实现语义的固化与补强；后续 T06 的统一 job/attempt/runtime 表必须**满足同一契约**（本测试将作为迁移回归门禁）。

> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# AI 测试平台改造计划 · 收口报告（终审）

> 范围：`docs/superpowers/plans/2026-09-16-ai-platform-completion-plan.md` 全部任务与验收矩阵（A01–A20）。
> 方法：每门以真实工具证据为准——独立对抗复核（T-R5 八轮）、真实浏览器全链路 E2E（真实 fixture 上游 + 真实登录 + 已部署镜像）、四门本地回归、旧镜像回滚演练。
> 台账：`2026-09-16-ai-platform-implementation-tasks.md`（121 项已勾选；未勾/部分项全部为外部依赖，见下）。

## 一、任务状态（全部 21 项 + 复核门）

| 任务 | 状态 | 关键证据 |
|---|---|---|
| T00 契约与基线 | ✅ | 契约测试与基线命令；CI 分层（纯确定性 / MySQL 集成 / Vue 浏览器） |
| T01 版本化配置注册表 | ✅ | Connection/Profile/Policy/RunSnapshot 追加式版本；resolver 溯源 `{value, source, sourceVersion}`；legacy 幂等导入；MySQL 核验 0 孤儿 |
| T02 流式 | ⏳ 留档（立场显式化） | 按主计划第 190 行：仅协议验证后启用。当前环境无 streaming 档案 ⇒ **两协议一律走非流式，平台不宣称 streaming supported**；立场由 `AiProtocolTransportTest.transportNeverRequestsStreamingForEitherProtocol` 固化（出站体不得携带 stream/stream_options）。消费/事件顺序/断流/取消与 usage 明细测试随首个 streaming 档案接入 |
| T03 统一调用契约 | ✅ | 四目标编译器；本地 schema 语义唯一来源；结构化输出与 repair 预算 |
| T04 能力探针 | ✅ | 结构化探针（非 1-token ping）；未验证档案 BLOCKED/UNKNOWN；A15 语义（保存不宣称可用） |
| T05 路由 | ✅ | 十步固定序列；目录不绑模型；缺项不默认 |
| T06 持久执行 | ✅ | CAS 领取 / 租约与围栏 / 两相恢复 / 零分母 null |
| T07 安全与秘密 | ✅ | 脱敏全域（日志/消息/快照）；注入不提升权限 |
| T08 证据与知识 | ✅ | manifest 完整；未解析来源标缺口；Figma RULE_EXTRACTED 与独立语义节点 |
| T09 MCP 工具 | ✅ | 真实出口 + nonce 反回显；readOnly 服务端裁决；幂等键；被 bug_analysis/log_triage 真实使用 |
| T10 Web 自动化产物 | ✅ | DSL 白名单 + ≥1 断言；revision 钉住；执行回写只来自 Runner 快照 |
| T11 事件与能力报告 | ✅ | eventSeq 续接；心跳≠token；409 冲突单列；前置探针登记；**版本化编辑器 + 浏览器全链路 E2E（含导出）** |
| T12 QA 助手 | ✅ | 七类入口/8 类型；输出校验全确定性（无固定分数）；人审恒 PENDING_HUMAN_CONFIRMATION；对话即用闭环（建议→预检→运行→评审→改稿→导出） |
| T13 预算与评测 | ✅ | reserve CAS；未知 usage 全额预留；指标全由执行事实计算 |
| T14 资源隔离 | ✅ | 不存在与无权同形 404（含 assetId 面）；逐节点 actor 复核；出站载荷脱敏 |
| T15 发布与回滚 | ✅（代码侧） | 镜像身份自证（gitSha/buildTime）；`.dockerignore` 显式规则；Flyway 只前进；发布开关在发请求前阻断（legacy + vertical slice） |
| T-R1–T-R4 | ✅ | 逐轮独立复核通过（见台账） |
| T-R5 对抗复核 | ✅ | **八轮独立复核；轮 8 PASS**（首次全量通过）；四项残余已全部关闭（④统一归一化 `78eeaa3`；①②③登记为设计性/提示级） |
| T-Review-5 质量评测 | ✅ | gold-set 8 类×10 例（负例只增不减）；指标确定性计算；V49 |
| T01-FE/T11-FE 收口 | ✅ | 三类列表输入全部来自真实目录（文档/最近产物/草稿点）；改稿编辑器；运行前预检 |

## 二、验收矩阵 A01–A20

全部 20 项均有对应任务的交付与验证记录（映射见台账对应行）。两点如实说明：

- **A01b（当前部署真实档案业务验收）**：业务验收已通过**本地 fixture 上游**完成全链路（生成→校验→人审→导出）；生产档案在上游凭据就绪前按 **BLOCKED/UNKNOWN** 呈现——即矩阵要求的"未验证档案"语义本身。
- **A20（刷新恢复/大文本）**：由 T11 事件续接（eventSeq）+ 前端单列冲突 + 中英双语测试覆盖；流式场景随 T02。

## 三、四门基线（终审实测）

| 门 | 结果 |
|---|---|
| 平台（含 MySQL 8.4 集成） | **945 / 0 / 0 / 0**（2026-10-13 五点复核轮 924 ⇒ 938 ⇒ 944；2026-10-14 取证网络策略双模式 +945：转发钉子 + runner 侧策略/夹具用例） |
| 前端 | **159 files / 800 tests** + `vue-tsc` 0 + build ✓（2026-10-14 取证网络策略双模式：策略默认值/切换 UI/载荷钉子 +2） |
| Web Runner | **267 / 267** |
| 文档引用/一致性 | 通过（本报告随台账提交） |

**备份/恢复演练（2026-10-13 复跑，`scripts/ai-migration-drill.sh`）**：备份 2,088,324 B → 独立库恢复 → 计数逐项一致（analysis_runs 10=10 / draft_test_points 96=96 / assistant_artifacts 6=6 / knowledge 0=0 / run_events 128=128 / run_policies 3=3）、外键抽查无孤儿；`credential_ref` 形态为别名引用 + `enc:v1` 密文（密文不落文档）；DRILL PASS，演练库已清理。

**回滚演练（2026-10-13 复跑，新增 `scripts/rollback-drill.sh`，可复跑）**：旧镜像 `540a740` 以运行容器的真实配置在现行库（V53）独立启动，`/api/system/health` 自证 `gitSha=540a74079408fdc1f56799cf360bf6e162a8c9c1`；演练前后计数 6/96/10/0 与 flyway 版本 53 零变化；演练容器已回收，现役服务（`f04013d`）全程未受影响。

**精确部署验收（2026-10-13）**：`docker build --build-arg GIT_SHA=$(git rev-parse HEAD)` → 镜像 `haitlify-api-platform:f04013d` 部署后 `/api/system/health` 回读 `gitSha=f04013dec960302219101ffca504b8ece3b3e282`（= 当轮 HEAD，非旧镜像 `540a740`）；Flyway V52/V53 在真实 MySQL 库落地；web 镜像未重建（最后 FE 提交 `44cfcf5` 早于其构建时间，本轮零前端变更）。

## 四、遗留清单（全部为外部依赖，含责任人）

| 编号 | 项 | 责任人 | 操作要点 |
|---|---|---|---|
| L1 | GitHub Actions 账单阻断（私有仓计费） | 用户（**自行处理**，2026-10-13 起助手不再跟进） | Billing & plans 与后续 rerun 由用户安排；失败运行清单备查：`<synthetic-hermes-cache>/scratch/e2e/failed-runs.txt`（26 个运行，均为 payments-failed 型秒失败；runner 已钉 `ubuntu-24.04`） |
| L2 | T15/M4 发布窗口项 | 用户 | 生产脱敏备份迁移 + 正式回滚演练 + 受控站点人工启动 Runner 回放 + 仅由测试创建的临时数据清理 |
| L3 | T02 流式 + usage 明细字段 | 随首个 streaming 档案 | 接口保留；协议验证后接入消费、事件顺序与断流测试 |
| L4 | 正式 smoke（真实上游凭据） | **已完成 2026-10-13** | `scripts/ai-runtime-smoke.mjs` **SMOKE PASS 8/8（exit 0）**：health+sha → login 200 → 建 run → PENDING → 事件流 → reject→REJECTED → 404 负例；run `assistant-3ee253a3…`、attempt `succeeded`、`provider_request_id=req-34b5f729…`、usage 3502/2177、模型 deepseek-v4-flash |
| L5 | T-R5 低危登记 ①②③ | 已闭合 | ①写入口目录白名单已实现（`67ae96c`，并集白名单 + canonical 落库；连带补齐控制器 400 手柄 `540a740`）；②历史行由读取端容错覆盖（不回写）；③NFKC 组合类按轮 8 决议属契约行为 |

## 五、2026-10-12 复核轮增量（按用户五项风险顺序）

> 本轮不信任既有 ✅ 声明，逐面从代码与真实执行复核；下列每项均有本轮新提交与有牙测试。

- **产物确认门禁（项 1）**：`f2813cc` 草稿测试点全契约——真实认证 actor、项目权限（无权与不存在同形 404）、`expectedRevision`（过期 409）、允许前置状态、CAS 写入 + 重复确认幂等 + 修改产生 `-rN` 新 revision 并保留 parent/双审计（复用 V26 已备 revision/parent_artifact_id 列）；`fa7c4c8` Figma element candidate 同契约（+V51：revision/reviewed_by/reviewed_at；列表补读闸；证据缺失不得确认）；Web plan 驳回改走验收状态机（`7f4b716`，PENDING/DEGRADED→BLOCKED；ACCEPTED 不可驳回/不可再改；重复确认/全量确认幂等）。
- **人工确认的全局漏洞（本轮实测发现）**：所有手写 actor 守卫只查「非空名字」，安全过滤器注入的 `anonymousUser` 匿名令牌可以照常确认/驳回/导出——已收敛到 `AiActorResolver.requireHumanActor()`（拒绝 null/未认证/匿名令牌/空名/字面 system），三处评审路径全部改用；无认证、匿名令牌、跨项目、过期 revision、非法翻转、并发单写者负例全部有测试。
- **QA 助手批准/导出复检（项 3）**：批准前对钉住 revision 重跑确定性校验，未解决违规 ⇒ `unresolved_violations` fail-closed 且违规记录只增不减；人工修订（change）同样校验并落库；导出对已接受产物复检（拦截旧规则下接受的带违规产物）。组合流复检还抓出过一处 task_type/载荷错配的夹具缺陷（有牙佐证）。
- **模型调用收敛（项 2）**：9 个消费者全部只经 `AiTaskExecutionFacade`（Runtime/预算/重试/attempt/取消唯一入口）；`AiModelCallConvergenceTest` 反射护栏固化——直连网关或私持执行器的类必须显式入白名单（含嵌套类归属），新增旁路即红。业务层其余并行均为作业级编排（dispatcher/心跳/恢复），非模型调用并行。
- **T02 流式（项 4）**：见上表——立场显式化并有测试固化；**未验证即不得标支持**。
- **知识经验面（Exit Gate 3 点名）**：`AgentMemoryService` 此前零守卫（确认/停用/删除/修改无 actor/项目/revision/状态机，审计写死 system、confirmed_by 写死 'user'）——本轮补齐同族契约（`14ddc29`，V52）：真实 actor、同形 404、expectedRevision 409、CAS 状态机（确认仅自 pending 且重复幂等不重复审计；停用仅自 active；删除幂等；修改拒已删除并 revision+1）、confirmed_by 与审计均为真实操作人。
- **纵向切片立场（项 1 覆盖面）**：`ai_runtime_vertical_artifacts` 无任何交付下游消费（仅自引用 + 单测试引用），产物永停 `PENDING_HUMAN_CONFIRMATION`、无接受路径 ⇒ 不构成可交付物，不存在「无人工确认的正式交付」面。

### 重点核验的证据分栏（真实 provider / fixture / 规则 / mock 不可互替）

| 核验点 | 证据类别 | 本轮结论 |
|---|---|---|
| 七类 QA 任务 | 规则（确定性校验器）+ mock（服务测试） | 七类规则齐；批准/导出复检新固化为**规则门禁**（非 mock 语义） |
| MCP 真实只读工具 | fixture（nonce 反回显）+ 规则（服务端 readOnly 裁决） | 沿用 T09/T-R5 轮 2 证据（`229a1c8`）；本轮未改该路径 |
| UI/API/export 同一 revision | mock（服务/仓库测试）+ **真实浏览器 E2E（新镜像）** | ✅ 2026-10-13：新镜像（health 自证 `f04013d`）上复跑登录态 UI/API/导出全链路——违规阻断复现、v2 改稿/批准、错配 revision 409、正确 revision 导出全字段（见 §六） |
| 真实 provider 调用 | **真实 provider（已实测）** | ✅ 2026-10-13：七类（8 个 taskType）各一次真实调用 + 正式 smoke 8/8；requestId/usage 见 §六 |
| Web Runner 人工启动 | **真实受控站点（已实测）** | ✅ 2026-10-13：用户人工启动首跑 + 修环境后复跑 **1/1 通过**（受控目标=平台登录页；step 日志与报告入库） |
| 生产发布门禁 | 生产环境 | **BLOCKED**——需发布窗口（L2）；L4（真实凭据 smoke）已于 2026-10-13 完成 |

**对收口声明的更正**：T12 的「✅」应理解为生成与人审链闭合；「批准/导出的确定性复检」「匿名调用拒绝」属本轮补齐（此前 approve 可在存在业务违规时通过）。T10/T11 的 revision 契约在 Web plan 面此前存在状态机旁路，本轮关闭。

### 三个 Exit Gate 的逐项结论（主计划 §4.8 对照）

| Gate | 逐项要点 | 本轮结论 |
|---|---|---|
| **MCP transport** | tools/list 读真实 fixture 定义；tools/call 每次变化远端结果（nonce）+ provider call id/参数 hash/摘要/evidenceId/project/actor/耗时/错误留档；参数回显实现必须失败；取消/超时/权限/重定向/写工具阻断负例；一个真实只读工具被 QA 任务实际使用 | **PASS**（证据类别：fixture + 规则；T09 + T-R5 轮 2 `229a1c8`，`AiAssistantMcpEvidenceTest` 4 例；真实外部 MCP server 的 live 证据不在本环境——与 T-R5 判定一致） |
| **统一 UI/API/export** | 同一 artifact 的 API 字段（artifactId/revision/origin/acceptanceState/fieldOrigins/evidenceIds/modelProfileVersion）一致；前端/导出一致只展示；确认/修改/驳回/导出/重试同一 revision；BLOCKED/DEGRADED 导出带警告；未 ACCEPTED 不入正式库；一条需求→测试点→确认→导出场景对照 | **PASS（代码/API/测试）**：revision 契约本轮强化（409/幂等/复检）；真实浏览器字段对照 E2E 待新镜像部署后复跑（`540a740` 为既往镜像，本轮提交未部署） |
| **全链路人工确认** | 六个面（需求测试点/接口功能用例/Web 计划/Web 编译候选/知识经验/报告上线结论）各有 WAITING_REVIEW；确认接口校验 actor/project/expectedRevision/evidence/前置状态；重复幂等、过期 409、拒绝阻断、修改新 revision 保留 parent；仅 ACCEPTED 入库/导出；仅用户启动 + Runner 证据才 EXECUTED | **代码侧 PASS（本轮补齐六个面的确认契约）**；`EXECUTED` 面 **BLOCKED**——需用户本人启动受控站点 Runner 回放（L2） |

## 六、2026-10-13 五点复核轮（用户指定项 1–5）与部署验收

> 基线：上轮 HEAD `fb73dbf`。本轮按用户指定五项逐一复现、修复、验证，每项独立提交。

- **项 1 · 知识经验来源与目标授权（`f7e7314`）**：`list/create` 增项目访问校验（同形 404）；`confirm/update` 在原有来源项目校验之外增**目标范围授权**——发布 GLOBAL 需管理员（非管理员显式 403 且零落库）、PROJECT_SET 逐目标项目校验（含无权项目即拒）、已全局经验的再编辑同样要求管理员；系统内部路径（promptContext/交付候选/使用记录）走显式命名的私有查询。`AgentMemoryAccessGateTest` 7 例：跨项目 list/匿名 list/越权 create/越权 confirm/成员发全局被拒/管理员放行/集合含无权项目被拒/update 双路径。
- **项 2 · report_draft 事实可追溯与全路径复检（`56a562d`）**：生成时把确定性事实（passRate/totalItems/sourceRunIds）随产物落库（V53）；批准、改稿、导出三处复检均携带存储事实，passRate 与执行记录不符即 `unresolved_violations` fail-closed；人工修订继承事实；导出载荷新增 `deterministicFacts`。测试含三路径篡改负例、修订继承与合法匹配正例。
- **项 3 · 模型调用收敛（`b563776`）**：审计确认真实 provider 调用仅四处：facade、路由执行器、结构修复、连接探针（后者为登记在册的预运行管理探针）。路由执行器与修复服务改为经 `AiTaskExecutionFacade.generateForRoute` 发起每次调用（台账时间上限/两阶段 attempt/token 预算 reserve-settle/失败分类与 `generate` 同权），两者移入 `AiRuntimeConsumerCoverageTest.MIGRATED`（源码扫描从此禁止其直连）；原 `AiModelCallConvergenceTest` 的反射护栏引用了**不存在的类名**（空判无效——用户判断成立），已重写为行为证明：真实 facade 装配下断言 T06 attempt 落库 + T13 三 scope reserve/settle + 预算不足零 provider 请求 fail-closed；修复服务测试新增 `ai_runtime_attempts` 落库断言；facade 时钟改注入应用 `Clock` bean（修正与台账时钟错位）。路由/重试/取消编排仍归执行器（未改语义，`AiExecutionControlTest`/`AiRouteExecutorTest`/`AiFailureRoutingTest` 全过）。
- **项 4 · assistant 评审写路径 CAS（`fddee70`）**：approve/reject/change 全部改为 `where acceptance_state='PENDING_HUMAN_CONFIRMATION' and revision=?` 的 CAS 写入；重复终态审批幂等且不重复发事件；竞态落败者按最新状态裁决（同终态幂等返回 / 否则 `review_state_conflict`）；改稿旧行 CAS 保证唯一新 revision 链。测试：并发 approve-vs-reject 恰一赢家、并发 change 恰一新 revision、重复请求事件恰一次、approve→reject 与 approve→change 翻转被拒。
- **项 5 · T02 流式立场显式化（`99f7b1f`）**：能力检查新增 `streaming` 探针行——未声明 ⇒ UNSUPPORTED、已声明 ⇒ UNKNOWN（"not verified"，绝不 pass，与 image/tool 同族语义）；既有出站体测试继续固化两协议不携带 stream 字段。**非流式探针通过不构成流式验收**，T02 消费/事件顺序/断流/取消与 usage 明细仍为未支持（随首个 streaming 档案接入）。

### 部署验收 + 新镜像登录态全链路（真实 fixture 上游，非旧镜像）

- **部署**：镜像 `f04013d` 部署后 health 回读 `gitSha=f04013dec960302219101ffca504b8ece3b3e282`=HEAD ✓；Flyway V52/V53 已应用于真实库 ✓。
- **UI 运行**（登录态、真实浏览器）：首页对话 → 关键词路由到 `bug_analysis` → 填必填输入运行 → 产物 `artifact-36508a3c-…` **v1** `PENDING_HUMAN_CONFIRMATION`，违规如实标注「必须给出至少一条假设（hypotheses）」；fixture 计数 `chat#1`（`chatcmpl-fixture-1`）；`ai_runtime_attempts` 对应行 `succeeded / usage 10+20`（与 fixture 返回值一致）。
- **批准=复检阻断（线上复现项 2/3 契约）**：UI 点击「通过」→ `400 unresolved_violations: 存在未解决的确定性违规，不得批准：bug_analysis 必须给出至少一条假设（hypotheses）`，状态保持待评审（fail-closed）✓。
- **改稿**：UI 改稿提交合规范本 → 新产物 `…-r2` **v2**（无违规），v1 `REJECTED`（旧版不可变）✓。
- **批准**：v2 → `ACCEPTED`，评审人 synthetic_user ✓。
- **导出**：以 v1 的 artifactId + revision 2 导出 → **409 stale_revision**（revision 钉住语义）；以 `…-r2` + revision 2 导出 → **200**：`artifactId=…-r2`、`revision=2`、`acceptanceState=ACCEPTED`、`reviewedBy=synthetic_user`、`projectId=1`、`runId=assistant-f0b890fe-…`、`taskType=bug_analysis`、`violations=[]`。导出 JSON 存证 `<synthetic-hermes-cache>/scratch/e2e/export-record-r2.json`；截图 `review-accepted-v2-live.png`（Playwright + 登录态 cookie；CDP 截图与系统截图在本机当时均不可用，取证方式如实记录）。
- **环境还原**：legacy 配置已按快照还原（api_key 保持上轮已知的本地 dummy 值）；fixture 进程回收；容器保持新镜像运行态。

**本地演练复跑（同日）**：备份/恢复演练与回滚演练双双 PASS（详见 §三；脚本 `scripts/ai-migration-drill.sh`、`scripts/rollback-drill.sh` 均可复跑）。

**用户本地验证发现的缺陷修复 · 2MB 需求文档上传（2026-10-13 傍晚）**：用户在需求解析中上传约 2MB 文档，先后命中两层缺陷，均已修复并有实测：
1. **静态代理 413（`c893ccc`）**：nginx 未显式设置 `client_max_body_size`（默认 1m），数 MB 文档在上游之前即被 413；平台侧 multipart 上限本为 25MB。已对齐 `25m`，并加部署资产锁测（覆盖两文件；注释掉/降值两种突变均实测咬合）。实测：2MB 经 nginx → 403 鉴权响应（不再 413）；30MB → 413（上限仍生效）。
2. **落库截断 500（`c84d1b0`，V54）**：`ai_source_documents.extracted_text` 与 `ai_source_chunks.content_text` 均为 TEXT（64KB），2MB 全文写库报 `Data too long` → 500。V54 两列扩为 LONGTEXT；新增真库集成测试 `AiSourceDocumentMysqlIntegrationTest`（1.2MB 文档写入并全量回读；**迁移前该测试精确复现用户所见的截断错误**，迁移后通过；类名对齐 surefire 模式，已入全量）。全量后端 **939/0/0/0**；真实库 V54 已应用、两列 longtext。端到端复验：登录态 2MB 上传 → **200**（`doc-08fcf28c…`，chunk 落库 2,162,687 字符）。用户原始文档可直接重试，无需压缩。

**界面修复 · Web 自动化工作台布局（`ac0a73c`）**：根网格误把会话工具条放在 fr 行、主区落进隐式 auto 行，导致「配置条—表格」之间出现约 357px 大面积留白、表格与侧栏被挤到页面底部。改为固定四行（任务头/运行准备/工具条/主区 fr），监控模式单子项整格占满；结构测试钉住两条规则（已验「有牙」）。已构建 web 镜像（`haitlify-api-web:ac0a73c`，容器镜像 ID 更新）并部署，实测：工具条行 367px→36px、主区 897px 贴底、空白探测为空、无重叠（截图 `web-automation-fixed.png`）。

**受控站点 Runner 人工启动（EXECUTED 腿，2026-10-13）**：用户在界面启动首跑 `b764e53d`（真实 Runner 执行；`page.goto … networkidle` 45s 超时失败——环境等待策略在产品默认值下不适用于轮询型 SPA）。经产品 UI 将环境等待策略改为 `domcontentloaded` 后复跑 `5e68b0b3`：**completed，1/1 通过**（goto 127ms + 用户名/密码/登录按钮三条可见断言全部 passed）；DB `ai_web_automation_run_reports` 两行存证。附注：匿名访问登录页会记录一条 `/api/auth/me 401` 控制台信息（不影响判定）；产品新环境默认 `waitUntil=networkidle` 建议后续评估。

**七类 QA 真实 provider 验收（2026-10-13 16:18–16:21）**：用户将平台配置切至真实通道（`openai-compatible / ai.example.invalid/v1 / deepseek-v4-flash / credential_configured=1`）后，八个 taskType（覆盖 §5.2 七类）各执行一次**真实模型调用**，全部 `waiting_review` + `PENDING_HUMAN_CONFIRMATION`：

| taskType | provider_request_id | in/out tokens | 时长 | 产物 violations |
|---|---|---|---|---|
| bug_analysis | req-2610c337… | 3516/3430 | 24s | 0 |
| testcase_generation | req-c442be8e… | 3442/9865 | 50s | 0 |
| requirement_analysis | req-772d3fee… | 3423/13956 | 71s | 0 |
| log_triage | req-62f8ea20… | 3573/3409 | 20s | 6（timeline 缺行级引用） |
| sql_analysis | req-108fc922… | 3437/2927 | 17s | 0 |
| regression_planning | req-a91659cc… | 3455/4726 | 26s | 9（条目缺真实资产 caseId；用例库为空） |
| report_draft | req-f2206a43… | 3423/5061 | 25s | 0；确定性事实 passRate=0.0/totalItems=34/sourceRunIds=[1,3] 与库内一致 |
| prompt_evaluation | req-0ddfda84… | 3460/2279 | 14s | 1（样例未标 realRequest=true） |

八份产物均带 source manifest、`reviewed_by` 为空（等待人工，未做终审）；attempt 全部 `succeeded`、usage 由真实响应回填（合计 in 27,729 / out 45,653 tokens）。证据文件：`<synthetic-hermes-cache>/scratch/e2e/seven-qa-evidence.md` + `seven-qa-runs.json`。**正式 smoke 补记（同日 16:32）**：`scripts/ai-runtime-smoke.mjs` **SMOKE PASS — 8/8 通过，exit 0**。口令经密码库注入登录页 → 剪贴板 → 临时文件 → 环境变量流转执行，全程未读出/未外显；跑完立即删除临时文件并清零剪贴板。注：此前两次剪贴板尝试被平台以 401 拒绝（平台侧 2 条「登录失败」记录），管理账号口令自 2026-08-24 未变更。此后 L4 关闭。

**用户实测缺陷修复 · 需求分析合并截断（2026-10-13 傍晚，`063bb36` + V55）**：用户复跑需求分析，任务在合并阶段整单失败——`insert into ai_artifact_gate_audits` 报 `Data too long for column 'artifact_id'`（18:05:42，run `run-bfc6795f-…`，18:04:21 创建、81s 后失败）。根因为**同一类缺陷的三层实例**：模型原文/异常原文直拼进定长列。
1. **gate/幂等键（`063bb36`）**：`AiAnalysisWorker` 的「evidenceHash」实为 `chunkId:expectedResult` 原文（非散列），拼成 `draft-point:<runPk>:<chunkId>:<模型长句>` 顶爆 `artifact_id varchar(128)`（同样影响草稿点 `idempotency_key`）；`AiFigmaAnalysisService` 的 `"figma:"+runPk+":"+节点名` 同构。两处改为 **SHA-256 指纹**（长度恒 64/79，确定性去重语义不变）。
2. **草稿点 `source_text`（V55）**：该列原 TEXT（64KB），合并时原样抄入整段 chunk 全文（上传上限 25MB 后内容型文档必然超限）→ 扩为 LONGTEXT。
3. **失败上报自身（`063bb36`）**：`ai_run_events.summary` 与 `ai_analysis_runs.error_summary` 均 varchar(1024)，超长 SQL 原文把事件写入也顶爆（这正是容器日志缺完整堆栈的原因）→ worker 侧诊断文本钳位 900 + 事件服务兜底钳位 1000，任何超长诊断文本不再让上报自爆。
**测试（三处均先于修复复现同形截断再验愈）**：`AiAnalysisWorkerTest` 长 expectedResult 用例（修复前 H2 同形截断+事件 summary 二次截断）；`AiFigmaAnalysisServiceTest` 长节点名用例（修复前报错与用户完全同形：`Value too long for column "artifact_id … (128)": "figma:1:<长名>" (164)`）；`AiDraftMergeMysqlIntegrationTest`（>64KB 真库写入+回读，修复前 `Data too long for column 'source_text'`）。另补失败上报钳位契约用例（超长异常文本仍落 failed 终态且摘要钳位 1024 内）。全量后端 **943/0/0/0**。
**部署与端到端复验**：镜像 `063bb36` 部署，health 回读 `gitSha=063bb364bfce6680d15888aa8306ae81d9255b2c`；真实库 V55 已应用（`source_text longtext`）。复跑①合成样本 `run-077b1046-…` **succeeded 2/2**；复跑②**用户原文档**（`doc-778222ca…`，10150 字符）`run-0f2ba16d-…` **succeeded 2/2**——gate 审计 **42 行 artifact_id 长度恒 79**（原报错位）、42 条草稿点全量落库（`source_text` 10150 字符完整）、全部 `PENDING_HUMAN_CONFIRMATION`。用户原失败 run 保留为历史（不重写）。

**用户实测缺陷修复 · 用例生成链路（2026-10-13 晚，`af1734a`+`4102f61`+`65dfc5a`）**：用户录屏反馈「测试解析有数据（10 条已确认），到用例生成页面没有任何数据」。逐层排查发现**同一条用户链路被三处缺陷卡死**（其中两处为 `b241e2d` 一次重构同时引入的回归）：
1. **首次生成按钮不可点击（`af1734a`）**：`NaturalLanguageCaseGenerationStage` 以 `:disabled="!cases.length"` 禁用生成按钮——「用例数为 0」恰是首次生成的前提条件，形成死锁，用户永远无法点动。修复为按「可生成的点数」判断（新增 `generatablePointCount`，App 传已确认∩勾选范围数量）；无确认点时禁用。测试新增两条（空态有确认点→可点击、无确认点→禁用），修复前首条失败（有牙）。
2. **计划产物被误判 BLOCKED（`4102f61`）**：真实模型调用已发生（attempt `succeeded`、`req-c8004429-…`），但 `AgentWebCaseGenerationFacade.withCompileContext` 重建批次时走了 2 参兼容构造器，把 `providerRequestId` 丢成空串 → `saveNaturalPlanArtifacts` 判 `runtimeEvidence=false` → 10/10 计划落 BLOCKED、无法进入人工评审。修复为透传 model evidence；测试断言「证据必须穿过 compile context 注入」（修复前 `expected "plan-provider-req-1" but was ""`）。回归自 `585174e`（该 2 参构造器引入时丢掉了 `b8a8085` 刚建立的证据链）。
3. **待确认计划不展示、「确认用例」不可达（`65dfc5a`）**：`b241e2d` 把 artifact 交付收紧为 ACCEPTED-only（旧过滤器为 `accepted || natural_web_case_plan`），同时删掉了「计划生成完成后先以待确认形态展示」的链路 → 计划不展示 → `generatedCaseCount=0` → 主按钮「确认用例」永久禁用 → confirmAll 永不触发 → 死锁；且 PLAN 的 blocked（等待人工确认）被界面当作失败态（挂错误 + 「重试生成」）。修复：PENDING 计划同样进入表格（正式用例仍严格 ACCEPTED-only）并补全 artifact 元数据；monitor 对 blocked-PLAN 改为「已生成 N 条待确认用例，点击「确认用例」完成确认」；确认即走既有 `confirmAllWebCasePlanArtifacts`（人工确认落库）。
**端到端复验（真实浏览器 + 真实模型 + 已部署新镜像；步骤 2→3→4 全链）**：以用户 run `run-e78a17be-…` 的 10 条已确认测试点恢复工作流 → 「生成自然语言用例」可点击（`disabled=false` 实测）→ 生成运行 `web-agent-c2cfee5c-…`：10/10 计划 `PENDING_HUMAN_CONFIRMATION` + `MODEL_GENERATED` + `providerRequestId=req-5ee0022c-…`（另一早前运行 `web-agent-3dec0bf4` 同证）→ 表格出现 **10 行待确认用例**（WEB-PLAN-0001…0010）、主按钮「确认用例」启用 → 点击 → DB：**10/10 `ACCEPTED`、10 条 `HUMAN_EDITED`**（人工确认经界面真实操作落库）→ 界面进入「4 质量评审」→ 评审运行 `multi-agent-run-7e14e4af-…` `merge_ready` + `PENDING_HUMAN_CONFIRMATION`，评审面板呈现整体质量 72%、26 项待确认（有真实评审内容）。截图证据：`webgen-pending-rows-live.png`、`webgen-review-done-live.png`。步骤 5/6（自动化编译→保存执行）留给用户按需继续（其依赖的编译链此前已单独验证）。

**用户实测缺陷修复 · 取证网络白名单与「精确放行」通道（2026-10-13 深夜，`8eb5176`）**：用户在 ICC 环境上配置了自动登录（验证可通过），但进入自动化编译的素材取证阶段仍报「验证码拦截 · 需人工接管」。排查 `details_json` 发现真因不是账号密码：取证阶段的网络策略 `route.abort` 拦掉了两个非白名单请求——ICC 登录接口 `POST https://api.example.invalid/v4/uc/n/auth/login`（登录永远完不成）与阿里云验证码脚本 `o.alicdn.com/…/AliyunCaptcha.js`；浏览器因此停在 ICC 登录页、captcha 元素被探测 → `captcha_required`。而「自动登录验证」走执行流程、不受该白名单约束，故两边行为不一致。
1. **「精确放行」通道在 UI 侧不可达（`8eb5176`，核心修复）**：平台侧本就支持把白名单里**带路径的完整 URL** 转为 exact allowedRequestUrls（任意方法，含 POST；`addExactRequestUrl` + runner `allowedRequestUrls`），但前端归一化把所有条目折叠为 Origin 并拒绝带路径者，导致该通道从界面根本到不了 runner——用户在取证里永远无法放行登录 POST。修复：FE 保留带路径条目（protocol+host+path，去查询/片段、拒账号信息）、校验同步放行；字段更名「认证与放行白名单」并写明 Origin（资源/GET）与带路径 URL（精确放行含 POST）的区别。
2. **验证会话持久化**：验证自动登录成功后的 sessionId 原先只在页面内存（刷新即丢）。现在写入环境配置（界面可见/可清除）、编译/生成优先复用（runner 内存会话约 10 分钟有效，过期自动回退到登录发现）。
3. **诊断信息点名真因**：取证失败信息与运行建议现在直接列出被网络白名单拦截的 origin/完整 URL，并给出「加入认证与放行白名单」的可执行建议（此前界面只显示「验证码拦截」，真因只藏在 details_json 里）。
**验证**：FE 归一化回归测试回退折叠行为即失败（有牙）；dispatcher/controller 各加断言；runner 容器内以真实浏览器复跑 ICC 夹具契约（`node --test icc-readonly-exploration.test.mjs`）：带路径 URL 进入 allowedRequestUrls 后 **跨域登录 POST 被精确放行（authHits=1）且未列入来源仍被拦（externalHits=0）**。全量后端 **944/0/0/0**、前端 **159 files / 798** + build ✓。镜像 `8eb5176` 部署（health 自证）。

**用户设计改进 · 取证网络策略双模式（黑名单默认 / 白名单可选，2026-10-14，`9f9e09d`）**：承接上一条——逐条收集白名单对真实站点不现实，用户提出「不如出一个默认黑名单，默认都不拦截，除非用户特殊填写；然后可以黑名单白名单切换」。
1. **runner 策略层**：`createPageNetworkPolicy` 支持 `networkPolicyMode`——`blocklist`（默认不拦截，仅拦截名单内条目：Origin 拦整站、带路径 URL 只拦该请求）与 `allowlist`（原严格语义：仅放行名单内 + 精确 URL，其余一律拦截）。**缺省仍是 `allowlist`**，既有调用方与全部既有测试行为不变；产品侧由前端显式发送默认 `blocklist`。
2. **全链透传**：平台 `BrowserDiscoveryRequest` → `BrowserDiscoveryOptions` → `WebLocatorDiscoveryContext` → `PageLocatorDiscoveryRequest` 四层记录逐级携带（缺省 null ⇒ runner 走原白名单行为，不改变旧客户端）。
3. **前端**：环境配置新增「网络放行策略」下拉（黑名单默认 / 白名单可选），名单字段标签、占位与说明随模式动态切换（黑名单=「拦截名单」、白名单=「放行名单」），编译载荷携带模式。
**验证**：runner 策略新增 3 例（黑名单只拦名单内条目且放行其余、空名单全放行、缺省仍为白名单严格态）；ICC 夹具新增「黑名单零配置」实证（不配置任何条目即应放行跨域登录 POST）；平台转发钉子在案（dispatcher 捕获 `options.networkPolicyMode()`）；phase3 证据按新鲜度门重录（实现哈希同步）；全量后端 **945/0/0/0**、前端 **159 files / 800** + build ✓、runner **271/271**。三镜像 `9f9e09d` 部署（health 自证）。

**用户核查 · 素材取证失败的资源回收缺口（2026-10-14，`6ea0a0b`）**：用户追问「素材取证失败时资源如何回收」。逐层取证（含容器内活体实验）确认设计上的三层回收（失败即时 `finally` 关闭 / 60s 巡检按 TTL 销毁 / SIGTERM 全清），但存在两处真实缺口：
1. **销毁不关进程**：取证与人工接管路径创建的会话不记 `browserInstance` → `destroySession` 只关 page/context，**Chromium 进程常驻**。实测：100 秒内 12 个会话被巡检销毁、浏览器实例数零减少（0 个进程退出）；容器内微探针直接对照（无句柄 `browserConnected=true` / 有句柄 `false`）。
2. **早失败留无主浏览器**：`reuseSession=true`（生产默认：前端显式 + 平台开关默认开启）且无现成会话时，会话创建之前的失败（目标被网络策略拦、登录失败、导航超时）因 `retainSession` 由「请求意图」而非「实际会话」初始化，`finally` 跳过关闭，且**无会话记录、巡检不可达**（复现：+1 实例跨过 60s 巡检窗口仍在；手动定点回收验证）。
**修复（三处）**：取证两处 `createSession` 补 `browserInstance`；`destroySession` 沿 `browserInstance → context.browser() → page.context().browser()` 兜底关闭（不重复关）；`retainSession` 改为「确有会话」语义（创建成功后置位）。两条新测试在父版本上均以预期断言为红（`pageClosed=0` / `closed.browser=0`），修复后全绿；phase3 证据按新鲜度门重录。runner 全量 **273/273**。镜像重新部署，部署后微探针对照复验（A 路径 `browserConnected=false`；真实失败复现不再新增残留）。

**用户界面指正 · Agent 工作区去冗余（2026-10-14，`53818ed`）**：用户截图红框两处要求去掉——① 工作区的两阶段说明栏；② 流水线中的「测试设计」卡片（该阶段由前置流程完成、本页只读取已评审计划）。实现：说明栏从其唯一使用处（WebCaseAgentWorkspace）移除、TwoPhaseIndicator 收敛为 chips 单形态；流水线去「测试设计」卡（阶段索引仍按完整顺序计算，其余卡片 done/active/blocked 不变；列数 6→5）。两条新锁（无 `.two-phase-indicator`、无「阶段 1/2」「测试设计」文案、流水线恰 5 项）在父版本以预期断言为红；前端全量 **799/799** + `vue-tsc`/build ✓；web 镜像 `53818ed` 部署（线上 bundle 已不含说明栏文案）。

**用户指正 · 重试编译清理旧阻塞展示 + 「目标页面尚未就绪」根因定位（2026-10-14，`9c9c8d3`）**：用户要求「点击重试编译时清理掉之前素材取证 · 已阻塞的内容」。实现：创建新任务前清空旧运行对象与旧时间线（工作区不再残留上一次的「素材取证 · 已阻塞」标题/阻塞统计/日志内容，随即进入「正在创建 Agent 任务」状态）；钉子（清理语句与事件清空同段）在父版本为红，前端全量 **159 files / 800** + build ✓，web 镜像 `9c9c8d3` 部署。
同轮排查校正了一个归因：**「重试编译总失败 · 目标页面尚未就绪」的根因不在平台旧状态**——① 数据：15:21–15:23 用户四次重试 = 四个独立新运行（DB 逐条在案），10/10 点均 `wait_ready` 就绪超时（每点 ~5s×2 轮）→ `page_not_ready`，**网络零拦截**；② 代码：失败不会把旧会话/旧结果回填复用（跨点会话缓存仅成功路径生效，dispatcher:207），旧运行 blocked 列表不传递给新运行；③ 复现：runner 容器内干净 chromium 访 `https://app.example.invalid/`（未登录）正常渲染登录页（排除站点整体/网络/容器问题），而**已登录状态下根路径 `/` 渲染为空壳**（标题在、正文 0 长度、`emptyShell=true`）——`/#/home`（概览）、`/#/console/account` 等具体路由渲染正常。结论：环境入口为根路径 `/`，ICC 该路径当前对已登录用户的落地渲染异常 → 就绪判定不可能通过 → 每次重试同样失败。行动项：ICC 侧修 `/` 落地（或环境入口改指具体路由，如 `/#/home`）。

**用户核查 · 本地网络默认开启 + 「素材库查询失败」级联（UnexpectedRollbackException）根因修复（2026-10-14，`e73ff31`）**：
1. **本地网络访问默认开启（用户要求「本地网络访问拦截需要默认开启」）**：Web runner 的 Chromium 本地网络兼容模式（`APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS`）与登录发现私网放行（`APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOW_PRIVATE_NETWORKS`）改为**默认开启**——新增 `runner-env.mjs` 集中解析（未设置/空串/其他取值均放行，仅显式 `"false"` 关闭），`server.mjs` 两处接入；两处 README 改写为「默认开启、可 `=false` 收紧」。新增 4 例默认值钉子（未设置→true / 显式 false→false / `true`、空串→true），回退旧语义即红。
2. **`UnexpectedRollbackException` 真实根因（用户 15:54 截图错误，经异常原文定位）**：给取证降级路径的 `catch(DataAccessException)` 补打完整异常后（此前只记类名），COMPILE 模式运行稳定复现 `Parameter index out of range (11 > number of parameters, which is 10)`——素材库检索 SQL 的 `ESCAPE` 子句在 Java 文本块写成 `'\\'`（编译后运行时单反斜杠），MySQL 驱动（connector-j 9.7）将其解析为「引号被转义」，连锁吞掉 5 个占位符（本地探针实测：8 占位漏成 5；生产 16 漏成 10），每次检索必抛 `TransientDataAccessResourceException`；失败经 `LocatorKnowledgeService` 类级 `@Transactional` 的内层代理标记 rollback-only → 外层 catch 降级继续 → 运行最终提交时抛 `UnexpectedRollbackException`。**该缺陷只在真实 MySQL 驱动上触发、H2 单元测试不敏感，已潜伏多轮**（每次 Windows 编译运行都降级但被浏览器兜底掩盖）。
   **修复**：`KNOWLEDGE_ROWS_SQL` 提为常量且 `ESCAPE` 改 `!`（反斜杠在 Java/MySQL/H2 三层转义中天然歧义：单反斜杠驱动计数错、双反斜杠 H2 拒绝——本地探针逐形态实证）；`escapeLike` 改为 `!`/`%`/`_` 以 `!` 前缀转义；`searchCandidates` 改 `@Transactional(REQUIRES_NEW, readOnly)`（只读检索失败不再污染调用方事务，结构性杜绝该级联）；降级日志保留完整异常。
   **验证**：MySQL 探针（真实 connector-j 9.7）`escape '!'` 形态占位符 16/16 + 真实执行命中 50 行；契约钉子 3 例（坏形态回退已实测为红，修复后绿）+ 全量后端 **948/0/0/0**（上轮 945 + 新钉子 3）；部署后同形 COMPILE 验收：素材库失败 **0 次**（修复前每次运行 10+ 次）、10 点全程走完证据流程、运行干净到达 DELIVERY（修复前在此处抛回滚异常）。
3. **ICC 自动化环境双端对照（用户要求「驱动浏览器用我的登录信息看差异」）**：同一 Cookies 下，真实浏览器与 runner 容器 Chromium（带 LNA 兼容标志）**均可完整渲染** `#/console/account`、`#/home`（`user/detail`、`tenant/detail`、`me/permissions` 等接口全 200）；**根路径 `/` 的全新启动在任何客户端（含健康登录态的真实浏览器）60 秒内都不重定向且空白**（应用仅在其内登录流程中跳 `#/home`）——「重试编译 · 目标页面尚未就绪」由此而来，行动项：**环境访问入口改用具体路由（如 `/#/home`），或 ICC 侧修 `/` 落地**。另实测低权限测试账号（`user_84792`）在 ICC 侧数据受限（`cloud3-dev/api/v3/user` 返回 4003、页面多处「禁止访问」），自动登录建议使用数据完整的账号（对照账号 `user_414870` 全量加载正常）。

**用户实测缺陷 · 逐点编译被整批计划校验拦截（2026-10-14，`86520b1`）**：用户按建议把入口改到 `/#/home` 后实测——**素材取证全通**（自动登录 ICC、`已登录目标页面采集 44 个元素并写入素材库`、素材命中 10），但「自动化编译」必现 `IllegalArgumentException: AI Web 自然语言计划不完整：自然语言计划引用了当前批次之外的 sourcePointId`。根因：编译按测试点逐条执行（dispatcher 每点一次 `compile(List.of(pointId))`），而 `buildNaturalPlanBatch` 却用单点去核对已确认的**整批计划（10 条）**——其余 9 条被判「批次之外」直接抛错；且即便放过，下游 `compileEvidenceBoundCase` 会因其它点缺少单点证据报 `step_evidence_missing`，逐点编译永远无法完成。修复：编译调用允许「批次大于本次请求」（其它点的计划在校验中跳过、各自在自己的编译调用中处理），`buildNaturalPlanBatch` 校验通过后把批次**收窄到本次编译的点**再交给编译服务（覆盖/字段/重复校验语义不变）。两条新钉子（单点+双计划批次：不抛错且编译服务收到的批次恰含 1 条计划）在父版本上复现生产同款报错（红），修复后全绿；后端全量 **950/0/0/0**（948+2）。该缺陷此前一直被更早阶段的失败掩盖（取证/检索不通过时走不到编译阶段），本轮是全链路首次行进到编译阶段后暴露。

**BLOCKED（未伪造，剩余）**：生产脱敏备份迁移与正式回滚（L2）、CI 账单（L1）。~~真实 provider 七类验收~~、~~受控站点 Runner 人工启动~~、~~真实凭据 smoke~~ 均已于 2026-10-13 完成（见上）。

## 七、结论

**计划在本地可验证边界内全部完成**：21 项任务全部落地并经独立复核；验收矩阵全部覆盖；四门回归全绿（平台 **943**/前端 791/Runner 267），并已在新镜像（health 自证 `063bb36`）上复跑登录态 UI/API/导出全链路；同日晚间按用户实测缺陷完成上传/落库两层扩列（V54/V55）与 gate 键散列化修复，并以用户原文档复跑分析（42 条草稿点 + 42 条 gate 审计全量落库）；随后按用户实测继续修复用例生成链路三处缺陷，并修复 ICC 取证网络白名单的「精确放行」通道不可达与验证会话不持久化问题（`8eb5176`）（首次生成按钮死锁 / 计划证据丢失致 BLOCKED / 待确认计划不可见与确认入口不可达），并在真实浏览器上完成 测试点→生成→确认（10/10 ACCEPTED）→评审 全链复验（含违规阻断复现与 revision 钉住 409）；全链路浏览器 E2E（配置→预检→生成→校验失败→修复/失败→人审→改稿→导出）在真实登录 + 已部署镜像 + 真实 fixture 上游上通过；2026-10-12 复核轮按五项风险顺序补齐了产物确认门禁（draft/figma/assistant/web plan）、批准/导出确定性复检、模型调用收敛护栏与 streaming 立场显式化（见第五节）。遗留项均为**外部依赖**且归用户侧：CI 账单（L1，用户自行处理，助手不再跟进）、发布窗口（L2）、未来 streaming 档案（L3）；L4 已于 2026-10-13 完成。生产签收待用户按其安排处理 L1/L2 后进行。

> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# 阶段 7：产品化能力补齐 验收记录（增量 1）

> 验收基线：`docs/reviews/2026-09-09-ai-assisted-web-automation-stabilization-plan.md` 阶段 7（771–845 行）：7.1 测试资产模型、7.2 执行报告、7.3 稳定性指标、7.4 UI 能力 + 4 条验收标准。
> 说明：阶段 7 是大件产品化，按增量推进。**增量 1 = 7.3 稳定性指标（后端聚合端点）**；其余能力的现状盘点与后续增量清单见「一/五」。

## Status

增量 1（7.3 稳定性指标）与**增量 2（执行结果平台侧持久化，V37）**均已实现并通过全量门禁：
- 增量 1：`StabilityMetricsService` + `StabilityMetricsController`（`GET /api/ai/web-case-stability/metrics?projectId=`）——已通过第二十一轮独立复核（PASS）。
- 增量 2：`WebAutomationRunService` 快照持久化（V37 `ai_web_automation_run_reports`，run_id 幂等）+ 前端创建运行时携带项目 id + 指标 `execution.*` 两项转正——已通过第二十二轮独立复核（PASS，见「八」）。
- **增量 3（7.4-4 Session 状态面板 + 7.4-5 Locator 修复审核页）**：runner `GET /sessions`（脱敏元数据）→ 平台代理 `GET /api/web-automation/sessions` → 前端 `WebSessionPanel`；`LocatorRepairReviewDialog`（列出/接受/拒绝待审提案）——已通过第二十三轮独立复核（PASS，见「十」）。
- **增量 4（7.4-1~3/7.4-7：Action IR 查看、步骤证据 + locator 证据详情、截图↔DOM 对照）**：`ActionIrViewerDialog`、`WebStepEvidenceCard`、`ScreenshotDomCompareDialog` 三个查看组件 + 运行日志/执行报告装配——已通过第二十四轮独立复核（PASS，见「十二」），并按该轮 C-①~③ 修正了证据字段读取路径与夹具形状。
- **增量 5（Sprint 6-6：批量执行、重试、回归套件管理）**：批量=既有选择范围执行（已证实）；新增「重跑失败用例」与回归套件（保存/应用/删除）——待独立复核。

## 一、能力盘点（7.1/7.2/7.4 现状）

**7.1 测试资产模型** — 已有：运行/事件/产物三表（V10 起）承载业务信息、产物 revision/幂等键/父产物（V26）、来源与人工接受状态（V24/V32–V34）、步骤 trace 与证据 id（`stepTraces`/`evidenceIds`/`expectedResultEvidenceIds`）、环境引用（`environmentId/baseUrl/authenticationMode`）、质量评分与回归对比（V15/V16）。缺口（后续增量）：测试数据引用的独立字段；用例到 locator revision 的直接引用（当前经知识库间接关联）；失败诊断历史的无用例级聚合视图（事件表已存全量）。

**7.2 执行报告** — 已有：`WebAutomationRunReport` 含 summary 通过率、逐用例状态、逐步骤耗时与 URL、`screenshotPath`、`consoleErrors`/`pageErrors`、`failedRequests`、`stepEvidence`（含 pageFingerprint 与 locator 绑定证据）。缺口（后续增量）：repair 建议在运行报告中的直出（当前 `repairTrail` 在取证侧）；执行结果的**平台侧持久化**（目前报告只在前端/运行器侧——这也是 7.3 三项指标的前置依赖）。

**7.4 UI 能力** — 已有：用例表/编辑、IR 导入与编辑、质量洞察、失败分类标签、Session 状态面板（增量 3）、Locator 修复审核页（增量 3）、**Action IR 查看（增量 4，弹窗形态）**、**步骤证据 + locator 证据详情（增量 4，运行日志内嵌）**、**截图↔DOM 对照（增量 4）**。缺口：以上查看器均为弹窗/内嵌形态（非独立路由页）；`reportStepEvidence` 已由增量 4 接线消费（第二十一轮 C-1 修正落地）。

## 二、增量 1 实现（7.3 稳定性指标）

- 位置：`ai/stability/StabilityMetricsService.java`（全部指标从现有表派生，零推测）、`ai/stability/StabilityMetricsController.java`（`@Profile("!jira-readonly")`，项目级路径，避免与 `/web-case-agent-runs/{runId}` 冲突）。
- 指标口径：
  - `runs`：按状态分组计数 + `failureCount`（状态名含 fail/error）；
  - `compile`：产物总数 / 无校验错误数 / 正式交付接受数（**accepted=true 且 acceptance_state='ACCEPTED'**，与正式交付查询同口径）+ 两个比率；
  - `events`：事件总数 / error 级计数 + 比率 + **失败原因分布**（error_code Top-10）；
  - `locator`：条目按状态分组 + 执行成功/失败计数合计 + `locatorFailureRate`（V11 计数）；
  - `repair`：提案按状态分组 + `repairSuccessRate = accepted/(accepted+rejected)`。
- 诚实约束：**零分母返回 `null` 而不是 0%**（不把「没有数据」伪装成「零失败」）；`deferred` 列表显式列出尚未埋点的 3 项指标与原因（执行成功率 / 平均执行耗时 / session 复用失败率——共同前置依赖=执行结果持久化），不伪造数值。

## 三、验收标准映射（阶段 7 的 4 条）

| # | 标准 | 现有支撑 | 状态 |
| --- | --- | --- | --- |
| 1 | 每一步为什么这样点 | 产物步骤 trace（naturalStep/intent/dsl/evidenceIds/pageFingerprint）+ 结构化 locator + 证据派生 origin；独立查看页属 UI 后续增量 | ✅ 数据齐备（查看页待做） |
| 2 | 失败可区分用例/环境/登录/locator/业务缺陷 | 稳定失败分类（`invalid_action_ir`/`action_evidence_mismatch`/`ambiguous_locator`/`login_failed`/`pagination_not_changed`/`unsafe_action` 等）+ 前端分类标签 + 本增量的失败原因分布 | ✅ |
| 3 | AI 参与的每个结论都有证据链 | 证据派生 origin（MODEL_GENERATED/TOOL_OBSERVED/RULE_*/HUMAN_EDITED）+ BLOCKED 兜底 + 步骤级 evidence + 交付双重门（accepted∧ACCEPTED） | ✅ |
| 4 | 持续积累 locator 与页面结构经验 | locator 知识库（命中/成功/失败计数 + 置信度 + 指纹复核 + 退役）+ repair 版本化（proposal/revision/audit + 一次性服务端复核）+ 本增量的失效率/成功率指标 | ✅ |

## 四、验证方式与实测结果

- 聚焦：`StabilityMetricsServiceTest` **2/2**（含零分母 null 与时序无关的固定夹具断言）。
- platform 全量：**Tests run: 560, Failures: 0, Errors: 0, Skipped: 5**，BUILD SUCCESS，**103 份** surefire 报告（运行前清理历史报告与临时探针类）。
- runner / 前端：本增量未改动，沿用当日门禁（264/264、139 files/694）。

## 五、边界与后续增量（如实列出）

- 指标端点**无 UI**（API 先行）；`deferred` 三项在前置依赖（执行结果持久化）落地前保持显式缺失，不用 0 冒充。
- 后续增量建议顺序：① ~~执行结果平台侧持久化~~（**已完成，见「七」**）→ ② Repair 审核页 + Session 面板（7.4）→ ③ Action IR / Step Evidence / locator 证据独立查看页与截图-DOM 对照（7.4）。
- `runs.failureCount` 用状态名字符串包含 fail/error 判定——对不同状态词表稳健，但若未来出现非英文状态名需同步调整（低）。
- deferred 文案可再精确（复核方第二十一轮 C-2）：**增量 2 已处理**——原先措辞过宽的两项（执行成功率/平均耗时）已从 deferred 列表中移除并真实转正；剩余 `sessionReuseFailureRate` 一项维持显式缺失（原因：会话复用失败只出现在单次运行诊断里，未按项目落库）。
- 既有、非本增量引入（复核方第二十一轮 C-3/C-4，信息披露）：`artifactsAccepted` 与正式导出接口相差 artifact_type 过滤（当前数据下不可观测）；未知 runId 返回 500、以及某处 jira-readonly 排除疑似未生效——留待后续轮次单独处理。

## 六、独立复核（第二十一轮对抗性复核，判定 PASS）

- 复核对象：`958dce2b2a326f531a123b3d6f849594dda31f2a`；复核方 `deleg_61a29795`（953s，api_calls=130），自建 H2 夹具（102 checks）+ 真实 MySQL 探针双算，未引用文档或对方说法；报告在 `<synthetic-hermes-cache>/scratch/adv21/verifier/`。
- A1 指标正确性：accepted∧ACCEPTED 双向边界（计入/不计入）、项目隔离零污染、零分母 5 比率全 null、Top-10 恰 10 条降序且 ''/NULL 排除、`countBy` 边界（纯数字状态/长名/大小写/空串/中文）两种 H2 标签模式一致 + 真实 MySQL 双向取值一致。
- A2 deferred 诚实性：执行成功率**不可干净推导**（webautomation 包 0 处 JDBC；唯一信号 `ai_agent_asset_usage.outcome` 有偏）；平均耗时/session 复用失败率全库无来源列——三项 deferred 成立（仅 C-2 措辞问题）。
- A3 端点接线：401/403/400/405/404 各错误路径行为正确；成员注入 200 数字正确；jira-readonly 下 404（profile 门控生效）；无路由冲突。
- B 段逐字：platform `560/0/0/5` + BUILD SUCCESS + **103 报告**（与声明一致）；runner `264/264`；前端 `139 files / 694`（Node 26 无异常）；`git status` 无新增。
- C 段均低危：① 7.4 `reportStepEvidence` 无调用方（本文档已修正）；② deferred 措辞过宽（本文档已记录修订计划）；③④ 既有信息项（见「五」）。

## 七、增量 2：执行结果平台侧持久化（V37）

- 迁移：`V37__web_automation_run_reports.sql` 新建 `ai_web_automation_run_reports`（run_id 唯一键；project_id 可空；状态/计数/`total_case_duration_ms`/session_id）。
- 写入侧：`WebAutomationRunService` 在 `createRun`/`getRun`/`controlRun` 三个快照经过点调用 `persistSnapshot`——创建时捕获 `projectId`（请求可选携带），后续快照只更新状态/计数/耗时/session（**不覆盖 project_id**）；重复轮询走 `DuplicateKeyException` 分支，run_id 幂等；空 runId/空状态直接忽略，不写垃圾行。
- 前端：`WebAutomationRunOptions.projectId?`（可选）随创建请求发送；App.vue 运行启动时解析——用例项目名**去空后恰一个**才 `ensureBackendProject` 取其 id（全为空或多名则不带；解析失败也不带，运行不受影响，只是不计入项目指标）。runner 读取 runtime/cases/loopCount/mode/browserOverride 等既有字段，对 `projectId` 等未识别字段无害（复核方实测：带额外字段 202 且 payload 保留）。
- 指标转正（7.3）：`execution` 组 = `terminalRuns`（completed/failed/stopped）/`completedRuns`/`executionSuccessRate`/`casesTotal`/`totalCaseDurationMs`/`averageCaseDurationMs`（**只摊终态行的用例**）；deferred 3 项 → 1 项。
- 语义说明（复核方第二十二轮 C-①/②）：`executionSuccessRate` 是**运行级完成率**（终态里 completed 的占比），**不是用例通过率**——用例级通过率看运行报告 summary；未知状态既不进任何聚合也无告警，未来终态词表扩展时需同步指标侧判定（低）。
- 实测：聚焦 `StabilityMetricsServiceTest` 2/2、`WebAutomationRunServiceTest` 5/5、`PlaywrightRunClientTest` 15/15；platform 全量 **565/0/0/5 + BUILD SUCCESS + 104 报告**；前端 `vue-tsc` 0 + **139 files / 695 tests**（+1 新锁测：projectId 序列化/省略）；runner 未改动。
- 边界：V37 只存聚合（状态/计数/耗时/session），每个步骤的明细报告仍在运行器/前端侧——如需平台侧步骤级报告，按需扩展 V38+；缺 projectId 的历史调用路径仍持久化但不出现在项目指标里（与端点口径一致）。
- **线上部署验证（2026-10-08）**：重建 platform 容器 → Flyway 实际应用 V37（`success=1`，表结构逐列核对）；未认证 `GET /metrics` → **401 fail-closed**；已登录 → 200 且 `execution` 组在位。随后从真实页面发起一条真实运行（公开页面、skip 登录、`projectId=1`；202 → 轮询至 `completed`）→ 平台侧实际落行：run `92a1bda8…`（project_id=1 / status=completed / 1 用例 / 12276ms）→ `execution` 组即时转正（terminalRuns=1、completedRuns=1、successRate=1.0、averageCaseDurationMs=12276.0），deferred 残留 1 项。浏览器会话用后已回收（端口 9333 释放）。

## 八、终审：第二十二轮独立复核判定 PASS（2026-10-08）

- 复核对象：`7655d9a13f94e3e6595be6cd5d05918f1b237515`（实现 `86009df` + 本文档提交）；复核方 `deleg_a96abf9b`（1042s，api_calls=60），回执与全部原始日志在 `<synthetic-hermes-cache>/scratch/adv22/verifier/`。
- A1 持久化 25/25：带/不带 projectId、**project_id 永不被覆盖**（含二次 create 换 id 场景）、6 次轮询恰 1 行、并发 8 线程 1 行 0 异常、空值守卫、V37 列集逐字 + UNIQUE 生效、未知状态原样落库（与「终态由指标侧判定」口径一致）、『Completed』小写归一。
- A2 指标 18/18：终态边界、running/paused/未知/跨项目/NULL 全排除、零分母 null、与既有组不串、deferred 恰 1 项。
- A3 deferred 诚实性：`sessionReuseFailureRate` **确认不可推导**（迁移仅 V37 有 session_id 列；复用失败只存在于 runner 内存与单次运行诊断）。
- A4 链路：出站体含 projectId（null 时经 non_null mapper 省略）；runner 未知字段无害（源码行号 + 真实 runner 探针 202 且 payload 保留）；前端 8/8 独立复跑；App.vue 解析逻辑与文档一致。
- A5 线上只读交叉核对：flyway V37 success=1；run `92a1bda8…` 行与文档一致（created≠updated 证明确实走过更新路径）；按线上数据实算 execution 组与文档一致；未认证 401 实测；已认证 200 标注「不可复现，仅文档声明」（无登录态，如实披露）。
- B 段逐字：platform `565/0/0/5` + BUILD SUCCESS + 104 报告（跑两遍一致）；runner `264/264`；前端 `139 files / 695`（Node 26 flag 对照记录）；`git status` 无新增。
- C 段（低/信息，已处理）：① 完成率语义已在「七」补注；② 未知状态词表扩展风险已记；③ 前端去空行为与文案已对齐；④ runner 读取字段清单已在「七」修正为不穷尽表述；⑤⑥ INFO 记录在案（session_id 更新不可回空、中间快照瞬态覆盖均为设计取舍）。

## 九、增量 3：Session 状态面板 + Locator 修复审核页

- Runner：`session-manager.listSessions(now)`（只列未过期；字段白名单 `sessionId/projectId/environmentId/browser/state/leased/pageKey/createdAt/expiresAt/lastUsedAt`，**page/context/browserInstance 绝不出列表**）；`GET /sessions` 路由（`sessionsProvider` 注入，缺省空列表）；`server.mjs` 用真实 `listSessions` 接线。
- Platform：`PlaywrightRunClient.listSessions()` → `/sessions`；records `WebAutomationSessionView`/`WebAutomationSessionListResponse`；`GET /api/web-automation/sessions`（与 `/runs/**` 同控制器、无路由冲突；受既有认证/角色规则保护）。
- Frontend：`listWebAutomationSessions()`（经 `/api/web-automation/sessions`）；`WebSessionPanel.vue`（活跃数、刷新、占用/空闲、剩余有效期、空态）；`domain/locatorKnowledgeRepository.ts`（list `?projectId&status` / accept / reject，POST 走 `authedFetch` 带 CSRF）；`LocatorRepairReviewDialog.vue`（待审列表、**确认弹窗后才发 POST**、操作后刷新）；入口：Web 自动化工作台「Session 状态」按钮 + Agent 工作区「locator 修复审核」按钮（`v-if="run?.projectId"`）。
- 审核 UI **只是触发器**：服务端的一次性 verification（含 `matched_element_count==1` 复检）仍是唯一放行路径（阶段 6 已锁）。
- 实测：runner 全量 **266/266 / 0 fail**（含 2 条新锁测：字段白名单 `Object.keys` 断言 + 路由 provider/缺省空）；platform 全量 **566/0/0/5 + BUILD SUCCESS + 104 报告**（RunServiceTest 6/6、PlaywrightRunClientTest 15/15、StabilityMetricsServiceTest 2/2）；前端 `vue-tsc` 0 + **142 files / 704 tests**（+3 文件/+9 用例）。
- 边界：Session 面板只显示 runner **内存中**的活跃会话（runner 重启即空，属运行器会话模型本身的取舍）；审核页只读取 `pending_review` 状态（其余状态不出现，符合审核入口语义）。

## 十、终审：第二十三轮独立复核判定 PASS（2026-10-08）

- 复核对象：`6a902de`；复核方 `deleg_01904768`（1336s，api_calls=98），回执与自建 harness/日志在 `<synthetic-hermes-cache>/scratch/adv23/verifier/`。
- A1 runner /sessions（11/11）：过期未清理会话不出现且边界 `expiresAt==now` 严格排除；leased 与真实租约一致；字段白名单恰 10 键、7 类哨兵（page/context/browserInstance/sessionKey/fingerprint/origins/ownerId）序列化后 0 泄漏；路由注入/缺省/非 GET 行为如实记录。
- A2 平台代理：线上 38081 未认证 401；本 commit 新实例 18099 上登录后 200 `{"sessions":[]}`（证明该 200 出自新代码路径）；Java harness 18/18（仅 Accept 头、全字段反序列化、leased null/false 建模、未知键丢弃、500/404/503 映射）。
- A3 前端（19/19）：repository 编码与错误路径、items 缺省；面板 7 列/门控/空态三变体/错误态；**取消确认 ⇒ 零 POST**；in-flight 单发；服务端 409 不刷新；**UI 不可绕过服务端**（pending_review 要求 + 存储行 verification 复检 + 条目匹配 + 原子 one-shot 守卫，行号已核）。
- A4 无过度宣称；B 段逐字：platform `566/0/0/5` + BUILD SUCCESS + **104 报告**（逐份求和一致）；runner `266/266, 0 fail`；前端 `142 files / 704 tests` + `vue-tsc` 0（flag 对照复现 Node 26 现象）；`git status` 无新增。
- C 段（低/信息，处理记录）：① `/sessions` 仅要求认证且无项目隔离（VIEWER 可见所有项目会话元数据）——与既有 web-automation 面口径一致，**如实记录为已知取舍**，如需按项目收紧留待后续；② runner 非 GET `/sessions` 为 404（非 405），如实记录；③ provider 异常消息 503 回显（生产接线不可触发）；④ 文档括注修正：`v-if="run?.projectId"` 仅适用 Agent 工作区按钮（Web 工作台按钮为常显入口）；⑤ accept「复检」指**存储行复检**（非重新拉起浏览器），与阶段 6 设计一致；⑥ runner login-flow 浏览器用例偶发 30s 超时 1 次（干净重跑未复现）；⑦ 单发保护靠 disabled 属性（复核方自建 harness 补测取消路径通过）；⑧ 线上 docker runner（09-24 镜像）尚无 `/sessions`——**已随本终审重建 runner 容器**并将线上冒烟补记。

## 十一、增量 4：Action IR 查看 / 步骤证据与 locator 详情 / 截图↔DOM 对照

- `components/automation/WebStepEvidenceCard.vue`：**步骤证据**卡（运行日志弹窗内）——每条证据列出 actionId、actionType、evidenceId、执行状态/耗时、failureType/观察态差异，以及 before→after pageKey；展开显示 **locator / resolvedTarget JSON、visible·enabled·receivesEvents·unique 状态位、前后 pageFingerprint**（即 7.4-3 locator 证据详情）。数据来源 `reportStepEvidence()`——该函数此前全仓 0 调用方（第二十一轮 C-1），本增量接线。
- `components/views/ScreenshotDomCompareDialog.vue`：**截图↔DOM 对照**（执行报告行内「截图/DOM 对照」按钮，`ReportsView.vue`）——左右两栏（失败截图 + 页面 DOM 片段）+「打开原图」，单侧缺数据显式占位。
- `components/automation/ActionIrViewerDialog.vue`：**Action IR 查看**（运行日志弹窗内「查看 Action IR / 用例步骤」）——左栏 Action IR JSON（动作数），右栏用例步骤源（action/targetType/target + 名称）；**没有 Action IR 的用例显式说明「只保存步骤源」，不伪造**。
- 装配：App.vue 运行日志弹窗（步骤证据卡 + IR 卡，IR 数据经 `selectedActionIrCase` 按 caseId/sourceCaseId 从已保存用例解析）；ReportsView 行按钮 + 对照弹窗。
- 实测：前端 `vue-tsc` 0 + **145 files / 710 tests**（+3 文件/+6 用例）；**platform 与 runner 本轮零改动**（本轮 diff 仅 `apps/web`）。
- 边界：三个查看器均为弹窗/内嵌形态（不做独立路由页，避免路由与导航改造成本）；步骤证据对**旧运行记录**（runner 未带该字段）不显示（诚实降级，不补造数据）。

## 十二、终审：第二十四轮独立复核判定 PASS（2026-10-08）+ C-①~③ 修正

- 复核对象：`457cb28`；复核方 `deleg_5d5a0605`（963s，api_calls=78），自建 mount harness（6 文件/33 用例）+ 报告在 `<synthetic-hermes-cache>/scratch/adv24/verifier/`。
- A 段：组件真实性（缺字段显示 `—` 而非 `false`、长值不截断、空数组空卡、无 IR 不伪造 JSON、对照三边界）、`selectedActionIrCase` 谓词四情形（逐字提取执行）、C-1 落地（0 调用方 → App.vue 3 处）、本轮范围（`git diff --stat` = 9 文件 398+/1-，`-- server/` 0 行）全过。
- B 段逐字：platform `566/0/0/5` + BUILD SUCCESS + 104 报告；runner `266/266`；前端 `145 files / 710 tests` + `vue-tsc` 0；`git status` 无新增。
- **C 段与修正（随后续提交落地）**：
  - **C-①（中低）**：真实管道（runner `buildStepEvidence` + `page-evidence.withTargetState`、平台 `STEP_EVIDENCE_FIELDS`）只产出**嵌套形状**——`locator` 与 `visible/enabled/receivesEvents/unique/matchedElementCount` 都在 `resolvedTarget` 对象内，顶层不存在这些字段；原组件按扁平字段读 ⇒ 真实数据下恒显示 `—`，且原单测夹具虚构了管道不产出的扁平形状。**修正**：组件改为按真实形状从 `resolvedTarget` 读取（缺字段仍显示 `—`，不伪装 `false`）；夹具重写为三类真实产物（locator-backed / route / observation-failed）；`matchedElementCount` 纳入展示；新增「空 evidenceId 行可寻址展开」锁测。文档「状态位」表述同步修正为「resolvedTarget 内」。
  - C-②（低）：`evidenceId=''` 哨兵冲突导致默认展开且收起无效 → 行键改为 `evidenceId||'no-evidence'` + 行号，并加锁测。
  - C-③（低，潜在）：`selectedActionIrCase` 谓词在 `resultSourceId` 为 undefined 时可交叉误命中 → 两个 sourceCaseId 分支加 `!!resultSourceId` 守卫。
  - C-④/C-⑤（信息）：文档措辞修正（证据 id 仅出现在行 testid/键中；本轮 diff 说明含 docs）。

## 十三、增量 5：批量执行 / 重试 / 回归套件管理（Sprint 6-6）

- **批量执行（既有能力，本轮证实）**：Web 自动化工作台按「选择 + 过滤」范围执行——`effectiveWebAutomationScope = buildWebAutomationScope(filteredWebAutomationCases, webAutomationSelectedIds)`（App.vue:1742-1743，取证链：`selectFiltered`(≈3725) → `filteredWebAutomationCases` → 执行入口 ≈3937-3940 消费该范围）。
- **重试（新增「重跑失败用例」）**：工作台工具条在最近一次运行存在 failed/error 用例时出现「重跑失败用例（N）」按钮（`retryFailedCount` 由 `webAutomationResultStatusById` 统计），点击仅以失败用例集重跑（复用 `startVisualWebAutomationRun(explicitCases)`）；无失败时不显示。
- **回归套件管理（新增）**：`domain/webRunSuiteRepository.ts`——套件 = 范围/过滤快照（project/module/source/status/keyword + savedAt），localStorage 持久化（同名覆盖、损坏容错、按保存时间倒序）；工作台工具条提供「保存为套件」（ElMessageBox 命名）/ 套件下拉「应用」/「删除套件」。
- 装配：`WebAutomationWorkspace` 新增可选 props（`suites`/`retryFailedCount`）与 emits（`saveSuite`/`applySuite`/`deleteSuite`/`retryFailed`）；App.vue 实现存储与过滤器回填。
- 实测：前端 `vue-tsc` 0 + **146 files / 717 tests**（本增量：套件仓储 +3、工具条 +2、证据卡真实形状重写 4 条；相对上一轮 `145/710` 净增 +1 文件 / +7 用例）；platform 与 runner 本增量零改动。
- 边界：套件只存过滤条件（不复制用例数据，用例增删后按条件重新求值）；工具条位于工作台模式（运行监控模式不显示，返回工作台即可操作）。

## 十四、线上冒烟补记（第二十三轮 C-⑧ 收尾）

- runner 容器重建后实测：`GET http://localhost:19323/sessions` → **200 `{"sessions":[]}`**（新代码路径在线）；平台代理 `GET http://localhost:38081/api/web-automation/sessions` 未认证 → **401**。

## 十五、终审：第二十五轮独立复核判定 PASS（2026-10-08）+ C 段修正

- 复核对象：`550e13c`；复核方 `deleg_739db85c`（1019s，api_calls=95），产物在 `<synthetic-hermes-cache>/scratch/adv25/verifier/`（含真实 run 捕获的 stepEvidence JSON）。
- A 段亮点：**双层验证 C-①**——用真实 `buildStepEvidence`+`withTargetState`+本机 Chromium 对本地页跑真实 run，捕获真实 stepEvidence 直喂组件（9 用例通过；locator-backed 显示真值、route 显示 `—`+route JSON、observation-failed 五位全 `—` 且不含 `=false`、`matchedElementCount=2/0` 如实展示）；**修正前组件喂同数据复现了 C-①/C-② 缺陷**（确证「已修复」成立）；谓词机械提取执行（新谓词 undefined 场景不误命中、旧谓词同场景返回 cases[0]）；仓储 6 用例 + 工具条 gating + App.vue 读码 + 批量执行取证链全部成立；线上 19323/401 亲自复现。
- B 段逐字：platform `566/0/0/5` + BUILD SUCCESS + 104 报告；runner `266/266`；前端 `146 files / 717 tests` + `vue-tsc` 0；`git status` 无新增。
- **C 段与修正（随本条提交落地）**：① 文档行号失准（1685-1686 → 实际 1742-1743，取证链已补全）；② 文档数字表述修正（证据卡重写 4 条，相对上轮净增 +1 文件/+7 用例）；③ **重跑按钮补运行中守卫**（handler 加 `webAutomationRunning` 短路 + 传给工具条的 `retryFailedCount` 在运行中归零，杜绝运行中静默重启）；④ route 夹具真实化（四状态位改 false，与 `run-executor.test.mjs` 自证的真实管道一致）；⑤ 仓储 `name` 严格字符串判定（`{name:42}` 拒绝，测试补例）。信息级观察照录不误。

## 十六、终审：第二十六轮定点复核判定 PASS（2026-10-08）+ C 段残余边角补修

- 复核对象：`a9843b5`；复核方 `deleg_e9c0a566`（604s，api_calls=52），产物在 `<synthetic-hermes-cache>/scratch/adv26/verifier/`。
- A 段 6/6：行号 ±3 内全中（1743-1744/3726/3938 + 消费行 3941）；数字自洽（145→146、710→717，静态对账 +1 文件/+7 用例，无「4→5」残留）；重跑守卫三处（handler 短路/表达式归零/工具条 v-if）以真实源码机械执行 4/4；route 夹具与真实 Chromium 捕获逐字段一致；仓储 `{name:42}` 等 6 类边界全拒；两轮测试文件为最小改动无回归。
- B 段逐字：platform `566/0/0/5` + BUILD SUCCESS（41.6s，104 报告）；runner `266/266`（412.9s）；前端 `146/717` + `vue-tsc` 0（无开关对照 9 files/16 tests 环境类失败，0 个涉及本轮改动文件）；`git diff 550e13c..HEAD` 仅 apps/web 4 文件 + docs 1 文件。
- **C 段残余（同一守卫模型的边角）与补修（随本条提交落地）**：① 刷新恢复路径 `recoverWebRunSession` 未置运行态 ⇒ 恢复期间重跑按钮可再现、短路失效 → 补：非终态恢复时置 `webAutomationRunning`（终态/中断分别由 `finalizeWebRunSnapshot`/`clearRecoveredWebRun` 复位，失败路径 `createWebAutomationRun` catch 复位已核实）；② `startVisualWebAutomationRun` 置位发生在 `await resolveVisualWebAutomationRunProjectId`（含后端调用）之后、且入口无前置检查 ⇒ 双击存在并发窗 → 补：运行态置位前移至 await 之前 + 函数首行加运行中互斥短路。前端 `146/717` + `vue-tsc` 0 复跑通过；platform/runner 零改动（diff 仅 App.vue）。

## 十七、终审：第二十七轮定点复核判定 PASS（2026-10-08）——阶段 7 全增量收口

- 复核对象：`de5b7b6`；复核方 `deleg_a5c69d01`（250s，api_calls=35），产物在 `<synthetic-hermes-cache>/scratch/adv27/verifier/`。
- A 段 3/3：**恢复路径置位**——全文件赋值枚举恰 5 处（clearRecovered=false / finalize=false / start=true / start-catch=false / recover=true），置位被非终态守卫夹逼、两者间无 await；「恢复期间重跑按钮不可现」以逐字提取求值 + 真实组件 mount 双重证明（9/9）。**双击并发窗**——首行短路 + 置位前移 + catch 复位三件套齐备；**反证矩阵**（去任一单点 ⇒ 双击 creates=2、第二击不被拒），证明守卫有牙而非摆设。
- B 段逐字：`vue-tsc` exit 0（空输出）；`146 files / 717 tests` 全过（13.08s）；`git status` 仅既存未跟踪项；`git diff a9843b5..HEAD` 仅 App.vue(+10/−2) + 文档 ⇒ platform/runner 零改动。
- C 段 5 条全为信息级（无阻断）：非 404 网络故障下 running 滞留由轮询 3s 自愈/刷新解除；resolve 理论悬挂；双击第二击静默拒绝可接受；文档编号口径已随本条修正；「diff 仅 App.vue」在代码语境成立。
- **至此阶段 7（7.1~7.4 与 Sprint 6 全部任务）的五个增量全部实现并通过独立对抗性复核**：增量 1（第二十一轮 PASS）、增量 2（第二十二轮 PASS）、增量 3（第二十三轮 PASS）、增量 4（第二十四轮 PASS + 修正同轮 C 段）、增量 5 与 C 段修正（第二十五~二十七轮 PASS）。

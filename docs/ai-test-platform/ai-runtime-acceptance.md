> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# AI Runtime Acceptance Ledger（T00 台账）

- 状态：**进行中**（随各任务收口滚动更新）。口径要求（计划原文）：列「未实现／待集成／待真实验证／已验收」四态；**禁止用「类存在」填已验收**；每条已验收必须有可复现证据（命令/测试类/文档）指向。
- 基线：分支 `ui/beauty-redesign-home`；台账建立基线 commit = `2743519`（2026-10-09）；运行环境 macOS + Java 21 + Node（前端）+ docker compose（MySQL 8.4 / Redis 7.4）；DB 迁移位点 **V38**（V1–V38 已应用）。
- 关联文档：`t00-baseline.md`（放行规则与范围限缩）、`provider-compatibility-matrix.md`（T-R2 矩阵）、`ai-artifact-acceptance.md`（T-R3 收口）、`job-scheduler-contract.md`（T-R4 契约）。

## 1. R01–R16 → 任务 → 状态

| 需求 | 覆盖任务 | 当前状态 | 证据 / 缺口 |
|---|---|---|---|
| R01 配置连接并切换模型 | T01–T04、T11 | 待集成（seam 已交付） | V22 seam + `AiConnectionProfileRegistry`；profile/config 版本化管理未做（见 T01） |
| R02 一处验证配置是否能完成任务 | T04、T11 | 未实现 | 无能力探针与分项报告（T04/T11 未开工） |
| R03 导入多模态来源 | T08 | 未实现 | T08 未开工 |
| R04 需求分析与追溯矩阵 | T07–T08 | 待集成 | 需求分析垂直闭环已可用（T-R1 起）；全入口迁移与追溯矩阵增强未做（T07/T08） |
| R05 生成用例并评审交付 | T07、T10 | **部分已验收** | Web 取证→编译→执行→交付链（阶段 3–7）已逐项独立复核；接口/功能两流程的三条 schema 流程与 `AiCaseDeliveryContractTest` 未做（T03/T10 余项） |
| R06 七类 QA 助手 | T12–T13 | 未实现 | — |
| R07 Skill/Agent/工具/MCP 可用 | T09 | 待集成（部分已验收） | MCP 真实远端 HTTP transport 已交付（T-R3 证据）；工具面 DAG/准入未做 |
| R08 长任务可见/可取消/可恢复/可重试 | T05–T07 | **部分已验收** | 可见/取消/恢复/重试已在需求分析链验证（T-R3 线上 + T-R4 契约）；统一 Runtime 的 attempt 级控制未做（T05/T06） |
| R09 多 Agent 产物解释 | T09、T12 | 未实现 | — |
| R10 知识复用与经验学习 | T08–T09 | 未实现 | — |
| R11 失败定位/花费/质量改善 | T05–T06、T13 | 未实现 | 调度指标（T-R4）已交付 queue/lease/attempt/TTFB/blocked/recovery；usage/预算/评测未做（T05/T13） |
| R12 升级后数据/权限/历史可用 | T01、T14–T15 | 未实现 | 迁移向前可升级（Flyway V1–V38 在 MySQL 实际应用）；回滚演练/发布版本可见未做 |
| R13 真实 AI 结果可判定 | T-Review-3、T07–T12 | **已验收（本任务链）** | `ai-artifact-acceptance.md` 全部闭合 + 线上 UI/API/export 校验 |
| R14 模型切换可证明 | T-Review-1、T-Review-2、T04、T13 | **已验收（R14 的矩阵部分）** | 矩阵文档 + 双 profile live 切换证据（T-R1/T-R2 PASSED）；T04/T13 侧未做 |
| R15 真实长任务运行 | T-Review-4、T06、T15 | **待复核**（T-R4 交付物已实现并自测通过） | `job-scheduler-contract.md` + `AiJobSchedulerIntegrationTest`（真实 MySQL 8 用例）；T06 统一 runtime 迁移待 T-R4 复核通过后启动 |
| R16 AI 质量可复现 | T-Review-5、T13 | 未实现 | Gold Set 与 rubric 未建（T-R5 未开工） |

## 2. A01a–A20 门禁清单 → 状态

| 门禁 | 当前状态 | 证据 / 缺口 |
|---|---|---|
| A01a 双协议 contract fixture | **部分已验收** | `AiProtocolTransportTest`（Chat 请求序列化/可选参数/错误路径）+ Responses typed output/usage fixture（矩阵 CONTRACT_PASS）；流事件契约未做 |
| A01b 当前部署真实档案业务验收 | **已验收** | T-R1 真实业务闭环（gpt-5.6-sol，requestId 留存） |
| A02 更换同协议模型名称 | **已验收（矩阵部分）** | 同输入 hash 双 profile 切换（T-R2）；「未知能力预检不放行」依赖 T04 未做 |
| A03 200 空内容/仅 reasoning/refusal/length 分类 | 未实现 | T05/T03 未开工；worker 侧现有截断/空响应识别为局部实现 |
| A04 JSON 错误/引用/截断/fence 拒绝 | **部分已验收** | `AiStructuredOutputRepairService` + Web 编译 fail-closed（阶段 3–6）；完整 schema 遍历校验属 T03 |
| A05 401/429/503/502 重试与跳路由 | **部分已验收** | `AiRouteExecutor` 预算/备用路由（既有单测）；与 T05 分类表对齐未做 |
| A06 各阶段取消后 2s 内停止发新调用 | **部分已验收** | 需求分析链在分片边界检查取消（现状）；「2s 内」的严格口径待 T05 |
| A07 剩余 8s vs attempt 300s 共享预算 | **部分已验收** | whole-run deadline（既有测试）；模型/repair/工具共享预算属 T05/T06 |
| A08 JVM kill/双 worker/重复 submit | **待复核**（T-R4 实现） | 集成测试覆盖双 worker 单 owner、崩溃接管、fence 拒写、重复领取；待独立复核 |
| A09 A/B 项目交叉越权全拒 | 未实现（专项） | `ProjectAccessService` 为既有能力；AI 对象全量接入核查属 T14 |
| A10 多模态来源 manifest 完整性 | 未实现 | T08 未开工 |
| A11 MCP nonce/错误/断连/跨项目拦截 | 未实现（专项） | 真实 transport 已交付；对抗性用例与跨项目拦截属 T09 |
| A12 Web 候选含猜测 locator 不可交付 | **已验收** | 阶段 3/4/6 的 fail-closed 编译 + 证据链（多轮独立复核）；跨页面证据专项用例可补 |
| A13 七类 QA 标准案例 | 未实现 | T12/T13 |
| A14 usage 缺失/配额/repair 费用 | 未实现 | T13/T05 |
| A15 probe pass 后改 endpoint/header 失效 | 未实现 | T04/T01 版本化 |
| A16 INHERIT/EXPLICIT 快照语义 | **部分已验收** | run snapshot 不可变（T-R1/T-R4 契约）；INHERIT/EXPLICIT 策略属 T01 |
| A17 secrets/注入不进入可见面 | **部分已验收** | `SecretCipher` + 脱敏（`MIN_REDACT_LENGTH`）；全通道专项属 T14 |
| A18 MySQL 升级/旧库导入/回滚 | 未实现 | 发布演练属 T15 |
| A19 fallback 显示 RULE/DEGRADED/BLOCKED | **已验收** | T-R3 原产地体系（多入口负例同语义） |
| A20 中英双语/大文本/多分片/刷新恢复 | **部分已验收** | 多分片续跑（T-R4 契约）+ Web 中文链路（阶段 3–7）；大文本/双语专项属 T13/T15 |

## 3. 基线冻结项（T00 交付）

- **SHA/环境**：见文档头；分支与迁移位点以每次收口时为准（滚动更新）。
- **现有 AI routes 与真实 gateway call-site 清单**：`OpenAiCompatibleAdapter`（legacy 网关）+ `AiProtocolTransportClient`（Runtime 双协议 transport）；调用方见 `AiRouteExecutor` / `AiRuntimeInvocationService` / `AiVerticalSliceService`。
- **凭证**：仅以 opaque ref 记录（`credential_ref`/`legacy-ai-system-config`），不复制值。
- **五个核心测试类摘要**：`AiAnalysisWorkerTest 12/0/0/0；AiAnalysisRunObservabilityTest 7/0/0/0；AiArtifactAcceptanceTest 4/0/0/0；AiArtifactAcceptanceCrossEntryTest 2/0/0/0；AiRuntimeInvocationServiceTest 2/0/0/0（合计 27/0/0/0，BUILD SUCCESS，专用 H2 测试库）；前端配置相关：aiConfigRepository + aiEnvironmentReadiness 2 文件 8 例全绿。全量门禁（本轮）：platform 577/0/0/0 + BUILD SUCCESS（含真实 MySQL 的 AiJobSchedulerIntegrationTest 8 例）。`。
- **上游 channel 事实**：GPT-5.6-sol 的 503/超时均为**当次观察**（矩阵文档已限定结论），不作为永久结论。

## 4. 参考项目固定 commit

- lemon-TestPilot `d185e077861f58f4fda71e9ad5763463e69407c3`：采用其「清晰任务入口、输入完整性、provider 参数可见、知识引用、人工复核、固定回归案例」的产品组织；**不采用**其「页面可操作即真实 AI 完成」「空内容以 `{}` 兜底」「失败后本地规则兜底并标完成」的语义（计划 §3 已明确）。

## 历史行为基线（受控合成数据，T00 第 3 项）

以受控合成数据固定以下既有契约作为后续对比基线（全部有确定性测试复现，不依赖真实上游）：

1. **模型名可配置**：默认连接/策略装配读取当前激活 provider 的模型名与地址，不存在按 `gpt-*` 字符串的分支或硬编码常量。复现：`AiRunPolicyServiceTest#seedsThreeDefaultPoliciesFromActiveProviderAndIsIdempotent`。
2. **三类默认 policy**：`requirement_analysis` / `web_case_generation` / `api_case_generation` 的默认路由与超时（retry=2、perCall=180s、deadline=900s）。复现：同上 + `AiRunPolicyServiceTest` 系列。
3. **analysis→point→review→generation 链**：分析产物 → 草稿测试点 → 评审 → 用例生成的状态机与契约（确认/驳回带 revision、过期 409 `stale_revision`、部分成功记 `partial`）。复现：`AiCaseDeliveryContractTest`、`WebCaseAgentRunServiceTest`。
4. **Web plan/compile 契约**：计划须先确认才可编译；动作白名单、≥1 断言、越界整批 `invalid_action_ir` fail-closed；执行回写只来自 Runner 快照。复现：`WebCaseAiRuntimeFlowTest`、`WebCaseRunnerWriteBackContractTest`。

基线纪律：上述用例在受控数据上的结果即「历史行为」。改造后若结果变化，先更新本清单再修改实现——杜绝凭单次观察推导根因。

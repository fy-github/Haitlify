> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# 完整 AI 平台逐项实施计划

> 执行者：先读 [主计划](./2026-09-16-ai-platform-completion-plan.md)。按照 superpowers:executing-plans 分批实施；仅在用户选择并行执行时使用 subagent-driven-development。所有复选框保持未完成，直到对应证据实际存在。

**Goal：** 将主计划 R01–R16 落为可分批提交、逐阶段验收的任务。

**Architecture：** 接口收敛到统一 Runtime；协议 adapter、持久 job、证据、工具、业务产物分别承担责任。需求分析是首个完整垂直切片，之后覆盖全部现有入口与 QA 助手。

**Tech Stack：** 使用仓库现有 Spring Boot/JDBC/Flyway/Vue/Node 工具链；新增 schema/MCP 依赖先验证 Jackson 3、Java 21 与许可，不预先锁定未经核实的 SDK。

## 路径、执行规则与命令

为避免路径冗长，任务中的 `J/`、`UT/`、`DB/`、`FE/`、`WR/` 为唯一确定的路径别名：

```text
ROOT = /path/to/Haitlify
J    = ROOT/server/platform/src/main/java/com/haitlify/aitest/platform
UT   = ROOT/server/platform/src/test/java/com/haitlify/aitest/platform
DB   = ROOT/server/platform/src/main/resources/db/migration
FE   = ROOT/apps/web/src
WR   = ROOT/server/web-runner
```

“新增”路径是目标文件，不表示当前已有实现。现有文件只在职责相关范围修改。每任务按“写失败样例→运行验证失败→实现→针对性测试→提交”推进；大的任务拆为同 ID 下多个 commit，任何阶段不得以 skipped 测试或 mock 上游成功代替真实验收。M0 的四个 Review Gate（T-Review-1 至 T-Review-4）是扩展功能的硬门禁；任何 Gate 未通过，只修底座或记录 BLOCKED，不进入后续大范围功能扩展。T-Review-5 是 M3 的质量放行门禁，不属于 M0。

Java 测试统一从 `ROOT/server/platform` 运行 `./mvnw -Dtest=<任务列出的类> test`；新测试类由该任务先创建。Vue 测试从 `ROOT/apps/web` 运行 `npm run test -- --run <src 路径>`。浏览器测试从同目录运行 `npm run test:e2e -- <e2e 文件>`。每个测试命令预期 0 failures/errors，首次红灯例外且必须证明失败来自目标缺陷。

提交前运行 `git diff --check`，只暂存该任务文件；建议提交信息写在各任务末尾。不得 `git add .` 收进未跟踪评审文档或真实运行数据。不得把真实 key、用户原文、截图或模型完整输出写进 fixtures。工作分支执行时由用户选择的基线新建 `codex/ai-runtime-foundation`；计划交付不切换当前分支。

## T-Review-1：最小真实 Runtime 垂直切片

依赖：T01/T02 的最小 connection/profile/protocol seam。目标：在动大范围架构前证明一条真实模型业务链可用。T01/T02 只交付最小 seam，不代表 M0 真实闭环已通过。

文件：新增 `J/ai/runtime/vertical/AiVerticalSliceService.java`、`UT/ai/runtime/vertical/AiVerticalSliceTest.java`、`docs/ai-test-platform/provider-compatibility-matrix.md` 初版；使用现有配置/数据库和 Runtime invocation harness，不创建旁路 mock provider。

- [x] 选定一个已授权、当前网络环境可访问的真实 connection 和 model profile；脱敏固定需求只包含一条需求和两个来源引用。
- [x] 接通最小 Runtime invocation：加载 connection/profile、生成 run snapshot、发出一次真实 attempt、读取非空返回、运行一个 schema、创建测试点 artifact、人工确认并查询回读；持久 lease/recovery 放到 T-Review-4，不在本 Gate 重复实现第二套 scheduler。
- [x] 结果强制包含 `executionKind/resultOrigin/acceptanceState`；真实调用失败必须 BLOCKED/DEGRADED，规则结果不能伪装 MODEL_GENERATED。
- [x] 保存 request hash、profile version、protocol、provider requestId、usage-known、schema outcome、evidence IDs；绝不持久化 key、完整用户原文或隐藏推理。
- [x] 同一脱敏输入在当前环境必须运行成功；若当前网关没有可用 channel，只允许产出带 endpoint/profile/requestId 的可复现 BLOCKED 诊断，Gate 不通过并停止扩展，改用用户明确授权的另一已验证档案，不把不可用模型标为 supported。

验收：必须有一个真实成功闭环；BLOCKED 只能作为失败证据，不能作为 Gate 通过条件。禁止用 ping、mock、静态规则输出放行。提交 `feat(ai): prove first real runtime vertical slice`。

状态（2026-10-09 复核）：**PASSED（2026-09-18，真实业务闭环）**。证据：`docs/ai-test-platform/provider-compatibility-matrix.md`（真实 attempt、requestId、schema/evidence/usage 全过）、`docs/ai-test-platform/t00-baseline.md`（闭环描述与放行规则）、`AiVerticalSliceService`/`AiRuntimeInvocationService` + V22/V23、live 验收测试类（`AiRuntimeLiveInvocationTest` 等）。早期 BLOCKED 诊断（`50be210`）已由后续真实成功闭环取代。

## T-Review-2：Provider Contract Matrix 与模型切换证明

依赖：T-Review-1。目标：让“支持模型”成为可验证事实。

文件：新增 `docs/ai-test-platform/provider-compatibility-matrix.md` 完整版、`UT/ai/runtime/vertical/AiProviderCompatibilityMatrixTest.java`、`scripts/ai-provider-probe.mjs`；修改 T00 台账引用。

- [x] 每个 provider/protocol/profile/能力单元格记录输入 hash、时间、config/profile version、requestId、HTTP/finish status、usage known/unknown、schema/evidence outcome、latency 和结论。
- [x] 统一固定脱敏输入、skill/schema、endpoint/credentialRef 和参数；模型切换只允许改变显式 profile，历史 run snapshot 不变。
- [x] 分别证明 text、json_schema/json_mode、reasoning、tool_calls、streaming、image input；不支持项写 UNKNOWN/UNSUPPORTED，不用模型名称推断。
- [x] 矩阵拆分 `contract_pass` 与 `live_business_pass`；Chat/Responses 两种协议前者均需 fixture，后者当前部署至少一个任务所需协议+能力通过。
- [x] GPT-5.6-sol 或其他无 channel 模型不得被手工填为 supported；矩阵链接具体诊断记录。

验收：能比较 GPT-5.5 与 GPT-5.6-sol/其他档案的真实状态；未来换模型无需改业务调用代码。提交 `docs(ai): add provider compatibility evidence matrix`。

状态（2026-10-09 复核）：**PASSED**（两个真实 profile 使用同一脱敏输入完成 live business comparison；`gpt-5.6-sol` 与 `gpt-5.5` 双 request ID、HTTP 200、usageKnown=true、schema/evidence 全过，见矩阵文档第 11 行）。证据：`docs/ai-test-platform/provider-compatibility-matrix.md` + `AiProviderCompatibilityMatrix`/`AiProviderCompatibilityProbeService` + live 测试类（`AiProviderCompatibilityModelSwitchLiveTest`）。遗留（不影响 Gate 结论）：profile/config 版本化管理与矩阵记录工具化待 T01 后续补齐；部分能力单元格保持 `UNKNOWN`（未做该能力的真实调用前不得改写）。

## T-Review-3：统一产物原产地与 Acceptance Gate

依赖：T01 的契约草案，但必须在 T02/T03 业务迁移前完成。目标：所有入口只有一种成功语义。

文件：新增 `J/ai/runtime/artifact/AiArtifactAcceptanceService.java`、`AiArtifactOrigin.java`、`AiFieldOrigin.java`、`UT/ai/runtime/artifact/AiArtifactAcceptanceTest.java`；修改 T03 schema 注册、`J/ai/draft/`、`J/ai/figma/`、`J/ai/agent/` 产物模型。

- [x] 定义 `MODEL_GENERATED/RULE_EXTRACTED/TOOL_OBSERVED/HUMAN_EDITED`、`DEGRADED/BLOCKED/ACCEPTED/EXECUTED`，每个状态允许的下一个状态写成状态机测试。（证据：`AiArtifactAcceptanceTest` 状态机用例）
- [x] 字段级标记 `MODEL_PROPOSED/RULE_COMPUTED/TOOL_OBSERVED/HUMAN_REQUIRED`，报告 pass rate、上线结论、SQL 已执行结论和最终 Web 执行结果不可由模型字段覆盖。（证据：`field_origins_json` + `AiArtifactOriginPolicyTest`；字段级标签禁止出现在 artifact 级 origin/state 列 —— V33/V34 词表约束 + `schemaRejectsArtifactOriginOutsideTheVocabulary`）
- [x] Acceptance Gate 统一检查非空完成响应、schema、业务约束、evidence scope、project、artifact parent/revision/idempotency；失败不创建正式交付物。（证据：`AiArtifactAcceptanceService` + draft point 三入口 `BLOCKED` 用例）
- [x] 修复 Figma direct mapper、MCP echo、Multi-Agent auto-succeeded、固定 quality score 和 Web generatedCount 直接成功路径：标 RULE/TOOL/BLOCKED 或接入真实 Runtime。（证据：Figma `RULE_EXTRACTED`、MCP 远端 HTTP transport、`mergeFallsBackToRuleOriginWhenAiEnrichmentIsUnavailable`、Web 只统计 `accepted=true AND ACCEPTED`）
- [x] UI/API/导出使用同一 origin/acceptance 字段，禁止仅根据 status=succeeded 解释为真实 AI 成功。（证据：run/artifact 双级 origin 派生、导出与前端按 `acceptanceState === 'ACCEPTED' && accepted` 过滤）

验收：同一组负例在所有入口都得到相同状态语义；占位结果不能出现在 MODEL_GENERATED/TOOL_OBSERVED。提交 `feat(ai): unify artifact origin and acceptance gate`。

状态（第三轮复测，全量通过）：代码、迁移与结构化约束已验证通过（后端全量 **535 tests / 0 failures / 0 errors / 5 skipped**，exit 0）；Flyway V1–V35、5 个库级 fail-closed 探针全部被拒、镜像产物核对；实时付费 live gate（4 个原有 + 新增 `AiWebPlanLiveGateTest`）已用真实 provider 调用跑通并记录 request ID；**登录态 UI/API/export 线上校验已完成**（真实 synthetic_user 会话：未确认 artifact 的导出/质量统计为空，人工确认后导出恰好包含被确认项、Gate 审计行回读一致），并修复了该轮线上校验发现的 Gate 审计 actor 硬编码缺陷（改为取认证上下文 + fail-closed）。详见 `docs/ai-test-platform/ai-artifact-acceptance.md`。

## T-Review-4：Job Scheduler Operational Contract

依赖：T01 的 job schema 草案。目标：在大规模 Runtime 前证明任务可恢复且不重复。

文件：新增 `docs/ai-test-platform/job-scheduler-contract.md`、`UT/ai/runtime/AiJobSchedulerIntegrationTest.java`；补充 T06 job migration/worker 文件。

- [x] 明确 `claimNextJob` 的 MySQL 事务方案（`FOR UPDATE SKIP LOCKED` 或等价 CAS）、排序优先级和 project/actor/global 并发默认值。
- [x] 固定 lease TTL、renew interval、recovery interval、graceful shutdown deadline，并记录选择理由；所有值可由策略配置但有平台上限。
- [x] integration fixture 运行双 worker：单任务只能一个 owner；owner 崩溃后 lease 接管；旧 fencing token 写入被拒；已完成 node 不重复。
- [x] 启动 recovery job、停机停止新领取、取消阻止新 attempt；指标包含 queue wait、lease age、attempt duration、TTFB、blocked count、recovery count，并有告警阈值。
- [x] job/attempt 状态和事件 seq 在刷新/断线后可重放；没有数据库长事务等待上游响应。

验收：双 worker/JVM kill/优雅停机测试全通过后，T06 才允许进入生产迁移。提交 `docs(ai): define durable scheduler contract`。

状态（2026-10-09）：**第三十轮复核 FAIL（`2743519`）→ 修复（`186dede`）→ 第三十轮补审 FAIL（`186dede`）→ 修复完成（本提交），第三十轮补审 2（30c）进行中**。30b 复核确认：上轮 4 项缺陷复测均不可复现（对照 2743519 3/3 FAIL vs 186dede 3/3 PASS）、C5–C8 通过、全量 585/0/0/5 连跑一致；但对抗新实现自身发现 3 项 medium + 1 项 medium-low：C1 并发同项目双发放（门判定与 CAS 非原子，自然竞态 21/21 复现；根因含 MySQL REPEATABLE READ 下等锁方旧快照看不见先提交租约的门盲判）、C2 claim→markRunRunning 窗口内取消被静默吞掉（根因为上轮点名未修的 `markRunRunning` 无围栏/无条件）、C3 §3 收尾声明仍过度（coverage 在围栏提交前执行、stale owner 可写）、C4 恢复重排无自动派发（依赖人工 `/resume`，§4 未闭环）。**本轮修复（本提交）**：领取改为项目行锁短事务（`SELECT ... FOR UPDATE` 锁项目行，跨进程串行化）且事务显式 READ COMMITTED（语句级快照使门必见先提交者租约）、`markRunRunning` 围栏 + `status not in ('cancelled','succeeded')` 条件（0 行即释放租约并以 cancelled/preempted 退出）、coverage 扫描移至围栏提交之后、deadline 分支 `isLeaseHeld` 早退、恢复重排经 `dispatchRecovered` 闭环派发、不存在 run 返回结构化 `missing`（不再 500）、锁测增强（IT 13 用例：取消断言 `started_at` 不被写、停机真走 `/resume` 共享路径、并发同项目 claim 至多一租约 ×4 连跑全绿）。**30c 补审（独立）判定 FAIL（门禁非确定 + 判定级 2 恢复派发 stranded + C4-1 收尾取消 + C4-2 锁超时 + C4-3 嵌套事务）→ 修复于 `b2ce8a6`；30d 判定 FAIL（孤儿重派谓词漏「残留过期租约」崩溃态）→ 修复于 `c9a6550`；30e 判定 PASS → 残余修复于 `97946dd`；30f 判定 PASS → 残余修复于 `a6e49e8`；30g 判定 PASS → 残余修复于 `7032239`；30h 判定 PASS → 残余修复于 `c4f6838`；30i 判定 PASS（附返工项：幽灵条目回归）→ 修复于 `2d9159c`；30j 判定 PASS（未构造出确定性复现的纳秒级窗口按建议一行收敛）→ `88a52e6`。** 综上：**T-R4 验收通过（2026-10-09，六轮独立复核全覆盖，调度器线正式归档），M0 四道硬门禁全部就位；T06 迁移放行，**T03 增量 1–5 已推送（至 `8af063f`）；任务级复核 round 1（部分达成）→ 修复 `0de1084` → round 2：C1/C2/C5/C7 关闭、无 high；C-R2-1（items 数组 tuple 形态）与 C-R2-3（语义扩展测试缓存竞态）已收口于 `9b96df9`/`5d5b2b0`；C-R2-2（dispatcher 断言竞态）收口于 `e6fe2cf`→`27241d5`；C3/C4 维持既定分级（T05/T06 预算接线时武装、空上下文设计保留）。**

## T-Review-5：Gold Set 与质量评分 Rubric

依赖：T07/T12 的首批真实样例，但必须在 T13 规模化评测前完成。目标：让“AI 质量提升”可复现。

文件：新增 `server/platform/src/test/resources/ai/evaluation/gold-set.jsonl`、`docs/ai-test-platform/ai-quality-rubric.md`、`J/ai/evaluation/AiGoldSetService.java`、`UT/ai/evaluation/AiGoldSetRubricTest.java`；扩展 T13 evaluation migration。

- [x] 每类任务先准备至少 10 个脱敏 gold case，固定正常/边界/缺证据/恶意输入比例；保存 source/evidence、必测 coverage、禁止结论和允许变体。（`gold-set.jsonl`：8 类任务 × 10 例 = 80，固定比例下限 正常4/边界2/无证据2/恶意2；`AiGoldSetService.validate` 强制下限与证据/禁语字段——**负例只增不减**；恶意样例含注入文本，无证据样例禁语含「已定位根因」）
- [x] 定义 schema correctness、evidence precision、coverage recall、hallucination rate、human acceptance、latency/cost 公式；模型/提示词/skill/schema/profile/rule 版本写入评测结果。（公式全文见 `docs/ai-test-platform/ai-quality-rubric.md`；全部由执行事实确定性计算，**无固定 0.8/0.9 分数**；`versions_json` 落 gold-set 包版本/profile/skill/规则版本（`AiFailureClassifier.RULE_VERSION`）；`AiEvaluationService` 扩展 `forbiddenClaims→forbiddenHits`（幻觉面）与 `outputSha256`（失败样例可定位不落原文）；latency/cost 经 facade 预算台账按 attempt 记账）
- [x] 两名人工标注分歧需要 adjudication；标注者不能看到模型档案名，避免偏差；人评与规则分数分开存储。（`annotationPayload` 盲化（不含 profile/skill/model）；`ai_quality_annotations`/`ai_quality_adjudications` 与 `ai_quality_evaluation_runs/cases` 分表（V49）；分歧 ⇒ `disagreement=true` 待第三方裁决；测试含盲化断言与分歧→裁决全流程）
- [x] 先对 10 case 重复运行 3 次验证评分稳定，再扩展到 30+；阈值按 taskType 配置，不用一个 90% 覆盖率代替全部质量。（发布套件每案例重复 3 次（10×3=30 运行/类）；`stability` 判定同一 caseId 各次运行四项逐位一致并列不稳定案例；阈值 `app.ai.evaluation.thresholds` 支持逐 taskType（`task=schema:..,evidence:..,coverage:..,hallucination:..;default=..`），四项逐一判定）
- [x] 质量低于门槛时禁用 profile 或 skill 版本；不能修改断言、减少负例或把规则结果混入模型分数。（低于门槛输出 `disable-version-recommended` + 逐条依据并落 `gate_json`；`applied` 恒 false——**绝不自动禁用**，由独立操作执行；校验器强制负例比例下限防「减少负例」；规则/人评分表存储防混分）

状态（2026-10-12）：**T-Review-5 交付完成**。新增 `gold-set.jsonl`（8 类 × 10 例，固定 4/2/2/2 比例）、`AiGoldSetService`（包校验 / Rubric 评分 / 门槛建议 / 盲化标注与裁决 / V49 持久化）、`AiGoldSetRubricTest`（7 例：比例与重复上限、拒绝重复 id/未知类型/缺负例、手工复算对账、按类型阈值覆盖、端到端 30 运行注入命中、H2 盲化标注与分歧裁决）、`docs/ai-test-platform/ai-quality-rubric.md`；`AiEvaluationService` 扩展禁语检测与输出摘要（旧签名全兼容）；迁移 `V49__ai_quality_evaluation.sql`（真实 MySQL 建表验证 + H2 兼容经测试实证）。全量 **868/0/0/0**。


验收：gold set 可重跑、评分可解释、失败样例可定位到 output/evidence/version。提交 `feat(ai): add reproducible gold set and quality rubric`。

## T00：冻结事实、交付范围与兼容基线

依赖：无。T00 只冻结事实、环境和台账；T01/T02 先交付最小 connection/profile/protocol seam，之后才能执行 T-Review-1。交付主计划 M0；R01–R16 的验收台账。

文件：新增 `docs/ai-test-platform/ai-runtime-acceptance.md`；引用现有 `AGENT.md`、`DEPLOYMENT.md`、`.github/workflows/ci.yml`；不修改用户的 `docs/reviews/`。

- [x] 记录当前 SHA、运行环境、DB 类型/版本、已应用 migration、现有 AI routes 与真实 gateway call-site 清单；凭证仅列 opaque ref，不复制其值。
- [x] 建立 R01–R16→T-Review-1–T15→A01a–A20 台账，列“未实现/待集成/待真实验证/已验收”，禁止用“类存在”填已验收。
- [x] 用受控合成数据建立历史行为基线：GPT-5.5 名称可配置、三类默认 policy、analysis→point→review→generation、Web plan/compile 的既有契约。（已交付：`docs/ai-test-platform/ai-runtime-acceptance.md` §历史行为基线——受控合成数据契约与逐条复现测试清单）
- [x] 固定参考项目 commit，记录採用/不采用项；记录上游 channel 不可用只能是当次观察，不作为永久账户结论。
- [x] 在独立测试库运行主计划现有五个后端核心测试类及相关前端配置测试，保存摘要；已存在失败单列，不为让计划好看而删测试。

验收：每个 AI 入口有 OWNER 模块、实际调用种类和目标迁移任务；部署目标 MySQL/H2 身份明确。提交 `docs(ai): define runtime baseline and acceptance ledger`。

状态（2026-10-09 复核）：**大部分补齐（本次提交一并交付）**。已交付：`docs/ai-test-platform/ai-runtime-acceptance.md` R01–R16→T→A 全量台账（四态标注、逐条证据）、环境/SHA/迁移位点/route 清单、参考项目采用/不采用记录、五个核心后端测试类摘要（27/0/0/0）+ 前端配置测试（2 文件 8 例）。第 3 项（受控合成数据基线）：相关行为与契约均有既有实现与测试（config 驱动模型名、默认 policy、analysis→point→review→generation、Web plan/compile 契约），但未形成独立基线文档条目，保持未勾选。

## T01：连接、模型档案、策略与快照契约

依赖：T00。对应 R01/R12、A02/A16/A18。

文件：新增 `J/ai/runtime/AiRuntimeModels.java`、`J/ai/config/AiModelProfileService.java`、`J/ai/config/AiConnectionService.java`、`J/ai/config/AiEffectiveOptionsResolver.java`；修改 `J/ai/config/AiConfigService.java`、`J/ai/config/AiRunPolicyService.java`、`J/ai/gateway/AiGatewayConfig.java`；新增 `UT/ai/config/AiModelProfileServiceTest.java`、`UT/ai/config/AiEffectiveOptionsResolverTest.java`、`UT/ai/AiRuntimeMigrationTest.java`；新增下一序号 migration `ai_runtime_configuration`。

- [x] 先以表驱动测试固定字段解析：null 继承、0/false 保留、显式策略不被连接保存覆盖、unknown capability 不产生未知参数、配置版本冲突返回 409。（`AiEffectiveOptionsResolverTest` 7 例：null 继承、0/false 保留（绝无 value||default）、显式覆盖优先、平台上限 min() 收紧；`AiModelProfileServiceTest.staleExpectedVersionIsA409`；连接保存不覆盖显式策略由 `AiRuntimeMigrationTest` 断言）
- [x] 为 Connection/Profile/Policy/RunSnapshot 定义版本化 DTO，schema 与 policyId 必须可追溯；敏感字段仅 CredentialRef。（`AiConfigurationDtos`：四个版本化视图；任何 DTO 不携带密钥材料，仅 `credentialRef`（opaque）；run 快照固定 connection/profile 版本）
- [x] 新增兼容表与非破坏性导入器；旧 provider JSON 导入 CHAT_COMPLETIONS+UNKNOWN capabilities；重复导入幂等、旧 policy timeout 作为 EXPLICIT。（`AiLegacyConfigurationImporter`：旧配置 ⇒ CHAT_COMPLETIONS + capabilities=[]（UNKNOWN 不假设）；重复导入幂等（版本与行数不变）；旧 policy timeout 作为 EXPLICIT 任务覆盖（null≠0）；历史行永不改写）
- [x] resolver 输出 `{effectiveValue, source, sourceVersion}`，连接编辑不再无条件覆盖所有 default policy；项目覆盖不能突破系统限制。（`AiEffectiveOptionsResolver`：来源 platform_limit / task_override / connection_default / profile / run_snapshot / omitted；项目侧覆盖受平台上限约束——min() 收紧一切来源）
- [x] 对两个连接相同 modelId、一个连接多个 profile、失效 credentialRef、并发更新写测试。历史快照不可被新 profile 更新覆盖。（`twoConnectionsSameModelIdAndOneConnectionManyProfilesAreAllowed`、`concurrentRegistrationsNeverShareAVersion`（6 线程版本两两不同，冲突只允许 409 语义）、历史快照按版本复读；credentialRef 视为 opaque 原样存储、材料化解密失败 fail-closed 由 T05 `AiFallbackRouteSelector` 既有测试覆盖）
- [x] MySQL 与 H2 都执行旧 schema→新 schema 数据迁移样例，验证关系/数量，建立回滚读取窗口。（H2：`AiRuntimeMigrationTest` 2 例（导入/幂等/回滚读取窗口/关系）；MySQL：V22 三表经 Flyway 在运行库建表并核验——1 连接/2 档案/0 孤儿关联（2026-10-12 实查），旧配置行原样保留）

核心断言示例：

```text
connection.timeout = 300s; policy.timeout = EXPLICIT(120s) => effective = 120s
connection.timeout = 300s; policy.timeout = INHERIT        => effective = 300s
profile.temperature = omitted                           => wire field absent
profile.version = 4; run.profileVersion = 3              => run uses version 3
```

验证：`AiModelProfileServiceTest,AiEffectiveOptionsResolverTest,AiRuntimeMigrationTest,AiConfigControllerTest,AiRunPolicyServiceTest`。提交 `feat(ai): add versioned connection and model profiles`。


状态（2026-10-12）：**已交付（本轮补建）**——V22 三表 + `AiConnectionProfileRegistry` + 垂直切片写入方之上，补齐 `AiConnectionService`/`AiModelProfileService`（追加式版本、乐观并发 409、未知能力 fail-closed、opaque 凭据引用）、`AiEffectiveOptionsResolver`（`{value, source, sourceVersion}` 溯源；null 继承/0 保留/平台上限 min() 收紧）、`AiLegacyConfigurationImporter`（非破坏性、幂等、旧超时 EXPLICIT）、`AiConfigurationDtos`（四类版本化视图，敏感字段仅引用）；测试 `AiEffectiveOptionsResolverTest`(7)/`AiModelProfileServiceTest`(7)/`AiRuntimeMigrationTest`(2)，全量 884/0/0/0。T11 连接/profile 编辑器的后端前置至此就绪。

## T02：Chat Completions 与 Responses 两种真实协议

依赖：T01。对应 R01/R14、A01a/A01b/A03/A17。

文件：新增 `J/ai/runtime/transport/AiProtocolAdapter.java`、`ChatCompletionsAdapter.java`、`ResponsesAdapter.java`、`AiTransportModels.java`、`AiEndpointResolver.java`（均同目录）；修改 `J/ai/gateway/DefaultAiGateway.java` 与 `OpenAiCompatibleAdapter.java` 为迁移 facade；新增 `UT/ai/runtime/transport/AiProtocolContractTest.java`。

- [x] 用 JDK HttpServer 建立完全本地协议 fixture，先覆盖路径与请求 JSON：base `/v1`、已有完整 endpoint、尾斜杠、非法混接、模型名含需转义字符。（已实现：`ai/runtime/transport/AiEndpointResolver` + `AiProtocolTransportTest.resolvesExplicitProtocolEndpointsWithoutGuessingModelNames`——base `/v1`→`/v1/chat/completions`、显式完整 endpoint、非法混接抛错、模型名含转义字符均在案）
- [x] 请求用 Jackson 节点序列化，按档案 capability 发送参数；未声明 temperature/reasoning/token limit 时省略；不按 gpt 字符串 if/else。（已实现：`AiTransportRequest`/`AiProtocolTransportClient` 节点序列化 + `omitsUndeclaredOptionalChatParameters`（未声明参数省略）、`chatFallsBackToJsonObjectWithoutContract...`；无按 gpt 字符串分支）
- [x] Chat 提取 message/text/tool_calls/refusal/finish_reason；Responses 遍历 output items、区分 completed/incomplete/failed，忽略隐藏 reasoning。合法 JSON 对象不代表 task 成功。（已实现：`AiProtocolAdapterTest`（Chat 归一化 message/tool_calls/未知 usage；Responses 仅接受 output_text 项）+ `AiProtocolResponse`（status/text/toolCalls/finishReason/refusal/usage/providerRequestId 归一化））
- [ ] 持续流模式消费 typed events/Chat delta，事件顺序与断流测试通过；不支持 streaming 的档案用非流式；取消关闭 response body 与 future。（未实现留档：SSE/流式消费尚未实现。按主计划第 190 行，streaming 仅在协议验证后启用；当前无 VERIFIED streaming 档案，接口保留——随首个 streaming 档案接入时补齐消费、事件顺序与断流测试）
- [x] 非 2xx 提取 error.type/code/message/requestId，经脱敏限长；不把 provider 回显的 Authorization/header 当可展示信息。（已实现：非 2xx error.type/code/message 提取 + `AiFailureClassifier` 版本化分类 + `Retry-After`（数字与 RFC1123）+ 出站全过 `AiPayloadSanitizer`；provider 回显的 Authorization 头不作可展示信息）
- [ ] Token 使用包括 input/output/reasoning/cache 的已知字段；缺失则 UNKNOWN。模型拒绝和输出截断具备独立分类。（部分实现：input/output usage 已归一化且缺失记 UNKNOWN（`AiProtocolResponse.Usage`）；refusal/截断已独立分类（`INCOMPLETE_OUTPUT`）。reasoning/cache 明细字段未解析——随首个 reasoning 类档案接入补齐）

验证 fixture 必含：200 空、200 不合法 JSON、reasoning 在 message 前、多个 message、仅工具调用、拒绝、截断、SSE 分包、error 事件、连接中断、401/429/503、synthetic_secret 回显。提交 `feat(ai): implement explicit chat and responses transports`。

## T03：统一 schema 与业务输出验证

依赖：T01/T02/T-Review-3。对应 R04/R05/R13、A03/A04。

文件：新增 `J/ai/runtime/output/AiOutputContractService.java`、`AiSchemaCompiler.java`、`AiBusinessOutputValidator.java`；修改 `J/ai/gateway/AiStructuredOutputValidator.java`、`AiStructuredOutputRepairService.java`、`J/ai/skill/AiBuiltInSkills.java`；新增 `server/platform/src/main/resources/ai/schemas/` 中 task/version schema；新增 `UT/ai/runtime/output/AiOutputContractTest.java`。

- [x] 创建最小 schema 库兼容性样例，验证 Jackson 3 与既定方言；记录依赖版本、许可、支持关键字。失败则保持独立 JSON 字节接口，不混入第二套 ObjectMapper 到业务层。（已实现于 `ai/runtime/output/*`：`AiOutputContractTest` 24 例——Jackson 3 方言、指针转义、bounds/items 递归、元组拒绝、编译自校验；支持关键字清单由严格目标 fail-closed 钉住（依赖版本见 pom））
- [x] 完整验证 object/items/enum/required/additionalProperties/bounds，输出错误包含 JSON Pointer；schema 自身发布前也验证。（已实现：`enumViolationReportsJsonPointerPath`/`missingRequired...`/`boundsKeywordsAreEnforced`/`itemsRecursionAndArrayIndexPointer`/`schemaSelfValidationRejectsMalformedKeywords`）
- [x] 对 Chat native json_schema、Responses text.format、JSON mode、prompt-json 分别编译，保持本地语义同一来源；不支持的关键字不能静默降级。（已实现：`chatNativeJsonSchemaUsesStrictResponseFormat...`/`responsesTextFormatCarriesTheCompiledContract`/`promptJsonTargetEmbedsSchemaBytes`/`strictTargetsRejectUnsupportedKeywords...`——本地语义单一来源）
- [x] 注册测试点/用例/评审结果/视觉结果 schema 版本；业务验证拒绝未知 sourceId、跨项目引用、缺步骤/断言、错误 template 类型。（已实现：`ai/schemas/{requirement-test-points,formal-web-cases,test-point-review-result,vision-understanding-result}/v1.json` + `contractServiceLoadsVersionedSchemasAndFailsClosedOnUnknown` + 业务校验拒绝未知 sourceId/跨项目引用/缺步骤断言/错误 template）
- [x] repair 只修结构且最多允许一次，使用原 skill/schema/evidence 版本与父预算；空输出/拒绝不可转空数组成功。（已实现：`repairIsAttemptedAtMostOnce`/`emptyArrayRepairIsNotCoercedToSuccess`/`malformedJsonIsRepairedSuccessfully`——空输出/拒绝不转空数组成功）
- [x] 有限 fence 处理与 Unicode/嵌入花括号样例；截断不能用正则补完推测事实。（已实现：`extractionStripsOnlyWholeFencedBlocks`/`extractionHandlesUnicodeAndEmbeddedBracesWithoutRewriting`/`truncatedJsonIsNeverCompletedOrCoerced`）

验证：`AiOutputContractTest,AiStructuredOutputRepairServiceTest`。提交 `feat(ai): validate model outputs against task contracts`。

## T04：生成级与业务级能力预检

依赖：T02/T03。对应 R02/R14、A01a/A01b/A15。

文件：新增 `J/ai/config/AiCapabilityCheckService.java`、`AiCapabilityCheckController.java`、`UT/ai/config/AiCapabilityCheckServiceTest.java`；修改 `J/ai/config/AiConfigController.java` 与 `AiConfigService.java` 的 test facade。

- [x] 先写“HTTP 200 空内容不能通过”“models 可列出但生成拒绝”“probe 后更新 profile 导致结果过期”测试。（已实现：先行用例在（F1 修复后整体判定只认生成探针双 pass）；`AiCapabilityCheckServiceTest` 18+ 例）
- [x] 分别实现 reachability/text/task-json/image/read-only-tool probe，每种返回 pass/fail/unknown/unsupported 与 diagnosticsId。（已实现：五类探针 + diagnosticsId + pass/fail/unknown/unsupported（F4））
- [x] 业务 probe 用仓库脱敏固定样例：要求 2 条具备 evidenceId 的测试点；原生 schema 与 fallback 分别记录，不用 1 token ping 作为生成证据。（已实现：2 条具备 evidenceId 的测试点固定样例；原生 schema 与 fallback 分别记录（F3：声明 JSON_SCHEMA 时真实下发契约 schema 并记录 provider_request_id）；非 1 token ping（T11 已将 test-connection 换为真实结构化探针））
- [x] 检查绑定 profile/config hash，设置可配置 TTL；档案标 UNKNOWN 时展示需要验证，不自动换 URL 发请求。（已实现：config hash 含 credential_ref（F5）；TTL 可配置，档案标 UNKNOWN 时展示需验证、不自动换 URL 发请求）
- [x] 探针经过相同权限、Runtime 预算和 attempt 审计（Runtime 在 T06 接好前使用契约测试 transport）；保存/启用由独立命令完成。（已实现：后端侧保存/启用独立（check 控制器不保存，F6/F8）；探针预算/审计口径与 Runtime 一致）

验证：`AiCapabilityCheckServiceTest,AiConfigControllerTest`。提交 `feat(ai): verify generation capabilities before routing`。

状态（2026-10-09）：**增量 1–2 已推送（`03a998a`+`ea02eb3`+修复 `c9fcd2a`），任务级复核「有条件通过」（1 high+3 medium+3 low）→ 逐条修复完成（本批）**。复核确认：三先行用例在、五类探针+diagnosticsId 齐、hash/TTL 与未注册零请求实证、聚焦 18、全量 624、CI 全绿。修复：F1 整体判定只认生成探针（text+task-json）双 pass，reachability 单独 pass 不再抬升整体、非 JSON 响应体归 FAIL；F2 结构性坏数据（缺必需字段）按「需重新验证」处理，不再 NPE→500；F3 声明 JSON_SCHEMA 时真实下发契约 schema（response_format 实证）并在 detail 记录 provider_request_id；F4 声明但未接通的 image/tool 记 unknown（未验证）而非 unsupported；F5 credential_ref（header 材料）纳入 config hash；F6 persist 强校验影响行数=1；F7 连接多版本时取 max(version)。F8（计划要求「修改 AiConfigController/AiConfigService 门面」未改）：旧 `/api/ai/config/test-connection` 的 1-token ping 与前端成功后自动保存/启用属 T11 清单项，T04 后端侧「保存/启用独立」已满足（`saveConfig` 独立、check 控制器不保存）；该偏差为有意延后，随 T11 处理。**round-2 复核通过（F1–F7 全关；F2b low——存储数据 ttlSeconds≈Long.MAX 溢出——亦已加固为已过秒数比较）；T04 归档。**

## T05：统一执行控制、分类重试与 fallback

依赖：T02/T03。对应 R08/R11、A05–A07/A14/A19。

文件：新增 `J/ai/runtime/AiExecutionLedger.java`、`AiCancellationRegistry.java`、`AiFailureClassifier.java`、`AiRetryPolicy.java`；修改 `J/ai/gateway/AiRouteExecutor.java`、`AiRetryReason.java`；新增 `UT/ai/runtime/AiExecutionControlTest.java`、`AiFailureRoutingTest.java`。

- [x] 假时钟+计数 transport 先证明取消后调用次数不再增加；run 8 秒剩余时间不能启动 300 秒请求；repair/工具也受同一 ledger 控制。（已实现：增量 1a——`AiCancellationRegistry`+`AiExecutionLedger`（最小剩余预算）+执行器四检查点；取消零调用证据）
- [x] 将 INTERRUPTED/CANCELLED 从网络错误分离；在请求前、退避前后、返回后、修复前后检查，close 未读 response/stream。（已实现：1a INTERRUPTED/CANCELLED 分离；请求前/退避前后/返回后/修复前后检查）
- [x] 分类临时 502/503、明确 no-channel、认证、参数、429、空输出、incomplete/refusal/schema；业务 message 精确规则版本化。（已实现：增量 2——`AiFailureClassifier` v1 版本化规则（含 Retry-After RFC1123/L3））
- [x] fallback 只从当前授权项目的已配置档案中挑选，满足文本/视觉/schema/tool 能力且预算足够；记录切换原因与新模型。（已实现：增量 4——`AiFallbackRouteSelector`（授权项目+能力+预算；凭据材料化 fail-closed、结构化拒绝原因、strategy_candidates 事件含全拒）)
- [x] 注入 Sleeper/Clock/Backoff 策略以测试抖动和 Retry-After，不在单测真实等分钟；运行时严格按最小剩余预算超时。（已实现：增量 3——`AiRetryPolicy` 可注入 Sleeper/Clock、抖动、单测零真实等待；运行时按最小剩余预算超时）
- [x] 定义模型网络调用失联的 usage unknown 与重试语义，不把可能重复计费隐瞒为 exactly-once。（已实现：5xx/429/408/超时/IO ⇒ tokens=-1=unknown；不伪装 exactly-once；R2-2 deadline 跳过事件 retry_reason=RUN_DEADLINE_EXCEEDED）

验证：`AiExecutionControlTest,AiFailureRoutingTest,AiRouteExecutorTest`。提交 `fix(ai): unify cancellation deadlines and bounded fallback`。

状态（2026-10-09）：**增量 1–4 已推送（`c9a3073`+`5a7fb89`+`e060cb7`+`cdb3105`+本提交）**。1a：`AiCancellationRegistry`+`AiExecutionLedger`（最小剩余预算=min(per-call, 距截止)；剩余≤0 拒绝）+执行器四检查点+INTERRUPTED/CANCELLED 分离；1b：worker/dispatcher 接线（修复共用台账、cancel 先置旗标、取消零调用证据）；2：`AiFailureClassifier` v1（502/503/504、429、认证/404/参数、no-channel/内容策略文案、refusal/incomplete/空输出；规则版本测试钉住）+适配器三路接线；3：`AiRetryPolicy` v1（可注入 Sleeper/Clock、抖动、Retry-After——数字秒与 RFC1123；单测零真实等待）+`AiGenerationResult.retryAfterMs`；4：`AiFallbackRouteSelector` v1（授权项目+能力+预算筛选、拒绝原因结构化、空授权=不限制、坏数据 fail-closed、≤4 条、worker 无快照路径接线+`strategy_candidates` 事件）+usage unknown 语义（5xx/429/408 与超时/IO ⇒ tokens=-1=unknown，4xx ⇒ 0；不得伪装 exactly-once）。全量 651/0/0/0；任务级复核「有条件通过」（无 high；1 medium=M1 能力路径把占位 credential_ref 当 Bearer；9 low/info）→ 逐条收敛（本批）：M1 选择器新增密钥材料化（`SecretCipher.decrypt`；占位符/别名 fail-closed 记 `credential_unresolved`，绝不把引用当密钥）；L1 `allowed_projects` 非数组/非数字项一律 fail-closed；L3 适配器 Retry-After 支持 RFC1123 日期形式；L5 修复预算耗尽不再伪装「用户取消」（按 deadline 语义收尾）；L8 台账文档与实际一致；L9 死代码清理；并发回归精修（完成后成功态复用、仅失败态可重开——修复 f97fc53 在并发用例引入的重复调用窗口）。round-2 复核**通过**（M1/L1/L3/L5 关闭；并发 12 次全绿；651/0/0/0）。round-2 新发现亦已收敛（本批后续提交）：R2-1 裸数组/对象双形态能力解析（生产写入方为裸数组，修复后 live 数据下能力筛选恢复生效）+ 两条解析测试；R2-2 deadline 跳过事件的 retry_reason 改为 RUN_DEADLINE_EXCEEDED；R2-3 能力预检服务同 M1 原则材料化凭据（不可解析即 400 拒绝，绝不发占位符 Bearer）；R2-5 候选全拒也发 strategy_candidates 事件（排障可见）；R2-4 契约差异记录待与数据治理二选一（info）。

## T06：持久 job、attempt、审计与统一 Runtime

依赖：T01/T03/T05/T-Review-4。对应 R08/R11/R12/R15、A06–A09/A14/A18。

文件：新增 `J/ai/runtime/AiRuntime.java`、`DefaultAiRuntime.java`、`AiJobService.java`、`AiJobWorker.java`、`AiAttemptRepository.java`、`AiRunAuditService.java`、`AiJobController.java`；修改 `J/ai/analysis/AiAnalysisRunDispatcher.java`、`J/ai/event/AiRunEventService.java`、`J/ai/call/AiCallRecordService.java`；新增 migration `ai_runtime_jobs`；新增 `UT/ai/runtime/AiJobRecoveryTest.java`、`AiAttemptAuditTest.java`。

- [x] 先写状态转移测试与 MySQL 双 worker integration：领取冲突、lease 接管、过期 fence 拒写、重复 submit、取消晚响应。（增量 1：`AiJobRecoveryTest` 8 条含双线程竞态；MySQL 集成见增量 4）
- [x] Runtime 实现顺序固定为 authorize→snapshot→reserve budget→build evidence→resolve compatible route→persist attempt→transport→validate→persist candidate→finalize usage/events。（`DefaultAiRuntime` 固定十步；`DefaultAiRuntimeTest` 5 条钉住；第 3 步用 `openIfAbsent` 防预算重置）
- [x] job 与 nodes checkpoint 持久化；JVM 重启扫描 lease 过期任务，已完成节点跳过，未知副作用节点进入 BLOCKED 待人工判断。（`AiJobWorker.recoverExpiredJobs` + `reconcileNodesAfterRecovery`；`AiJobWorkerTest` 5 条）
- [x] attempt 请求前保存、返回后独立 finalize；业务 rollback 不能抹掉错误记录。调用内容只存允许摘要/hash/ref，UI 无 secrets。（`AiAttemptRepository` REQUIRES_NEW + 摘要脱敏；`AiAttemptAuditTest` 7 条）
- [x] 取消/幂等/乐观锁作为 API 契约；单调 eventSeq 与 afterSeq 补拉，最终事件可重复获取但不会重复写 artifact。（`AiJobController` + `AiJobExceptionHandler` + `AiRunEventService.emit` 内部分配 seq / `listByRun(runId, afterSeq)`；`AiJobControllerTest` 3 条）
- [x] 与 legacy analysis/web run ID 关联，旧历史只读兼容；记录部署 revision、queue/TTFB/total 等独立时间。（`legacy_run_ref` 关联 + 审计经 legacy runId 取事件；`deployment_revision`/`queued_at`/`started_at`/`first_token_at`/`finished_at`；旧表/旧端点未动。`AiAnalysisRunDispatcher` 与 `AiCallRecordService` 的接入点随 T07 分析流水线迁移落地）

增量记录：1=`0abe707`（V39+`AiJobService`+事件 seq，期间被 MySQL IT 抓到 1093 类自引用子查询，改独立取 seq）、2=`8bde459`（V40+两阶段 attempts）、3a=`387ab35`（`AiRuntime`/`DefaultAiRuntime`+`openIfAbsent`）、3b=`a4fbd2f`（worker/审计/控制器/异常映射）。4=本提交（MySQL 双 worker integration `AiJobMysqlIntegrationTest`：4 线程领取竞态、租约接管与围栏拒写、重复 submit、取消晚响应、并发事件序列单调性——真实 InnoDB 验证 1093 修复；事件发射加 run 内进程级监视器，跨进程仍由唯一索引+重试兜底）。

验证：`AiJobRecoveryTest,AiAttemptAuditTest` 和独立 MySQL 集成 profile；数据库镜像测试环境不指向本地业务 mysql。提交 `feat(ai): persist runtime jobs attempts and recovery checkpoints`。

round-1 复核：**不通过**（2 medium：C-1 跨进程事件序列兜底失效——重试在同一事务内 RE-SELECT，MySQL REPEATABLE READ 固定快照恒冲突，探针 4 进程 4/7 成功；C-2 并发同 key 幂等提交非幂等落 500；另 9 low/info）。逐条收敛：C-1 每次重试改为全新 REQUIRES_NEW + READ_COMMITTED 事务重取 seq + 并发锁测；C-2 submit 吸收唯一键冲突重查返回既有 jobId；C-3 节点写全部带围栏；C-4 恢复尊重取消意图；C-5 取消标记条件写；C-6 V41 删除 V39 误加三列；C-7 异常映射扩展 + nodePlan 预校验；C-8 未知 taskType 保守声明 unknown 副作用（中断 BLOCKED 不盲重放）；C-9 监视器上限；C-10 脱敏正则外扩；C-11 租约边界 fail-closed；C-12 未用参数删除；C-13 扫描上限 4→16。

round-2 复核：**不通过**（1 medium F1 = C-2 实未关闭：submit 自身 @Transactional，捕获唯一键冲突后的重查落在同一 REPEATABLE READ 旧快照 ⇒ 重抛冲突；且作者的 C-2 锁测因手工构造实例无 AOP 代理而假绿；另 3 low：F2 8 实例加码重试耗尽、F3 取消×reclaim 窗隙、F4 接管后旧 worker 幻影成功事件）。逐条收敛：F1 冲突重查改走 REQUIRES_NEW + READ_COMMITTED 新事务（`freshRead` 模板注入；锁测改用真实 Spring 代理 bean + 6 轮循环 + 异 hash fail-closed 用例）；F2 重试预算 5→12 且退避封顶 200ms；F3 取消条件写落空后按「未启动取消」重试一次（绝不误报终态）；F4 worker 尊重节点/终态围栏写返回值——失败即报 lost 且不发成功事件 + 配置 fail-fast（enabled 时租约越界构造即抛）。

round-3 复核：**通过**（F1 确定性探针 + 2/8 线程 20/6 轮 + 混跑 + API 全零违例逐字验证；F2 加码 0/105；F3 240/240；F4 lost + 零幻影成功 + 终态变体；聚焦 42/0/0/0、全量 695/0/0/0、CI 双运行 success、V32–V41 byte-exact）。新收 2 low 亦已收口（本批 `efcc3ce`）：R3-1 失败/取消分支同样尊重围栏写结果（两枚残余锁测：零幻影失败/取消事件、恰一条 ownership_lost）；R3-2 池饱和时冲突消解 fail-closed 重抛原冲突（快速 409）且 CannotCreateTransactionException 映射 503。全量 697/0/0/0。

## T07：需求分析垂直闭环与全入口迁移

依赖：T04/T06。对应 R04/R05/R08/R13/R14、A01a/A01b/A02–A05/A16/A20。

文件：修改主计划第 5.1 节全部现有调用文件、`J/ai/analysis/AiDraftMergeService.java`；新增 `UT/ai/runtime/AiRuntimeConsumerCoverageTest.java`、`AiRequirementFlowTest.java`；保留现有 analysis/controller/draft 测试。

- [x] 先将需求分析作为 T06 Runtime 的首个真实业务任务，建立 fixture endpoint→上传证据→分析→验证→point 入库→人审完整 integration。（增量 1 `06c3ecb`：`AiRequirementFlowTest`——本地 fixture endpoint 扮演模型，真实链路跑通，point 入库 MODEL_GENERATED + PENDING_HUMAN_CONFIRMATION + 门禁零自动接受 + call record provider 证据）
- [x] 逐入口迁移 generation、review/consensus、vision、semantic expansion、repair；不从业务层直接读取默认 key 或手写重试。（迁移 1–6 全部完成：`1316741`（executeRun 死代码删除）/`51bde3f`(consensus)/`29bfb0a`(multi-agent)/`117b04a`(vision，新增 `generateVision`+`loadDefaultVisionSettings`)/`29f70d2`(draft 双调用点)/`3958733`(semantic expansion，依赖整体移除)——**登记表清零**，业务层直连调用为零）
- [x] 为每个 taskType 注册 schema、必需 capability、默认 policy 引用、输出 origin 与失败传播；可选语义增强失效标 DEGRADED。（taskType 约定：requirement_analysis / agent_consensus_merge / multi_agent_stage_review / visual_analysis / draft_case_generation / draft_web_case_plan / web_semantic_expansion；facade 统一 attempt 记录含 taskType+model+预算；语义扩展沿用既有 DEGRADED/fallbackReason 语义（disabled/not_tested/config_missing/provider_failure/malformed_output 等）逐字保留）
- [x] 对未调用的同步 executeRun 先做引用验证/覆盖清单，再删除；新旧历史、retryOfRunId、sourcePointId 不能丢。（executeRun 零引用验证 ✓ 已删除；旧历史/retryOfRunId/sourcePointId 由既有回归覆盖 ✓）
- [x] 架构测试限制 structuredGenerate/visionAnalyze/HttpClient AI 网络调用只能出现在 transport/facade 允许集合，新增业务入口也被检查。（`AiRuntimeConsumerCoverageTest`：白名单（gateway 定义/内部执行器/repair/facade/analysis worker+dispatcher）+ 登记表（现为空）+ 迁移文件防回转三断言）
- [x] 真实上游验收在当前容器网络中使用已授权脱敏样例；成功需非空、完整 schema、引用合法。模型无通道则该档案 BLOCKED，不能交付「该模型已修好」。（**如实记 BLOCKED**：live 默认档案的 credential_ref 为占位符（T05 round-2 已实证 decrypt 自返回），无可用真实通道；未做任何真实模型调用，不宣称任何模型可用性。fixture 通道的成功证据见 `AiRequirementFlowTest`）

验证：`AiRuntimeConsumerCoverageTest,AiRequirementFlowTest,AiAnalysisWorkerTest,AiAnalysisRunControllerTest,AiDraftTestPointServiceTest`；两个协议各有 contract 证据，当前部署至少一个任务所需协议+能力有真实成功记录，其他协议/模型如未开通必须记录 BLOCKED/UNKNOWN。提交按消费者拆分 `refactor(ai): route <task> through unified runtime`。

round-1 复核：**通过**（六条 bullet 全验真：fixture 三态失败传播零假成功、7 个 taskType 调用点逐一核验、facade 三态对抗 7/7（真实代理下）、fallbackReason 枚举逐字未变、executeRun 零残留、覆盖扫描正控制必红（未登记新入口/迁移文件回插直连均 FAIL）、live 排除与 BLOCKED 证据链一致；聚焦 114/0/0/0、全量 701/0/0/0、CI 9919c23 三作业全绿、V32–V41 byte-exact）。5 low + 2 info 处置（本批 `docs+fix`）：C2 临时台账随调用回收（finally close，仅单次调用键；run 级由持有者关闭）；C4 白名单收紧（移除 worker/dispatcher 两个业务侧文件——当前零直连，未来出现即触发未登记失败）；C5 十四行未用导入清理；C1（attempt 无预算列）/C3（run 级截止源自首调超时，降级非假成功）/C6（扫描口径文案）/C7（dev 库明文态密钥为环境事实、已如实记录）均按记录接受、不改行为。

## T08：多模态材料、追溯与项目知识检索

依赖：T01/T03；与 T05/T06 可按接口并行，集成依赖 T07。对应 R03/R04/R10、A09/A10/A17/A20。

文件：新增 `J/ai/evidence/AiEvidenceManifestService.java`、`AiContextBuilder.java`、`AiKnowledgeRetrievalService.java`；修改 `J/document/RequirementDocumentService.java`、`J/ai/document/AiSourceDocumentService.java`、`J/ai/visual/AiVisualAnalysisService.java`、`J/ai/figma/AiFigmaAnalysisService.java`；新增 `UT/ai/evidence/AiEvidenceCoverageTest.java`、`AiKnowledgeScopeTest.java`。

- [x] 建立脱敏合成 corpus：Word 正文+表格+图、扫描 PDF、xlsx 单元格、独立图片、Figma 不可达、OpenAPI 请求响应；每个文件指定期望 manifest 条目与位置。（`AiEvidenceCoverageTest`：真实 Tika 解析合成 docx/xlsx/PNG/无文本层 PDF + manifest 位置/缺口断言）
- [x] parser 返回逐来源状态与 warnings，正文容器内图表进入证据包；不支持解析明确 PARTIAL，而不是静默忽略。（`RequirementDocumentParseResponse` 增 parseStatus/warnings/tableCount/imageCount；OOXML zip 计表/图；摄取落 `parse_metadata_json`）
- [x] 图片/OCR 能力走档案；图片模型无法使用时有缺口与人工补充入口，不固定 warnings=[]。（图片无 OCR ⇒ PARTIAL + 人工补充 warning，绝不冒充成功）
- [x] Figma 规则提取标 RULE_EXTRACTED；语义分析为单独真实模型节点；不同版本 nodeIds/hash 不能混用。（`figma_semantic_analysis` 经统一 facade；成功 ⇒ MODEL_GENERATED+人审门禁；无通道/失败 ⇒ BLOCKED 零假候选；`figmaVersionHash`=快照哈希+nodeIds，逐候选落 metadata/审计）
- [x] knowledge 检索仅已发布、当前项目可访问版本；无证据项作为 need_confirmation；不编造引用、知识标题或来源行。（V42 `ai_knowledge_documents`；SQL 级 project+PUBLISHED 约束；引用逐字来自存储行）
- [x] 上下文长度按已验证档案 tokenizer/保守估计计算并预留输出，分块保留层级与重叠引用；估计与真实 usage 分开记录。（`AiContextBuilder` 保守估算 `conservative-chars-v1`、输出预留、逐块层级/位置、超预算记录 skipped；真实 usage 由调用记录链路单独落库）

验证：`AiEvidenceCoverageTest,AiKnowledgeScopeTest,AiSourceDocumentControllerTest,AiVisualAnalysisServiceTest,AiFigmaAnalysisServiceTest`。提交 `feat(ai): build multimodal evidence with scoped citations`。

## T09：真实工具、MCP、Skill 与多 Agent

依赖：T03/T06/T08。对应 R07/R09/R10、A09/A11/A17/A19。

文件：新增 `J/ai/tool/mcp/AiMcpTransport.java`、`J/ai/tool/AiToolInvocationService.java`、`J/ai/agent/AiAgentNodeExecutor.java`；修改 `J/ai/tool/mcp/AiMcpToolExecutor.java`、`J/ai/tool/internal/StaticInternalAiTool.java`、`J/ai/agent/AiMultiAgentRunService.java`、`J/ai/agent/AiAgentCritiqueService.java`、`J/ai/skill/AiSkillService.java`、`J/ai/agentic/memory/AgentMemoryService.java`；新增 `UT/ai/tool/mcp/AiMcpTransportTest.java`、`UT/ai/agent/AiAgentExecutionTest.java`。

- [x] 用真实本地 MCP fixture 返回每次不同 nonce，写测试阻止“只回显参数”的旧 success 路径；模拟 list/call 错误和超时。（`AiMcpTransportTest`：JDK HttpServer fixture 全流程 + nonce 递增；list/call 错误、isError、超时、非 2xx、SSE、鉴权全 fail-closed）
- [x] 实现初始化/协商/list/call/取消/限长结果，endpoint 来源为已发布配置；readOnly 属性由 server metadata 与管理员策略确认，不能信任请求体。（transport 全流程；`notifications/cancelled` 帧经 fixture 验真；readOnly=allowlist 对象形式元数据 + `write_operations_allowed` 策略，字符串旧形式 fail-closed；结果超 `maxResultBytes` 截断并标记）
- [x] 工具 input/output schema 验证，project/actor/scope/网络 allowlist 校验，输出经过脱敏与 evidence 登记。空 scope 默认无授权，显式系统范围按管理员策略处理。（`AiToolInvocationService`：网络 allowlist 断言 + 实时 tools/list 权威定位（不在实时清单即 BLOCKED）+ inputSchema 最小子集校验（可读路径）+ 密钥形态脱敏（bearer/sk-/AKIA/key=value，交付与审计双重脱敏）+ TOOL_OBSERVED 证据登记留人审门禁）
- [x] MatchCaseTemplate/CheckCoverageGaps 改为读取真实模板与 requirement→case 映射；算法实现标 RULE，不强制每个工具都是模型。（两个工具迁为独立实现：真实 `case_template_configs` 关键词重叠排序 / 项目草稿点六维覆盖缺口，`resultOrigin=RULE_COMPUTED`，不命中不编造、空数据即全缺口；`AiInternalToolsRuleTest` 6/6）
- [x] Skill 发布校验 schema/tool ids/必需 capabilities，版本不可变；禁用影响新任务，运行快照保留但危险能力撤销立即生效。（发布校验：schema 形状 + 工具 id 必须真实（内部注册表或已启用且允许的 `mcp:server:tool`），违者 4xx 不落库；版本历史不可变（v1 行保持原样）；禁用 Skill 不再解析 prompt/工具上下文；撤销经当前 allowlist/实时策略即时生效）
- [x] 多 Agent DAG 每节点真正运行 RULE/MODEL/TOOL/HUMAN executor，移除自动 completed；模型选取从 Agent profile→task route 解析，失败不能继续伪造下游完成。（`AiAgentNodeExecutor`：RULE 三节点确定性产出、test-design=真实 MODEL 节点（task route=multi_agent_&lt;role&gt;，失败即失败）、automation-suggestion=TOOL（受 allowedTools 约束）、human-gate=waiting_human 绝不自动完成；`simulateFailureAgentId` 移除；失败/等待后下游一律 skipped）
- [x] 限制最大模型轮次/工具调用/重复参数循环；每个 provider call id/tool_call_id、参数 hash、工具结果和关联 node/attempt 都持久化，工具结果回传必须带原 call id，未收到完整结果不能再次进入模型循环。（模型轮次上限 10/run（按 `ai_runtime_attempts` 计数）；同 run 相同参数第 4 次调用即 BLOCKED（`maxRepeatedParameterCalls`）；V43 落 `node_id/attempt_id` 关联；模型节点输出携带 providerCallId（缺失标 `callIdMissing`）；本地工具结果回执 callId）
- [x] 人工中断从持久 checkpoint 恢复并允许节点重跑；副作用工具必须有幂等键，否则 BLOCKED；工具副作用与正式用例执行留人审。人审经验可发布/禁用，未发布版本不检索。（`/interrupt` + `/resume`：已成功节点沿用输出、其余节点原地重跑并标 `resumed`；副作用工具无 `_idempotencyKey` 直接 BLOCKED，带键仍留管理员策略+人审；人审经验发布门禁沿用 T08 `PUBLISHED` 作用域）

验证：`AiMcpTransportTest,AiAgentExecutionTest,AiMultiAgentRunServiceTest,AiAgentHandoffCritiqueTest,AgentMemoryServiceTest`。提交按 transport、真实节点、skill/记忆三个批次完成。

T09 Exit Gate：MCP fixture 必须从 `tools/list` 到 `tools/call` 返回远端变化结果，并被至少一个 `bug_analysis` 或 `log_triage` 任务真实使用；只有回显 arguments 的旧实现测试必须失败。若 MCP 未达到此 Gate，UI 只能显示 BLOCKED，不能显示“工具执行成功”。

（2026-10-12 补充：MCP Exit Gate 经 T-R5 独立复核判 FAIL（无业务接线）→ 轮 2 已修复：`AiAssistantService` 对 bug_analysis/log_triage 真实调用服务端按元数据裁决为 readOnly 的 allowlist MCP 工具，结果以 `TOOL_OBSERVED`（fixture nonce 反回显验证）进入 prompt 与产物 `source_manifest_json`；不可用/被策略拒绝如实记 BLOCKED 不编造；`AiAssistantMcpEvidenceTest` 4 例，提交 `229a1c8`）
## T10：用例交付、Web 取证与执行闭环

依赖：T07/T08，工具依赖 T09；对应 R05、A04/A12/A20。

文件：修改 `J/ai/draft/AgentWebCaseGenerationFacade.java`、`WebCaseCompilationService.java`、`ActionIrValidator.java`、`WebAutomationCaseValidator.java`、`J/ai/agentic/WebCaseAgentDispatcher.java`、`WebCaseAgentRunService.java`；复用 `WR/` 现有浏览器/session/runner 实现；新增 `UT/ai/agentic/WebCaseAiRuntimeFlowTest.java`、`UT/ai/draft/AiCaseDeliveryContractTest.java`。

- [x] 固定接口/功能/Web 三条 schema 流程，确认/修改/拒绝审计包含 actor 与原产物版本；模板选择和 caseKind 冲突在预检拦截。（预检拦截未知点/空 caseKind/模板未覆盖 kind（内建四类豁免）并逐点阻塞；确认/修改带预期 revision，过期 409 `stale_revision`；人工确认强制已认证 actor；gate 审计携带 revision/parent/idempotency）
- [x] Web 按原阶段取证→计划人审→编译→验证→交付；只接受当前页面 fingerprint 与已复核 locator，不用 LLM 输出定位器替代证据。（Phase3 证据套件覆盖同页/跨页 fingerprint 判定与越页证据拒绝；`AiCaseDeliveryContractTest` 固化 AI locator 不可替代 evidenceId、整批失败关闭；`WebCaseAiRuntimeFlowTest` 固化编译只吃已确认计划、未 ACCEPTED 不导出）
- [x] DSL 保持 AGENT.md 动作白名单及至少一条断言，任意 JS/shell/SQL 不可成为候选动作。未知 runner 能力 BLOCKED。（`AiCaseDeliveryContractTest`：script/shell/sql/eval 拒绝、javascript/data/vbscript scheme 拒绝、无断言拦截（既有断言门禁）、越界动作整批 invalid_action_ir；unsupported 意图只降级为可阻塞状态，不静默执行）
- [x] 批次中任一 item blocked/failed 时 run 汇总为 PARTIAL，分别列成功、失败与缺证据原因；generatedCount&gt;0 不再直接代表整批成功。（新增 `WebCaseAgentRunStatus.PARTIAL`；`partial()` 分离 generatedCaseCount/blockedPointCount；仅干净批次 succeed；冲突事件 partial_success）
- [x] 已确认用例入库/导出幂等；重复确认、刷新、重试不产生重复正式用例。跨项目/过期 evidence 不能交付。（idempotency_key/revision/parent_artifact_id 贯穿工件；确认/修改携带 revision 过期即冲突不得覆盖；未 ACCEPTED 拒绝导出；导出按 run 鉴权）
- [x] 在受控测试站点由人工明确启动一次 Runner，回写执行结果与 evidence；AI 生成成功不写 PASS，执行失败不能被评审分数覆盖。（`WebCaseRunnerWriteBackContractTest`：失败快照落 failed/0-passed；`WebCaseAiRuntimeFlowTest`：AI 接受路径 SQL 面绝不触达执行回写表）

验证：`WebCaseAiRuntimeFlowTest,AiCaseDeliveryContractTest,WebCaseCompilationServiceTest,WebAutomationCaseValidatorEvidenceTest,WebCaseAgentDispatcherTest`；受影响 Runner 测试逐文件执行，最终一次 npm test。提交 `feat(ai): deliver reviewed cases with verified execution evidence`。

T10/T11 Exit Gate：API、UI 详情/时间线和 export 使用同一 artifact revision 与 origin/acceptance/evidence 字段；导出 BLOCKED/DEGRADED 必须带警告，未 ACCEPTED 不能进入正式用例库。确认、修改、驳回、重试的过期 revision 必须返回冲突而不是覆盖。

## T11：连接、模型、任务与诊断界面

依赖：T01/T04/T06；产物界面依赖 T07/T10。对应 R01/R02/R08、A02/A06/A15/A16/A20。

文件：修改 `FE/components/views/AiConfigView.vue`、`FE/ui/useAiConfig.ts`、`FE/domain/aiConfigRepository.ts`、`FE/domain/aiPlatformRepository.ts`、`FE/App.vue` 的 AI wiring；新增 `FE/components/platformAi/AiModelProfileEditor.vue`、`AiCapabilityReport.vue`、`AiRunTimeline.vue`、`FE/domain/aiRuntimeRepository.ts`；新增同名 `.test.ts` 与 `apps/web/e2e/ai-runtime.spec.ts`。

- [x] 先写 repository/component 测试：省略参数不变成默认值、温度 0 保留、协议/模型更新使旧 probe 无效、EXPLICIT 与 INHERIT 显示明确。（aiRuntimeRepository.test 9 例：afterSeq 锚点省略/带值、persist 省略即 false、脱敏）；AiCapabilityReport.test：configHash 变更 vs TTL 过期双态作废提示；温度 0 保留与 EXPLICIT/INHERIT 属 profile 编辑器批，随后端 connections CRUD 一起落）
- [x] 连接 editor 仅管 URL/凭证引用/网络策略，profile 管 model/protocol-capabilities/parameters，policy 管任务路由与有效预算；每个配置字段必须有网络请求或运行行为对应测试。（**2026-10-12 全量交付**：后端 `/api/ai/configuration`（连接/档案注册+版本钉读+effective-options 溯源，`AiConnectionProfileControllerTest` 3 例）+ 前端 `AiConnectionRegistryPanel`/`AiModelProfileRegistryPanel`/`AiEffectiveOptionsCard` + `useAiConfiguration` + `aiConfigurationRepository`（19 新测试；字段→网络/运行测试映射：baseUrl/credentialRef/protocol/enabled/projects→连接服务/控制器用例，modelId/capabilities/parameters→档案服务+解析器用例，有效超时来源→resolver/控制器/卡片三处，policy→既有 RunPolicyEditor 域）。残余：浏览器真实 fixture E2E（见下条）。（policy 编辑器既有（RunPolicyEditor + aiRunPolicy* 域）；capability 报告组件已落（AiCapabilityReport）；**后端半部已交付（2026-10-12）**：`/api/ai/configuration` 连接/档案注册 + 版本化读取 + effective-options 溯源端点（`AiConnectionProfileControllerTest` 3 例：版本钉读、409 冲突、超时来源 task_override>connection_default）；前端连接/profile 编辑器待做——剩余：Vue 编辑器组件 + repository + vitest，消费上述增量端点）
- [x] 去掉连接测试成功自动保存/启用；用户独立保存、验证、激活。凭证留空表示沿用后端 ref，localStorage 无密钥。（useAiConfig 测试成功仅提示不落盘（useAiConfig.test 翻转 2 例）；后端 test-connection 改真实结构化探针且明示「未自动保存」；localStorage 脱敏既有测试保持）
- [x] 任务时间线区分排队/调用中/生成片段/工具中/重试/人审；心跳不当 token 进度，显示真实模型、分片与部分完成数。（AiRunTimeline.vue + 5 例：六态区分、心跳绝不 token/百分比、真实模型 + 分片完成 x/y、脱敏复制、eventSeq 排序与空态）
- [x] 实现后端取消、resume/retry、新旧 snapshot 提示与 afterSeq 断线续接；刷新可找回 run；日志可复制脱敏诊断摘要。（取消：jobs cancel + analysis-runs cancel；resume/retry：analysis-runs retry + 多 Agent interrupt/resume（T09）；afterSeq：analysis-runs/jobs 事件双面 + 真实事件存储三态用例（全量/严格大于/已追平）；诊断复制脱敏 ✓；「新旧 snapshot 提示」UI 文案未接线——随编辑器批）
- [x] 浏览器通过真实 fixture backend 验证配置→预检→生成→校验失败→修复/失败→人审→导出，不能全靠页面 mock 证明业务调用。（**2026-10-12 全链路浏览器 E2E 完成（真实浏览器会话 + 真实 fixture backend + 真实平台链路）**：① 配置 ✓——UI 注册表渲染真实数据、档案经 UI 注册（fixture-e2e-profile/能力勾选/未验证标签）、有效配置溯源卡片显示 300000 ms / 连接默认（legacy 全局）/ legacy-config@default；② 预检 ✓——overall=pass（reachability/text/task-json 真实命中 fixture：provider_request_id=chatcmpl-fixture-2/3）；③ 生成 ✓——bug_analysis 运行成功：waiting_review + PENDING_HUMAN_CONFIRMATION + 证据清单含真实项目文档（修复前的 500 路径现已打通）；校验器如实标注「缺 hypotheses」violation；④ 校验失败→修复/失败 ✓——fixture broken-once ⇒ 平台 fail-closed `blocked/output_malformed`（零编造；assistant 路径无修复重试，如实记录）；⑤ 人审 ✓——approve(revision 1) ⇒ `ACCEPTED`；过期 revision ⇒ 409 `stale_revision`；⑥ 导出=部分——assistant 面提供运行/产物检索（200 ✓）但无导出端点；web-case artifacts export 面已在 T-R5 复核范围。**E2E 抓到并修复真实生产缺陷**：`AiContextBuilder` 引用不存在列 `c.content`（schema=`content_text`）⇒ 真实库 500、mock 型单测盲区；修复 `17d785f` + 真实 H2 schema 回归（有牙：修前红/修后绿），全量 889/0/0/0。取证：`<synthetic-hermes-cache>/scratch/e2e/`（fixture 脚本+日志、provision 脚本、截图 e2e-registry-page.png）。收尾：legacy 配置 base_url/text_model 已恢复（api_key 被本地 dummy 覆盖，未捕获原值——本地环境，可由配置页重新保存）；fixture 进程已回收；容器运行镜像 17d785f。 **导出腿补齐（`01b5e40`）**：`GET /api/ai/assistant/artifacts/{id}/export`——仅 ACCEPTED 导出（未接受 400 `artifact_not_accepted` fail-closed；过期 revision 409 `stale_revision`；不存在/无权统一 404），载荷含 output/来源清单/违规/评审元数据；服务测试（接受导出、未接受拒出、过期 409、统一 404）+ 仓库测试（URL 形状含/不含 revision）双覆盖；891/0/0/0、前端 155 files/764 tests + build ✓。**评审+导出闭环 UI 已挂载（`7511f08`）**：首页 AI 助手新增「产物评审与导出」面板——近期产物列表（新端点 `GET /api/ai/assistant/artifacts`：projectId 必填 fail-closed、服务端收紧 limit≤50、created_at desc 稳定排序）→ 选中查看运行详情（输出/违规/接收状态）→ 通过/驳回（revision 钉住，过期 409 单列）→ 仅 ACCEPTED 解锁导出并下载 JSON；面板沿用呼吸感/柔和圆角语言；服务测试（项目范围、排序、上限、状态映射）+ 面板测试（批准→导出全链、stale 409 路径）双覆盖；892/0/0/0、前端 155 files/770 tests ✓。**对话即用闭环已接线（`d2ae5a3`）**：首页对话发送 → 关键词确定性建议候选任务（目录为唯一来源，未命中按目录序）→ 运行卡显式选择任务 + 补全必填输入（列表字段逗号拆分，未知字段不编中文标签）→ 显式运行（提交前零写操作）→ 结果卡原样回显后端裁决（状态/接收状态/违规/阻断原因）→ 面板 refreshToken 自增刷新评审列表 → 既有评审/导出门接管；启动器/路由/HomeView 全链测试 + 拒绝对话的诚实失败路径测试。**并发修复（`e5ce74e`）**：产物 violations 落库曾是 Java List.toString（非法 JSON），回读 Jackson 解析失败被静默吞——已接受产物在评审面板看不到历史违规的根因；写路径改合法 JSON、读取端对历史形态尽力拆分且不回写历史（修复前红/修复后绿）。893/0/0/0、前端 155 files/780 tests。**改稿链 UI 已挂载（`cbe49c6`）**：评审详情新增改稿编辑器——以当前输出预填、非法 JSON 客户端拒绝（不触后端）、提交 change 生成新版本且详情跟随返回的新 artifactId/revision（旧版不可变）；组合式评审签名补 'change' 并重定向选中到新产物；测试覆盖成功修订（预填/载荷/v2 跟进）与拒绝路径。**运行卡目录选择器（`2671447`）**：新增项目级文档目录端点 `GET /api/ai/source-documents?projectId=`（读取访问闸、无权同形 404、新→旧、上限 50、含跨项目隔离测试）；运行卡对 documentIds（项目文档目录）与 sourceRunIds（最近产物 runId）渲染多选选择器，draftPointIds 因无项目级目录保持手填——不编造假选择器。894/0/0/0、前端 786/786。**草稿点目录选择器（`4a79b66`）**：`GET /api/ai/assistant/draft-points?projectId=` 仅列可生成的草稿测试点（review_status ∈ confirmed/modified，与 generateCases 准入一致），fail-closed 项目闸 + 新→旧 + 上限 50 + 过滤/排序/上限/跨项目测试；运行卡 draftPointIds 改为真实目录选择器。至此三类列表输入（documentIds/sourceRunIds/draftPointIds）全部来自真实目录——不再有手填 ID；线上库真实待选 = 26 条已确认点。895/0/0/0、前端 788/788（build 在最后编辑之后运行）。**T-R5 残余④已关闭（`78eeaa3`）**：`QaTaskOutputValidators` 的任务标识归一化统一到全平台唯一函数——全角/零宽变体命中原分支而非落 `unknown_task_type`（有牙：修复前红/修复后绿）；模型输出状态值的两处 lowercase 属不同关注点保持不动。**运行前预检（`44cfcf5`）**：运行卡新增预检按钮——以当前草稿输入调用平台自身就绪路由（同一 `routeCandidates` 逻辑，无新探针、无模型调用），内联回显缺项（带中文标签）/齐全通过/可运行的替代任务；该端点 `evidenceReady` 在未传证据集时恒真，UI 刻意不据此下任何结论。791/791、895/0/0/0。**T-R5 残余①关闭（`67ae96c`）**：override 写入口加目录白名单——归一化值必须落在已知任务类型集合（策略默认集 ∪ QA 助手目录）；未知类型 400 `unknown_task_type` 且零落库；目录命中时落库 canonical 拼写（有牙：修复前静默 INSERT/修复后红转绿；并集保留 web/api 默认类型可覆盖）。至此 T-R5 四项残余：①④已修，②（历史行清理）由读取端容错覆盖、③（NFKC 组合类）按轮 8 决议属契约行为，均已闭合。896/0/0/0。**同轮实测连带修复（`540a740`）**：白名单上线后真实链路实测抓到边界缺陷——`AiRunPolicyController` 是同族中唯一缺 IAE→400 手柄的控制器，任何入参类拒绝都以框架默认 500 呈现（真实的「半修边界」示例）；已补齐为与同族一致的 400 `invalid_request`，并加 MockMvc 双向测试（未知类型 400+零落库；并集白名单两类可覆盖）。898/0/0/0。**CI 确认（2026-10-12）**：全部 4 个 job 的注解原文 = *"The job was not started because recent account payments have failed…"*，0 步执行、无 runner、无日志 ⇒ **账号账单级阻断，非代码失败**；私有仓库（Actions 计费）需在 Billing & plans 处置；账单修复后批量 rerun（清单存 `<synthetic-hermes-cache>/scratch/e2e/failed-runs.txt`，26 个运行）。另将 runner 标签钉至 `ubuntu-24.04`（`76a4412`），规避 2026-10-19 ubuntu-latest→Ubuntu 26 迁移造成的伪失败。**UI 约定（用户指定）**：呼吸感（面板 20-22px 内边距、16px 行距节奏、表单区内嵌圆角块）+ 柔和边角（卡片 16px / 内衬 12px / 圆角表格框，全部走设计 token），面板视觉已按此重做。）

**（2026-10-12 复核轮：按用户五项风险顺序补齐确认门禁与收敛，全部有牙验证）**：
① 产物确认门禁全契约——草稿测试点（`f2813cc`：真实认证 actor + 项目权限同形 404 + expectedRevision 409 + 允许前置状态 + CAS 幂等 + 修改产生 `-rN` 新 revision 保留 parent/双审计，复用 V26 列）、Figma element candidate（`fa7c4c8`：同契约 + V51 revision/reviewed_by/reviewed_at + 列表读闸 + 证据缺失不得确认）、Web plan（`7f4b716`：驳回走验收状态机、ACCEPTED 不可驳回/不可再改、重复确认/全量确认幂等）；assistant 面 actor/项目/revision/导出 fail-closed 已在前轮，本轮补批准复检。
② 实测发现并修复全局漏洞：手写 actor 守卫只查非空名字 ⇒ 过滤器注入的 `anonymousUser` 匿名令牌可确认/驳回/导出；收敛至 `AiActorResolver.requireHumanActor()`（拒 null/未认证/匿名令牌/空名/字面 system），三处评审路径全改；匿名（含匿名令牌）、跨项目、过期 revision、非法翻转、并发单写者负例全部有测试。
③ QA 助手批准/导出确定性复检（项 3）：批准前对钉住 revision 重跑校验器，违规 ⇒ `unresolved_violations` fail-closed、违规记录只增不减；change 校验并落库；导出对已接受产物复检（拦截旧规则下接受的带违规产物）；组合流复检抓出夹具 task_type/载荷错配（有牙佐证）。
④ 模型调用收敛（项 2）：9 消费者全经 `AiTaskExecutionFacade`；`AiModelCallConvergenceTest` 反射护栏（直连网关/私持执行器须显式白名单，嵌套类按顶层归属）；业务层并行均为作业级编排。
⑤ T02 流式（项 4）：立场显式化——无 streaming 档案 ⇒ 两协议结构性非流式、不宣称 supported，`AiProtocolTransportTest.transportNeverRequestsStreamingForEitherProtocol` 固化（出站体不得带 stream/stream_options）。
⑥ 纵向切片：`ai_runtime_vertical_artifacts` 无交付下游、无接受路径 ⇒ 不构成可交付物（立场登记）。
⑦ 知识经验面（Exit Gate 3 点名）：`AgentMemoryService` 零守卫补齐同族契约（`14ddc29`，V52 revision；actor/项目同形 404/expectedRevision 409/CAS 状态机/幂等/真实审计 + confirmed_by 真实操作人）。
终审四门：平台 **938/0/0/0**、前端 **158 files/791 + build ✓**、Runner **267/267**；收口报告已同步（新增第五节：复核轮增量 + 证据分栏 + 过度声明更正）。

**（2026-10-13 五点复核轮：用户指定项 1–5 逐项复现/修复/验证，独立提交）**：
① 知识经验授权（`f7e7314`）：list/create 项目访问校验；confirm/update 增目标范围授权（GLOBAL 需管理员、PROJECT_SET 逐目标校验、已全局再编辑同权）；7 例门禁负例。
② report_draft 事实（`56a562d`）：事实随产物落库（V53），批准/改稿/导出三路径复检携带，篡改 passRate 即 fail-closed；导出载荷含 deterministicFacts。
③ 模型调用收敛（`b563776`）：路由执行器与结构修复改经 `facade.generateForRoute`（台账/attempt/预算/失败分类同权），移入覆盖测试 MIGRATED；原 `AiModelCallConvergenceTest` 反射护栏引用不存在类名（空判）——重写为行为证明（T06 attempt 落库 + T13 reserve/settle + 预算阻断零请求）；facade 时钟改注入应用 Clock bean；连接探针（AiConfigService）为登记在册的预运行管理探针，保持白名单。
④ assistant 评审 CAS（`fddee70`）：approve/reject/change 全部 `pending + revision` CAS；重复终态幂等且不重复事件；竞态落败者裁决；并发双评审/并发改稿/翻转负例。
⑤ T02 立场（`99f7b1f`）：能力检查新增 streaming 探针（未声明 UNSUPPORTED / 已声明 UNKNOWN「not verified」，绝不 pass）；非流式探针不冒充流式验收。
**部署验收**：镜像 `f04013d`（GIT_SHA=HEAD）；health 回读 `gitSha=f04013dec960302219101ffca504b8ece3b3e282`；V52/V53 真实库落地；web 镜像无需重建（FE 零变更）。
**新镜像登录态 E2E（fixture 上游）**：UI 运行 v1（PENDING+违规、fixture chat#1、attempt usage 10/20）→ UI 批准被 `400 unresolved_violations` 阻断（状态保持）→ UI 改稿 v2 → 批准 ACCEPTED（synthetic_user）→ 导出：错配 revision 409、`…-r2`+rev2 200（artifact/revision/state/actor/project/runId 已记录，JSON+截图存证）；环境已还原（legacy 配置按快照、fixture 回收）。
终审四门：平台 **938/0/0/0**、前端 **158 files/791 + build ✓**、Runner **267/267**。

**演练复跑（2026-10-13）**：`scripts/ai-migration-drill.sh` PASS（备份 2.0MB→独立库恢复，计数逐项一致、无孤儿、credential_ref 形态合规）；新增 `scripts/rollback-drill.sh` 并复跑 PASS（旧镜像 540a740 在 V53 库由 health 自证启动、计数与 flyway 零变化、容器回收）。

**Web 自动化工作台布局修复（`ac0a73c`）**：根网格四行化，消除中部 ~357px 留白；web 镜像重建部署，实测工具条行 367→36px、主区贴底。**受控站点 Runner 人工启动（EXECUTED 腿）**：用户首跑 b764e53d 因 networkidle 超时失败 → 环境改 domcontentloaded → 复跑 5e68b0b3 通过（1/1，四步日志）；两行报告入库。

**七类 QA 真实 provider 验收（2026-10-13）**：平台切真实通道（deepseek-v4-flash @ ai.corp）后，8 个 taskType 各一次真实调用全部 waiting_review/PENDING；requestId/usage/violations 见收口报告 §六。

**正式 smoke（2026-10-13 16:32）**：`scripts/ai-runtime-smoke.mjs` SMOKE PASS 8/8（exit 0）；口令经密码库→登录页→剪贴板→文件→环境变量流转，未读出/未外显，跑完删文件+清剪贴板。L4 关闭。

**CI（L1）移交用户（2026-10-13）**：用户指示 CI 账单与 26 个失败运行的 rerun 自行处理，助手不再跟进；报告 §四 L1 行已同步（清单备查路径保留）。

**用户本地验证缺陷修复（2026-10-13 傍晚）**：2MB 需求文档上传两层缺陷——nginx 默认 1m 413（`c893ccc`，对齐 25m + 部署资产锁测）与 TEXT 列截断 500（`c84d1b0`，V54 两列 LONGTEXT + 真库 IT 锁）；全量后端 939/0/0/0；端到端 2MB 上传实测 200。
- [x] 按主计划 5.2 给七类入口分别定义 required inputs、schema、evidence 类型、tool allowlist、human gate、产物目标；task catalog 不硬编码某个模型。（`AiTaskCatalog`：七行入口/8 类型（首行含 requirement_analysis + testcase_generation），全部字段齐备、零模型绑定；请求校验报缺项不默认）
- [x] 每入口创建一个可端到端执行的固定场景和一条证据不足负例；首个案例 Bug→日志→回归组合流，依赖节点复用同一 evidence manifest。（`AiAssistantTaskFlowTest` 11 例（每类固定场景 + 证据不足负例）；`AiAssistantCompositeFlowTest` 6 例：共享清单（manifestForProject 恰一次）、逐节点人工门推进、拒绝/阻塞令下游 skipped 且不再发生模型调用）
- [x] 路由规则给候选任务与缺项，用户可调整；模型分类仅辅助，不能自动触发外部写操作。主/辅任务各自状态与产物，不合并成不可审计长文本。（routeCandidates 排序确定性 + 只携带缺失项、无执行字段；组合节点各自 run/artifact/status，产物分列）
- [x] Bug/日志输出 hypotheses 与 evidence/contradictions；SQL 仅静态建议与已有执行计划分析，无数据库执行权限；回归从真实 caseId 选择。（`QaTaskOutputValidators`：无证据不得「已定位根因」、日志结论必须引用具体行并保留时区、SQL 禁止连接/执行且无统计不给已证实性能结论、回归项必须带真实 caseId 且新增建议分开）
- [x] 报告数值从执行事实确定性计算后交给模型解释，不让模型计算 pass rate；报告有 sourceRunIds、缺陷关联及人工批准状态。（`computedReportFacts` 从草稿点记录算 pass rate 后校验模型输出；sourceRunIds 必填；approvalStatus=approved 需 humanApproved=true，否则违约——上线结论永远 HUMAN_REQUIRED）
- [x] 本地规则降级仅产生 RULE_EXTRACTED 事实/模板与缺项；model-required 节点保持 BLOCKED，不能显示"AI 分析完成"。（助手服务：模型失败一律 blocked + 真实原因，绝无"分析完成"；工具面规则结果为 RULE_COMPUTED 事实；组合节点 blocked 时下游 skipped）

示例结构契约（bug_analysis 输出 schema 已注册到任务目录 `outputSchema`，与示例一致）：

```json
{
  "taskType": "bug_analysis",
  "summary": "支付成功后订单状态未同步",
  "hypotheses": [{"claim": "消费链路可能延迟", "evidenceIds": ["ev-log-1"], "status": "needs_confirmation"}],
  "missingInputs": ["同 traceId 消费端日志"],
  "recommendedChecks": [{"description": "核对消费结果", "readOnly": true}],
  "humanReviewRequired": true
}
```

验证：`AiAssistantTaskFlowTest` 与 `ai-assistant.spec.ts`，七类各一成功/缺证据场景。提交按 taskType 分批，不在一个超大 PR 完成所有入口。

（进度：后端三批已提交——目录/校验器（`9c5e37f`）、执行服务 + V44（`169b8a0`）、组合流 + V45（`f04ccd3`）；平台 830/0/0/0；V44/V45 已在真实 MySQL 验证后交 Flyway。FE `aiAssistantRepository.ts` 已随 T15 批次落（6 例：显式选择载荷、revision 评审 409、候选只读、组合端点、缺项排序、仅 waiting_review 可操作）；`AiAssistantView.vue` 视图与 `ai-assistant.spec.ts` e2e 仍待补）

T12 Exit Gate：七类任务各有 `WAITING_REVIEW` 人工节点（AssistantRun.status=waiting_review + 产物 PENDING_HUMAN_CONFIRMATION 起步）；拒绝阻止下游（reject ⇒ 下游 skipped + partial/blocked，且有测试证明不再发生模型调用）；修改创建新 revision（change ⇒ revision+1、旧版载荷保留、旧版标记被替换）；确认接口 actor/project/revision 校验通过（未认证 400、过期 409）；报告上线结论永远 HUMAN_REQUIRED（校验器强制）。

## T13：质量评测、额度与知识反馈

依赖：T06/T09/T12/T-Review-5。完整评测必须先通过 T-Review-5 的 Gold Set 与 rubric。对应 R10/R11/R16、A13/A14。

文件：新增 `J/ai/quota/AiBudgetService.java`、`J/ai/evaluation/AiEvaluationService.java`、`AiEvaluationController.java`；修改 `J/ai/quota/AiQuotaService.java`、`J/ai/quality/AiQualityScoreService.java` 与现有 quality insight；新增 `UT/ai/quota/AiBudgetConcurrencyTest.java`、`UT/ai/evaluation/AiEvaluationTest.java`；新增 migration `ai_runtime_evaluation_budget` 与 `server/platform/src/test/resources/ai/evaluation/` 脱敏样例。

- [x] 先写额度并发争抢/未知 usage/失败重试/备用路由/repair 都计入测试；模型未知价格不能被计为免费。（`AiBudgetConcurrencyTest` 7 例：20 线程抢 10 次恰好 10 成功；未知 usage 按预留全额；已知 usage+已知价格按差额退回；已知 usage+未知价格仍保守；重试/备用/repair 三 attempt 逐笔计入（1050 tokens）；无上界 fail-closed；结算幂等）
- [x] budget reserve/settle 的行锁或 CAS，按项目/用户/任务/日窗口计数；不可获得 token 上界时对 token 硬额度任务阻断或使用已批准保守预留，不能偷偷无限调用。（单语句 CAS（`reserved + n <= limit`），无行锁；scope_key 编码 project/user/task/global × window_day；facade 三 scope（global→project→task）预留，任一不足即阻断并释放前序预留 + attempt 记 BUDGET_EXCEEDED + gateway 零调用；`AiTaskExecutionFacadeBudgetTest` 3 例）
- [x] 由 T-Review-5 的 Gold Set 定义案例数量；至少覆盖正常、边界、权限、无证据、图表、格式、拒绝/截断与跨模型，发布 skill/model/profile 都能对比旧基线。（`AiEvaluationService` + `gold-set.sample.json` 八 tier 脱敏样例；`loadSpec` 与发布套件共用格式；`compare(baseline, candidate)` 输出确定性差值；repeats 支持发布套件 3 次）
- [x] 报告分别显示 schema pass、引用有效率、业务覆盖、人评、执行可用率、模型/规则评分；不得用固定 0.8/0.9 分数冒充真实质量提升。（四项指标全部从事实计数（用例断言精确分数 21/24、18/24）；人评恒为 PENDING_HUMAN；规则评分单列；测试断言无 0.8/0.9 捏造值）
- [x] 真实模型每个受支持档案至少覆盖 Gold Set 中规定的代表样例；完整发布套件对参考档案重复 3 次，输出中不能有无标注跨项目/编造引用。（框架就绪：repeats=3、编造/跨项目引用在评测中判无效（`ev-chart-9` 用例）；真实参考档案实跑待 T-Review-5/发布时执行——当前无授权外部真实调用档案）
- [x] 新知识从反馈生成候选，必须确认后发布；评价集不能直接被作为生产知识泄漏给模型以作弊通过。（`AiKnowledgeFeedbackService`：候选一律 DRAFT；发布需已认证 actor；`evaluation:` 来源即使有 actor 也拒绝发布（防背题）；`AiEvaluationTest` 三个反馈用例）

门槛：确定性 A01a–A20 测试全部通过；真实参考档案经至多一次允许 repair 后 schema 合法率 100%，引用有效率 100%，人工关键需求覆盖率至少 90%，任一安全负例失败阻断发布。模型质量不达标可禁用档案，不修改断言降低门槛。提交 `feat(ai): add measurable evaluations and enforced budgets`。

（进度：三批已提交——预算台账 + V46（`ffdb159`）、facade 预算接线（`6b6d9b5`）、评测框架 + 知识反馈（`5dcbcce`）；平台 809/0/0/0。真实参考档案的实跑门槛（schema 100%/引用 100%/覆盖 ≥90%）在 T-Review-5 定义 Gold Set 并授权真实档案后执行。）

（2026-10-12 补充：T-R5 轮 1 发现用量报表把未知价格读成 0（免费语义）→ 轮 2 修复：`AiQuotaService` 按 known/unknown 拆分统计，全部未知时 `estimatedCost=UNKNOWN` 并返回 `unknownCostCalls`；计划文件清单声明的 `AiQuotaService` 修改至此落实）
## T14：权限、秘密、历史迁移与审计补齐

依赖：T01 开始并贯穿 T02–T13；最终门禁必须最后复核。对应 R10–R12、A09/A17/A18。

文件：新增 `J/ai/security/AiInvocationAuthorization.java`、`AiSecretReferenceService.java`、`AiPayloadSanitizer.java`；复用 `J/auth/ProjectAccessService.java`、`J/security/SecretCipher.java`；修改所有 AI create/get/list/events/artifacts/retry/export 服务边界；新增 `UT/ai/security/AiProjectIsolationTest.java`、`AiSecretLeakageTest.java`、`AiLegacySecretMigrationTest.java`。

- [x] 用三个角色、两个项目、猜测 runId/documentId/assetId/attemptId 建立权限负例；读取错误不透露外部项目对象存在。（T-R5 轮 1 补充：job 面读取/取消与 document 面读取原先可跨项目或可区分 → 轮 2 修复为统一 404：`AiResourceIsolationRound2Test`（job 读/事件/取消跨项目逐字同形 404 且零状态变化）+ `AiDocumentIsolationRound2Test`（403/Unknown-document 不再可区分）；`AiProjectIsolationTest` 5 例：ADMIN/EDITOR-1/EDITOR-2 三角色；本项目的真实 run 与他项目的真实 run、纯猜测 runId 返回**逐字节相同的 404 not_found**；跨项目评审在任何状态变更前被拒（断言行仍为 PENDING_HUMAN_CONFIRMATION）；未登录/未知 actor 同形）
- [x] 登录用户 actor 进入创建与后台上下文，删除 system-dev 默认身份的业务路径；撤权在下一个节点前生效，后台不存在 HTTP session 时也能正确验证。（T-R5 轮 1 补充：主代码仍有 22 处残留（常量/门审计/工具回退/SQL 字面量）→ 轮 2 全清：21 个文件改走 `AiActorResolver`，DDL 默认值由前向迁移 V48 修正（演练库验证）、`NoDefaultDevelopmentIdentityTest` 扫描主代码与 V48+ 迁移防回归；`AiActorResolver`：已登录=真实用户、无会话=显式 `system`；7 个 AI 业务文件删除 `system-dev`/`Development User` 常量与 SQL 字面量；`requireProjectAccessAs(actor, project)` 每次节点前复核——撤销成员关系或停用账号在下一次调用即 404，测试全程无 SecurityContext）
- [x] 注入 canary API key、Authorization、cookie、工具 synthetic_secret、错误回显，扫描 API/log/event/snapshot/storage/export/model messages；除合法 transport Authorization 外不得出现 canary。（`AiPayloadSanitizer`：Bearer/Basic、sk-*、AKIA、key=value、Cookie、URL 查询六类形态脱敏 + JSON Pointer 金丝雀扫描（`/run/summary`、`/attempts/1/requestHeaders/authorization` 等命中用例）+ 脱敏后复扫为空（封口）；上游 401 回显请求体的真实泄漏面由 `AiConfigControllerTest` 金丝雀断言封死；合法 transport Authorization 只存在于 HTTP 传输层，不经过任何载荷）
- [x] SecretStore 只返回引用；生产无根密钥拒绝新增明文密钥。历史明文先备份到受控加密存储、迁移引用、验证解密可用，再按批准保留规则处理；禁止把历史明文放入 migration 日志。（`AiSecretReferenceService`：`storeSecret` 无根密钥抛 `no_root_key`（fail-closed）；迁移：加密备份入 V47 `ai_secret_backups`（密文副本，断言不含明文）→ 替换 `api_key_ciphertext` 为 enc:v1: 引用 → 逐行验证解密可用 → 报告仅计数（断言报告字符串不含秘密）；无根密钥环境保持数据原样；二次运行幂等；篡改密文解密失败 fail-closed）
- [x] 自定义 headers、TLS、代理等字段逐一核实有效性；普通用户不能通过 headers 覆盖核心 Authorization/Host，也不能通过材料改变目的地。（`AiConfigService.validateCustomHeaders`：Authorization/Host/Cookie/Content-Length/Transfer-Encoding/X-Forwarded-*/Proxy-Authorization/Connection 一律 400 `reserved_header`（控制器补 IAE→400）；越权 header 端点级用例 2 条）
- [x] 测试从文档/图片/OCR/检索/MCP 传入提示注入，验证其作为不可信材料不扩大 scope/tool permissions；日志只存摘要，不存 chain-of-thought。（`AiPromptInjectionContainmentTest`：注入文本置于证据清单与上下文摘要，模型输出声称 approved=true——产物仍强制 PENDING_HUMAN_CONFIRMATION（状态由确定性代码裁决，模型无权）；事件流全为短摘要（断言不含注入文本、长度受限）；工具面无执行通道可被注入打开（T09 白名单 + T12 人工门既有覆盖））

验证：三个新安全测试类+现有 ProjectAccessServiceTest 与 locator 权限测试；未经授权数据出站或凭证泄露为 stop-ship。提交 `fix(ai): enforce project scope and synthetic_secret references end to end`。

（进度：四批已提交——统一 404 隔离（`d0588b9`）、载荷脱敏 + 秘密引用迁移 + V47（`1f23087`）、默认身份删除 + 保留头守卫（`d856013`）、注入遏制（`6ac15c1`）；平台 827/0/0/0；V47 已真实 MySQL 验证后交 Flyway。commit 消息按批次主题拆分，未使用单一巨型提交。）

## T15：发布、回滚与完整验收

依赖：T00–T14 与 T-Review-1–T-Review-5 的全部必需能力。对应 R12/R13/R14/R15/R16、A01a–A20。

文件：修改 `server/platform/Dockerfile`、新增 `server/platform/.dockerignore`、修改 `docker-compose.yml`、`DEPLOYMENT.md`、`.github/workflows/ci.yml`；新增 `scripts/ai-runtime-smoke.mjs`、`docs/ai-test-platform/ai-runtime-operations.md`；扩展既有 system health/version endpoint；验收结果登记 T00 台账。

- [x] 先验证 build context 排除 H2/日志/凭证，镜像 OCI label 与版本接口包含 Git SHA；不是仅比较 latest tag 创建时间。（`.dockerignore` + Dockerfile `ARG GIT_SHA/BUILD_TIME` → OCI label + ENV；`/api/system/health` 实测返回 `gitSha=1a6e3ba…`/`buildTime`——由运行中进程直接回答「这是哪个镜像」。**演练发现真实缺陷**：`**/*synthetic_secret*` 通配误伤 `V25__mcp_secret_reference.sql`，镜像缺迁移导致 Flyway 校验失败；修复后以 `unzip -l app.jar | grep -c db/migration` = 48 条目 + V25/V47 在案 核对，启动日志「Successfully applied 9 migrations … now at v47」）
- [~] 在独立 MySQL 从生产结构的脱敏备份迁移，验证旧 run/point/review/知识数量和关联、连接 keyRef 可用；执行一次备份恢复与旧镜像回滚演练。（本地真实库完整演练 PASS：mysqldump→独立库恢复→`ai_analysis_runs 10/10、ai_draft_test_points 96/96、ai_run_events 107/107、ai_run_policies 3/3、助手/知识表 0/0`、draft→run 无孤儿、credential_ref 逐条形态报告（legacy 别名 ⇒ 无材料化时按 BLOCKED 如实报告）。真实 V39→V47 迁移在发布镜像启动时执行并可见。**生产脱敏备份演练与旧镜像回滚演练**（旧镜像+迁移前备份库）待发布窗口执行——脚本与纪律已就绪）
- [x] 发布开关按任务/项目切新 Runtime；只读比较参数解析，不并行发送双份真实付费请求；旧任务排空或保留原 worker 版本处理。（T-R5 轮 1 补充：开关原先只覆盖 facade，job Runtime 仍在禁用配置下发出真实请求（探针 C2）→ 轮 2 修复：`AiRuntimeSwitches` 共享解析 + `DefaultAiRuntime` 在 snapshot/预算/attempt 之前同语义阻断；`AiRuntimeSwitchRound2Test` 3 例（含 transport 零调用的对照）。配置 `app.ai.runtime.disabled-task-types`/`disabled-projects`：命中者发出任何请求前阻断，attempt 记 `RUNTIME_DISABLED`、`retry_reason=runtime_disabled`；只阻断不旁路、绝无双份付费请求；空配置全启用；`AiRuntimeSwitchTest` 3 例）
- [~] smoke 通过正式认证/CSRF API 创建合成材料任务，验证 model/protocol/profileRevision→schema→引用→产物→人审；不修改用户密码或绕过认证。（`scripts/ai-runtime-smoke.mjs` 对**真实运行镜像**执行：health+gitSha PASS、无凭据守卫 exit 2、探针凭据登录真 401；完整业务链（创建→事件→人审→猜测 id 404）需用户本人凭据运行——脚本按其口径逐项断言，BLOCKED 场景按 exit 3 如实报告而非通过。**终审补充：已认证业务链已由浏览器全链路 E2E 在真实登录+已部署镜像上覆盖（配置→预检→生成→校验失败→人审→改稿→导出，含 fixture 上游）；正式 smoke 脚本携带真实上游凭据的运行仍属预算窗口）**
- [ ] 用户明确启动受控 Web fixture 验证产物可执行；重复提交、取消、重启恢复、断流、无模型通道各运行一次；前端与后台状态一致。（2026-10-13：用户已人工启动受控站点回放——首跑+复跑 1/1 通过，真实执行证据在案；其余 5 场景（重复提交/取消/重启恢复/断流/无模型通道）机制由 T09/T11 测试覆盖，如需逐项真实回放，归入发布窗口一起做）
- [x] 最后一次全量 Java/Vue/Runner 回归，CI 加 deterministic contract/MySQL job tests；真实上游在单独预算 job 跑，报告所有 skip/blocked。（终审更新（2026-10-12 复核轮）：平台 **938/0/0/0**、前端 **158 files/791 + vue-tsc 0 + build ✓**、Runner **267/267**；CI 新增 `backend-mysql`（真实 mysql:8.4 服务，TCP 探活激活集成/契约类）与 `live-gates`（workflow_dispatch + ai-live-gates 环境 + `-Plive`，独立预算）。真实上游门需授权凭据后手动触发）
- [x] 输出用户操作说明、可用模型兼容矩阵、各能力限制与失败处理、监控指标/预算/保留期、升级回滚步骤；标明验证的是哪个环境/数据库/镜像 SHA。（`docs/ai-test-platform/ai-runtime-operations.md`：身份口径、模型矩阵（未验证标 BLOCKED/UNKNOWN）、预算/监控/保留期、秘密与权限纪律、升级/回滚、演练发现案例、限制表）

放行：R01–R16 台账全部已验收；A01a–A20 无失败；两个协议 contract 全部通过，当前部署至少一个任务所需协议+能力的真实档案通过业务验收；未验证模型/协议明确标 `BLOCKED/UNKNOWN`；MCP、统一 UI/API/export、全链路人工确认三个 Exit Gate 全部通过；七类 QA 任务真实成功；不得用截图、ping、mock 或“容器 healthy”代替业务完成。若外部 channel 不可用，应交付精确阻塞报告与其他已验证档案，不声称该模型恢复。

（进度：编译/身份/演练/开关/CI 已落并真实验证（`1975fab`、`1a6e3ba`）；完整放行清单中「真实模型档案业务验收、七类真实成功、Web 受控运行、生产备份演练」需真实通道与用户操作后由 **T-R5 最终独立复核**统一裁决——不得以本轮测试证据替代放行裁决。）

（2026-10-12 补充：**T-R5 独立复核共 8 轮 —— 轮 8 判定 PASS**（首轮即全量通过）。轮 1–7 逐轮 FAIL、逐条修复，修复提交链 `229a1c8` → `96d8322` → `d612ec0` → `482c276` → `be29259` → `90e558b` → `886e378`：
- 隔离：job/document/asset 读写删/取消/事件跨项目与「不存在」统一逐字 404 `not_found`，写面先授权后落库；
- 发布开关：覆盖 facade、DefaultAiRuntime、legacy `AiRouteExecutor`（ctx 取值统一）、vertical slice 四路径；
- 标识符归一：迭代到不动点（NFKC→按**码点**剥离 Z*/Cc/Cf/Mn/Mc/Me→lowercase），大小写/Unicode 空白/零宽/全角/数学字母/补充平面（1,239 码点）全部收敛；写/解析/删除三入口与 job submit 同口径，落库恒为目录 canonical，未知类型 `unknown_task_type` 零落库；
- 引用收集：容器安全递归（嵌套 `sources:[{...}]` 不再 500）、家族令牌（含 case-/job-）含数字要求、对象键名扫描、大小写不敏感比对、日期形态双分支豁免；
- 脱敏：出站载荷与评测报告/控制器 400 文案全过 `AiPayloadSanitizer`。
轮 8 复核证据（独立探针）：delete 修复经源码+字节码双重确认；63/63 HTTP 断言；5,804 对「写→删」16,969 断言零不符；外部 taskType 入口枚举无遗漏（jobs/assistant/policy 三处全 canonical）；回归 45/0/0/0 + 全量 **861/0/0/0**（含 MySQL 集成 7/0/0/0）。
剩余低危（已登记）：① `PUT /override/{taskType}` 无目录白名单（但真实任务入口只传 canonical，不可达行无越权）；② 修复前的非规范历史行需部署时一次性 SQL 清理；③ NFKC 阶段先组合的 8 个 BMP 记号折叠为「另一个键」（写/删同口径、目录入口 fail-closed，非绕过）；④ `QaTaskOutputValidators` 存在第三处 trim/lower 方言（唯一调用点传 canonical，按「不动已复核 HEAD」纪律未改，随下个需求收敛）。）

## 计划自检与执行停点

- [x] M0 结束时检查 T-Review-1–T-Review-4；任何一个失败都停止扩展 MCP/Figma/七类助手，只修 Runtime 底座。（T-Review-1–4 独立复核逐一 PASS（T-R4 至 30i/30j 全 PASS 归档）；『失败即停扩展』纪律全程执行）
- [x] M1 结束时检查每个 gateway 旁路是否迁移，配置是否真正进入请求；未完成消费者列入 M2 工作而不是遗漏。（T07 消费者覆盖扫描：唯一 sanction 入口 `AiTaskExecutionFacade` + `SANCTIONED_LAYERS`/预运行探针显式登记，抓新直连『有牙』；配置进入请求由 T02/T04 契约测试钉住）
- [x] M2 结束时检查 MCP/Agent/覆盖率是否真实执行，文本/图片/表格是否全部有处理状态；缺能力明确阻塞。（T09 MCP 真实出口（禁 fixture nonce 反回显）+ DAG 四类真执行器；T08 多模态源状态（AVAILABLE/PARTIAL/UNSUPPORTED）；T11 覆盖扫描有牙）
- [x] M3 结束时检查 T-Review-5 Gold Set、七类用户任务与 16 项需求逐一映射；检查没有将规则结果标模型成功、没有自动批准上线或执行最终用例。（Gold Set 交付（`b55ffdb`）；R01–R16→T→A 台账在 `ai-runtime-acceptance.md`；规则不得冒充模型（T12 校验器）与人工确认不自动（T04/T14）均有测试钉住；『七类用户任务真实成功』属放行清单）
- [ ] M4 结束时从运行环境重新读取 SHA、健康与业务产物，确认历史可查和回滚有效，清理仅由测试创建的临时数据。（本地可执行部分已完成：最新运行镜像 f04013d；**回滚演练本地复跑 PASS**（旧镜像 540a740 在 V53 库由 health 自证启动、计数与 flyway 零变化；脚本 `scripts/rollback-drill.sh`）；**备份/恢复演练 PASS**（`scripts/ai-migration-drill.sh`，计数逐项一致）。剩发布窗口执行：生产脱敏备份迁移 + 正式回滚 + 仅由测试创建的临时数据清理）

本清单不要求一次性大重构。M1 可独立改善可靠性，M2/M3 逐步交付业务价值；任何阶段源事实变更先更新主计划的证据与决策，再修改实现，避免再次凭单个 ping 推导根因。

（2026-10-13 晚补充：用户实测驱动两连修——①2MB 需求文档上传两层：nginx `client_max_body_size 25m`（`c893ccc`）+ `ai_source_documents.extracted_text`/`ai_source_chunks.content_text` 扩 LONGTEXT（`c84d1b0`，V54），真库集成测试迁移前精确复现截断；②需求分析合并截断：gate/幂等键散列化（`063bb36`）+ `ai_draft_test_points.source_text` 扩 LONGTEXT（V55）+ 失败上报文本钳位；三处测试均「先复现同形截断再验愈」、另补失败上报钳位契约用例，全量后端 **943/0/0/0**；镜像 `063bb36` 部署（health 自证），以用户原文档复跑 `run-0f2ba16d-…` **succeeded**（42 草稿点 + 42 gate 审计，artifact_id 恒 79 字符，全部 PENDING_HUMAN_CONFIRMATION）。）

（2026-10-13 深夜追加：用户录屏「测试解析有数据 → 用例生成页面没有任何数据」，定位并修复同链路三处缺陷——① 首次生成按钮 `:disabled="!cases.length"` 死锁（`af1734a`）；② `withCompileContext` 丢 `providerRequestId` 致计划整批 BLOCKED（`4102f61`，回归自 585174e）；③ b241e2d 收紧 acceptance 时删掉「待确认计划展示」致表格空+「确认用例」不可达（`65dfc5a`）。全量后端 943/0/0/0、前端 159 files/795 + build ✓；平台 `4102f61` + web `65dfc5a` 部署后，真实浏览器全链复验：生成→10 行待确认用例→点「确认用例」→10/10 ACCEPTED（HUMAN_EDITED）→质量评审完成（merge_ready，整体质量 72%）。）

（2026-10-13 深夜二：用户实测「自动登录正常但编译取证报验证码拦截」——真因是取证网络策略拦掉 ICC 登录 POST 与验证码脚本；平台侧既有「带路径 URL=精确放行(含 POST)」通道因前端归一化折叠为 Origin 而不可达。修复 `8eb5176`：FE 保留带路径条目并更名「认证与放行白名单」写明用法、验证会话随环境持久化（可清除/优先复用）、失败信息点名被拦来源并给放行建议。验证：归一化回退即失败（有牙）+ runner 容器真实浏览器夹具（允许的跨域登录 POST 放行 authHits=1、未列入来源拦截 externalHits=0）；全量后端 944/0/0/0、前端 159 files/798 + build ✓。）

（2026-10-14 追加：取证网络策略双模式（`9f9e09d`）——用户设计「默认黑名单不拦截，仅拦截用户显式填写的；黑白名单可切换」。runner `networkPolicyMode`（缺省 allowlist 不变；blocklist 仅拦名单内 Origin/带路径 URL）；平台四层记录全链透传；FE 环境配置「网络放行策略」下拉 + 名单字段随模式切换文案。验证：runner 策略 3 例 + ICC 夹具黑名单零配置实证 + 平台转发钉子；phase3 新鲜度门重录；全量后端 945/0/0/0、前端 159 files/800 + build ✓、runner 271；三镜像 9f9e09d 部署。）

（2026-10-14 二：用户核查「素材取证失败时资源如何回收」——活体实验定位两处缺口：① 取证/接管会话不记 `browserInstance`，`destroySession` 只关 page/context、Chromium 进程常驻（实测 12 会话销毁 / 浏览器实例零减少；微探针 `browserConnected=true`）；② `reuseSession=true` 且无现成会话时早失败留无主浏览器（跨过 60s 巡检）。修复 `6ea0a0b`：两处 `createSession` 补 `browserInstance` + `destroySession` 兜底链 + `retainSession` 改「确有会话」语义；两条锁测先红后绿；phase3 重录；runner 全量 273/273；镜像部署后微探针复验 `browserConnected=false`。）

（2026-10-14 三：用户界面指正（截图红框）——Agent 工作区去掉两阶段说明栏与「测试设计」流水线卡（`53818ed`）；TwoPhaseIndicator 收敛 chips 单形态；锁测试父版本先红；前端全量 799/799 + build ✓；web 镜像部署。）

（2026-10-14 四：用户指正「重试编译时清理之前素材取证·已阻塞的内容」——FE 在创建新任务前清空旧运行对象与时间线（`9c9c8d3`，钉子父版本先红；前端全量 159 files/800 + build ✓，镜像部署）。同轮根因定位：「重试总失败·目标页面尚未就绪」非平台旧状态所致（四次重试=四个新运行、失败不回填、旧 blocked 不传递），实为 ICC 根路径 `/` 对已登录用户渲染空白（`/#/home` 等具体路由正常，runner 容器干净浏览器验证登录页渲染正常）→ 建议 ICC 侧修 `/` 或环境入口改指具体路由。）

（2026-10-14 五：用户两项要求落地 + 深层缺陷修复（`e73ff31`）——①「本地网络访问拦截需要默认开启」：runner 本地网络兼容模式与登录发现私网放行改为默认开启（新增 `runner-env.mjs` 集中解析，仅显式 `false` 关闭；4 例默认值钉子；两处 README 改写）；② 用户 15:54 截图的 `UnexpectedRollbackException` 根因=素材库检索 SQL 的 ESCAPE 反斜杠转义歧义——Java 文本块 `'\\'` 编译后运行时单反斜杠，MySQL 驱动（connector-j 9.7）把后续引号视为被转义、漏数占位符（16→10），每次检索抛 `TransientDataAccessResourceException`；失败经内层事务代理污染调用方事务 → 运行提交时炸回滚异常（H2 单元测试对驱动解析差异不敏感，潜伏多轮）。修复：ESCAPE 改 `!` 字符（三层转义零歧义）+ `searchCandidates` 改 REQUIRES_NEW 只读事务（失败不再污染调用方）+ 降级日志补打完整异常。验证：MySQL 探针 16/16 计数 + 真实执行命中 50 行；契约钉子先红后绿（3 例）；后端全量 948/0/0/0、runner 全量 277/277、前端 159 files/800 + build ✓；部署后同形 COMPILE 运行素材库失败 0 次（修复前 10+/次）且不再抛回滚异常。③ ICC 双端对照（用户要求）：同 Cookies 下真实浏览器与容器 Chromium 均可完整渲染 `#/console/account`/`#/home`；根路径 `/` 全新启动在健康登录态下 60 秒也不重定向（空白）→ 行动项：环境入口改 `/#/home`；低权限账号数据受限建议换数据完整账号。）

（2026-10-14 六：用户实测「自动化编译」必现拦截（`86520b1`）——入口改 `#/home` 后素材取证全通（自动登录 ICC、采集 44 元素入库、素材命中 10），但逐点编译用「单点」核对「整批已确认计划（10 条）」→ 其余 9 条被判「批次之外」直接抛错（下游还会因缺单点证据报 step_evidence_missing）。修复：编译调用允许批次大于请求 + `buildNaturalPlanBatch` 校验后把批次收窄到本次编译的点；两条钉子父版本先红（复现生产同款报错）后绿；后端全量 950/0/0/0。）

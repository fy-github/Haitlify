> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# T-Review-3 Acceptance Gate

## Status

**全部闭合。** 代码、迁移、结构化约束、实时付费 Gate（含 plan 路径）与**登录态 UI/API/export 线上校验**均已实际执行并核对；线上校验过程中发现并修复了一个 Gate 审计缺陷（actor 被写成固定值），修复后再次线上复验通过。

本轮（第二轮）修复的是第一轮复测暴露出的 fail-closed 缺口：artifact 级 origin 允许写入字段级标签（`MODEL_PROPOSED`、`RULE_COMPUTED`），以及 `accepted` 与 `acceptance_state` 之间只有应用层约定、没有结构约束。

### 上一轮结论更正

第一轮复测时我曾报告「live 库存在 69 条 `accepted=true` 且 `acceptance_state='PENDING_HUMAN_CONFIRMATION'` 的矛盾数据」。重新查询后确认该结论**不成立**：这 69 条的真实值是 `accepted=false`，是我读错了查询结果。该不实断言在此撤回。真实存在且已修复的问题是：这些行的 `result_origin` 为字段级标签 `MODEL_PROPOSED`，而 `ai_quality_scores` 的 `result_origin` 与 `acceptance_state` 都被写成字段级标签 `RULE_COMPUTED`。

## 本轮验证证据（全部为实际执行的工具结果）

- 后端全量：`./server/platform/mvnw -f server/platform/pom.xml test` → **exit=0，tests=528 failures=0 errors=0 skipped=5**（5 个 skipped 全部为 opt-in 付费 live gate，已单独运行，见下节）。
- **实时付费 Gate（`-Dai.live.gate=true`，真实 provider 调用，本轮实际执行）**：
  - `AiRuntimeLiveInvocationTest` → `LIVE_GATE resultOrigin=MODEL_GENERATED acceptanceState=PENDING_HUMAN_CONFIRMATION providerRequestId=resp_09d7f1e1b6b5e479016ab481602e4887d0ac8b134f1d1d7735 usageKnown=true`（真实 token usage 已知）。
  - `AiVerticalSliceLiveAcceptanceTest` → `LIVE_VERTICAL runId=live-vertical-1790214708164 artifactId=artifact-dfb20f8c-7757-4031-8e4c-bc319516742c requestId=resp_042b713738913ebf016ab482360cbc87d0957bb01efd73b67f origin=MODEL_GENERATED acceptance=ACCEPTED`（真实调用 → artifact → 人工确认 → 回读全链路）。
  - `AiProviderCompatibilityLiveProbeTest`（11.27s）与 `AiProviderCompatibilityModelSwitchLiveTest`（28.91s）通过。
  - **`AiWebPlanLiveGateTest`（本轮新增，覆盖本轮改动的 plan 路径）** → `LIVE_PLAN_GATE plans=1 providerRequestId=req-c8cc7f57-cf7e-4252-ba36-9f1c37552feb elapsedMs=33487`：真实 plan 调用产出 `MODEL_GENERATED / PENDING_HUMAN_CONFIRMATION` artifact，`candidate_json` 持久化了该 provider request ID，Gate 审计与行一致。
  - 两个 gate 首次运行时因 **fixture 缺陷** 报错（plan gate 漏了 NOT NULL 列 `field_origins_json`；vertical slice 用了上一轮已存在的固定 run id 触发 `Duplicate entry`），非产品缺陷；fixture 修好后重跑通过。
- 迁移：Flyway **V1–V35** 全部 success（`flyway_schema_history` 实测）。
- V33 实际作用：`ai_web_case_agent_artifacts` 69 行由 `MODEL_PROPOSED` → `BLOCKED`（实测 `SELECT result_origin, COUNT(*)` → `BLOCKED 69`），`acceptance_state` 保持 `PENDING_HUMAN_CONFIRMATION`。
- V35 实际作用：`ai_quality_scores` 30 行由 `RULE_COMPUTED / RULE_COMPUTED` → `RULE_EXTRACTED / PENDING_HUMAN_CONFIRMATION`（实测）。
- 结构化约束实测（`probe-constraints.sh`，5 个探针全部被库拒绝，探针行残留 0）：
  - `accepted=true` + `PENDING_HUMAN_CONFIRMATION` → `ck_ai_web_case_agent_artifact_acceptance` violated；
  - `result_origin='MODEL_PROPOSED'` → `ck_ai_web_case_agent_artifact_origin` violated；
  - `result_origin='DEGRADED'`（词表外）→ 同上；
  - 质量分 `result_origin='RULE_COMPUTED'` → `ck_ai_quality_scores_origin` violated；
  - 质量分 `acceptance_state='RULE_COMPUTED'` → `ck_ai_quality_scores_acceptance_state` violated。
- 运行时：`docker compose ps` → platform healthy；`GET /actuator/health` → `{"groups":["liveness","readiness"],"status":"UP"}`。
- 部署产物核对（不只看部署命令返回码）：`docker create` + `docker cp` + `unzip -l` 确认镜像 `app.jar` 含 `V32`、`V33`、`V34`、`V35` 迁移与 `WebCasePlanOutput.class`、`WebCaseAgentRunService.class`、`AiQualityScoreService.class`（class 时间戳 09-23 19:10）。
- 前端与 Runner：本轮改动仅落在 `server/platform`（`git status` 可证），未触及 `apps/web` 与 `server/web-runner`，其上一轮证据仍然有效（前端 `vue-tsc` 通过、139 files/686 tests、build 通过；runner 185 pass/0 fail）。
- API 契约范围说明：`result_origin / acceptance_state` 的响应字段与导出过滤由 Spring 集成测试（真实上下文 + 真实 SQL）验证；线上 HTTP 只读校验受 `app.security.enabled=true` 限制（需登录态），未在本次执行。

## 登录态线上校验（本轮实际执行）

环境：`docker compose up -d --build web web-runner platform` → web 38080、web-runner 19323、platform 38081（health `{"groups":["liveness","readiness"],"status":"UP"}`）；镜像内容核对：web 打包产物含作用域定位器标签修复、runner 镜像含 `evidence_truncated`/`unsupported_shadow_dom`、platform 镜像 `app.jar` 内 `WebCaseAgentRunService.class` 与本地新构建产物 sha256 一致且含 `currentHumanActor`。操作为真实 synthetic_user 会话（`GET /api/auth/me` → `{"username":"synthetic_user","role":"ADMIN"}`），非匿名/test-profile。本轮修复后的后端全量回归：**535 tests / 0 failures / 0 errors / 5 skipped，exit 0**。

| 校验项 | 线上实测 |
| --- | --- |
| 未确认 artifact 的接口暴露 | `GET /api/ai/web-case-agent-runs/{run}/artifacts` → 9 条全部 `resultOrigin=BLOCKED`、`acceptanceState=PENDING_HUMAN_CONFIRMATION`、`accepted=false` |
| 导出 fail-closed（排除方向） | 同上 run 的 `GET .../artifacts/export` → `[]`（库中存在 9 条 artifact） |
| 质量统计 fail-closed | `GET .../quality-insight?projectId=1` → `ratedCount=0, objectiveCount=0, humanCount=0`；`GET .../quality-ratings?projectId=1` → `{"items":[]}` |
| 人工确认（包含方向） | `POST .../artifacts/{id}/decision {"accepted":true}` → 200；回读 `accepted=true / acceptanceState=ACCEPTED / resultOrigin=HUMAN_EDITED`；`GET .../artifacts/export` → 恰好 1 条，且只含被确认的那条 |
| Gate 审计行 | `ai_artifact_gate_audits` 新增一行：`result_origin=HUMAN_EDITED`、`acceptance_state=ACCEPTED`、`decision=ACCEPTED`、`idempotency_key=legacy:web:61` |
| UI | 登录后 `/web-cases` 正常渲染；前端交付集合只收 `acceptanceState==='ACCEPTED' && accepted`（本次确认前为 0 条，确认后 1 条） |

### 线上校验发现并修复的缺陷：Gate 审计 actor 不是真实操作人

- 现象：`POST .../decision` 确认成功后，`ai_artifact_gate_audits.actor_id` 为固定值 `system-dev`，与真实确认人无关 → 「谁确认了交付物」不可审计。
- 定位：`WebCaseAgentRunService.confirmPlan` / `confirmAllPlans` 构造 `GateRecord` 时硬编码 actor。
- 修复：新增 `currentHumanActor()`，actor 取自 `SecurityContextHolder`，无已认证主体时抛错（fail-closed，不写人类决策记录）；两处 GateRecord 改为使用它。
- 回归：`WebCaseAgentRunServiceTest` 新增 `rejectsPlanConfirmationWithoutAnAuthenticatedActor`，并把 `confirmsPlanAndPersistsAcceptedGateAudit`/`confirmAllPlansPersistsAcceptedGateAuditForEveryPlan` 改为在认证上下文下断言 actor；`AiArtifactAcceptanceCrossEntryTest#webArtifactsAreNotExportableBeforeHumanConfirmation` 改为以认证主体（`synthetic_actor`）发起确认并断言审计 actor（此前无主体调用掩盖了该缺陷）。
- 线上复验（修复部署后，sha256 核对过的镜像）：再次确认真实记录 `actor_id=synthetic_user`，与修复前那条 `actor_id=system-dev` 形成对照；导出随即变为 2 条，仍只含被确认的两条。

### 复现线上校验的步骤

```bash
cd /path/to/Haitlify
docker compose up -d --build web web-runner platform
# 浏览器打开 http://localhost:38080 用 synthetic_user 登录（agent 浏览器需在 Hermes 侧放行本机地址：
#   ~/.hermes/config.yaml → security.allow_private_urls: true，重启后生效）
# 直接用登录态会话访问接口（同源 fetch 或直接开标签页）：
#   GET  /api/ai/web-case-agent-runs/{runId}/artifacts
#   GET  /api/ai/web-case-agent-runs/{runId}/artifacts/export
#   POST /api/ai/web-case-agent-runs/{runId}/artifacts/{artifactId}/decision  (需 X-XSRF-TOKEN)
```

## 取值语义（唯一成功语义）

- artifact 级 `result_origin ∈ {MODEL_GENERATED, RULE_EXTRACTED, TOOL_OBSERVED, HUMAN_EDITED, BLOCKED}`（`AiArtifactOrigin` 的四个值 + fail-closed 的 `BLOCKED`）；
- field 级来源 `field_origins_json ∈ {MODEL_PROPOSED, RULE_COMPUTED, TOOL_OBSERVED, HUMAN_REQUIRED}`；
- `MODEL_PROPOSED`、`RULE_COMPUTED` **只是字段级标签**，任何 artifact 级 origin / acceptance_state 列都不得写入这两个值（已由 CHECK 约束封死）；
- Web artifact 的 origin 由证据推导：plan 产物只有在模型调用留下可审计 provider request ID（非空）时才写 `MODEL_GENERATED`，否则写 `BLOCKED`；编译产物是规则编译器基于人工确认的 plan 与浏览器观测证据产出，固定写 `RULE_EXTRACTED`（有证据时 field origin 含 `TOOL_OBSERVED`）；
- 判定正式交付物只有一条规则：`accepted = true AND acceptance_state = 'ACCEPTED'`，且该组合由 `ck_ai_web_case_agent_artifact_acceptance` 强制。`status = 'succeeded'` 只表示任务执行状态，不表示真实 AI 成功，也不产生交付物。

## 结构化 fail-closed 约束（本轮新增，V32–V35）

| 约束/默认值 | 作用 |
|---|---|
| `ck_ai_web_case_agent_artifact_acceptance` | `accepted=false OR acceptance_state='ACCEPTED'`，`accepted` 不再能被单独翻转 |
| `ck_ai_web_case_agent_artifact_origin` | `ai_web_case_agent_artifacts.result_origin` 限定为 artifact 级词表 |
| `ai_web_case_agent_artifacts.result_origin` 默认值 | `MODEL_PROPOSED` → `BLOCKED`（未声明来源的行不得默认宣称模型生成） |
| `ck_ai_quality_scores_origin` / `ck_ai_quality_scores_acceptance_state` | 质量分的 origin 与 state 分别限定为 artifact 词表与状态机词表 |
| `ai_quality_scores.result_origin` / `.acceptance_state` 默认值 | `RULE_COMPUTED` → `BLOCKED` |

## Gate 规则

正式 artifact 必须满足：

- content 非空；
- project scope 一致；
- evidence IDs 属于当前允许范围；
- revision >= 1；
- idempotency key 非空；
- `MODEL_GENERATED` 必须有真实 Runtime result、provider request ID、validated output 和 `PENDING_HUMAN_CONFIRMATION`；
- 未通过时返回 `BLOCKED`，不创建正式交付物；
- 只允许 `PENDING_HUMAN_CONFIRMATION → ACCEPTED → EXECUTED`；
- RULE/TOOL/HUMAN 结果不会被标记为 `MODEL_GENERATED`。

## 已完成的入口标记

- MCP：已实现真实 HTTP JSON-RPC transport（`initialize` → `tools/call`），成功结果必须来自远端 `result`，并记录 `TOOL_OBSERVED / PENDING_HUMAN_CONFIRMATION`；不支持的 server type、认证模式、HTTP 错误和 MCP `isError` 仍 fail-closed 为 `BLOCKED / BLOCKED`。echo 参数不等于 `TOOL_OBSERVED`。
- Figma direct mapper：分析 run 返回 `waiting_review`，draft point 标记 `RULE_EXTRACTED / PENDING_HUMAN_CONFIRMATION`（`ai_figma_files`、`ai_figma_element_candidates` 已有独立 origin/acceptance 列）。
- Multi-Agent graph/node：结果表持久化 `result_origin / acceptance_state`；AI enrichment 不可用时回退规则来源并显式暴露 `aiEnrichmentFallback = true`，不再把规则结果当模型结果。
- 分析 worker：`MODEL_GENERATED` 必须以真实 provider request ID 为证据，缺失时 draft point 写 `BLOCKED / BLOCKED`（不写正式交付物）；worker 正常结束、`no_resume_point` 与异常路径都释放 lease。
- 视觉分析：`ai_visual_analysis_results` 与视觉 draft point 的来源同样按 request ID 判定（`MODEL_GENERATED` 或 `BLOCKED`）。
- run 级 provenance：`ai_analysis_runs.result_origin / acceptance_state` 由 `AiRunOriginSyncService` 依据该 run 实际产生的 artifact 派生（任一 artifact 为 `MODEL_GENERATED` 才写 `MODEL_GENERATED`，否则 `BLOCKED`）。
- Web artifact：plan 产物按 provider request ID 写 `MODEL_GENERATED` 或 `BLOCKED`，编译产物写 `RULE_EXTRACTED`，两者都 `accepted=false`；只有人工确认（`confirmPlan`）写入 `ACCEPTED / HUMAN_EDITED` 后才可导出，且每次确认/拒绝都落 Gate 审计，**审计 actor 取认证上下文（无已认证操作人时 fail-closed，不写固定系统账号）**。
- Quality score：artifact 级来源为 `RULE_EXTRACTED`，状态为 `PENDING_HUMAN_CONFIRMATION`（评分理由内的 `RULE_COMPUTED:` 前缀属字段级说明，不再写入状态列）。
- Web quality/regression/export：只统计 `accepted=true AND acceptance_state='ACCEPTED'` 的 artifact（`WebCaseObjectiveQualityService`、`WebCaseRegressionComparisonService`、`WebCaseAgentRunService` 导出与计数）；前端 `App.vue` 只有在 `acceptanceState === 'ACCEPTED' && accepted` 时才把 artifact 纳入交付集合，并以 `artifactId:revision` 去重。
- coverage 扫描按 run 覆盖的需求/测试点统计，不把待确认 artifact 计入交付物；未确认 artifact 不进入质量、回归、完成数量与导出。
- T-Review-1 vertical slice：Runtime 结果先经过 Acceptance Gate，再创建 artifact。

## 跨入口一致性与回归证据

- `AiArtifactAcceptanceCrossEntryTest`：同一组负例（成功假象但无可审计 provider 证据）在 Acceptance Gate、分析 worker、Web 导出三个入口必须得到同一语义 —— 一律 `BLOCKED`/不可导出；并断言 `ai_web_case_agent_artifacts` 中不存在 artifact 级 `MODEL_PROPOSED`、不存在 `accepted=true AND acceptance_state<>'ACCEPTED'`。
- `WebCaseAgentSchemaMigrationTest`：`schemaRejectsAcceptedArtifactWhoseAcceptanceStateIsNotAccepted`（拒绝 `accepted=true` + 非 `ACCEPTED`，并断言 origin 默认值为 `BLOCKED`）、`schemaRejectsArtifactOriginOutsideTheVocabulary`（拒绝 `MODEL_PROPOSED` 及任意词表外 origin，接受五个合法值）、`planArtifactsCarryModelOriginOnlyWithProviderEvidence`（真实库中 plan artifact 有 provider request ID 才 `MODEL_GENERATED`，无则 `BLOCKED`，Gate 审计与行一致）。
- `WebCaseAgentRunServiceTest`：`savesConfirmedPlanActionsAndExecutionContextWithoutDroppingThem`（含 `resultOrigin=MODEL_GENERATED` 与 `providerRequestId` 断言）、`planArtifactsAreBlockedWhenTheProviderRequestIdIsMissing`、`compiledCaseArtifactsNeverClaimAModelOrigin`。
- `AiQualityScoreServiceTest#qualityScoresPersistArtifactLevelRuleOriginAndPendingAcceptance`：质量分落库为 `RULE_EXTRACTED / PENDING_HUMAN_CONFIRMATION`，库中不得出现 `RULE_COMPUTED` 状态值。
- `AiSchemaMigrationTest#qualityScoreProvenanceColumnsRejectFieldLevelLabels`：schema 拒绝把 `RULE_COMPUTED` 写进质量分的 origin 或 state 列。
- `AiAnalysisWorkerTest`：`draftPointsAreBlockedWhenProviderRequestIdIsMissing`、`draftPointsCarryModelGeneratedOriginWithProviderEvidence`（含 run 级 origin 断言）、lease 竞态与 fencing 接管用例。
- `AiAgentConsensusMergeServiceTest`：`mergeFallsBackToRuleOriginWhenAiEnrichmentIsUnavailable`。
- `AiVisualAnalysisServiceTest`：`visualDraftPointsCarryModelGeneratedOriginOnlyWithProviderEvidence`、`visualDraftPointsAreBlockedWhenProviderRequestIdIsMissing`。

## 持久任务可靠性

- `ai_analysis_worker_leases(run_id, owner_id, lease_until, fencing_token, updated_at)`（V30）：`poll` 先取 lease，失败返回 `busy`。
- 续租严格绑定 `run_id + owner_id + fencing_token`；续租失败或 token 被递增即判定 `leaseLost`，worker 停止后续写入（记录调用结果、合并 draft、标记 chunk succeeded 之前都有 `ensureLeaseHeld` 二次校验）。
- lease 过期可被其他 worker 接管并递增 `fencing_token`；任务完成后 lease 行必须清空。

## 未闭合项（不能宣称「全量通过」的原因）

无。

（本轮闭合：① 4 个原有 opt-in live gate 与新增 plan 路径 live gate 均已用真实 provider 调用跑通，实际 request ID 见上节；② 线上库数据归一、约束生效、镜像产物均经回读核对；③ **登录态 UI/API/export 线上校验已完成**（排除方向 + 包含方向，见「登录态线上校验」一节），并修复了其中发现的 Gate 审计 actor 缺陷。）

### 运维注意（本轮踩到的坑）

默认 profile 的 live gate 测试类（不带 `@ActiveProfiles("test")`）在加载 Spring 上下文时会**对 dev MySQL 执行 Flyway 迁移**。因此：跑全量测试会把新迁移应用到开发库；已应用的迁移**绝不能改写**（改写会导致下一次部署 `Migration checksum mismatch` 而启动失败），修复必须新增迁移（本轮 V33/V35 即为此原因从 V32 拆出）。

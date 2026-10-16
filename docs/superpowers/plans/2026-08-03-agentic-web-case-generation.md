> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# Agent 式 Web 用例生成与 Agent 资产 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Web 用例生成改造成可恢复、可审计、浏览器证据驱动的阶段式 Agent 任务，并提供可维护的规则、locator 知识库和经验记忆资产。

**Architecture:** 新增独立的 `WebCaseAgentRun` 任务边界，按预检、取证、规划、分点生成、校验修复、持久化六个阶段推进；任务状态、阶段产物、脱敏事件和已完成用例全部持久化，前端通过 REST 轮询事件得到流式摘要。规则版本、页面元素知识和经验记忆使用独立服务与表，Agent 只使用当前浏览器重新验证过的 locator，最终通过既有 Web DSL 校验后才追加到现有生成列表。

**Tech Stack:** Spring Boot、Spring JDBC、Flyway、MySQL/H2 测试数据库、Jackson、现有 `AiGateway`、现有 Web Runner/Playwright、Vue 3、TypeScript、Element Plus、Vitest、Playwright。

---

## 文件边界

以下文件在实现阶段按职责维护，已有文件只改动对应职责区域：

- 创建根目录 `AGENT.md`，作为全局安全边界和输出契约；创建 `server/platform/src/main/resources/ai/agent/phases/01-preflight.md` 至 `06-delivery.md`，作为初始阶段规则种子。
- 创建 `server/platform/src/main/resources/db/migration/V10__agentic_web_case_generation.sql`，新增 Agent 规则、运行、事件、证据、locator 知识和经验记忆表。
- 创建后端 `ai/agentic` 包，放置运行状态机、规则资产、事件、运行快照、脱敏和任务 DTO；locator 知识服务放在 `ai/agentic/locator`，经验记忆放在 `ai/agentic/memory`。
- 复用并扩展 `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java` 与 `WebAutomationCaseValidator.java`，只负责 Agent 生成所需的提示词、结果归一化和确定性校验，不负责页面展示。
- 复用 `server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/PlaywrightRunClient.java`、现有 `WebAutomationLocatorDiscoveryService.java` 和 `server/web-runner/page-locator-discovery.mjs`，补齐证据白名单和知识库写入接口。
- 创建 `apps/web/src/domain/webCaseAgentRepository.ts`、`apps/web/src/composables/useWebCaseAgent.ts`、`apps/web/src/components/workflow/WebCaseAgentWorkspace.vue` 及其详情/时间线子组件。
- 创建 `apps/web/src/components/platformAi/AgentAssetsDashboard.vue` 及规则、locator、记忆三个面板；`App.vue` 只保留路由状态和既有工作流数据绑定，不再新增大段业务逻辑。
- 现有 `AiGenerationStage.vue` 增加 Agent 工作区插槽和状态属性；`apps/web/src/styles/studio-canvas.css` 增加与当前 Studio Canvas 主题一致的工作区样式。

## Task 1: 建立 Agent 持久化边界与全局规则

**Files:**
- Create: `AGENT.md`
- Create: `server/platform/src/main/resources/ai/agent/phases/01-preflight.md`
- Create: `server/platform/src/main/resources/ai/agent/phases/02-evidence.md`
- Create: `server/platform/src/main/resources/ai/agent/phases/03-planning.md`
- Create: `server/platform/src/main/resources/ai/agent/phases/04-generation.md`
- Create: `server/platform/src/main/resources/ai/agent/phases/05-validation.md`
- Create: `server/platform/src/main/resources/ai/agent/phases/06-delivery.md`
- Create: `server/platform/src/main/resources/db/migration/V10__agentic_web_case_generation.sql`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentModels.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentSchemaMigrationTest.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentModelsTest.java`

- [ ] **Step 1: Define the persisted state contract before services.**

  Add records/enums in `WebCaseAgentModels.java` with the exact values used by every later task:

  ```java
  enum WebCaseAgentStage { PREFLIGHT, EVIDENCE, PLANNING, GENERATION, VALIDATION, DELIVERY }
  enum WebCaseAgentRunStatus { QUEUED, RUNNING, PAUSED, SUCCEEDED, FAILED, BLOCKED, CANCELLED }
  enum WebCaseAgentEventType { INPUT_SUMMARY, ACTION, TOOL_RESULT, OUTPUT_SUMMARY, VALIDATION, STATUS, ERROR }
  enum AgentMemoryStatus { PENDING, ACTIVE, DISABLED, DELETED }
  enum AgentMemoryScopeType { PROJECT, PROJECT_SET, GLOBAL }
  ```

  Define records for `WebCaseAgentStartRequest`, `WebCaseAgentRunResponse`, `WebCaseAgentEventResponse`, `LocatorEvidence`, `LocatorKnowledgeEntry`, `AgentRuleSnapshot`, `AgentMemoryResponse` and `GeneratedCaseArtifact`. `GeneratedCaseArtifact` must carry `sourcePointId`, normalized case JSON, evidence IDs, validation errors, and `accepted` so a partial candidate can never be mistaken for a delivered case.

- [ ] **Step 2: Add the database tables and indexes.**

  `V10__agentic_web_case_generation.sql` must create:

  - `ai_agent_rule_versions(rule_set_id, scope, project_id, version, status, content_text, phase_rules_json, content_hash, published_at, actor_id, actor_name, created_at, updated_at)` with a unique key on `(rule_set_id, scope, project_id, version)` and an index on `(rule_set_id, scope, project_id, status)`.
  - `ai_web_case_agent_runs(run_id, project_id, analysis_run_id, status, current_stage, requested_point_ids_json, completed_point_ids_json, blocked_point_ids_json, rules_snapshot_json, input_summary_json, output_summary_json, last_heartbeat_at, cancel_requested, error_summary, created_at, updated_at, started_at, finished_at)` with unique `run_id` and an index on `(project_id, status, updated_at)`.
  - `ai_web_case_agent_events(event_id, run_id, sequence_no, occurred_at, stage, event_type, severity, summary, point_id, tool_name, duration_ms, rule_version, knowledge_hit_ids_json, error_code, created_at)` with unique `(run_id, sequence_no)` and an index on `(run_id, sequence_no)`.
  - `ai_web_case_agent_artifacts(artifact_id, run_id, point_id, artifact_type, candidate_json, evidence_ids_json, validation_errors_json, accepted, repair_round, created_at, updated_at)` with unique `artifact_id`.
  - `ai_locator_knowledge_entries(entry_id, project_id, environment_id, module_name, page_name, page_url, page_fingerprint, natural_name, locator, role_name, accessible_name, element_type, source, status, evidence_version, collected_at, last_verified_at, metadata_json, created_at, updated_at)` and `ai_locator_knowledge_history(history_id, entry_id, snapshot_json, changed_reason, created_at)`.
  - `ai_agent_memories(memory_id, source_run_id, project_id, module_name, stage, category, content, status, scope_type, scope_project_ids_json, rules_version, created_by, confirmed_by, confirmed_at, created_at, updated_at)` and `ai_agent_memory_audit(audit_id, memory_id, action_type, before_json, after_json, actor_id, actor_name, created_at)`.

  Use the same `projects(id)` foreign-key style as `V3__ai_agent_foundation.sql`; do not alter existing AI tables in this migration.

- [ ] **Step 3: Write the global rule file and six seed phase files.**

  `AGENT.md` must include the non-overridable safety rules: no hidden reasoning persistence, no synthetic_secret persistence, no guessed locator, current-browser verification required, at least one executable assertion, only supported Web DSL actions, and no automatic final execution. Each phase file must contain `Purpose`, `Inputs`, `Allowed tools`, `Forbidden actions`, `Pass conditions`, `Failure state`, and `Visible event summary` headings so the rule validator can check them mechanically.

- [ ] **Step 4: Add migration and model tests before service code.**

  `WebCaseAgentSchemaMigrationTest` must start the test database, query `information_schema.tables`, and assert all eight new tables plus the unique keys exist. `WebCaseAgentModelsTest` must assert JSON round-tripping preserves `stage`, `status`, `pointId`, `evidenceIds`, `accepted`, and `scopeType` without serializing synthetic_secret fields.

- [ ] **Step 5: Run the focused backend tests and commit the foundation.**

  Run:

  ```bash
  ./mvnw -pl server/platform -Dtest=WebCaseAgentSchemaMigrationTest,WebCaseAgentModelsTest test
  ```

  Expected: `Tests run: 2` or more, `Failures: 0`, `Errors: 0`. Commit only the files in this task with `git commit -m "feat: add agentic web case persistence"`.

## Task 2: Implement versioned Agent rules and snapshots

**Files:**
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/rule/AgentRuleValidator.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/rule/AgentRuleService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/rule/AgentRuleController.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/rule/AgentRuleDtos.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/rule/AgentRuleServiceTest.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/rule/AgentRuleControllerTest.java`

- [ ] **Step 1: Define rule APIs and DTO validation.**

  Expose these endpoints under `/api/ai/agent-assets/rules`:

  ```text
  GET  /api/ai/agent-assets/rules?projectId={id}
  GET  /api/ai/agent-assets/rules/{ruleSetId}/versions?projectId={id}
  GET  /api/ai/agent-assets/rules/{ruleSetId}/diff?from={version}&to={version}
  POST /api/ai/agent-assets/rules/{ruleSetId}/draft
  POST /api/ai/agent-assets/rules/{ruleSetId}/publish
  POST /api/ai/agent-assets/rules/{ruleSetId}/rollback
  ```

  `SaveAgentRuleRequest` requires `contentText` and all six phase keys. `PublishAgentRuleRequest` requires the draft version. Return `AgentRuleVersionResponse` with `ruleSetId`, `version`, `status`, `contentHash`, `phaseRules`, `publishedAt`, and `updatedAt`.

- [ ] **Step 2: Implement deterministic safety validation.**

  `AgentRuleValidator.validate(contentText, phaseRules)` must reject missing headings, empty phase rules, instructions containing API-key/password/Cookie persistence, hidden-reasoning capture, locator guessing, or final browser execution. Project rules may only add forbidden actions; `AgentRuleService.mergeProjectRules` must reject any project rule that removes a global restriction.

- [ ] **Step 3: Implement seed, draft, publish, diff, and rollback.**

  On first read, load `AGENT.md` and the six classpath phase files into version `1` with status `published`. Draft writes a new immutable version with status `draft`; publish marks the selected draft as `published` and records the previous published version as `archived`; rollback creates a new published version whose content is copied from the selected historical version. Never update a published row in place.

- [ ] **Step 4: Add service/controller tests.**

  Cover first-read seeding, version increment, rejected unsafe content, project-only additive constraints, diff output, rollback as a new version, and controller status codes. Assert that the response never contains a synthetic_secret-looking field and that a published version remains unchanged after a subsequent draft.

- [ ] **Step 5: Run and commit.**

  ```bash
  ./mvnw -pl server/platform -Dtest=AgentRuleServiceTest,AgentRuleControllerTest test
  git add AGENT.md server/platform/src/main/resources/ai/agent server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/rule server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/rule
  git commit -m "feat: add versioned agent rules"
  ```

## Task 3: Make browser evidence and locator knowledge first-class

**Files:**
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/PlaywrightRunClient.java`
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationLocatorDiscoveryService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/locator/LocatorKnowledgeService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/locator/LocatorKnowledgeController.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/locator/LocatorKnowledgeDtos.java`
- Modify: `server/web-runner/page-locator-discovery.mjs`
- Modify: `server/web-runner/web-runner-server.mjs`
- Test: `server/platform/src/test/java/com/haitlify/aitest/platform/webautomation/WebAutomationLocatorDiscoveryServiceTest.java`
- Test: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/locator/LocatorKnowledgeServiceTest.java`
- Test: `server/web-runner/page-locator-discovery.test.mjs`

- [ ] **Step 1: Define evidence shape and runner contract.**

  The runner response for `POST /page-locator-discovery` must contain `pageUrl`, `pageFingerprint`, `collectedAt`, and `elements[]`; each element contains `evidenceId`, `naturalName`, `locator`, `role`, `accessibleName`, `elementType`, and `visible`. The server must normalize locator text without changing its meaning and reject empty or unsupported locator formats.

- [ ] **Step 2: Enforce current-browser verification.**

  Add `verifyEvidence(runContext, evidenceIds)` to `WebAutomationLocatorDiscoveryService`. It must match evidence IDs to the current page fingerprint and re-query each locator through the runner before returning a whitelist. If the fingerprint differs, mark stored entries `stale` and return a blocking result. Credentials remain in the existing environment configuration and are never copied into evidence, events, or knowledge rows.

- [ ] **Step 3: Implement knowledge grouping and lifecycle APIs.**

  Expose `/api/ai/agent-assets/locator-knowledge` with `GET` filters for `projectId`, `environmentId`, `moduleName`, `pageName`, and `keyword`; `POST /collect` for browser collection; `POST /{entryId}/revalidate`; `POST /{entryId}/stale`; and `GET /{entryId}/history`. Collection writes the project → environment → module → page → element grouping and appends history rather than overwriting old evidence.

- [ ] **Step 4: Test locator safety and persistence.**

  Test that a locator not present in the current evidence whitelist is rejected, a changed page fingerprint marks old entries stale, collection groups the same locator under the correct page, revalidation updates `lastVerifiedAt`, and secrets are absent from serialized rows. Extend the runner test with an empty page, duplicate locator, hidden element, and unsupported locator response.

- [ ] **Step 5: Run and commit.**

  ```bash
  ./mvnw -pl server/platform -Dtest=WebAutomationLocatorDiscoveryServiceTest,LocatorKnowledgeServiceTest test
  node --test server/web-runner/page-locator-discovery.test.mjs
  git add server/platform/src/main/java/com/haitlify/aitest/platform/webautomation server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/locator server/platform/src/test/java/com/haitlify/aitest/platform/webautomation server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/locator server/web-runner/page-locator-discovery.mjs server/web-runner/web-runner-server.mjs server/web-runner/page-locator-discovery.test.mjs
  git commit -m "feat: persist browser locator knowledge"
  ```

## Task 4: Build the persisted Web Case Agent state machine

**Files:**
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentRunService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentRunController.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcher.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentEventService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentSanitizer.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentRunRepository.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentRunServiceTest.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentEventServiceTest.java`

- [ ] **Step 1: Define the run endpoints and lifecycle transitions.**

  Expose:

  ```text
  POST /api/ai/web-case-agent-runs
  GET  /api/ai/web-case-agent-runs/{runId}
  GET  /api/ai/web-case-agent-runs/{runId}/events?after={sequence}
  GET  /api/ai/web-case-agent-runs/{runId}/artifacts
  POST /api/ai/web-case-agent-runs/{runId}/cancel
  POST /api/ai/web-case-agent-runs/{runId}/retry
  POST /api/ai/web-case-agent-runs/{runId}/resume
  ```

  `WebCaseAgentStartRequest` accepts `projectId`, `analysisRunId`, `pointIds`, `environmentId`, `baseUrl`, `browser`, `viewport`, and the existing login-discovery context. `POST` returns a queued `runId` immediately. Allowed transitions are `QUEUED → RUNNING → {SUCCEEDED, FAILED, BLOCKED, CANCELLED}`, `FAILED/BLOCKED/CANCELLED → RUNNING` only through retry/resume, and no endpoint may mutate a completed accepted artifact.

- [ ] **Step 2: Snapshot rules and inputs at task creation.**

  `WebCaseAgentRunService.createRun` resolves the published global and project rules, writes `rules_snapshot_json`, writes only a field-count/file-name input summary, initializes requested point IDs, and emits `STATUS:任务已创建`. The snapshot is immutable even if the UI edits rules while the run is active.

- [ ] **Step 3: Implement an asynchronous dispatcher with heartbeat and cancellation.**

  Follow the existing `AiAnalysisRunDispatcher` execution pattern. Each stage updates `last_heartbeat_at` at least every 10 seconds and checks `cancel_requested` before every model, runner, and persistence operation. Do not introduce a whole-run deadline; transport/provider failures are recorded as stage failures, while the persisted heartbeat determines whether a run is still active. Retry resumes from the first incomplete stage/point and keeps accepted artifacts.

- [ ] **Step 4: Implement append-only, sanitized events.**

  `WebCaseAgentEventService.append` assigns a monotonically increasing sequence per run and stores only stage, type, severity, summary, point ID, tool name, duration, rule version, knowledge-hit IDs, and error code. `WebCaseAgentSanitizer` must redact API keys, passwords, cookies, bearer tokens, authorization headers, absolute local paths, full document text, raw HTML, and hidden model reasoning before persistence.

- [ ] **Step 5: Test state transitions and event ordering.**

  Use a fake dispatcher and fake clock to assert immediate run creation, ordered six-stage progression, cancellation between points, heartbeat updates during a long provider call, retry preserving accepted artifacts, resume from a blocked point, event sequence monotonicity, and sanitizer redaction. Assert that a blocked preflight never calls `AiGateway` or the runner.

- [ ] **Step 6: Run and commit.**

  ```bash
  ./mvnw -pl server/platform -Dtest=WebCaseAgentRunServiceTest,WebCaseAgentEventServiceTest test
  git add server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic
  git commit -m "feat: add resumable web case agent runs"
  ```

## Task 5: Implement evidence-grounded planning, generation, validation, and delivery

**Files:**
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiAgentWebCaseGenerationService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiAgentWebCasePromptService.java`
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java`
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebAutomationCaseValidator.java`
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcher.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/draft/AiAgentWebCaseGenerationServiceTest.java`
- Modify: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/draft/WebAutomationCaseValidatorTest.java`
- Modify: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointReviewTest.java`

- [ ] **Step 1: Add the six stage contracts to the dispatcher.**

  Implement the following stage order in `WebCaseAgentDispatcher`:

  ```text
  PREFLIGHT: AI readiness + runner health + URL + browser + published rules
  EVIDENCE: model returns page intent only, runner collects current-page evidence
  PLANNING: model returns one plan per point with evidence IDs and expected outcome
  GENERATION: one point or small batch returns complete Web DSL using evidence IDs
  VALIDATION: server validator checks schema, action, URL, locator whitelist, assertion, coverage, duplicate IDs
  DELIVERY: accepted artifacts append to draft list, persist run summary, create memory candidate
  ```

  A missing page, missing evidence, unavailable Runner, or invalid rule snapshot must set the point/run to `BLOCKED` and emit a visible reason instead of sending a guessed locator to the next stage.

- [ ] **Step 2: Separate planning prompts from final generation prompts.**

  `AiAgentWebCasePromptService` must include the immutable global rule snapshot, stage rule text, point source summary, and current evidence IDs. The planning prompt must explicitly forbid locator output and final DSL. The generation prompt must require every locator-bearing action to reference an evidence ID and require at least one of `assertVisible`, `assertHidden`, `assertText`, or `assertUrl` in each case. The prompt must never include credentials, cookies, full source documents, or raw hidden reasoning.

- [ ] **Step 3: Make the validator enforce the evidence whitelist.**

  Extend `WebAutomationCaseValidator` with `validateEvidenceBoundCase(ObjectNode candidate, Set<String> evidenceLocators, Set<String> evidencePageUrls)`. For `click`, `input`, `select`, `hover`, `waitForVisible`, `assertVisible`, `assertHidden`, and `assertText`, reject any locator not in `evidenceLocators`; for `goto`, `waitForUrl`, and `assertUrl`, reject any URL not in `evidencePageUrls`. Retain existing browser, viewport, action, required-field, numbered-prose, and assertion checks. Return errors grouped by point ID so repair prompts contain only deterministic failures.

- [ ] **Step 4: Add bounded repair and per-point artifact persistence.**

  On validation failure, call the model with only the normalized candidate summary, exact validation errors, and evidence whitelist. Store every candidate and repair round in `ai_web_case_agent_artifacts`; after two repair rounds, mark the point blocked. Accepted points are appended idempotently by `artifactId` and never replace earlier accepted points. Preserve the existing `AiDraftTestPointService` fallback endpoint for clients that have not opted into Agent runs.

- [ ] **Step 5: Add regression tests for the reported failures.**

  Cover a prompt that omits the common generation rules, a second batch that must append rather than replace the first, a candidate with guessed CSS/XPath locator, a candidate without an assertion, an invalid Runner 404, a repair that succeeds on round two, a repair that remains invalid after the limit, and a cancelled run that keeps completed cases. Use the existing `AiDraftTestPointReviewTest` fixtures and assert accepted artifacts contain source point IDs and current evidence IDs.

- [ ] **Step 6: Run and commit.**

  ```bash
  ./mvnw -pl server/platform -Dtest=AiAgentWebCaseGenerationServiceTest,WebAutomationCaseValidatorTest,AiDraftTestPointReviewTest test
  git add server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcher.java server/platform/src/test/java/com/haitlify/aitest/platform/ai/draft
  git commit -m "feat: ground web case generation in browser evidence"
  ```

## Task 6: Add experience memory with human-controlled scope

**Files:**
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/memory/AgentMemoryService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/memory/AgentMemoryController.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/memory/AgentMemoryDtos.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/memory/AgentMemoryServiceTest.java`

- [ ] **Step 1: Define memory lifecycle APIs.**

  Expose `GET /api/ai/agent-assets/memories`, `POST /api/ai/agent-assets/memories/{memoryId}/confirm`, `PUT /api/ai/agent-assets/memories/{memoryId}/scope`, `POST /api/ai/agent-assets/memories/{memoryId}/disable`, and `DELETE /api/ai/agent-assets/memories/{memoryId}`. A confirm request must choose `PROJECT`, `PROJECT_SET`, or `GLOBAL`; scope changes require the actor and target project IDs.

- [ ] **Step 2: Generate short candidates only after complete delivery.**

  `AgentMemoryService.createCandidate` receives the delivered run summary, validation outcomes, locator notes, and failure summaries, then stores no more than 500 Chinese characters in categories `SUCCESS_PATTERN`, `FAILURE_LESSON`, `PLATFORM_HABIT`, `LOCATOR_NOTE`, or `DATA_PREPARATION`. Candidates start as `PENDING` and are excluded from Agent retrieval.

- [ ] **Step 3: Implement confirmation, scope changes, audit, and retrieval.**

  Confirmation changes status to `ACTIVE`; disable changes to `DISABLED`; delete changes to `DELETED` without removing the audit row. Retrieval returns only active memories whose scope matches the current project. Every mutation writes `ai_agent_memory_audit` with before/after JSON.

- [ ] **Step 4: Test scope safety.**

  Assert project memories are invisible to another project, global memories are visible to all projects, pending memories are not retrieved, a project-set scope requires an explicit non-empty project list, and all transitions remain auditable.

- [ ] **Step 5: Run and commit.**

  ```bash
  ./mvnw -pl server/platform -Dtest=AgentMemoryServiceTest test
  git add server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/memory server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/memory
  git commit -m "feat: add scoped agent memories"
  ```

## Task 7: Add the frontend Agent repository and live workspace

**Files:**
- Create: `apps/web/src/domain/webCaseAgentRepository.ts`
- Create: `apps/web/src/domain/webCaseAgentRepository.test.ts`
- Create: `apps/web/src/composables/useWebCaseAgent.ts`
- Create: `apps/web/src/composables/useWebCaseAgent.test.ts`
- Create: `apps/web/src/components/workflow/WebCaseAgentWorkspace.vue`
- Create: `apps/web/src/components/workflow/WebCaseAgentWorkspace.test.ts`
- Create: `apps/web/src/components/workflow/WebCaseAgentEventTimeline.vue`
- Create: `apps/web/src/components/workflow/WebCaseAgentDetailDrawer.vue`
- Modify: `apps/web/src/components/workflow/AiGenerationStage.vue`
- Modify: `apps/web/src/components/workflow/AiGenerationStage.test.ts`
- Modify: `apps/web/src/domain/caseGenerationBatch.ts`
- Modify: `apps/web/src/styles/studio-canvas.css`

- [ ] **Step 1: Define typed repository methods.**

  `webCaseAgentRepository.ts` must expose `startWebCaseAgentRun`, `getWebCaseAgentRun`, `listWebCaseAgentEvents(runId, afterSequence)`, `listWebCaseAgentArtifacts`, `cancelWebCaseAgentRun`, `retryWebCaseAgentRun`, and `resumeWebCaseAgentRun`. The repository must pass `AbortSignal` through `fetch`, preserve the backend error message, and never log request bodies.

- [ ] **Step 2: Implement polling and scroll-aware event state.**

  `useWebCaseAgent` starts a run, polls status and events every 1000 ms while `QUEUED` or `RUNNING`, advances `afterSequence`, stops polling on terminal status, and exposes `isAutoScrollPaused` when the user scrolls away from the bottom. It must merge accepted artifacts by `artifactId` and call the existing case append callback only for `accepted === true` artifacts.

- [ ] **Step 3: Build the workspace around the existing generation stage.**

  `WebCaseAgentWorkspace.vue` renders the current stage, status, rule version, completed/blocked counts, live event timeline, and action buttons for terminate, retry current stage, resume blocked points, and open details. `WebCaseAgentEventTimeline.vue` renders only sanitized event summaries. `WebCaseAgentDetailDrawer.vue` shows redacted input summary, knowledge hits, candidate output, repair diff, and validation errors; it must not render raw request/response text.

- [ ] **Step 4: Integrate without changing the main workflow stages.**

  Add an `agent-workspace` slot to `AiGenerationStage.vue` below the progress status and preserve the existing generation controls, generated-case table, retry button, and cancel button. `App.vue` passes the Agent workspace state and uses the existing `generatedWebCases` append/persistence path. The legacy direct generation path remains available behind a repository fallback when no Agent run is selected.

- [ ] **Step 5: Add component and repository tests.**

  Test endpoint/method/body mapping, polling sequence advancement, terminal polling stop, accepted-artifact append without duplicate IDs, cancel/retry/resume button events, live timeline rendering, auto-scroll pause/resume, drawer redaction, and disabled upload/generation controls while an Agent run is active.

- [ ] **Step 6: Run and commit.**

  ```bash
  cd apps/web
  npm run test -- src/domain/webCaseAgentRepository.test.ts src/composables/useWebCaseAgent.test.ts src/components/workflow/WebCaseAgentWorkspace.test.ts src/components/workflow/AiGenerationStage.test.ts
  npm run build
  git add apps/web/src/domain/webCaseAgentRepository.ts apps/web/src/composables apps/web/src/components/workflow/WebCaseAgentWorkspace.vue apps/web/src/components/workflow/WebCaseAgentEventTimeline.vue apps/web/src/components/workflow/WebCaseAgentDetailDrawer.vue apps/web/src/components/workflow/AiGenerationStage.vue apps/web/src/components/workflow/AiGenerationStage.test.ts apps/web/src/domain/caseGenerationBatch.ts apps/web/src/styles/studio-canvas.css
  git commit -m "feat: add live web case agent workspace"
  ```

## Task 8: Add the AI Settings Agent Assets dashboard

**Files:**
- Create: `apps/web/src/components/platformAi/AgentAssetsDashboard.vue`
- Create: `apps/web/src/components/platformAi/AgentRuleVersionPanel.vue`
- Create: `apps/web/src/components/platformAi/LocatorKnowledgePanel.vue`
- Create: `apps/web/src/components/platformAi/AgentMemoryQueue.vue`
- Create: `apps/web/src/components/platformAi/AgentAssetsDashboard.test.ts`
- Modify: `apps/web/src/domain/aiPlatformRepository.ts`
- Modify: `apps/web/src/domain/aiPlatformRepository.test.ts`
- Modify: `apps/web/src/App.vue`
- Modify: `apps/web/src/styles/studio-canvas.css`

- [ ] **Step 1: Add frontend types and API calls for rules, locator knowledge, and memories.**

  Extend `aiPlatformRepository.ts` with typed methods matching Tasks 2, 3, and 6. Use `projectId` query parameters for every project-scoped request. Add tests for query encoding, publish/rollback payloads, collect/revalidate actions, memory confirmation, and scope updates.

- [ ] **Step 2: Build the dashboard layout.**

  `AgentAssetsDashboard.vue` uses the approved overview dashboard layout: summary counts at the top, left project/environment/module/page tree for locator knowledge, center table for current entries, and right-side detail drawer for rule version diff or memory details. Keep the existing light Studio Canvas palette; expose CSS custom properties for the future Violet/deep-blue action color without making purple the page background.

- [ ] **Step 3: Implement editable rules with release controls.**

  `AgentRuleVersionPanel.vue` supports draft editing, format/safety validation result, publish, historical version list, side-by-side diff, and rollback. Disable publish until all six phase rules pass validation and display the immutable version used by active runs.

- [ ] **Step 4: Implement locator tree and memory queue.**

  `LocatorKnowledgePanel.vue` groups entries by project → environment → module → page, displays status `verified/stale`, allows revalidate, stale marking, history, and collect-current-page. It must never expose a manual “mark locator verified” input. `AgentMemoryQueue.vue` lists pending candidates and provides confirm, scope selection, disable, and delete with audit feedback.

- [ ] **Step 5: Integrate the new settings page.**

  Add `agentAssets` to `PlatformAiPage` and `platformAiPages` in `App.vue`, place “Agent 资产” next to existing AI configuration pages, and render `AgentAssetsDashboard` in the platform AI canvas. Keep the existing Agent/Skill/MCP extension table for compatibility, but make the new dashboard the primary entry point for rules, locator knowledge, and memories.

- [ ] **Step 6: Test and commit.**

  ```bash
  cd apps/web
  npm run test -- src/domain/aiPlatformRepository.test.ts src/components/platformAi/AgentAssetsDashboard.test.ts
  npm run build
  git add apps/web/src/domain/aiPlatformRepository.ts apps/web/src/domain/aiPlatformRepository.test.ts apps/web/src/components/platformAi apps/web/src/App.vue apps/web/src/styles/studio-canvas.css
  git commit -m "feat: add agent assets dashboard"
  ```

## Task 9: End-to-end integration, recovery checks, and release evidence

**Files:**
- Modify: `apps/web/src/App.vue`
- Modify: `apps/web/src/components/workflow/AiGenerationStage.test.ts`
- Create: `apps/web/e2e/web-case-agent.spec.ts`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentIntegrationTest.java`
- Create: `docs/superpowers/plans/agentic-web-case-generation-acceptance.md`

- [ ] **Step 1: Wire the actual generate action to Agent runs.**

  Update the existing `generateWebCasesWithAi` flow so it sends selected confirmed point IDs and the current Web environment to `startWebCaseAgentRun`, uses `useWebCaseAgent` for progress, and leaves `generatedWebCases` unchanged until accepted artifacts arrive. Preserve the current batch retry and cancellation labels for users; map Agent terminal states to the existing `AiGenerationStage` status values.

- [ ] **Step 2: Add backend integration coverage.**

  `WebCaseAgentIntegrationTest` must run the six stages with fake AI and Runner adapters, assert rule snapshot/version traceability, current-browser locator evidence, accepted-artifact append across two batches, memory candidate creation, cancellation/resume, and blocked preflight. Assert no event or artifact contains `apiKey`, `password`, `Cookie`, or complete source text.

- [ ] **Step 3: Add browser acceptance coverage.**

  `web-case-agent.spec.ts` must open the Web test-design flow, select confirmed points, start generation, observe the workspace status and appended event summaries, open the detail drawer, cancel, resume, and verify accepted cases remain in the table. It must also open AI 设置 → Agent 资产, publish a rule draft, view a locator group, and confirm a memory with project scope.

- [ ] **Step 4: Produce an acceptance checklist.**

  `docs/superpowers/plans/agentic-web-case-generation-acceptance.md` must record the exact commands, expected service URLs (`http://127.0.0.1:8080` and `http://127.0.0.1:9323`), test counts, UI checkpoints, and known intentional behavior: Agent never runs the final Web case automatically and stale locator evidence blocks generation.

- [ ] **Step 5: Run the complete focused verification.**

  ```bash
  ./mvnw -pl server/platform -Dtest='*WebCaseAgent*Test,*AgentRule*Test,*LocatorKnowledge*Test,*AgentMemory*Test,*WebAutomationCaseValidatorTest' test
  cd apps/web
  npm run test -- src/domain/webCaseAgentRepository.test.ts src/composables/useWebCaseAgent.test.ts src/components/workflow/WebCaseAgentWorkspace.test.ts src/components/platformAi/AgentAssetsDashboard.test.ts src/components/workflow/AiGenerationStage.test.ts
  npm run build
  npx playwright test e2e/web-case-agent.spec.ts
  ```

  Expected: all selected backend/frontend tests pass, build exits `0`, and the browser test records the live workspace and asset dashboard without console errors.

- [ ] **Step 6: Review the complete diff and commit the integration.**

  Run `git diff --check`, inspect staged names, confirm no `.env`, API key, cookie, document payload, or generated local artifact is staged, then commit with `git commit -m "feat: integrate agentic web case generation"`.

## Plan self-review

- Spec coverage: Tasks 1–2 cover `AGENT.md`, six phase rules, publishing, history, diff, rollback, and immutable run snapshots; Tasks 3 and 5 cover browser evidence, locator knowledge, current-page verification, no guessed locator, assertions, repair, and batch append; Task 4 covers asynchronous state, heartbeats, cancellation, retry, resume, and sanitized event order; Task 6 covers pending memories and project/project-set/global scope; Tasks 7–9 cover the generation workspace, detail drawer, scroll behavior, settings dashboard, and end-to-end acceptance.
- Placeholder scan: every step contains concrete files, state values, endpoint names, test commands, and expected results; no step relies on an unspecified future decision.
- Type consistency: `WebCaseAgentStage`, `WebCaseAgentRunStatus`, event sequence, `artifactId`, `pointId`, `evidenceIds`, `ruleVersion`, `scopeType`, and accepted-artifact semantics are defined in Task 1 and reused unchanged in later tasks.
- Scope check: the rule, locator, memory, and run services share the same Agent asset APIs and immutable run snapshot, so they remain separate implementation tasks within one user-visible workflow rather than unrelated projects.

> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# Self-Hosted Test Agent V5 Multi-Agent Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a controlled multi-Agent review workflow that separates requirement understanding, test design, quality review, and automation suggestions while keeping one merged, source-linked, human-reviewed draft output.

**Architecture:** Build on V1 analysis runs, V2 tool calling, V3 visual analysis, and V4 Figma evidence. V5 introduces role-specific Agent definitions, inter-Agent handoff records, critique/repair loops, consensus merging, quality scoring, and conflict resolution. Multi-Agent output still enters the same draft-review model and cannot bypass human confirmation.

**Tech Stack:** Spring Boot 3.5.x, Java 21, Spring Data JDBC, Flyway, MySQL/H2, existing AI Gateway, existing Skill/tool/MCP infrastructure, Vue 3, TypeScript, Element Plus, Vite, Vitest.

---

## Prerequisites

Required:

- V1 AI foundation is complete.
- Draft review gate is enforced.
- AI call records and analysis runs exist.
- Skills and rules are versioned.

Recommended:

- V2 tool-calling and long-document workflow.
- V3 visual analysis.
- V4 Figma evidence.

## V5 Scope

### In Scope

- Role-specific Agents:
  - Requirement Understanding Agent,
  - Test Design Agent,
  - Quality Review Agent,
  - Automation Suggestion Agent,
  - Merge Coordinator Agent.
- Agent run graph and handoff records.
- Critique/repair loop.
- Cross-Agent conflict detection.
- Consensus merge.
- Quality scoring:
  - coverage,
  - source traceability,
  - testability,
  - duplicate risk,
  - automation readiness.
- Multi-Agent run UI.
- Human review remains mandatory.

### Out Of Scope

- Fully autonomous project mutation.
- Automatic Jira issue creation.
- Direct MCP write execution without user confirmation.
- Replacing human review.
- Training or fine-tuning models.

## V5 Data Model Additions

Create migration:

- `server/platform/src/main/resources/db/migration/V7__ai_multi_agent_review.sql`

Tables:

```text
ai_agent_definitions
ai_agent_run_graphs
ai_agent_run_nodes
ai_agent_handoffs
ai_agent_critiques
ai_agent_conflicts
ai_agent_consensus_records
ai_quality_scores
ai_automation_suggestions
```

Important fields:

```text
run_id
agent_id
agent_role
status
input_snapshot_json
output_snapshot_json
handoff_payload_json
critique_json
conflict_type
resolution_status
quality_dimension
score
score_reason
created_at
updated_at
```

## V5 API Contract

Agent definitions:

```text
GET /api/ai/agents
GET /api/ai/agents/{agentId}
PUT /api/ai/agents/{agentId}/override
```

Multi-Agent runs:

```text
POST /api/ai/multi-agent-runs
GET  /api/ai/multi-agent-runs/{runId}
GET  /api/ai/multi-agent-runs/{runId}/graph
GET  /api/ai/multi-agent-runs/{runId}/handoffs
GET  /api/ai/multi-agent-runs/{runId}/conflicts
GET  /api/ai/multi-agent-runs/{runId}/quality-scores
GET  /api/ai/multi-agent-runs/{runId}/automation-suggestions
POST /api/ai/multi-agent-runs/{runId}/merge
```

Conflict resolution:

```text
POST /api/ai/agent-conflicts/{conflictId}/resolve
POST /api/ai/agent-conflicts/{conflictId}/ignore
```

## Task 1: Agent Definitions And Role Registry

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agent/AiAgentDefinition.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agent/AiAgentRegistry.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agent/AiAgentController.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/agent/AiAgentControllerTest.java`

**Deliverable:**

- Built-in Agent definitions exist for each role.
- Agent definitions have version, prompt template, allowed tools, output schema, and enabled flag.
- Built-in Agents can be overridden like built-in Skills.

- [x] **Step 1: Write failing tests**

Test:

- `GET /api/ai/agents` returns five built-in Agents,
- each Agent has role and output schema,
- overriding an Agent changes effective prompt,
- restoring override returns built-in prompt.

- [x] **Step 2: Implement built-in registry**

Agents:

```text
requirement-understanding
test-design
quality-review
automation-suggestion
merge-coordinator
```

- [x] **Step 3: Implement Agent APIs**

Endpoints:

```text
GET /api/ai/agents
GET /api/ai/agents/{agentId}
PUT /api/ai/agents/{agentId}/override
```

- [x] **Step 4: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiAgentControllerTest
```

Expected:

- PASS.

## Task 2: Multi-Agent Run Graph

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agent/AiMultiAgentRunController.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agent/AiMultiAgentRunService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agent/AiAgentRunGraph.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agent/AiAgentRunNode.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/agent/AiMultiAgentRunServiceTest.java`

**Deliverable:**

- Multi-Agent run creates graph nodes for each Agent role.
- Nodes execute in controlled order.
- Each node stores input/output snapshots.
- Failed node fails the run without saving malformed draft output.

- [x] **Step 1: Write failing tests**

Test:

- creating multi-Agent run creates graph with five nodes,
- nodes execute in role order,
- failed quality-review node marks run failed,
- successful run has merge-ready state.

- [x] **Step 2: Implement run graph**

Default order:

```text
Requirement Understanding -> Test Design -> Quality Review -> Automation Suggestion -> Merge Coordinator
```

- [x] **Step 3: Implement run APIs**

Endpoints:

```text
POST /api/ai/multi-agent-runs
GET  /api/ai/multi-agent-runs/{runId}
GET  /api/ai/multi-agent-runs/{runId}/graph
```

- [x] **Step 4: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiMultiAgentRunServiceTest
```

Expected:

- PASS.

## Task 3: Agent Handoffs And Critique Loop

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agent/AiAgentHandoffService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agent/AiAgentCritiqueService.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/agent/AiAgentHandoffCritiqueTest.java`

**Deliverable:**

- Each Agent receives a bounded input from previous Agent output.
- Quality Review Agent can critique Test Design output.
- One controlled repair pass can be requested.
- Critique records are persisted.

- [x] **Step 1: Write failing tests**

Test:

- requirement-understanding output is handed to test-design,
- quality-review critique records missing source evidence,
- repair pass updates test-design output,
- repair pass count is capped at one by default.

- [x] **Step 2: Implement handoff records**

Handoff payload:

```text
fromAgentId
toAgentId
sourceRunNodeId
targetRunNodeId
payloadSummary
payloadJson
createdAt
```

- [x] **Step 3: Implement critique loop**

Default critique gates:

```text
source evidence missing
expected result not verifiable
duplicate validation target
case kind mismatch
automation readiness missing
```

- [x] **Step 4: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiAgentHandoffCritiqueTest
```

Expected:

- PASS.

## Task 4: Conflict Detection And Consensus Merge

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agent/AiAgentConflictService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agent/AiAgentConsensusMergeService.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/agent/AiAgentConsensusMergeServiceTest.java`

**Deliverable:**

- Conflicting outputs are detected before draft persistence.
- Merge Coordinator creates one deduplicated, source-linked draft output.
- Unresolved conflicts are visible to users.

- [x] **Step 1: Write failing tests**

Test:

- conflicting priority creates conflict record,
- conflicting expected result creates conflict record,
- duplicate test points merge,
- unresolved conflict blocks final merge,
- resolved conflict allows merge.

- [x] **Step 2: Implement conflict detection**

Conflict types:

```text
priority_conflict
expected_result_conflict
source_evidence_conflict
case_kind_conflict
duplicate_target
automation_suggestion_conflict
```

- [x] **Step 3: Implement merge API**

Endpoint:

```text
POST /api/ai/multi-agent-runs/{runId}/merge
```

Merged output still creates normal draft test points with review status:

```text
ai_generated
```

- [x] **Step 4: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiAgentConsensusMergeServiceTest
```

Expected:

- PASS.

## Task 5: Quality Scoring

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/quality/AiQualityScoreService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/quality/AiQualityScoreController.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/quality/AiQualityScoreServiceTest.java`

**Deliverable:**

- Multi-Agent output gets quality scores.
- Scores are stored by dimension and shown in run detail.
- Low scores create warnings, not automatic rejection.

- [x] **Step 1: Write failing tests**

Test:

- missing source evidence lowers traceability score,
- duplicate targets lower duplication score,
- missing expected result lowers testability score,
- automation-ready structured steps improve automation readiness score.

- [x] **Step 2: Implement quality dimensions**

Dimensions:

```text
coverage
source_traceability
testability
duplicate_risk
automation_readiness
review_risk
```

- [x] **Step 3: Implement quality API**

Endpoint:

```text
GET /api/ai/multi-agent-runs/{runId}/quality-scores
```

- [x] **Step 4: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiQualityScoreServiceTest
```

Expected:

- PASS.

## Task 6: Automation Suggestions

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/automation/AiAutomationSuggestionService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/automation/AiAutomationSuggestionController.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/automation/AiAutomationSuggestionServiceTest.java`

**Deliverable:**

- Automation Suggestion Agent can propose automation readiness information.
- Suggestions do not directly create automation cases.
- Suggestions can be attached to draft test points for reviewer reference.

- [x] **Step 1: Write failing tests**

Test:

- Web case suggestion includes page, locator needs, waits, and assertions,
- API case suggestion includes request data, assertions, and environment needs,
- suggestion is attached to draft point,
- suggestion does not bypass review.

- [x] **Step 2: Implement suggestion schema**

Fields:

```text
draftPointId
caseKind
automationReadiness
requiredData
requiredEnvironment
suggestedSteps
suggestedAssertions
risks
```

- [x] **Step 3: Implement suggestion API**

Endpoint:

```text
GET /api/ai/multi-agent-runs/{runId}/automation-suggestions
```

- [x] **Step 4: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiAutomationSuggestionServiceTest
```

Expected:

- PASS.

## Task 7: Frontend Multi-Agent Run UI

**Files:**

- Create: `apps/web/src/domain/aiMultiAgentRunView.ts`
- Create: `apps/web/src/domain/aiMultiAgentRunView.test.ts`
- Modify: `apps/web/src/App.vue`
- Modify: `apps/web/src/styles.css`

**Deliverable:**

- User can start multi-Agent analysis mode.
- Run graph is visible.
- Handoffs, critiques, conflicts, quality scores, and automation suggestions are visible.
- Merge output enters existing draft review UI.

- [x] **Step 1: Write frontend domain tests**

Test:

- graph nodes sort by execution order,
- conflict rows group by type,
- quality scores map to display levels,
- automation suggestions group by case kind.

- [x] **Step 2: Implement UI**

UI sections:

```text
Agent运行图
交接记录
质量评审
冲突列表
自动化建议
合并结果
```

- [x] **Step 3: Run frontend tests**

Run:

```bash
cd apps/web
npm test -- src/domain/aiMultiAgentRunView.test.ts --run
npm test -- --run
npm run build
```

Expected:

- PASS.

## Task 8: Multi-Agent Smoke Verification

**Files:**

- Modify only if tests expose defects.

- [x] **Step 1: Backend full test**

Run:

```bash
cd server/platform
./mvnw test
```

Expected:

- PASS.

Verified:

- `cd server/platform && ./mvnw test`
- Result: PASS, 130 tests, 0 failures, 0 errors, 0 skipped.

- [x] **Step 2: Frontend full test and build**

Run:

```bash
cd apps/web
npm test -- --run
npm run build
```

Expected:

- PASS.

Verified:

- `cd apps/web && npm test -- --run`
- Result: PASS, 60 files, 237 tests.
- `cd apps/web && npm run build`
- Result: PASS. Vite reported existing Rollup PURE annotation warnings from `@vueuse/core` and a chunk-size warning.

- [x] **Step 3: Manual smoke**

Verify:

```text
启动多Agent分析
查看Agent运行图
查看需求理解输出
查看测试设计输出
查看质量审查意见
查看自动化建议
处理冲突
执行合并
合并结果进入待评审测试点
未确认测试点不能入库
确认后的测试点可以生成用例
```

Expected:

- Multi-Agent improves quality without bypassing human review.

Verified:

- Started backend on `APP_PORT=18080` with H2 test datasource and all 7 Flyway migrations applied.
- First startup attempt fell back to default MySQL and failed; fixed by enabling test classpath and explicitly setting the H2 driver/datasource.
- API smoke created a temporary project, listed 5 built-in Agents, started a multi-Agent run, verified 5 graph nodes, 4 handoffs, 1 critique, successful merge, 6 quality scores, 1 automation suggestion, and an `ai_generated` draft point.
- Human review gate verified: generating formal cases from the unconfirmed `ai_generated` draft returned HTTP 400; after confirm, case generation returned 1 generated case.
- Conflict smoke verified: conflicting priority blocked merge, conflict list returned `priority_conflict`, ignoring the conflict allowed merge, and the merged output deduplicated to 1 draft candidate.

## Post-Task 8 Continuation: Frontend Merge Closure

- [x] Added frontend repository methods for `POST /api/ai/multi-agent-runs/{runId}/merge` and `POST /api/ai/agent-conflicts/{conflictId}/ignore`.
- [x] Added merge-candidate mapping from AI draft test points, preserving target key, priority, expected result, source evidence, and structured steps.
- [x] Added Web generation workflow actions for refreshing multi-Agent review, ignoring unresolved conflicts, and merging multi-Agent output into `ai_generated` draft test points.
- [x] Merge success refreshes the existing AI draft review table so human confirmation remains mandatory before formal case generation.

Verified:

- `cd apps/web && npm test -- src/domain/aiMultiAgentRunRepository.test.ts src/domain/aiMultiAgentRunView.test.ts src/appShellStructure.test.ts --run`
- Result: PASS, 3 files, 15 tests.
- `cd apps/web && npm test -- --run`
- Result: PASS, 60 files, 240 tests.
- `cd apps/web && npm run build`
- Result: PASS. Vite reported the existing Rollup PURE annotation warnings from `@vueuse/core` and a chunk-size warning.

## Completion Gate

V5 is complete when:

- Built-in role Agents exist and can be overridden.
- Multi-Agent run graph executes in controlled order.
- Agent handoffs and critique records are persisted.
- Conflicts are detected and must be resolved or ignored intentionally.
- Consensus merge produces deduplicated, source-linked draft points.
- Quality scores are visible.
- Automation suggestions are visible but do not directly create automation cases.
- Frontend shows run graph, conflicts, scores, suggestions, and merge result.
- Human review gate remains mandatory.
- Backend tests pass.
- Frontend tests and build pass.

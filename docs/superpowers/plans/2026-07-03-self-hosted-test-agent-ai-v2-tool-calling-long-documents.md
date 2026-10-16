> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# Self-Hosted Test Agent V2 Tool Calling And Long Documents Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the V1 AI Agent from a fixed single-pass workflow into a controlled tool-calling and long-document analysis workflow with chunk-level processing, merge/deduplication, coverage scanning, custom Skills, MCP read-only tools, and project-level quota aggregation.

**Architecture:** Keep V1's backend-owned AI Gateway, analysis runs, draft review, evidence, Skill, and rule models. Add an Agent tool registry, read-only MCP execution boundary, semantic chunk planning, multi-pass analysis, output merge/dedupe services, coverage summary, schema repair, and background worker execution while preserving the mandatory human review gate.

**Tech Stack:** Spring Boot 3.5.x, Java 21, Spring Data JDBC, Flyway, MySQL/H2, Vue 3, TypeScript, Element Plus, Vite, Vitest, OpenAI-compatible structured output, existing V1 AI Agent tables and services.

---

## Prerequisites

This plan starts only after V1 is complete:

- Backend AI config is backend-owned and masked.
- Built-in Skill override works.
- Rule configuration exists.
- Source documents and chunks are persisted.
- Analysis runs and call records exist.
- Draft test points require review.
- Formal generation only accepts `confirmed` and `modified`.

## V2 Scope

### In Scope

- Agent tool registry.
- Rule extractors exposed as internal Agent tools.
- Read-only MCP tool execution with allowlists and audit.
- Custom Skill creation and version comparison.
- Semantic chunk planning for long documents.
- Multi-pass analysis:
  - chunk analysis,
  - merge,
  - deduplicate,
  - coverage scan,
  - unclear question list.
- JSON Schema repair pass.
- Background queue/worker for analysis runs.
- Chunk-level retry and partial resume.
- Project/time-window quota aggregation.

### Out Of Scope

- MCP write operations.
- Vision model analysis.
- Figma structured node import.
- Multi-Agent role split.
- Automatic formal case-library entry without review.

## V2 Data Model Additions

Create a new migration:

- `server/platform/src/main/resources/db/migration/V4__ai_agent_tool_calling.sql`

Tables:

```text
ai_agent_tools
ai_tool_call_records
ai_analysis_chunks
ai_analysis_merge_records
ai_coverage_summaries
ai_unclear_questions
ai_quota_usage
```

Add fields if missing:

```text
ai_analysis_runs.execution_mode
ai_analysis_runs.worker_status
ai_analysis_runs.current_chunk_id
ai_analysis_runs.resume_from_chunk_id
ai_analysis_runs.coverage_status
ai_skills.skill_kind
ai_skills.is_custom
```

## V2 API Contract

Tools:

```text
GET  /api/ai/tools
GET  /api/ai/tools/{toolId}
POST /api/ai/tools/{toolId}/dry-run
```

Custom Skills:

```text
POST /api/ai/skills
PUT  /api/ai/skills/{skillId}
GET  /api/ai/skills/{skillId}/versions
GET  /api/ai/skills/{skillId}/diff?fromVersion=1&toVersion=2
```

Analysis:

```text
POST /api/ai/analysis-runs/{runId}/plan-chunks
POST /api/ai/analysis-runs/{runId}/resume
GET  /api/ai/analysis-runs/{runId}/coverage-summary
GET  /api/ai/analysis-runs/{runId}/unclear-questions
GET  /api/ai/analysis-runs/{runId}/tool-calls
```

Quota:

```text
GET /api/projects/{projectId}/ai-quota-usage
```

## Task 1: Tool Registry And Internal Rule Tools

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/tool/AiTool.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/tool/AiToolRegistry.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/tool/AiToolController.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/tool/internal/ExtractTextStructureTool.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/tool/internal/ExtractRequirementKeywordsTool.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/tool/internal/ExtractFieldRulesTool.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/tool/internal/ExtractStateRulesTool.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/tool/AiToolControllerTest.java`

**Deliverable:**

- Internal deterministic extractors are listed as Agent tools.
- Tools expose input/output schemas.
- Dry-run works for internal read-only tools.
- Tool calls are not allowed to mutate data.

- [ ] **Step 1: Write failing tests**

Test:

- `GET /api/ai/tools` lists internal tools.
- `POST /api/ai/tools/extract-requirement-keywords/dry-run` returns explicit operations from sample text.
- Disabled or unknown tool returns validation error.

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiToolControllerTest
```

Expected:

- FAIL before tool registry exists.

- [ ] **Step 2: Implement registry**

Register tools:

```text
extract-text-structure
extract-requirement-keywords
extract-field-rules
extract-state-rules
match-case-template
check-coverage-gaps
```

- [ ] **Step 3: Implement dry-run controller**

Dry-run accepts text/chunk payload and returns structured JSON. Do not write database state during dry-run.

- [ ] **Step 4: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiToolControllerTest
```

Expected:

- PASS.

## Task 2: Read-Only MCP Tool Execution Boundary

**Files:**

- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/mcp/AiMcpServerService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/tool/mcp/AiMcpToolExecutor.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/tool/mcp/AiMcpToolCallRecord.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/tool/mcp/AiMcpToolCallRepository.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/tool/mcp/AiMcpToolExecutorTest.java`

**Deliverable:**

- MCP tools can be executed only when:
  - server is enabled,
  - tool is allowlisted,
  - tool is read-only,
  - project scope matches,
  - execution count limit is not exceeded.
- Write tools remain blocked.
- Tool calls are audited separately from AI model calls.

- [ ] **Step 1: Write failing tests**

Test:

- read-only allowlisted tool executes,
- non-allowlisted tool is blocked,
- write tool is blocked,
- project-scope mismatch is blocked,
- successful and blocked calls create audit records.

- [ ] **Step 2: Implement sandbox checks**

Sandbox checks run before tool execution:

```text
enabled
projectScope
toolAllowlist
read/write classification
maxCallsPerRun
timeout
```

- [ ] **Step 3: Implement audit records**

Record:

```text
runId
serverId
toolName
readOnly
status
requestSummary
responseSummary
errorMessage
actorId
createdAt
```

- [ ] **Step 4: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiMcpToolExecutorTest
```

Expected:

- PASS.

## Task 3: Custom Skills And Version Comparison

**Files:**

- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/skill/AiSkillController.java`
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/skill/AiSkillService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/skill/AiSkillVersion.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/skill/AiSkillVersionRepository.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/skill/AiCustomSkillControllerTest.java`

**Deliverable:**

- Users can create custom Skills.
- Updating a Skill creates a version record.
- Skill version diff can compare prompt, schema, tools, and applicable case kinds.
- Built-in override behavior from V1 remains unchanged.

- [ ] **Step 1: Write failing tests**

Test:

- create custom Skill,
- update custom Skill increments version,
- get versions returns history,
- diff endpoint identifies changed prompt and tools,
- built-in restore default still works.

- [ ] **Step 2: Implement custom Skill APIs**

Endpoints:

```text
POST /api/ai/skills
PUT  /api/ai/skills/{skillId}
GET  /api/ai/skills/{skillId}/versions
GET  /api/ai/skills/{skillId}/diff?fromVersion=1&toVersion=2
```

- [ ] **Step 3: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiCustomSkillControllerTest,AiSkillControllerTest
```

Expected:

- PASS.

## Task 4: Semantic Chunk Planning

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/analysis/chunk/AiChunkPlanner.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/analysis/chunk/AiAnalysisChunk.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/analysis/chunk/AiAnalysisChunkRepository.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/analysis/chunk/AiChunkPlannerTest.java`

**Deliverable:**

- Long source documents are split into semantic analysis chunks.
- Chunk metadata records section, table/image marker, token estimate, and evidence reference.
- The UI/API can report skipped or capped chunks.

- [ ] **Step 1: Write failing chunk planner tests**

Test:

- markdown headings produce separate chunks,
- tables stay together,
- very long sections split by size,
- skipped chunks are marked when max analysis chunks is exceeded.

- [ ] **Step 2: Implement planner**

Planner inputs:

```text
source document chunks
maxInputTokens
maxChunks
analysisScope
```

Planner outputs:

```text
plannedChunkId
sourceChunkIds
section
tokenEstimate
status: planned | skipped
skipReason
```

- [ ] **Step 3: Add analysis run API**

Endpoint:

```text
POST /api/ai/analysis-runs/{runId}/plan-chunks
```

- [ ] **Step 4: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiChunkPlannerTest
```

Expected:

- PASS.

## Task 5: Multi-Pass Analysis, Merge, Deduplicate, Coverage

**Files:**

- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/analysis/AiAnalysisRunService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/analysis/AiDraftMergeService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/analysis/AiCoverageScanService.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/analysis/AiMultiPassAnalysisTest.java`

**Deliverable:**

- Analysis runs process chunks individually.
- Draft points are merged and deduplicated by validation target and evidence.
- Coverage summary is produced by module, role, state, operation, field, and case kind.
- Unclear questions are persisted.

- [ ] **Step 1: Write failing tests**

Test:

- two chunks producing the same validation target merge into one draft point,
- coverage summary counts modules and operations,
- missing precondition creates unclear question,
- skipped chunk appears in coverage warnings.

- [ ] **Step 2: Implement merge/dedupe service**

Deduplication key:

```text
projectId
caseKind
module
pageOrFeature
testObjective
expectedResult
evidenceHash
```

- [ ] **Step 3: Implement coverage scan**

Coverage dimensions:

```text
module
role
state
operation
field
caseKind
exceptionFlow
boundary
```

- [ ] **Step 4: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiMultiPassAnalysisTest
```

Expected:

- PASS.

## Task 6: JSON Schema Repair

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/gateway/AiStructuredOutputValidator.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/gateway/AiStructuredOutputRepairService.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/gateway/AiStructuredOutputRepairServiceTest.java`

**Deliverable:**

- Invalid model output triggers one controlled repair pass.
- Repair pass uses the same schema and original model output.
- If repair fails, no draft points are persisted and the run fails clearly.

- [ ] **Step 1: Write failing tests**

Test:

- malformed JSON repaired successfully,
- missing required field repaired successfully,
- unrecoverable output fails and creates call record.

- [ ] **Step 2: Implement repair service**

Repair prompt includes:

```text
original invalid output
validation errors
target JSON schema
instruction to return JSON only
```

- [ ] **Step 3: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiStructuredOutputRepairServiceTest
```

Expected:

- PASS.

## Task 7: Background Worker, Resume, And Quota Aggregation

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/analysis/AiAnalysisWorker.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/quota/AiQuotaService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/quota/AiQuotaController.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/analysis/AiAnalysisWorkerTest.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/quota/AiQuotaServiceTest.java`

**Deliverable:**

- Analysis run can be queued and processed by backend worker.
- Failed chunk can resume from chunk id.
- Project quota usage aggregates calls/tokens/cost by time window.

- [ ] **Step 1: Write failing worker tests**

Test:

- queued run becomes running then succeeded,
- failed chunk stores resume point,
- resume continues from failed chunk,
- project quota aggregates token usage.

- [ ] **Step 2: Implement worker**

V2 can use in-process worker first. External queues are not required.

- [ ] **Step 3: Implement quota endpoint**

Endpoint:

```text
GET /api/projects/{projectId}/ai-quota-usage
```

- [ ] **Step 4: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiAnalysisWorkerTest,AiQuotaServiceTest
```

Expected:

- PASS.

## Task 8: Frontend Long-Document And Coverage UI

**Files:**

- Modify: `apps/web/src/App.vue`
- Create: `apps/web/src/domain/aiAnalysisCoverageView.ts`
- Create: `apps/web/src/domain/aiAnalysisCoverageView.test.ts`
- Modify: `apps/web/src/styles.css`

**Deliverable:**

- Analysis run page shows chunk progress.
- Coverage summary is visible.
- Unclear questions are visible.
- Retry/resume actions are available for failed runs.
- Tool call records can be opened from run detail.

- [ ] **Step 1: Write frontend domain tests**

Test:

- coverage rows are grouped by module and case kind,
- skipped chunks produce warning rows,
- unclear questions are sorted by severity.

- [ ] **Step 2: Implement UI**

Use compact operational layout:

```text
run status
chunk progress
coverage summary
unclear questions
tool call records
retry/resume actions
```

- [ ] **Step 3: Run frontend tests**

Run:

```bash
cd apps/web
npm test -- src/domain/aiAnalysisCoverageView.test.ts --run
npm test -- --run
npm run build
```

Expected:

- PASS.

## Completion Gate

V2 is complete when:

- Internal rule extractors are callable as Agent tools.
- Read-only MCP tools can execute only through allowlisted sandbox rules.
- Custom Skills can be created, versioned, and compared.
- Long documents are chunk-planned without silent truncation.
- Multi-pass analysis merges and deduplicates draft points.
- Coverage summary and unclear questions are persisted and visible.
- Invalid structured output gets one repair attempt.
- Background worker can process and resume analysis runs.
- Project quota usage is visible.
- All backend tests pass.
- All frontend tests and build pass.

> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# Self-Hosted Test Agent AI Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the first production-shaped AI integration slice: backend-owned AI configuration, editable built-in Skills, rule configuration, source-document evidence, AI analysis runs, draft test-point review, and confirmed-only case generation.

**Architecture:** Add AI as a backend-first platform capability. The frontend provides configuration and review workflows, while `server/platform` owns credentials, source documents, AI Gateway calls, analysis runs, draft persistence, review audit, and rule enforcement. V1 uses a fixed workflow-style `需求测试点分析 Agent` and an OpenAI-compatible provider adapter; MCP execution, deep vision, custom Skills, and background workers are reserved for later versions.

**Tech Stack:** Vue 3, TypeScript, Element Plus, Vite, Vitest, Spring Boot 3.5.x, Java 21, Spring Web, Spring Data JDBC, Flyway, MySQL runtime, H2 tests, Apache Tika, OpenAI-compatible HTTP API.

---

## Source Documents

- Spec: `docs/superpowers/specs/2026-07-03-self-hosted-test-agent-ai-integration-design.md`
- Decision log: `docs/ai-test-platform/decisions.md`
- Related roadmap: `docs/superpowers/plans/2026-06-22-web-automation-case-library-roadmap.md`

## Current Baseline

- Frontend already has a placeholder `AI 配置` page and localStorage config:
  - `apps/web/src/App.vue`
  - `apps/web/src/domain/aiConfigRepository.ts`
  - `apps/web/src/domain/aiConfigRepository.test.ts`
- Backend already has:
  - projects,
  - template config,
  - interface case library,
  - requirement document parsing,
  - Figma parsing,
  - Jira readonly proxy,
  - Web automation runner proxy.
- AI-sensitive data must be backend-first. Do not keep real API keys, AI call records, source documents, draft test points, or review actions as localStorage-only data.
- MCP V1 is configuration only. Do not execute MCP tools in this plan.
- V1 may execute analysis inline behind an analysis-run API, but the data model must already expose run status, retry metadata, and cancellation marker.

## V1 Scope

### In Scope

- Backend AI configuration with masked credential responses.
- OpenAI-compatible connection test.
- Built-in Skill list with editable override and restore default.
- Rule configuration for mandatory review, source evidence, allowed case kinds, and formal-generation allowed statuses.
- MCP configuration entry with sandbox fields, execution disabled by default.
- Source document persistence and chunk/evidence records.
- AI analysis run records.
- AI call records.
- Fixed workflow `需求测试点分析 Agent`.
- Structured output validation before draft persistence.
- Draft test-point review list.
- Review actions: confirm, edit-confirm, reject.
- Formal generation only from `confirmed` or `modified` draft test points.

### Out Of Scope

- Real MCP tool execution.
- Claude provider adapter.
- Vision model adapter.
- Embedding/RAG.
- Background queue/worker.
- Chunk-level resume.
- Custom Skill creation UI beyond the data model.
- Full enterprise login. V1 uses an actor abstraction with development/system fallback.

## V1 Data Model

Create a new Flyway migration after the current latest migration:

- `server/platform/src/main/resources/db/migration/V3__ai_agent_foundation.sql`

Tables:

```text
ai_system_configs
ai_project_configs
ai_rules
ai_skills
ai_skill_overrides
ai_mcp_servers
ai_source_documents
ai_source_chunks
ai_analysis_runs
ai_draft_test_points
ai_review_actions
ai_call_records
```

Minimum indexed fields:

```text
project_id
status
run_id
document_id
created_at
updated_at
```

Use `TEXT` JSON payload columns for flexible snapshots:

```text
provider_config_json
masked_config_json
rule_snapshot_json
skill_snapshot_json
schema_snapshot_json
input_summary_json
output_summary_json
raw_payload_json
```

Do not store full provider API keys in response payloads.

## V1 API Contract

System/project config:

```text
GET  /api/ai/config
PUT  /api/ai/config
POST /api/ai/config/test-connection
GET  /api/projects/{projectId}/ai-config
PUT  /api/projects/{projectId}/ai-config
```

Rules:

```text
GET /api/ai/rules
PUT /api/ai/rules
```

Skills:

```text
GET    /api/ai/skills
GET    /api/ai/skills/{skillId}
PUT    /api/ai/skills/{skillId}/override
DELETE /api/ai/skills/{skillId}/override
```

MCP config:

```text
GET    /api/ai/mcp-servers
POST   /api/ai/mcp-servers
PUT    /api/ai/mcp-servers/{serverId}
DELETE /api/ai/mcp-servers/{serverId}
```

Source documents:

```text
POST /api/ai/source-documents
GET  /api/ai/source-documents/{documentId}
GET  /api/ai/source-documents/{documentId}/chunks
```

Analysis runs:

```text
POST /api/ai/analysis-runs
GET  /api/ai/analysis-runs/{runId}
GET  /api/projects/{projectId}/ai-analysis-runs
POST /api/ai/analysis-runs/{runId}/retry
POST /api/ai/analysis-runs/{runId}/cancel
```

Draft test points:

```text
GET  /api/ai/analysis-runs/{runId}/draft-test-points
PUT  /api/ai/draft-test-points/{pointId}
POST /api/ai/draft-test-points/{pointId}/confirm
POST /api/ai/draft-test-points/{pointId}/reject
POST /api/ai/draft-test-points/generate-cases
```

Call records:

```text
GET /api/ai/call-records
GET /api/ai/call-records/{recordId}
```

## Actor Model For V1

Until full authentication exists, backend AI services should resolve a development actor:

```text
actor_id: system-dev
actor_name: Development User
```

Every config change, Skill override, rule update, MCP update, review action, analysis run, and AI call record must store actor fields. Do not leave actor fields blank.

## Task 0: Baseline Verification

**Files:**

- Read only.

- [ ] **Step 1: Record current git state**

Run:

```bash
git status --short
```

Expected:

- Existing frontend and documentation changes are visible.
- Unrelated `docs/*.html` exports remain untouched.

- [ ] **Step 2: Run frontend tests and build**

Run:

```bash
cd apps/web
npm test -- --run
npm run build
```

Expected:

- If tests/build pass, record that as the AI implementation baseline.
- If they fail, record exact failures before changing AI code.

- [ ] **Step 3: Run backend tests**

Run:

```bash
cd server/platform
./mvnw test
```

Expected:

- Backend tests pass, or pre-existing failures are recorded before AI implementation starts.

## Task 1: Backend AI Foundation Migration

**Files:**

- Create: `server/platform/src/main/resources/db/migration/V3__ai_agent_foundation.sql`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/AiSchemaMigrationTest.java`

**Deliverable:**

- Flyway migration creates V1 AI tables.
- H2 test profile can start with the migration.
- Core query fields are first-class columns.
- Flexible snapshots use `TEXT` JSON columns.

- [ ] **Step 1: Write migration smoke test**

Create `server/platform/src/test/java/com/haitlify/aitest/platform/ai/AiSchemaMigrationTest.java`:

```java
package com.haitlify.aitest.platform.ai;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AiSchemaMigrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void createsAiAgentFoundationTables() {
        Set<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'PUBLIC'",
                String.class
            )
            .stream()
            .map(String::toLowerCase)
            .collect(Collectors.toSet());

        assertTrue(tables.contains("ai_system_configs"));
        assertTrue(tables.contains("ai_project_configs"));
        assertTrue(tables.contains("ai_rules"));
        assertTrue(tables.contains("ai_skills"));
        assertTrue(tables.contains("ai_skill_overrides"));
        assertTrue(tables.contains("ai_mcp_servers"));
        assertTrue(tables.contains("ai_source_documents"));
        assertTrue(tables.contains("ai_source_chunks"));
        assertTrue(tables.contains("ai_analysis_runs"));
        assertTrue(tables.contains("ai_draft_test_points"));
        assertTrue(tables.contains("ai_review_actions"));
        assertTrue(tables.contains("ai_call_records"));
    }
}
```

- [ ] **Step 2: Run test and verify it fails**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiSchemaMigrationTest
```

Expected:

- FAIL because the AI tables do not exist.

- [ ] **Step 3: Add migration**

Create `server/platform/src/main/resources/db/migration/V3__ai_agent_foundation.sql` with tables named in the data model section.

Implementation constraints:

- Use `BIGINT PRIMARY KEY AUTO_INCREMENT`.
- Use `TIMESTAMP NOT NULL` for `created_at` and `updated_at`.
- Use `VARCHAR` columns for query fields such as status, provider, model, skill id, run id, and actor id.
- Use `TEXT` columns for JSON snapshots.
- Add foreign keys to `projects(id)` where `project_id` is present.
- Add indexes for project/status/run/time list queries.

- [ ] **Step 4: Run migration test**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiSchemaMigrationTest
```

Expected:

- PASS.

## Task 2: AI Config Backend And Connection Test

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/config/AiConfigController.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/config/AiConfigService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/config/AiSystemConfig.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/config/AiSystemConfigRepository.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/gateway/AiGateway.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/gateway/AiProviderAdapter.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/gateway/OpenAiCompatibleAdapter.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/config/AiConfigControllerTest.java`

**Deliverable:**

- Backend stores AI config.
- API key is masked on read.
- Connection test calls provider through the gateway.
- Failed connection returns safe error without exposing secrets.

- [ ] **Step 1: Write config controller tests**

Create tests for:

- default config returns disabled and no API key,
- saving config returns masked key only,
- loading saved config does not reveal full key,
- connection test uses gateway and returns success/failure.

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiConfigControllerTest
```

Expected:

- FAIL before controller/service exist.

- [ ] **Step 2: Implement backend config service**

Implement:

```text
GET  /api/ai/config
PUT  /api/ai/config
POST /api/ai/config/test-connection
```

Rules:

- `apiKey` may be accepted on PUT.
- GET never returns full `apiKey`.
- Response uses `credentialConfigured` and optional `maskedApiKey`.
- Existing key is retained when PUT omits `apiKey`.

- [ ] **Step 3: Implement OpenAI-compatible adapter**

V1 adapter behavior:

- `testConnection` sends a minimal chat/completions request.
- Uses configured `baseUrl`, `model`, and API key.
- Applies timeout from config.
- Maps non-2xx response to a platform error.

- [ ] **Step 4: Run backend tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiConfigControllerTest
```

Expected:

- PASS.

## Task 3: Built-In Skills, Overrides, And Restore Default

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/skill/AiSkillController.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/skill/AiSkillService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/skill/AiBuiltInSkills.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/skill/AiSkillOverride.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/skill/AiSkillOverrideRepository.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/skill/AiSkillControllerTest.java`

**Deliverable:**

- Built-in `需求测试点分析` Skill exists.
- Built-in Skill shows `内置`.
- Editing creates override and shows `内置 / 已自定义`.
- Restore default removes override.

- [ ] **Step 1: Write Skill tests**

Test cases:

- list returns built-in `requirement-test-point-analysis`,
- override changes prompt/schema fields,
- effective Skill merges built-in plus override,
- delete override restores built-in default.

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiSkillControllerTest
```

Expected:

- FAIL before Skill controller/service exist.

- [ ] **Step 2: Implement built-in Skill registry**

Built-in Skill minimum fields:

```text
id: requirement-test-point-analysis
name: 需求测试点分析
isBuiltIn: true
enabled: true
version: 1
applicableCaseKinds: interface, web_automation, app_automation, ai_test, functional, performance
outputJsonSchema: draft test point schema
```

- [ ] **Step 3: Implement override API**

Endpoints:

```text
GET    /api/ai/skills
GET    /api/ai/skills/{skillId}
PUT    /api/ai/skills/{skillId}/override
DELETE /api/ai/skills/{skillId}/override
```

- [ ] **Step 4: Run Skill tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiSkillControllerTest
```

Expected:

- PASS.

## Task 4: Rules And MCP Configuration

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/rule/AiRuleController.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/rule/AiRuleService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/mcp/AiMcpServerController.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/mcp/AiMcpServerService.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/rule/AiRuleControllerTest.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/mcp/AiMcpServerControllerTest.java`

**Deliverable:**

- Rules can be loaded and saved.
- Hard defaults enforce mandatory review and allowed formal statuses.
- MCP config can be created/read/updated/deleted.
- MCP execution remains disabled by default.

- [ ] **Step 1: Write tests**

Rules tests:

- default rules contain `forcedHumanReview=true`,
- default allowed formal statuses are `confirmed` and `modified`,
- saving rules increments version.

MCP tests:

- create server with `writeOperationsAllowed=false`,
- read server,
- update allowlist,
- delete server.

- [ ] **Step 2: Implement rules API**

Endpoints:

```text
GET /api/ai/rules
PUT /api/ai/rules
```

- [ ] **Step 3: Implement MCP config API**

Endpoints:

```text
GET    /api/ai/mcp-servers
POST   /api/ai/mcp-servers
PUT    /api/ai/mcp-servers/{serverId}
DELETE /api/ai/mcp-servers/{serverId}
```

V1 rule:

- no endpoint executes MCP tools.

- [ ] **Step 4: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiRuleControllerTest,AiMcpServerControllerTest
```

Expected:

- PASS.

## Task 5: Source Documents And Evidence Chunks

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/document/AiSourceDocumentController.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/document/AiSourceDocumentService.java`
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/document/RequirementDocumentService.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/document/AiSourceDocumentControllerTest.java`

**Deliverable:**

- Uploading a source document creates durable document and chunk records.
- Chunks retain file name, document type, text snippet, hash, parser version, and evidence metadata.
- Existing `/api/requirement-documents/parse` behavior remains compatible.

- [ ] **Step 1: Write source document tests**

Test cases:

- upload text requirement creates document id,
- chunks can be listed,
- content hash is stable for same file content,
- document metadata includes parser version and actor.

- [ ] **Step 2: Implement source document API**

Endpoint:

```text
POST /api/ai/source-documents
GET  /api/ai/source-documents/{documentId}
GET  /api/ai/source-documents/{documentId}/chunks
```

- [ ] **Step 3: Reuse existing parser**

Refactor parsing so AI source document service can reuse existing Tika parsing and visual summary logic without breaking existing controller tests.

- [ ] **Step 4: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiSourceDocumentControllerTest,RequirementDocumentControllerTest
```

Expected:

- PASS.

## Task 6: Analysis Runs, Gateway Calls, And Draft Test Points

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/analysis/AiAnalysisRunController.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/analysis/AiAnalysisRunService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointController.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/call/AiCallRecordService.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/analysis/AiAnalysisRunControllerTest.java`

**Deliverable:**

- Creating an analysis run resolves config, rules, Skill, source chunks, and template context.
- V1 runs inline and stores `succeeded` or `failed`.
- AI output is schema-validated before draft persistence.
- Draft test points reference evidence chunks.
- Call records are stored for success and failure.

- [ ] **Step 1: Write analysis run tests**

Test cases:

- create run from uploaded document creates `succeeded` run and draft points when gateway returns valid JSON,
- malformed JSON creates failed run and no draft points,
- retry failed run creates a new attempt or updates retry metadata,
- cancel marks a non-complete run as `cancelled`.

- [ ] **Step 2: Implement fixed workflow Agent**

Workflow:

```text
load source chunks
load rules
load effective Skill
load AI config
assemble prompt
call structuredGenerate
validate output JSON
persist draft test points
persist call record
update run status
```

- [ ] **Step 3: Implement run APIs**

Endpoints:

```text
POST /api/ai/analysis-runs
GET  /api/ai/analysis-runs/{runId}
GET  /api/projects/{projectId}/ai-analysis-runs
POST /api/ai/analysis-runs/{runId}/retry
POST /api/ai/analysis-runs/{runId}/cancel
```

- [ ] **Step 4: Implement draft list API**

Endpoint:

```text
GET /api/ai/analysis-runs/{runId}/draft-test-points
```

- [ ] **Step 5: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiAnalysisRunControllerTest
```

Expected:

- PASS.

## Task 7: Review Actions And Formal Generation Gate

**Files:**

- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointController.java`
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointReviewTest.java`

**Deliverable:**

- Draft points support edit, confirm, and reject.
- Review actions are append-only.
- Formal generation rejects unreviewed points.
- Formal generation accepts `confirmed` and `modified`.

- [ ] **Step 1: Write review gate tests**

Test cases:

- confirming draft changes status to `confirmed` and writes review action,
- edit-confirm changes status to `modified` and preserves before/after content,
- rejecting draft excludes it from generation,
- generating cases from `ai_generated` returns validation error,
- generating cases from `confirmed` succeeds.

- [ ] **Step 2: Implement review APIs**

Endpoints:

```text
PUT  /api/ai/draft-test-points/{pointId}
POST /api/ai/draft-test-points/{pointId}/confirm
POST /api/ai/draft-test-points/{pointId}/reject
POST /api/ai/draft-test-points/generate-cases
```

- [ ] **Step 3: Implement rule gate**

Generation service must enforce:

```text
allowed statuses: confirmed, modified
source evidence required: true
```

- [ ] **Step 4: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiDraftTestPointReviewTest
```

Expected:

- PASS.

## Task 8: Frontend AI Repository Adapters

**Files:**

- Create: `apps/web/src/domain/aiPlatformRepository.ts`
- Create: `apps/web/src/domain/aiPlatformRepository.test.ts`
- Modify: `apps/web/src/domain/aiConfigRepository.ts`
- Modify: `apps/web/src/domain/aiConfigRepository.test.ts`

**Deliverable:**

- Frontend talks to backend AI APIs.
- API key is not stored as source of truth in localStorage for real AI integration.
- Repository exposes config, Skill, rule, MCP, source document, analysis run, draft point, and review methods.

- [ ] **Step 1: Write repository tests**

Test:

- save config sends API key to backend,
- load config receives masked key,
- create analysis run posts document ids and options,
- confirm/reject draft point calls backend.

- [ ] **Step 2: Implement repository**

Add typed functions for V1 endpoints.

- [ ] **Step 3: Run tests**

Run:

```bash
cd apps/web
npm test -- src/domain/aiPlatformRepository.test.ts src/domain/aiConfigRepository.test.ts --run
```

Expected:

- PASS.

## Task 9: Frontend AI Configuration Page

**Files:**

- Modify: `apps/web/src/App.vue`
- Modify: `apps/web/src/styles.css`
- Modify: `apps/web/src/appShellStructure.test.ts`

**Deliverable:**

- `AI 配置` page has tabs:
  - 模型配置,
  - Agent配置,
  - 规则配置,
  - Skill配置,
  - MCP配置,
  - 调用记录.
- Built-in Skills show `内置`.
- Edited built-in Skills show `内置 / 已自定义`.
- MCP config shows sandbox fields and disabled-by-default write operations.

- [ ] **Step 1: Extend structure tests**

Update `apps/web/src/appShellStructure.test.ts` to assert:

- AI page contains all tabs,
- template settings page does not contain AI config,
- Skill tab contains `内置`,
- MCP tab contains `写操作默认关闭`.

- [ ] **Step 2: Implement UI**

Keep layout consistent with current Element Plus settings pages.

- [ ] **Step 3: Run frontend tests**

Run:

```bash
cd apps/web
npm test -- src/appShellStructure.test.ts --run
```

Expected:

- PASS.

## Task 10: Frontend Requirement AI Analysis And Review Flow

**Files:**

- Modify: `apps/web/src/App.vue`
- Create: `apps/web/src/domain/aiDraftTestPointView.ts`
- Create: `apps/web/src/domain/aiDraftTestPointView.test.ts`
- Modify: `apps/web/src/styles.css`

**Deliverable:**

- Upload requirement files to backend source-document API.
- `AI分析需求` creates an analysis run.
- Run status is visible and recoverable.
- Draft test points are listed with evidence, confidence, inferred flag, and status.
- User can confirm, edit-confirm, and reject.
- `生成用例` only uses confirmed/modified points.

- [ ] **Step 1: Write view-domain tests**

Test filtering/grouping draft points by:

- module,
- status,
- confidence,
- source document.

- [ ] **Step 2: Implement review UI**

Add review table/drawer in the generation page.

Required visible fields:

```text
模块
测试点
来源
置信度
状态
是否推断
操作
```

- [ ] **Step 3: Wire actions**

Connect:

- upload source document,
- create analysis run,
- load draft points,
- confirm,
- reject,
- edit-confirm,
- generate cases.

- [ ] **Step 4: Run tests**

Run:

```bash
cd apps/web
npm test -- src/domain/aiDraftTestPointView.test.ts --run
npm test -- --run
npm run build
```

Expected:

- PASS.

## Task 11: End-To-End Verification

**Files:**

- Modify only if failures expose defects.

- [ ] **Step 1: Backend full test**

Run:

```bash
cd server/platform
./mvnw test
```

Expected:

- PASS.

- [ ] **Step 2: Frontend full test and build**

Run:

```bash
cd apps/web
npm test -- --run
npm run build
```

Expected:

- PASS.

- [ ] **Step 3: Manual smoke**

Run services as usual, then verify:

```text
AI配置 -> 保存模型配置 -> 连接测试
AI配置 -> Skill配置 -> 编辑内置Skill -> 显示 内置 / 已自定义 -> 恢复默认
AI配置 -> 规则配置 -> 强制评审开启
AI配置 -> MCP配置 -> 写操作默认关闭
用例生成 -> 上传需求文件 -> AI分析需求 -> 生成待评审测试点
待评审测试点 -> 确认/修改确认/拒绝
生成用例 -> 只允许 confirmed/modified
```

Expected:

- Unreviewed AI output cannot enter the formal case library.

## Implementation Notes

- Keep backend package boundaries under `com.haitlify.aitest.platform.ai`.
- Prefer small service/controller classes over expanding existing large classes.
- Keep existing requirement parsing APIs backward compatible.
- Do not execute MCP tools in V1.
- Do not store real AI credentials in frontend localStorage as source of truth.
- Keep the defect module paused.
- Do not modify unrelated exported HTML docs under `docs/*.html` or their `_files` directories.

## Completion Gate

V1 is complete when:

- Backend AI config is saved and connection-tested.
- Full credentials are backend-owned and masked in frontend responses.
- Built-in Skill override and restore default work.
- Rules enforce mandatory review and source evidence.
- MCP config exists with sandbox fields and no execution.
- Source documents and evidence chunks are persisted.
- AI analysis creates recoverable analysis runs.
- AI calls are recorded.
- Malformed AI output is rejected before draft persistence.
- Review actions are audited with actor and before/after content.
- Draft output enters review status only.
- Formal generation accepts only `confirmed` and `modified`.
- Backend tests pass.
- Frontend tests and build pass.

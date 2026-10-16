> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# Self-Hosted Test Agent AI Integration Design

> **Status:** Planning only. This document records the agreed AI integration direction for requirement analysis, test-point extraction, and test-case generation. Implementation should start from a separate task plan after review.

**Decision date:** 2026-07-03.

**Goal:** Add AI capability through a platform-owned testing-domain Agent, not by embedding an external coding Agent as the product runtime. The Agent must generate reviewable drafts only; formal test points and test cases must pass human review confirmation before entering the case library.

---

## 1. Confirmed Direction

The platform will use a self-hosted testing Agent architecture:

```text
AI Test Platform
  -> AI Gateway
  -> Platform-owned testing Agent
  -> Configurable model providers
```

Do not make Codex, Claude Code, opencode, or similar coding-agent CLIs the runtime core of the product.

Those tools can still help developers build the platform, but the product runtime needs platform-owned control over:

- project and user permissions,
- document and attachment security,
- source traceability,
- review status,
- output schema validation,
- model provider switching,
- cost and token limits,
- audit logs,
- retry and failure handling,
- future private model or internal gateway integration.

MeterSphere is a product reference for the user-facing direction: AI assists with generating test assets inside the platform workflow, and generated content remains editable and reviewable instead of bypassing the testing workflow.

## 2. Runtime Boundary

The first AI runtime should be a backend service boundary:

```text
Frontend
  -> Requirement document upload
  -> Backend document parsing
  -> Rule-based extraction tools
  -> AI Gateway
  -> Requirement Test Point Analysis Agent
  -> Draft test points
  -> Human review confirmation
  -> Test case generation
  -> Case library
```

The frontend must not call external AI providers directly. API keys, model routing, request logging, and provider fallback belong on the backend.

## 3. AI Gateway

Add an `AI Gateway` abstraction before binding to any concrete model provider.

The gateway should expose provider-neutral operations:

```text
chat
structuredGenerate
visionAnalyze
embedding
```

V1 only needs:

- `chat`
- `structuredGenerate`

Reserved for later:

- `visionAnalyze` for screenshots, UI designs, and Figma previews,
- `embedding` for long-document retrieval, requirement reuse, and similarity checks.

Provider order:

1. OpenAI-compatible provider first.
   - This supports OpenAI, internal gateways, OneAPI, LiteLLM, Qwen-compatible gateways, DeepSeek-compatible gateways, and many private deployments through one adapter.
2. Claude API adapter second.
   - Useful for long requirement understanding and high-quality test design, but it should be a provider adapter rather than the platform architecture.
3. Vision model adapter.
   - Used for screenshots, UI design images, and later Figma-derived visual analysis.
4. Local or private model adapter.
   - Used for sensitive requirement documents that cannot leave the company network.

## 4. First Agent

The first Agent should be:

```text
Requirement Test Point Analysis Agent
```

Chinese UI label:

```text
需求测试点分析 Agent
```

This is a workflow-style Agent, not a fully autonomous coding Agent.

Responsibilities:

- read normalized document parsing results,
- consume rule-based extraction results,
- read selected project and case template information,
- generate structured draft test points,
- identify unclear or untestable requirement gaps,
- provide coverage summary and risk notes,
- attach evidence sources to every generated item.

Non-responsibilities in V1:

- directly writing formal cases into the case library,
- directly executing automation,
- changing templates,
- changing project data,
- mutating defects or Jira records,
- autonomously deciding that a draft is final.

## 5. Rule-Based Extraction As AI Tooling

The existing normal analysis path should not be removed. It becomes a stable tool layer for AI.

V1 backend orchestration:

```text
1. Parse document.
2. Run rule-based extractors.
3. Build Agent input from parsed document, rule results, selected template, and user options.
4. Call the AI model once or in controlled chunks.
5. Validate structured JSON.
6. Save results as draft review items.
```

This is intentionally simpler than model-native function calling and easier to debug.

Later versions can expose these as real callable tools:

- `extractTextStructure`
- `extractRequirementKeywords`
- `extractFieldRules`
- `extractStateRules`
- `extractUiElements`
- `matchCaseTemplate`
- `validateGeneratedPoints`
- `checkCoverageGaps`

## 6. Mandatory Review Confirmation

AI output must pass through a hard review gate.

Allowed AI-created states:

```text
ai_generated
reviewing
rejected
```

Formal case generation may only use:

```text
confirmed
modified
```

The platform must not allow `ai_generated` items to enter the formal case library directly.

Review page requirements:

- show test points grouped by module, page, flow, or requirement section,
- show the evidence source for each item,
- allow edit, merge, split, ignore, and confirm,
- mark AI-inferred items separately from directly sourced items,
- show confidence and pending-confirmation flags,
- preserve human modification records.

## 7. Draft Test Point Contract

Every AI-generated test point should carry enough metadata for traceability and review.

Suggested fields:

```text
id
title
module
pageOrFeature
caseKind
testType
priority
precondition
testObjective
suggestedSteps
expectedResult
sourceFile
sourceSection
sourcePage
sourceText
sourceImageId
extractionMethod
confidence
isInferred
reviewStatus
reviewComment
templateId
createdBy
createdAt
updatedAt
```

`extractionMethod` values:

```text
rule
ai
rule_plus_ai
human
```

`reviewStatus` values:

```text
ai_generated
reviewing
confirmed
modified
rejected
```

## 8. AI Configuration

AI configuration must be a dedicated navigation module, not part of template configuration.

Recommended navigation structure:

```text
AI配置
  - 模型配置
  - Agent配置
  - 规则配置
  - Skill配置
  - MCP配置
  - 调用记录
```

System-level settings:

- AI enabled/disabled,
- provider type,
- Base URL,
- API Key or credential reference,
- text model,
- vision model,
- embedding model,
- request timeout,
- max input length,
- max output length,
- retry count,
- whether request/response logs are stored,
- whether document content may be sent to the configured provider,
- connection test.

Project-level settings:

- whether AI is enabled for the project,
- default requirement-analysis model,
- default case-generation model,
- default analysis strategy:
  - conservative,
  - balanced,
  - expanded,
- enabled case kinds:
  - interface,
  - web,
  - app,
  - ai,
  - functional,
  - performance,
- forced human review.

`forced human review` should be always on for formal case-library entry.

Task-level settings shown when clicking `AI分析需求`:

- target case kind,
- selected template,
- document analysis scope,
- whether to include images,
- whether to generate only test points,
- whether to also create draft cases,
- whether to list unclear requirements.

## 9. Agent Rule Configuration

The Agent needs an explicit rule configuration entry.

Rules define hard platform boundaries and review requirements. They are not only prompt text.

Rule configuration should cover:

- forced human review,
- source evidence requirement for every generated test point,
- whether AI-inferred content must be marked as pending confirmation,
- allowed case kinds,
- forbidden output patterns such as source-less assertions or cases without expected results,
- priority mapping rules,
- template field mapping rules,
- coverage dimension requirements,
- formal case-library entry rules,
- external model data-sending policy for sensitive documents.

Default hard rule:

```text
Only confirmed or human-modified test points can generate formal cases.
```

## 10. Skill Configuration

The Agent needs a Skill configuration entry.

Skills are reusable testing capability packages, such as:

- requirement test-point analysis,
- interface case generation,
- Web case generation,
- UI design analysis,
- Figma design parsing,
- permission testing,
- form testing,
- list/search/filter testing,
- file upload/import/export testing,
- exception-flow and boundary-value analysis.

Skill fields:

```text
id
name
description
tags
applicableCaseKinds
applicableFileTypes
promptTemplate
outputJsonSchema
availableTools
examples
enabled
version
isBuiltIn
baseSkillId
updatedBy
updatedAt
```

Built-in Skills must be editable, but the UI must clearly mark them as:

```text
内置
```

Editing a built-in Skill should create a project/system override instead of destroying the original built-in definition.

Recommended behavior:

- built-in Skill rows show an `内置` tag,
- edited built-in Skills show `内置 / 已自定义`,
- users can restore a built-in Skill to the system default,
- system upgrades may update the original built-in definition without silently deleting user overrides,
- calls should resolve the effective Skill by applying the user override over the built-in base,
- audit logs should record who edited the Skill and when.

V1 should support:

- listing built-in Skills,
- editing built-in Skill prompt/rule/schema fields through an override,
- showing `内置` and `已自定义` tags,
- restoring defaults,
- enabling/disabling Skills.

Custom Skills can be added in a later V1.x or V2 slice if needed, but the data model should already distinguish:

```text
built-in Skill
built-in Skill with override
custom Skill
```

## 11. MCP Configuration

The Agent needs an MCP configuration entry.

MCP is the integration surface for external tools and systems, such as:

- Figma,
- Jira,
- Confluence,
- GitHub or GitLab,
- Apifox or Postman,
- read-only databases,
- document parsing services,
- OCR and visual recognition services,
- Web automation runners,
- internal knowledge bases,
- object storage.

MCP configuration should include:

```text
name
type
endpoint
authMode
availableTools
toolAllowlist
projectScope
writeOperationsAllowed
timeout
enabled
auditEnabled
```

Default safety rule:

```text
MCP write operations are disabled unless explicitly enabled for a specific trusted tool and project scope.
```

V1 may keep MCP as a configured placeholder entry, but the architecture must reserve this boundary so later Agent tool-calling does not need to be redesigned.

## 12. Implementation Gaps And Required Solutions

This section records known implementation gaps before execution starts. Each item includes the required solution so future implementation does not get blocked or take an unsafe shortcut.

### 12.1 API Key And Credential Safety

Gap:

- The current frontend AI configuration is local-only and can store API keys in browser storage.
- That is acceptable for a placeholder, but not for real AI integration.

Required solution:

- Real provider credentials must be saved by the backend.
- The frontend may show only masked credential state, such as `已配置` or the last four characters.
- API keys must not be returned in full from backend APIs.
- Prefer environment variables or an encrypted credential store for production.
- Local development may use database storage first, but the model must allow later migration to Vault, KMS, or company synthetic_secret management.

V1 acceptance:

- Saving AI config through the backend does not echo the full API key.
- Loading AI config returns a masked credential indicator.
- Connection test uses the stored backend credential.

### 12.2 Configuration Scope

Gap:

- AI settings can become ambiguous when system defaults, project overrides, and task-time options all exist.

Required solution:

- Split configuration into three scopes:
  - system-level defaults,
  - project-level overrides,
  - task-level runtime options.
- Use a deterministic resolution order:

```text
task option -> project override -> system default -> built-in default
```

- Store the resolved configuration snapshot with each AI call record so later audits can explain which model, rules, and Skills were used.

V1 acceptance:

- System config exists.
- Project config can at least enable or disable AI and choose default model/strategy.
- Task-time analysis can select target case kind and template.

### 12.3 Built-In Skill Version And Override Governance

Gap:

- Built-in Skills must be editable, but direct editing would destroy the system baseline and make upgrades risky.

Required solution:

- Store built-in Skill definitions separately from user overrides.
- Editing a built-in Skill creates an override record:

```text
baseSkillId
overrideId
scope
overriddenFields
version
updatedBy
updatedAt
```

- Runtime resolves the effective Skill by overlaying the override on top of the built-in base.
- The UI must show:
  - `内置`,
  - `内置 / 已自定义`,
  - `自定义`.
- Built-in Skills must support restore-to-default by deleting or disabling the override.
- When a system upgrade changes the built-in base, existing overrides remain explicit and are not silently deleted.

V1 acceptance:

- A built-in Skill can be edited.
- The original default can be restored.
- The UI distinguishes built-in, customized built-in, and custom concepts even if custom Skill creation is deferred.

### 12.4 Rule Enforcement Points

Gap:

- Rules are easy to show in UI but easy to bypass if case generation or save APIs do not enforce them.

Required solution:

- Treat rules as backend-enforced policy.
- Enforce these gates in both frontend and backend:
  - AI-generated test points cannot enter the formal case library directly.
  - Only `confirmed` and `modified` test points can generate formal cases.
  - Formal generated cases must retain source traceability.
  - Source-less assertions should be rejected or marked as pending confirmation.
- Add backend validation at the formal case generation endpoint, not only at the review UI.

V1 acceptance:

- Calling the case-generation API with `ai_generated`, `reviewing`, or `rejected` test points fails with a clear validation error.
- Confirmed or modified test points can generate cases through the selected template.

### 12.5 Draft Review Persistence

Gap:

- AI-generated test points need a storage area separate from the formal case library.

Required solution:

- Add a draft-review model for requirement analysis output.
- Persist draft test points with review status, source evidence, model metadata, and human modification metadata.
- Do not reuse formal saved-case tables as a draft area.

Suggested persistence concepts:

```text
ai_analysis_runs
ai_draft_test_points
ai_draft_test_point_reviews
```

Minimum data:

- analysis run id,
- project id,
- source document id or source file metadata,
- target case kind,
- template id,
- extraction method,
- review status,
- source evidence,
- confidence,
- created/updated timestamps.

V1 acceptance:

- AI output can be reopened after page refresh.
- Review status survives refresh.
- Rejected items are retained for audit but excluded from generation.

### 12.6 Evidence And Source Traceability

Gap:

- Without evidence metadata, AI-generated test points are hard to trust and hard to review.

Required solution:

- Create a requirement evidence model before or alongside AI analysis.
- Evidence must support:
  - source file,
  - document type,
  - section heading,
  - page number when available,
  - line or paragraph range when available,
  - table identifier when available,
  - image id or region id when available,
  - Figma node id later.
- Every AI-generated test point must point to one or more evidence records.
- AI-inferred items without direct evidence must be marked `isInferred=true` and `reviewStatus=reviewing` or `ai_generated`.

V1 acceptance:

- Text-based documents provide at least file name and section/text snippet evidence.
- Image-based outputs preserve image file metadata and visual-summary evidence.
- The review UI shows source evidence for each test point.

### 12.7 AI Call Records And Audit

Gap:

- Without AI call records, failures, cost, model drift, and user disputes are hard to debug.

Required solution:

- Store an AI call record for each request.
- Record:
  - provider,
  - model,
  - resolved configuration snapshot id,
  - Agent id,
  - Skill ids and versions,
  - rule set version,
  - input summary,
  - output summary,
  - request id from provider when available,
  - duration,
  - status,
  - error code/message,
  - token usage and estimated cost when available,
  - created by,
  - created at.
- Avoid storing full sensitive document text by default unless audit policy explicitly allows it.

V1 acceptance:

- Successful and failed AI calls appear in `调用记录`.
- Failed calls expose enough error detail for troubleshooting without leaking secrets.

### 12.8 Structured Output Validation And Repair

Gap:

- AI output can be malformed, incomplete, or inconsistent with the expected JSON contract.

Required solution:

- Define JSON Schema for Agent outputs.
- Validate every AI response before saving draft test points.
- On validation failure:
  - try one controlled repair call if the provider is reachable,
  - otherwise store a failed call record and return a clear user-facing error.
- Never save malformed AI output as draft test points.

V1 acceptance:

- Invalid JSON does not enter draft-review storage.
- The user sees a clear failure message.
- The call record preserves validation failure details.

### 12.9 Long Document Handling

Gap:

- Large Word/PDF/Markdown/Excel documents may exceed model context limits and produce shallow extraction if sent as one blob.

Required solution:

- Split parsed documents into semantic chunks:
  - heading sections,
  - tables,
  - pages,
  - images,
  - API blocks,
  - flow descriptions.
- Run rule extraction on each chunk.
- For V1, analyze selected chunks or capped chunks with clear coverage notices.
- For V2, add multi-pass analysis:

```text
chunk analysis -> merge -> deduplicate -> coverage scan -> review draft
```

- Always show uncovered or skipped sections when the document is too large.

V1 acceptance:

- The system does not silently truncate long documents.
- If only part of a document is analyzed, the UI shows the analyzed range and skipped range.

### 12.10 MCP Safety

Gap:

- MCP can connect to powerful external systems and may introduce unsafe writes.

Required solution:

- Default MCP mode is configured-but-disabled for execution.
- Real MCP tools require:
  - project authorization,
  - tool allowlist,
  - read/write classification,
  - timeout,
  - audit logging,
  - user-visible confirmation for write operations.
- Write operations are disabled by default.
- Dangerous operations such as Jira mutation, repository writes, database writes, and production system operations require explicit allowlist and confirmation.

V1 acceptance:

- MCP configuration entry exists.
- Tool execution is deferred or disabled by default.
- The data model has fields for allowlist, project scope, and write permission.

### 12.11 User Identity And Permission Boundary

Gap:

- The current backend foundation does not yet include a complete user identity, role, or permission boundary.
- AI configuration, Skill editing, MCP write permissions, review confirmation, and audit records all need a user identity to be trustworthy.

Required solution:

- Add a minimal actor model for V1 even if full enterprise authentication is deferred.
- Every AI-sensitive operation should accept or resolve:
  - actor id,
  - actor display name,
  - project id,
  - operation scope.
- Protect operations by role or capability:
  - AI config synthetic_user,
  - Skill editor,
  - rule editor,
  - MCP synthetic_user,
  - reviewer,
  - case generator.
- If full login is not available in V1, use a development/system actor explicitly named in audit records instead of leaving actor fields blank.
- The data model must not assume a single-user product.

V1 acceptance:

- AI config changes, Skill overrides, rule changes, review actions, and MCP config changes all record an actor.
- The code path can later replace the development/system actor with real authentication without changing table shapes.

### 12.12 Asynchronous Analysis Runs

Gap:

- Long requirement documents, images, and Figma parsing can take longer than a normal synchronous request.
- A synchronous-only API makes cancellation, retries, progress, and partial failure handling difficult.

Required solution:

- Model AI analysis as an `analysis run` even if the first implementation executes immediately.
- Use explicit statuses:

```text
queued
running
succeeded
failed
cancelled
partial
```

- Store run progress:
  - total chunks,
  - completed chunks,
  - current stage,
  - error summary,
  - started/finished timestamps.
- Frontend should poll or refresh run status instead of assuming the request always finishes in one response.
- V1 may execute synchronously behind the API, but it should still create and return an analysis run id.

V1 acceptance:

- `AI分析需求` creates an analysis run.
- Refreshing the page can recover the run result by id.
- Failed runs remain visible with a retry action and failure reason.

### 12.13 Source Document Persistence

Gap:

- Requirement files are currently parsed, but uploaded documents do not yet become durable source records.
- Evidence traceability and re-analysis need stable document ids, hashes, chunks, and parse metadata.

Required solution:

- Add source document persistence before or with AI analysis.
- Store:
  - document id,
  - project id,
  - file name,
  - document type,
  - content hash,
  - storage location or external reference,
  - extracted text,
  - parse metadata,
  - parser version,
  - created by,
  - created at.
- Store chunks separately so long-document analysis can reference stable chunk ids.
- Use MinIO or another object store for original files when binary retention is enabled.
- If original file retention is disabled, still store metadata, hash, and extracted text/chunks according to retention policy.

V1 acceptance:

- Each AI analysis run references one or more persisted source document records.
- Draft test points reference document/chunk evidence instead of only raw file names.

### 12.14 Cost, Quota, And Rate Limits

Gap:

- AI calls can be expensive and slow.
- Without limits, one large document or repeated retry loop can consume excessive tokens or provider quota.

Required solution:

- Add system and project-level limits:
  - max calls per day,
  - max input tokens per call,
  - max output tokens per call,
  - max concurrent analysis runs,
  - max retries,
  - request timeout,
  - optional estimated cost limit.
- Enforce limits in the AI Gateway before calling the provider.
- Record token usage when providers return it.
- Show clear user-facing errors when a quota is exceeded.

V1 acceptance:

- Gateway rejects requests that exceed configured max input/output sizes.
- Gateway applies timeout and retry limits.
- Call records include token usage or `unknown` when the provider does not return usage.

### 12.15 Cancel, Retry, And Failure Recovery

Gap:

- AI analysis can fail due to provider outage, timeout, malformed JSON, network errors, or document parsing issues.
- Without a recovery model, users may need to re-upload and restart manually.

Required solution:

- Add retry and cancel operations at the analysis-run level.
- Retry should preserve:
  - source documents,
  - target case kind,
  - template,
  - rule set version,
  - Skill version,
  - previous failure reason.
- Cancel should mark the run as `cancelled` and stop future chunk processing where possible.
- Failed runs should keep partial diagnostic records but must not save malformed draft test points.

V1 acceptance:

- Failed analysis runs can be retried from the UI.
- Cancelled or failed runs do not create formal cases.
- Failure reasons remain visible in call records and run details.

### 12.16 Idempotency And Re-Analysis

Gap:

- Users may upload the same document or click AI analysis multiple times.
- Without idempotency, duplicate runs and duplicate draft points will accumulate.

Required solution:

- Calculate a source document hash.
- Add an analysis fingerprint:

```text
document hashes
target case kind
template id/version
rule set version
Skill ids/versions
model provider/model
analysis strategy
```

- Use the fingerprint to decide whether to:
  - reuse a previous successful result,
  - create a new run,
  - explicitly re-analyze by user request.
- Draft test points should carry stable source ids so regeneration can update or supersede drafts instead of blindly duplicating them.

V1 acceptance:

- Re-running analysis on the same document and same settings prompts reuse or re-analysis.
- Duplicate draft test points are not silently added to the same review scope.

### 12.17 Database Migration Scope

Gap:

- AI Agent support touches many persistent concepts and cannot be represented by one generic JSON column without creating future migration pain.

Required solution:

- Use explicit Flyway migrations for:
  - AI configuration,
  - project AI configuration,
  - rules,
  - Skills,
  - Skill overrides,
  - MCP server configs,
  - source documents,
  - source document chunks/evidence,
  - analysis runs,
  - draft test points,
  - review actions,
  - AI call records.
- JSON columns/text payloads may be used for flexible provider payloads and schema snapshots, but core query fields should be first-class columns.
- Keep `project_id`, `created_at`, `updated_at`, and status fields indexed where list pages need filtering.

V1 acceptance:

- The first AI slice has real database migrations instead of relying on browser localStorage.
- Core list/filter pages can query by project, status, created time, and run id.

### 12.18 LocalStorage To Backend Boundary

Gap:

- The current frontend still uses localStorage heavily for cases, templates, environments, reports, and defects.
- Real AI Agent features need backend state for security, audit, review, and multi-device continuity.

Required solution:

- Declare AI Agent data backend-first.
- Do not store real API keys, AI call records, source documents, draft test points, or review actions only in localStorage.
- localStorage may keep UI-only state:
  - selected tab,
  - filters,
  - collapsed sidebars,
  - unsaved form drafts without secrets.
- Where existing local generation paths are reused, bridge them through backend-backed draft ids before formal case generation.

V1 acceptance:

- AI config, analysis runs, draft test points, review actions, and call records survive browser changes and refreshes through backend persistence.
- localStorage is not the source of truth for AI-sensitive data.

### 12.19 Prompt And Schema Versioning

Gap:

- AI output changes when prompts, Skills, model versions, rules, or JSON schemas change.
- Without version snapshots, it is hard to explain or reproduce a generated result.

Required solution:

- Version:
  - Agent definition,
  - Skill definition,
  - prompt template,
  - output JSON Schema,
  - rule set,
  - provider/model configuration.
- Store the effective versions and snapshots on the analysis run and call record.
- When a Skill override changes, increment its version.
- When a schema changes incompatibly, keep a migration or renderer for older draft records.

V1 acceptance:

- Every AI-generated draft test point can be traced to the Agent, Skill, rule, schema, and model versions used to create it.

### 12.20 Review Audit Granularity

Gap:

- A simple status field is not enough to understand human review decisions.
- The platform needs to preserve original AI output and human changes.

Required solution:

- Store review actions as append-only events:
  - confirm,
  - edit,
  - reject,
  - split,
  - merge,
  - restore,
  - comment.
- Preserve:
  - before value,
  - after value,
  - actor,
  - timestamp,
  - reason/comment.
- Keep the original AI draft immutable or reconstructable from the first event.

V1 acceptance:

- Editing and confirming a draft produces a `modified` state and a review event.
- The review detail can show original AI content and human-edited content.

### 12.21 MCP Sandbox And Tool Preview

Gap:

- A future MCP tool may read or write production-adjacent systems.
- Configuration alone is not sufficient once tool execution is enabled.

Required solution:

- Add a sandbox policy model before real execution:
  - tool allowlist,
  - read/write classification,
  - max calls per analysis run,
  - allowed projects,
  - allowed resource patterns,
  - dry-run support where possible.
- For write-capable tools, require a preview/confirmation step:

```text
Agent proposes tool action -> platform renders preview -> user confirms -> backend executes
```

- Log all tool calls separately from model calls.

V1 acceptance:

- MCP configuration captures sandbox fields even if execution is deferred.
- The design does not require redesign to add safe tool preview later.

### 12.22 Pre-Implementation Baseline Verification

Gap:

- The current workspace has ongoing frontend and documentation changes.
- AI implementation will touch shared build and backend surfaces, so pre-existing failures could mask new regressions.

Required solution:

- Before starting AI implementation, run and record baseline verification:

```bash
cd apps/web
npm run build
npm test -- --run

cd ../../server/platform
./mvnw test
```

- If existing failures are unrelated, record them before starting the AI branch.
- Fix blocking build failures before adding AI work.

V1 acceptance:

- AI implementation starts from a known baseline or an explicitly documented set of pre-existing failures.

## 13. Difficulty Assessment And Execution Strategy

This section converts the difficulty assessment into implementation guidance.

### 13.1 AI Configuration Page Expansion

Difficulty: Medium.

Why:

- The UI is straightforward, but real integration requires credential safety and backend persistence.

Solution:

- Replace frontend-only localStorage as the source of truth for real AI credentials.
- Keep localStorage only as a development fallback or temporary draft.
- Add backend config APIs:

```text
GET /api/ai/config
PUT /api/ai/config
POST /api/ai/config/test-connection
```

- Return masked credentials only.
- Add tabs for model, Agent, rules, Skills, MCP, and call records.

Implementation order:

1. backend config model and masked response,
2. connection test,
3. frontend tabbed configuration UI,
4. local fallback removal or clear dev-only labeling.

### 13.2 Backend AI Gateway

Difficulty: Medium.

Why:

- OpenAI-compatible chat and structured generation are manageable, but timeout, error mapping, and provider differences need discipline.

Solution:

- Implement provider adapters behind one gateway interface:

```text
AiGateway
AiProviderAdapter
OpenAiCompatibleAdapter
```

- Use provider-neutral request/response models.
- Normalize errors into platform error codes.
- Add timeout and retry at the gateway level.
- Add call-record creation around every gateway call.

Implementation order:

1. provider-neutral contracts,
2. OpenAI-compatible adapter,
3. connection test,
4. structured generation with JSON schema validation,
5. call records.

### 13.3 Requirement Test Point Analysis Agent

Difficulty: Medium-High.

Why:

- The Agent must combine parsed documents, rule extraction, templates, Skills, and structured output validation.

Solution:

- Start with a fixed workflow service instead of a complex Agent runtime.
- Use one built-in Skill: `需求测试点分析`.
- Inputs:
  - document chunks,
  - rule extraction results,
  - target case kind,
  - selected template,
  - rule set,
  - project context.
- Output:
  - draft test points,
  - unclear questions,
  - coverage summary,
  - risks.

Implementation order:

1. output JSON Schema,
2. built-in Skill prompt,
3. workflow service that assembles context,
4. AI call,
5. schema validation,
6. draft persistence.

### 13.4 Editable Built-In Skills

Difficulty: Medium-High.

Why:

- It requires versioning, override resolution, restore defaults, and future upgrade compatibility.

Solution:

- Store built-in definitions as seed data or code-defined defaults.
- Store overrides in persistence.
- Resolve effective Skill at runtime.
- Add restore action that removes the override.
- Add audit fields.

Implementation order:

1. Skill domain model,
2. built-in seed list,
3. override persistence,
4. effective Skill resolver,
5. frontend edit and restore UI.

### 13.5 Rule Configuration

Difficulty: Medium.

Why:

- Rule editing is not hard; enforcing rules consistently is the important part.

Solution:

- Keep a simple rule set in V1:
  - forced review,
  - source evidence required,
  - AI inference requires pending confirmation,
  - allowed case kinds,
  - formal generation allowed statuses.
- Enforce rules in backend services.
- Store rule version with analysis runs.

Implementation order:

1. rule model,
2. rule config UI,
3. rule resolver,
4. backend validation in AI draft save and formal generation.

### 13.6 MCP Entry

Difficulty: Low-Medium in V1, High later.

Why:

- A visible configuration entry is simple.
- Real tool execution needs security controls, audit, and per-tool adapters.

Solution:

- V1 only stores MCP server definitions and shows tool authorization fields.
- Do not execute MCP tools in V1 unless a tool is explicitly implemented and audited.
- Add disabled-by-default write permission fields.

Implementation order:

1. MCP config model,
2. frontend config entry,
3. backend CRUD,
4. call audit data model reserved,
5. real tool execution in later version.

### 13.7 Review Confirmation Flow

Difficulty: Medium-High.

Why:

- This is the core business gate between AI drafts and formal case-library records.

Solution:

- Build a dedicated review page or drawer for draft test points.
- Support:
  - confirm,
  - edit and confirm,
  - reject,
  - batch confirm,
  - filter by module/status/confidence/source.
- Keep human edits as `modified`.
- Formal case generation queries only confirmed/modified items.

Implementation order:

1. draft persistence,
2. review UI,
3. status transitions,
4. formal generation from confirmed drafts,
5. audit trail.

### 13.8 Image, UI, And Figma Understanding

Difficulty: High.

Why:

- OCR is not enough; real UI understanding requires visual model output and later structured Figma data.

Solution:

- V1 only preserves visual metadata and evidence fields.
- V2/V3 add vision model analysis.
- Figma integration should prefer structured Figma API/MCP data rather than screenshot-only inference.

Implementation order:

1. keep current visual-summary fields,
2. add evidence model fields for image/region/Figma node,
3. later add vision analysis adapter,
4. later add Figma structured parser.

### 13.9 User Identity, Permissions, And Audit

Difficulty: Medium-High.

Why:

- It crosses AI configuration, Skill editing, MCP permissions, review confirmation, and call records.
- Full enterprise authentication may not be ready, but audit fields cannot wait.

Solution:

- Add an actor abstraction now.
- Use a development/system actor only as a temporary fallback.
- Keep permission checks capability-based so real login can replace the fallback later.
- Enforce sensitive operations through backend services.

Implementation order:

1. actor model and audit fields,
2. operation capability constants,
3. backend checks for AI config, Skill, rule, MCP, and review actions,
4. later replace fallback actor with real authentication.

### 13.10 Asynchronous Analysis Runs

Difficulty: High.

Why:

- It changes frontend interaction and backend state from request/response to long-running task management.

Solution:

- Design runs as asynchronous from day one.
- V1 may execute inline but must still create run records and expose status APIs:

```text
POST /api/ai/analysis-runs
GET /api/ai/analysis-runs/{runId}
POST /api/ai/analysis-runs/{runId}/retry
POST /api/ai/analysis-runs/{runId}/cancel
```

- Frontend should display run status and recover by run id after refresh.

Implementation order:

1. analysis run table and status model,
2. create/get APIs,
3. inline execution behind run creation,
4. retry support,
5. cancellation marker,
6. later background queue/worker.

### 13.11 Source Document Persistence And Evidence

Difficulty: Medium-High.

Why:

- It touches upload, parsing, storage, chunking, evidence references, and retention policy.

Solution:

- Persist source document metadata and extracted content before AI analysis.
- Store chunks as stable evidence records.
- Use MinIO for original files when retention is enabled.
- Keep file hash and parser version for reproducibility.

Implementation order:

1. source document table,
2. chunk/evidence table,
3. parse endpoint writes records,
4. AI analysis consumes document/chunk ids,
5. review UI links drafts back to evidence.

### 13.12 Cost, Quota, And Rate Limiting

Difficulty: Medium.

Why:

- The rules are simple, but they must be enforced before provider calls and reflected in user-facing errors.

Solution:

- Add gateway-level limit checks.
- Store quota settings in system/project AI config.
- Record token usage and estimated cost on call records.
- Reject requests before calling the provider when configured limits are exceeded.

Implementation order:

1. config fields for limits,
2. gateway preflight validation,
3. usage recording,
4. user-facing quota errors,
5. project/day aggregation later if needed.

### 13.13 Retry, Cancel, And Failure Recovery

Difficulty: Medium.

Why:

- Provider errors and malformed output are normal; the product needs a friendly recovery path.

Solution:

- Treat retry/cancel as analysis-run operations.
- Preserve run inputs and versions for deterministic retry.
- Do not persist malformed draft test points.
- Keep partial diagnostic records in call logs.

Implementation order:

1. failed run state and error fields,
2. retry API,
3. cancel API/status,
4. frontend retry/cancel actions,
5. later chunk-level resume.

### 13.14 Idempotency And Re-Analysis

Difficulty: Medium.

Why:

- Duplicate runs and duplicate draft points become noisy quickly.

Solution:

- Use document hash plus model/template/rule/Skill versions as an analysis fingerprint.
- Prompt users to reuse or explicitly re-analyze when an equivalent successful run exists.
- Use stable draft source keys so regenerated output can supersede prior drafts.

Implementation order:

1. document hash,
2. analysis fingerprint,
3. duplicate detection,
4. reuse/re-analyze UI,
5. draft superseding behavior.

### 13.15 Database Migration And Storage Design

Difficulty: Medium-High.

Why:

- The number of related AI tables is larger than a one-button integration suggests.

Solution:

- Create explicit Flyway migrations for AI core tables.
- Keep query-critical fields as first-class columns.
- Store flexible snapshots as JSON/text payloads only where appropriate.
- Add indexes for project/status/run/time filters.

Implementation order:

1. V3 or next Flyway migration for AI foundation,
2. repository/service tests,
3. H2-compatible tests,
4. MySQL migration verification,
5. frontend adapter.

### 13.16 Frontend LocalStorage To Backend Transition

Difficulty: Medium-High.

Why:

- Existing flows are local-first, while AI-sensitive flows must be backend-first.

Solution:

- Mark AI Agent data as backend-first.
- Keep localStorage for UI state only.
- Create frontend repositories that call backend APIs for AI data.
- Do not reuse local-only case save paths for AI draft review.

Implementation order:

1. backend-backed AI repositories,
2. frontend AI repository adapters,
3. UI state stored locally only where harmless,
4. formal generation bridges confirmed backend draft ids into existing case-library flows.

### 13.17 Prompt And Schema Versioning

Difficulty: Medium.

Why:

- The implementation is straightforward, but missing it damages reproducibility.

Solution:

- Add versions to Agent, Skill, prompt, schema, and rule definitions.
- Store effective snapshots on runs and call records.
- Increment Skill override versions on edit.

Implementation order:

1. version fields,
2. snapshot serialization,
3. run/call association,
4. display versions in call/run detail.

### 13.18 Review Audit Trail

Difficulty: Medium-High.

Why:

- Review is not a simple update; it is a business decision trail.

Solution:

- Store review actions as append-only events.
- Keep original AI output immutable or recoverable.
- Mark edited confirmations as `modified`.

Implementation order:

1. review action model,
2. status transition service,
3. edit/confirm/reject APIs,
4. frontend review detail timeline,
5. generation only from confirmed/modified drafts.

### 13.19 MCP Sandbox And Tool Preview

Difficulty: High later, Low-Medium in V1.

Why:

- A configuration page is easy, but safe real tool execution needs a controlled sandbox and human confirmation for writes.

Solution:

- V1 captures sandbox fields but keeps execution disabled.
- Later execution uses preview-before-write.
- Tool calls are audited separately from AI model calls.

Implementation order:

1. sandbox fields in MCP config,
2. read/write classification,
3. disabled-by-default execution switch,
4. later read-only tool execution,
5. later write preview and confirmation.

### 13.20 Baseline Verification And Existing Worktree Risk

Difficulty: Low-Medium.

Why:

- It is process work, but skipping it makes regressions hard to attribute.

Solution:

- Before implementation, run frontend build/tests and backend tests.
- Record existing failures before AI changes.
- Keep AI changes isolated from unrelated frontend layout or defect-module work.

Implementation order:

1. run baseline checks,
2. record failures if any,
3. fix blocking build errors,
4. start AI implementation on top of a known baseline.

## 14. Requirement Analysis Modes

The generation page should separate the two analysis modes:

```text
分析需求
AI分析需求
```

`分析需求`:

- rule-based,
- deterministic,
- fast,
- no external model call,
- good for headings, tables, explicit actions, fields, states, and keywords.

`AI分析需求`:

- uses the AI Gateway and Requirement Test Point Analysis Agent,
- consumes rule-based results as context,
- extracts implicit rules, alternate flows, exception flows, permissions, states, risks, and coverage gaps,
- returns structured draft test points,
- requires review confirmation.

Both modes should write into the same draft-review model so the user can compare, merge, confirm, or reject outputs.

## 15. Image, UI, And Figma Direction

OCR alone is not enough for images or UI designs.

The platform should reserve a visual-analysis path that can extract:

- layout hierarchy,
- regions and containers,
- navigation,
- forms and fields,
- buttons and actions,
- tables and columns,
- dialogs and drawers,
- validation states,
- empty/error/success states,
- component style hints,
- interaction intent.

Later Figma integration should prefer structured design data over screenshot-only analysis:

- frames,
- components,
- variants,
- prototype flows,
- design tokens,
- auto-layout information,
- component properties.

Figma-derived information should flow into the same requirement evidence and draft test-point review model.

## 16. Version Plan

### V1: Controlled AI Requirement Analysis

- dedicated AI configuration page,
- model configuration,
- Agent configuration,
- rule configuration,
- editable built-in Skill configuration with `内置` and `已自定义` tags,
- MCP configuration entry reserved with sandbox fields,
- backend credential safety and masked config responses,
- backend AI Gateway,
- OpenAI-compatible provider adapter,
- Requirement Test Point Analysis Agent,
- rule extraction results passed into AI context,
- structured JSON output,
- JSON Schema validation before persistence,
- source document and evidence persistence,
- analysis run records with status and retry metadata,
- AI call records,
- draft-test-point persistence,
- mandatory review confirmation,
- review action audit trail,
- rule enforcement at formal generation,
- confirmed or modified test points can generate cases.

### V2: Tool-Calling And Large Document Depth

- expose rule extractors as Agent tools,
- enable real MCP tool connections with allowlists and audit,
- support custom Skills and Skill version comparison,
- chunk long documents by semantic section,
- merge and deduplicate test points,
- coverage summary by module, role, state, operation, field, and case kind,
- unclear requirement question list,
- stronger JSON schema validation and repair,
- long-document multi-pass chunk analysis,
- background queue/worker execution for analysis runs,
- chunk-level resume and partial retry,
- quota aggregation by project and time window.

### V3: Visual Requirement Analysis

- image OCR plus visual layout understanding,
- UI component and style extraction,
- screenshot-to-test-point generation,
- source linkage from visual regions to test points.

### V4: Figma Integration

- import Figma files or selected nodes,
- read components, variants, tokens, and prototype flows,
- compare design changes and generate new or changed test points,
- map UI components to Web automation locators or element library entries.

### V5: Multi-Agent Review

- requirement-understanding Agent,
- test-design Agent,
- quality-review Agent,
- automation-suggestion Agent,
- final merge under the same human review gate.

## 17. First Implementation Recommendation

Do V1 with a simple backend workflow service rather than a complex Agent framework:

```text
Spring Boot service
  + Prompt template
  + JSON schema validation
  + AI Provider Adapter
  + Draft review persistence
```

Avoid introducing LangChain, LangGraph, or a full multi-Agent runtime until V2 or later unless the workflow complexity clearly requires it.

The first acceptance gate should be:

- AI configuration can be saved and connection-tested,
- full provider credentials are backend-owned and masked in frontend responses,
- built-in Skills are editable through overrides and visually marked as `内置`,
- customized built-in Skills can be restored to defaults,
- rule configuration can enforce mandatory review and source evidence,
- MCP has a visible configuration entry with sandbox fields even if real tool execution is deferred,
- source documents and evidence records are persisted,
- AI analysis creates a recoverable analysis run,
- `AI分析需求` calls the configured provider through the backend,
- generated test points are structured and source-linked,
- AI call records show success and failure details,
- malformed AI output is rejected before draft persistence,
- review actions are audited with actor and before/after content,
- AI output enters review status only,
- unreviewed AI output cannot be saved as formal cases,
- confirmed/modified test points can generate cases through the selected template.

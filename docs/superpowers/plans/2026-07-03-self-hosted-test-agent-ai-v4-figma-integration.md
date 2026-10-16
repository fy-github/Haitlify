> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# Self-Hosted Test Agent V4 Figma Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add structured Figma requirement ingestion so selected Figma files or nodes can generate reviewable test points from frames, components, variants, prototype flows, design tokens, and UI hierarchy.

**Architecture:** Extend the existing backend Figma parsing foundation and V1/V3 evidence model. V4 prefers structured Figma API/MCP data over screenshot-only analysis, persists Figma nodes as requirement evidence, maps components and interactions into draft test points, and optionally links visual previews from V3. All generated output still passes the mandatory review gate.

**Tech Stack:** Spring Boot 3.5.x, Java 21, Spring Data JDBC, Flyway, MySQL/H2, existing `FigmaDesignClient`, Figma REST API or MCP boundary, Vue 3, TypeScript, Element Plus, Vite, Vitest.

---

## Prerequisites

Required:

- V1 AI Agent foundation is complete.
- Source document/evidence model exists.
- Draft test points require review confirmation.
- AI call records and analysis runs exist.
- Existing backend Figma parsing remains compatible:
  - `server/platform/src/main/java/com/haitlify/aitest/platform/document/FigmaDesignClient.java`
  - `server/platform/src/main/java/com/haitlify/aitest/platform/document/FigmaDesignService.java`

Recommended:

- V3 visual asset/evidence model exists for previews and region linking.

## V4 Scope

### In Scope

- Figma file/node import.
- Figma credential/config management through AI/MCP configuration boundary.
- Persist Figma files, nodes, components, variants, styles, tokens, and prototype links.
- Convert Figma structure into requirement evidence.
- Generate draft test points from:
  - page/frame hierarchy,
  - components,
  - component variants,
  - form fields,
  - tables/lists,
  - dialogs/drawers,
  - navigation,
  - prototype interactions,
  - style/token rules.
- Detect design changes and generate new/changed test points.
- Map Figma components toward Web automation element library candidates.

### Out Of Scope

- Direct Figma write-back.
- Automatic code generation from Figma.
- Pixel-perfect visual regression execution.
- Full design-system library management.
- MCP write operations.

## V4 Data Model Additions

Create migration:

- `server/platform/src/main/resources/db/migration/V6__ai_figma_integration.sql`

Tables:

```text
ai_figma_files
ai_figma_nodes
ai_figma_components
ai_figma_variants
ai_figma_styles
ai_figma_tokens
ai_figma_prototype_links
ai_figma_change_sets
ai_figma_element_candidates
```

Important fields:

```text
file_key
node_id
node_type
node_name
parent_node_id
component_key
variant_properties_json
style_json
token_json
absolute_bounds_json
prototype_link_json
source_snapshot_hash
created_at
updated_at
```

## V4 API Contract

Figma import:

```text
POST /api/ai/figma/import
GET  /api/ai/figma/files/{figmaFileId}
GET  /api/ai/figma/files/{figmaFileId}/nodes
GET  /api/ai/figma/nodes/{figmaNodeId}
```

Figma analysis:

```text
POST /api/ai/figma/files/{figmaFileId}/analysis-runs
POST /api/ai/figma/nodes/{figmaNodeId}/analysis-runs
GET  /api/ai/figma/analysis-runs/{runId}/draft-test-points
```

Figma change detection:

```text
POST /api/ai/figma/files/{figmaFileId}/refresh
GET  /api/ai/figma/files/{figmaFileId}/change-sets
GET  /api/ai/figma/change-sets/{changeSetId}
```

Element candidates:

```text
GET  /api/ai/figma/files/{figmaFileId}/element-candidates
POST /api/ai/figma/element-candidates/{candidateId}/confirm
POST /api/ai/figma/element-candidates/{candidateId}/reject
```

## Task 1: Figma Import Model And Config Boundary

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/figma/AiFigmaImportController.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/figma/AiFigmaImportService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/figma/AiFigmaFile.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/figma/AiFigmaFileRepository.java`
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/document/FigmaDesignClient.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/figma/AiFigmaImportControllerTest.java`

**Deliverable:**

- Figma URL/fileKey/nodeId can be imported.
- Figma credential is resolved through backend config, not frontend raw token.
- Imported file has stable id, file key, selected node id, snapshot hash, and actor.

- [x] **Step 1: Write failing tests**

Test:

- import parses file key and node id from URL,
- disabled Figma config returns controlled error,
- imported file record stores file key and node id,
- repeated import of same file/node updates snapshot instead of duplicating blindly.

- [x] **Step 2: Implement import API**

Endpoint:

```text
POST /api/ai/figma/import
```

Request fields:

```text
figmaUrl
fileKey
nodeId
projectId
```

- [x] **Step 3: Reuse existing Figma client**

Keep existing document Figma endpoint working while adding AI-specific persistence.

- [x] **Step 4: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiFigmaImportControllerTest,FigmaDesignClientTest
```

Expected:

- PASS.

## Task 2: Persist Figma Node Tree And Component Metadata

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/figma/AiFigmaNode.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/figma/AiFigmaNodeRepository.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/figma/AiFigmaNodeMapper.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/figma/AiFigmaNodeMapperTest.java`

**Deliverable:**

- Figma frame/node tree is persisted.
- Components, instances, variants, auto-layout, bounds, constraints, and text styles are captured.
- Parent/child relationships are queryable.

- [x] **Step 1: Write failing tests**

Test:

- frame maps to node with bounds,
- instance maps component name/key,
- variant properties persist,
- child hierarchy can be queried.

- [x] **Step 2: Implement node mapper**

Capture node fields:

```text
id
name
type
parentId
componentKey
componentName
variantProperties
absoluteBounds
layoutMode
constraints
styleJson
```

- [x] **Step 3: Implement node APIs**

Endpoints:

```text
GET /api/ai/figma/files/{figmaFileId}/nodes
GET /api/ai/figma/nodes/{figmaNodeId}
```

- [x] **Step 4: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiFigmaNodeMapperTest
```

Expected:

- PASS.

## Task 3: Styles, Tokens, And Prototype Flow Extraction

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/figma/AiFigmaStyleExtractor.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/figma/AiFigmaPrototypeExtractor.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/figma/AiFigmaStylePrototypeExtractorTest.java`

**Deliverable:**

- Styles and token-like values are extracted from fills, strokes, typography, radius, shadows, spacing, opacity, and layout.
- Prototype interactions are extracted into source/target/event/action records.

- [x] **Step 1: Write failing tests**

Test:

- color token extracted from fill,
- typography token extracted from text style,
- radius/shadow extracted,
- prototype click link extracted,
- overlay/close/back/scroll interactions extracted where available.

- [x] **Step 2: Implement style extractor**

Token categories:

```text
color
typography
spacing
radius
shadow
opacity
layout
```

- [x] **Step 3: Implement prototype extractor**

Prototype fields:

```text
sourceNodeId
targetNodeId
eventType
actionType
transitionType
durationMs
```

- [x] **Step 4: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiFigmaStylePrototypeExtractorTest
```

Expected:

- PASS.

## Task 4: Figma Evidence And Test Point Mapping

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/figma/AiFigmaEvidenceService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/figma/AiFigmaTestPointMapper.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/figma/AiFigmaTestPointMapperTest.java`

**Deliverable:**

- Figma nodes become requirement evidence.
- Draft test points are generated from Figma hierarchy, components, variants, interactions, and styles.
- Each draft point references file key and node id.

- [x] **Step 1: Write failing tests**

Test:

- frame hierarchy generates page/layout test point,
- button component generates interaction test point,
- input component generates form-field test point,
- variant state generates component-state test point,
- prototype link generates navigation/flow test point,
- style token generates visual consistency test point.

- [x] **Step 2: Implement evidence mapping**

Evidence source fields:

```text
sourceType: figma
fileKey
nodeId
nodeName
nodeType
componentName
prototypeLinkId
styleTokenId
```

- [x] **Step 3: Implement draft mapping**

Map categories:

```text
frame -> 页面/布局测试点
component -> 组件测试点
variant -> 状态测试点
prototype -> 交互流程测试点
styleToken -> 视觉样式测试点
```

- [x] **Step 4: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiFigmaTestPointMapperTest
```

Expected:

- PASS.

## Task 5: Figma Analysis Run Integration

**Files:**

- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/analysis/AiAnalysisRunService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/figma/AiFigmaAnalysisService.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/figma/AiFigmaAnalysisServiceTest.java`

**Deliverable:**

- Figma file or selected node can start an AI analysis run.
- Figma-derived test points enter the same draft review model.
- Optional AI pass can enrich unclear names or infer missing requirement questions, but source evidence remains Figma-backed.

- [x] **Step 1: Write failing tests**

Test:

- starting run for file creates draft points,
- starting run for node scopes output to selected subtree,
- generated points preserve node evidence,
- unreviewed Figma points cannot generate formal cases.

- [x] **Step 2: Implement analysis endpoints**

Endpoints:

```text
POST /api/ai/figma/files/{figmaFileId}/analysis-runs
POST /api/ai/figma/nodes/{figmaNodeId}/analysis-runs
GET  /api/ai/figma/analysis-runs/{runId}/draft-test-points
```

- [x] **Step 3: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiFigmaAnalysisServiceTest,AiDraftTestPointReviewTest
```

Expected:

- PASS.

## Task 6: Design Change Detection

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/figma/AiFigmaChangeSetService.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/figma/AiFigmaChangeSetServiceTest.java`

**Deliverable:**

- Refreshing a Figma file detects added, removed, and changed nodes.
- Changed components/styles/prototype links can generate new or changed draft test points.

- [x] **Step 1: Write failing tests**

Test:

- new node is marked added,
- removed node is marked removed,
- changed style hash is marked changed,
- changed prototype target is marked changed.

- [x] **Step 2: Implement snapshot hash**

Hash should include:

```text
node type
name
bounds
component key
variant properties
style json
prototype links
children ids
```

- [x] **Step 3: Implement refresh API**

Endpoints:

```text
POST /api/ai/figma/files/{figmaFileId}/refresh
GET  /api/ai/figma/files/{figmaFileId}/change-sets
GET  /api/ai/figma/change-sets/{changeSetId}
```

- [x] **Step 4: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiFigmaChangeSetServiceTest
```

Expected:

- PASS.

## Task 7: Element Library Candidates

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/figma/AiFigmaElementCandidateService.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/figma/AiFigmaElementCandidateServiceTest.java`
- Modify: `apps/web/src/domain/designLocatorView.ts`

**Deliverable:**

- Figma components can become candidate element-library records.
- Candidates can be confirmed or rejected.
- Confirmed candidates can later help Web automation locator management.

- [x] **Step 1: Write failing tests**

Test:

- button instance creates candidate,
- input instance creates candidate,
- duplicate component candidate merges by component key and node role,
- confirm/reject changes candidate status.

- [x] **Step 2: Implement candidate service**

Candidate fields:

```text
componentKey
componentName
nodeId
nodeName
role
suggestedLocatorName
status
evidenceJson
```

- [x] **Step 3: Implement candidate APIs**

Endpoints:

```text
GET  /api/ai/figma/files/{figmaFileId}/element-candidates
POST /api/ai/figma/element-candidates/{candidateId}/confirm
POST /api/ai/figma/element-candidates/{candidateId}/reject
```

- [x] **Step 4: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiFigmaElementCandidateServiceTest
```

Expected:

- PASS.

## Task 8: Frontend Figma Import And Review UI

**Files:**

- Create: `apps/web/src/domain/aiFigmaRequirementView.ts`
- Create: `apps/web/src/domain/aiFigmaRequirementView.test.ts`
- Modify: `apps/web/src/App.vue`
- Modify: `apps/web/src/styles.css`

**Deliverable:**

- User can import Figma URL.
- Figma file/node tree is visible.
- User can start analysis for file or selected node.
- Draft review shows Figma source evidence.
- Change sets and element candidates are visible.

- [x] **Step 1: Write frontend domain tests**

Test:

- Figma nodes group by hierarchy,
- draft evidence label includes file key and node name,
- change set rows sort by added/changed/removed,
- element candidates group by component.

- [x] **Step 2: Implement UI**

Add Figma section to requirement generation or AI analysis page:

```text
Figma URL import
file/node tree
analyze selected node
change set list
element candidates
draft point review
```

- [x] **Step 3: Run frontend tests**

Run:

```bash
cd apps/web
npm test -- src/domain/aiFigmaRequirementView.test.ts --run
npm test -- --run
npm run build
```

Expected:

- PASS.

## Task 9: Figma Integration Smoke Verification

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

- [x] **Step 2: Frontend full test and build**

Run:

```bash
cd apps/web
npm test -- --run
npm run build
```

Expected:

- PASS.

- [ ] **Step 3: Manual smoke**

Status:

- Not run in this pass because it requires a configured Figma token/model and a live Figma file. Automated backend/frontend coverage and production build are verified.

Verify:

```text
导入 Figma URL
查看节点树
选择节点分析
生成带 Figma 证据的待评审测试点
确认/修改/拒绝测试点
刷新 Figma 文件查看变更集
查看元素候选并确认/拒绝
确认后的测试点可以生成用例
未确认测试点不能入库
```

Expected:

- Figma-derived output remains review-gated and source-linked.

## Completion Gate

V4 is complete when:

- Figma import persists file/node snapshots.
- Frames, components, variants, styles, tokens, and prototype links are extracted.
- Figma nodes become requirement evidence.
- Figma-derived draft test points are source-linked.
- Design change detection identifies added/removed/changed nodes.
- Element candidates can be confirmed/rejected.
- Frontend can import, inspect, analyze, and review Figma sources.
- Formal generation remains limited to `confirmed` and `modified`.
- Backend tests pass.
- Frontend tests and build pass.

> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# Self-Hosted Test Agent V3 Visual Requirement Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add visual requirement analysis so uploaded screenshots, UI mockups, and design images can produce reviewable test points with OCR text, layout hierarchy, UI components, style hints, interaction clues, and visual-region evidence.

**Architecture:** Extend the V1 evidence model and V2 tool workflow with a vision provider adapter, visual asset persistence, visual region extraction, OCR-plus-layout analysis, visual-to-test-point generation, and review UI support. V3 analyzes image files and screenshots as visual requirements; structured Figma node parsing remains V4.

**Tech Stack:** Spring Boot 3.5.x, Java 21, Spring Data JDBC, Flyway, MySQL/H2, Apache Tika, ImageIO, optional Tesseract OCR, OpenAI-compatible vision API, Vue 3, TypeScript, Element Plus, Vite, Vitest.

---

## Prerequisites

This plan starts only after V1 is complete. V2 is recommended but not strictly required for the first visual slice.

Required V1 capabilities:

- Source documents and evidence chunks are persisted.
- AI Gateway exists.
- Analysis runs and call records exist.
- Draft test points require review confirmation.
- Rules enforce source evidence and formal-generation status gates.

Recommended V2 capabilities:

- Tool registry.
- Long-running analysis run status.
- Coverage and unclear question model.

## V3 Scope

### In Scope

- Vision model configuration fields.
- OpenAI-compatible vision adapter.
- Visual source document metadata.
- Visual region evidence model.
- OCR text plus visual analysis result.
- Layout hierarchy extraction.
- UI component and style hint extraction.
- Screenshot/design-image-to-test-point generation.
- Review UI that shows visual evidence references.

### Out Of Scope

- Figma API structured frame/component parsing.
- Automatic locator generation for Web automation.
- Pixel-perfect visual diff execution.
- Real accessibility audit.
- Real image annotation canvas editing.

## V3 Data Model Additions

Create migration:

- `server/platform/src/main/resources/db/migration/V5__ai_visual_requirement_analysis.sql`

Tables:

```text
ai_visual_assets
ai_visual_regions
ai_visual_analysis_results
```

Add fields if missing:

```text
ai_source_documents.visual_asset_id
ai_draft_test_points.source_region_id
ai_call_records.modality
```

Visual region fields:

```text
asset_id
region_id
region_type
x
y
width
height
text
component_hint
style_hint_json
confidence
created_at
```

## V3 API Contract

Visual assets:

```text
POST /api/ai/visual-assets
GET  /api/ai/visual-assets/{assetId}
GET  /api/ai/visual-assets/{assetId}/regions
```

Visual analysis:

```text
POST /api/ai/visual-assets/{assetId}/analyze
GET  /api/ai/visual-assets/{assetId}/analysis
POST /api/ai/analysis-runs/{runId}/include-visual-assets
```

Draft evidence:

```text
GET /api/ai/draft-test-points/{pointId}/visual-evidence
```

## Task 1: Vision Config And Provider Adapter

**Files:**

- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/config/AiConfigService.java`
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/gateway/AiGateway.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/gateway/AiVisionRequest.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/gateway/AiVisionResponse.java`
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/gateway/OpenAiCompatibleAdapter.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/gateway/AiVisionGatewayTest.java`

**Deliverable:**

- AI config supports `visionModel`.
- Gateway supports `visionAnalyze`.
- OpenAI-compatible adapter can send image input to configured vision model.
- Vision errors are mapped into safe platform errors.

- [ ] **Step 1: Write failing tests**

Test:

- missing vision model returns validation error,
- vision request sends image content and prompt,
- non-2xx provider response creates failed call record without leaking key.

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiVisionGatewayTest
```

Expected:

- FAIL before vision gateway exists.

- [ ] **Step 2: Extend AI config**

Add fields:

```text
visionModel
visionEnabled
maxImageBytes
allowedImageTypes
```

Do not expose credentials in config responses.

- [ ] **Step 3: Implement vision adapter**

Provider request must include:

```text
model
prompt
image bytes or image URL
max output tokens
```

- [ ] **Step 4: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiVisionGatewayTest,AiConfigControllerTest
```

Expected:

- PASS.

## Task 2: Visual Asset Persistence

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/visual/AiVisualAsset.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/visual/AiVisualAssetRepository.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/visual/AiVisualAssetController.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/visual/AiVisualAssetService.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/visual/AiVisualAssetControllerTest.java`

**Deliverable:**

- Uploaded image becomes durable visual asset.
- Asset records include dimensions, hash, content type, source document id, and actor.
- Unsupported or oversized files are rejected clearly.

- [ ] **Step 1: Write failing tests**

Test:

- upload PNG creates visual asset with width/height/hash,
- upload same image returns same hash,
- unsupported file type is rejected,
- oversized file is rejected according to config.

- [ ] **Step 2: Implement visual asset API**

Endpoints:

```text
POST /api/ai/visual-assets
GET  /api/ai/visual-assets/{assetId}
```

- [ ] **Step 3: Link source documents**

When image requirement files are uploaded through source document flow, link `source_document.visual_asset_id`.

- [ ] **Step 4: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiVisualAssetControllerTest,AiSourceDocumentControllerTest
```

Expected:

- PASS.

## Task 3: OCR And Visual Region Model

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/visual/AiVisualRegion.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/visual/AiVisualRegionRepository.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/visual/AiVisualRegionService.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/visual/AiVisualRegionServiceTest.java`

**Deliverable:**

- Visual regions can represent OCR text, component candidates, containers, buttons, forms, tables, dialogs, and unknown regions.
- OCR unavailable status is explicit and does not block visual model analysis.
- Regions are linked to draft test point evidence later.

- [ ] **Step 1: Write failing tests**

Test:

- region can store bounding box and text,
- OCR unavailable creates analysis warning,
- component candidate stores region type and confidence.

- [ ] **Step 2: Implement region service**

Supported region types:

```text
text
container
navigation
form
field
button
table
dialog
toast
state_badge
unknown
```

- [ ] **Step 3: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiVisualRegionServiceTest
```

Expected:

- PASS.

## Task 4: Visual Analysis Prompt And Schema

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/visual/AiVisualAnalysisService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/visual/AiVisualAnalysisSchema.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/visual/AiVisualAnalysisServiceTest.java`

**Deliverable:**

- Vision model output is validated before persistence.
- Output includes layout, components, styles, interactions, risks, and suggested test points.
- Invalid visual output fails safely.

- [ ] **Step 1: Write failing tests**

Test:

- valid visual JSON persists analysis result and regions,
- missing layout fails validation,
- suggested test points include visual evidence references,
- interaction hints are preserved.

- [ ] **Step 2: Define V3 output schema**

Required top-level fields:

```text
layout
components
styles
interactions
states
risks
suggestedTestPoints
unclearQuestions
```

Component fields:

```text
name
type
region
text
state
styleHints
confidence
```

- [ ] **Step 3: Implement analysis service**

Flow:

```text
load visual asset
build prompt
call visionAnalyze
validate schema
persist analysis result
persist regions
create call record
```

- [ ] **Step 4: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiVisualAnalysisServiceTest
```

Expected:

- PASS.

## Task 5: Visual Test Point Generation

**Files:**

- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/analysis/AiAnalysisRunService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/visual/AiVisualTestPointMapper.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/visual/AiVisualTestPointMapperTest.java`

**Deliverable:**

- Visual analysis results can produce draft test points.
- Draft test points reference visual region evidence.
- AI-inferred visual assumptions are marked as pending review.

- [ ] **Step 1: Write failing tests**

Test:

- button component generates visual/component test point,
- form field generates required UI behavior test point,
- layout hierarchy generates responsive/layout stability test point,
- inferred interaction creates `isInferred=true`.

- [ ] **Step 2: Implement mapper**

Map visual categories:

```text
layout -> UI布局测试点
component -> 组件显示/状态测试点
style -> 视觉样式测试点
interaction -> 交互流程测试点
state -> 空态/错误态/成功态测试点
```

- [ ] **Step 3: Integrate analysis run**

When run includes visual assets:

```text
visual asset -> visual analysis -> draft test points -> review
```

- [ ] **Step 4: Run tests**

Run:

```bash
cd server/platform
./mvnw test -Dtest=AiVisualTestPointMapperTest,AiAnalysisRunControllerTest
```

Expected:

- PASS.

## Task 6: Frontend Visual Evidence And Review UI

**Files:**

- Create: `apps/web/src/domain/aiVisualRequirementView.ts`
- Create: `apps/web/src/domain/aiVisualRequirementView.test.ts`
- Modify: `apps/web/src/App.vue`
- Modify: `apps/web/src/styles.css`

**Deliverable:**

- Requirement upload recognizes images as visual assets.
- Review table shows visual evidence tag.
- Draft detail can show image metadata and region coordinates.
- Visual test points remain review-gated.

- [ ] **Step 1: Write frontend domain tests**

Test:

- visual draft points group by region type,
- confidence and inferred flags are rendered as status data,
- region coordinates convert to display labels.

- [ ] **Step 2: Implement UI**

Review detail fields:

```text
图片名称
区域类型
区域坐标
组件线索
样式线索
交互线索
置信度
```

- [ ] **Step 3: Run frontend tests**

Run:

```bash
cd apps/web
npm test -- src/domain/aiVisualRequirementView.test.ts --run
npm test -- --run
npm run build
```

Expected:

- PASS.

## Task 7: Visual Analysis Smoke Verification

**Files:**

- Modify only if tests expose defects.

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

Verify:

```text
上传图片需求文件
创建视觉资产
AI视觉分析
生成带视觉证据的待评审测试点
确认/修改/拒绝测试点
确认后的视觉测试点可以生成用例
未确认视觉测试点不能入库
```

Expected:

- Visual analysis improves image requirement extraction without bypassing review.

## Completion Gate

V3 is complete when:

- Vision model config and adapter work.
- Visual assets are persisted with dimensions and hash.
- OCR unavailable state is explicit.
- Visual regions are stored with coordinates and component/style hints.
- Vision model output is schema validated.
- Visual analysis produces source-linked draft test points.
- Visual evidence is visible in review UI.
- Formal generation remains limited to `confirmed` and `modified`.
- Backend tests pass.
- Frontend tests and build pass.

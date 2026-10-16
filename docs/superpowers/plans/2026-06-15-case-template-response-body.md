> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# Case Template Response Body Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add template-aware generated data, response body matching, and bulk field maintenance for interface/API automation cases.

**Architecture:** Keep executable behavior in small domain modules and wire it into the existing Vue shell. Use TDD for parser/generator/automation/bulk rules, then add compact dialogs and controls in `App.vue`.

**Tech Stack:** Vue 3, TypeScript, Vite, Vitest, Element Plus.

---

### Task 1: Response Body Data Contract

**Files:**
- Modify: `apps/web/src/domain/openapiParser.ts`
- Modify: `apps/web/src/domain/caseGenerator.ts`
- Modify: `apps/web/src/domain/caseTemplates.ts`
- Test: `apps/web/src/domain/openapiParser.test.ts`
- Test: `apps/web/src/domain/caseGenerator.test.ts`

- [x] Write failing tests for response example/schema parsing and generated expected response body rows.
- [x] Run targeted tests and confirm they fail for missing fields.
- [x] Implement parser and generator changes.
- [x] Run targeted tests and confirm they pass.

### Task 2: Template Pruning

**Files:**
- Create: `apps/web/src/domain/caseTemplateApplication.ts`
- Test: `apps/web/src/domain/caseTemplateApplication.test.ts`
- Modify: `apps/web/src/App.vue`

- [x] Write failing tests proving hidden large fields are cleared from row and metadata.
- [x] Run targeted tests and confirm failure.
- [x] Implement template application and wire generation/edit/save paths to it.
- [x] Run targeted tests and confirm pass.

### Task 3: Response Body Match Threshold

**Files:**
- Modify: `apps/web/src/domain/apiAutomation.ts`
- Test: `apps/web/src/domain/apiAutomation.test.ts`
- Modify: `apps/web/src/App.vue`

- [x] Write failing tests for 80 percent pass and 100 percent fail on missing response body paths.
- [x] Run targeted tests and confirm failure.
- [x] Implement response path match scoring in the runner.
- [x] Add the compact threshold control to API automation.
- [x] Run targeted tests and confirm pass.

### Task 4: Bulk Field Maintenance

**Files:**
- Create: `apps/web/src/domain/caseBulkEdit.ts`
- Test: `apps/web/src/domain/caseBulkEdit.test.ts`
- Modify: `apps/web/src/App.vue`
- Modify: `apps/web/src/styles.css`

- [x] Write failing tests for filtered conditional field update and JSON path assignment.
- [x] Run targeted tests and confirm failure.
- [x] Implement bulk update and field assignment helpers.
- [x] Add `批量修改字段` and `字段赋值` dialogs to the case library.
- [x] Run targeted tests and confirm pass.

### Task 5: Verification

**Files:**
- Modify: `docs/ai-test-platform/decisions.md`

- [x] Update decision log with template pruning, response body threshold, and bulk field maintenance.
- [x] Run `npm test` in `apps/web`.
- [x] Run `npm run build` in `apps/web`.
- [x] Verify UI in the browser for generation, edit drawer, automation threshold, and case library dialogs.

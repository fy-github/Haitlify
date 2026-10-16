> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# Web Review And Login Verification Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make natural-language expected results consistently numbered, remove accepted review suggestions from the pending list while giving the suggestion panel more room, and make Web login verification visibly browser-driven with live progress and diagnostics.

**Architecture:** Keep numbering as a pure domain formatter reused by the generated-case table and preview. Keep review finding state in the existing `caseReviewFindingDecisions` model and filter accepted findings at the view boundary after merge succeeds. Reuse the existing asynchronous Web runner snapshot API for login verification, adding a dedicated modal that receives live snapshots instead of using the generic toast-only result path.

**Tech Stack:** Vue 3, TypeScript, Element Plus, Vitest, Spring Boot Web runner proxy, Playwright-backed runner snapshots.

---

### Task 1: Normalize Natural-Language Numbering

**Files:**
- Modify: `apps/web/src/domain/webCaseImport.ts`
- Test: `apps/web/src/domain/webCaseImport.test.ts`
- Test: `apps/web/src/domain/webRequirementFlow.test.ts`

- [x] Add a regression test for multi-line expected results containing existing mixed numbering (`1)`, `2、`, `3.`) and assert output is exactly `1. ...\n2. ...\n3. ...`.
- [x] Apply `formatNaturalLanguageText` to both `naturalSteps` and `naturalExpectedResult` at case construction boundaries so generated rows, previews, and appended review cases share the same normalized value.
- [x] Preserve blank values and remove only leading list markers, not numeric content inside a sentence.
- [x] Run the focused Vitest files and commit the formatter change.

### Task 2: Review Findings State And Panel Height

**Files:**
- Modify: `apps/web/src/App.vue`
- Modify: `apps/web/src/components/workflow/AiReviewInspector.vue`
- Modify: `apps/web/src/components/workflow/AiReviewInspector.test.ts` or add it if absent
- Modify: `apps/web/src/domain/caseDesignReviewView.ts`
- Test: `apps/web/src/domain/caseDesignReviewView.test.ts`

- [x] Add a view-model test proving accepted findings are excluded from the pending findings collection while ignored/modified findings remain visible with their decision state.
- [x] After a successful `mergeAcceptedReviewFinding`, persist the accepted decision and refresh the computed findings so the adopted item disappears from `覆盖缺口与改进建议`; keep it visible only in generated cases and review history.
- [x] Change the inspector section grid so the findings section receives the available vertical space, the conflict section remains collapsible, and the findings list keeps an internal scroll with a larger minimum usable height and more visible cards.
- [x] Add component assertions for accepted-item removal and the expanded list container class/height behavior.
- [x] Run focused component/domain tests and commit the review change.

### Task 3: Live Browser Login Verification Modal

**Files:**
- Modify: `apps/web/src/domain/webAutomationRunRepository.ts`
- Modify: `apps/web/src/App.vue`
- Modify: `apps/web/src/styles.css`
- Test: `apps/web/src/domain/webAutomationRunRepository.test.ts`
- Test: `apps/web/src/App.integration.test.ts` or the nearest existing environment workflow test

- [x] Add repository coverage that `createWebAutomationRun` plus polling exposes `currentPageUrl`, `currentStep`, `latestScreenshotUrl`, step logs, and terminal failure diagnostics.
- [x] Add dedicated reactive login-verification state and an abort controller. Start a Web runner probe using the existing `buildWebLoginProbeCase(runtime)` and call `createWebAutomationRun`/`waitForWebAutomationRun` with `onSnapshot` updates.
- [x] Render a centered Element Plus dialog while verification is active or has just failed, showing live screenshot, page URL, current phase/step, step log, and a redacted failure detail. Keep credentials and raw request headers out of the dialog.
- [x] Stop polling and close the dialog cleanly on cancel/close; preserve the final failure detail in the existing compact environment verification block.
- [x] Add a success path that shows the final page and route, and a failure path that surfaces the login request error plus current URL without falling back to the API-test environment editor.
- [x] Run repository/component tests, Web build, and local Playwright smoke against `http://127.0.0.1:51730`.

### Task 4: Verification And Delivery

- [x] Run `cd apps/web && npm test -- --run` and `npm run build`.
- [x] Run `cd server/platform && ./mvnw -q -Dtest=AiDraftTestPointServiceTest,WebCaseCompilationServiceTest test`.
- [x] Check `git diff --check`, confirm the local services remain healthy, and commit without pushing unless explicitly requested.

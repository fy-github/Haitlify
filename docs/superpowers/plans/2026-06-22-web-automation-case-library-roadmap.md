> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# Web Automation Case Library Roadmap And Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Web automation test cases to the case library and carry the feature from a simple first version through real execution, reusable elements, document generation, reporting, and enterprise-ready closure.

**Architecture:** Implement Web automation as a first-class `web_automation` case kind. Keep the workflow consistent with API automation, but keep templates, saved case models, execution environments, runner contracts, reports, and defects Web-specific. Deliver in four versions so V1 is small and usable while later versions have a clear path.

**Tech Stack:** Vue 3, TypeScript, Element Plus, Vite, Vitest, Spring Boot, Java 21, MySQL/H2, Playwright runner in a later version.

## 2026-06-23 Current Status

- The product navigation now uses `用例生成` and `自动化`.
- Web automation is presented to users as `Web 用例` in generation and case-library selection.
- The generation page has a template selector. Built-in templates currently include `接口用例`, `Web 用例`, `APP 用例`, `AI 用例`, `功能用例`, and `性能用例`.
- Template configuration uses a left-side template list. Built-in templates are marked `内置`; custom templates can be created and renamed/deleted.
- API automation and Web automation both support `循环次数` from `1` to `100`.
- Web generated cases can now be saved and directly converted into runnable Web execution cases.
- The Java backend forwards `loopCount` through `POST /api/web-automation/runs`.
- The independent Playwright runner expands `loopCount` into separate result rows with `#1`, `#2`, ... suffixes.
- The defect module remains paused; do not continue defect-module feature work until this plan is revisited.
- Deferred follow-up: structured Web step editor, richer report diagnostics UI, and full custom-template field isolation should remain on the later backlog.
- AI requirement analysis direction is now recorded in `/path/to/Haitlify/docs/superpowers/specs/2026-07-03-self-hosted-test-agent-ai-integration-design.md`.
  Web case generation should use the platform-owned AI Gateway and `需求测试点分析 Agent` once AI is implemented.
  Unreviewed AI-generated test points must not enter the formal case library.

---

## Current Baseline

Current repository facts that affect this plan:

- `apps/web/src/domain/caseTemplates.ts` currently defines first-class case kinds for interface, Web, APP, AI, functional, and performance cases.
- `apps/web/src/domain/templateConfigRepository.ts` currently stores `fieldsByKind`, `caseTypes`, and `caseTemplates` while preserving `interfaceFields` compatibility.
- `apps/web/src/domain/caseRepository.ts` currently saves `SavedInterfaceCase[]` in localStorage.
- API automation already has generation from saved interface cases, local execution, run modes, loop execution, reports, and failed-result-to-defect flow.
- Web automation already has Markdown/YAML import, saved Web cases, runnable Web cases, real runner proxy, mock fallback, loop execution, and Web report diagnostics.
- `server/platform` already persists project interface templates and interface case library records.
- The defect module is paused separately; Web automation failed-result-to-defect should reuse the future defect source boundary but should not expand the defect module during V1.

## External Reference Rules

- Use MeterSphere as product reference for UI testing concepts: element library, UI automation scenarios, and test reports.
- Use MeterSphere as product reference for AI-assisted test asset generation inside platform workflows, while keeping generated test points and cases editable and review-gated.
- Use the MeterSphere idea of reusable elements and low-code browser automation, but do not copy its implementation or license-bound code.
- Use shadcn/ui as a visual reference for table, sidebar, tabs, dialog, drawer, badges, filters, and row action composition.
- Keep the current Vue + Element Plus implementation stack.

---

## V1: Simple Web Automation Case Library Foundation

**V1 Goal:** Users can generate or manually create Web automation cases, review them, save them into the case library, and convert them into a Web automation workspace with mock execution results.

**V1 Non-goals:**

- no real browser execution,
- no reusable element library,
- no Word/PDF production extraction; V1 only keeps the upload entry and a controlled user-facing unsupported message,
- no scheduled runs,
- no distributed runner,
- no production Jira mutation.

**V1 Acceptance Gate:**

- `web_automation` is present in template configuration and cannot be confused with `interface`.
- A Web automation case can be created from YAML or Markdown, reviewed, saved, edited, and deleted.
- The case library can switch between API/interface cases and Web automation cases without breaking existing interface case behavior.
- A saved Web case can be converted into a Web automation runnable case.
- Mock execution produces a normalized report with passed/failed/error status.
- No real browser runner is required for V1 completion.

### Task 1: Expand Template Model To Web Automation

**Files:**

- Modify: `apps/web/src/domain/caseTemplates.ts`
- Modify: `apps/web/src/domain/templateConfigRepository.ts`
- Modify: `apps/web/src/domain/templateConfigRepository.test.ts`
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/template/CaseTemplateService.java`
- Modify: `server/platform/src/main/resources/db/migration/V2__template_and_case_library.sql` only if a new migration is not used
- Create: `server/platform/src/main/resources/db/migration/V3__web_automation_templates.sql` if backend schema needs explicit Web defaults

**Deliverable:**

- Add `web_automation` to case kinds.
- Add `webAutomationCaseFields`.
- Migrate old `interfaceFields` storage into `fieldsByKind.interface`.
- Keep backward compatibility for existing localStorage and backend template JSON.
- Add case type applicability for `web_automation`.

**Verification:**

```bash
cd apps/web
npm test -- src/domain/templateConfigRepository.test.ts --run
npm test -- --run
npm run build
```

Expected:

- template migration tests pass,
- full frontend tests pass,
- build exits `0`.

### Task 2: Add Web Automation Case Domain Model

**Files:**

- Create: `apps/web/src/domain/webAutomationCase.ts`
- Create: `apps/web/src/domain/webAutomationCase.test.ts`
- Modify: `apps/web/src/domain/caseRepository.ts`
- Modify: `apps/web/src/domain/caseRepository.test.ts`

**Deliverable:**

- Add `GeneratedWebAutomationCase`.
- Add `SavedWebAutomationCase`.
- Add structured `WebAutomationStep`, `WebLocator`, and `WebAssertion` types.
- Add save/list/delete helpers for Web automation saved cases.
- Keep interface saved-case behavior unchanged.

**Verification:**

```bash
cd apps/web
npm test -- src/domain/webAutomationCase.test.ts src/domain/caseRepository.test.ts --run
```

Expected:

- Web cases can be saved, updated, listed, filtered, and deleted,
- existing interface case repository tests still pass.

### Task 3: Add Web Case Generation From Markdown And YAML

**Files:**

- Create: `apps/web/src/domain/webCaseImport.ts`
- Create: `apps/web/src/domain/webCaseImport.test.ts`
- Modify: `apps/web/src/App.vue`

**Deliverable:**

- Add Markdown parsing for headings, tables, and ordered lists into normalized generation input.
- Add YAML parsing for deterministic Web automation cases.
- Add import UI entry that accepts `.md`, `.yaml`, `.yml`, `.docx`, `.pdf`.
- In V1, `.docx` and `.pdf` show a clear unsupported-for-generation message while preserving the final design path.
- Generated cases enter a preview table before saving.

**Verification:**

```bash
cd apps/web
npm test -- src/domain/webCaseImport.test.ts --run
npm test -- --run
npm run build
```

Expected:

- Markdown and YAML fixtures generate structured Web automation cases,
- Word/PDF are rejected with controlled messages,
- frontend build succeeds.

### Task 4: Make Case Library Multi-Kind

**Files:**

- Modify: `apps/web/src/domain/caseLibraryView.ts`
- Modify: `apps/web/src/domain/caseLibraryView.test.ts`
- Modify: `apps/web/src/App.vue`
- Modify: `apps/web/src/styles.css`

**Deliverable:**

- Add case-kind selector in the case library: `接口用例`, `功能用例`, `Web 自动化用例`.
- Use the selected kind to choose template fields and table columns.
- Add Web automation edit drawer with tabs:
  - 基础信息,
  - 步骤,
  - 元素定位,
  - 断言,
  - 元数据.
- Keep the left project/module layout consistent with the existing case library.

**Verification:**

```bash
cd apps/web
npm test -- src/domain/caseLibraryView.test.ts --run
npm test -- --run
npm run build
```

Expected:

- interface case library still works,
- Web automation case library filters by project/module/kind,
- table text does not overlap at desktop and mobile widths.

### Task 5: Add Web Automation Workspace With Mock Runner

**Files:**

- Create: `apps/web/src/domain/webAutomation.ts`
- Create: `apps/web/src/domain/webAutomation.test.ts`
- Create: `apps/web/src/domain/webAutomationRepository.ts`
- Create: `apps/web/src/domain/webAutomationRepository.test.ts`
- Modify: `apps/web/src/domain/appNavigation.ts`
- Modify: `apps/web/src/App.vue`
- Modify: `apps/web/src/styles.css`

**Deliverable:**

- Add a Web automation workspace or a Web mode under automation.
- Convert saved Web automation cases into runnable Web automation cases.
- Add run modes matching API automation:
  - sequential,
  - concurrent,
  - module.
- Use lower default concurrency:
  - default `2`,
  - range `1` to `10`.
- Mock runner marks cases as passed/failed/error based on deterministic test data so report UI can be built before real Playwright.

**Verification:**

```bash
cd apps/web
npm test -- src/domain/webAutomation.test.ts src/domain/webAutomationRepository.test.ts --run
npm test -- --run
npm run build
```

Expected:

- Web automation conversion is deterministic,
- mock execution produces normalized reports,
- API automation behavior remains unchanged.

### Task 6: Record V1 Decisions And Stop

**Files:**

- Modify: `docs/ai-test-platform/decisions.md`
- Modify: `docs/superpowers/specs/2026-06-22-web-automation-case-library-design.md`
- Modify: `docs/superpowers/plans/2026-06-22-web-automation-case-library-roadmap.md`

**Deliverable:**

- Record that V1 intentionally stops before real browser execution.
- Record the model boundary between API automation and Web automation.
- Record that defect integration is only source metadata in V1.

**Verification:**

```bash
git status --short
```

Expected:

- only intended V1 files are changed before commit.

---

## V2: Real Playwright Execution And Web Reports

**V2 Goal:** Replace mock execution with real browser execution through a backend runner contract, producing step logs, screenshots, and normalized reports.

**V2 Acceptance Gate:**

- Web automation cases execute through a backend runner API instead of frontend-only mock logic.
- The frontend sends normalized cases and Web environment settings, not Playwright internals.
- Reports include step-level logs, error messages, and screenshot references.
- Runner-disabled local development remains supported with clear user feedback.
- API automation execution remains unchanged.

### Task 1: Add Backend Runner Contract

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationRunController.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationRunService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationRunRepository.java`
- Create: `server/platform/src/main/resources/db/migration/V4__web_automation_runs.sql`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/webautomation/WebAutomationRunControllerTest.java`

**Deliverable:**

- Add `POST /api/web-automation/runs`.
- Add `GET /api/web-automation/runs/{runId}`.
- Persist normalized run summary and step results.
- Return controlled unsupported response when runner is disabled.

**Verification:**

```bash
cd server/platform
./mvnw -Dtest=WebAutomationRunControllerTest test
```

Expected:

- controller contract tests pass.

### Task 2: Add Playwright Runner Adapter

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/PlaywrightRunClient.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationProperties.java`
- Modify: `server/platform/src/main/resources/application.yml`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/webautomation/PlaywrightRunClientTest.java`

**Deliverable:**

- Add config keys for runner URL, timeout, artifact directory, and enabled flag.
- Keep the platform API independent from Playwright internal details.
- Support disabled mode for local dev.

**Verification:**

```bash
cd server/platform
./mvnw -Dtest=PlaywrightRunClientTest test
```

Expected:

- request mapping and disabled behavior tests pass.

### Task 3: Add Web Environment Repository

**Files:**

- Create: `apps/web/src/domain/webExecutionEnvironmentRepository.ts`
- Create: `apps/web/src/domain/webExecutionEnvironmentRepository.test.ts`
- Modify: `apps/web/src/App.vue`

**Deliverable:**

- Add Web environments with:
  - name,
  - base URL,
  - browser,
  - headless,
  - viewport,
  - timeout,
  - screenshot strategy,
  - video strategy,
  - storage state mode,
  - variables.
- Add an environment drawer mirroring the API automation environment UX, but with Web-specific fields.

**Verification:**

```bash
cd apps/web
npm test -- src/domain/webExecutionEnvironmentRepository.test.ts --run
npm test -- --run
npm run build
```

Expected:

- Web environment persistence works,
- API environment persistence remains unchanged.

### Task 4: Wire Real Execution Into Frontend

**Files:**

- Create: `apps/web/src/domain/webAutomationRunRepository.ts`
- Create: `apps/web/src/domain/webAutomationRunRepository.test.ts`
- Modify: `apps/web/src/domain/webAutomation.ts`
- Modify: `apps/web/src/App.vue`

**Deliverable:**

- Submit selected Web automation cases to backend.
- Poll or refresh run status.
- Render summary, step logs, screenshots, and error messages.
- Keep mock runner available only for local disabled-runner mode.

**Verification:**

```bash
cd apps/web
npm test -- src/domain/webAutomationRunRepository.test.ts src/domain/webAutomation.test.ts --run
npm test -- --run
npm run build
```

Expected:

- frontend maps backend run payloads into report rows,
- disabled backend behavior is user-visible and non-destructive.

---

## V3: Element Library, Rich Document Generation, And Reuse

**V3 Goal:** Reduce maintenance cost by introducing reusable UI elements and stronger generation from Word/PDF/MD/YAML.

**V3 Acceptance Gate:**

- Web steps can reference reusable element records by `elementRef`.
- Inline locators remain supported for quick one-off cases.
- Word and PDF inputs produce normalized extraction sections before AI generation.
- Reusable instruction groups can be expanded into execution steps without circular references.
- Existing V1/V2 Web cases migrate without losing inline locator data.

### Task 1: Add Element Library

**Files:**

- Create: `apps/web/src/domain/webElementRepository.ts`
- Create: `apps/web/src/domain/webElementRepository.test.ts`
- Modify: `apps/web/src/domain/webAutomationCase.ts`
- Modify: `apps/web/src/App.vue`
- Create: `server/platform/src/main/resources/db/migration/V5__web_elements.sql`

**Deliverable:**

- Add reusable elements:
  - project,
  - module,
  - page,
  - name,
  - locator type,
  - locator value,
  - fallback locator,
  - description.
- Let Web steps reference `elementRef`.
- Updating an element updates execution resolution without editing every case.

**Verification:**

```bash
cd apps/web
npm test -- src/domain/webElementRepository.test.ts src/domain/webAutomationCase.test.ts --run
```

Expected:

- element references resolve correctly,
- inline locators still work.

### Task 2: Add Word And PDF Extraction

**Files:**

- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/document/DocumentExtractionController.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/document/DocumentExtractionService.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/document/DocumentExtractionControllerTest.java`
- Modify: `apps/web/src/domain/webCaseImport.ts`
- Modify: `apps/web/src/domain/webCaseImport.test.ts`

**Deliverable:**

- Extract normalized text sections and tables from `.docx` and `.pdf`.
- Preserve source filename and page/section references.
- Feed extracted sections into Web automation generation preview.

**Verification:**

```bash
cd server/platform
./mvnw -Dtest=DocumentExtractionControllerTest test

cd ../../apps/web
npm test -- src/domain/webCaseImport.test.ts --run
```

Expected:

- Word/PDF fixture extraction returns normalized sections,
- frontend converts extracted sections into generation input.

### Task 3: Add Reusable Instructions And Scenario Composition

**Files:**

- Create: `apps/web/src/domain/webInstructionRepository.ts`
- Create: `apps/web/src/domain/webInstructionRepository.test.ts`
- Modify: `apps/web/src/domain/webAutomationCase.ts`
- Modify: `apps/web/src/App.vue`

**Deliverable:**

- Add reusable instruction groups, such as login, logout, search, create, delete.
- Allow Web automation cases to reference an instruction group.
- Expand instruction references at execution time.

**Verification:**

```bash
cd apps/web
npm test -- src/domain/webInstructionRepository.test.ts src/domain/webAutomationCase.test.ts --run
```

Expected:

- instruction group references expand deterministically,
- circular references are rejected with a clear error.

---

## V4: Completion And Enterprise Readiness

**V4 Goal:** Finish the Web automation module so it is reliable for team use and long-term operation.

**V4 Acceptance Gate:**

- Scheduled and manual runs share the same report and artifact model.
- Trace, screenshot, video, and log artifacts have a retention policy.
- Failed Web automation results can create defect drafts with page, step, locator, screenshot, and environment context.
- Permission and audit boundaries are defined for environment, element, runner, schedule, and report mutations.
- Large project data remains usable through filters, pagination, and indexed view helpers.

## Deferred Follow-up

- The structured Web step editor remains a planned follow-up after the current V1 editor slice.
- The current Web editor supports practical text-based editing for:
  - basic metadata,
  - steps text,
  - locator text,
  - assertion text.
- A later dedicated step editor should replace the free-text step area with structured row editing for:
  - action,
  - target type,
  - target,
  - value,
  - timeout,
  - screenshot behavior.
- Do not lose this follow-up when moving into V2 Playwright execution. The V2 runner should continue to accept the current normalized step model so the richer editor can land independently.

### Task 1: Scheduled Runs And Execution Plans

**Deliverable:**

- Add schedule configuration for Web automation suites.
- Support manual, scheduled, and CI-triggered runs.
- Store run ownership, trigger source, and environment snapshot.

**Verification:**

- backend schedule tests pass,
- frontend schedule form validation tests pass.

### Task 2: Artifact Retention And Trace Review

**Deliverable:**

- Store screenshots, videos, traces, and logs with retention policy.
- Report detail opens artifacts safely from backend URLs.
- Export report packages as JSON plus artifact manifest.

**Verification:**

- artifact access tests pass,
- report export contains trace references.

### Task 3: Defect Integration Hardening

**Deliverable:**

- Failed Web automation steps create defect drafts with Web-specific context.
- Jira export/proxy paths include:
  - project,
  - page,
  - failed step,
  - locator,
  - screenshot/trace link,
  - environment snapshot.
- Real Jira mutation remains separate from read-only Jira proxy until approved.

**Verification:**

- defect draft tests include Web automation source,
- no production Jira mutating API is called in tests.

### Task 4: Permissions, Audit, And Cleanup

**Deliverable:**

- Restrict who can edit environments, delete runs, and change element library records.
- Audit Web case edits, element changes, runner config changes, and schedule changes.
- Add cleanup tooling for stale local mock data after backend persistence is authoritative.

**Verification:**

- backend authorization tests pass once auth exists,
- audit records are written for Web automation mutations.

---

## Done Definition For The Whole Web Automation Module

The module is complete when all of these are true:

- Web automation is a first-class case kind in templates, case library, generation, automation, reports, and defects.
- API automation behavior remains unchanged.
- Web cases can be generated from Markdown, YAML, Word, and PDF.
- Web cases can be edited as structured steps.
- Elements can be reused across cases.
- Cases can execute through a real browser runner.
- Reports include step logs and artifacts.
- Failed results can become defect drafts.
- Large projects remain usable through project/module filters, pagination, and indexed view helpers.
- The module has frontend unit tests, backend contract tests, and at least one browser smoke path.

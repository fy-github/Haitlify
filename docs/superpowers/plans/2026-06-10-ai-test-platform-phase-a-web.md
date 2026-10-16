> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# AI Test Platform Phase A Web Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a usable Phase A web workbench for OpenAPI/Apifox import, interface test case generation, preview/edit/delete, and Chinese CSV export, while showing future modules as UI placeholders.

**Architecture:** Start with a frontend-only Vue 3 workbench because Java Runtime and Maven are currently unavailable in this environment. Keep the domain logic in framework-independent TypeScript modules so the same structures can later be moved behind Spring Boot APIs without changing the UI contract.

**Tech Stack:** Vue 3, TypeScript, Vite, Vitest, Element Plus, lucide-vue-next, browser File API, local CSV export.

---

## File Structure

- Create `apps/web/package.json`: frontend scripts and dependencies.
- Create `apps/web/index.html`: Vite entry HTML.
- Create `apps/web/tsconfig.json`: TypeScript config.
- Create `apps/web/tsconfig.node.json`: TypeScript config for Vite.
- Create `apps/web/vite.config.ts`: Vite + Vue + Vitest config.
- Create `apps/web/src/main.ts`: Vue app bootstrap.
- Create `apps/web/src/App.vue`: application shell and feature wiring.
- Create `apps/web/src/styles.css`: approved workflow workspace styling.
- Create `apps/web/src/domain/caseTemplates.ts`: Chinese headers and case type defaults.
- Create `apps/web/src/domain/openapiParser.ts`: OpenAPI parsing and interface filtering.
- Create `apps/web/src/domain/caseGenerator.ts`: deterministic case generation by granularity.
- Create `apps/web/src/domain/csvExport.ts`: Chinese CSV export helper.
- Create `apps/web/src/domain/openapiParser.test.ts`: parser tests using inline OpenAPI fixtures.
- Create `apps/web/src/domain/caseGenerator.test.ts`: generation tests for required case types and headers.
- Create `apps/web/src/domain/csvExport.test.ts`: CSV export tests for Chinese headers.
- Modify `docs/ai-test-platform/decisions.md`: record that Phase A starts with a frontend-local generator due to missing Java/Maven.

## Tasks

### Task 1: Scaffold Vue App And Tests

**Files:**
- Create: `apps/web/package.json`
- Create: `apps/web/index.html`
- Create: `apps/web/tsconfig.json`
- Create: `apps/web/tsconfig.node.json`
- Create: `apps/web/vite.config.ts`
- Create: `apps/web/src/main.ts`

- [ ] **Step 1: Create minimal project files**

Create the listed files with Vite/Vue/Vitest configuration.

- [ ] **Step 2: Install dependencies**

Run: `npm install` from `apps/web`.

Expected: dependencies install successfully.

- [ ] **Step 3: Verify empty test command**

Run: `npm run test -- --run`.

Expected: Vitest starts and reports no tests or passes once tests are added.

### Task 2: Add Domain Defaults

**Files:**
- Create: `apps/web/src/domain/caseTemplates.ts`

- [ ] **Step 1: Implement default Chinese headers**

Export interface and functional field templates exactly matching the design spec.

- [ ] **Step 2: Implement default case types**

Export case types: `正向`, `必填缺失`, `参数类型错误`, `边界值`, `鉴权缺失`, `权限不足`, `数据不存在`, `重复提交`, `业务规则异常`.

### Task 3: TDD OpenAPI Parser

**Files:**
- Create: `apps/web/src/domain/openapiParser.test.ts`
- Create: `apps/web/src/domain/openapiParser.ts`

- [ ] **Step 1: Write failing tests**

Tests must assert that OpenAPI 3.0 JSON is parsed into operations with project title, module/tag, method, path, summary, operationId, parameters, request body, and response status list.

- [ ] **Step 2: Run parser tests and verify red**

Run: `npm run test -- --run src/domain/openapiParser.test.ts`.

Expected: fail because parser file is missing or not implemented.

- [ ] **Step 3: Implement minimal parser**

Support `info`, `tags`, `paths`, methods, parameters, requestBody, responses, and filtering helpers.

- [ ] **Step 4: Run parser tests and verify green**

Run: `npm run test -- --run src/domain/openapiParser.test.ts`.

Expected: pass.

### Task 4: TDD Case Generator

**Files:**
- Create: `apps/web/src/domain/caseGenerator.test.ts`
- Create: `apps/web/src/domain/caseGenerator.ts`

- [ ] **Step 1: Write failing tests**

Tests must assert risk-based generation includes positive, required-missing, type-error, auth-missing, permission, not-found, duplicate-submit, and business-rule cases when applicable.

- [ ] **Step 2: Run generator tests and verify red**

Run: `npm run test -- --run src/domain/caseGenerator.test.ts`.

Expected: fail because generator file is missing or not implemented.

- [ ] **Step 3: Implement minimal generator**

Generate visible Chinese row fields plus hidden executable metadata. Support `basic`, `risk`, and `full` granularity.

- [ ] **Step 4: Run generator tests and verify green**

Run: `npm run test -- --run src/domain/caseGenerator.test.ts`.

Expected: pass.

### Task 5: TDD CSV Export

**Files:**
- Create: `apps/web/src/domain/csvExport.test.ts`
- Create: `apps/web/src/domain/csvExport.ts`

- [ ] **Step 1: Write failing tests**

Tests must assert exported CSV starts with Chinese headers and escapes comma, quote, and newline values.

- [ ] **Step 2: Run CSV tests and verify red**

Run: `npm run test -- --run src/domain/csvExport.test.ts`.

Expected: fail because exporter file is missing or not implemented.

- [ ] **Step 3: Implement minimal CSV exporter**

Export selected fields in template order with UTF-8 BOM.

- [ ] **Step 4: Run CSV tests and verify green**

Run: `npm run test -- --run src/domain/csvExport.test.ts`.

Expected: pass.

### Task 6: Build Workbench UI

**Files:**
- Create: `apps/web/src/App.vue`
- Create: `apps/web/src/styles.css`
- Modify: `apps/web/src/main.ts`

- [ ] **Step 1: Create app shell**

Build the approved modern workflow workspace with collapsible first-level sidebar, secondary pane, top toolbar, workflow canvas, and case preview table.

- [ ] **Step 2: Wire OpenAPI file import**

Use browser File API to read JSON, call the parser, show grouped modules/interfaces, and estimate generated case count.

- [ ] **Step 3: Wire generation controls**

Add generation scope, `basic/risk/full` granularity selector with risk default, and case type selection.

- [ ] **Step 4: Wire preview actions**

Add filters, grouping indicator, single-case edit, delete generated case, counts, AI review placeholder, save placeholder, and export CSV.

- [ ] **Step 5: Add future module placeholders**

Show requirements, defects, personnel scheduling, versions, charts, and panorama modules as visible placeholders with disabled backend status.

### Task 7: Verify And Run

**Files:**
- Modify: `docs/ai-test-platform/decisions.md`

- [ ] **Step 1: Run full test suite**

Run: `npm run test -- --run`.

Expected: all tests pass.

- [ ] **Step 2: Run production build**

Run: `npm run build`.

Expected: Vite build succeeds.

- [ ] **Step 3: Start dev server**

Run: `npm run dev -- --host 127.0.0.1`.

Expected: local URL is printed.

- [ ] **Step 4: Record implementation decision**

Append to `docs/ai-test-platform/decisions.md` that Phase A begins with frontend-local parsing/generation while backend Java/Spring Boot is pending local toolchain setup.

## Self-review

- Spec coverage: this plan implements the urgent use-case and API-test-related workflow first. It intentionally defers Spring Boot because the current machine lacks Java Runtime and Maven.
- Placeholder scan: UI placeholders are intentional for future modules and are visible as backend-pending modules.
- Type consistency: `basic`, `risk`, and `full` are the generation granularity ids used across parser, generator, and UI.

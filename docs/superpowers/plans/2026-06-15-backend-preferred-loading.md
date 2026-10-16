> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# Backend Preferred Loading Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Load template configuration and current-project saved interface cases from the backend on app startup when available, while preserving local fallback behavior.

**Architecture:** Add a focused case-repository replacement helper for one project, then add `loadSavedCasesWithBackend` to the existing backend adapter. Wire `App.vue` startup to call template and case backend loaders without changing existing local-first writes.

**Tech Stack:** Vue 3, TypeScript, Vitest, Vite.

---

### Task 1: Case Repository Project Replacement

**Files:**
- Modify: `apps/web/src/domain/caseRepository.ts`
- Modify: `apps/web/src/domain/caseRepository.test.ts`

- [ ] Write failing tests for replacing saved cases for one project while preserving another project.
- [ ] Run `npm test -- src/domain/caseRepository.test.ts --run` and confirm failure.
- [ ] Add `replaceSavedCasesForProject(storage, projectName, cases)` to `caseRepository.ts`.
- [ ] Run the target test and confirm pass.

### Task 2: Backend Case Loading Adapter

**Files:**
- Modify: `apps/web/src/domain/platformBackendRepository.ts`
- Modify: `apps/web/src/domain/platformBackendRepository.test.ts`

- [ ] Write failing tests for `loadSavedCasesWithBackend`.
- [ ] Cover backend data mirroring, empty backend preserving local data, and backend failure fallback.
- [ ] Run `npm test -- src/domain/platformBackendRepository.test.ts --run` and confirm failure.
- [ ] Implement `loadSavedCasesWithBackend`.
- [ ] Run the target test and confirm pass.

### Task 3: App Startup Wiring

**Files:**
- Modify: `apps/web/src/App.vue`

- [ ] Import `loadSavedCasesWithBackend` and use existing `loadTemplateConfigWithBackend`.
- [ ] Add a startup loader called from `onMounted`.
- [ ] Refresh `savedCases`, `templateConfig`, and selected case types after backend loading.
- [ ] Keep failures silent because repository helpers already fall back to local state.

### Task 4: Decisions And Verification

**Files:**
- Modify: `docs/ai-test-platform/decisions.md`

- [ ] Record backend-preferred loading as the next migration step after local-first sync.
- [ ] Run `npm test -- --run` in `apps/web`.
- [ ] Run `npm run build` in `apps/web`.
- [ ] Run `./mvnw test` in `server/platform`.
- [ ] Do a browser smoke check for `/cases`.
- [ ] Commit the completed checkpoint.

---

## Self-Review

- Spec coverage: one-project backend case loading, template backend loading, local fallback, empty backend preservation, and current non-goals are covered.
- Placeholder scan: no placeholder steps remain.
- Type consistency: the plan uses `replaceSavedCasesForProject` and `loadSavedCasesWithBackend` consistently.

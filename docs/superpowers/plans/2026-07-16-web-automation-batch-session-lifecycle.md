> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# Web Automation Batch Session Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Execute automatic login and logout once per actual browser while all cases assigned to that browser reuse one authenticated Playwright session.

**Architecture:** Move run lifecycle orchestration from per-case browser creation to browser-group sessions. Keep reusable step construction in `runner-core.mjs`, persist optional logout steps in the Web environment runtime, and expose setup/test/teardown evidence through the existing asynchronous run snapshot.

**Tech Stack:** Node.js, Playwright, Node test runner, Vue 3, TypeScript, Element Plus, Vitest.

---

## File Map

- Modify `server/web-runner/runner-core.mjs`: expose separate setup, test, and teardown step builders.
- Modify `server/web-runner/runner-core.test.mjs`: cover teardown normalization and no per-case login expansion.
- Create `server/web-runner/run-executor.mjs`: group cases by browser and execute one shared Playwright session per group.
- Create `server/web-runner/run-executor.test.mjs`: cover grouping and lifecycle failure semantics with injected browser dependencies.
- Modify `server/web-runner/server.mjs`: delegate asynchronous jobs to the new executor and retain screenshot/diagnostic adapters.
- Modify `server/web-runner/fixtures/login-app.mjs`: expose session-aware two-case pages and logout controls.
- Modify `server/web-runner/login-flow.integration.test.mjs`: prove one login and one logout across two real Chromium cases.
- Modify `apps/web/src/domain/executionEnvironmentRepository.ts`: add normalized persisted logout steps to the Web runtime.
- Modify `apps/web/src/domain/executionEnvironmentRepository.test.ts`: cover defaults, legacy migration, validation, and runtime building.
- Modify `apps/web/src/domain/webAutomation.ts`: allow `teardown` in result step phases.
- Modify `apps/web/src/domain/webAutomationRunRepository.ts`: allow `teardown` in live snapshot phases.
- Modify `apps/web/src/domain/webAutomationRunView.ts`: label teardown as environment cleanup.
- Modify `apps/web/src/domain/webAutomationRunView.test.ts`: cover setup/test/teardown labels.
- Modify `apps/web/src/App.vue`: edit logout steps in the Web environment dialog.
- Modify `apps/web/src/styles.css`: lay out lifecycle step rows without nested cards.

### Task 1: Lifecycle Step Contracts

- [ ] Add failing runner-core tests asserting login setup is built separately, case test steps never include environment login, and logout steps are normalized with `phase: 'teardown'`.
- [ ] Run `cd server/web-runner && node --test runner-core.test.mjs` and confirm the new tests fail for missing exports/behavior.
- [ ] Implement `buildLoginSetupSteps`, `buildCaseTestSteps`, and `buildLogoutTeardownSteps`; keep `buildRunnableSteps` compatible for callers that still need the combined form.
- [ ] Re-run the focused runner-core tests and confirm they pass.

### Task 2: Browser-Grouped Run Executor

- [ ] Add failing executor tests with fake browsers proving two Chromium cases launch once, login once, run both case steps, logout once, and close once.
- [ ] Add failing tests proving Chromium and Firefox each receive independent setup/teardown, setup failures fan out to group errors, case failures continue, and teardown failures only add diagnostics.
- [ ] Implement `executeRunJobByBrowserGroups(job, payload, dependencies)` in `run-executor.mjs` with injected launch, context options, cookies, step execution, screenshots, diagnostics, and browser resolution.
- [ ] Replace the per-case browser lifecycle in `server.mjs` with the executor call.
- [ ] Run `cd server/web-runner && npm test` and confirm all runner tests pass.

### Task 3: Real Chromium Session Verification

- [ ] Extend the login fixture with an in-memory/session cookie, two authenticated operations, a logout button, and a login-page assertion target.
- [ ] Update the integration payload to contain two cases and logout teardown steps.
- [ ] Assert one login event, both case results passed, one logout event, teardown logs passed, and the final page returned to login.
- [ ] Run `cd server/web-runner && node --test login-flow.integration.test.mjs` and confirm the real browser test passes.

### Task 4: Web Environment Logout Configuration

- [ ] Add failing Vitest cases for default `logout: { enabled: false, steps: [] }`, legacy migration, step normalization, validation, and `buildWebExecutionRuntime` output.
- [ ] Implement persisted logout types and normalization in `executionEnvironmentRepository.ts`.
- [ ] Add environment-dialog controls for enabling, adding, deleting, and reordering logout steps; use existing Web step actions and target syntax.
- [ ] Update live/result phase types and the execution drawer labels for `teardown`.
- [ ] Run `cd apps/web && npm test -- --run` and confirm all frontend tests pass.

### Task 5: Final Verification

- [ ] Run `cd server/web-runner && npm test` and confirm zero failures, including real Chromium.
- [ ] Run `cd apps/web && npm test -- --run` and confirm zero failures.
- [ ] Run `cd apps/web && npm run build` and confirm TypeScript and Vite complete successfully.
- [ ] Restart only the runner service because runner source changed; leave Web and platform services running.
- [ ] Verify `http://127.0.0.1:5173/automation`, `http://127.0.0.1:8080/api/projects`, and `http://127.0.0.1:9323/health` return healthy responses.

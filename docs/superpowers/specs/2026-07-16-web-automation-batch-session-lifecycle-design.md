> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# Web Automation Batch Session Lifecycle Design

## Goal

Run Web automation with one authenticated browser session per actual browser. Login is a browser-group setup condition, test cases reuse the same context and page, and logout is a browser-group teardown condition.

## Lifecycle

The runner expands looped cases, resolves the actual browser for every case, and groups cases by browser while preserving their original order inside each group. Each browser group executes:

1. Launch one browser, context, and page.
2. Apply runtime cookies, headers, variables, timeouts, and viewport once.
3. Execute automatic-login setup steps once when enabled.
4. Execute every case assigned to that browser without repeating login.
5. Execute configured logout teardown steps once.
6. Close the page, context, and browser.

Chromium, Chrome, Edge, Firefox, and WebKit keep separate sessions. A mixed-browser run therefore performs one setup and teardown for each browser that actually has cases.

## Environment Contract

The Web runtime keeps the existing `login` configuration and adds an optional `logout` configuration:

- `enabled`: whether teardown is active.
- `steps`: an ordered list of Web automation steps.

Logout steps support the runner's existing actions and target syntax so applications can model avatar-menu clicks, logout clicks, confirmation dialogs, navigation, and login-page assertions without a product-specific schema. Step IDs are persisted and normalized; blank names use a concise default label.

## Execution And Evidence

Step logs use three phases: `setup`, `test`, and `teardown`. Setup and teardown steps are written once per browser group. Case results contain only the test steps belonging to that case, while the live run timeline contains all lifecycle logs.

Progress counts setup and teardown once per browser group plus every case test step. The current phase in the visual drawer must distinguish environment preparation, case execution, and environment cleanup.

## Failure Semantics

- Setup failure: all cases in that browser group become `error`; no test steps run for that group; teardown is still attempted when a page exists.
- Case failure: the failed case keeps its original evidence and later cases in the same browser group continue.
- Teardown failure: append a failed `teardown` log and run diagnostic without changing already-computed case results.
- Stop: stop scheduling test steps, attempt teardown without waiting on pause/stop controls, then close all browser resources and preserve stopped state.
- Browser initialization failure: all cases in that browser group become `error`; no teardown runs because no usable page exists.

## UI

The Web environment dialog adds an `退出登录后置条件` section below automatic login. Users can enable it, add/remove/reorder steps, and configure step name, action, target, and optional value. The section explains that it runs once after all cases for each browser.

The execution drawer timeline labels teardown logs as `清理`; the phase summary displays `环境准备`, `用例执行`, or `环境清理`.

## Verification

- Unit tests prove grouping, one setup/teardown per browser, setup failure fan-out, case failure continuation, and teardown failure diagnostics.
- Environment repository tests prove defaulting, legacy migration, persistence, runtime building, and validation.
- A real Chromium integration test runs two cases, asserts one login and one logout, and verifies the second case still has the authenticated session.
- Full runner tests, frontend tests, TypeScript build, and live service health checks must pass.

> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# Web Automation Visual Run Control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace synchronous Web execution with controllable asynchronous runs that expose live step screenshots, pause/resume/stop controls, and real per-run browser selection.

**Architecture:** The Node Runner owns in-memory run jobs and Playwright handles, while Spring mirrors short-lived create/query/control calls instead of waiting for a whole batch. Vue polls sanitized snapshots into a dedicated view model and renders the accepted wide right-side execution drawer without leaking credentials.

**Tech Stack:** Node.js, Playwright, Node test runner, Spring Boot, Java HTTP client, Jackson, JUnit/MockMvc, Vue 3, TypeScript, Element Plus, Vitest.

---

## File Map

- Create `server/web-runner/browser-launcher.mjs`: resolve browser IDs to Playwright engines/channels and probe capabilities.
- Create `server/web-runner/browser-launcher.test.mjs`: verify exact engine/channel selection and unavailable-browser diagnostics.
- Create `server/web-runner/run-job-manager.mjs`: own lifecycle state, control flags, safe-boundary waiting, progress snapshots, sanitization, and retention.
- Create `server/web-runner/run-job-manager.test.mjs`: verify transitions, stop behavior, synthetic_secret redaction, and retention.
- Modify `server/web-runner/server.mjs`: expose capabilities and asynchronous run endpoints, execute jobs, update screenshots and diagnostics.
- Create `server/web-runner/server.integration.test.mjs`: verify HTTP create/poll/pause/resume/stop and nonblocking creation.
- Create `server/web-runner/fixtures/login-app.mjs`: deterministic local login target with delayed steps.
- Create `server/web-runner/login-flow.integration.test.mjs`: verify Chromium login, visual snapshots, pause/resume, stop, and nonblank screenshots.
- Modify `server/web-runner/package.json`: include all tests and integration test command.
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/PlaywrightRunClient.java`: add short create/query/control/capability requests.
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationRunController.java`: mirror asynchronous endpoints and typed payloads.
- Modify `server/platform/src/test/java/com/haitlify/aitest/platform/webautomation/PlaywrightRunClientTest.java`: verify Runner method/path/status forwarding.
- Modify `server/platform/src/test/java/com/haitlify/aitest/platform/webautomation/WebAutomationRunControllerTest.java`: verify platform API contract and error mapping.
- Modify `apps/web/src/domain/webAutomationCase.ts`: extend supported browser IDs.
- Modify `apps/web/src/domain/executionEnvironmentRepository.ts`: normalize and persist Chrome/Edge browser defaults.
- Modify related domain tests for browser migration and serialization.
- Rewrite `apps/web/src/domain/webAutomationRunRepository.ts`: create, query, control, capability, and polling APIs.
- Rewrite `apps/web/src/domain/webAutomationRunRepository.test.ts`: cover the asynchronous frontend contract.
- Create `apps/web/src/domain/webAutomationRunView.ts`: lifecycle labels, progress, allowed controls, browser availability, and timeline projection.
- Create `apps/web/src/domain/webAutomationRunView.test.ts`: cover all view states without mounting the large app.
- Modify `apps/web/src/App.vue`: wire the run poller, browser override, active-run indicator, and execution drawer.
- Modify `apps/web/src/styles.css`: style a stable wide drawer, preview, timeline, diagnostics, and responsive fallback.
- Modify `server/web-runner/README.md` and `server/platform/README.md`: document asynchronous routes and browser installation.

### Task 1: Real Browser Launcher and Capability Probe

**Files:**
- Create: `server/web-runner/browser-launcher.mjs`
- Create: `server/web-runner/browser-launcher.test.mjs`
- Modify: `server/web-runner/server.mjs`

- [ ] **Step 1: Write failing launcher tests**

Test the desired dependency-injected API so unit tests do not launch real browsers:

```js
test('launches exact browser engine and channel without fallback', async () => {
  const calls = []
  const engines = {
    chromium: { launch: async (options) => calls.push(['chromium', options]) },
    firefox: { launch: async (options) => calls.push(['firefox', options]) },
    webkit: { launch: async (options) => calls.push(['webkit', options]) }
  }
  await launchSelectedBrowser('chrome', { headless: true }, engines)
  await launchSelectedBrowser('msedge', { headless: true }, engines)
  await launchSelectedBrowser('firefox', { headless: true }, engines)
  assert.deepEqual(calls, [
    ['chromium', { headless: true, channel: 'chrome' }],
    ['chromium', { headless: true, channel: 'msedge' }],
    ['firefox', { headless: true }]
  ])
})

test('probes every supported browser independently', async () => {
  const fakeEngines = {
    chromium: {
      launch: async ({ channel } = {}) => {
        if (channel) throw new Error(`${channel} is not installed`)
        return { close: async () => {} }
      }
    },
    firefox: { launch: async () => ({ close: async () => {} }) },
    webkit: { launch: async () => ({ close: async () => {} }) }
  }
  const capabilities = await probeBrowserCapabilities(fakeEngines)
  assert.deepEqual(capabilities.browsers.map(({ id, available }) => ({ id, available })), [
    { id: 'chromium', available: true },
    { id: 'chrome', available: false },
    { id: 'msedge', available: false },
    { id: 'firefox', available: true },
    { id: 'webkit', available: true }
  ])
})
```

- [ ] **Step 2: Run the tests and verify RED**

Run: `cd server/web-runner && node --test browser-launcher.test.mjs`

Expected: FAIL because `browser-launcher.mjs` does not exist.

- [ ] **Step 3: Implement the launcher**

Export these exact units:

```js
export const SUPPORTED_BROWSERS = ['chromium', 'chrome', 'msedge', 'firefox', 'webkit']

export const launchSelectedBrowser = (browserId, options, engines) => {
  if (browserId === 'firefox') return engines.firefox.launch(options)
  if (browserId === 'webkit') return engines.webkit.launch(options)
  if (browserId === 'chrome' || browserId === 'msedge') {
    return engines.chromium.launch({ ...options, channel: browserId })
  }
  if (browserId === 'chromium') return engines.chromium.launch(options)
  throw new Error(`Unsupported browser: ${browserId}`)
}
```

`probeBrowserCapabilities` launches and closes each choice independently and returns `{ id, label, available, recommendation }`. Do not silently substitute Chromium.

- [ ] **Step 4: Verify GREEN and existing Runner tests**

Run: `cd server/web-runner && npm test`

Expected: all launcher and existing runner-core tests PASS.

- [ ] **Step 5: Commit the launcher slice**

```bash
git add server/web-runner/browser-launcher.mjs server/web-runner/browser-launcher.test.mjs server/web-runner/server.mjs
git commit -m "feat: select web runner browser engines"
```

### Task 2: Run Job State Machine and Secret-Safe Snapshots

**Files:**
- Create: `server/web-runner/run-job-manager.mjs`
- Create: `server/web-runner/run-job-manager.test.mjs`

- [ ] **Step 1: Write failing lifecycle tests**

Cover state transitions and boundary controls with the wished-for API:

```js
const manager = createRunJobManager({ idFactory: () => 'run-1', now: fakeNow })
const job = manager.create({ cases: [{ id: 'WEB-1', steps: [] }], runtime: { login: { password: 'synthetic_secret' } } })
assert.equal(job.snapshot().status, 'queued')
job.markRunning()
job.requestPause()
assert.equal(job.snapshot().status, 'pause_requested')
const wait = job.beforeNextStep()
assert.equal(job.snapshot().status, 'paused')
job.resume()
await wait
assert.equal(job.snapshot().status, 'running')
job.requestStop()
await assert.rejects(() => job.beforeNextStep(), RunStoppedError)
assert.equal(job.snapshot().status, 'stopped')
```

Add a sanitization assertion:

```js
const serialized = JSON.stringify(job.snapshot())
assert.equal(serialized.includes('synthetic_secret'), false)
assert.equal(serialized.includes('cookie-token'), false)
assert.equal(serialized.includes('typed-password'), false)
```

- [ ] **Step 2: Run and verify RED**

Run: `cd server/web-runner && node --test run-job-manager.test.mjs`

Expected: FAIL because the manager module does not exist.

- [ ] **Step 3: Implement the minimal state machine**

Implement `createRunJobManager`, `RunStoppedError`, and job methods:

```js
job.markRunning()
job.updateCurrent({ caseItem, step, pageUrl })
job.appendStepLog(log)
job.appendCaseResult(result)
job.updateScreenshot({ url, capturedAt })
job.requestPause()
job.resume()
job.requestStop()
await job.beforeNextStep()
job.complete(report)
job.fail(error)
job.snapshot()
```

Keep the original runtime and credentials in a private closure. Build snapshots from an allowlist; never clone the input payload into a response. Evict terminal jobs older than one hour and keep no more than 50 terminal entries.

- [ ] **Step 4: Verify GREEN**

Run: `cd server/web-runner && node --test run-job-manager.test.mjs`

Expected: all lifecycle, sanitization, and retention tests PASS.

- [ ] **Step 5: Commit the job manager**

```bash
git add server/web-runner/run-job-manager.mjs server/web-runner/run-job-manager.test.mjs
git commit -m "feat: manage controllable web run jobs"
```

### Task 3: Asynchronous Runner HTTP API and Live Evidence

**Files:**
- Modify: `server/web-runner/server.mjs`
- Create: `server/web-runner/server.integration.test.mjs`
- Modify: `server/web-runner/package.json`

- [ ] **Step 1: Add failing HTTP contract tests**

Start an injectable server on an ephemeral port and assert:

```js
const created = await request('POST', '/runs', payload)
assert.equal(created.status, 202)
assert.equal(created.body.runId, 'run-1')
assert.ok(Date.now() - startedAt < 500)

assert.equal((await request('GET', '/runs/run-1')).body.status, 'running')
assert.equal((await request('POST', '/runs/run-1/pause')).body.status, 'pause_requested')
assert.equal((await request('POST', '/runs/run-1/resume')).body.status, 'running')
assert.equal((await request('POST', '/runs/run-1/stop')).body.status, 'stop_requested')
```

Also assert `GET /capabilities` and 404 for unknown run IDs.

- [ ] **Step 2: Run and verify RED**

Run: `cd server/web-runner && node --test server.integration.test.mjs`

Expected: FAIL because `POST /runs` still blocks and control routes do not exist.

- [ ] **Step 3: Integrate the job manager and execution worker**

Refactor server creation into an exported `createWebRunnerServer(dependencies)` while keeping `await main()` only for direct execution. `POST /runs` must schedule `executeRunJob(job)` without awaiting it and return 202.

Before every case and step call `await job.beforeNextStep()`. Update current progress before the Playwright action. After each step capture a viewport screenshot:

```js
const captureStepScreenshot = async (page, runId) => {
  const fileName = `${sanitizeFileName(runId)}-live-${Date.now()}.png`
  await page.screenshot({ path: path.join(artifactDir, fileName), fullPage: false })
  return `${publicBaseUrl}/artifacts/${fileName}`
}
```

Launch the exact browser resolved from `payload.browserOverride || item.browser || runtime.browser || 'chromium'`. The frontend must omit `browserOverride` when `跟随用例` is selected; the Runner rejects `follow-case` as an unsupported browser instead of guessing. Close all Playwright handles in `finally`, including stopped jobs.

- [ ] **Step 4: Verify HTTP tests and Runner suite**

Run: `cd server/web-runner && npm test`

Expected: asynchronous API, control, launcher, and runner-core tests PASS.

- [ ] **Step 5: Commit the asynchronous Runner API**

```bash
git add server/web-runner/server.mjs server/web-runner/server.integration.test.mjs server/web-runner/package.json
git commit -m "feat: expose controllable web run api"
```

### Task 4: Real Login and Screenshot Integration Coverage

**Files:**
- Create: `server/web-runner/fixtures/login-app.mjs`
- Create: `server/web-runner/login-flow.integration.test.mjs`

- [ ] **Step 1: Create the deterministic fixture and failing integration test**

The fixture serves `/login` with username/password inputs and redirects valid `synthetic_user/synthetic_secret` credentials to `/users`. `/users` renders `用户管理` after a short configurable delay.

The test creates a Chromium job, waits for `setup-login-submit`, requests pause, verifies the next step does not begin, resumes, and then verifies:

```js
assert.equal(final.status, 'completed')
assert.equal(final.summary.passed, 1)
assert.match(final.currentPageUrl, /users/)
assert.ok(final.latestScreenshotUrl)
assert.ok(fs.statSync(screenshotFile).size > 1000)
```

A second run pauses and stops, then asserts `status === 'stopped'` and no browser handle remains active.

- [ ] **Step 2: Run and verify RED**

Run: `cd server/web-runner && node --test login-flow.integration.test.mjs`

Expected: FAIL until the asynchronous worker produces live screenshots and honors controls.

- [ ] **Step 3: Make the smallest Runner corrections required by the integration test**

Only adjust worker control checks, fixture URL resolution, or screenshot publication. Do not add video streaming or database persistence.

- [ ] **Step 4: Verify GREEN**

Run: `cd server/web-runner && npm test`

Expected: all unit and real Chromium integration tests PASS.

- [ ] **Step 5: Commit integration coverage**

```bash
git add server/web-runner/fixtures/login-app.mjs server/web-runner/login-flow.integration.test.mjs server/web-runner/server.mjs
git commit -m "test: cover controlled web login execution"
```

### Task 5: Mirror Async Runs Through Spring

**Files:**
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/PlaywrightRunClient.java`
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationRunController.java`
- Modify: `server/platform/src/test/java/com/haitlify/aitest/platform/webautomation/PlaywrightRunClientTest.java`
- Modify: `server/platform/src/test/java/com/haitlify/aitest/platform/webautomation/WebAutomationRunControllerTest.java`

- [ ] **Step 1: Add failing client and controller tests**

Assert these platform calls and Runner paths:

```java
mockMvc.perform(post("/api/web-automation/runs").content(runJson))
    .andExpect(status().isAccepted())
    .andExpect(jsonPath("$.runId").value("run-1"));
mockMvc.perform(get("/api/web-automation/runs/run-1"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.status").value("running"));
mockMvc.perform(post("/api/web-automation/runs/run-1/pause"))
    .andExpect(status().isOk());
mockMvc.perform(post("/api/web-automation/runs/run-1/resume"))
    .andExpect(status().isOk());
mockMvc.perform(post("/api/web-automation/runs/run-1/stop"))
    .andExpect(status().isOk());
mockMvc.perform(get("/api/web-automation/capabilities"))
    .andExpect(status().isOk());
```

Verify the fake Runner receives exact GET/POST paths and that create returns before a delayed browser worker completes.

- [ ] **Step 2: Run and verify RED**

Run: `cd server/platform && ./mvnw -Dtest=WebAutomationRunControllerTest,PlaywrightRunClientTest test`

Expected: FAIL because only synchronous `run` exists.

- [ ] **Step 3: Implement short Runner commands and typed records**

Add `createRun`, `getRun`, `controlRun`, and `capabilities` methods. Use a shared request helper with JSON serialization and the configured transport timeout. Preserve Runner 404/409/422/503 status details in `WebAutomationRunnerUnavailableException`.

Add Spring mappings for create, query, pause, resume, stop, and capabilities. Return HTTP 202 for create and the Runner snapshot for control calls. Records must include run lifecycle, progress, current case/step, latest screenshot, allowed controls, results, and browser capabilities.

- [ ] **Step 4: Verify GREEN and focused backend suite**

Run: `cd server/platform && ./mvnw -Dtest=WebAutomationRunControllerTest,PlaywrightRunClientTest test`

Expected: all focused backend tests PASS.

- [ ] **Step 5: Commit the platform proxy**

```bash
git add server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/PlaywrightRunClient.java \
  server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationRunController.java \
  server/platform/src/test/java/com/haitlify/aitest/platform/webautomation/PlaywrightRunClientTest.java \
  server/platform/src/test/java/com/haitlify/aitest/platform/webautomation/WebAutomationRunControllerTest.java
git commit -m "feat: proxy controllable web runs"
```

### Task 6: Frontend Async Repository and Run View Model

**Files:**
- Modify: `apps/web/src/domain/webAutomationRunRepository.ts`
- Modify: `apps/web/src/domain/webAutomationRunRepository.test.ts`
- Create: `apps/web/src/domain/webAutomationRunView.ts`
- Create: `apps/web/src/domain/webAutomationRunView.test.ts`

- [ ] **Step 1: Write failing repository tests**

Verify exact methods and URLs:

```ts
await createWebAutomationRun(cases, options, repositoryOptions)
await getWebAutomationRun('run-1', repositoryOptions)
await pauseWebAutomationRun('run-1', repositoryOptions)
await resumeWebAutomationRun('run-1', repositoryOptions)
await stopWebAutomationRun('run-1', repositoryOptions)
await getWebAutomationCapabilities(repositoryOptions)
```

Assert create accepts HTTP 202, query uses `GET /api/web-automation/runs/run-1`, controls use POST, and errors preserve backend messages and recommendations.

- [ ] **Step 2: Write failing view-model tests**

Cover lifecycle labels and controls:

```ts
expect(buildWebRunView(running).controls).toEqual({ canPause: true, canResume: false, canStop: true })
expect(buildWebRunView(paused).controls).toEqual({ canPause: false, canResume: true, canStop: true })
expect(buildWebRunView(completed).progressPercent).toBe(100)
expect(buildBrowserOptions(capabilities).find(item => item.value === 'chrome')?.disabled).toBe(true)
```

- [ ] **Step 3: Run and verify RED**

Run: `cd apps/web && npm test -- --run src/domain/webAutomationRunRepository.test.ts src/domain/webAutomationRunView.test.ts`

Expected: FAIL because async methods and view module do not exist.

- [ ] **Step 4: Implement repository and pure view model**

Export typed lifecycle, snapshot, progress, current item, capability, and control response interfaces. Implement `pollWebAutomationRun` so it never overlaps requests and supports an `AbortSignal`; callers own retry timing.

The view model maps setup/test phases, statuses, progress, elapsed time, diagnostics, and browser availability without Vue dependencies.

- [ ] **Step 5: Verify GREEN**

Run: `cd apps/web && npm test -- --run src/domain/webAutomationRunRepository.test.ts src/domain/webAutomationRunView.test.ts`

Expected: repository and view tests PASS.

- [ ] **Step 6: Commit the frontend domain slice**

```bash
git add apps/web/src/domain/webAutomationRunRepository.ts apps/web/src/domain/webAutomationRunRepository.test.ts \
  apps/web/src/domain/webAutomationRunView.ts apps/web/src/domain/webAutomationRunView.test.ts
git commit -m "feat: model live web run state"
```

### Task 7: Persist and Override Supported Browsers

**Files:**
- Modify: `apps/web/src/domain/webAutomationCase.ts`
- Modify: `apps/web/src/domain/executionEnvironmentRepository.ts`
- Modify: `apps/web/src/domain/executionEnvironmentRepository.test.ts`
- Modify: `apps/web/src/domain/webAutomation.test.ts`
- Modify: `apps/web/src/domain/webAutomationEditor.ts`
- Modify: `apps/web/src/domain/webCaseImport.ts`

- [ ] **Step 1: Add failing browser normalization tests**

Assert Chrome and Edge survive environment save/load and case serialization:

```ts
expect(normalizeSavedWebEnvironment({ web: { browser: 'chrome' } }).web?.browser).toBe('chrome')
expect(normalizeSavedWebEnvironment({ web: { browser: 'msedge' } }).web?.browser).toBe('msedge')
```

Keep unknown historical values falling back to Chromium.

- [ ] **Step 2: Run and verify RED**

Run: `cd apps/web && npm test -- --run src/domain/executionEnvironmentRepository.test.ts src/domain/webAutomation.test.ts`

Expected: FAIL because types and normalizers only allow Chromium, Firefox, and WebKit.

- [ ] **Step 3: Extend browser IDs consistently**

Use one union everywhere:

```ts
export type WebAutomationBrowser = 'chromium' | 'chrome' | 'msedge' | 'firefox' | 'webkit'
```

Update all normalizers to preserve these five values. Add `browserOverride?: 'follow-case' | WebAutomationBrowser` to frontend run options. Serialize it at the top run level only when it is not `follow-case`; otherwise omit the property so case and environment defaults apply.

- [ ] **Step 4: Verify GREEN and affected domain tests**

Run: `cd apps/web && npm test -- --run src/domain/executionEnvironmentRepository.test.ts src/domain/webAutomation.test.ts src/domain/webAutomationEditor.test.ts src/domain/webCaseImport.test.ts`

Expected: all affected browser tests PASS.

- [ ] **Step 5: Commit browser persistence**

```bash
git add apps/web/src/domain/webAutomationCase.ts apps/web/src/domain/executionEnvironmentRepository.ts \
  apps/web/src/domain/executionEnvironmentRepository.test.ts apps/web/src/domain/webAutomation.test.ts \
  apps/web/src/domain/webAutomationEditor.ts apps/web/src/domain/webCaseImport.ts
git commit -m "feat: persist web browser choices"
```

### Task 8: Wide Visual Execution Drawer and Controls

**Files:**
- Modify: `apps/web/src/App.vue`
- Modify: `apps/web/src/styles.css`
- Create: `apps/web/src/webRunDrawerStructure.test.ts`

- [ ] **Step 1: Add a failing structure test**

Follow the repository's existing source-structure test pattern and assert the Web execution surface contains:

```ts
expect(source).toContain('可视化执行')
expect(source).toContain('web-run-visual-drawer')
expect(source).toContain('pauseActiveWebRun')
expect(source).toContain('resumeActiveWebRun')
expect(source).toContain('stopActiveWebRun')
expect(source).toContain('latestScreenshotUrl')
expect(source).toContain('跟随用例')
```

- [ ] **Step 2: Run and verify RED**

Run: `cd apps/web && npm test -- --run src/webRunDrawerStructure.test.ts`

Expected: FAIL because the drawer and controls are absent.

- [ ] **Step 3: Wire run creation and polling in App.vue**

Replace the synchronous Web branch in `executeAllAutomationCases` with create + poll. Keep API execution unchanged. Store:

```ts
const webRunDrawerVisible = ref(false)
const activeWebRunId = ref('')
const activeWebRunSnapshot = ref<WebAutomationRunSnapshot>()
const webRunBrowserOverride = ref<WebRunBrowserOverride>('follow-case')
const webRunConnectionState = ref<'idle' | 'polling' | 'retrying' | 'interrupted'>('idle')
```

Abort polling on component unmount. Closing the drawer only changes visibility. Convert terminal snapshots into the existing `webAutomationRunReport` so case rows, saved reports, logs, and defect transfer continue working.

- [ ] **Step 4: Build the accepted drawer layout**

Add a wide right-side Element Plus drawer with:

- header status, elapsed time, browser, close;
- browser select with unavailable options disabled and tooltips;
- case/step progress bars;
- latest screenshot preview with stable aspect ratio and empty/loading/error states;
- setup/test timeline with current, passed, failed, pending, and stopped colors;
- current URL and diagnostics;
- pause, resume, stop, and rerun buttons with icon + text.

Add a compact active-run indicator near the Web execution toolbar. Extend Web environment and case editor browser selects with Chrome and Edge.

- [ ] **Step 5: Add stable responsive styles**

Use a 72vw drawer capped only by the viewport, a two-column `minmax(0, 3fr) minmax(280px, 2fr)` body, fixed preview aspect ratio, scroll-contained timeline, and a single-column layout below 900px. Do not let screenshots or long URLs expand the drawer width.

- [ ] **Step 6: Verify structure, domain tests, and build**

Run:

```bash
cd apps/web
npm test -- --run src/webRunDrawerStructure.test.ts src/domain/webAutomationRunRepository.test.ts src/domain/webAutomationRunView.test.ts src/domain/executionEnvironmentRepository.test.ts
npm run build
```

Expected: tests PASS and production build succeeds.

- [ ] **Step 7: Commit the drawer**

```bash
git add apps/web/src/App.vue apps/web/src/styles.css apps/web/src/webRunDrawerStructure.test.ts
git commit -m "feat: add visual web run controls"
```

### Task 9: Documentation, Full Verification, and Rendered QA

**Files:**
- Modify: `server/web-runner/README.md`
- Modify: `server/platform/README.md`

- [ ] **Step 1: Document startup, browsers, and async routes**

Document these commands and behaviors:

```bash
cd server/web-runner
npx playwright install chromium firefox webkit
npm start
curl http://127.0.0.1:9323/capabilities
```

Explain Chrome/Edge system-channel requirements, the run endpoints, one-hour in-memory retention, and that `APP_WEB_AUTOMATION_RUNNER_TIMEOUT_MS` is now a command transport timeout rather than a total run limit.

- [ ] **Step 2: Run all focused automated suites**

Run:

```bash
cd server/web-runner && npm test
cd ../platform && ./mvnw -Dtest=WebAutomationRunControllerTest,PlaywrightRunClientTest,AiDraftTestPointReviewTest test
cd ../../apps/web && npm test -- --run \
  src/domain/executionEnvironmentRepository.test.ts \
  src/domain/webAutomationRunRepository.test.ts \
  src/domain/webAutomationRunView.test.ts \
  src/domain/webAutomation.test.ts \
  src/domain/webAutomationEditor.test.ts \
  src/domain/webCaseImport.test.ts \
  src/webRunDrawerStructure.test.ts
npm run build
```

Expected: all focused tests PASS and the frontend build succeeds.

- [ ] **Step 3: Start all services and verify health**

Start Runner, platform, and frontend in persistent sessions. Verify:

```bash
curl http://127.0.0.1:9323/health
curl http://127.0.0.1:9323/capabilities
curl http://127.0.0.1:8080/actuator/health
curl -I http://127.0.0.1:5173/
```

Expected: all services respond successfully and capabilities show exact browser availability.

- [ ] **Step 4: Validate the rendered flow in the in-app Browser**

Test this exact flow:

```text
http://127.0.0.1:5173 -> Web 自动化执行 -> 选择浏览器 -> 可视化执行
-> 抽屉显示实时步骤 -> 暂停 -> 已暂停 -> 继续 -> 运行中
-> 停止或完成 -> 保留日志和截图 -> 关闭并重新打开执行详情
```

Check page identity, nonblank content, no framework overlay, console errors, desktop and mobile layout, screenshot rendering, and table action width. Capture screenshots for the final QA report.

- [ ] **Step 5: Commit documentation**

```bash
git add server/web-runner/README.md server/platform/README.md
git commit -m "docs: explain visual web run controls"
```

## Completion Criteria

- Run creation returns in under one second while browser execution continues asynchronously.
- Browser override launches the exact available engine/channel and unavailable choices are disabled.
- Polling snapshots never contain credentials, cookies, tokens, variables, or typed synthetic_secret values.
- Pause, resume, and stop follow the confirmed step-boundary semantics.
- The wide drawer shows nonblank step screenshots, current URL, timeline, diagnostics, and stable controls.
- Existing Web result rows, logs, report saving, and defect transfer continue to work from terminal snapshots.
- Runner, backend, frontend tests, build, and rendered Browser QA all pass.

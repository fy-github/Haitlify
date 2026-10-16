> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

> **Status: ✅ 已落地**(2026-08-20 归档标记。功能已上生产;下方 `- [ ]` 为执行期 TDD 脚手架,实施时未逐项回勾,不代表未完成。)

# Web Automation Login and Executable AI Cases Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Web automation open the configured application, complete a reusable real login flow, execute AI-generated browser steps, and expose enough evidence to diagnose every failed case.

**Architecture:** Keep Web authentication separate from API authentication. The frontend stores a Web-specific form-login profile in the existing Web environment library and sends it as runner runtime data; the Java backend remains a typed proxy and must preserve every step field; the Playwright runner expands login setup and case steps into one logged execution stream. AI case generation uses a strict browser-step DSL, and unsupported natural-language instructions remain visibly non-executable instead of being interpreted as selectors.

**Tech Stack:** Vue 3, TypeScript, Element Plus, Vitest, Spring Boot, Java records, JUnit/MockMvc, Node.js, Playwright, Node test runner.

---

## File Map

- Modify `apps/web/src/domain/executionEnvironmentRepository.ts`: define, normalize, validate, persist, and build Web form-login runtime configuration.
- Modify `apps/web/src/domain/executionEnvironmentRepository.test.ts`: cover Web login defaults, migration, validation, persistence, and runtime output.
- Modify `apps/web/src/domain/webAutomationCase.ts`: add per-case login policy and preserve complete executable step fields.
- Modify `apps/web/src/domain/webAutomation.ts`: include login policy in runner cases and label setup/test logs.
- Modify `apps/web/src/domain/webAutomationRunRepository.ts`: preserve the complete runtime and step payload sent to the backend.
- Modify `apps/web/src/domain/webAutomationRunRepository.test.ts`: assert credentials, selectors, values, timeouts, and screenshot policies survive request serialization.
- Modify `apps/web/src/domain/webCaseImport.ts`: parse the strict Web DSL and mark unsupported lines as manual.
- Modify `apps/web/src/domain/webRequirementFlow.test.ts`: cover executable AI output, environment-login inheritance, and manual fallback.
- Modify `apps/web/src/App.vue`: replace API-style Web authentication controls with form-login controls, add login verification, and show setup/test diagnostics.
- Modify `apps/web/src/styles.css`: style the Web login section and stable log/screenshot layout.
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationRunController.java`: preserve all runtime/case/step fields through the Java proxy.
- Modify `server/platform/src/test/java/com/haitlify/aitest/platform/webautomation/WebAutomationRunControllerTest.java`: assert full Web payload forwarding and diagnostic response mapping.
- Modify `server/platform/src/test/java/com/haitlify/aitest/platform/webautomation/PlaywrightRunClientTest.java`: assert the proxy forwards login and step values unchanged.
- Modify `server/web-runner/runner-core.mjs`: resolve locator strategies and variables, build login setup, and preserve step metadata.
- Modify `server/web-runner/runner-core.test.mjs`: cover login expansion, variable substitution, locator resolution, and duplicate navigation prevention.
- Modify `server/web-runner/server.mjs`: execute setup/test phases and collect screenshots, console errors, page errors, and failed requests.
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java`: provide the model with an exact Web DSL contract and reject malformed Web automation output.
- Modify `server/platform/src/test/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointReviewTest.java`: verify Web prompt instructions and malformed-output handling.
- Create `server/web-runner/fixtures/login-app.mjs`: deterministic local login target for runner integration verification.
- Create `server/web-runner/login-flow.integration.test.mjs`: real Chromium login, navigation, assertion, logs, and screenshot test.

### Task 1: Preserve Complete Web Step Data Through the Java Proxy

The current frontend step type contains `value`, `timeoutMs`, and `screenshot`, but `WebAutomationStepRequest` only declares `id`, `name`, `action`, `targetType`, and `target`. This causes input values such as usernames and passwords to disappear before reaching Playwright.

- [ ] **Step 1: Add a failing backend proxy test**

Add a request to `WebAutomationRunControllerTest` containing:

```json
{
  "runtime": {
    "variables": { "webUsername": "synthetic_user", "webPassword": "synthetic_secret" }
  },
  "cases": [{
    "id": "WEB-LOGIN",
    "authenticationMode": "environment",
    "steps": [{
      "id": "step-1",
      "name": "输入账号",
      "action": "input",
      "targetType": "locator",
      "target": "css=#username",
      "value": "{{webUsername}}",
      "timeoutMs": 8000,
      "screenshot": "always"
    }]
  }]
}
```

Capture the request received by the mocked runner client and assert all four fields `authenticationMode`, `value`, `timeoutMs`, and `screenshot` remain present.

- [ ] **Step 2: Run the backend test and verify it fails**

Run:

```bash
cd server/platform
./mvnw -Dtest=WebAutomationRunControllerTest,PlaywrightRunClientTest test
```

Expected: FAIL because the current Java records discard the new fields.

- [ ] **Step 3: Extend the typed proxy records**

Change the records in `WebAutomationRunController.java` to preserve the contract:

```java
record WebAutomationRunCaseRequest(
    String id,
    String sourceCaseId,
    String project,
    String module,
    String pageName,
    String name,
    String startUrl,
    String browser,
    String viewport,
    int runOrder,
    String mockOutcome,
    String authenticationMode,
    List<WebAutomationStepRequest> steps
) {}

record WebAutomationStepRequest(
    String id,
    String name,
    String action,
    String targetType,
    String target,
    String value,
    Integer timeoutMs,
    String screenshot
) {}
```

- [ ] **Step 4: Run backend tests**

Run the command from Step 2.

Expected: PASS; captured JSON sent to the runner still contains every login and step field.

- [ ] **Step 5: Commit the proxy contract fix**

```bash
git add server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationRunController.java \
  server/platform/src/test/java/com/haitlify/aitest/platform/webautomation/WebAutomationRunControllerTest.java \
  server/platform/src/test/java/com/haitlify/aitest/platform/webautomation/PlaywrightRunClientTest.java
git commit -m "fix: preserve web automation step values"
```

### Task 2: Add Web-Specific Login Configuration and Persistence

Use a browser form-login profile instead of API Bearer/Header authentication. Keep existing Cookie/Header entries as an optional advanced login-state import path.

- [ ] **Step 1: Add failing environment-domain tests**

Define the expected configuration in `executionEnvironmentRepository.test.ts`:

```ts
web: {
  ...defaultWebExecutionRuntimeConfig(),
  login: {
    enabled: true,
    loginUrl: '/#/login',
    usernameLocator: 'css=input[name="username"]',
    passwordLocator: 'css=input[type="password"]',
    submitLocator: 'role=button[name="登录"]',
    username: 'synthetic_user',
    password: 'synthetic_secret',
    successType: 'url',
    successTarget: '/user-center'
  }
}
```

Assert that validation rejects missing login URL, credentials, selectors, or success target when login is enabled; assert old saved environments without `login` normalize to `enabled: false`; assert a save/load round trip keeps all fields.

- [ ] **Step 2: Run the frontend domain test and verify it fails**

```bash
cd apps/web
npm test -- --run src/domain/executionEnvironmentRepository.test.ts
```

Expected: FAIL because `WebLoginConfig` does not exist.

- [ ] **Step 3: Add the Web login domain contract**

Add these types to `executionEnvironmentRepository.ts`:

```ts
export type WebLoginSuccessType = 'url' | 'visible'

export interface WebLoginConfig {
  enabled: boolean
  loginUrl: string
  usernameLocator: string
  passwordLocator: string
  submitLocator: string
  username: string
  password: string
  successType: WebLoginSuccessType
  successTarget: string
}
```

Nest `login: WebLoginConfig` under `WebExecutionRuntimeConfig`, include it in `BuiltWebExecutionRuntime`, and normalize it whenever old local-storage data is loaded. Validation must mention the exact missing field in Chinese.

- [ ] **Step 4: Replace Web API-auth controls in the environment drawer**

In `App.vue`, remove `Bearer Token` and `自定义 Header` from the Web-only branch. Add an `自动登录` switch and conditional fields for login URL, username/password, username/password/submit locators, and success check. Leave Header/Cookie entries below under an `高级登录态` section.

Add a `验证登录` button that builds one synthetic run using the active edited environment and the existing `/api/web-automation/runs` endpoint. It succeeds only when the setup phase reaches the configured success check; show the returned failed setup step when it does not.

- [ ] **Step 5: Verify persistence and rendering**

Run:

```bash
cd apps/web
npm test -- --run src/domain/executionEnvironmentRepository.test.ts src/domain/webAutomationRunRepository.test.ts
npm run build
```

Expected: tests PASS and production build succeeds.

- [ ] **Step 6: Commit Web login configuration**

```bash
git add apps/web/src/App.vue apps/web/src/styles.css \
  apps/web/src/domain/executionEnvironmentRepository.ts \
  apps/web/src/domain/executionEnvironmentRepository.test.ts \
  apps/web/src/domain/webAutomationRunRepository.test.ts
git commit -m "feat: add web form login environments"
```

### Task 3: Execute Environment Login Before Web Test Steps

- [ ] **Step 1: Add failing runner-core tests**

Test that an enabled login profile expands to this ordered stream:

```js
[
  ['setup', 'goto', '/#/login'],
  ['setup', 'input', 'css=input[name="username"]', 'synthetic_user'],
  ['setup', 'input', 'css=input[type="password"]', 'synthetic_secret'],
  ['setup', 'click', 'role=button[name="登录"]'],
  ['setup', 'waitForUrl', '/user-center'],
  ['test', 'goto', '/user/list'],
  ['test', 'assertVisible', 'text=用户管理']
]
```

Also test `authenticationMode: 'skip'`, variable substitution for `{{webUsername}}`, a missing variable error, and no duplicate `goto` when the first case step already opens its start URL.

- [ ] **Step 2: Run runner tests and verify they fail**

```bash
cd server/web-runner
npm test
```

Expected: FAIL because login setup, phases, locator strategies, and substitution are not implemented.

- [ ] **Step 3: Implement variable and locator resolution**

In `runner-core.mjs`, add pure helpers with these semantics:

```js
resolveTemplate('{{webUsername}}', { webUsername: 'synthetic_user' }) // 'synthetic_user'
resolveLocator(page, 'css=#username')                       // page.locator('#username')
resolveLocator(page, 'text=用户管理')                        // page.getByText('用户管理', { exact: true })
resolveLocator(page, 'role=button[name="登录"]')            // page.getByRole('button', { name: '登录' })
resolveLocator(page, 'testid=login-submit')                 // page.getByTestId('login-submit')
```

All element actions must use `resolveLocator`; all targets and values must pass through strict variable substitution. An unresolved `{{name}}` must fail with `运行变量未配置: name`.

- [ ] **Step 4: Build login setup and phase-aware steps**

Add `buildLoginSetupSteps(runtime, item)` and return steps with `phase: 'setup' | 'test'`. Respect `authenticationMode: 'skip'`; default existing cases to `environment`. Apply each step's `timeoutMs` to its Playwright operation.

- [ ] **Step 5: Log setup and test execution separately**

In `server.mjs`, include `phase` in every step log. On setup failure, stop the case and set a clear case error such as `环境登录失败：登录成功 URL 未匹配`; do not continue into test steps.

- [ ] **Step 6: Run runner and frontend contract tests**

```bash
cd server/web-runner
npm test
cd ../../apps/web
npm test -- --run src/domain/webAutomation.test.ts src/domain/webAutomationRunRepository.test.ts
```

Expected: PASS; login steps precede test steps and input values reach Playwright.

- [ ] **Step 7: Commit login execution**

```bash
git add server/web-runner/runner-core.mjs server/web-runner/runner-core.test.mjs server/web-runner/server.mjs \
  apps/web/src/domain/webAutomationCase.ts apps/web/src/domain/webAutomation.ts \
  apps/web/src/domain/webAutomationRunRepository.ts apps/web/src/domain/webAutomationRunRepository.test.ts
git commit -m "feat: execute reusable web login setup"
```

### Task 4: Make AI-Generated Web Cases Executable by Contract

- [ ] **Step 1: Add failing AI generation tests**

In `AiDraftTestPointReviewTest`, capture the prompt passed to `structuredGenerate` and assert it contains the exact allowed DSL:

```text
goto <url>
click <css=...|text=...|role=...|testid=...>
input <locator> = <literal|{{variable}}>
select <locator> = <value>
hover <locator>
waitForVisible <locator>
waitForUrl <url-pattern>
assertVisible <locator>
assertHidden <locator>
assertText <locator> = <expected-text>
assertUrl <url-pattern>
manual <human-readable instruction>
```

Add a response containing `steps: "登录系统并进入用户中心"` and assert the endpoint returns HTTP 400 with `AI 生成的 Web 用例包含不可执行步骤`.

- [ ] **Step 2: Run the backend AI test and verify it fails**

```bash
cd server/platform
./mvnw -Dtest=AiDraftTestPointReviewTest test
```

Expected: FAIL because the prompt is generic and output is not validated.

- [ ] **Step 3: Add the strict prompt and server-side validation**

For `caseKind == web_automation`, require one DSL command per line. Tell the model:

- Login is inherited from the selected Web environment; do not invent account credentials.
- Use `{{variableName}}` for test data and environment secrets.
- Prefer `testid`, `role`, visible text, then CSS in that order.
- Use `manual` only when no reliable locator/action can be inferred.
- Put executable assertions in `expectedResult`, using the same DSL.

Validate actions and locator prefixes before returning the model output. Do not silently convert malformed commands into selectors.

- [ ] **Step 4: Parse generated assertions into metadata steps**

Update `buildGeneratedCasesFromAiGeneratedCases` so executable lines in both `steps` and `expectedResult` become ordered `metadata.steps`. Keep `row.assertions` for table display, but ensure Playwright receives the assertions.

Expected sequence for the existing login example:

```ts
['goto', 'input', 'click', 'assertVisible']
```

- [ ] **Step 5: Expose per-case login policy in the Web case editor**

Add `authenticationMode: 'environment' | 'skip'` to metadata and a case editor select labelled `登录方式` with values `使用运行环境登录` and `用例自行处理登录`. AI-generated non-login cases default to `environment`; explicit login-test cases can select `skip`.

- [ ] **Step 6: Run AI and frontend parsing tests**

```bash
cd server/platform
./mvnw -Dtest=AiDraftTestPointReviewTest test
cd ../../apps/web
npm test -- --run src/domain/webRequirementFlow.test.ts src/domain/webAutomationEditor.test.ts src/domain/webCaseImport.test.ts
```

Expected: PASS; generated assertions are runnable and natural-language lines never become CSS selectors.

- [ ] **Step 7: Commit executable AI cases**

```bash
git add server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java \
  server/platform/src/test/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointReviewTest.java \
  apps/web/src/domain/webCaseImport.ts apps/web/src/domain/webRequirementFlow.test.ts \
  apps/web/src/domain/webAutomationCase.ts apps/web/src/domain/webAutomationEditor.ts \
  apps/web/src/domain/webAutomationEditor.test.ts apps/web/src/App.vue
git commit -m "feat: generate executable web automation cases"
```

### Task 5: Make Failure Logs and Screenshots Diagnostic

- [ ] **Step 1: Add failing runner diagnostics tests**

Test a page that emits one console error, one uncaught page error, and one failed request before an assertion failure. Assert the result contains:

```json
{
  "failedStepId": "step-2",
  "finalPageUrl": "http://127.0.0.1:PORT/user/list",
  "screenshotPath": "http://127.0.0.1:9323/artifacts/...png",
  "consoleLogs": [{ "type": "error", "text": "..." }],
  "pageErrors": [{ "message": "..." }],
  "failedRequests": [{ "method": "GET", "url": "...", "failure": "..." }]
}
```

- [ ] **Step 2: Implement event collection and reliable screenshots**

Attach `console`, `pageerror`, and `requestfailed` listeners immediately after creating the page. Before a failure screenshot, wait for one animation frame and capture both viewport and full-page images when possible; if screenshot capture fails, record `screenshotError` instead of returning no evidence.

Honor the configured strategy:

- `none`: no routine screenshots, but still capture the final failure screenshot.
- `on_failure`: capture the failed state.
- `always`: capture each completed step and include `screenshotPath` in that step log.

- [ ] **Step 3: Extend backend and frontend report types**

Add the diagnostic fields to the Java response records and TypeScript result types. In `App.vue`, group logs by `登录前置` and `用例步骤`; show final URL, error, screenshot preview, console errors, page errors, and failed requests in the existing log dialog without increasing the table height.

- [ ] **Step 4: Run diagnostics tests and build**

```bash
cd server/web-runner
npm test
cd ../platform
./mvnw -Dtest=WebAutomationRunControllerTest,PlaywrightRunClientTest test
cd ../../apps/web
npm test -- --run src/domain/webAutomation.test.ts src/domain/webAutomationRunRepository.test.ts
npm run build
```

Expected: all tests PASS; a failed case always explains the failing phase, step, final URL, and screenshot-capture status.

- [ ] **Step 5: Commit diagnostics**

```bash
git add server/web-runner/server.mjs server/web-runner/runner-core.test.mjs \
  server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationRunController.java \
  server/platform/src/test/java/com/haitlify/aitest/platform/webautomation/WebAutomationRunControllerTest.java \
  apps/web/src/domain/webAutomation.ts apps/web/src/domain/webAutomationRunRepository.ts \
  apps/web/src/App.vue apps/web/src/styles.css
git commit -m "feat: add web execution diagnostics"
```

### Task 6: Verify a Real Login Flow End to End

- [ ] **Step 1: Create a deterministic local login fixture**

Create `server/web-runner/fixtures/login-app.mjs` with:

- `GET /login`: username/password inputs and a login button.
- `POST /session`: accepts `synthetic_user` / `synthetic_secret`, sets a session cookie, redirects to `/user/list`.
- `GET /user/list`: redirects to `/login` without the cookie; otherwise renders `用户管理`.
- `GET /broken`: renders a page whose assertion intentionally fails.

- [ ] **Step 2: Add a real Chromium integration test**

Start the fixture on an ephemeral port, execute `runOneCase` with environment login enabled, and assert:

- the browser opens `/login`;
- username and password values are filled;
- submit navigation reaches `/user/list`;
- `assertVisible text=用户管理` passes;
- setup and test logs are both present;
- the broken case returns a nonblank PNG artifact.

- [ ] **Step 3: Run the full focused verification suite**

```bash
cd server/web-runner
npm test
cd ../platform
./mvnw -Dtest=AiDraftTestPointReviewTest,WebAutomationRunControllerTest,PlaywrightRunClientTest test
cd ../../apps/web
npm test -- --run src/domain/executionEnvironmentRepository.test.ts \
  src/domain/webRequirementFlow.test.ts \
  src/domain/webAutomationEditor.test.ts \
  src/domain/webCaseImport.test.ts \
  src/domain/webAutomation.test.ts \
  src/domain/webAutomationRunRepository.test.ts
npm run build
```

Expected: all tests PASS; only the existing Rollup chunk-size warning may remain.

- [ ] **Step 4: Start all three services for manual verification**

```bash
cd server/web-runner
node server.mjs
```

```bash
cd server/platform
./mvnw test-compile exec:java \
  -Dexec.mainClass=com.haitlify.aitest.platform.AiTestPlatformApplication \
  -Dexec.args='--spring.profiles.active=local' \
  -Dexec.classpathScope=test
```

```bash
cd apps/web
npm run dev -- --host 127.0.0.1
```

Verify `http://127.0.0.1:9323/health`, `http://127.0.0.1:8080/actuator/health`, and `http://127.0.0.1:5173/`.

- [ ] **Step 5: Verify against the configured ICC development environment**

In Web 运行环境配置, enter the actual login URL, selectors, credentials, and success URL/element, click `验证登录`, then run one generated user-center case. Acceptance criteria:

- logs show `打开登录页 -> 输入账号 -> 输入密码 -> 点击登录 -> 登录成功检查 -> 打开用例页面 -> 用例断言`;
- the final URL is not `#/login?from=...`;
- no natural-language sentence is passed to `page.locator()`;
- failed cases display a nonblank screenshot and a specific failed step.

- [ ] **Step 6: Commit integration coverage**

```bash
git add server/web-runner/fixtures/login-app.mjs server/web-runner/login-flow.integration.test.mjs
git commit -m "test: verify real web login automation"
```

## Completion Gate

The work is complete only when all conditions are true:

- Input step values survive Vue -> Spring -> Playwright unchanged.
- Web environments use browser login settings rather than API authentication semantics.
- Environment login can be verified independently before running cases.
- AI-generated Web cases use the documented DSL or are explicitly marked `manual`.
- Generated expected results are executed as assertions, not only displayed in the table.
- Login setup and test steps have separate, complete logs.
- Every failure reports phase, step, final URL, error, and screenshot status.
- The deterministic login integration test passes in real Chromium.
- One ICC development-environment case reaches the authenticated target page before executing its test assertion.

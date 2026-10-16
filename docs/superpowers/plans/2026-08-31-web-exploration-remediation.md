> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# Web Exploration Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the remaining correctness, security, observability, and completeness gaps in multi-round Web element exploration so ICC login works, evidence remains auditable, ambiguous controls never get guessed, and different business pages can be discovered without hardcoded routes.

**Architecture:** Keep browser actions inside the deterministic Web Runner state machine. Platform supplies explicit application and authentication origins, consumes structured exploration reports, and treats evidence persistence failure as a visible point-level failure. Locator diagnostics use safe structured references rather than lossy string truncation. Semantic expansion is bounded and optional: deterministic extraction is always the fallback, while one short AI-assisted alias/ranking call may improve unfamiliar business wording without granting additional browser permissions.

**Tech Stack:** Node.js ESM, Playwright 1.61, Java 21, Spring Boot 4, Jackson 3, JdbcTemplate, Flyway, Vue 3, TypeScript, Vitest, Docker Compose.

---

## Review Findings And Scope

The current implementation has passed focused tests and frontend build, but the review identified these remaining issues:

1. `server/web-runner/page-locator-discovery.mjs:42-44,208-229` only allows the Web page origin, login page origin, and requested target origin. ICC login submits to `https://api.example.invalid`, which is a different origin from `https://app.example.invalid`; the request is therefore blocked before authentication can complete.
2. `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcher.java:380-406` swallows locator knowledge persistence errors, so a run can appear successful while the locator library and audit trail are incomplete.
3. `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcher.java:304-308` strips everything after `=` from action targets. This makes event details lose the actual locator strategy/name and weakens diagnosis.
4. `server/web-runner/page-locator-discovery.mjs:378-385` falls back to `text=...` for duplicate id-less tabs. Current count checks often block this, but no stable positive disambiguation is produced.
5. The current intent builder and policy use fixed keyword/synonym lists. Unknown page aliases or business labels still require manual dictionary changes.
6. The current development environment has not completed a read-only, authenticated ICC business-page acceptance test with an actual target row and business element.

The remediation is deliberately scoped to these gaps. Existing generated-case validation, review UI, quality scoring, and case-library behavior remain unchanged except for displaying richer exploration failure details.

## File Map

- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationLocatorDiscoveryService.java`: add explicit allowed request origins and wire-compatible request fields.
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentModels.java`: carry safe origin configuration and exploration persistence failures where the Agent API needs them.
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcher.java`: stop swallowing evidence persistence failures, preserve safe locator diagnostics, and emit point-level failure events.
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/locator/LocatorKnowledgeService.java`: return explicit persistence results and preserve safe locator references.
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java`: pass authentication/request origins, expose persistence failures, and carry semantic expansion settings.
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebExplorationIntentBuilder.java`: extract richer page aliases, action/state terms, and business element phrases.
- Create `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebExplorationSemanticExpansionService.java`: optional one-shot AI alias/candidate expansion with deterministic fallback.
- Create `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebExplorationSemanticExpansionServiceTest.java`: bounded expansion, timeout, malformed response, and fallback tests.
- Modify `server/web-runner/page-locator-discovery.mjs`: origin allowlist, login API origin support, safe tab locator generation, and strict ambiguous-tab handling.
- Modify `server/web-runner/page-locator-discovery.test.mjs`: cross-origin login API, resource policy, duplicate-tab, and locator-diagnostics fixtures.
- Modify `server/web-runner/page-exploration-policy.mjs`: distinguish safe diagnostic references from executable locators and add phrase/context scoring helpers.
- Modify `server/web-runner/page-exploration-policy.test.mjs`: regression tests for unfamiliar phrases and safe references.
- Modify `apps/web/src/domain/webCaseAgentRepository.ts`: type persistence failure and diagnostic fields.
- Modify `apps/web/src/components/workflow/WebCaseAgentWorkspace.vue`: display point-level persistence and ambiguous-locator reasons without exposing credentials or raw request headers.
- Modify `apps/web/src/components/workflow/WebCaseAgentWorkspace.test.ts`: UI regression tests for the new failure summaries.
- No new database migration is expected for this remediation: locator diagnostics remain in the existing `metadata_json`, and event details remain in V18 `details_json`.
- Modify `apps/web/src/domain/executionEnvironmentRepository.ts`: add explicit `allowedRequestOrigins` to the Web runtime configuration and normalize it without secrets.
- Modify `apps/web/src/App.vue`: expose the explicit authentication/API origins in the Web environment editor and include them in Agent start requests.
- Modify focused backend, runner, and frontend tests listed in each task.
- Create `docs/superpowers/specs/2026-08-31-web-exploration-remediation-design.md` only if implementation decisions materially diverge from this plan; otherwise this plan is the source of truth.

## Design Invariants

These invariants must hold in every task:

- Browser exploration never clicks `确定`, `确认`, `提交`, `保存`, `删除`, `停用`, `启用`, `开通`, `支付`, `发送`, or `上传`.
- Explicit authentication origins are allowlisted only for the configured Web environment and only after origin parsing; arbitrary cross-origin requests remain blocked.
- `assertAllowedUrl` remains the final URL policy gate even for an explicitly allowed origin.
- A locator used for an actual browser action must be browser-observed in the current exploration session or a verified knowledge entry; event details may contain only a safe diagnostic reference.
- Duplicate controls without a unique stable scope return `ambiguous_locator`; they never fall back to `.first()` or a duplicate `text=` locator.
- A locator knowledge persistence failure is visible as a point-level error and cannot silently produce a successful evidence state.
- Legacy requests with no new origin, expansion, or diagnostic fields continue to work through compatibility constructors and null-safe defaults.
- Credentials, passwords, cookies, Authorization headers, raw DOM, raw screenshots, and query strings never enter event details, metadata JSON, or AI prompts.

## Task 1: Reproduce Every Remaining Review Finding

**Files:**
- Modify: `server/web-runner/page-locator-discovery.test.mjs`
- Modify: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcherTest.java`
- Modify: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/locator/LocatorKnowledgeServiceTest.java`

- [ ] **Step 1: Add a failing cross-origin login API fixture**

Create two local HTTP servers in `page-locator-discovery.test.mjs`: one serves the login page and one serves `/auth/login`. The login page must submit `fetch(apiOrigin + '/auth/login', { method: 'POST' })`, then set `location.hash = '#/project'` only when the API responds successfully. Call `discoverPageLocators` with `baseUrl` and `login.url` on the first origin, `allowedRequestOrigins: [apiOrigin]`, and a deterministic `assertAllowedUrl` callback that allows only the two fixture origins. Assert the current implementation fails with `network_blocked` or login timeout because it ignores the new origin field.

- [ ] **Step 2: Add a failing persistence error test**

In `WebCaseAgentDispatcherTest`, make `locatorKnowledge.recordAgentEvidence(...)` throw `DataAccessException`. Capture `events.append(...)` and assert the current implementation neither emits a persistence error nor marks the point blocked. This test must fail for the current behavior. Use independent mocks for status/event writes so the eventual fix can prove point blocking survives the failed evidence transaction.

- [ ] **Step 3: Add a failing diagnostic-reference test**

In `WebCaseAgentDispatcherTest`, pass `actionTarget = 'role=button[name="停用"]'` into the exploration event helper and assert the resulting details currently contain only `role`. The test should define the desired post-fix contract: `targetKind='role'`, `locatorFingerprint` is present, and no password/token/query/header value is present.

- [ ] **Step 4: Add a failing duplicate-tab test**

In `page-locator-discovery.test.mjs`, render two id-less tabs with the same visible label and no unique role/name, test id, or scoped CSS path. Assert the observable current output exposes an ambiguous text candidate or fails to provide a unique locator. The desired post-fix assertion is `status='blocked'` and `failureReason='ambiguous_locator'`, with no tab mutation counter increment; do not make the red test depend on an internal click attempt.

- [ ] **Step 5: Run the red tests**

Run:

```bash
cd server/web-runner
node --test --test-name-pattern='cross-origin login API|diagnostic reference|duplicate tab' page-locator-discovery.test.mjs

cd ../platform
./mvnw -q -Dtest=WebCaseAgentDispatcherTest,LocatorKnowledgeServiceTest test
```

Expected: each new test fails for the stated existing behavior.

- [ ] **Step 6: Commit the red tests**

```bash
git add server/web-runner/page-locator-discovery.test.mjs server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcherTest.java server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/locator/LocatorKnowledgeServiceTest.java
git commit -m "test(web): reproduce remaining exploration review gaps"
```

## Task 2: Make Authentication Origins Explicit And Safe

**Files:**
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationLocatorDiscoveryService.java`
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentModels.java`
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java`
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AgentWebCaseGenerationFacade.java`
- Modify: `apps/web/src/domain/executionEnvironmentRepository.ts`
- Modify: `apps/web/src/App.vue`
- Modify: `server/web-runner/page-locator-discovery.mjs`
- Modify: `server/web-runner/page-locator-discovery.test.mjs`

- [ ] **Step 1: Add a request-level origin field with compatibility defaults**

Extend `PageLocatorDiscoveryRequest` with `List<String> allowedRequestOrigins`, while retaining the old constructor. Extend `WebLocatorDiscoveryContext`, `AgentWebCaseGenerationFacade.BrowserDiscoveryOptions`, and the frontend `BuiltWebExecutionRuntime` with the same optional list. Do not infer arbitrary origins from a page URL. In `AiDraftTestPointService`, build the list from:

```java
List<String> allowedRequestOrigins = Stream.of(
    context.baseUrl(),
    context.login() == null ? "" : context.login().url(),
    context.allowedRequestOrigins(),
    context.cookieOrigins()
).flatMap(this::flattenOriginInputs).distinct().toList();

private Stream<String> flattenOriginInputs(Object value) {
    if (value instanceof Iterable<?> values) {
        return StreamSupport.stream(values.spliterator(), false).map(String::valueOf);
    }
    return value == null ? Stream.empty() : Stream.of(String.valueOf(value));
}
```

Only origin values parseable as `http` or `https` are retained. `allowedRequestOrigins` is a new optional Web environment field populated by the user with authentication API and required same-site resource origins. `cookieOrigins` are used only when the user explicitly configured them for the same Web environment. The UI must label this field as an explicit allowlist, not as a guessed domain setting.

In `apps/web/src/domain/executionEnvironmentRepository.ts`, add `allowedRequestOrigins?: string[]` to `WebExecutionRuntimeConfig` and `BuiltWebExecutionRuntime`; normalize only origin values with no username/password/query/fragment. In `apps/web/src/App.vue`, include the field in `startWebCaseAgentRun` and the environment editor, but never include its values in AI prompts or event summaries.

The editor must display this as `认证/资源 Origin 白名单（每行一个）`, validate each entry as an origin, reject userinfo/query/fragment, and preserve an empty list for legacy environments. Add a frontend runtime-construction assertion so the field cannot be dropped before `startWebCaseAgentRun`.

- [ ] **Step 2: Add wire aliases for Runner compatibility**

Serialize `allowedRequestOrigins` as both `allowedRequestOrigins` and the Runner-compatible `allowedOrigins`; preserve `targetIntent`/`intent` and `previousAttempts`/`history` compatibility already present. Add Jackson round-trip tests that assert the API request can be read by the Runner input shape. Add frontend normalization tests proving invalid origins and credentials-bearing origins are rejected before a run starts.

- [ ] **Step 3: Harden the Runner request policy**

In `page-locator-discovery.mjs`, normalize `allowedRequestOrigins` and `allowedOrigins` into an origin set. For every `http`/`https` request:

1. Reject malformed or credential-bearing URLs.
2. Reject origins outside the explicit set.
3. Run `assertAllowedUrl(requestUrl)` after the origin check.
4. Record only `{ origin, resourceType, failureType: 'network_blocked' }` for blocked requests; never record request headers, body, cookies, or query values.
5. Continue `data:`, `blob:`, and browser-internal resources only when Playwright requires them for rendering.

The first login page navigation and explicitly configured login API origin are allowed; no other subdomain is inferred from the hostname. The ICC configuration must therefore explicitly list `https://api.example.invalid`.

- [ ] **Step 4: Verify the cross-origin login fixture turns green**

Run:

```bash
cd server/web-runner
node --test --test-name-pattern='cross-origin login API' page-locator-discovery.test.mjs
```

Expected: login reaches `#/project`, discovery returns without `network_blocked` for the explicitly configured API origin, and unrelated cross-origin iframe/fetch requests remain blocked.

- [ ] **Step 5: Run the full Runner suite and commit**

```bash
cd server/web-runner
npm test
git add server/web-runner/page-locator-discovery.mjs server/web-runner/page-locator-discovery.test.mjs server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationLocatorDiscoveryService.java server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentModels.java server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java
git commit -m "fix(web): allow configured authentication origins"
```

## Task 3: Surface Locator Knowledge Persistence Failures

**Files:**
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/locator/LocatorKnowledgeService.java`
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcher.java`
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java`
- Modify: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcherTest.java`
- Modify: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/locator/LocatorKnowledgeServiceTest.java`

- [ ] **Step 1: Define a typed persistence failure**

Create package-private `WebLocatorKnowledgePersistenceException` with `pageUrl` and `cause`; `runId` and `pointId` are added by the Dispatcher because the knowledge service does not receive them. It must not contain credentials or raw request payloads. `LocatorKnowledgeService.recordAgentEvidence` may continue returning `void`, but its database exceptions must propagate through this typed exception.

- [ ] **Step 2: Remove the empty catch block**

Replace `catch (Exception ignored) { }` in `captureLocatorKnowledge` with:

```java
catch (WebLocatorKnowledgePersistenceException failure) {
    runService.markPointBlocked(runId, pointId);
    appendPersistenceFailureEvent(runId, pointId, failure);
    continue;
}
```

The point-blocking update and bounded error event must execute in a `REQUIRES_NEW` transaction (or equivalent independent transaction boundary), because the failed evidence transaction may already be marked rollback-only. `appendPersistenceFailureEvent` must use a bounded summary such as `页面元素素材保存失败，测试点已阻塞` and structured fields `{category:'locator_persistence', retryable:true, pageUrl:safePageUrl}`. If event insertion itself fails, log a server-side warning with the run/point IDs and do not recursively append another event.

- [ ] **Step 3: Prevent compilation after evidence persistence failure**

Make `captureLocatorKnowledge` return a boolean or typed result. The dispatcher must call `compile` only when all browser-discovered pages for the current point were persisted successfully. A point with persistence failure becomes blocked and the next point continues.

- [ ] **Step 4: Add tests for both persistence failure and event failure**

Assert that a persistence failure:

- calls `markPointBlocked(runId, pointId)`;
- does not call `generator.compile` for that point;
- emits one bounded error event;
- does not stop later points;
- does not include credentials, headers, cookies, raw locator values, or query strings.

Add a second test where event insertion fails and assert the point remains blocked without recursive failure.

- [ ] **Step 5: Run tests and commit**

```bash
cd server/platform
./mvnw -q -Dtest=WebCaseAgentDispatcherTest,LocatorKnowledgeServiceTest,AiDraftTestPointReviewTest test
git add server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/locator/LocatorKnowledgeService.java server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcher.java server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcherTest.java server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/locator/LocatorKnowledgeServiceTest.java
git commit -m "fix(ai): surface locator knowledge persistence failures"
```

## Task 4: Preserve Safe Locator Diagnostics And Reject Ambiguous Tabs

**Files:**
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcher.java`
- Modify: `server/web-runner/page-locator-discovery.mjs`
- Modify: `server/web-runner/page-locator-discovery.test.mjs`
- Modify: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcherTest.java`

- [ ] **Step 1: Replace lossy target truncation with a safe diagnostic reference**

Split the current `safeActionTarget` behavior into two functions:

```java
private String safeLocatorReference(String locator) {
    // Preserve strategy and a bounded, escaped semantic name; never include input literals or URL query values.
}

private String locatorFingerprint(String locator) {
    // SHA-256, first 16 hex characters, no raw locator in event details.
}
```

For `role=button[name="停用"]`, details should contain `targetKind='role'`, `accessibleName='停用'`, and `locatorFingerprint`, not a misleading `role`-only target and not a raw locator. URL targets continue through `safePageUrl` and retain safe hash routes.

Derive `accessibleName` only from the browser-observed candidate's already collected `label`/`accessibleName` fields in the matching exploration round. If no candidate is uniquely associated with the action, leave it empty and classify the event as `ambiguous_locator`; never infer a name from the raw request string.

- [ ] **Step 2: Make id-less duplicate tabs non-actionable**

In `observePage`, produce a tab locator only when one of these is true:

- stable `data-testid` or `id` exists;
- unique `role=tab[name=...]` exists;
- unique CSS path is generated inside the tablist.

If the label is duplicated and no stable scope exists, set `locator: undefined` and `ambiguous: true`. Do not generate `text=...` for an actionable tab.

- [ ] **Step 3: Harden switch-tab execution**

In `performSafeAction`:

- reject an empty target with `failureType='ambiguous_locator'`;
- require the target to be in `observedSafeTabLocators`;
- resolve exactly one element;
- re-check `role='tab'`, enabled state, selected state, and forbidden business labels after resolution;
- never use `.first()` as fallback.

- [ ] **Step 4: Run the new red tests and existing locator tests**

```bash
cd server/web-runner
node --test --test-name-pattern='diagnostic reference|duplicate tab|id-less safe tab|dangerous tab' page-locator-discovery.test.mjs
```

Expected: details preserve safe references, duplicate tabs return `ambiguous_locator`, dangerous tabs never mutate the fixture, and existing unique-tab scenarios remain green.

- [ ] **Step 5: Commit**

```bash
git add server/web-runner/page-locator-discovery.mjs server/web-runner/page-locator-discovery.test.mjs server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcher.java server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcherTest.java
git commit -m "fix(web): preserve safe locator diagnostics and reject ambiguity"
```

## Task 5: Add Bounded Semantic Expansion For Unfamiliar Requirements

**Files:**
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebExplorationIntentBuilder.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebExplorationSemanticExpansionService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebExplorationSemanticExpansionServiceTest.java`
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java`
- Modify: `server/web-runner/page-exploration-policy.mjs`
- Modify: `server/web-runner/page-exploration-policy.test.mjs`

- [ ] **Step 1: Expand deterministic intent extraction before any AI call**

Extend `WebExplorationIntentBuilder` to extract:

- quoted UI labels from title, natural steps, expected result, and source text;
- page aliases from module, page name, breadcrumb-like phrases, and route hints;
- state phrases from quoted values and status words;
- table context phrases such as `操作列`, `状态列`, `列表`, `详情`;
- explicit negative terms separately from automatic generic navigation terms.

Add tests for unfamiliar phrases such as `冻结资格入口`, `恢复服务资格`, `灰度状态`, and `权益失效提示` without adding those strings to a global keyword list.

- [ ] **Step 2: Define an optional bounded AI expansion service**

Create `WebExplorationSemanticExpansionService.expand(TargetIntent intent, List<String> sourceTexts)` with these hard limits:

- feature flag `app.ai.web-exploration.semantic-expansion-enabled`, default `false` in existing environments;
- at most one call per point;
- timeout 5 seconds;
- output maximum 8 page aliases, 8 element terms, 6 state terms, and 4 action terms;
- output must be JSON only and pass the same generic-term, negative-term, and forbidden-action validation as deterministic terms;
- on timeout, provider failure, malformed JSON, or unsafe term, return the deterministic intent unchanged;
- never send credentials, headers, cookies, raw DOM, screenshots, or full URLs with query strings.

Store the flag in the existing AI system configuration as `webExplorationSemanticExpansionEnabled`, defaulting to `false` when absent. Add `sanitizeExpansionInput()` before the Gateway call: remove URL query/fragment, Authorization/Cookie/password/token/apiKey/secretKey assignments, cap each source text at 2,400 characters and the combined prompt at 8,000 characters. Record the bounded call through the existing AI call-record path with a redacted input summary; provider failure must not fail the exploration run.

- [ ] **Step 3: Use expansion only to rank evidence, not to grant actions**

Merge the bounded terms into `TargetIntent` before calling Runner. The Runner still controls the browser action allowlist and minimum scores. AI-expanded terms may affect candidate ranking and page alias search, but cannot change `allowedRequestOrigins`, budgets, forbidden labels, or evidence thresholds.

- [ ] **Step 4: Add policy tests for contextual matching**

Add tests proving a candidate with matching `rowContext` and state phrase outranks a generic navigation candidate, while a candidate matching only `项目`, `服务`, or `管理` remains ineligible. Add tests for compound business labels that contain a generic word, such as `用户组` and `云服务`.

- [ ] **Step 5: Run tests and commit**

```bash
cd server/platform
./mvnw -q -Dtest=WebExplorationSemanticExpansionServiceTest,AiDraftTestPointReviewTest test

cd ../../web-runner
node --test page-exploration-policy.test.mjs page-explorer.test.mjs

git add server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebExplorationIntentBuilder.java server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebExplorationSemanticExpansionService.java server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebExplorationSemanticExpansionServiceTest.java server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java server/web-runner/page-exploration-policy.mjs server/web-runner/page-exploration-policy.test.mjs
git commit -m "feat(ai): expand unfamiliar web exploration terms safely"
```

## Task 6: End-To-End ICC Read-Only Acceptance

**Files:**
- Modify only test fixtures or configuration adapters if a failure is found; do not change business operation code during this task.
- Create `server/web-runner/fixtures/icc-readonly-exploration.html` only for local deterministic replay, never with real credentials.
- Create `server/platform/src/test/resources/icc-exploration-contract.json` with redacted origins and labels only.
- Do not add real-ICC credentials or authenticated network calls to CI; the authenticated ICC check is a manually triggered development acceptance using the user's existing local Web environment.

- [ ] **Step 1: Validate the configured origin contract without credentials**

Use the Web environment configuration shape with redacted credentials for the contract test. Assert:

- Web origin: `https://app.example.invalid`;
- login origin: same Web origin;
- authentication API origin: `https://api.example.invalid`;
- target route: `#/open/threat-intelligence`;
- cookie origins, if configured, contain only explicitly approved origins.

Do not print or persist username/password values.

The manual authenticated check must inject credentials from the existing local environment at runtime, must be excluded from the normal CI test command, and must be marked `manual-dev-only` in the run report.

- [ ] **Step 2: Run a controlled read-only exploration**

Run the evidence stage only for `point-dbe244b9-1e20-4192-aecc-25c5dc00296c`. Do not invoke generated `click`, `stop`, `delete`, `save`, or confirmation steps. Verify:

- login succeeds without `network_blocked` for the configured auth API;
- navigation reaches `#/open/threat-intelligence`;
- at least one round reports the target page signals;
- if the account has a matching business row, the evidence contains row-scoped `停用` or equivalent target element;
- if no matching row exists, the result is `exhausted` or `blocked` with an explicit reason, never a global-navigation assertion;
- no blocked request record contains query strings, cookies, or headers;
- no mutation counter or business state changes.

- [ ] **Step 3: Verify retry behavior**

Use a deterministic local fixture to force, in order:

1. wrong page;
2. hidden menu;
3. target behind safe overlay;
4. target in a scroll container;
5. duplicate target requiring row context.

Assert the explorer changes strategy between rounds, stops at five rounds or 90 seconds, and records the exact failure category when exhausted.

- [ ] **Step 4: Record acceptance metrics**

For at least 20 redacted representative points, record:

- page-match rate;
- business-element match rate;
- median and p95 rounds;
- median and p95 duration;
- percentage blocked by login, network, ambiguity, missing data, and persistence failure;
- number of forbidden business actions attempted, which must remain zero.

- [ ] **Step 5: Commit only acceptance fixtures or required adapter fixes**

```bash
git diff --check
git status --short
git add server/web-runner/fixtures/icc-readonly-exploration.html server/platform/src/test/resources/icc-exploration-contract.json
git commit -m "test(web): add read-only ICC exploration acceptance"
```

Do not commit real account data, cookies, tokens, screenshots containing personal data, or environment secrets.

## Task 7: Frontend Failure Visibility

**Files:**
- Modify: `apps/web/src/domain/webCaseAgentRepository.ts`
- Modify: `apps/web/src/components/workflow/WebCaseAgentWorkspace.vue`
- Modify: `apps/web/src/components/workflow/WebCaseAgentWorkspace.test.ts`

- [ ] **Step 1: Extend event details types**

Add optional fields:

```ts
type WebExplorationFailureDetails = {
  category?: 'network_blocked' | 'locator_persistence' | 'ambiguous_locator' | 'wrong_page' | 'element_not_found' | 'unsafe_overlay' | 'duration_exhausted'
  retryable?: boolean
  failureReason?: string
  locatorFingerprint?: string
  accessibleName?: string
}
```

Keep old events with `details: null` valid.

- [ ] **Step 2: Render compact, non-sensitive failure information**

In `WebCaseAgentWorkspace.vue`, show the latest point-level failure category, retryability, exploration round, score, and safe page route in the existing meta/timeline area. Use title text for full safe details; never render raw request URL query strings, credentials, headers, cookies, or raw Locator values.

- [ ] **Step 3: Add UI regressions**

Test that:

- legacy event details render summary only;
- `locator_persistence` shows “素材保存失败，测试点已阻塞”;
- `ambiguous_locator` shows “定位器存在歧义，等待重新取证”;
- `network_blocked` shows a retryable network category without the blocked URL query;
- long routes and fingerprints do not expand the workspace layout.

- [ ] **Step 4: Run frontend tests and build**

```bash
cd apps/web
npm test -- --run src/domain/webCaseAgentRepository.test.ts src/components/workflow/WebCaseAgentWorkspace.test.ts src/components/workflow/AutomationCompileStage.test.ts
npm run build
git add apps/web/src/domain/webCaseAgentRepository.ts apps/web/src/components/workflow/WebCaseAgentWorkspace.vue apps/web/src/components/workflow/WebCaseAgentWorkspace.test.ts
git commit -m "feat(web): show safe exploration failure diagnostics"
```

## Task 8: Full Verification, Rollout, And Rollback

**Files:**
- Modify only release/configuration files required to expose the semantic-expansion feature flag and origin allowlist.

- [ ] **Step 1: Run all focused suites**

```bash
cd server/web-runner
npm test

cd ../platform
./mvnw -q -Dtest=AiDraftTestPointReviewTest,WebCaseAgentDispatcherTest,WebCaseAgentEventServiceTest,LocatorKnowledgeServiceTest,WebCaseAgentSchemaMigrationTest,WebExplorationIntentBuilderTest,WebExplorationSemanticExpansionServiceTest test

cd ../../apps/web
npm test -- --run src/domain/webCaseAgentRepository.test.ts src/components/workflow/WebCaseAgentWorkspace.test.ts src/components/workflow/AutomationCompileStage.test.ts
npm run build
```

Expected: zero test failures and a successful TypeScript/Vite build. Existing chunk-size warning may remain but must be reported, not hidden.

- [ ] **Step 2: Run static and security hygiene checks**

```bash
cd /path/to/Haitlify
git diff --check
rg -n '\[DEBUG-|console\.log|force:\s*true|password|authToken|access_token|Cookie:' server/web-runner server/platform/src/main/java apps/web/src -g '*.mjs' -g '*.java' -g '*.vue' -g '*.ts' | rg -v 'test|placeholder|type|field|redacted' || true
git status --short
```

Expected: no debug instrumentation, no forced clicks, no newly introduced credentials or raw request headers in production code, and a clean worktree.

- [ ] **Step 3: Rebuild and verify services**

```bash
docker compose build platform web-runner web
docker compose up -d platform web-runner web
curl --fail http://127.0.0.1:38081/actuator/health
curl --fail http://127.0.0.1:19323/health
curl --fail -I http://127.0.0.1:38080/web-cases
docker compose ps
```

Expected: platform and Runner report `UP`, Web returns `HTTP 200`, and all expected containers remain running.

- [ ] **Step 4: Verify deployed source identity**

```bash
docker exec ai-test-platform-web-runner grep -n 'allowedRequestOrigins\|network_blocked\|ambiguous_locator' /app/page-locator-discovery.mjs
docker exec ai-test-platform-server sha256sum /app/app.jar
shasum -a 256 server/platform/target/platform-0.1.0-SNAPSHOT.jar
```

Expected: the Runner contains the origin policy and ambiguity handling; platform JAR hash matches the local build.

- [ ] **Step 5: Rollout with feature flags**

Release in this order:

1. Deploy explicit authentication-origin allowlist with semantic expansion disabled.
2. Run the read-only ICC acceptance and verify login/network behavior.
3. Enable multi-round exploration for development projects only.
4. Observe the metrics from Task 6 for one representative batch.
5. Enable optional semantic expansion only after deterministic exploration remains safe and stable.

- [ ] **Step 6: Rollback procedure**

If login failures, cross-origin leakage, forbidden-action attempts, or persistence loss appears:

1. Disable semantic expansion.
2. Disable multi-round exploration through a server-side feature flag; keep accepting the new request fields but ignore them when the flag is off, so legacy single-pass behavior is a real runtime mode rather than a source-code rollback assumption.
3. Keep explicit origin policy enabled only for the configured Web and auth origins; do not revert to unrestricted network access.
4. Revert only behavior commits in reverse order, preserving V18 `details_json`, backward-compatible readers, and the `allowedRequestOrigins` field so existing stored environments remain readable.
5. Confirm platform/Runner health and rerun the focused login, persistence, and ambiguity tests.

- [ ] **Step 7: Final review gate**

Request two independent reviews over the final diff:

- code/security reviewer: origin policy, persistence errors, diagnostic redaction, duplicate locator behavior;
- architecture reviewer: Platform/Runner contract, feature flags, fallback path, migration compatibility, and rollout/rollback.

Do not mark the work merge-ready if either review lane is unavailable. Record unavailable review evidence explicitly.

## Acceptance Criteria

The remediation is complete only when all criteria hold:

- ICC login API requests succeed when their origin is explicitly configured, while unrelated cross-origin requests remain blocked.
- A locator persistence failure blocks only the affected point, emits a bounded diagnostic event, and never appears as a successful evidence step.
- Exploration event details preserve safe target references through `targetKind`, `accessibleName`, and `locatorFingerprint`; they do not reduce meaningful diagnostics to `role` alone or leak raw locators.
- Duplicate id-less tabs never produce an actionable ambiguous text locator; the result is an explicit `ambiguous_locator` block.
- Unknown business wording can be expanded through deterministic phrase extraction and, when enabled, one bounded AI semantic call; expansion cannot grant new browser actions.
- The real ICC read-only acceptance either finds the intended business element or reports a precise, actionable blocker without producing a global-navigation assertion.
- Single-point failure isolation, five-round/90-second budgets, forbidden-action protection, page fingerprint invalidation, and legacy request compatibility remain green.
- Web Runner, platform, frontend tests and build pass; services report healthy; no secrets or debug instrumentation are introduced.

## Risk Matrix

| Risk | Detection | Mitigation | Rollback |
| --- | --- | --- | --- |
| Auth API still blocked | login fixture, ICC read-only acceptance, `network_blocked` events | explicit `allowedRequestOrigins` | disable exploration only, keep narrow allowlist |
| Cross-origin leakage | blocked fetch/iframe fixture and event audit | all-request origin gate + URL policy | disable affected origin and redeploy |
| Evidence silently lost | forced DB exception test and persistence error event | typed exception + point blocking | revert persistence adapter only |
| Wrong tab clicked | duplicate-tab fixture and mutation counter | no text fallback, unique locator requirement | disable tab exploration action |
| Semantic expansion creates false matches | adversarial phrase fixtures and match-rate metrics | bounded terms, score thresholds, feature flag off by default | disable semantic expansion |
| Real ICC has no target data | read-only acceptance result | explicit exhausted reason and manual guidance | no code rollback; fix data/environment |
| Event details leak sensitive data | serialized details inspection and token-pattern scan | DTO whitelist, URL/query stripping, locator fingerprint | disable detailed event rendering, retain summary |

> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# Web Exploration Resilience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Web automatic login and browser evidence collection resilient to benign blocked resources, session-context drift, page readiness delays, and unfamiliar natural-language targets while preserving strict safety boundaries.

**Architecture:** Keep the Web Runner as the deterministic execution boundary. The Platform supplies explicit origins, structured business intent, and bounded retry context; an optional AI semantic layer may expand ranking terms but cannot grant network or browser permissions. Login validation and evidence discovery use the same session contract, network requests are classified by criticality, and every failed point receives a precise diagnostic instead of a generic batch failure.

**Tech Stack:** Node.js ESM, Playwright 1.61, Java 21, Spring Boot 4, Jackson 3, JdbcTemplate, Vue 3, TypeScript, Vitest, Node test runner, Docker Compose.

---

## Scope And Invariants

The implementation covers these independently testable areas:

- Runner request classification and safe Origin handling.
- Login session handoff and multi-signal success detection.
- Bounded page readiness and evidence retry state machine.
- AI intent parsing and safe repair suggestions.
- Platform/Runner diagnostic contract and frontend display.
- Rollout flags, metrics, and rollback behavior.

The following invariants must remain true:

- No automatic action may click `确认`, `确定`, `提交`, `保存`, `删除`, `停用`, `启用`, `开通`, `支付`, `发送`, or `上传`.
- AI output cannot add Origins, lower evidence thresholds, extend budgets, or bypass observed Locator validation.
- Explicit Origin allowlists accept only valid `http`/`https` Origins without userinfo, query, or fragment.
- Optional blocked assets never turn a valid business evidence result into a failed result.
- Critical blocked requests remain visible and fail closed when business evidence is unavailable.
- Login credentials, cookies, Authorization headers, raw DOM, screenshots, and query-bearing URLs never enter AI prompts or durable event details.
- Every point is isolated: one point may be blocked while later points continue.
- Default budgets remain bounded at five exploration rounds and 90 seconds per point.

## File Map

- Modify `server/web-runner/page-locator-discovery.mjs`: request classification, session-aware login, readiness probes, and precise exploration failure reasons.
- Create `server/web-runner/session-manager.mjs`: bounded in-memory authenticated session registry with project/environment/browser isolation and TTL cleanup.
- Modify `server/web-runner/page-locator-discovery.test.mjs`: critical/optional Origin fixtures, session reuse, login readiness, and blocked-resource regressions.
- Modify `server/web-runner/page-explorer.mjs` and `server/web-runner/page-exploration-policy.mjs`: bounded repair action categories and criticality-aware scoring.
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationLocatorDiscoveryService.java`: request fields for session metadata, request classification, and diagnostic response types.
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/PlaywrightRunClient.java`: preserve Runner diagnostic payloads and distinguish Runner unavailable from evidence blocked.
- Create `server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationEvidenceException.java`: typed 422-level evidence/login failure carrying only safe structured details.
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java`: pass session/origin context, preserve point-level diagnostics, and keep browser fallback on knowledge-read failure.
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcher.java`: classify evidence failures, retry only safe categories, and isolate points.
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebExplorationSemanticExpansionService.java`: keep AI expansion bounded and attach a reasoned fallback result.
- Modify `apps/web/src/domain/executionEnvironmentRepository.ts` and `apps/web/src/App.vue`: expose diagnostics, preserve explicit Origins, and keep login/session settings consistent.
- Modify `apps/web/src/domain/webCaseAgentRepository.ts` and `apps/web/src/components/workflow/WebCaseAgentWorkspace.vue`: consume structured diagnostics and show actionable messages.
- Add focused tests in `server/web-runner/page-locator-discovery.test.mjs`, `server/web-runner/page-explorer.test.mjs`, `server/platform/src/test/java/com/haitlify/aitest/platform/webautomation/PlaywrightRunClientTest.java`, `server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcherTest.java`, `server/platform/src/test/java/com/haitlify/aitest/platform/ai/draft/WebExplorationSemanticExpansionServiceTest.java`, `apps/web/src/domain/webCaseAgentRepository.test.ts`, and `apps/web/src/components/workflow/WebCaseAgentWorkspace.test.ts`.
- Add `docs/superpowers/specs/2026-09-01-web-exploration-resilience-design.md` only if implementation decisions diverge from this plan.

## Concrete Contracts

### Network Diagnostic Contract

Runner returns these fields under `exploration.network` and never returns raw request payloads:

```json
{
  "criticalBlocked": [
    {
      "origin": "https://api.example.com",
      "path": "/api/project/list",
      "resourceType": "xhr",
      "criticality": "critical",
      "failureType": "network_blocked"
    }
  ],
  "optionalBlocked": [
    {
      "origin": "https://static.example.com",
      "path": "/font.woff2",
      "resourceType": "font",
      "criticality": "optional",
      "failureType": "network_blocked"
    }
  ]
}
```

Criticality is deterministic and evaluated in this order:

1. `document` for the requested target URL is critical.
2. The configured login URL and every configured `allowedRequestOrigins` path used during authentication are critical.
3. A `script` is critical when it is same-origin, loaded by the target document, and its response is required before the target shell becomes visible; otherwise it is optional.
4. `xhr`/`fetch` is critical when its path matches a configured authentication path, a same-origin route bootstrap path, or a request whose successful response causes the target business container/target text to appear.
5. `image`, `font`, `media`, `manifest`, `favicon`, analytics, telemetry, and known static decoration are optional.
6. Unknown requests are recorded as `unknown`; they fail the point only when no target page or business element evidence is available after the repair budget.

When a request cannot be classified from response/DOM evidence, Runner records `criticality=unknown` and Platform maps the final failure to `network_blocked_unknown`, never silently to `network_blocked`.

### Session Contract

Session state is held only in the Runner process:

```json
{
  "sessionId": "session-uuid",
  "environmentKey": "project-id:environment-id",
  "createdAt": "ISO-8601",
  "expiresAt": "ISO-8601",
  "observedOrigins": ["https://app.example.invalid", "https://api.example.invalid"],
  "storageState": "runner-internal"
}
```

Rules:

- TTL is 10 minutes, refreshed only while a point is actively running.
- Sessions are keyed by project and environment; a session cannot be used by another project, environment, or browser family.
- `sessionId` is opaque and is never written to AI prompts, event summaries, screenshots, or browser-visible UI.
- Cookies and storage state stay in Runner memory. Platform receives only `sessionId`, `observedOrigins`, `expiresAt`, and a boolean `authenticated`.
- A session is destroyed on explicit stop, logout, expiry, or Origin-policy mismatch.
- If reuse fails, the Runner performs at most one reauthentication within the point budget; it must not transfer credentials between contexts.

### AI Repair Contract

The optional AI expansion call may return only this object:

```json
{
  "pageAliases": [],
  "elementTerms": [],
  "stateTerms": [],
  "actionTerms": [],
  "repairSuggestion": {
    "category": "wait_ready",
    "reason": "目标页面可能仍在加载业务容器"
  }
}
```

`category` must be one of the Task 4 safe repair categories. Runner validates it against the current observation and ignores it when the suggested action is unavailable, unsafe, repeated without state change, or outside the current budget. AI never returns or modifies Origins, cookies, headers, credentials, budgets, Locator strings, or executable DSL.

### Rollout And Metrics Contract

Flags live in `ai_system_configs.provider_config_json` under the `default` row, with a compatibility fallback row using the same key. Missing keys use the defaults in Task 7. Configuration is read once per point, not on every browser request.

Each point emits one bounded metrics object in the existing exploration event `details_json`:

```json
{
  "rounds": 3,
  "durationMs": 12840,
  "status": "matched",
  "failureCategory": "",
  "criticalBlockedCount": 0,
  "optionalBlockedCount": 2,
  "repairActionCount": 2,
  "matchedElementCount": 4,
  "forbiddenActionCount": 0
}
```

The 20-point aggregation is test/diagnostic tooling only; production retains the bounded per-point event fields for later aggregation.

## Current Failure Closure

The reported result `failureReason=network_blocked, status=exhausted, rounds=2, totalScore=20, elementScore=0` is handled in this order:

1. Task 2 records the exact blocked Origin/path/resource type and decides whether it is critical or optional.
2. Task 3 verifies that the evidence context is authenticated and waits for the target shell/business API.
3. Task 4 applies only safe repairs (`wait_ready`, `reauthenticate`, navigation, scroll, pagination, or reobserve) and stops at the point budget.
4. Task 6 maps the final result to `auth_context_lost`, `page_not_ready`, `business_api_failed`, `network_blocked_critical`, or `network_blocked_optional`.
5. The UI presents the blocked Origin and the next safe action, rather than only `network_blocked`.

If the target business API remains blocked after explicit Origin configuration and one bounded reauthentication, the point remains blocked by design; the system must never weaken the network policy or guess a business Locator to force a match.

## Task 1: Reproduce The Current Network And Session Failures

**Files:**
- Modify `server/web-runner/page-locator-discovery.test.mjs`
- Modify `server/platform/src/test/java/com/haitlify/aitest/platform/webautomation/PlaywrightRunClientTest.java`
- Modify `apps/web/src/domain/webCaseAgentRepository.test.ts`

- [ ] **Step 1: Add a failing fixture with one critical and one optional blocked Origin.**

Serve the app from `appOrigin`, issue a business API request to `apiOrigin`, load a font from `fontOrigin`, and render a target element only after the business API succeeds. Call `discoverPageLocators` with only `appOrigin` allowed. The desired assertion is that the report separates `criticalBlocked` and `optionalBlocked`, with final reason `network_blocked_critical`.

- [ ] **Step 2: Add a failing login-context fixture.**

Make the login page set an authenticated cookie and navigate to `/project`; make a separate evidence context omit the cookie. Assert the desired diagnostic category is `auth_context_lost`, not generic `network_blocked`.

- [ ] **Step 3: Add a failing stale-success-target fixture.**

Navigate from `/login` to `/project` while configuring an obsolete `successTarget`. Assert the desired result is successful login with a warning, not HTTP 503.

- [ ] **Step 4: Run the red tests.**

```bash
cd /path/to/Haitlify/server/web-runner
node --test --test-name-pattern='critical|optional|auth context|stale success' page-locator-discovery.test.mjs
```

Expected result: each new assertion fails against the current behavior.

- [ ] **Step 5: Commit the red tests.**

```bash
git add server/web-runner/page-locator-discovery.test.mjs server/platform/src/test/java/com/haitlify/aitest/platform/webautomation/PlaywrightRunClientTest.java apps/web/src/domain/webCaseAgentRepository.test.ts
git commit -m "test(web): reproduce fragile evidence and login failures"
```

## Task 2: Classify Network Requests Without Weakening Security

**Files:**
- Modify `server/web-runner/page-locator-discovery.mjs`
- Modify `server/web-runner/page-exploration-policy.mjs`
- Modify `server/web-runner/page-locator-discovery.test.mjs`
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationLocatorDiscoveryService.java`

- [ ] **Step 1: Define the safe request diagnostic shape.**

Use `{ origin, resourceType, criticality, failureType, path }`. Strip query and fragment from `path`; never include headers, body, cookies, or response body.

- [ ] **Step 2: Implement deterministic criticality classification.**

Add `classifyRequest({ request, response, targetUrl, loginUrl, authOrigins, observedBusinessSignals })` in `page-locator-discovery.mjs`. The helper must return `critical`, `optional`, or `unknown` using the ordered rules in `Concrete Contracts`; tests must call the helper with representative `document`, `script`, `xhr`, `font`, and analytics requests.

- [ ] **Step 3: Preserve narrow Origin validation.**

Normalize `allowedRequestOrigins` and `allowedOrigins` into a strict HTTP/HTTPS Origin set. Reject malformed, credential-bearing, query-bearing, or fragment-bearing entries. Run `assertAllowedUrl` after Origin validation and never infer sibling subdomains.

- [ ] **Step 4: Stop optional blocks from overriding valid evidence.**

Use this result rule:

```js
const hasCriticalBlock = blocked.some(item => item.criticality === 'critical')
const hasBusinessEvidence = exploration.status === 'matched' && exploration.scores?.elementScore > 0
const failureReason = hasCriticalBlock && !hasBusinessEvidence
  ? 'network_blocked_critical'
  : exploration.failureReason
```

Return optional blocks as warnings when evidence is matched.

The returned `exploration.network` object must include `criticalBlocked`, `optionalBlocked`, and `unknownBlocked`; `networkBlocked` remains as a compatibility alias containing all three lists.

- [ ] **Step 5: Add policy tests.**

Cover successful evidence with blocked font/analytics, failed evidence with blocked business API, explicit auth API allowlisting, unrelated iframe blocking, and query/header redaction.

- [ ] **Step 6: Run and commit.**

```bash
cd /path/to/Haitlify/server/web-runner
npm test
git add page-locator-discovery.mjs page-exploration-policy.mjs page-locator-discovery.test.mjs
git commit -m "fix(web): classify blocked resources by criticality"
```

## Task 3: Unify Login Validation And Evidence Session State

**Files:**
- Modify `server/web-runner/page-locator-discovery.mjs`
- Modify `server/web-runner/runner-core.mjs`
- Modify `server/web-runner/web-runner-server.mjs`
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationLocatorDiscoveryService.java`
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java`
- Modify `server/web-runner/page-locator-discovery.test.mjs`

- [ ] **Step 1: Add a session request contract.**

Extend the request with optional `sessionId` and `reuseSession` only. Do not accept raw `sessionCookies` or `sessionStorageState` from Platform. Runner owns those values through `session-manager.mjs`; compatibility constructors default `reuseSession=true` and `sessionId` empty.

- [ ] **Step 2: Reuse validated browser state.**

Implement `createSession`, `getSession`, `touchSession`, and `destroySession` in `session-manager.mjs`. `discoverPageLocators` first obtains a matching session; if absent, it creates one context, runs login, stores the context, and performs evidence in that same context. `finally` closes only contexts that were created for a completed one-shot request; reusable sessions remain until TTL/stop/logout. Reject mismatched project/environment/browser keys with `auth_context_lost`.

- [ ] **Step 3: Use multi-signal login success.**

Accept login when the API succeeds, the URL leaves the login route, the login form disappears or authenticated shell appears, and no explicit failure text exists. Keep `successTarget` as an enhancement; report `successWithWarning` when it is stale.

- [ ] **Step 4: Add bounded readiness probes.**

Implement `waitForPageReadiness(page, intent, networkState, timeoutMs)` with a hard five-second cap. It must wait for two consecutive observations with the same fingerprint and then accept any one of: a matching heading/page alias, a matching business element, or a successful critical business response. If the login shell remains visible, return `auth_context_lost`; if authenticated but no target shell appears, return `page_not_ready`.

- [ ] **Step 5: Test same-context and context-loss behavior.**

Assert same-context evidence can read the authenticated cookie, a mismatched session key reports `auth_context_lost`, session expiry causes one reauthentication and then a bounded failure, stale targets produce `successWithWarning`, and login failure remains fail-closed with redacted diagnostics.

- [ ] **Step 6: Commit.**

```bash
git add server/web-runner/page-locator-discovery.mjs server/web-runner/runner-core.mjs server/web-runner/web-runner-server.mjs server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationLocatorDiscoveryService.java server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java server/web-runner/page-locator-discovery.test.mjs
git commit -m "fix(web): reuse authenticated evidence sessions"
```

## Task 4: Implement Bounded Evidence Repair State Machine

**Files:**
- Modify `server/web-runner/page-explorer.mjs`
- Modify `server/web-runner/page-exploration-policy.mjs`
- Modify `server/web-runner/page-locator-discovery.mjs`
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcher.java`
- Add tests in `server/web-runner/page-explorer.test.mjs` and `server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcherTest.java`

- [ ] **Step 1: Define safe repair categories.**

Use only `wait_ready`, `dismiss_safe_overlay`, `menu_search`, `same_origin_navigation`, `switch_readonly_tab`, `scroll_container`, `paginate`, `reobserve`, and `reauthenticate`. Define the TypeScript/Java enum as a closed list and reject any other value before dispatch. Filter mutation labels before action selection.

- [ ] **Step 2: Track point-local repair state.**

Track `round`, `remainingDurationMs`, `pageFingerprint`, `lastFailureCategory`, `sameFailureCount`, and `repairTrail`. `repairTrail` entries are `{ category, round, changedFingerprint }`. Reset only `sameFailureCount` when the fingerprint changes; preserve the full trail for diagnostics. Stop at five rounds, 90 seconds, or the same-failure budget.

- [ ] **Step 3: Retry only actionable categories.**

Retry `page_not_ready`, `auth_context_lost`, `network_blocked_critical`, and `element_not_found` only when the next safe action changes state. `reauthenticate` is allowed at most once per point and never after an explicit login failure response. Do not repeat unchanged `ambiguous_locator` or unsafe overlay actions indefinitely.

- [ ] **Step 4: Preserve point isolation.**

When evidence fails, block only the current point, append its structured diagnostic, and continue later points. Compile only evidence bundles with matched business elements and no critical unresolved block. `WebAutomationRunnerUnavailableException` is reserved for transport/health failures; Runner-returned login/evidence failures retain their category and are mapped to point-level failures.

- [ ] **Step 5: Add hostile fixture coverage.**

Force wrong route, delayed shell, safe overlay, hidden menu, scroll container, pagination, duplicate row actions, optional asset block, critical API block, and expired auth context. Assert mutation counters remain zero.

- [ ] **Step 6: Run and commit.**

```bash
cd /path/to/Haitlify/server/web-runner
npm test
cd ../platform
./mvnw -q -Dtest=WebCaseAgentDispatcherTest,AiDraftTestPointReviewTest test
cd ../..
git add server/web-runner/page-explorer.mjs server/web-runner/page-exploration-policy.mjs server/web-runner/page-locator-discovery.mjs server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcher.java server/web-runner/page-explorer.test.mjs server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcherTest.java
git commit -m "feat(web): add bounded evidence repair state machine"
```

## Task 5: Use AI For Intent Understanding And Repair Suggestions Only

**Files:**
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebExplorationIntentBuilder.java`
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebExplorationSemanticExpansionService.java`
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java`
- Modify `server/web-runner/page-exploration-policy.mjs`
- Add tests to `WebExplorationSemanticExpansionServiceTest.java` and `page-exploration-policy.test.mjs` for output validation and fallback reasons.

- [ ] **Step 1: Keep deterministic extraction first.**

Extract quoted labels, page aliases, state phrases, row/table context, expected visible text, and explicit negative terms from title, objective, natural steps, expected result, and source evidence.

- [ ] **Step 2: Bound the optional AI call.**

Keep feature flag default-off, one call per point, five-second timeout, maximum 8 page aliases, 8 element terms, 6 state terms, 4 action terms, 2,400 characters per source, and 8,000 combined characters. Return `{ intent, repairSuggestion, fallbackReason }`, with `fallbackReason` values `disabled`, `config_missing`, `timeout`, `malformed_output`, and `provider_failure`.

- [ ] **Step 3: Sanitize the complete prompt.**

Remove query/fragment, userinfo, Authorization/Cookie/password/token/apiKey/secretKey assignments, raw DOM, screenshots, and headers from route hints, aliases, source text, and evidence summaries.

- [ ] **Step 4: Restrict AI output.**

Validate terms with existing generic, forbidden, negative, length, and count filters. AI output cannot modify Origins, budgets, action allowlists, or minimum scores.

- [ ] **Step 5: Add repair suggestion schema.**

Allow only one deterministic repair category plus a short reason. Runner validates the suggestion against the current observation and ignores unknown or unsafe categories.

- [ ] **Step 6: Run and commit.**

```bash
cd /path/to/Haitlify/server/platform
./mvnw -q -Dtest=WebExplorationSemanticExpansionServiceTest,AiDraftTestPointReviewTest test
cd ../../server/web-runner
node --test page-exploration-policy.test.mjs page-explorer.test.mjs
cd ../..
git add server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebExplorationIntentBuilder.java server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebExplorationSemanticExpansionService.java server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java server/web-runner/page-exploration-policy.mjs
git commit -m "feat(ai): use bounded semantic intent and repair suggestions"
```

## Task 6: Expose Structured Diagnostics In Platform And Frontend

**Files:**
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/PlaywrightRunClient.java`
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcher.java`
- Modify `apps/web/src/domain/webCaseAgentRepository.ts`
- Modify `apps/web/src/components/workflow/WebCaseAgentWorkspace.vue`
- Modify `apps/web/src/components/workflow/WebCaseAgentWorkspace.test.ts`

- [ ] **Step 1: Define stable categories.**

Use `auth_context_lost`, `login_failed`, `page_not_ready`, `network_blocked_critical`, `network_blocked_optional`, `business_api_failed`, `element_not_found`, `ambiguous_locator`, `unsafe_overlay`, `locator_persistence`, and `duration_exhausted`.

- [ ] **Step 2: Preserve Runner details safely.**

When Runner returns a login/evidence failure, preserve only `category`, `criticalBlocked`, `optionalBlocked`, sanitized `pageUrl`, `repairTrail`, and `retryable`. `PlaywrightRunClient.runnerError()` must parse these fields from JSON; it must map status 503 to `WebAutomationRunnerUnavailableException` only when the body lacks a recognized evidence/login category. Otherwise return a typed `WebAutomationEvidenceException` with status 422 and safe details.

- [ ] **Step 3: Replace generic UI summaries.**

Render compact point-level diagnostics such as `关键业务 API 被阻断，页面未加载` plus safe Origin/path/resource type and a configuration/retry action. Optional blocked assets display as warnings when evidence is matched.

- [ ] **Step 4: Add frontend tests.**

Cover login failure, auth context loss, critical/optional network blocks, stale success target warning, long repair trail truncation, and the absence of credentials, query strings, and raw Locators in rendered text or titles.

- [ ] **Step 5: Run and commit.**

```bash
cd /path/to/Haitlify/apps/web
npm test -- --run src/domain/webCaseAgentRepository.test.ts src/components/workflow/WebCaseAgentWorkspace.test.ts
npm run build
cd ../..
git add server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/PlaywrightRunClient.java server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcher.java apps/web/src/domain/webCaseAgentRepository.ts apps/web/src/components/workflow/WebCaseAgentWorkspace.vue apps/web/src/components/workflow/WebCaseAgentWorkspace.test.ts
git commit -m "feat(web): show actionable evidence diagnostics"
```

## Task 7: Add Rollout Flags And Metrics

**Files:**
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java`
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebExplorationSemanticExpansionService.java`
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcher.java`
- Modify `server/platform/src/main/resources/application.yml` and `application-local.yml` to document the four flag defaults; database `provider_config_json` remains the runtime override source.
- Add tests for flags and metrics.

- [ ] **Step 1: Add compatibility-safe flags.**

Use `webMultiRoundExplorationEnabled=true`, `webEvidenceSessionReuseEnabled=true`, `webCriticalityAwareNetworkPolicyEnabled=true`, and `webExplorationSemanticExpansionEnabled=false`. Missing keys retain the safe current defaults.

- [ ] **Step 2: Record safe point metrics.**

Record `rounds`, `durationMs`, `status`, `failureCategory`, `criticalBlockedCount`, `optionalBlockedCount`, `repairActionCount`, `matchedElementCount`, and `forbiddenActionCount` in the existing exploration event `details_json` using the exact object from `Concrete Contracts`. Exclude query-bearing URLs, credentials, DOM, and screenshots.

- [ ] **Step 3: Add test-only representative aggregation.**

For at least 20 redacted point results calculate page-match rate, business-element match rate, median/p95 rounds, median/p95 duration, category distribution, and forbidden-action count. Keep aggregation out of the production request path.

- [ ] **Step 4: Run and commit.**

```bash
cd /path/to/Haitlify/server/platform
./mvnw -q -Dtest=WebCaseAgentDispatcherTest,WebExplorationSemanticExpansionServiceTest test
git add server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebExplorationSemanticExpansionService.java server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcher.java
git commit -m "feat(web): add exploration rollout flags and metrics"
```

## Task 8: Read-Only ICC Acceptance And Deployment Verification

**Files:**
- Modify only `server/web-runner/fixtures/`, `server/web-runner/icc-readonly-exploration.test.mjs`, or the Platform request adapter when acceptance finds a failure.
- Never add real ICC credentials, cookies, screenshots, or authenticated URLs to the repository.

- [ ] **Step 1: Extend the deterministic ICC fixture.**

Cover login page, explicit auth API Origin, business API, optional static asset, hidden menu, safe overlay, scroll container, pagination, repeated row action, and a read-only mutation counter.

- [ ] **Step 2: Run deterministic acceptance.**

```bash
cd /path/to/Haitlify/server/web-runner
npm test -- --test-name-pattern='ICC exploration|critical|optional|auth context'
```

Expected: explicit auth API succeeds, optional blocks do not fail matched evidence, critical business API blocks precisely, and mutation count remains zero.

- [ ] **Step 3: Run real ICC only as manual development acceptance.**

Use the existing local Web environment at runtime. Inject credentials only at execution time, do not print them, and perform only login plus read-only route/evidence checks. Mark the redacted result `manual-dev-only`.

Manual ICC checklist (no submit/delete/save/stop/confirm actions):

1. Login validation reaches the configured post-login route and records `authenticated=true`.
2. Evidence reuses the same `sessionId`, or reports `auth_context_lost` with a repair trail.
3. `https://api.example.invalid` is explicitly allowlisted and has no critical block.
4. `#/open/threat-intelligence` becomes ready within five seconds.
5. A business target element is collected, or the report gives `page_not_ready`, `business_api_failed`, or `element_not_found` with a repair trail.
6. Optional blocked assets are warnings only when business evidence is matched.
7. No mutation counter, business row, or account state changes.
8. Credentials are absent from reports, logs, event details, and screenshots.

- [ ] **Step 4: Rebuild and verify source identity.**

```bash
cd /path/to/Haitlify
docker compose build platform web-runner web
docker compose up -d platform web-runner web
curl --fail http://127.0.0.1:38081/actuator/health
curl --fail http://127.0.0.1:19323/health
curl --fail -I http://127.0.0.1:38080/web-cases
docker compose ps
```

Verify Runner source includes `criticality`, `auth_context_lost`, and `network_blocked_critical`; verify Platform local/container JAR hashes match.

- [ ] **Step 5: Commit only fixture or adapter changes.**

```bash
git diff --check
git status --short
git add server/web-runner/fixtures server/web-runner/icc-readonly-exploration.test.mjs server/platform/src/test/resources/icc-exploration-contract.json
git commit -m "test(web): verify resilient ICC evidence flow"
```

## Task 9: Full Verification And Rollback Drill

- [ ] **Step 1: Run all suites.**

```bash
cd /path/to/Haitlify/server/web-runner
npm test
cd ../platform
./mvnw -q test
cd ../../apps/web
npm test -- --run
npm run build
```

Expected: zero failures and errors. Report existing chunk-size warnings without hiding them.

- [ ] **Step 2: Run hygiene checks.**

```bash
cd /path/to/Haitlify
git diff --check
rg -n '\[DEBUG-|force:\s*true|password|authToken|access_token|Cookie:' server/web-runner server/platform/src/main/java apps/web/src -g '*.mjs' -g '*.java' -g '*.vue' -g '*.ts' | rg -v 'test|placeholder|redacted|type|field' || true
git status --short
```

Expected: no introduced secrets, forced browser actions, raw sensitive diagnostics, or uncommitted changes.

- [ ] **Step 3: Exercise rollback flags.**

1. Disable semantic expansion and verify deterministic intent still finds the fixture target.
2. Disable multi-round exploration and verify one-round behavior with an explicit failure category.
3. Disable session reuse and verify `auth_context_lost` rather than unrestricted fallback.
4. Re-enable production-safe defaults and rerun focused suites.

- [ ] **Step 4: Perform independent review.**

Request code/security review for Origin policy, redaction, session state, and forbidden actions. Request architecture review for Platform/Runner contracts, flags, fallback behavior, compatibility, and rollback. If either lane is unavailable, record it and do not mark merge-ready.

## Acceptance Criteria

- Login validation and evidence collection share an authenticated session contract or report `auth_context_lost` precisely.
- Blocked fonts, images, favicon, or telemetry do not fail valid business evidence.
- A blocked business API or required script produces `network_blocked_critical` with safe Origin/path/resource type.
- Login succeeds after leaving the login route even when a stale success target no longer matches.
- Readiness waits distinguish `page_not_ready` from authentication failure and remain bounded.
- Repair actions change strategy and stop at five rounds/90 seconds.
- AI may understand unfamiliar wording and suggest only deterministic safe repair categories.
- Every automation assertion is backed by current browser evidence or verified knowledge.
- Duplicate controls remain non-actionable without stable scope.
- One point failure does not block later points.
- Frontend diagnostics are actionable and contain no secrets or raw Locators.
- Runner, Platform, frontend tests/build, ICC fixture acceptance, service health, source identity, and rollback checks pass.

## Rollback Procedure

1. Set `webExplorationSemanticExpansionEnabled=false`.
2. Keep criticality-aware policy enabled; remove only a problematic explicit Origin.
3. If session reuse is implicated, set `webEvidenceSessionReuseEnabled=false` and require `auth_context_lost` rather than unrestricted fallback.
4. If multi-round behavior is implicated, set `webMultiRoundExplorationEnabled=false` and preserve diagnostic readers.
5. Re-run login, critical/optional network, duplicate Locator, persistence, and ICC fixture tests.
6. Revert behavior commits only after flags provide a controlled stop; preserve backward-compatible request fields and diagnostic readers.

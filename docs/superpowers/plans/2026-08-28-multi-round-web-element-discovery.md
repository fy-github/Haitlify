> **Archival record:** Preserved historical design/evidence from its original point in time. It does not claim Hermes integration is implemented.

# Multi-Round Web Element Discovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace single-pass Web locator collection with a bounded, observable, safe multi-round explorer that finds the correct page and business element before AI compilation.

**Architecture:** The Web Runner owns a deterministic exploration state machine and all browser actions. Platform builds a structured target intent, rejects evidence below the deterministic threshold, persists successful navigation knowledge, and can request one evidence refresh from remaining budget when compilation reports a semantic mismatch. AI may rank aliases and candidates but cannot expand the browser action allowlist or lower evidence thresholds.

**Tech Stack:** Node.js 22, Playwright 1.61, Java 21, Spring Boot 4, JdbcTemplate, Flyway, Vue 3, TypeScript, Vitest, Docker Compose.

---

## File Map

- Create `server/web-runner/page-exploration-policy.mjs`: text normalization, synonym expansion, evidence scoring, safe-label classification, and deterministic next-action selection.
- Create `server/web-runner/page-exploration-policy.test.mjs`: pure policy tests.
- Create `server/web-runner/page-explorer.mjs`: budgeted exploration loop independent of Playwright DOM details.
- Create `server/web-runner/page-explorer.test.mjs`: round, timeout, retry, stagnation, and result tests using deterministic fakes.
- Modify `server/web-runner/page-locator-discovery.mjs`: Playwright observation and safe-action adapter; delegate orchestration to `page-explorer.mjs`.
- Modify `server/web-runner/page-locator-discovery.test.mjs`: browser-backed tests for hidden menus, overlays, scrolling, tabs, pagination, wrong pages, and forbidden actions.
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationLocatorDiscoveryService.java`: request/response records for target intent, budget, attempts, scores, rounds, and navigation trail.
- Create `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebExplorationIntentBuilder.java`: build structured intent from reviewed natural-language plans and draft point evidence.
- Create `server/platform/src/test/java/com/haitlify/aitest/platform/ai/draft/WebExplorationIntentBuilderTest.java`: intent extraction and generic-term rejection tests.
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java`: send exploration requests, enforce evidence gate, attach exploration reports, and persist successful evidence.
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AgentWebCaseGenerationFacade.java`: expose exploration status, remaining budget, and feedback-aware refresh.
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcher.java`: structured exploration events and one feedback-driven rediscovery before blocking a point.
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/locator/LocatorKnowledgeService.java`: persist navigation trail and exploration confidence in `metadata_json`; revalidate before reuse.
- Create `server/platform/src/main/resources/db/migration/V18__web_case_exploration_event_details.sql`: store structured event details.
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentEventService.java`: append structured details while keeping existing callers compatible.
- Modify `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentModels.java`: expose event details.
- Modify `apps/web/src/domain/webCaseAgentRepository.ts`: type exploration event details.
- Modify `apps/web/src/components/workflow/WebCaseAgentWorkspace.vue`: show current round, score, URL, action, and final reason without changing the established compact layout.
- Modify focused tests beside every changed component.

## Task 1: Pure Exploration Policy

**Files:**
- Create: `server/web-runner/page-exploration-policy.mjs`
- Create: `server/web-runner/page-exploration-policy.test.mjs`

- [ ] **Step 1: Write failing normalization and synonym tests**

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  expandIntentTerms,
  isForbiddenBusinessAction,
  normalizeIntent,
  scoreObservation
} from './page-exploration-policy.mjs'

test('normalizes page aliases and expands bounded business synonyms', () => {
  const intent = normalizeIntent({
    pageAliases: ['威胁情报业务列表页'],
    elementTerms: ['停用'],
    stateTerms: ['即将到期'],
    negativeTerms: ['项目', '服务']
  })
  assert.deepEqual(intent.pageAliases, ['威胁情报'])
  assert.ok(expandIntentTerms(intent.elementTerms).includes('关闭'))
})

test('forbids business mutations even when their labels match the target', () => {
  assert.equal(isForbiddenBusinessAction('停用'), true)
  assert.equal(isForbiddenBusinessAction('删除'), true)
  assert.equal(isForbiddenBusinessAction('取消'), false)
})

test('requires business element evidence instead of global navigation', () => {
  const score = scoreObservation(normalizeIntent({
    pageAliases: ['威胁情报'], elementTerms: ['停用'], stateTerms: ['即将到期'], negativeTerms: ['项目', '服务']
  }), {
    url: 'https://example.test/#/project', title: '项目', breadcrumbs: [], activeMenus: ['项目'], headings: [],
    elements: [{ label: '项目', role: 'button', contextText: '' }]
  })
  assert.equal(score.matched, false)
  assert.equal(score.elementScore, 0)
})
```

- [ ] **Step 2: Run tests and verify RED**

Run: `cd server/web-runner && node --test page-exploration-policy.test.mjs`

Expected: FAIL because `page-exploration-policy.mjs` does not exist.

- [ ] **Step 3: Implement the policy module**

```js
export const EXPLORATION_DEFAULTS = Object.freeze({
  maxRounds: 5,
  maxDurationMs: 90_000,
  maxSameFailureRetries: 2,
  maxLoginRetries: 1,
  maxReloads: 1,
  maxPaginationSteps: 3,
  minimumTotalScore: 70,
  minimumElementScore: 25
})

const GENERIC_TERMS = new Set(['项目', '设备', '应用', '系统', '服务', '用户', '首页', '管理', '页面', '列表'])
const FORBIDDEN_ACTIONS = ['确定', '提交', '保存', '删除', '停用', '启用', '开通', '支付', '发送', '上传']
const SYNONYMS = new Map([
  ['停用', ['停用', '关闭', '禁用']],
  ['重新开通', ['重新开通', '恢复', '启用']],
  ['删除', ['删除', '移除']]
])

export const normalizeText = (value) => String(value || '').trim().replace(/\s+/g, '').replace(/业务列表页|业务页面|列表页面|列表页|页面|模块|业务$/g, '')
export const normalizeIntent = (intent = {}) => ({
  pageAliases: [...new Set((intent.pageAliases || []).map(normalizeText).filter((term) => term.length >= 2))],
  routeHints: [...new Set(intent.routeHints || [])],
  elementTerms: [...new Set((intent.elementTerms || []).map(normalizeText).filter((term) => term.length >= 2 && !GENERIC_TERMS.has(term)))],
  actionTerms: [...new Set((intent.actionTerms || []).map(normalizeText).filter(Boolean))],
  stateTerms: [...new Set((intent.stateTerms || []).map(normalizeText).filter(Boolean))],
  expectedTexts: [...new Set((intent.expectedTexts || []).map(normalizeText).filter(Boolean))],
  negativeTerms: [...new Set([...(intent.negativeTerms || []), ...GENERIC_TERMS].map(normalizeText))]
})
export const expandIntentTerms = (terms) => [...new Set(terms.flatMap((term) => SYNONYMS.get(term) || [term]))]
export const isForbiddenBusinessAction = (label) => FORBIDDEN_ACTIONS.some((term) => normalizeText(label).includes(term))

export const scoreObservation = (intent, observation, budget = EXPLORATION_DEFAULTS) => {
  const pageSignals = [observation.url, observation.title, ...(observation.breadcrumbs || []), ...(observation.activeMenus || []), ...(observation.headings || [])].map(normalizeText)
  const pageScore = Math.min(40, pageSignals.reduce((score, signal) => score + (intent.pageAliases.some((alias) => signal.includes(alias)) ? 20 : 0), 0))
  const expandedElements = expandIntentTerms(intent.elementTerms)
  const relevantElements = (observation.elements || []).filter((element) => expandedElements.some((term) => normalizeText(`${element.label} ${element.contextText}`).includes(term)))
  const elementScore = Math.min(40, relevantElements.length * 25)
  const stateText = (observation.elements || []).map((element) => `${element.label} ${element.contextText}`).join(' ')
  const stateScore = Math.min(20, intent.stateTerms.reduce((score, term) => score + (normalizeText(stateText).includes(term) ? 10 : 0), 0))
  const totalScore = pageScore + elementScore + stateScore
  return { pageScore, elementScore, stateScore, totalScore, relevantElements, matched: totalScore >= budget.minimumTotalScore && elementScore >= budget.minimumElementScore }
}
```

- [ ] **Step 4: Run policy tests and verify GREEN**

Run: `cd server/web-runner && node --test page-exploration-policy.test.mjs`

Expected: all policy tests PASS.

- [ ] **Step 5: Commit**

```bash
git add server/web-runner/page-exploration-policy.mjs server/web-runner/page-exploration-policy.test.mjs
git commit -m "feat(web): add deterministic exploration policy"
```

## Task 2: Budgeted Exploration State Machine

**Files:**
- Create: `server/web-runner/page-explorer.mjs`
- Create: `server/web-runner/page-explorer.test.mjs`

- [ ] **Step 1: Write failing round, retry, and stagnation tests**

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { runPageExploration } from './page-explorer.mjs'

test('changes strategy until evidence matches', async () => {
  const actions = []
  const observations = [
    { url: '#/project', fingerprint: 'home', elements: [] },
    {
      url: '#/open/threat-intelligence', title: '威胁情报', headings: ['威胁情报'], fingerprint: 'target',
      elements: [{ label: '停用', contextText: '即将到期' }]
    }
  ]
  const result = await runPageExploration({
    intent: { pageAliases: ['威胁情报'], elementTerms: ['停用'], stateTerms: ['即将到期'] },
    budget: { maxRounds: 5, maxDurationMs: 90_000 }
  }, {
    observe: async () => observations.shift(),
    chooseAction: async () => ({ type: 'menu-search', target: '威胁情报' }),
    performAction: async (action) => actions.push(action),
    now: () => 1
  })
  assert.equal(result.status, 'matched')
  assert.equal(result.rounds.length, 2)
  assert.deepEqual(actions.map((action) => action.type), ['menu-search'])
})

test('stops after two unchanged observations', async () => {
  const result = await runPageExploration({ intent: { pageAliases: ['日志管理'], elementTerms: ['告警日志'] } }, {
    observe: async () => ({ url: '#/project', fingerprint: 'same', elements: [] }),
    chooseAction: async () => ({ type: 'scroll-page' }),
    performAction: async () => {},
    now: () => 1
  })
  assert.equal(result.status, 'exhausted')
  assert.equal(result.failureReason, 'observation_stagnated')
})
```

- [ ] **Step 2: Run tests and verify RED**

Run: `cd server/web-runner && node --test page-explorer.test.mjs`

Expected: FAIL because `runPageExploration` does not exist.

- [ ] **Step 3: Implement the state machine**

```js
import { EXPLORATION_DEFAULTS, normalizeIntent, scoreObservation } from './page-exploration-policy.mjs'

export const runPageExploration = async (request, adapter) => {
  const intent = normalizeIntent(request.intent)
  const budget = { ...EXPLORATION_DEFAULTS, ...(request.budget || {}) }
  const startedAt = adapter.now()
  const rounds = []
  let unchangedRounds = 0
  let lastSignature = ''
  const sameFailureCounts = new Map()

  for (let round = 1; round <= budget.maxRounds; round += 1) {
    if (adapter.now() - startedAt >= budget.maxDurationMs) return { status: 'exhausted', rounds, failureReason: 'duration_exhausted' }
    const observation = await adapter.observe()
    const scores = scoreObservation(intent, observation, budget)
    const signature = `${observation.url}|${observation.fingerprint}|${scores.relevantElements.map((item) => item.locator || item.label).join(',')}`
    unchangedRounds = signature === lastSignature ? unchangedRounds + 1 : 0
    lastSignature = signature
    const entry = { round, observation, scores }
    rounds.push(entry)
    if (scores.matched) return { status: 'matched', rounds, finalObservation: observation, scores, navigationTrail: adapter.navigationTrail?.() || [] }
    if (unchangedRounds >= 2) return { status: 'exhausted', rounds, failureReason: 'observation_stagnated' }
    const action = await adapter.chooseAction({ intent, budget, rounds, observation, scores })
    if (!action) return { status: 'blocked', rounds, failureReason: 'no_safe_action' }
    const failureKey = action.failureType || action.type
    const count = (sameFailureCounts.get(failureKey) || 0) + 1
    sameFailureCounts.set(failureKey, count)
    if (count > budget.maxSameFailureRetries) return { status: 'exhausted', rounds, failureReason: 'same_failure_limit' }
    entry.action = action
    await adapter.performAction(action)
  }
  return { status: 'exhausted', rounds, failureReason: 'round_budget_exhausted' }
}
```

- [ ] **Step 4: Run state-machine tests and verify GREEN**

Run: `cd server/web-runner && node --test page-explorer.test.mjs`

Expected: all state-machine tests PASS.

- [ ] **Step 5: Commit**

```bash
git add server/web-runner/page-explorer.mjs server/web-runner/page-explorer.test.mjs
git commit -m "feat(web): add bounded page exploration state machine"
```

## Task 3: Playwright Observation and Safe Corrections

**Files:**
- Modify: `server/web-runner/page-locator-discovery.mjs`
- Modify: `server/web-runner/page-locator-discovery.test.mjs`

- [ ] **Step 1: Add failing browser-backed scenarios**

Add this fixture helper and browser-backed tests:

```js
const discoverFixture = async (context, html, overrides = {}) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(html)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  return discoverPageLocators({
    url: `http://127.0.0.1:${address.port}/#/project`,
    baseUrl: `http://127.0.0.1:${address.port}`,
    browser: 'chromium', viewport: '900x600', headless: true,
    targetIntent: {
      pageAliases: ['威胁情报'], elementTerms: ['停用'], stateTerms: ['即将到期'],
      negativeTerms: ['项目', '服务']
    },
    budget: { maxRounds: 5, maxDurationMs: 90_000, maxSameFailureRetries: 2, maxPaginationSteps: 3, minimumTotalScore: 70, minimumElementScore: 25 },
    ...overrides
  }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) })
}

test('dismisses only safe overlays before matching a target', async (context) => {
  const snapshot = await discoverFixture(context, `<!doctype html>
    <h1>威胁情报</h1><div>即将到期</div><button id="target">停用</button>
    <div role="dialog" id="overlay"><button id="cancel">取消</button><button id="confirm">确定</button></div>
    <output id="mutations">0</output>
    <script>
      cancel.onclick = () => overlay.remove()
      confirm.onclick = () => mutations.textContent = '1'
    </script>`)
  assert.equal(snapshot.exploration.status, 'matched')
  assert.equal(snapshot.exploration.rounds.some((round) => round.action?.type === 'dismiss-overlay'), true)
  assert.equal(snapshot.elements.some((element) => element.label === '停用'), true)
  assert.equal(snapshot.elements.some((element) => element.label === '1'), false)
})

test('scrolls a container and scopes a duplicate action to the target state row', async (context) => {
  const snapshot = await discoverFixture(context, `<!doctype html><h1>威胁情报</h1>
    <div style="height:100px;overflow:auto" id="list"><div style="height:180px"></div>
      <table><tr><td>有效期</td><td><button>停用</button></td></tr>
      <tr id="expiring"><td>即将到期</td><td><button>停用</button></td></tr></table></div>`)
  const target = snapshot.elements.find((element) => element.label === '停用' && element.contextText.includes('即将到期'))
  assert.ok(target)
  assert.match(target.locator, /expiring|nth-of-type/)
})

test('tries a safe tab then pagination without clicking business actions', async (context) => {
  const snapshot = await discoverFixture(context, `<!doctype html><h1>威胁情报</h1>
    <button role="tab" id="expired-tab">已过期</button><main id="panel"></main><output id="mutations">0</output>
    <button id="delete">删除</button><script>
      expiredTab.onclick = () => panel.innerHTML = '<div>即将到期</div><button>停用</button>'
      delete.onclick = () => mutations.textContent = '1'
    </script>`)
  assert.equal(snapshot.exploration.status, 'matched')
  assert.equal(snapshot.exploration.rounds.some((round) => round.action?.type === 'switch-tab'), true)
  assert.equal(snapshot.elements.some((element) => element.label === '1'), false)
})

test('blocks when an overlay can only be closed by a mutation confirmation', async (context) => {
  const snapshot = await discoverFixture(context, `<!doctype html><h1>威胁情报</h1>
    <div role="dialog"><p>是否停用业务？</p><button>确定停用</button></div>`)
  assert.equal(snapshot.exploration.status, 'blocked')
  assert.equal(snapshot.exploration.failureReason, 'unsafe_overlay')
})
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run: `cd server/web-runner && node --test --test-name-pattern='safe overlays|scopes a duplicate|safe tab|unsafe overlay' page-locator-discovery.test.mjs`

Expected: FAIL because the current discovery performs only one navigation correction and one observation.

- [ ] **Step 3: Replace single-pass correction with the explorer adapter**

Implement these adapter functions in `page-locator-discovery.mjs` and pass them to `runPageExploration`:

```js
const observePage = async (page) => ({
  url: page.url(),
  title: await page.title(),
  fingerprint: await page.evaluate(() => `${location.href}|${document.body.innerText.length}|${document.querySelectorAll('a,button,input,[role]').length}`),
  breadcrumbs: await visibleTexts(page, '[aria-label="Breadcrumb"], .ant-breadcrumb, .el-breadcrumb'),
  activeMenus: await visibleTexts(page, '[aria-current], [role="menuitem"][aria-selected="true"], .ant-menu-item-selected, .is-active'),
  headings: await visibleTexts(page, 'h1,h2,h3,[role="heading"]'),
  overlays: await collectOverlays(page),
  elements: await collectVisibleElements(page, { includeContext: true })
})

const chooseSafeAction = async ({ intent, budget, rounds, observation }) => {
  if (observation.overlays.some((item) => item.safeDismissLocator)) return { type: 'dismiss-overlay', locator: observation.overlays.find((item) => item.safeDismissLocator).safeDismissLocator }
  if (!rounds.some((item) => item.action?.type === 'direct-navigation')) return { type: 'direct-navigation', target: intent.pageAliases[0] }
  if (!rounds.some((item) => item.action?.type === 'menu-search')) return { type: 'menu-search', target: intent.pageAliases[0] }
  if (!rounds.some((item) => item.action?.type === 'scroll-page')) return { type: 'scroll-page' }
  const tab = observation.elements.find((item) => item.role === 'tab' && !isForbiddenBusinessAction(item.label))
  if (tab) return { type: 'switch-tab', locator: tab.locator }
  const nextPage = observation.elements.find((item) => item.semanticType === 'next-page' && item.enabled)
  if (nextPage && rounds.filter((item) => item.action?.type === 'paginate').length < budget.maxPaginationSteps) return { type: 'paginate', locator: nextPage.locator }
  return undefined
}
```

`performAction` must reject any non-allowlisted action type and reject labels matching `isForbiddenBusinessAction`. It must never pass `{ force: true }`.

- [ ] **Step 4: Return backward-compatible exploration data**

```js
const exploration = await runPageExploration({ intent: input.targetIntent || { pageAliases: input.navigationHints }, budget: input.budget }, adapter)
return {
  pageUrl: exploration.finalObservation?.url || page.url(),
  elements: exploration.finalObservation?.elements || await collectVisibleElements(page),
  exploration
}
```

- [ ] **Step 5: Run Web Runner tests**

Run: `cd server/web-runner && npm test`

Expected: all tests PASS and mutation counters remain zero.

- [ ] **Step 6: Commit**

```bash
git add server/web-runner/page-locator-discovery.mjs server/web-runner/page-locator-discovery.test.mjs
git commit -m "feat(web): explore pages with safe corrective actions"
```

## Task 4: Platform Target Intent and Exploration Contract

**Files:**
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationLocatorDiscoveryService.java`
- Create: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebExplorationIntentBuilder.java`
- Create: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/draft/WebExplorationIntentBuilderTest.java`
- Modify: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointReviewTest.java`

- [ ] **Step 1: Write failing intent builder tests**

```java
@Test
void buildsBusinessFocusedIntentFromReviewedPlanAndSourcePoint() {
    JsonNode plan = objectMapper.readTree("""
        {"title":"即将到期免费体验版显示停用按钮","module":"威胁情报业务","pageName":"威胁情报业务列表页",
         "naturalSteps":"进入列表页\\n找到即将到期记录\\n检查停用按钮","naturalExpectedResult":"操作列展示停用按钮"}
        """);
    var intent = new WebExplorationIntentBuilder().build(plan, Map.of(
        "TITLE", "即将到期免费体验版显示停用按钮",
        "TEST_OBJECTIVE", "验证即将到期记录允许停用"
    ));
    assertThat(intent.pageAliases()).contains("威胁情报业务列表页", "威胁情报业务");
    assertThat(intent.elementTerms()).contains("停用");
    assertThat(intent.stateTerms()).contains("即将到期");
    assertThat(intent.negativeTerms()).contains("项目", "服务");
}
```

- [ ] **Step 2: Run test and verify RED**

Run: `cd server/platform && ./mvnw -q -Dtest=WebExplorationIntentBuilderTest test`

Expected: FAIL because the builder and request records do not exist.

- [ ] **Step 3: Add request and response records**

In `WebAutomationLocatorDiscoveryService.java` define:

```java
public record TargetIntent(
    List<String> pageAliases,
    List<String> routeHints,
    List<String> elementTerms,
    List<String> actionTerms,
    List<String> stateTerms,
    List<String> expectedTexts,
    List<String> negativeTerms
) { }

public record ExplorationBudget(
    int maxRounds,
    long maxDurationMs,
    int maxSameFailureRetries,
    int maxLoginRetries,
    int maxReloads,
    int maxPaginationSteps,
    int minimumTotalScore,
    int minimumElementScore
) { }

public record ExplorationScores(int pageScore, int elementScore, int stateScore, int totalScore, boolean matched) { }
public record ExplorationRound(int round, String pageUrl, String fingerprint, String actionType, String actionTarget, ExplorationScores scores) { }
public record ExplorationReport(String status, List<ExplorationRound> rounds, ExplorationScores scores, List<String> navigationTrail, String failureReason, long durationMs) { }
```

Extend `PageLocatorDiscoveryRequest` with `TargetIntent targetIntent`, `ExplorationBudget budget`, and `List<String> previousAttempts`. Extend `PageLocatorDiscoveryResponse` with `ExplorationReport exploration`. Keep existing fields and tolerate `null` new fields.

- [ ] **Step 4: Implement the intent builder**

```java
final class WebExplorationIntentBuilder {
    private static final List<String> NEGATIVE_TERMS = List.of("项目", "设备", "应用", "系统", "服务", "用户", "首页", "管理");
    private static final List<String> ELEMENT_KEYWORDS = List.of("停用", "关闭", "删除", "重新开通", "按钮", "Tab", "页签", "字段", "文案", "提示");
    private static final List<String> STATE_KEYWORDS = List.of("有效期", "即将到期", "已过期", "已停用", "成功", "失败", "离线", "在线");

    WebAutomationLocatorDiscoveryService.TargetIntent build(JsonNode plan, Map<String, Object> source) {
        String semanticText = Stream.of(
            plan.path("title").asText(""), plan.path("objective").asText(""), plan.path("naturalSteps").asText(""),
            plan.path("naturalExpectedResult").asText(""), String.valueOf(source.getOrDefault("TITLE", "")),
            String.valueOf(source.getOrDefault("TEST_OBJECTIVE", "")), String.valueOf(source.getOrDefault("EXPECTED_RESULT", ""))
        ).collect(Collectors.joining(" "));
        return new WebAutomationLocatorDiscoveryService.TargetIntent(
            distinct(plan.path("pageName").asText(""), plan.path("module").asText("")),
            distinct(plan.path("startUrl").asText("")),
            presentKeywords(semanticText, ELEMENT_KEYWORDS),
            List.of(),
            presentKeywords(semanticText, STATE_KEYWORDS),
            distinct(plan.path("naturalExpectedResult").asText("")),
            NEGATIVE_TERMS
        );
    }
}
```

- [ ] **Step 5: Pass the contract from `AiDraftTestPointService`**

Create one builder per service and construct the default budget exactly as approved:

```java
new ExplorationBudget(5, 90_000, 2, 1, 1, 3, 70, 25)
```

Update `AiDraftTestPointReviewTest` to capture the discovery request and assert target page, `停用`, `即将到期`, and the budget values.

- [ ] **Step 6: Run platform tests**

Run: `cd server/platform && ./mvnw -q -Dtest=WebExplorationIntentBuilderTest,AiDraftTestPointReviewTest test`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationLocatorDiscoveryService.java server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebExplorationIntentBuilder.java server/platform/src/test/java/com/haitlify/aitest/platform/ai/draft/WebExplorationIntentBuilderTest.java server/platform/src/test/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointReviewTest.java
git commit -m "feat(ai): send structured web exploration intent"
```

## Task 5: Evidence Gate and Feedback-Driven Rediscovery

**Files:**
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java`
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AgentWebCaseGenerationFacade.java`
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcher.java`
- Modify: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcherTest.java`
- Modify: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointReviewTest.java`

- [ ] **Step 1: Write failing evidence-gate tests**

Add test helpers that build `PageLocatorDiscoveryResponse` values with explicit reports, then add these tests:

```java
private WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse discoveryResponse(
    String status, int totalScore, int elementScore, String failureReason
) {
    var scores = new WebAutomationLocatorDiscoveryService.ExplorationScores(20, elementScore, 0, totalScore, "matched".equals(status));
    var report = new WebAutomationLocatorDiscoveryService.ExplorationReport(
        status, List.of(), scores, List.of(), failureReason, 1_000
    );
    return new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse(
        "https://test.example/#/project", List.of(), report
    );
}

private AgentWebCaseGenerationFacade.BrowserEvidenceBundle evidenceBundle(
    String status, int rounds, long remainingDurationMs
) {
    return new AgentWebCaseGenerationFacade.BrowserEvidenceBundle(
        objectMapper.createArrayNode(), List.of("locator-1"), 1, 1, true,
        status, rounds, 80, remainingDurationMs
    );
}

private AgentWebCaseGenerationFacade.GeneratedWebCase generatedCase(String pointId) {
    return new AgentWebCaseGenerationFacade.GeneratedWebCase(
        pointId, "停用即将到期业务", "威胁情报", "web_automation", "威胁情报列表页", "/open/threat-intelligence",
        "chromium", "1440x900", "已登录", "验证停用按钮", "assertVisible text=停用", "assertVisible text=停用",
        "即将到期记录展示停用按钮", "进入威胁情报页面\\n查看即将到期记录", "操作列展示停用按钮", "requirements.txt"
    );
}

private WebCaseAgentModels.WebCaseAgentStartRequest compileRequest(String pointId) {
    return new WebCaseAgentModels.WebCaseAgentStartRequest(
        1L, "analysis-1", List.of(pointId), "env-1", "https://test.example/", "chromium", "1440x900",
        null, null, WebCaseAgentModels.WebCaseAgentMode.COMPILE, "plan-run-1"
    );
}

@Test
void rejectsUnmatchedExplorationBeforeGroundedCompilation() throws Exception {
    insertDraft("point-unmatched", "confirmed", "web_automation", "即将到期记录显示停用按钮");
    when(webAutomationLocatorDiscoveryService.discover(any()))
        .thenReturn(discoveryResponse("exhausted", 45, 0, "round_budget_exhausted"));
    when(aiGateway.structuredGenerate(any(), any())).thenReturn(new AiGenerationResult(true,
        "{\"testCases\":[{\"sourcePointId\":\"point-unmatched\",\"module\":\"威胁情报\",\"pageName\":\"威胁情报列表页\",\"startUrl\":\"/\"}]}",
        "plan-request", 1, 1, null));

    mockMvc.perform(post("/api/ai/draft-test-points/generate-cases")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"pointIds\":[\"point-unmatched\"],\"locatorDiscovery\":{\"baseUrl\":\"https://test.example/\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("round_budget_exhausted")));
    verify(aiGateway, times(1)).structuredGenerate(any(), any());
}

@Test
void retriesDiscoveryOnceWithCompilerSemanticFeedbackAndRemainingBudget() {
    when(generator.resolveEvidence(eq(List.of("point-1")), any(), any()))
        .thenReturn(evidenceBundle("matched", 2, 80_000));
    when(generator.compile(eq(List.of("point-1")), any(), any(), any(), any(), any()))
        .thenThrow(new IllegalArgumentException("执行步骤中的元素断言未命中自然语言步骤/预期中的目标元素"))
        .thenReturn(List.of(generatedCase("point-1")));
    when(generator.resolveEvidence(eq(List.of("point-1")), any(), any(), any(), eq(80_000L)))
        .thenReturn(evidenceBundle("matched", 3, 30_000));

    dispatcher.dispatch("run-1", compileRequest("point-1"));

    verify(generator, timeout(1000)).resolveEvidence(
        eq(List.of("point-1")), any(), any(),
        eq(List.of("执行步骤中的元素断言未命中自然语言步骤/预期中的目标元素")), eq(80_000L)
    );
    verify(runService, timeout(1000)).markPointCompleted("run-1", "point-1");
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `cd server/platform && ./mvnw -q -Dtest=AiDraftTestPointReviewTest,WebCaseAgentDispatcherTest test`

Expected: FAIL because unmatched evidence currently reaches AI and compilation failures block immediately.

- [ ] **Step 3: Enforce the exploration gate**

In `collectBrowserLocatorEvidence`, attach the report and reject non-matched reports:

```java
if (snapshot.exploration() != null && !"matched".equals(snapshot.exploration().status())) {
    throw new WebExplorationBlockedException(
        pointId,
        snapshot.exploration().failureReason(),
        snapshot.exploration()
    );
}
page.set("exploration", objectMapper.valueToTree(snapshot.exploration()));
```

Create `WebExplorationBlockedException` as a package-private exception with `pointId` and `ExplorationReport` accessors so the dispatcher can log structured details.

- [ ] **Step 4: Expose report and remaining budget through the facade**

Extend `BrowserEvidenceBundle`:

```java
public record BrowserEvidenceBundle(
    JsonNode rawEvidence,
    List<String> knowledgeHitIds,
    int pageCount,
    int elementCount,
    boolean usedBrowserDiscovery,
    String explorationStatus,
    int explorationRounds,
    int totalScore,
    long remainingDurationMs
) { }
```

Add an overload:

```java
public BrowserEvidenceBundle resolveEvidence(
    List<String> pointIds,
    NaturalWebCasePlanBatch plan,
    BrowserDiscoveryOptions discovery,
    List<String> previousAttempts,
    Long remainingDurationMs
)
```

- [ ] **Step 5: Rediscover once on semantic mismatch**

In the dispatcher compilation catch, detect messages containing `元素断言未命中` or `不能只使用 assertUrl`. If `remainingDurationMs > 5_000`, call the overload once with the validation message, then compile with the new bundle. Never retry authentication, provider errors, cancellation, or malformed JSON through this path.

```java
if (isSemanticEvidenceMismatch(message) && evidenceBundle.remainingDurationMs() > 5_000) {
    var refreshed = generator.resolveEvidence(
        List.of(pointId), plan, discoveryOptions(request), List.of(message), evidenceBundle.remainingDurationMs()
    );
    var regenerated = generator.compile(List.of(pointId), plan, refreshed, discoveryOptions(request), runService.ruleSnapshot(runId), memoryContext);
    runService.saveArtifacts(runId, regenerated, refreshed.knowledgeHitIds());
    runService.markPointCompleted(runId, pointId);
    continue;
}
```

- [ ] **Step 6: Run focused tests**

Run: `cd server/platform && ./mvnw -q -Dtest=AiDraftTestPointReviewTest,WebCaseAgentDispatcherTest test`

Expected: PASS, with exactly one rediscovery and no retry for unrelated failures.

- [ ] **Step 7: Commit**

```bash
git add server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AgentWebCaseGenerationFacade.java server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcher.java server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcherTest.java server/platform/src/test/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointReviewTest.java
git commit -m "feat(ai): gate compilation on matched browser evidence"
```

## Task 6: Persist Navigation Knowledge and Revalidate It

**Files:**
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/locator/LocatorKnowledgeService.java`
- Modify: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/locator/LocatorKnowledgeServiceTest.java`
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java`

- [ ] **Step 1: Write failing persistence tests**

```java
@Test
void storesSuccessfulRouteTrailAndExplorationScoreInMetadata() {
    service.recordAgentEvidence(1L, "env-1", "威胁情报业务", "威胁情报业务列表页",
        "https://example.test/#/open/threat-intelligence", elements,
        new ExplorationKnowledge(List.of("menu-search:威胁情报"), 85, 2, "matched"));
    String metadata = jdbcTemplate.queryForObject("select metadata_json from ai_locator_knowledge_entries where entry_id = ?", String.class, entryId);
    assertThat(metadata).contains("menu-search:威胁情报", "85", "matched");
}

@Test
void doesNotReuseKnowledgeWhenItsPageFingerprintNoLongerMatches() {
    insertKnowledge("locator-stale", "sha256:old", "active");
    when(discoveryService.discover(any())).thenReturn(new PageLocatorDiscoveryResponse(
        "https://example.test/#/logs",
        List.of(new PageLocatorEvidence("new", "text=新页面", "新页面", "heading", "h1", "", "text")),
        null
    ));
    assertThatThrownBy(() -> service.revalidate("locator-stale", discoveryRequest()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(jdbcTemplate.queryForObject(
        "select status from ai_locator_knowledge_entries where entry_id='locator-stale'", String.class
    )).isEqualTo("stale");
    assertThat(service.searchEvidence(1L, "env-1", "日志管理", "日志页面", "告警日志")).isEmpty();
}

private void insertKnowledge(String entryId, String fingerprint, String status) {
    Instant now = Instant.now();
    jdbcTemplate.update("""
        insert into ai_locator_knowledge_entries(
          entry_id, project_id, environment_id, module_name, page_name, page_url, page_fingerprint,
          natural_name, locator, role_name, accessible_name, element_type, source, status,
          evidence_version, collected_at, last_verified_at, metadata_json, created_at, updated_at
        ) values (?, 1, 'env-1', '日志管理', '日志页面', 'https://example.test/#/logs', ?,
          '告警日志', 'text=告警日志', 'tab', '告警日志', 'tab', 'browser', ?, 1, ?, ?, '{}', ?, ?)
        """, entryId, fingerprint, status, now, now, now, now);
}

private WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest discoveryRequest() {
    return new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest(
        "https://example.test/#/logs", "https://example.test/", "chromium", "1440x900", true, 10_000,
        "domcontentloaded", null, Map.of(), List.of(), null, List.of("日志管理"),
        new TargetIntent(List.of("日志管理"), List.of("/logs"), List.of("告警日志"), List.of(), List.of(), List.of(), List.of()),
        new ExplorationBudget(5, 90_000, 2, 1, 1, 3, 70, 25), List.of()
    );
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `cd server/platform && ./mvnw -q -Dtest=LocatorKnowledgeServiceTest test`

Expected: FAIL because `recordAgentEvidence` does not accept exploration knowledge.

- [ ] **Step 3: Add exploration metadata without a schema migration**

Use the existing `metadata_json` column. Define:

```java
public record ExplorationKnowledge(
    List<String> navigationTrail,
    int totalScore,
    int rounds,
    String status
) { }
```

Serialize it through `ObjectMapper`, update it on existing rows, and insert it for new rows. Do not store credentials, headers, cookies, source documents, raw DOM, or screenshots.

- [ ] **Step 4: Prefer and revalidate high-confidence route knowledge**

Update `searchEvidence` ordering to prefer `last_verified_at`, confidence, and matching environment. When page fingerprint changes or two execution failures occur, set `status='stale'`; the next request must fall through to browser exploration.

- [ ] **Step 5: Run tests**

Run: `cd server/platform && ./mvnw -q -Dtest=LocatorKnowledgeServiceTest,AiDraftTestPointReviewTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/locator/LocatorKnowledgeService.java server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/locator/LocatorKnowledgeServiceTest.java server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java
git commit -m "feat(ai): persist verified web navigation knowledge"
```

## Task 7: Structured Exploration Observability

**Files:**
- Create: `server/platform/src/main/resources/db/migration/V18__web_case_exploration_event_details.sql`
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentEventService.java`
- Modify: `server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentModels.java`
- Modify: `server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentEventServiceTest.java`
- Modify: `apps/web/src/domain/webCaseAgentRepository.ts`
- Modify: `apps/web/src/domain/webCaseAgentRepository.test.ts`
- Modify: `apps/web/src/components/workflow/WebCaseAgentWorkspace.vue`
- Modify: `apps/web/src/components/workflow/WebCaseAgentWorkspace.test.ts`

- [ ] **Step 1: Add the event details migration**

```sql
ALTER TABLE ai_web_case_agent_events
    ADD COLUMN details_json TEXT;
```

- [ ] **Step 2: Write failing backend event details tests**

```java
@Test
void persistsStructuredExplorationDetailsWithoutExpandingTheSummary() {
    var details = objectMapper.readTree("""
        {"round":2,"maxRounds":5,"pageUrl":"https://example.test/#/open/threat-intelligence",
         "totalScore":85,"actionType":"menu-search","failureReason":""}
        """);
    service.appendDetailed("run-1", EVIDENCE, TOOL_RESULT, "info", "第 2/5 轮，评分 85", "point-1",
        "browser-exploration", 100, 1, List.of(), null, details);
    assertThat(service.list("run-1", 0).getFirst().details().path("totalScore").asInt()).isEqualTo(85);
}
```

- [ ] **Step 3: Add a backward-compatible detailed append method**

Keep the current `append(...)` signature and delegate to:

```java
public WebCaseAgentEventResponse appendDetailed(
    String runId, WebCaseAgentStage stage, WebCaseAgentEventType type, String severity,
    String summary, String pointId, String toolName, long durationMs, Integer ruleVersion,
    List<String> knowledgeHitIds, String errorCode, JsonNode details
)
```

Store `details_json`; read malformed or missing details as an empty object. Continue applying `normalizeSummary` to the compact summary.

- [ ] **Step 4: Emit one event per exploration round**

The dispatcher should emit summaries like `第 2/5 轮 · 页面 35 · 元素 25 · 总分 70 · 菜单搜索“威胁情报”`, with full URL, fingerprint, candidate count, action, and failure reason in `details_json`.

- [ ] **Step 5: Write failing frontend rendering tests**

```ts
it('shows exploration round, score and page without expanding the compact workspace', () => {
  const event = makeEvent({
    summary: '第 2/5 轮 · 总分 85',
    details: { round: 2, maxRounds: 5, totalScore: 85, pageUrl: 'https://example.test/#/open/threat-intelligence', actionType: 'menu-search' }
  })
  const wrapper = mountWorkspace({ events: [event] })
  expect(wrapper.text()).toContain('探索 2/5')
  expect(wrapper.text()).toContain('评分 85')
  expect(wrapper.text()).toContain('菜单搜索')
})
```

- [ ] **Step 6: Implement compact UI fields**

Extend `WebCaseAgentEvent`:

```ts
export interface WebExplorationEventDetails {
  round?: number
  maxRounds?: number
  pageUrl?: string
  pageScore?: number
  elementScore?: number
  stateScore?: number
  totalScore?: number
  actionType?: string
  actionTarget?: string
  failureReason?: string
}
```

Show only the latest exploration round in the meta row. Keep full details in the existing event timeline button title/tooltip; do not add nested cards or resize the stage indicator.

- [ ] **Step 7: Run backend and frontend tests**

Run: `cd server/platform && ./mvnw -q -Dtest=WebCaseAgentEventServiceTest,WebCaseAgentSchemaMigrationTest test`

Run: `cd apps/web && npm test -- --run src/domain/webCaseAgentRepository.test.ts src/components/workflow/WebCaseAgentWorkspace.test.ts`

Expected: all tests PASS.

- [ ] **Step 8: Commit**

```bash
git add server/platform/src/main/resources/db/migration/V18__web_case_exploration_event_details.sql server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentEventService.java server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentModels.java server/platform/src/test/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentEventServiceTest.java apps/web/src/domain/webCaseAgentRepository.ts apps/web/src/domain/webCaseAgentRepository.test.ts apps/web/src/components/workflow/WebCaseAgentWorkspace.vue apps/web/src/components/workflow/WebCaseAgentWorkspace.test.ts
git commit -m "feat(web): expose multi-round exploration progress"
```

## Task 8: End-to-End Verification and Deployment

**Files:**
- Modify only if verification finds a defect in files already owned by Tasks 1-7.

- [ ] **Step 1: Run all Web Runner tests**

Run: `cd server/web-runner && npm test`

Expected: all tests PASS, including direct navigation, hidden menu search, overlays, scrolling, tabs, pagination, stagnation, timeout, and forbidden actions.

- [ ] **Step 2: Run all focused platform tests**

Run:

```bash
cd server/platform
./mvnw -q -Dtest=AiDraftTestPointReviewTest,WebCaseAgentDispatcherTest,WebCaseAgentEventServiceTest,LocatorKnowledgeServiceTest,WebCaseAgentSchemaMigrationTest test
```

Expected: all tests PASS.

- [ ] **Step 3: Run frontend tests and build**

Run:

```bash
cd apps/web
npm test -- --run src/domain/webCaseAgentRepository.test.ts src/components/workflow/WebCaseAgentWorkspace.test.ts src/components/workflow/AutomationCompileStage.test.ts
npm run build
```

Expected: tests PASS and Vite build completes without TypeScript errors.

- [ ] **Step 4: Rebuild services**

Run from repository root:

```bash
docker compose build web-runner platform web
docker compose up -d web-runner platform web
```

Expected: all three services start and remain running.

- [ ] **Step 5: Verify service health and deployed source**

Run:

```bash
curl --fail http://127.0.0.1:19323/health
curl --fail http://127.0.0.1:38081/actuator/health
curl --fail http://127.0.0.1:38080/web-cases
docker exec ai-test-platform-web-runner grep -n 'runPageExploration' /app/page-locator-discovery.mjs
```

Expected: runner and platform report `UP`, Web returns HTTP 200, and the running runner contains the explorer integration.

- [ ] **Step 6: Execute one controlled real-page exploration**

Use the configured development Web environment and the confirmed natural-language plan for `point-dbe244b9-1e20-4192-aecc-25c5dc00296c`. Do not execute the compiled business steps. Verify the evidence stage only:

- navigation trail reaches `#/open/threat-intelligence`;
- report status is `matched`;
- the relevant evidence is scoped to the intended status row when such a row exists;
- no mutation action appears in the trail;
- if the required business state does not exist, the point ends as `exhausted` with an explicit reason rather than generating a global-navigation assertion.

- [ ] **Step 7: Final hygiene check**

Run:

```bash
git diff --check
rg -n '\[DEBUG-' server apps || true
git status --short
```

Expected: no whitespace errors, no debug instrumentation, and no uncommitted changes outside the task.

- [ ] **Step 8: Final integration commit if verification required fixes**

If Task 8 required code changes, commit only those verified fixes:

Stage only the files owned by Tasks 1-7 that changed during verification, using this explicit command:

```bash
git add server/web-runner/page-exploration-policy.mjs server/web-runner/page-exploration-policy.test.mjs server/web-runner/page-explorer.mjs server/web-runner/page-explorer.test.mjs server/web-runner/page-locator-discovery.mjs server/web-runner/page-locator-discovery.test.mjs server/platform/src/main/java/com/haitlify/aitest/platform/webautomation/WebAutomationLocatorDiscoveryService.java server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/WebExplorationIntentBuilder.java server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AiDraftTestPointService.java server/platform/src/main/java/com/haitlify/aitest/platform/ai/draft/AgentWebCaseGenerationFacade.java server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentDispatcher.java server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/locator/LocatorKnowledgeService.java server/platform/src/main/resources/db/migration/V18__web_case_exploration_event_details.sql server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentEventService.java server/platform/src/main/java/com/haitlify/aitest/platform/ai/agentic/WebCaseAgentModels.java apps/web/src/domain/webCaseAgentRepository.ts apps/web/src/components/workflow/WebCaseAgentWorkspace.vue
git commit -m "fix(web): harden multi-round exploration verification"
```

If no changes were required, do not create an empty commit.

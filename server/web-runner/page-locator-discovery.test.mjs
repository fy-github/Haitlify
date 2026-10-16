import test from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import { randomUUID } from 'node:crypto'
import { chromium } from 'playwright'
import { __testables as pageLocatorDiscoveryTestables, classifyRequestCriticality, discoverPageLocators, waitForPageReadiness } from './page-locator-discovery.mjs'
import { executeStep } from './runner-core.mjs'
import { observePageEvidence } from './page-evidence.mjs'
import { createHandoff, createSession, handoffCount, sessionCount } from './session-manager.mjs'

test('classifies non-auth same-origin API 404 responses as optional diagnostics', () => {
  const isOptionalNotFound = pageLocatorDiscoveryTestables.isOptionalNotFoundResponse

  assert.equal(isOptionalNotFound({
    status: 404,
    criticality: 'critical',
    resourceType: 'xhr',
    url: 'https://app-dev.example.test/api/v3/operational_activities',
    loginUrl: 'https://api.app-dev.example.test/v4/uc/n/auth/login',
    observedBusinessSignals: []
  }), true)
  assert.equal(isOptionalNotFound({
    status: 404,
    criticality: 'auth_dependency',
    resourceType: 'xhr',
    url: 'https://api.app-dev.example.test/v4/uc/n/auth/login',
    loginUrl: 'https://api.app-dev.example.test/v4/uc/n/auth/login',
    observedBusinessSignals: []
  }), false)
  assert.equal(isOptionalNotFound({
    status: 404,
    criticality: 'critical',
    resourceType: 'fetch',
    url: 'https://app-dev.example.test/api/v3/users',
    loginUrl: 'https://api.app-dev.example.test/v4/uc/n/auth/login',
    observedBusinessSignals: ['/api/v3/users']
  }), false)
})

const createCaptchaResumeBrowser = (options = {}) => {
  const state = {
    mode: 'captcha',
    failureCategory: options.failureCategory || 'captcha_required',
    launches: 0,
    contextClosed: 0,
    pageClosed: 0,
    browserClosed: 0,
    evaluateCount: 0,
    now: 0
  }
  const listenerBuckets = new Map()
  const routeBuckets = []
  const readinessState = {
    pageKey: 'https://example.test/#/captcha',
    title: '项目管理',
    headings: ['项目管理'],
    controlTexts: ['项目管理'],
    controlRecords: [{ label: '项目管理', role: 'button', tagName: 'button', pageScope: 'main', rowContext: '' }],
    targetSignals: ['项目管理'],
    loginShell: false,
    errorPage: false,
    emptyShell: false,
    readyMarker: false,
    captchaState: ''
  }
  const captchaObservation = {
    url: 'https://example.test/#/captcha',
    title: '登录',
    headings: ['登录'],
    breadcrumbs: [],
    activeMenus: [],
    tabs: [],
    scrollContainers: 0,
    hasNextPage: false,
    hasInactiveTab: false,
    hasNavigation: false,
    unsupported: { iframes: false, shadowDom: false },
    fingerprint: 'captcha-observation'
  }
  const readyObservation = {
    url: 'https://example.test/#/project',
    title: '项目管理',
    headings: ['项目管理'],
    stateTexts: ['项目管理'],
    breadcrumbs: [],
    activeMenus: [],
    tabs: [],
    scrollContainers: 0,
    hasNextPage: false,
    hasInactiveTab: false,
    hasNavigation: false,
    unsupported: { iframes: false, shadowDom: false },
    fingerprint: 'ready-observation'
  }
  const page = {
    on: (event, handler) => {
      const bucket = listenerBuckets.get(event) || []
      bucket.push(handler)
      listenerBuckets.set(event, bucket)
    },
    off: (event, handler) => {
      const bucket = listenerBuckets.get(event) || []
      listenerBuckets.set(event, bucket.filter((candidate) => candidate !== handler))
    },
    listenerCount: (event) => (listenerBuckets.get(event) || []).length,
    setDefaultTimeout: () => {},
    setDefaultNavigationTimeout: () => {},
    goto: async (url) => { page.currentUrl = String(url || page.currentUrl) },
    url: () => page.currentUrl,
    waitForTimeout: async (ms) => { state.now += Number(ms || 0) },
    evaluate: async (fn) => {
      state.evaluateCount += 1
      const phase = ((state.evaluateCount - 1) % 5) + 1
      if (state.mode === 'captcha') {
        if (phase === 1 || phase === 2) return readinessState
        if (phase === 3) return state.failureCategory
        if (phase === 4) return captchaObservation
        return []
      }
      if (phase === 1 || phase === 2 || phase === 3 || phase === 4) return readinessState
      if (phase === 5) return ''
      if (phase === 6) return readyObservation
      return [
        { id: 'element-1', locator: 'text=项目管理', label: '项目管理', role: 'heading', tagName: 'h1', visible: true, enabled: true, receivesEvents: true, coveredByBlockingOverlay: false, semanticType: 'heading', pageScope: 'main', tableColumn: '', matchedElementCount: 1 },
        { id: 'element-2', locator: 'text=项目管理@@scope=main', label: '项目管理', role: 'text', tagName: 'div', visible: true, enabled: true, receivesEvents: true, coveredByBlockingOverlay: false, semanticType: 'content', pageScope: 'main', tableColumn: '', matchedElementCount: 1 }
      ]
    },
    close: async () => { state.pageClosed += 1 }
  }
  const context = {
    addCookies: async () => {},
    route: async (pattern, handler) => {
      routeBuckets.push({ pattern, handler })
    },
    unroute: async (pattern, handler) => {
      const index = routeBuckets.findIndex((item) => item.pattern === pattern && item.handler === handler)
      if (index >= 0) routeBuckets.splice(index, 1)
    },
    routeCount: () => routeBuckets.length,
    newPage: async () => page,
    close: async () => { state.contextClosed += 1 }
  }
  const browser = {
    newContext: async () => context,
    close: async () => { state.browserClosed += 1 }
  }
  return { state, browser, page, context }
}

test('readiness requires two matching samples before it turns ready', async () => {
  let now = 0
  let calls = 0
  const originalNow = Date.now
  Date.now = () => now
  try {
    const result = await waitForPageReadiness({
      evaluate: async () => {
        calls += 1
        return {
          pageKey: 'https://example.test/#/target',
          title: '目标页面',
          headings: ['目标页面'],
          controlTexts: ['业务字段'],
          controlRecords: [{ label: '业务字段', role: 'button', tagName: 'button', pageScope: 'main', rowContext: '' }],
          targetSignals: ['目标页面', '业务字段'],
          loginShell: false,
          errorPage: false,
          emptyShell: false,
          readyMarker: false,
          captchaState: ''
        }
      },
      waitForTimeout: async (_ms) => { now += 100 }
    }, { pageAliases: ['目标页面'], elementTerms: ['业务字段'] }, 250)

    assert.equal(calls >= 2, true)
    assert.equal(result.ready, true)
    assert.equal(result.failureReason, '')
    assert.equal(result.pageKey, 'https://example.test/#/target')
    assert.equal(result.pageFingerprint, result.shellFingerprint)
  } finally {
    Date.now = originalNow
  }
})

test('readiness accepts stable content-only pages even without target terms', async () => {
  let now = 0
  const originalNow = Date.now
  Date.now = () => now
  try {
    const result = await waitForPageReadiness({
      evaluate: async () => ({
        pageKey: 'https://example.test/#/content',
        title: '',
        bodyText: '这是一段仅正文内容',
        headings: [],
        controlTexts: [],
        controlRecords: [],
        targetSignals: [],
        loginShell: false,
        errorPage: false,
        emptyShell: true,
        readyMarker: false,
        captchaState: ''
      }),
      waitForTimeout: async (_ms) => { now += 100 }
    }, { pageAliases: [], elementTerms: [], routeHints: [], expectedTexts: [] }, 250)

    assert.equal(result.ready, true)
    assert.equal(result.failureReason, '')
  } finally {
    Date.now = originalNow
  }
})

test('readiness rejects login shells, error pages, and empty shells', async () => {
  const scenarios = [
    [{ pageKey: 'https://example.test/#/login', title: '登录', headings: ['登录'], controlTexts: ['登录'], controlRecords: [], targetSignals: ['登录'], loginShell: true, errorPage: false, emptyShell: false, readyMarker: false, captchaState: '' }, 'auth_context_lost'],
    [{ pageKey: 'https://example.test/#/error', title: '出错', headings: ['页面出错'], controlTexts: ['错误'], controlRecords: [], targetSignals: ['页面出错'], loginShell: false, errorPage: true, emptyShell: false, readyMarker: false, captchaState: '' }, 'page_error'],
    [{ pageKey: 'https://example.test/#/empty', title: '空壳', headings: [], controlTexts: [], controlRecords: [], targetSignals: [], loginShell: false, errorPage: false, emptyShell: true, readyMarker: false, captchaState: '' }, 'page_not_ready']
  ]

  for (const [state, failureReason] of scenarios) {
    let now = 0
    const originalNow = Date.now
    Date.now = () => now
    try {
      const result = await waitForPageReadiness({
        evaluate: async () => state,
        waitForTimeout: async () => { now += 100 }
      }, { pageAliases: ['目标页面'], elementTerms: ['业务字段'] }, 250)

      assert.equal(result.ready, false)
      assert.equal(result.failureReason, failureReason)
    } finally {
      Date.now = originalNow
    }
  }
})

test('action ir actions stay with the action executor instead of being replayed as safe actions', async () => {
  // Action IR 动作（带 id / 结构化 target）由 executeActionIr 执行完毕；
  // 探索轮次不能再拿到它们，否则同一个动作被第二个执行器重复执行。
  const actionIr = [{ id: 'go-detail', type: 'navigate', target: { kind: 'route', value: 'http://127.0.0.1:1234/detail' } }]
  assert.equal(await pageLocatorDiscoveryTestables.chooseSafeAction({}, {}, {}, {}, { actions: actionIr, rounds: [], budget: {} }), undefined)

  const structuredOnly = [{ type: 'click', target: { kind: 'evidence', evidenceId: 'element-1' } }]
  assert.equal(await pageLocatorDiscoveryTestables.chooseSafeAction({}, {}, {}, {}, { actions: structuredOnly, rounds: [], budget: {} }), undefined)

  const legacy = [{ type: 'click', target: 'text=查询' }]
  assert.deepEqual(await pageLocatorDiscoveryTestables.chooseSafeAction({}, {}, {}, {}, { actions: legacy, rounds: [], budget: {} }), legacy[0])
})

test('a structured navigation target is never stringified into a url', async () => {
  const action = { id: 'go-detail', type: 'navigate', target: { kind: 'route', value: 'http://127.0.0.1:1234/detail' } }
  const page = { url: () => 'http://127.0.0.1:1234/list' }
  // 走 validateRequestedActions：结构化 target 必须取 value（而不是 '[object Object]'）。
  assert.doesNotThrow(() => pageLocatorDiscoveryTestables.validateRequestedActions(page, [action], [], []))

  // 合法的同源相对路径必须被接受：过度收紧会把正常路由目标也拒绝掉。
  const base = 'http://127.0.0.1:1234/list'
  for (const value of ['detail', 'users/detail', '?q=1', '#/open/threat', '/list', './detail.html', '../detail', 'http://127.0.0.1:1234/detail']) {
    const resolved = pageLocatorDiscoveryTestables.resolveNavigationUrl({ target: { kind: 'route', value } }, base)
    assert.equal(resolved.origin, 'http://127.0.0.1:1234', `相对目标必须同源解析：${value}`)
  }

  // 站点入口：'/' 在带 hash 路由的基址上必须沿用基址（如 '#/home'）。
  // 实测 2026-10-14：原样解析成根文档会让 goto 落到不渲染的 SPA 空壳；waitForUrl '/' 与
  // normalizeRoute 后的 '#/home' 也永不相等，必然超时（本次一起修复）。
  const siteEntry = pageLocatorDiscoveryTestables.resolveNavigationUrl({ target: { kind: 'route', value: '/' } }, 'http://127.0.0.1:1234/#/home')
  assert.equal(siteEntry.href, 'http://127.0.0.1:1234/#/home')
  // 无 hash 路由的基址行为不变：'/' 仍是站点根。
  const plainRoot = pageLocatorDiscoveryTestables.resolveNavigationUrl({ target: { kind: 'route', value: '/' } }, 'http://127.0.0.1:1234/list')
  assert.equal(plainRoot.href, 'http://127.0.0.1:1234/')

  // fail-closed：字符串化产物、危险 scheme、非字符串 target、跨源
  for (const bad of [{ target: {} }, { target: '[object Object]' }, { target: { kind: 'route', value: '[object Object]' } }, { target: 'javascript:alert(1)' }, { target: { kind: 'route', value: 'data:text/html,x' } }]) {
    assert.throws(
      () => pageLocatorDiscoveryTestables.resolveNavigationUrl({ id: 'bad', type: 'navigate', ...bad }, base),
      (error) => error.failureType === 'unsupported_action_target',
      `必须 fail-closed：${JSON.stringify(bad)}`
    )
  }
  // 含空白的描述文字与纯自然语言：不是路由，必须 fail-closed（否则会被百分号编码成同源垃圾地址并执行）。
  for (const prose of ['not a url', 'a b', '威胁情报', '设备详情页']) {
    assert.equal(pageLocatorDiscoveryTestables.isRouteShaped(prose), false, `不是路由形状：${prose}`)
    assert.throws(
      () => pageLocatorDiscoveryTestables.resolveNavigationUrl({ id: 'bad', type: 'navigate', target: { kind: 'route', value: prose } }, base),
      (error) => error.failureType === 'unsupported_action_target',
      `描述文字必须 fail-closed：${prose}`
    )
  }
  assert.throws(
    () => pageLocatorDiscoveryTestables.resolveNavigationUrl({ target: 'http://evil.example/detail' }, base),
    (error) => error.failureType === 'navigation_failed'
  )
  assert.throws(
    () => pageLocatorDiscoveryTestables.validateRequestedActions(page, [{ type: 'direct-navigation', target: '[object Object]' }], [], []),
    (error) => error.failureType === 'unsupported_action_target'
  )
  // 命中 ASCII 子串 hint 的描述文字（复核方实测 GET /detail%20page）：必须导航到 hint 指向的路由，
  // 绝不能把标签本身百分号编码后打开；命中不了就 fail-closed。
  const hintedLabel = pageLocatorDiscoveryTestables.resolveLegacyNavigationTarget(
    { type: 'direct-navigation', target: 'detail page' }, base, { routeHints: ['detail'], history: [] })
  assert.equal(hintedLabel.href, 'http://127.0.0.1:1234/detail', `标签必须映射到路由：${hintedLabel.href}`)
  const slashHintLabel = pageLocatorDiscoveryTestables.resolveLegacyNavigationTarget(
    { type: 'direct-navigation', target: 'list page' }, base, { routeHints: ['/list'], history: [] })
  assert.equal(slashHintLabel.href, 'http://127.0.0.1:1234/list', `带斜杠的 hint 也要匹配：${slashHintLabel.href}`)
  assert.throws(
    () => pageLocatorDiscoveryTestables.resolveLegacyNavigationTarget(
      { type: 'direct-navigation', target: '威胁情报' }, base, { routeHints: ['/detail'], history: [] }),
    (error) => error.failureType === 'unsupported_action_target' && /neither a route nor a known route hint/.test(error.message)
  )
  // legacy direct-navigation 没有 route hints 时，描述文字必须 fail-closed（曾经照单全收并记 ok）。
  for (const prose of ['not a url', '威胁情报']) {
    assert.throws(
      () => pageLocatorDiscoveryTestables.validateRequestedActions(page, [{ type: 'direct-navigation', target: prose }], [], []),
      (error) => error.failureType === 'unsupported_action_target',
      `legacy 入口也必须 fail-closed：${prose}`
    )
  }
})

test('an action ir navigate step fails closed instead of opening a garbage address', async (context) => {
  const requestPaths = []
  const server = http.createServer((request, response) => {
    requestPaths.push(request.url)
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end('<!doctype html><h1>起始页</h1>')
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const origin = `http://127.0.0.1:${address.port}`

  // route 值是字符串化产物 / 不可解析的地址：整轮取证必须 fail-closed，
  // 曾经这些值被直接喂给 page.goto（'[object Object]' → /[object%20Object]）并且判 passed。
  const badCases = [
    { target: { kind: 'route', value: '[object Object]' }, failureType: 'unsupported_action_target' },
    { target: { kind: 'route', value: 'javascript:alert(1)' }, failureType: 'unsupported_action_target' },
    // 字符串型描述文字：会被浏览器百分号编码后真实请求（/not%20a%20url），必须 fail-closed。
    { target: { kind: 'route', value: 'not a url' }, failureType: 'unsupported_action_target' },
    { target: { kind: 'route', value: '威胁情报' }, failureType: 'unsupported_action_target' },
    { target: { kind: 'route' }, failureType: 'invalid_action_ir' }
  ]
  for (const item of badCases) {
    requestPaths.length = 0
    const outcome = await discoverPageLocators({
      sourcePointId: 'point-navigate-fail',
      url: `${origin}/start`,
      baseUrl: origin,
      browser: 'chromium',
      headless: true,
      timeoutMs: 1_000,
      intent: { pageAliases: ['起始页'], elementTerms: ['起始页'] },
      actions: [
        { id: 'go-ok', type: 'navigate', safety: 'readonly', target: { kind: 'route', value: `${origin}/start#ok` } },
        { id: 'go-bad', type: 'navigate', safety: 'readonly', target: item.target }
      ]
    }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) }).catch((error) => error)

    assert.ok(outcome instanceof Error, `非法导航目标必须整体失败：${JSON.stringify(item.target)}`)
    assert.equal(outcome.failureType, item.failureType, `非法导航目标的失败分类必须稳定：${outcome.message}`)
    assert.equal(requestPaths.some((path) => /object|javascript|%20|%E5|not/i.test(path)), false,
      `不允许把页面导航到字符串化产物/描述文字地址：${requestPaths.join(',')}`)
  }

  // 反向：合法的同源相对路径必须被接受（曾经把 'detail' / '?q=1' 这类合法路径也判成非法）。
  const relative = await discoverPageLocators({
    sourcePointId: 'point-navigate-relative',
    url: `${origin}/start`,
    baseUrl: origin,
    browser: 'chromium',
    headless: true,
    timeoutMs: 1_000,
    intent: { pageAliases: ['起始页'], elementTerms: ['起始页'] },
    actions: [
      { id: 'go-relative', type: 'navigate', safety: 'readonly', target: { kind: 'route', value: '/start?q=1' } }
    ]
  }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) }).catch((error) => error)

  assert.equal(relative instanceof Error, false, `合法相对路径不得失败：${relative?.message}`)
  const relativeStep = relative.stepEvidence.find((step) => step.actionId === 'go-relative')
  assert.equal(relativeStep.execution.status, 'passed')
  assert.equal(requestPaths.includes('/start?q=1'), true)
})

test('a legacy direct navigation with a prose target never reaches the page', async (context) => {
  // legacy（无 id）direct-navigation 的 target 曾经被当成合法地址直接执行：
  // '威胁情报' → 真实请求 /%E5%A8%81%E8%83%81%E6%83%85%E6%8A%A5，且探索轮次记 ok、失败原因被引向「页面无变化」。
  const requestPaths = []
  const server = http.createServer((request, response) => {
    requestPaths.push(request.url)
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(request.url === '/detail'
      ? '<!doctype html><h1>详情页</h1><button data-testid="save">保存</button>'
      : '<!doctype html><h1>起始页</h1><a href="/detail">详情</a>')
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const origin = `http://127.0.0.1:${address.port}`
  // 目标词不在起始页上：探索轮次才会真的执行请求里的 legacy 动作（否则第一轮就 matched）。
  const intent = { pageAliases: ['详情页'], elementTerms: ['保存'] }

  for (const target of ['not a url', '威胁情报', 'a b']) {
    requestPaths.length = 0
    const outcome = await discoverPageLocators({
      sourcePointId: 'point-direct-nav-prose',
      url: `${origin}/start`,
      baseUrl: origin,
      browser: 'chromium',
      headless: true,
      timeoutMs: 1_000,
      intent,
      actions: [{ type: 'direct-navigation', target }]
    }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) }).catch((error) => error)

    assert.ok(outcome instanceof Error, `描述文字不得被当成地址执行：${target}`)
    assert.equal(outcome.failureType, 'unsupported_action_target', `失败分类必须稳定：${outcome.message}`)
    assert.equal(requestPaths.some((path) => /%20|%E5|not|a%20b/i.test(path)), false,
      `不允许真的请求描述文字地址：${requestPaths.join(',')}`)
  }

  // 复核方实测：命中 ASCII 子串 hint 的描述文字会被真的导航出去（GET /detail%20page、/list%20%E9%A1%B5%E9%9D%A2）。
  // 修复后必须导航到 hint 指向的路由，且两个闸门（请求校验 / 探索轮次）看到同一份 hints。
  requestPaths.length = 0
  const substringHinted = await discoverPageLocators({
    sourcePointId: 'point-direct-nav-substring',
    url: `${origin}/start`,
    baseUrl: origin,
    browser: 'chromium',
    headless: true,
    timeoutMs: 1_000,
    intent,
    routeHints: ['detail'],
    actions: [{ type: 'direct-navigation', target: 'detail page' }]
  }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) }).catch((error) => error)

  assert.equal(substringHinted instanceof Error, false, `hint 命中的标签不得整体失败：${substringHinted?.message}`)
  assert.equal(requestPaths.some((path) => /%20|page/i.test(path)), false, `不得请求描述文字地址：${requestPaths.join(',')}`)
  assert.equal(requestPaths.includes('/detail'), true, `必须导航到 hint 指向的路由：${requestPaths.join(',')}`)

  // navigationHints 也必须被两个闸门认可（复核方实测这里曾经不对称：校验期放行、执行期被拒）。
  requestPaths.length = 0
  const viaNavigationHints = await discoverPageLocators({
    sourcePointId: 'point-direct-nav-navigation-hints',
    url: `${origin}/start`,
    baseUrl: origin,
    browser: 'chromium',
    headless: true,
    timeoutMs: 1_000,
    intent,
    navigationHints: ['detail'],
    actions: [{ type: 'direct-navigation', target: 'detail page' }]
  }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) }).catch((error) => error)

  assert.equal(viaNavigationHints instanceof Error, false, `navigationHints 不得造成闸门不对称：${viaNavigationHints?.message}`)
  assert.equal(requestPaths.some((path) => /%20|page/i.test(path)), false, `不得请求描述文字地址：${requestPaths.join(',')}`)
  assert.equal(requestPaths.includes('/detail'), true, `必须导航到 hint 指向的路由：${requestPaths.join(',')}`)

  // 反向：route hints 证明确实是已知路由时，legacy 导航必须继续可用（探索轮次会执行它）。
  requestPaths.length = 0
  const hinted = await discoverPageLocators({
    sourcePointId: 'point-direct-nav-hinted',
    url: `${origin}/start`,
    baseUrl: origin,
    browser: 'chromium',
    headless: true,
    timeoutMs: 1_000,
    intent,
    routeHints: ['/detail'],
    actions: [{ type: 'direct-navigation', target: `${origin}/detail` }]
  }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) }).catch((error) => error)

  assert.equal(hinted instanceof Error, false, `带 route hints 的合法路由不得被拒绝：${hinted?.message}`)
  assert.equal(requestPaths.includes('/detail'), true, `合法路由必须真的被导航：${requestPaths.join(',')}`)
})

test('a relative route value is accepted while string artefacts and schemes are rejected', () => {
  const resolve = pageLocatorDiscoveryTestables.resolveNavigationUrl
  const base = 'http://127.0.0.1:41827/list'
  for (const good of ['detail', 'users/detail', '?q=1', '#/detail', '/detail', './detail', '../detail', 'http://127.0.0.1:41827/detail']) {
    const url = resolve({ id: 'go', type: 'navigate', target: { kind: 'route', value: good } }, base)
    assert.ok(url instanceof URL, `必须接受合法路由目标：${good}`)
  }
  for (const bad of ['', '   ', '[object Object]', 'javascript:alert(1)', 'data:text/html,hi', {}, { kind: 'route' }, 'https://evil.example.test/x']) {
    assert.throws(() => resolve({ id: 'go', type: 'navigate', target: typeof bad === 'string' ? { kind: 'route', value: bad } : bad }, base),
      (error) => Boolean(error.failureType), `必须拒绝非法路由目标：${JSON.stringify(bad)}`)
  }
})

test('auth_context_lost prefers reauthentication and does not crash on missing lastFailureCategory', async () => {
  const action = await pageLocatorDiscoveryTestables.chooseSafeAction({}, {
    failureCategory: 'auth_context_lost'
  }, {}, {}, {
    rounds: [],
    budget: { maxLoginRetries: 2 }
  })

  assert.deepEqual(action, { type: 'reauthenticate', repairCategory: 'reauthenticate' })
})

test('repair suggestion reauthenticate respects the login retry budget', async () => {
  const action = await pageLocatorDiscoveryTestables.chooseSafeAction({}, {
    failureCategory: 'auth_context_lost',
    repairSuggestion: { category: 'reauthenticate', reason: 'retry login' }
  }, {}, {}, {
    rounds: [],
    budget: { maxLoginRetries: 0 }
  })

  assert.notEqual(action?.type, 'reauthenticate')
})

test('auth_context_lost stops when the reauthentication budget is exhausted', async () => {
  const action = await pageLocatorDiscoveryTestables.chooseSafeAction({}, {
    failureCategory: 'auth_context_lost'
  }, {}, {}, {
    rounds: [],
    budget: { maxLoginRetries: 0 }
  })

  assert.equal(action, undefined)
})

test('classifies critical business requests separately from optional assets', () => {
  const context = {
    targetUrl: 'https://app-dev.example.test/#/open/threat-intelligence',
    loginUrl: 'https://app-dev.example.test/#/login',
    authOrigins: ['https://api.app-dev.example.test'],
    allowedRequestUrls: ['https://api.app-dev.example.test/v4/uc/n/auth/login?token=a'],
    observedBusinessSignals: new Set(['/api/v4/threat/list'])
  }

  assert.equal(classifyRequestCriticality({ url: context.targetUrl, resourceType: 'document' }, context), 'critical')
  assert.equal(classifyRequestCriticality({ url: 'https://api.app-dev.example.test/v4/uc/n/auth/login?token=a', resourceType: 'xhr' }, context), 'auth_dependency')
  assert.equal(classifyRequestCriticality({ url: 'https://api.app-dev.example.test/v4/uc/n/auth/login?token=b', resourceType: 'xhr' }, context), 'unknown')
  assert.equal(classifyRequestCriticality({ url: 'https://api.app-dev.example.test/admin', resourceType: 'xhr' }, context), 'unknown')
  assert.equal(classifyRequestCriticality({ url: 'https://app-dev.example.test/api/v4/threat/list', resourceType: 'fetch' }, context), 'critical')
  assert.equal(classifyRequestCriticality({ url: 'https://static.app-dev.example.test/app.woff2', resourceType: 'font' }, context), 'optional')
})

test('page locator discovery safe request diagnostics redact credentials and queries', () => {
  const line = pageLocatorDiscoveryTestables.formatSafeRequestDiagnostic(
    'post',
    'https://user:secret@api.app-dev.example.test/auth/login?token=secret#frag',
    'Authorization=Bearer secret responseBody={"token":"secret"}'
  )

  assert.equal(line, 'POST https://api.app-dev.example.test/auth/login -> Authorization=[redacted] responseBody=[redacted]')
  assert.equal(line.includes('secret'), false)
  assert.equal(line.includes('?token='), false)
})

test('page locator discovery returns only browser-observed visible elements', async (context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(`<!doctype html>
      <button data-testid="create-user">新增用户</button>
      <h1>用户管理</h1>
      <input type="hidden" id="not-visible" value="secret">
    `)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')

  const snapshot = await discoverPageLocators({
    url: `http://127.0.0.1:${address.port}/users`,
    browser: 'chromium',
    viewport: '900x600',
    headless: true
  }, {
    launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions)
  })

  assert.equal(snapshot.pageUrl, `http://127.0.0.1:${address.port}/users`)
  assert.deepEqual(snapshot.elements.map((element) => element.locator), [
    'testId=create-user',
    'text=用户管理, exact=true'
  ])
  assert.equal(JSON.stringify(snapshot.elements).includes('secret'), false)
})

test('historical locators are only accepted when the current UI DOM observes them', async (context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end('<!doctype html><h1>项目管理</h1><button data-testid="current-target">查看状态</button>')
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const appOrigin = `http://127.0.0.1:${address.port}`

  const snapshot = await discoverPageLocators({
    url: appOrigin,
    baseUrl: appOrigin,
    browser: 'chromium',
    headless: true,
    timeoutMs: 1_000,
    intent: { pageAliases: ['项目管理'], elementTerms: ['查看状态'] },
    historicalLocators: [
      { entryId: 'known-current', locator: 'testId=current-target', label: '查看状态', confidenceScore: 95 },
      { entryId: 'known-stale', locator: 'testId=removed-target', label: '查看状态', confidenceScore: 90 }
    ]
  }, {
    launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions)
  })

  assert.equal(snapshot.status, 'matched')
  assert.deepEqual(snapshot.historicalMatchedIds, ['known-current'])
  assert.deepEqual(snapshot.historicalRejectedIds, ['known-stale'])
  assert.equal(snapshot.elements.some((element) => element.locator === 'testId=current-target'), true)
})

test('page locator discovery carries sourcePointId from request to response', async (context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end('<!doctype html><h1>设备管理</h1><button data-testid="device-status">设备状态</button>')
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const appOrigin = `http://127.0.0.1:${address.port}`

  const snapshot = await discoverPageLocators({
    sourcePointId: 'point-device-status',
    url: `${appOrigin}/devices`,
    baseUrl: appOrigin,
    browser: 'chromium',
    headless: true,
    timeoutMs: 1_000,
    intent: { pageAliases: ['设备管理'], elementTerms: ['设备状态'] }
  }, {
    launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions)
  })

  assert.equal(snapshot.sourcePointId, 'point-device-status')
  assert.equal(snapshot.exploration.sourcePointId, 'point-device-status')
})

test('page locator discovery propagates Action IR and returns step evidence', async (context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(`<!doctype html><button data-testid="open">打开详情</button><h1 id="content">列表页</h1><h1 id="static-title">固定标题</h1>
      <script>document.querySelector('[data-testid=open]').onclick = () => { document.querySelector('#content').textContent = '详情页' }</script>`)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const appOrigin = `http://127.0.0.1:${address.port}`

  // evidenceId 是内容派生的（含 locator 串）：必须先真实取证再引用，硬编码会在 locator 格式演进时腐烂。
  const recon = await discoverPageLocators({
    sourcePointId: 'point-action-ir',
    url: `${appOrigin}/list`,
    baseUrl: appOrigin,
    browser: 'chromium',
    headless: true,
    timeoutMs: 2_000,
    intent: { pageAliases: ['列表页'], elementTerms: ['详情页', '固定标题'] }
  }, {
    launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions)
  })
  const openEvidenceId = recon.elements.find((element) => element.locator === 'testId=open')?.evidenceId
  const staticEvidenceId = recon.elements.find((element) => element.locator === 'text=固定标题, exact=true')?.evidenceId
  assert.ok(openEvidenceId, '必须取证到「打开详情」按钮')
  assert.ok(staticEvidenceId, '必须取证到「固定标题」（不随点击变化的断言目标）')

  const snapshot = await discoverPageLocators({
    sourcePointId: 'point-action-ir',
    url: `${appOrigin}/list`,
    baseUrl: appOrigin,
    browser: 'chromium',
    headless: true,
    timeoutMs: 2_000,
    intent: { pageAliases: ['列表页'], elementTerms: ['详情页', '固定标题'] },
    actions: [
      { id: 'open-detail', type: 'click', safety: 'readonly', target: { kind: 'evidence', evidenceId: openEvidenceId } },
      { id: 'assert-detail', type: 'assertVisible', safety: 'readonly', target: { kind: 'evidence', evidenceId: staticEvidenceId } },
      { id: 'assert-detail-enabled', type: 'assertEnabled', safety: 'readonly', target: { kind: 'evidence', evidenceId: staticEvidenceId } }
    ]
  }, {
    launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions)
  })

  assert.equal(snapshot.stepEvidence.length, 3)
  assert.deepEqual(snapshot.stepEvidence.map((item) => item.actionId), ['open-detail', 'assert-detail', 'assert-detail-enabled'])
  assert.equal(snapshot.stepEvidence.every((item) => item.execution.status === 'passed'), true)
  assert.equal(snapshot.stepEvidence[0].resolvedTarget.visible, true)
  assert.equal(snapshot.stepEvidence[1].resolvedTarget.evidenceId, staticEvidenceId)
  assert.equal(snapshot.stepEvidence.every((item) => /^element-[a-f0-9]{16}$/.test(item.evidenceId)), true)
  assert.equal(snapshot.stepEvidence[0].resolvedTarget.evidenceId, snapshot.exploration.rounds[0]?.observation?.elements?.find((element) => element.locator === 'testId=open')?.evidenceId)
})

test('Action IR rejects unsupported types and locator-shaped targets at the discovery boundary', async () => {
  assert.throws(
    () => pageLocatorDiscoveryTestables.validateActionIr([
      { id: 'unsafe', type: 'evaluate', target: { kind: 'semantic', role: 'button', name: '打开' } }
    ]),
    (error) => error.failureType === 'invalid_action_ir' && /unsupported/i.test(error.message)
  )
  assert.throws(
    () => pageLocatorDiscoveryTestables.validateActionIr([
      { id: 'locator', type: 'click', safety: 'readonly', target: { locator: 'css=#open' } }
    ]),
    (error) => error.failureType === 'invalid_action_ir' && /target/i.test(error.message)
  )
  assert.throws(
    () => pageLocatorDiscoveryTestables.validateActionIr([
      { id: 'semantic', type: 'click', safety: 'readonly', target: { kind: 'semantic', role: 'button', name: '打开' } }
    ]),
    (error) => error.failureType === 'invalid_action_ir' && /evidenceId/i.test(error.message)
  )
})

// 点击观测通道的单一实现来源（第二十九轮 C1 修复；第三十一轮升级为「绑定可证伪」；
// 第三十一轮补审继续加固为「自触发探针可证伪」）：
// 5-1/5-2/5-3 必须复用同一工厂——记录服务器 + 点击处理器是同一份实现，任何破坏都会同时影响三测
// （含正控制 5-3），不允许各测自备副本。第三十一轮复核证明：仅「共享实现 + 缺注入点守卫」仍不够——
// id 拼错 / 空 ids / 注入点被移入 <head> 这类一行级失同步仍会让零断言静默真空。故工厂强制四层：
//   ① 静态校验：ids 非空、无重复，且每个 id 必须作为独立属性（id="x"，不是 data-id="x"）真实存在；
//   ② 注入脚本以 DOMContentLoaded 兜底绑定（对注入点位置不敏感）；
//   ③ 绑定回执：binder 自报的挂载清单（含每次实例随机 nonce）必须与请求一致——不一致即抛错；
//   ④ 自触发探针：binder 挂载后对每个可点击元素派发一次受控点击（探针监听器先手 preventDefault +
//      stopImmediatePropagation，不触发真实导航），每个探针必须真实到达本服务器——回执为真而绑定
//      为假（addEventListener 被页面阉割等）会让探针缺失 ⇒ 响亮超时，绝不静默真空。
// 边界（第三十一轮补审 C1/C2 残余，与「重写测试」等价，不在防线内）：页面脚本主动伪造回执/探针、
// 或在绑定后替换目标节点（TOCTOU）属于对测试内容的主动干扰。
function createClickTrackingFixture(fullHtml, clickableElementIds) {
  if (!fullHtml.includes('<!--click-handlers-->')) {
    // 缺注入点的页面会让点击处理器不挂载、观测恒为空——零断言会静默退化为空断言。
    throw new Error('click-tracking fixture requires the <!--click-handlers--> placeholder so handlers are always attached');
  }
  if (!Array.isArray(clickableElementIds) || clickableElementIds.length === 0) {
    throw new Error('click-tracking fixture requires at least one clickable element id');
  }
  const htmlIds = new Map();
  for (const match of fullHtml.matchAll(/(?:^|\s)id="([^"]*)"/g)) {
    htmlIds.set(match[1], (htmlIds.get(match[1]) || 0) + 1);
  }
  const missingIds = clickableElementIds.filter((id) => !htmlIds.has(id));
  if (missingIds.length > 0) {
    throw new Error(`click-tracking fixture ids not found in the page: ${missingIds.join(', ')}`);
  }
  const duplicateIds = clickableElementIds.filter((id) => htmlIds.get(id) > 1);
  if (duplicateIds.length > 0) {
    throw new Error(`click-tracking fixture ids are duplicated in the page: ${duplicateIds.join(', ')}`);
  }
  const nonce = randomUUID().replace(/-/g, '');
  const clickedHits = []
  const selftestHits = []
  let verified = false
  let attachedResolve
  const attached = new Promise((resolve) => { attachedResolve = resolve })
  const requestPath = (rawUrl) => {
    try {
      return new URL(String(rawUrl || ''), 'http://127.0.0.1/').pathname
    } catch (error) {
      return ''
    }
  }
  const server = http.createServer((request, response) => {
    const rawUrl = String(request.url || '')
    const pathname = requestPath(rawUrl)
    if (pathname === '/click-attach') {
      const params = new URL(rawUrl, 'http://127.0.0.1/').searchParams
      if (params.get('nonce') === nonce) {
        attachedResolve({
          ids: params.get('ids') || '',
          probe: params.get('probe') || ''
        })
      }
      response.writeHead(204)
      response.end()
      return
    }
    if (pathname === '/click-probe') {
      const params = new URL(rawUrl, 'http://127.0.0.1/').searchParams
      if (params.get('nonce') === nonce) {
        selftestHits.push(params.get('el') || '')
      }
      response.writeHead(204)
      response.end()
      return
    }
    if (pathname === '/clicked') {
      let hit = rawUrl
      try {
        hit = decodeURIComponent(rawUrl)
      } catch (error) {
        response.writeHead(400)
        response.end()
        return
      }
      clickedHits.push(hit)
      response.writeHead(204)
      response.end()
      return
    }
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(fullHtml.replace('<!--click-handlers-->', `
        <script>
          (() => {
            const NONCE = ${JSON.stringify(nonce)};
            const IDS = ${JSON.stringify(clickableElementIds)};
            const bind = () => {
              const bound = [];
              const probed = [];
              for (const id of IDS) {
                const el = document.getElementById(id);
                if (!el) continue;
                const isDisabled = el.disabled === true || el.hasAttribute('disabled');
                if (!isDisabled) {
                  // 探针监听器必须先于真实导航监听器注册、先手吞掉事件（不触发导航），
                  // 且处理完自触发点击后立即自摘——否则会吞掉后续真实点击。
                  const handler = (event) => {
                    event.preventDefault();
                    event.stopImmediatePropagation();
                    el.removeEventListener('click', handler);
                    const probe = new Image();
                    probe.src = '/click-probe?el=' + encodeURIComponent(id) + '&nonce=' + NONCE;
                  };
                  el.addEventListener('click', handler);
                }
                el.addEventListener('click', () => { location.href = '/clicked?el=' + encodeURIComponent(id) });
                bound.push(id);
                if (!isDisabled) probed.push(id);
              }
              for (const id of probed) {
                const el = document.getElementById(id);
                if (el) el.click();
              }
              // 回执用同源 <img> 子资源（discovery 的 networkPolicy 允许 document/script/image 等同源类型；
              // fetch/xhr 属非关键请求会被其拦截）。
              const receipt = new Image();
              receipt.src = '/click-attach?ids=' + encodeURIComponent(bound.join(',')) +
                '&probe=' + encodeURIComponent(probed.join(',')) + '&nonce=' + NONCE;
            };
            if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', bind); } else { bind(); }
          })();
        </script>`))
  })
  const fixture = {
    async listen() {
      await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
      const address = server.address()
      if (!address || typeof address === 'string') throw new Error('click-tracking fixture failed to bind')
      return `http://127.0.0.1:${address.port}/`
    },
    /**
     * 在任何断言之前调用（页面至少被加载过一次之后）：绑定回执必须与请求的 ids 完全一致，
     * 且自触发探针必须全部真实到达本服务器（回执与绑定可证伪——仅自报不算数）。
     */
    async waitForHandlers(timeoutMs = 5000) {
      const deadline = Date.now() + timeoutMs
      const receipt = await Promise.race([
        attached,
        new Promise((_resolve, reject) => setTimeout(
          () => {
            const probeNote = selftestHits.length > 0
              ? '; note: self-test probes DID arrive, so the receipt channel itself appears blocked'
              : ''
            reject(new Error(`click-tracking fixture: no valid attach receipt from the page (missing nonce or blocked by the network policy?)${probeNote}; the observation channel is not proven (silent vacuity guard)`))
          },
          timeoutMs
        ))
      ])
      const actual = String(receipt.ids).split(',').filter(Boolean).sort().join(',')
      const expected = [...clickableElementIds].sort().join(',')
      if (actual !== expected) {
        throw new Error(`click-tracking fixture: handlers bound to [${receipt.ids}] but expected ${JSON.stringify(clickableElementIds)}`)
      }
      const probeIds = String(receipt.probe).split(',').filter(Boolean)
      while (probeIds.some((id) => !selftestHits.includes(id))) {
        if (Date.now() > deadline) {
          const missing = probeIds.filter((id) => !selftestHits.includes(id))
          throw new Error(`click-tracking fixture: bind receipt claims [${receipt.ids}] but self-test probes never reached the server for [${missing.join(', ')}]; the handlers are not actually attached (silent vacuity guard)`)
        }
        await new Promise((resolve) => setTimeout(resolve, 20))
      }
      verified = true
    },
    close() {
      return new Promise((resolve) => server.close(resolve))
    }
  }
  Object.defineProperty(fixture, 'clickedHits', {
    get() {
      if (!verified) {
        throw new Error('click-tracking fixture: read clickedHits only after waitForHandlers() — the observation channel is not proven yet (silent vacuity guard)')
      }
      return clickedHits
    }
  })
  return fixture
}

test('acceptance 5-2: disabled controls never become clickable evidence and are never clicked', async (context) => {
  const fixture = createClickTrackingFixture(`<!doctype html><html><head><meta charset="utf-8"><title>禁用控件页</title></head>
      <body>
        <h1>禁用控件页</h1>
        <button id="disabled-submit" disabled>提交</button>
        <button id="enabled-cancel">取消</button>
        <!--click-handlers-->
      </body></html>`, ['disabled-submit', 'enabled-cancel'])
  context.after(() => fixture.close())
  const baseUrl = await fixture.listen()

  const recon = await discoverPageLocators({
    url: baseUrl,
    browser: 'chromium',
    headless: true,
    timeoutMs: 2_000,
    intent: { pageAliases: ['禁用控件页'], elementTerms: ['提交', '取消'] },
    budget: { maxRounds: 1 }
  }, { launchBrowser: (_id, options) => chromium.launch(options) })

  const disabled = recon.elements.find((element) => (element.label || '').includes('提交'))
  await fixture.waitForHandlers()
  assert.ok(disabled, '控件会以证据形式被采集（是否可点击由执行层强制）')
  assert.equal(recon.elements.some((element) => (element.label || '').includes('取消')), true,
    '可用的对照按钮应当存在（证明确实采集到了本页控件）')
  await assert.rejects(
    () => discoverPageLocators({
      url: baseUrl,
      browser: 'chromium',
      headless: true,
      timeoutMs: 2_000,
      actions: [{ id: 'click-disabled', type: 'click', safety: 'readonly', target: { kind: 'evidence', evidenceId: disabled.evidenceId } }]
    }, { launchBrowser: (_id, options) => chromium.launch(options) }),
    (error) => {
      // 验收 5-2：禁用控件不得被点击 —— 动作必须在点击前拒绝（category 以实测为准，随后收紧）。
      assert.match(String(error.failureType || error.category || ''), /ambiguous_locator|action_evidence_missing|element_not_found/)
      return true
    }
  )
  // 断言层锁死「不点击」：点击处理器会导航到夹具服务器的 /clicked（通道由 acceptance 5-3 正控制自检兜底，防真空失守；第十八轮复核方指出的加固项落地）。
  await new Promise((resolve) => setTimeout(resolve, 300))
  assert.deepEqual(fixture.clickedHits, [], `不得对禁用/遮挡控件发起任何点击，实际收到：${fixture.clickedHits.join('，')}`)
})

test('acceptance 5-1: overlay-covered controls never become clickable evidence', async (context) => {
  const fixture = createClickTrackingFixture(`<!doctype html><html><head><meta charset="utf-8"><title>遮挡页</title>
      <style>#cover{position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.4);z-index:50}</style></head>
      <body>
        <h1>遮挡页</h1>
        <button id="covered-confirm">确认</button>
        <div id="cover"></div>
        <!--click-handlers-->
      </body></html>`, ['covered-confirm'])
  context.after(() => fixture.close())
  const baseUrl = await fixture.listen()

  const recon = await discoverPageLocators({
    url: baseUrl,
    browser: 'chromium',
    headless: true,
    timeoutMs: 2_000,
    intent: { pageAliases: ['遮挡页'], elementTerms: ['确认'] },
    budget: { maxRounds: 1 }
  }, { launchBrowser: (_id, options) => chromium.launch(options) })

  const covered = recon.elements.find((element) => (element.label || '').includes('确认'))
  await fixture.waitForHandlers()
  assert.ok(covered, '控件会以证据形式被采集（是否可接收事件由执行层强制）')
  await assert.rejects(
    () => discoverPageLocators({
      url: baseUrl,
      browser: 'chromium',
      headless: true,
      timeoutMs: 2_000,
      actions: [{ id: 'click-covered', type: 'click', safety: 'readonly', target: { kind: 'evidence', evidenceId: covered.evidenceId } }]
    }, { launchBrowser: (_id, options) => chromium.launch(options) }),
    (error) => {
      // 验收 5-1：被 overlay 遮挡的元素不得被点击 —— 动作必须在点击前拒绝。
      assert.match(String(error.failureType || error.category || ''), /ambiguous_locator|action_evidence_missing|element_not_found/)
      return true
    }
  )
  // 断言层锁死「不点击」（同一加固项，覆盖 overlay 场景；观测通道由 acceptance 5-3 正控制自检兜底）。
  await new Promise((resolve) => setTimeout(resolve, 300))
  assert.deepEqual(fixture.clickedHits, [], `不得对遮挡控件发起任何点击，实际收到：${fixture.clickedHits.join('，')}`)
})

test('acceptance 5-3: click-observation channel has teeth (positive control for 5-1/5-2)', async (context) => {
  // 正控制：对“可用”控件发起成功 click，必须被夹具服务器观测到（点击导航到 /clicked）。
  // 与 5-1/5-2 复用同一工厂（单点实现，见 createClickTrackingFixture 注释）。
  const fixture = createClickTrackingFixture(`<!doctype html><html><head><meta charset="utf-8"><title>可点页</title></head>
      <body>
        <h1>可点页</h1>
        <button id="go">执行</button>
        <!--click-handlers-->
      </body></html>`, ['go'])
  context.after(() => fixture.close())
  const baseUrl = await fixture.listen()

  const recon = await discoverPageLocators({
    url: baseUrl,
    browser: 'chromium',
    headless: true,
    timeoutMs: 2_000,
    intent: { pageAliases: ['可点页'], elementTerms: ['执行'] },
    budget: { maxRounds: 1 }
  }, { launchBrowser: (_id, options) => chromium.launch(options) })
  const go = recon.elements.find((element) => (element.label || '').includes('执行'))
  await fixture.waitForHandlers()
  assert.ok(go, '必须先取到按钮证据')

  await discoverPageLocators({
    url: baseUrl,
    browser: 'chromium',
    headless: true,
    timeoutMs: 2_000,
    actions: [{ id: 'click-go', type: 'click', safety: 'readonly', target: { kind: 'evidence', evidenceId: go.evidenceId } }]
  }, { launchBrowser: (_id, options) => chromium.launch(options) })

  await new Promise((resolve) => setTimeout(resolve, 300))
  assert.ok(
    fixture.clickedHits.includes('/clicked?el=go'),
    `成功点击必须被观测到（通道失效会让 5-1/5-2 的零断言变成空断言），实际：${fixture.clickedHits.join('，')}`
  )
})

test('Action IR assertion failures retain before and after browser observations', async (context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end('<!doctype html><h1>实际标题</h1>')
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')

  const recon = await discoverPageLocators({
    url: `http://127.0.0.1:${address.port}/`,
    browser: 'chromium',
    headless: true,
    timeoutMs: 1_000,
    intent: { pageAliases: ['实际标题'], elementTerms: ['实际标题'] }
  }, { launchBrowser: (_id, options) => chromium.launch(options) })
  const titleEvidenceId = recon.elements.find((element) => element.locator === 'text=实际标题, exact=true')?.evidenceId
  assert.ok(titleEvidenceId, '必须先取证到标题元素（evidenceId 内容派生，不能硬编码）')

  await assert.rejects(
    () => discoverPageLocators({
      url: `http://127.0.0.1:${address.port}/`,
      browser: 'chromium',
      headless: true,
      timeoutMs: 1_000,
      actions: [{
        id: 'wrong-title',
        type: 'assertText',
        safety: 'readonly',
        target: { kind: 'evidence', evidenceId: titleEvidenceId },
        expected: '期望标题',
      }]
    }, { launchBrowser: (_id, options) => chromium.launch(options) }),
    (error) => {
      assert.equal(error.failureType, 'assertion_failed')
      assert.equal(error.stepEvidence?.[0]?.execution?.failureType, 'assertion_failed')
      assert.equal(typeof error.stepEvidence?.[0]?.before?.pageFingerprint, 'string')
      assert.equal(typeof error.stepEvidence?.[0]?.after?.pageFingerprint, 'string')
      return true
    }
  )
})

test('historical locator matches are scoped by sourcePointId even on the same URL', async (context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end('<!doctype html><h1>设备管理</h1><button data-testid="shared-status">状态</button>')
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const appOrigin = `http://127.0.0.1:${address.port}`

  const snapshot = await discoverPageLocators({
    sourcePointId: 'point-devices-status',
    url: `${appOrigin}/devices`,
    baseUrl: appOrigin,
    browser: 'chromium',
    headless: true,
    timeoutMs: 1_000,
    intent: { pageAliases: ['设备管理'], elementTerms: ['状态'] },
    historicalLocators: [
      { entryId: 'devices-status', sourcePointId: 'point-devices-status', pageUrl: `${appOrigin}/devices`, locator: 'testId=shared-status', label: '状态', confidenceScore: 95 },
      { entryId: 'users-status', sourcePointId: 'point-users-status', pageUrl: `${appOrigin}/devices`, locator: 'testId=shared-status', label: '状态', confidenceScore: 95 }
    ]
  }, {
    launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions)
  })

  assert.deepEqual(snapshot.historicalMatchedIds, ['devices-status'])
  assert.deepEqual(snapshot.historicalRejectedIds, ['users-status'])
})

test('page locator discovery follows a matching navigation item before collecting business elements', async (context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(`<!doctype html>
      <button role="menuitem" id="project-menu">项目</button>
      <button role="menuitem" id="threat-menu">威胁情报</button>
      <main id="content"><h1>项目首页</h1></main>
      <script>
        const render = () => {
          document.querySelector('#content').innerHTML = location.hash === '#/open/threat-intelligence'
            ? '<h1>威胁情报</h1><button id="disable-action">停用</button>'
            : '<h1>项目首页</h1>'
        }
        document.querySelector('#threat-menu').addEventListener('click', () => {
          location.hash = '#/open/threat-intelligence'
          render()
        })
        render()
      </script>
    `)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const baseUrl = `http://127.0.0.1:${address.port}`

  const snapshot = await discoverPageLocators({
    url: `${baseUrl}/#/project`,
    baseUrl,
    browser: 'chromium',
    viewport: '900x600',
    headless: true,
    navigationHints: ['威胁情报业务列表页', '威胁情报业务']
  }, {
    launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions)
  })

  assert.equal(snapshot.pageUrl, `${baseUrl}/#/open/threat-intelligence`)
  assert.equal(snapshot.elements.some((element) => element.label === '停用'), true)
})

test('page locator discovery uses menu search when the target navigation item is initially hidden', async (context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(`<!doctype html>
      <nav><button>项目</button><button>设备</button><button>服务</button></nav>
      <input id="menu-search" placeholder="搜索菜单">
      <div id="menu-results"></div>
      <main id="content"><h1>项目首页</h1></main>
      <script>
        document.querySelector('#menu-search').addEventListener('input', (event) => {
          document.querySelector('#menu-results').innerHTML = event.target.value.includes('威胁情报')
            ? '<button class="_menuSearchResult_fixture" id="threat-menu">威胁情报</button>'
            : ''
          document.querySelector('#threat-menu')?.addEventListener('click', () => {
            location.hash = '#/open/threat-intelligence'
            document.querySelector('#content').innerHTML = '<h1>威胁情报</h1><button id="disable-action">停用</button>'
          })
        })
      </script>
    `)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const baseUrl = `http://127.0.0.1:${address.port}`

  const snapshot = await discoverPageLocators({
    url: `${baseUrl}/#/project`,
    baseUrl,
    browser: 'chromium',
    viewport: '900x600',
    headless: true,
    navigationHints: ['威胁情报业务列表页', '威胁情报业务']
  }, {
    launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions)
  })

  assert.equal(snapshot.pageUrl, `${baseUrl}/#/open/threat-intelligence`)
  assert.equal(snapshot.elements.some((element) => element.label === '停用'), true)
})

test('page locator discovery forwards local-network access option to browser launch', async () => {
  let receivedLaunchOptions
  const snapshot = await discoverPageLocators({
    url: 'https://app-dev.example.test/#/project',
    baseUrl: 'https://app-dev.example.test/#/',
    browser: 'chromium',
    viewport: '900x600',
    headless: true
  }, {
    allowLocalNetworkAccess: true,
    launchBrowser: async (_browserId, launchOptions) => {
      receivedLaunchOptions = launchOptions
      return {
        newContext: async () => ({
          addCookies: async () => {},
          route: async () => {},
          newPage: async () => ({
            on: () => {},
            setDefaultTimeout: () => {},
            setDefaultNavigationTimeout: () => {},
            goto: async () => {},
            url: () => 'https://app-dev.example.test/#/project',
            waitForTimeout: async () => {},
            evaluate: async () => [],
            close: async () => {}
          }),
          close: async () => {}
        }),
        close: async () => {}
      }
    }
  })

  assert.equal(receivedLaunchOptions.allowLocalNetworkAccess, true)
  assert.equal(snapshot.pageUrl, 'https://app-dev.example.test/#/project')
})

test('resume from handoff rejects mismatched project environment or browser before launching', async (context) => {
  const clockStart = Date.now()
  const originalNow = Date.now
  Date.now = () => clockStart
  const session = createSession({
    projectId: 1,
    environmentId: 'env-1',
    browser: 'chromium',
    sessionKey: 'https://example.test',
    context: { close: async () => {} },
    page: {}
  }, 1_000, clockStart)
  const handoff = createHandoff({
    sessionId: session.sessionId,
    projectId: 1,
    environmentId: 'env-1',
    browser: 'chromium',
    runId: 'run-1',
    pageKey: 'https://example.test/login',
    pageUrlPath: '/login',
    pageFingerprint: 'fingerprint-1'
  }, 1_000, clockStart)
  Date.now = originalNow

  let launches = 0
  await assert.rejects(
    () => discoverPageLocators({
      url: 'https://example.test/login',
      baseUrl: 'https://example.test',
      projectId: 2,
      environmentId: 'env-1',
      browser: 'chromium',
      handoffId: handoff.handoffId,
      resumeFromHandoff: true
    }, {
      launchBrowser: async () => {
        launches += 1
        throw new Error('browser should not launch for mismatched handoff')
      }
    }),
    (error) => {
      assert.equal(error.category, 'manual_required')
      assert.equal(error.handoff.reason, 'handoff_mismatch')
      assert.equal(error.handoff.resumeSupported, false)
      return true
    }
  )
  assert.equal(launches, 0)
})

test('resume from handoff rejects when the stored session is already on a different page', async () => {
  const beforeSessions = sessionCount()
  const beforeHandoffs = handoffCount()
  const context = { close: async () => {} }
  const page = {
    url: () => 'https://example.test/#/project-b',
    evaluate: async (fn) => {
      const source = String(fn || '')
      if (source.includes('location.href')) {
        return {
          url: 'https://example.test/#/project-b',
          title: '项目B',
          headings: ['项目B'],
          breadcrumbs: [],
          activeMenus: ['项目B'],
          tabs: [],
          overlays: [],
          elements: [],
          fingerprint: 'fingerprint-b'
        }
      }
      return []
    },
    close: async () => {}
  }
  const session = createSession({
    projectId: 11,
    environmentId: 'env-page-mismatch',
    browser: 'chromium',
    sessionKey: 'https://example.test',
    context,
    page
  }, 1_000)
  const handoff = createHandoff({
    sessionId: session.sessionId,
    projectId: 11,
    environmentId: 'env-page-mismatch',
    browser: 'chromium',
    runId: 'run-page-mismatch',
    pageKey: 'https://example.test/#/project-a',
    pageUrlPath: '/#/project-a',
    pageFingerprint: 'fingerprint-a'
  }, 1_000)

  let launches = 0
  await assert.rejects(
    () => discoverPageLocators({
      url: 'https://example.test/#/project-a',
      baseUrl: 'https://example.test',
      projectId: 11,
      environmentId: 'env-page-mismatch',
      browser: 'chromium',
      handoffId: handoff.handoffId,
      resumeFromHandoff: true
    }, {
      launchBrowser: async () => {
        launches += 1
        throw new Error('browser should not launch for page mismatch')
      }
    }),
    (error) => {
      assert.equal(error.category, 'manual_required')
      assert.equal(error.handoff.reason, 'handoff_mismatch')
      assert.equal(error.handoff.resumeSupported, false)
      assert.equal(error.handoff.handoffId, handoff.handoffId)
      return true
    }
  )

  assert.equal(launches, 0)
  assert.equal(sessionCount(), beforeSessions)
  assert.equal(handoffCount(), beforeHandoffs)
})

test('resume from non-manual handoff rejects when the same route renders a different fingerprint', async (context) => {
  const beforeSessions = sessionCount()
  const beforeHandoffs = handoffCount()
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(`<!doctype html>
      <main>
        <h1>项目管理</h1>
        <button id="primary-action">保存</button>
        <div id="variant-a">A</div>
      </main>
    `)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const baseUrl = `http://127.0.0.1:${address.port}`
  const browser = await chromium.launch({ headless: true })
  context.after(() => browser.close())
  const browserContext = await browser.newContext()
  const page = await browserContext.newPage()
  await page.goto(`${baseUrl}/project`)
  const initialObservation = await observePageEvidence(page)
  const session = createSession({
    projectId: 12,
    environmentId: 'env-fingerprint-mismatch',
    browser: 'chromium',
    sessionKey: baseUrl,
    context: browserContext,
    page,
    pageKey: initialObservation.pageKey,
    pageUrlPath: '/project',
    pageFingerprint: initialObservation.pageFingerprint
  }, 1_000)
  const handoff = createHandoff({
    sessionId: session.sessionId,
    projectId: 12,
    environmentId: 'env-fingerprint-mismatch',
    browser: 'chromium',
    status: 'ready',
    runId: 'run-fingerprint-mismatch',
    pageKey: initialObservation.pageKey,
    pageUrlPath: '/project',
    pageFingerprint: initialObservation.pageFingerprint
  }, 1_000)
  await page.evaluate(() => {
    document.querySelector('#variant-a')?.remove()
    const extra = document.createElement('div')
    extra.id = 'variant-b'
    extra.textContent = 'B'
    document.querySelector('main')?.appendChild(extra)
  })

  let launches = 0
  await assert.rejects(
    () => discoverPageLocators({
      url: `${baseUrl}/project`,
      baseUrl,
      projectId: 12,
      environmentId: 'env-fingerprint-mismatch',
      browser: 'chromium',
      handoffId: handoff.handoffId,
      resumeFromHandoff: true,
      timeoutMs: 1_000,
      intent: { pageAliases: ['项目管理'], elementTerms: ['保存'] }
    }, {
      launchBrowser: async () => {
        launches += 1
        throw new Error('browser should not launch for fingerprint mismatch')
      }
    }),
    (error) => {
      assert.equal(error.category, 'manual_required')
      assert.equal(error.handoff.reason, 'handoff_mismatch')
      assert.equal(error.handoff.resumeSupported, false)
      assert.equal(error.handoff.handoffId, handoff.handoffId)
      return true
    }
  )

  assert.equal(launches, 0)
  assert.equal(sessionCount(), beforeSessions)
  assert.equal(handoffCount(), beforeHandoffs)
})

test('captcha handoff keeps the session alive and resumes after the captcha clears', async () => {
  const { state, browser, page, context } = createCaptchaResumeBrowser()
  const originalNow = Date.now
  Date.now = () => state.now
  let launches = 0
  let captchaError
  try {
    await assert.rejects(
      () => discoverPageLocators({
        url: 'https://example.test/#/project',
        baseUrl: 'https://example.test',
        projectId: 7,
        environmentId: 'env-captcha',
        browser: 'chromium',
        headless: true,
        timeoutMs: 1_000,
        intent: { pageAliases: ['项目管理'], elementTerms: ['项目管理'], expectedTexts: ['项目管理'] }
      }, {
        launchBrowser: async () => {
          launches += 1
          return browser
        }
      }),
      (error) => {
        captchaError = error
        assert.equal(error.category, 'captcha_required')
        assert.equal(error.handoff.reason, 'captcha_required')
        assert.equal(error.handoff.resumeSupported, true)
        assert.equal(typeof error.handoff.handoffId, 'string')
        assert.equal(typeof error.handoff.sessionId, 'string')
        return true
      }
    )

    assert.equal(launches, 1)
    assert.equal(state.contextClosed, 0)
    assert.equal(state.pageClosed, 0)
    assert.equal(state.browserClosed, 0)
    assert.equal(page.currentUrl, 'https://example.test/#/project')
    assert.equal(page.listenerCount('requestfailed'), 1)
    assert.equal(page.listenerCount('request'), 1)
    assert.equal(page.listenerCount('response'), 1)
    assert.equal(context.routeCount(), 1)

    state.mode = 'ready'
    state.evaluateCount = 0
    const resumed = await discoverPageLocators({
      url: 'https://example.test/#/project',
      baseUrl: 'https://example.test',
      projectId: 7,
      environmentId: 'env-captcha',
      browser: 'chromium',
      headless: true,
      timeoutMs: 1_000,
      handoffId: captchaError.handoff.handoffId,
      resumeFromHandoff: true,
      intent: { pageAliases: ['项目管理'], elementTerms: ['项目管理'], expectedTexts: ['项目管理'] }
    }, {
      launchBrowser: async () => {
        launches += 1
        return browser
      }
    })

    assert.equal(resumed.status, 'exhausted')
    assert.equal(launches, 1)
    assert.equal(state.contextClosed, 0)
    assert.equal(state.pageClosed, 0)
    assert.equal(state.browserClosed, 0)
    assert.equal(page.listenerCount('requestfailed'), 1)
    assert.equal(page.listenerCount('request'), 1)
    assert.equal(page.listenerCount('response'), 1)
    assert.equal(context.routeCount(), 1)
  } finally {
    Date.now = originalNow
  }
})

test('manual_required handoff keeps the session alive even when the fingerprint changes', async () => {
  const { state, browser, page } = createCaptchaResumeBrowser({ failureCategory: 'manual_required' })
  const originalNow = Date.now
  Date.now = () => state.now
  let launches = 0
  let manualError
  try {
    await assert.rejects(
      () => discoverPageLocators({
        url: 'https://example.test/#/project',
        baseUrl: 'https://example.test',
        projectId: 8,
        environmentId: 'env-manual',
        browser: 'chromium',
        headless: true,
        timeoutMs: 1_000,
        intent: { pageAliases: ['项目管理'], elementTerms: ['项目管理'], expectedTexts: ['项目管理'] }
      }, {
        launchBrowser: async () => {
          launches += 1
          return browser
        }
      }),
      (error) => {
        manualError = error
        assert.equal(error.category, 'manual_required')
        assert.equal(error.handoff.reason, 'manual_required')
        assert.equal(error.handoff.resumeSupported, true)
        return true
      }
    )

    assert.equal(launches, 1)
    assert.equal(state.contextClosed, 0)
    assert.equal(state.pageClosed, 0)
    assert.equal(state.browserClosed, 0)

    state.mode = 'ready'
    state.evaluateCount = 0
    await page.evaluate(() => {
      document.querySelector('button')?.remove()
      const extra = document.createElement('div')
      extra.id = 'variant-manual'
      extra.textContent = '手动接管后新状态'
      document.querySelector('main')?.appendChild(extra)
    })

    const resumed = await discoverPageLocators({
      url: 'https://example.test/#/project',
      baseUrl: 'https://example.test',
      projectId: 8,
      environmentId: 'env-manual',
      browser: 'chromium',
      headless: true,
      timeoutMs: 1_000,
      handoffId: manualError.handoff.handoffId,
      resumeFromHandoff: true,
      intent: { pageAliases: ['项目管理'], elementTerms: ['项目管理'], expectedTexts: ['项目管理'] }
    }, {
      launchBrowser: async () => {
        launches += 1
        return browser
      }
    })

    assert.equal(resumed.status, 'exhausted')
    assert.equal(launches, 1)
    assert.equal(state.contextClosed, 0)
    assert.equal(state.pageClosed, 0)
    assert.equal(state.browserClosed, 0)
  } finally {
    Date.now = originalNow
  }
})

test('captcha handoff stores a hash route path without the query string', async () => {
  assert.equal(pageLocatorDiscoveryTestables.normalizeHandoffPageUrlPath('https://example.test/#/captcha?x=1'), '/#/captcha')
})

test('captcha handoff renews to a fresh resumable handoff when captcha persists after resume', async () => {
  const { state, browser } = createCaptchaResumeBrowser()
  const originalNow = Date.now
  Date.now = () => state.now
  let launches = 0
  let firstCaptchaError
  let secondCaptchaError
  try {
    await assert.rejects(
      () => discoverPageLocators({
        url: 'https://example.test/#/project',
        baseUrl: 'https://example.test',
        projectId: 7,
        environmentId: 'env-captcha',
        browser: 'chromium',
        headless: true,
        timeoutMs: 1_000,
        intent: { pageAliases: ['项目管理'], elementTerms: ['项目管理'], expectedTexts: ['项目管理'] }
      }, {
        launchBrowser: async () => {
          launches += 1
          return browser
        }
      }),
      (error) => {
        firstCaptchaError = error
        assert.equal(error.category, 'captcha_required')
        assert.equal(error.handoff.resumeSupported, true)
        return true
      }
    )

    state.evaluateCount = 0
    await assert.rejects(
      () => discoverPageLocators({
        url: 'https://example.test/#/project',
        baseUrl: 'https://example.test',
        projectId: 7,
        environmentId: 'env-captcha',
        browser: 'chromium',
        headless: true,
        timeoutMs: 1_000,
        handoffId: firstCaptchaError.handoff.handoffId,
        resumeFromHandoff: true,
        intent: { pageAliases: ['项目管理'], elementTerms: ['项目管理'], expectedTexts: ['项目管理'] }
      }, {
        launchBrowser: async () => {
          launches += 1
          return browser
        }
      }),
      (error) => {
        secondCaptchaError = error
        assert.equal(error.category, 'captcha_required')
        assert.equal(error.handoff.resumeSupported, true)
        assert.notEqual(error.handoff.handoffId, firstCaptchaError.handoff.handoffId)
        return true
      }
    )

    state.evaluateCount = 0
    state.mode = 'ready'
    const resumed = await discoverPageLocators({
      url: 'https://example.test/#/project',
      baseUrl: 'https://example.test',
      projectId: 7,
      environmentId: 'env-captcha',
      browser: 'chromium',
      headless: true,
      timeoutMs: 1_000,
      handoffId: secondCaptchaError.handoff.handoffId,
      resumeFromHandoff: true,
      intent: { pageAliases: ['项目管理'], elementTerms: ['项目管理'], expectedTexts: ['项目管理'] }
    }, {
      launchBrowser: async () => {
        launches += 1
        return browser
      }
    })

    assert.equal(launches, 1)
    assert.equal(resumed.status, 'exhausted')
    assert.equal(state.contextClosed, 0)
    assert.equal(state.pageClosed, 0)
    assert.equal(state.browserClosed, 0)
  } finally {
    Date.now = originalNow
  }
})

test('session mismatch clears the stored handoff and invalidates the paired session', async () => {
  const beforeSessions = sessionCount()
  const beforeHandoffs = handoffCount()
  const session = createSession({
    projectId: 9,
    environmentId: 'env-mismatch',
    browser: 'chromium',
    sessionKey: 'https://example.test',
    context: { close: async () => {} },
    page: {}
  }, 1_000)
  const handoff = createHandoff({
    sessionId: session.sessionId,
    projectId: 9,
    environmentId: 'env-mismatch',
    browser: 'chromium',
    runId: 'run-mismatch',
    pageKey: 'https://example.test/#/project',
    pageUrlPath: '/#/project',
    pageFingerprint: 'fingerprint-project'
  }, 1_000)

  let launches = 0
  await assert.rejects(
    () => discoverPageLocators({
      url: 'https://example.test/#/project',
      baseUrl: 'https://example.test/other',
      projectId: 9,
      environmentId: 'env-mismatch',
      browser: 'chromium',
      handoffId: handoff.handoffId,
      resumeFromHandoff: true
    }, {
      launchBrowser: async () => {
        launches += 1
        throw new Error('browser should not launch for mismatched session state')
      }
    }),
    (error) => {
      assert.equal(error.category, 'auth_context_lost')
      assert.equal(error.handoff.reason, 'manual_required')
      assert.equal(error.handoff.resumeSupported, false)
      assert.equal(error.handoff.handoffId, handoff.handoffId)
      return true
    }
  )

  assert.equal(launches, 0)
  assert.equal(sessionCount(), beforeSessions)
  assert.equal(handoffCount(), beforeHandoffs)
})

test('resume from handoff rejects expired handoff before launching', async () => {
  const originalNow = Date.now
  const createdAt = 1_000
  Date.now = () => createdAt
  const session = createSession({
    projectId: 3,
    environmentId: 'env-expired',
    browser: 'chromium',
    context: { close: async () => {} },
    page: {}
  }, 1_000, createdAt)
  const handoff = createHandoff({
    sessionId: session.sessionId,
    projectId: 3,
    environmentId: 'env-expired',
    browser: 'chromium',
    runId: 'run-expired'
  }, 1, createdAt)
  Date.now = () => createdAt + 10

  let launches = 0
  try {
    await assert.rejects(
      () => discoverPageLocators({
        url: 'https://example.test/login',
        baseUrl: 'https://example.test',
        projectId: 3,
        environmentId: 'env-expired',
        browser: 'chromium',
        handoffId: handoff.handoffId,
        resumeFromHandoff: true
      }, {
        launchBrowser: async () => {
          launches += 1
          throw new Error('browser should not launch for expired handoff')
        }
      }),
      (error) => {
        assert.equal(error.category, 'manual_required')
        assert.equal(error.handoff.reason, 'handoff_expired')
        assert.equal(error.handoff.resumeSupported, false)
        return true
      }
    )
    assert.equal(launches, 0)
  } finally {
    Date.now = originalNow
  }
})

test('page locator discovery login supports xpath locators and hash route success targets', async (context) => {
  const server = http.createServer((request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    if (request.url?.startsWith('/project')) {
      response.end('<!doctype html><h1>项目管理</h1>')
      return
    }
    response.end(`<!doctype html>
      <input name="username">
      <input name="password" type="password">
      <button data-testid="submit-login" type="button">登录</button>
      <script>
        document.querySelector('[data-testid="submit-login"]').addEventListener('click', () => {
          location.hash = '#/project'
        })
      </script>
    `)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const baseUrl = `http://127.0.0.1:${address.port}`

  const snapshot = await discoverPageLocators({
    url: `${baseUrl}/project`,
    browser: 'chromium',
    viewport: '900x600',
    headless: true,
    allowedRequestUrls: [`${baseUrl}/login`, `${baseUrl}/project`],
    login: {
      enabled: true,
      url: `${baseUrl}/login`,
      usernameLocator: 'xpath=//input[@name="username"]',
      passwordLocator: '//input[@name="password"]',
      submitLocator: 'testId=submit-login',
      username: 'admin',
      password: 'secret',
      successType: 'url',
      successTarget: `${baseUrl}/login#/project`
    }
  }, {
    launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions)
  })

  assert.equal(snapshot.pageUrl, `${baseUrl}/project`)
  assert.equal(snapshot.elements.some((element) => element.locator === 'text=项目管理, exact=true'), true)
})

test('page locator discovery accepts login when the page leaves the login route despite a stale success target', async (context) => {
  const server = http.createServer((request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    if (request.url?.startsWith('/project')) {
      response.end('<!doctype html><h1>项目管理</h1>')
      return
    }
    response.end(`<!doctype html>
      <input name="username"><input name="password" type="password">
      <button data-testid="submit-login" type="button">登录</button>
      <script>document.querySelector('[data-testid="submit-login"]').onclick=()=>{ location.href='/project' }</script>`)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address(); assert.ok(address && typeof address !== 'string')
  const baseUrl = `http://127.0.0.1:${address.port}`

  const snapshot = await discoverPageLocators({
    url: `${baseUrl}/project`, baseUrl, browser: 'chromium', headless: true, timeoutMs: 1_000,
    login: {
      enabled: true, url: `${baseUrl}/login`, usernameLocator: 'css=input[name="username"]',
      passwordLocator: 'css=input[name="password"]', submitLocator: 'testId=submit-login',
      username: 'admin', password: 'secret', successType: 'url', successTarget: `${baseUrl}/wrong-target`
    }
  }, { launchBrowser: (_id, opts) => chromium.launch(opts) })

  assert.equal(snapshot.pageUrl, `${baseUrl}/project`)
  assert.equal(snapshot.elements.some((element) => element.locator === 'text=项目管理, exact=true'), true)
})

test('page locator discovery applies imported browser cookies before opening protected pages', async (context) => {
  const server = http.createServer((request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    if (!request.headers.cookie?.includes('haitlify-token=cookie-token')) {
      response.end('<!doctype html><script>location.hash = "#/login?from=%2F"</script>')
      return
    }
    response.end('<!doctype html><h1>项目管理</h1>')
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const baseUrl = `http://127.0.0.1:${address.port}`

  const snapshot = await discoverPageLocators({
    url: `${baseUrl}/project`,
    baseUrl,
    browser: 'chromium',
    viewport: '900x600',
    headless: true,
    extraHeaders: {
      Cookie: 'haitlify-token=cookie-token'
    }
  }, {
    launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions)
  })

  assert.equal(snapshot.pageUrl, `${baseUrl}/project`)
  assert.equal(snapshot.elements.some((element) => element.locator === 'text=项目管理, exact=true'), true)
})

test('page locator discovery reports safe automatic-login diagnostics without credentials', async (context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(`<!doctype html>
      <input name="username">
      <input name="password" type="password">
      <button data-testid="submit-login" type="button">登录</button>
      <script>
        document.querySelector('[data-testid="submit-login"]').addEventListener('click', () => {
          fetch('/auth/login', { method: 'POST' })
        })
      </script>
    `)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const baseUrl = `http://127.0.0.1:${address.port}`

  await assert.rejects(
    () => discoverPageLocators({
      url: `${baseUrl}/project`,
      baseUrl,
      browser: 'chromium',
      viewport: '900x600',
      headless: true,
      timeoutMs: 1_000,
      login: {
        enabled: true,
        url: `${baseUrl}/login`,
        usernameLocator: 'css=input[name="username"]',
        passwordLocator: 'css=input[name="password"]',
        submitLocator: 'testId=submit-login',
        username: 'admin',
        password: 'secret',
        successType: 'url',
        successTarget: `${baseUrl}/project`
      }
    }, {
      launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions)
    }),
    (error) => {
      const message = error instanceof Error ? error.message : String(error)
      assert.match(message, /自动登录失败/)
      assert.match(message, /登录页/)
      assert.match(message, /successType=url/)
      assert.match(message, /successTarget=/)
      assert.match(message, /submitLocator=testId=\[redacted\]/)
      assert.equal(message.includes('admin'), false)
      assert.equal(message.includes('secret'), false)
      return true
    }
  )
})

test('page exploration closes a safe overlay and never clicks a dangerous confirmation', async (context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(`<!doctype html>
      <div role="dialog" aria-modal="true" id="overlay"><p>提示</p><button id="cancel">取消</button><button id="confirm">确认</button></div>
      <main><h1>威胁情报</h1><button data-testid="target">查看详情</button></main>
      <script>document.querySelector('#cancel').onclick=()=>document.querySelector('#overlay').remove(); document.querySelector('#confirm').onclick=()=>document.body.dataset.confirmed='yes'</script>`)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address(); assert.ok(address && typeof address !== 'string')
  const baseUrl = `http://127.0.0.1:${address.port}`
  const snapshot = await discoverPageLocators({ url: `${baseUrl}/`, baseUrl, browser: 'chromium', headless: true,
    intent: { pageAliases: ['威胁情报'], elementTerms: ['查看详情'] }, budget: { maxRounds: 3 } }, {
    launchBrowser: (_id, opts) => chromium.launch(opts)
  })
  assert.equal(snapshot.exploration.status, 'matched')
  assert.equal(snapshot.exploration.rounds.some((round) => round.action?.type === 'dismiss-overlay'), true)
  assert.equal(snapshot.finalObservation.overlays.length, 0)
})

test('page exploration reports unsafe overlay when only a dangerous confirmation exists', async (context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end('<!doctype html><div role="dialog" aria-modal="true"><p>确认停用</p><button>确认停用</button></div><h1>威胁情报</h1><button>查看详情</button>')
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address(); assert.ok(address && typeof address !== 'string')
  const baseUrl = `http://127.0.0.1:${address.port}`
  const snapshot = await discoverPageLocators({ url: `${baseUrl}/`, baseUrl, browser: 'chromium', headless: true,
    intent: { pageAliases: ['威胁情报'], elementTerms: ['查看详情'] }, budget: { maxRounds: 1 } }, {
    launchBrowser: (_id, opts) => chromium.launch(opts)
  })
  assert.equal(snapshot.exploration.status, 'blocked')
  assert.equal(snapshot.exploration.failureReason, 'unsafe_overlay')
})

test('page exploration disambiguates repeated table actions with row context', async (context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end('<!doctype html><h1>设备</h1><table><tr><th>状态</th><th>操作</th></tr><tr><td>即将到期</td><td><button>停用</button></td></tr><tr><td>正常</td><td><button>停用</button></td></tr></table>')
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address(); assert.ok(address && typeof address !== 'string')
  const snapshot = await discoverPageLocators({ url: `http://127.0.0.1:${address.port}/`, browser: 'chromium', headless: true }, {
    launchBrowser: (_id, opts) => chromium.launch(opts)
  })
  const disable = snapshot.elements.filter((element) => element.text === '停用')
  assert.equal(disable.length, 2)
  assert.equal(disable.some((element) => element.rowContext.includes('即将到期')), true)
  assert.equal(disable.every((element) => element.locator), true)
})

test('page exploration rejects direct navigation outside the current origin', async (context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end('<!doctype html><h1>首页</h1>')
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address(); assert.ok(address && typeof address !== 'string')
  const baseUrl = `http://127.0.0.1:${address.port}`
  await assert.rejects(() => discoverPageLocators({ url: `${baseUrl}/`, browser: 'chromium', headless: true,
    actions: [{ type: 'direct-navigation', url: 'https://example.com' }] }, {
    launchBrowser: (_id, opts) => chromium.launch(opts)
  }), (error) => {
    assert.equal(error.failureType, 'navigation_failed')
    assert.match(error.message, /same-origin|站外|navigation/i)
    return true
  })
})

test('page exploration scrolls a container to reveal a target', async (context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end('<!doctype html><h1>资源</h1><div id="scroll" style="height:80px;overflow:auto"><div style="height:1500px"><button data-testid="bottom-target" style="margin-top:1400px">底部目标</button></div></div>')
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address(); assert.ok(address && typeof address !== 'string')
  const snapshot = await discoverPageLocators({ url: `http://127.0.0.1:${address.port}/`, browser: 'chromium', headless: true,
    intent: { pageAliases: ['资源'], elementTerms: ['底部目标'] }, budget: { maxRounds: 4 } }, {
    launchBrowser: (_id, opts) => chromium.launch(opts)
  })
  assert.equal(snapshot.status, 'matched')
  assert.equal(snapshot.rounds.some((round) => round.action?.type === 'scroll-container'), true)
})

test('page exploration switches a read-only tab before collecting its target', async (context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(`<!doctype html><h1>资源</h1><div role="tablist"><button role="tab" aria-selected="true" id="one">概览</button><button role="tab" aria-selected="false" id="two">只读详情</button></div><div id="panel">概览</div><script>two.onclick=()=>{two.setAttribute('aria-selected','true');one.setAttribute('aria-selected','false');panel.innerHTML='<button data-testid="readonly-target">只读目标</button>'}</script>`)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address(); assert.ok(address && typeof address !== 'string')
  const snapshot = await discoverPageLocators({ url: `http://127.0.0.1:${address.port}/`, browser: 'chromium', headless: true,
    intent: { pageAliases: ['资源'], elementTerms: ['只读目标'] }, budget: { maxRounds: 4 } }, {
    launchBrowser: (_id, opts) => chromium.launch(opts)
  })
  assert.equal(snapshot.status, 'matched')
  assert.equal(snapshot.rounds.some((round) => round.action?.type === 'switch-tab'), true)
})

test('page exploration searches a visible menu-search input even when no menu is exposed', async (context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(`<!doctype html><h1>首页</h1><input id="search" placeholder="搜索菜单"><div id="results"></div><main id="content"></main><script>search.oninput=()=>{results.innerHTML='<button class="menuSearchResult" id="target-menu">威胁情报</button>';document.querySelector('#target-menu').onclick=()=>content.innerHTML='<h1>威胁情报</h1><button data-testid="detail">详情</button>'}</script>`)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address(); assert.ok(address && typeof address !== 'string')
  const snapshot = await discoverPageLocators({ url: `http://127.0.0.1:${address.port}/`, browser: 'chromium', headless: true,
    navigationHints: ['威胁情报'], intent: { pageAliases: ['威胁情报'], elementTerms: ['详情'] }, budget: { maxRounds: 3 } }, {
    launchBrowser: (_id, opts) => chromium.launch(opts)
  })
  assert.equal(snapshot.status, 'matched')
  assert.equal(snapshot.rounds.some((round) => round.action?.type === 'menu-search'), true)
})

test('page exploration chooses the safest relevant read-only tab instead of the first tab', async (context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(`<!doctype html><h1>资源</h1><div role="tablist"><button role="tab" aria-selected="true" id="overview">概览</button><button role="tab" aria-selected="false" id="danger">停用确认</button><button role="tab" aria-selected="false" id="readonly">只读详情</button></div><div id="panel">概览</div><script>danger.onclick=()=>panel.textContent='危险';readonly.onclick=()=>panel.innerHTML='<button data-testid="readonly-target">只读目标</button>'</script>`)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address(); assert.ok(address && typeof address !== 'string')
  const snapshot = await discoverPageLocators({ url: `http://127.0.0.1:${address.port}/`, browser: 'chromium', headless: true,
    intent: { pageAliases: ['资源'], elementTerms: ['只读目标'], stateTerms: ['只读'] }, budget: { maxRounds: 4 } }, {
    launchBrowser: (_id, opts) => chromium.launch(opts)
  })
  assert.equal(snapshot.status, 'matched')
  assert.equal(snapshot.rounds.some((round) => round.action?.target?.includes('readonly')), true)
  assert.equal(snapshot.finalObservation.elements.some((element) => element.text === '只读目标'), true)
})

test('page exploration creates a unique locator for an id-less safe tab and clicks it', async (context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(`<!doctype html><h1>资源</h1><div role="tablist"><button role="tab" aria-selected="true">概览</button><button role="tab" aria-selected="false">停用确认</button><button role="tab" aria-selected="false">只读详情</button></div><div id="panel">概览</div><script>document.querySelectorAll('[role=tab]')[1].onclick=()=>panel.textContent='危险';document.querySelectorAll('[role=tab]')[2].onclick=()=>panel.innerHTML='<button data-testid="readonly-target">只读目标</button>'</script>`)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address(); assert.ok(address && typeof address !== 'string')
  const snapshot = await discoverPageLocators({ url: `http://127.0.0.1:${address.port}/`, browser: 'chromium', headless: true,
    intent: { pageAliases: ['资源'], elementTerms: ['只读目标'], stateTerms: ['只读'] }, budget: { maxRounds: 4 } }, {
    launchBrowser: (_id, opts) => chromium.launch(opts)
  })
  assert.equal(snapshot.status, 'matched')
  const tabAction = snapshot.rounds.find((round) => round.action?.type === 'switch-tab')?.action
  assert.equal(tabAction?.target, 'role=tab[name="只读详情", exact=true]')
  assert.equal(snapshot.finalObservation.elements.some((element) => element.text === '只读目标'), true)
})

test('page exploration allows a unique id-less text tab only within its tablist', async (context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(`<!doctype html><h1>资源</h1><div role="tablist"><div class="el-tabs__item" aria-selected="true">概览</div><div class="el-tabs__item" aria-selected="false">只读文本</div></div><div id="panel">概览</div><script>document.querySelectorAll('.el-tabs__item')[1].onclick=()=>panel.innerHTML='<button data-testid="text-target">文本目标</button>'</script>`)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address(); assert.ok(address && typeof address !== 'string')
  const snapshot = await discoverPageLocators({ url: `http://127.0.0.1:${address.port}/`, browser: 'chromium', headless: true,
    intent: { pageAliases: ['资源'], elementTerms: ['文本目标'] }, budget: { maxRounds: 3 } }, {
    launchBrowser: (_id, opts) => chromium.launch(opts)
  })
  const tabAction = snapshot.rounds.find((round) => round.action?.type === 'switch-tab')?.action
  assert.match(tabAction?.target || '', /^text=只读文本@@scope=/)
  assert.equal(snapshot.status, 'matched')
  assert.equal(snapshot.finalObservation.elements.some((element) => element.text === '文本目标'), true)
})

test('page exploration scopes same-label text tabs to their stable tablist', async (context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(`<!doctype html><h1>资源</h1><div id="primary-tabs" role="tablist"><div class="el-tabs__item" aria-selected="true">概览</div><div class="el-tabs__item" aria-selected="false">只读文本</div></div><div id="secondary-tabs" role="tablist"><div class="el-tabs__item" aria-selected="true">概览</div><div class="el-tabs__item" aria-selected="false">只读文本</div></div><div id="panel">概览</div><script>document.querySelector('#primary-tabs .el-tabs__item:last-child').onclick=()=>panel.innerHTML='<button data-testid="scoped-target">作用域目标</button>'</script>`)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address(); assert.ok(address && typeof address !== 'string')
  const snapshot = await discoverPageLocators({ url: `http://127.0.0.1:${address.port}/`, browser: 'chromium', headless: true,
    intent: { pageAliases: ['资源'], elementTerms: ['作用域目标'] }, budget: { maxRounds: 3 } }, {
    launchBrowser: (_id, opts) => chromium.launch(opts)
  })
  const tabAction = snapshot.rounds.find((round) => round.action?.type === 'switch-tab')?.action
  assert.match(tabAction?.target || '', /^text=只读文本@@scope=css=#primary-tabs$/)
  assert.equal(snapshot.status, 'matched')
  assert.equal(snapshot.finalObservation.elements.some((element) => element.text === '作用域目标'), true)
})

test('page exploration blocks same-label text tabs when no stable tablist scope exists', async (context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end('<!doctype html><h1>资源</h1><div role="tablist"><div class="el-tabs__item" aria-selected="true">概览</div><div class="el-tabs__item" aria-selected="false">只读文本</div></div><div role="tablist"><div class="el-tabs__item" aria-selected="true">概览</div><div class="el-tabs__item" aria-selected="false">只读文本</div></div><div id="mutation">0</div>')
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address(); assert.ok(address && typeof address !== 'string')
  const snapshot = await discoverPageLocators({ url: `http://127.0.0.1:${address.port}/`, browser: 'chromium', headless: true,
    intent: { pageAliases: ['资源'], elementTerms: ['不存在的目标'] }, budget: { maxRounds: 2 } }, {
    launchBrowser: (_id, opts) => chromium.launch(opts)
  })
  assert.equal(snapshot.rounds[0]?.actionFailure?.failureType, 'ambiguous_locator')
  assert.equal(snapshot.exploration.status, 'blocked')
  assert.equal(snapshot.exploration.failureReason, 'ambiguous_locator')
  assert.equal(snapshot.finalObservation.tabs.filter((tab) => tab.label === '只读文本').every((tab) => tab.ambiguous === true && tab.locator === undefined), true)
})

test('page exploration rejects explicit css and xpath targets for dangerous tabs before clicking', async (context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end('<!doctype html><div role="tablist"><button role="tab" aria-selected="true" id="safe">概览</button><button role="tab" aria-label="概览" aria-selected="false" id="danger" onclick="document.body.dataset.mutation=\'1\'">确认停用</button></div>')
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address(); assert.ok(address && typeof address !== 'string')
  for (const locator of ['css=#danger', 'xpath=//*[@id="danger"]']) {
    const snapshot = await discoverPageLocators({ url: `http://127.0.0.1:${address.port}/`, browser: 'chromium', headless: true,
      actions: [{ type: 'switch-tab', target: locator }], budget: { maxRounds: 1 } }, {
      launchBrowser: (_id, opts) => chromium.launch(opts)
    })
    assert.equal(snapshot.rounds.at(-1).actionFailure.failureType, 'unsafe_action')
    assert.equal(snapshot.finalObservation.elements.some((element) => element.text === '确认停用'), true)
  }
})

test('page exploration parses id-less role tab locators containing quotes and backslashes', async (context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end('<!doctype html><div role="tablist"><button role="tab" aria-selected="true">概览</button><button role="tab" aria-selected="false">只读 &quot;A\\B&quot;</button></div><div id="panel">概览</div><script>document.querySelectorAll("[role=tab]")[1].onclick=()=>panel.innerHTML="<button data-testid=target>目标</button>"</script>')
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address(); assert.ok(address && typeof address !== 'string')
  const snapshot = await discoverPageLocators({ url: `http://127.0.0.1:${address.port}/`, browser: 'chromium', headless: true,
    intent: { pageAliases: [], elementTerms: ['目标'], stateTerms: ['只读'] }, budget: { maxRounds: 3 } }, {
    launchBrowser: (_id, opts) => chromium.launch(opts)
  })
  const action = snapshot.rounds.find((round) => round.action?.type === 'switch-tab')?.action
  // 可访问名是 只读 "A\B"：转义后 locator 里应当出现 \\ 与 \"，并且带 exact。
  assert.equal(action.target.startsWith('role=tab[name="'), true)
  assert.equal(action.target.endsWith('", exact=true]'), true)
  assert.equal(action.target.includes('\\\\'), true, `反斜杠必须被转义：${action.target}`)
  assert.equal(action.target.includes('\\"'), true, `引号必须被转义：${action.target}`)
  assert.equal(snapshot.finalObservation.elements.some((element) => element.text === '目标'), true)
})

test('page locator discovery blocks cross-origin fetch and iframe resources with classified diagnostics', async (context) => {
  let crossOriginHits = 0
  const cross = http.createServer((_request, response) => {
    crossOriginHits += 1
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end('<!doctype html><p>external</p>')
  })
  await new Promise((resolve) => cross.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => cross.close(resolve)))
  const crossAddress = cross.address(); assert.ok(crossAddress && typeof crossAddress !== 'string')
  const app = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(`<!doctype html><h1>首页</h1><iframe src="http://127.0.0.1:${crossAddress.port}/frame"></iframe><script>fetch('http://127.0.0.1:${crossAddress.port}/data').catch(()=>{});</script>`)
  })
  await new Promise((resolve) => app.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => app.close(resolve)))
  const address = app.address(); assert.ok(address && typeof address !== 'string')
  const snapshot = await discoverPageLocators({ url: `http://127.0.0.1:${address.port}/`, baseUrl: `http://127.0.0.1:${address.port}`, browser: 'chromium', headless: true }, {
    assertAllowedUrl: async () => {},
    launchBrowser: (_id, opts) => chromium.launch(opts)
  })
  assert.equal(crossOriginHits, 0)
  assert.equal(snapshot.exploration.networkBlocked.length >= 2, true)
  assert.equal(snapshot.exploration.networkBlocked.some((entry) => entry.failureType === 'network_blocked'), true)
  assert.equal(Array.isArray(snapshot.exploration.network.unknownBlocked), true)
  assert.equal(snapshot.exploration.networkBlocked.every((entry) => !/[?#]/.test(entry.path || '')), true)
})

test('page locator discovery allows a cross-origin login API when it is explicitly listed', async (context) => {
  const api = http.createServer((request, response) => {
    if (request.url !== '/auth/login') {
      response.writeHead(404)
      response.end()
      return
    }
    response.writeHead(200, {
      'Content-Type': 'application/json; charset=utf-8',
      'Access-Control-Allow-Origin': request.headers.origin || '*'
    })
    response.end('{"ok":true}')
  })
  await new Promise((resolve) => api.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => api.close(resolve)))
  const apiAddress = api.address()
  assert.ok(apiAddress && typeof apiAddress !== 'string')
  const apiOrigin = `http://127.0.0.1:${apiAddress.port}`

  const app = http.createServer((request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    if (request.url?.startsWith('/project')) {
      response.end('<!doctype html><h1>项目管理</h1>')
      return
    }
    response.end(`<!doctype html>
      <input name="username">
      <input name="password" type="password">
      <button data-testid="submit-login" type="button">登录</button>
      <script>
        document.querySelector('[data-testid="submit-login"]').addEventListener('click', async () => {
          await fetch('${apiOrigin}/auth/login', { method: 'POST' })
          location.href = '/project'
        })
      </script>
    `)
  })
  await new Promise((resolve) => app.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => app.close(resolve)))
  const appAddress = app.address()
  assert.ok(appAddress && typeof appAddress !== 'string')
  const appOrigin = `http://127.0.0.1:${appAddress.port}`
  const allowedOrigins = new Set([appOrigin, apiOrigin])

  const snapshot = await discoverPageLocators({
    url: `${appOrigin}/project`,
    baseUrl: appOrigin,
    allowedRequestUrls: [`${apiOrigin}/auth/login`],
    browser: 'chromium',
    viewport: '900x600',
    headless: true,
    timeoutMs: 1_000,
    login: {
      enabled: true,
      url: `${appOrigin}/login`,
      usernameLocator: 'css=input[name="username"]',
      passwordLocator: 'css=input[name="password"]',
      submitLocator: 'testId=submit-login',
      username: 'admin',
      password: 'secret',
      successType: 'url',
      successTarget: `${appOrigin}/project`
    }
  }, {
    assertAllowedUrl: async (value) => {
      assert.equal(allowedOrigins.has(new URL(value).origin), true)
    },
    launchBrowser: (_id, opts) => chromium.launch(opts)
  })

  assert.equal(snapshot.pageUrl, `${appOrigin}/project`)
  assert.equal(snapshot.elements.some((element) => element.locator === 'text=项目管理, exact=true'), true)
})

test('assertalias assertion types are normalized and route assertions fail closed', async () => {
  // 别名形状（{type:'assert', assertion:{type:'assert_url'|'assert_visible'|…}}）此前在 runner 侧被整条
  // 判成 unsupported（词表只有 visible/hidden/text/url），执行映射还会拼出 assertAssert_url；
  // 且 assert_url 别名绕过了 navigate/assertUrl 同款的路由形状校验。
  const { validateActionIr, canonicalAssertionType } = pageLocatorDiscoveryTestables
  assert.equal(canonicalAssertionType('assert_url'), 'url')
  assert.equal(canonicalAssertionType('assertText'), 'text')
  assert.equal(canonicalAssertionType('assert_visible'), 'visible')
  assert.equal(canonicalAssertionType('nonsense'), '')

  for (const alias of ['url', 'asserturl', 'assert_url']) {
    // 描述文字目标：必须与 navigate/assertUrl 同口径 fail-closed。
    assert.throws(
      () => validateActionIr([{
        id: 'assert-detail-url', type: 'assert', safety: 'readonly',
        assertion: { type: alias, target: { kind: 'route', value: '设备详情页' } }
      }]),
      (error) => error.failureType === 'unsupported_action_target' && /route assertion target/.test(error.message),
      `别名 ${alias} 的描述文字目标必须被拦下`
    )
    // 合法相对路由继续通过。
    assert.doesNotThrow(() => validateActionIr([{
      id: 'assert-detail-url', type: 'assert', safety: 'readonly',
      assertion: { type: alias, target: { kind: 'route', value: '/detail' } }
    }]), `别名 ${alias} 的合法路由必须通过`)
  }
  for (const alias of ['assert_visible', 'assert_hidden', 'assert_text']) {
    assert.doesNotThrow(() => validateActionIr([{
      id: `assert-${alias}`, type: 'assert', safety: 'readonly',
      assertion: { type: alias, target: { evidenceId: 'element-1' }, expected: 'x' }
    }]), `别名 ${alias} 必须被接受`)
  }
  // 未定义的断言类型仍然 fail-closed。
  assert.throws(
    () => validateActionIr([{ id: 'assert-x', type: 'assert', safety: 'readonly', assertion: { type: 'nonsense', target: { evidenceId: 'element-1' } } }]),
    (error) => error.failureType === 'invalid_action_ir' && /Unsupported Action IR assertion/.test(error.message)
  )
})

test('page locator discovery keeps path allowlists separate from exact request URLs', async (context) => {
  const hits = {
    login: 0,
    tokenized: 0,
    admin: 0
  }
  const server = http.createServer((request, response) => {
    if (request.url === '/project') {
      response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
      response.end(`<!doctype html>
        <meta charset="utf-8">
        <link rel="icon" href="data:,">
        <h1>控制台</h1>
        <button data-testid="target">查看</button>
        <script>
          void fetch('/auth/login', { method: 'POST' }).catch(() => {})
          void fetch('/auth/tokenized', { method: 'POST' }).catch(() => {})
          void fetch('/admin', { method: 'POST' }).catch(() => {})
        </script>
      `)
      return
    }
    if (request.url === '/auth/login') {
      hits.login += 1
      response.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' })
      response.end('{"ok":true,"route":"login"}')
      return
    }
    if (request.url === '/auth/tokenized') {
      hits.tokenized += 1
      response.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' })
      response.end('{"ok":true,"route":"tokenized"}')
      return
    }
    if (request.url === '/admin') {
      hits.admin += 1
      response.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' })
      response.end('{"ok":true,"route":"admin"}')
      return
    }
    response.writeHead(404)
    response.end()
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const baseUrl = `http://127.0.0.1:${address.port}`

  const snapshot = await discoverPageLocators({
    url: `${baseUrl}/project`,
    baseUrl,
    browser: 'chromium',
    headless: true,
    timeoutMs: 1_000,
    allowedOrigins: [baseUrl],
    authOrigins: [baseUrl],
    allowedRequestPaths: ['/auth/login'],
    allowedRequestUrls: [`${baseUrl}/auth/tokenized`],
    intent: { pageAliases: ['控制台'], elementTerms: ['查看'] }
  }, {
    launchBrowser: (_id, opts) => chromium.launch(opts)
  })

  assert.equal(snapshot.status, 'matched')
  assert.equal(snapshot.pageUrl, `${baseUrl}/project`)
  assert.equal(hits.login, 1)
  assert.equal(hits.tokenized, 1)
  assert.equal(hits.admin, 0)
  assert.equal(snapshot.exploration.networkBlocked.some((entry) => entry.path === '/auth/login'), false)
  assert.equal(snapshot.exploration.networkBlocked.some((entry) => entry.path === '/auth/tokenized'), false)
  assert.equal(snapshot.exploration.networkBlocked.some((entry) => entry.path === '/admin'), true)
  assert.equal(snapshot.exploration.networkBlocked.filter((entry) => entry.path === '/admin').length, 1)
})

test('page exploration blocks an ambiguous duplicate tab locator without clicking either tab', async (context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(`<!doctype html>
      <h1>资源</h1>
      <div>
        <div class="el-tabs__item" aria-selected="true" style="display:inline-block;padding:8px">概览</div>
        <div class="el-tabs__item" aria-selected="false" style="display:inline-block;padding:8px" onclick="document.querySelector('#mutation').textContent='1'">只读详情</div>
        <div class="el-tabs__item" aria-selected="false" style="display:inline-block;padding:8px" onclick="document.querySelector('#mutation').textContent='1'">只读详情</div>
      </div>
      <div id="mutation" role="status" aria-label="mutation">0</div>
      <div id="panel">概览</div>
    `)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')

  const snapshot = await discoverPageLocators({
    url: `http://127.0.0.1:${address.port}/`,
    browser: 'chromium',
    viewport: '900x600',
    headless: true,
    intent: { pageAliases: ['资源'], elementTerms: ['只读详情'], expectedTexts: ['只读详情'], explicitNegativeTerms: ['不存在的危险动作'] },
    budget: { maxRounds: 3 }
  }, {
    launchBrowser: (_id, opts) => chromium.launch(opts)
  })

  assert.equal(snapshot.rounds[0]?.actionFailure?.failureType, 'ambiguous_locator')
  assert.equal(snapshot.rounds.some((round) => round.actionFailure?.failureType === 'ambiguous_locator'), true)
  assert.equal(snapshot.exploration.status, 'blocked')
  assert.equal(snapshot.exploration.failureReason, 'ambiguous_locator')
  assert.equal(snapshot.finalObservation?.tabs?.filter((tab) => tab.label === '只读详情').every((tab) => tab.ambiguous === true && tab.locator === undefined), true)
  assert.equal(snapshot.finalObservation?.elements?.find((element) => element.accessibleName === 'mutation')?.text, '0')
})

test('element actions reject route targets and unclassified failures stay classified', async (context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end([
      '<!doctype html><h1>设备管理</h1>',
      '<input id="name" name="name" placeholder="名称">',
      '<select id="kind" name="kind" aria-label="类型"><option value="a">A</option><option value="b">B</option></select>',
      '<button data-testid="next">下一页</button>'
    ].join(''))
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const url = `http://127.0.0.1:${address.port}/`
  const launch = (_id, options) => chromium.launch(options)

  // 元素型动作拿到 route 目标：平台 ActionIrValidator 曾放行，执行期解引用 undefined（TypeError → 503）。
  assert.throws(
    () => pageLocatorDiscoveryTestables.validateActionIr([
      { id: 'route-target', type: 'assertText', safety: 'readonly', target: { kind: 'route', value: '/list' }, expected: 'x' }
    ]),
    (error) => error.failureType === 'invalid_action_ir' && /element target/i.test(error.message)
  )

  const recon = await discoverPageLocators({ url, browser: 'chromium', headless: true, timeoutMs: 2_000, intent: { pageAliases: ['设备管理'], elementTerms: ['名称', 'A'] } }, { launchBrowser: launch })
  const nameEvidenceId = recon.elements.find((element) => element.tagName === 'input')?.evidenceId
  const kindEvidenceId = recon.elements.find((element) => element.tagName === 'select')?.evidenceId
  assert.ok(nameEvidenceId && kindEvidenceId, '必须先取证到输入框与下拉框的 evidenceId')

  // 值来源：请求里带 variables 时运行时变量必须能解析（与平台 ActionIrValidator 的词表一致）。
  const filled = await discoverPageLocators({
    url,
    browser: 'chromium',
    headless: true,
    timeoutMs: 2_000,
    variables: { kindOption: 'b' },
    intent: { pageAliases: ['设备管理'], elementTerms: ['名称', 'A'] },
    actions: [{ id: 'pick-kind', type: 'select', safety: 'readonly', target: { kind: 'evidence', evidenceId: kindEvidenceId }, value: { kind: 'runtimevariable', key: 'kindOption' } }]
  }, { launchBrowser: launch })
  assert.equal(filled.stepEvidence?.find((step) => step.actionId === 'pick-kind')?.execution?.status, 'passed')

  // 没有值来源时必须给出指名到变量的分类错误，而不是让计划在取证期以「runner 不可用」失败。
  await assert.rejects(
    () => discoverPageLocators({
      url,
      browser: 'chromium',
      headless: true,
      timeoutMs: 2_000,
      intent: { pageAliases: ['设备管理'], elementTerms: ['名称', 'A'] },
      actions: [{ id: 'pick-kind', type: 'select', safety: 'readonly', target: { kind: 'evidence', evidenceId: kindEvidenceId }, value: { kind: 'runtimevariable', key: 'kindOption' } }]
    }, { launchBrowser: launch }),
    (error) => {
      assert.equal(error.failureType, 'invalid_action_ir')
      assert.match(error.message, /runtimevariable/)
      assert.match(error.message, /kindOption/)
      assert.equal(Array.isArray(error.stepEvidence), true, '失败必须带步骤证据')
      return true
    }
  )

  // 未分类的执行期错误（select 作用于 input）必须有兜底分类，并连同 before/after 一起返回。
  await assert.rejects(
    () => discoverPageLocators({
      url,
      browser: 'chromium',
      headless: true,
      timeoutMs: 2_000,
      intent: { pageAliases: ['设备管理'], elementTerms: ['名称', 'A'] },
      actions: [{ id: 'select-on-input', type: 'select', safety: 'readonly', target: { kind: 'evidence', evidenceId: nameEvidenceId }, value: 'b' }]
    }, { launchBrowser: launch }),
    (error) => {
      assert.equal(error.failureType, 'action_failed')
      assert.equal(error.stepEvidence?.[0]?.execution?.failureType, 'action_failed')
      assert.equal(typeof error.stepEvidence?.[0]?.before?.pageFingerprint, 'string')
      return true
    }
  )
})

test('a click that never changes the list fails with pagination_not_changed', async (context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end('<!doctype html><h1>订单列表</h1><button data-testid="next">下一页</button><div>第 1 页</div>')
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const url = `http://127.0.0.1:${address.port}/`
  const launch = (_id, options) => chromium.launch(options)

  const recon = await discoverPageLocators({ url, browser: 'chromium', headless: true, timeoutMs: 2_000, intent: { pageAliases: ['订单列表'], elementTerms: ['下一页'] } }, { launchBrowser: launch })
  const nextEvidenceId = recon.elements.find((element) => element.accessibleName === '下一页')?.evidenceId
  assert.ok(nextEvidenceId, '必须先取证到翻页按钮的 evidenceId')

  await assert.rejects(
    () => discoverPageLocators({
      url,
      browser: 'chromium',
      headless: true,
      timeoutMs: 2_000,
      intent: { pageAliases: ['订单列表'], elementTerms: ['下一页'] },
      actions: [{ id: 'next-page', type: 'paginate', safety: 'readonly', target: { kind: 'evidence', evidenceId: nextEvidenceId } }]
    }, { launchBrowser: launch }),
    (error) => {
      // 与 DSL 的 paginate 同一分类：点击后列表未变化 = pagination_not_changed（不是 503、不是 observation_failed）。
      assert.equal(error.failureType, 'pagination_not_changed')
      assert.deepEqual(error.stepEvidence?.map((step) => step.actionId), ['next-page'])
      return true
    }
  )
})

test('pagination that only changes list text is detected as a real change', async (context) => {
  // 复核方实测（/paginate-live）：点击后 tbody 行文本真的变了，但 pageFingerprint 的元素键不含文本，
  // before/after 指纹完全相同 → IR 与 DSL 两条路都把真实翻页报成 pagination_not_changed。
  const html = `<!doctype html><h1>订单列表</h1>
    <table><tbody><tr><td>第一页行一</td></tr></tbody></table>
    <button data-testid="next">下一页</button><div aria-current="page">1</div>
    <script>
      document.querySelector('[data-testid=next]').addEventListener('click', () => {
        document.querySelector('tbody').innerHTML = '<tr><td>第二页行一</td></tr>'
        document.querySelector('[aria-current=page]').textContent = '2'
      })
    </script>`
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(html)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const url = `http://127.0.0.1:${address.port}/`
  const launch = (_id, options) => chromium.launch(options)
  const browser = await chromium.launch({ headless: true })
  context.after(() => browser.close())
  const page = await browser.newPage()
  await page.goto(url)

  // DSL 路径：谓词必须自带实现（引用模块作用域的 readPaginationSnapshot 会在浏览器侧 ReferenceError）。
  await executeStep(page, { action: 'paginate', target: 'testid=next', timeoutMs: 5_000 })
  assert.equal(await page.locator('tbody').innerText(), '第二页行一')

  // IR 路径：同一页面重新加载后走 discoverPageLocators。
  await page.goto(url)
  const recon = await discoverPageLocators({ url, browser: 'chromium', headless: true, timeoutMs: 2_000, intent: { pageAliases: ['订单列表'], elementTerms: ['下一页'] } }, { launchBrowser: launch })
  const nextEvidenceId = recon.elements.find((element) => element.accessibleName === '下一页')?.evidenceId
  assert.ok(nextEvidenceId, '必须先取证到翻页按钮的 evidenceId')
  const result = await discoverPageLocators({
    url,
    browser: 'chromium',
    headless: true,
    timeoutMs: 6_000,
    intent: { pageAliases: ['订单列表'], elementTerms: ['下一页'] },
    actions: [{ id: 'next-page', type: 'paginate', safety: 'readonly', target: { kind: 'evidence', evidenceId: nextEvidenceId } }]
  }, { launchBrowser: launch })
  assert.equal(result.stepEvidence?.[0]?.actionId, 'next-page')
  assert.notEqual(result.stepEvidence?.[0]?.before?.contentSignature, result.stepEvidence?.[0]?.after?.contentSignature)
})

test('form controls without a real label fall back to a resolvable locator', async (context) => {
  // 复核方实测：无标签 <select> 的 accessible name 被采集器用 option 文本合成（唯一），
  // 回放期 Playwright 按 ARIA 算名字为空 → 0 命中，采集期证明的唯一性与回放不一致。
  const html = `<!doctype html><h1>设备管理</h1>
    <select id="kind"><option>路由器</option><option>交换机</option></select>
    <input id="name" aria-label="名称" />
    <button data-testid="save">保存</button>`
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(html)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const url = `http://127.0.0.1:${address.port}/`
  const launch = (_id, options) => chromium.launch(options)

  const recon = await discoverPageLocators({
    url,
    browser: 'chromium',
    headless: true,
    timeoutMs: 2_000,
    intent: { pageAliases: ['设备管理'], elementTerms: ['路由器', '令牌'] }
  }, { launchBrowser: launch })
  const select = recon.elements.find((element) => element.tagName?.toLowerCase() === 'select' || element.cssPath?.includes('#kind'))
  assert.ok(select, '无标签 select 必须被采集到')
  assert.equal(select.accessibleName, '')
  assert.ok(String(select.locator).startsWith('css='), `无标签 select 必须退回 css 定位，实际 ${select.locator}`)

  // 用该证据执行一次真实 select：能解析到唯一元素才算唯一性证明成立。
  const result = await discoverPageLocators({
    url,
    browser: 'chromium',
    headless: true,
    timeoutMs: 2_000,
    intent: { pageAliases: ['设备管理'], elementTerms: ['路由器', '令牌'] },
    actions: [{ id: 'pick-kind', type: 'select', safety: 'readonly', target: { kind: 'evidence', evidenceId: select.evidenceId }, value: '交换机' }]
  }, { launchBrowser: launch })
  assert.equal(result.stepEvidence?.[0]?.actionId, 'pick-kind')

  // testdata 只给 key（没有内联 value）时不能把键名当值填入。
  const input = recon.elements.find((element) => element.accessibleName === '名称')
  assert.ok(input, '必须采集到名称为「名称」的输入框')
  await assert.rejects(
    () => discoverPageLocators({
      url,
      browser: 'chromium',
      headless: true,
      timeoutMs: 2_000,
      intent: { pageAliases: ['设备管理'], elementTerms: ['名称'] },
      actions: [{ id: 'fill-name', type: 'fill', safety: 'readonly', target: { kind: 'evidence', evidenceId: input.evidenceId }, value: { kind: 'testdata', key: 'deviceName' } }]
    }, { launchBrowser: launch }),
    (error) => {
      assert.equal(error.failureType, 'invalid_action_ir')
      assert.match(String(error.message), /testdata/)
      return true
    }
  )
})

const withHtmlPage = async (context, html, run) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(html)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  return run(`http://127.0.0.1:${address.port}/`)
}

test('pagination signature sees changes beyond the first containers and below the section head', async (context) => {
  // 复核方 F3：采集侧签名只取前 8 个容器 + 4000 字符，第 9 个列表容器的翻页被漏判成「未变化」。
  const html = `<!doctype html><h1>订单列表</h1>
    ${Array.from({ length: 8 }, (_item, index) => `<ul><li>侧边分组 ${index + 1}</li></ul>`).join('')}
    <p>${'说明文字'.repeat(80)}</p>
    <ul id="orders"><li>远端条目-1-1</li></ul>
    <button data-testid="next">下一页</button>
    <script>
      document.querySelector('[data-testid=next]').addEventListener('click', () => {
        document.querySelector('#orders').innerHTML = '<li>远端条目-2-1</li>'
      })
    </script>`
  await withHtmlPage(context, html, async (url) => {
    const launch = (_id, options) => chromium.launch(options)
    const recon = await discoverPageLocators({ url, browser: 'chromium', headless: true, timeoutMs: 2_000, intent: { pageAliases: ['订单列表'], elementTerms: ['下一页'] } }, { launchBrowser: launch })
    const nextEvidenceId = recon.elements.find((element) => element.accessibleName === '下一页')?.evidenceId
    assert.ok(nextEvidenceId)
    const result = await discoverPageLocators({
      url,
      browser: 'chromium',
      headless: true,
      timeoutMs: 3_000,
      intent: { pageAliases: ['订单列表'], elementTerms: ['下一页'] },
      actions: [{ id: 'remote-next', type: 'paginate', safety: 'readonly', target: { kind: 'evidence', evidenceId: nextEvidenceId } }]
    }, { launchBrowser: launch })
    const evidence = result.stepEvidence?.find((step) => step.actionId === 'remote-next')
    assert.equal(evidence?.execution?.status, 'passed')
    assert.notEqual(evidence?.before?.contentSignature, evidence?.after?.contentSignature)
  })
})

test('IR and DSL agree that a real <ul> page turn changed the list', async (context) => {
  // 复核方 F5：IR 的容器集合含 ul/ol、DSL 只认 table 系，同一页面采集期算「已变化」、回放期算「未变化」。
  const html = `<!doctype html><h1>条目列表</h1>
    <ul id="items"><li>条目-1-1</li><li>条目-1-2</li></ul>
    <button data-testid="next">下一页</button><div aria-current="page">1</div>
    <script>
      document.querySelector('[data-testid=next]').addEventListener('click', () => {
        document.querySelector('#items').innerHTML = '<li>条目-2-1</li><li>条目-2-2</li>'
        document.querySelector('[aria-current=page]').textContent = '2'
      })
    </script>`
  await withHtmlPage(context, html, async (url) => {
    const launch = (_id, options) => chromium.launch(options)
    const browser = await chromium.launch({ headless: true })
    context.after(() => browser.close())
    const page = await browser.newPage()
    await page.goto(url)
    await executeStep(page, { action: 'paginate', target: 'testid=next', timeoutMs: 5_000 })
    assert.equal(await page.locator('#items').innerText(), '条目-2-1\n条目-2-2')

    const recon = await discoverPageLocators({ url, browser: 'chromium', headless: true, timeoutMs: 2_000, intent: { pageAliases: ['条目列表'], elementTerms: ['下一页'] } }, { launchBrowser: launch })
    const nextEvidenceId = recon.elements.find((element) => element.accessibleName === '下一页')?.evidenceId
    assert.ok(nextEvidenceId)
    const result = await discoverPageLocators({
      url,
      browser: 'chromium',
      headless: true,
      timeoutMs: 3_000,
      intent: { pageAliases: ['条目列表'], elementTerms: ['下一页'] },
      actions: [{ id: 'ul-next', type: 'paginate', safety: 'readonly', target: { kind: 'evidence', evidenceId: nextEvidenceId } }]
    }, { launchBrowser: launch })
    assert.equal(result.stepEvidence?.find((step) => step.actionId === 'ul-next')?.execution?.status, 'passed')
  })
})

test('a page that updates itself does not turn a dead paginate click into success', async (context) => {
  // 复核方 F4/H4 + X2/D3：列表被页面自己定时刷新时，变化按节拍反复出现、永远静默不下来 ⇒
  // 不归因给这次点击，点了没反应的翻页必须判 pagination_not_changed（节律判据，不再用预采样）。
  const html = `<!doctype html><h1>库存列表</h1>
    <ul id="rows"><li>自动行 1</li></ul>
    <button data-testid="stuck">下一页</button><div aria-current="page">1</div>
    <script>
      let round = 0
      setInterval(() => {
        round += 1
        document.querySelector('#rows').innerHTML = '<li>自动行 ' + round + '</li>'
      }, 80)
    </script>`
  await withHtmlPage(context, html, async (url) => {
    const launch = (_id, options) => chromium.launch(options)
    const browser = await chromium.launch({ headless: true })
    context.after(() => browser.close())
    const page = await browser.newPage()
    await page.goto(url)
    await assert.rejects(
      () => executeStep(page, { action: 'paginate', target: 'testid=stuck', timeoutMs: 3_000 }),
      (error) => {
        assert.equal(error.category || error.failureType, 'pagination_not_changed')
        return true
      }
    )

    const recon = await discoverPageLocators({ url, browser: 'chromium', headless: true, timeoutMs: 2_000, intent: { pageAliases: ['库存列表'], elementTerms: ['下一页'] } }, { launchBrowser: launch })
    const stuckEvidenceId = recon.elements.find((element) => element.accessibleName === '下一页')?.evidenceId
    assert.ok(stuckEvidenceId)
    await assert.rejects(
      () => discoverPageLocators({
        url,
        browser: 'chromium',
        headless: true,
        timeoutMs: 3_000,
        intent: { pageAliases: ['库存列表'], elementTerms: ['下一页'] },
        actions: [{ id: 'stuck-next', type: 'paginate', safety: 'readonly', target: { kind: 'evidence', evidenceId: stuckEvidenceId } }]
      }, { launchBrowser: launch }),
      (error) => {
        assert.equal(error.failureType || error.category, 'pagination_not_changed')
        return true
      }
    )
  })
})

test('a real asynchronous page turn is attributed to the click', async (context) => {
  // 复核方 D1：真实翻页先出加载态、数据再晚到（两次变化但会停住）——不能因为「又变了一次」就判
  // pagination_not_changed。归因看节律：最后一次变化之后静默 ≥ max(1s, 1.25×最大间隔) 即归因。
  const html = `<!doctype html><h1>订单列表</h1>
    <ul id="rows"><li>第1页 行1</li><li>第1页 行2</li></ul>
    <div class="loading" style="display:none">加载中…</div>
    <button data-testid="next">下一页</button>
    <script>
      document.querySelector('[data-testid=next]').addEventListener('click', () => {
        const loading = document.querySelector('.loading')
        loading.style.display = 'block'
        setTimeout(() => {
          loading.style.display = 'none'
          document.querySelector('#rows').innerHTML = '<li>第2页 行1</li><li>第2页 行2</li>'
        }, 600)
      })
    </script>`
  await withHtmlPage(context, html, async (url) => {
    const launch = (_id, options) => chromium.launch(options)
    const browser = await chromium.launch({ headless: true })
    context.after(() => browser.close())
    const page = await browser.newPage()
    await page.goto(url)
    await executeStep(page, { action: 'paginate', target: 'testid=next', timeoutMs: 5_000 })

    const recon = await discoverPageLocators({ url, browser: 'chromium', headless: true, timeoutMs: 3_000, intent: { pageAliases: ['订单列表'], elementTerms: ['下一页'] } }, { launchBrowser: launch })
    const nextEvidenceId = recon.elements.find((element) => element.accessibleName === '下一页')?.evidenceId
    assert.ok(nextEvidenceId)
    const result = await discoverPageLocators({
      url,
      browser: 'chromium',
      headless: true,
      timeoutMs: 5_000,
      intent: { pageAliases: ['订单列表'], elementTerms: ['下一页'] },
      actions: [{ id: 'async-next', type: 'paginate', safety: 'readonly', target: { kind: 'evidence', evidenceId: nextEvidenceId } }]
    }, { launchBrowser: launch })
    assert.equal(result.stepEvidence?.find((step) => step.actionId === 'async-next')?.execution?.status, 'passed')
  })
})

test('discovery does not accept a failed login as authenticated', async (context) => {
  // 复核方 X1：runner 侧（DSL）只比路由，而 discovery 侧曾逐字比较 pathname+search+hash ——
  // 同一 hash 路由登录失败（#/login → #/login?error=1）在 DSL 判失败、在采集侧却判成功
  // （HTTP 200 + authenticated=true，证据取自登录页）。判定现在只有一份实现（login-route.mjs）。
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(`<!doctype html>
      <input name="username"><input name="password" type="password">
      <button data-testid="submit-login" type="button">登录</button>
      <script>
        document.querySelector('[data-testid="submit-login"]').addEventListener('click', () => {
          location.hash = '#/login?error=1'
        })
      </script>
    `)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const baseUrl = `http://127.0.0.1:${address.port}`

  await assert.rejects(
    () => discoverPageLocators({
      url: `${baseUrl}/login#/project`,
      browser: 'chromium',
      headless: true,
      timeoutMs: 2_000,
      intent: { pageAliases: ['项目管理'], elementTerms: ['新建'] },
      login: {
        enabled: true,
        url: `${baseUrl}/login`,
        usernameLocator: 'css=input[name="username"]',
        passwordLocator: 'css=input[name="password"]',
        submitLocator: 'testId=submit-login',
        username: 'admin',
        password: 'secret',
        successType: 'url',
        successTarget: `${baseUrl}/login#/project`
      }
    }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) })
  )
})

test('discovery requires leaving the login route when no success target is configured', async (context) => {
  // 复核方 D2：successTarget 为空时采集侧曾经直接当成功（outcome=resolved + authenticated=true，
  // 产出的是登录页元素），而同一份配置在 DSL 侧是 fail-closed（runner-core 要求离开登录路由）。
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(`<!doctype html>
      <h1>系统登录</h1>
      <input name="username"><input name="password" type="password">
      <button data-testid="submit-login" type="button">登录</button>
      <script>
        document.querySelector('[data-testid="submit-login"]').addEventListener('click', () => {
          history.replaceState(null, '', '/login?error=1')
        })
      </script>
    `)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const baseUrl = `http://127.0.0.1:${address.port}`

  await assert.rejects(
    () => discoverPageLocators({
      url: `${baseUrl}/login`,
      browser: 'chromium',
      headless: true,
      timeoutMs: 2_000,
      intent: { pageAliases: ['项目管理'], elementTerms: ['新建'] },
      login: {
        enabled: true,
        url: `${baseUrl}/login`,
        usernameLocator: 'css=input[name="username"]',
        passwordLocator: 'css=input[name="password"]',
        submitLocator: 'testId=submit-login',
        username: 'admin',
        password: 'secret'
      }
    }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) }),
    (error) => error.category === 'login_failed'
  )
})

test('discovery waits for an asynchronous login redirect when no success target is configured', async (context) => {
  // 复核方 14 轮 A4-7a / C-①：空 successTarget 时采集侧曾经在点击后 174ms 单发判定「没离开登录路由」，
  // 对「XHR 登录成功后异步跳转」的形式是竞态 —— 同一个夹具在 DSL 侧（100ms 轮询）passed。
  // 现在采集侧与 DSL 共用 login-route.mjs 的 waitForLoginRouteExit。
  const server = http.createServer((request, response) => {
    const pathname = String(request.url || '').split('?')[0]
    if (pathname === '/ok') {
      response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
      response.end(`<!doctype html>
        <h1>设备列表</h1>
        <nav><a href="/ok">设备列表</a><a href="/ok/system">系统设置</a></nav>
        <button type="button">下一页</button>
      `)
      return
    }
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(`<!doctype html>
      <h1>系统登录</h1>
      <input name="username"><input name="password" type="password">
      <button data-testid="submit-login" type="button">登录</button>
      <script>
        document.querySelector('[data-testid="submit-login"]').addEventListener('click', () => {
          setTimeout(() => { location.href = '/ok' }, 350)
        })
      </script>
    `)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const baseUrl = `http://127.0.0.1:${address.port}`

  const outcome = await discoverPageLocators({
    url: `${baseUrl}/login`,
    browser: 'chromium',
    headless: true,
    timeoutMs: 10_000,
    intent: { pageAliases: ['设备列表'], elementTerms: ['下一页'] },
    login: {
      enabled: true,
      url: `${baseUrl}/login`,
      usernameLocator: 'css=input[name="username"]',
      passwordLocator: 'css=input[name="password"]',
      submitLocator: 'testId=submit-login',
      username: 'admin',
      password: 'secret'
    }
  }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) })

  assert.equal(outcome.authenticated, true)
  assert.match(String(outcome.pageUrl || ''), /\/ok$/)
})

test('a re-authenticated capture reports readiness for the final page, not the login detour', async (context) => {
  // 复核方 15 轮 A-4/C-⑤：重登修复轮之后的 readiness 曾是登录阶段采到的陈旧样本（pageKey 停在登录路由），
  // 与同一条 payload 里的 authenticated=true 自相矛盾；上一版修复挂在 input.rounds 上，而平台 DTO 没有该
  // 字段 ⇒ 生产永不触发（复核方实测 v1 仍是陈旧样本）。现在由采集内部的重登成功置位。
  const state = { sessionAlive: false, logins: 0 }
  const serve = (response, html) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' })
    response.end(html)
  }
  const redirect = (response, location) => {
    response.writeHead(302, { Location: location, 'Cache-Control': 'no-store' })
    response.end()
  }
  const server = http.createServer((request, response) => {
    const pathname = String(request.url || '/').split('?')[0]
    if (pathname === '/session-login') {
      state.logins += 1
      state.sessionAlive = true
      redirect(response, '/app')
      return
    }
    if (pathname === '/login') {
      serve(response, `<!doctype html><h1>登录</h1>
        <input name="username"><input name="password" type="password">
        <button data-testid="submit-login" type="button">登录</button>
        <script>
          document.querySelector('[data-testid="submit-login"]').addEventListener('click', () => { location.href = '/session-login' })
        </script>`)
      return
    }
    if (pathname === '/kill') {
      state.sessionAlive = false
      redirect(response, '/login')
      return
    }
    if (pathname === '/app') {
      if (!state.sessionAlive) { redirect(response, '/login'); return }
      serve(response, state.logins >= 2
        ? `<!doctype html><h1>设备列表</h1><ul id="rows"><li>第1页 行1</li></ul><button data-testid="next" type="button">下一页</button>`
        : `<!doctype html><h1>用户中心</h1><ul id="rows"><li>第1页 行1</li></ul><nav><a href="/kill" data-testid="device-nav">设备列表</a></nav>`)
      return
    }
    response.writeHead(404)
    response.end('nf')
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const baseUrl = `http://127.0.0.1:${address.port}`

  const outcome = await discoverPageLocators({
    url: `${baseUrl}/app`,
    browser: 'chromium',
    headless: true,
    timeoutMs: 25_000,
    intent: { pageAliases: ['设备清单'], elementTerms: ['下一页'] },
    navigationHints: ['设备列表'],
    login: {
      enabled: true,
      url: `${baseUrl}/login`,
      loginUrl: `${baseUrl}/login`,
      usernameLocator: 'css=input[name="username"]',
      passwordLocator: 'css=input[name="password"]',
      submitLocator: 'testId=submit-login',
      username: 'admin',
      password: 'secret',
      successType: 'url',
      successTarget: `${baseUrl}/app`
    }
  }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) })

  assert.equal(outcome.authenticated, true)
  assert.match(String(outcome.pageUrl || ''), /\/app$/)
  assert.equal(outcome.finalObservation?.readiness?.ready, true)
  assert.match(String(outcome.finalObservation?.readiness?.pageKey || ''), /\/app$/)
})

test('a configured login does not reject a business page that merely looks like a login shell', async (context) => {
  // 复核方 C5 的加强面：用例**配置了登录**（登录步骤真实执行成功），随后进入的业务页里有密码框 +
  // 「退出登录」按钮 —— readiness 会把它判成 loginShell，但用例早已离开登录路由，不能整轮丢弃，
  // 也不能把 authenticated 报成 false（否则下游会误判需人工重登）。
  const businessHtml = `<!doctype html>
    <h1>用户中心</h1>
    <button>退出登录</button>
    <input name="password" type="password"><button data-testid="save">保存</button>`
  const loginHtml = `<!doctype html>
    <h1>系统登录</h1>
    <input name="username"><input name="password" type="password">
    <button data-testid="submit-login" type="button">登录</button>
    <script>
      document.querySelector('[data-testid="submit-login"]').addEventListener('click', () => {
        location.href = '/app'
      })
    </script>`
  const server = http.createServer((request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(String(request.url).startsWith('/login') ? loginHtml : businessHtml)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const baseUrl = `http://127.0.0.1:${address.port}`

  const recon = await discoverPageLocators({
    url: `${baseUrl}/app`,
    browser: 'chromium',
    headless: true,
    timeoutMs: 2_000,
    intent: { pageAliases: ['用户中心'], elementTerms: ['保存'] },
    // 注意：不传 reuseSession —— 复用路径的会话对象会让 node --test 进程的 event loop 不退出；
    // 复用路径下的 authenticated 由 extra-probe 的 GATE-*-reuse 场景覆盖。
    login: {
      enabled: true,
      url: `${baseUrl}/login`,
      usernameLocator: 'css=input[name="username"]',
      passwordLocator: 'css=input[name="password"]',
      submitLocator: 'testId=submit-login',
      username: 'admin',
      password: 'secret'
    }
  }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) })

  assert.ok((recon.elements || []).some((element) => element.accessibleName === '保存'))
})

test('a change that reverts to the pre-click content is not attributed to the click', async (context) => {
  // 复核方 C3：点击后先出占位、随后回滚到原样 ⇒ 最终 DOM 与点击前完全相同，两侧都必须判失败。
  // 占位文本用「加载中…」（`PAGINATION_PLACEHOLDER_PATTERN` 认这个形状），这样归因在占位态上是
  // 被明确拒绝的 —— 无论回滚多快都不可能先被放行（复核方夹具用的是 400ms/1200ms 的晚变化）。
  const html = `<!doctype html><h1>回退列表</h1>
    <ul id="rows"><li>原始 行1</li></ul>
    <button data-testid="next">下一页</button>
    <script>
      document.querySelector('[data-testid=next]').addEventListener('click', () => {
        const rows = document.querySelector('#rows')
        rows.innerHTML = '<li>加载中…</li>'
        setTimeout(() => { rows.innerHTML = '<li>原始 行1</li>' }, 1200)
      })
    </script>`
  await withHtmlPage(context, html, async (url) => {
    const launch = (_id, options) => chromium.launch(options)
    const browser = await chromium.launch({ headless: true })
    context.after(() => browser.close())
    const page = await browser.newPage()
    await page.goto(url)
    await assert.rejects(
      () => executeStep(page, { action: 'paginate', target: 'testid=next', timeoutMs: 4_000 }),
      (error) => {
        assert.equal(error.failureType || error.category, 'pagination_not_changed')
        assert.match(String(error.message), /回退/)
        return true
      }
    )

    const recon = await discoverPageLocators({ url, browser: 'chromium', headless: true, timeoutMs: 2_000, intent: { pageAliases: ['回退列表'], elementTerms: ['下一页'] } }, { launchBrowser: launch })
    const nextEvidenceId = recon.elements.find((element) => element.accessibleName === '下一页')?.evidenceId
    assert.ok(nextEvidenceId)
    await assert.rejects(
      () => discoverPageLocators({
        url,
        browser: 'chromium',
        headless: true,
        timeoutMs: 4_000,
        intent: { pageAliases: ['回退列表'], elementTerms: ['下一页'] },
        actions: [{ id: 'revert-next', type: 'paginate', safety: 'readonly', target: { kind: 'evidence', evidenceId: nextEvidenceId } }]
      }, { launchBrowser: launch }),
      (error) => {
        assert.equal(error.failureType || error.category, 'pagination_not_changed')
        assert.match(String(error.message), /回退/)
        return true
      }
    )
  })
})

test('an indicator-only change on a dead click is not a page turn', async (context) => {
  // 复核方 C4：被点按钮自己加 .is-active（通用指示器选择器会命中），列表完全没变 ⇒ 两侧都必须判失败。
  const html = `<!doctype html><h1>点击态列表</h1>
    <ul id="rows"><li>行1</li><li>行2</li></ul>
    <button data-testid="next">下一页</button>
    <script>
      document.querySelector('[data-testid=next]').addEventListener('click', (event) => { event.currentTarget.classList.add('is-active') })
    </script>`
  await withHtmlPage(context, html, async (url) => {
    const launch = (_id, options) => chromium.launch(options)
    const browser = await chromium.launch({ headless: true })
    context.after(() => browser.close())
    const page = await browser.newPage()
    await page.goto(url)
    await assert.rejects(
      () => executeStep(page, { action: 'paginate', target: 'testid=next', timeoutMs: 1_500 }),
      (error) => error.failureType === 'pagination_not_changed'
    )

    const recon = await discoverPageLocators({ url, browser: 'chromium', headless: true, timeoutMs: 2_000, intent: { pageAliases: ['点击态列表'], elementTerms: ['下一页'] } }, { launchBrowser: launch })
    const nextEvidenceId = recon.elements.find((element) => element.accessibleName === '下一页')?.evidenceId
    assert.ok(nextEvidenceId)
    await assert.rejects(
      () => discoverPageLocators({
        url,
        browser: 'chromium',
        headless: true,
        timeoutMs: 1_500,
        intent: { pageAliases: ['点击态列表'], elementTerms: ['下一页'] },
        actions: [{ id: 'active-next', type: 'paginate', safety: 'readonly', target: { kind: 'evidence', evidenceId: nextEvidenceId } }]
      }, { launchBrowser: launch }),
      (error) => error.failureType === 'pagination_not_changed'
    )
  })
})

test('a slow self-refreshing list does not turn a dead paginate click into success', async (context) => {
  // 复核方 X2/D3：刷新周期大于采样间隔的页面（1.5s/2s/3s/5s 轮询）曾经两侧都放行 ——
  // 点击后 1s 内毫无响应 ⇒ 必须静默 11s 才能归因，周期刷新永远攒不出来（截稿实测：1.5/3/5/8s 全拦）。
  const html = `<!doctype html><h1>库存列表</h1>
    <ul id="rows"><li>自动 行1</li></ul>
    <button data-testid="stuck">下一页</button><div aria-current="page">1</div>
    <script>
      let round = 0
      setInterval(() => {
        round += 1
        document.querySelector('#rows').innerHTML = '<li>自动 行' + round + '</li>'
      }, 1500)
    </script>`
  await withHtmlPage(context, html, async (url) => {
    const launch = (_id, options) => chromium.launch(options)
    const browser = await chromium.launch({ headless: true })
    context.after(() => browser.close())
    const page = await browser.newPage()
    await page.goto(url)
    await assert.rejects(
      () => executeStep(page, { action: 'paginate', target: 'testid=stuck', timeoutMs: 3000 }),
      (error) => {
        assert.equal(error.failureType || error.category, 'pagination_not_changed')
        return true
      }
    )

    const recon = await discoverPageLocators({ url, browser: 'chromium', headless: true, timeoutMs: 2_000, intent: { pageAliases: ['库存列表'], elementTerms: ['下一页'] } }, { launchBrowser: launch })
    const stuckEvidenceId = recon.elements.find((element) => element.accessibleName === '下一页')?.evidenceId
    assert.ok(stuckEvidenceId)
    await assert.rejects(
      () => discoverPageLocators({
        url,
        browser: 'chromium',
        headless: true,
        timeoutMs: 2000,
        intent: { pageAliases: ['库存列表'], elementTerms: ['下一页'] },
        actions: [{ id: 'slow-stuck', type: 'paginate', safety: 'readonly', target: { kind: 'evidence', evidenceId: stuckEvidenceId } }]
      }, { launchBrowser: launch }),
      (error) => {
        assert.equal(error.failureType || error.category, 'pagination_not_changed')
        return true
      }
    )
  })
})

test('a loading overlay flipping cannot be mistaken for a page change', async (context) => {
  // 复核方 X3：IR 的签名曾包含 loading（整份快照 JSON），DSL 谓词不包含 ——
  // 同一次死点击 DSL failed、IR passed。两侧现在用同一份投影，loading 由调用方单独判断。
  const html = `<!doctype html><h1>静态列表</h1>
    <div class="loading" aria-busy="true">加载中</div>
    <ul id="rows"><li>静态 行1</li><li>静态 行2</li></ul>
    <button data-testid="stuck">下一页</button>
    <script>setTimeout(() => { const element = document.querySelector('.loading'); element.style.display = 'none' }, 800)</script>`
  await withHtmlPage(context, html, async (url) => {
    const launch = (_id, options) => chromium.launch(options)
    const browser = await chromium.launch({ headless: true })
    context.after(() => browser.close())
    const page = await browser.newPage()
    await page.goto(url)
    await assert.rejects(
      () => executeStep(page, { action: 'paginate', target: 'testid=stuck', timeoutMs: 2000 }),
      (error) => {
        assert.equal(error.failureType || error.category, 'pagination_not_changed')
        return true
      }
    )

    const recon = await discoverPageLocators({ url, browser: 'chromium', headless: true, timeoutMs: 2_000, intent: { pageAliases: ['静态列表'], elementTerms: ['下一页'] } }, { launchBrowser: launch })
    const stuckEvidenceId = recon.elements.find((element) => element.accessibleName === '下一页')?.evidenceId
    assert.ok(stuckEvidenceId)
    await assert.rejects(
      () => discoverPageLocators({
        url,
        browser: 'chromium',
        headless: true,
        timeoutMs: 1000,
        intent: { pageAliases: ['静态列表'], elementTerms: ['下一页'] },
        actions: [{ id: 'loading-stuck', type: 'paginate', safety: 'readonly', target: { kind: 'evidence', evidenceId: stuckEvidenceId } }]
      }, { launchBrowser: launch }),
      (error) => {
        assert.equal(error.failureType || error.category, 'pagination_not_changed')
        return true
      }
    )
  })
})

test('a page turn that only changes clock-shaped text is still a change', async (context) => {
  // 复核方 X4：曾经把 ISO 时间/时钟/UUID 归一化后再取摘要 —— 「页间差异只由时钟形状文本构成」的真实翻页
  // 两侧都被判未变化（真实成功被报成失败）。摘要按原文计算。
  const html = `<!doctype html><h1>时段列表</h1>
    <table><tbody><tr><td>12:01</td><td>A</td></tr><tr><td>12:02</td><td>B</td></tr></tbody></table>
    <button data-testid="next">下一页</button>
    <script>
      document.querySelector('[data-testid=next]').addEventListener('click', () => {
        document.querySelector('tbody').innerHTML = '<tr><td>12:03</td><td>A</td></tr><tr><td>12:04</td><td>B</td></tr>'
      })
    </script>`
  await withHtmlPage(context, html, async (url) => {
    const launch = (_id, options) => chromium.launch(options)
    const browser = await chromium.launch({ headless: true })
    context.after(() => browser.close())
    const page = await browser.newPage()
    await page.goto(url)
    await executeStep(page, { action: 'paginate', target: 'testid=next', timeoutMs: 3000 })
    assert.match(await page.locator('tbody').innerText(), /12:03/)

    const recon = await discoverPageLocators({ url, browser: 'chromium', headless: true, timeoutMs: 2_000, intent: { pageAliases: ['时段列表'], elementTerms: ['下一页'] } }, { launchBrowser: launch })
    const nextEvidenceId = recon.elements.find((element) => element.accessibleName === '下一页')?.evidenceId
    assert.ok(nextEvidenceId)
    const result = await discoverPageLocators({
      url,
      browser: 'chromium',
      headless: true,
      timeoutMs: 6_000,
      intent: { pageAliases: ['时段列表'], elementTerms: ['下一页'] },
      actions: [{ id: 'clock-next', type: 'paginate', safety: 'readonly', target: { kind: 'evidence', evidenceId: nextEvidenceId } }]
    }, { launchBrowser: launch })
    assert.equal(result.stepEvidence?.find((step) => step.actionId === 'clock-next')?.execution?.status, 'passed')
  })
})

test('an evidence target below the fold is observable in the action phase', async (context) => {
  // 复核方 X5：探索阶段会滚动页面、动作阶段的观测不会 —— 首屏之外的真实元素曾被报成
  // action_evidence_missing（fail-closed 假失败）。动作前先做一次有界滚动扫描。
  const html = `<!doctype html><h1>长页面</h1>
    <div style="height: 1400px">占位内容</div>
    <button data-testid="deep-target">深页按钮</button>`
  await withHtmlPage(context, html, async (url) => {
    const launch = (_id, options) => chromium.launch(options)
    const recon = await discoverPageLocators({ url, browser: 'chromium', headless: true, timeoutMs: 3_000, intent: { pageAliases: ['长页面'], elementTerms: ['深页按钮'] } }, { launchBrowser: launch })
    const targetEvidenceId = recon.elements.find((element) => element.accessibleName === '深页按钮')?.evidenceId
    assert.ok(targetEvidenceId, '探索阶段必须采集到首屏之外的按钮')
    const result = await discoverPageLocators({
      url,
      browser: 'chromium',
      headless: true,
      timeoutMs: 3_000,
      intent: { pageAliases: ['长页面'], elementTerms: ['深页按钮'] },
      actions: [{ id: 'deep-click', type: 'click', safety: 'readonly', target: { kind: 'evidence', evidenceId: targetEvidenceId } }]
    }, { launchBrowser: launch })
    assert.equal(result.stepEvidence?.find((step) => step.actionId === 'deep-click')?.execution?.status, 'passed')
  })
})

test('a dead click followed by a phase-aligned self-refresh is not attributed to the click', async (context) => {
  // 复核方 12 轮 high-1：点击处理函数只安排自刷新（点击本身零变化），相位 100ms、周期 1.5–10.5s ——
  // 旧判据把「点击后 ≤300ms 的立即变化」当响应，自刷新冒充点击响应。判据收敛后：响应只认
  // （a）加载态翻转（b）点击**同拍**同步改 DOM；异步出现的变化按「无响应」处理 ⇒ 需要 11s 长静默，
  // 周期自刷新永远攒不出来 ⇒ failed。两侧同一个模型，这里锁 DSL 侧。
  const html = `<!doctype html><h1>库存列表</h1>
    <ul id="rows"><li>自动 行0</li></ul>
    <button data-testid="stuck">下一页</button>
    <script>
      let round = 0
      const tick = () => { round += 1; document.querySelector('#rows').innerHTML = '<li>自动 行' + round + '</li>' }
      document.querySelector('[data-testid=stuck]').addEventListener('click', () => {
        setTimeout(() => { tick(); setInterval(tick, 2000) }, 100)
      })
    </script>`
  await withHtmlPage(context, html, async (url) => {
    const browser = await chromium.launch({ headless: true })
    context.after(() => browser.close())
    const page = await browser.newPage()
    await page.goto(url)
    await assert.rejects(
      executeStep(page, { action: 'paginate', target: 'testid=stuck', timeoutMs: 6_000 }),
      (error) => error?.failureType === 'pagination_not_changed'
    )
  })
})

test('the same dead click with a self-refresh fails the IR path too', async (context) => {
  // 两侧同一份判据：IR 侧对同样的夹具也必须给出 failed + pagination_not_changed（复核方要求两侧结论一致）。
  const html = `<!doctype html><h1>库存列表</h1>
    <ul id="rows"><li>自动 行0</li></ul>
    <button data-testid="stuck">下一页</button>
    <script>
      let round = 0
      const tick = () => { round += 1; document.querySelector('#rows').innerHTML = '<li>自动 行' + round + '</li>' }
      document.querySelector('[data-testid=stuck]').addEventListener('click', () => {
        setTimeout(() => { tick(); setInterval(tick, 1500) }, 100)
      })
    </script>`
  await withHtmlPage(context, html, async (url) => {
    const launch = (_id, options) => chromium.launch(options)
    const recon = await discoverPageLocators({ url, browser: 'chromium', headless: true, timeoutMs: 3_000, intent: { pageAliases: ['库存列表'], elementTerms: ['下一页'] } }, { launchBrowser: launch })
    const nextEvidenceId = recon.elements.find((element) => element.accessibleName === '下一页')?.evidenceId
    assert.ok(nextEvidenceId, '必须先取证到翻页按钮的 evidenceId')
    // IR 侧对「动作失败」的传播是 reject（与 DSL 的 executeStep 同语义）：分类必须是 pagination_not_changed。
    await assert.rejects(
      discoverPageLocators({
        url,
        browser: 'chromium',
        headless: true,
        timeoutMs: 6_000,
        intent: { pageAliases: ['库存列表'], elementTerms: ['下一页'] },
        actions: [{ id: 'stuck-next', type: 'paginate', safety: 'readonly', target: { kind: 'evidence', evidenceId: nextEvidenceId } }]
      }, { launchBrowser: launch }),
      (error) => error?.failureType === 'pagination_not_changed' && /pagination_not_changed/.test(String(error?.message || ''))
    )
  })
})

test('a mask that never hides is not accepted as the click response (DSL + IR)', async (context) => {
  // 复核方 C-1：点击同拍改内容、1000ms 后出现遮罩且不再隐藏。DSL 侧要求「当前不加载」正确失败，
  // IR 侧曾用上一轮样本（非加载）误验收成 resolved ⇒ 现在两侧同判 pagination_not_changed。
  const html = `<!doctype html><h1>库存列表</h1>
    <ul id="rows"><li>第1页 行1</li></ul>
    <div class="loading" aria-busy="true" style="display:none">加载中</div>
    <button data-testid="next">下一页</button>
    <script>
      document.querySelector('[data-testid=next]').addEventListener('click', () => {
        document.querySelector('#rows').innerHTML = '<li>第2页 行1</li>'
        setTimeout(() => { document.querySelector('.loading').style.display = 'block' }, 1000)
      })
    </script>`
  await withHtmlPage(context, html, async (url) => {
    const launch = (_id, options) => chromium.launch(options)
    const browser = await chromium.launch({ headless: true })
    context.after(() => browser.close())
    const page = await browser.newPage()
    await page.goto(url)
    await assert.rejects(
      () => executeStep(page, { action: 'paginate', target: 'testid=next', timeoutMs: 5_000 }),
      (error) => (error?.category || error?.failureType) === 'pagination_not_changed'
    )
    const recon = await discoverPageLocators({ url, browser: 'chromium', headless: true, timeoutMs: 3_000, intent: { pageAliases: ['库存列表'], elementTerms: ['下一页'] } }, { launchBrowser: launch })
    const nextEvidenceId = recon.elements.find((element) => element.accessibleName === '下一页')?.evidenceId
    assert.ok(nextEvidenceId, '必须先取证到翻页按钮的 evidenceId')
    await assert.rejects(
      discoverPageLocators({
        url,
        browser: 'chromium',
        headless: true,
        timeoutMs: 5_000,
        intent: { pageAliases: ['库存列表'], elementTerms: ['下一页'] },
        actions: [{ id: 'stuck-mask', type: 'paginate', safety: 'readonly', target: { kind: 'evidence', evidenceId: nextEvidenceId } }]
      }, { launchBrowser: launch }),
      (error) => error?.failureType === 'pagination_not_changed'
    )
  })
})

test('a navigation-style page turn is attributed while jumping away is not (DSL + IR)', async (context) => {
  // 复核方 C-6：href=?page=2 型服务端翻页会整页导航、观测哨兵随之丢失 —— 新文档出现不同列表内容时算
  // 真实翻页（两侧 passed）；跳到空白页（列表消失）时两侧同判 pagination_not_changed，不得 fail-open。
  const routes = {
    '/turn': `<!doctype html><h1>库存列表</h1>
      <ul id="rows"><li>第1页 行1</li></ul>
      <button data-testid="next">下一页</button>
      <script>document.querySelector('[data-testid=next]').addEventListener('click', () => { location.href = '/list?page=2' })</script>`,
    '/list?page=2': `<!doctype html><h1>库存列表</h1>
      <ul id="rows"><li>第2页 行1</li><li>第2页 行2</li></ul>
      <button data-testid="next">下一页</button>`,
    '/jump': `<!doctype html><h1>库存列表</h1>
      <ul id="rows"><li>第1页 行1</li></ul>
      <button data-testid="next">下一页</button>
      <script>document.querySelector('[data-testid=next]').addEventListener('click', () => { location.href = '/blank' })</script>`,
    '/blank': `<!doctype html><h1>空白页</h1>`
  }
  const server = http.createServer((request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(routes[request.url] || routes['/blank'])
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const origin = `http://127.0.0.1:${address.port}`
  const browser = await chromium.launch({ headless: true })
  context.after(() => browser.close())
  const turnPage = await browser.newPage()
  await turnPage.goto(`${origin}/turn`)
  await executeStep(turnPage, { action: 'paginate', target: 'testid=next', timeoutMs: 5_000 })
  const jumpPage = await browser.newPage()
  await jumpPage.goto(`${origin}/jump`)
  await assert.rejects(
    executeStep(jumpPage, { action: 'paginate', target: 'testid=next', timeoutMs: 5_000 }),
    (error) => error?.failureType === 'pagination_not_changed'
  )
  const launch = (_id, options) => chromium.launch(options)
  const intent = { pageAliases: ['库存列表'], elementTerms: ['下一页'] }
  const turnRecon = await discoverPageLocators({ url: `${origin}/turn`, browser: 'chromium', headless: true, timeoutMs: 3_000, intent }, { launchBrowser: launch })
  const turnId = turnRecon.elements.find((element) => element.accessibleName === '下一页')?.evidenceId
  assert.ok(turnId)
  const turnResult = await discoverPageLocators({ url: `${origin}/turn`, browser: 'chromium', headless: true, timeoutMs: 5_000, intent, actions: [{ id: 'nav-turn', type: 'paginate', safety: 'readonly', target: { kind: 'evidence', evidenceId: turnId } }] }, { launchBrowser: launch })
  assert.equal(turnResult.stepEvidence?.find((step) => step.actionId === 'nav-turn')?.execution?.status, 'passed')
  const jumpRecon = await discoverPageLocators({ url: `${origin}/jump`, browser: 'chromium', headless: true, timeoutMs: 3_000, intent }, { launchBrowser: launch })
  const jumpId = jumpRecon.elements.find((element) => element.accessibleName === '下一页')?.evidenceId
  assert.ok(jumpId)
  await assert.rejects(
    discoverPageLocators({ url: `${origin}/jump`, browser: 'chromium', headless: true, timeoutMs: 5_000, intent, actions: [{ id: 'nav-jump', type: 'paginate', safety: 'readonly', target: { kind: 'evidence', evidenceId: jumpId } }] }, { launchBrowser: launch }),
    (error) => error?.failureType === 'pagination_not_changed'
  )
})

test('the login-shell evidence predicate fires on login forms and spares business pages', () => {
  // 复核方 C-4/C-5：元素字段是 accessibleName / semanticType / type（*不是* name）；业务页里的
  // 「修改密码 / 保存 / 退出登录」不得命中。元素名取自复核方实测（c3f 的 登录|用户名|密码|登录、
  // A2-7 的 订单列表|设备列表|下一页|修改密码|保存|退出登录）。
  const looks = pageLocatorDiscoveryTestables.evidenceLooksLikeLoginShell
  const field = (accessibleName, extra = {}) => ({ tagName: 'input', semanticType: 'field', accessibleName, ...extra })
  const action = (accessibleName) => ({ tagName: 'button', semanticType: 'action', accessibleName })
  assert.equal(looks([field('用户名'), field('密码', { type: 'password' }), action('登录')]), true)
  assert.equal(looks([field('用户名'), action('登录')]), true)
  assert.equal(looks([field('订单列表'), field('设备列表'), action('下一页'), field('修改密码'), action('保存'), action('退出登录')]), false)
  assert.equal(looks([field('用户中心'), field('新密码', { type: 'password' }), action('保存')]), false)
  assert.equal(looks([field('手机号'), action('立即登录')]), true)
  assert.equal(looks([action('登录')]), false)
  assert.equal(looks([]), false)
  assert.equal(looks(undefined), false)
})


test('a failed discovery with reuseSession and no live session still closes the launched browser', async () => {
  // 用户核查（2026-10-14）：reuseSession=true 且无现成会话时，早失败（入口放行断言/登录/导航）
  // 会留下无主浏览器：retainSession 必须在「确实持有会话」时才为 true，否则 finally 必须关闭本次启动的浏览器。
  const sessionCountBefore = sessionCount()
  const state = { launches: 0, contextClosed: 0, pageClosed: 0, browserClosed: 0 }
  const page = {
    on: () => {},
    off: () => {},
    listenerCount: () => 0,
    setDefaultTimeout: () => {},
    setDefaultNavigationTimeout: () => {},
    goto: async () => {},
    url: () => 'https://blocked.example/#/project',
    close: async () => { state.pageClosed += 1 }
  }
  const context = {
    addCookies: async () => {},
    route: async () => {},
    unroute: async () => {},
    newPage: async () => page,
    close: async () => { state.contextClosed += 1 }
  }
  const browser = {
    newContext: async () => context,
    close: async () => { state.browserClosed += 1 }
  }
  await assert.rejects(
    discoverPageLocators({
      url: 'https://blocked.example/#/project',
      baseUrl: 'https://blocked.example',
      sessionKey: 'blocked.example',
      browser: 'chromium',
      reuseSession: true
    }, {
      launchBrowser: async () => {
        state.launches += 1
        return browser
      },
      assertAllowedUrl: async (value) => {
        throw new Error(`blocked target: ${value}`)
      }
    }),
    /blocked target/
  )
  assert.equal(state.launches, 1)
  assert.equal(state.pageClosed, 1)
  assert.equal(state.contextClosed, 1)
  assert.equal(state.browserClosed, 1)
  assert.equal(sessionCount(), sessionCountBefore)
})

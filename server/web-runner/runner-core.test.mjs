import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildBrowserContextCookies,
  buildBrowserContextOptions,
  buildCaseTestSteps,
  buildLoginSetupSteps,
  buildLogoutTeardownSteps,
  buildRunnableSteps,
  executeStep,
  formatStepFailureMessage
} from './runner-core.mjs'
import { urlMatchesExpected } from './locator-utils.mjs'
import { hasChangedRoutePath, hasLeftLoginRoute, waitForLoginRouteExit } from './login-route.mjs'

test('login route comparison does not treat an in-page hash as a different route', async () => {
  // 复核方 C-3：业务路由 + 页内 hash 跳转（/spa-pw → /spa-pw#/list）曾被判成「离开了登录路由」，
  // 合法采集被整轮丢弃。hash 路由只在应用挂在根路径时才算路由。
  assert.equal(hasLeftLoginRoute('https://example.com/spa-pw', 'https://example.com/spa-pw#/list'), false)
  assert.equal(hasLeftLoginRoute('https://example.com/#/login', 'https://example.com/#/app'), true)
  assert.equal(hasLeftLoginRoute('https://example.com/#/login?error=1', 'https://example.com/#/app?r=2'), true)
  assert.equal(hasLeftLoginRoute('https://example.com/login', 'https://example.com/app'), true)
  assert.equal(hasLeftLoginRoute('https://example.com/#/login', 'https://example.com/#/login?error=1'), false)
  assert.equal(hasLeftLoginRoute('https://a.example.com/login', 'https://b.example.com/login'), true)
  // hash 应用挂在任意前缀下都算路由；根路径出现 hash 也算路由变化（会话失效回跳门依赖这条）。
  assert.equal(hasLeftLoginRoute('https://example.com/hash-app#/login?error=1', 'https://example.com/hash-app#/login'), false)
  assert.equal(hasLeftLoginRoute('https://example.com/hash-app#/login', 'https://example.com/hash-app#/project'), true)
  assert.equal(hasLeftLoginRoute('https://example.com/', 'https://example.com/#/app'), true)
  // 采集结束的**会话门**只比 origin + pathname：SPA 采集期间在 hash 路由间跳不算离开起始路由（复核方 C-3），
  // 回跳登录页（pathname 变化）才算（复核方 high-2 / D2）。
  assert.equal(hasChangedRoutePath('https://example.com/spa-pw#/home', 'https://example.com/spa-pw#/list'), false)
  assert.equal(hasChangedRoutePath('https://example.com/app', 'https://example.com/login'), true)
  // 302 到一个挂在根路径下的 hash 登录页同样是 pathname 变化（`/app` → `/`）：会话门要判离开。
  assert.equal(hasChangedRoutePath('https://example.com/app', 'https://example.com/#/login'), true)
  assert.equal(hasChangedRoutePath('https://a.example.com/app', 'https://b.example.com/app'), true)
  // 复核方 14 轮 C-①：等到离开登录路由必须**轮询**（异步提交登录成功后 ~350ms 才跳转）——
  // 采集侧曾用单发检查（点击后 174ms 就判没离开），与 DSL 的 100ms 轮询口径不一致。
  const makePage = (urls) => {
    let index = 0
    return {
      url: () => urls[Math.min(index, urls.length - 1)],
      waitForTimeout: async () => { index += 1; await new Promise((resolve) => setTimeout(resolve, 1)) }
    }
  }
  assert.equal(await waitForLoginRouteExit(makePage(['https://example.com/app']), 'https://example.com/login', 50), true)
  assert.equal(await waitForLoginRouteExit(makePage(['https://example.com/login', 'https://example.com/login', 'https://example.com/app']), 'https://example.com/login', 1_000), true)
  assert.equal(await waitForLoginRouteExit(makePage(['https://example.com/login']), 'https://example.com/login', 50), false)
})

test('empty expectations fail closed instead of passing vacuously', async () => {
  // 空期望值曾让断言恒真：assertUrl 用 currentUrl.includes('')、assertText 用 text.includes('')、
  // waitForUrl 在 expected 为空时直接 return true —— 步骤全部记 passed，实际什么都没验证。
  const page = {
    url: () => 'https://example.com/list',
    locator: () => ({ textContent: async () => '列表', waitFor: async () => {} }),
    waitForFunction: async () => { throw new Error('empty expectation must not reach the page') }
  }
  await assert.rejects(
    () => executeStep(page, { action: 'assertUrl', target: '' }),
    (error) => error.failureType === 'assertion_failed' && /expected URL is empty/.test(error.message)
  )
  await assert.rejects(
    () => executeStep(page, { action: 'assertText', target: '#title', value: '' }),
    (error) => error.failureType === 'assertion_failed' && /expected text is empty/.test(error.message)
  )
  await assert.rejects(
    () => executeStep(page, { action: 'waitForUrl', target: '' }),
    (error) => error.failureType === 'assertion_failed' && /expected URL is empty/.test(error.message)
  )
  assert.equal(urlMatchesExpected('https://example.com/list', ''), false, '空期望值不构成匹配')
})

test('assertUrl uses route semantics instead of substring prefixes', async () => {
  // 复核方实测：startUrl=/listing + assertUrl /list 曾 passed（'' 的空期望修过之后，子串匹配仍然 fail-open：
  // 'http://host/list' 是 'http://host/listing' 的前缀子串）。统一走 urlMatchesExpected（origin + 路由比较）。
  const on = (url) => ({ url: () => url, waitForFunction: async () => {} })
  await executeStep(on('https://example.com/list'), { action: 'assertUrl', target: '/list' })
  await executeStep(on('https://example.com/list/detail'), { action: 'assertUrl', target: '/list' }, { baseUrl: 'https://example.com' })
  await executeStep(on('https://example.com/#/project'), { action: 'assertUrl', target: '#/project' })
  await assert.rejects(
    () => executeStep(on('https://example.com/listing'), { action: 'assertUrl', target: '/list' }),
    (error) => error.failureType === 'assertion_failed' && /assertUrl failed/.test(error.message)
  )
  await assert.rejects(
    () => executeStep(on('https://example.com/list'), { action: 'assertUrl', target: 'https://other.example.com/list' }),
    (error) => error.failureType === 'assertion_failed'
  )
})

test('goto without a target fails closed instead of silently opening the base url', async () => {
  // 空目标会被 Playwright 的 baseURL 解析成环境首页（实测 goto '' → 打开 baseUrl 并记 passed），
  // 与 assertUrl/assertText 的空期望恒真是同一类缺陷。
  const page = { goto: async () => { throw new Error('goto must not be called for an empty target') }, url: () => 'about:blank' }
  await assert.rejects(
    () => executeStep(page, { action: 'goto', target: '' }, { baseUrl: 'https://example.com' }),
    (error) => error.failureType === 'invalid_action_ir' && /requires a target URL/.test(error.message)
  )
})

test('a failed hash-router login is not counted as leaving the login route', async () => {
  // 复核方 F1：hash 路由里的查询串不属于路由，#/login -> #/login?error=1 仍是登录页，
  // 只有 #/login -> #/project 才算离开（旧实现把 '?...' 一起比较，登录失败被记成成功）。
  const step = { id: 'setup-login-success', action: 'waitForUrl', target: '', timeoutMs: 250 }
  const failedLogin = {
    url: () => 'https://example.com/hash-app#/login?error=1',
    waitForTimeout: async () => {}
  }
  await assert.rejects(
    () => executeStep(failedLogin, step, { loginPageUrl: 'https://example.com/hash-app#/login' }),
    (error) => error.failureType === 'assertion_failed' && /did not leave the login route/.test(error.message)
  )
  const hashApp = {
    url: () => 'https://example.com/hash-app#/login?error=1',
    waitForTimeout: async () => {}
  }
  await executeStep({
    ...hashApp,
    url: () => 'https://example.com/hash-app#/project'
  }, step, { loginPageUrl: 'https://example.com/hash-app#/login' })
})

test('login success without a target verifies leaving the login route instead of being swallowed', async () => {
  // 旧行为：空判定目标 → 底层错误（css 选择器/断言）被宽松 catch 吞掉，且 runtime.loginPageUrl 缺失时
  // hasLeftLoginRoute(undefined, …) 直接返回 true ⇒ 登录失败也记 passed。现在改成可判定的「离开登录路由」。
  const step = { id: 'setup-login-success', action: 'waitForUrl', target: '' }
  const leftLogin = {
    url: () => 'https://example.com/#/project',
    waitForTimeout: async () => {}
  }
  await executeStep(leftLogin, step, { loginPageUrl: 'https://example.com/#/login', login: { afterLoginUrl: 'https://example.com/#/login' } })

  const stillLogin = {
    url: () => 'https://example.com/#/login',
    waitForTimeout: async () => {}
  }
  await assert.rejects(
    () => executeStep(stillLogin, { ...step, timeoutMs: 250 }, { loginPageUrl: 'https://example.com/#/login' }),
    (error) => error.failureType === 'assertion_failed' && /did not leave the login route/.test(error.message)
  )
  await assert.rejects(
    () => executeStep(leftLogin, step, {}),
    (error) => error.failureType === 'assertion_failed' && /cannot be verified/.test(error.message)
  )

  // 非空判定目标 + 中途离开登录路由：保留原有的「宽松通过」语义，但不能在没有登录页地址时兜底放行。
  const failingWait = {
    locator: () => ({ waitFor: async () => { throw new Error('Timeout 30000ms exceeded') } }),
    url: () => 'https://example.com/#/project'
  }
  await executeStep(failingWait, { id: 'setup-login-success', action: 'waitForVisible', target: '#avatar' }, { loginPageUrl: 'https://example.com/#/login' })
  await assert.rejects(
    () => executeStep(failingWait, { id: 'setup-login-success', action: 'waitForVisible', target: '#avatar' }, {}),
    /Timeout 30000ms exceeded/
  )
})

test('executeStep supports manual, select, hover, waitForVisible, waitForUrl, assertText, assertUrl, screenshot', async () => {
  const calls = []
  const page = {
    goto: async (target, options) => calls.push(['goto', target, options]),
    waitForURL: async (target) => calls.push(['waitForURL', target]),
    waitForFunction: async (_predicate, payload, options) => calls.push(['waitForFunction', payload.expectedUrl, options]),
    url: () => 'https://example.com/dashboard',
    screenshot: async () => calls.push(['screenshot']),
    locator: (target) => ({
      click: async () => calls.push(['click', target]),
      fill: async (value) => calls.push(['fill', target, value]),
      hover: async () => calls.push(['hover', target]),
      selectOption: async (value) => calls.push(['selectOption', target, value]),
      waitFor: async (options) => calls.push(['waitFor', target, options]),
      textContent: async () => {
        calls.push(['textContent', target])
        return 'Dashboard Ready'
      }
    }),
    getByText: (target) => ({
      waitFor: async (options) => calls.push(['waitForText', target, options])
    }),
    getByRole: (role, options = {}) => ({
      click: async () => calls.push(['clickRole', role, options]),
      waitFor: async (waitOptions) => calls.push(['waitForRole', role, options, waitOptions])
    }),
    getByTestId: (target) => ({
      click: async () => calls.push(['clickTestId', target])
    })
  }

  await executeStep(page, { action: 'manual', target: '登录系统并进入用户中心任一页面' })
  await executeStep(page, { action: 'select', target: '#role', value: 'admin' })
  await executeStep(page, { action: 'hover', target: '#menu' })
  await executeStep(page, { action: 'waitForVisible', target: '#dashboard' })
  await executeStep(page, { action: 'waitForUrl', target: '/dashboard' })
  await executeStep(page, { action: 'assertText', target: '#dashboard', value: 'Ready' })
  await executeStep(page, { action: 'assertUrl', target: '/dashboard' })
  await executeStep(page, { action: 'click', target: 'role=button[name="登录"]' })
  await executeStep(page, { action: 'waitForVisible', target: 'text=用户中心' })
  await executeStep(page, { action: 'click', target: 'testid=submit-login' })
  await executeStep(page, { action: 'screenshot', target: '' })

  assert.deepEqual(calls, [
    ['selectOption', '#role', 'admin'],
    ['hover', '#menu'],
    ['waitFor', '#dashboard', { state: 'visible' }],
    ['waitForFunction', '/dashboard', {}],
    ['textContent', '#dashboard'],
    ['clickRole', 'button', { name: '登录' }],
    ['waitForText', '用户中心', { state: 'visible' }],
    ['clickTestId', 'submit-login'],
    ['screenshot']
  ])
})

test('executeStep waits for hash route url matches without waiting for a document load', async () => {
  const calls = []
  const page = {
    waitForFunction: async (_predicate, payload, options) => calls.push(['waitForFunction', payload.expectedUrl, options]),
    url: () => 'https://app-dev.example.test/#/login'
  }

  await executeStep(page, { action: 'waitForUrl', target: 'https://app-dev.example.test/#/project', timeoutMs: 45_000 })

  assert.deepEqual(calls, [
    ['waitForFunction', 'https://app-dev.example.test/#/project', { timeout: 45_000 }]
  ])
})

test('urlMatchesExpected treats same-origin hash and path SPA routes as compatible', () => {
  assert.equal(
    urlMatchesExpected(
      'https://app-dev.example.test/project/list?keyword=test',
      'https://app-dev.example.test/#/project'
    ),
    true
  )
  assert.equal(
    urlMatchesExpected(
      'https://app-dev.example.test/#/project/list?keyword=test',
      'https://app-dev.example.test/project'
    ),
    true
  )
  assert.equal(
    urlMatchesExpected(
      'https://app-dev.example.test/#/project-set',
      'https://app-dev.example.test/#/project'
    ),
    false
  )
})

test('executeStep reports the actual current URL when url waiting times out', async () => {
  const page = {
    url: () => 'https://app-dev.example.test/#/login?from=%2Fproject',
    waitForFunction: async () => {
      throw new Error('page.waitForFunction: Timeout 45000ms exceeded')
    }
  }

  await assert.rejects(
    () => executeStep(page, {
      action: 'waitForUrl',
      target: 'https://app-dev.example.test/#/project',
      timeoutMs: 45_000
    }),
    (error) => /current URL: "https:\/\/app-dev\.example\.test\/#\/login\?from=%2Fproject"/.test(error.message)
      // 必须带分类：裸 Error 会被 runner HTTP 层映射成 503「runner 不可用」，断言失败被降级成基础设施异常。
      && error.failureType === 'assertion_failed' && error.category === 'assertion_failed'
  )
})

test('executeStep explains login redirects when a protected route is not authenticated', async () => {
  const page = {
    url: () => 'https://app-dev.example.test/#/login?from=%2F',
    waitForFunction: async () => {
      throw new Error('page.waitForFunction: Timeout 45000ms exceeded')
    }
  }

  await assert.rejects(
    () => executeStep(page, {
      action: 'waitForUrl',
      target: 'https://app-dev.example.test/#/project',
      timeoutMs: 45_000
    }),
    /目标页面被登录页拦截/
  )
})

test('executeStep supports xpath and camel-case testId locator prefixes', async () => {
  const calls = []
  const page = {
    locator: (target) => ({
      waitFor: async (options) => calls.push(['waitFor', target, options]),
      click: async () => calls.push(['click', target])
    }),
    getByTestId: (target) => ({
      click: async () => calls.push(['clickTestId', target])
    })
  }

  await executeStep(page, { action: 'assertVisible', target: '//button[normalize-space()="登录"]' })
  await executeStep(page, { action: 'assertVisible', target: 'xpath=//main//button[1]' })
  await executeStep(page, { action: 'click', target: 'testId=submit-login' })

  assert.deepEqual(calls, [
    ['waitFor', 'xpath=//button[normalize-space()="登录"]', { state: 'visible' }],
    ['waitFor', 'xpath=//main//button[1]', { state: 'visible' }],
    ['clickTestId', 'submit-login']
  ])
})

test('executeStep scrollIntoView waits for the target to become visible', async () => {
  const calls = []
  const locator = {
    scrollIntoViewIfNeeded: async (options) => calls.push(['scroll', options]),
    waitFor: async (options) => calls.push(['wait', options])
  }
  const page = { locator: () => locator }

  await executeStep(page, { action: 'scrollIntoView', target: 'css=#target', timeoutMs: 1234 })

  assert.deepEqual(calls, [
    ['scroll', { timeout: 1234 }],
    ['wait', { state: 'visible', timeout: 1234 }]
  ])
})

test('executeStep assertEnabled/assertDisabled decide on the locator enabled state', async () => {
  const makePage = (enabled) => {
    const calls = []
    const locator = {
      waitFor: async (options) => calls.push(['waitFor', options]),
      isEnabled: async () => enabled
    }
    return { calls, page: { locator: () => locator } }
  }

  // 目标可用：assertEnabled 通过、assertDisabled 必须以 assertion_failed 失败。
  const on = makePage(true)
  await executeStep(on.page, { action: 'assertEnabled', target: 'css=#submit', timeoutMs: 77 })
  assert.deepEqual(on.calls, [['waitFor', { state: 'visible', timeout: 77 }]])
  await assert.rejects(
    () => executeStep(on.page, { action: 'assertDisabled', target: 'css=#submit', timeoutMs: 77 }),
    (error) => error.failureType === 'assertion_failed' && error.category === 'assertion_failed'
  )

  // 目标不可用：assertDisabled 通过、assertEnabled 必须以 assertion_failed 失败。
  const off = makePage(false)
  await executeStep(off.page, { action: 'assertDisabled', target: 'css=#cancel' })
  await assert.rejects(
    () => executeStep(off.page, { action: 'assertEnabled', target: 'css=#cancel' }),
    (error) => error.failureType === 'assertion_failed' && error.category === 'assertion_failed'
  )
})

test('executeStep paginate requires the shared signature to change', async () => {
  const calls = []
  let snapshot = { indicator: '1', head: '行一', digests: 'd1', tail: 't', count: 1, loading: false }
  const page = {
    locator: (target) => ({ click: async () => calls.push('click') }),
    getByRole: (role, options) => ({ click: async () => calls.push(['role', role, options]) }),
    evaluate: async () => snapshot,
    waitForTimeout: async () => {},
    waitForFunction: async (predicate, payload, options) => {
      calls.push(['waitForFunction', payload, options])
      // 谓词必须是「自带实现」的函数：字符串形式在 Playwright 里会被当表达式反复求值，
      // 传 (arg) => {...} 只会得到一个恒真的函数对象。容器集合与采集侧共用（ul/ol 也要看，复核方 F5）。
      assert.equal(typeof predicate, 'function')
      assert.match(String(predicate), /table tbody/)
      assert.match(String(predicate), /ul, ol/)
      assert.equal(payload.before, JSON.stringify(['1', '行一', 'd1', 't', 1]))
      snapshot = { indicator: '2', head: '行二', digests: 'd2', tail: 't', count: 1, loading: false }
    }
  }

  await executeStep(page, { action: 'paginate', target: 'role=button[name="Next", exact=true]' })

  assert.deepEqual(calls.map((item) => Array.isArray(item) ? item[0] : item), ['role', 'waitForFunction'])
})

test('executeStep paginate does not attribute a self-updating page to the click', async () => {
  // 页面自己在刷新（时钟/自动刷新表格）：变化按节拍反复出现、永远静默不下来 ⇒ 不归因给点击
  // （复核方 X2/D3：250ms 预采样 + 固定安静窗口都挡不住慢刷新，只有节律判据能）。
  let sample = 0
  const page = {
    locator: () => ({ click: async () => {} }),
    getByRole: () => ({ click: async () => {} }),
    evaluate: async () => {
      sample += 1
      return { indicator: '1', head: `自动行 ${sample}`, digests: `d${sample}`, tail: 't', count: 1, loading: false }
    },
    waitForTimeout: async () => {},
    waitForFunction: async () => {}
  }

  await assert.rejects(
    () => executeStep(page, { action: 'paginate', target: '#next', timeoutMs: 1_500 }),
    (error) => error.failureType === 'pagination_not_changed'
  )
})

test('executeStep paginate reports pagination_not_changed when the page remains identical', async () => {
  const snapshot = { indicator: '1', head: '行一', digests: 'd1', tail: 't', count: 1, loading: false }
  const page = {
    locator: () => ({ click: async () => {} }),
    getByRole: () => ({ click: async () => {} }),
    url: () => 'https://example.test/list',
    evaluate: async () => snapshot,
    waitForTimeout: async () => {},
    // 真实 Playwright 的等待超时是 TimeoutError；普通 Error('timeout') 必须分类成 observation_failed
    // （旧桩用裸 Error 掩盖了「任何错误都被报成 pagination_not_changed」的缺陷）。
    waitForFunction: async () => {
      const error = new Error('page.waitForFunction: Timeout 2000ms exceeded.')
      error.name = 'TimeoutError'
      throw error
    }
  }

  await assert.rejects(
    () => executeStep(page, { action: 'paginate', target: '#next', timeoutMs: 25 }),
    (error) => error.failureType === 'pagination_not_changed' && error.message.includes('pagination_not_changed')
  )
})

test('a slow self-refreshing page is not attributed to the click (stub level)', async () => {
  // 复核方 X2/D3 的桩级锁定：250ms 预采样看不到慢周期，节律判据（静默 ≥ max(1s, 1.25×最大间隔)）
  // 必须把「每次等待都改写列表」的页面拦住。
  let tick = 0
  let clicked = false
  const page = {
    locator: () => ({ click: async () => { clicked = true } }),
    getByRole: () => ({ click: async () => {} }),
    evaluate: async () => ({ indicator: '1', head: `自动 行${tick}`, digests: `d${tick}`, tail: 't', count: 1, loading: false }),
    // 点击后页面自己在刷新（每次等待都改写列表）。
    waitForTimeout: async () => { if (clicked) tick += 1 },
    waitForFunction: async () => { tick += 1 }
  }
  await assert.rejects(
    () => executeStep(page, { action: 'paginate', target: '#next', timeoutMs: 1_500 }),
    (error) => error.failureType === 'pagination_not_changed'
  )
})

test('a real asynchronous page turn is attributed to the click (stub level)', async () => {
  // 复核方 D1：真实翻页先出加载态、数据晚到，然后停住 ⇒ 静默成立、归因给这次点击（不能因为
  // 「又变了一次」就判 pagination_not_changed）。
  let staged = 0
  const models = [
    { indicator: '1', head: '第1页 行1', digests: 'd1', tail: 't', count: 1, loading: false },
    { indicator: '1', head: '加载中…', digests: 'd-loading', tail: 't', count: 1, loading: false },
    { indicator: '1', head: '第2页 行1', digests: 'd2', tail: 't', count: 1, loading: false }
  ]
  const page = {
    locator: () => ({ click: async () => { staged = 1 } }),
    getByRole: () => ({ click: async () => {} }),
    evaluate: async () => models[staged],
    waitForTimeout: async () => { staged = Math.min(2, staged + 1) },
    waitForFunction: async () => { staged = 1 }
  }

  await executeStep(page, { action: 'paginate', target: '#next', timeoutMs: 3_000 })

  assert.equal(staged, 2)
})

test('a change that reverts to the pre-click content is not attributed to the click', async () => {
  // 复核方 C3：点击后先出占位/临时数据、随后回滚到原样 ⇒ 最终 DOM 与点击前完全相同，必须判失败
  // （旧实现把「回退」当成又一次变化，静默满足后直接放过）。用真实时间建模：
  // 占位在点击后 500ms 出现（晚到的纯内容变化 ⇒ 无响应），1400ms 回滚 ⇒ 必须在回滚时判失败。
  let clickedAt = 0
  const snapshot = () => {
    const elapsed = Date.now() - clickedAt
    return elapsed >= 500 && elapsed < 1400
      ? { indicator: '1', head: '占位中…', digests: 'd-temp', tail: 't', count: 1, loading: false, placeholderOnly: true }
      : { indicator: '1', head: '原始 行1', digests: 'd1', tail: 't', count: 1, loading: false }
  }
  const page = {
    locator: () => ({ click: async () => { clickedAt = Date.now() } }),
    getByRole: () => ({ click: async () => {} }),
    evaluate: async () => snapshot(),
    waitForTimeout: async (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
    waitForFunction: async () => {}
  }

  await assert.rejects(
    () => executeStep(page, { action: 'paginate', target: '#next', timeoutMs: 5_000 }),
    (error) => error.failureType === 'pagination_not_changed' && /回退/.test(error.message)
  )
})

test('an indicator-only change (click state, carousel, wizard step) is not a page turn', async () => {
  // 复核方 C4：被点按钮自己加 .is-active / 轮播 .is-active / 向导 aria-current 都可能命中通用指示器
  // 选择器，但内容没变 ⇒ 不是翻页证据（指示器必须在分页容器内，且内容要真的变化）。
  const snapshot = { indicator: '1', indicatorScoped: false, head: '行1', digests: 'd1', tail: 't', count: 1, loading: false }
  const page = {
    locator: () => ({ click: async () => { snapshot.indicator = '下一页' } }),
    getByRole: () => ({ click: async () => {} }),
    evaluate: async () => ({ ...snapshot }),
    waitForTimeout: async () => {},
    waitForFunction: async () => {}
  }

  await assert.rejects(
    () => executeStep(page, { action: 'paginate', target: '#next', timeoutMs: 1_500 }),
    (error) => error.failureType === 'pagination_not_changed'
  )
})

test('a slow self-refresh with no click response is not attributed to the click', async () => {
  // 复核方 D3 的另一面：点击后 1s 内毫无响应（首次自刷新在 2s），随后每 2s 自刷新一次 ⇒
  // 无响应时要求静默 11s，周期刷新永远攒不出来 ⇒ 必须判失败（不能因为「变了一次就停了」而放过）。
  let tick = 0
  let clicked = false
  const page = {
    locator: () => ({ click: async () => { clicked = true } }),
    getByRole: () => ({ click: async () => {} }),
    evaluate: async () => ({ indicator: '1', head: `自动 行${tick}`, digests: `d${tick}`, tail: 't', count: 1, loading: false }),
    waitForTimeout: async () => { if (clicked) tick += 1 },
    waitForFunction: async () => {}
  }

  await assert.rejects(
    () => executeStep(page, { action: 'paginate', target: '#next', timeoutMs: 2_000 }),
    (error) => error.failureType === 'pagination_not_changed'
  )
})

test('executeStep resolves relative urls and substitutes variables in input values', async () => {
  const calls = []
  const page = {
    goto: async (target, options) => calls.push(['goto', target, options]),
    locator: (target) => ({
      fill: async (value) => calls.push(['fill', target, value])
    }),
    waitForURL: async () => {},
    url: () => 'https://app.example.test/dashboard',
    screenshot: async () => {}
  }

  await executeStep(page, { action: 'goto', target: '/login' }, {
    baseUrl: 'https://app.example.test/app',
    waitUntil: 'networkidle'
  })
  await executeStep(page, {
    action: 'input',
    target: 'css=#username',
    value: '{{webUsername}}'
  }, {
    variables: {
      webUsername: 'admin'
    }
  })

  assert.deepEqual(calls, [
    ['goto', 'https://app.example.test/login', { waitUntil: 'networkidle' }],
    ['fill', '#username', 'admin']
  ])
})

test('executeStep resolves root paths against hash-route Web runtime base urls', async () => {
  const calls = []
  const page = {
    goto: async (target, options) => calls.push(['goto', target, options])
  }

  await executeStep(page, { action: 'goto', target: '/project' }, {
    baseUrl: 'https://app-dev.example.test/#/'
  })
  await executeStep(page, { action: 'goto', target: '#/alarm' }, {
    baseUrl: 'https://app-dev.example.test/#/'
  })
  await executeStep(page, { action: 'goto', target: '/#/login' }, {
    baseUrl: 'https://app-dev.example.test/#/'
  })

  assert.deepEqual(calls, [
    ['goto', 'https://app-dev.example.test/#/project', { waitUntil: 'domcontentloaded' }],
    ['goto', 'https://app-dev.example.test/#/alarm', { waitUntil: 'domcontentloaded' }],
    ['goto', 'https://app-dev.example.test/#/login', { waitUntil: 'domcontentloaded' }]
  ])
})

test('executeStep fails fast when a referenced variable is missing', async () => {
  const page = {
    locator: () => ({
      fill: async () => {}
    })
  }

  await assert.rejects(
    () => executeStep(page, { action: 'input', target: '#username', value: '{{webUsername}}' }, { variables: {} }),
    /Missing runtime variable: webUsername/
  )
})

test('executeStep scopes an ambiguous environment login button to the password form', async () => {
  const calls = []
  const scopedButton = {
    count: async () => 1,
    click: async () => calls.push('scoped-login-click')
  }
  const passwordForm = {
    count: async () => 1,
    getByRole: () => scopedButton
  }
  const page = {
    getByRole: () => ({
      count: async () => 2,
      click: async () => calls.push('ambiguous-global-click')
    }),
    locator: (target) => target === '#password'
      ? {
          count: async () => 1,
          locator: () => passwordForm
        }
      : { count: async () => 0 }
  }

  await executeStep(page, {
    id: 'setup-login-submit',
    phase: 'setup',
    action: 'click',
    target: 'role=button[name="登录"]',
    scopeLocator: 'css=#password'
  })

  assert.deepEqual(calls, ['scoped-login-click'])
})

test('formatStepFailureMessage reports a failed login request before the success wait timeout', () => {
  const message = formatStepFailureMessage(
    { id: 'setup-login-success' },
    new Error('locator.waitFor: Timeout 30000ms exceeded'),
    [
      'POST https://posthog.example.com/flags -> net::ERR_FAILED',
      'POST https://api.example.com/v4/uc/n/auth/login -> net::ERR_FAILED'
    ],
    {
      login: {
        enabled: true,
        loginUrl: 'https://app-dev.example.test/#/login',
        usernameLocator: 'css=input[name="username"]',
        passwordLocator: 'css=input[type="password"]',
        submitLocator: 'testId=submit-login',
        username: 'admin',
        password: 'secret',
        successType: 'url',
        successTarget: '/project'
      }
    }
  )

  assert.match(message, /登录请求失败/)
  assert.match(message, /auth\/login/)
  assert.match(message, /loginUrl=https:\/\/app-dev\.example\.test\/#\/login/)
  assert.match(message, /submitLocator=testId=submit-login/)
  assert.equal(message.includes('admin'), false)
  assert.equal(message.includes('secret'), false)
  assert.match(message, /原始等待错误/)
})

test('formatStepFailureMessage explains local network address-space blocking', () => {
  const message = formatStepFailureMessage(
    { id: 'setup-login-success' },
    new Error('waitForUrl failed'),
    ['POST https://api.app-dev.example.test/v4/uc/n/auth/login -> net::ERR_FAILED', 'Access to XMLHttpRequest was blocked by CORS policy: local address space'],
    { login: { enabled: true, loginUrl: 'https://app-dev.example.test/#/login', successType: 'url', successTarget: '/project' } }
  )

  assert.match(message, /本地网络访问被拦截/)
  assert.match(message, /APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS=true/)
})

test('executeStep accepts login success when the app leaves the captured login route', async () => {
  let currentUrl = 'https://example.com/#/login?from=%2F'
  const runtime = {}
  const page = {
    goto: async () => {},
    url: () => currentUrl,
    locator: () => ({
      waitFor: async () => {
        throw new Error('locator.waitFor: Timeout 30000ms exceeded')
      }
    })
  }

  await executeStep(page, {
    id: 'setup-login-goto',
    action: 'goto',
    target: 'https://example.com'
  }, runtime)
  currentUrl = 'https://example.com/#/home'
  await assert.doesNotReject(() => executeStep(page, {
    id: 'setup-login-success',
    action: 'waitForVisible',
    target: 'css=._header_hash > svg'
  }, runtime))
})

test('executeStep keeps login success failures when the page remains on the login route', async () => {
  const runtime = {}
  const page = {
    goto: async () => {},
    url: () => 'https://example.com/#/login?from=%2F',
    locator: () => ({
      waitFor: async () => {
        throw new Error('locator.waitFor: Timeout 30000ms exceeded')
      }
    })
  }

  await executeStep(page, {
    id: 'setup-login-goto',
    action: 'goto',
    target: 'https://example.com'
  }, runtime)
  await assert.rejects(() => executeStep(page, {
    id: 'setup-login-success',
    action: 'waitForVisible',
    target: 'css=._header_hash > svg'
  }, runtime), /Timeout 30000ms/)
})

test('executeStep accepts url-based login success when the app leaves the captured login route', async () => {
  let currentUrl = 'https://app-dev.example.test/#/login?from=%2Fproject'
  const runtime = {}
  const page = {
    goto: async () => {},
    url: () => currentUrl,
    waitForFunction: async () => {
      throw new Error('page.waitForFunction: Timeout 45000ms exceeded')
    }
  }

  await executeStep(page, {
    id: 'setup-login-goto',
    action: 'goto',
    target: 'https://app-dev.example.test/#/login?from=%2Fproject'
  }, runtime)
  currentUrl = 'https://app-dev.example.test/#/home'

  await assert.doesNotReject(() => executeStep(page, {
    id: 'setup-login-success',
    action: 'waitForUrl',
    target: 'https://app-dev.example.test/#/project',
    timeoutMs: 45_000
  }, runtime))
})

test('buildBrowserContextCookies tolerates an explicit null extraHeaders from the platform DTO', () => {
  // 默认参数只对 undefined 生效：平台 DTO 把未配置的可选字段序列化成 null，
  // 之前会在这里抛 TypeError（Cannot convert undefined or null to object），
  // 整个取证请求被降级成 503「runner 不可用」。
  const cookies = buildBrowserContextCookies({
    baseUrl: 'https://app.example.test',
    extraHeaders: null
  })
  assert.deepEqual(cookies, [])

  const withNullHeaders = buildBrowserContextOptions({ baseUrl: 'https://app.example.test', extraHeaders: null })
  assert.equal(withNullHeaders.baseURL, 'https://app.example.test')
})

test('buildBrowserContextOptions uses Web runtime browser context settings instead of API request url', () => {
  const options = buildBrowserContextOptions({
    baseUrl: 'https://app.example.test',
    viewport: '1280x720',
    authToken: 'token-1',
    extraHeaders: {
      Cookie: 'haitlify-token=cookie-token; tenant_switch_sync=tenant-1',
      Origin: 'https://app.example.test',
      isAjax: '1'
    }
  }, {
    viewport: '1024x768'
  })

  assert.equal(options.baseURL, 'https://app.example.test')
  assert.deepEqual(options.viewport, { width: 1280, height: 720 })
  assert.deepEqual(options.extraHTTPHeaders, {
    Authorization: 'Bearer token-1',
    Origin: 'https://app.example.test',
    isAjax: '1'
  })
})

test('buildBrowserContextOptions ignores invalid header names before creating Playwright context', () => {
  const options = buildBrowserContextOptions({
    baseUrl: 'https://app.example.test',
    extraHeaders: {
      '': 'empty-name',
      ':authority': 'pseudo-header',
      'bad header': 'space-name',
      Cookie: 'haitlify-token=cookie-token',
      Origin: 'https://app.example.test',
      'x-auth-token': 'token-1'
    }
  })

  assert.deepEqual(options.extraHTTPHeaders, {
    Origin: 'https://app.example.test',
    'x-auth-token': 'token-1'
  })
})

test('buildBrowserContextCookies converts imported curl cookies into browser cookies for the Web base url', () => {
  const cookies = buildBrowserContextCookies({
    baseUrl: 'https://app.example.test',
    cookieOrigins: ['https://api.example.test'],
    extraHeaders: {
      Cookie: 'haitlify-token=cookie-token; tenant_switch_sync=tenant-1'
    }
  })

  assert.deepEqual(cookies.sort((left, right) => `${left.url}:${left.name}`.localeCompare(`${right.url}:${right.name}`)), [
    {
      name: 'haitlify-token',
      value: 'cookie-token',
      url: 'https://api.example.test'
    },
    {
      name: 'tenant_switch_sync',
      value: 'tenant-1',
      url: 'https://api.example.test'
    },
    {
      name: 'haitlify-token',
      value: 'cookie-token',
      url: 'https://app.example.test'
    },
    {
      name: 'tenant_switch_sync',
      value: 'tenant-1',
      url: 'https://app.example.test'
    }
  ])
})

test('buildRunnableSteps expands environment login and test steps in execution order', () => {
  const steps = buildRunnableSteps({
    authenticationMode: 'environment',
    startUrl: '/login',
    steps: [
      { id: 'step-1', name: '验证首页', action: 'assertVisible', target: 'text=用户管理' }
    ]
  }, {
    login: {
      enabled: true,
      loginUrl: '/#/login',
      usernameLocator: 'css=input[name="username"]',
      passwordLocator: 'css=input[type="password"]',
      submitLocator: 'role=button[name="登录"]',
      username: 'admin',
      password: 'secret',
      successType: 'url',
      successTarget: '/user-center'
    }
  })

  assert.deepEqual(steps.map((step) => [
    step.phase,
    step.action,
    step.target,
    step.value
  ]), [
    ['setup', 'goto', '/#/login', undefined],
    ['setup', 'input', 'css=input[name="username"]', 'admin'],
    ['setup', 'input', 'css=input[type="password"]', 'secret'],
    ['setup', 'click', 'role=button[name="登录"]', undefined],
    ['setup', 'waitForUrl', '/user-center', undefined],
    ['test', 'goto', '/login', undefined],
    ['test', 'assertVisible', 'text=用户管理', undefined]
  ])
  assert.equal(steps.find((step) => step.id === 'setup-login-submit').scopeLocator, 'css=input[type="password"]')
})

test('buildRunnableSteps can skip environment login for a case', () => {
  const steps = buildRunnableSteps({
    authenticationMode: 'skip',
    startUrl: '/user/list',
    steps: [
      { id: 'step-1', name: '验证用户管理', action: 'assertVisible', target: 'text=用户管理' }
    ]
  })

  assert.deepEqual(steps.map((step) => ({
    id: step.id,
    phase: step.phase,
    action: step.action,
    target: step.target
  })), [
    { id: 'open-start-url', phase: 'test', action: 'goto', target: '/user/list' },
    { id: 'step-1', phase: 'test', action: 'assertVisible', target: 'text=用户管理' }
  ])
})

test('buildRunnableSteps keeps probe assertions on the post-login page when no case start url is provided', () => {
  const steps = buildRunnableSteps({
    authenticationMode: 'environment',
    startUrl: '',
    steps: [
      { id: 'step-1', name: '确认登录成功元素', action: 'assertVisible', target: 'text=用户中心' }
    ]
  }, {
    baseUrl: 'https://app.example.test',
    login: {
      enabled: true,
      loginUrl: '/#/login',
      usernameLocator: 'css=#username',
      passwordLocator: 'css=#password',
      submitLocator: 'role=button[name="登录"]',
      username: 'admin',
      password: 'secret',
      successType: 'visible',
      successTarget: 'text=用户中心'
    }
  })

  assert.deepEqual(steps.map((step) => [step.phase, step.action, step.target]), [
    ['setup', 'goto', '/#/login'],
    ['setup', 'input', 'css=#username'],
    ['setup', 'input', 'css=#password'],
    ['setup', 'click', 'role=button[name="登录"]'],
    ['setup', 'waitForVisible', 'text=用户中心'],
    ['test', 'assertVisible', 'text=用户中心']
  ])
})

test('buildRunnableSteps opens the Web runtime base url when the case start url is empty', () => {
  const steps = buildRunnableSteps({
    startUrl: '   ',
    steps: [
      { id: 'step-1', name: '输入账号', action: 'input', target: '#username' }
    ]
  }, {
    baseUrl: 'https://app.example.test/login'
  })

  assert.deepEqual(steps.map((step) => ({
    id: step.id,
    name: step.name,
    phase: step.phase,
    action: step.action,
    target: step.target
  })), [
    { id: 'open-start-url', name: '打开运行环境入口', phase: 'test', action: 'goto', target: 'https://app.example.test/login' },
    { id: 'step-1', name: '输入账号', phase: 'test', action: 'input', target: '#username' }
  ])
})

test('buildRunnableSteps does not duplicate an explicit first goto step', () => {
  const steps = buildRunnableSteps({
    startUrl: '/login',
    steps: [
      { id: 'step-1', name: '打开登录页', action: 'goto', target: '/login' },
      { id: 'step-2', name: '输入账号', action: 'input', target: '#username' }
    ]
  })

  assert.deepEqual(steps.map((step) => step.id), ['step-1', 'step-2'])
})

test('buildCaseTestSteps excludes environment login and keeps only case navigation and steps', () => {
  const steps = buildCaseTestSteps({
    authenticationMode: 'environment',
    startUrl: '/users',
    steps: [
      { id: 'step-1', name: '验证用户列表', action: 'assertVisible', target: 'text=用户列表' }
    ]
  }, {
    baseUrl: 'https://app.example.test',
    login: {
      enabled: true,
      loginUrl: '/#/login',
      usernameLocator: 'css=#username',
      passwordLocator: 'css=#password',
      submitLocator: 'role=button[name="登录"]',
      username: 'admin',
      password: 'secret',
      successType: 'url',
      successTarget: '/users'
    }
  })

  assert.deepEqual(steps.map((step) => [step.id, step.phase, step.action, step.target]), [
    ['open-start-url', 'test', 'goto', '/users'],
    ['step-1', 'test', 'assertVisible', 'text=用户列表']
  ])
})

test('buildLoginSetupSteps and buildLogoutTeardownSteps create one browser lifecycle', () => {
  const runtime = {
    login: {
      enabled: true,
      loginUrl: '/#/login',
      usernameLocator: 'css=#username',
      passwordLocator: 'css=#password',
      submitLocator: 'role=button[name="登录"]',
      username: 'admin',
      password: 'secret',
      successType: 'url',
      successTarget: '/users'
    },
    logout: {
      enabled: true,
      steps: [
        { id: 'open-user-menu', name: '打开用户菜单', action: 'click', target: 'testid=user-menu' },
        { id: 'logout', name: '退出登录', action: 'click', target: 'testid=logout' },
        { id: 'logout-success', name: '确认返回登录页', action: 'waitForUrl', target: '/#/login' }
      ]
    }
  }

  assert.deepEqual(buildLoginSetupSteps(runtime).map((step) => [step.id, step.phase]), [
    ['setup-login-goto', 'setup'],
    ['setup-login-username', 'setup'],
    ['setup-login-password', 'setup'],
    ['setup-login-submit', 'setup'],
    ['setup-login-success', 'setup']
  ])
  assert.deepEqual(buildLogoutTeardownSteps(runtime).map((step) => [step.id, step.phase, step.action, step.target]), [
    ['open-user-menu', 'teardown', 'click', 'testid=user-menu'],
    ['logout', 'teardown', 'click', 'testid=logout'],
    ['logout-success', 'teardown', 'waitForUrl', '/#/login']
  ])
})

test('unknown DSL actions fail closed instead of silently passing', async () => {
  // 复核方实测：未实现的动作名（拼错、错拼大小写）走完 if 链后静默 return，整步记 passed——
  // 「什么都没做但全绿」。平台词表放行的动作名必须全部能执行，其余一律 fail-closed。
  const page = {
    url: () => 'https://example.com/list',
    locator: () => ({ click: async () => { throw new Error('unknown actions must not touch the page') } })
  }
  for (const action of ['clickk', 'wait_until_visible', 'scrollintoview', 'AssertVisible']) {
    await assert.rejects(
      () => executeStep(page, { action, target: 'testid=row' }),
      (error) => {
        assert.equal(error.failureType, 'unsupported_action', `${action} 必须抛 unsupported_action`)
        assert.equal(error.category, 'unsupported_action')
        return true
      }
    )
  }
})

test('snake_case action aliases execute the canonical step', async () => {
  // 平台词表同时接受 scroll_into_view / wait_for_visible 等写法，runner 侧必须执行而不是静默通过。
  const calls = []
  const page = {
    url: () => 'https://example.com/list',
    getByTestId: () => ({
      scrollIntoViewIfNeeded: async () => { calls.push('scrollIntoView') },
      waitFor: async (options) => { calls.push(['waitFor', options?.state]) }
    })
  }
  await executeStep(page, { action: 'scroll_into_view', target: 'testid=row' })
  await executeStep(page, { action: 'wait_for_visible', target: 'testid=row' })
  assert.deepEqual(calls, ['scrollIntoView', ['waitFor', 'visible'], ['waitFor', 'visible']])
})

test('login exit only counts when the route changed, not when the query changed', async () => {
  // 登录失败后跳 /login?error=1（或 GET 表单回同一路径带参数）曾被当成「已离开登录页」记 passed。
  const runtime = { loginPageUrl: 'http://127.0.0.1:9/login-query' }
  const step = { id: 'setup-login-success', action: 'waitForUrl', timeoutMs: 300 }
  const stillOnLogin = {
    url: () => 'http://127.0.0.1:9/login-query?username=admin&password=***',
    waitForTimeout: async () => {}
  }
  await assert.rejects(
    () => executeStep(stillOnLogin, step, runtime),
    (error) => {
      assert.match(String(error.message), /did not leave the login route/)
      assert.equal(error.failureType, 'assertion_failed')
      return true
    }
  )
  const leftLogin = { url: () => 'http://127.0.0.1:9/project/detail', waitForTimeout: async () => {} }
  await executeStep(leftLogin, step, runtime)
  // 同源、pathname 相同但 hash 路由不同算离开（hash 路由应用的登录页判定）。
  const hashRouted = { url: () => 'http://127.0.0.1:9/#/project', waitForTimeout: async () => {} }
  await executeStep(hashRouted, { ...step }, { loginPageUrl: 'http://127.0.0.1:9/#/login' })
})

test('waitForText without expected text is rejected before touching the page', async () => {
  const { executeStep } = await import('./runner-core.mjs')
  const step = { id: 's1', action: 'waitForText', target: '#list', value: '   ' }
  await assert.rejects(() => executeStep({}, step, {}), /waitForText 需要一个非空期望文本/)
})

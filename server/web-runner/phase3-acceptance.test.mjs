import test from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import { chromium } from 'playwright'
import { collectVisibleElements, observePageEvidence, resolveStepTargetEvidence } from './page-evidence.mjs'
import { resolveLocator } from './locator-utils.mjs'
import { discoverPageLocators } from './page-locator-discovery.mjs'

// 阶段 3 验收测试：步骤级 Browser Evidence
// 对应 docs/reviews/2026-09-09-ai-assisted-web-automation-stabilization-plan.md 的验收 1-6。
// 全部使用真实 Chromium 与真实 HTTP 页面，不使用手工 fixture。

const startHtmlServer = async (html, context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(html)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  return `http://127.0.0.1:${address.port}`
}

const startRouteServer = async (routes, context) => {
  const server = http.createServer((request, response) => {
    const path = String(request.url || '/').split('?')[0]
    const html = routes[path]
    if (!html) {
      response.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' })
      response.end('not found')
      return
    }
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(html)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  return `http://127.0.0.1:${address.port}`
}

const launchPage = async (url) => {
  const browser = await chromium.launch({ headless: true })
  const page = await browser.newPage()
  await page.goto(url, { waitUntil: 'domcontentloaded' })
  return { browser, page }
}

test('acceptance 4: a target beyond the legacy 120 element slice is still collected, and truncation is reported explicitly', async (context) => {
  const buttons = Array.from({ length: 200 }, (_value, index) => `<button data-testid="deep-${index + 1}">按钮${index + 1}</button>`).join('')
  const origin = await startHtmlServer(`<!doctype html><main>${buttons}</main>`, context)
  const { browser, page } = await launchPage(`${origin}/deep`)
  try {
    const evidence = await collectVisibleElements(page)
    assert.ok(evidence.some((entry) => entry.locator === 'testId=deep-150'), '第 150 个位置的元素必须被采集')

    const observation = await observePageEvidence(page)
    assert.equal(observation.elementsTruncated?.partitions?.main?.total, 200, '分区预算统计必须覆盖全部匹配元素')
    assert.equal(observation.elementsTruncated.dropped > 0, true, '按分区预算丢弃时必须返回 evidence_truncated')
    assert.equal(observation.elementsTruncated.budget > 0, true)

    const targeted = await collectVisibleElements(page, { budget: 30, targetedTerms: ['按钮150'] })
    assert.ok(targeted.some((entry) => entry.locator === 'testId=deep-150'), '定向查询必须优先于预算截断')

    const targetedObservation = await observePageEvidence(page, { budget: 30, targetedTerms: ['按钮150'] })
    assert.ok(targetedObservation.elementsTruncated, '超预算时必须返回 evidence_truncated 诊断')
    assert.equal(targetedObservation.elementsTruncated.dropped > 0, true)
    assert.equal(targetedObservation.elementsTruncated.total, 200)
    assert.equal(targetedObservation.elementsTruncated.collected, targetedObservation.elements.length)
    assert.ok(targetedObservation.elementsTruncated.partitions.main.total >= 200)
  } finally {
    await page.close()
    await browser.close()
  }
})

test('acceptance 5: iframe content is collected with frame scoped locators and unsupported frames are reported explicitly', async (context) => {
  const crossOrigin = await startHtmlServer('<!doctype html><button id="blocked">跨域按钮</button>', context)
  const hostOrigin = await startRouteServer({
    '/host': `<!doctype html><button id="outside">外部按钮</button>`
      + `<iframe id="inner" src="/frame"></iframe>`
      + `<iframe id="blocked" src="${crossOrigin}/blocked"></iframe>`,
    '/frame': '<!doctype html><button id="inside">帧内按钮</button>'
  }, context)
  const { browser, page } = await launchPage(`${hostOrigin}/host`)
  try {
    const evidence = await collectVisibleElements(page)
    const framed = evidence.find((entry) => String(entry.locator || '').includes('frame='))
    assert.ok(framed, '同源 iframe 内元素必须被采集')
    assert.equal(framed.framePath, '#inner')
    assert.equal(framed.frameUrl.endsWith('/frame'), true)
    assert.equal(await resolveLocator(page, framed.locator).count(), 1, 'frame 作用域 locator 必须可以真实解析')

    const observation = await observePageEvidence(page)
    const frames = observation.unsupported.frames || []
    const supported = frames.find((frame) => frame.framePath === '#inner')
    assert.equal(supported?.supported, true)
    const blocked = frames.find((frame) => frame.framePath === '#blocked')
    assert.equal(blocked?.supported, false)
    assert.equal(blocked?.reason, 'unsupported_frame')

    // 编译产物在 runner 执行期使用同一个 locator 解析路径：必须唯一且可见。
    const resolved = await resolveStepTargetEvidence(page, framed.locator)
    assert.equal(resolved.unique, true)
    assert.equal(resolved.visible, true)
  } finally {
    await page.close()
    await browser.close()
  }
})

test('acceptance 6: open shadow roots are collected recursively and closed shadow hosts are reported as unsupported', async (context) => {
  const origin = await startHtmlServer(`<!doctype html>
    <div id="host"></div>
    <x-closed></x-closed>
    <script>
      const open = document.querySelector('#host').attachShadow({ mode: 'open' })
      open.innerHTML = '<button id="shadow-btn">影子按钮</button>'
      class Closed extends HTMLElement {
        connectedCallback() {
          const root = this.attachShadow({ mode: 'closed' })
          root.innerHTML = '<button id="locked">封闭按钮</button>'
        }
      }
      customElements.define('x-closed', Closed)
    </script>`, context)
  const { browser, page } = await launchPage(`${origin}/shadow`)
  try {
    const evidence = await collectVisibleElements(page)
    const inShadow = evidence.find((entry) => entry.shadowPath === '#host')
    assert.ok(inShadow, 'open shadow root 内元素必须被采集')
    assert.equal(inShadow.locator.startsWith('css=#host >> '), true)
    assert.equal(await resolveLocator(page, inShadow.locator).count(), 1, 'shadow 作用域 locator 必须可以真实解析')

    const observation = await observePageEvidence(page)
    const closed = observation.unsupported.closedShadowHosts || []
    assert.equal(closed.some((host) => host.hostPath === 'x-closed' && host.reason === 'unsupported_shadow_dom'), true)
    assert.equal(observation.unsupported.shadowDom, true)

    const resolved = await resolveStepTargetEvidence(page, inShadow.locator)
    assert.equal(resolved.unique, true)
    assert.equal(resolved.visible, true)
  } finally {
    await page.close()
    await browser.close()
  }
})

test('acceptance 6b: an unobservable target on a page with closed shadow hosts is classified as unsupported_shadow_dom', async (context) => {
  const origin = await startHtmlServer(`<!doctype html>
    <x-closed></x-closed>
    <script>
      class Closed extends HTMLElement {
        connectedCallback() {
          const root = this.attachShadow({ mode: 'closed' })
          root.innerHTML = '<button id="locked">封闭按钮</button>'
        }
      }
      customElements.define('x-closed', Closed)
    </script>`, context)
  await assert.rejects(
    () => discoverPageLocators({
      sourcePointId: 'point-unsupported-shadow',
      url: `${origin}/closed`,
      baseUrl: origin,
      browser: 'chromium',
      headless: true,
      timeoutMs: 1_000,
      intent: { pageAliases: ['封闭按钮'], elementTerms: ['封闭按钮'] },
      actions: [{ id: 'click-locked', type: 'click', safety: 'readonly', target: { kind: 'evidence', evidenceId: 'element-0000000000000002' } }]
    }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) }),
    (error) => {
      assert.equal(error.failureType, 'unsupported_shadow_dom')
      assert.match(String(error.message), /shadow/i)
      return true
    }
  )
})

test('acceptance 1: a cross page click plus assertion produces two step evidence entries bound to different pages', async (context) => {
  const origin = await startRouteServer({
    '/list': `<!doctype html>
      <nav><a id="menu-link" href="/detail">设备详情</a></nav>
      <h1 id="list-title">设备列表</h1>`,
    '/detail': '<!doctype html><h1 id="detail-title">设备详情页</h1>'
  }, context)

  const listSnapshot = await discoverPageLocators({
    sourcePointId: 'point-cross-page-list',
    url: `${origin}/list`,
    baseUrl: origin,
    browser: 'chromium',
    headless: true,
    timeoutMs: 2_000,
    intent: { pageAliases: ['设备列表'], elementTerms: ['设备详情'] }
  }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) })

  const detailSnapshot = await discoverPageLocators({
    sourcePointId: 'point-cross-page-detail',
    url: `${origin}/detail`,
    baseUrl: origin,
    browser: 'chromium',
    headless: true,
    timeoutMs: 2_000,
    intent: { pageAliases: ['设备详情页'], elementTerms: ['设备详情页'] }
  }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) })

  const menuEvidence = listSnapshot.elements.find((entry) => entry.locator && entry.accessibleName === '设备详情')
  const titleEvidence = detailSnapshot.elements.find((entry) => entry.locator && entry.text === '设备详情页')
  assert.ok(menuEvidence, '起始页菜单证据必须可定位')
  assert.ok(titleEvidence, '目标页标题证据必须可定位')

  const snapshot = await discoverPageLocators({
    sourcePointId: 'point-cross-page',
    url: `${origin}/list`,
    baseUrl: origin,
    browser: 'chromium',
    headless: true,
    timeoutMs: 3_000,
    intent: { pageAliases: ['设备列表'], elementTerms: ['设备详情'] },
    actions: [
      { id: 'open-detail', type: 'click', safety: 'readonly', target: { kind: 'evidence', evidenceId: menuEvidence.evidenceId } },
      { id: 'assert-detail-title', type: 'assertVisible', safety: 'readonly', target: { kind: 'evidence', evidenceId: titleEvidence.evidenceId } }
    ]
  }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) })

  assert.equal(snapshot.stepEvidence.length, 2)
  assert.deepEqual(snapshot.stepEvidence.map((item) => item.actionId), ['open-detail', 'assert-detail-title'])
  assert.equal(snapshot.stepEvidence.every((item) => item.execution.status === 'passed'), true)

  const [openStep, assertStep] = snapshot.stepEvidence
  assert.equal(openStep.before.pageKey.endsWith('/list'), true, '点击步骤必须发生在起始页')
  assert.equal(openStep.after.pageKey.endsWith('/detail'), true, '点击后必须到达目标页')
  assert.equal(assertStep.before.pageKey.endsWith('/detail'), true, '断言步骤必须已经在目标页')
  assert.notEqual(openStep.before.pageKey, assertStep.before.pageKey, '两个步骤必须来自不同页面')
  assert.notEqual(openStep.before.pageFingerprint, assertStep.before.pageFingerprint)
  assert.equal(openStep.resolvedTarget.evidenceId, menuEvidence.evidenceId, '菜单证据必须来自点击前的页面')
  assert.equal(assertStep.resolvedTarget.evidenceId, titleEvidence.evidenceId, '标题证据必须来自点击后的页面')
})

test('acceptance 1b: click waits for an explicit postcondition before the after observation is recorded', async (context) => {
  const origin = await startHtmlServer(`<!doctype html>
    <button data-testid="start">开始</button>
    <h1 id="state">等待中</h1>
    <script>
      document.querySelector('[data-testid=start]').onclick = () => {
        setTimeout(() => { document.querySelector('#state').textContent = '已完成' }, 400)
      }
    </script>`, context)
  const first = await discoverPageLocators({
    sourcePointId: 'point-postcondition',
    url: `${origin}/post`,
    baseUrl: origin,
    browser: 'chromium',
    headless: true,
    timeoutMs: 1_000,
    intent: { pageAliases: ['等待中'], elementTerms: ['开始'] }
  }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) })
  const buttonEvidence = first.elements.find((entry) => entry.locator === 'testId=start')
  assert.ok(buttonEvidence)

  const snapshot = await discoverPageLocators({
    sourcePointId: 'point-postcondition',
    url: `${origin}/post`,
    baseUrl: origin,
    browser: 'chromium',
    headless: true,
    timeoutMs: 5_000,
    intent: { pageAliases: ['等待中'], elementTerms: ['开始'] },
    actions: [{ id: 'start', type: 'click', safety: 'readonly', target: { kind: 'evidence', evidenceId: buttonEvidence.evidenceId } }]
  }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) })

  const step = snapshot.stepEvidence[0]
  assert.equal(step.execution.status, 'passed')
  assert.ok(step.execution.postcondition, '步骤证据必须记录明确的 postcondition')
  assert.equal(step.execution.postcondition.pageChanged, true)
  assert.equal(step.execution.postcondition.kind, 'settled')
  assert.notEqual(step.after.pageFingerprint, step.before.pageFingerprint, 'after 证据必须等到异步更新完成')
})

test('acceptance 5b: an unobservable target on a page with unsupported frames is classified as unsupported_frame', async (context) => {
  const crossOrigin = await startHtmlServer('<!doctype html><button id="blocked">跨域按钮</button>', context)
  const origin = await startHtmlServer(
    `<!doctype html><button id="outside">外部按钮</button><iframe id="blocked" src="${crossOrigin}/blocked"></iframe>`,
    context
  )
  await assert.rejects(
    () => discoverPageLocators({
      sourcePointId: 'point-unsupported-frame',
      url: `${origin}/host`,
      baseUrl: origin,
      browser: 'chromium',
      headless: true,
      timeoutMs: 1_000,
      intent: { pageAliases: ['外部按钮'], elementTerms: ['跨域按钮'] },
      actions: [{ id: 'click-blocked', type: 'click', safety: 'readonly', target: { kind: 'evidence', evidenceId: 'element-0000000000000001' } }]
    }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) }),
    (error) => {
      assert.equal(error.failureType, 'unsupported_frame')
      assert.match(String(error.message), /unsupported_frame|frame/i)
      assert.equal(error.scopeUncertain, true, '该分类是推断而不是已证实的归属，必须显式标注')
      return true
    }
  )
})

test('acceptance 6c: an open shadow root target is actionable and the Action IR click executes', async (context) => {
  const origin = await startHtmlServer(`<!doctype html>
    <div id="host"></div>
    <script>
      const open = document.querySelector('#host').attachShadow({ mode: 'open' })
      open.innerHTML = '<button id="shadow-run">影子执行</button>'
      open.querySelector('#shadow-run').addEventListener('click', () => { document.title = '影子执行完成' })
    </script>`, context)
  const discovery = await discoverPageLocators({
    sourcePointId: 'point-shadow-action',
    url: `${origin}/shadow-action`,
    baseUrl: origin,
    browser: 'chromium',
    headless: true,
    timeoutMs: 1_000,
    intent: { pageAliases: ['影子执行'], elementTerms: ['影子执行'] }
  }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) })

  const shadowEvidence = discovery.elements.find((entry) => entry.shadowPath === '#host')
  assert.ok(shadowEvidence, 'open shadow 内元素必须被采集')
  assert.equal(shadowEvidence.receivesEvents, true, 'open shadow 内元素必须判定为可接收事件（elementFromPoint 只返回 host 会误判）')

  const snapshot = await discoverPageLocators({
    sourcePointId: 'point-shadow-action',
    url: `${origin}/shadow-action`,
    baseUrl: origin,
    browser: 'chromium',
    headless: true,
    timeoutMs: 3_000,
    intent: { pageAliases: ['影子执行'], elementTerms: ['影子执行'] },
    actions: [{ id: 'click-shadow', type: 'click', safety: 'readonly', target: { kind: 'evidence', evidenceId: shadowEvidence.evidenceId } }]
  }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) })

  const step = snapshot.stepEvidence[0]
  assert.equal(step.execution.status, 'passed', 'open shadow 内目标必须能真正执行 Action IR click')
  assert.equal(step.resolvedTarget.receivesEvents, true)
  assert.equal(step.execution.postcondition.pageChanged, true, 'click 后标题变化必须被 postcondition 捕获')
})

test('acceptance 6d: nested open shadow roots without ids are collected with a composed shadow path', async (context) => {
  const origin = await startHtmlServer(`<!doctype html>
    <div id="outer-host"></div>
    <script>
      const outer = document.querySelector('#outer-host').attachShadow({ mode: 'open' })
      outer.innerHTML = '<x-inner></x-inner>'
      class Inner extends HTMLElement {
        connectedCallback() {
          const root = this.attachShadow({ mode: 'open' })
          root.innerHTML = '<button id="deep-shadow">嵌套影子按钮</button>'
        }
      }
      customElements.define('x-inner', Inner)
    </script>`, context)
  const { browser, page } = await launchPage(`${origin}/nested-shadow`)
  try {
    const evidence = await collectVisibleElements(page)
    const nested = evidence.find((entry) => entry.shadowPath && entry.shadowPath.includes('>>'))
    assert.ok(nested, '无 id 的嵌套 open shadow host 内的元素必须被采集（不能静默跳过）')
    assert.equal(nested.shadowPath, '#outer-host >> x-inner', '嵌套 shadow 路径必须逐层合成')
    assert.equal(nested.locator.startsWith('css=#outer-host >> css=x-inner >> '), true)
    assert.equal(await resolveLocator(page, nested.locator).count(), 1, '嵌套 shadow 作用域 locator 必须唯一解析')
  } finally {
    await page.close()
    await browser.close()
  }
})

test('acceptance 5c: nested same-origin iframes keep the outer frame scope', async (context) => {
  const origin = await startRouteServer({
    '/outer': '<!doctype html><iframe id="outer" src="/outer-frame"></iframe>',
    '/outer-frame': '<!doctype html><iframe id="inner" src="/inner-frame"></iframe>',
    '/inner-frame': '<!doctype html><button id="deep">深层按钮</button>'
  }, context)
  const { browser, page } = await launchPage(`${origin}/outer`)
  try {
    // 内层 iframe 需要二次加载：先等深层节点挂上，否则采集到的是「尚未加载完的中间态」。
    await page.frameLocator('#outer').frameLocator('#inner').locator('#deep').waitFor({ state: 'attached', timeout: 3_000 })
    const evidence = await collectVisibleElements(page)
    const nested = evidence.find((entry) => entry.framePath === '#outer >> #inner')
    assert.ok(nested, '嵌套 iframe 内元素必须被采集')
    assert.equal(nested.locator.startsWith('frame=#outer >> frame=#inner >> '), true, '嵌套 iframe 必须保留外层 frame 作用域')
    assert.equal(await resolveLocator(page, nested.locator).count(), 1, '嵌套 frame 作用域 locator 必须唯一解析')
  } finally {
    await page.close()
    await browser.close()
  }
})

test('acceptance 7: a cross page action run returns per page snapshots for platform evidence', async (context) => {
  const origin = await startRouteServer({
    '/list': `<!doctype html>
      <nav><a id="menu-link" href="/detail">设备详情</a></nav>
      <h1 id="list-title">设备列表</h1>`,
    '/detail': '<!doctype html><h1 id="detail-title">设备详情页</h1>'
  }, context)
  const listSnapshot = await discoverPageLocators({
    sourcePointId: 'point-snapshots',
    url: `${origin}/list`,
    baseUrl: origin,
    browser: 'chromium',
    headless: true,
    timeoutMs: 2_000,
    intent: { pageAliases: ['设备列表'], elementTerms: ['设备详情'] }
  }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) })
  const menuEvidence = listSnapshot.elements.find((entry) => entry.accessibilityText === '设备详情')
  assert.ok(menuEvidence)

  const detailSnapshot = await discoverPageLocators({
    sourcePointId: 'point-snapshots',
    url: `${origin}/detail`,
    baseUrl: origin,
    browser: 'chromium',
    headless: true,
    timeoutMs: 2_000,
    intent: { pageAliases: ['设备详情页'], elementTerms: ['设备详情页'] }
  }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) })
  const titleEvidence = detailSnapshot.elements.find((entry) => entry.accessibilityText === '设备详情页')
  assert.ok(titleEvidence)

  const snapshot = await discoverPageLocators({
    sourcePointId: 'point-snapshots',
    url: `${origin}/list`,
    baseUrl: origin,
    browser: 'chromium',
    headless: true,
    timeoutMs: 3_000,
    intent: { pageAliases: ['设备列表'], elementTerms: ['设备详情'] },
    actions: [
      { id: 'open-detail', type: 'click', safety: 'readonly', target: { kind: 'evidence', evidenceId: menuEvidence.evidenceId } },
      { id: 'assert-detail-title', type: 'assertVisible', safety: 'readonly', target: { kind: 'evidence', evidenceId: titleEvidence.evidenceId } }
    ]
  }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) })

  const pageSnapshots = snapshot.pageSnapshots || []
  const pageKeys = pageSnapshots.map((item) => item.pageKey)
  assert.equal(pageSnapshots.length >= 2, true, '跨页动作必须为每个页面各返回一份快照')
  assert.equal(new Set(pageKeys).size, pageKeys.length, '页面快照必须按 pageKey 去重')
  assert.equal(pageKeys.some((key) => key.endsWith('/list')), true, '起始页必须有自己的快照')
  assert.equal(pageKeys.some((key) => key.endsWith('/detail')), true, '目标页必须有自己的快照')
  for (const item of pageSnapshots) {
    assert.ok(item.pageFingerprint, '页面快照必须带 pageFingerprint')
    assert.ok(item.pageUrl, '页面快照必须带 pageUrl')
    assert.equal(Array.isArray(item.elements), true)
  }
  const listPage = pageSnapshots.find((item) => item.pageKey.endsWith('/list'))
  assert.equal(
    listPage.elements.some((element) => element.evidenceId === menuEvidence.evidenceId), true,
    '起始页快照必须包含该步骤引用的元素（平台按页面证据校验 locator 归属）'
  )
  const finalPage = pageSnapshots.find((item) => (item.roles || []).includes('final'))
  assert.ok(finalPage, '最终页面快照必须标记 roles=final')
  assert.equal(finalPage.pageKey.endsWith('/detail'), true)
})

test('acceptance 8: role locators proven unique by exact name stay unique when replayed', async (context) => {
  const origin = await startHtmlServer(`<!doctype html>
    <button>查询</button>
    <button>查询用户</button>`, context)
  const { browser, page } = await launchPage(`${origin}/exact`)
  try {
    const evidence = await collectVisibleElements(page)
    const query = evidence.find((entry) => entry.accessibleName === '查询')
    assert.ok(query, '必须采集到「查询」按钮')
    assert.equal(query.locator, 'role=button[name="查询", exact=true]', '唯一性按精确名字证明时必须带上 exact 标记')
    assert.equal(await resolveLocator(page, query.locator).count(), 1, 'exact locator 必须唯一')
    assert.equal(await page.getByRole('button', { name: '查询' }).count(), 2, '非 exact 匹配会命中「查询用户」，这正是需要 exact 的原因')
    const resolved = await resolveStepTargetEvidence(page, query.locator)
    assert.equal(resolved.unique, true)
  } finally {
    await page.close()
    await browser.close()
  }
})

test('acceptance 9: a click that changes nothing is recorded but explicitly flagged as no_page_change', async (context) => {
  const origin = await startHtmlServer('<!doctype html><button id="noop">无变化按钮</button>', context)
  const discovery = await discoverPageLocators({
    sourcePointId: 'point-noop',
    url: `${origin}/noop`,
    baseUrl: origin,
    browser: 'chromium',
    headless: true,
    timeoutMs: 1_000,
    intent: { pageAliases: ['无变化按钮'], elementTerms: ['无变化按钮'] }
  }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) })
  const target = discovery.elements.find((entry) => entry.accessibleName === '无变化按钮')
  assert.ok(target, '必须采集到无变化按钮')

  const snapshot = await discoverPageLocators({
    sourcePointId: 'point-noop',
    url: `${origin}/noop`,
    baseUrl: origin,
    browser: 'chromium',
    headless: true,
    timeoutMs: 3_000,
    intent: { pageAliases: ['无变化按钮'], elementTerms: ['无变化按钮'] },
    actions: [{ id: 'click-noop', type: 'click', safety: 'readonly', target: { kind: 'evidence', evidenceId: target.evidenceId } }]
  }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) })

  const step = snapshot.stepEvidence[0]
  assert.equal(step.execution.status, 'passed', '只读发现阶段不因「页面无变化」判失败')
  assert.equal(step.execution.postcondition.kind, 'no_page_change')
  assert.equal(step.execution.postcondition.pageChanged, false)
  assert.equal(step.execution.warning, 'no_page_change', '无变化必须显式标注，不能被下游当成交互已证实生效')
})

test('acceptance 6e: sibling shadow hosts with the same tag produce distinct, uniquely resolvable locators', async (context) => {
  const origin = await startHtmlServer(`<!doctype html>
    <div id="outer-host"></div>
    <script>
      const root = document.querySelector('#outer-host').attachShadow({ mode: 'open' })
      root.innerHTML = '<div></div><div></div>'
      const [first, second] = Array.from(root.querySelectorAll('div'))
      first.attachShadow({ mode: 'open' }).innerHTML = '<button>编辑</button>'
      second.attachShadow({ mode: 'open' }).innerHTML = '<button>编辑</button>'
    </script>`, context)
  const { browser, page } = await launchPage(`${origin}/sibling-shadow`)
  try {
    // ShadowRoot 下的 parentElement 为 null：兄弟区分必须走 parentNode，否则两个 host 会得到同一条路径。
    const parentElementIsNull = await page.locator('css=#outer-host >> css=div:nth-of-type(1)').evaluate((host) => host.parentElement === null)
    assert.equal(parentElementIsNull, true, '前置事实：ShadowRoot 直接子节点的 parentElement 为 null')

    const evidence = await collectVisibleElements(page)
    const buttons = evidence.filter((entry) => entry.accessibleName === '编辑')
    assert.equal(buttons.length, 2, '两个同名兄弟 host 内的按钮都必须被采集')
    assert.equal(new Set(buttons.map((entry) => entry.locator)).size, 2, '两条证据不能共用同一条 locator')
    for (const entry of buttons) {
      assert.equal(entry.locator.includes('nth-of-type'), true, `兄弟 host 必须用 nth-of-type 区分：${entry.locator}`)
      assert.equal(await resolveLocator(page, entry.locator).count(), 1, `locator 必须唯一：${entry.locator}`)
      const resolved = await resolveStepTargetEvidence(page, entry.locator)
      assert.equal(resolved.unique, true)
    }
  } finally {
    await page.close()
    await browser.close()
  }
})

test('acceptance 10: a navigate (route) cross page run keeps a usable element set for every page snapshot', async (context) => {
  const origin = await startRouteServer({
    '/list': `<!doctype html>
      <nav><a id="menu-link" href="/detail">设备详情</a></nav>
      <h1 id="list-title">设备列表</h1>`,
    '/detail': '<!doctype html><h1 id="detail-title">设备详情页</h1>'
  }, context)
  const detailSnapshot = await discoverPageLocators({
    sourcePointId: 'point-navigate',
    url: `${origin}/detail`,
    baseUrl: origin,
    browser: 'chromium',
    headless: true,
    timeoutMs: 2_000,
    intent: { pageAliases: ['设备详情页'], elementTerms: ['设备详情页'] }
  }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) })
  const titleEvidence = detailSnapshot.elements.find((entry) => entry.accessibilityText === '设备详情页')
  assert.ok(titleEvidence)

  const snapshot = await discoverPageLocators({
    sourcePointId: 'point-navigate',
    url: `${origin}/list`,
    baseUrl: origin,
    browser: 'chromium',
    headless: true,
    timeoutMs: 3_000,
    intent: { pageAliases: ['设备列表'], elementTerms: ['设备详情'] },
    actions: [
      { id: 'go-detail', type: 'navigate', safety: 'readonly', target: { kind: 'route', value: `${origin}/detail` } },
      { id: 'assert-detail-title', type: 'assertVisible', safety: 'readonly', target: { kind: 'evidence', evidenceId: titleEvidence.evidenceId } }
    ]
  }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) })

  const pageSnapshots = snapshot.pageSnapshots || []
  assert.equal(pageSnapshots.length >= 2, true, '导航型跨页必须为两页各留一份快照')
  for (const item of pageSnapshots) {
    assert.equal(item.elements.length > 0, true, `页面快照必须带可用元素（平台按此装配证据）：${item.pageKey}`)
  }
  assert.equal(pageSnapshots.some((item) => item.pageKey.endsWith('/list')), true)
  assert.equal(pageSnapshots.some((item) => item.pageKey.endsWith('/detail')), true)
  // 探索轮次曾经把同一个 Action IR 动作再执行一次，结构化 target 被当成 URL 字符串，
  // 页面真的被导航到 /[object%20Object]：最终页与所有页面键里都不能出现这种兜底字符串。
  assert.equal(snapshot.pageUrl.includes('[object'), false, `最终页面不能被导航到兜底 URL：${snapshot.pageUrl}`)
  assert.equal(snapshot.pageUrl.endsWith('/detail'), true, `导航型用例最终必须停在目标页：${snapshot.pageUrl}`)
  assert.equal(pageSnapshots.every((item) => !item.pageKey.includes('[object')), true)
  const navigateStep = snapshot.stepEvidence.find((step) => step.actionId === 'go-detail')
  assert.ok(navigateStep, '导航步骤也必须产生 step evidence')
  assert.equal(navigateStep.execution.status, 'passed')
  assert.equal(navigateStep.after.pageKey.endsWith('/detail'), true)
})

test('acceptance 13: an element-free start page still yields a page snapshot with an explicit diagnosis', async (context) => {
  const origin = await startRouteServer({
    '/plain': '<!doctype html><p>纯文本说明，没有任何可采集元素</p>',
    '/detail': '<!doctype html><h1 id="detail-title">设备详情页</h1>'
  }, context)

  const snapshot = await discoverPageLocators({
    sourcePointId: 'point-plain',
    url: `${origin}/plain`,
    baseUrl: origin,
    browser: 'chromium',
    headless: true,
    timeoutMs: 3_000,
    intent: { pageAliases: ['纯文本页'], elementTerms: [] },
    actions: [
      { id: 'plain-go-detail', type: 'navigate', safety: 'readonly', target: { kind: 'route', value: `${origin}/detail` } }
    ]
  }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) })

  const startSnapshot = (snapshot.pageSnapshots || []).find((item) => item.pageKey.endsWith('/plain'))
  assert.ok(startSnapshot, '无可采集元素的页面也必须留下快照（平台按页面装配证据）')
  assert.equal(startSnapshot.elements.length, 0)
  // 空元素页必须显式自证：平台据此保留该页证据（路由型步骤只需要页面身份），
  // 直接丢页会让「起始页无元素」的跨页用例以「缺少 step evidence」这种误导性错误失败。
  assert.equal(startSnapshot.elementsEmpty, true)
  assert.equal(startSnapshot.elementsEmptyReason, 'no_collectable_elements')
  const step = snapshot.stepEvidence.find((item) => item.actionId === 'plain-go-detail')
  assert.equal(step.execution.status, 'passed')
})

test('acceptance 11: the same evidence target may be reused within one page state', async (context) => {
  const origin = await startHtmlServer(`<!doctype html>
    <button id="repeat">重复点击</button>
    <script>let count = 0
      document.querySelector('#repeat').addEventListener('click', () => { count += 1; document.title = 'clicked-' + count })</script>`, context)
  const discovery = await discoverPageLocators({
    sourcePointId: 'point-repeat',
    url: `${origin}/repeat`,
    baseUrl: origin,
    browser: 'chromium',
    headless: true,
    timeoutMs: 1_000,
    intent: { pageAliases: ['重复点击'], elementTerms: ['重复点击'] }
  }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) })
  const target = discovery.elements.find((entry) => entry.accessibleName === '重复点击')
  assert.ok(target)

  const snapshot = await discoverPageLocators({
    sourcePointId: 'point-repeat',
    url: `${origin}/repeat`,
    baseUrl: origin,
    browser: 'chromium',
    headless: true,
    timeoutMs: 3_000,
    intent: { pageAliases: ['重复点击'], elementTerms: ['重复点击'] },
    actions: [
      { id: 'click-1', type: 'click', safety: 'readonly', target: { kind: 'evidence', evidenceId: target.evidenceId } },
      { id: 'click-2', type: 'click', safety: 'readonly', target: { kind: 'evidence', evidenceId: target.evidenceId } }
    ]
  }, { launchBrowser: (_browserId, launchOptions) => chromium.launch(launchOptions) })

  assert.equal(snapshot.stepEvidence.length, 2)
  assert.equal(snapshot.stepEvidence.every((step) => step.execution.status === 'passed'), true, '同页复用同一 evidence 必须允许')
})

test('acceptance 12: a tab locator proven unique by label carries the exact flag and stays unique when replayed', async (context) => {
  const origin = await startHtmlServer(`<!doctype html>
    <div role="tablist"><button role="tab">查询</button><button role="tab">查询用户</button></div>`, context)
  const { browser, page } = await launchPage(`${origin}/tabs`)
  try {
    const observation = await observePageEvidence(page)
    const tabs = Array.isArray(observation.tabs) ? observation.tabs : []
    const queryTab = tabs.find((tab) => tab.label === '查询')
    assert.ok(queryTab, '必须采集到「查询」标签页')
    assert.equal(queryTab.locator, 'role=tab[name="查询", exact=true]', '唯一性按标签文本证明时必须带 exact')
    assert.equal(await resolveLocator(page, queryTab.locator).count(), 1)
    assert.equal(await page.getByRole('tab', { name: '查询' }).count(), 2, '非 exact 匹配会命中「查询用户」')
  } finally {
    await page.close()
    await browser.close()
  }
})

test('acceptance 6f: a shadow host whose path cannot be made unique is reported instead of mislocated', async (context) => {
  const origin = await startHtmlServer(`<!doctype html>
    <style>div{display:block;width:40px;height:40px}</style>
    <div id="outer"></div>
    <script>
      const root = document.querySelector('#outer').attachShadow({ mode: 'open' })
      root.innerHTML = '<button id="shallow-btn">浅层按钮</button>'
      let level = root
      for (let index = 0; index < 7; index += 1) {
        const first = document.createElement('div')
        const second = document.createElement('div')
        level.append(first, second)
        level = second
      }
      const deepHost = document.createElement('div')
      deepHost.attachShadow({ mode: 'open' }).innerHTML = '<button>深层按钮</button>'
      level.append(deepHost)
    </script>`, context)
  const { browser, page } = await launchPage(`${origin}/unresolved-shadow`)
  try {
    const observation = await observePageEvidence(page)
    const unresolved = observation.unsupported?.unresolvedShadowHosts || []
    assert.equal(unresolved.length >= 1, true, '无法唯一寻址的 shadow host 必须进入诊断')
    assert.equal(unresolved.every((host) => host.reason === 'unresolved_shadow_scope'), true)
    // 深层 host 的路径超过可解析深度：宁可不采集并留下诊断，也不能用父级 shadowPath 冒充（那会产出相同 locator）。
    assert.equal(observation.elements.some((entry) => entry.accessibleName === '深层按钮'), false)
    assert.equal(observation.elements.some((entry) => entry.accessibleName === '浅层按钮'), true, '同一 shadow root 里可寻址的元素仍要采集')
    assert.equal(observation.elements.every((entry) => !String(entry.locator || '').endsWith(' >> ')), true)
  } finally {
    await page.close()
    await browser.close()
  }
})

test('acceptance 4b: targeted collection is still bounded by the element budget', async (context) => {
  const buttons = Array.from({ length: 200 }, (_value, index) => `<button data-testid="wide-${index + 1}">统一按钮${index + 1}</button>`).join('')
  const origin = await startHtmlServer(`<!doctype html><main>${buttons}</main>`, context)
  const { browser, page } = await launchPage(`${origin}/wide`)
  try {
    const observation = await observePageEvidence(page, { budget: 10, targetedTerms: ['统一按钮'] })
    assert.equal(observation.elements.length <= 10, true, '定向匹配再多也不能突破整体预算')
    assert.equal(observation.elementsTruncated?.total, 200)
    assert.equal(observation.elementsTruncated?.dropped > 0, true, '被预算丢弃的定向元素必须出现在诊断里')
  } finally {
    await page.close()
    await browser.close()
  }
})

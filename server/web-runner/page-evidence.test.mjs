import test from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import { chromium } from 'playwright'
import {
  collectVisibleElements,
  observePageEvidence,
  pageFingerprint,
  normalizePageKey,
  resolveStepTargetEvidence,
  verifyHistoricalLocators
} from './page-evidence.mjs'

const withServer = async (html, fn, context) => {
  const server = http.createServer((_request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(html)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')
  const url = `http://127.0.0.1:${address.port}/page`
  return fn(url)
}

test('collectVisibleElements preserves locator priority without leaking sensitive values', async (context) => {
  await withServer(`<!doctype html>
    <button data-testid="create-user">新增用户</button>
    <button aria-label="发布公告" type="button"></button>
    <div role="button" aria-label="重复操作" style="display:inline-block;width:60px;height:24px"></div>
    <div role="button" aria-label="重复操作">唯一文本</div>
    <input type="password" value="secret-password" aria-label="登录密码">
    <input type="hidden" value="hidden-secret">
  `, async (url) => {
    const browser = await chromium.launch({ headless: true })
    try {
      const page = await browser.newPage()
      await page.goto(url, { waitUntil: 'domcontentloaded' })

      const evidence = await collectVisibleElements(page)
      const locators = evidence.map((entry) => entry.locator)

      assert.ok(locators.includes('testId=create-user'))
      assert.ok(locators.includes('role=button[name="发布公告", exact=true]'), '角色 locator 必须带 exact 标记（唯一性按精确名字证明）')
      assert.ok(locators.includes('text=唯一文本, exact=true'), '文本 locator 必须带 exact 标记（唯一性按精确文本证明）')
      assert.ok(locators.some((locator) => locator.startsWith('css=')))
      assert.equal(locators.some((locator) => locator === 'role=button[name="重复操作"]'), false, '同名元素不得生成未加 exact 的角色 locator')
      assert.equal(JSON.stringify(evidence).includes('secret-password'), false)
      assert.equal(JSON.stringify(evidence).includes('hidden-secret'), false)

      await page.close()
    } finally {
      await browser.close()
    }
  }, context)
})

test('verifyHistoricalLocators validates unique locators and rejects hidden disabled or covered matches', async (context) => {
  await withServer(`<!doctype html>
    <h1>项目管理</h1>
    <button data-testid="current-target">查看状态</button>
    <button aria-label="发布公告" type="button">发布</button>
    <button>重复标签</button>
    <button>重复标签</button>
    <button id="disabled-target" disabled>不可点击</button>
    <button id="exact-target">精确命中</button>
    <button id="role-target" aria-label="唯一角色命中">角色命中</button>
    <div id="overlay" role="dialog" aria-modal="true" style="position:absolute;top:120px;left:20px;width:140px;height:48px;background:rgba(0,0,0,.2);z-index:10">
      <button id="overlay-close">关闭</button>
    </div>
    <button id="covered-target" style="position:absolute;top:120px;left:20px;width:140px;height:48px">被遮罩</button>
    <script>
      const overlay = document.querySelector('#overlay')
      const covered = document.querySelector('#covered-target')
      covered.style.top = overlay.getBoundingClientRect().top + 'px'
      covered.style.left = overlay.getBoundingClientRect().left + 'px'
      covered.style.width = overlay.getBoundingClientRect().width + 'px'
      covered.style.height = overlay.getBoundingClientRect().height + 'px'
    </script>
  `, async (url) => {
    const browser = await chromium.launch({ headless: true })
    try {
      const page = await browser.newPage()
      await page.goto(url, { waitUntil: 'domcontentloaded' })

      const result = await verifyHistoricalLocators(page, [
        { entryId: 'exact-hit', locator: 'testId=current-target', label: '查看状态' },
        { entryId: 'role-hit', locator: 'testId=missing-target', role: 'button', label: '发布公告' },
        { entryId: 'duplicate-label', locator: 'testId=missing-duplicate', label: '重复标签' },
        { entryId: 'hidden-hit', locator: 'css=#missing-hidden', label: '隐藏节点' },
        { entryId: 'disabled-hit', locator: 'css=#disabled-target', label: '不可点击' },
        { entryId: 'unique-role-hit', locator: 'css=#missing-role', role: 'button', label: '唯一角色命中' },
        { entryId: 'exact-text-hit', locator: 'css=#missing-text', label: '精确命中' },
        { entryId: 'overlay-hit', locator: 'css=#covered-target', label: '被遮罩' }
      ])

      const byId = new Map(result.map((entry) => [entry.entryId, entry]))
      assert.equal(byId.get('exact-hit')?.verificationMethod, 'locator')
      assert.equal(byId.get('role-hit')?.verificationMethod, 'role-name')
      assert.equal(byId.get('unique-role-hit')?.verificationMethod, 'role-name')
      assert.equal(byId.get('exact-text-hit')?.verificationMethod, 'exact-label')
      assert.equal(byId.has('duplicate-label'), false)
      assert.equal(byId.has('hidden-hit'), false)
      assert.equal(byId.has('disabled-hit'), false)
      assert.equal(byId.has('overlay-hit'), false)
      assert.equal(byId.get('exact-hit')?.visible, true)
      assert.equal(byId.get('exact-hit')?.enabled, true)
      assert.equal(byId.get('exact-hit')?.receivesEvents, true)
    } finally {
      await browser.close()
    }
  }, context)
})

test('normalizePageKey strips query and hash query state while keeping route origin', () => {
  assert.equal(
    normalizePageKey('https://user:pass@example.com/app/path?token=abc#/open/threat-intelligence?from=menu&secret=1'),
    'https://example.com/app/path#/open/threat-intelligence'
  )
})

test('pageFingerprint stays stable across ordering changes and changes when the page changes', () => {
  const a = {
    pageKey: 'https://example.com/app#/page',
    title: '项目管理',
    headings: ['A', 'B'],
    breadcrumbs: ['首页', '项目'],
    activeMenus: [{ label: '项目', selected: true }],
    tabs: [{ role: 'tab', label: '概览', selected: true }],
    overlays: [{ blocking: true, text: '提示', locator: 'css=#overlay' }],
    elements: [
      { locator: 'text=A', role: 'heading', tagName: 'h1', pageScope: 'main', rowContext: 'R1' },
      { locator: 'text=B', role: 'button', tagName: 'button', pageScope: 'main', rowContext: 'R2' }
    ]
  }
  const b = {
    ...a,
    headings: ['B', 'A'],
    breadcrumbs: ['项目', '首页'],
    activeMenus: [{ selected: true, label: '项目' }],
    tabs: [{ selected: true, role: 'tab', label: '概览' }],
    overlays: [{ locator: 'css=#overlay', text: '提示', blocking: true }],
    elements: [
      { locator: 'text=B', role: 'button', tagName: 'button', pageScope: 'main', rowContext: 'R2' },
      { locator: 'text=A', role: 'heading', tagName: 'h1', pageScope: 'main', rowContext: 'R1' }
    ]
  }
  const c = { ...a, title: '变更后的标题' }

  assert.equal(pageFingerprint(a), pageFingerprint(b))
  assert.notEqual(pageFingerprint(a), pageFingerprint(c))
})

test('pageFingerprint changes when the observed page route changes', () => {
  const usersPage = {
    pageKey: 'https://example.com/app#/users',
    title: '用户管理',
    headings: ['用户管理'],
    elements: [{ locator: 'text=用户管理', role: 'heading', tagName: 'h1', pageScope: 'main' }]
  }
  const rolesPage = {
    ...usersPage,
    pageKey: 'https://example.com/app#/roles'
  }

  assert.notEqual(pageFingerprint(usersPage), pageFingerprint(rolesPage))
})

test('resolveStepTargetEvidence always includes live target state fields', async () => {
  const target = {
    count: async () => 2
  }
  const page = {
    getByRole: () => target
  }

  const evidence = await resolveStepTargetEvidence(page, 'role=button[name="重复"]')

  assert.deepEqual(evidence, {
    strategy: 'role',
    role: 'button',
    name: '重复',
    exact: true,
    locator: 'role=button[name="重复"]',
    visible: false,
    enabled: false,
    receivesEvents: false,
    // 非唯一时必须带实际匹配数：count=0 与 count=2 都是「不能用」，但诊断信息不能一样。
    matchedElementCount: 2,
    unique: false
  })
})

test('observePageEvidence exposes a unified observation without raw sensitive fields', async (context) => {
  await withServer(`<!doctype html>
    <h1>用户管理</h1>
    <button data-testid="safe-target">查看</button>
    <input type="password" value="super-secret" aria-label="登录密码">
    <div role="dialog" aria-modal="true"><button>确认</button></div>
  `, async (url) => {
    const browser = await chromium.launch({ headless: true })
    try {
      const page = await browser.newPage()
      await page.goto(url, { waitUntil: 'domcontentloaded' })

      const observation = await observePageEvidence(page)
      assert.equal(observation.pageKey, normalizePageKey(url))
      assert.equal(observation.pageFingerprint, pageFingerprint(observation))
      assert.equal(typeof observation.fingerprint, 'string')
      assert.notEqual(observation.fingerprint, observation.pageFingerprint)
      assert.equal(JSON.stringify(observation).includes('super-secret'), false)
      assert.equal(JSON.stringify(observation).includes('value'), false)
    } finally {
      await browser.close()
    }
  }, context)
})

test('closed shadow roots inside same-origin iframes are detected and cdp drops heuristic false positives', async (context) => {
  // 复核方实测两处：① 同源 iframe 内的 closed shadow root 完全漏检（CDP 树的 contentDocument 没被递归），
  // 目标缺失随后被降级成普通 action_evidence_missing；② 没有 shadow root 的自定义元素被启发式误报成 closed 宿主。
  // 这里不写内联 <script>（字符串转义容易失真）：先加载标记，再用 evaluate 在两个文档里挂 closed shadow root。
  const frameHtml = '<!doctype html><meta charset="utf-8"><div id="frame-closed"></div>'
  const hostHtml = '<!doctype html><meta charset="utf-8">'
    + '<div id="main-closed"></div>'
    + '<x-unupgraded style="display:inline-block;min-width:24px;min-height:12px"></x-unupgraded>'
    + '<iframe id="inner-frame" src="/frame"></iframe>'
  const server = http.createServer((request, response) => {
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    response.end(String(request.url || '').startsWith('/frame') ? frameHtml : hostHtml)
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  context.after(() => new Promise((resolve) => server.close(resolve)))
  const address = server.address()
  assert.ok(address && typeof address !== 'string')

  const browser = await chromium.launch({ headless: true })
  try {
    const page = await browser.newPage()
    await page.goto(`http://127.0.0.1:${address.port}/host`, { waitUntil: 'networkidle' })
    await page.locator('#main-closed').evaluate((element) => {
      element.attachShadow({ mode: 'closed' }).innerHTML = '<button>主文档封闭</button>'
    })
    await page.frameLocator('#inner-frame').locator('#frame-closed').evaluate((element) => {
      element.attachShadow({ mode: 'closed' }).innerHTML = '<button>帧内封闭</button>'
    })

    const observation = await observePageEvidence(page)
    assert.equal(observation.unsupported.closedShadowDetection, 'cdp')
    const hosts = observation.unsupported.closedShadowHosts
    const mainHost = hosts.find((host) => host.hostPath === '#main-closed')
    assert.ok(mainHost, `主文档宿主必须被检测到：${JSON.stringify(hosts)}`)
    assert.equal(mainHost.framePath, '')
    const frameHost = hosts.find((host) => host.hostPath === '#frame-closed')
    assert.ok(frameHost, `同源 iframe 内的宿主必须被检测到：${JSON.stringify(hosts)}`)
    assert.equal(frameHost.framePath, '#inner-frame', `iframe 内的宿主必须带 frame 前缀：${JSON.stringify(frameHost)}`)
    // 没有 shadow root 的自定义元素不得被误报（CDP 是权威来源，启发式假阳性必须被剔除）。
    assert.equal(hosts.some((host) => host.tagName === 'x-unupgraded'), false, `不得误报未 attach shadow 的自定义元素：${JSON.stringify(hosts)}`)
  } finally {
    await browser.close()
  }
})

test('closed shadow roots are detected through cdp even under a plain div or with light dom fallback', async (context) => {
  // 只靠 DOM 启发式会漏检：宿主是普通 <div>、或宿主带 light-DOM 回退内容时都读不到 closed shadow root，
  // 失败随后被降级成普通 action_evidence_missing，而阶段 3 要求记成 unsupported_shadow_dom。
  await withServer(`<!doctype html><meta charset="utf-8">
    <div id="div-host"></div>
    <x-text>回退文本</x-text>
    <x-child><span>回退子节点</span></x-child>
    <x-empty></x-empty>
    <div id="plain-sized" style="width:60px;height:20px"></div>
    <div id="plain-text">普通文本</div>
    <script>
      const mk = () => class extends HTMLElement { connectedCallback() { this.attachShadow({ mode: 'closed' }).innerHTML = '<button>封闭按钮</button>' } }
      customElements.define('x-text', mk()); customElements.define('x-child', mk()); customElements.define('x-empty', mk())
      document.getElementById('div-host').attachShadow({ mode: 'closed' }).innerHTML = '<button>div 内封闭按钮</button>'
    </script>`, async (url) => {
    const browser = await chromium.launch({ headless: true })
    try {
      const page = await browser.newPage()
      await page.goto(url, { waitUntil: 'domcontentloaded' })
      const observation = await observePageEvidence(page)
      assert.equal(observation.unsupported.closedShadowDetection, 'cdp')
      assert.equal(observation.unsupported.shadowDom, true)
      const paths = observation.unsupported.closedShadowHosts.map((host) => host.hostPath)
      assert.ok(paths.includes('#div-host'), `普通 div 宿主必须被检测到：${paths}`)
      assert.ok(paths.includes('x-text'), `带 light-DOM 文本的宿主必须被检测到：${paths}`)
      assert.ok(paths.includes('x-child'), `带 light-DOM 子节点的宿主必须被检测到：${paths}`)
      assert.ok(paths.includes('x-empty'), `空的自定义元素宿主必须被检测到：${paths}`)
      // 没有 shadow root 的元素不得被误报（有尺寸的空 div、普通文本节点）。
      assert.equal(paths.includes('#plain-sized'), false, `无 shadow root 的空 div 不得误报：${paths}`)
      assert.equal(paths.includes('#plain-text'), false, `普通文本元素不得误报：${paths}`)
    } finally {
      await browser.close()
    }
  }, context)
})

test('a target that does not resolve to exactly one element reports the match count', async (context) => {
  await withServer('<!doctype html><h1>设备管理</h1><button aria-label="新增">新增</button>', async (url) => {
    const browser = await chromium.launch({ headless: true })
    context.after(() => browser.close())
    const page = await browser.newPage()
    await page.goto(url, { waitUntil: 'domcontentloaded' })

    // 0 命中（采集器给出的 role 名在 Playwright 侧匹配不到）与 2 命中都必须带实际计数，
    // 否则下游只能看到一句笼统的 ambiguous_locator，无法区分「找不到」与「不唯一」。
    const missing = await resolveStepTargetEvidence(page, 'role=combobox[name="不存在"]')
    assert.equal(missing.unique, false)
    assert.equal(missing.matchedElementCount, 0)

    const duplicated = await resolveStepTargetEvidence(page, 'role=button[name="新增", exact=true]')
    assert.equal(duplicated.unique, true)
    assert.equal(duplicated.locator, 'role=button[name="新增", exact=true]')
  }, context)
})

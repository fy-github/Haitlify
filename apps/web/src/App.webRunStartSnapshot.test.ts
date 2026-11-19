// @vitest-environment jsdom
// Web 执行发起:确认 → 快照 → POST。跨 await 期间改动 live state 不得进入提交体。
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import type { WebAutomationRunSnapshot } from './domain/webAutomationRunRepository'

const APP = './App.vue'
const OWNER_PREFIX = `ai-test-platform.workspace.${encodeURIComponent('user:1')}.`
const WEB_LIBRARY_KEY = `${OWNER_PREFIX}ai-test-platform.web-execution-environments.v1`
const WEB_CASES_KEY = `${OWNER_PREFIX}ai-test-platform.web-automation-library.v1`

const adminUser = { id: 1, username: 'admin', displayName: '管理员', role: 'ADMIN', mustChangePassword: false }
const PROJECT = '华东区域跨境订单履约与退款协同平台'

const webEnvironment = (id: string, name: string, baseUrl: string) => ({
  id,
  name,
  baseUrl,
  auth: { type: 'none' },
  entries: [],
  web: {
    browser: 'chromium', viewport: '1440x900', headless: true, defaultTimeoutMs: 30000,
    navigationTimeoutMs: 45000, waitUntil: 'networkidle', screenshotStrategy: 'always',
    videoRecording: false, retryCount: 0, cookieOrigins: [], allowedRequestOrigins: [],
    networkPolicyMode: 'blocklist',
    login: { enabled: false, loginUrl: '', usernameLocator: '', passwordLocator: '', submitLocator: '', username: '', password: '', successType: 'url', successTarget: '' },
    logout: { enabled: false, steps: [] }
  },
  createdAt: '2026-10-29T00:00:00.000Z',
  updatedAt: '2026-10-29T00:00:00.000Z'
})

const webLibrary = () => ({
  activeEnvironmentId: 'env-web-b',
  environments: [
    webEnvironment('env-web-a', 'Web 环境 A', 'https://web-a.example.test'),
    webEnvironment('env-web-b', 'Web 环境 B', 'https://web-b.example.test')
  ]
})

const webCase = (id: string, order: number) => ({
  id,
  sourceCaseId: `SRC-${order}`,
  project: PROJECT,
  module: '退款',
  pageName: '退款详情',
  name: `Web 用例 ${order}`,
  startUrl: `/orders/refund/${order}`,
  environmentId: 'env-web-b',
  browser: 'chromium',
  viewport: '1440x900',
  authenticationMode: 'environment',
  steps: [{ id: `${id}-setup`, name: '打开目标页面', action: 'goto', targetType: 'url', target: `/orders/refund/${order}` }],
  runOrder: order,
  mockOutcome: 'passed',
  automationLibraryId: `${PROJECT}:web:${order}`,
  savedAt: '2026-10-29T00:00:00.000Z'
})

const CASES = [webCase('WEB-AUTO-0001', 1), webCase('WEB-AUTO-0002', 2)]

const runSnapshot = (): WebAutomationRunSnapshot => ({
  runId: 'run-fixture',
  status: 'running',
  mode: 'sequential',
  selectedBrowser: 'chromium',
  createdAt: '2026-10-29T00:00:00.000Z',
  startedAt: '2026-10-29T00:00:01.000Z',
  updatedAt: '2026-10-29T00:00:02.000Z',
  progress: { totalCases: 1, completedCases: 0, totalSteps: 1, completedSteps: 0 },
  controls: { canPause: true, canResume: false, canStop: true },
  summary: { total: 1, passed: 0, failed: 0, error: 0 },
  stepLogs: [],
  results: [],
  diagnostics: []
})

interface Deferred { promise: Promise<unknown>; resolve: (value: unknown) => void }
const defer = (): Deferred => {
  let resolve!: (value: unknown) => void
  const promise = new Promise<unknown>((res) => { resolve = res })
  return { promise, resolve }
}

const json = (payload: unknown, status = 200) => ({ ok: status < 400, status, json: async () => payload }) as unknown as Response

const mounted: Array<ReturnType<typeof mount>> = []

const mountWeb = async (projectsGate?: Deferred, snapshot = runSnapshot(), recover = false) => {
  const { defaultTemplateConfig } = await import('./domain/templateConfigRepository')
  const template = defaultTemplateConfig()
  vi.resetModules()
  localStorage.clear()
  sessionStorage.clear()
  localStorage.setItem(WEB_LIBRARY_KEY, JSON.stringify(webLibrary()))
  localStorage.setItem(WEB_CASES_KEY, JSON.stringify(CASES))
  window.history.replaceState({}, '', recover ? '/automation/web?view=monitor&runId=run-fixture' : '/automation/web')
  const posts: Array<Record<string, unknown>> = []
  const requests: Array<{ method: string; url: string }> = []
  const fetchImpl = vi.fn(async (url: string, init?: RequestInit) => {
    const method = (init?.method || 'GET').toUpperCase()
    requests.push({ method, url: String(url) })
    if (String(url).includes('/api/auth/me')) return json(adminUser)
    if (String(url) === '/api/projects') {
      if (projectsGate) await projectsGate.promise
      return json({ items: [{ id: 901, code: 'PROJECT', name: PROJECT }] })
    }
    if (String(url).includes('/api/web-automation/capabilities')) {
      return json({ status: 'UP', checkedAt: '2026-10-29T00:00:00Z', browsers: [{ id: 'chromium', label: 'Chromium', available: true, recommendation: '' }] })
    }
    if (String(url).endsWith('/runs/run-fixture/stop') && method === 'POST') return json({ ...runSnapshot(), status: 'stopped' })
    if (String(url).includes('/api/web-automation/runs') && method === 'POST') {
      const body = init?.body ? JSON.parse(String(init.body)) : {}
      posts.push(body)
      return json(snapshot)
    }
    if (String(url).includes('/api/web-automation/runs/')) return json(snapshot)
    if (String(url).includes('/api/jira/config')) return json({ enabled: false, baseUrl: '', username: '', projectJql: {} })
    if (String(url).includes('/case-template')) return json(template)
    if (String(url).endsWith('/cases')) return json({ items: [] })
    return json({})
  })
  vi.stubGlobal('fetch', fetchImpl)
  const { default: App } = await import(APP)
  const wrapper = mount(App as never, { global: { plugins: [ElementPlus] }, attachTo: document.body })
  mounted.push(wrapper)
  await flushPromises()
  await vi.waitFor(() => expect(document.querySelector('[data-testid="web-automation-workspace"]')).toBeTruthy())
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const s = ((wrapper.vm as unknown as { $: { setupState: unknown } }).$).setupState as any
  s.webAutomationSelectedIds = [CASES[0].id]
  await flushPromises()
  return { wrapper, s, posts, requests }
}

afterEach(() => {
  mounted.splice(0).forEach((wrapper) => { try { wrapper.unmount() } catch { /* already gone */ } })
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

describe('Web 执行发起:s快照冻结', () => {
  it.each([
    ['failed', 'passed'],
    ['passed', 'failed'],
    ['error', 'passed']
  ] as const)('retries a base case once when its two rounds are %s then %s', async (first, second) => {
    const results = CASES.flatMap((item, index) => [first, second].map((status, loop) => ({
      caseId: `${item.id}#${loop + 1}`, sourceCaseId: item.sourceCaseId,
      name: item.name, module: item.module, pageName: item.pageName, startUrl: item.startUrl,
      status: index === 0 ? status : 'passed', durationMs: 10
    })))
    const terminal: WebAutomationRunSnapshot = { ...runSnapshot(), status: 'completed', results, endedAt: '2026-10-29T00:00:03Z' }
    const { wrapper, posts } = await mountWeb(undefined, terminal)
    await wrapper.get('[data-testid="execute-web-automation"]').trigger('click')
    await flushPromises()
    ;(document.querySelector('[data-testid="confirm-web-execute"]') as HTMLButtonElement).click()
    await vi.waitFor(() => expect(posts).toHaveLength(1))
    await flushPromises()
    const retry = wrapper.find('[data-testid="retry-failed-cases"]')
    expect(retry.exists()).toBe(true)
    expect(retry.text()).toContain('（1）')
    await retry.trigger('click')
    await vi.waitFor(() => expect(posts).toHaveLength(2))
    expect((posts[1].cases as Array<{ id: string }>).map((item) => item.id)).toEqual([CASES[0].id])
  })

  it('recovers original runnable identities from loop-expanded snapshot results', async () => {
    const item = CASES[0]
    const terminal: WebAutomationRunSnapshot = { ...runSnapshot(), status: 'completed', endedAt: '2026-10-29T00:00:03Z', results: [{
      caseId: `${item.id}#2`, sourceCaseId: item.sourceCaseId,
      name: item.name, module: item.module, pageName: item.pageName, startUrl: item.startUrl,
      status: 'failed', durationMs: 10
    }] }
    const { wrapper, posts } = await mountWeb(undefined, terminal, true)
    await flushPromises()
    await wrapper.get('[data-testid="back-to-preparation"]').trigger('click')
    await flushPromises()
    const retry = wrapper.find('[data-testid="retry-failed-cases"]')
    expect(retry.exists()).toBe(true)
    await retry.trigger('click')
    await vi.waitFor(() => expect(posts).toHaveLength(1))
    expect((posts[0].cases as Array<{ id: string }>).map((row) => row.id)).toEqual([item.id])
  })

  it.each(['workbench', 'monitor'] as const)('opens the failed round and preserves its exact defect provenance from %s', async (surface) => {
    const item = CASES[0]
    const terminal: WebAutomationRunSnapshot = { ...runSnapshot(), status: 'completed', endedAt: '2026-10-29T00:00:03Z', results: [
      { caseId: `${item.id}#1`, sourceCaseId: item.sourceCaseId, name: item.name, module: item.module,
        pageName: item.pageName, startUrl: item.startUrl, status: 'failed', durationMs: 10, errorMessage: 'distinctive first-round failure' },
      { caseId: `${item.id}#2`, sourceCaseId: item.sourceCaseId, name: item.name, module: item.module,
        pageName: item.pageName, startUrl: item.startUrl, status: 'passed', durationMs: 10 }
    ] }
    const { wrapper, posts } = await mountWeb(undefined, terminal)
    await wrapper.get('[data-testid="execute-web-automation"]').trigger('click')
    await flushPromises()
    ;(document.querySelector('[data-testid="confirm-web-execute"]') as HTMLButtonElement).click()
    await vi.waitFor(() => expect(posts).toHaveLength(1))
    await flushPromises()
    if (surface === 'monitor') {
      await wrapper.get('[data-testid="enter-web-run-monitor"]').trigger('click')
      await flushPromises()
    }
    const resultId = surface === 'monitor' ? `${item.id}#1` : item.id
    await wrapper.get(`[aria-label="查看 Web 用例 ${resultId} 日志"]`).trigger('click')
    await flushPromises()
    const drawer = document.querySelector('.web-automation-log-drawer')
    expect(drawer?.textContent).toContain('distinctive first-round failure')
    ;(drawer?.querySelector('.el-drawer__close-btn') as HTMLButtonElement)?.click()
    await flushPromises()
    if (surface === 'monitor') {
      await wrapper.get(`[aria-label="查看 Web 用例 ${item.id}#2 日志"]`).trigger('click')
      await flushPromises()
      const passedRound = document.querySelector('.web-automation-log-drawer')
      expect(passedRound?.textContent).toContain(`${item.id}#2`)
      expect(passedRound?.textContent).not.toContain('distinctive first-round failure')
      ;(passedRound?.querySelector('.el-drawer__close-btn') as HTMLButtonElement)?.click()
      await flushPromises()
    }
    await wrapper.get(`[aria-label="将 Web 用例 ${resultId} 转为缺陷"]`).trigger('click')
    await flushPromises()
    const drafts = JSON.parse(localStorage.getItem(`${OWNER_PREFIX}ai-test-platform.defect-drafts.v1`) || '[]')
    expect(drafts).toHaveLength(1)
    expect(drafts[0].sourceCaseId).toBe(item.sourceCaseId)
    expect(drafts[0].sourceReportCaseId).toBe(`${item.id}#1`)
    expect(drafts[0].status).toBe('pending_jira_export')
  })

  it('keeps the stop feedback below the request-detail header controls', async () => {
    const { wrapper, posts } = await mountWeb()
    const feedback = vi.spyOn(ElMessage, 'info')
    await wrapper.get('[data-testid="execute-web-automation"]').trigger('click')
    await flushPromises()
    ;(document.querySelector('[data-testid="confirm-web-execute"]') as HTMLButtonElement).click()
    await vi.waitFor(() => expect(posts).toHaveLength(1))
    await wrapper.get('.studio-global-task-status').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="stop-web-run"]').trigger('click')
    await flushPromises()
    expect(feedback).toHaveBeenCalledWith({ message: 'Web 执行已停止，已保留当前日志和截图', customClass: 'web-run-terminal-feedback' })
  })

  it('opens the created run monitor through the mounted global task control', async () => {
    const { wrapper, posts } = await mountWeb()
    await wrapper.get('[data-testid="execute-web-automation"]').trigger('click')
    await flushPromises()
    const confirm = document.querySelector('[data-testid="confirm-web-execute"]') as HTMLButtonElement
    expect(confirm).toBeTruthy()
    confirm.click()
    await flushPromises()
    await vi.waitFor(() => expect(posts).toHaveLength(1))
    await wrapper.get('.studio-global-task-status').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="web-monitor-grid"]').exists()).toBe(true)
    expect(new URL(window.location.href).searchParams.get('runId')).toBe('run-fixture')
    expect(new URL(window.location.href).searchParams.get('view')).toBe('monitor')
  })

  it('does not publish monitor navigation when the environment leave confirmation is cancelled', async () => {
    const { wrapper, s, posts } = await mountWeb()
    await wrapper.get('[data-testid="execute-web-automation"]').trigger('click')
    await flushPromises()
    ;(document.querySelector('[data-testid="confirm-web-execute"]') as HTMLButtonElement).click()
    await vi.waitFor(() => expect(posts).toHaveLength(1))
    s.navigateToNav('environments')
    await flushPromises()
    const name = document.querySelector('[data-testid="environment-inline-editor"] input[placeholder="测试环境"]') as HTMLInputElement
    expect(name).toBeTruthy()
    name.value = 'UNSAVED-TASK-NAVIGATION'
    name.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    const confirmation = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    await wrapper.get('.studio-global-task-status').trigger('click')
    await flushPromises()
    expect(window.location.pathname).toBe('/environments')
    expect(window.location.search).toBe('')
    expect(name.value).toBe('UNSAVED-TASK-NAVIGATION')
    confirmation.mockImplementation(() => Promise.resolve('confirm') as never)
    await wrapper.get('.studio-global-task-status').trigger('click')
    await flushPromises()
    expect(window.location.pathname).toBe('/automation/web')
    expect(new URL(window.location.href).searchParams.get('runId')).toBe('run-fixture')
    expect(wrapper.find('[data-testid="web-monitor-grid"]').exists()).toBe(true)
    expect(s.environmentVisible).toBe(false)
    expect(document.querySelector('[data-testid="environment-inline-editor"]')).toBeNull()
  })

  it('closes an open Web environment drawer before the global task enters its monitor', async () => {
    const { wrapper, s, posts } = await mountWeb()
    await wrapper.get('[data-testid="execute-web-automation"]').trigger('click')
    await flushPromises()
    ;(document.querySelector('[data-testid="confirm-web-execute"]') as HTMLButtonElement).click()
    await vi.waitFor(() => expect(posts).toHaveLength(1))

    s.openEnvironmentEditor(undefined, 'web')
    await flushPromises()
    expect(s.environmentVisible).toBe(true)

    await wrapper.get('.studio-global-task-status').trigger('click')
    await flushPromises()

    expect(s.environmentVisible).toBe(false)
    expect(wrapper.find('[data-testid="web-monitor-grid"]').exists()).toBe(true)
  })

  it('submits the case/env/loop captured at start even when live state changes across the await', async () => {
    const gate = defer()
    const { s, posts } = await mountWeb(gate)
    expect(s.webAutomationLoopCount).toBe(1)

    const pending = s.startVisualWebAutomationRun()
    await flushPromises()
    expect(posts).toHaveLength(0)

    // 发起后、POST 落地前改动 live state
    s.webAutomationCases[0].startUrl = '/mutated-after-confirm'
    s.webAutomationLoopCount = 3
    s.webExecutionEnvironmentLibrary.environments.find((environment: { id: string }) => environment.id === 'env-web-b').baseUrl = 'https://changed.example.test'

    gate.resolve(undefined)
    await flushPromises()
    await pending
    await flushPromises()

    expect(posts).toHaveLength(1)
    const body = posts[0] as { loopCount: number; cases: Array<{ startUrl: string }>; runtime: { baseUrl: string } }
    expect(body.loopCount).toBe(1)
    expect(body.cases).toHaveLength(1)
    expect(body.cases[0].startUrl).toBe('/orders/refund/1')
    expect(body.runtime.baseUrl).toBe('https://web-b.example.test')
    expect(s.activeWebRunCases.map((item: { startUrl: string }) => item.startUrl)).toEqual(['/orders/refund/1'])
  })

  it('honours the confirmed request the workspace emitted instead of later live values', async () => {
    const gate = defer()
    const { s, posts } = await mountWeb(gate)

    const pending = s.startVisualWebAutomationRun(undefined, {
      scopeLabel: '执行选中 1 条',
      selectedCaseIds: [CASES[0].id],
      loopCount: 2,
      environmentId: 'env-web-a',
      environmentName: 'Web 环境 A',
      environmentBaseUrl: 'https://web-a.example.test',
      browser: 'firefox',
      mode: 'sequential'
    })
    await flushPromises()

    s.webAutomationLoopCount = 9
    s.webExecutionEnvironmentLibrary.environments.find((environment: { id: string }) => environment.id === 'env-web-a').baseUrl = 'https://changed.example.test'

    gate.resolve(undefined)
    await flushPromises()
    await pending
    await flushPromises()

    expect(posts).toHaveLength(1)
    const body = posts[0] as { loopCount: number; browserOverride?: string; runtime: { baseUrl: string; browser: string } }
    expect(body.loopCount).toBe(2)
    expect(body.browserOverride).toBe('firefox')
    expect(body.runtime.baseUrl).toBe('https://web-a.example.test')
    expect(body.runtime.browser).toBe('firefox')
  })

  it('does not submit anything when the session owner changes before the request is created', async () => {
    const gate = defer()
    const { s, posts } = await mountWeb(gate)

    const pending = s.startVisualWebAutomationRun()
    await flushPromises()

    s.handleSessionExpired()
    await flushPromises()
    gate.resolve(undefined)
    await flushPromises()
    await pending
    await flushPromises()

    expect(posts).toHaveLength(0)
    expect(s.webAutomationRunning).toBe(false)
  })

  it('cancelling the confirmation creates no run at all', async () => {
    const { s, posts } = await mountWeb()

    const execute = document.querySelector('[data-testid="execute-web-automation"]') as HTMLButtonElement
    expect(execute).toBeTruthy()
    execute.click()
    await flushPromises()
    await flushPromises()
    expect(document.querySelector('[data-testid="web-execute-confirm"]')).toBeTruthy()
    expect(posts).toHaveLength(0)

    const cancel = document.querySelector('[data-testid="cancel-web-execute"]') as HTMLButtonElement
    cancel.click()
    await flushPromises()
    await flushPromises()

    expect(posts).toHaveLength(0)
    expect(s.webAutomationRunning).toBe(false)
    expect(s.activeWebRunId).toBe('')
  })

  it('submits exactly what the confirmation dialog showed when confirmed through the UI', async () => {
    const gate = defer()
    const { s, posts } = await mountWeb(gate)
    s.webAutomationLoopCount = 2
    await flushPromises()

    const execute = document.querySelector('[data-testid="execute-web-automation"]') as HTMLButtonElement
    execute.click()
    await flushPromises()
    await flushPromises()
    const dialog = (document.querySelector('[data-testid="web-execute-confirm"]') as HTMLElement).textContent || ''
    expect(dialog).toContain('循环 2 次')
    expect(dialog).toContain('Web 环境 B')

    const confirm = document.querySelector('[data-testid="confirm-web-execute"]') as HTMLButtonElement
    confirm.click()
    await flushPromises()
    expect(posts).toHaveLength(0)

    // 确认之后、POST 落地之前改 live state:循环、用例起址、环境地址全部不得进入本次提交
    s.webAutomationCases[0].startUrl = '/mutated-after-confirm'
    s.webAutomationLoopCount = 5
    s.webExecutionEnvironmentLibrary.environments.find((environment: { id: string }) => environment.id === 'env-web-b').baseUrl = 'https://changed.example.test'

    gate.resolve(undefined)
    await flushPromises()
    await flushPromises()

    expect(posts).toHaveLength(1)
    const body = posts[0] as { loopCount: number; cases: Array<{ startUrl: string }>; runtime: { baseUrl: string } }
    expect(body.loopCount).toBe(2)
    expect(body.cases.map((item) => item.startUrl)).toEqual(['/orders/refund/1'])
    expect(body.runtime.baseUrl).toBe('https://web-b.example.test')
  })
})

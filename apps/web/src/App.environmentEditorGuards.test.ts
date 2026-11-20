import { getAppState } from './ui/appTestAccess'
// @vitest-environment jsdom
// 环境配置页:冷启动绑定目录所选环境 + 未保存草稿的统一 guard(切换类别/选择其他/返回/导航/关闭窗口)。
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'

const APP = './App.vue'
const OWNER_PREFIX = `ai-test-platform.workspace.${encodeURIComponent('user:1')}.`
const API_LIBRARY_KEY = `${OWNER_PREFIX}ai-test-platform.execution-environments.v1`
const WEB_LIBRARY_KEY = `${OWNER_PREFIX}ai-test-platform.web-execution-environments.v1`

const apiLibrary = () => ({
  activeEnvironmentId: 'env-api-b',
  environments: [
    {
      id: 'env-api-a', name: '环境 A（默认）', baseUrl: 'https://api-a.example.test',
      auth: { type: 'none' }, entries: [], createdAt: '2026-10-29T00:00:00.000Z', updatedAt: '2026-10-29T00:00:00.000Z'
    },
    {
      id: 'env-api-b', name: '环境 B（目录所选）', baseUrl: 'https://api-b.example.test',
      auth: { type: 'none' }, entries: [], createdAt: '2026-10-29T00:00:00.000Z', updatedAt: '2026-10-29T00:00:00.000Z'
    }
  ]
})

const webLibrary = () => ({
  activeEnvironmentId: 'env-web-a',
  environments: [{
    id: 'env-web-a', name: 'Web 环境 A', baseUrl: 'https://web-a.example.test',
    auth: { type: 'none' }, entries: [],
    web: {
      browser: 'chromium', viewport: '1440x900', headless: true, defaultTimeoutMs: 30000,
      navigationTimeoutMs: 45000, waitUntil: 'networkidle', screenshotStrategy: 'always',
      videoRecording: false, retryCount: 0, cookieOrigins: [], allowedRequestOrigins: [],
      networkPolicyMode: 'blocklist',
      login: { enabled: false, loginUrl: '', usernameLocator: '', passwordLocator: '', submitLocator: '', username: '', password: '', successType: 'url', successTarget: '' },
      logout: { enabled: false, steps: [] }
    },
    createdAt: '2026-10-29T00:00:00.000Z', updatedAt: '2026-10-29T00:00:00.000Z'
  }]
})

interface Deferred { promise: Promise<unknown>; resolve: (value: unknown) => void; reject: (error: unknown) => void }
const defer = (): Deferred => {
  let resolve!: (value: unknown) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<unknown>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

/** 受控确认框:由用例决定确认/取消,避免过渡动画竞态。 */
const controlConfirm = () => {
  const pending: Deferred[] = []
  const spy = vi.spyOn(ElMessageBox, 'confirm').mockImplementation(((..._args: unknown[]) => {
    const gate = defer()
    pending.push(gate)
    return gate.promise
  }) as never)
  return {
    spy,
    count: () => pending.length,
    last: () => pending[pending.length - 1],
    texts: () => spy.mock.calls.map((call) => String(call[0] ?? '')),
    accept: async () => { pending[pending.length - 1].resolve({ action: 'confirm' }); await flushPromises(); await flushPromises() },
    cancel: async () => { pending[pending.length - 1].reject('cancel'); await flushPromises(); await flushPromises() }
  }
}

const adminUser = { id: 1, username: 'admin', displayName: '管理员', role: 'ADMIN', mustChangePassword: false }

const makeFetch = (template: unknown) => {
  const calls: Array<{ method: string; url: string }> = []
  const impl = vi.fn(async (url: string, init?: RequestInit) => {
    calls.push({ method: (init?.method || 'GET').toUpperCase(), url: String(url) })
    const json = (payload: unknown) => ({ ok: true, status: 200, json: async () => payload }) as unknown as Response
    if (String(url).includes('/api/auth/me')) return json(adminUser)
    if (String(url) === '/api/projects') return json({ items: [{ id: 1, code: 'PROJECT', name: '默认项目' }] })
    if (String(url).includes('/case-template')) return json(template)
    if (String(url).endsWith('/cases')) return json({ items: [] })
    if (String(url).includes('/api/jira/config')) return json({ enabled: false, baseUrl: '', username: '', projectJql: {} })
    if (String(url).includes('/capabilities')) return json({ status: 'UP', browsers: [], checkedAt: '2026-10-29T00:00:00Z' })
    return json({})
  })
  return { impl, calls }
}

const mounted: Array<ReturnType<typeof mount>> = []

const mountEnvironments = async () => {
  vi.resetModules()
  localStorage.clear()
  sessionStorage.clear()
  localStorage.setItem(API_LIBRARY_KEY, JSON.stringify(apiLibrary()))
  localStorage.setItem(WEB_LIBRARY_KEY, JSON.stringify(webLibrary()))
  window.history.replaceState({}, '', '/environments')
  const { defaultTemplateConfig } = await import('./domain/templateConfigRepository')
  const stub = makeFetch(defaultTemplateConfig())
  vi.stubGlobal('fetch', stub.impl)
  const { default: App } = await import(APP)
  const wrapper = mount(App as never, { global: { plugins: [ElementPlus] }, attachTo: document.body })
  mounted.push(wrapper)
  await flushPromises()
  await vi.waitFor(() => expect(document.querySelector('[data-testid="environment-inline-editor"]')).toBeTruthy())
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const s = getAppState(wrapper) as any
  return { wrapper, s, ...stub }
}

const editor = () => document.querySelector('[data-testid="environment-inline-editor"]') as HTMLElement
const nameInput = () => editor().querySelector('input[placeholder="测试环境"]') as HTMLInputElement
const webNameInput = () => editor().querySelector('input[placeholder="Web 测试环境"]') as HTMLInputElement
const baseUrlInput = () => editor().querySelector('input[placeholder="https://api.example.com"]') as HTMLInputElement

const setInput = async (element: HTMLInputElement, value: string) => {
  element.value = value
  element.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
}

const clickByText = async (text: string) => {
  const buttons = Array.from(document.querySelectorAll('button')) as HTMLButtonElement[]
  const found = buttons.find((button) => (button.textContent || '').replace(/\s+/g, '').includes(text.replace(/\s+/g, '')))
  if (!found) throw new Error(`button not found: ${text}`)
  found.click()
  await flushPromises()
  await flushPromises()
}

const beforeUnload = () => {
  const event = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(event)
  return event.defaultPrevented
}

afterEach(() => {
  mounted.splice(0).forEach((wrapper) => { try { wrapper.unmount() } catch { /* already gone */ } })
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

describe('环境配置页冷启动绑定', () => {
  it('binds the inline editor to the environment the directory has selected', async () => {
    const { s } = await mountEnvironments()

    expect(s.currentEnvironmentLibrary.activeEnvironmentId).toBe('env-api-b')
    expect(s.editingEnvironment.id).toBe('env-api-b')
    expect(nameInput().value).toBe('环境 B（目录所选）')
    expect(baseUrlInput().value).toBe('https://api-b.example.test')
    expect(s.environmentDraftDirty).toBe(false)
  })

  it('saves over the same environment and stops reporting unsaved changes', async () => {
    const { s, calls } = await mountEnvironments()

    await setInput(nameInput(), '环境 B（已保存）')
    expect(s.environmentDraftDirty).toBe(true)

    await clickByText('保存环境')

    const stored = JSON.parse(localStorage.getItem(API_LIBRARY_KEY) as string)
    const saved = stored.environments.find((environment: { id: string }) => environment.id === 'env-api-b')
    expect(saved.name).toBe('环境 B（已保存）')
    expect(saved.baseUrl).toBe('https://api-b.example.test')
    expect(stored.environments.find((environment: { id: string }) => environment.id === 'env-api-a').name).toBe('环境 A（默认）')
    expect(s.environmentDraftDirty).toBe(false)
    expect(beforeUnload()).toBe(false)
    expect(calls.some((call) => call.method !== 'GET')).toBe(false)
  })
})

describe('环境配置页未保存草稿 guard', () => {
  it.each(['api', 'web'] as const)('footer cancel confirms and restores the saved %s draft without writes', async (kind) => {
    const { calls } = await mountEnvironments()
    if (kind === 'web') await clickByText('Web环境')
    const input = kind === 'web' ? webNameInput : nameInput
    const baseline = input().value
    const raw = JSON.stringify(localStorage)
    const confirmBox = controlConfirm()
    const cancel = () => editor().querySelectorAll<HTMLButtonElement>('.environment-drawer-actions button')[1]!
    await setInput(input(), 'UNSAVED-FOOTER')
    cancel().click()
    await flushPromises()
    expect(confirmBox.count()).toBe(1)
    await confirmBox.cancel()
    expect(input().value).toBe('UNSAVED-FOOTER')
    expect(JSON.stringify(localStorage)).toBe(raw)
    cancel().click()
    await flushPromises()
    await confirmBox.accept()
    expect(input().value).toBe(baseline)
    expect(beforeUnload()).toBe(false)
    expect(JSON.stringify(localStorage)).toBe(raw)
    expect(calls.some((call) => call.method !== 'GET')).toBe(false)
  })

  it('asks before switching category and keeps the draft when cancelled', async () => {
    const { s } = await mountEnvironments()
    const confirmBox = controlConfirm()

    await setInput(nameInput(), 'UNSAVED-CATEGORY')
    await clickByText('Web环境')

    expect(confirmBox.count()).toBe(1)
    expect(confirmBox.texts()[0]).toContain('丢弃')
    await confirmBox.cancel()

    expect(s.environmentEditorKind).toBe('api')
    expect(s.editingEnvironment.name).toBe('UNSAVED-CATEGORY')
    expect(nameInput().value).toBe('UNSAVED-CATEGORY')

    await clickByText('Web环境')
    expect(confirmBox.count()).toBe(2)
    const storedBefore = JSON.stringify(localStorage)
    await confirmBox.accept()

    expect(s.environmentEditorKind).toBe('web')
    expect(s.editingEnvironment.name).toBe('Web 环境 A')
    expect(webNameInput().value).toBe('Web 环境 A')
    expect(s.environmentDraftDirty).toBe(false)
    // 丢弃草稿不写存储:未保存的修改不会被「顺带保存」
    expect(JSON.stringify(localStorage)).toBe(storedBefore)
  })

  it('asks before selecting another environment and keeps the draft when cancelled', async () => {
    const { s } = await mountEnvironments()
    const confirmBox = controlConfirm()

    await setInput(nameInput(), 'UNSAVED-SELECT')
    await clickByText('环境 A（默认）')

    expect(confirmBox.count()).toBe(1)
    await confirmBox.cancel()
    expect(s.editingEnvironment.id).toBe('env-api-b')
    expect(s.editingEnvironment.name).toBe('UNSAVED-SELECT')

    await clickByText('环境 A（默认）')
    await confirmBox.accept()
    expect(s.editingEnvironment.id).toBe('env-api-a')
    expect(s.editingEnvironment.name).toBe('环境 A（默认）')
  })

  it('asks before returning to the preparation page and stays put when cancelled', async () => {
    const { s } = await mountEnvironments()
    const confirmBox = controlConfirm()

    await setInput(nameInput(), 'UNSAVED-LEAVE')
    await clickByText('返回API执行准备')

    expect(confirmBox.count()).toBe(1)
    // 「返回」走自己的 guard(先确认再动页面状态),不是复用侧栏导航的通用文案
    expect(confirmBox.texts()[0]).toContain('返回执行准备')
    await confirmBox.cancel()

    expect(window.location.pathname).toBe('/environments')
    expect(s.activeNav).toBe('environments')
    expect(s.editingEnvironment.name).toBe('UNSAVED-LEAVE')

    await clickByText('返回API执行准备')
    await confirmBox.accept()
    expect(window.location.pathname).toBe('/automation/api')
    expect(s.activeNav).toBe('automation')
  })

  it('asks before navigating away through the sidebar and blocks the nav when cancelled', async () => {
    const { s } = await mountEnvironments()
    const confirmBox = controlConfirm()

    await setInput(nameInput(), 'UNSAVED-NAV')
    s.navigateToNav('cases')
    await flushPromises()

    expect(confirmBox.count()).toBe(1)
    await confirmBox.cancel()
    expect(s.activeNav).toBe('environments')
    expect(window.location.pathname).toBe('/environments')

    s.navigateToNav('cases')
    await confirmBox.accept()
    expect(s.activeNav).toBe('cases')
  })

  it('registers its own beforeunload listener without replacing window.onbeforeunload', async () => {
    const { s } = await mountEnvironments()

    expect(beforeUnload()).toBe(false)
    expect(window.onbeforeunload).toBeNull()

    await setInput(nameInput(), 'UNSAVED-UNLOAD')

    expect(beforeUnload()).toBe(true)
    expect(window.onbeforeunload).toBeNull()
    expect(s.environmentDraftDirty).toBe(true)
  })

  it('asks before a browser back out of the page and keeps the address when cancelled', async () => {
    const { s } = await mountEnvironments()
    const confirmBox = controlConfirm()

    await setInput(nameInput(), 'UNSAVED-POP')
    window.history.pushState({}, '', '/cases')
    window.dispatchEvent(new PopStateEvent('popstate'))
    await flushPromises()

    expect(confirmBox.count()).toBe(1)
    await confirmBox.cancel()

    expect(s.activeNav).toBe('environments')
    expect(window.location.pathname).toBe('/environments')
    expect(s.editingEnvironment.name).toBe('UNSAVED-POP')
  })
})

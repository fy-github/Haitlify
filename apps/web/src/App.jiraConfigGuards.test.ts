import { getAppState } from './ui/appTestAccess'
// @vitest-environment jsdom
// Jira 配置:真实 App.vue 集成测试(挂载真实组件 + 可控 fetch 桩)。
// 覆盖审计 read-only-admin 提出的失败路径:保存/测试竞态、token/JQL dirty、
// URL/空 JQL/重复项目名校验、重新加载与导航守卫、beforeunload 守卫。
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'

const APP = './App.vue'

interface Deferred<T> { promise: Promise<T>; resolve: (value: T) => void; reject: (error: unknown) => void }
const defer = <T,>(): Deferred<T> => {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

/** 受控的确认框:断言"弹了、问的是什么",再由用例决定确认/取消(避免过渡动画竞态) */
const controlConfirm = () => {
  const pending: Array<Deferred<unknown>> = []
  const spy = vi.spyOn(ElMessageBox, 'confirm').mockImplementation(((..._args: unknown[]) => {
    const gate = defer<unknown>()
    pending.push(gate)
    return gate.promise
  }) as never)
  return {
    spy,
    count: () => pending.length,
    texts: () => spy.mock.calls.map((call) => String(call[0] ?? '')),
    accept: async () => {
      pending[pending.length - 1].resolve({ action: 'confirm' })
      await flushPromises()
      await flushPromises()
    },
    cancel: async () => {
      pending[pending.length - 1].reject('cancel')
      await flushPromises()
      await flushPromises()
    }
  }
}

const adminUser = { id: 1, username: 'admin', displayName: '管理员', role: 'ADMIN', mustChangePassword: false }

const jiraFixture = (overrides: Record<string, unknown> = {}) => ({
  enabled: true,
  baseUrl: 'https://jira-a.example.test',
  authMode: 'service-account',
  username: 'jira-reader',
  credentialConfigured: true,
  maskedToken: '••••abcd',
  projectJql: { HAITLIFY_API: 'project = HAITLIFY' },
  updatedAt: '2026-10-29T00:00:00Z',
  ...overrides
})

interface JiraFetchOptions {
  initial?: Record<string, unknown>
  putGate?: Deferred<void>
  testGate?: Deferred<void>
  testResult?: { success: boolean; message: string }
}

const makeJiraFetch = (options: JiraFetchOptions = {}) => {
  const calls: Array<{ method: string; url: string; body: unknown }> = []
  const fetchImpl = vi.fn(async (url: string, init?: RequestInit) => {
    const method = (init?.method || 'GET').toUpperCase()
    const body = init?.body ? JSON.parse(String(init.body)) : undefined
    calls.push({ method, url: String(url), body })
    const json = (payload: unknown) => ({ ok: true, status: 200, json: async () => payload }) as unknown as Response
    if (String(url).includes('/api/auth/me')) return json(adminUser)
    if (String(url).includes('/api/jira/config/test-connection')) {
      if (options.testGate) await options.testGate.promise
      return json(options.testResult ?? { success: true, message: '连接测试通过' })
    }
    if (String(url).includes('/api/jira/config') && method === 'PUT') {
      if (options.putGate) await options.putGate.promise
      return json({
        enabled: body?.enabled ?? false,
        baseUrl: body?.baseUrl ?? '',
        authMode: body?.authMode ?? 'service-account',
        username: body?.username ?? '',
        credentialConfigured: Boolean(body?.token) || Boolean(options.initial?.credentialConfigured),
        maskedToken: body?.token ? '••••1234' : options.initial?.maskedToken,
        projectJql: body?.projectJql ?? {},
        updatedAt: '2026-10-29T00:00:00Z'
      })
    }
    if (String(url).includes('/api/jira/config')) return json(options.initial ?? jiraFixture())
    return json({})
  })
  return { fetchImpl, calls }
}

interface MountedApp {
  wrapper: ReturnType<typeof mount>
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  s: any
}

const mountedApps: Array<ReturnType<typeof mount>> = []

const mountApp = async (path: string, fetchImpl: unknown): Promise<MountedApp> => {
  vi.resetModules()
  localStorage.clear()
  sessionStorage.clear()
  window.history.replaceState({}, '', path)
  vi.stubGlobal('fetch', fetchImpl)
  const { default: App } = await import(APP)
  const wrapper = mount(App as never, { global: { plugins: [ElementPlus] }, attachTo: document.body })
  mountedApps.push(wrapper)
  await flushPromises()
  await vi.waitFor(() => expect(document.querySelector('h1')).toBeTruthy())
  const s = getAppState(wrapper)
  return { wrapper, s: s as never }
}

const setInput = async (element: Element, value: string): Promise<void> => {
  const target = element as HTMLInputElement | HTMLTextAreaElement
  target.value = value
  target.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
}

const clickButton = async (text: string): Promise<void> => {
  const buttons = Array.from(document.querySelectorAll('button')) as HTMLButtonElement[]
  const found = buttons.find((button) => (button.textContent || '').replace(/\s+/g, '').includes(text.replace(/\s+/g, '')))
  if (!found) throw new Error(`button not found: ${text}`)
  found.click()
  await flushPromises()
}

const stateBarText = (): string =>
  ((document.querySelector('.j-state-bar') as HTMLElement | null)?.textContent || '').replace(/\s+/g, ' ').trim()

const mountJira = async (options: JiraFetchOptions = {}) => {
  const stub = makeJiraFetch(options)
  const app = await mountApp('/jira-config', stub.fetchImpl)
  await vi.waitFor(() => expect(document.querySelector('#jira-base-url')).toBeTruthy())
  return { ...app, ...stub }
}

afterEach(() => {
  mountedApps.splice(0).forEach((wrapper) => {
    try {
      wrapper.unmount()
    } catch {
      // 已经卸载的实例忽略
    }
  })
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})

describe('Jira 配置:未保存修改口径(token / JQL)', () => {
  it('输入新 Token 立即计入未保存修改', async () => {
    const { wrapper } = await mountJira()
    expect(stateBarText()).toContain('无未保存修改')

    await setInput(document.querySelector('#jira-token') as HTMLInputElement, 'brand-new-token-value')

    expect(stateBarText()).toContain('有未保存修改')
    wrapper.unmount()
  })

  it('行内输入 JQL(未失焦)同样计入未保存修改', async () => {
    const { wrapper } = await mountJira()
    const textarea = document.querySelector('.j-table textarea') as HTMLTextAreaElement
    await setInput(textarea, 'project = HAITLIFY order by updated DESC')
    expect(stateBarText()).toContain('有未保存修改')
    wrapper.unmount()
  })

  it('「重新加载」在存在未保存修改时先确认;取消保留草稿,确认后回到服务端配置', async () => {
    const { wrapper, calls } = await mountJira()
    const confirmBox = controlConfirm()
    await setInput(document.querySelector('#jira-token') as HTMLInputElement, 'brand-new-token-value')
    const loads = () => calls.filter((call) => call.url.includes('/api/jira/config') && call.method === 'GET').length
    const before = loads()

    await clickButton('重新加载')
    expect(confirmBox.count()).toBe(1)
    expect(confirmBox.texts()[0]).toContain('丢弃')
    await confirmBox.cancel()

    expect((document.querySelector('#jira-token') as HTMLInputElement).value).toBe('brand-new-token-value')
    expect(loads()).toBe(before)
    expect(stateBarText()).toContain('有未保存修改')

    await clickButton('重新加载')
    expect(confirmBox.count()).toBe(2)
    await confirmBox.accept()
    await flushPromises()

    expect((document.querySelector('#jira-token') as HTMLInputElement).value).toBe('')
    expect(stateBarText()).toContain('无未保存修改')
    wrapper.unmount()
  })
})

describe('Jira 配置:保存前校验', () => {
  it('非法 Base URL 被拦下,不发起保存请求', async () => {
    const { wrapper, calls } = await mountJira()
    await setInput(document.querySelector('#jira-base-url') as HTMLInputElement, 'not-a-url')

    await clickButton('保存配置')

    expect(calls.some((call) => call.method === 'PUT')).toBe(false)
    const error = document.querySelector('.j-error') as HTMLElement | null
    expect(error?.textContent || '').toContain('HTTP/HTTPS')
    wrapper.unmount()
  })

  it('非法 Base URL 不能发起连接测试', async () => {
    const { wrapper, calls } = await mountJira({ testResult: { success: true, message: '连接测试通过' } })
    await setInput(document.querySelector('#jira-base-url') as HTMLInputElement, 'ftp://jira.example.test')

    await clickButton('连接测试')

    expect(calls.some((call) => call.url.includes('test-connection'))).toBe(false)
    expect((document.querySelector('.j-error') as HTMLElement).textContent || '').toContain('HTTP/HTTPS')
    expect((document.querySelector('.j-test-state') as HTMLElement).dataset.state).not.toBe('success')
    wrapper.unmount()
  })

  it('空 JQL 与重复项目名都被拦下,不发起保存请求', async () => {
    const { wrapper, calls } = await mountJira()
    await clickButton('新增映射')
    await clickButton('新增映射')
    const rows = Array.from(document.querySelectorAll('.j-table tbody tr'))
    const keys = rows.map((row) => row.querySelector('input') as HTMLInputElement)
    const jqls = rows.map((row) => row.querySelector('textarea') as HTMLTextAreaElement)
    await setInput(keys[0], 'HAITLIFY')
    await setInput(jqls[0], 'project = HAITLIFY order by created DESC')
    await setInput(keys[1], 'HAITLIFY')
    await setInput(jqls[1], 'project = HAITLIFY order by updated DESC')

    await clickButton('保存配置')
    expect(calls.some((call) => call.method === 'PUT')).toBe(false)
    expect((document.querySelector('.j-error') as HTMLElement).textContent || '').toContain('重复项目名')

    await setInput(jqls[1], '')
    await clickButton('保存配置')
    expect(calls.some((call) => call.method === 'PUT')).toBe(false)
    expect((document.querySelector('.j-error') as HTMLElement).textContent || '').toContain('JQL 不能为空')
    wrapper.unmount()
  })
})

describe('Jira 配置:请求快照与竞态', () => {
  it('连接测试绑定发起时的参数;测试中改动参数后结果置为失效且不冒充新地址', async () => {
    const testGate = defer<void>()
    const { wrapper, calls } = await mountJira({ testGate, testResult: { success: true, message: '连接测试通过' } })

    await clickButton('连接测试')
    const posted = calls.find((call) => call.url.includes('test-connection'))
    expect((posted?.body as { baseUrl: string }).baseUrl).toBe('https://jira-a.example.test')

    await setInput(document.querySelector('#jira-base-url') as HTMLInputElement, 'https://jira-b.example.test')
    testGate.resolve()
    await flushPromises()
    await vi.waitFor(() => expect(document.querySelector('.j-test-state')).toBeTruthy())

    const block = document.querySelector('.j-test-state') as HTMLElement
    const text = (block.textContent || '').replace(/\s+/g, ' ')
    expect(block.dataset.state).toBe('stale')
    expect(text).not.toContain('连接测试通过')
    expect(text).toContain('失效')
    expect(text).toContain('https://jira-a.example.test')
    wrapper.unmount()
  })

  it('保存发起时固定提交内容;保存返回不覆盖期间的新编辑,且仍标记未保存', async () => {
    const putGate = defer<void>()
    const { wrapper, calls } = await mountJira({ putGate })

    await clickButton('保存配置')
    const sent = calls.find((call) => call.method === 'PUT')
    expect((sent?.body as { baseUrl: string }).baseUrl).toBe('https://jira-a.example.test')

    await setInput(document.querySelector('#jira-base-url') as HTMLInputElement, 'https://jira-b.example.test')
    putGate.resolve()
    await flushPromises()
    await vi.waitFor(() => expect(stateBarText()).toContain('未保存修改'))

    expect((document.querySelector('#jira-base-url') as HTMLInputElement).value).toBe('https://jira-b.example.test')
    expect(stateBarText()).toContain('有未保存修改')
    wrapper.unmount()
  })

  it('保存成功不清除仍然适用的连接测试结论', async () => {
    const { wrapper } = await mountJira({ testResult: { success: true, message: '连接测试通过' } })

    await clickButton('连接测试')
    await vi.waitFor(() => expect((document.querySelector('.j-test-state') as HTMLElement).dataset.state).toBe('success'))

    await clickButton('保存配置')
    await flushPromises()

    expect((document.querySelector('.j-test-state') as HTMLElement).dataset.state).toBe('success')
    wrapper.unmount()
  })
})

describe('Jira 配置:离开守卫', () => {
  it('存在未保存修改时导航离开需确认;取消则留在本页,确认才切换', async () => {
    const { wrapper, s } = await mountJira()
    const confirmBox = controlConfirm()
    await setInput(document.querySelector('#jira-base-url') as HTMLInputElement, 'https://edited.example.test')
    expect(stateBarText()).toContain('有未保存修改')

    s.navigateToNav('home')
    await flushPromises()
    expect(confirmBox.count()).toBe(1)
    expect(confirmBox.texts()[0]).toContain('离开')
    await confirmBox.cancel()
    expect(s.activeNav).toBe('jiraConfig')

    s.navigateToNav('home')
    await flushPromises()
    expect(confirmBox.count()).toBe(2)
    await confirmBox.accept()
    expect(s.activeNav).toBe('home')
    wrapper.unmount()
  })

  it('存在未保存修改时注册 beforeunload 并阻止默认关闭', async () => {
    const { wrapper } = await mountJira()
    const clean = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(clean)
    expect(clean.defaultPrevented).toBe(false)

    await setInput(document.querySelector('#jira-token') as HTMLInputElement, 'unsaved-token')
    const dirty = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(dirty)
    expect(dirty.defaultPrevented).toBe(true)
    wrapper.unmount()
  })
})

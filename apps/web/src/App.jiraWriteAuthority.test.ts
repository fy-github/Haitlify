import { getAppState } from './ui/appTestAccess'
// @vitest-environment jsdom
// 复核轮 2(R14/R16/R17):Jira 页写入口的「只读角色拒绝」与「确认回调跨 owner 不生效」。
// 契约:一个 authority —— 发起时固定会话 epoch/operation,await 之后用同一口径校验,
// 不在每处另加布尔 flag;读操作(重新加载)不受只读角色限制。
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'

const ADMIN = { id: 1, username: 'admin', displayName: 'Admin A', role: 'ADMIN', mustChangePassword: false }
const OWNER_B = { ...ADMIN, id: 2, username: 'owner-b', displayName: 'Owner B' }
const VIEWER = { ...ADMIN, id: 3, username: 'viewer', displayName: 'Viewer', role: 'VIEWER' }

const jiraFixture = (owner: 'a' | 'b') => ({
  enabled: true,
  baseUrl: `https://jira-${owner}.example.test`,
  authMode: 'service-account',
  username: `reader-${owner}`,
  token: '',
  credentialConfigured: true,
  maskedToken: 'masked',
  projectJql: { APP: `project = ${owner}` }
})

const usersFixture = () => [
  { username: 'admin', displayName: 'Admin', role: 'ADMIN', enabled: true, mustChangePassword: false, createdAt: '2026-01-01T00:00:00Z' },
  { username: 'editor', displayName: 'Editor', role: 'EDITOR', enabled: true, mustChangePassword: true, createdAt: '2026-01-01T00:00:00Z' }
]

interface Deferred<T> { promise: Promise<T>; resolve: (value: T) => void; reject: (error: unknown) => void }
const defer = <T,>(): Deferred<T> => {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}

const controlConfirm = () => {
  const pending: Array<Deferred<unknown>> = []
  vi.spyOn(ElMessageBox, 'confirm').mockImplementation(((..._args: unknown[]) => {
    const gate = defer<unknown>()
    pending.push(gate)
    return gate.promise
  }) as never)
  return {
    count: () => pending.length,
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

interface Call { method: string; url: string; body: unknown }

const makeFetch = (options: { user?: Record<string, unknown>; nextUser?: Record<string, unknown> } = {}) => {
  const calls: Call[] = []
  let owner: 'a' | 'b' = 'a'
  const json = (payload: unknown, status = 200) => ({ ok: status < 400, status, json: async () => structuredClone(payload) }) as unknown as Response
  const fetchImpl = vi.fn(async (url: string, init?: RequestInit) => {
    const target = String(url)
    const method = (init?.method || 'GET').toUpperCase()
    const body = init?.body ? JSON.parse(String(init.body)) : undefined
    calls.push({ method, url: target, body })
    if (target.includes('/api/auth/me')) return json(options.user ?? ADMIN)
    if (target === '/expire') return json({}, 401)
    if (target.includes('/api/auth/login')) { owner = 'b'; return json(options.nextUser ?? OWNER_B) }
    if (target.includes('/api/jira/config/test-connection')) return json({ success: true, message: '连接测试通过' })
    if (target.includes('/api/jira/config') && method === 'PUT') {
      return json({ ...(body as Record<string, unknown>), token: '', credentialConfigured: true, maskedToken: 'saved' })
    }
    if (target.includes('/api/jira/config')) return json(jiraFixture(owner))
    if (target.includes('/api/users')) return json(usersFixture())
    return json({})
  })
  return {
    fetchImpl,
    calls,
    writes: () => calls.filter((call) => call.method !== 'GET'),
    gets: (fragment: string) => calls.filter((call) => call.method === 'GET' && call.url.includes(fragment))
  }
}

const mountedApps: Array<ReturnType<typeof mount>> = []

const mountApp = async (path: string, options: { user?: Record<string, unknown>; nextUser?: Record<string, unknown> } = {}) => {
  vi.resetModules()
  localStorage.clear()
  sessionStorage.clear()
  window.history.replaceState({}, '', path)
  const stub = makeFetch(options)
  vi.stubGlobal('fetch', stub.fetchImpl)
  // 变量路径:避开单文件 tsc 对 .vue 模块声明的解析(与既有 App.*.test.ts 一致)
  const appModule = './App.vue'
  const { default: App } = await import(appModule)
  const wrapper = mount(App as never, { global: { plugins: [ElementPlus] }, attachTo: document.body })
  mountedApps.push(wrapper)
  await flushPromises()
  await flushPromises()
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const s = getAppState(wrapper)
  return { wrapper, s, ...stub }
}

/** 401 重新登录为 owner-b:会话 epoch 前进,所有旧上下文内的动作都应失效 */
const switchOwner = async (wrapper: ReturnType<typeof mount>): Promise<void> => {
  const http = await import('./domain/http')
  await http.authedFetch('/expire')
  await flushPromises()
  await wrapper.get('[name="username"]').setValue('owner-b')
  await wrapper.get('[name="password"]').setValue('fixture-password')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  await flushPromises()
}

const setInput = async (selector: string, value: string): Promise<void> => {
  const element = document.querySelector(selector) as HTMLInputElement | HTMLTextAreaElement
  expect(element, selector).toBeTruthy()
  element.value = value
  element.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
}

afterEach(() => {
  mountedApps.splice(0).forEach((wrapper) => {
    try {
      wrapper.unmount()
    } catch {
      // 已卸载的实例忽略
    }
  })
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})

describe('Jira 写入口:只读角色(R14)', () => {
  it('VIEWER 直连 /jira-config:保存、测试、新增映射全部被 handler 拒绝', async () => {
    const { s, writes, gets } = await mountApp('/jira-config', { user: VIEWER })
    // 读:只读角色仍然可以重新加载服务端配置
    await s.loadJiraSettingsFromBackend()
    await flushPromises()
    expect(gets('/api/jira/config').length).toBeGreaterThan(0)
    const rowsBefore = s.jiraProjectJqlRows.length

    await s.saveJiraSettings()
    await s.testJiraConnection()
    s.addJiraProjectJqlRow()
    await flushPromises()

    expect(writes()).toHaveLength(0)
    expect(s.jiraProjectJqlRows.length).toBe(rowsBefore)
    expect(s.jiraConfigValidationError).toContain('只读')
  }, 20000)

  it('VIEWER 的写控件在界面上是禁用的,重新加载(读)保持可用', async () => {
    const { writes, gets } = await mountApp('/jira-config', { user: VIEWER })
    const save = document.querySelector('.jira-head .el-button--primary') as HTMLButtonElement | null
    const buttons = Array.from(document.querySelectorAll('button')) as HTMLButtonElement[]
    const findByText = (text: string) => buttons.find((button) => (button.textContent || '').includes(text))
    const test = findByText('连接测试')
    const addRow = findByText('新增映射')
    const remove = document.querySelector('.j-table .j-remove') as HTMLButtonElement | null

    expect(save).toBeTruthy()
    expect(save?.disabled).toBe(true)
    expect(test?.disabled).toBe(true)
    expect(addRow?.disabled).toBe(true)
    expect(remove?.disabled).toBe(true)
    // 只读角色仍然可以读取服务端配置
    ;(findByText('重新加载') as HTMLButtonElement).click()
    await flushPromises()
    expect(gets('/api/jira/config').length).toBeGreaterThan(0)
    expect(writes()).toHaveLength(0)
  })

  it('ADMIN 的写控件保持可用(只读门禁不误伤)', async () => {
    await mountApp('/jira-config')
    const save = document.querySelector('.jira-head .el-button--primary') as HTMLButtonElement | null
    const buttons = Array.from(document.querySelectorAll('button')) as HTMLButtonElement[]
    const findByText = (text: string) => buttons.find((button) => (button.textContent || '').includes(text))
    expect(save?.disabled).toBe(false)
    expect(findByText('连接测试')?.disabled).toBe(false)
    expect(findByText('新增映射')?.disabled).toBe(false)
  })
})

describe('Jira 未落定的确认:跨 owner 不生效(R16/R17)', () => {
  it('挂起的删除映射确认,owner 切换后确认不改动新 owner 的行', async () => {
    const { s, wrapper } = await mountApp('/jira-config')
    const confirm = controlConfirm()
    void s.removeJiraProjectJqlRow(0)
    await flushPromises()
    expect(confirm.count()).toBe(1)

    await switchOwner(wrapper)
    const ownerRows = JSON.stringify(s.jiraProjectJqlRows)
    expect(s.currentUser.username).toBe('owner-b')
    expect(ownerRows).toContain('project = b')

    await confirm.accept()
    expect(JSON.stringify(s.jiraProjectJqlRows)).toBe(ownerRows)
  })

  it('同一会话内的删除映射确认仍然生效(门禁不误伤)', async () => {
    const { s } = await mountApp('/jira-config')
    const confirm = controlConfirm()
    const rowsBefore = s.jiraProjectJqlRows.length
    void s.removeJiraProjectJqlRow(0)
    await flushPromises()
    await confirm.accept()
    expect(s.jiraProjectJqlRows.length).toBe(rowsBefore - 1)
  })

  it('挂起的离开确认,owner 切换后确认不覆盖新会话导航', async () => {
    const { s, wrapper } = await mountApp('/jira-config')
    const confirm = controlConfirm()
    await setInput('#jira-token', 'fixture-token')
    s.navigateToNav('home')
    await flushPromises()
    expect(confirm.count()).toBe(1)

    await switchOwner(wrapper)
    s.navigateToNav('users')
    await flushPromises()
    const navigationAfterSwitch = s.activeNav
    expect(navigationAfterSwitch).toBe('users')

    await confirm.accept()
    expect(s.activeNav).toBe(navigationAfterSwitch)
  })

  it('同一会话内的离开确认仍然导航(门禁不误伤)', async () => {
    const { s } = await mountApp('/jira-config')
    const confirm = controlConfirm()
    await setInput('#jira-token', 'fixture-token')
    s.navigateToNav('home')
    await flushPromises()
    await confirm.accept()
    expect(s.activeNav).toBe('home')
  })
})

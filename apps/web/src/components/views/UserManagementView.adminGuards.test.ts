// @vitest-environment jsdom
// 用户管理:真实视图 + 真实 handler 的保护规则(当前账号 / 最后可用管理员 / 创建校验 / 角色检索)。
// 审计 read-only-admin 指出抽屉保存路径绕过了列表级保护,这里直接约束抽屉路径。
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'

const usersFixture = () => [
  { username: 'admin', displayName: '平台负责人', role: 'ADMIN', enabled: true, mustChangePassword: false, createdAt: '2026-08-01T01:20:00Z' },
  { username: 'qa.editor', displayName: '编辑者', role: 'EDITOR', enabled: true, mustChangePassword: true, createdAt: '2026-08-11T06:10:00Z' },
  { username: 'reviewer', displayName: '观察者', role: 'VIEWER', enabled: true, mustChangePassword: false, createdAt: '2026-09-02T02:05:00Z' }
]

interface Writes { method: string; url: string; body: unknown }

const mountedViews: Array<ReturnType<typeof mount>> = []

const mountView = async (currentUsername: string, fixture = usersFixture()) => {
  vi.resetModules()
  const writes: Writes[] = []
  const fetchImpl = vi.fn(async (url: string, init?: RequestInit) => {
    const method = (init?.method || 'GET').toUpperCase()
    if (method !== 'GET') writes.push({ method, url: String(url), body: init?.body ? JSON.parse(String(init.body)) : undefined })
    return { ok: true, status: 200, json: async () => fixture } as unknown as Response
  })
  vi.stubGlobal('fetch', fetchImpl)
  const { default: UserManagementView } = await import('./UserManagementView.vue')
  const wrapper = mount(UserManagementView, {
    props: { currentUsername },
    global: { plugins: [ElementPlus] },
    attachTo: document.body
  })
  mountedViews.push(wrapper)
  await flushPromises()
  await vi.waitFor(() => expect(document.querySelectorAll('.el-table__row').length).toBeGreaterThan(0))
  return { wrapper, writes }
}

const clickText = async (text: string, root: ParentNode = document): Promise<void> => {
  const buttons = Array.from(root.querySelectorAll('button')) as HTMLButtonElement[]
  const found = buttons.find((button) => (button.textContent || '').replace(/\s+/g, '').includes(text.replace(/\s+/g, '')))
  if (!found) throw new Error(`button not found: ${text}`)
  found.click()
  await flushPromises()
}

const editUser = async (username: string): Promise<void> => {
  const row = Array.from(document.querySelectorAll('.el-table__row'))
    .find((element) => (element.textContent || '').includes(username)) as HTMLElement
  if (!row) throw new Error(`row not found: ${username}`)
  await clickText('编辑', row)
  await vi.waitFor(() => expect(document.querySelector('.studio-responsive-drawer')).toBeTruthy())
}

const drawerError = (): string =>
  ((document.querySelector('.user-drawer__error') as HTMLElement | null)?.textContent || '').trim()

afterEach(() => {
  mountedViews.splice(0).forEach((wrapper) => {
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

describe('用户管理:抽屉保存的账号保护', () => {
  it('不能停用当前登录账号:抽屉保存被拦,不发起更新请求', async () => {
    const { wrapper, writes } = await mountView('admin')
    await editUser('admin')

    ;(document.querySelector('.studio-responsive-drawer .el-switch') as HTMLElement).click()
    await flushPromises()
    await clickText('保存修改')

    expect(drawerError()).toContain('当前账号')
    expect(writes).toHaveLength(0)
    wrapper.unmount()
  })

  it('不能降级当前登录管理员:抽屉保存被拦,不发起更新请求', async () => {
    const { wrapper, writes } = await mountView('admin')
    await editUser('admin')

    const viewerOption = Array.from(document.querySelectorAll('.user-role-option'))
      .find((option) => (option.textContent || '').includes('观察者')) as HTMLButtonElement
    viewerOption.click()
    await flushPromises()
    await clickText('保存修改')

    expect(drawerError()).toContain('当前账号')
    expect(writes).toHaveLength(0)
    wrapper.unmount()
  })

  it('不能移除最后一个可用管理员:抽屉降级最后一位管理员被拦', async () => {
    const { wrapper, writes } = await mountView('someone-else')
    await editUser('admin')

    const viewerOption = Array.from(document.querySelectorAll('.user-role-option'))
      .find((option) => (option.textContent || '').includes('观察者')) as HTMLButtonElement
    viewerOption.click()
    await flushPromises()
    await clickText('保存修改')

    expect(drawerError()).toContain('至少保留一个可用的管理员')
    expect(writes).toHaveLength(0)
    wrapper.unmount()
  })
})

describe('用户管理:创建校验与检索', () => {
  it('用户名重复被拦下,不发起创建请求', async () => {
    const { wrapper, writes } = await mountView('admin')
    await clickText('新建用户')
    await vi.waitFor(() => expect(document.querySelector('.studio-responsive-drawer')).toBeTruthy())

    const inputs = Array.from(document.querySelectorAll('.studio-responsive-drawer input')) as HTMLInputElement[]
    const username = inputs.find((input) => input.placeholder === '如 qa.zhang') as HTMLInputElement
    const display = inputs.find((input) => input.placeholder === '如 张三') as HTMLInputElement
    const password = inputs.find((input) => (input.placeholder || '').includes('至少 8 位')) as HTMLInputElement
    username.value = 'admin'
    username.dispatchEvent(new Event('input', { bubbles: true }))
    display.value = '新同事'
    display.dispatchEvent(new Event('input', { bubbles: true }))
    password.value = 'passw0rd123'
    password.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()

    await clickText('创建账号')

    expect(drawerError()).toContain('用户名已存在')
    expect(writes).toHaveLength(0)
    wrapper.unmount()
  })

  it('显示名为空被拦下,不发起创建请求', async () => {
    const { wrapper, writes } = await mountView('admin')
    await clickText('新建用户')
    await vi.waitFor(() => expect(document.querySelector('.studio-responsive-drawer')).toBeTruthy())

    const inputs = Array.from(document.querySelectorAll('.studio-responsive-drawer input')) as HTMLInputElement[]
    const username = inputs.find((input) => input.placeholder === '如 qa.zhang') as HTMLInputElement
    const password = inputs.find((input) => (input.placeholder || '').includes('至少 8 位')) as HTMLInputElement
    username.value = 'new.user'
    username.dispatchEvent(new Event('input', { bubbles: true }))
    password.value = 'passw0rd123'
    password.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()

    await clickText('创建账号')

    expect(drawerError()).toContain('显示名不能为空')
    expect(writes).toHaveLength(0)
    wrapper.unmount()
  })

  it('关键词命中角色名(管理员/观察者)也能筛出账号', async () => {
    const { wrapper } = await mountView('admin')
    const search = document.querySelector('.user-search input') as HTMLInputElement
    search.value = '管理员'
    search.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    expect(document.querySelectorAll('.el-table__row').length).toBe(1)

    search.value = '观察者'
    search.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    expect(document.querySelectorAll('.el-table__row').length).toBe(1)
    wrapper.unmount()
  })
})

describe('用户管理:抽屉版式', () => {
  it('抽屉宽 740px 且底部按钮使用设计稿 footer', async () => {
    const { wrapper } = await mountView('admin')
    await editUser('admin')

    const drawer = document.querySelector('.studio-responsive-drawer') as HTMLElement
    expect((drawer.getAttribute('style') || '')).toContain('740px')
    const actions = document.querySelector('.studio-responsive-drawer .drawer-actions') as HTMLElement | null
    expect(actions).toBeTruthy()
    expect(actions?.textContent || '').toContain('取消')
    expect(actions?.textContent || '').toContain('保存修改')
    wrapper.unmount()
  })
})

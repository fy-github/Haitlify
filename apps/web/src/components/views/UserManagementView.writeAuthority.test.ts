// @vitest-environment jsdom
// 复核轮 2(R18/R19/R20):用户管理的特权写在 await 确认之后必须仍属于同一 authority。
// 契约:发起时固定「挂载代 + 当前登录账号」,await 之后再校验一次;owner/self 变化或组件
// 卸载都使该 authority 失效,不再 splice/导航/发写。同一上下文内确认仍照常写入(不误伤)。
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'

const VIEW = './UserManagementView.vue'

const usersFixture = () => [
  { username: 'admin', displayName: '平台负责人', role: 'ADMIN', enabled: true, mustChangePassword: false, createdAt: '2026-08-01T01:20:00Z' },
  { username: 'qa.editor', displayName: '编辑者', role: 'EDITOR', enabled: true, mustChangePassword: true, createdAt: '2026-08-11T06:10:00Z' },
  { username: 'second.admin', displayName: '第二管理员', role: 'ADMIN', enabled: true, mustChangePassword: false, createdAt: '2026-09-03T02:05:00Z' }
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
    }
  }
}

const mountedViews: Array<ReturnType<typeof mount>> = []

const mountView = async (currentUsername: string, fixture = usersFixture()) => {
  vi.resetModules()
  const writes: Array<{ method: string; url: string; body: unknown }> = []
  const fetchImpl = vi.fn(async (url: string, init?: RequestInit) => {
    const method = (init?.method || 'GET').toUpperCase()
    if (method !== 'GET') {
      writes.push({ method, url: String(url), body: init?.body ? JSON.parse(String(init.body)) : undefined })
    }
    return { ok: true, status: 200, json: async () => fixture } as unknown as Response
  })
  vi.stubGlobal('fetch', fetchImpl)
  const { default: UserManagementView } = await import(VIEW)
  const wrapper = mount(UserManagementView, {
    props: { currentUsername },
    global: { plugins: [ElementPlus] },
    attachTo: document.body
  })
  mountedViews.push(wrapper)
  await flushPromises()
  await flushPromises()
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const s = ((wrapper.vm as any).$).setupState
  return { wrapper, s, writes }
}

afterEach(() => {
  mountedViews.splice(0).forEach((wrapper) => {
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

describe('权限确认的 authority(R18)', () => {
  it('确认期间 owner 变成目标账号:确认后不再发 self 停用请求', async () => {
    const { s, wrapper, writes } = await mountView('second.admin')
    const confirm = controlConfirm()
    s.openEdit(s.users[0])
    s.draft.enabled = false
    void s.submitDrawer()
    await flushPromises()
    expect(confirm.count()).toBe(1)

    await wrapper.setProps({ currentUsername: 'admin' })
    await confirm.accept()

    expect(writes).toHaveLength(0)
    expect(s.drawerError).toContain('身份')
  })

  it('确认期间 owner 未变:确认后照常保存(门禁不误伤)', async () => {
    const { s, writes } = await mountView('second.admin')
    const confirm = controlConfirm()
    s.openEdit(s.users[0])
    s.draft.enabled = false
    void s.submitDrawer()
    await flushPromises()
    await confirm.accept()

    expect(writes).toHaveLength(1)
    expect(writes[0].method).toBe('PUT')
    expect(writes[0].url).toContain('/api/users/admin')
    expect(writes[0].body).toMatchObject({ enabled: false })
  })
})

describe('权限确认的 authority(R19/R20)', () => {
  it('组件卸载后,挂起的抽屉权限确认不再发特权写', async () => {
    const { s, wrapper, writes } = await mountView('second.admin')
    const confirm = controlConfirm()
    s.openEdit(s.users[0])
    s.draft.enabled = false
    void s.submitDrawer()
    await flushPromises()
    expect(confirm.count()).toBe(1)

    wrapper.unmount()
    await confirm.accept()

    expect(writes).toHaveLength(0)
  })

  it.each(['toggleEnabled', 'removeUser'])('组件卸载后,挂起的列表 %s 确认不再发写', async (handler) => {
    const { s, wrapper, writes } = await mountView('admin')
    const confirm = controlConfirm()
    void s[handler](s.users[1])
    await flushPromises()
    expect(confirm.count()).toBe(1)

    wrapper.unmount()
    await confirm.accept()

    expect(writes).toHaveLength(0)
  })

  it('同一上下文的列表停用确认仍然写入(门禁不误伤)', async () => {
    const { s, writes } = await mountView('admin')
    const confirm = controlConfirm()
    void s.toggleEnabled(s.users[1])
    await flushPromises()
    await confirm.accept()

    expect(writes).toHaveLength(1)
    expect(writes[0].url).toContain('/api/users/qa.editor')
    expect(writes[0].body).toMatchObject({ enabled: false })
  })
})

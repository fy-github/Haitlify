// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'

const user = { id: 74, username: 'new-user', displayName: '新账号', role: 'EDITOR', mustChangePassword: false }
const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
const deferred = () => {
  let resolve!: (value: Response) => void
  const promise = new Promise<Response>(yes => { resolve = yes })
  return { promise, resolve }
}
afterEach(() => vi.unstubAllGlobals())

it.each(['bootstrap', 'login'])('keeps the accepted %s session when an earlier non-abortable 401 arrives', async path => {
  vi.resetModules()
  localStorage.clear(); sessionStorage.clear()
  window.history.replaceState({}, '', '/')
  const authentication = deferred()
  const old = deferred()
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (url.includes('/api/auth/me')) return path === 'bootstrap' ? authentication.promise : response({}, 401)
    if (url.includes('/api/auth/login')) return authentication.promise
    if (url === '/old-session') return old.promise
    if (url === '/current-session') return response({}, 401)
    return response({})
  }))
  const { default: App } = await import('./App.vue')
  const { authedFetch } = await import('./domain/http')
  const wrapper = mount(App as never, { global: { plugins: [ElementPlus] } })
  try {
    if (path === 'login') {
      await flushPromises()
      await wrapper.get('[name="username"]').setValue('new-user')
      await wrapper.get('[name="password"]').setValue('test-password')
      await wrapper.get('form').trigger('submit')
    }
    // No AbortSignal, and the App keeps the same global callback registered throughout.
    const pending = authedFetch('/old-session')
    authentication.resolve(response(user))
    await flushPromises()
    expect(wrapper.find('[data-testid="home-open-chain"]').exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'GlobalTopbar' }).props('userDisplayName')).toBe('新账号')
    old.resolve(response({}, 401))
    expect((await pending).status).toBe(401)
    await flushPromises()
    expect(wrapper.find('[data-testid="home-open-chain"]').exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'GlobalTopbar' }).props('userDisplayName')).toBe('新账号')
    await authedFetch('/current-session'); await flushPromises()
    expect(wrapper.find('[name="username"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="home-open-chain"]').exists()).toBe(false)
  } finally { wrapper.unmount() }
})

it('shows a real failed login after advancing the session epoch', async () => {
  vi.resetModules()
  localStorage.clear(); sessionStorage.clear()
  vi.stubGlobal('fetch', vi.fn(async (url: string) => response({ message: url.includes('/login') ? '用户名或密码错误' : '未登录' }, 401)))
  const { default: App } = await import('./App.vue')
  const wrapper = mount(App as never, { global: { plugins: [ElementPlus] } })
  try {
    await flushPromises()
    await wrapper.get('[name="username"]').setValue('new-user')
    await wrapper.get('[name="password"]').setValue('wrong')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('用户名或密码错误')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
  } finally { wrapper.unmount() }
})

import { getAppState } from './ui/appTestAccess'
// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'

const actor = { id: 73, username: 'old', displayName: 'OLD', role: 'EDITOR', mustChangePassword: false }
const nextActor = { ...actor, id: 74, username: 'new', displayName: 'NEW' }
const reply = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
const deferred = () => {
  let resolve!: (value: Response) => void
  const promise = new Promise<Response>(yes => { resolve = yes })
  return { promise, resolve }
}
const init = async (fetcher: ReturnType<typeof vi.fn>) => {
  vi.resetModules(); localStorage.clear(); sessionStorage.clear(); window.history.replaceState({}, '', '/')
  vi.stubGlobal('fetch', fetcher)
  const { default: App } = await import('./App.vue')
  const http = await import('./domain/http')
  const wrapper = mount(App as never, { global: { plugins: [ElementPlus], stubs: {
    ElDialog: { props: ['modelValue'], template: '<section v-if="modelValue"><slot/><slot name="footer"/></section>' }
  } } })
  return { wrapper, http }
}
// Retain the setup object to observe writes after Vue has removed the DOM.
const stateOf = (w: ReturnType<typeof mount>) => getAppState(w)

const authSnapshot = (w: ReturnType<typeof mount>) => {
  const s = stateOf(w)
  return JSON.stringify({ user: s.currentUser, status: s.authStatus, dialog: s.changePasswordDialogVisible, form: s.changePasswordForm })
}
const openPassword = async (w: ReturnType<typeof mount>) => {
  w.findComponent({ name: 'GlobalTopbar' }).vm.$emit('change-password'); await flushPromises()
}
const submitPassword = async (w: ReturnType<typeof mount>) => {
  await w.get('input[placeholder="当前密码"]').setValue('current-pass')
  await w.get('input[placeholder="新密码（至少 8 位）"]').setValue('next-password')
  await w.get('input[placeholder="确认新密码"]').setValue('next-password')
  await w.findAll('button').find(b => b.text().includes('确认修改'))!.trigger('click'); await flushPromises()
}
const signIn = async (w: ReturnType<typeof mount>) => {
  await w.get('[name="username"]').setValue('new'); await w.get('[name="password"]').setValue('test-password')
  await w.get('form').trigger('submit'); await flushPromises()
  expect(stateOf(w).currentUser?.id).toBe(nextActor.id)
}
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); ElMessage.closeAll() })

it.each(['success', '500'])('bootstrap %s is discarded as soon as login starts, before it completes', async outcome => {
  const bootstrap = deferred(), login = deferred()
  const { wrapper: w, http } = await init(vi.fn(async (url: string) => {
    if (url.includes('/auth/me')) return bootstrap.promise
    if (url.includes('/auth/login')) return login.promise
    if (url === '/expire') return reply({}, 401)
    return reply({})
  }))
  try {
    await http.authedFetch('/expire'); await flushPromises()
    await w.get('[name="username"]').setValue('new'); await w.get('[name="password"]').setValue('test-password'); await w.get('form').trigger('submit')
    const before = authSnapshot(w)
    bootstrap.resolve(outcome === 'success' ? reply(actor) : reply({}, 500)); await flushPromises()
    expect(authSnapshot(w)).toBe(before)
    expect(w.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    login.resolve(reply(nextActor)); await flushPromises()
    expect(stateOf(w).currentUser).toEqual(nextActor)
  } finally { w.unmount() }
})

it('logout initiation fences password success before the logout response arrives', async () => {
  const password = deferred(), logout = deferred()
  const { wrapper: w } = await init(vi.fn(async (url: string) => {
    if (url.includes('/auth/me')) return reply(actor)
    if (url.includes('/auth/password')) return password.promise
    if (url.includes('/auth/logout')) return logout.promise
    return reply({})
  }))
  const message = vi.spyOn(ElMessage, 'success')
  try {
    await flushPromises(); await openPassword(w); await submitPassword(w)
    w.findComponent({ name: 'GlobalTopbar' }).vm.$emit('logout'); await flushPromises()
    const before = authSnapshot(w)
    password.resolve(reply({ ...actor, displayName: 'STALE' })); await flushPromises()
    expect(authSnapshot(w)).toBe(before)
    expect(message).not.toHaveBeenCalled()
    logout.resolve(reply({})); await flushPromises()
    expect(stateOf(w).authStatus).toBe('unauthenticated')
  } finally { w.unmount() }
})

it.each(['success', '401', '500'])('old password %s cannot alter new dialog, busy flag, actor or messages', async outcome => {
  const old = deferred(), next = deferred()
  let calls = 0
  const { wrapper: w, http } = await init(vi.fn(async (url: string) => {
    if (url.includes('/auth/me')) return reply(actor)
    if (url.includes('/auth/password')) return ++calls === 1 ? old.promise : next.promise
    if (url.includes('/auth/login')) return reply(nextActor)
    if (url === '/expire') return reply({}, 401)
    return reply({})
  }))
  const message = vi.spyOn(ElMessage, 'success')
  try {
    await flushPromises(); await openPassword(w); await submitPassword(w)
    await http.authedFetch('/expire'); await flushPromises(); await signIn(w)
    expect(stateOf(w).changePasswordDialogVisible).toBe(false)
    expect(stateOf(w).changePasswordForm.submitting).toBe(false)
    await openPassword(w); await submitPassword(w)
    expect(stateOf(w).changePasswordForm.submitting).toBe(true)
    const before = authSnapshot(w)
    message.mockClear()
    old.resolve(outcome === 'success' ? reply(actor) : reply({ message: 'old failure' }, Number(outcome)))
    await flushPromises()
    expect(authSnapshot(w)).toBe(before)
    expect(message).not.toHaveBeenCalled()
    next.resolve(reply({ ...nextActor, displayName: 'UPDATED' })); await flushPromises()
    expect(stateOf(w).currentUser?.displayName).toBe('UPDATED')
    expect(stateOf(w).changePasswordDialogVisible).toBe(false)
    expect(stateOf(w).changePasswordForm.submitting).toBe(false)
    expect(message).toHaveBeenCalledWith('密码已更新')
  } finally { w.unmount() }
})

it.each(['bootstrap', 'password', 'logout', 'login'])('no auth writes or workspace requests after unmount: %s', async operation => {
  for (const outcome of ['success', '401', '500']) {
    const pending = deferred()
    const fetcher = vi.fn(async (url: string) => {
      if (url.includes('/auth/me')) return operation === 'bootstrap' ? pending.promise : operation === 'login' ? reply({}, 401) : reply(actor)
      if (url.includes('/auth/')) return pending.promise
      return reply({})
    })
    const { wrapper: w, http } = await init(fetcher)
    const message = vi.spyOn(ElMessage, 'success')
    await flushPromises()
    if (operation === 'password') { await openPassword(w); await submitPassword(w) }
    if (operation === 'logout') { w.findComponent({ name: 'GlobalTopbar' }).vm.$emit('logout'); await flushPromises() }
    if (operation === 'login') {
      await w.get('[name="username"]').setValue('new'); await w.get('[name="password"]').setValue('test-password'); await w.get('form').trigger('submit')
    }
    w.unmount()
    const before = authSnapshot(w), requests = fetcher.mock.calls.length
    message.mockClear()
    pending.resolve(outcome === 'success' ? reply({ ...nextActor, mustChangePassword: true }) : reply({}, Number(outcome)))
    await flushPromises()
    expect(authSnapshot(w)).toBe(before)
    expect(fetcher.mock.calls.length).toBe(requests)
    expect(message).not.toHaveBeenCalled()
    // The handler registered by the removed App must also be inert.
    fetcher.mockResolvedValue(reply({}, 401))
    await http.authedFetch('/after-unmount'); await flushPromises()
    expect(authSnapshot(w)).toBe(before)
    message.mockRestore()
  }
})

it.each([200, 401, 500])('normal logout %s clears only its own session', async status => {
  const { wrapper: w } = await init(vi.fn(async (url: string) => url.includes('/auth/me') ? reply(actor) : reply({}, url.includes('/auth/logout') ? status : 200)))
  try {
    await flushPromises()
    expect(stateOf(w).authStatus).toBe('authenticated')
    await openPassword(w)
    w.findComponent({ name: 'GlobalTopbar' }).vm.$emit('logout'); await flushPromises()
    expect(stateOf(w).currentUser).toBeNull()
    expect(stateOf(w).authStatus).toBe('unauthenticated')
    expect(stateOf(w).changePasswordDialogVisible).toBe(false)
  } finally { w.unmount() }
})

it.each([401, 500])('normal bootstrap %s shows login', async status => {
  const { wrapper: w } = await init(vi.fn(async () => reply({}, status)))
  try { await flushPromises(); expect(w.find('[name="username"]').exists()).toBe(true) } finally { w.unmount() }
})

it('normal password failure retains dialog and actor and releases submitting', async () => {
  const { wrapper: w } = await init(vi.fn(async (url: string) => url.includes('/auth/me') ? reply(actor) : reply({ message: '当前密码错误' }, 400)))
  try {
    await flushPromises(); await openPassword(w); await submitPassword(w)
    expect(stateOf(w).currentUser).toEqual(actor)
    expect(stateOf(w).changePasswordDialogVisible).toBe(true)
    expect(stateOf(w).changePasswordForm.error).toBe('当前密码错误')
    expect(stateOf(w).changePasswordForm.submitting).toBe(false)
  } finally { w.unmount() }
})

it('reopening the password dialog invalidates the previous operation without changing sessions', async () => {
  const old = deferred()
  const { wrapper: w } = await init(vi.fn(async (url: string) => url.includes('/auth/me') ? reply(actor) : url.includes('/auth/password') ? old.promise : reply({})))
  try {
    await flushPromises(); await openPassword(w); await submitPassword(w)
    await openPassword(w)
    const before = authSnapshot(w)
    old.resolve(reply({ ...actor, displayName: 'stale update' })); await flushPromises()
    expect(authSnapshot(w)).toBe(before)
  } finally { w.unmount() }
})

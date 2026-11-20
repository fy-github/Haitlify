// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const user = { id: 1, username: 'actor', displayName: 'Actor', role: 'EDITOR', mustChangePassword: false }
const reply = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
const deferred = () => {
  let resolve!: (value: Response) => void
  const promise = new Promise<Response>(yes => { resolve = yes })
  return { promise, resolve }
}
afterEach(() => vi.unstubAllGlobals())

it.each(['success', 'error'])('does not emit or mutate after unmount: %s', async outcome => {
  vi.resetModules()
  const old = deferred()
  vi.stubGlobal('fetch', vi.fn(() => old.promise))
  const { default: LoginScreen } = await import('./LoginScreen.vue')
  const wrapper = mount(LoginScreen)
  await wrapper.get('[name="username"]').setValue('actor')
  await wrapper.get('[name="password"]').setValue('test-password')
  await wrapper.get('form').trigger('submit')
  wrapper.unmount()
  old.resolve(outcome === 'success' ? reply(user) : reply({ message: 'old failure' }, 500))
  await flushPromises()
  expect(wrapper.emitted('authenticated')).toBeUndefined()
})

it.each(['success', 'error'])('ignores a login displaced by a newer session: %s', async outcome => {
  vi.resetModules()
  const old = deferred()
  vi.stubGlobal('fetch', vi.fn(() => old.promise))
  const { default: LoginScreen } = await import('./LoginScreen.vue')
  const { advanceAuthSessionEpoch } = await import('../../domain/http')
  const wrapper = mount(LoginScreen)
  try {
    await wrapper.get('[name="username"]').setValue('actor')
    await wrapper.get('[name="password"]').setValue('test-password')
    await wrapper.get('form').trigger('submit')
    advanceAuthSessionEpoch()
    old.resolve(outcome === 'success' ? reply(user) : reply({ message: 'old failure' }, 500))
    await flushPromises()
    expect(wrapper.emitted('authenticated')).toBeUndefined()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
  } finally { wrapper.unmount() }
})

it('does not start a second request while submitting', async () => {
  vi.resetModules()
  const old = deferred()
  const fetcher = vi.fn(() => old.promise)
  vi.stubGlobal('fetch', fetcher)
  const { default: LoginScreen } = await import('./LoginScreen.vue')
  const wrapper = mount(LoginScreen)
  try {
    await wrapper.get('[name="username"]').setValue('actor')
    await wrapper.get('[name="password"]').setValue('test-password')
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    expect(fetcher).toHaveBeenCalledTimes(1)
  } finally { wrapper.unmount(); old.resolve(reply(user)); await flushPromises() }
})

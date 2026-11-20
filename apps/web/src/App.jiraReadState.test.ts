import { getAppState } from './ui/appTestAccess'
// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import App from './App.vue'

const mounted: Array<ReturnType<typeof mount>> = []
const response = (status = 200, username = 'fixture-reader') => new Response(JSON.stringify(status === 200
  ? { enabled: true, baseUrl: 'https://jira.fixture.example.test', username, credentialConfigured: false, projectJql: {} }
  : { message: 'fixture read failure' }), { status, headers: { 'Content-Type': 'application/json' } })

const mountJira = async (read: () => Promise<Response>) => {
  localStorage.clear()
  sessionStorage.clear()
  window.history.replaceState({}, '', '/jira-config')
  vi.stubGlobal('fetch', vi.fn(async (input: string) => {
    const path = String(input).split('?')[0]
    const json = (body: unknown) => new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } })
    if (path === '/api/auth/me') return json({ id: 9001, username: 'read.fixture', displayName: 'Fixture', role: 'ADMIN', mustChangePassword: false })
    if (path === '/api/projects') return json({ items: [{ id: 901, code: 'PROJECT', name: 'Fixture' }] })
    if (path === '/api/jira/config') return read()
    return json({})
  }))
  const wrapper = mount(App, { global: { plugins: [ElementPlus] }, attachTo: document.body })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}
const reload = async (wrapper: ReturnType<typeof mount>) => {
  await wrapper.findAll('button').find(button => button.text() === '重新加载')!.trigger('click')
  await flushPromises()
}

afterEach(() => {
  mounted.splice(0).forEach(view => view.unmount())
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})

it('Jira read failure is visible and explicit reload restores the verified source state', async () => {
  let status = 503
  let reads = 0
  const wrapper = await mountJira(async () => { reads += 1; return response(status) })
  const state = () => wrapper.get('.j-state-bar').text()
  await vi.waitFor(() => expect(state()).toContain('读取失败'))
  expect(state()).not.toContain('配置来自平台后端')
  expect(wrapper.get('[data-testid="jira-read-state"]').attributes('role')).toBe('status')
  expect(wrapper.get('[data-testid="jira-read-state"]').attributes('aria-live')).toBe('polite')
  const priorReads = reads
  status = 200
  await reload(wrapper)
  expect(reads).toBe(priorReads + 1)
  expect(state()).toContain('配置来自平台后端')
  expect(state()).not.toContain('读取失败')
  expect((wrapper.get('#jira-username').element as HTMLInputElement).value).toBe('fixture-reader')
})

it('failed reload preserves the last read form without claiming it is current backend data', async () => {
  let status = 200
  const wrapper = await mountJira(async () => response(status))
  expect(wrapper.get('[data-testid="jira-read-state"]').attributes('data-state')).toBe('ready')
  status = 503
  await reload(wrapper)
  expect(wrapper.get('[data-testid="jira-read-state"]').attributes('data-state')).toBe('error')
  expect(wrapper.get('.j-state-bar').text()).not.toContain('配置来自平台后端')
  expect((wrapper.get('#jira-username').element as HTMLInputElement).value).toBe('fixture-reader')
})

it('a pending initial read is loading rather than verified ready', async () => {
  let release!: (value: Response) => void
  const pending = new Promise<Response>(resolve => { release = resolve })
  const wrapper = await mountJira(() => pending)
  expect(wrapper.get('[data-testid="jira-read-state"]').attributes('data-state')).toBe('loading')
  expect(wrapper.get('.j-state-bar').text()).not.toContain('配置来自平台后端')
  release(response())
  await flushPromises()
  expect(wrapper.get('[data-testid="jira-read-state"]').attributes('data-state')).toBe('ready')
})

it.each([200, 503])('obsolete initial read HTTP %s cannot replace a newer ready read state', async status => {
  let release!: (value: Response) => void
  const pending = new Promise<Response>(resolve => { release = resolve })
  let reads = 0
  const wrapper = await mountJira(() => ++reads === 1 ? pending : Promise.resolve(response(200, 'newest-reader')))
  // Trigger the real handler while bootstrap is pending; its button is deliberately loading-disabled.
  const setup = getAppState(wrapper)
  await setup.loadJiraSettingsFromBackend()
  await flushPromises()
  release(response(status, 'obsolete-reader'))
  await flushPromises()
  expect(wrapper.get('[data-testid="jira-read-state"]').attributes('data-state')).toBe('ready')
  expect((wrapper.get('#jira-username').element as HTMLInputElement).value).toBe('newest-reader')
})

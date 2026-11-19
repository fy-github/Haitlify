// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'

const fixture = (id = 'A', module = 'M') => ({
  id, sourceCaseId: `source-${id}`, project: 'Fixture', module, name: `Case ${id}`,
  method: 'POST', path: `/fixture/${id}`, headers: {}, pathParams: {}, queryParams: {}, body: { id },
  expectedStatus: 200, assertions: ['body.ok = true'], runOrder: 1,
  exportTarget: 'apifox', automationLibraryId: `fixture-${id}`, savedAt: '2026-01-01T00:00:00Z'
})
const deferred = <T,>() => { let resolve!: (v: T) => void; const promise = new Promise<T>(r => { resolve = r }); return { promise, resolve } }
const reply = (body: unknown, status = 200) => ({ ok: status < 400, status, json: async () => body, headers: new Headers({ 'content-type': 'application/json' }) })
const wrappers: VueWrapper[] = []
const setup = async () => {
  vi.resetModules(); localStorage.clear(); sessionStorage.clear()
  window.history.replaceState({}, '', '/automation/api')
  const pending: ReturnType<typeof deferred<ReturnType<typeof reply>>>[] = []
  const requests: Array<{ url: string; body?: unknown }> = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    requests.push({ url: String(url), body: init?.body ? JSON.parse(String(init.body)) : undefined })
    if (String(url).includes('/api/auth/me')) return reply({ id: 901, username: 'fixture', role: 'ADMIN', mustChangePassword: false })
    if (String(url) === '/__api-test-platform/execute') {
      const p = deferred<ReturnType<typeof reply>>(); pending.push(p); return p.promise
    }
    return reply({})
  }))
  const { default: App } = await import('./App.vue')
  const wrapper = mount(App, { global: { plugins: [ElementPlus] } }); wrappers.push(wrapper)
  const s = (wrapper.vm as unknown as { $: { setupState: Record<string, any> } }).$.setupState
  await vi.waitFor(() => expect(s.authStatus).toBe('authenticated'))
  await flushPromises()
  s.automationCases = [fixture('A'), fixture('B')]
  s.executionEnvironmentLibrary = { activeEnvironmentId: 'env', environments: [{
    id: 'env', name: 'Fixture', baseUrl: 'https://fixture.invalid', auth: { type: 'none' }, entries: [],
    createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z'
  }] }
  await flushPromises()
  return { wrapper, s, pending, requests }
}

describe('App API monitor lifecycle', () => {
  afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.restoreAllMocks(); vi.unstubAllGlobals() })
  it('enters a separate monitor with all frozen loop identities and updates each real result before completion', async () => {
    const { wrapper, s, pending } = await setup()
    vi.spyOn(ElMessageBox, 'confirm').mockImplementation(() => Promise.resolve('confirm') as never)
    s.apiAutomationLoopCount = 2
    const finished = s.executeAllAutomationCases()
    await vi.waitFor(() => expect(pending).toHaveLength(1))
    expect(wrapper.find('[data-testid="api-run-monitor"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-testid="api-monitor-queue-row"]')).toHaveLength(4)
    expect(s.apiRunMonitor.rows.map((r: any) => [r.caseId, r.status])).toEqual([
      ['A#1', 'running'], ['B#1', 'pending'], ['A#2', 'pending'], ['B#2', 'pending']
    ])
    pending[0]!.resolve(reply({ status: 200, body: { ok: false } }))
    await vi.waitFor(() => expect(pending).toHaveLength(2))
    expect(s.apiRunMonitor.rows[0].result.actualStatus).toBe(200)
    expect(s.apiRunMonitor.rows[0].result.errorMessage).toContain('断言失败')
    expect(s.apiRunMonitor.rows[0].status).toBe('failed')
    expect(s.apiRunMonitor.rows[1].status).toBe('running')
    expect(s.apiAutomationRunning).toBe(true)
    for (let i = 1; i < 4; i++) {
      await vi.waitFor(() => expect(pending.length).toBeGreaterThan(i))
      pending[i]!.resolve(reply({ status: 200, body: { ok: true } }))
    }
    await finished; await flushPromises()
    expect(s.apiRunMonitor.status).toBe('completed')
    expect(s.apiRunMonitor.rows.every((r: any) => r.result)).toBe(true)
  })
  it('holds the launch lock while an already-dispatched sibling drains after request construction fails', async () => {
    const { s, pending } = await setup()
    vi.spyOn(ElMessageBox, 'confirm').mockImplementation(() => Promise.resolve('confirm') as never)
    s.automationRunMode = 'concurrent'
    s.automationCases[1].method = undefined
    const finished = s.executeAllAutomationCases()
    await vi.waitFor(() => expect(pending).toHaveLength(1))
    await flushPromises()
    expect(s.apiRunMonitor.status).toBe('error')
    expect(s.apiAutomationRunning).toBe(true)
    const old = s.apiRunMonitor
    await s.executeAllAutomationCases()
    expect(pending).toHaveLength(1)
    expect(s.apiRunMonitor).toBe(old)
    pending[0]!.resolve(reply({ status: 200, body: { ok: true } }))
    await finished
    expect(s.apiAutomationRunning).toBe(false)
    expect(old.rows[0].result).toBeUndefined()
    expect(old.rows[0].status).toBe('unreturned')
  })
  it('ignores a confirmed start after owner changes and does not dispatch requests', async () => {
    const { s, pending } = await setup()
    const confirmation = deferred<string>()
    vi.spyOn(ElMessageBox, 'confirm').mockImplementation(() => confirmation.promise as never)
    const finished = s.executeAllAutomationCases()
    await flushPromises()
    s.onLoginSuccess({ id: 902, username: 'next-fixture', role: 'ADMIN', mustChangePassword: false })
    await flushPromises()
    confirmation.resolve('confirm')
    await finished
    expect(pending).toHaveLength(0)
    expect(s.apiRunMonitor).toBeUndefined()
    expect(s.apiRunConfirming).toBe(false)
  })
  it.each([true, false])('ignores late API %s and never starts the next old-owner request', async (ok) => {
    const { s, pending, requests } = await setup()
    vi.spyOn(ElMessageBox, 'confirm').mockImplementation(() => Promise.resolve('confirm') as never)
    const finished = s.executeAllAutomationCases()
    await vi.waitFor(() => expect(pending).toHaveLength(1))
    const old = s.apiRunMonitor
    s.onLoginSuccess({ id: 902, username: 'next-fixture', role: 'ADMIN', mustChangePassword: false })
    await flushPromises()
    const storage = JSON.stringify({ ...localStorage })
    pending[0]!.resolve(ok ? reply({ status: 200, body: { ok: true } }) : reply({ error: 'fixture fail' }, 502))
    await finished; await flushPromises()
    expect(s.apiRunMonitor).toBeUndefined()
    expect(s.apiAutomationRunning).toBe(false)
    expect(old.rows[0].result).toBeUndefined()
    expect(requests.filter(r => r.url === '/__api-test-platform/execute')).toHaveLength(1)
    expect(JSON.stringify({ ...localStorage })).toBe(storage)
  })
  it('does not restore preparation scroll after the owner changes during nextTick', async () => {
    const { s, wrapper } = await setup()
    const scroll = wrapper.get('.automation-workspace .el-scrollbar__wrap').element as HTMLElement
    s.apiPreparationScroll = { top: 80, left: 120 }
    const finished = s.returnApiRunPreparation()
    s.onLoginSuccess({ id: 902, username: 'next-fixture', role: 'ADMIN', mustChangePassword: false })
    await finished
    expect(scroll.scrollTop).toBe(0)
    expect(scroll.scrollLeft).toBe(0)
  })
  it('does not fall back to a target URL after the original owner expires during the proxy await', async () => {
    const { s, pending, requests } = await setup()
    vi.spyOn(ElMessageBox, 'confirm').mockImplementation(() => Promise.resolve('confirm') as never)
    const finished = s.executeAllAutomationCases()
    await vi.waitFor(() => expect(pending).toHaveLength(1))
    s.onLoginSuccess({ id: 902, username: 'next-fixture', role: 'ADMIN', mustChangePassword: false })
    await flushPromises()
    pending[0]!.resolve(reply({}, 404))
    await finished
    expect(requests.filter(r => r.url.startsWith('https://fixture.invalid'))).toHaveLength(0)
  })

  it('closes an open environment drawer before the global API task enters its monitor', async () => {
    const { wrapper, s, pending } = await setup()
    vi.spyOn(ElMessageBox, 'confirm').mockImplementation(() => Promise.resolve('confirm') as never)
    const finished = s.executeAllAutomationCases()
    await vi.waitFor(() => expect(pending).toHaveLength(1))

    s.openEnvironmentEditor('env', 'api')
    await flushPromises()
    expect(s.environmentVisible).toBe(true)

    await wrapper.get('.studio-global-task-status').trigger('click')
    await flushPromises()

    expect(s.environmentVisible).toBe(false)
    expect(wrapper.find('[data-testid="api-run-monitor"]').exists()).toBe(true)
    pending[0]!.resolve(reply({ status: 200, body: { ok: true } }))
    await vi.waitFor(() => expect(pending).toHaveLength(2))
    pending[1]!.resolve(reply({ status: 200, body: { ok: true } }))
    await finished
  })

  it('refuses duplicate confirmation and duplicate launch while a request is in flight', async () => {
    const { s, pending } = await setup()
    const confirmation = deferred<string>()
    const dialog = vi.spyOn(ElMessageBox, 'confirm').mockImplementation(() => confirmation.promise as never)
    const finished = s.executeAllAutomationCases()
    await s.executeAllAutomationCases()
    expect(dialog).toHaveBeenCalledTimes(1)
    confirmation.resolve('confirm')
    await vi.waitFor(() => expect(pending).toHaveLength(1))
    await s.executeAllAutomationCases()
    expect(dialog).toHaveBeenCalledTimes(1)
    pending[0]!.resolve(reply({ status: 200, body: { ok: true } }))
    await vi.waitFor(() => expect(pending).toHaveLength(2))
    pending[1]!.resolve(reply({ status: 200, body: { ok: true } }))
    await finished
  })
})

// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'

const user = { id: 1, username: 'qa', displayName: 'QA', role: 'ADMIN', mustChangePassword: false }
const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
const boot = async (projectReply: () => Promise<Response> | Response) => {
  vi.resetModules()
  localStorage.clear()
  sessionStorage.clear()
  window.history.replaceState({}, '', '/overview')
  const fetcher = vi.fn(async (url: string) => {
    if (String(url).endsWith('/api/auth/me')) return response(user)
    if (String(url).endsWith('/api/projects')) return projectReply()
    return response({})
  })
  vi.stubGlobal('fetch', fetcher)
  const { default: App } = await import('./App.vue')
  return { wrapper: mount(App as never, { global: { plugins: [ElementPlus] } }), fetcher }
}

describe('Overview App read-only availability wiring', () => {
  afterEach(() => { vi.unstubAllGlobals(); window.history.replaceState({}, '', '/') })

  it('checks access but never promotes a project-list response to ready aggregate metrics', async () => {
    const { wrapper, fetcher } = await boot(() => response({ items: [{ id: 7, name: '默认项目', code: 'DEFAULT' }] }))
    try {
      await vi.waitFor(() => expect(wrapper.find('[data-testid="overview-availability-checked"]').exists()).toBe(true))
      expect(wrapper.get('[data-overview-state]').attributes('data-overview-state')).toBe('unavailable')
      expect(wrapper.find('[data-testid="overview-metric-value"]').exists()).toBe(false)
      const before = fetcher.mock.calls.filter(([url]) => String(url).endsWith('/api/projects')).length
      await wrapper.get('[data-testid="overview-retry"]').trigger('click')
      await vi.waitFor(() => expect(fetcher.mock.calls.filter(([url]) => String(url).endsWith('/api/projects')).length).toBeGreaterThan(before))
      await flushPromises()
      expect(wrapper.get('[data-testid="overview-availability-checked"]').text()).toContain('统计数据源仍未接入')
    } finally { wrapper.unmount() }
  })

  it('shows a transport failure then makes a new read-only request when the user retries', async () => {
    let failed = true
    const { wrapper, fetcher } = await boot(() => response({ items: [{ id: 7, name: '默认项目', code: 'DEFAULT' }] }, failed ? 503 : 200))
    try {
      await vi.waitFor(() => expect(wrapper.find('[data-testid="overview-error"]').exists()).toBe(true))
      expect(wrapper.get('[data-testid="overview-error"]').text()).toContain('项目服务暂时不可用')
      failed = false
      const before = fetcher.mock.calls.length
      await wrapper.get('[data-testid="overview-retry"]').trigger('click')
      await vi.waitFor(() => expect(wrapper.find('[data-testid="overview-availability-checked"]').exists()).toBe(true))
      expect(fetcher.mock.calls.slice(before).some(([url]) => String(url).endsWith('/api/projects'))).toBe(true)
      expect(wrapper.find('[data-testid="overview-error"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="overview-metric-value"]').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })

  it('renders permission failure without inventing project or team metrics', async () => {
    const { wrapper } = await boot(() => response({}, 403))
    try {
      await vi.waitFor(() => expect(wrapper.find('[data-testid="overview-forbidden"]').exists()).toBe(true))
      expect(wrapper.get('[data-testid="overview-forbidden"]').text()).toContain('项目访问被拒绝')
      expect(wrapper.get('[data-testid="overview-forbidden"]').text()).not.toContain('开通跨项目统计权限')
      expect(wrapper.find('[data-testid="overview-metric-value"]').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })

  it.each(['success', 'rejection'] as const)('ignores a late %s after leaving and returning to the overview', async outcome => {
    let mode: 'ready' | 'held' | 'forbidden' = 'ready'
    let settle!: (value: Response) => void
    let reject!: (error: Error) => void
    const held = new Promise<Response>((resolve, fail) => { settle = resolve; reject = fail })
    const { wrapper } = await boot(() => mode === 'held' ? held : response({ items: [] }, mode === 'forbidden' ? 403 : 200))
    try {
      await vi.waitFor(() => expect(wrapper.find('[data-testid="overview-availability-checked"]').exists()).toBe(true))
      mode = 'held'
      await wrapper.get('[data-testid="overview-retry"]').trigger('click')
      await vi.waitFor(() => expect(wrapper.find('[data-testid="overview-loading"]').exists()).toBe(true))
      window.history.pushState({}, '', '/')
      window.dispatchEvent(new PopStateEvent('popstate'))
      await flushPromises()
      mode = 'forbidden'
      window.history.pushState({}, '', '/overview')
      window.dispatchEvent(new PopStateEvent('popstate'))
      await vi.waitFor(() => expect(wrapper.find('[data-testid="overview-forbidden"]').exists()).toBe(true))
      if (outcome === 'success') settle(response({ items: [] }))
      else reject(new Error('late fixture network failure'))
      await flushPromises()
      expect(wrapper.get('[data-overview-state]').attributes('data-overview-state')).toBe('forbidden')
      expect(wrapper.find('[data-testid="overview-availability-checked"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="overview-error"]').exists()).toBe(false)
    } finally { settle(response({ items: [] })); wrapper.unmount() }
  })
})

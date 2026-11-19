// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import WebSessionPanel from './WebSessionPanel.vue'

describe('WebSessionPanel', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('does not claim zero active sessions before the first response', async () => {
    let resolve!: (response: Response) => void
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((done) => { resolve = done })))
    const wrapper = mount(WebSessionPanel, {
      props: { visible: true },
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()
    const count = wrapper.get('[data-testid="web-session-count"]')
    expect(wrapper.find('[data-testid="web-session-loading"]').exists()).toBe(true)
    expect(count.text()).not.toContain('0 个')
    expect(count.attributes('role')).toBe('status')
    resolve(new Response(JSON.stringify({ sessions: [] }), { status: 200 }))
    await flushPromises()
    expect(count.text()).toContain('活跃会话 0 个')
    wrapper.unmount()
  })

  it('reports an unknown count on load failure and recovers through refresh', async () => {
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ message: 'fixture unavailable' }), { status: 503 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ sessions: [] }), { status: 200 }))
    vi.stubGlobal('fetch', fetchImpl)
    const wrapper = mount(WebSessionPanel, {
      props: { visible: true },
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()
    expect(wrapper.find('[data-testid="web-session-error"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="web-session-count"]').text()).not.toContain('0 个')
    expect(wrapper.find('[data-testid="web-session-empty"]').exists()).toBe(false)
    await wrapper.get('[data-testid="web-session-refresh"]').trigger('click')
    await flushPromises()
    expect(fetchImpl).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-testid="web-session-count"]').text()).toContain('活跃会话 0 个')
    expect(wrapper.find('[data-testid="web-session-error"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('renders active sessions with lease and expiry columns', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      sessions: [
        {
          sessionId: 'session-abcdefghijklmnopqrstuvwxyz', projectId: 3, environmentId: 'env-1', browser: 'chromium',
          state: 'READY_FOR_COMPILE', leased: false, pageKey: 'https://example.com/x', createdAt: 1,
          expiresAt: Date.now() + 300_000, lastUsedAt: 2
        },
        {
          sessionId: 'session-2', projectId: 4, environmentId: 'env-2', browser: 'firefox',
          state: 'IN_USE', leased: true, createdAt: 1, expiresAt: Date.now() + 60_000, lastUsedAt: 2
        }
      ]
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })))

    const wrapper = mount(WebSessionPanel, {
      props: { visible: true },
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    expect(wrapper.get('[data-testid="web-session-count"]').text()).toContain('活跃会话 2 个')
    const rows = wrapper.findAll('[data-testid^="web-session-row-"]')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('空闲')
    expect(rows[0].text()).toContain('chromium')
    expect(rows[1].text()).toContain('占用中')
    expect(rows[1].text()).toContain('firefox')
    wrapper.unmount()
  })

  it('refreshes on demand and shows the empty state when no sessions are active', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({ sessions: [] }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    }))
    vi.stubGlobal('fetch', fetchImpl)

    const wrapper = mount(WebSessionPanel, {
      props: { visible: true },
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="web-session-empty"]').exists()).toBe(true)
    await wrapper.get('[data-testid="web-session-refresh"]').trigger('click')
    await flushPromises()
    expect(fetchImpl).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
})

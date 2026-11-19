// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'

describe('App local help entry', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    document.body.innerHTML = ''
    window.history.replaceState({}, '', '/')
  })

  it('opens and closes local guidance without navigation, requests or changing the current workspace', async () => {
    vi.resetModules()
    localStorage.clear()
    sessionStorage.clear()
    window.history.replaceState({}, '', '/overview')
    const fetcher = vi.fn(async (url: string) => new Response(JSON.stringify(
      String(url).endsWith('/api/auth/me')
        ? { id: 1, username: 'qa', displayName: 'QA', role: 'ADMIN', mustChangePassword: false }
        : String(url).endsWith('/api/projects') ? { items: [{ id: 7, code: 'DEFAULT', name: '默认项目' }] } : {}
    ), { status: 200 }))
    vi.stubGlobal('fetch', fetcher)
    const { default: App } = await import('./App.vue')
    const wrapper = mount(App as never, { attachTo: document.body, global: { plugins: [ElementPlus] } })
    try {
      await vi.waitFor(() => expect(wrapper.find('[data-testid="overview-availability-checked"]').exists()).toBe(true))
      await flushPromises()
      const before = fetcher.mock.calls.length
      const path = window.location.pathname
      const state = wrapper.get('[data-overview-state]').attributes('data-overview-state')
      await wrapper.get('.studio-sidebar__tools button[aria-label="帮助文档"]').trigger('click')
      await vi.waitFor(() => expect(document.querySelector('[data-testid="local-help-content"]')).not.toBeNull())
      const content = document.querySelector('[data-testid="local-help-content"]')!
      expect(content.textContent).toContain('本地使用说明')
      expect(content.textContent).toContain('不代表真实后端、认证或 Runner 已通过验收')
      expect(content.querySelectorAll('a')).toHaveLength(0)
      expect(fetcher.mock.calls.length).toBe(before)
      expect(window.location.pathname).toBe(path)
      expect(wrapper.get('[data-overview-state]').attributes('data-overview-state')).toBe(state)
      const close = document.querySelector('button[aria-label="关闭帮助文档"]') as HTMLButtonElement
      close.click()
      await flushPromises()
      expect(wrapper.findComponent({ name: 'HelpDrawer' }).props('modelValue')).toBe(false)
    } finally { wrapper.unmount() }
  })
})

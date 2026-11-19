// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'

let wrapper: ReturnType<typeof mount> | undefined
afterEach(() => { wrapper?.unmount(); vi.unstubAllGlobals(); localStorage.clear(); sessionStorage.clear() })

const backendResponse = (url: string) => ({
  ok: true, status: 200,
  json: async () => String(url).includes('/auth/me') ? { id: 1, username: 'admin', role: 'ADMIN', mustChangePassword: false } : {},
  text: async () => '{}'
})

it('renders the standalone environment page on /environments with the third execution nav item selected', async () => {
  vi.resetModules(); localStorage.clear(); sessionStorage.clear()
  window.history.replaceState({}, '', '/environments')
  vi.stubGlobal('fetch', vi.fn(async (url: string) => backendResponse(url)))
  const { default: App } = await import('./App.vue')
  wrapper = mount(App as never, { global: { plugins: [ElementPlus] }, attachTo: document.body })
  await vi.waitFor(() => expect(wrapper!.find('[data-testid="environment-management-page"]').exists()).toBe(true))
  await flushPromises()
  const state = (wrapper.vm.$ as any).setupState
  expect(state.activeNav).toBe('environments')
  expect(state.activeSidebarItemId).toBe('environments')
  expect(wrapper.find('[data-testid="environment-management-page"] h1').text()).toBe('环境配置')
  expect(window.location.pathname).toBe('/environments')
  expect(wrapper.find('[data-testid="environment-inline-editor"]').exists()).toBe(true)
  expect(wrapper.find('[data-testid="environment-inline-editor"] .environment-editor').exists()).toBe(true)
  expect(document.querySelector('.el-overlay .web-automation-drawer[aria-label="API 运行环境配置"]')).toBeNull()
  expect(document.querySelector('.el-overlay .web-automation-drawer[aria-label="Web 运行环境配置"]')).toBeNull()
  wrapper.unmount(); wrapper = undefined
  window.history.replaceState({}, '', '/')
})

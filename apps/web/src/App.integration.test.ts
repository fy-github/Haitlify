// @vitest-environment jsdom
// App-level smoke test: mounts the real App.vue (the single wired creation point for every
// ui/ singleton) with an expired session and verifies it falls back to the login screen.
// fetch is stubbed so bootstrapAuth -> fetchCurrentUser -> authedFetch sees a 401.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'

const expiredSessionResponse = { ok: false, status: 401, json: async () => ({ message: '未登录' }) }

const adminUser = { username: 'admin', displayName: '管理员', role: 'ADMIN', mustChangePassword: false }

const backendResponse = (url: string) => {
  if (String(url).includes('/api/auth/me')) {
    return { ok: true, status: 200, json: async () => adminUser }
  }
  if (String(url).includes('/api/ai/run-policies')) {
    return { ok: true, status: 200, json: async () => ({ items: [] }) }
  }
  // All other workspace/backend calls return empty data; repositories fall back to local state.
  return { ok: true, status: 200, json: async () => ({}) }
}

describe('App.vue integration', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('mounts and shows the login screen when the session is expired', async () => {
    vi.resetModules()
    localStorage.clear()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(expiredSessionResponse))
    const { default: App } = await import('./App.vue')
    const wrapper = mount(App as never, { global: { plugins: [ElementPlus] } })
    await vi.waitFor(() => {
      expect(wrapper.find('h1').text()).toContain('Haitlify AI 测试平台')
    })
  })

  it('mounts the authenticated app shell and renders the home AI assistant by default', async () => {
    vi.resetModules()
    localStorage.clear()
    vi.stubGlobal('fetch', vi.fn(async (url: string) => backendResponse(url)))
    const { default: App } = await import('./App.vue')
    const wrapper = mount(App as never, { global: { plugins: [ElementPlus] } })
    // Default nav = 首页 AI 助手。根路径 '/' 经 navKeyFromPath 解析为 'home'。
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('AI 测试助手')
    })
    // 首页流程导览含阶段模块跳转按钮(非 admin 过滤后仍可见)。
    expect(wrapper.text()).toContain('自然语言驱动测试构思、生成、执行与复盘')
    expect(wrapper.text()).toContain('执行日志')
  })
})

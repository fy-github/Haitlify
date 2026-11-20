import { getAppState } from './ui/appTestAccess'
// @vitest-environment jsdom
// 模板配置保存:后端不可用(本地兜底)时不得对外声称"已保存"。
// 与设置代理的契约:App 的 persistTemplateConfig 在 source==='local' 时抛出明确错误,
// 让 useTemplateConfig 的 saveState 落到 'failed',而不是 'saved'。
import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'

const user = { id: 1, username: 'admin', displayName: '管理员', role: 'ADMIN', mustChangePassword: false }

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

it('模板配置仅写入本地时保存状态为 failed 且给出明确原因', async () => {
  vi.resetModules()
  localStorage.clear()
  sessionStorage.clear()
  window.history.replaceState({}, '', '/')
  const { defaultTemplateConfig } = await import('./domain/templateConfigRepository')
  const fetchImpl = vi.fn(async (url: string, init?: RequestInit) => {
    const method = (init?.method || 'GET').toUpperCase()
    if (String(url).includes('/auth/me')) return { ok: true, status: 200, json: async () => user } as unknown as Response
    if (String(url).endsWith('/case-template/interface') && method === 'PUT') {
      return { ok: false, status: 503, json: async () => ({ message: 'backend down' }) } as unknown as Response
    }
    if (String(url) === '/api/projects') {
      return { ok: true, status: 200, json: async () => ({ items: [{ id: 1, name: '默认项目', code: 'PROJECT' }] }) } as unknown as Response
    }
    if (String(url).includes('/case-template/')) {
      return { ok: true, status: 200, json: async () => defaultTemplateConfig() } as unknown as Response
    }
    return { ok: true, status: 200, json: async () => ({}) } as unknown as Response
  })
  vi.stubGlobal('fetch', fetchImpl)

  const { default: App } = await import('./App.vue')
  const wrapper = mount(App as never, { global: { plugins: [ElementPlus] }, attachTo: document.body })
  await flushPromises()
  const s = getAppState(wrapper)

  s.templateConfigModule.patchField('caseId', { label: '本地草稿' })
  await flushPromises()
  await flushPromises()

  expect(s.templateConfigModule.saveState.value).toBe('failed')
  expect(String(s.templateConfigModule.saveError.value)).toContain('本地')
  wrapper.unmount()
})

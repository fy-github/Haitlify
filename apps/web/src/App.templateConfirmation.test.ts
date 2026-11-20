import { getAppState } from './ui/appTestAccess'
// @vitest-environment jsdom
// Real App/bootstrap/storage wiring; adapted from the independent cross-path oracle.
import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
const A = { id: 1, username: 'same-name', displayName: 'A', role: 'EDITOR', mustChangePassword: false }
const B = { ...A, id: 2, displayName: 'B' }
const reply = (body: unknown) => new Response(JSON.stringify(body))
let wrapper: ReturnType<typeof mount> | undefined
afterEach(() => { wrapper?.unmount(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

it.each(['resetConfig', 'removeField', 'removeCaseTemplate', 'removeCaseType'])('cross-path: A %s confirmation cannot touch B bootstrap state/cache/network/toast', async action => {
  vi.resetModules(); localStorage.clear(); sessionStorage.clear(); window.history.replaceState({}, '', '/')
  const { defaultTemplateConfig, saveTemplateConfig } = await import('./domain/templateConfigRepository')
  const config = (owner: string) => {
    const value = defaultTemplateConfig()
    value.interfaceFields[0].label = `${owner}-owned-custom`
    value.caseTemplates.push({ id: 'custom-template', name: owner, kind: 'interface', builtIn: false, enabled: true, fieldKeys: [] })
    value.caseTypes.push({ id: 'custom-type', name: owner, appliesTo: 'interface', enabled: true })
    return value
  }
  const seed=(id:number,label:string)=>saveTemplateConfig({setItem:(k:string,v:string)=>localStorage.setItem(`ai-test-platform.workspace.user%3A${id}.`+k,v)} as Storage,config(label),{updatedAt:'2026-01-01T00:00:00Z'})
  seed(1,'A');seed(2,'B')
  let owner = 'A'
  const fetch = vi.fn((url: string) => Promise.resolve(reply(
    url.includes('/auth/me') ? A : url.includes('/jira/config') ? {} :
    url === '/api/projects' ? { items: [{ id: 1, name: '默认项目', code: 'PROJECT' }] } :
    url.endsWith('/interface-cases') ? { items: [] } : url.includes('/case-template/') ? {...config(owner),updatedAt:'2026-01-01T00:00:00Z'} :
    url.includes('/capabilities') ? { status: 'UP', browsers: [] } : {})))
  vi.stubGlobal('fetch', fetch)
  const { default: App } = await import('./App.vue')
  wrapper = mount(App as never, { global: { plugins: [ElementPlus] } })
  const s = getAppState(wrapper)
  await flushPromises()
  let resolve!: (value: any) => void
  const confirmation = new Promise<any>(done => { resolve = done })
  vi.spyOn(ElMessageBox, 'confirm').mockImplementation(() => confirmation)
  const success = vi.spyOn(ElMessage, 'success')
  const m = s.templateConfigModule
  const pending = action === 'removeField' ? m.removeField(s.templateConfig.interfaceFields[0].key) :
    action === 'removeCaseTemplate' ? m.removeCaseTemplate(s.templateConfig.caseTemplates.find((t: any) => t.id === 'custom-template')!) :
    action === 'removeCaseType' ? m.removeCaseType('custom-type') : m.resetConfig()
  owner = 'B'; s.handleSessionExpired(); s.onLoginSuccess(B); await flushPromises()
  expect(s.templateConfig.interfaceFields[0].label).toBe('B-owned-custom')
  const key = 'ai-test-platform.workspace.user%3A2.ai-test-platform.'
  expect(localStorage.getItem(key + 'template-config.v1')).toContain('B-owned-custom')
  expect(localStorage.getItem(key + 'template-config-meta.v1')).not.toBeNull()
  const fingerprint = () => JSON.stringify({ config: s.templateConfig, selected: m.selectedTemplateId.value,
    generation: m.generationTemplateId.value, selectedTypes: s.generation.selectedCaseTypeIds,
    local: { ...localStorage }, session: { ...sessionStorage }, toasts: success.mock.calls, requests: fetch.mock.calls })
  const before = fingerprint()
  resolve({ value: '', action: 'confirm' }); await pending; await flushPromises()
  expect(fingerprint()).toBe(before)
})

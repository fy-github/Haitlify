// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'

let wrapper: ReturnType<typeof mount> | undefined
const testCase = { id: 'api-one', sourceCaseId: 'one', project: '项目A', module: '模块A', name: '读取列表', method: 'GET', path: '/items', headers: {}, pathParams: {}, queryParams: {}, expectedStatus: 200, assertions: [], runOrder: 1, exportTarget: 'apifox', automationLibraryId: 'lib', savedAt: '' }

afterEach(() => { wrapper?.unmount(); vi.restoreAllMocks(); vi.unstubAllGlobals(); localStorage.clear(); sessionStorage.clear() })

async function boot() {
  vi.resetModules(); localStorage.clear(); sessionStorage.clear()
  window.history.replaceState({}, '', '/automation/api')
  // 真实执行路径：本地代理 POST /__api-test-platform/execute（不是浏览器直连目标接口）
  const executed: string[] = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: { body?: string }) => {
    const target = String(url)
    if (target.startsWith('/__api-test-platform/execute')) {
      executed.push(JSON.parse(String(init?.body)).url)
      return { ok: true, status: 200, json: async () => ({ status: 200, body: {} }), text: async (): Promise<string> => '' }
    }
    return {
      ok: true, status: 200,
      json: async () => target.includes('/auth/me') ? { id: 1, username: 'admin', role: 'ADMIN', mustChangePassword: false } : {},
      text: async (): Promise<string> => '{}'
    }
  }))
  const { default: App } = await import('../../App.vue')
  wrapper = mount(App as never, { global: { plugins: [ElementPlus] } })
  await flushPromises()
  const state = (wrapper.vm.$ as any).setupState
  state.automationCases = [structuredClone(testCase)]
  state.executionEnvironmentLibrary = { activeEnvironmentId: 'env-one', environments: [{ id: 'env-one', name: '测试环境', baseUrl: 'https://fixture.test', auth: { type: 'none' }, entries: [], updatedAt: '' }] }
  return { state, executed }
}

it('confirms a locked API scope before executing and keeps the frozen snapshot after preparation edits', async () => {
  const { state, executed } = await boot()
  let confirm!: (value: string) => void
  const dialog = vi.spyOn(ElMessageBox, 'confirm').mockImplementation(() => new Promise(resolve => { confirm = resolve }) as never)

  const running = state.executeAllAutomationCases()
  await flushPromises()
  expect(dialog).toHaveBeenCalled()
  expect(executed).toEqual([])
  expect(String(dialog.mock.calls[0][0])).toContain('执行筛选 1 条')
  expect(String(dialog.mock.calls[0][0])).toContain('https://fixture.test')

  state.automationCases[0].path = '/changed'
  state.executionEnvironmentLibrary.environments[0].baseUrl = 'https://changed.test'
  state.apiAutomationLoopCount = 2
  confirm('confirm')
  await running

  expect(executed).toEqual(['https://fixture.test/items'])
  expect(state.automationRunReport.summary.total).toBe(1)
  expect(state.apiRunSnapshot.options.loopCount).toBe(1)
})

it('does not execute when the confirmation is cancelled', async () => {
  const { state, executed } = await boot()
  vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
  await state.executeAllAutomationCases()
  expect(executed).toEqual([])
  expect(state.automationRunReport).toBeUndefined()
})

it('rejects a non-positive-integer loop count before confirmation', async () => {
  const { state, executed } = await boot()
  const dialog = vi.spyOn(ElMessageBox, 'confirm')
  state.apiAutomationLoopCount = 0
  await state.executeAllAutomationCases()
  expect(dialog).not.toHaveBeenCalled()
  expect(executed).toEqual([])
})

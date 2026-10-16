// @vitest-environment jsdom
// View-level integration test: mounts the real CasesView with ElementPlus and verifies
// it renders seeded library data without a backend.
// NOTE: useCases is a module-level singleton shared with App.vue, so each scenario re-imports
// the view through vi.resetModules() to get a fresh singleton + fresh localStorage.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { CASE_LIBRARY_STORAGE_KEY, type SavedInterfaceCase } from '../../domain/caseRepository'

const mountView = (Component: { new (): unknown }) =>
  mount(Component as never, { global: { plugins: [ElementPlus] } })

const makeSavedCase = (caseId: string, title: string): SavedInterfaceCase => ({
  libraryId: `Haitlify.openapi.json:createProjectMember:/api/projects/{projectId}/members:正向:${caseId}`,
  savedAt: '2026-08-01T08:00:00.000Z',
  id: `case-${caseId}`,
  row: {
    caseId,
    project: 'Haitlify.openapi.json',
    module: '项目成员',
    interfaceName: '新增项目成员',
    method: 'POST',
    path: '/api/projects/{projectId}/members',
    title,
    caseType: '正向',
    precondition: '',
    headers: '',
    pathParams: '',
    queryParams: '',
    body: '{}',
    steps: '',
    expectedResult: '',
    assertions: '',
    priority: 'P1',
    executionResult: '',
    executor: '',
    executedAt: '',
    defectId: '',
    tags: '',
    sourceFile: 'Haitlify.openapi.json',
    remark: ''
  },
  metadata: {
    sourceSpecId: 'spec-1',
    sourceFile: 'Haitlify.openapi.json',
    sourceOperationId: 'createProjectMember',
    tag: '项目成员',
    method: 'POST',
    path: '/api/projects/{projectId}/members',
    baseUrlRef: 'default',
    environmentId: 'env-1',
    authProfileId: 'auth-1',
    contentType: 'application/json',
    headers: {},
    pathParams: {},
    queryParams: {},
    body: {},
    expectedStatus: 200,
    assertions: [],
    expectedBodyRules: [],
    variableExtractions: [],
    dependsOnCaseIds: [],
    runOrder: 1,
    generationMeta: { granularity: 'basic', strategy: 'default' },
    reviewMeta: { status: '待复核' },
    exportMeta: { apifoxReady: false }
  }
})

const loadView = async () => {
  vi.resetModules()
  const { default: CasesView } = await import('./CasesView.vue')
  return CasesView as unknown as { new (): unknown }
}

describe('CasesView integration', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('mounts with an empty library and shows the case library header', async () => {
    const wrapper = mountView(await loadView())
    expect(wrapper.exists()).toBe(true)
    expect(wrapper.find('h1').text()).toContain('用例库')
  })

  it('renders seeded saved cases in the library table', async () => {
    localStorage.setItem(CASE_LIBRARY_STORAGE_KEY, JSON.stringify([
      makeSavedCase('API-0001', '新增项目成员-正常请求成功'),
      makeSavedCase('API-0002', '查询项目列表-正常请求成功')
    ]))
    const wrapper = mountView(await loadView())
    const text = wrapper.text()
    // el-table 按容器高度渲染行(jsdom 无布局高度,行不进 DOM),这里断言数据派生文本:
    // localStorage 播种 → useCases 单例 → 视图绑定链路是否打通。
    expect(text).toContain('总用例2项')
    expect(text).toContain('共 2 条')
  })
})

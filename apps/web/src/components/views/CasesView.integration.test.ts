// @vitest-environment jsdom
// View-level integration test: mounts the real CasesView with ElementPlus and verifies
// it renders seeded library data without a backend.
// Each scenario creates a controller and provides it to the real view.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { CASE_LIBRARY_STORAGE_KEY, type SavedInterfaceCase } from '../../domain/caseRepository'

const mountView = (Component: { new (): unknown }) =>
  mount(Component as never, { global: { plugins: [ElementPlus] }, attachTo: document.body })

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

type ViewDeps = {
  openEdit?: (testCase: unknown) => void
  openWebEdit?: (testCase: unknown) => void
}

const loadView = async (deps: ViewDeps = {}) => {
  vi.resetModules()
  const { provideCases, useCases } = await import('../../ui/useCases')
  const controller = useCases({ storage: window.localStorage, ...deps } as never)
  const { default: CasesView } = await import('./CasesView.vue')
  return defineComponent({
    setup() {
      provideCases(controller)
      return () => h(CasesView)
    }
  }) as unknown as { new (): unknown }
}

const nextFrame = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('CasesView integration', () => {
  beforeEach(() => {
    localStorage.clear()
    document.body.innerHTML = ''
  })

  it('mounts with an empty library and names the active case category', async () => {
    const wrapper = mountView(await loadView())
    expect(wrapper.exists()).toBe(true)
    expect(wrapper.find('.settings-page-kicker').text()).toContain('用例库')
    // 页面标题是用例类别,表格区标题才是目录范围
    expect(wrapper.find('h1').text()).toBe('接口用例')
    expect(wrapper.find('.cv-list-heading h2').text()).toBe('全部用例')
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
    // 统计改到左目录与卡头:全部用例计数 + 列表条数
    expect(text).toContain('全部用例')
    expect(text).toContain('接口用例')
    expect(text).toContain('共 2 条')
  })

  it('starts from readable default columns without losing the other visible fields', async () => {
    localStorage.setItem(CASE_LIBRARY_STORAGE_KEY, JSON.stringify([
      makeSavedCase('API-0003', '字段完整性检查')
    ]))
    const wrapper = mountView(await loadView())

    const headers = wrapper.findAll('th').map((cell) => cell.text())
    expect(headers).toContain('用例编号')
    expect(headers).toEqual(expect.arrayContaining(['用例标题', '项目', '模块', '用例类型', '优先级', '执行结果']))

    // 非默认可见字段不是被白名单丢掉:显示列里仍提供,并可加回表格
    await wrapper.find('.cv-list-tools button.cv-columns-btn').trigger('click')
    await nextTick()
    const dialog = wrapper.find('.cv-columns-dialog')
    expect(dialog.findAll('input[data-column]').length).toBeGreaterThanOrEqual(20)
    await dialog.find('input[data-column="method"]').setValue(true)
    await wrapper.find('button[data-action="apply"]').trigger('click')
    await nextTick()
    expect(wrapper.findAll('th').map((cell) => cell.text())).toContain('请求方法')
  })

  it('keeps selected rows across pages and marks the current page header indeterminate', async () => {
    localStorage.setItem(CASE_LIBRARY_STORAGE_KEY, JSON.stringify(
      Array.from({ length: 51 }, (_, index) => makeSavedCase(`API-${1000 + index}`, `跨页用例 ${index + 1}`))
    ))
    const wrapper = mountView(await loadView())
    await nextFrame()

    const pagerButton = (label: string) =>
      wrapper.findAll('button.dp-btn').find((button) => button.text() === label)

    const firstPageRow = wrapper.findAll('tbody tr')[0]
    await firstPageRow.find('input[type="checkbox"]').setValue(true)
    expect(wrapper.find('.cv-list-tools .cv-selected-count').text()).toContain('已选 1 条')

    await pagerButton('下一页')!.trigger('click')
    await nextTick()
    const secondPageRow = wrapper.findAll('tbody tr')[0]
    expect(secondPageRow.find('.cv-id-col').text()).toBe('API-1050')
    await secondPageRow.find('input[type="checkbox"]').setValue(true)
    expect(wrapper.find('.cv-list-tools .cv-selected-count').text()).toContain('已选 2 条')

    await pagerButton('上一页')!.trigger('click')
    await nextTick()
    // 表头 checked 只按当前页求交集:第一页 1/50 命中 → 半选。
    const header = wrapper.find('thead input[type="checkbox"]').element as HTMLInputElement
    expect(header.checked).toBe(false)
    expect(header.indeterminate).toBe(true)
    expect(wrapper.findAll('tbody tr')[0].find('input[type="checkbox"]').element).toHaveProperty('checked', true)
  })

  it('routes a title click through the existing edit callback', async () => {
    localStorage.setItem(CASE_LIBRARY_STORAGE_KEY, JSON.stringify([makeSavedCase('API-0004', '打开编辑')]))
    const openEdit = vi.fn()
    const wrapper = mountView(await loadView({ openEdit }))

    const title = wrapper.find('button.cv-title')
    expect(title.exists()).toBe(true)
    await title.trigger('click')

    // 标题是编辑入口:点击必须进入既有编辑流程,不能只改 selectedCase。
    expect(openEdit).toHaveBeenCalledTimes(1)
    expect((openEdit.mock.calls[0][0] as { row: { caseId: string } }).row.caseId).toBe('API-0004')
  })

  it('shows batch actions only with a selection and keeps them left of the column settings', async () => {
    localStorage.setItem(CASE_LIBRARY_STORAGE_KEY, JSON.stringify([
      makeSavedCase('API-0005', '工具栏顺序')
    ]))
    const wrapper = mountView(await loadView())
    const toolbar = wrapper.find('.cv-list-tools')
    expect(toolbar.exists()).toBe(true)
    expect(toolbar.text()).not.toContain('导出选中')
    expect(wrapper.find('.cv-selection-strip').exists()).toBe(false)

    await wrapper.find('tbody input[type="checkbox"]').setValue(true)
    await nextTick()

    const labels = wrapper.findAll('.cv-list-tools button').map((button) => button.text().trim())
    const columnsIndex = labels.findIndex((label) => label.startsWith('显示列'))
    expect(labels).toContain('导出选中')
    expect(columnsIndex).toBeGreaterThan(-1)
    expect(labels.lastIndexOf('删除勾选')).toBeLessThan(columnsIndex)
    expect(labels.indexOf('批量修改字段')).toBeLessThan(columnsIndex)
    // 批量操作只在工具条里,不再另起通栏
    expect(wrapper.find('.cv-selection-strip').exists()).toBe(false)
  })

  it('lets a person stage column visibility per category and apply it', async () => {
    localStorage.setItem(CASE_LIBRARY_STORAGE_KEY, JSON.stringify([
      makeSavedCase('API-0006', '显示列')
    ]))
    const wrapper = mountView(await loadView())

    await wrapper.find('.cv-list-tools button.cv-columns-btn').trigger('click')
    await nextTick()
    const dialog = wrapper.find('.cv-columns-dialog')
    expect(dialog.exists()).toBe(true)
    // ID/标题是必需列,不可取消
    expect(dialog.find('input[data-column="title"]').attributes('disabled')).toBeDefined()

    await dialog.find('input[data-column="remark"]').setValue(false)
    await wrapper.find('button[data-action="apply"]').trigger('click')
    await nextTick()
    expect(wrapper.findAll('th').map((cell) => cell.text())).not.toContain('备注')
  })
})

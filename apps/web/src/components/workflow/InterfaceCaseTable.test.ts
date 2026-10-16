// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { defineComponent, h, nextTick } from 'vue'
import { describe, expect, it } from 'vitest'
import InterfaceCaseTable from './InterfaceCaseTable.vue'
import type { GeneratedInterfaceCase } from '../../domain/caseGenerator'
import { interfaceCaseFields } from '../../domain/caseTemplates'

const makeCase = (caseId: string, patch: Partial<GeneratedInterfaceCase['row']> = {}): GeneratedInterfaceCase => ({
  id: caseId,
  row: {
    caseId,
    project: 'Haitlify',
    module: '用户管理',
    interfaceName: '创建用户',
    method: 'POST',
    path: '/api/users',
    title: '创建用户成功',
    caseType: '正向',
    precondition: '',
    headers: '{}',
    pathParams: '{}',
    queryParams: '{}',
    body: '{}',
    steps: '发送创建用户请求',
    expectedResult: '返回 200',
    expectedResponseBody: '{}',
    actualResponseBody: '',
    assertions: 'status == 200',
    priority: 'P1',
    executionResult: '未执行',
    executor: '',
    executedAt: '',
    defectId: '',
    tags: '',
    sourceFile: 'openapi.yaml',
    remark: '',
    ...patch
  },
  metadata: {
    sourceSpecId: 'spec-1',
    sourceFile: 'openapi.yaml',
    sourceOperationId: `${caseId}-operation`,
    tag: patch.module || '用户管理',
    method: patch.method || 'POST',
    path: patch.path || '/api/users',
    baseUrlRef: '',
    environmentId: '',
    authProfileId: '',
    contentType: 'application/json',
    headers: {},
    pathParams: {},
    queryParams: {},
    body: {},
    expectedStatus: 200,
    assertions: ['status == 200'],
    expectedBodyRules: [],
    expectedResponseBody: {},
    variableExtractions: [],
    dependsOnCaseIds: [],
    runOrder: 1,
    generationMeta: { granularity: 'risk', strategy: 'success' },
    reviewMeta: { status: '待复核' },
    exportMeta: { apifoxReady: true }
  }
})

const cases = [
  makeCase('API-001'),
  makeCase('API-002', {
    module: '项目管理',
    interfaceName: '查询项目',
    method: 'GET',
    path: '/api/projects',
    title: '查询项目列表',
    caseType: '边界',
    priority: 'P2'
  })
]

const visibleFields = interfaceCaseFields.filter((field) =>
  ['caseId', 'module', 'method', 'path', 'title', 'caseType', 'priority'].includes(field.key)
)

type InterfaceCaseTableProps = Partial<InstanceType<typeof InterfaceCaseTable>['$props']> & {
  moduleOptions?: string[]
  caseTypeOptions?: string[]
  priorityOptions?: string[]
  reviewStatusOptions?: string[]
}

const mountTable = (props: InterfaceCaseTableProps = {}) =>
  mount(InterfaceCaseTable, {
    attachTo: document.body,
    props: {
      cases,
      visibleFields,
      selectedIds: [],
      keyword: '',
      module: '',
      caseType: '',
      priority: '',
      reviewStatus: '',
      moduleOptions: [],
      caseTypeOptions: [],
      priorityOptions: [],
      reviewStatusOptions: [],
      ...props
    },
    global: {
      plugins: [ElementPlus]
    }
  })

const flushTable = async (wrapper: ReturnType<typeof mountTable>) => {
  await wrapper.vm.$nextTick()
  await new Promise((resolve) => setTimeout(resolve, 0))
  await wrapper.vm.$nextTick()
}

const selectionSyncTableStub = defineComponent({
  name: 'ElTable',
  props: {
    data: { type: Array, default: () => [] }
  },
  emits: ['selection-change', 'row-click'],
  setup(_props, { emit, expose, slots }) {
    expose({
      clearSelection: () => {
        void nextTick(() => emit('selection-change', []))
      },
      toggleRowSelection: () => undefined
    })

    return () => h('table', slots.default?.())
  }
})

const tableColumnStub = defineComponent({
  name: 'ElTableColumn',
  setup(_props, { slots }) {
    return () => h('tbody', slots.default?.({ row: cases[0] }))
  }
})

describe('InterfaceCaseTable', () => {
  it('renders generated interface cases with filters, selection state, and case actions', async () => {
    const wrapper = mountTable()
    await flushTable(wrapper)

    expect(wrapper.find('[data-testid="interface-case-toolbar"]').exists()).toBe(true)
    expect(wrapper.find('input[aria-label="搜索标题或接口路径"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('已选择 0 条')
    expect(wrapper.text()).toContain('操作')
    expect(wrapper.get('button[aria-label="更多批量操作（待接入）"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('button[aria-label="更多批量操作（待接入）"]').attributes('title')).toBe('更多批量操作待接入')
    for (const label of ['上移 API-001', '下移 API-001', '编辑 API-001', '删除 API-001']) {
      expect(wrapper.find(`button[aria-label="${label}"]`).exists()).toBe(true)
    }

    await wrapper.get('input[aria-label="搜索标题或接口路径"]').setValue('项目')
    await wrapper.get('[data-testid="interface-case-module-filter"]').setValue('项目管理')
    await wrapper.get('[data-testid="interface-case-type-filter"]').setValue('边界')
    await wrapper.get('[data-testid="interface-case-priority-filter"]').setValue('P2')
    await wrapper.get('[data-testid="interface-case-review-status-filter"]').setValue('待复核')
    await wrapper.findComponent({ name: 'ElTable' }).vm.$emit('selection-change', [cases[0]])
    await wrapper.get('tbody tr').trigger('click')
    await wrapper.get('button[aria-label="上移 API-001"]').trigger('click')
    await wrapper.get('button[aria-label="下移 API-001"]').trigger('click')
    await wrapper.get('button[aria-label="编辑 API-001"]').trigger('click')
    await wrapper.get('button[aria-label="删除 API-001"]').trigger('click')
    await wrapper.get('[data-testid="interface-case-bulk-review"]').trigger('click')

    expect(wrapper.emitted('updateKeyword')?.[0]).toEqual(['项目'])
    expect(wrapper.emitted('updateModule')?.[0]).toEqual(['项目管理'])
    expect(wrapper.emitted('updateCaseType')?.[0]).toEqual(['边界'])
    expect(wrapper.emitted('updatePriority')?.[0]).toEqual(['P2'])
    expect(wrapper.emitted('updateReviewStatus')?.[0]).toEqual(['待复核'])
    expect(wrapper.emitted('selectionChange')?.[0]).toEqual([['API-001']])
    expect(wrapper.emitted('activate')?.[0]).toEqual([cases[0]])
    expect(wrapper.emitted('move')?.[0]).toEqual([cases[0], -1])
    expect(wrapper.emitted('move')?.[1]).toEqual([cases[0], 1])
    expect(wrapper.emitted('edit')?.[0]).toEqual([cases[0]])
    expect(wrapper.emitted('remove')?.[0]).toEqual([cases[0]])
    expect(wrapper.emitted('bulkReview')?.[0]).toEqual([])
  })

  it('offers clear filters for a filtered-empty state without mutating selected ids', async () => {
    const selectedIds = ['API-001']
    const wrapper = mountTable({
      cases: [],
      selectedIds,
      keyword: '不存在'
    })

    await wrapper.get('[data-testid="interface-case-clear-filters"]').trigger('click')

    expect(wrapper.emitted('clearFilters')?.[0]).toEqual([])
    expect(wrapper.props('selectedIds')).toEqual(['API-001'])
    expect(selectedIds).toEqual(['API-001'])
    expect(wrapper.emitted('selectionChange')).toBeUndefined()
  })

  it('keeps explicit filter options available when visible cases are empty or filtered', async () => {
    const wrapper = mountTable({
      cases: [],
      module: '项目管理',
      caseType: '边界',
      priority: 'P2',
      reviewStatus: '待复核',
      moduleOptions: ['用户管理', '项目管理'],
      caseTypeOptions: ['正向', '边界'],
      priorityOptions: ['P1', 'P2'],
      reviewStatusOptions: ['待复核', '已通过']
    })

    const moduleFilter = wrapper.get('[data-testid="interface-case-module-filter"]').element as HTMLSelectElement
    const caseTypeFilter = wrapper.get('[data-testid="interface-case-type-filter"]').element as HTMLSelectElement
    const priorityFilter = wrapper.get('[data-testid="interface-case-priority-filter"]').element as HTMLSelectElement
    const reviewStatusFilter = wrapper.get('[data-testid="interface-case-review-status-filter"]').element as HTMLSelectElement

    expect(moduleFilter.value).toBe('项目管理')
    expect(caseTypeFilter.value).toBe('边界')
    expect(priorityFilter.value).toBe('P2')
    expect(reviewStatusFilter.value).toBe('待复核')
    expect([...moduleFilter.options].map((option) => option.value)).toEqual(['', '项目管理', '用户管理'])
    expect([...caseTypeFilter.options].map((option) => option.value)).toEqual(['', '边界', '正向'])
    expect([...priorityFilter.options].map((option) => option.value)).toEqual(['', 'P1', 'P2'])
    expect([...reviewStatusFilter.options].map((option) => option.value)).toEqual(['', '待复核', '已通过'])
  })

  it('suppresses stale selection-change events during rapid selected id synchronization', async () => {
    const wrapper = mount(InterfaceCaseTable, {
      props: {
        cases,
        visibleFields,
        selectedIds: [],
        keyword: '',
        module: '',
        caseType: '',
        priority: '',
        reviewStatus: '',
        moduleOptions: [],
        caseTypeOptions: [],
        priorityOptions: [],
        reviewStatusOptions: []
      },
      global: {
        stubs: {
          ElTable: selectionSyncTableStub,
          ElTableColumn: tableColumnStub,
          ElButton: { template: '<button><slot /></button>' }
        }
      }
    })

    await wrapper.setProps({ selectedIds: ['API-001'] })
    await wrapper.setProps({ selectedIds: ['API-002'] })
    await flushTable(wrapper as ReturnType<typeof mountTable>)

    expect(wrapper.emitted('selectionChange')).toBeUndefined()
    expect(wrapper.text()).toContain('已选择 1 条')
  })
})

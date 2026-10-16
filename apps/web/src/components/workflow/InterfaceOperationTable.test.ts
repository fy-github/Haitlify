// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import type { ParsedOperation } from '../../domain/openapiParser'
import InterfaceOperationTable from './InterfaceOperationTable.vue'

const operations: ParsedOperation[] = [
  {
    id: 'get /v4/pm/project-tree',
    module: '项目树',
    method: 'GET',
    path: '/v4/pm/project-tree',
    summary: '获取项目树入口节点',
    operationId: 'getProjectTree',
    parameters: [],
    responseStatuses: ['200']
  },
  {
    id: 'post /v4/pm/project-tree/group',
    module: '项目树',
    method: 'POST',
    path: '/v4/pm/project-tree/group',
    summary: '创建项目分组',
    operationId: 'createProjectGroup',
    parameters: [],
    responseStatuses: ['201']
  }
]

const mountTable = (props: Partial<InstanceType<typeof InterfaceOperationTable>['$props']> = {}) =>
  mount(InterfaceOperationTable, {
    attachTo: document.body,
    props: {
      operations,
      selectedIds: ['get /v4/pm/project-tree'],
      loading: false,
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

describe('InterfaceOperationTable', () => {
  it('labels the selection header for assistive technology without changing row selection behavior', async () => {
    const wrapper = mountTable()
    await flushTable(wrapper)

    const selectionHeader = wrapper.get('thead th:first-child')
    const accessibleLabel = selectionHeader.get('.studio-visually-hidden')
    expect(accessibleLabel.text()).toBe('选择接口')

    await wrapper.get('input[aria-label="选择接口 创建项目分组"]').setValue(true)

    expect(wrapper.emitted('toggleOperation')?.[0]).toEqual([operations[1], true])
  })

  it('renders operation counts, exact table columns, paths, and controlled selection state', async () => {
    const wrapper = mountTable({ loading: true })
    await flushTable(wrapper)

    expect(wrapper.get('[data-testid="interface-operation-table"]').element.tagName).toBe('SECTION')
    expect(wrapper.text()).toContain('接口列表')
    expect(wrapper.text()).toContain('2 个接口')
    expect(wrapper.text()).toContain('已选 1 个')
    for (const column of ['请求方法', '模块', '接口名称', '请求路径']) {
      expect(wrapper.text()).toContain(column)
    }
    expect(wrapper.text()).toContain('/v4/pm/project-tree')
    expect(wrapper.text()).toContain('/v4/pm/project-tree/group')
    expect(wrapper.get('.interface-operation-table__method.is-get').text()).toBe('GET')
    expect(wrapper.get('.interface-operation-table__method.is-post').text()).toBe('POST')
    expect(wrapper.findComponent({ name: 'ElTable' }).props('data')).toEqual(operations)
    expect(wrapper.findComponent({ name: 'ElTable' }).props('rowKey')).toBe('id')
    expect(wrapper.findComponent({ name: 'ElTable' }).props('height')).toBe('100%')
    expect(wrapper.findComponent({ name: 'ElTable' }).attributes('aria-busy')).toBe('true')

    const selected = wrapper.get('input[aria-label="选择接口 获取项目树入口节点"]').element as HTMLInputElement
    const unselected = wrapper.get('input[aria-label="选择接口 创建项目分组"]').element as HTMLInputElement
    expect(selected.checked).toBe(true)
    expect(unselected.checked).toBe(false)
  })

  it('emits row and batch events without mutating props', async () => {
    const selectedIds = ['get /v4/pm/project-tree']
    const wrapper = mountTable({ selectedIds })
    await flushTable(wrapper)

    await wrapper.get('input[aria-label="选择接口 创建项目分组"]').setValue(true)
    await wrapper.get('[data-testid="select-filtered-interface-operations"]').trigger('click')
    await wrapper.get('[data-testid="clear-interface-operation-selection"]').trigger('click')

    expect(wrapper.emitted('toggleOperation')?.[0]).toEqual([operations[1], true])
    expect(wrapper.emitted('selectFiltered')?.[0]).toEqual([])
    expect(wrapper.emitted('clearSelected')?.[0]).toEqual([])
    expect(wrapper.props('selectedIds')).toEqual(['get /v4/pm/project-tree'])
    expect(selectedIds).toEqual(['get /v4/pm/project-tree'])
    expect(operations[1]).toEqual({
      id: 'post /v4/pm/project-tree/group',
      module: '项目树',
      method: 'POST',
      path: '/v4/pm/project-tree/group',
      summary: '创建项目分组',
      operationId: 'createProjectGroup',
      parameters: [],
      responseStatuses: ['201']
    })
  })

  it('disables batch commands from current controlled state', () => {
    const emptyWrapper = mountTable({ operations: [], selectedIds: [] })

    expect(emptyWrapper.get('[data-testid="select-filtered-interface-operations"]').attributes('disabled')).toBeDefined()
    expect(emptyWrapper.get('[data-testid="clear-interface-operation-selection"]').attributes('disabled')).toBeDefined()

    const selectedWrapper = mountTable({ selectedIds: ['get /v4/pm/project-tree'] })

    expect(selectedWrapper.get('[data-testid="select-filtered-interface-operations"]').attributes('disabled')).toBeUndefined()
    expect(selectedWrapper.get('[data-testid="clear-interface-operation-selection"]').attributes('disabled')).toBeUndefined()
  })
})

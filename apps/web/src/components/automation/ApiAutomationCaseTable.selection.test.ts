// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import ElementPlus, { ElTable } from 'element-plus'
import ApiAutomationCaseTable from './ApiAutomationCaseTable.vue'
import type { SavedAutomationCase } from '../../domain/automationRepository'

const makeCases = (): SavedAutomationCase[] => [1, 2].map(index => ({
  id: `API-${index}`, sourceCaseId: `SOURCE-${index}`, project: '选择项目', module: '选择模块', name: `接口 ${index}`,
  method: 'GET', path: `/selection/${index}`, headers: {}, pathParams: {}, queryParams: {}, expectedStatus: 200,
  assertions: [], runOrder: index, exportTarget: 'apifox', automationLibraryId: `selection-library-${index}`,
  savedAt: '2026-10-30T10:00:00.000Z'
}))
const wrappers: VueWrapper[] = []
const tableProps = {
  resultMap: new Map(), defectDraftByReportCaseId: new Map(),
  defectStatusLabel: () => '未转缺陷', defectStatusTagType: () => 'info' as const
}
const mountTable = (cases: SavedAutomationCase[], selection: SavedAutomationCase[]) => {
  const wrapper = mount(ApiAutomationCaseTable, {
    attachTo: document.body,
    props: {
      ...tableProps, cases, selection
    },
    global: { plugins: [ElementPlus] }
  })
  wrappers.push(wrapper)
  return wrapper
}
const rowCheckbox = (wrapper: VueWrapper, id: string) => {
  const row = wrapper.findAll('.el-table__body-wrapper tr.el-table__row')
    .find(candidate => candidate.findAll('td')[1]?.text() === id)
  expect(row, `real Element Plus row ${id}`).toBeDefined()
  return row!.get<HTMLInputElement>('input[type="checkbox"]')
}
const selectionIds = (rows: SavedAutomationCase[]) => rows.map(row => row.automationLibraryId).sort()
const controlledTable = (cases: SavedAutomationCase[], selection: SavedAutomationCase[]) => {
  const rows = ref(cases)
  const selected = ref(selection)
  const visible = ref(true)
  const updates: SavedAutomationCase[][] = []
  const host = mount(defineComponent({
    setup: () => () => visible.value ? h(ApiAutomationCaseTable, {
      ...tableProps, cases: rows.value, selection: selected.value,
      'onUpdate:selection': (next: SavedAutomationCase[]) => {
        updates.push(next)
        selected.value = next
      }
    }) : h('div')
  }), { attachTo: document.body, global: { plugins: [ElementPlus] } })
  wrappers.push(host)
  return { host, rows, selected, visible, updates }
}

afterEach(() => {
  for (const wrapper of wrappers.splice(0)) wrapper.unmount()
  document.body.innerHTML = ''
})

describe('ApiAutomationCaseTable controlled selection with real Element Plus', () => {
  it('checks the supplied API2 row and adding API1 emits both identities', async () => {
    const cases = makeCases()
    const wrapper = mountTable(cases, [cases[1]!])
    await flushPromises()
    expect(wrapper.findComponent(ElTable).exists()).toBe(true)
    expect(rowCheckbox(wrapper, 'API-2').element.checked).toBe(true)
    expect(rowCheckbox(wrapper, 'API-1').element.checked).toBe(false)
    expect(wrapper.emitted('update:selection')).toBeUndefined()

    await rowCheckbox(wrapper, 'API-1').setValue(true)
    const updates = wrapper.emitted('update:selection')!
    expect(updates).toHaveLength(1)
    expect(selectionIds(updates[0]![0] as SavedAutomationCase[])).toEqual([
      'selection-library-1', 'selection-library-2'
    ])
  })

  it('applies parent replacement, in-place selection edits and clear without emitting echoes', async () => {
    const cases = makeCases()
    const { host, selected, rows, updates } = controlledTable(cases, [cases[1]!])
    await flushPromises()
    selected.value = [rows.value[0]!]
    await flushPromises()
    expect(rowCheckbox(host, 'API-1').element.checked).toBe(true)
    expect(rowCheckbox(host, 'API-2').element.checked).toBe(false)
    selected.value.push(rows.value[1]!)
    await flushPromises()
    expect(rowCheckbox(host, 'API-2').element.checked).toBe(true)
    selected.value.splice(0)
    await flushPromises()
    expect(rowCheckbox(host, 'API-1').element.checked).toBe(false)
    expect(rowCheckbox(host, 'API-2').element.checked).toBe(false)
    selected.value = [rows.value[1]!]
    await flushPromises()
    selected.value = []
    await flushPromises()
    expect(rowCheckbox(host, 'API-2').element.checked).toBe(false)
    expect(updates).toEqual([])
  })

  it('keeps library identity checked after data refresh and rebinds the selected execution payload once', async () => {
    const cases = makeCases()
    const { host, rows, selected, updates } = controlledTable(cases, [cases[1]!])
    await flushPromises()
    rows.value = makeCases().map(row => ({ ...row, path: `${row.path}/refreshed` }))
    rows.value[1]!.id = 'API-2-REFRESHED'
    await flushPromises()
    expect(rowCheckbox(host, 'API-2-REFRESHED').element.checked).toBe(true)
    expect(rowCheckbox(host, 'API-1').element.checked).toBe(false)
    expect(updates).toHaveLength(1)
    expect(selected.value[0]).toBe(rows.value[1])
    expect(selected.value[0]!.path).toBe('/selection/2/refreshed')
    await flushPromises()
    expect(updates).toHaveLength(1)
    await rowCheckbox(host, 'API-1').setValue(true)
    await flushPromises()
    expect(updates).toHaveLength(2)
    expect(selectionIds(selected.value)).toEqual(['selection-library-1', 'selection-library-2'])
    expect(selected.value[1]).toBe(rows.value[1])
  })

  it('keeps the latest parent target when selection and filtered data change in the same render', async () => {
    const cases = makeCases()
    const { host, rows, selected, updates } = controlledTable(cases, [cases[0]!])
    await flushPromises()
    const refreshed = makeCases()
    rows.value = [refreshed[1]!]
    selected.value = [refreshed[1]!]
    await flushPromises()
    expect(rowCheckbox(host, 'API-2').element.checked).toBe(true)
    expect(selectionIds(selected.value)).toEqual(['selection-library-2'])
    expect(updates).toEqual([])
    await rowCheckbox(host, 'API-2').setValue(false)
    await flushPromises()
    expect(selected.value).toEqual([])
    expect(updates).toHaveLength(1)
  })

  it('prunes selection to filtered or removed rows without reserving invisible identities', async () => {
    const cases = makeCases()
    const { host, rows, selected, updates } = controlledTable(cases, [cases[0]!, cases[1]!])
    await flushPromises()
    rows.value = [rows.value[1]!]
    await flushPromises()
    expect(rowCheckbox(host, 'API-2').element.checked).toBe(true)
    expect(selectionIds(selected.value)).toEqual(['selection-library-2'])
    expect(updates).toHaveLength(1)
    rows.value = makeCases()
    await flushPromises()
    expect(rowCheckbox(host, 'API-1').element.checked).toBe(false)
    expect(rowCheckbox(host, 'API-2').element.checked).toBe(true)
    rows.value = [rows.value[0]!]
    await flushPromises()
    expect(rowCheckbox(host, 'API-1').element.checked).toBe(false)
    expect(selected.value).toEqual([])
    expect(updates).toHaveLength(3)
    rows.value = []
    await flushPromises()
    expect(host.findAll('.el-table__body-wrapper tr.el-table__row')).toHaveLength(0)
    expect(updates).toHaveLength(3)
    rows.value = makeCases()
    await flushPromises()
    expect(rowCheckbox(host, 'API-2').element.checked).toBe(false)
    expect(updates).toHaveLength(3)
  })

  it('restores controlled checks after an actual table unmount and remount without changing parent selection', async () => {
    const cases = makeCases()
    const { host, selected, rows, visible, updates } = controlledTable(cases, [cases[1]!])
    await flushPromises()
    expect(rowCheckbox(host, 'API-2').element.checked).toBe(true)
    visible.value = false
    await flushPromises()
    expect(host.findComponent(ElTable).exists()).toBe(false)
    expect(selectionIds(selected.value)).toEqual(['selection-library-2'])
    visible.value = true
    await flushPromises()
    expect(rowCheckbox(host, 'API-2').element.checked).toBe(true)
    expect(rowCheckbox(host, 'API-1').element.checked).toBe(false)
    expect(updates).toEqual([])
    visible.value = false
    await flushPromises()
    selected.value = [rows.value[0]!]
    visible.value = true
    await flushPromises()
    expect(rowCheckbox(host, 'API-1').element.checked).toBe(true)
    expect(rowCheckbox(host, 'API-2').element.checked).toBe(false)
    expect(updates).toEqual([])
  })

  it('publishes exactly the full selection on row additions, removals, header select-all and clear', async () => {
    const cases = makeCases()
    const { host, selected, updates } = controlledTable(cases, [cases[0]!])
    await flushPromises()
    await rowCheckbox(host, 'API-2').setValue(true)
    await flushPromises()
    expect(selectionIds(selected.value)).toEqual(['selection-library-1', 'selection-library-2'])
    await rowCheckbox(host, 'API-1').setValue(false)
    await flushPromises()
    expect(selectionIds(selected.value)).toEqual(['selection-library-2'])
    await rowCheckbox(host, 'API-2').setValue(false)
    await flushPromises()
    expect(selected.value).toEqual([])
    const header = () => host.get<HTMLInputElement>('.el-table__header-wrapper input[type="checkbox"]')
    await header().setValue(true)
    // Element Plus debounces the real header select-all by 10ms.
    await vi.waitFor(() => expect(selectionIds(selected.value)).toEqual(['selection-library-1', 'selection-library-2']))
    await flushPromises()
    expect(header().element.checked).toBe(true)
    expect(rowCheckbox(host, 'API-1').element.checked).toBe(true)
    expect(rowCheckbox(host, 'API-2').element.checked).toBe(true)
    expect(selectionIds(selected.value)).toEqual(['selection-library-1', 'selection-library-2'])
    await header().setValue(false)
    await vi.waitFor(() => expect(selected.value).toEqual([]))
    await flushPromises()
    expect(header().element.checked).toBe(false)
    expect(rowCheckbox(host, 'API-1').element.checked).toBe(false)
    expect(rowCheckbox(host, 'API-2').element.checked).toBe(false)
    expect(selected.value).toEqual([])
    expect(updates.map(selectionIds)).toEqual([
      ['selection-library-1', 'selection-library-2'], ['selection-library-2'], [],
      ['selection-library-1', 'selection-library-2'], []
    ])
    await flushPromises()
    expect(updates).toHaveLength(5)
  })

  it('keeps row action emits independent of selection synchronization', async () => {
    const cases = makeCases()
    const wrapper = mountTable(cases, [cases[1]!])
    await wrapper.setProps({ resultMap: new Map([[cases[0]!.id, {
      caseId: cases[0]!.id, status: 'failed'
    } as never]]) })
    await flushPromises()
    const row = wrapper.findAll('.el-table__body-wrapper tr.el-table__row')[0]!
    for (const [label, event] of [['cURL', 'openCurl'], ['日志', 'openLog'], ['转缺陷', 'transferDefect'], ['删除', 'deleteCase']]) {
      await row.findAll('button').find(button => button.text() === label)!.trigger('click')
      expect(wrapper.emitted(event!)).toHaveLength(1)
      expect((wrapper.emitted(event!)![0]![0] as SavedAutomationCase).automationLibraryId).toBe('selection-library-1')
    }
    expect(wrapper.emitted('update:selection')).toBeUndefined()
    expect(rowCheckbox(wrapper, 'API-2').element.checked).toBe(true)
  })
})

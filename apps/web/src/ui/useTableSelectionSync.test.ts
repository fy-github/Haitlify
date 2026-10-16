// @vitest-environment jsdom
import { nextTick, ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { useTableSelectionSync, type TableSelectionApi } from './useTableSelectionSync'

interface Row {
  id: string
}

const rows = (ids: string[]): Row[] => ids.map((id) => ({ id }))

describe('useTableSelectionSync', () => {
  it('applies the external selected ids to the table rows', async () => {
    const cases = rows(['a', 'b', 'c'])
    const selectedIds = ref<string[]>([])
    const clearSelection = vi.fn()
    const toggleRowSelection = vi.fn()
    const tableRef = ref<TableSelectionApi<Row>>()
    const onSelectionChange = vi.fn()

    useTableSelectionSync({
      tableRef,
      cases: () => cases,
      selectedIds: () => selectedIds.value,
      onSelectionChange
    })
    tableRef.value = { clearSelection, toggleRowSelection }
    selectedIds.value = ['b', 'c']

    await nextTick()
    await nextTick()

    expect(clearSelection).toHaveBeenCalled()
    expect(toggleRowSelection).toHaveBeenCalledWith({ id: 'b' }, true)
    expect(toggleRowSelection).toHaveBeenCalledWith({ id: 'c' }, true)
    expect(toggleRowSelection).not.toHaveBeenCalledWith({ id: 'a' }, true)
    expect(onSelectionChange).not.toHaveBeenCalled()
  })

  it('suppresses the selection-change echo raised while syncing', async () => {
    const cases = rows(['a', 'b'])
    const selectedIds = ref<string[]>([])
    const clearSelection = vi.fn()
    const toggleRowSelection = vi.fn()
    const tableRef = ref<TableSelectionApi<Row>>()
    const onSelectionChange = vi.fn()

    const { handleSelectionChange } = useTableSelectionSync({
      tableRef,
      cases: () => cases,
      selectedIds: () => selectedIds.value,
      onSelectionChange
    })
    // simulate Element Plus echoing selection-change in response to toggleRowSelection
    toggleRowSelection.mockImplementation(() => handleSelectionChange(rows(selectedIds.value)))
    tableRef.value = { clearSelection, toggleRowSelection }
    selectedIds.value = ['b']

    await nextTick()
    await nextTick()

    expect(toggleRowSelection).toHaveBeenCalledWith({ id: 'b' }, true)
    expect(onSelectionChange).not.toHaveBeenCalled()
  })

  it('does not emit when the selection is unchanged and emits when it differs', async () => {
    const cases = rows(['a', 'b'])
    const selectedIds = ref<string[]>(['b'])
    const clearSelection = vi.fn()
    const toggleRowSelection = vi.fn()
    const tableRef = ref<TableSelectionApi<Row>>()
    const onSelectionChange = vi.fn()

    const { handleSelectionChange } = useTableSelectionSync({
      tableRef,
      cases: () => cases,
      selectedIds: () => selectedIds.value,
      onSelectionChange
    })
    tableRef.value = { clearSelection, toggleRowSelection }
    // settle the initial immediate sync so the sync token is released
    await nextTick()
    await nextTick()

    handleSelectionChange(rows(['b']))
    expect(onSelectionChange).not.toHaveBeenCalled()

    handleSelectionChange(rows(['a']))
    expect(onSelectionChange).toHaveBeenCalledTimes(1)
    expect(onSelectionChange).toHaveBeenCalledWith(['a'])
  })

  it('does not touch the table when the feature is disabled', async () => {
    const cases = rows(['a', 'b'])
    const selectedIds = ref<string[]>(['a'])
    const clearSelection = vi.fn()
    const toggleRowSelection = vi.fn()
    const tableRef = ref<TableSelectionApi<Row>>()
    const onSelectionChange = vi.fn()

    useTableSelectionSync({
      tableRef,
      cases: () => cases,
      selectedIds: () => selectedIds.value,
      enabled: () => false,
      onSelectionChange
    })
    tableRef.value = { clearSelection, toggleRowSelection }
    selectedIds.value = ['b']

    await nextTick()
    await nextTick()

    expect(clearSelection).not.toHaveBeenCalled()
    expect(toggleRowSelection).not.toHaveBeenCalled()
  })
})

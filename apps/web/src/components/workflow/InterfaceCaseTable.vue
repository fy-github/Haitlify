<script setup lang="ts">
import { computed, ref } from 'vue'
import { ArrowDown, ArrowUp, Ellipsis, Pencil, Trash2 } from '@lucide/vue'
import type { GeneratedInterfaceCase, InterfaceCaseRow } from '../../domain/caseGenerator'
import type { CaseField } from '../../domain/caseTemplates'
import { useTableSelectionSync, type TableSelectionApi } from '../../ui/useTableSelectionSync'

const props = withDefaults(defineProps<{
  cases: GeneratedInterfaceCase[]
  visibleFields: CaseField[]
  selectedIds: string[]
  keyword: string
  module: string
  caseType: string
  priority: string
  reviewStatus: string
  moduleOptions?: string[]
  caseTypeOptions?: string[]
  priorityOptions?: string[]
  reviewStatusOptions?: string[]
  loading?: boolean
}>(), {
  moduleOptions: () => [],
  caseTypeOptions: () => [],
  priorityOptions: () => [],
  reviewStatusOptions: () => [],
  loading: false
})

const emit = defineEmits<{
  selectionChange: [ids: string[]]
  updateKeyword: [value: string]
  updateModule: [value: string]
  updateCaseType: [value: string]
  updatePriority: [value: string]
  updateReviewStatus: [value: string]
  clearFilters: []
  activate: [testCase: GeneratedInterfaceCase]
  move: [testCase: GeneratedInterfaceCase, offset: -1 | 1]
  edit: [testCase: GeneratedInterfaceCase]
  remove: [testCase: GeneratedInterfaceCase]
  bulkReview: []
}>()

const tableRef = ref<TableSelectionApi<GeneratedInterfaceCase>>()
const { handleSelectionChange } = useTableSelectionSync({
  tableRef,
  cases: () => props.cases,
  selectedIds: () => props.selectedIds,
  onSelectionChange: (ids) => emit('selectionChange', ids)
})

const uniqueOptions = (values: string[]): string[] =>
  Array.from(new Set(values.map((value) => value.trim()).filter(Boolean))).sort((left, right) => left.localeCompare(right, 'zh-Hans-CN'))

const mergeOptions = (explicitOptions: string[], currentValue: string, fallbackValues: string[]): string[] =>
  uniqueOptions([...explicitOptions, currentValue, ...fallbackValues])

const modules = computed(() => mergeOptions(props.moduleOptions, props.module, props.cases.map((testCase) => testCase.row.module)))
const caseTypes = computed(() => mergeOptions(props.caseTypeOptions, props.caseType, props.cases.map((testCase) => testCase.row.caseType)))
const priorities = computed(() => mergeOptions(props.priorityOptions, props.priority, props.cases.map((testCase) => testCase.row.priority)))
const reviewStatuses = computed(() => mergeOptions(
  props.reviewStatusOptions,
  props.reviewStatus,
  props.cases.map((testCase) => testCase.metadata.reviewMeta.status)
))

const selectedCountText = computed(() => `已选择 ${props.selectedIds.length} 条`)

const fieldCellText = (row: InterfaceCaseRow, key: string): string => {
  const value = row[key as keyof InterfaceCaseRow]
  return value === undefined || value === null ? '' : String(value)
}

const fieldColumnWidth = (key: string): number | undefined => {
  const widths: Record<string, number> = {
    caseId: 120,
    module: 120,
    interfaceName: 160,
    method: 96,
    path: 220,
    title: 260,
    caseType: 120,
    priority: 90,
    executionResult: 110,
    sourceFile: 160
  }
  return widths[key]
}

const inputValue = (event: Event): string => (event.target as HTMLInputElement).value
const selectValue = (event: Event): string => (event.target as HTMLSelectElement).value

</script>

<template>
  <section class="interface-case-table">
    <div class="interface-case-toolbar" data-testid="interface-case-toolbar">
      <label class="interface-case-toolbar__search">
        <span>搜索</span>
        <input
          :value="keyword"
          aria-label="搜索标题或接口路径"
          placeholder="搜索标题或接口路径"
          type="search"
          @input="emit('updateKeyword', inputValue($event))"
        >
      </label>
      <label>
        <span>模块</span>
        <select
          :value="module"
          data-testid="interface-case-module-filter"
          aria-label="筛选模块"
          @change="emit('updateModule', selectValue($event))"
        >
          <option value="">全部模块</option>
          <option v-for="item in modules" :key="item" :value="item">{{ item }}</option>
        </select>
      </label>
      <label>
        <span>类型</span>
        <select
          :value="caseType"
          data-testid="interface-case-type-filter"
          aria-label="筛选用例类型"
          @change="emit('updateCaseType', selectValue($event))"
        >
          <option value="">全部类型</option>
          <option v-for="item in caseTypes" :key="item" :value="item">{{ item }}</option>
        </select>
      </label>
      <label>
        <span>优先级</span>
        <select
          :value="priority"
          data-testid="interface-case-priority-filter"
          aria-label="筛选优先级"
          @change="emit('updatePriority', selectValue($event))"
        >
          <option value="">全部优先级</option>
          <option v-for="item in priorities" :key="item" :value="item">{{ item }}</option>
        </select>
      </label>
      <label>
        <span>评审</span>
        <select
          :value="reviewStatus"
          data-testid="interface-case-review-status-filter"
          aria-label="筛选评审状态"
          @change="emit('updateReviewStatus', selectValue($event))"
        >
          <option value="">全部状态</option>
          <option v-for="item in reviewStatuses" :key="item" :value="item">{{ item }}</option>
        </select>
      </label>
      <span class="interface-case-toolbar__selection">{{ selectedCountText }}</span>
      <button
        type="button"
        data-testid="interface-case-bulk-review"
        @click="emit('bulkReview')"
      >
        批量评审
      </button>
      <button
        type="button"
        aria-label="更多批量操作（待接入）"
        title="更多批量操作待接入"
        disabled
      >
        <Ellipsis :size="16" />
      </button>
    </div>

    <el-table
      ref="tableRef"
      class="interface-case-table__grid"
      :data="cases"
      :aria-busy="loading"
      row-key="id"
      border
      size="small"
      @row-click="emit('activate', $event)"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="48" fixed reserve-selection />
      <el-table-column
        v-for="field in visibleFields"
        :key="field.key"
        :prop="`row.${field.key}`"
        :label="field.label"
        :width="fieldColumnWidth(field.key)"
        :min-width="field.key === 'title' ? 240 : undefined"
        :fixed="field.key === 'caseId' ? 'left' : undefined"
        show-overflow-tooltip
      >
        <template #default="{ row }">
          {{ fieldCellText(row.row, field.key) }}
        </template>
      </el-table-column>
      <el-table-column prop="actions" label="操作" width="168" fixed="right">
        <template #default="{ row }">
          <div class="interface-case-table__actions">
            <el-button
              link
              :aria-label="`上移 ${row.row.caseId}`"
              :title="`上移 ${row.row.caseId}`"
              @click.stop="emit('move', row, -1)"
            >
              <ArrowUp :size="14" />
            </el-button>
            <el-button
              link
              :aria-label="`下移 ${row.row.caseId}`"
              :title="`下移 ${row.row.caseId}`"
              @click.stop="emit('move', row, 1)"
            >
              <ArrowDown :size="14" />
            </el-button>
            <el-button
              link
              :aria-label="`编辑 ${row.row.caseId}`"
              :title="`编辑 ${row.row.caseId}`"
              @click.stop="emit('edit', row)"
            >
              <Pencil :size="14" />
            </el-button>
            <el-button
              link
              type="danger"
              :aria-label="`删除 ${row.row.caseId}`"
              :title="`删除 ${row.row.caseId}`"
              @click.stop="emit('remove', row)"
            >
              <Trash2 :size="14" />
            </el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="loading || cases.length === 0" class="interface-case-table__empty">
      <span>{{ loading ? '正在加载...' : '暂无匹配用例' }}</span>
      <button
        type="button"
        data-testid="interface-case-clear-filters"
        @click="emit('clearFilters')"
      >
        清空筛选
      </button>
    </div>
  </section>
</template>

<style scoped>
.interface-case-table {
  display: grid;
  gap: 12px;
}

.interface-case-toolbar {
  display: flex;
  align-items: end;
  gap: 8px;
  flex-wrap: wrap;
}

.interface-case-toolbar label {
  display: grid;
  gap: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.interface-case-toolbar input,
.interface-case-toolbar select {
  min-height: 32px;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  padding: 0 10px;
  color: var(--el-text-color-primary);
  background: var(--el-bg-color);
}

.interface-case-toolbar__search input {
  width: 220px;
}

.interface-case-toolbar__selection {
  color: var(--el-text-color-regular);
  line-height: 32px;
}

.interface-case-toolbar button,
.interface-case-table__empty button {
  min-height: 32px;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  padding: 0 10px;
  color: var(--el-text-color-primary);
  background: var(--el-bg-color);
  cursor: pointer;
}

.interface-case-table__actions {
  display: flex;
  align-items: center;
  gap: 2px;
}

.interface-case-table__empty {
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--el-text-color-secondary);
}
</style>

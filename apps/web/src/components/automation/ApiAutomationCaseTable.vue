<script setup lang="ts">
import { ref, watch } from 'vue'
import type { TableInstance } from 'element-plus'
import type { AutomationRunResult, AutomationRunStatus } from '../../domain/apiAutomation'
import type { SavedAutomationCase } from '../../domain/automationRepository'
import type { DefectDraft } from '../../domain/defectRepository'
import { automationStatusLabel, automationStatusTagType } from '../../ui/automationFormat'

const props = withDefaults(defineProps<{
  cases: SavedAutomationCase[]
  selection?: SavedAutomationCase[]
  resultMap: Map<string, AutomationRunResult>
  defectDraftByReportCaseId: Map<string, DefectDraft>
  defectStatusLabel: (caseId: string) => string
  defectStatusTagType: (draft?: DefectDraft) => 'warning' | 'success' | 'info'
}>(), { selection: () => [] })

const emit = defineEmits<{
  'update:selection': [rows: SavedAutomationCase[]]
  openCurl: [row: SavedAutomationCase]
  openLog: [row: SavedAutomationCase]
  transferDefect: [row: SavedAutomationCase]
  deleteCase: [row: SavedAutomationCase]
}>()

const table = ref<TableInstance>()
const selectionKey = (row: SavedAutomationCase) => row.automationLibraryId
const sameRows = (left: SavedAutomationCase[], right: SavedAutomationCase[]) =>
  left.length === right.length && left.every((row, index) => row === right[index])
let syncingSelection = true

// setData clears Element Plus selection before the post-render reconciliation.
// Mark external changes synchronously so that clear cannot erase parent intent.
watch([() => props.cases, () => props.selection], () => {
  syncingSelection = true
}, { deep: true, flush: 'sync' })

const handleSelectionChange = (rows: SavedAutomationCase[]) => {
  if (syncingSelection) return
  const selected = new Set(rows.map(selectionKey))
  emit('update:selection', props.cases.filter(row => selected.has(selectionKey(row))))
}

watch([table, () => props.cases, () => props.selection], () => {
  const selected = new Set(props.selection.map(selectionKey))
  const rows = props.cases.filter(row => selected.has(selectionKey(row)))
  // API selection remains scoped to the current filtered rows (no reserve-selection).
  // Rebind to refreshed records so executing selected uses the current payload too.
  if (!sameRows(rows, props.selection)) emit('update:selection', rows)
  if (!table.value) return
  syncingSelection = true
  try {
    table.value.clearSelection()
    rows.forEach(row => table.value!.toggleRowSelection(row, true))
  } finally {
    syncingSelection = false
  }
}, { deep: true, flush: 'post' })

const resultStatus = (testCase: SavedAutomationCase): AutomationRunStatus | 'pending' =>
  props.resultMap.get(testCase.id)?.status || 'pending'
</script>

<template>
  <el-table
    ref="table"
    class="case-table"
    :data="cases"
    row-key="automationLibraryId"
    height="100%"
    border
    @selection-change="handleSelectionChange"
  >
    <el-table-column type="selection" width="48" fixed />
    <el-table-column prop="id" label="自动化ID" width="140" fixed />
    <el-table-column prop="project" label="项目" width="130" />
    <el-table-column prop="module" label="模块" width="150" />
    <el-table-column prop="name" label="用例名称" min-width="260" />
    <el-table-column prop="method" label="方法" width="90" />
    <el-table-column prop="path" label="路径" min-width="260" />
    <el-table-column prop="expectedStatus" label="期望状态" width="110" />
    <el-table-column label="执行状态" width="110">
      <template #default="{ row }">
        <el-tag :type="automationStatusTagType(resultStatus(row))" effect="light">
          {{ automationStatusLabel(resultStatus(row)) }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column label="实际状态" width="100">
      <template #default="{ row }">
        {{ resultMap.get(row.id)?.actualStatus || '-' }}
      </template>
    </el-table-column>
    <el-table-column label="返回体一致度" width="128">
      <template #default="{ row }">
        {{ resultMap.get(row.id)?.responseBodyMatch?.ratio ?? '-' }}<span v-if="resultMap.get(row.id)?.responseBodyMatch">%</span>
      </template>
    </el-table-column>
    <el-table-column label="耗时" width="100">
      <template #default="{ row }">
        {{ resultMap.get(row.id)?.durationMs ?? '-' }}<span v-if="resultMap.get(row.id)"> ms</span>
      </template>
    </el-table-column>
    <el-table-column prop="sourceCaseId" label="来源用例" width="120" />
    <el-table-column label="操作" width="280" fixed="right">
      <template #default="{ row }">
        <div class="table-actions">
          <el-button link @click.stop="emit('openCurl', row)">cURL</el-button>
          <el-button link @click.stop="emit('openLog', row)">日志</el-button>
          <el-button
            v-if="resultStatus(row) === 'failed' || resultStatus(row) === 'error'"
            link
            type="warning"
            @click.stop="emit('transferDefect', row)"
          >
            转缺陷
          </el-button>
          <el-tag
            v-if="defectStatusLabel(row.id) !== '未转缺陷'"
            :type="defectStatusTagType(defectDraftByReportCaseId.get(row.id))"
            effect="light"
          >
            {{ defectStatusLabel(row.id) }}
          </el-tag>
          <el-button link type="danger" @click.stop="emit('deleteCase', row)">删除</el-button>
        </div>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
/* The two sticky edges exceed the narrow scrollport. Keep every column and
   desktop pinning; mobile users can pan the complete row normally. */
@media (max-width: 768px) {
  .case-table :deep(.el-table-fixed-column--left),
  .case-table :deep(.el-table-fixed-column--right) {
    position: static !important;
    box-shadow: none !important;
  }
  .case-table :deep(.el-table-fixed-column--left::before),
  .case-table :deep(.el-table-fixed-column--right::before) {
    display: none;
  }
}
</style>

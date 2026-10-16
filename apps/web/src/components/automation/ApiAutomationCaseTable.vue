<script setup lang="ts">
import type { AutomationRunResult, AutomationRunStatus } from '../../domain/apiAutomation'
import type { SavedAutomationCase } from '../../domain/automationRepository'
import type { DefectDraft } from '../../domain/defectRepository'
import { automationStatusLabel, automationStatusTagType } from '../../ui/automationFormat'

const props = defineProps<{
  cases: SavedAutomationCase[]
  resultMap: Map<string, AutomationRunResult>
  defectDraftByReportCaseId: Map<string, DefectDraft>
  defectStatusLabel: (caseId: string) => string
  defectStatusTagType: (draft?: DefectDraft) => 'warning' | 'success' | 'info'
}>()

const emit = defineEmits<{
  'update:selection': [rows: SavedAutomationCase[]]
  openCurl: [row: SavedAutomationCase]
  openLog: [row: SavedAutomationCase]
  transferDefect: [row: SavedAutomationCase]
  deleteCase: [row: SavedAutomationCase]
}>()

const resultStatus = (testCase: SavedAutomationCase): AutomationRunStatus | 'pending' =>
  props.resultMap.get(testCase.id)?.status || 'pending'
</script>

<template>
  <el-table
    class="case-table"
    :data="cases"
    height="100%"
    border
    @selection-change="emit('update:selection', $event)"
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

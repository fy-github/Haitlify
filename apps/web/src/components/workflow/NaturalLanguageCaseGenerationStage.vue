<script setup lang="ts">
import { CircleAlert, RefreshCcw, Sparkles, Square } from '@lucide/vue'
import { computed, ref } from 'vue'
import type { WorkflowAsyncStatus } from '../../domain/caseDesignWorkflow'
import type { GeneratedWebAutomationCase } from '../../domain/webAutomationCase'
import GeneratedWebCaseTable from './GeneratedWebCaseTable.vue'

const props = defineProps<{
  status: WorkflowAsyncStatus
  progressPercentage: number
  progressText: string
  errorMessage: string
  cases: GeneratedWebAutomationCase[]
  selectedIds: string[]
  projectId?: number
  retryablePointCount?: number
  /** 当前可生成的已确认测试点数（勾选范围内）；首次生成是否可点击看它，而不是看已有用例数。 */
  generatablePointCount?: number
  cancelling?: boolean
}>()

const moduleFilter = ref('ALL')
const typeFilter = ref('ALL')
const keyword = ref('')
const modules = computed(() => Array.from(new Set(props.cases.map((testCase) => testCase.row.module).filter(Boolean))))
const caseTypes = computed(() => Array.from(new Set(props.cases.map((testCase) => testCase.row.caseType).filter(Boolean))))
const showStatus = computed(() => props.status === 'running' || props.status === 'failed' || Boolean(props.errorMessage))
const filteredCases = computed(() => props.cases.filter((testCase) => {
  if (moduleFilter.value !== 'ALL' && testCase.row.module !== moduleFilter.value) return false
  if (typeFilter.value !== 'ALL' && testCase.row.caseType !== typeFilter.value) return false
  const query = keyword.value.trim().toLowerCase()
  return !query || [testCase.row.caseId, testCase.row.title, testCase.row.module, testCase.row.pageName, testCase.row.naturalSteps, testCase.row.naturalExpectedResult].join(' ').toLowerCase().includes(query)
}))

const emit = defineEmits<{
  generate: []
  retry: []
  cancel: []
  selectionChange: [ids: string[]]
  activate: [testCase: GeneratedWebAutomationCase]
  preview: [testCase: GeneratedWebAutomationCase]
  edit: [testCase: GeneratedWebAutomationCase]
  remove: [testCase: GeneratedWebAutomationCase]
}>()
</script>

<template>
  <section class="studio-natural-case-stage" :class="{ 'has-status': showStatus }" data-testid="natural-language-case-generation-stage">
    <header class="studio-natural-case-stage__toolbar">
      <div class="studio-natural-case-stage__filters">
        <el-select v-model="moduleFilter" aria-label="用例模块"><el-option label="全部模块" value="ALL" /><el-option v-for="module in modules" :key="module" :label="module" :value="module" /></el-select>
        <el-select v-model="typeFilter" aria-label="用例类型"><el-option label="全部类型" value="ALL" /><el-option v-for="type in caseTypes" :key="type" :label="type" :value="type" /></el-select>
        <el-input v-model="keyword" clearable placeholder="搜索 Web 用例" aria-label="搜索 Web 用例" />
      </div>
      <el-button v-if="status === 'running'" type="danger" plain :loading="cancelling" @click="emit('cancel')"><Square :size="14" />终止生成</el-button>
      <el-button v-else-if="status === 'failed'" type="primary" :disabled="!retryablePointCount" @click="emit('retry')"><RefreshCcw :size="15" />重试生成</el-button>
      <el-button v-else type="primary" :disabled="!generatablePointCount" @click="emit('generate')"><Sparkles :size="15" />{{ cases.length ? '重新生成用例' : '生成自然语言用例' }}</el-button>
    </header>
    <div v-if="showStatus" class="studio-natural-case-stage__status" :class="`is-${status}`" role="status">
      <CircleAlert v-if="status === 'failed'" :size="17" /><Sparkles v-else :size="17" />
      <span>{{ errorMessage || progressText }}</span>
      <el-progress v-if="status === 'running'" :percentage="progressPercentage" :show-text="false" />
    </div>
    <GeneratedWebCaseTable v-if="filteredCases.length" :cases="filteredCases" :selected-ids="selectedIds" :project-id="projectId" table-height="100%" selectable @selection-change="emit('selectionChange', $event)" @activate="emit('activate', $event)" @preview="emit('preview', $event)" @edit="emit('edit', $event)" @remove="emit('remove', $event)" />
    <el-empty v-else-if="cases.length" description="没有符合条件的用例" />
    <el-empty v-else-if="status === 'idle'" description="确认测试点后生成自然语言用例" />
  </section>
</template>

<style scoped>
.studio-natural-case-stage { display: grid; height: calc(100% - 20px); min-height: 0; box-sizing: border-box; grid-template-rows: auto minmax(0, 1fr); margin: 14px 16px 6px; overflow: hidden; background: var(--surface-raised); border: 1px solid var(--border-default); border-radius: 8px; }
.studio-natural-case-stage.has-status { grid-template-rows: auto auto minmax(0, 1fr); }
.studio-natural-case-stage__toolbar { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 12px; padding: 10px 16px; border-bottom: 1px solid var(--border-subtle); }
.studio-natural-case-stage__filters { display: grid; grid-template-columns: minmax(120px, 180px) minmax(120px, 180px) minmax(180px, 1fr); gap: 8px; min-width: 0; }
.studio-natural-case-stage > :deep(.studio-generated-case-table) { min-height: 0; height: 100%; border-right: 0; border-bottom: 0; border-left: 0; border-radius: 0; }
.studio-natural-case-stage__status { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 8px; align-items: center; padding: 10px 12px; border-radius: 8px; color: var(--brand-primary); background: var(--brand-primary-subtle); font-size: 12px; }
.studio-natural-case-stage__status :deep(.el-progress) { grid-column: 2; width: 100%; }
.studio-natural-case-stage__status.is-failed { color: var(--color-danger); background: var(--surface-page); }
@media (max-width: 720px) { .studio-natural-case-stage__toolbar { grid-template-columns: 1fr; }.studio-natural-case-stage__filters { grid-template-columns: repeat(2, minmax(0, 1fr)); }.studio-natural-case-stage__filters .el-input { grid-column: 1 / -1; } }
</style>

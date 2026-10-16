<script setup lang="ts">
import { ref } from 'vue'
import { Eye, FingerprintPattern, Pencil, Trash2 } from '@lucide/vue'
import type { GeneratedWebAutomationCase } from '../../domain/webAutomationCase'
import { formatNaturalLanguageText } from '../../domain/webCaseImport'
import { listLocatorKnowledge, type LocatorKnowledgeEntry } from '../../domain/webCaseAgentRepository'
import { useTableSelectionSync, type TableSelectionApi } from '../../ui/useTableSelectionSync'

const props = withDefaults(defineProps<{
  cases: GeneratedWebAutomationCase[]
  selectedIds: string[]
  selectable?: boolean
  tableHeight?: string | number
  tableMaxHeight?: string | number
  projectId?: number
}>(), { selectable: false })

// 证据闭环:点击「证据 N」徽章可追溯该用例绑定的页面证据(locator 素材)。
const evidenceVisible = ref(false)
const evidenceCase = ref<GeneratedWebAutomationCase | null>(null)
const evidenceEntries = ref<LocatorKnowledgeEntry[]>([])
const evidenceLoading = ref(false)
const evidenceError = ref('')
const openEvidence = async (testCase: GeneratedWebAutomationCase) => {
  evidenceCase.value = testCase
  evidenceVisible.value = true
  evidenceEntries.value = []
  evidenceError.value = ''
  const ids = testCase.metadata.agentEvidenceIds || []
  if (!ids.length || !props.projectId) return
  evidenceLoading.value = true
  try {
    const { items } = await listLocatorKnowledge(props.projectId)
    const matched = new Set(ids)
    evidenceEntries.value = items.filter((entry) => matched.has(entry.entryId))
  } catch (cause) {
    evidenceError.value = cause instanceof Error ? cause.message : '证据加载失败'
  } finally {
    evidenceLoading.value = false
  }
}

const emit = defineEmits<{
  selectionChange: [ids: string[]]
  activate: [testCase: GeneratedWebAutomationCase]
  preview: [testCase: GeneratedWebAutomationCase]
  edit: [testCase: GeneratedWebAutomationCase]
  remove: [testCase: GeneratedWebAutomationCase]
}>()

const tableRef = ref<TableSelectionApi<GeneratedWebAutomationCase>>()
const { handleSelectionChange } = useTableSelectionSync({
  tableRef,
  cases: () => props.cases,
  selectedIds: () => props.selectedIds,
  enabled: () => props.selectable,
  onSelectionChange: (ids) => emit('selectionChange', ids)
})

const summarizeNaturalText = (value: string | undefined, maxLines = 2): string => {
  const lines = formatNaturalLanguageText(value).split('\n').filter(Boolean)
  if (lines.length === 0) return '-'
  const preview = lines.slice(0, maxLines).join(' / ')
  return lines.length > maxLines ? `${preview} …` : preview
}

const hasNaturalText = (value: string | undefined): boolean => !!value?.trim()
const assertionCount = (testCase: GeneratedWebAutomationCase): number =>
  testCase.metadata.steps.filter((step) => step.action.startsWith('assert')).length
</script>

<template>
  <el-table
    class="studio-generated-case-table"
    ref="tableRef"
    :data="cases"
    :height="tableHeight"
    :max-height="tableMaxHeight"
    row-key="id"
    size="small"
    scrollbar-always-on
    @row-click="emit('activate', $event)"
    @selection-change="handleSelectionChange"
  >
    <el-table-column v-if="selectable" type="selection" width="42" reserve-selection />
    <el-table-column label="用例" min-width="300" show-overflow-tooltip>
      <template #default="{ row }">
        <div class="studio-generated-case-table__case">
          <span class="studio-generated-case-table__id">{{ row.row.caseId }}</span>
          <strong>{{ row.row.title }}</strong>
          <small>{{ row.row.module || '未分模块' }} · {{ row.row.pageName || '未分页面' }}</small>
        </div>
      </template>
    </el-table-column>
    <el-table-column label="测试设计" min-width="390">
      <template #default="{ row }">
        <div
          v-if="hasNaturalText(row.row.naturalSteps) || hasNaturalText(row.row.naturalExpectedResult)"
          class="studio-generated-case-table__design"
        >
          <span><b>步骤</b>{{ summarizeNaturalText(row.row.naturalSteps) }}</span>
          <span><b>预期</b>{{ summarizeNaturalText(row.row.naturalExpectedResult, 1) }}</span>
        </div>
        <div v-else class="studio-generated-case-table__missing-design">
          未生成自然语言步骤/预期，请重新生成该用例
        </div>
      </template>
    </el-table-column>
    <el-table-column label="自动化编排" min-width="210">
      <template #default="{ row }">
        <div class="studio-generated-case-table__automation">
          <span>{{ row.metadata.steps.length }} 步</span>
          <span>{{ assertionCount(row) }} 个断言</span>
          <span v-if="row.metadata.agentEvidenceIds?.length" class="studio-generated-case-table__evidence" :title="`基于 ${row.metadata.agentEvidenceIds.length} 条页面证据生成`" @click.stop="openEvidence(row)">
            <FingerprintPattern :size="12" />证据 {{ row.metadata.agentEvidenceIds.length }}
          </span>
          <small>{{ summarizeNaturalText(row.row.assertions, 1) }}</small>
        </div>
      </template>
    </el-table-column>
    <el-table-column prop="row.priority" label="优先级" width="78" />
    <el-table-column prop="row.sourceFile" label="来源" min-width="110" show-overflow-tooltip />
    <el-table-column label="操作" width="112" fixed="right">
      <template #default="{ row }">
        <div class="studio-case-table-actions">
          <el-button link :aria-label="`查看 ${row.row.caseId}`" @click.stop="emit('preview', row)"><Eye :size="14" /></el-button>
          <el-button link :aria-label="`编辑 ${row.row.caseId}`" @click.stop="emit('edit', row)"><Pencil :size="14" /></el-button>
          <el-button link type="danger" :aria-label="`删除 ${row.row.caseId}`" @click.stop="emit('remove', row)"><Trash2 :size="14" /></el-button>
        </div>
      </template>
    </el-table-column>
  </el-table>
  <el-dialog
    :model-value="evidenceVisible"
    title="页面证据"
    width="520px"
    :close-on-click-modal="false"
    :teleported="false"
    @update:model-value="evidenceVisible = $event"
  >
    <div class="studio-evidence-dialog" data-testid="evidence-dialog">
      <div v-if="evidenceLoading" class="studio-evidence-dialog__hint">加载中…</div>
      <div v-else-if="evidenceError" class="studio-evidence-dialog__error" role="alert">{{ evidenceError }}</div>
      <template v-else>
        <p v-if="evidenceCase?.metadata.agentEvidenceIds?.length" class="studio-evidence-dialog__hint">
          该用例基于 {{ evidenceCase.metadata.agentEvidenceIds.length }} 条页面证据生成
        </p>
        <div v-if="evidenceEntries.length" class="studio-evidence-dialog__list">
          <div v-for="entry in evidenceEntries" :key="entry.entryId" class="studio-evidence-dialog__item">
            <code>{{ entry.locator }}</code>
            <span>{{ entry.pageName || '未分页面' }} · {{ entry.roleName || entry.elementType || '元素' }}</span>
          </div>
        </div>
        <p v-else-if="evidenceCase?.metadata.agentEvidenceIds?.length" class="studio-evidence-dialog__hint">
          素材库未找到对应证据条目：{{ evidenceCase.metadata.agentEvidenceIds.join('、') }}
        </p>
      </template>
    </div>
    <template #footer>
      <el-button @click="evidenceVisible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.studio-generated-case-table {
  min-width: 0;
}

.studio-generated-case-table :deep(.el-scrollbar__bar) {
  opacity: 1;
}

.studio-generated-case-table :deep(.el-scrollbar__bar.is-horizontal) {
  bottom: 2px;
}

.studio-generated-case-table :deep(.el-scrollbar__bar.is-vertical) {
  right: 2px;
}

.studio-generated-case-table__case,
.studio-generated-case-table__design,
.studio-generated-case-table__automation {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.studio-generated-case-table__case strong {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 13px;
  font-weight: var(--font-weight-semibold);
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.studio-generated-case-table__case small,
.studio-generated-case-table__automation small {
  overflow: hidden;
  color: var(--text-tertiary);
  font-size: 11px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.studio-generated-case-table__id {
  color: var(--brand-primary);
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: var(--font-weight-semibold);
}

.studio-generated-case-table__design {
  color: var(--text-primary);
  font-size: 12px;
  line-height: 1.45;
}

.studio-generated-case-table__design span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.studio-generated-case-table__design b {
  display: inline-flex;
  margin-right: 6px;
  color: var(--text-tertiary);
  font-size: 11px;
  font-weight: 600;
}

.studio-generated-case-table__missing-design {
  color: var(--color-warning);
  font-size: 12px;
}

.studio-generated-case-table__automation {
  grid-template-columns: repeat(2, max-content);
  column-gap: 8px;
  row-gap: 3px;
  color: var(--text-regular);
  font-size: 12px;
}

.studio-generated-case-table__automation small {
  grid-column: 1 / -1;
}

.studio-generated-case-table__evidence {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  border: 0;
  border-radius: 999px;
  padding: 0 7px;
  color: var(--brand-primary);
  background: var(--brand-primary-soft);
  font-size: 11px;
  font-weight: var(--font-weight-semibold);
  cursor: pointer;
}

.studio-generated-case-table__evidence:hover {
  background: var(--brand-primary-subtle);
}

.studio-evidence-dialog {
  display: grid;
  gap: 10px;
}

.studio-evidence-dialog__hint {
  margin: 0;
  color: var(--text-tertiary);
  font-size: 12px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.studio-evidence-dialog__error {
  color: var(--color-danger);
  font-size: 12px;
}

.studio-evidence-dialog__list {
  display: grid;
  gap: 6px;
  max-height: 320px;
  overflow: auto;
}

.studio-evidence-dialog__item {
  display: grid;
  gap: 3px;
  padding: 7px 9px;
  border: 1px solid var(--border-default);
  border-radius: 8px;
  background: var(--surface-raised);
}

.studio-evidence-dialog__item code {
  overflow: hidden;
  color: var(--brand-primary);
  font-family: var(--font-mono);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.studio-evidence-dialog__item span {
  color: var(--text-tertiary);
  font-size: 11px;
}
</style>

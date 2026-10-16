<script setup lang="ts">
import { CircleCheck, Eye, Pencil, Save, Trash2, X } from '@lucide/vue'
import { computed, reactive, ref, watch } from 'vue'
import type { AiDraftTestPoint } from '../../domain/aiPlatformRepository'
import { formatNaturalLanguageText } from '../../domain/webCaseImport'

const props = defineProps<{
  points: AiDraftTestPoint[]
  selectedIds: string[]
  pendingCount: number
  confirming?: boolean
}>()

const emit = defineEmits<{
  updateSelection: [ids: string[]]
  confirmAll: []
  openDetails: [point: AiDraftTestPoint]
  confirmOne: [point: AiDraftTestPoint]
  deletePoint: [point: AiDraftTestPoint]
  editPoint: [payload: {
    point: AiDraftTestPoint
    title: string
    objective: string
    steps: string
    expectedResult: string
  }]
}>()

const PAGE_SIZE = 10
const currentPage = ref(1)
const moduleFilter = ref('ALL')
const statusFilter = ref('ALL')
const keyword = ref('')
const detailsVisible = ref(false)
const detailsEditing = ref(false)
const selectedPoint = ref<AiDraftTestPoint>()
const editForm = reactive({ title: '', objective: '', steps: '', expectedResult: '' })
const modules = computed(() => Array.from(new Set(props.points.map((point) => point.module).filter(Boolean))))
const filteredPoints = computed(() => props.points.filter((point) => {
  if (moduleFilter.value !== 'ALL' && point.module !== moduleFilter.value) return false
  if (statusFilter.value !== 'ALL' && statusLabel(point) !== statusFilter.value) return false
  const query = keyword.value.trim().toLowerCase()
  return !query || [point.title, point.module, point.pageOrFeature, point.objective, point.sourceFile].join(' ').toLowerCase().includes(query)
}))
const pageCount = computed(() => Math.max(1, Math.ceil(filteredPoints.value.length / PAGE_SIZE)))
const visiblePoints = computed(() => {
  const start = (currentPage.value - 1) * PAGE_SIZE
  return filteredPoints.value.slice(start, start + PAGE_SIZE)
})
const confirmedCount = computed(() => props.points.filter((point) => point.reviewStatus === 'confirmed' || point.reviewStatus === 'modified').length)

watch(pageCount, (count) => {
  if (currentPage.value > count) currentPage.value = count
})
watch(() => props.points.length, () => {
  if (currentPage.value > pageCount.value) currentPage.value = pageCount.value
})
watch([moduleFilter, statusFilter, keyword], () => { currentPage.value = 1 })
watch(() => props.points, (nextPoints) => {
  if (!selectedPoint.value) return
  const latest = nextPoints.find((point) => point.pointId === selectedPoint.value?.pointId)
  if (latest) selectedPoint.value = latest
})

const isSelected = (pointId: string) => props.selectedIds.includes(pointId)
const toggleSelection = (pointId: string, checked: boolean) => {
  const selected = new Set(props.selectedIds)
  if (checked) selected.add(pointId)
  else selected.delete(pointId)
  emit('updateSelection', props.points.filter((point) => selected.has(point.pointId)).map((point) => point.pointId))
}
const showDetails = (point: AiDraftTestPoint) => {
  selectedPoint.value = point
  detailsEditing.value = false
  detailsVisible.value = true
  emit('openDetails', point)
}
const beginDetailsEditing = () => {
  if (!selectedPoint.value) return
  editForm.title = selectedPoint.value.title
  editForm.objective = selectedPoint.value.objective
  editForm.steps = selectedPoint.value.steps
  editForm.expectedResult = selectedPoint.value.expectedResult
  detailsEditing.value = true
}
const cancelDetailsEditing = () => {
  detailsEditing.value = false
}
const saveDetails = () => {
  if (!selectedPoint.value || !editForm.title.trim()) return
  emit('editPoint', {
    point: selectedPoint.value,
    title: editForm.title.trim(),
    objective: editForm.objective.trim(),
    steps: editForm.steps.trim(),
    expectedResult: editForm.expectedResult.trim()
  })
  detailsEditing.value = false
}
const statusLabel = (point: AiDraftTestPoint) => point.reviewStatus === 'rejected'
  ? '已拒绝'
  : point.reviewStatus === 'confirmed' || point.reviewStatus === 'modified'
    ? '已确认'
    : '待确认'
</script>

<template>
  <section class="studio-test-parse-stage" data-testid="test-parse-stage">
    <header class="studio-test-parse-stage__toolbar">
      <div class="studio-test-parse-stage__filters">
        <el-select v-model="moduleFilter" aria-label="测试点模块"><el-option label="全部模块" value="ALL" /><el-option v-for="module in modules" :key="module" :label="module" :value="module" /></el-select>
        <el-select v-model="statusFilter" aria-label="测试点状态"><el-option label="全部状态" value="ALL" /><el-option label="待确认" value="待确认" /><el-option label="已确认" value="已确认" /><el-option label="已拒绝" value="已拒绝" /></el-select>
        <el-input v-model="keyword" clearable placeholder="搜索测试点" aria-label="搜索测试点" />
      </div>
      <el-button type="primary" :loading="confirming" :disabled="pendingCount === 0" @click="emit('confirmAll')">
        <CircleCheck :size="15" />确认全部测试点
      </el-button>
    </header>
    <el-table v-if="points.length" class="studio-test-parse-stage__table" :data="visiblePoints" height="100%" row-key="pointId" size="small">
      <el-table-column width="42"><template #default="{ row }"><el-checkbox :model-value="isSelected(row.pointId)" :data-testid="`test-point-checkbox-${row.pointId}`" :aria-label="`选择${row.title}`" @change="toggleSelection(row.pointId, $event as boolean)" /></template></el-table-column>
      <el-table-column label="测试点" min-width="310" show-overflow-tooltip><template #default="{ row }"><div :data-testid="`test-point-row-${row.pointId}`" class="studio-test-parse-stage__item-main"><strong>{{ row.title }}</strong><small>{{ row.module || '未分模块' }} · {{ row.pageOrFeature || row.sourceDocumentId || '需求解析' }}</small></div></template></el-table-column>
      <el-table-column label="测试设计" min-width="380" show-overflow-tooltip><template #default="{ row }"><div class="studio-test-parse-stage__design"><span><b>目标</b>{{ row.objective || '-' }}</span><span><b>预期</b>{{ row.expectedResult || '-' }}</span></div></template></el-table-column>
      <el-table-column label="优先级" width="82"><template #default="{ row }">{{ row.priority || '-' }}</template></el-table-column>
      <el-table-column label="状态" width="88"><template #default="{ row }"><span class="studio-test-parse-stage__status">{{ statusLabel(row) }}</span></template></el-table-column>
      <el-table-column label="操作" width="150" fixed="right"><template #default="{ row }"><div class="studio-test-parse-stage__item-actions"><el-tooltip content="查看详情"><el-button text size="small" :data-testid="`test-point-detail-${row.pointId}`" aria-label="查看详情" @click.stop="showDetails(row)"><Eye :size="14" /></el-button></el-tooltip><el-button v-if="statusLabel(row) === '待确认'" text size="small" type="primary" :data-testid="`test-point-confirm-${row.pointId}`" :disabled="confirming" @click.stop="emit('confirmOne', row)"><CircleCheck :size="14" />确认</el-button><el-tooltip content="删除测试点"><el-button text size="small" type="danger" :data-testid="`test-point-delete-${row.pointId}`" aria-label="删除测试点" :disabled="confirming" @click.stop="emit('deletePoint', row)"><Trash2 :size="14" /></el-button></el-tooltip></div></template></el-table-column>
    </el-table>
    <el-pagination
      v-if="points.length > PAGE_SIZE"
      v-model:current-page="currentPage"
      class="studio-test-parse-stage__pagination"
      :page-size="PAGE_SIZE"
      :total="filteredPoints.length"
      layout="total, prev, pager, next"
      background
    />
    <el-empty v-if="points.length === 0" description="暂无测试点，请先完成需求解析" />
    <el-drawer v-model="detailsVisible" title="测试点详情" size="min(560px, 92vw)">
      <div v-if="selectedPoint" class="studio-test-point-detail">
        <template v-if="!detailsEditing">
          <div data-testid="test-point-details-readonly" class="studio-test-point-detail__content">
            <section class="studio-test-point-detail__section">
              <div class="studio-test-point-detail__section-heading"><strong>基础信息</strong><span>{{ statusLabel(selectedPoint) }}</span></div>
              <dl class="studio-test-point-detail__meta">
                <div><dt>测试点</dt><dd>{{ selectedPoint.title }}</dd></div>
                <div><dt>模块</dt><dd>{{ selectedPoint.module || '未分模块' }}</dd></div>
                <div><dt>页面 / 功能</dt><dd>{{ selectedPoint.pageOrFeature || '-' }}</dd></div>
                <div><dt>类型</dt><dd>{{ selectedPoint.testType || '-' }}</dd></div>
                <div><dt>优先级</dt><dd>{{ selectedPoint.priority || '-' }}</dd></div>
                <div><dt>置信度</dt><dd>{{ Math.round(selectedPoint.confidenceScore * 100) }}%</dd></div>
              </dl>
            </section>
            <section class="studio-test-point-detail__section">
              <div class="studio-test-point-detail__section-heading"><strong>测试内容</strong></div>
              <div class="studio-test-point-detail__block"><span>测试目标</span><p>{{ selectedPoint.objective || '-' }}</p></div>
              <div class="studio-test-point-detail__block"><span>测试步骤</span><pre>{{ formatNaturalLanguageText(selectedPoint.steps) || '-' }}</pre></div>
              <div class="studio-test-point-detail__block"><span>预期结果</span><pre>{{ formatNaturalLanguageText(selectedPoint.expectedResult) || '-' }}</pre></div>
            </section>
            <section class="studio-test-point-detail__section studio-test-point-detail__section--source">
              <div class="studio-test-point-detail__section-heading"><strong>来源证据</strong></div>
              <div class="studio-test-point-detail__block"><span>来源文件</span><p>{{ selectedPoint.sourceFile || '-' }}</p></div>
              <div class="studio-test-point-detail__block"><span>来源原文</span><pre>{{ selectedPoint.sourceText || '-' }}</pre></div>
            </section>
          </div>
          <div class="studio-test-point-detail__footer">
            <el-button type="primary" data-testid="test-point-edit-details" @click="beginDetailsEditing"><Pencil :size="14" />编辑详情</el-button>
          </div>
        </template>
        <template v-else>
          <el-form label-position="top" class="studio-test-point-detail__form" @submit.prevent="saveDetails">
            <el-form-item label="测试点标题" required>
              <el-input v-model="editForm.title" data-testid="test-point-edit-title" maxlength="200" show-word-limit />
            </el-form-item>
            <el-form-item label="测试目标">
              <el-input v-model="editForm.objective" data-testid="test-point-edit-objective" type="textarea" :rows="3" />
            </el-form-item>
            <el-form-item label="测试步骤">
              <el-input v-model="editForm.steps" data-testid="test-point-edit-steps" type="textarea" :rows="6" />
            </el-form-item>
            <el-form-item label="预期结果">
              <el-input v-model="editForm.expectedResult" data-testid="test-point-edit-expected-result" type="textarea" :rows="5" />
            </el-form-item>
          </el-form>
          <div class="studio-test-point-detail__footer">
            <el-button data-testid="test-point-cancel-details" @click="cancelDetailsEditing"><X :size="14" />取消</el-button>
            <el-button type="primary" data-testid="test-point-save-details" :disabled="!editForm.title.trim()" @click="saveDetails"><Save :size="14" />保存修改</el-button>
          </div>
        </template>
      </div>
    </el-drawer>
  </section>
</template>

<style scoped>
.studio-test-parse-stage { display: grid; height: calc(100% - 20px); min-height: 0; box-sizing: border-box; grid-template-rows: auto minmax(0, 1fr) auto; margin: 14px 16px 6px; overflow: hidden; background: var(--surface-raised); border: 1px solid var(--border-default); border-radius: 8px; }
.studio-test-parse-stage__toolbar { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 12px; padding: 10px 16px; border-bottom: 1px solid var(--border-subtle); }
.studio-test-parse-stage__filters { display: grid; grid-template-columns: minmax(120px, 180px) minmax(120px, 180px) minmax(180px, 1fr); gap: 8px; min-width: 0; }
.studio-test-parse-stage__table { width: 100%; min-height: 0; border: 0; }
.studio-test-parse-stage__table :deep(.el-table__header th) { color: var(--text-secondary); background: var(--surface-page); font-size: 11px; font-weight: var(--font-weight-medium); }
.studio-test-parse-stage__item-main { display: grid; gap: 4px; min-width: 0; }
.studio-test-parse-stage__item-main strong { overflow: hidden; color: var(--text-primary); font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.studio-test-parse-stage__item-main small { color: var(--text-tertiary); font-size: 11px; }
.studio-test-parse-stage__design { display: grid; gap: 4px; min-width: 0; color: var(--text-secondary); font-size: 12px; }.studio-test-parse-stage__design span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.studio-test-parse-stage__design b { margin-right: 6px; color: var(--text-tertiary); font-size: 11px; }
.studio-test-parse-stage__item-actions { display: inline-flex; width: 100%; min-width: 0; align-items: center; gap: 0; overflow: visible; }
.studio-test-parse-stage__item-actions :deep(.el-tooltip__trigger) { display: inline-flex; flex: none; }
.studio-test-parse-stage__item-actions .el-button { flex: none; gap: 3px; min-width: 28px; padding-inline: 4px; }
.studio-test-parse-stage__item-actions .el-button--primary { padding-inline: 6px; }
.studio-test-parse-stage__status { color: var(--text-secondary); font-size: 11px; white-space: nowrap; }
.studio-test-parse-stage__pagination { justify-content: flex-start; padding: 8px 12px; border-top: 1px solid var(--border-subtle); }
.studio-test-point-detail { display: grid; min-height: 100%; grid-template-rows: minmax(0, 1fr) auto; gap: 18px; }
.studio-test-point-detail__content { display: grid; align-content: start; gap: 18px; }
.studio-test-point-detail__section { display: grid; gap: 12px; padding-bottom: 18px; border-bottom: 1px solid var(--border-subtle); }
.studio-test-point-detail__section--source { border-bottom: 0; }
.studio-test-point-detail__section-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; color: var(--text-primary); font-size: 13px; }
.studio-test-point-detail__section-heading > span { color: var(--color-success); font-size: 11px; }
.studio-test-point-detail__meta { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px 16px; margin: 0; }
.studio-test-point-detail__meta div { min-width: 0; }
.studio-test-point-detail__meta dt, .studio-test-point-detail__block > span { margin-bottom: 5px; color: var(--text-tertiary); font-size: 11px; }
.studio-test-point-detail__meta dd { margin: 0; overflow-wrap: anywhere; color: var(--text-primary); font-size: 13px; line-height: 1.45; }
.studio-test-point-detail__block { display: grid; gap: 2px; }
.studio-test-point-detail__block p, .studio-test-point-detail__block pre { margin: 0; color: var(--text-primary); font-size: 13px; line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere; }
.studio-test-point-detail__block pre { max-height: 180px; overflow: auto; padding: 10px 12px; border: 1px solid var(--border-subtle); border-radius: 6px; background: var(--surface-subtle); font: inherit; }
.studio-test-point-detail__footer { display: flex; justify-content: flex-end; gap: 8px; padding-top: 14px; border-top: 1px solid var(--border-subtle); }
.studio-test-point-detail__footer .el-button { gap: 5px; }
.studio-test-point-detail__form { padding-right: 4px; }

@media (max-width: 720px) {
  .studio-test-parse-stage__toolbar { grid-template-columns: 1fr; }.studio-test-parse-stage__filters { grid-template-columns: repeat(2, minmax(0, 1fr)); }.studio-test-parse-stage__filters .el-input { grid-column: 1 / -1; }
  .studio-test-point-detail__meta { grid-template-columns: 1fr; }
}
</style>

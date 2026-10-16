<script setup lang="ts">
import { computed, ref } from 'vue'
import { ChevronDown, Eye, Sparkles } from '@lucide/vue'
import type { AgentConflictView } from '../../domain/aiMultiAgentRunView'
import type { CaseReviewDecision } from '../../domain/caseDesignDraftRepository'
import type { CaseDesignReviewFindingView } from '../../domain/caseDesignReviewView'
import ReviewFindingList, { type ReviewFindingCardView } from './ReviewFindingList.vue'

const props = defineProps<{
  selectedCount: number
  highRiskCount: number
  pendingFindingCount: number
  averageScore: number
  summary: string
  findings: CaseDesignReviewFindingView[]
  conflicts: AgentConflictView[]
  canMerge: boolean
  loading?: boolean
}>()

const emit = defineEmits<{
  ignoreConflicts: []
  ignoreConflict: [conflictId: string]
  merge: []
  decide: [findingId: string, decision: CaseReviewDecision]
  bulkDecide: [decision: CaseReviewDecision]
}>()

const decisionLabel = (decision: CaseReviewDecision): string => ({
  pending: '待确认',
  accepted: '已采纳',
  modified: '已修改',
  ignored: '已忽略'
}[decision])

const findingData = (card: ReviewFindingCardView): CaseDesignReviewFindingView =>
  card.data as CaseDesignReviewFindingView

const reviewCards = computed<ReviewFindingCardView[]>(() =>
  props.findings.filter((finding) => finding.decision !== 'accepted').map((finding) => ({
    id: finding.findingId,
    tone: finding.category === 'coverage_gap' ? 'danger' : 'primary',
    badge: finding.category === 'coverage_gap' ? '覆盖缺口' : '改进建议',
    meta: [finding.priority, decisionLabel(finding.decision)].filter((item): item is string => Boolean(item)),
    title: finding.title,
    detail: finding.detail,
    data: finding
  }))
)
const conflictsExpanded = ref(true)
const findingsExpanded = ref(true)
const conflictDetailsVisible = ref(false)
const selectedConflict = ref<AgentConflictView>()
const openConflictDetails = (conflict: AgentConflictView) => {
  selectedConflict.value = conflict
  conflictDetailsVisible.value = true
}
</script>

<template>
  <aside class="studio-review-inspector" aria-label="AI 质量评审">
    <header class="studio-review-inspector__header">
      <div><Sparkles :size="16" aria-hidden="true" /><strong>AI 质量评审</strong></div>
    </header>

    <div class="studio-review-inspector__score">
      <span>整体质量</span>
      <strong>{{ averageScore }}%</strong>
      <small>{{ highRiskCount }} 项高风险或待处理发现</small>
      <small>{{ pendingFindingCount }} 项评审内容待确认</small>
      <p v-if="summary" class="studio-review-inspector__summary" :title="summary">{{ summary }}</p>
    </div>

    <div class="studio-review-inspector__sections">
      <section class="studio-review-inspector__section">
        <header><button type="button" class="studio-review-inspector__collapse" :aria-expanded="conflictsExpanded" @click="conflictsExpanded = !conflictsExpanded"><ChevronDown :size="14" :class="{ 'is-collapsed': !conflictsExpanded }" /><strong>待处理冲突</strong></button><span>{{ conflicts.filter((item) => item.resolutionStatus === 'unresolved').length }}</span></header>
        <div v-if="conflictsExpanded">
        <article v-for="conflict in conflicts" :key="conflict.conflictId" class="studio-review-inspector__conflict">
          <button type="button" class="studio-review-inspector__conflict-detail" @click="openConflictDetails(conflict)"><div><strong>{{ conflict.conflictType }}</strong><p>{{ conflict.targetKey }}</p></div><Eye :size="14" aria-hidden="true" /></button>
          <el-button
            v-if="conflict.resolutionStatus === 'unresolved'"
            link
            :data-testid="`ignore-conflict-${conflict.conflictId}`"
            @click="emit('ignoreConflict', conflict.conflictId)"
          >
            忽略
          </el-button>
        </article>
        <p v-if="conflicts.length === 0" class="studio-review-inspector__empty">当前没有待处理冲突</p>
        </div>
      </section>

      <section class="studio-review-inspector__findings-section">
        <header class="studio-review-inspector__findings-header"><button type="button" class="studio-review-inspector__collapse" :aria-expanded="findingsExpanded" @click="findingsExpanded = !findingsExpanded"><ChevronDown :size="14" :class="{ 'is-collapsed': !findingsExpanded }" /><strong>覆盖缺口与改进建议</strong></button><span>{{ reviewCards.length }}</span></header>
      <ReviewFindingList v-if="findingsExpanded" title="" count-text="" :findings="reviewCards" empty-text="选择用例后将在这里呈现相关证据和建议">
        <template #actions="{ finding }">
          <el-button link :data-testid="`accept-finding-${finding.id}`" @click="emit('decide', findingData(finding).findingId, 'accepted')">采纳</el-button>
          <el-button link @click="emit('decide', findingData(finding).findingId, 'modified')">修改</el-button>
          <el-button link @click="emit('decide', findingData(finding).findingId, 'pending')">待确认</el-button>
          <el-button link @click="emit('decide', findingData(finding).findingId, 'ignored')">忽略</el-button>
        </template>
      </ReviewFindingList>
      </section>
    </div>
    <el-dialog v-model="conflictDetailsVisible" title="冲突详情" width="min(460px, 88vw)" :teleported="false">
      <el-descriptions v-if="selectedConflict" :column="1" border>
        <el-descriptions-item label="冲突类型">{{ selectedConflict.conflictType }}</el-descriptions-item>
        <el-descriptions-item label="目标">{{ selectedConflict.targetKey }}</el-descriptions-item>
        <el-descriptions-item label="冲突内容"><pre>{{ JSON.stringify(selectedConflict.conflictJson || {}, null, 2) }}</pre></el-descriptions-item>
        <el-descriptions-item label="处理状态">{{ selectedConflict.resolutionStatus }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>


    <footer class="studio-review-inspector__footer">
      <el-button v-if="selectedCount > 1" text data-testid="bulk-pending" @click="emit('bulkDecide', 'pending')">
        批量标记待确认（{{ selectedCount }}）
      </el-button>
      <el-button v-if="selectedCount > 1" text @click="emit('bulkDecide', 'accepted')">批量采纳</el-button>
      <el-button
        text
        data-testid="ignore-conflicts"
        :disabled="!conflicts.some((item) => item.resolutionStatus === 'unresolved')"
        @click="emit('ignoreConflicts')"
      >
        忽略未处理冲突
      </el-button>
      <el-button type="primary" plain data-testid="merge-review" :loading="loading" :disabled="!canMerge || loading" @click="emit('merge')">对齐补充</el-button>
    </footer>
  </aside>
</template>

<style scoped>
.studio-review-inspector { display: grid; height: 100%; min-height: 0; grid-template-rows: auto auto minmax(0, 1fr) auto; color: var(--text-secondary); background: var(--surface-raised); font-size: 12px; }
.studio-review-inspector__header, .studio-review-inspector__section > header { display: flex; align-items: center; justify-content: space-between; }
.studio-review-inspector__header { min-height: 52px; padding: 0 13px; border-bottom: 1px solid var(--border-subtle); }
.studio-review-inspector__header > div { display: inline-flex; align-items: center; gap: 7px; color: var(--brand-primary-active); }.studio-review-inspector__header strong { color: var(--text-primary); font-size: 13px; font-weight: 600; }
.studio-review-inspector__score { display: grid; gap: 5px; padding: 12px 13px; border-bottom: 1px solid var(--border-subtle); }.studio-review-inspector__score > span { color: var(--text-secondary); font-size: 11px; }
.studio-review-inspector__score { grid-template-columns: minmax(0, 1fr) auto; background: var(--brand-primary-subtle); }.studio-review-inspector__score strong { grid-row: span 2; align-self: center; color: var(--brand-primary); font-size: 16px; font-weight: 600; line-height: 1.2; }.studio-review-inspector__score small { color: var(--text-secondary); font-size: 10px; }.studio-review-inspector__score p { grid-column: 1 / -1; display: -webkit-box; max-height: 6em; margin: 3px 0 0; overflow: hidden; color: var(--text-secondary); line-height: 1.5; -webkit-box-orient: vertical; -webkit-line-clamp: 4; overflow-wrap: anywhere; }
.studio-review-inspector__sections { display: grid; min-height: 0; grid-template-rows: minmax(170px, auto) minmax(360px, 1fr); overflow: hidden; }
.studio-review-inspector__sections :deep(.review-finding-list) { min-height: 100%; box-sizing: border-box; padding: 11px 13px; align-content: start; }
.studio-review-inspector__sections :deep(.review-finding-list__body) { min-height: 0; }
.studio-review-inspector__section, .studio-review-inspector__findings-section { padding: 11px 13px; border-bottom: 1px solid var(--border-subtle); }
.studio-review-inspector__section { max-height: 260px; overflow: auto; }
.studio-review-inspector__findings-section { display: grid; min-height: 0; grid-template-rows: auto minmax(0, 1fr); overflow: hidden; }
.studio-review-inspector__section > header, .studio-review-inspector__findings-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; color: var(--text-secondary); font-size: 11px; }
.studio-review-inspector__section > header > span, .studio-review-inspector__findings-header > span { display: grid; min-width: 18px; height: 18px; place-items: center; padding: 0 5px; color: var(--brand-primary); background: var(--brand-primary-soft); border-radius: 5px; font-size: 10px; }
.studio-review-inspector__collapse { display: inline-flex; align-items: center; gap: 4px; padding: 0; color: var(--text-regular); background: transparent; border: 0; cursor: pointer; font: inherit; font-weight: 600; }
.studio-review-inspector__collapse svg { transition: transform 160ms ease; }.studio-review-inspector__collapse svg.is-collapsed { transform: rotate(-90deg); }
.studio-review-inspector__conflict { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 9px; background: var(--surface-subtle); border: 1px solid var(--surface-page); border-radius: 6px; }.studio-review-inspector__conflict + .studio-review-inspector__conflict { margin-top: 7px; }
.studio-review-inspector__conflict-detail { display: flex; min-width: 0; flex: 1; align-items: center; justify-content: space-between; gap: 8px; padding: 0; color: inherit; text-align: left; background: transparent; border: 0; cursor: pointer; }.studio-review-inspector__conflict-detail > div { min-width: 0; }.studio-review-inspector__conflict-detail > svg { flex: none; color: var(--text-tertiary); }.studio-review-inspector__conflict strong { color: var(--text-regular); font-size: 11px; font-weight: 600; }.studio-review-inspector__conflict p { overflow: hidden; margin: 3px 0 0; color: var(--text-secondary); font-size: 10px; line-height: 1.45; text-overflow: ellipsis; white-space: nowrap; }.studio-review-inspector__conflict .el-button { height: 22px; padding: 0 3px; font-size: 10px; }
.studio-review-inspector__findings-section :deep(.review-finding-list) { display: grid; min-height: 0; height: 100%; grid-template-rows: minmax(0, 1fr); padding: 0; overflow: hidden; }.studio-review-inspector__findings-section :deep(.review-finding-list__header) { display: none; }.studio-review-inspector__findings-section :deep(.review-finding-list__body) { min-height: 0; align-content: start; overflow-y: auto; padding-right: 3px; }.studio-review-inspector__findings-section :deep(.review-finding-card__title) { font-size: 11px; }.studio-review-inspector__findings-section :deep(.review-finding-card__detail) { font-size: 10px; }
.studio-review-inspector pre { max-height: 240px; margin: 0; overflow: auto; white-space: pre-wrap; word-break: break-word; font: inherit; }
.studio-review-inspector__empty { margin: 0; color: var(--text-secondary); font-size: 11px; line-height: 1.5; }
.studio-review-inspector__footer { display: flex; flex-wrap: wrap; gap: 3px; padding: 9px 10px; border-top: 1px solid var(--border-subtle); background: var(--surface-raised); }.studio-review-inspector__footer .el-button { height: 26px; margin: 0; padding: 0 6px; font-size: 10px; }
</style>

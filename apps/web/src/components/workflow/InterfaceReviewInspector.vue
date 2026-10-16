<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { EyeOff, LocateFixed, Plus, RefreshCcw, Sparkles } from '@lucide/vue'
import type { CaseReviewFinding, ReviewSeverity } from '../../domain/caseReview'
import ReviewFindingList, { type ReviewFindingCardView, type ReviewFindingTone } from './ReviewFindingList.vue'

interface ReviewSummary {
  total: number
  high: number
  medium: number
  low: number
  info: number
}

const props = defineProps<{
  findings: CaseReviewFinding[]
  summary: ReviewSummary
  reviewing: boolean
  reviewAttempted: boolean
}>()

const emit = defineEmits<{
  review: []
  supplementMissing: []
  locateFinding: [finding: CaseReviewFinding]
  ignoreFinding: [findingId: string]
}>()

const severityLabels: Record<ReviewSeverity, string> = {
  high: '高风险',
  medium: '中风险',
  low: '低风险',
  info: '信息'
}

const severityTone = (severity: ReviewSeverity): ReviewFindingTone =>
  ({ high: 'danger', medium: 'warning', low: 'info', info: 'success' } satisfies Record<ReviewSeverity, ReviewFindingTone>)[severity]

const ignoredFindingIds = ref(new Set<string>())
const visibleFindings = computed(() => props.findings.filter((finding) => !ignoredFindingIds.value.has(finding.id)))
const statusText = computed(() => {
  if (props.reviewing) return 'AI 正在评审'
  if (props.reviewAttempted) return '评审完成'
  return '尚未运行评审'
})

const canLocate = (finding: CaseReviewFinding) =>
  finding.code === 'weak_assertion' || finding.code === 'weak_expected_result'

const findingData = (card: ReviewFindingCardView): CaseReviewFinding =>
  card.data as CaseReviewFinding

const ignoreFinding = (finding: CaseReviewFinding) => {
  ignoredFindingIds.value.add(finding.id)
  emit('ignoreFinding', finding.id)
}

const reviewCards = computed<ReviewFindingCardView[]>(() =>
  visibleFindings.value.map((finding) => ({
    id: finding.id,
    tone: severityTone(finding.severity),
    badge: severityLabels[finding.severity],
    meta: [finding.scope],
    title: finding.message,
    showActions: finding.code !== 'review_passed',
    data: finding
  }))
)

watch(() => props.findings, () => {
  ignoredFindingIds.value.clear()
})
</script>

<template>
  <aside class="interface-review-inspector" aria-label="AI 质量评审">
    <header class="interface-review-inspector__header">
      <div>
        <Sparkles :size="16" aria-hidden="true" />
        <strong>AI 质量评审</strong>
      </div>
      <button
        type="button"
        data-testid="interface-review-action"
        :disabled="reviewing"
        :aria-label="reviewAttempted ? '重新运行 AI 评审' : '运行 AI 评审'"
        @click="emit('review')"
      >
        <RefreshCcw :size="15" aria-hidden="true" />
        <span>{{ reviewing ? '评审中' : reviewAttempted ? '重新评审' : '运行评审' }}</span>
      </button>
    </header>

    <section class="interface-review-inspector__status" aria-live="polite">
      <span>评审状态</span>
      <strong>{{ statusText }}</strong>
      <small v-if="reviewAttempted">共发现 {{ summary.total }} 项内容</small>
      <small v-else>运行评审后将在此呈现覆盖缺口和质量建议</small>
    </section>

    <dl class="interface-review-inspector__summary" aria-label="评审结果统计">
      <div class="is-high">
        <dt>高风险</dt>
        <dd>{{ summary.high }}</dd>
      </div>
      <div class="is-medium">
        <dt>中风险</dt>
        <dd>{{ summary.medium }}</dd>
      </div>
      <div class="is-low">
        <dt>低风险</dt>
        <dd>{{ summary.low }}</dd>
      </div>
      <div class="is-info">
        <dt>信息</dt>
        <dd>{{ summary.info }}</dd>
      </div>
    </dl>

    <section class="interface-review-inspector__findings">
      <ReviewFindingList
        title="评审发现"
        :count-text="`${visibleFindings.length} / ${summary.total}`"
        :findings="reviewCards"
        :empty-text="reviewAttempted ? '当前没有待展示的评审发现' : '尚无评审结果'"
      >
        <template #actions="{ finding }">
          <div class="interface-review-inspector__finding-actions">
            <button
              v-if="findingData(finding).code === 'missing_case_type'"
              type="button"
              :data-testid="`supplement-finding-${finding.id}`"
              @click="emit('supplementMissing')"
            >
              <Plus :size="14" aria-hidden="true" />
              <span>补充缺失用例</span>
            </button>
            <button
              v-if="canLocate(findingData(finding))"
              type="button"
              :data-testid="`locate-finding-${finding.id}`"
              @click="emit('locateFinding', findingData(finding))"
            >
              <LocateFixed :size="14" aria-hidden="true" />
              <span>定位用例</span>
            </button>
            <button
              type="button"
              :data-testid="`ignore-finding-${finding.id}`"
              @click="ignoreFinding(findingData(finding))"
            >
              <EyeOff :size="14" aria-hidden="true" />
              <span>忽略</span>
            </button>
          </div>
        </template>
      </ReviewFindingList>
    </section>

    <footer v-if="ignoredFindingIds.size > 0" class="interface-review-inspector__ignored" aria-live="polite">
      已忽略 {{ ignoredFindingIds.size }} 项，仅影响当前页面展示
    </footer>
  </aside>
</template>

<style scoped>
.interface-review-inspector {
  display: grid;
  height: 100%;
  min-height: 0;
  grid-template-rows: auto auto auto minmax(0, 1fr) auto;
  color: var(--text-regular);
  background: var(--surface-panel);
  font-family: var(--font-sans);
  font-size: var(--font-size-sm);
}

.interface-review-inspector__header,
.interface-review-inspector__header > div,
.interface-review-inspector__finding-actions {
  display: flex;
  align-items: center;
}

.interface-review-inspector__header {
  min-height: 52px;
  justify-content: space-between;
  gap: 10px;
  padding: 0 13px;
  border-bottom: 1px solid var(--border-subtle);
}

.interface-review-inspector__header > div {
  gap: 7px;
  color: var(--brand-primary);
}

.interface-review-inspector__header strong {
  color: var(--text-primary);
  font-weight: 600;
}

.interface-review-inspector button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-height: 28px;
  padding: 5px 8px;
  color: var(--brand-primary-active);
  background: var(--brand-primary-subtle);
  border: 1px solid var(--brand-primary-border);
  border-radius: var(--radius-control);
  font: inherit;
  cursor: pointer;
}

.interface-review-inspector button:hover:not(:disabled) {
  background: var(--brand-primary-soft);
}

.interface-review-inspector button:focus-visible {
  outline: 2px solid var(--focus-ring-color);
  outline-offset: 2px;
}

.interface-review-inspector button:disabled {
  color: var(--text-placeholder);
  background: var(--surface-subtle);
  border-color: var(--border-default);
  cursor: wait;
}

.interface-review-inspector__status {
  display: grid;
  gap: 4px;
  padding: 13px;
  background: var(--brand-primary-subtle);
  border-bottom: 1px solid var(--border-subtle);
}

.interface-review-inspector__status span,
.interface-review-inspector__status small {
  color: var(--text-secondary);
}

.interface-review-inspector__status strong {
  color: var(--brand-primary-active);
  font-size: var(--font-size-lg);
  font-weight: 600;
}

.interface-review-inspector__summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  margin: 0;
  background: var(--border-subtle);
  border-bottom: 1px solid var(--border-subtle);
}

.interface-review-inspector__summary div {
  display: grid;
  gap: 2px;
  justify-items: center;
  padding: 9px 4px;
  background: var(--surface-panel);
}

.interface-review-inspector__summary dt,
.interface-review-inspector__summary dd {
  margin: 0;
}

.interface-review-inspector__summary dt {
  color: var(--text-secondary);
}

.interface-review-inspector__summary dd {
  color: var(--text-primary);
  font-weight: var(--font-weight-semibold);
}

.interface-review-inspector__findings {
  min-height: 0;
  padding: 12px 13px;
  overflow-y: auto;
}

.interface-review-inspector__finding-actions {
  flex-wrap: wrap;
  gap: 5px;
}

.interface-review-inspector__finding-actions button {
  min-height: 24px;
  padding: 3px 6px;
  background: transparent;
  border-color: transparent;
  font-size: var(--font-size-xs);
}

.interface-review-inspector__ignored {
  padding: 9px 13px;
  color: var(--text-secondary);
  background: var(--surface-subtle);
  border-top: 1px solid var(--border-subtle);
  font-size: var(--font-size-xs);
}
</style>

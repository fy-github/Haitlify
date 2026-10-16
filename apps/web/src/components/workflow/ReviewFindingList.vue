<script setup lang="ts">
export type ReviewFindingTone = 'danger' | 'warning' | 'info' | 'success' | 'primary'

export interface ReviewFindingCardView {
  id: string
  tone: ReviewFindingTone
  /** 徽章文案(高风险 / 覆盖缺口 / 改进建议 …) */
  badge: string
  /** 徽章右侧的次要元信息(范围 / 优先级 / 决策状态等),右对齐省略 */
  meta?: string[]
  title: string
  detail?: string
  /** 是否渲染动作区(供插槽动作回传原始 finding) */
  showActions?: boolean
  /** 携带原始 finding,供插槽动作回传 */
  data?: unknown
}

withDefaults(defineProps<{
  findings: ReviewFindingCardView[]
  title: string
  countText?: string
  emptyText?: string
}>(), {
  countText: '',
  emptyText: '暂无发现'
})
</script>

<template>
  <section class="review-finding-list" aria-label="评审发现">
    <header class="review-finding-list__header">
      <strong>{{ title }}</strong>
      <span v-if="countText">{{ countText }}</span>
    </header>
    <div class="review-finding-list__body">
      <article
        v-for="finding in findings"
        :key="finding.id"
        class="review-finding-card"
        :class="`is-${finding.tone}`"
      >
        <div class="review-finding-card__meta">
          <span class="review-finding-card__badge" :class="`is-${finding.tone}`">{{ finding.badge }}</span>
          <small v-for="item in finding.meta || []" :key="item">{{ item }}</small>
        </div>
        <strong class="review-finding-card__title">{{ finding.title }}</strong>
        <p v-if="finding.detail" class="review-finding-card__detail">{{ finding.detail }}</p>
        <div v-if="finding.showActions !== false && $slots.actions" class="review-finding-card__actions">
          <slot name="actions" :finding="finding" />
        </div>
      </article>
      <p v-if="findings.length === 0" class="review-finding-list__empty">{{ emptyText }}</p>
    </div>
  </section>
</template>

<style scoped>
.review-finding-list {
  display: grid;
  min-width: 0;
  gap: 8px;
}

.review-finding-list__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--text-secondary);
  font-size: var(--font-size-xs);
}

.review-finding-list__header strong {
  color: var(--text-primary);
  font-weight: 600;
}

.review-finding-list__header span {
  display: grid;
  min-width: 18px;
  height: 18px;
  place-items: center;
  padding: 0 5px;
  color: var(--brand-primary);
  background: var(--brand-primary-soft);
  border-radius: 5px;
  font-size: 10px;
}

.review-finding-list__body {
  display: grid;
  gap: 7px;
}

.review-finding-card {
  padding: 9px;
  background: var(--surface-subtle);
  border: 1px solid var(--border-subtle);
  border-left-width: 3px;
  border-radius: var(--radius-control);
}

.review-finding-card.is-danger { border-left-color: var(--status-danger); }
.review-finding-card.is-warning { border-left-color: var(--status-warning); }
.review-finding-card.is-info { border-left-color: var(--status-info); }
.review-finding-card.is-success { border-left-color: var(--status-success); }
.review-finding-card.is-primary { border-left-color: var(--brand-primary); }

.review-finding-card__meta {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-bottom: 5px;
}

.review-finding-card__meta small {
  overflow: hidden;
  margin-left: auto;
  color: var(--text-secondary);
  font-size: var(--font-size-xs);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.review-finding-card__badge {
  padding: 2px 5px;
  border-radius: 4px;
  font-size: var(--font-size-xs);
  font-weight: 600;
}

.review-finding-card__badge.is-danger { color: var(--status-danger-text); background: var(--status-danger-soft); }
.review-finding-card__badge.is-warning { color: var(--status-warning-text); background: var(--status-warning-soft); }
.review-finding-card__badge.is-info { color: var(--status-info-text); background: var(--status-info-soft); }
.review-finding-card__badge.is-success { color: var(--status-success-text); background: var(--status-success-soft); }
.review-finding-card__badge.is-primary { color: var(--brand-primary-active); background: var(--brand-primary-subtle); }

.review-finding-card__title {
  display: block;
  color: var(--text-primary);
  font-size: var(--font-size-sm);
  font-weight: 600;
  line-height: 1.45;
}

.review-finding-card__detail {
  margin: 4px 0 0;
  color: var(--text-regular);
  font-size: var(--font-size-xs);
  line-height: 1.5;
}

.review-finding-card__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 6px;
}

.review-finding-list__empty {
  margin: 0;
  padding: 14px 0;
  color: var(--text-secondary);
  font-size: var(--font-size-sm);
  text-align: center;
}
</style>

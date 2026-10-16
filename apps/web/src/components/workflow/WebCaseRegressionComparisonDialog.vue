<script setup lang="ts">
import { ref, watch } from 'vue'
import { GitCompareArrows } from '@lucide/vue'
import type { WebCaseAgentRun, WebCaseRegressionComparison, WebCaseRegressionPointComparison } from '../../domain/webCaseAgentRepository'
import { getWebCaseRegressionComparison } from '../../domain/webCaseAgentRepository'

const props = defineProps<{ run?: WebCaseAgentRun; visible: boolean }>()
const emit = defineEmits<{ 'update:visible': [boolean] }>()

const loading = ref(false)
const comparison = ref<WebCaseRegressionComparison | null>(null)
const error = ref('')

const formatRecallRatio = (ratio: number): string => `${Math.round(ratio * 100)}%`

const load = async () => {
  if (!props.run?.runId) return
  loading.value = true
  comparison.value = null
  error.value = ''
  try {
    comparison.value = await getWebCaseRegressionComparison(props.run.runId)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '回归集对比加载失败'
  } finally {
    loading.value = false
  }
}

watch(() => props.visible, (visible) => {
  if (!visible) return
  load()
}, { immediate: true })

const pointSummary = (point: WebCaseRegressionPointComparison): string => {
  const parts = [`基线 ${point.baselineCount}`]
  if (point.recalledCount > 0) parts.push(`召回 ${point.recalledCount}`)
  if (point.addedCount > 0) parts.push(`新增 ${point.addedCount}`)
  if (point.lostCount > 0) parts.push(`丢失 ${point.lostCount}`)
  return parts.join(' · ')
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="回归集对比"
    width="560px"
    :close-on-click-modal="false"
    :teleported="false"
    @update:model-value="(value: boolean) => emit('update:visible', value)"
  >
    <div class="regression-comparison-dialog" data-testid="regression-comparison-dialog">
      <div v-if="loading" class="regression-comparison-dialog__hint" data-testid="regression-comparison-loading">加载中…</div>
      <div v-else-if="error" class="regression-comparison-dialog__error" role="alert">{{ error }}</div>
      <div v-else-if="!comparison || !comparison.available" class="regression-comparison-dialog__hint" data-testid="regression-comparison-empty">暂无已固化的基准 run 可对比</div>
      <template v-else>
        <div v-if="comparison.summary" class="regression-comparison-dialog__summary">
          <span class="regression-comparison-dialog__badge is-recalled">召回 {{ comparison.summary.recalled }}</span>
          <span class="regression-comparison-dialog__badge is-added">新增 {{ comparison.summary.added }}</span>
          <span class="regression-comparison-dialog__badge is-lost">丢失 {{ comparison.summary.lost }}</span>
          <span class="regression-comparison-dialog__ratio"><GitCompareArrows :size="13" />召回率 {{ formatRecallRatio(comparison.summary.recallRatio) }}</span>
        </div>
        <div class="regression-comparison-dialog__points">
          <div v-for="point in comparison.points" :key="point.pointId" class="regression-comparison-dialog__point">
            <div class="regression-comparison-dialog__point-head">
              <strong>{{ point.pointId }}</strong>
              <span>{{ pointSummary(point) }}</span>
            </div>
            <div v-if="point.lost.length" class="regression-comparison-dialog__case-group">
              <span class="regression-comparison-dialog__case-label is-lost">丢失</span>
              <div v-for="(lostCase, index) in point.lost" :key="index" class="regression-comparison-dialog__case">
                <span class="regression-comparison-dialog__case-title">{{ lostCase.title }}</span>
                <span v-if="lostCase.assertions" class="regression-comparison-dialog__case-assertions">{{ lostCase.assertions }}</span>
              </div>
            </div>
            <div v-if="point.added.length" class="regression-comparison-dialog__case-group">
              <span class="regression-comparison-dialog__case-label is-added">新增</span>
              <div v-for="(addedCase, index) in point.added" :key="index" class="regression-comparison-dialog__case">
                <span class="regression-comparison-dialog__case-title">{{ addedCase.title }}</span>
                <span v-if="addedCase.assertions" class="regression-comparison-dialog__case-assertions">{{ addedCase.assertions }}</span>
              </div>
            </div>
          </div>
        </div>
      </template>
    </div>
    <template #footer>
      <el-button @click="emit('update:visible', false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.regression-comparison-dialog { display: grid; gap: 14px; }
.regression-comparison-dialog__hint { color: var(--text-tertiary); font-size: 13px; }
.regression-comparison-dialog__error { color: var(--color-danger); font-size: 12px; }
.regression-comparison-dialog__summary { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.regression-comparison-dialog__badge { border-radius: 999px; padding: 2px 9px; font-size: 12px; font-weight: 600; }
.regression-comparison-dialog__badge.is-recalled { color: var(--brand-primary); background: var(--brand-primary-soft); }
.regression-comparison-dialog__badge.is-added { color: var(--color-success); background: var(--color-success-soft); }
.regression-comparison-dialog__badge.is-lost { color: var(--color-danger); background: var(--color-danger-soft); }
.regression-comparison-dialog__ratio { display: inline-flex; align-items: center; gap: 5px; margin-left: auto; color: var(--text-primary); font-size: 13px; font-weight: 600; }
.regression-comparison-dialog__points { display: grid; gap: 10px; max-height: 320px; overflow: auto; }
.regression-comparison-dialog__point { display: grid; gap: 8px; border: 1px solid rgba(194, 213, 225, .86); border-radius: 8px; padding: 9px 10px; background: var(--surface-raised); }
.regression-comparison-dialog__point-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.regression-comparison-dialog__point-head strong { color: var(--text-primary); font-size: 13px; font-weight: var(--font-weight-semibold); }
.regression-comparison-dialog__point-head span { color: var(--text-tertiary); font-size: 12px; }
.regression-comparison-dialog__case-group { display: grid; gap: 5px; padding-left: 8px; border-left: 2px solid rgba(194, 213, 225, .72); }
.regression-comparison-dialog__case-label { font-size: 11px; font-weight: 600; }
.regression-comparison-dialog__case-label.is-lost { color: var(--color-danger); }
.regression-comparison-dialog__case-label.is-added { color: var(--color-success); }
.regression-comparison-dialog__case { display: grid; gap: 2px; }
.regression-comparison-dialog__case-title { color: var(--text-primary); font-size: 12px; }
.regression-comparison-dialog__case-assertions { color: var(--text-tertiary); font-size: 11px; line-height: 1.45; }
</style>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { TrendingUp } from '@lucide/vue'
import { getWebCaseQualityInsight, type WebCaseQualityInsight } from '../../domain/webCaseAgentRepository'

const props = defineProps<{ projectId: number; visible: boolean }>()
const emit = defineEmits<{ 'update:visible': [boolean] }>()

const DIMENSION_LABELS: Record<string, string> = {
  coverage: '覆盖度',
  source_traceability: '来源可追溯',
  testability: '可测试性',
  duplicate_risk: '去重质量',
  automation_readiness: '自动化就绪',
  review_risk: '评审把握'
}
const DIMENSION_ORDER = ['coverage', 'source_traceability', 'testability', 'duplicate_risk', 'automation_readiness', 'review_risk']

const loading = ref(false)
const insight = ref<WebCaseQualityInsight | null>(null)
const error = ref('')
const weakKeys = computed(() => new Set((insight.value?.weakestDimensions || []).map((item) => item.key)))
const barWidth = (avg: number) => `${Math.max(0, Math.min(100, Math.round(avg * 10)))}%`

const fetchInsight = async () => {
  if (!props.projectId) return
  loading.value = true
  insight.value = null
  error.value = ''
  try {
    insight.value = await getWebCaseQualityInsight(props.projectId)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '质量洞察加载失败'
  } finally {
    loading.value = false
  }
}

watch(() => props.visible, (visible) => {
  if (visible) void fetchInsight()
}, { immediate: true })
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="质量洞察"
    width="520px"
    :close-on-click-modal="false"
    :teleported="false"
    @update:model-value="(value: boolean) => emit('update:visible', value)"
  >
    <div class="quality-insight-dialog" data-testid="quality-insight-dialog">
      <div v-if="loading" class="quality-insight-dialog__state" data-testid="quality-insight-loading">加载中…</div>
      <div v-else-if="error" class="quality-insight-dialog__error" role="alert">{{ error }}</div>
      <div v-else-if="!insight || insight.ratedCount === 0" class="quality-insight-dialog__state" data-testid="quality-insight-empty">
        暂无基线数据：每次生成会自动形成客观基线，人工打分后将作为校准叠加。
      </div>
      <template v-else>
        <div class="quality-insight-dialog__summary">
          <div class="quality-insight-dialog__metric"><strong>{{ insight.overallAvg }}</strong><span>平均总分</span></div>
          <div class="quality-insight-dialog__metric"><strong>{{ insight.baselineCount }}</strong><span>固化基准</span></div>
          <div class="quality-insight-dialog__metric"><strong>{{ Math.round(insight.recallAvg * 100) }}%</strong><span>平均召回率</span></div>
          <div class="quality-insight-dialog__metric"><strong>{{ insight.lostCaseCount }}</strong><span>丢失用例</span></div>
        </div>
        <div class="quality-insight-dialog__source" data-testid="quality-insight-source">
          自动基线 {{ insight.objectiveCount }} 次 · 人工校准 {{ insight.humanCount }} 次
        </div>
        <div class="quality-insight-dialog__dimensions">
          <div v-for="key in DIMENSION_ORDER" :key="key" class="quality-insight-dialog__dimension" :class="{ 'is-weak': weakKeys.has(key) }">
            <div class="quality-insight-dialog__dimension-head">
              <span>{{ DIMENSION_LABELS[key] }}</span>
              <strong>{{ insight.dimensionAvgs[key] ?? 0 }}</strong>
            </div>
            <div class="quality-insight-dialog__bar"><div class="quality-insight-dialog__fill" :style="{ width: barWidth(insight.dimensionAvgs[key] ?? 0) }" /></div>
          </div>
        </div>
        <div v-if="insight.weakestDimensions.length" class="quality-insight-dialog__hint">
          <TrendingUp :size="14" />
          <span>优先迭代：{{ insight.weakestDimensions.map((item) => DIMENSION_LABELS[item.key] || item.key).join('、') }}</span>
        </div>
        <div v-if="insight.lostCases.length" class="quality-insight-dialog__lost">
          <strong>近期丢失用例</strong>
          <ul>
            <li v-for="lost in insight.lostCases" :key="lost.title"><span>{{ lost.title }}</span><em>×{{ lost.count }}</em></li>
          </ul>
        </div>
      </template>
    </div>
    <template #footer>
      <el-button @click="emit('update:visible', false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.quality-insight-dialog { display: grid; gap: 14px; }
.quality-insight-dialog__state { padding: 18px 6px; color: var(--text-tertiary); font-size: 13px; text-align: center; }
.quality-insight-dialog__error { color: var(--color-danger); font-size: 12px; }
.quality-insight-dialog__summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; }
.quality-insight-dialog__metric { display: grid; gap: 2px; padding: 8px 10px; border: 1px solid rgba(194, 213, 225, .86); border-radius: 8px; background: var(--surface-raised); }
.quality-insight-dialog__metric strong { color: var(--text-primary); font-size: 15px; font-weight: var(--font-weight-semibold); line-height: 1.2; }
.quality-insight-dialog__metric span { color: var(--text-tertiary); font-size: 11px; }
.quality-insight-dialog__source { color: var(--text-tertiary); font-size: 11px; }
.quality-insight-dialog__dimensions { display: grid; gap: 8px; }
.quality-insight-dialog__dimension-head { display: flex; justify-content: space-between; margin-bottom: 3px; color: var(--quality-text); font-size: 12px; }
.quality-insight-dialog__dimension-head strong { color: var(--text-primary); font-weight: var(--font-weight-semibold); }
.quality-insight-dialog__dimension.is-weak .quality-insight-dialog__dimension-head strong { color: var(--color-danger); }
.quality-insight-dialog__bar { height: 6px; overflow: hidden; border-radius: 999px; background: var(--surface-subtle); }
.quality-insight-dialog__fill { height: 100%; border-radius: 999px; background: var(--quality-good); }
.quality-insight-dialog__dimension.is-weak .quality-insight-dialog__fill { background: var(--quality-weak); }
.quality-insight-dialog__hint { display: flex; align-items: center; gap: 6px; color: var(--quality-weak-text); font-size: 12px; }
.quality-insight-dialog__lost { display: grid; gap: 6px; }
.quality-insight-dialog__lost strong { color: var(--text-primary); font-size: 12px; font-weight: var(--font-weight-semibold); }
.quality-insight-dialog__lost ul { display: grid; gap: 4px; margin: 0; padding: 0; list-style: none; }
.quality-insight-dialog__lost li { display: flex; justify-content: space-between; gap: 8px; padding: 5px 8px; border: 1px solid rgba(194, 213, 225, .72); border-radius: 6px; color: var(--quality-text); font-size: 12px; }
.quality-insight-dialog__lost em { color: var(--text-tertiary); font-style: normal; font-variant-numeric: tabular-nums; }
</style>

<script setup lang="ts">
import { reactive, watch } from 'vue'
import type { WebCaseAgentRun, WebCaseQualityRating } from '../../domain/webCaseAgentRepository'
import { rateWebCaseQuality } from '../../domain/webCaseAgentRepository'

const props = defineProps<{ run?: WebCaseAgentRun; visible: boolean; existing?: WebCaseQualityRating | null }>()
const emit = defineEmits<{ 'update:visible': [boolean]; rated: [WebCaseQualityRating] }>()

const DIMENSIONS = [
  { key: 'coverage', label: '覆盖度' },
  { key: 'source_traceability', label: '来源可追溯' },
  { key: 'testability', label: '可测试性' },
  { key: 'duplicate_risk', label: '去重质量' },
  { key: 'automation_readiness', label: '自动化就绪' },
  { key: 'review_risk', label: '评审把握' }
]

const form = reactive({
  overallScore: 0,
  dimensions: {} as Record<string, number>,
  comment: '',
  submitting: false,
  error: ''
})

watch(() => props.visible, (visible) => {
  if (!visible) return
  form.overallScore = props.existing?.overallScore || 0
  form.dimensions = { ...(props.existing?.dimensionScores || {}) }
  form.comment = props.existing?.comment || ''
  form.error = ''
})

const submit = async () => {
  if (!props.run?.runId) return
  if (form.overallScore < 1 || form.overallScore > 10) {
    form.error = '请先给总分打分(1-10)'
    return
  }
  form.submitting = true
  form.error = ''
  try {
    const dimensionScores = Object.fromEntries(
      Object.entries(form.dimensions).filter(([, value]) => value >= 1 && value <= 10)
    )
    const rating = await rateWebCaseQuality(props.run.runId, {
      overallScore: form.overallScore,
      dimensionScores,
      comment: form.comment
    })
    emit('rated', rating)
    emit('update:visible', false)
  } catch (error) {
    form.error = error instanceof Error ? error.message : '评分提交失败'
  } finally {
    form.submitting = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    :title="existing ? '重新评分' : '给本次生成质量打分'"
    width="460px"
    :close-on-click-modal="false"
    :teleported="false"
    @update:model-value="(value: boolean) => emit('update:visible', value)"
  >
    <div class="quality-rating-dialog" data-testid="quality-rating-dialog">
      <div class="quality-rating-dialog__overall">
        <span class="quality-rating-dialog__label">总体评分</span>
        <el-rate v-model="form.overallScore" :max="10" show-score score-template="{value}/10" />
      </div>
      <div class="quality-rating-dialog__dimensions">
        <div v-for="dimension in DIMENSIONS" :key="dimension.key" class="quality-rating-dialog__dimension">
          <span class="quality-rating-dialog__label">{{ dimension.label }}</span>
          <el-rate v-model="form.dimensions[dimension.key]" :max="10" size="small" />
        </div>
      </div>
      <el-input
        v-model="form.comment"
        type="textarea"
        :rows="2"
        maxlength="500"
        show-word-limit
        placeholder="可选:一句话记录这次生成哪里好、哪里差"
      />
      <div v-if="form.error" class="quality-rating-dialog__error" role="alert">{{ form.error }}</div>
    </div>
    <template #footer>
      <el-button @click="emit('update:visible', false)">取消</el-button>
      <el-button type="primary" :loading="form.submitting" @click="submit">提交评分</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.quality-rating-dialog { display: grid; gap: 14px; }
.quality-rating-dialog__overall { display: flex; align-items: center; gap: 12px; }
.quality-rating-dialog__label { flex: 0 0 84px; color: var(--text-primary); font-size: 13px; font-weight: 600; }
.quality-rating-dialog__dimensions { display: grid; gap: 8px; }
.quality-rating-dialog__dimension { display: flex; align-items: center; gap: 12px; }
.quality-rating-dialog__error { color: var(--color-danger); font-size: 12px; }
</style>

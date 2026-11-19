<script setup lang="ts">
import { computed } from 'vue'
import { useDefects } from '../../ui/useDefects'

const {
  defectFailure,
  defectTemplateCandidate,
  defectRawStore,
  exportDefectRawStore,
  defectRecoveryCandidate,
  retryDefectMutation,
  exportDefectRecoveryCandidate,
  discardDefectRecoveryCandidate
} = useDefects()

const failureMessage = computed(() => {
  const message = defectFailure.value?.message || ''
  const rawOnly = defectFailure.value?.outcome === 'confirmed-no-write'
    && defectTemplateCandidate.value === null
    && defectRecoveryCandidate.value === null
  if (rawOnly && message.endsWith('请保留候选后重试。')) {
    return message.slice(0, -'请保留候选后重试。'.length)
  }
  return message
})

const failurePresentation = computed(() => {
  switch (defectFailure.value?.outcome) {
    case 'uncertain-commit':
      return {
        level: 'warning',
        title: '保存结果待核对',
        impact: '保存结果未知，数据可能已经写入。',
        nextStep: '先核对保存结果，再决定是否重试保存。',
        action: '核对保存结果'
      }
    case 'committed-navigation-failed':
      return {
        level: 'info',
        title: '数据已保存，页面未更新',
        impact: '数据已保存，本次失败只影响页面更新/跳转。',
        nextStep: '仅重试跳转，不要再次保存。',
        action: '仅重试跳转'
      }
    case 'confirmed-no-write':
      return {
        level: 'error',
        title: '未保存',
        impact: defectTemplateCandidate.value || defectRecoveryCandidate.value
          ? '本次操作未写入存储，候选数据仍可恢复。'
          : '本次操作未写入存储。',
        nextStep: defectTemplateCandidate.value || defectRecoveryCandidate.value
          ? '保留需要的数据后重试保存。'
          : defectRawStore.value !== null ? '先下载原始存储文本，核对并修复读取问题。' : '请核对下方失败原因后再操作。',
        action: defectRecoveryCandidate.value?.operation === 'csv-mark' ? '仅重试保存导出状态' : '重试保存'
      }
    default:
      return null
  }
})
</script>
<template>
  <section
    v-if="defectFailure && failurePresentation"
    role="alert"
    class="defect-failure-banner"
    data-testid="defect-failure-banner"
    :data-outcome="defectFailure.outcome"
    :data-message-level="failurePresentation.level"
    aria-live="assertive"
  >
    <div class="defect-failure-banner__summary">
      <h2 data-testid="defect-failure-title">{{ failurePresentation.title }}</h2>
      <p data-testid="defect-failure-impact">{{ failurePresentation.impact }}</p>
      <p class="defect-failure-banner__message">{{ failureMessage }}</p>
    </div>
    <div class="defect-failure-banner__next-step" data-testid="defect-failure-next-step">
      <strong>下一步</strong>
      <span>{{ failurePresentation.nextStep }}</span>
    </div>
    <p v-if="defectRecoveryCandidate" class="defect-failure-banner__note">候选只保留在当前页面；刷新/关页可能丢失。JSON 包含页面敏感文本，不包含路径指向的文件。</p>
    <div class="defect-failure-banner__actions">
      <button
        v-if="defectTemplateCandidate || defectRecoveryCandidate || defectFailure.outcome === 'committed-navigation-failed'"
        type="button"
        class="btn light defect-failure-banner__action is-primary"
        data-action="retry-defect-mutation"
        @click="() => retryDefectMutation()"
      >{{ failurePresentation.action }}</button>
      <button v-if="defectRawStore !== null" type="button" class="btn light defect-failure-banner__action is-secondary" data-action="export-defect-raw" @click="exportDefectRawStore">下载存储原始文本</button>
      <button v-if="defectRecoveryCandidate" type="button" class="btn light defect-failure-banner__action is-secondary" data-action="export-defect-candidate" @click="exportDefectRecoveryCandidate">下载本次 candidate JSON</button>
      <button v-if="defectRecoveryCandidate" type="button" class="btn light defect-failure-banner__action is-secondary is-destructive" data-action="discard-defect-candidate" @click="discardDefectRecoveryCandidate">放弃候选</button>
    </div>
  </section>
</template>
<style src="../../styles/defect-design.css"></style>

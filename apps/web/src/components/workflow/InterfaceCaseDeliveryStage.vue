<script setup lang="ts">
import { ArrowRight, Download, Save } from '@lucide/vue'

defineProps<{
  generatedCount: number
  reviewAttempted: boolean
  reviewFindingCount: number
  saving: boolean
}>()

const emit = defineEmits<{
  save: []
  transfer: []
  export: []
}>()
</script>

<template>
  <section class="interface-delivery-stage" data-testid="interface-case-delivery-stage">
    <header class="interface-delivery-stage__header">
      <span>保存 / 交付</span>
      <h2>{{ generatedCount }} 条接口用例待交付</h2>
      <p>确认本次生成结果后，可保存到用例库、转为 API 自动化或导出 CSV。</p>
    </header>

    <div
      v-if="!reviewAttempted"
      class="interface-delivery-stage__warning"
      role="status"
    >
      <strong>尚未运行 AI 评审，仍可保存或交付</strong>
      <span>建议评审后再交付，但这不会阻止保存。</span>
    </div>
    <div v-else class="interface-delivery-stage__review" role="status">
      <strong>{{ reviewFindingCount > 0 ? `AI 评审发现 ${reviewFindingCount} 项待处理内容` : 'AI 评审已通过' }}</strong>
      <span>{{ reviewFindingCount > 0 ? '可继续保存，建议交付前确认评审发现。' : '当前结果可以交付。' }}</span>
    </div>

    <div class="interface-delivery-stage__actions" aria-label="接口用例交付操作">
      <button
        type="button"
        class="interface-delivery-stage__primary"
        data-testid="interface-save-action"
        :disabled="saving"
        @click="emit('save')"
      >
        <Save :size="17" aria-hidden="true" />
        <span>{{ saving ? '保存中' : '保存到用例库' }}</span>
      </button>
      <button
        type="button"
        data-testid="interface-transfer-action"
        @click="emit('transfer')"
      >
        <ArrowRight :size="17" aria-hidden="true" />
        <span>转 API 自动化</span>
      </button>
      <button
        type="button"
        data-testid="interface-export-action"
        @click="emit('export')"
      >
        <Download :size="17" aria-hidden="true" />
        <span>导出 CSV</span>
      </button>
    </div>
  </section>
</template>

<style scoped>
.interface-delivery-stage {
  display: grid;
  align-content: start;
  gap: 22px;
  width: min(100%, 760px);
  padding: 32px;
  color: var(--text-regular);
  font-family: var(--font-sans);
}

.interface-delivery-stage__header {
  display: grid;
  gap: 6px;
}

.interface-delivery-stage__header > span {
  color: var(--brand-primary);
  font-size: var(--font-size-sm);
  font-weight: 600;
}

.interface-delivery-stage__header h2 {
  margin: 0;
  color: var(--text-primary);
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-semibold);
  letter-spacing: 0;
}

.interface-delivery-stage__header p,
.interface-delivery-stage__warning span,
.interface-delivery-stage__review span {
  margin: 0;
  color: var(--text-secondary);
  line-height: 1.6;
}

.interface-delivery-stage__warning,
.interface-delivery-stage__review {
  display: grid;
  gap: 5px;
  padding: 13px 14px;
  border-radius: var(--radius-control);
}

.interface-delivery-stage__warning {
  color: var(--status-warning-text);
  background: var(--status-warning-soft);
  border: 1px solid var(--color-warning-border);
}

.interface-delivery-stage__review {
  color: var(--brand-primary-active);
  background: var(--brand-primary-subtle);
  border: 1px solid var(--brand-primary-border);
}

.interface-delivery-stage__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.interface-delivery-stage__actions button {
  display: inline-flex;
  min-height: 36px;
  align-items: center;
  gap: 7px;
  padding: 8px 13px;
  color: var(--text-regular);
  background: var(--surface-panel);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-control);
  font: inherit;
  cursor: pointer;
}

.interface-delivery-stage__actions button:hover:not(:disabled) {
  color: var(--brand-primary-active);
  background: var(--brand-primary-subtle);
  border-color: var(--brand-primary-border);
}

.interface-delivery-stage__actions button:focus-visible {
  outline: 2px solid var(--focus-ring-color);
  outline-offset: 2px;
}

.interface-delivery-stage__actions .interface-delivery-stage__primary {
  color: var(--brand-primary-contrast);
  background: var(--brand-primary);
  border-color: var(--brand-primary);
  box-shadow: 0 4px 12px var(--brand-primary-shadow);
}

.interface-delivery-stage__actions .interface-delivery-stage__primary:hover:not(:disabled) {
  color: var(--brand-primary-contrast);
  background: var(--brand-primary-hover);
  border-color: var(--brand-primary-hover);
}

.interface-delivery-stage__actions button:disabled {
  color: var(--text-placeholder);
  background: var(--surface-subtle);
  border-color: var(--border-default);
  box-shadow: none;
  cursor: wait;
}
</style>

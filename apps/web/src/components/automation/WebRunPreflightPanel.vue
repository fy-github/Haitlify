<script setup lang="ts">
import { CircleCheck, CircleX, TriangleAlert } from '@lucide/vue'
import type { Component } from 'vue'
import type { WebAutomationPreflight, WebPreflightItem } from '../../domain/webAutomationWorkspaceView'

defineProps<{ preflight: WebAutomationPreflight; message?: string }>()

const emit = defineEmits<{
  retryCapabilities: []
  editEnvironment: []
  editLogin: []
  clearFilters: []
}>()

const states: Record<WebPreflightItem['status'], { label: string; icon: Component }> = {
  ready: { label: '已就绪', icon: CircleCheck },
  warning: { label: '需注意', icon: TriangleAlert },
  blocked: { label: '需处理', icon: CircleX }
}

const repairLabels = {
  'retry-capabilities': '重新检测',
  'edit-environment': '编辑环境',
  'edit-login': '编辑登录',
  'clear-filters': '清除筛选'
} as const

const repair = (action: NonNullable<WebPreflightItem['repairAction']>) => {
  if (action === 'retry-capabilities') emit('retryCapabilities')
  if (action === 'edit-environment') emit('editEnvironment')
  if (action === 'edit-login') emit('editLogin')
  if (action === 'clear-filters') emit('clearFilters')
}
</script>

<template>
  <section class="web-preflight" aria-labelledby="web-preflight-heading">
  <header class="web-preflight__header">
    <div>
      <h2 id="web-preflight-heading">执行前检查</h2>
    </div>
  </header>

    <p v-if="message" class="web-preflight__message" role="alert">{{ message }}</p>

    <ul class="web-preflight__list">
      <li v-for="item in preflight.items" :key="item.id" class="web-preflight__item" :data-preflight-item="item.id">
        <component :is="states[item.status].icon" :size="18" aria-hidden="true" :class="`web-preflight__icon web-preflight__icon--${item.status}`" />
        <div class="web-preflight__content">
          <div class="web-preflight__title">
            <strong data-preflight-label>{{ item.label }}</strong>
            <span :class="`web-preflight__state web-preflight__state--${item.status}`">{{ states[item.status].label }}</span>
          </div>
          <p>{{ item.detail }}</p>
        </div>
        <button
          v-if="item.repairAction"
          type="button"
          class="web-preflight__repair"
          :data-testid="`repair-preflight-${item.id}`"
          @click="repair(item.repairAction)"
        >{{ repairLabels[item.repairAction] }}</button>
      </li>
    </ul>

    <p
      class="web-preflight__summary"
      :class="`web-preflight__summary--${preflight.canExecute ? 'ready' : 'blocked'}`"
      aria-live="polite"
      aria-atomic="true"
    >
      {{ preflight.canExecute ? '执行检查已通过' : `暂不可执行：${preflight.blockingReason}` }}
    </p>
  </section>
</template>

<style scoped>
.web-preflight {
  display: grid;
  min-height: 0;
  align-content: start;
  gap: 12px;
  overflow-y: auto;
  padding: 16px 14px;
  color: var(--text-primary);
  background: var(--surface-raised);
  border: 1px solid var(--border-regular);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-soft);
}
.web-preflight__header, .web-preflight__title, .web-preflight__item { display: flex; align-items: center; }
.web-preflight__header { justify-content: space-between; }
.web-preflight__header h2 { margin: 0; font-size: 12px; font-weight: 600; letter-spacing: -0.005em; line-height: 20px; }
.web-preflight__content p { margin: 3px 0 0; color: var(--text-secondary); font-size: var(--font-size-xs); }
.web-preflight__message { margin: 0; padding: 8px 10px; color: var(--color-danger); background: var(--color-danger-soft, var(--surface-raised)); border: 1px solid var(--color-danger-border); border-left: 3px solid var(--color-danger); border-radius: 10px; }
.web-preflight__list { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
.web-preflight__item { min-width: 0; gap: 8px; padding: 10px; background: rgba(255, 255, 255, 0.82); border: 1px solid rgba(214, 228, 224, 0.82); border-radius: var(--radius-lg); }
.web-preflight__content { min-width: 0; flex: 1; }
.web-preflight__content strong { font-size: 12px; font-weight: 600; line-height: 17px; }
.web-preflight__title { gap: 8px; }
.web-preflight__state { font-size: var(--font-size-xs); font-weight: var(--font-weight-medium); }
.web-preflight__icon { width: 16px; height: 16px; flex: 0 0 16px; }
.web-preflight__icon--ready, .web-preflight__state--ready { color: var(--color-success); }
.web-preflight__icon--warning, .web-preflight__state--warning { color: var(--color-warning); }
.web-preflight__icon--blocked, .web-preflight__state--blocked, .web-preflight__summary--blocked { color: var(--color-danger); }
.web-preflight__summary--ready { color: var(--color-success); }
.web-preflight__repair { min-height: 30px; padding: 0 11px; color: var(--brand-primary); background: rgba(255, 255, 255, 0.84); border: 1px solid rgba(183, 210, 202, 0.82); border-radius: 11px; box-shadow: none; cursor: pointer; font-size: 11px; line-height: 1.2; }
.web-preflight__repair:hover { background: var(--surface-raised); border-color: rgba(124, 190, 178, 0.8); box-shadow: 0 8px 20px rgba(15, 118, 110, 0.07); }
.web-preflight__repair:focus-visible { outline: 2px solid var(--brand-primary); outline-offset: 2px; }
.web-preflight__summary { min-height: 20px; margin: 0; padding: 10px 11px; background: rgba(255, 255, 255, 0.82); border: 1px solid rgba(214, 228, 224, 0.82); border-radius: var(--radius-lg); font-size: 12px; font-weight: 600; line-height: 1.45; }
@media (max-width: 520px) { .web-preflight__item { align-items: flex-start; flex-wrap: wrap; } .web-preflight__repair { margin-left: 28px; } }
</style>

<script setup lang="ts">
import { computed } from 'vue'
import { TriangleAlert, CircleCheck, ChevronRight, ShieldCheck, X } from '@lucide/vue'
import type { AiRunPolicy } from '../../domain/aiPlatformRepository'

const props = withDefaults(defineProps<{
  policy?: AiRunPolicy | null
  ready?: boolean
  error?: string
}>(), { policy: null, ready: false, error: '' })

const emit = defineEmits<{ viewDetails: []; dismiss: [] }>()

const requirementPolicy = computed(() => props.policy ?? null)
const primaryRoute = computed(() => requirementPolicy.value?.routes.find((route) => route.position === 0) ?? requirementPolicy.value?.routes[0])
const fallbackCount = computed(() => Math.max(0, (requirementPolicy.value?.routes.length ?? 0) - 1))
const warningCount = computed(() => requirementPolicy.value?.warnings.length ?? 0)

const state = computed<{ tone: 'ready' | 'warning' | 'unavailable'; label: string; detail: string }>(() => {
  if (props.error) {
    return { tone: 'unavailable', label: 'Platform AI 未就绪', detail: props.error.includes('404') ? '运行策略接口不可用，请重启后端服务' : '运行策略加载失败，请稍后重试' }
  }
  if (!props.ready) {
    return { tone: 'unavailable', label: 'Platform AI 未就绪', detail: '请先在 Platform AI 中配置并启用模型连接' }
  }
  const policy = requirementPolicy.value
  if (!policy || !policy.enabled || !primaryRoute.value) {
    return { tone: 'unavailable', label: 'Platform AI 未就绪', detail: '运行策略尚未配置主路由' }
  }
  const model = primaryRoute.value.model || '默认模型'
  const fallbackLabel = fallbackCount.value > 0 ? `${fallbackCount.value} 条备用路由` : '无备用路由'
  return { tone: warningCount.value > 0 ? 'warning' : 'ready', label: `Platform AI 就绪 · ${model}`, detail: fallbackLabel }
})

const toneClass = computed(() => `ai-policy-disclosure--${state.value.tone}`)
const iconComponent = computed(() => {
  if (state.value.tone === 'ready') return CircleCheck
  if (state.value.tone === 'warning') return TriangleAlert
  return ShieldCheck
})
</script>

<template>
  <div class="ai-policy-disclosure" :class="toneClass" data-testid="ai-policy-disclosure" role="status" aria-live="polite">
    <component :is="iconComponent" :size="16" class="ai-policy-disclosure__icon" aria-hidden="true" />
    <div class="ai-policy-disclosure__body">
      <strong class="ai-policy-disclosure__label">{{ state.label }}</strong>
      <span class="ai-policy-disclosure__detail">
        {{ state.detail }}
        <span v-if="warningCount > 0" class="ai-policy-disclosure__warnings">{{ warningCount }} 条策略校验提示</span>
      </span>
    </div>
    <button v-if="requirementPolicy" type="button" class="ai-policy-disclosure__link" data-testid="ai-policy-details-link" @click="emit('viewDetails')">
      查看路由详情<ChevronRight :size="14" aria-hidden="true" />
    </button>
    <button type="button" class="ai-policy-disclosure__dismiss" data-testid="ai-policy-dismiss" aria-label="关闭 Platform AI 状态提示" title="关闭" @click="emit('dismiss')">
      <X :size="15" aria-hidden="true" />
    </button>
  </div>
</template>

<style scoped>
.ai-policy-disclosure {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  border-radius: var(--radius-control);
  border: 1px solid var(--border-subtle);
  background: var(--surface-sunken, var(--surface-page));
  color: var(--text-secondary);
  font-size: 13px;
}
.ai-policy-disclosure__icon { flex: none; }
.ai-policy-disclosure__body { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.ai-policy-disclosure__label { color: var(--text-primary); font-weight: 600; }
.ai-policy-disclosure__detail { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.ai-policy-disclosure__warnings { color: var(--color-warning); }
.ai-policy-disclosure__link {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  background: none;
  border: none;
  color: var(--brand-primary);
  cursor: pointer;
  font: inherit;
  padding: 0;
}
.ai-policy-disclosure__link:hover { text-decoration: underline; }
.ai-policy-disclosure__dismiss { display: inline-grid; flex: none; width: 26px; height: 26px; margin-left: 2px; place-items: center; padding: 0; border: 0; border-radius: var(--radius-control); background: transparent; color: var(--text-tertiary); cursor: pointer; }
.ai-policy-disclosure__dismiss:hover { background: rgba(86, 103, 124, .08); color: var(--text-primary); }
.ai-policy-disclosure__dismiss:focus-visible { outline: 2px solid var(--brand-primary-focus); outline-offset: 1px; }
.ai-policy-disclosure--ready { border-color: var(--color-success-border); background: var(--color-success-soft); }
.ai-policy-disclosure--ready .ai-policy-disclosure__icon { color: var(--color-success); }
.ai-policy-disclosure--warning { border-color: var(--color-warning-border); background: var(--color-warning-soft); }
.ai-policy-disclosure--warning .ai-policy-disclosure__icon { color: var(--color-warning); }
.ai-policy-disclosure--unavailable .ai-policy-disclosure__icon { color: var(--text-tertiary); }
</style>

<script setup lang="ts">
import { computed } from 'vue'
import type { WebAutomationActionIR, WebAutomationStep } from '../../domain/webAutomationCase'

const props = defineProps<{
  visible: boolean
  title?: string
  actions?: WebAutomationActionIR[]
  steps?: WebAutomationStep[]
}>()
const emit = defineEmits<{ 'update:visible': [boolean] }>()

const actionCount = computed(() => (props.actions || []).length)
const pretty = (value: unknown) => {
  if (value === undefined || value === null) return '—'
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}
const stepLine = (step: WebAutomationStep) => [step.action, step.targetType, step.target].filter(Boolean).join(' ')
</script>

<template>
  <el-dialog
    :model-value="visible"
    :title="title || 'Action IR / 用例步骤'"
    width="min(920px, 94vw)"
    :close-on-click-modal="false"
    :teleported="false"
    @update:model-value="(value: boolean) => emit('update:visible', value)"
  >
    <div class="action-ir-viewer" data-testid="action-ir-viewer">
      <div class="action-ir-viewer__pane" data-testid="action-ir-pane">
        <strong>Action IR（{{ actionCount }} 个动作）</strong>
        <pre v-if="actionCount" data-testid="action-ir-json">{{ pretty(actions) }}</pre>
        <span v-else class="action-ir-viewer__empty" data-testid="action-ir-empty">
          该用例没有 Action IR（自然语言/手工步骤用例只保存步骤源）。
        </span>
      </div>
      <div class="action-ir-viewer__pane" data-testid="action-steps-pane">
        <strong>用例步骤（源）</strong>
        <ol v-if="(steps || []).length" class="action-ir-viewer__steps">
          <li v-for="step in steps || []" :key="step.id">
            <code>{{ stepLine(step) }}</code>
            <span class="action-ir-viewer__step-name">{{ step.name }}</span>
          </li>
        </ol>
        <span v-else class="action-ir-viewer__empty">没有步骤记录。</span>
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
.action-ir-viewer { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 12px; }
.action-ir-viewer__pane { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.action-ir-viewer__pane strong { color: var(--brand-text-secondary, #5f6b66); font-size: 12px; }
.action-ir-viewer__pane pre { margin: 0; padding: 8px 10px; max-height: 420px; overflow: auto; background: rgba(15, 118, 110, 0.05); border-radius: 10px; font-size: 11.5px; }
.action-ir-viewer__steps { margin: 0; padding-left: 18px; font-size: 12px; display: flex; flex-direction: column; gap: 4px; }
.action-ir-viewer__steps code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.action-ir-viewer__step-name { margin-left: 8px; color: var(--brand-text-secondary, #5f6b66); }
.action-ir-viewer__empty { color: var(--brand-text-secondary, #5f6b66); font-size: 12px; }
@media (max-width: 720px) { .action-ir-viewer { grid-template-columns: minmax(0, 1fr); } }
</style>

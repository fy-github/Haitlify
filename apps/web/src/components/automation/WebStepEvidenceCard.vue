<script setup lang="ts">
import { ref } from 'vue'
import type { WebAutomationStepEvidence } from '../../domain/webAutomation'

defineProps<{ evidence: WebAutomationStepEvidence[] }>()

const expandedKey = ref('')
const rowKey = (item: WebAutomationStepEvidence, index: number) => `${item.evidenceId || 'no-evidence'}-${index}`
const toggle = (key: string) => {
  expandedKey.value = expandedKey.value === key ? '' : key
}
const pretty = (value: unknown) => {
  if (value === undefined || value === null) return '—'
  if (typeof value === 'string') return value || '—'
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

// 真实管道（runner run-executor.buildStepEvidence + page-evidence.withTargetState）把 locator 与
// visible/enabled/receivesEvents/unique/matchedElementCount 归一到 resolvedTarget 对象内，
// 顶层不存在这些字段；这里按真实形状读取，缺字段显示 —（不把缺失伪装成 false）。
const targetObject = (item: WebAutomationStepEvidence): Record<string, unknown> | undefined => {
  const target = item.resolvedTarget
  return target && typeof target === 'object' && !Array.isArray(target)
    ? target as Record<string, unknown>
    : undefined
}
const locatorText = (item: WebAutomationStepEvidence): string => {
  const target = targetObject(item)
  const fromTarget = target && typeof target.locator === 'string' ? target.locator : ''
  if (fromTarget) return fromTarget
  return typeof item.locator === 'string' && item.locator ? item.locator : '—'
}
const flagText = (item: WebAutomationStepEvidence, key: 'visible' | 'enabled' | 'receivesEvents' | 'unique'): string => {
  const target = targetObject(item)
  const raw = target && key in target ? target[key] : undefined
  if (raw === undefined || raw === null) return '—'
  return String(raw)
}
const matchedCount = (item: WebAutomationStepEvidence): string => {
  const target = targetObject(item)
  const raw = target?.matchedElementCount
  return raw === undefined || raw === null ? '—' : String(raw)
}
</script>

<template>
  <div class="web-step-evidence" data-testid="web-step-evidence">
    <div
      v-for="(item, index) in evidence"
      :key="rowKey(item, index)"
      class="web-step-evidence__row"
      :data-testid="`web-step-evidence-${item.evidenceId || `row-${index}`}`"
    >
      <div class="web-step-evidence__head">
        <span class="web-step-evidence__action">{{ item.actionId || `（无 actionId · 行 ${index + 1}）` }}</span>
        <el-tag size="small" effect="plain">{{ item.actionType }}</el-tag>
        <el-tag :type="item.execution.status === 'passed' ? 'success' : 'danger'" effect="light">
          {{ item.execution.status === 'passed' ? '通过' : '失败' }}
        </el-tag>
        <span class="web-step-evidence__duration">{{ item.execution.durationMs }} ms</span>
        <span v-if="item.failureType" class="web-step-evidence__failure">{{ item.failureType }}</span>
        <span v-if="item.observationStatus && item.observationStatus !== item.execution.status" class="web-step-evidence__failure">
          观察 {{ item.observationStatus }}
        </span>
        <el-button
          link
          size="small"
          :data-testid="`web-step-evidence-toggle-${item.evidenceId || `row-${index}`}`"
          @click="toggle(rowKey(item, index))"
        >
          {{ expandedKey === rowKey(item, index) ? '收起详情' : 'locator / 证据详情' }}
        </el-button>
      </div>
      <div class="web-step-evidence__paths">
        <code>{{ item.before.pageKey || '—' }}</code>
        <span aria-hidden="true">→</span>
        <code>{{ item.after.pageKey || '—' }}</code>
      </div>
      <div
        v-if="expandedKey === rowKey(item, index)"
        class="web-step-evidence__detail"
        :data-testid="`web-step-evidence-detail-${item.evidenceId || `row-${index}`}`"
      >
        <div class="web-step-evidence__field">
          <strong>locator</strong>
          <span class="web-step-evidence__locator" data-testid="step-evidence-locator">{{ locatorText(item) }}</span>
        </div>
        <div class="web-step-evidence__field">
          <strong>resolvedTarget</strong>
          <pre>{{ pretty(item.resolvedTarget) }}</pre>
        </div>
        <div class="web-step-evidence__field">
          <strong>状态位（resolvedTarget 内）</strong>
          <span data-testid="step-evidence-flags">
            visible={{ flagText(item, 'visible') }} · enabled={{ flagText(item, 'enabled') }} · receivesEvents={{ flagText(item, 'receivesEvents') }} · unique={{ flagText(item, 'unique') }} · matchedElementCount={{ matchedCount(item) }}
          </span>
        </div>
        <div class="web-step-evidence__field">
          <strong>指纹</strong>
          <span>{{ item.before.pageFingerprint || '—' }} → {{ item.after.pageFingerprint || '—' }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.web-step-evidence { display: flex; flex-direction: column; gap: 8px; }
.web-step-evidence__row { padding: 8px 10px; border: 1px solid rgba(183, 210, 202, 0.6); border-radius: 10px; background: rgba(255, 255, 255, 0.6); }
.web-step-evidence__head { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; font-size: 12.5px; }
.web-step-evidence__action { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-weight: 600; }
.web-step-evidence__duration { color: var(--brand-text-secondary, #5f6b66); }
.web-step-evidence__failure { color: #b91c1c; }
.web-step-evidence__paths { display: flex; align-items: center; gap: 8px; margin-top: 4px; font-size: 12px; color: var(--brand-text-secondary, #5f6b66); overflow-wrap: anywhere; }
.web-step-evidence__detail { display: flex; flex-direction: column; gap: 6px; margin-top: 8px; padding-top: 8px; border-top: 1px dashed rgba(183, 210, 202, 0.7); }
.web-step-evidence__field { display: flex; flex-direction: column; gap: 2px; font-size: 12px; }
.web-step-evidence__field strong { color: var(--brand-text-secondary, #5f6b66); font-weight: 600; }
.web-step-evidence__field pre { margin: 0; padding: 6px 8px; background: rgba(15, 118, 110, 0.05); border-radius: 8px; font-size: 11.5px; overflow: auto; max-height: 180px; }
.web-step-evidence__locator { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; overflow-wrap: anywhere; }
</style>

<script setup lang="ts">
import { computed } from 'vue'
import { CircleCheck, CircleX, CircleAlert, Play, ShieldAlert } from '@lucide/vue'
import type { AiCapabilityCheckReport } from '../../domain/aiRuntimeRepository'

const props = withDefaults(defineProps<{
  report?: AiCapabilityCheckReport | null
  fresh?: boolean
  currentConfigHash?: string | null
  loading?: boolean
}>(), { report: null, fresh: false, currentConfigHash: null, loading: false })

const overallLabel = computed(() => {
  switch (String(props.report?.overall ?? '').toLowerCase()) {
    case 'pass': return '全部通过'
    case 'partial': return '部分通过（有降级项）'
    case 'fail': return '未通过'
    default: return '尚未验证'
  }
})

const overallKind = computed(() => {
  switch (String(props.report?.overall ?? '').toLowerCase()) {
    case 'pass': return 'pass'
    case 'partial': return 'partial'
    case 'fail': return 'fail'
    default: return 'unknown'
  }
})

const overallIcon = computed(() => (overallKind.value === 'pass' ? CircleCheck : overallKind.value === 'fail' ? CircleX : CircleAlert))

// 报告新鲜度：config hash 必须与当前一致且在 TTL 内（协议/模型/凭证任一变更都会使旧 probe 无效）。
const staleReason = computed(() => {
  if (!props.report) return '尚无验证报告，请先运行能力检查'
  if (!props.fresh) {
    const changed = props.report.configHash && props.currentConfigHash && props.report.configHash !== props.currentConfigHash
    return changed ? '连接配置（协议/模型/凭证）已更新，旧报告作废——请重新验证' : '报告已过期，请重新验证'
  }
  return ''
})

const probeLabel = (probe: string): string => {
  const labels: Record<string, string> = {
    text_generation: '文本生成',
    structured_output: '结构化输出',
    vision: '视觉理解',
    tool_calling: '工具调用',
    streaming: '流式输出'
  }
  return labels[probe] ?? probe
}
</script>

<template>
  <section class="ai-capability-report" aria-label="AI 能力验证报告">
    <header class="report-head">
      <component :is="overallIcon" :size="18" aria-hidden="true" />
      <span class="report-overall" :data-testid="`ai-capability-overall-${overallKind}`">{{ overallLabel }}</span>
      <button
        type="button"
        class="report-run"
        data-testid="ai-capability-run"
        :disabled="loading"
        @click="$emit('run')"
      >
        <Play :size="14" aria-hidden="true" />
        {{ loading ? '验证中…' : '运行能力检查' }}
      </button>
    </header>

    <p v-if="staleReason" class="report-stale" data-testid="ai-capability-stale">
      <ShieldAlert :size="14" aria-hidden="true" />
      {{ staleReason }}
    </p>

    <ul v-if="report && report.probes.length > 0" class="report-probes">
      <li
        v-for="probe in report.probes"
        :key="probe.probe"
        class="report-probe"
        :data-testid="`ai-capability-probe-${probe.probe}`"
      >
        <span class="probe-name">{{ probeLabel(probe.probe) }}</span>
        <span class="probe-status" :class="`status-${probe.status}`">{{ probe.status }}</span>
        <span v-if="probe.detail" class="probe-detail">{{ probe.detail }}</span>
      </li>
    </ul>

    <footer v-if="report?.checkedAt" class="report-foot">
      <span class="report-checked">验证时间 {{ report.checkedAt }}</span>
      <span v-if="report.configHash" class="report-hash" :data-testid="'ai-capability-config-hash'">
        配置指纹 {{ report.configHash.slice(0, 12) }}
      </span>
    </footer>
  </section>
</template>

<style scoped>
.ai-capability-report { display: flex; flex-direction: column; gap: 8px; }
.report-head { display: flex; align-items: center; gap: 8px; }
.report-overall { font-weight: 600; }
.report-run { margin-left: auto; display: inline-flex; gap: 4px; align-items: center; }
.report-stale { display: flex; gap: 6px; align-items: center; color: var(--warning, #b45309); margin: 0; }
.report-probes { display: flex; flex-direction: column; gap: 6px; list-style: none; margin: 0; padding: 0; }
.report-probe { display: flex; gap: 8px; align-items: baseline; padding: 6px 8px; border: 1px solid var(--border, #e5e7eb); border-radius: 6px; }
.probe-name { font-weight: 500; }
.probe-status { font-size: 12px; text-transform: uppercase; }
.status-pass { color: var(--success, #15803d); }
.status-fail { color: var(--destructive, #ef4444); }
.status-partial { color: var(--warning, #b45309); }
.probe-detail { color: var(--muted-foreground, #6b7280); }
.report-foot { display: flex; gap: 12px; color: var(--muted-foreground, #6b7280); font-size: 12px; }
</style>

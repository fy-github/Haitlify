<script setup lang="ts">
import { Activity, CircleCheck, CircleX, Clock3, RotateCcw, ScrollText } from '@lucide/vue'
import { computed } from 'vue'
import type { WebAutomationRunSnapshot } from '../../domain/webAutomationRunRepository'
import type { ReturnTypeOfBuildWebRunView } from '../../domain/webAutomationRunView'

const props = defineProps<{
  snapshot: WebAutomationRunSnapshot
  view: ReturnTypeOfBuildWebRunView
  connectionLabel: string
  elapsed: string
}>()

const emit = defineEmits<{ enterMonitor: []; rerun: []; openReport: [] }>()

const terminal = computed(() => ['completed', 'stopped', 'failed'].includes(props.snapshot.status))
const statusIcon = computed(() => props.snapshot.status === 'completed'
  ? CircleCheck
  : props.snapshot.status === 'failed'
    ? CircleX
    : props.snapshot.status === 'queued'
      ? Clock3
      : Activity)
const screenshotWaitingText = computed(() => props.snapshot.status === 'queued'
  ? '等待浏览器启动'
  : terminal.value
    ? '本次执行没有页面截图'
    : '等待首个页面截图')
const latestDiagnostic = computed(() => props.snapshot.diagnostics[props.snapshot.diagnostics.length - 1])
</script>

<template>
  <section class="web-run-summary" aria-label="Web 自动化运行摘要">
    <header class="web-run-summary__header">
      <div class="web-run-summary__status">
        <component :is="statusIcon" :size="19" aria-hidden="true" />
        <strong>{{ view.statusLabel }}</strong>
      </div>
      <div class="web-run-summary__meta">
        <span>{{ connectionLabel }}</span><span>{{ elapsed }}</span><span>{{ snapshot.selectedBrowser }}</span>
      </div>
    </header>

    <div class="web-run-summary__progress-copy">
      <span>执行进度</span><strong>{{ snapshot.progress.completedCases }} / {{ snapshot.progress.totalCases }}</strong>
    </div>
    <div class="web-run-summary__progress" role="progressbar" aria-label="Web 自动化执行进度" aria-valuemin="0" aria-valuemax="100" :aria-valuenow="view.progressPercent">
      <span :style="{ width: `${view.progressPercent}%` }" />
    </div>

    <div class="web-run-summary__body">
      <div class="web-run-summary__visual">
        <img v-if="snapshot.latestScreenshotUrl" :src="snapshot.latestScreenshotUrl" alt="Web 自动化运行截图">
        <p v-else>{{ screenshotWaitingText }}</p>
      </div>
      <dl class="web-run-summary__details">
        <div><dt>当前用例</dt><dd>{{ snapshot.currentCase?.name || '暂无当前用例' }}</dd></div>
        <div><dt>当前步骤</dt><dd>{{ snapshot.currentStep?.name || '暂无当前步骤' }}</dd></div>
        <div v-if="latestDiagnostic"><dt>最新诊断</dt><dd>{{ latestDiagnostic }}</dd></div>
      </dl>
    </div>

    <footer class="web-run-summary__actions">
      <button type="button" class="web-run-summary__primary" data-testid="enter-web-run-monitor" @click="emit('enterMonitor')">
        <Activity :size="16" aria-hidden="true" />进入实时监控
      </button>
      <button v-if="terminal" type="button" data-testid="rerun-web-automation" @click="emit('rerun')">
        <RotateCcw :size="16" aria-hidden="true" />重新执行
      </button>
      <button v-if="terminal && snapshot.results.length > 0" type="button" data-testid="open-web-run-report" @click="emit('openReport')">
        <ScrollText :size="16" aria-hidden="true" />查看报告
      </button>
    </footer>
  </section>
</template>

<style scoped>
.web-run-summary { display: grid; min-height: 0; align-content: start; gap: 10px; overflow-y: auto; padding: 14px; color: var(--text-primary); background: var(--surface-subtle); border: 0; border-radius: 0; }
.web-run-summary__header, .web-run-summary__status, .web-run-summary__meta, .web-run-summary__progress-copy, .web-run-summary__actions, .web-run-summary__actions button { display: flex; align-items: center; }
.web-run-summary__header, .web-run-summary__progress-copy { justify-content: space-between; gap: 12px; }
.web-run-summary__status { gap: 7px; color: var(--text-primary); font-size: 14px; }
.web-run-summary__meta { flex-wrap: wrap; justify-content: flex-end; gap: 6px 14px; color: var(--text-secondary); font-size: var(--font-size-sm); }
.web-run-summary__progress-copy { color: var(--text-secondary); font-size: var(--font-size-xs); }
.web-run-summary__progress { height: 6px; overflow: hidden; background: var(--surface-raised); border-radius: 3px; }
.web-run-summary__progress span { display: block; height: 100%; background: var(--brand-primary); transition: width 180ms ease; }
.web-run-summary__body { display: grid; min-width: 0; grid-template-columns: minmax(180px, 38%) 1fr; gap: 14px; }
.web-run-summary__visual { display: grid; aspect-ratio: 16 / 9; min-height: 112px; overflow: hidden; place-items: center; background: var(--surface-raised); border: 1px solid var(--border-subtle, var(--border-default)); border-radius: var(--radius-control, 6px); }
.web-run-summary__visual img { width: 100%; height: 100%; object-fit: cover; }
.web-run-summary__visual p { margin: 0; padding: 12px; color: var(--text-secondary); font-size: var(--font-size-sm); text-align: center; }
.web-run-summary__details { display: grid; align-content: start; gap: 9px; min-width: 0; margin: 0; }
.web-run-summary__details div { min-width: 0; }
.web-run-summary__details dt { color: var(--text-secondary); font-size: var(--font-size-xs); }
.web-run-summary__details dd { margin: 2px 0 0; overflow-wrap: anywhere; color: var(--text-primary); font-size: var(--font-size-sm); font-weight: var(--font-weight-medium); line-height: 18px; }
.web-run-summary__actions { flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.web-run-summary__actions button { min-height: 34px; gap: 6px; padding: 0 11px; color: var(--text-primary); background: var(--surface-raised); border: 1px solid var(--border-default); border-radius: var(--radius-control, 6px); cursor: pointer; font-size: var(--font-size-sm); }
.web-run-summary__actions .web-run-summary__primary { color: var(--text-inverse); background: var(--brand-primary); border-color: var(--brand-primary); font-weight: 600; }
.web-run-summary__actions button:focus-visible { outline: 2px solid var(--brand-primary); outline-offset: 2px; }
@media (max-width: 600px) { .web-run-summary__header { align-items: flex-start; flex-direction: column; } .web-run-summary__meta { justify-content: flex-start; } .web-run-summary__body { grid-template-columns: 1fr; } .web-run-summary__actions button { flex: 1 1 auto; justify-content: center; } }
</style>

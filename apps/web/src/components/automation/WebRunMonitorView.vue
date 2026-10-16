<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElTooltip } from 'element-plus'
import {
  Activity,
  TriangleAlert,
  ArrowLeft,
  Bug,
  Camera,
  CircleCheck,
  CircleX,
  Clock3,
  Pause,
  Play,
  RotateCcw,
  ScrollText,
  Square,
  Stethoscope,
  TerminalSquare,
  Wifi,
  WifiOff
} from '@lucide/vue'
import type { WebAutomationRunResult } from '../../domain/webAutomation'
import type { WebAutomationRunSnapshot } from '../../domain/webAutomationRunRepository'
import type { ReturnTypeOfBuildWebRunView, WebRunTimelineItem } from '../../domain/webAutomationRunView'

const props = defineProps<{
  snapshot: WebAutomationRunSnapshot
  view: ReturnTypeOfBuildWebRunView
  connectionState: 'idle' | 'polling' | 'retrying' | 'interrupted'
  connectionLabel: string
  elapsed: string
  controlLoading: 'pause' | 'resume' | 'stop' | ''
}>()

const emit = defineEmits<{
  returnWorkbench: []
  pause: []
  resume: []
  stop: []
  rerun: []
  openLog: [caseId: string]
  openReport: []
  transferDefect: [caseId: string]
}>()

const terminal = computed(() => ['completed', 'stopped', 'failed'].includes(props.snapshot.status))
const lastScreenshotUrl = ref(props.snapshot.latestScreenshotUrl || '')
const lastTimeline = ref<WebRunTimelineItem[]>(props.view.timeline)
const connectedControlsAvailable = computed(() => props.connectionState !== 'interrupted')
const canPause = computed(() => connectedControlsAvailable.value && props.view.controls.canPause)
const canResume = computed(() => connectedControlsAvailable.value && props.view.controls.canResume)
const canStop = computed(() => props.view.controls.canStop)
const runningCount = computed(() => !terminal.value && props.snapshot.currentCase ? 1 : 0)
const resultCounts = computed(() => {
  if (props.snapshot.results.length > 0) {
    return props.snapshot.results.reduce((counts, row) => ({
      ...counts,
      [row.status]: counts[row.status] + 1
    }), { passed: 0, failed: 0, error: 0 })
  }
  return props.snapshot.summary
})
const completedResultCount = computed(() => props.snapshot.results.length > 0
  ? props.snapshot.results.length
  : Math.max(
    props.snapshot.progress.completedCases,
    resultCounts.value.passed + resultCounts.value.failed + resultCounts.value.error
  ))
const pendingCount = computed(() => Math.max(
  0,
  props.snapshot.progress.totalCases
    - completedResultCount.value
    - runningCount.value
))
const screenshotWaitingText = computed(() => props.snapshot.status === 'queued'
  ? '等待浏览器启动'
  : terminal.value
    ? '本次执行没有页面截图'
    : '等待首个页面截图')
const currentAction = computed(() => props.snapshot.currentStep?.action || '暂无动作')
const currentTimestamp = computed(() => props.snapshot.latestScreenshotAt || props.snapshot.updatedAt)
const shouldUseCachedLiveContext = computed(() => props.connectionState === 'retrying' || props.connectionState === 'interrupted')
const displayedScreenshotUrl = computed(() => props.snapshot.latestScreenshotUrl || (shouldUseCachedLiveContext.value ? lastScreenshotUrl.value : ''))
const displayedTimeline = computed(() => props.view.timeline.length > 0 ? props.view.timeline : shouldUseCachedLiveContext.value ? lastTimeline.value : [])
const latestDiagnostic = computed(() => props.snapshot.diagnostics[props.snapshot.diagnostics.length - 1] || '暂无诊断')
const timelineGroups = computed(() => [
  { phase: 'setup', label: '环境准备', items: phaseItems('setup') },
  { phase: 'test', label: '用例执行', items: phaseItems('test') },
  { phase: 'teardown', label: '环境清理', items: phaseItems('teardown') }
])
const liveAnnouncement = computed(() => `${props.view.statusLabel}，${props.connectionLabel}`)
const lifecycleIcon = computed(() => lifecycleIcons[props.snapshot.status])
const connectionIcon = computed(() => connectionIcons[props.connectionState])
const countItems = computed(() => [
  { id: 'passed', className: 'is-passed', label: `通过 ${resultCounts.value.passed}`, icon: statusIcons.passed },
  { id: 'failed', className: 'is-failed', label: `失败 ${resultCounts.value.failed}`, icon: statusIcons.failed },
  { id: 'error', className: 'is-error', label: `错误 ${resultCounts.value.error}`, icon: statusIcons.error },
  { id: 'running', className: 'is-running', label: `运行中 ${runningCount.value}`, icon: statusIcons.running },
  { id: 'pending', className: 'is-pending', label: `待执行 ${pendingCount.value}`, icon: Clock3 }
])

const phaseItems = (phase: WebRunTimelineItem['phase']) =>
  displayedTimeline.value.filter((item) => item.phase === phase)

const statusLabel = (status: WebAutomationRunResult['status'] | WebRunTimelineItem['status']) => ({
  passed: '通过',
  failed: '失败',
  error: '错误',
  running: '运行中'
}[status])

const statusIcon = (status: WebAutomationRunResult['status'] | WebRunTimelineItem['status']) => statusIcons[status]
const durationLabel = (durationMs?: number) => typeof durationMs === 'number' ? `${durationMs} ms` : '进行中'

const statusIcons = {
  passed: CircleCheck,
  failed: CircleX,
  error: TriangleAlert,
  running: Activity
}

const lifecycleIcons = {
  queued: Clock3,
  running: Activity,
  pause_requested: Pause,
  paused: Pause,
  stop_requested: Square,
  stopped: Square,
  completed: CircleCheck,
  failed: TriangleAlert
}

const connectionIcons = {
  idle: Clock3,
  polling: Wifi,
  retrying: RotateCcw,
  interrupted: WifiOff
}

watch(() => props.snapshot.runId, () => {
  lastScreenshotUrl.value = props.snapshot.latestScreenshotUrl || ''
  lastTimeline.value = props.view.timeline
})

watch(() => props.snapshot.latestScreenshotUrl, (url) => {
  if (url) lastScreenshotUrl.value = url
}, { immediate: true })

watch(() => props.view.timeline, (timeline) => {
  if (timeline.length > 0) lastTimeline.value = timeline
}, { immediate: true })
</script>

<template>
  <section class="web-run-monitor-view" aria-label="Web 自动化实时监控">
    <header class="web-run-monitor-view__header">
      <div class="web-run-monitor-view__identity">
        <el-tooltip content="返回 Web 自动化工作台">
          <button type="button" class="web-run-monitor-view__icon-button" aria-label="返回 Web 自动化工作台" @click="emit('returnWorkbench')">
            <ArrowLeft :size="19" aria-hidden="true" />
          </button>
        </el-tooltip>
        <div>
          <h1>实时监控</h1>
          <p><span>{{ snapshot.runId }}</span><span>{{ snapshot.mode }}</span></p>
        </div>
      </div>

      <div class="web-run-monitor-view__meta" aria-label="运行状态">
        <span data-testid="web-run-lifecycle-status" class="web-run-monitor-view__status" :class="`is-${view.statusType}`">
          <component :is="lifecycleIcon" :size="14" aria-hidden="true" />{{ view.statusLabel }}
        </span>
        <span data-testid="web-run-connection-status" :class="`is-${connectionState}`">
          <component :is="connectionIcon" :size="14" aria-hidden="true" />{{ connectionLabel }}
        </span>
        <span>{{ elapsed }}</span>
        <span>{{ snapshot.selectedBrowser }}</span>
      </div>

      <div class="web-run-monitor-view__controls" aria-label="运行控制">
        <el-tooltip v-if="canPause" content="暂停执行">
          <button type="button" data-testid="pause-web-run" data-control-testid="pause" :disabled="controlLoading === 'pause'" @click="emit('pause')">
            <Pause :size="16" aria-hidden="true" />暂停
          </button>
        </el-tooltip>
        <el-tooltip v-if="canResume" content="继续执行">
          <button type="button" data-testid="resume-web-run" data-control-testid="resume" :disabled="controlLoading === 'resume'" @click="emit('resume')">
            <Play :size="16" aria-hidden="true" />继续执行
          </button>
        </el-tooltip>
        <el-tooltip v-if="canStop" content="停止执行">
          <button type="button" data-testid="stop-web-run" data-control-testid="stop" :disabled="controlLoading === 'stop'" @click="emit('stop')">
            <Square :size="15" aria-hidden="true" />停止
          </button>
        </el-tooltip>
        <el-tooltip v-if="terminal" content="重新执行">
          <button type="button" data-testid="rerun-web-run" data-control-testid="rerun" @click="emit('rerun')">
            <RotateCcw :size="16" aria-hidden="true" />重新执行
          </button>
        </el-tooltip>
        <el-tooltip v-if="terminal" content="查看报告">
          <button type="button" data-testid="open-web-run-report" data-control-testid="report" @click="emit('openReport')">
            <ScrollText :size="16" aria-hidden="true" />查看报告
          </button>
        </el-tooltip>
      </div>
    </header>

    <span class="web-run-monitor-view__live" role="status" aria-live="polite" aria-atomic="true">{{ liveAnnouncement }}</span>

    <div class="web-run-monitor-view__counts" aria-label="运行计数">
      <span v-for="item in countItems" :key="item.id" :data-testid="`web-run-count-${item.id}`" :class="item.className">
        <component :is="item.icon" :size="14" aria-hidden="true" />{{ item.label }}
      </span>
    </div>

    <div class="web-run-monitor-view__canvas">
      <section class="web-run-monitor-view__live-area">
        <section class="web-run-monitor-view__screen" aria-label="实时页面截图">
          <header><Camera :size="17" aria-hidden="true" /><strong>页面截图</strong><span>{{ currentTimestamp }}</span></header>
          <div class="web-run-monitor-view__visual">
            <img v-if="displayedScreenshotUrl" :src="displayedScreenshotUrl" alt="Web 自动化实时页面截图">
            <p v-else>{{ screenshotWaitingText }}</p>
          </div>
        </section>

        <dl class="web-run-monitor-view__details" aria-label="当前执行详情">
          <div><dt>当前阶段</dt><dd>{{ view.phaseLabel }}</dd></div>
          <div><dt>当前用例</dt><dd data-testid="current-web-run-case" class="web-run-monitor-view__wrap-text">{{ snapshot.currentCase?.name || '暂无当前用例' }}</dd></div>
          <div><dt>当前步骤</dt><dd>{{ snapshot.currentStep?.name || '暂无当前步骤' }}</dd></div>
          <div><dt>当前页面</dt><dd data-testid="current-web-run-url" class="web-run-monitor-view__wrap-text">{{ snapshot.currentPageUrl || '暂无页面地址' }}</dd></div>
          <div><dt>动作</dt><dd>{{ currentAction }}</dd></div>
          <div><dt>更新时间</dt><dd>{{ currentTimestamp }}</dd></div>
          <div data-testid="latest-web-run-diagnostic" class="web-run-monitor-view__diagnostic"><dt><Stethoscope :size="14" aria-hidden="true" />最新诊断</dt><dd class="web-run-monitor-view__wrap-text">{{ latestDiagnostic }}</dd></div>
        </dl>

        <section class="web-run-monitor-view__results" aria-label="已完成结果">
          <header><strong>已完成结果</strong><span>{{ snapshot.results.length }}</span></header>
          <table v-if="snapshot.results.length > 0">
            <thead><tr><th>用例</th><th>页面</th><th>状态</th><th>耗时</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="row in snapshot.results" :key="row.caseId">
                <td><strong>{{ row.caseId }}</strong><span>{{ row.name }}</span></td>
                <td><strong>{{ row.pageName }}</strong><span>{{ row.module }}</span></td>
                <td>
                  <span :data-testid="`web-run-result-status-${row.caseId}`" class="web-run-monitor-view__result-status" :class="`is-${row.status}`">
                    <component :is="statusIcon(row.status)" :size="14" aria-hidden="true" />{{ statusLabel(row.status) }}
                  </span>
                </td>
                <td>{{ durationLabel(row.durationMs) }}</td>
                <td>
                  <div class="web-run-monitor-view__row-actions">
                    <el-tooltip content="查看日志">
                      <button type="button" :aria-label="`查看 Web 用例 ${row.caseId} 日志`" @click="emit('openLog', row.caseId)">
                        <ScrollText :size="16" aria-hidden="true" />
                      </button>
                    </el-tooltip>
                    <el-tooltip v-if="row.status === 'failed' || row.status === 'error'" content="转缺陷">
                      <button type="button" :aria-label="`将 Web 用例 ${row.caseId} 转为缺陷`" @click="emit('transferDefect', row.caseId)">
                        <Bug :size="16" aria-hidden="true" />
                      </button>
                    </el-tooltip>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
          <p v-else>暂无完成结果</p>
        </section>
      </section>

      <aside class="web-run-monitor-view__timeline" aria-label="执行时间线" tabindex="0">
        <header><TerminalSquare :size="17" aria-hidden="true" /><strong>执行轨迹</strong></header>
        <section v-for="group in timelineGroups" :key="group.phase" class="web-run-monitor-view__timeline-group">
          <h2>{{ group.label }}</h2>
          <ol v-if="group.items.length > 0">
            <li v-for="item in group.items" :key="item.id" :class="`is-${item.status}`">
              <span :data-testid="`web-run-timeline-status-${item.id}`">
                <component :is="statusIcon(item.status)" :size="13" aria-hidden="true" />{{ statusLabel(item.status) }}
              </span>
              <strong>{{ item.name }}</strong>
              <small>{{ item.action }} · {{ durationLabel(item.durationMs) }}</small>
              <em v-if="item.errorMessage">{{ item.errorMessage }}</em>
            </li>
          </ol>
          <p v-else>等待阶段开始</p>
        </section>
      </aside>
    </div>
  </section>
</template>

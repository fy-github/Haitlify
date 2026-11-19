<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  Activity,
  TriangleAlert,
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
import type { WebAutomationRunResult, WebAutomationRunStatus } from '../../domain/webAutomation'
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

type QueueStatus = WebAutomationRunStatus | 'running' | 'pending'
type MonitorPane = 'queue' | 'screen' | 'log'

interface MonitorQueueRow {
  key: string
  caseId?: string
  name: string
  module?: string
  pageName?: string
  startUrl?: string
  status: QueueStatus
  durationMs?: number
  loopLabel?: string
  result?: WebAutomationRunResult
}

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

/* ── 执行队列：全部来自真实快照（results / currentCase / 待执行计数） ────────── */
const loopLabelOf = (caseId?: string) => {
  const match = caseId?.match(/#(\d+)$/)
  return match ? `第 ${match[1]} 轮` : ''
}

const queueRows = computed<MonitorQueueRow[]>(() => {
  const rows: MonitorQueueRow[] = props.snapshot.results.map((row) => ({
    key: row.caseId,
    caseId: row.caseId,
    name: row.name,
    module: row.module,
    pageName: row.pageName,
    startUrl: row.startUrl,
    status: row.status,
    durationMs: row.durationMs,
    loopLabel: loopLabelOf(row.caseId),
    result: row
  }))
  const currentCase = props.snapshot.currentCase
  if (currentCase && !rows.some((row) => row.caseId === currentCase.id)) {
    rows.push({
      key: currentCase.id,
      caseId: currentCase.id,
      name: currentCase.name,
      status: 'running',
      loopLabel: loopLabelOf(currentCase.id)
    })
  }
  if (pendingCount.value > 0) {
    rows.push({
      key: 'pending',
      name: `待执行 ${pendingCount.value} 条`,
      status: 'pending'
    })
  }
  return rows
})

const defaultSelectionKey = () =>
  props.snapshot.currentCase?.id
  || props.snapshot.results[props.snapshot.results.length - 1]?.caseId
  || 'pending'

const selectedKey = ref(defaultSelectionKey())
const selectedRow = computed(() => queueRows.value.find((row) => row.key === selectedKey.value))
const selectedIsRunning = computed(() => selectedRow.value?.status === 'running')
const screenshotScopeLabel = computed(() => selectedIsRunning.value
  ? '本次运行最新截图'
  : '本次运行最新截图 · 非所选用例的独立截图')

/* ── 步骤与日志：运行中用例显示实时轨迹，已完成用例显示该结果自带步骤 ───────── */
const buildGroups = (items: WebRunTimelineItem[]) => [
  { phase: 'setup', label: '环境准备', items: items.filter((item) => item.phase === 'setup') },
  { phase: 'test', label: '用例执行', items: items.filter((item) => item.phase === 'test') },
  { phase: 'teardown', label: '环境清理', items: items.filter((item) => item.phase === 'teardown') }
]

const resultTimeline = (row?: MonitorQueueRow): WebRunTimelineItem[] =>
  (row?.result?.stepLogs || []).map((log) => ({
    id: log.stepId,
    name: log.name,
    action: log.action,
    phase: log.phase || 'test',
    status: log.status,
    durationMs: log.durationMs,
    errorMessage: log.errorMessage
  }))

const visibleLogGroups = computed(() => {
  const row = selectedRow.value
  if (!row) return undefined
  if (row.status === 'running') return buildGroups(displayedTimeline.value)
  if (row.result) {
    const items = resultTimeline(row)
    return items.length > 0 ? buildGroups(items) : undefined
  }
  return undefined
})

const logEmptyText = computed(() => {
  if (!selectedRow.value) return '未选择执行队列中的用例'
  if (selectedRow.value.status === 'pending') {
    return '该用例尚未执行，暂无步骤日志。不会用假步骤或假截图冒充执行证据。'
  }
  return '该结果未包含步骤日志。'
})

/* ── 独立详情抽屉：只使用真实 case / result 字段 ───────────────────────────── */
const detailVisible = ref(false)
const detailRow = computed(() => selectedRow.value)
const detailStepLogs = computed(() => detailRow.value?.result?.stepLogs || [])
const detailConsoleErrors = computed(() => detailRow.value?.result?.consoleErrors || [])
const detailPageErrors = computed(() => detailRow.value?.result?.pageErrors || [])
const detailFailedRequests = computed(() => detailRow.value?.result?.failedRequests || [])
const detailScreenshotPath = computed(() => detailRow.value?.result?.screenshotPath || '')
const detailCanTransferDefect = computed(() =>
  detailRow.value?.status === 'failed' || detailRow.value?.status === 'error'
)
const detailOpenable = computed(() => Boolean(selectedRow.value))

const openDetail = () => {
  if (!detailOpenable.value) return
  detailVisible.value = true
}

const pane = ref<MonitorPane>('screen')
const setPane = (next: MonitorPane) => {
  pane.value = next
}

const phaseName = (phase: WebRunTimelineItem['phase']) => ({ setup: '环境准备', test: '用例执行', teardown: '环境清理' }[phase])

const statusLabel = (status: WebAutomationRunResult['status'] | WebRunTimelineItem['status'] | QueueStatus) => (queueStatusLabels[status])

const queueStatusLabels: Record<QueueStatus, string> = {
  passed: '通过',
  failed: '失败',
  error: '错误',
  running: '运行中',
  pending: '待执行'
}

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
  selectedKey.value = defaultSelectionKey()
  detailVisible.value = false
})

watch(() => props.snapshot.currentCase?.id, () => {
  // 新一轮当前用例出现时跟随选中，但保留用户手动选中的已完成结果。
  const currentKey = props.snapshot.currentCase?.id
  if (!currentKey) return
  if (!selectedRow.value || selectedRow.value.status === 'running') selectedKey.value = currentKey
})

watch(() => props.snapshot.latestScreenshotUrl, (url) => {
  if (url) lastScreenshotUrl.value = url
}, { immediate: true })

watch(() => props.view.timeline, (timeline) => {
  if (timeline.length > 0) lastTimeline.value = timeline
}, { immediate: true })
</script>

<template>
  <section class="web-run-monitor-view web-ed-monitor" aria-label="Web 运行监控">
    <header class="web-ed-monitor__head">
      <div class="web-ed-monitor__identity">
        <h1 class="web-ed-monitor__title">Web 运行监控</h1>
        <p class="web-ed-monitor__identity-line">
          <span>{{ snapshot.runId }}</span><span>{{ snapshot.mode }}</span><span>{{ snapshot.selectedBrowser }}</span>
        </p>
      </div>

      <div class="web-ed-monitor__actions" aria-label="运行控制">
        <button type="button" data-testid="back-to-preparation" @click="emit('returnWorkbench')">
          返回执行准备
        </button>
        <button
          v-if="canPause"
          type="button"
          data-testid="pause-web-run"
          data-control-testid="pause"
          :disabled="controlLoading === 'pause'"
          @click="emit('pause')"
        >
          <Pause :size="14" aria-hidden="true" />暂停
        </button>
        <button
          v-if="canResume"
          type="button"
          data-testid="resume-web-run"
          data-control-testid="resume"
          :disabled="controlLoading === 'resume'"
          @click="emit('resume')"
        >
          <Play :size="14" aria-hidden="true" />继续执行
        </button>
        <button
          v-if="canStop"
          type="button"
          data-testid="stop-web-run"
          data-control-testid="stop"
          :disabled="controlLoading === 'stop'"
          @click="emit('stop')"
        >
          <Square :size="13" aria-hidden="true" />停止
        </button>
        <button v-if="terminal" type="button" data-testid="rerun-web-run" data-control-testid="rerun" @click="emit('rerun')">
          <RotateCcw :size="14" aria-hidden="true" />重新执行
        </button>
        <button v-if="terminal" type="button" data-testid="open-web-run-report" data-control-testid="report" @click="emit('openReport')">
          <ScrollText :size="14" aria-hidden="true" />查看报告
        </button>
      </div>
    </header>

    <span class="web-ed-monitor__live" role="status" aria-live="polite" aria-atomic="true">{{ liveAnnouncement }}</span>

    <section class="web-ed-monitor__summary" aria-label="执行进度">
      <div class="web-ed-monitor__summary-top">
        <span data-testid="web-run-lifecycle-status" class="web-ed-monitor__status" :class="`is-${view.statusType}`">
          <component :is="lifecycleIcon" :size="14" aria-hidden="true" />{{ view.statusLabel }}
        </span>
        <div class="web-ed-monitor__counts" aria-label="运行计数">
          <span v-for="item in countItems" :key="item.id" :data-testid="`web-run-count-${item.id}`" :class="item.className">
            <component :is="item.icon" :size="13" aria-hidden="true" />{{ item.label }}
          </span>
        </div>
      </div>
      <div
        class="web-ed-monitor__bar"
        role="progressbar"
        aria-label="用例执行次数"
        aria-valuemin="0"
        aria-valuemax="100"
        :aria-valuenow="view.progressPercent"
      >
        <span :style="{ width: `${view.progressPercent}%` }" />
      </div>
      <p class="web-ed-monitor__meta" aria-label="运行状态">
        <span data-testid="web-run-connection-status" :class="`is-${connectionState}`">
          <component :is="connectionIcon" :size="13" aria-hidden="true" />{{ connectionLabel }}
        </span>
        <span>{{ elapsed }}</span>
        <span>完成 {{ snapshot.progress.completedCases }} / {{ snapshot.progress.totalCases }} 次用例执行</span>
      </p>
    </section>

    <div class="web-ed-monitor__body">
      <div class="web-ed-monitor__pane-nav" role="group" aria-label="监控区域切换" data-testid="web-monitor-pane-nav">
        <button
          v-for="option in ([
            { id: 'queue', label: '执行队列' },
            { id: 'screen', label: '页面截图' },
            { id: 'log', label: '步骤与日志' }
          ] as const)"
          :key="option.id"
          type="button"
          :data-testid="`web-monitor-pane-${option.id}`"
          :aria-pressed="pane === option.id"
          @click="setPane(option.id)"
        >{{ option.label }}</button>
      </div>

      <div class="web-ed-monitor__grid" :data-pane="pane" data-testid="web-monitor-grid">
        <section class="web-ed-monitor__panel web-ed-monitor__queue" aria-label="执行队列" data-testid="web-monitor-queue">
          <header class="web-ed-monitor__panel-head">
            <div>
              <h2>执行队列</h2>
              <small>{{ queueRows.length }} 项 · 共 {{ snapshot.progress.totalCases }} 次执行</small>
            </div>
          </header>
          <div class="web-ed-monitor__queue-scroll">
            <div
              v-for="row in queueRows"
              :key="row.key"
              class="web-ed-monitor__queue-item web-ed-queue-item"
              :class="[`is-${row.status}`, { 'is-active': row.key === selectedKey }]"
              :data-testid="`web-monitor-queue-${row.key}`"
            >
              <button
                type="button"
                class="web-ed-queue-item__select"
                :aria-pressed="row.key === selectedKey"
                @click="selectedKey = row.key"
              >
                <span class="web-ed-queue-item__head">
                  <strong>{{ row.caseId || row.name }}</strong>
                  <span :data-testid="row.caseId ? `web-run-result-status-${row.caseId}` : undefined" class="web-ed-queue-item__status">
                    <component :is="row.status === 'pending' ? Clock3 : statusIcon(row.status)" :size="12" aria-hidden="true" />
                    {{ statusLabel(row.status) }}
                  </span>
                </span>
                <span class="web-ed-queue-item__name">{{ row.name }}</span>
                <small class="web-ed-queue-item__meta">
                  <span v-if="row.loopLabel">{{ row.loopLabel }}</span>
                  <span v-if="row.module">{{ row.module }} · {{ row.pageName }}</span>
                  <span v-if="row.durationMs !== undefined">{{ durationLabel(row.durationMs) }}</span>
                  <span v-if="row.status === 'pending'">运行快照未返回待执行用例身份</span>
                </small>
              </button>
              <div v-if="row.result" class="web-ed-queue-item__actions">
                <button type="button" :aria-label="`查看 Web 用例 ${row.caseId} 日志`" @click="emit('openLog', row.caseId as string)">
                  <ScrollText :size="14" aria-hidden="true" />
                </button>
                <button
                  v-if="row.status === 'failed' || row.status === 'error'"
                  type="button"
                  :aria-label="`将 Web 用例 ${row.caseId} 转为缺陷`"
                  @click="emit('transferDefect', row.caseId as string)"
                >
                  <Bug :size="14" aria-hidden="true" />
                </button>
              </div>
            </div>
            <p v-if="queueRows.length === 0" class="web-ed-monitor__empty">执行队列为空</p>
          </div>
        </section>

        <section class="web-ed-monitor__panel web-ed-monitor__screen" aria-label="页面截图" data-testid="web-monitor-screen">
          <header class="web-ed-monitor__panel-head">
            <div>
              <h2>页面截图</h2>
              <small>{{ currentTimestamp }}</small>
            </div>
            <Camera :size="16" aria-hidden="true" />
          </header>
          <div class="web-ed-monitor__screen-body">
            <div class="web-ed-monitor__visual">
              <img v-if="displayedScreenshotUrl" :src="displayedScreenshotUrl" alt="Web 自动化实时页面截图">
              <p v-else>{{ screenshotWaitingText }} · 不会用绘制的假页面冒充实时截图</p>
            </div>
            <p class="web-ed-monitor__screen-scope">
              {{ screenshotScopeLabel }}<span v-if="snapshot.latestScreenshotAt"> · 画面时间 {{ snapshot.latestScreenshotAt }}</span>
            </p>
            <dl class="web-ed-monitor__current">
              <div><dt>当前阶段</dt><dd>{{ view.phaseLabel }}</dd></div>
              <div><dt>当前用例</dt><dd data-testid="current-web-run-case" class="web-run-monitor-view__wrap-text">{{ snapshot.currentCase?.name || '暂无当前用例' }}</dd></div>
              <div><dt>当前步骤</dt><dd>{{ snapshot.currentStep?.name || '暂无当前步骤' }}</dd></div>
              <div><dt>页面地址</dt><dd data-testid="current-web-run-url" class="web-run-monitor-view__wrap-text">{{ snapshot.currentPageUrl || '暂无页面地址' }}</dd></div>
              <div><dt>动作</dt><dd>{{ currentAction }}</dd></div>
              <div><dt>更新时间</dt><dd>{{ currentTimestamp }}</dd></div>
              <div data-testid="latest-web-run-diagnostic" class="web-ed-monitor__diagnostic">
                <dt><Stethoscope :size="13" aria-hidden="true" />最新诊断</dt>
                <dd class="web-run-monitor-view__wrap-text">{{ latestDiagnostic }}</dd>
              </div>
            </dl>
          </div>
        </section>

        <section class="web-ed-monitor__panel web-ed-monitor__log" aria-label="步骤与日志" data-testid="web-monitor-log">
          <header class="web-ed-monitor__panel-head">
            <div>
              <h2>步骤与日志</h2>
              <p class="web-ed-monitor__panel-name" data-testid="web-monitor-panel-name">
                {{ selectedRow ? `${selectedRow.caseId || ''} ${selectedRow.name}`.trim() : '未选择用例' }}
              </p>
            </div>
            <button
              type="button"
              class="web-ed-monitor__details"
              data-testid="open-web-run-detail"
              :disabled="!detailOpenable"
              @click="openDetail"
            >详情 ↗</button>
          </header>
          <div class="web-ed-monitor__log-scroll" tabindex="0" role="region" aria-label="所选用例步骤与日志">
            <template v-if="visibleLogGroups">
              <section v-for="group in visibleLogGroups" :key="group.phase" class="web-ed-monitor__log-group">
                <h3>{{ group.label }}</h3>
                <ol v-if="group.items.length > 0">
                  <li v-for="item in group.items" :key="item.id" :class="`is-${item.status}`">
                    <span :data-testid="`web-run-timeline-status-${item.id}`">
                      <component :is="statusIcon(item.status)" :size="12" aria-hidden="true" />{{ statusLabel(item.status) }}
                    </span>
                    <strong>{{ item.name }}</strong>
                    <small>{{ item.action }} · {{ durationLabel(item.durationMs) }}</small>
                    <em v-if="item.errorMessage">{{ item.errorMessage }}</em>
                  </li>
                </ol>
                <p v-else>等待阶段开始</p>
              </section>
            </template>
            <p v-else class="web-ed-monitor__empty">{{ logEmptyText }}</p>
          </div>
        </section>
      </div>
    </div>

    <el-drawer v-model="detailVisible" class="web-ed-detail-drawer" :with-header="false" size="520px" direction="rtl">
      <div class="web-ed-detail" data-testid="web-run-detail">
        <header class="web-ed-drawer__head">
          <div>
            <p class="web-ed-kicker">{{ detailRow?.caseId || '未选择用例' }}</p>
            <h2 class="web-ed-drawer__title">{{ detailRow?.name || '用例详情' }}</h2>
            <p class="web-ed-drawer__sub">内容取自本次运行快照的用例与结果字段，不含推测数据。</p>
          </div>
          <button
            type="button"
            class="web-ed-drawer__close"
            data-testid="close-web-run-detail"
            aria-label="关闭用例详情"
            @click="detailVisible = false"
          >×</button>
        </header>

        <div class="web-ed-detail__body">
          <dl class="web-ed-detail__list">
            <div><dt>执行状态</dt><dd>{{ detailRow ? statusLabel(detailRow.status) : '未知' }}</dd></div>
            <div v-if="detailRow?.loopLabel"><dt>循环轮次</dt><dd>{{ detailRow.loopLabel }}</dd></div>
            <div v-if="detailRow?.result"><dt>耗时</dt><dd>{{ durationLabel(detailRow.result.durationMs) }}</dd></div>
            <div v-if="detailRow?.module"><dt>模块</dt><dd>{{ detailRow.module }}</dd></div>
            <div v-if="detailRow?.pageName"><dt>页面</dt><dd>{{ detailRow.pageName }}</dd></div>
            <div v-if="detailRow?.startUrl"><dt>起始地址</dt><dd class="web-run-monitor-view__wrap-text">{{ detailRow.startUrl }}</dd></div>
            <div v-if="detailRow?.result?.failedStepId"><dt>失败步骤</dt><dd>{{ detailRow.result.failedStepId }}</dd></div>
            <div v-if="detailRow?.result?.errorMessage"><dt>错误信息</dt><dd class="web-run-monitor-view__wrap-text">{{ detailRow.result.errorMessage }}</dd></div>
          </dl>

          <section class="web-ed-detail__section">
            <h3>步骤日志</h3>
            <ol v-if="detailStepLogs.length" class="web-ed-detail__steps">
              <li v-for="log in detailStepLogs" :key="log.stepId" :class="`is-${log.status}`">
                <span>{{ phaseName(log.phase || 'test') }}</span>
                <strong>{{ log.name }}</strong>
                <small>{{ log.action }} · {{ durationLabel(log.durationMs) }}</small>
                <em v-if="log.errorMessage">{{ log.errorMessage }}</em>
              </li>
            </ol>
            <p v-else>该结果未包含步骤日志。</p>
          </section>

          <section v-if="detailConsoleErrors.length || detailPageErrors.length || detailFailedRequests.length" class="web-ed-detail__section">
            <h3>页面与控制台异常</h3>
            <ul class="web-ed-detail__errors">
              <li v-for="message in detailConsoleErrors" :key="`console-${message}`">控制台：{{ message }}</li>
              <li v-for="message in detailPageErrors" :key="`page-${message}`">页面异常：{{ message }}</li>
              <li v-for="message in detailFailedRequests" :key="`request-${message}`">失败请求：{{ message }}</li>
            </ul>
          </section>

          <section v-if="detailScreenshotPath" class="web-ed-detail__section">
            <h3>截图证据</h3>
            <p class="web-run-monitor-view__wrap-text">{{ detailScreenshotPath }}</p>
            <p>证据路径由 Runner 返回，未在此渲染为实时截图。</p>
          </section>

          <p v-if="detailRow?.status === 'pending'">
            该用例尚未执行：没有步骤日志、响应或截图证据。
          </p>
        </div>

        <footer class="web-ed-detail__foot">
          <button type="button" class="web-ed-btn" data-testid="detail-open-log" @click="detailRow?.caseId && emit('openLog', detailRow.caseId)">
            <TerminalSquare :size="14" aria-hidden="true" />执行日志
          </button>
          <div class="web-ed-detail__foot-actions">
            <button
              v-if="detailCanTransferDefect && detailRow?.caseId"
              type="button"
              class="web-ed-btn"
              :aria-label="`将 Web 用例 ${detailRow.caseId} 转为缺陷`"
              @click="emit('transferDefect', detailRow.caseId)"
            >转缺陷</button>
            <button type="button" class="web-ed-btn web-ed-btn--primary" @click="detailVisible = false">关闭详情</button>
          </div>
        </footer>
      </div>
    </el-drawer>
  </section>
</template>

<style src="../../styles/run-monitor-controls.css"></style>

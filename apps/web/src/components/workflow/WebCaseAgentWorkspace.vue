<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { TriangleAlert, CircleCheck, CircleDot, GitCompareArrows, CirclePause, RefreshCcw, Square, Star, TrendingUp } from '@lucide/vue'
import { getWebCaseQualityRating, type WebCaseAgentEvent, type WebCaseAgentRun, type WebCaseAgentStage, type WebCaseAgentStatus, type WebCaseQualityRating, type WebExplorationEventDetails } from '../../domain/webCaseAgentRepository'
import WebCaseQualityRatingDialog from './WebCaseQualityRatingDialog.vue'
import WebCaseRegressionComparisonDialog from './WebCaseRegressionComparisonDialog.vue'
import WebCaseQualityInsightDialog from './WebCaseQualityInsightDialog.vue'
import LocatorRepairReviewDialog from './LocatorRepairReviewDialog.vue'

const props = defineProps<{ run?: WebCaseAgentRun; events: WebCaseAgentEvent[]; naturalPlanCount?: number; loading?: boolean }>()
const emit = defineEmits<{ cancel: []; retry: []; resume: []; openDetails: [WebCaseAgentEvent] }>()
const stageLabels: Record<WebCaseAgentStage, string> = {
  PREFLIGHT: '环境预检',
  PLANNING: '测试设计',
  EVIDENCE: '素材取证',
  GENERATION: '自动化编译',
  VALIDATION: '确定性校验',
  DELIVERY: '交付'
}
// 头部状态码中文展示:状态是后端 DSL 值,仅做展示层映射,不改领域模型(词汇对齐 WebRunMonitorView/AiRunSummaryView)。
const statusLabels: Record<WebCaseAgentStatus, string> = {
  queued: '排队中',
  running: '运行中',
  paused: '已暂停',
  succeeded: '已完成',
  waiting_review: '等待人工确认',
  failed: '失败',
  blocked: '已阻塞',
  partial: '部分完成',
  cancelled: '已取消'
}
const runStatusLabel = (): string => (props.run?.status ? statusLabels[props.run.status] : '空闲')
const stageDescriptions: Record<WebCaseAgentStage, string> = {
  PREFLIGHT: '确认测试点、规则和运行配置',
  PLANNING: '先生成标题、自然语言步骤和业务预期',
  EVIDENCE: '优先查素材库，缺失时打开真实页面采集元素',
  GENERATION: '根据自然语言用例和页面证据生成 DSL',
  VALIDATION: '校验 viewport、locator 来源和页面断言',
  DELIVERY: '保存可评审 Web 自动化用例'
}
const stageOrder: WebCaseAgentStage[] = ['PREFLIGHT', 'PLANNING', 'EVIDENCE', 'GENERATION', 'VALIDATION', 'DELIVERY']
// 用户 2026-10-14 指正：本工作区去掉「测试设计」流水线卡与两阶段说明栏——该阶段由前置流程完成，
// 这里只读取已评审计划。阶段索引仍按完整顺序计算，保证其余卡片 done/active/blocked 状态不变。
const displayedStages = stageOrder.filter((stage) => stage !== 'PLANNING')
const stageLabel = computed(() => stageLabels[props.run?.currentStage || 'PREFLIGHT'])
const currentStageIndex = computed(() => stageOrder.indexOf(props.run?.currentStage || 'PREFLIGHT'))
const stageItems = computed(() => displayedStages.map((stage) => {
  const index = stageOrder.indexOf(stage)
  return {
    stage,
    label: stageLabels[stage],
    description: stageDescriptions[stage],
    state: (props.run?.status === 'blocked' || props.run?.status === 'partial') && stage === props.run.currentStage
      ? 'blocked'
      : index < currentStageIndex.value || props.run?.status === 'succeeded'
        ? 'done'
        : index === currentStageIndex.value
          ? 'active'
          : 'pending'
  }
}))
const evidenceHitCount = computed(() => props.events.reduce((total, event) => total + (event.knowledgeHitIds?.length || 0), 0))
const browserEvidenceCount = computed(() => props.events.filter((event) => event.toolName === 'browser-discovery' || event.summary.includes('浏览器')).length)
const explorationEvents = computed(() => props.events.filter((event) => event.details?.round != null))
const failureCategoryLabels: Record<string, string> = {
  auth_context_lost: '登录会话失效',
  login_failed: '自动登录失败',
  captcha_required: '验证码拦截',
  page_not_ready: '目标页面尚未就绪',
  wrong_page: '页面不匹配',
  network_blocked_critical: '关键业务请求被阻断',
  network_blocked_unknown: '未知请求被阻断',
  business_api_failed: '业务接口失败',
  ambiguous_locator: '定位器存在歧义，等待重新取证',
  element_not_found: '未找到目标元素',
  unsafe_overlay: '存在不安全遮罩',
  unsupported_dom: 'DOM 不支持',
  navigation_failed: '页面跳转失败',
  unsafe_action: '动作存在风险，未执行',
  locator_persistence: '定位器无法稳定命中',
  unsupported_action: '不支持该步骤动作',
  action_failed: '动作执行失败',
  pagination_not_changed: '翻页后列表未变化',
  no_page_change: '页面未发生预期变化',
  page_error: '页面脚本报错',
  duration_exhausted: '探索时长耗尽',
  manual_review_required: '人工复核',
  unsupported_frame: '存在不可观测的 iframe',
  unsupported_shadow_dom: '存在不可观测的 shadow DOM',
  unsupported_action_target: '动作目标不是可执行的地址或元素',
  invalid_action_ir: 'Action IR 不合法',
  action_evidence_missing: '缺少该步骤的浏览器证据',
  action_evidence_mismatch: '证据与当前页面/动作不一致',
  assertion_failed: '业务断言失败',
  observation_failed: '页面观察失败',
  settle_timeout: '页面状态未在预期时间内稳定',
  session_busy: '浏览器会话被占用'
}
const normalizeFailureCategory = (category?: unknown): string => {
  switch (String(category ?? '').trim()) {
    case 'network_blocked':
    case 'network_blocked_optional':
      return 'network_blocked_unknown'
    case 'page_error':
    case 'no_safe_action':
    case 'unsafe_repair_category':
      return 'unsupported_dom'
    case 'manual_required':
      return 'manual_review_required'
    case 'round_budget_exhausted':
    case 'same_failure_limit':
    case 'observation_stagnated':
      return 'duration_exhausted'
    default:
      return String(category ?? '').trim()
  }
}
const eventFailureCategory = (event?: WebCaseAgentEvent): string => {
  const details = event?.details
  if (!details) return ''
  if (typeof details.category === 'string') return normalizeFailureCategory(details.category)
  const failure = details.failure
  return failure && typeof failure === 'object' && typeof (failure as Record<string, unknown>).category === 'string'
    ? normalizeFailureCategory(String((failure as Record<string, unknown>).category))
    : ''
}
const eventRetryable = (event?: WebCaseAgentEvent): boolean => {
  const details = event?.details
  if (!details) return false
  if (typeof details.retryable === 'boolean') return details.retryable
  const failure = details.failure
  return Boolean(failure && typeof failure === 'object' && (failure as Record<string, unknown>).retryable === true)
}
const failureLabel = (event?: WebCaseAgentEvent): string => {
  const category = eventFailureCategory(event)
  if (!category) return ''
  const label = failureCategoryLabels[category] || category
  return (category.startsWith('network_blocked') || category === 'page_not_ready' || category === 'auth_context_lost') && eventRetryable(event) ? `${label} · 可重试` : label
}
const manualTakeoverPrompt = (event?: WebCaseAgentEvent): string => {
  const category = eventFailureCategory(event)
  if (category === 'login_failed') return '需人工重新登录接管'
  if (category === 'auth_context_lost' && (!eventRetryable(event) || event?.details?.handoff?.required)) return '需人工重新登录接管'
  if (category === 'captcha_required' || category === 'manual_review_required' || category === 'unsupported_dom') return '需人工接管'
  return ''
}
const failureEvents = computed(() => props.events.filter((event) => eventFailureCategory(event)))
const latestFailureEvent = computed(() => failureEvents.value[failureEvents.value.length - 1])
const latestPointFailureEvent = computed(() => {
  const events = failureEvents.value.filter((event) => event.pointId)
  return events[events.length - 1]
})
const latestDiagnosticEvent = computed(() => latestPointFailureEvent.value || latestFailureEvent.value || explorationEvents.value[explorationEvents.value.length - 1])
const scoreValue = (details: WebExplorationEventDetails | null | undefined, key: 'page' | 'element' | 'state' | 'total'): number | string => {
  const scores = details?.scores
  const score = scores && typeof scores === 'object' ? scores[key] ?? scores[`${key}Score`] : undefined
  const legacyScore = details?.[`${key}Score`]
  return typeof score === 'number' || typeof legacyScore === 'number' ? score ?? legacyScore ?? '-' : '-'
}
const shortPageUrl = (url?: string | null): string => {
  if (!url) return ''
  try {
    const parsed = new URL(url)
    const hash = parsed.hash.startsWith('#/') ? parsed.hash.split(/[?&=]/, 1)[0] : ''
    return `${parsed.pathname}${hash}` || parsed.hostname
  } catch {
    const safePath = url.split(/[?#]/, 1)[0].replace(/^(https?:\/\/)[^/@]+@/i, '$1')
    return safePath.length > 48 ? `${safePath.slice(0, 45)}...` : safePath
  }
}
const safePageUrlForTitle = (url?: string | null): string => {
  if (!url) return ''
  try {
    const parsed = new URL(url)
    const hash = parsed.hash.startsWith('#/') ? parsed.hash.split(/[?&=]/, 1)[0] : ''
    return `${parsed.origin}${parsed.pathname}${hash}`
  } catch {
    return url.split(/[?#]/, 1)[0].replace(/^(https?:\/\/)[^/@]+@/i, '$1')
  }
}
const safeDetailText = (value: unknown): string => String(value ?? '')
  .replace(/[\u0000-\u001f\u007f]/g, '')
  .replace(/https?:\/\/[^\s?#]+[^\s]*/gi, (url) => safePageUrlForTitle(url))
  .replace(/\b[^\s,;:]+:[^\s,;@]+@[^\s,;]+/g, '[redacted-url]')
  .replace(/\b(?:[a-z0-9-]+\.)+[a-z]{2,}(?:\/[^\s,;]*)?/gi, (url) => url.split(/[?#]/, 1)[0])
  .replace(/["']?\b(authorization|cookie|password|passwd|token|apikey|secretkey|secret|credential)["']?\s*[:=]\s*(?:"[^"]*"|'[^']*'|\{[^}]*\}|\[[^\]]*\}|Bearer\s+[^\s,;]+(?:\s+[^\s,;]+)?|[^\s,;]+)/gi, '$1=[redacted]')
  .replace(/["']?\b(locator|selector)["']?\s*[:=]\s*(?:"[^"]*"|'[^']*'|\{[^}]*\}|\[[^\]]*\]|[^\s,;]+)/gi, '$1=[redacted]')
const safeNetworkEntry = (entry: Record<string, unknown>): Record<string, unknown> => {
  const output: Record<string, unknown> = {}
  if (typeof entry.origin === 'string') output.origin = safeDetailText(entry.origin)
  if (typeof entry.path === 'string') output.path = safeDetailText(entry.path.split(/[?#]/, 1)[0])
  if (typeof entry.resourceType === 'string') output.resourceType = safeDetailText(entry.resourceType)
  if (typeof entry.criticality === 'string') output.criticality = safeDetailText(entry.criticality)
  if (typeof entry.failureType === 'string') output.failureType = normalizeFailureCategory(entry.failureType)
  return output
}
const safeNetworkForTitle = (network?: WebExplorationEventDetails['network']): Record<string, unknown> | undefined => {
  if (!network) return undefined
  const output: Record<string, unknown> = {}
  ;(['criticalBlocked', 'optionalBlocked', 'unknownBlocked', 'authDependencies'] as const).forEach((key) => {
    const entries = network[key]
    if (!Array.isArray(entries) || !entries.length) return
    output[key] = entries.slice(0, 2).map((entry) => (entry && typeof entry === 'object' ? safeNetworkEntry(entry as Record<string, unknown>) : {}))
  })
  return Object.keys(output).length ? output : undefined
}
const safeHandoffForTitle = (handoff?: WebExplorationEventDetails['handoff']): Record<string, unknown> | undefined => {
  if (!handoff) return undefined
  const output: Record<string, unknown> = {}
  if (typeof handoff.required === 'boolean') output.required = handoff.required
  if (typeof handoff.mode === 'string') output.mode = safeDetailText(handoff.mode)
  if (typeof handoff.reason === 'string') output.reason = normalizeFailureCategory(handoff.reason) || safeDetailText(handoff.reason)
  if (typeof handoff.resumeSupported === 'boolean') output.resumeSupported = handoff.resumeSupported
  return Object.keys(output).length ? output : undefined
}
const safeActionTargetLabel = (details?: WebExplorationEventDetails | null): string => {
  const target = details?.actionTarget
  if (!target) return ''
  if (/^https?:\/\//i.test(target)) return shortPageUrl(target)
  if (/^(#|\.|\[|\/\/|css=|xpath=|frame=|role=|test[Ii]d=|[a-z-]+\[)/i.test(target)) {
    const name = details?.accessibleName || details?.accessName
    return name ? `定位器[${safeDetailText(name)}]` : '定位器'
  }
  return safeDetailText(target)
}
const safeDetailsForTitle = (details: WebExplorationEventDetails): Record<string, unknown> => {
  const output: Record<string, unknown> = {}
  const failure = details.failure && typeof details.failure === 'object' && !Array.isArray(details.failure) ? details.failure as Record<string, unknown> : undefined
  const copyString = (key: keyof WebExplorationEventDetails, value = details[key]) => { if (typeof value === 'string' && value) output[key] = safeDetailText(value) }
  const copyNumber = (key: keyof WebExplorationEventDetails) => { if (typeof details[key] === 'number') output[key] = details[key] }
  copyString('category', normalizeFailureCategory(typeof details.category === 'string' ? details.category : failure?.category))
  if (typeof details.retryable === 'boolean' || typeof failure?.retryable === 'boolean') output.retryable = details.retryable ?? failure?.retryable
  copyString('failureReason', typeof details.failureReason === 'string' ? details.failureReason : failure?.failureReason)
  copyString('locatorFingerprint')
  copyString('accessibleName')
  copyString('accessName')
  const safePageUrl = safePageUrlForTitle(details.safePageUrl || details.pageUrl)
  if (safePageUrl) output.safePageUrl = safePageUrl
  copyString('actionType')
  const safeActionTarget = safeActionTargetLabel(details)
  if (safeActionTarget) output.actionTarget = safeActionTarget
  ;(['round', 'maxRounds', 'pageScore', 'elementScore', 'stateScore', 'totalScore'] as const).forEach(copyNumber)
  if (details.scores && typeof details.scores === 'object') {
    const scores: Record<string, unknown> = {}
    ;(['page', 'element', 'state', 'total', 'pageScore', 'elementScore', 'stateScore', 'totalScore'] as const).forEach((key) => {
      if (typeof details.scores?.[key] === 'number') scores[key] = details.scores[key]
    })
    if (Object.keys(scores).length) output.scores = scores
  }
  if (Array.isArray(details.repairTrail)) {
    output.repairTrail = details.repairTrail.slice(-5).map((item) => ({
      category: typeof item.category === 'string' ? item.category : '',
      round: typeof item.round === 'number' ? item.round : undefined,
      changedFingerprint: typeof item.changedFingerprint === 'boolean' ? item.changedFingerprint : undefined
    }))
  }
  const safeNetwork = safeNetworkForTitle(details.network)
  if (safeNetwork) output.network = safeNetwork
  const safeHandoff = safeHandoffForTitle(details.handoff)
  if (safeHandoff) output.handoff = safeHandoff
  return output
}
const eventDetailsTitle = (event: WebCaseAgentEvent): string => {
  if (!event.details || Object.keys(event.details).length === 0) return event.summary
  try {
    const safeDetails = safeDetailsForTitle(event.details)
    return Object.keys(safeDetails).length ? JSON.stringify(safeDetails) : event.summary
  } catch { return event.summary }
}
const failureDetailsTitle = (event: WebCaseAgentEvent): string => {
  const title = eventDetailsTitle(event)
  return title === event.summary ? event.summary : `${failureLabel(event)} · ${title}`
}
const explorationActionLabel = (action?: string | null): string => ({ 'menu-search': '菜单搜索', click: '点击', goto: '页面导航', navigate: '页面导航', wait: '等待', scroll: '滚动' }[action || ''] || action || '')
const eventStageLabel = (stage: WebCaseAgentStage): string => stageLabels[stage] || stage
const timeline = ref<HTMLElement>()
const ratingDialogVisible = ref(false)
const comparisonDialogVisible = ref(false)
const insightDialogVisible = ref(false)
const repairReviewVisible = ref(false)
const evidenceDialogVisible = ref(false)
const errorDialogVisible = ref(false)
const selectedEvidenceEvent = ref<WebCaseAgentEvent>()
const lastRating = ref<WebCaseQualityRating | null>(null)
const handleRated = (rating: WebCaseQualityRating) => { lastRating.value = rating }
// 刷新/切换 run 后回填既有评分,恢复「已评分/已固化基准」徽章与「重新评分」态;失败静默降级,不打断工作区。
watch(() => (props.run ? `${props.run.runId}:${props.run.status}` : ''), async (key) => {
  lastRating.value = null
  if (!key || props.run?.status !== 'succeeded') return
  try {
    lastRating.value = await getWebCaseQualityRating(props.run.runId)
  } catch {
    lastRating.value = null
  }
}, { immediate: true })
const formatEventTime = (occurredAt: string): string => {
  const parsed = new Date(occurredAt)
  if (Number.isNaN(parsed.getTime())) return occurredAt.replace(/\.\d+/, '').replace('T', ' ').replace(/Z$/, '')
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
  }).format(parsed).replace(/\//g, '-')
}
const openEventDetails = (event: WebCaseAgentEvent) => {
  if (event.stage === 'EVIDENCE' || event.toolName === 'browser-discovery' || event.summary.includes('素材')) {
    selectedEvidenceEvent.value = event
    evidenceDialogVisible.value = true
    return
  }
  selectedEvidenceEvent.value = event
  if (event.severity === 'error') errorDialogVisible.value = true
  emit('openDetails', event)
}
const latestEvidenceEvent = computed(() => {
  const evidenceEvents = props.events.filter((event) => event.stage === 'EVIDENCE' || event.toolName === 'browser-discovery' || event.summary.includes('素材'))
  return evidenceEvents[evidenceEvents.length - 1]
})
const openEvidenceStage = () => {
  if (latestEvidenceEvent.value) {
    openEventDetails(latestEvidenceEvent.value)
    return
  }
  selectedEvidenceEvent.value = undefined
  evidenceDialogVisible.value = true
}
const evidenceDetailsJson = computed(() => {
  const event = effectiveEvidenceEvent.value
  if (!event) return ''
  try { return JSON.stringify(event.details || {}, null, 2) } catch { return event.summary }
})
const effectiveEvidenceEvent = computed<WebCaseAgentEvent | undefined>(() => {
  const selected = selectedEvidenceEvent.value
  if (!selected) return undefined
  const relatedEvents = props.events.filter((event) =>
    event.stage === 'EVIDENCE'
      && event.pointId === selected.pointId
      && event.details?.screenshotUrl
  )
  const related = relatedEvents[relatedEvents.length - 1]
  if (!related || related.eventId === selected.eventId) return selected
  return {
    ...selected,
    details: { ...(selected.details || {}), ...(related.details || {}) },
    summary: related.summary || selected.summary,
    occurredAt: related.occurredAt,
    toolName: related.toolName || selected.toolName
  }
})
watch(() => props.events.length, async () => {
  await nextTick()
  if (timeline.value) timeline.value.scrollTop = timeline.value.scrollHeight
})
</script>

<template>
  <section class="web-case-agent-workspace" data-testid="web-case-agent-workspace">
    <header class="web-case-agent-workspace__head">
      <div><strong>Agent 工作区</strong><span>{{ stageLabel }} · {{ runStatusLabel() }} · 规则 v{{ run?.ruleVersion || '-' }}</span></div>
      <div class="web-case-agent-workspace__actions">
        <el-button v-if="run?.status === 'succeeded'" size="small" @click="comparisonDialogVisible = true"><GitCompareArrows :size="13" />回归对比</el-button>
        <el-button v-if="run?.status === 'succeeded' && run?.projectId" size="small" @click="insightDialogVisible = true"><TrendingUp :size="13" />质量洞察</el-button>
        <el-button v-if="run?.projectId" size="small" @click="repairReviewVisible = true">locator 修复审核</el-button>
        <el-button v-if="run?.status === 'succeeded'" type="primary" plain size="small" @click="ratingDialogVisible = true"><Star :size="13" />{{ lastRating ? '重新评分' : '评分' }}</el-button>
        <el-button v-if="run?.status === 'running' || run?.status === 'queued'" type="danger" plain size="small" @click="emit('cancel')"><Square :size="13" />终止</el-button>
        <el-button v-else-if="run?.status === 'failed' || run?.status === 'blocked'" size="small" @click="emit('retry')"><RefreshCcw :size="13" />重试</el-button>
        <el-button v-else-if="run?.status === 'cancelled'" size="small" @click="emit('resume')"><CirclePause :size="13" />继续</el-button>
      </div>
    </header>
    <ol class="web-case-agent-workspace__stages" aria-label="Web Agent 执行进度">
      <li v-for="item in stageItems" :key="item.stage" :class="[`is-${item.state}`, { 'is-clickable': item.stage === 'EVIDENCE' }]" @click="item.stage === 'EVIDENCE' && openEvidenceStage()">
        <span class="web-case-agent-workspace__stage-icon">
          <CircleCheck v-if="item.state === 'done'" :size="14" />
          <TriangleAlert v-else-if="item.state === 'blocked'" :size="14" />
          <CircleDot v-else :size="14" />
        </span>
        <div>
          <strong>{{ item.label }}</strong>
          <span>{{ item.description }}</span>
        </div>
      </li>
    </ol>
    <div class="web-case-agent-workspace__meta">
      <span>已完成 {{ run?.completedPointIds.length || 0 }}</span>
      <span>阻塞 {{ run?.blockedPointIds.length || 0 }}</span>
      <span>自然语言设计 {{ naturalPlanCount || 0 }}</span>
      <span>素材命中 {{ evidenceHitCount }}</span>
      <span>取证事件 {{ browserEvidenceCount }}</span>
      <span>事件 {{ events.length }}</span>
      <span v-if="latestDiagnosticEvent?.details" class="web-case-agent-workspace__exploration-meta">探索第 {{ latestDiagnosticEvent.details.round || '?' }}/{{ latestDiagnosticEvent.details.maxRounds || '?' }} 轮 · 总分 {{ scoreValue(latestDiagnosticEvent.details, 'total') }} · {{ shortPageUrl(latestDiagnosticEvent.details.safePageUrl || latestDiagnosticEvent.details.pageUrl) }}</span>
      <span v-if="latestDiagnosticEvent && eventFailureCategory(latestDiagnosticEvent)" class="web-case-agent-workspace__failure-meta" :title="failureDetailsTitle(latestDiagnosticEvent)">失败 {{ failureLabel(latestDiagnosticEvent) }}<template v-if="manualTakeoverPrompt(latestDiagnosticEvent)"> · {{ manualTakeoverPrompt(latestDiagnosticEvent) }}</template></span>
      <span v-if="lastRating" class="web-case-agent-workspace__rating">{{ lastRating.baseline ? '已固化基准' : '已评分' }} {{ lastRating.overallScore }}/10</span>
    </div>
    <div v-if="run?.errorSummary" class="web-case-agent-workspace__error" role="alert">{{ run.errorSummary }}</div>
    <div ref="timeline" class="web-case-agent-workspace__timeline" role="log" aria-live="polite">
      <button v-for="event in events" :key="event.eventId" type="button" class="web-case-agent-event" :title="eventDetailsTitle(event)" @click="openEventDetails(event)">
        <time :datetime="event.occurredAt" :title="event.occurredAt">{{ formatEventTime(event.occurredAt) }}</time>
        <span class="web-case-agent-event__stage">{{ eventStageLabel(event.stage) }}</span>
        <span class="web-case-agent-event__summary">{{ event.summary }}</span>
        <span v-if="event.details?.actionType || eventFailureCategory(event)" class="web-case-agent-event__action">
          <template v-if="event.details?.actionType">{{ explorationActionLabel(event.details.actionType as string) }}</template>
          <template v-if="safeActionTargetLabel(event.details)"> · {{ safeActionTargetLabel(event.details) }}</template>
          <span v-if="eventFailureCategory(event)" class="web-case-agent-event__failure" :title="failureDetailsTitle(event)">{{ failureLabel(event) }}</span>
        </span>
        <span v-if="event.knowledgeHitIds?.length" class="web-case-agent-event__badge">素材 {{ event.knowledgeHitIds.length }}</span>
      </button>
      <el-empty v-if="!events.length" description="等待 Agent 事件" :image-size="44" />
    </div>
    <WebCaseQualityRatingDialog v-model:visible="ratingDialogVisible" :run="run" :existing="lastRating" @rated="handleRated" />
    <WebCaseRegressionComparisonDialog v-model:visible="comparisonDialogVisible" :run="run" />
    <WebCaseQualityInsightDialog v-model:visible="insightDialogVisible" :project-id="run?.projectId || 0" />
    <LocatorRepairReviewDialog v-model:visible="repairReviewVisible" :project-id="run?.projectId || 0" />
    <el-dialog v-model="evidenceDialogVisible" class="web-case-agent-evidence-dialog" title="素材取证详情" width="min(760px, 92vw)" :close-on-click-modal="true">
      <div v-if="effectiveEvidenceEvent" class="web-case-agent-evidence-detail">
        <div class="web-case-agent-evidence-detail__head"><span>{{ formatEventTime(effectiveEvidenceEvent.occurredAt) }}</span><strong>{{ effectiveEvidenceEvent.summary }}</strong></div>
        <div v-if="effectiveEvidenceEvent.details?.screenshotUrl" class="web-case-agent-evidence-detail__screenshot">
          <img :src="effectiveEvidenceEvent.details.screenshotUrl" alt="素材取证浏览器页面" />
        </div>
        <dl>
          <div><dt>测试点</dt><dd>{{ effectiveEvidenceEvent.pointId || '批次级取证' }}</dd></div>
          <div><dt>动作</dt><dd>{{ effectiveEvidenceEvent.toolName || 'browser-discovery' }}</dd></div>
          <div v-if="effectiveEvidenceEvent.details?.pageUrl"><dt>当前页面</dt><dd>{{ effectiveEvidenceEvent.details.pageUrl }}</dd></div>
          <div v-if="effectiveEvidenceEvent.details?.failureReason"><dt>失败原因</dt><dd class="is-error">{{ effectiveEvidenceEvent.details.failureReason }}</dd></div>
          <div v-if="effectiveEvidenceEvent.details?.actionType"><dt>执行动作</dt><dd>{{ explorationActionLabel(effectiveEvidenceEvent.details.actionType) }} {{ safeActionTargetLabel(effectiveEvidenceEvent.details) }}</dd></div>
        </dl>
        <pre>{{ evidenceDetailsJson }}</pre>
      </div>
      <el-empty v-else description="当前还没有素材取证事件" :image-size="64" />
    </el-dialog>
    <el-dialog v-model="errorDialogVisible" class="web-case-agent-error-dialog" title="错误详情" width="min(760px, 92vw)" :close-on-click-modal="true">
      <div v-if="selectedEvidenceEvent" class="web-case-agent-error-detail" role="alert">
        <div class="web-case-agent-error-detail__head">
          <span>{{ formatEventTime(selectedEvidenceEvent.occurredAt) }}</span>
          <strong>{{ selectedEvidenceEvent.stage }} · {{ selectedEvidenceEvent.severity }}</strong>
        </div>
        <dl>
          <div><dt>错误摘要</dt><dd class="is-error">{{ selectedEvidenceEvent.summary }}</dd></div>
          <div v-if="selectedEvidenceEvent.errorCode"><dt>错误码</dt><dd>{{ selectedEvidenceEvent.errorCode }}</dd></div>
          <div v-if="selectedEvidenceEvent.pointId"><dt>测试点</dt><dd>{{ selectedEvidenceEvent.pointId }}</dd></div>
          <div v-if="eventFailureCategory(selectedEvidenceEvent)"><dt>失败分类</dt><dd>{{ failureLabel(selectedEvidenceEvent) }}</dd></div>
          <div v-if="selectedEvidenceEvent.details?.failureReason"><dt>失败原因</dt><dd>{{ safeDetailText(selectedEvidenceEvent.details.failureReason) }}</dd></div>
        </dl>
        <pre>{{ eventDetailsTitle(selectedEvidenceEvent) }}</pre>
      </div>
      <template #footer><el-button @click="errorDialogVisible = false">关闭</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.web-case-agent-workspace {
  margin: 0 0 10px;
  border: 1px solid var(--border-default);
  border-radius: 14px;
  overflow: hidden;
  background: var(--surface-subtle);
}

.web-case-agent-workspace__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 12px 8px;
}

.web-case-agent-workspace__head > div:first-child {
  display: grid;
  gap: 3px;
}

.web-case-agent-workspace__head strong { color: var(--text-primary); font-size: 14px; font-weight: var(--font-weight-semibold); }
.web-case-agent-workspace__head span,
.web-case-agent-workspace__meta { color: var(--text-tertiary); font-size: 12px; }
.web-case-agent-workspace__actions { display: flex; flex: 0 0 auto; }

.web-case-agent-workspace__stages {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 6px;
  margin: 0;
  padding: 0 12px 8px;
  list-style: none;
}

.web-case-agent-workspace__stages li {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 6px;
  min-width: 0;
  padding: 7px 7px;
  border: 1px solid rgba(194, 213, 225, .72);
  border-radius: 8px;
  background: var(--surface-glass);
}

.web-case-agent-workspace__stages li.is-clickable { cursor: pointer; }
.web-case-agent-workspace__stages li.is-clickable:hover { border-color: var(--brand-primary); box-shadow: 0 4px 14px rgba(15, 118, 110, 0.12); }

.web-case-agent-workspace__stages li.is-active {
  border-color: var(--brand-primary-border);
  background: linear-gradient(90deg, rgba(15, 118, 110, 0.08), rgba(255, 255, 255, 0.92));
  box-shadow: 0 10px 24px rgba(15, 118, 110, 0.06);
}

.web-case-agent-workspace__stages li.is-done {
  border-color: var(--color-success-border);
  background: var(--color-success-soft);
}

.web-case-agent-workspace__stages li.is-blocked {
  border-color: var(--color-danger-border);
  background: var(--surface-page);
}

.web-case-agent-workspace__stage-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-top: 1px;
  color: var(--text-tertiary);
}

.web-case-agent-workspace__stages li.is-active .web-case-agent-workspace__stage-icon { color: var(--brand-primary); }
.web-case-agent-workspace__stages li.is-done .web-case-agent-workspace__stage-icon { color: var(--color-success); }
.web-case-agent-workspace__stages li.is-blocked .web-case-agent-workspace__stage-icon { color: var(--color-danger); }

.web-case-agent-workspace__stages strong {
  display: block;
  overflow: hidden;
  color: var(--text-primary);
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  font-weight: var(--font-weight-semibold);
}

.web-case-agent-workspace__stages span {
  display: block;
  overflow: hidden;
  color: var(--text-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  line-height: 1.35;
}

.web-case-agent-workspace__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 14px;
  padding: 0 12px 8px;
  border-bottom: 1px solid rgba(194, 213, 225, .72);
}

.web-case-agent-workspace__rating {
  color: var(--color-success);
  font-weight: 600;
}

.web-case-agent-workspace__failure-meta {
  min-width: 0;
  max-width: 360px;
  overflow: hidden;
  color: var(--color-danger);
  text-overflow: ellipsis;
  white-space: normal;
  line-height: 1.4;
}

.web-case-agent-workspace__error {
  margin: 8px 10px 0;
  padding: 9px 10px;
  border: 1px solid var(--color-danger-border);
  border-radius: 6px;
  color: var(--color-danger);
  background: var(--surface-page);
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
}

.web-case-agent-workspace__timeline {
  max-height: 124px;
  overflow: auto;
  padding: 6px;
  scrollbar-color: var(--border-strong) transparent;
  scrollbar-width: thin;
}

.web-case-agent-event {
  display: grid;
  grid-template-columns: minmax(148px, .72fr) minmax(94px, .44fr) minmax(0, 1.8fr) minmax(90px, .7fr) auto;
  width: 100%;
  column-gap: 14px;
  row-gap: 4px;
  align-items: center;
  border: 0;
  border-radius: 8px;
  padding: 5px 7px;
  color: var(--quality-text);
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.web-case-agent-event:hover { background: var(--surface-hover); }
.web-case-agent-event time {
  min-width: 0;
  color: var(--text-tertiary);
  font-variant-numeric: tabular-nums;
  font-size: 10px;
  white-space: nowrap;
}

.web-case-agent-event__stage {
  min-width: 0;
  color: var(--brand-primary);
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
}

.web-case-agent-event__summary {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-height: 1.45;
  font-size: 11px;
}

.web-case-agent-event__action {
  min-width: 0;
  overflow: hidden;
  color: var(--text-tertiary);
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 10px;
}

.web-case-agent-event__failure {
  display: inline-block;
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
  color: var(--color-danger);
  text-overflow: ellipsis;
  vertical-align: bottom;
  white-space: nowrap;
}

.web-case-agent-workspace__exploration-meta {
  min-width: 0;
  max-width: 360px;
  overflow: hidden;
  color: var(--brand-primary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.web-case-agent-event__badge {
  justify-self: end;
  border-radius: 999px;
  padding: 2px 7px;
  color: var(--brand-primary);
  background: var(--brand-primary-soft);
  font-size: 11px;
  white-space: nowrap;
}

.web-case-agent-evidence-detail { display: grid; gap: 12px; }
.web-case-agent-evidence-detail__head { display: grid; gap: 4px; padding-bottom: 10px; border-bottom: 1px solid var(--border-soft); }
.web-case-agent-evidence-detail__head span { color: var(--text-muted); font-size: 11px; }
.web-case-agent-evidence-detail__head strong { color: var(--text-primary); font-size: 13px; }
.web-case-agent-evidence-detail__screenshot { aspect-ratio: 16 / 9; overflow: hidden; border: 1px solid var(--border-soft); border-radius: 8px; background: var(--surface-subtle); }
.web-case-agent-evidence-detail__screenshot img { width: 100%; height: 100%; display: block; object-fit: contain; }
.web-case-agent-evidence-detail dl { display: grid; gap: 8px; margin: 0; }
.web-case-agent-evidence-detail dl > div { display: grid; grid-template-columns: 72px minmax(0, 1fr); gap: 10px; }
.web-case-agent-evidence-detail dt { color: var(--text-muted); font-size: 11px; }
.web-case-agent-evidence-detail dd { min-width: 0; margin: 0; color: var(--text-regular); font-size: 11px; overflow-wrap: anywhere; }
.web-case-agent-evidence-detail dd.is-error { color: var(--status-danger-text); }
.web-case-agent-evidence-detail pre { max-height: 300px; margin: 0; padding: 10px; overflow: auto; border: 1px solid var(--border-soft); border-radius: 6px; color: var(--text-regular); background: var(--surface-subtle); font-size: 10px; line-height: 1.5; white-space: pre-wrap; word-break: break-word; }

@media (max-width: 720px) {
  .web-case-agent-workspace__head { align-items: flex-start; flex-direction: column; gap: 8px; }
  .web-case-agent-workspace__stages { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .web-case-agent-event { grid-template-columns: minmax(128px, 1fr) auto; column-gap: 12px; }
  .web-case-agent-event__summary,
  .web-case-agent-event__action,
  .web-case-agent-event__badge { grid-column: 1 / -1; justify-self: start; }
}
</style>

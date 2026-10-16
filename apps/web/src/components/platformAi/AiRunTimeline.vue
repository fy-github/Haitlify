<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  Clock,
  LoaderCircle,
  FileText,
  Wrench,
  RefreshCw,
  UserCheck,
  CircleCheck,
  CircleX,
  HeartPulse,
  Copy
} from '@lucide/vue'
import { buildRedactedDiagnostics, type RuntimeRunEvent } from '../../domain/aiRuntimeRepository'

const props = withDefaults(defineProps<{
  events?: RuntimeRunEvent[]
  runStatus?: string
  loading?: boolean
}>(), { events: () => [], runStatus: '', loading: false })

const copied = ref('')

type TimelineKind = 'queued' | 'calling' | 'streaming' | 'tool' | 'retry' | 'human' | 'succeeded' | 'failed' | 'info'

interface TimelineEntry {
  id: string
  kind: TimelineKind
  icon: unknown
  label: string
  summary: string
  clock: string
  model: string
  shard: string
  detail: string
}

// 心跳不是 token 进度：心跳只证明连接存活，绝不换算成百分比或 token 数。
const isHeartbeat = (stage: string): boolean => stage.toLowerCase().includes('heartbeat')

const kindFor = (stage: string, severity: string): TimelineKind => {
  const normalized = stage.toLowerCase()
  if (normalized.startsWith('queued') || normalized === 'preflight') return 'queued'
  if (isHeartbeat(normalized)) return 'info'
  if (normalized.includes('tool')) return 'tool'
  if (normalized.includes('chunk_started') || normalized.includes('chunk_completed') || normalized.includes('stream')) return 'streaming'
  if (normalized.includes('fallback') || normalized.includes('retry') || normalized.includes('repair')) return 'retry'
  if (normalized.includes('human') || normalized.includes('pending_confirmation')) return 'human'
  if (normalized.includes('attempt_started') || normalized.includes('call')) return 'calling'
  if (normalized.includes('succeeded')) return 'succeeded'
  if (normalized.includes('failed') || severity === 'error') return 'failed'
  return 'info'
}

const labelFor = (kind: TimelineKind, stage: string): string => {
  if (isHeartbeat(stage)) return '心跳'
  switch (kind) {
    case 'queued': return '排队中'
    case 'calling': return '调用中'
    case 'streaming': return '生成片段'
    case 'tool': return '工具中'
    case 'retry': return '重试'
    case 'human': return '等待人工确认'
    case 'succeeded': return '完成'
    case 'failed': return '失败'
    default: return '事件'
  }
}

const iconFor = (kind: TimelineKind): unknown => {
  switch (kind) {
    case 'queued': return Clock
    case 'calling': return LoaderCircle
    case 'streaming': return FileText
    case 'tool': return Wrench
    case 'retry': return RefreshCw
    case 'human': return UserCheck
    case 'succeeded': return CircleCheck
    case 'failed': return CircleX
    default: return HeartPulse
  }
}

const clockOf = (occurredAt?: string): string => {
  if (!occurredAt) return ''
  const parsed = new Date(occurredAt)
  return Number.isNaN(parsed.getTime()) ? '' : parsed.toLocaleTimeString('zh-CN', { hour12: false })
}

const entries = computed<TimelineEntry[]>(() =>
  (props.events ?? [])
    .slice()
    .sort((left, right) => (left.eventSeq ?? 0) - (right.eventSeq ?? 0))
    .map((event, index) => {
      const stage = String(event.stage ?? '')
      const kind = kindFor(stage, String(event.severity ?? ''))
      const chunkPart = typeof event.chunkIndex === 'number'
        ? `分片 ${event.chunkIndex}${typeof event.chunkTotal === 'number' ? `/${event.chunkTotal}` : ''}`
        : ''
      return {
        id: `ai-timeline-${event.eventSeq ?? index}`,
        kind,
        icon: iconFor(kind),
        label: labelFor(kind, stage),
        summary: String(event.summary ?? ''),
        clock: clockOf(event.occurredAt),
        model: event.model ? String(event.model) : '',
        shard: chunkPart,
        detail: buildRedactedDiagnostics({
          stage,
          model: event.model,
          providerType: event.providerType,
          retryReason: event.retryReason,
          detail: String(event.summary ?? '')
        })
      }
    })
)

// 部分完成数：只用后端事件里的分片事实，不猜测、不按心跳外推。
const shardProgress = computed(() => {
  const totals = entries.value.map((entry) => entry.shard).filter(Boolean)
  const completed = props.events?.filter((event) => String(event.stage ?? '').toLowerCase().includes('chunk_completed')).length ?? 0
  const total = props.events?.reduce((max, event) => {
    const candidate = typeof event.chunkTotal === 'number' ? event.chunkTotal : 0
    return candidate > max ? candidate : max
  }, 0) ?? 0
  return { completed, total, labels: totals }
})

const copyDiagnostics = async (entry: TimelineEntry): Promise<void> => {
  try {
    if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(entry.detail)
    }
  } catch {
    // 剪贴板不可用时静默——诊断文本已脱敏，重试由用户发起。
  }
  copied.value = entry.id
}
</script>

<template>
  <section class="ai-run-timeline" aria-label="AI 运行时间线">
    <header class="timeline-head">
      <span class="timeline-title">运行时间线</span>
      <span v-if="runStatus" class="timeline-status">{{ runStatus }}</span>
      <span v-if="shardProgress.total > 0" class="timeline-shards" data-testid="ai-timeline-shards">
        分片完成 {{ shardProgress.completed }}/{{ shardProgress.total }}
      </span>
    </header>
    <p v-if="loading" class="timeline-empty" data-testid="ai-timeline-loading">加载中…</p>
    <p v-else-if="entries.length === 0" class="timeline-empty" data-testid="ai-timeline-empty">暂无事件</p>
    <ol v-else class="timeline-list">
      <li
        v-for="entry in entries"
        :key="entry.id"
        class="timeline-item"
        :class="`kind-${entry.kind}`"
        :data-testid="`ai-timeline-item-${entry.kind}`"
      >
        <component :is="entry.icon" class="timeline-icon" :size="16" aria-hidden="true" />
        <div class="timeline-body">
          <div class="timeline-line">
            <span class="timeline-label">{{ entry.label }}</span>
            <span v-if="entry.clock" class="timeline-clock">{{ entry.clock }}</span>
            <span v-if="entry.model" class="timeline-model" data-testid="ai-timeline-model">模型 {{ entry.model }}</span>
            <span v-if="entry.shard" class="timeline-shard">{{ entry.shard }}</span>
          </div>
          <p class="timeline-summary">{{ entry.summary }}</p>
        </div>
        <button
          v-if="entry.kind === 'failed' || entry.kind === 'retry'"
          type="button"
          class="timeline-copy"
          data-testid="ai-timeline-copy"
          @click="copyDiagnostics(entry)"
        >
          <Copy :size="14" aria-hidden="true" />
          {{ copied === entry.id ? '已复制' : '复制诊断' }}
        </button>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.ai-run-timeline { display: flex; flex-direction: column; gap: 8px; }
.timeline-head { display: flex; align-items: baseline; gap: 12px; }
.timeline-title { font-weight: 600; }
.timeline-status { color: var(--muted-foreground, #6b7280); }
.timeline-shards { margin-left: auto; color: var(--muted-foreground, #6b7280); }
.timeline-empty { color: var(--muted-foreground, #6b7280); }
.timeline-list { display: flex; flex-direction: column; gap: 6px; list-style: none; margin: 0; padding: 0; }
.timeline-item { display: flex; gap: 8px; align-items: flex-start; padding: 6px 8px; border: 1px solid var(--border, #e5e7eb); border-radius: 6px; }
.kind-failed { border-color: var(--destructive, #ef4444); }
.kind-retry { border-color: var(--warning, #f59e0b); }
.timeline-line { display: flex; gap: 8px; align-items: baseline; flex-wrap: wrap; }
.timeline-label { font-weight: 500; }
.timeline-clock, .timeline-model, .timeline-shard { color: var(--muted-foreground, #6b7280); font-size: 12px; }
.timeline-summary { margin: 2px 0 0; }
.timeline-copy { margin-left: auto; display: inline-flex; gap: 4px; align-items: center; }
</style>

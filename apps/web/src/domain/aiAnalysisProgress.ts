/**
 * Pure progress + streaming-log reducer for an AI requirement-analysis run.
 *
 * The backend processes an analysis run asynchronously (queued -> running ->
 * succeeded/failed). The frontend polls `getAiAnalysisRun`, the draft test
 * points and the call records, then feeds each poll through
 * {@link applyAnalysisSnapshot}. The reducer diffs against the previous poll
 * and appends only new log lines, so the UI can render a live "AI action"
 * stream under the progress bar.
 *
 * The module is intentionally pure and free of wall-clock dependencies: entry
 * ordering uses a monotonic sequence counter, so it stays deterministic and
 * unit-testable. Note the run GET does not expose `currentChunkId`, so chunk
 * transitions are derived from `completedChunks` deltas.
 */

export type AiAnalysisLogKind = 'info' | 'chunk' | 'call' | 'draft' | 'success' | 'error'

export interface AiAnalysisLogEntry {
  id: string
  kind: AiAnalysisLogKind
  text: string
}

/** Minimal structural view of an analysis run consumed by the reducer. */
export interface AiAnalysisRunSnapshot {
  status: string
  totalChunks: number
  completedChunks: number
  errorSummary?: string | null
}

/** Minimal structural view of an LLM call record consumed by the reducer. */
export interface AiAnalysisCallSnapshot {
  recordId: string
  modelName: string
  status: string
  durationMs: number
  tokenUsageInput?: number | null
  tokenUsageOutput?: number | null
  errorMessage?: string | null
}

export interface AiAnalysisSnapshot {
  run: AiAnalysisRunSnapshot
  draftPoints: ReadonlyArray<{ pointId: string; title?: string }>
  callRecords: ReadonlyArray<AiAnalysisCallSnapshot>
}

export interface AiAnalysisProgressView {
  percentage: number
  text: string
  log: AiAnalysisLogEntry[]
}

export interface AiAnalysisProgressState {
  seq: number
  log: AiAnalysisLogEntry[]
  seenDraftPointIds: Set<string>
  seenCallRecordIds: Set<string>
  lastCompletedChunks: number
  announcedInProgressChunk: number
  announcedPlan: boolean
  announcedTerminal: boolean
}

const RUNNING_FLOOR_PERCENTAGE = 40
const RUNNING_CEIL_PERCENTAGE = 95
const PRE_PLAN_RUNNING_PERCENTAGE = 35
const SUCCEEDED_PERCENTAGE = 100

export const createAnalysisProgressState = (): AiAnalysisProgressState => ({
  seq: 0,
  log: [],
  seenDraftPointIds: new Set(),
  seenCallRecordIds: new Set(),
  lastCompletedChunks: 0,
  announcedInProgressChunk: 0,
  announcedPlan: false,
  announcedTerminal: false
})

export const isTerminalAnalysisStatus = (status: string): boolean =>
  status === 'succeeded' || status === 'failed' || status === 'cancelled'

const nextId = (state: AiAnalysisProgressState): string => `ai-analysis-log-${state.seq++}`

const append = (state: AiAnalysisProgressState, kind: AiAnalysisLogKind, text: string): void => {
  state.log.push({ id: nextId(state), kind, text })
}

const clamp = (value: number, min: number, max: number): number =>
  Math.max(min, Math.min(max, value))

const tokenSegment = (call: AiAnalysisCallSnapshot): string => {
  if (call.tokenUsageInput == null && call.tokenUsageOutput == null) return ''
  return ` · ${call.tokenUsageInput ?? 0}/${call.tokenUsageOutput ?? 0} tokens`
}

const appendChunkProgress = (state: AiAnalysisProgressState, run: AiAnalysisRunSnapshot): void => {
  if (run.totalChunks <= 0) return

  if (run.completedChunks > state.lastCompletedChunks) {
    for (let index = state.lastCompletedChunks + 1; index <= run.completedChunks; index += 1) {
      append(state, 'chunk', `第 ${index}/${run.totalChunks} 块分析完成`)
    }
    state.lastCompletedChunks = run.completedChunks
  }

  if (run.status === 'running' && run.completedChunks < run.totalChunks) {
    const inProgress = run.completedChunks + 1
    if (inProgress !== state.announcedInProgressChunk) {
      append(state, 'chunk', `正在分析第 ${inProgress}/${run.totalChunks} 块…`)
      state.announcedInProgressChunk = inProgress
    }
  }
}

const appendCallRecords = (state: AiAnalysisProgressState, callRecords: ReadonlyArray<AiAnalysisCallSnapshot>): void => {
  for (const call of callRecords) {
    if (!call.recordId || state.seenCallRecordIds.has(call.recordId)) continue
    state.seenCallRecordIds.add(call.recordId)
    if (call.status === 'failed') {
      append(state, 'error', `调用 ${call.modelName || 'AI 模型'} 失败：${call.errorMessage || '未知错误'}`)
      continue
    }
    append(state, 'call', `调用 ${call.modelName || 'AI 模型'} · ${call.durationMs}ms${tokenSegment(call)}`)
  }
}

const percentageFor = (run: AiAnalysisRunSnapshot): number => {
  if (run.status === 'succeeded') return SUCCEEDED_PERCENTAGE
  if (run.totalChunks <= 0) return PRE_PLAN_RUNNING_PERCENTAGE
  if (run.completedChunks <= 0) return RUNNING_FLOOR_PERCENTAGE
  const ratio = Math.min(run.completedChunks / run.totalChunks, 1)
  return clamp(
    Math.round(RUNNING_FLOOR_PERCENTAGE + (RUNNING_CEIL_PERCENTAGE - RUNNING_FLOOR_PERCENTAGE) * ratio),
    RUNNING_FLOOR_PERCENTAGE,
    RUNNING_CEIL_PERCENTAGE
  )
}

const textFor = (run: AiAnalysisRunSnapshot, draftCount: number): string => {
  if (run.status === 'succeeded') return `AI分析需求完成，共生成 ${draftCount} 条草稿测试点`
  if (run.status === 'failed') return 'AI分析需求未完成'
  if (run.status === 'cancelled') return 'AI分析需求已取消'
  if (run.status === 'running') {
    return run.totalChunks > 0
      ? `正在分析需求 · ${run.completedChunks}/${run.totalChunks} 分片`
      : '正在拆分需求并准备分析…'
  }
  return '已创建分析任务，等待开始…'
}

export const applyAnalysisSnapshot = (
  state: AiAnalysisProgressState,
  snapshot: AiAnalysisSnapshot
): AiAnalysisProgressView => {
  const { run, draftPoints, callRecords } = snapshot

  if (run.totalChunks > 0 && !state.announcedPlan) {
    append(state, 'info', `已将需求拆分为 ${run.totalChunks} 个分析块`)
    state.announcedPlan = true
  }

  appendChunkProgress(state, run)

  const newDraftPoints = draftPoints.filter(
    (point) => point.pointId && !state.seenDraftPointIds.has(point.pointId)
  )
  if (newDraftPoints.length > 0) {
    newDraftPoints.forEach((point) => state.seenDraftPointIds.add(point.pointId))
    const titles = newDraftPoints
      .slice(0, 3)
      .map((point) => point.title?.trim() || '未命名测试点')
      .join('、')
    const more = newDraftPoints.length > 3 ? ` 等 ${newDraftPoints.length} 条` : ''
    append(state, 'draft', `新增草稿测试点：${titles}${more}（累计 ${draftPoints.length}）`)
  }

  appendCallRecords(state, callRecords)

  if (isTerminalAnalysisStatus(run.status) && !state.announcedTerminal) {
    state.announcedTerminal = true
    if (run.status === 'succeeded') {
      append(state, 'success', `分析完成，共生成 ${draftPoints.length} 条草稿测试点`)
    } else if (run.status === 'cancelled') {
      append(state, 'error', run.errorSummary || '已取消分析任务')
    } else {
      append(state, 'error', `分析失败：${run.errorSummary || '未知错误'}`)
    }
  }

  return {
    percentage: percentageFor(run),
    text: textFor(run, draftPoints.length),
    log: [...state.log]
  }
}

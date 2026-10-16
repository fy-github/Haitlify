/**
 * T11：AI 运行时仓库——任务时间线、断线续接（afterSeq）、取消/重试、能力报告。
 *
 * 只封装既有后端面（/api/ai/jobs、/api/ai/analysis-runs、/api/ai/capability-check），
 * 不引入第二套状态：事件流以后端 eventSeq 为唯一续接锚点，心跳与真实 token/分片
 * 进度分离由消费方（AiRunTimeline）按 stage 呈现，这里不做进度换算。
 */
import { joinBackendPath } from './backendBaseUrl'
import { authedFetch } from './http'
import type { AiPlatformRepositoryOptions, AiRunEvent } from './aiPlatformRepository'

export type AiRuntimeRepositoryOptions = AiPlatformRepositoryOptions

/** 后端事件带 eventSeq（run 内单调）；旧响应缺省时按 0 处理。 */
export interface RuntimeRunEvent extends AiRunEvent {
  eventSeq?: number
}

export interface RuntimeRunEventList {
  items: RuntimeRunEvent[]
}

export interface AiJobSubmitRequest {
  jobType: string
  projectId: number
  actorId?: string
  idempotencyKey?: string
  inputSnapshotJson?: string
  legacyRunRef?: string
}

export interface AiJobSubmitResponse {
  jobId: string
}

export interface AiJobAuditResponse {
  job: { jobId: string; jobType: string; projectId?: number; status: string; [key: string]: unknown }
  nodes: Array<Record<string, unknown>>
  attempts: Array<Record<string, unknown>>
  events: RuntimeRunEvent[]
}

export interface AiCapabilityProbeResult {
  probe: string
  status: string
  diagnosticsId?: string | null
  detail?: string | null
}

export interface AiCapabilityCheckReport {
  profileId: string
  profileVersion: number
  configHash?: string | null
  checkedAt?: string | null
  ttlSeconds?: number
  overall: string
  probes: AiCapabilityProbeResult[]
}

export interface AiStoredCapabilityCheck {
  report?: AiCapabilityCheckReport | null
  fresh: boolean
  currentConfigHash?: string | null
}

const jsonHeaders = (): HeadersInit => ({ 'Content-Type': 'application/json' })

const requestJson = async <T>(
  options: AiRuntimeRepositoryOptions,
  path: string,
  init?: RequestInit
): Promise<T> => {
  const requestInit = options.signal ? { ...init, signal: options.signal } : init
  const response = await fetcher(options)(joinBackendPath(path, options.baseUrl), requestInit)
  if (!response.ok) {
    const payload = await response.json().catch(() => undefined) as { message?: string; error?: string } | undefined
    const suffix = payload?.message || payload?.error ? ` ${payload.message || payload.error}` : ''
    throw new Error(`AI runtime request failed: ${response.status}${suffix}`)
  }
  return await response.json() as T
}

const fetcher = (options: AiRuntimeRepositoryOptions): typeof authedFetch => {
  if (options.fetchImpl) return options.fetchImpl as typeof authedFetch
  if (typeof fetch !== 'undefined') return authedFetch
  throw new Error('Fetch API is not available')
}

/** run 事件补拉：afterSeq 是 eventSeq 严格大于的锚点；缺省时全量拉取。 */
export const fetchRunEvents = (
  options: AiRuntimeRepositoryOptions,
  runId: string,
  afterSeq?: number
): Promise<RuntimeRunEventList> => {
  const query = afterSeq === undefined || afterSeq === null ? '' : `?afterSeq=${encodeURIComponent(afterSeq)}`
  return requestJson<RuntimeRunEventList>(
    options,
    `/api/ai/analysis-runs/${encodeURIComponent(runId)}/events${query}`
  )
}

/** 任务事件补拉（/api/ai/jobs 面），与 analysis-runs 面同构。 */
export const fetchJobEvents = (
  options: AiRuntimeRepositoryOptions,
  jobId: string,
  afterSeq?: number
): Promise<RuntimeRunEventList> => {
  const query = afterSeq === undefined || afterSeq === null ? '' : `?afterSeq=${encodeURIComponent(afterSeq)}`
  return requestJson<RuntimeRunEventList>(
    options,
    `/api/ai/jobs/${encodeURIComponent(jobId)}/events${query}`
  )
}

export const submitJob = (
  options: AiRuntimeRepositoryOptions,
  request: AiJobSubmitRequest
): Promise<AiJobSubmitResponse> =>
  requestJson<AiJobSubmitResponse>(options, '/api/ai/jobs', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(request)
  })

export const fetchJobAudit = (
  options: AiRuntimeRepositoryOptions,
  jobId: string,
  afterSeq?: number
): Promise<AiJobAuditResponse> => {
  const query = afterSeq === undefined || afterSeq === null ? '' : `?afterSeq=${encodeURIComponent(afterSeq)}`
  return requestJson<AiJobAuditResponse>(options, `/api/ai/jobs/${encodeURIComponent(jobId)}${query}`)
}

export const cancelJob = (
  options: AiRuntimeRepositoryOptions,
  jobId: string,
  requestedBy?: string
): Promise<{ outcome: string }> => {
  const query = requestedBy ? `?requestedBy=${encodeURIComponent(requestedBy)}` : ''
  return requestJson<{ outcome: string }>(
    options,
    `/api/ai/jobs/${encodeURIComponent(jobId)}/cancel${query}`,
    { method: 'POST' }
  )
}

export const cancelAnalysisRun = (
  options: AiRuntimeRepositoryOptions,
  runId: string
): Promise<Record<string, unknown>> =>
  requestJson(options, `/api/ai/analysis-runs/${encodeURIComponent(runId)}/cancel`, { method: 'POST' })

export const retryAnalysisRun = (
  options: AiRuntimeRepositoryOptions,
  runId: string
): Promise<Record<string, unknown>> =>
  requestJson(options, `/api/ai/analysis-runs/${encodeURIComponent(runId)}/retry`, { method: 'POST' })

/** 执行能力探针（默认只读；persist=true 由用户明确要求才写报告）。 */
export const runCapabilityCheck = (
  options: AiRuntimeRepositoryOptions,
  request: { profileId: string; profileVersion: number; persist?: boolean }
): Promise<AiCapabilityCheckReport> =>
  requestJson<AiCapabilityCheckReport>(options, '/api/ai/capability-check', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ profileId: request.profileId, profileVersion: request.profileVersion, persist: request.persist === true })
  })

export const fetchStoredCapabilityCheck = (
  options: AiRuntimeRepositoryOptions,
  profileId: string,
  profileVersion: number
): Promise<AiStoredCapabilityCheck> =>
  requestJson<AiStoredCapabilityCheck>(
    options,
    `/api/ai/capability-check?profileId=${encodeURIComponent(profileId)}&profileVersion=${encodeURIComponent(profileVersion)}`
  )

/** 事件流的最大 eventSeq；空流返回 0，作为下一次 afterSeq。 */
export const latestEventSeq = (events: ReadonlyArray<RuntimeRunEvent>): number =>
  events.reduce((max, event) => {
    const seq = typeof event.eventSeq === 'number' && Number.isFinite(event.eventSeq) ? event.eventSeq : 0
    return seq > max ? seq : max
  }, 0)

const SECRET_PATTERNS: RegExp[] = [
  /(bearer\s+)[\w.\-+/=]+/gi,
  /\b(sk-[A-Za-z0-9\-_]{6,})/g,
  /\b(AKIA[0-9A-Z]{8,})/g,
  /((?:api[_-]?key|token|secret|password|authorization)\s*[=:]\s*)([^\s"'&,;]+)/gi,
  /([?&](?:access_token|api_key|key|token)=)([^\s&"']+)/gi
]

/**
 * 诊断摘要脱敏：口令/API key/令牌形态一律替换为 ***，绝不把凭证复制进剪贴板。
 * 脱敏在生成时执行（而非复制时），保证日志/界面/复制三处一致。
 */
export const buildRedactedDiagnostics = (payload: {
  runId?: string
  stage?: string
  model?: string
  providerType?: string
  retryReason?: string
  detail?: string
}): string => {
  const lines: string[] = []
  if (payload.runId) lines.push(`run: ${payload.runId}`)
  if (payload.stage) lines.push(`stage: ${payload.stage}`)
  if (payload.model) lines.push(`model: ${payload.model}`)
  if (payload.providerType) lines.push(`provider: ${payload.providerType}`)
  if (payload.retryReason) lines.push(`retry: ${payload.retryReason}`)
  if (payload.detail) lines.push(`detail: ${payload.detail}`)
  let text = lines.join('\n')
  for (const pattern of SECRET_PATTERNS) {
    text = text.replace(pattern, (match, prefix: string) =>
      prefix && prefix !== match ? `${prefix}***` : '***'
    )
  }
  return text
}

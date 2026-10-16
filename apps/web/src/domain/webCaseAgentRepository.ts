import { joinBackendPath } from './backendBaseUrl'
import { authedFetch } from './http'

export type WebCaseAgentStatus = 'queued' | 'running' | 'paused' | 'succeeded' | 'waiting_review' | 'failed' | 'blocked' | 'partial' | 'cancelled'
export type WebCaseAgentMode = 'PLAN' | 'COMPILE'
export type WebCaseAgentStage = 'PREFLIGHT' | 'EVIDENCE' | 'PLANNING' | 'GENERATION' | 'VALIDATION' | 'DELIVERY'
export interface WebCaseAgentRun { runId: string; projectId: number; status: WebCaseAgentStatus; currentStage: WebCaseAgentStage; requestedPointIds: string[]; completedPointIds: string[]; blockedPointIds: string[]; ruleVersion?: number | null; updatedAt?: string | null; errorSummary?: string | null; mode?: WebCaseAgentMode; planRunId?: string }
export type WebExplorationFailureCategory = 'auth_context_lost' | 'login_failed' | 'captcha_required' | 'page_not_ready' | 'wrong_page' | 'network_blocked_critical' | 'network_blocked_unknown' | 'business_api_failed' | 'element_not_found' | 'ambiguous_locator' | 'unsafe_overlay' | 'unsupported_dom' | 'duration_exhausted' | 'manual_review_required' | 'unsupported_frame' | 'unsupported_shadow_dom' | 'unsupported_action_target' | 'invalid_action_ir' | 'action_evidence_missing' | 'action_evidence_mismatch' | 'assertion_failed' | 'observation_failed' | 'settle_timeout' | 'session_busy' | (string & {})
export interface WebExplorationNetworkBlockedEntry { origin?: string; path?: string; resourceType?: string; criticality?: string; failureType?: WebExplorationFailureCategory; [key: string]: unknown }
export interface WebExplorationHandoffDetails { required?: boolean; mode?: string; reason?: string; resumeSupported?: boolean; [key: string]: unknown }
export interface WebExplorationScores { page?: number; element?: number; state?: number; total?: number; pageScore?: number; elementScore?: number; stateScore?: number; totalScore?: number; [key: string]: unknown }
export interface WebExplorationEventDetails { category?: WebExplorationFailureCategory; retryable?: boolean; round?: number; maxRounds?: number; pageUrl?: string; safePageUrl?: string; screenshotUrl?: string; pageScore?: number; elementScore?: number; stateScore?: number; totalScore?: number; scores?: WebExplorationScores; fingerprint?: string; locatorFingerprint?: string; accessibleName?: string; accessName?: string; actionType?: string; actionTarget?: string; failureReason?: string; network?: { criticalBlocked?: Array<WebExplorationNetworkBlockedEntry>; optionalBlocked?: Array<WebExplorationNetworkBlockedEntry>; unknownBlocked?: Array<WebExplorationNetworkBlockedEntry>; authDependencies?: Array<WebExplorationNetworkBlockedEntry> }; handoff?: WebExplorationHandoffDetails; readiness?: { ready?: boolean; failureReason?: string; durationMs?: number }; repairTrail?: Array<{ category?: string; round?: number; changedFingerprint?: boolean | null }>; metrics?: Record<string, unknown>; candidate?: unknown; action?: unknown; failure?: unknown; report?: unknown; [key: string]: unknown }
export interface WebCaseAgentEvent { eventId: string; runId: string; sequenceNo: number; occurredAt: string; stage: WebCaseAgentStage; eventType: string; severity: string; summary: string; pointId?: string | null; toolName?: string | null; durationMs?: number | null; knowledgeHitIds?: string[]; errorCode?: string | null; details?: WebExplorationEventDetails | null }
export interface WebCaseAgentStepTrace { stepIndex: number; naturalStep: string; intent: string; dsl: string[]; evidenceIds: string[]; pageFingerprint: string; sourcePointId: string }
export interface WebCaseAgentArtifact { artifactId: string; runId: string; sourcePointId: string; artifactType: string; candidateJson: Record<string, unknown>; evidenceIds: string[]; validationErrors: string[]; accepted: boolean; repairRound: number; resultOrigin?: string; acceptanceState?: string; modelProfileVersion?: string; fieldOrigins?: string[]; revision?: number; parentArtifactId?: string; idempotencyKey?: string; stepTraces?: WebCaseAgentStepTrace[]; expectedResultEvidenceIds?: string[]; actions?: unknown; environmentId?: string; baseUrl?: string; authenticationMode?: string }

export interface AgentRuleVersion { ruleSetId: string; scope: string; projectId?: number | null; version: number; status: string; contentText: string; phaseRules: Record<string, string> }
export interface LocatorKnowledgeEntry { entryId: string; projectId?: number; environmentId: string; moduleName: string; pageName: string; pageUrl: string; naturalName: string; locator: string; roleName: string; accessibleName?: string; elementType?: string; source?: string; status: string; lastVerifiedAt?: string | null; hitCount: number; executionSuccessCount: number; executionFailureCount: number; confidenceScore: number; confidenceLocked: boolean; lastHitAt?: string | null }
export interface AgentMemory { memoryId: string; projectId?: number; moduleName?: string; category: string; content: string; status: 'PENDING' | 'ACTIVE' | 'DISABLED' | 'DELETED'; scopeType: 'PROJECT' | 'PROJECT_SET' | 'GLOBAL'; createdAt: string; updatedAt?: string; hitCount: number; executionSuccessCount: number; executionFailureCount: number; confidenceScore: number; confidenceLocked: boolean; lastHitAt?: string | null }
export interface SaveLocatorKnowledgeRequest { projectId: number; environmentId: string; moduleName: string; pageName: string; pageUrl: string; naturalName: string; locator: string; roleName: string; accessibleName: string; elementType: string }
export interface CreateAgentMemoryRequest { projectId: number; sourceRunId?: string; moduleName: string; category: string; content: string; rulesVersion?: number }
export interface UpdateAgentMemoryRequest { moduleName: string; category: string; content: string; scopeType?: AgentMemory['scopeType']; projectIds?: number[] }
export interface WebCaseAgentStartRequest {
  projectId: number
  analysisRunId?: string
  pointIds: string[]
  environmentId: string
  baseUrl: string
  browser: string
  viewport: string
  mode?: WebCaseAgentMode
  planRunId?: string
  loginDiscovery?: { discoveryId: string; status: string; successType: string; successTarget: string }
  browserDiscovery?: {
    headless: boolean
    timeoutMs?: number
    waitUntil?: string
    authToken?: string
    extraHeaders?: Record<string, string>
    cookieOrigins?: string[]
    allowedRequestOrigins?: string[]
    sessionId?: string
    reuseSession?: boolean
    /** 网络放行策略（黑名单默认/白名单可选），随取证请求转发到 runner。 */
    networkPolicyMode?: 'blocklist' | 'allowlist'
    diagnosticMode?: 'local' | 'fixture' | 'production' | string
    tracingEnabled?: boolean
    login?: { enabled: boolean; url: string; usernameLocator: string; passwordLocator: string; submitLocator: string; username: string; password: string; successType: string; successTarget: string }
  }
}
type FetchLike = (input: string, init?: RequestInit) => Promise<Response>
export interface WebCaseAgentRepositoryOptions { baseUrl?: string; fetchImpl?: FetchLike; signal?: AbortSignal }

const request = async <T>(options: WebCaseAgentRepositoryOptions, path: string, init?: RequestInit): Promise<T> => {
  const response = await (options.fetchImpl || authedFetch)(joinBackendPath(path, options.baseUrl), { ...init, signal: options.signal, headers: { Accept: 'application/json', ...(init?.headers || {}) } })
  if (!response.ok) throw new Error((await response.text()) || `请求失败: ${response.status}`)
  if (response.status === 204) return undefined as T
  const body = await response.text()
  if (!body.trim()) return undefined as T
  let value: T
  try {
    value = JSON.parse(body) as T
  } catch {
    throw new Error(`接口返回了无效 JSON: ${path}`)
  }
  if (path.includes('/web-case-agent-runs')) return normalizeAgentPayload(value) as T
  return value
}
const normalizeAgentPayload = (value: unknown): unknown => {
  if (Array.isArray(value)) return value.map(normalizeAgentPayload)
  if (!value || typeof value !== 'object') return value
  const record = value as Record<string, unknown>
  const details = record.details && typeof record.details === 'object' && !Array.isArray(record.details)
    ? normalizeAgentEventDetails(record.details as Record<string, unknown>)
    : record.details
  return {
    ...record,
    ...(typeof record.status === 'string' ? { status: record.status.toLowerCase() } : {}),
    ...(typeof record.currentStage === 'string' ? { currentStage: record.currentStage.toUpperCase() } : {}),
    ...(typeof record.stage === 'string' ? { stage: record.stage.toUpperCase() } : {}),
    ...(details !== undefined ? { details } : {})
  }
}
const normalizeSafePageUrl = (value: string): string => {
  try {
    const parsed = new URL(value)
    const hash = parsed.hash.startsWith('#/') ? parsed.hash.split(/[?&=]/, 1)[0] : ''
    return `${parsed.origin}${parsed.pathname}${hash}`
  } catch {
    return value.split(/[?#]/, 1)[0].replace(/^(https?:\/\/)[^/@]+@/i, '$1')
  }
}
const normalizeFailureCategory = (value: unknown): WebExplorationFailureCategory | '' => {
  const category = String(value ?? '').trim()
  if (!category) return ''
  switch (category) {
    case 'network_blocked':
    case 'network_blocked_optional':
      return 'network_blocked_unknown'
    // navigation_failed / unsafe_action / locator_persistence 都是 runner 的稳定分类，
    // 必须原样显示：折叠成 wrong_page/unsupported_dom 会让用户看到错误的原因和处理建议。
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
      return category as WebExplorationFailureCategory
  }
}
const sanitizeDetailText = (value: unknown): string => String(value ?? '')
  .replace(/[\u0000-\u001f\u007f]/g, '')
  .replace(/https?:\/\/[^\s?#]+[^\s]*/gi, (url) => normalizeSafePageUrl(url))
  .replace(/\b[^\s,;:]+:[^\s,;@]+@[^\s,;]+/g, '[redacted-url]')
  .replace(/["']?\b(authorization|cookie|password|passwd|token|apikey|secretkey|secret|credential)["']?\s*[:=]\s*(?:"[^"]*"|'[^']*'|\{[^}]*\}|\[[^\]]*\]|Bearer\s+[^\s,;]+(?:\s+[^\s,;]+)?|[^\s,;]+)/gi, '$1=[redacted]')
  .replace(/["']?\b(locator|selector)["']?\s*[:=]\s*(?:"[^"]*"|'[^']*'|\{[^}]*\}|\[[^\]]*\]|[^\s,;]+)/gi, '$1=[redacted]')
const normalizeNetworkEntries = (value: unknown): Array<WebExplorationNetworkBlockedEntry> => {
  if (!Array.isArray(value)) return []
  return value.map((entry) => {
    if (!entry || typeof entry !== 'object' || Array.isArray(entry)) return {}
    const normalized = { ...(entry as WebExplorationNetworkBlockedEntry) }
    if (typeof normalized.origin === 'string') normalized.origin = normalizeSafePageUrl(normalized.origin)
    if (typeof normalized.path === 'string') normalized.path = normalized.path.split(/[?#]/, 1)[0]
    if (typeof normalized.resourceType === 'string') normalized.resourceType = sanitizeDetailText(normalized.resourceType)
    if (typeof normalized.criticality === 'string') normalized.criticality = sanitizeDetailText(normalized.criticality)
    if (typeof normalized.failureType === 'string') normalized.failureType = normalizeFailureCategory(normalized.failureType) || normalized.failureType
    return normalized
  })
}
const normalizeAgentEventDetails = (value: Record<string, unknown>): Record<string, unknown> => {
  const details = { ...value }
  const failure = details.failure && typeof details.failure === 'object' && !Array.isArray(details.failure) ? details.failure as Record<string, unknown> : undefined
  const normalizedCategory = normalizeFailureCategory(details.category)
  const normalizedFailureCategory = normalizeFailureCategory(failure?.category)
  if (normalizedCategory) details.category = normalizedCategory
  else if (normalizedFailureCategory) details.category = normalizedFailureCategory
  if (failure && normalizedFailureCategory) failure.category = normalizedFailureCategory
  if (typeof details.retryable !== 'boolean' && typeof failure?.retryable === 'boolean') details.retryable = failure.retryable
  if (typeof details.failureReason === 'string') details.failureReason = sanitizeDetailText(details.failureReason)
  else if (typeof failure?.failureReason === 'string') details.failureReason = sanitizeDetailText(failure.failureReason)
  if (typeof details.pageUrl === 'string') details.pageUrl = normalizeSafePageUrl(details.pageUrl)
  if (typeof details.safePageUrl === 'string') details.safePageUrl = normalizeSafePageUrl(details.safePageUrl)
  if (typeof details.safePageUrl !== 'string' && typeof details.pageUrl === 'string') details.safePageUrl = details.pageUrl
  if (typeof details.locatorFingerprint !== 'string' && typeof details.fingerprint === 'string') details.locatorFingerprint = details.fingerprint
  if (typeof details.accessibleName !== 'string' && typeof details.accessName === 'string') details.accessibleName = details.accessName
  if (typeof details.accessName !== 'string' && typeof details.accessibleName === 'string') details.accessName = details.accessibleName
  if (details.handoff && typeof details.handoff === 'object' && !Array.isArray(details.handoff)) {
    const handoff = details.handoff as WebExplorationHandoffDetails
    const normalizedHandoffReason = normalizeFailureCategory(handoff.reason)
    details.handoff = {
      ...handoff,
      ...(typeof handoff.required === 'boolean' ? { required: handoff.required } : {}),
      ...(typeof handoff.mode === 'string' ? { mode: sanitizeDetailText(handoff.mode) } : {}),
      ...(normalizedHandoffReason ? { reason: normalizedHandoffReason } : typeof handoff.reason === 'string' ? { reason: sanitizeDetailText(handoff.reason) } : {}),
      ...(typeof handoff.resumeSupported === 'boolean' ? { resumeSupported: handoff.resumeSupported } : {})
    }
    if (!details.category && normalizedHandoffReason) details.category = normalizedHandoffReason
    if (!details.category && handoff.required) details.category = 'manual_review_required'
  }
  if (details.network && typeof details.network === 'object' && !Array.isArray(details.network)) {
    const network = details.network as WebExplorationEventDetails['network']
    details.network = {
      criticalBlocked: normalizeNetworkEntries(network?.criticalBlocked),
      optionalBlocked: normalizeNetworkEntries(network?.optionalBlocked),
      unknownBlocked: normalizeNetworkEntries(network?.unknownBlocked),
      authDependencies: normalizeNetworkEntries(network?.authDependencies)
    }
  }
  if (Array.isArray(details.repairTrail)) {
    details.repairTrail = details.repairTrail.slice(-5).map((item) => ({
      category: typeof item.category === 'string' ? sanitizeDetailText(item.category) : '',
      round: typeof item.round === 'number' ? item.round : undefined,
      changedFingerprint: typeof item.changedFingerprint === 'boolean' ? item.changedFingerprint : undefined
    }))
  }
  return details
}
export const startWebCaseAgentRun = (body: WebCaseAgentStartRequest, options: WebCaseAgentRepositoryOptions = {}) => request<WebCaseAgentRun>(options, '/api/ai/web-case-agent-runs', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
export const decideWebCasePlanArtifact = (runId: string, artifactId: string, accepted: boolean, options: WebCaseAgentRepositoryOptions = {}) => request<void>(options, `/api/ai/web-case-agent-runs/${encodeURIComponent(runId)}/artifacts/${encodeURIComponent(artifactId)}/decision`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ accepted }) })
export const confirmAllWebCasePlanArtifacts = (runId: string, options: WebCaseAgentRepositoryOptions = {}) => request<void>(options, `/api/ai/web-case-agent-runs/${encodeURIComponent(runId)}/artifacts/confirm-all`, { method: 'POST' })
export const updateWebCasePlanArtifact = (artifactId: string, candidateJson: Record<string, unknown>, options: WebCaseAgentRepositoryOptions = {}) => request<void>(options, `/api/ai/web-case-agent-runs/artifacts/${encodeURIComponent(artifactId)}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ candidateJson }) })
export const getWebCaseAgentRun = (runId: string, options: WebCaseAgentRepositoryOptions = {}) => request<WebCaseAgentRun>(options, `/api/ai/web-case-agent-runs/${encodeURIComponent(runId)}`)
export const listWebCaseAgentEvents = (runId: string, after = 0, options: WebCaseAgentRepositoryOptions = {}) => request<WebCaseAgentEvent[]>(options, `/api/ai/web-case-agent-runs/${encodeURIComponent(runId)}/events?after=${after}`)
export const listWebCaseAgentArtifacts = (runId: string, options: WebCaseAgentRepositoryOptions = {}) => request<WebCaseAgentArtifact[]>(options, `/api/ai/web-case-agent-runs/${encodeURIComponent(runId)}/artifacts`)
export const exportAcceptedWebCaseAgentArtifacts = (runId: string, options: WebCaseAgentRepositoryOptions = {}) => request<WebCaseAgentArtifact[]>(options, `/api/ai/web-case-agent-runs/${encodeURIComponent(runId)}/artifacts/export`)
export const exportAcceptedWebCaseAgentArtifactRevision = (runId: string, artifactId: string, revision: number, options: WebCaseAgentRepositoryOptions = {}) => request<WebCaseAgentArtifact>(options, `/api/ai/web-case-agent-runs/${encodeURIComponent(runId)}/artifacts/${encodeURIComponent(artifactId)}/revisions/${revision}/export`)
export const cancelWebCaseAgentRun = (runId: string, options: WebCaseAgentRepositoryOptions = {}) => request<WebCaseAgentRun>(options, `/api/ai/web-case-agent-runs/${encodeURIComponent(runId)}/cancel`, { method: 'POST' })
export const retryWebCaseAgentRun = (runId: string, options: WebCaseAgentRepositoryOptions = {}) => request<WebCaseAgentRun>(options, `/api/ai/web-case-agent-runs/${encodeURIComponent(runId)}/retry`, { method: 'POST' })
export const resumeWebCaseAgentRun = (runId: string, options: WebCaseAgentRepositoryOptions = {}) => request<WebCaseAgentRun>(options, `/api/ai/web-case-agent-runs/${encodeURIComponent(runId)}/resume`, { method: 'POST' })
export const getAgentRules = (projectId?: number, options: WebCaseAgentRepositoryOptions = {}) => request<AgentRuleVersion>(options, `/api/ai/agent-assets/rules${projectId ? `?projectId=${projectId}` : ''}`)
export const saveAgentRuleDraft = (contentText: string, phaseRules: Record<string, string>, projectId?: number, options: WebCaseAgentRepositoryOptions = {}) => request<AgentRuleVersion>(options, `/api/ai/agent-assets/rules/web-case-agent/draft${projectId ? `?projectId=${projectId}` : ''}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ contentText, phaseRules }) })
export const publishAgentRule = (version: number, projectId?: number, options: WebCaseAgentRepositoryOptions = {}) => request<AgentRuleVersion>(options, `/api/ai/agent-assets/rules/web-case-agent/publish${projectId ? `?projectId=${projectId}` : ''}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ version }) })
export const listLocatorKnowledge = (projectId: number, options: WebCaseAgentRepositoryOptions = {}) => request<{ items: LocatorKnowledgeEntry[] }>(options, `/api/ai/agent-assets/locator-knowledge?projectId=${projectId}`)
export const createLocatorKnowledge = (body: SaveLocatorKnowledgeRequest, options: WebCaseAgentRepositoryOptions = {}) => request<{ items: LocatorKnowledgeEntry[] }>(options, '/api/ai/agent-assets/locator-knowledge', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }).then(result => result.items[0])
export const updateLocatorKnowledge = (entryId: string, body: SaveLocatorKnowledgeRequest, options: WebCaseAgentRepositoryOptions = {}) => request<LocatorKnowledgeEntry>(options, `/api/ai/agent-assets/locator-knowledge/${encodeURIComponent(entryId)}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
export const markLocatorKnowledgeStale = (entryId: string, options: WebCaseAgentRepositoryOptions = {}) => request<void>(options, `/api/ai/agent-assets/locator-knowledge/${encodeURIComponent(entryId)}/stale`, { method: 'POST' })
export const listAgentMemories = (projectId: number, pendingOnly = false, options: WebCaseAgentRepositoryOptions = {}) => request<AgentMemory[]>(options, `/api/ai/agent-assets/memories?projectId=${projectId}&pendingOnly=${pendingOnly}`)
export const createAgentMemory = (body: CreateAgentMemoryRequest, options: WebCaseAgentRepositoryOptions = {}) => request<AgentMemory>(options, '/api/ai/agent-assets/memories', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
export const updateAgentMemory = (memoryId: string, body: UpdateAgentMemoryRequest, options: WebCaseAgentRepositoryOptions = {}) => request<AgentMemory>(options, `/api/ai/agent-assets/memories/${encodeURIComponent(memoryId)}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
export const confirmAgentMemory = (memoryId: string, scopeType: AgentMemory['scopeType'], projectIds: number[], options: WebCaseAgentRepositoryOptions = {}) => request<AgentMemory>(options, `/api/ai/agent-assets/memories/${encodeURIComponent(memoryId)}/confirm`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ scopeType, projectIds }) })
export const disableAgentMemory = (memoryId: string, options: WebCaseAgentRepositoryOptions = {}) => request<void>(options, `/api/ai/agent-assets/memories/${encodeURIComponent(memoryId)}/disable`, { method: 'POST' })
export const deleteAgentMemory = (memoryId: string, options: WebCaseAgentRepositoryOptions = {}) => request<void>(options, `/api/ai/agent-assets/memories/${encodeURIComponent(memoryId)}`, { method: 'DELETE' })
export const recordAgentAssetOutcome = (runId: string, pointId: string, succeeded: boolean, options: WebCaseAgentRepositoryOptions = {}) => request<void>(options, '/api/ai/agent-assets/usage/outcome', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ runId, pointId, succeeded }) })
export interface WebCaseQualityRating { ratingId: string; runId: string; projectId: number; overallScore: number; dimensionScores: Record<string, number>; comment: string; baseline: boolean; createdAt: string }
export interface RateWebCaseQualityRequest { overallScore: number; dimensionScores?: Record<string, number>; comment?: string }
export const rateWebCaseQuality = (runId: string, body: RateWebCaseQualityRequest, options: WebCaseAgentRepositoryOptions = {}) => request<WebCaseQualityRating>(options, `/api/ai/web-case-agent-runs/${encodeURIComponent(runId)}/quality-rating`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
export const getWebCaseQualityRating = (runId: string, options: WebCaseAgentRepositoryOptions = {}) => request<WebCaseQualityRating | null>(options, `/api/ai/web-case-agent-runs/${encodeURIComponent(runId)}/quality-rating`)
export const listWebCaseQualityRatings = (projectId: number, options: WebCaseAgentRepositoryOptions = {}) => request<{ items: WebCaseQualityRating[] }>(options, `/api/ai/web-case-agent-runs/quality-ratings?projectId=${projectId}`)
export interface WebCaseWeakDimension { key: string; avg: number }
export interface WebCaseLostCase { title: string; count: number }
export interface WebCaseQualityInsight {
  projectId: number
  ratedCount: number
  baselineCount: number
  overallAvg: number
  recallAvg: number
  lostCaseCount: number
  dimensionAvgs: Record<string, number>
  weakestDimensions: WebCaseWeakDimension[]
  lostCases: WebCaseLostCase[]
  humanCount: number
  objectiveCount: number
}
export const getWebCaseQualityInsight = (projectId: number, options: WebCaseAgentRepositoryOptions = {}) => request<WebCaseQualityInsight>(options, `/api/ai/web-case-agent-runs/quality-insight?projectId=${projectId}`)
export interface WebCaseRegressionCaseRef { title: string; assertions: string }
export interface WebCaseRegressionPointComparison { pointId: string; baselineCount: number; recalledCount: number; addedCount: number; lostCount: number; added: WebCaseRegressionCaseRef[]; lost: WebCaseRegressionCaseRef[] }
export interface WebCaseRegressionSummary { baselineTotal: number; recalled: number; added: number; lost: number; recallRatio: number }
export interface WebCaseRegressionComparison { runId: string; baselineRunId: string | null; baselineOverallScore: number | null; available: boolean; points: WebCaseRegressionPointComparison[]; summary: WebCaseRegressionSummary | null }
export const getWebCaseRegressionComparison = (runId: string, options: WebCaseAgentRepositoryOptions = {}) => request<WebCaseRegressionComparison>(options, "/api/ai/web-case-agent-runs/" + encodeURIComponent(runId) + "/regression-comparison")

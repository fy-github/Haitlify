/**
 * T12：QA 助手仓库——任务目录、执行、人工评审与组合任务（Bug→日志→回归）。
 *
 * 与后端契约对齐的关键语义：
 *   - 目录只给候选与缺项；显式选择优先，自动识别不触发任何写操作；
 *   - 产物状态由后端确定性裁决（PENDING_HUMAN_CONFIRMATION 起步），前端不推测；
 *   - 评审携带 revision：过期即 409 stale_revision，绝不覆盖。
 */
import { joinBackendPath } from './backendBaseUrl'
import { authedFetch } from './http'
import type { AiPlatformRepositoryOptions } from './aiPlatformRepository'

export type AiAssistantRepositoryOptions = AiPlatformRepositoryOptions

export interface AiTaskDefinition {
  taskType: string
  displayName: string
  requiredInputs: string[]
  evidenceTypes: string[]
  toolAllowlist: string[]
  humanGateRequired: boolean
  artifactTarget: string
}

export interface AiRoutingCandidate {
  taskType: string
  displayName: string
  missingInputs: string[]
  evidenceReady: boolean
  complete: boolean
}

export interface AiAssistantRun {
  runId: string
  taskType: string
  projectId: number
  status: string
  output?: unknown
  violations: string[]
  artifactId?: string | null
  revision: number
  acceptanceState: string
  blockReason?: string
  missingInputs?: string[]
}

export interface AiCompositeNode {
  nodeId: string
  position: number
  taskType: string
  dependsOn: string[]
  status: string
  runId: string
  artifactId: string
  skipReason: string
}

export interface AiCompositeRun {
  compositeId: string
  projectId: number
  template: string
  status: string
  nodes: AiCompositeNode[]
}

const jsonHeaders = (): HeadersInit => ({ 'Content-Type': 'application/json' })

const requestJson = async <T>(
  options: AiAssistantRepositoryOptions,
  path: string,
  init?: RequestInit
): Promise<T> => {
  const requestInit = options.signal ? { ...init, signal: options.signal } : init
  const response = await fetcher(options)(joinBackendPath(path, options.baseUrl), requestInit)
  if (!response.ok) {
    const payload = await response.json().catch(() => undefined) as { message?: string; code?: string } | undefined
    const suffix = payload?.message || payload?.code ? ` ${payload.message || payload.code}` : ''
    const error = new Error(`AI assistant request failed: ${response.status}${suffix}`)
    ;(error as { status?: number }).status = response.status
    throw error
  }
  return await response.json() as T
}

const fetcher = (options: AiAssistantRepositoryOptions): typeof authedFetch => {
  if (options.fetchImpl) return options.fetchImpl as typeof authedFetch
  if (typeof fetch !== 'undefined') return authedFetch
  throw new Error('Fetch API is not available')
}

export const fetchAssistantCatalog = (options: AiAssistantRepositoryOptions): Promise<{ tasks: AiTaskDefinition[] }> =>
  requestJson(options, '/api/ai/assistant/catalog')

export const fetchRoutingCandidates = (
  options: AiAssistantRepositoryOptions,
  inputs: Record<string, unknown>
): Promise<AiRoutingCandidate[]> =>
  requestJson(options, '/api/ai/assistant/routing-candidates', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ inputs })
  })

export const startAssistantRun = (
  options: AiAssistantRepositoryOptions,
  request: { taskType: string; projectId: number; inputs: Record<string, unknown>; actorId?: string }
): Promise<AiAssistantRun> =>
  requestJson(options, '/api/ai/assistant/runs', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(request)
  })

export const fetchAssistantRun = (
  options: AiAssistantRepositoryOptions,
  runId: string
): Promise<AiAssistantRun> =>
  requestJson(options, `/api/ai/assistant/runs/${encodeURIComponent(runId)}`)

export interface AiDraftPointSummary {
  pointId: string
  title: string
  moduleName: string
  caseKind: string
  reviewStatus: string
  updatedAt: string
}

/** 草稿测试点目录（testcase_generation 运行卡 draftPointIds 数据源）：仅已确认/已修订可用点，服务端收紧 limit。 */
export const fetchAssistantDraftPoints = (
  options: AiAssistantRepositoryOptions,
  projectId: number,
  limit?: number
): Promise<{ items: AiDraftPointSummary[]; limit: number }> =>
  requestJson(
    options,
    `/api/ai/assistant/draft-points?projectId=${projectId}${limit == null ? '' : `&limit=${limit}`}`
  )

export interface AiArtifactSummary {
  artifactId: string
  runId: string
  taskType: string
  projectId: number
  revision: number
  acceptanceState: string
  status: 'waiting_review' | 'accepted' | 'rejected'
  reviewedBy: string
  createdAt: string
}

/** 近期产物列表（评审 UI 数据源）：projectId 必填；服务端按 created_at desc 稳定排序并收紧 limit。 */
export const fetchAssistantArtifacts = (
  options: AiAssistantRepositoryOptions,
  projectId: number,
  limit?: number
): Promise<{ artifacts: AiArtifactSummary[]; limit: number }> =>
  requestJson(
    options,
    `/api/ai/assistant/artifacts?projectId=${projectId}${limit == null ? '' : `&limit=${limit}`}`
  )

export interface AiArtifactExport {
  artifactId: string
  runId: string
  taskType: string
  projectId: number
  revision: number
  acceptanceState: string
  reviewedBy: string
  exportedAt: string
  output?: unknown
  sourceManifest?: unknown
  violations?: unknown
}

/**
 * 导出已接受产物（T11:314 导出腿）：仅 ACCEPTED 可导出，未接受 ⇒ 400 artifact_not_accepted，
 * 过期 revision ⇒ 409 stale_revision；前端只透传后端裁决，不自行放宽。
 */
export const fetchAssistantArtifactExport = (
  options: AiAssistantRepositoryOptions,
  artifactId: string,
  revision?: number
): Promise<AiArtifactExport> =>
  requestJson(
    options,
    `/api/ai/assistant/artifacts/${encodeURIComponent(artifactId)}/export${revision == null ? '' : `?revision=${revision}`}`
  )

export const reviewAssistantArtifact = (
  options: AiAssistantRepositoryOptions,
  artifactId: string,
  request: { decision: 'approve' | 'reject' | 'change'; revision?: number; candidate?: unknown }
): Promise<AiAssistantRun> =>
  requestJson(options, `/api/ai/assistant/artifacts/${encodeURIComponent(artifactId)}/review`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(request)
  })

export const startCompositeRun = (
  options: AiAssistantRepositoryOptions,
  request: { template: string; projectId: number; inputs: Record<string, unknown>; actorId?: string }
): Promise<AiCompositeRun> =>
  requestJson(options, '/api/ai/assistant/composites', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(request)
  })

export const reviewCompositeNode = (
  options: AiAssistantRepositoryOptions,
  compositeId: string,
  nodeId: string,
  request: { decision: 'approve' | 'reject' | 'change'; revision?: number; candidate?: unknown }
): Promise<AiCompositeRun> =>
  requestJson(
    options,
    `/api/ai/assistant/composites/${encodeURIComponent(compositeId)}/nodes/${encodeURIComponent(nodeId)}/review`,
    { method: 'POST', headers: jsonHeaders(), body: JSON.stringify(request) }
  )

/** 组合节点是否可评审：仅 waiting_review 可操作；skipped 节点展示原因，不提供操作。 */
export const nodeActionable = (node: AiCompositeNode): boolean => node.status === 'waiting_review'

/** 缺项优先的显式路由建议（呈现用，不触发任何写操作）。 */
export const recommendTasks = (candidates: ReadonlyArray<AiRoutingCandidate>): AiRoutingCandidate[] =>
  [...candidates].sort((left, right) => {
    if (left.complete !== right.complete) return left.complete ? -1 : 1
    return left.missingInputs.length - right.missingInputs.length
  })

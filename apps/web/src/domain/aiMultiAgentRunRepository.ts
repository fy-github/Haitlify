import { joinBackendPath } from './backendBaseUrl'
import { authedFetch } from './http'
import type {
  AgentConflictView,
  AgentGraphNodeView,
  AutomationSuggestionView,
  QualityScoreView
} from './aiMultiAgentRunView'

type FetchLike = (input: string, init?: RequestInit) => Promise<Response>

export interface AiMultiAgentRepositoryOptions {
  baseUrl?: string
  fetchImpl?: FetchLike
}

export interface StartAiMultiAgentRunPayload {
  projectId: number
  targetCaseKind: string
  inputSummary?: Record<string, unknown>
}

export interface AiMultiAgentRunResponse {
  runId: string
  graphId: string
  projectId?: number
  status: string
  nodeCount: number
  inputSnapshot?: Record<string, unknown>
  outputSnapshot?: Record<string, unknown>
  errorSummary?: string
  createdAt?: string
  updatedAt?: string
}

export interface AiMultiAgentGraphResponse {
  runId: string
  status: string
  inputSnapshot?: Record<string, unknown>
  outputSnapshot?: Record<string, unknown>
  errorSummary?: string
  nodes: AgentGraphNodeView[]
}

export interface AiMultiAgentHandoffResponse {
  handoffId: string
  fromAgentId: string
  toAgentId: string
  payloadSummary: string
}

export interface AiMultiAgentListResponse<T> {
  items: T[]
}

export interface AiAgentMergeCandidate {
  title: string
  targetKey: string
  caseKind: string
  priority: string
  expectedResult: string
  sourceEvidence: string[]
  suggestedSteps: string[]
}

export interface AiAgentMergeRequest {
  candidates: AiAgentMergeCandidate[]
}

export interface AiAgentMergeResponse {
  status: string
  mergedCount: number
  unresolvedConflictCount: number
  createdDraftPointIds: string[]
  aiEnrichmentFallback?: boolean
}

export interface AiAgentConflictResolutionResponse {
  conflictId: string
  resolutionStatus: string
}

export const startAiMultiAgentRun = async (
  payload: StartAiMultiAgentRunPayload,
  options: AiMultiAgentRepositoryOptions = {}
): Promise<AiMultiAgentRunResponse> =>
  request('/api/ai/multi-agent-runs', options, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })

export const listAiMultiAgentGraph = async (
  runId: string,
  options: AiMultiAgentRepositoryOptions = {}
): Promise<AiMultiAgentGraphResponse> =>
  request(`/api/ai/multi-agent-runs/${encodeURIComponent(runId)}/graph`, options)

export const listAiMultiAgentHandoffs = async (
  runId: string,
  options: AiMultiAgentRepositoryOptions = {}
): Promise<AiMultiAgentListResponse<AiMultiAgentHandoffResponse>> =>
  request(`/api/ai/multi-agent-runs/${encodeURIComponent(runId)}/handoffs`, options)

export const listAiMultiAgentConflicts = async (
  runId: string,
  options: AiMultiAgentRepositoryOptions = {}
): Promise<AiMultiAgentListResponse<AgentConflictView>> =>
  request(`/api/ai/multi-agent-runs/${encodeURIComponent(runId)}/conflicts`, options)

export const listAiMultiAgentQualityScores = async (
  runId: string,
  options: AiMultiAgentRepositoryOptions = {}
): Promise<AiMultiAgentListResponse<QualityScoreView>> =>
  request(`/api/ai/multi-agent-runs/${encodeURIComponent(runId)}/quality-scores`, options)

export const listAiMultiAgentAutomationSuggestions = async (
  runId: string,
  options: AiMultiAgentRepositoryOptions = {}
): Promise<AiMultiAgentListResponse<AutomationSuggestionView>> =>
  request(`/api/ai/multi-agent-runs/${encodeURIComponent(runId)}/automation-suggestions`, options)

export const mergeAiMultiAgentRun = async (
  runId: string,
  payload: AiAgentMergeRequest,
  options: AiMultiAgentRepositoryOptions = {}
): Promise<AiAgentMergeResponse> =>
  request(`/api/ai/multi-agent-runs/${encodeURIComponent(runId)}/merge`, options, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })

export const ignoreAiMultiAgentConflict = async (
  conflictId: string,
  resolution: string,
  options: AiMultiAgentRepositoryOptions = {}
): Promise<AiAgentConflictResolutionResponse> =>
  request(`/api/ai/agent-conflicts/${encodeURIComponent(conflictId)}/ignore`, options, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ resolution })
  })

const request = async <T>(path: string, options: AiMultiAgentRepositoryOptions, init?: RequestInit): Promise<T> => {
  const response = await fetcher(options)(joinBackendPath(path, options.baseUrl), init)
  if (!response.ok) {
    throw new Error(`AI multi-agent request failed: ${response.status}`)
  }
  return await response.json() as T
}

const fetcher = (options: AiMultiAgentRepositoryOptions): FetchLike => {
  if (options.fetchImpl) return options.fetchImpl
  if (typeof fetch !== 'undefined') return authedFetch
  throw new Error('Fetch API is not available')
}

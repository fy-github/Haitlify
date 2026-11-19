/**
 * A1 助手链路的前端仓库：草稿 → 确认 → 执行 → 报告。
 *
 * 契约要点（与后端 `AssistantChainController` 对齐）：
 *   - 未确认草稿执行会被后端拒绝（409 draft_not_confirmed），前端不预判、只回显；
 *   - 执行结果以 `executionState` 为准，提交成功不等于成功；
 *   - 报告是快照，接受/驳回只改 `reportState`。
 */
import { joinBackendPath } from './backendBaseUrl'
import { authedFetch } from './http'

export interface AssistantChainOptions {
  baseUrl?: string
  signal?: AbortSignal
  fetchImpl?: typeof authedFetch
}

export interface CaseSpecStep {
  id: string
  action: string
  target: string
  value?: string | null
}

export interface CaseSpec {
  id: string
  name: string
  startUrl: string
  steps: CaseSpecStep[]
}

export interface DraftView {
  draftId: string
  state: 'DRAFT' | 'CONFIRMED' | 'REJECTED' | string
  requirementDigest: string
  specDigest: string
  spec: CaseSpec
  confirmedBy?: number | null
  projectId?: number | null
  actionCode: string
  targetRef: string
  targetRevision: string
  inputs?: Record<string, string>
  inputsDigest?: string
  revision?: number
  rejectedBy?: number | null
  supersededBy?: string | null
}

export interface TaskResult {
  requestId: string
  operationId: string
  executionState: string
  runId?: string | null
  digest: string
  stepCount: number
  created: boolean
  hermesSessionId?: string | null
  hermesTurnRef?: string | null
}

export interface AttemptView {
  attemptId: string
  generation: number
  runnerRunId?: string | null
  outcome: string
  hermesStoredSessionId?: string | null
  hermesTurnRef?: string | null
}

export interface OperationView {
  operationId: string
  executionState: string
  reportState: string
  dispatchGeneration: number
  attempts: AttemptView[]
  resources: ResourceView[]
}

export interface WaitingView {
  operationId: string
  executionState: string
  reason: string | null
  requestedAt: string | null
  deadlineAt: string | null
}

export interface ResourceView {
  resourceId: string
  resourceKind: string
  cleanupState: string
  cleanupExecutor?: string
  deadlineAt: string
  cleanedBy?: number | null
  cleanedAt?: string | null
}

export interface ReportView {
  reportId: string
  operationId: string
  revision: number
  bodyJson: string
  createdAt: string
}

const jsonHeaders = (): HeadersInit => ({ 'Content-Type': 'application/json' })

const fetcher = (options: AssistantChainOptions): typeof authedFetch => {
  if (options.fetchImpl) return options.fetchImpl
  if (typeof fetch !== 'undefined') return authedFetch
  throw new Error('Fetch API is not available')
}

const requestJson = async <T>(options: AssistantChainOptions, path: string, init?: RequestInit): Promise<T> => {
  const requestInit = options.signal ? { ...init, signal: options.signal } : init
  const response = await fetcher(options)(joinBackendPath(path, options.baseUrl), requestInit)
  if (!response.ok) {
    const payload = (await response.json().catch(() => undefined)) as
      | { message?: string; error?: string }
      | undefined
    const suffix = payload?.message || payload?.error ? ` ${payload.message || payload.error}` : ''
    const error = new Error(`assistant chain request failed: ${response.status}${suffix}`)
    ;(error as { status?: number }).status = response.status
    throw error
  }
  return (await response.json()) as T
}

export const createDraft = (
  options: AssistantChainOptions,
  body: {
    tenantId: number
    projectId: number
    createdBy: number
    requirementRef: string
    requirementText: string
    actionCode: string
    targetRef: string
    targetRevision: string
    inputs?: Record<string, string>
    spec: CaseSpec
  }
): Promise<DraftView> =>
  requestJson(options, '/api/assistant/drafts', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(body)
  })

export const confirmDraft = (
  options: AssistantChainOptions,
  draftId: string,
  tenantId: number,
  actorId: number
): Promise<DraftView> =>
  requestJson(options, `/api/assistant/drafts/${encodeURIComponent(draftId)}/confirm`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ tenantId, actorId })
  })

export const rejectDraft = (
  options: AssistantChainOptions,
  draftId: string,
  tenantId: number,
  actorId: number
): Promise<DraftView> =>
  requestJson(options, `/api/assistant/drafts/${encodeURIComponent(draftId)}/reject`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ tenantId, actorId })
  })

export const getDraft = (options: AssistantChainOptions, draftId: string, tenantId: number): Promise<DraftView> =>
  requestJson(options, `/api/assistant/drafts/${encodeURIComponent(draftId)}?tenantId=${tenantId}`)

export const runTask = (
  options: AssistantChainOptions,
  body: {
    tenantId: number
    actorId: number
    projectId: number
    requestId: string
    draftId: string
    actionCode: string
    targetRef: string
    targetRevision: string
    inputs?: Record<string, string>
    spec: CaseSpec
    waitSeconds?: number
  }
): Promise<TaskResult> =>
  requestJson(options, '/api/assistant/tasks', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(body)
  })

export const getOperation = (
  options: AssistantChainOptions,
  operationId: string,
  tenantId: number
): Promise<OperationView> =>
  requestJson(options, `/api/assistant/operations/${encodeURIComponent(operationId)}?tenantId=${tenantId}`)

export const syncOperation = (
  options: AssistantChainOptions,
  operationId: string,
  tenantId: number
): Promise<{ executionState: string }> =>
  requestJson(options, `/api/assistant/operations/${encodeURIComponent(operationId)}/sync?tenantId=${tenantId}`, {
    method: 'POST'
  })

export const describeWaitingUser = (
  options: AssistantChainOptions,
  operationId: string,
  tenantId: number
): Promise<WaitingView> =>
  requestJson(options, `/api/assistant/operations/${encodeURIComponent(operationId)}/waiting-user?tenantId=${tenantId}`)

export const requireWaitingUser = (
  options: AssistantChainOptions,
  operationId: string,
  tenantId: number,
  reason: string,
  timeoutMinutes?: number
): Promise<WaitingView> =>
  requestJson(options, `/api/assistant/operations/${encodeURIComponent(operationId)}/waiting-user?tenantId=${tenantId}`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ reason, timeoutMinutes: timeoutMinutes ?? null })
  })

export const resumeWaitingUser = (
  options: AssistantChainOptions,
  operationId: string,
  tenantId: number
): Promise<WaitingView> =>
  requestJson(options, `/api/assistant/operations/${encodeURIComponent(operationId)}/waiting-user/resume?tenantId=${tenantId}`, {
    method: 'POST'
  })

export const cancelWaitingUser = (
  options: AssistantChainOptions,
  operationId: string,
  tenantId: number,
  reason: string
): Promise<WaitingView> =>
  requestJson(options, `/api/assistant/operations/${encodeURIComponent(operationId)}/waiting-user/cancel?tenantId=${tenantId}`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ reason })
  })

export const generateReport = (
  options: AssistantChainOptions,
  operationId: string,
  tenantId: number,
  generatedBy?: number
): Promise<ReportView> =>
  requestJson(options, `/api/assistant/operations/${encodeURIComponent(operationId)}/report/generate?tenantId=${tenantId}`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ generatedBy: generatedBy ?? null })
  })

export const latestReport = (
  options: AssistantChainOptions,
  operationId: string,
  tenantId: number
): Promise<ReportView> =>
  requestJson(options, `/api/assistant/operations/${encodeURIComponent(operationId)}/report?tenantId=${tenantId}`)

export const decideReport = (
  options: AssistantChainOptions,
  operationId: string,
  tenantId: number,
  reportId: string,
  accepted: boolean
): Promise<{ operationId: string; reportState: string }> =>
  requestJson(options, `/api/assistant/operations/${encodeURIComponent(operationId)}/report?tenantId=${tenantId}`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ reportId, accepted })
  })

export const cleanResource = (
  options: AssistantChainOptions,
  operationId: string,
  resourceId: string,
  tenantId: number,
  evidence: string,
  actorId?: number
): Promise<{ operationId: string; resourceId: string; cleanupState: string }> =>
  requestJson(
    options,
    `/api/assistant/operations/${encodeURIComponent(operationId)}/resources/${encodeURIComponent(resourceId)}/clean?tenantId=${tenantId}`,
    {
      method: 'POST',
      headers: jsonHeaders(),
      body: JSON.stringify({ evidence, actorId: actorId ?? null })
    }
  )

export const markResourceUnrecoverable = (
  options: AssistantChainOptions,
  operationId: string,
  resourceId: string,
  tenantId: number,
  reason: string
): Promise<{ operationId: string; resourceId: string; cleanupState: string }> =>
  requestJson(
    options,
    `/api/assistant/operations/${encodeURIComponent(operationId)}/resources/${encodeURIComponent(resourceId)}/unrecoverable?tenantId=${tenantId}`,
    {
      method: 'POST',
      headers: jsonHeaders(),
      body: JSON.stringify({ reason })
    }
  )

export const resolveStalled = (
  options: AssistantChainOptions,
  operationId: string,
  tenantId: number,
  outcome: 'FAILED' | 'CANCELLED',
  reason: string,
  actorId?: number
): Promise<{ operationId: string; executionState: string }> =>
  requestJson(options, `/api/assistant/operations/${encodeURIComponent(operationId)}/resolve?tenantId=${tenantId}`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ outcome, reason, actorId: actorId ?? null })
  })

export const stopOperation = (
  options: AssistantChainOptions,
  operationId: string,
  tenantId: number,
  reason: string,
  actorId?: number
): Promise<{ operationId: string; executionState: string }> =>
  requestJson(options, `/api/assistant/operations/${encodeURIComponent(operationId)}/stop?tenantId=${tenantId}`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ reason, actorId: actorId ?? null })
  })

export const reviseDraft = (
  options: AssistantChainOptions,
  draftId: string,
  tenantId: number,
  actorId: number,
  spec: CaseSpec,
  inputs?: Record<string, string>
): Promise<DraftView> =>
  requestJson(options, `/api/assistant/drafts/${encodeURIComponent(draftId)}/revise`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ tenantId, actorId, spec, inputs: inputs ?? {} })
  })

export const listDrafts = (
  options: AssistantChainOptions,
  tenantId: number,
  projectId: number,
  state?: string
): Promise<DraftView[]> =>
  requestJson(
    options,
    `/api/assistant/drafts?tenantId=${tenantId}&projectId=${projectId}${state ? `&state=${encodeURIComponent(state)}` : ''}`
  )

export const listDraftRevisions = (
  options: AssistantChainOptions,
  draftId: string,
  tenantId: number
): Promise<DraftView[]> =>
  requestJson(options, `/api/assistant/drafts/${encodeURIComponent(draftId)}/revisions?tenantId=${tenantId}`)

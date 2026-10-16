import { joinBackendPath } from './backendBaseUrl'
import { authedFetch } from './http'

type FetchLike = (input: string, init?: RequestInit) => Promise<Response>

export interface LocatorKnowledgeRepositoryOptions {
  baseUrl?: string
  fetchImpl?: FetchLike
  signal?: AbortSignal
}

/** 阶段 7 增量 3：locator 修复提案（与平台 LocatorKnowledgeService.LocatorRevisionProposal 对齐）。 */
export interface LocatorRepairProposal {
  proposalId: string
  entryId: string
  oldLocator: string
  newLocator: string
  pageFingerprintBefore?: string
  pageFingerprintAfter?: string
  confidence?: number
  status: string
  reason?: string
  revision?: number
  createdAt?: string
}

const request = async <T>(
  options: LocatorKnowledgeRepositoryOptions,
  path: string,
  init?: RequestInit
): Promise<T> => {
  const response = await (options.fetchImpl || authedFetch)(joinBackendPath(path, options.baseUrl), {
    ...init,
    signal: options.signal,
    headers: { Accept: 'application/json', ...(init?.headers || {}) }
  })
  if (!response.ok) throw new Error((await response.text()) || `请求失败: ${response.status}`)
  const body = await response.text()
  if (!body.trim()) return undefined as T
  return JSON.parse(body) as T
}

const repairProposalsPath = '/api/ai/agent-assets/locator-knowledge/repair-proposals'

export const listLocatorRepairProposals = async (
  projectId: number,
  status?: string,
  options: LocatorKnowledgeRepositoryOptions = {}
): Promise<LocatorRepairProposal[]> => {
  const query = new URLSearchParams({ projectId: String(projectId) })
  if (status) query.set('status', status)
  const response = await request<{ items?: LocatorRepairProposal[] }>(
    options,
    `${repairProposalsPath}?${query.toString()}`
  )
  return response?.items || []
}

export const acceptLocatorRepairProposal = (
  proposalId: string,
  options: LocatorKnowledgeRepositoryOptions = {}
): Promise<LocatorRepairProposal> =>
  request(options, `${repairProposalsPath}/${encodeURIComponent(proposalId)}/accept`, { method: 'POST' })

export const rejectLocatorRepairProposal = (
  proposalId: string,
  options: LocatorKnowledgeRepositoryOptions = {}
): Promise<LocatorRepairProposal> =>
  request(options, `${repairProposalsPath}/${encodeURIComponent(proposalId)}/reject`, { method: 'POST' })

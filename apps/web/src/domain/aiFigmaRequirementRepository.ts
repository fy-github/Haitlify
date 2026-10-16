import { joinBackendPath } from './backendBaseUrl'
import { authedFetch } from './http'
import type {
  AiFigmaChangeRow,
  AiFigmaElementCandidateView,
  AiFigmaNodeView
} from './aiFigmaRequirementView'

type FetchLike = (input: string, init?: RequestInit) => Promise<Response>

export interface AiFigmaRepositoryOptions {
  baseUrl?: string
  fetchImpl?: FetchLike
}

export interface ImportAiFigmaFilePayload {
  projectId: number
  figmaUrl: string
  fileKey?: string
  nodeId?: string
}

export interface AiFigmaFileResponse {
  figmaFileId: string
  projectId: number
  fileKey: string
  selectedNodeId: string
  fileName: string
}

export interface AiFigmaNodeListResponse {
  items: AiFigmaNodeView[]
}

export interface AiFigmaAnalysisRunResponse {
  runId: string
  status: string
  draftTestPointCount: number
  resultOrigin?: string
  acceptanceState?: string
}

export interface AiFigmaDraftPointResponse {
  pointId: string
  title: string
  reviewStatus: string
  resultOrigin?: string
  acceptanceState?: string
  metadata: {
    evidence?: {
      sourceType: string
      fileKey: string
      nodeId: string
      nodeName: string
      nodeType: string
      componentName: string
    }
  }
}

export interface AiFigmaDraftPointListResponse {
  items: AiFigmaDraftPointResponse[]
}

export interface AiFigmaChangeSetResponse {
  changeSetId: string
  status: string
  changes: AiFigmaChangeRow[]
  createdAt: string
  updatedAt: string
}

export interface AiFigmaChangeSetListResponse {
  items: AiFigmaChangeSetResponse[]
}

export interface AiFigmaElementCandidateListResponse {
  items: AiFigmaElementCandidateView[]
}

export const importAiFigmaFile = async (
  payload: ImportAiFigmaFilePayload,
  options: AiFigmaRepositoryOptions = {}
): Promise<AiFigmaFileResponse> =>
  request('/api/ai/figma/import', options, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })

export const listAiFigmaNodes = async (
  figmaFileId: string,
  options: AiFigmaRepositoryOptions = {}
): Promise<AiFigmaNodeListResponse> =>
  request(`/api/ai/figma/files/${encodeURIComponent(figmaFileId)}/nodes`, options)

export const startAiFigmaFileAnalysis = async (
  figmaFileId: string,
  options: AiFigmaRepositoryOptions = {}
): Promise<AiFigmaAnalysisRunResponse> =>
  request(`/api/ai/figma/files/${encodeURIComponent(figmaFileId)}/analysis-runs`, options, { method: 'POST' })

export const startAiFigmaNodeAnalysis = async (
  figmaNodeId: string,
  options: AiFigmaRepositoryOptions = {}
): Promise<AiFigmaAnalysisRunResponse> =>
  request(`/api/ai/figma/nodes/${encodeURIComponent(figmaNodeId)}/analysis-runs`, options, { method: 'POST' })

export const listAiFigmaDraftPoints = async (
  runId: string,
  options: AiFigmaRepositoryOptions = {}
): Promise<AiFigmaDraftPointListResponse> =>
  request(`/api/ai/figma/analysis-runs/${encodeURIComponent(runId)}/draft-test-points`, options)

export const listAiFigmaChangeSets = async (
  figmaFileId: string,
  options: AiFigmaRepositoryOptions = {}
): Promise<AiFigmaChangeSetListResponse> =>
  request(`/api/ai/figma/files/${encodeURIComponent(figmaFileId)}/change-sets`, options)

export const listAiFigmaElementCandidates = async (
  figmaFileId: string,
  options: AiFigmaRepositoryOptions = {}
): Promise<AiFigmaElementCandidateListResponse> =>
  request(`/api/ai/figma/files/${encodeURIComponent(figmaFileId)}/element-candidates`, options)

const request = async <T>(path: string, options: AiFigmaRepositoryOptions, init?: RequestInit): Promise<T> => {
  const response = await fetcher(options)(joinBackendPath(path, options.baseUrl), init)
  if (!response.ok) {
    throw new Error(`AI Figma request failed: ${response.status}`)
  }
  return await response.json() as T
}

const fetcher = (options: AiFigmaRepositoryOptions): FetchLike => {
  if (options.fetchImpl) return options.fetchImpl
  if (typeof fetch !== 'undefined') return authedFetch
  throw new Error('Fetch API is not available')
}

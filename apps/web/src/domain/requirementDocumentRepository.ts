import { joinBackendPath } from './backendBaseUrl'
import { authedFetch } from './http'

type FetchLike = (input: string, init?: RequestInit) => Promise<Response>

export interface RequirementDocumentBackendOptions {
  baseUrl?: string
  fetchImpl?: FetchLike
}

export interface ParsedRequirementDocument {
  fileName: string
  documentType: string
  projectName: string
  text: string
  lineCount: number
  visualSummary?: RequirementVisualSummary
  suggestedRequirementPoints?: string[]
  designDraftCases?: RequirementDesignDraftCase[]
}

export interface RequirementVisualSummary {
  width: number
  height: number
  aspectRatio: number
  viewportHint: 'desktop' | 'mobile' | 'unknown'
  layoutHints: string[]
  styleHints: string[]
  componentHints: string[]
  ocrStatus: 'available' | 'unavailable' | 'empty'
}

export interface RequirementDesignDraftCase {
  title: string
  module: string
  pageName: string
  caseType: string
  steps: string[]
}

export const parseRequirementDocumentWithBackend = async (
  file: File,
  options: RequirementDocumentBackendOptions = {}
): Promise<ParsedRequirementDocument> => {
  const formData = new FormData()
  formData.set('file', file)

  const response = await fetcher(options)(joinBackendPath('/api/requirement-documents/parse', options.baseUrl), {
    method: 'POST',
    body: formData
  })
  if (!response.ok) {
    throw new Error(`Requirement document parse failed: ${response.status}`)
  }
  return await response.json() as ParsedRequirementDocument
}

const fetcher = (options: RequirementDocumentBackendOptions): FetchLike => {
  if (options.fetchImpl) return options.fetchImpl
  if (typeof fetch !== 'undefined') return authedFetch
  throw new Error('Fetch API is not available')
}

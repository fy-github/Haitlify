import type { ParsedRequirementDocument } from './requirementDocumentRepository'
import { joinBackendPath } from './backendBaseUrl'
import { authedFetch } from './http'

type FetchLike = (input: string, init?: RequestInit) => Promise<Response>

export interface FigmaDesignBackendOptions {
  baseUrl?: string
  fetchImpl?: FetchLike
}

export const parseFigmaDesignWithBackend = async (
  url: string,
  options: FigmaDesignBackendOptions = {}
): Promise<ParsedRequirementDocument> => {
  const response = await fetcher(options)(joinBackendPath('/api/requirement-documents/figma/parse', options.baseUrl), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ url })
  })
  if (!response.ok) {
    throw new Error(`Figma design parse failed: ${response.status}`)
  }
  return await response.json() as ParsedRequirementDocument
}

const fetcher = (options: FigmaDesignBackendOptions): FetchLike => {
  if (options.fetchImpl) return options.fetchImpl
  if (typeof fetch !== 'undefined') return authedFetch
  throw new Error('Fetch API is not available')
}

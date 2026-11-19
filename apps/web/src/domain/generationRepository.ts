import { joinBackendPath } from './backendBaseUrl'
import { authedFetch } from './http'

export interface GenerationAdmissionPayload {
  requestId: string
  projectId: number
  requirementRef: string
  requirementText: string
  targetUrl: string
  targetRevision: string
}

export interface GenerationReceipt {
  id: string
  requestId: string
  projectId: number
  state: 'WAITING_WORKER' | string
  createdAt: string
}

export interface GenerationRepositoryOptions {
  baseUrl?: string
  fetchImpl?: typeof authedFetch
}

const fetcher = (options: GenerationRepositoryOptions): typeof authedFetch => {
  if (options.fetchImpl) return options.fetchImpl
  if (typeof fetch !== 'undefined') return authedFetch
  throw new Error('Fetch API is not available')
}

const requestJson = async <T>(options: GenerationRepositoryOptions, path: string, init?: RequestInit): Promise<T> => {
  const response = await fetcher(options)(joinBackendPath(path, options.baseUrl), init)
  if (!response.ok) {
    const payload = await response.json().catch(() => undefined) as { message?: string; error?: string } | undefined
    const suffix = payload?.message || payload?.error ? ` ${payload.message || payload.error}` : ''
    const error = new Error(`generation request failed: ${response.status}${suffix}`)
    ;(error as { status?: number }).status = response.status
    throw error
  }
  return await response.json() as T
}

const validateReceipt = (value: GenerationReceipt): GenerationReceipt => {
  if (!value || !['id', 'requestId', 'state', 'createdAt'].every(key =>
    typeof value[key as keyof GenerationReceipt] === 'string' && String(value[key as keyof GenerationReceipt]).trim())
    || !Number.isSafeInteger(value.projectId) || value.projectId <= 0) throw new Error('受理回执无效')
  return value
}

export const submitGeneration = async (
  options: GenerationRepositoryOptions,
  payload: GenerationAdmissionPayload,
  signal?: AbortSignal
): Promise<GenerationReceipt> => {
  const { requestId, projectId, requirementRef, requirementText, targetUrl, targetRevision } = payload
  const result = validateReceipt(await requestJson<GenerationReceipt>(options, '/api/assistant/generations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ requestId, projectId, requirementRef, requirementText, targetUrl, targetRevision }),
    ...(signal ? { signal } : {})
  }))
  if (result.requestId !== requestId || result.projectId !== projectId) throw new Error('受理回执无效')
  return result
}

export const getGenerationReceipt = async (
  options: GenerationRepositoryOptions,
  id: string,
  signal?: AbortSignal
): Promise<GenerationReceipt> => {
  const result = validateReceipt(await requestJson<GenerationReceipt>(options,
    `/api/assistant/generations/${encodeURIComponent(id)}`, signal ? { signal } : undefined))
  if (result.id !== id) throw new Error('受理回执无效')
  return result
}

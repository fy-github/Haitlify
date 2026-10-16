/**
 * T11：AI 配置仓库层——版本化连接/模型档案注册表与有效配置溯源（/api/ai/configuration）。
 *
 * 只封装增量端点：连接/档案的版本化读写（钉读历史版本）与 effective-options 视图。
 * 敏感字段纪律：请求体只携带 credentialRef（opaque 引用），绝不携带密钥材料。
 */
import { joinBackendPath } from './backendBaseUrl'
import { authedFetch } from './http'
import type { AiPlatformRepositoryOptions } from './aiPlatformRepository'

export type AiConfigurationRepositoryOptions = AiPlatformRepositoryOptions

export interface ConnectionView {
  connectionId: string
  version: number
  baseUrl: string
  credentialRef: string
  protocol: string
  enabled: boolean
  allowedProjectIds: number[]
  createdAt?: string | null
  updatedAt?: string | null
}

export interface RegisterConnectionRequest {
  connectionId: string
  baseUrl: string
  credentialRef: string
  protocol: string
  enabled?: boolean
  allowedProjectIds?: number[]
  expectedVersion?: number
}

export interface ProfileView {
  profileId: string
  version: number
  connectionId: string
  modelId: string
  capabilities: string[]
  parameterPolicyJson: string
  verificationJson: string
  createdAt?: string | null
  updatedAt?: string | null
}

export interface RegisterProfileRequest {
  profileId: string
  connectionId: string
  modelId: string
  capabilities?: string[]
  parameterPolicyJson?: string
  verificationJson?: string
  expectedVersion?: number
}

export interface EffectiveOptionView {
  value: unknown
  source: string
  sourceVersion: string
  present: boolean
}

export interface EffectiveOptionsResponse {
  taskType: string
  profileId?: string | null
  profileVersion?: number | null
  timeout: EffectiveOptionView
  declaredCapabilities: string[]
  verificationJson: string
  taskTypes: string[]
  notes: Record<string, string>
}

const jsonHeaders = (): HeadersInit => ({ 'Content-Type': 'application/json' })

const requestJson = async <T>(
  options: AiConfigurationRepositoryOptions,
  path: string,
  init?: RequestInit
): Promise<T> => {
  const requestInit = options.signal ? { ...init, signal: options.signal } : init
  const response = await fetcher(options)(joinBackendPath(path, options.baseUrl), requestInit)
  if (!response.ok) {
    const payload = await response.json().catch(() => undefined) as { message?: string; error?: string } | undefined
    const suffix = payload?.message || payload?.error ? ` ${payload.message || payload.error}` : ''
    throw new Error(`AI configuration request failed: ${response.status}${suffix}`)
  }
  return await response.json() as T
}

const fetcher = (options: AiConfigurationRepositoryOptions): typeof authedFetch => {
  if (options.fetchImpl) return options.fetchImpl as typeof authedFetch
  if (typeof fetch !== 'undefined') return authedFetch
  throw new Error('Fetch API is not available')
}

const versionQuery = (version?: number): string =>
  version === undefined || version === null ? '' : `?version=${encodeURIComponent(version)}`

export const fetchConnections = (
  options: AiConfigurationRepositoryOptions
): Promise<ConnectionView[]> =>
  requestJson<ConnectionView[]>(options, '/api/ai/configuration/connections')

export const registerConnection = (
  options: AiConfigurationRepositoryOptions,
  body: RegisterConnectionRequest
): Promise<ConnectionView> =>
  requestJson<ConnectionView>(options, '/api/ai/configuration/connections', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(body)
  })

export const fetchConnection = (
  options: AiConfigurationRepositoryOptions,
  connectionId: string,
  version?: number
): Promise<ConnectionView> =>
  requestJson<ConnectionView>(
    options,
    `/api/ai/configuration/connections/${encodeURIComponent(connectionId)}${versionQuery(version)}`
  )

export const fetchProfiles = (
  options: AiConfigurationRepositoryOptions
): Promise<ProfileView[]> =>
  requestJson<ProfileView[]>(options, '/api/ai/configuration/profiles')

export const registerProfile = (
  options: AiConfigurationRepositoryOptions,
  body: RegisterProfileRequest
): Promise<ProfileView> =>
  requestJson<ProfileView>(options, '/api/ai/configuration/profiles', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(body)
  })

export const fetchProfile = (
  options: AiConfigurationRepositoryOptions,
  profileId: string,
  version?: number
): Promise<ProfileView> =>
  requestJson<ProfileView>(
    options,
    `/api/ai/configuration/profiles/${encodeURIComponent(profileId)}${versionQuery(version)}`
  )

export const fetchEffectiveOptions = (
  options: AiConfigurationRepositoryOptions,
  taskType: string,
  profileId?: string
): Promise<EffectiveOptionsResponse> => {
  const profileQuery = profileId ? `&profileId=${encodeURIComponent(profileId)}` : ''
  return requestJson<EffectiveOptionsResponse>(
    options,
    `/api/ai/configuration/effective-options?taskType=${encodeURIComponent(taskType)}${profileQuery}`
  )
}

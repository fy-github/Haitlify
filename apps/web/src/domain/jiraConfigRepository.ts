import { joinBackendPath } from './backendBaseUrl'
import { authedFetch } from './http'

type FetchLike = (input: string, init?: RequestInit) => Promise<Response>

export interface JiraConfigRepositoryOptions {
  baseUrl?: string
  fetchImpl?: FetchLike
}

export interface JiraConfig {
  enabled: boolean
  baseUrl: string
  authMode: string
  username: string
  token: string
  credentialConfigured: boolean
  maskedToken?: string
  projectJql: Record<string, string>
  updatedAt?: string
}

export interface JiraConnectionTestResult {
  success: boolean
  message: string
}

export const defaultJiraConfig = (): JiraConfig => ({
  enabled: false,
  baseUrl: '',
  authMode: 'service-account',
  username: '',
  token: '',
  credentialConfigured: false,
  projectJql: {}
})

interface PlatformJiraConfigResponse {
  enabled: boolean
  baseUrl: string
  authMode: string
  username: string
  credentialConfigured: boolean
  maskedToken?: string | null
  projectJql?: Record<string, string> | null
  updatedAt?: string | null
}

export const loadJiraConfigFromPlatform = async (
  options: JiraConfigRepositoryOptions = {}
): Promise<JiraConfig> =>
  toJiraConfig(await requestJson<PlatformJiraConfigResponse>(options, '/api/jira/config'))

export const saveJiraConfigToPlatform = async (
  config: JiraConfig,
  options: JiraConfigRepositoryOptions = {}
): Promise<JiraConfig> =>
  toJiraConfig(await requestJson<PlatformJiraConfigResponse>(options, '/api/jira/config', {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify(toJiraConfigRequest(config))
  }))

export const testJiraConfigConnection = async (
  config: JiraConfig,
  options: JiraConfigRepositoryOptions = {}
): Promise<JiraConnectionTestResult> =>
  requestJson<JiraConnectionTestResult>(options, '/api/jira/config/test-connection', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({
      baseUrl: config.baseUrl,
      username: config.username,
      token: config.token
    })
  })

const toJiraConfig = (response: PlatformJiraConfigResponse): JiraConfig => ({
  enabled: response.enabled,
  baseUrl: response.baseUrl || '',
  authMode: response.authMode || 'service-account',
  username: response.username || '',
  token: '',
  credentialConfigured: response.credentialConfigured,
  maskedToken: response.maskedToken || undefined,
  projectJql: response.projectJql || {},
  updatedAt: response.updatedAt || undefined
})

const toJiraConfigRequest = (config: JiraConfig) => ({
  enabled: config.enabled,
  baseUrl: config.baseUrl,
  authMode: config.authMode || 'service-account',
  username: config.username,
  token: config.token,
  projectJql: config.projectJql || {}
})

const requestJson = async <T>(
  options: JiraConfigRepositoryOptions,
  path: string,
  init?: RequestInit
): Promise<T> => {
  const response = await fetcher(options)(joinBackendPath(path, options.baseUrl), init)
  if (!response.ok) {
    const payload = await response.json().catch(() => undefined) as { message?: string } | undefined
    const suffix = payload?.message ? ` ${payload.message}` : ''
    throw new Error(`Jira config request failed: ${response.status}${suffix}`)
  }
  return await response.json() as T
}

const fetcher = (options: JiraConfigRepositoryOptions): FetchLike => {
  if (options.fetchImpl) return options.fetchImpl
  if (typeof fetch !== 'undefined') return authedFetch
  throw new Error('Fetch API is not available')
}

const jsonHeaders = (): HeadersInit => ({
  'Content-Type': 'application/json'
})

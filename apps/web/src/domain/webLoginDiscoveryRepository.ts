import { joinBackendPath } from './backendBaseUrl'
import { authedFetch } from './http'

type FetchLike = (input: string, init?: RequestInit) => Promise<Response>

export type WebLoginDiscoveryStatus = 'queued' | 'running' | 'completed' | 'failed'
export type WebLoginDiscoveryCandidateRole = 'username' | 'password' | 'submit'

export interface WebLoginDiscoveryCandidate {
  id: string
  role: WebLoginDiscoveryCandidateRole
  domIndex?: number
  locator: string
  strategy: string
  label: string
  confidence: number
  reason: string
}

export interface WebLoginDiscoveryCandidateGroups {
  username: WebLoginDiscoveryCandidate[]
  password: WebLoginDiscoveryCandidate[]
  submit: WebLoginDiscoveryCandidate[]
}

export interface WebLoginDiscoverySelection {
  usernameCandidateId: string
  passwordCandidateId: string
  submitCandidateId: string
}

export interface WebLoginDiscoverySnapshot {
  discoveryId: string
  status: WebLoginDiscoveryStatus
  createdAt: string
  startedAt?: string
  updatedAt?: string
  endedAt?: string
  request?: {
    url: string
    browser: string
    viewport: string
  }
  pageUrl?: string
  screenshotUrl?: string
  candidates: WebLoginDiscoveryCandidateGroups
  recommended: Partial<Record<WebLoginDiscoveryCandidateRole, WebLoginDiscoveryCandidate>>
  diagnostics: string[]
  confirmed?: WebLoginDiscoverySelection & {
    usernameLocator: string
    passwordLocator: string
    submitLocator: string
    confirmedAt?: string
  }
}

export interface WebLoginDiscoveryRequest {
  url: string
  browser: string
  viewport: string
  headless: boolean
  timeoutMs?: number
  waitUntil?: string
}

export interface WebLoginDiscoveryRepositoryOptions {
  baseUrl?: string
  fetchImpl?: FetchLike
}

export interface WebLoginDiscoveryPollingOptions {
  intervalMs?: number
  signal?: AbortSignal
  onSnapshot?: (snapshot: WebLoginDiscoverySnapshot) => void
}

export class WebLoginDiscoveryRequestError extends Error {
  constructor(readonly status: number, readonly backendMessage = '') {
    super(`Web login discovery request failed: ${status}${backendMessage ? ` ${backendMessage}` : ''}`)
    this.name = 'WebLoginDiscoveryRequestError'
  }

  toDisplayMessage(): string {
    if (this.status === 501) return 'Web runner 未启用，请启动 server/web-runner 后重试'
    if (this.status === 503) return 'Web runner 暂不可用，请检查 Runner 健康状态后重试'
    return this.backendMessage || `登录页识别请求失败（HTTP ${this.status}）`
  }
}

export const createWebLoginDiscovery = (
  request: WebLoginDiscoveryRequest,
  options: WebLoginDiscoveryRepositoryOptions = {}
): Promise<WebLoginDiscoverySnapshot> =>
  requestJson('/api/web-automation/login-discovery', options, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request)
  })

export const getWebLoginDiscovery = (
  discoveryId: string,
  options: WebLoginDiscoveryRepositoryOptions = {}
): Promise<WebLoginDiscoverySnapshot> =>
  requestJson(`/api/web-automation/login-discovery/${encodeURIComponent(discoveryId)}`, options)

export const confirmWebLoginDiscovery = (
  discoveryId: string,
  selection: WebLoginDiscoverySelection,
  options: WebLoginDiscoveryRepositoryOptions = {}
): Promise<WebLoginDiscoverySnapshot> =>
  requestJson(`/api/web-automation/login-discovery/${encodeURIComponent(discoveryId)}/confirm`, options, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(selection)
  })

export const waitForWebLoginDiscovery = async (
  discoveryId: string,
  pollingOptions: WebLoginDiscoveryPollingOptions = {},
  repositoryOptions: WebLoginDiscoveryRepositoryOptions = {}
): Promise<WebLoginDiscoverySnapshot> => {
  const intervalMs = Math.max(50, pollingOptions.intervalMs || 500)
  let snapshot = await getWebLoginDiscovery(discoveryId, repositoryOptions)
  pollingOptions.onSnapshot?.(snapshot)
  while (snapshot.status !== 'completed' && snapshot.status !== 'failed') {
    await abortableDelay(intervalMs, pollingOptions.signal)
    snapshot = await getWebLoginDiscovery(discoveryId, repositoryOptions)
    pollingOptions.onSnapshot?.(snapshot)
  }
  return snapshot
}

const requestJson = async <T>(
  path: string,
  options: WebLoginDiscoveryRepositoryOptions,
  init?: RequestInit
): Promise<T> => {
  const response = await fetcher(options)(joinBackendPath(path, options.baseUrl), init)
  if (!response.ok) {
    const payload = await response.json().catch(() => undefined) as { message?: string } | undefined
    throw new WebLoginDiscoveryRequestError(response.status, payload?.message)
  }
  return await response.json() as T
}

const abortableDelay = (delayMs: number, signal?: AbortSignal): Promise<void> =>
  new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new DOMException('Login discovery polling aborted', 'AbortError'))
      return
    }
    const timer = setTimeout(resolve, delayMs)
    signal?.addEventListener('abort', () => {
      clearTimeout(timer)
      reject(new DOMException('Login discovery polling aborted', 'AbortError'))
    }, { once: true })
  })

const fetcher = (options: WebLoginDiscoveryRepositoryOptions): FetchLike => {
  if (options.fetchImpl) return options.fetchImpl
  if (typeof fetch !== 'undefined') return authedFetch
  throw new Error('Fetch API is not available')
}

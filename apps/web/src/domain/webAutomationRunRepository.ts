import type {
  WebAutomationRunCase,
  WebAutomationRunOptions,
  WebAutomationRunReport,
  WebAutomationRunResult,
  WebAutomationRunStatus,
  WebAutomationSessionView,
  WebAutomationStepEvidence
} from './webAutomation'
import type { WebAutomationAction } from './webAutomationCase'
import { joinBackendPath } from './backendBaseUrl'
import { authedFetch } from './http'

type FetchLike = (input: string, init?: RequestInit) => Promise<Response>

export type WebAutomationRunLifecycle =
  | 'queued'
  | 'running'
  | 'pause_requested'
  | 'paused'
  | 'stop_requested'
  | 'stopped'
  | 'completed'
  | 'failed'

export interface WebAutomationRunProgress {
  totalCases: number
  completedCases: number
  totalSteps: number
  completedSteps: number
}

export interface WebAutomationRunControls {
  canPause: boolean
  canResume: boolean
  canStop: boolean
}

export interface WebAutomationLiveStepLog {
  stepId: string
  phase?: 'setup' | 'test' | 'teardown'
  name: string
  action: WebAutomationAction
  target: string
  status: 'passed' | 'failed'
  durationMs: number
  startedAt?: string
  endedAt?: string
  pageUrl?: string
  errorMessage?: string
  errorStack?: string
}


export interface WebAutomationRunSnapshot {
  runId: string
  status: WebAutomationRunLifecycle
  mode: WebAutomationRunOptions['mode']
  selectedBrowser: string
  createdAt: string
  startedAt?: string
  updatedAt: string
  endedAt?: string
  progress: WebAutomationRunProgress
  currentCase?: { id: string; name: string }
  currentStep?: {
    id: string
    name: string
    action: WebAutomationAction
    phase: 'setup' | 'test' | 'teardown'
  }
  currentPageUrl?: string
  latestScreenshotUrl?: string
  latestScreenshotAt?: string
  sessionId?: string
  controls: WebAutomationRunControls
  summary: {
    total: number
    passed: number
    failed: number
    error: number
  }
  stepLogs: WebAutomationLiveStepLog[]
  results: WebAutomationRunResult[]
  diagnostics: string[]
}

export interface WebAutomationBrowserCapability {
  id: string
  label: string
  available: boolean
  message?: string
  recommendation: string
}

export interface WebAutomationCapabilities {
  status: 'UP' | 'DEGRADED'
  checkedAt: string
  browsers: WebAutomationBrowserCapability[]
}

export interface WebAutomationRunRepositoryOptions {
  baseUrl?: string
  fetchImpl?: FetchLike
}

export interface WebAutomationRunPollingOptions {
  intervalMs?: number
  signal?: AbortSignal
  onSnapshot?: (snapshot: WebAutomationRunSnapshot) => void
}

export class WebAutomationRunRequestError extends Error {
  constructor(
    readonly status: number,
    backendMessage = '',
    recommendations: string[] = []
  ) {
    const safeBackendMessage = sanitizeBackendText(backendMessage)
    const safeRecommendations = recommendations.map((item) => sanitizeBackendText(item))
    super(buildRequestErrorMessage(status, safeBackendMessage))
    this.name = 'WebAutomationRunRequestError'
    this.backendMessage = safeBackendMessage
    this.recommendations = safeRecommendations
  }

  readonly backendMessage: string
  readonly recommendations: string[]

  toFallbackMessage(): string {
    if (this.status === 501) {
      return 'Web runner 未启用：请启动 server/web-runner，并设置 APP_WEB_AUTOMATION_RUNNER_ENABLED=true 后重启后端。'
    }
    if (this.status === 503) {
      const timeoutHint = this.backendMessage.includes('timed out')
        ? '；Runner 命令请求超时，请检查 Runner 负载和健康状态'
        : ''
      return `Web runner 暂不可用：请确认 server/web-runner 已启动，并执行 curl http://127.0.0.1:9323/health 检查健康状态${timeoutHint}。`
    }
    const recommendationText = this.recommendations.length ? `；建议：${this.recommendations.join('；')}` : ''
    return `后端 Web runner 不可用：${this.backendMessage || `HTTP ${this.status}`}${recommendationText}`
  }
}

export const createWebAutomationRun = async (
  cases: WebAutomationRunCase[],
  options: WebAutomationRunOptions,
  repositoryOptions: WebAutomationRunRepositoryOptions = {}
): Promise<WebAutomationRunSnapshot> => {
  const browserOverride = options.browserOverride && options.browserOverride !== 'follow-case'
    ? options.browserOverride
    : undefined
  return requestJson<WebAutomationRunSnapshot>(
    '/api/web-automation/runs',
    repositoryOptions,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        mode: options.mode,
        loopCount: options.loopCount,
        ...(browserOverride ? { browserOverride } : {}),
        ...(options.projectId ? { projectId: options.projectId } : {}),
        runtime: options.runtime,
        cases
      })
    }
  )
}

export const getWebAutomationRun = (
  runId: string,
  repositoryOptions: WebAutomationRunRepositoryOptions = {}
): Promise<WebAutomationRunSnapshot> =>
  requestJson(`/api/web-automation/runs/${encodeURIComponent(runId)}`, repositoryOptions)

export const pauseWebAutomationRun = (
  runId: string,
  repositoryOptions: WebAutomationRunRepositoryOptions = {}
): Promise<WebAutomationRunSnapshot> => controlRun(runId, 'pause', repositoryOptions)

export const resumeWebAutomationRun = (
  runId: string,
  repositoryOptions: WebAutomationRunRepositoryOptions = {}
): Promise<WebAutomationRunSnapshot> => controlRun(runId, 'resume', repositoryOptions)

export const stopWebAutomationRun = (
  runId: string,
  repositoryOptions: WebAutomationRunRepositoryOptions = {}
): Promise<WebAutomationRunSnapshot> => controlRun(runId, 'stop', repositoryOptions)

/** 阶段 7 增量 3：runner 会话状态面板（经平台代理 /api/web-automation/sessions）。 */
export const listWebAutomationSessions = async (
  repositoryOptions: WebAutomationRunRepositoryOptions & { signal?: AbortSignal } = {}
): Promise<WebAutomationSessionView[]> => {
  const response = await requestJson<{ sessions?: WebAutomationSessionView[] }>(
    '/api/web-automation/sessions',
    repositoryOptions,
    { signal: repositoryOptions.signal }
  )
  return response?.sessions || []
}

export const getWebAutomationCapabilities = (
  repositoryOptions: WebAutomationRunRepositoryOptions = {}
): Promise<WebAutomationCapabilities> =>
  requestJson('/api/web-automation/capabilities', repositoryOptions)

export const waitForWebAutomationRun = async (
  runId: string,
  pollingOptions: WebAutomationRunPollingOptions = {},
  repositoryOptions: WebAutomationRunRepositoryOptions = {}
): Promise<WebAutomationRunSnapshot> => {
  const intervalMs = Math.max(50, pollingOptions.intervalMs || 500)
  let snapshot = await getWebAutomationRun(runId, repositoryOptions)
  pollingOptions.onSnapshot?.(snapshot)
  while (!isTerminalWebAutomationRun(snapshot.status)) {
    await abortableDelay(intervalMs, pollingOptions.signal)
    snapshot = await getWebAutomationRun(runId, repositoryOptions)
    pollingOptions.onSnapshot?.(snapshot)
  }
  return snapshot
}

export const runWebAutomationCasesViaBackend = async (
  cases: WebAutomationRunCase[],
  options: WebAutomationRunOptions,
  repositoryOptions: WebAutomationRunRepositoryOptions = {}
): Promise<WebAutomationRunReport> => {
  const created = await createWebAutomationRun(cases, options, repositoryOptions)
  const snapshot = isTerminalWebAutomationRun(created.status)
    ? created
    : await waitForWebAutomationRun(created.runId, {}, repositoryOptions)
  return snapshotToReport(snapshot)
}

export const isTerminalWebAutomationRun = (status: WebAutomationRunLifecycle): boolean =>
  status === 'completed' || status === 'stopped' || status === 'failed'

export const snapshotToReport = (snapshot: WebAutomationRunSnapshot): WebAutomationRunReport => ({
  mode: snapshot.mode,
  startedAt: snapshot.startedAt || snapshot.createdAt,
  endedAt: snapshot.endedAt || snapshot.updatedAt || new Date().toISOString(),
  summary: snapshot.summary,
  results: snapshot.results.map((result) => ({
    ...result,
    status: normalizeResultStatus(result.status)
  }))
})

const normalizeResultStatus = (status: WebAutomationRunStatus): WebAutomationRunStatus =>
  status === 'passed' || status === 'failed' ? status : 'error'

const controlRun = (
  runId: string,
  action: 'pause' | 'resume' | 'stop',
  repositoryOptions: WebAutomationRunRepositoryOptions
): Promise<WebAutomationRunSnapshot> =>
  requestJson(
    `/api/web-automation/runs/${encodeURIComponent(runId)}/${action}`,
    repositoryOptions,
    { method: 'POST' }
  )

const requestJson = async <T>(
  path: string,
  options: WebAutomationRunRepositoryOptions,
  init?: RequestInit
): Promise<T> => {
  const response = await fetcher(options)(joinBackendPath(path, options.baseUrl), init)
  if (!response.ok) {
    const payload = await response.json().catch(() => undefined) as
      | { message?: string; recommendations?: string[] }
      | undefined
    throw new WebAutomationRunRequestError(response.status, payload?.message, payload?.recommendations || [])
  }
  return await response.json() as T
}

const abortableDelay = (delayMs: number, signal?: AbortSignal): Promise<void> =>
  new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new DOMException('Web run polling aborted', 'AbortError'))
      return
    }
    const timer = setTimeout(resolve, delayMs)
    signal?.addEventListener('abort', () => {
      clearTimeout(timer)
      reject(new DOMException('Web run polling aborted', 'AbortError'))
    }, { once: true })
  })

const fetcher = (options: WebAutomationRunRepositoryOptions): FetchLike => {
  if (options.fetchImpl) return options.fetchImpl
  if (typeof fetch !== 'undefined') return authedFetch
  throw new Error('Fetch API is not available')
}

const sanitizeBackendText = (value: string): string => String(value || '')
  .replace(/[\u0000-\u001f\u007f]/g, '')
  .replace(/https?:\/\/[^\s?#]+[^\s]*/gi, (url) => url.split(/[?#]/, 1)[0])
  .replace(/["']?\b(authorization|cookie|password|passwd|token|apikey|secretkey|secret|credential)["']?\s*[:=]\s*(?:"[^"]*"|'[^']*'|\{[^}]*\}|\[[^\]]*\]|Bearer\s+[^\s,;]+(?:\s+[^\s,;]+)?|[^\s,;]+)/gi, '$1=[redacted]')
  .replace(/["']?\b(locator|selector)["']?\s*[:=]\s*(?:"[^"]*"|'[^']*'|\{[^}]*\}|\[[^\]]*\]|[^\s,;]+)/gi, '$1=[redacted]')

const buildRequestErrorMessage = (status: number, backendMessage: string): string =>
  `Web automation run request failed: ${status}${backendMessage ? ` ${backendMessage}` : ''}`

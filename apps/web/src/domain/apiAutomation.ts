import type { GeneratedInterfaceCase } from './caseGenerator'
import {
  buildCurlCommand,
  buildUrl,
  parseRequestHeadersText,
  resolveVariableText,
  resolveVariables,
  type ExecutableRequest,
  type ExecutionEnvironment
} from './apiExecution'

export interface ApiAutomationCaseDraft {
  id: string
  sourceCaseId: string
  project: string
  module: string
  name: string
  method: string
  path: string
  headers: Record<string, string>
  pathParams: Record<string, string>
  queryParams: Record<string, string>
  body?: unknown
  expectedResponseBody?: unknown
  expectedStatus: number
  assertions: string[]
  runOrder: number
  exportTarget: 'apifox' | 'design-draft'
}

export interface ApifoxAutomationDraftPackage {
  kind: 'ai-test-platform.apifox-automation-draft'
  version: 1
  exportedAt: string
  target: 'apifox'
  environment: {
    baseUrl: string
    extraHeaders?: Record<string, string>
  }
  cases: ApifoxAutomationDraftCase[]
}

export interface ApifoxAutomationDraftCase {
  id: string
  sourceCaseId: string
  project: string
  module: string
  name: string
  request: {
    method: string
    path: string
    headers: Record<string, string>
    pathParams: Record<string, string>
    queryParams: Record<string, string>
    body?: unknown
  }
  expectedStatus: number
  expectedResponseBody?: unknown
  assertions: string[]
}

export interface ExportApifoxAutomationDraftOptions {
  exportedAt?: string
  environment?: ExecutionEnvironment
}

export type AutomationRunMode = 'sequential' | 'concurrent' | 'module'
export type AutomationRunStatus = 'passed' | 'failed' | 'error'

export interface AutomationRunOptions {
  mode: AutomationRunMode
  concurrencyLimit?: number
  responseBodyMatchThreshold?: number
  loopCount?: number
}

export interface AutomationRunProgress {
  completed: number
  total: number
  runningCaseId?: string
}

export interface AutomationRunRuntime {
  onProgress?: (progress: AutomationRunProgress) => void
  /** Frontend-only lifecycle: the actual loop-expanded case, before runOne starts. */
  onCaseStart?: (testCase: ApiAutomationCaseDraft) => void
  /** Frontend-only lifecycle: the actual runOne result, immediately after it settles. */
  onResult?: (result: AutomationRunResult) => void
}

export interface AutomationResponse {
  status: number
  body?: unknown
}

export interface AutomationRunResult {
  caseId: string
  sourceCaseId: string
  name: string
  module: string
  /** Web 结果专用：页面名称与起始地址，API 结果不设置。 */
  pageName?: string
  startUrl?: string
  /** Web 结果专用：原始步骤定义（用于转缺陷时保留 steps/evidence）。 */
  steps?: import('./webAutomationCase').WebAutomationStep[]
  method: string
  path: string
  status: AutomationRunStatus
  request: ExecutableRequest
  expectedStatus: number
  expectedResponseBody?: unknown
  actualStatus?: number
  durationMs: number
  failedStepId?: string
  pageHtmlSnippet?: string
  screenshotPath?: string
  errorMessage?: string
  consoleErrors?: string[]
  pageErrors?: string[]
  failedRequests?: string[]
  stepEvidence?: import('./webAutomation').WebAutomationStepEvidence[]
  responseBody?: unknown
  actualResponseBody?: unknown
  stepLogs?: Array<{
    stepId: string
    phase?: 'setup' | 'test' | 'teardown'
    name: string
    action: string
    target: string
    status: 'passed' | 'failed'
    durationMs: number
    startedAt?: string
    endedAt?: string
    pageUrl?: string
    errorMessage?: string
    errorStack?: string
  }>
  responseBodyMatch?: ResponseBodyMatchResult
}

export interface ResponseBodyMatchResult {
  matched: number
  expected: number
  ratio: number
  missingPaths: string[]
}

export interface AutomationRunSummary {
  total: number
  passed: number
  failed: number
  error: number
}

export interface AutomationRunReport {
  mode: AutomationRunMode
  startedAt: string
  endedAt: string
  summary: AutomationRunSummary
  results: AutomationRunResult[]
}

export type AutomationRequestExecutor = (
  request: ExecutableRequest,
  automationCase: ApiAutomationCaseDraft
) => Promise<AutomationResponse>

export const generateAutomationCasesFromInterfaceCases = (
  cases: GeneratedInterfaceCase[]
): ApiAutomationCaseDraft[] =>
  cases.map((testCase) => ({
    id: `AUTO-${testCase.row.caseId}`,
    sourceCaseId: testCase.row.caseId,
    project: testCase.row.project,
    module: testCase.row.module,
    name: testCase.row.title,
    method: testCase.metadata.method,
    path: testCase.metadata.path,
    headers: testCase.metadata.headers,
    pathParams: testCase.metadata.pathParams,
    queryParams: testCase.metadata.queryParams,
    body: testCase.metadata.body,
    expectedResponseBody: testCase.metadata.expectedResponseBody,
    expectedStatus: testCase.metadata.expectedStatus,
    assertions: testCase.metadata.assertions,
    runOrder: testCase.metadata.runOrder,
    exportTarget: 'apifox'
  }))

export const buildAutomationExecutableRequest = (
  automationCase: ApiAutomationCaseDraft,
  environment: ExecutionEnvironment
): ExecutableRequest => {
  const method = automationCase.method.toUpperCase()
  const variables = environment.variables || {}
  const headers = cleanHeaders({
    ...resolveVariables(automationCase.headers, variables),
    ...environment.extraHeaders,
    ...parseRequestHeadersText(environment.requestHeadersText || '')
  })

  if (environment.authToken) {
    headers.Authorization = `Bearer ${environment.authToken}`
  }

  return {
    method,
    url: buildUrl(
      environment.baseUrl,
      resolveVariableText(automationCase.path, variables),
      resolveVariables(automationCase.pathParams, variables),
      resolveVariables(automationCase.queryParams, variables)
    ),
    headers,
    body: methodAllowsBody(method) ? resolveVariables(automationCase.body, variables) : undefined,
    expectedStatus: automationCase.expectedStatus,
    assertions: automationCase.assertions
  }
}

export const buildAutomationCurlCommand = (
  automationCase: ApiAutomationCaseDraft,
  environment: ExecutionEnvironment
): string => buildCurlCommand(buildAutomationExecutableRequest(automationCase, environment))

export const exportApifoxAutomationDraft = (
  cases: ApiAutomationCaseDraft[],
  options: ExportApifoxAutomationDraftOptions = {}
): ApifoxAutomationDraftPackage => ({
  kind: 'ai-test-platform.apifox-automation-draft',
  version: 1,
  exportedAt: options.exportedAt || new Date().toISOString(),
  target: 'apifox',
  environment: {
    baseUrl: options.environment?.baseUrl || '',
    ...(options.environment?.extraHeaders ? { extraHeaders: options.environment.extraHeaders } : {})
  },
  cases: cases.map((automationCase) => ({
    id: automationCase.id,
    sourceCaseId: automationCase.sourceCaseId,
    project: automationCase.project,
    module: automationCase.module,
    name: automationCase.name,
    request: {
      method: automationCase.method,
      path: automationCase.path,
      headers: automationCase.headers,
      pathParams: automationCase.pathParams,
      queryParams: automationCase.queryParams,
      body: automationCase.body
    },
    expectedStatus: automationCase.expectedStatus,
    expectedResponseBody: automationCase.expectedResponseBody,
    assertions: automationCase.assertions
  }))
})

export const runAutomationCases = async (
  cases: ApiAutomationCaseDraft[],
  environment: ExecutionEnvironment,
  options: AutomationRunOptions,
  executor: AutomationRequestExecutor,
  runtime: AutomationRunRuntime = {}
): Promise<AutomationRunReport> => {
  const startedAt = new Date().toISOString()
  const concurrencyLimit = normalizeConcurrencyLimit(options.concurrencyLimit)
  const loopCount = normalizeLoopCount(options.loopCount)
  const runnableCases = expandAutomationRunCases(cases, loopCount)
  const total = runnableCases.length
  let completed = 0
  const notifyProgress = (runningCaseId?: string) => runtime.onProgress?.({ completed, total, ...(runningCaseId ? { runningCaseId } : {}) })
  const runAndTrack = async (testCase: ApiAutomationCaseDraft) => {
    notifyProgress(testCase.id)
    runtime.onCaseStart?.(testCase)
    const result = await runOneAutomationCase(testCase, environment, executor, options)
    completed += 1
    runtime.onResult?.(result)
    return result
  }
  const results =
    options.mode === 'sequential'
      ? await runSequentially(runnableCases, runAndTrack)
      : options.mode === 'module'
        ? await runByModule(runnableCases, concurrencyLimit, runAndTrack)
        : await runWithConcurrency(runnableCases, concurrencyLimit, runAndTrack)

  runtime.onProgress?.({ completed, total })

  return {
    mode: options.mode,
    startedAt,
    endedAt: new Date().toISOString(),
    summary: summarizeResults(results),
    results
  }
}

const runSequentially = async (
  cases: ApiAutomationCaseDraft[],
  runner: (testCase: ApiAutomationCaseDraft) => Promise<AutomationRunResult>
): Promise<AutomationRunResult[]> => {
  const results: AutomationRunResult[] = []
  for (const testCase of cases) {
    results.push(await runner(testCase))
  }
  return results
}

const runByModule = async (
  cases: ApiAutomationCaseDraft[],
  concurrencyLimit: number,
  runner: (testCase: ApiAutomationCaseDraft) => Promise<AutomationRunResult>
): Promise<AutomationRunResult[]> => {
  const results: AutomationRunResult[] = []
  const byModule = groupByModule(cases)

  for (const group of byModule) {
    results.push(...await runWithConcurrency(group, concurrencyLimit, runner))
  }

  return results
}

const runWithConcurrency = async (
  cases: ApiAutomationCaseDraft[],
  concurrencyLimit: number,
  runner: (testCase: ApiAutomationCaseDraft) => Promise<AutomationRunResult>
): Promise<AutomationRunResult[]> => {
  const results: AutomationRunResult[] = []

  for (let index = 0; index < cases.length; index += concurrencyLimit) {
    const batch = cases.slice(index, index + concurrencyLimit)
    results.push(...await Promise.all(batch.map((testCase) => runner(testCase))))
  }

  return results
}

const runOneAutomationCase = async (
  automationCase: ApiAutomationCaseDraft,
  environment: ExecutionEnvironment,
  executor: AutomationRequestExecutor,
  options: AutomationRunOptions
): Promise<AutomationRunResult> => {
  const started = performance.now()
  const request = buildAutomationExecutableRequest(automationCase, environment)

  try {
    const response = await executor(request, automationCase)
    const durationMs = Math.round(performance.now() - started)
    const statusMismatch = response.status !== automationCase.expectedStatus
    const responseBodyMatch = statusMismatch
      ? undefined
      : evaluateResponseBodyMatch(automationCase.expectedResponseBody, response.body)
    const responseBodyFailure = responseBodyMatch && responseBodyMatch.ratio < normalizeResponseBodyThreshold(options.responseBodyMatchThreshold)
    const assertionFailure = statusMismatch
      ? undefined
      : evaluateAssertionRules(automationCase.assertions, response.body)
    const passed = !statusMismatch && !assertionFailure && !responseBodyFailure
    const errorMessage = statusMismatch
      ? `状态码不匹配：期望 ${automationCase.expectedStatus}，实际 ${response.status}`
      : assertionFailure || formatResponseBodyMatchFailure(responseBodyMatch, normalizeResponseBodyThreshold(options.responseBodyMatchThreshold))

    return {
      caseId: automationCase.id,
      sourceCaseId: automationCase.sourceCaseId,
      name: automationCase.name,
      module: automationCase.module,
      method: automationCase.method,
      path: automationCase.path,
      status: passed ? 'passed' : 'failed',
      request,
      expectedStatus: automationCase.expectedStatus,
      expectedResponseBody: automationCase.expectedResponseBody,
      actualStatus: response.status,
      durationMs,
      responseBody: response.body,
      actualResponseBody: response.body,
      ...(responseBodyMatch ? { responseBodyMatch } : {}),
      ...(errorMessage ? { errorMessage } : {})
    }
  } catch (error) {
    return {
      caseId: automationCase.id,
      sourceCaseId: automationCase.sourceCaseId,
      name: automationCase.name,
      module: automationCase.module,
      method: automationCase.method,
      path: automationCase.path,
      status: 'error',
      request,
      expectedStatus: automationCase.expectedStatus,
      expectedResponseBody: automationCase.expectedResponseBody,
      durationMs: Math.round(performance.now() - started),
      errorMessage: error instanceof Error ? error.message : '执行失败'
    }
  }
}

export const removeResultsFromAutomationRunReport = (
  report: AutomationRunReport | undefined,
  caseIds: string[]
): AutomationRunReport | undefined => {
  if (!report || caseIds.length === 0) return report
  const deleting = new Set(caseIds)
  const results = report.results.filter((result) => !deleting.has(result.caseId))
  if (results.length === 0) return undefined

  return {
    ...report,
    results,
    summary: summarizeResults(results)
  }
}

const groupByModule = (cases: ApiAutomationCaseDraft[]): ApiAutomationCaseDraft[][] => {
  const groups = new Map<string, ApiAutomationCaseDraft[]>()
  for (const testCase of cases) {
    groups.set(testCase.module, [...(groups.get(testCase.module) || []), testCase])
  }
  return Array.from(groups.values())
}

const summarizeResults = (results: AutomationRunResult[]): AutomationRunSummary => ({
  total: results.length,
  passed: results.filter((result) => result.status === 'passed').length,
  failed: results.filter((result) => result.status === 'failed').length,
  error: results.filter((result) => result.status === 'error').length
})

const normalizeConcurrencyLimit = (limit?: number): number =>
  Math.max(1, Math.min(50, Math.floor(limit || 8)))

const normalizeLoopCount = (loopCount?: number): number =>
  Math.max(1, Math.min(100, Math.floor(loopCount || 1)))

const expandAutomationRunCases = (
  cases: ApiAutomationCaseDraft[],
  loopCount: number
): ApiAutomationCaseDraft[] => {
  if (loopCount <= 1) return cases

  return Array.from({ length: loopCount }).flatMap((_, loopIndex) =>
    cases.map((testCase) => ({
      ...testCase,
      id: `${testCase.id}#${loopIndex + 1}`
    }))
  )
}

const normalizeResponseBodyThreshold = (threshold?: number): number =>
  Math.max(0, Math.min(100, Math.floor(threshold ?? 100)))

const methodAllowsBody = (method: string): boolean => !['GET', 'HEAD'].includes(method)

const cleanHeaders = (headers: Record<string, string>): Record<string, string> =>
  Object.fromEntries(Object.entries(headers).filter(([, value]) => value !== ''))

const evaluateAssertionRules = (assertions: string[], responseBody: unknown): string | undefined => {
  for (const line of assertions.map((item) => item.trim()).filter(Boolean)) {
    if (!line.startsWith('body.')) continue

    const includesIndex = line.indexOf(' includes ')
    if (includesIndex > -1) {
      const path = line.slice(0, includesIndex).trim()
      const expected = line.slice(includesIndex + ' includes '.length).trim()
      const actual = readAssertionValue(responseBody, path.slice(5))
      if (!String(actual ?? '').includes(expected)) {
        return `断言失败: ${path} 期望包含 ${expected}，实际 ${formatAssertionValue(actual)}`
      }
      continue
    }

    const equalsIndex = line.indexOf('=')
    if (equalsIndex > -1) {
      const path = line.slice(0, equalsIndex).trim()
      const expected = line.slice(equalsIndex + 1).trim()
      const actual = readAssertionValue(responseBody, path.slice(5))
      if (String(actual ?? '') !== expected) {
        return `断言失败: ${path} 期望 ${expected}，实际 ${formatAssertionValue(actual)}`
      }
    }
  }

  return undefined
}

const readAssertionValue = (value: unknown, path: string): unknown =>
  path.split('.').filter(Boolean).reduce<unknown>((current, segment) => {
    if (!current || typeof current !== 'object') return undefined
    return (current as Record<string, unknown>)[segment]
  }, value)

const formatAssertionValue = (value: unknown): string => {
  if (value === undefined) return 'undefined'
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}

const evaluateResponseBodyMatch = (expected: unknown, actual: unknown): ResponseBodyMatchResult | undefined => {
  if (expected === undefined || expected === null || expected === '') return undefined

  const expectedPaths = collectBodyPaths(expected)
  if (expectedPaths.length === 0) return undefined

  const actualPaths = new Set(collectBodyPaths(actual))
  const missingPaths = expectedPaths.filter((path) => !actualPaths.has(path))
  const matched = expectedPaths.length - missingPaths.length
  return {
    matched,
    expected: expectedPaths.length,
    ratio: Math.round((matched / expectedPaths.length) * 100),
    missingPaths
  }
}

const collectBodyPaths = (value: unknown, prefix = 'body'): string[] => {
  if (value === undefined || value === null) return []
  if (typeof value !== 'object' || Array.isArray(value)) return [prefix]

  const entries = Object.entries(value as Record<string, unknown>)
  if (entries.length === 0) return [prefix]

  return [
    ...(prefix === 'body' ? [] : [prefix]),
    ...entries.flatMap(([key, child]) => collectBodyPaths(child, `${prefix}.${key}`))
  ]
}

const formatResponseBodyMatchFailure = (
  result: ResponseBodyMatchResult | undefined,
  threshold: number
): string | undefined => {
  if (!result || result.ratio >= threshold) return undefined
  const missing = result.missingPaths.slice(0, 5).join(', ')
  return `返回体参数一致度 ${result.ratio}%，低于阈值 ${threshold}%。缺少参数: ${missing}`
}

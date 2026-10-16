import type {
  BuiltWebExecutionRuntime
} from './executionEnvironmentRepository'
import type {
  GeneratedWebAutomationCase,
  WebAutomationAction,
  SavedWebAutomationCase,
  WebAutomationBrowser,
  WebAutomationStep
} from './webAutomationCase'

export type WebAutomationRunMode = 'sequential' | 'concurrent' | 'module'
export type WebAutomationRunStatus = 'passed' | 'failed' | 'error'
export type WebAutomationMockOutcome = WebAutomationRunStatus
export type WebAutomationAuthenticationMode = 'environment' | 'skip'

export interface WebAutomationStepEvidence {
  actionId: string
  actionType: string
  before: { pageUrl: string; pageKey: string; pageFingerprint: string }
  resolvedTarget?: Record<string, unknown> | string
  evidenceId: string
  locator?: Record<string, unknown> | string
  visible?: boolean
  enabled?: boolean
  receivesEvents?: boolean
  unique?: boolean
  execution: { status: 'passed' | 'failed'; durationMs: number }
  after: { pageUrl: string; pageKey: string; pageFingerprint: string }
  observationStatus?: 'passed' | 'failed'
  failureType?: string
}

export interface WebAutomationRunCase {
  id: string
  sourceCaseId: string
  project: string
  module: string
  pageName: string
  name: string
  startUrl: string
  environmentId?: string
  browser: WebAutomationBrowser
  viewport: string
  authenticationMode: WebAutomationAuthenticationMode
  steps: WebAutomationStep[]
  actions?: import('./webAutomationCase').WebAutomationActionIR[]
  runOrder: number
  mockOutcome: WebAutomationMockOutcome
  agentRunId?: string
  agentSourcePointId?: string
  agentEnvironmentId?: string
  agentBaseUrl?: string
  agentAuthenticationMode?: string
  agentEvidenceIds?: string[]
  agentPageKey?: string
  agentPageFingerprint?: string
  agentStepTraces?: import('./webAutomationCase').WebCaseStepTrace[]
  agentExpectedResultEvidenceIds?: string[]
  artifactId?: string
  artifactRevision?: number
  artifactResultOrigin?: string
  artifactAcceptanceState?: string
  artifactModelProfileVersion?: string
  artifactFieldOrigins?: string[]
  artifactEvidenceIds?: string[]
  artifactParentArtifactId?: string
  artifactIdempotencyKey?: string
}

export interface WebAutomationRunOptions {
  mode: WebAutomationRunMode
  loopCount?: number
  browserOverride?: 'follow-case' | WebAutomationBrowser
  runtime?: BuiltWebExecutionRuntime
  /** 平台项目 id（阶段 7 增量 2）：可选，供执行结果持久化做项目级指标；缺省时运行照常执行、只是不计入项目指标。 */
  projectId?: number
}

/** 阶段 7 增量 3：runner 会话状态面板条目（只读元数据，来自 GET /api/web-automation/sessions）。 */
export interface WebAutomationSessionView {
  sessionId: string
  projectId?: number
  environmentId?: string
  browser: string
  state: string
  leased: boolean
  pageKey?: string
  createdAt?: number
  expiresAt?: number
  lastUsedAt?: number
}

export interface WebAutomationRunResult {
  caseId: string
  sourceCaseId: string
  name: string
  module: string
  pageName: string
  startUrl: string
  status: WebAutomationRunStatus
  durationMs: number
  stepLogs?: Array<{
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
  }>
  failedStepId?: string
  pageHtmlSnippet?: string
  screenshotPath?: string
  errorMessage?: string
  consoleErrors?: string[]
  pageErrors?: string[]
  failedRequests?: string[]
  stepEvidence?: WebAutomationStepEvidence[]
}

export interface WebAutomationRunReport {
  mode: WebAutomationRunMode
  startedAt: string
  endedAt: string
  summary: {
    total: number
    passed: number
    failed: number
    error: number
  }
  results: WebAutomationRunResult[]
}

export interface WebAutomationRunCaseExportPackage {
  kind: 'ai-test-platform.web-automation-cases'
  version: 1
  exportedAt: string
  cases: WebAutomationRunCase[]
}

export interface WebAutomationConversionValidationError {
  caseId: string
  message: string
}

export const exportWebAutomationRunCases = (
  cases: WebAutomationRunCase[],
  exportedAt = new Date().toISOString()
): WebAutomationRunCaseExportPackage => ({
  kind: 'ai-test-platform.web-automation-cases',
  version: 1,
  exportedAt,
  cases: cases.map((testCase) => ({
    id: testCase.id,
    sourceCaseId: testCase.sourceCaseId,
    project: testCase.project,
    module: testCase.module,
    pageName: testCase.pageName,
    name: testCase.name,
    startUrl: testCase.startUrl,
    ...(testCase.environmentId ? { environmentId: testCase.environmentId } : {}),
    browser: testCase.browser,
    viewport: testCase.viewport,
    authenticationMode: testCase.authenticationMode,
    steps: testCase.steps.map((step) => ({ ...step })),
    ...(testCase.actions?.length ? { actions: testCase.actions.map((action) => ({ ...action })) } : {}),
    runOrder: testCase.runOrder,
    mockOutcome: testCase.mockOutcome,
    agentRunId: testCase.agentRunId,
    agentSourcePointId: testCase.agentSourcePointId,
    agentEvidenceIds: testCase.agentEvidenceIds,
    artifactId: testCase.artifactId,
    artifactRevision: testCase.artifactRevision,
    artifactResultOrigin: testCase.artifactResultOrigin,
    artifactAcceptanceState: testCase.artifactAcceptanceState,
    artifactModelProfileVersion: testCase.artifactModelProfileVersion,
    artifactFieldOrigins: testCase.artifactFieldOrigins,
    artifactEvidenceIds: testCase.artifactEvidenceIds,
    artifactParentArtifactId: testCase.artifactParentArtifactId,
    artifactIdempotencyKey: testCase.artifactIdempotencyKey
  }))
})

export const validateWebAutomationConversionCases = (
  cases: WebAutomationRunCase[]
): WebAutomationConversionValidationError[] => {
  const errors: WebAutomationConversionValidationError[] = []

  cases.forEach((testCase) => {
    testCase.steps.forEach((step, index) => {
      if (step.action === 'manual') {
        errors.push({
          caseId: testCase.sourceCaseId,
          message: `步骤 ${index + 1} 使用 manual，不能自动执行`
        })
      }
    })
  })

  return errors
}

export const generateWebAutomationCasesFromSavedCases = (
  cases: SavedWebAutomationCase[]
): WebAutomationRunCase[] =>
  cases.map((testCase) => ({
    id: `WEB-AUTO-${testCase.row.caseId}`,
    sourceCaseId: testCase.row.caseId,
    project: testCase.row.project,
    module: testCase.row.module,
    pageName: testCase.row.pageName,
    name: testCase.row.title,
    startUrl: testCase.row.startUrl,
    environmentId: testCase.row.environmentId,
    browser: normalizeBrowser(testCase.row.browser),
    viewport: testCase.row.viewport,
    authenticationMode: 'environment',
    steps: testCase.metadata.steps,
    ...(testCase.metadata.agentActions?.length ? { actions: testCase.metadata.agentActions } : {}),
    runOrder: testCase.metadata.runOrder,
    mockOutcome: inferMockOutcome(testCase)
  }))

export const generateWebAutomationCasesFromGeneratedCases = (
  cases: GeneratedWebAutomationCase[]
): WebAutomationRunCase[] =>
  cases.map((testCase) => ({
    id: `WEB-AUTO-${testCase.row.caseId}`,
    sourceCaseId: testCase.row.caseId,
    project: testCase.row.project,
    module: testCase.row.module,
    pageName: testCase.row.pageName,
    name: testCase.row.title,
    startUrl: testCase.row.startUrl,
    environmentId: testCase.row.environmentId,
    browser: normalizeBrowser(testCase.row.browser),
    viewport: testCase.row.viewport,
    authenticationMode: 'environment',
    steps: testCase.metadata.steps,
    ...(testCase.metadata.agentActions?.length ? { actions: testCase.metadata.agentActions } : {}),
    runOrder: testCase.metadata.runOrder,
    mockOutcome: inferWebAutomationMockOutcome(testCase.row.startUrl),
    agentRunId: testCase.metadata.agentRunId,
    agentSourcePointId: testCase.metadata.agentSourcePointId,
    agentEnvironmentId: testCase.metadata.agentEnvironmentId,
    agentBaseUrl: testCase.metadata.agentBaseUrl,
    agentAuthenticationMode: testCase.metadata.agentAuthenticationMode,
    agentEvidenceIds: testCase.metadata.agentEvidenceIds,
    agentPageKey: testCase.metadata.agentPageKey,
    agentPageFingerprint: testCase.metadata.agentPageFingerprint,
    agentStepTraces: testCase.metadata.agentStepTraces,
    agentExpectedResultEvidenceIds: testCase.metadata.agentExpectedResultEvidenceIds,
    artifactId: testCase.metadata.artifactId,
    artifactRevision: testCase.metadata.artifactRevision,
    artifactResultOrigin: testCase.metadata.artifactResultOrigin,
    artifactAcceptanceState: testCase.metadata.artifactAcceptanceState,
    artifactModelProfileVersion: testCase.metadata.artifactModelProfileVersion,
    artifactFieldOrigins: testCase.metadata.artifactFieldOrigins,
    artifactEvidenceIds: testCase.metadata.artifactEvidenceIds,
    artifactParentArtifactId: testCase.metadata.artifactParentArtifactId,
    artifactIdempotencyKey: testCase.metadata.artifactIdempotencyKey
  }))

export const runWebAutomationCases = async (
  cases: WebAutomationRunCase[],
  options: WebAutomationRunOptions
): Promise<WebAutomationRunReport> => {
  const startedAt = new Date().toISOString()
  const orderedCases = expandWebAutomationRunCases(sortCases(cases, options.mode), normalizeLoopCount(options.loopCount))
  const results: WebAutomationRunResult[] = []

  for (const testCase of orderedCases) {
    results.push(runOneWebAutomationCase(applyRuntimeToCase(testCase, options.runtime)))
  }

  return {
    mode: options.mode,
    startedAt,
    endedAt: new Date().toISOString(),
    summary: {
      total: results.length,
      passed: results.filter((item) => item.status === 'passed').length,
      failed: results.filter((item) => item.status === 'failed').length,
      error: results.filter((item) => item.status === 'error').length
    },
    results
  }
}

export const removeResultsFromWebAutomationRunReport = (
  report: WebAutomationRunReport | undefined,
  caseIds: string[]
): WebAutomationRunReport | undefined => {
  if (!report) return undefined

  const deleting = new Set(caseIds)
  const results = report.results.filter((item) => !deleting.has(item.caseId))

  return {
    ...report,
    summary: {
      total: results.length,
      passed: results.filter((item) => item.status === 'passed').length,
      failed: results.filter((item) => item.status === 'failed').length,
      error: results.filter((item) => item.status === 'error').length
    },
    results
  }
}

const runOneWebAutomationCase = (testCase: WebAutomationRunCase): WebAutomationRunResult => {
  const failedStepId = testCase.mockOutcome === 'failed'
    ? testCase.steps[testCase.steps.length - 1]?.id
    : testCase.mockOutcome === 'error'
      ? testCase.steps[0]?.id
      : undefined
  const errorMessage = testCase.mockOutcome === 'failed'
    ? '断言失败：目标页面元素未出现'
    : testCase.mockOutcome === 'error'
      ? '浏览器启动失败或页面脚本异常'
      : undefined

  return {
    caseId: testCase.id,
    sourceCaseId: testCase.sourceCaseId,
    name: testCase.name,
    module: testCase.module,
    pageName: testCase.pageName,
    startUrl: testCase.startUrl,
    status: testCase.mockOutcome,
    durationMs: 80 + testCase.steps.length * 25,
    stepLogs: testCase.steps.map((step) => {
      const startedAt = new Date().toISOString()
      const failed = step.id === failedStepId
      return {
        stepId: step.id,
        name: step.name,
        action: step.action,
        target: step.target,
        status: failed ? 'failed' as const : 'passed' as const,
        durationMs: 25,
        startedAt,
        endedAt: startedAt,
        pageUrl: testCase.startUrl,
        ...(failed && errorMessage ? { errorMessage } : {})
      }
    }),
    ...(errorMessage
      ? {
          errorMessage,
          failedStepId,
          pageHtmlSnippet: testCase.mockOutcome === 'failed'
            ? '<div class="mock-page">Mock failure snapshot</div>'
            : '<div class="mock-page">Mock error snapshot</div>'
        }
      : {})
  }
}

const applyRuntimeToCase = (
  testCase: WebAutomationRunCase,
  runtime: BuiltWebExecutionRuntime | undefined
): WebAutomationRunCase => {
  if (!runtime) return testCase
  return {
    ...testCase,
    startUrl: resolveWebRuntimeUrl(runtime.baseUrl, testCase.startUrl),
    browser: runtime.browser === 'follow-case' ? testCase.browser : runtime.browser,
    viewport: runtime.viewport
  }
}

const resolveWebRuntimeUrl = (baseUrl: string, startUrl: string): string => {
  const trimmedBaseUrl = baseUrl.trim()
  const trimmedStartUrl = startUrl.trim()
  if (!trimmedBaseUrl || /^https?:\/\//i.test(trimmedStartUrl)) return startUrl
  if (!trimmedStartUrl) return trimmedBaseUrl
  try {
    return new URL(trimmedStartUrl, trimmedBaseUrl).toString()
  } catch {
    return startUrl
  }
}

const sortCases = (cases: WebAutomationRunCase[], mode: WebAutomationRunMode): WebAutomationRunCase[] => {
  if (mode === 'module') {
    return [...cases].sort((left, right) =>
      left.module === right.module ? left.runOrder - right.runOrder : left.module.localeCompare(right.module)
    )
  }
  return [...cases].sort((left, right) => left.runOrder - right.runOrder)
}

const normalizeLoopCount = (loopCount?: number): number =>
  Math.max(1, Math.min(100, Math.floor(loopCount || 1)))

const expandWebAutomationRunCases = (
  cases: WebAutomationRunCase[],
  loopCount: number
): WebAutomationRunCase[] => {
  if (loopCount <= 1) return cases

  return Array.from({ length: loopCount }).flatMap((_, loopIndex) =>
    cases.map((testCase) => ({
      ...testCase,
      id: `${testCase.id}#${loopIndex + 1}`
    }))
  )
}

const inferMockOutcome = (testCase: SavedWebAutomationCase): WebAutomationMockOutcome => {
  return inferWebAutomationMockOutcome(testCase.row.startUrl)
}

const inferWebAutomationMockOutcome = (startUrl: string): WebAutomationMockOutcome => {
  if (startUrl.includes('error')) return 'failed'
  if (startUrl.includes('crash')) return 'error'
  return 'passed'
}

const normalizeBrowser = (value: string): WebAutomationBrowser => {
  if (value === 'chrome' || value === 'msedge' || value === 'firefox' || value === 'webkit') return value
  return 'chromium'
}

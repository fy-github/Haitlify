import type { AutomationRunResult, AutomationRunStatus } from '../domain/apiAutomation'
import type { WebAutomationRunReport, WebAutomationStepEvidence } from '../domain/webAutomation'

// Pure presentation/format helpers shared by the automation, reports, and
// execution-log views. Kept free of reactive state so each view composable can
// import them without introducing a dependency cycle.

export const automationStatusLabel = (status: AutomationRunStatus | 'pending') => {
  const labels: Record<AutomationRunStatus | 'pending', string> = {
    passed: '通过',
    failed: '失败',
    error: '错误',
    pending: '未执行'
  }
  return labels[status]
}

export const automationStatusTagType = (status: AutomationRunStatus | 'pending') => {
  const types: Record<AutomationRunStatus | 'pending', 'success' | 'danger' | 'warning' | 'info'> = {
    passed: 'success',
    failed: 'danger',
    error: 'warning',
    pending: 'info'
  }
  return types[status]
}

export type WebReportStepLog = {
  stepId: string
  phase?: 'setup' | 'test' | 'teardown'
  name?: string
  action: string
  target: string
  status: 'passed' | 'failed'
  durationMs: number
  startedAt?: string
  endedAt?: string
  pageUrl?: string
  errorMessage?: string
  errorStack?: string
}

export const reportWebStepLogs = (row: AutomationRunResult): WebReportStepLog[] =>
  ((row as AutomationRunResult & { stepLogs?: WebReportStepLog[] }).stepLogs) || []

export const reportStepEvidence = (row: AutomationRunResult): WebAutomationStepEvidence[] =>
  ((row as AutomationRunResult & { stepEvidence?: WebAutomationStepEvidence[] }).stepEvidence) || []

export const reportFailedStepId = (row: AutomationRunResult): string =>
  (row as AutomationRunResult & { failedStepId?: string }).failedStepId || ''

export const reportScreenshotPath = (row: AutomationRunResult): string =>
  (row as AutomationRunResult & { screenshotPath?: string }).screenshotPath || ''

export const reportHtmlSnippet = (row: AutomationRunResult): string =>
  (row as AutomationRunResult & { pageHtmlSnippet?: string }).pageHtmlSnippet || ''

export const formatLogValue = (value: unknown): string => {
  if (value === undefined || value === null || value === '') return '-'
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

export const webRunResultToAutomationLog = (
  result: WebAutomationRunReport['results'][number]
): AutomationRunResult => ({
  caseId: result.caseId,
  sourceCaseId: result.sourceCaseId,
  name: result.name,
  module: result.module,
  method: 'WEB',
  path: result.startUrl,
  status: result.status,
  request: {
    method: 'WEB',
    url: result.startUrl,
    headers: {},
    expectedStatus: 0,
    assertions: []
  },
  expectedStatus: 0,
  durationMs: result.durationMs,
  failedStepId: result.failedStepId,
  pageHtmlSnippet: result.pageHtmlSnippet,
  screenshotPath: result.screenshotPath,
  errorMessage: result.errorMessage,
  consoleErrors: result.consoleErrors,
  pageErrors: result.pageErrors,
  failedRequests: result.failedRequests,
  stepLogs: result.stepLogs,
  stepEvidence: result.stepEvidence
})

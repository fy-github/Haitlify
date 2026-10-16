import type { AutomationRunReport, AutomationRunResult } from './apiAutomation'
import type { WebAutomationRunReport } from './webAutomation'

export const AUTOMATION_REPORT_STORAGE_KEY = 'ai-test-platform.automation-reports.v1'

export interface SavedAutomationReport extends AutomationRunReport {
  reportId: string
  name: string
  project: string
  environmentName: string
  moduleScope: string[]
  createdAt: string
}

export interface BuildSavedAutomationReportOptions {
  reportId: string
  name: string
  project: string
  environmentName: string
  moduleScope: string[]
  createdAt?: string
}

export const listAutomationReports = (storage: Storage): SavedAutomationReport[] =>
  readReports(storage).sort((left, right) => right.createdAt.localeCompare(left.createdAt))

export const appendAutomationReport = (storage: Storage, report: SavedAutomationReport): void => {
  const reports = readReports(storage)
  storage.setItem(AUTOMATION_REPORT_STORAGE_KEY, JSON.stringify([report, ...reports]))
}

export const buildSavedAutomationReportRecord = (
  report: AutomationRunReport | WebAutomationRunReport,
  options: BuildSavedAutomationReportOptions
): SavedAutomationReport => ({
  ...normalizeReport(report),
  reportId: options.reportId,
  name: options.name,
  project: options.project,
  environmentName: options.environmentName,
  moduleScope: options.moduleScope,
  createdAt: options.createdAt || new Date().toISOString()
})

const normalizeReport = (report: AutomationRunReport | WebAutomationRunReport): AutomationRunReport =>
  'results' in report && report.results.length > 0 && 'request' in report.results[0]
    ? report as AutomationRunReport
    : {
        mode: report.mode,
        startedAt: report.startedAt,
        endedAt: report.endedAt,
        summary: report.summary,
        results: (report as WebAutomationRunReport).results.map(toAutomationRunResult)
      }

const toAutomationRunResult = (result: WebAutomationRunReport['results'][number]): AutomationRunResult => ({
  caseId: result.caseId,
  sourceCaseId: result.sourceCaseId,
  name: result.name,
  module: result.module,
  method: 'WEB',
  path: result.startUrl,
  status: result.status,
  request: {
    method: 'GET',
    url: result.startUrl,
    headers: {},
    expectedStatus: 0,
    assertions: []
  },
  expectedStatus: 0,
  durationMs: result.durationMs,
  ...(result.failedStepId ? { failedStepId: result.failedStepId } : {}),
  ...(result.pageHtmlSnippet ? { pageHtmlSnippet: result.pageHtmlSnippet } : {}),
  ...(result.screenshotPath ? { screenshotPath: result.screenshotPath } : {}),
  ...(result.stepLogs ? {
    stepLogs: result.stepLogs
  } : {}),
  ...(result.stepEvidence ? { stepEvidence: result.stepEvidence } : {}),
  ...(result.errorMessage ? { errorMessage: result.errorMessage } : {})
})

export const deleteAutomationReport = (storage: Storage, reportId: string): boolean => {
  const reports = readReports(storage)
  const next = reports.filter((report) => report.reportId !== reportId)
  storage.setItem(AUTOMATION_REPORT_STORAGE_KEY, JSON.stringify(next))
  return next.length !== reports.length
}

const readReports = (storage: Storage): SavedAutomationReport[] => {
  const raw = storage.getItem(AUTOMATION_REPORT_STORAGE_KEY)
  if (!raw) return []

  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter(isSavedAutomationReport) : []
  } catch {
    return []
  }
}

const isSavedAutomationReport = (value: unknown): value is SavedAutomationReport => {
  if (!value || typeof value !== 'object') return false
  const record = value as Record<string, unknown>
  return (
    typeof record.reportId === 'string' &&
    typeof record.name === 'string' &&
    typeof record.project === 'string' &&
    typeof record.createdAt === 'string' &&
    Array.isArray(record.moduleScope)
  )
}

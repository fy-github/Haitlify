import type { SavedAutomationReport } from './automationReportRepository'
import type { AutomationRunResult, AutomationRunStatus } from './apiAutomation'

export type ReportResultFilter = 'ALL' | 'ABNORMAL' | AutomationRunStatus

/** 整份报告的精确计数：只读 report.results，与当前结果筛选无关。 */
export const countReportResults = (report: SavedAutomationReport): { total: number; passed: number; failed: number; error: number } =>
  report.results.reduce(
    (totals, result) => {
      totals.total += 1
      totals[result.status] += 1
      return totals
    },
    { total: 0, passed: 0, failed: 0, error: 0 }
  )

export const filterReportResults = (
  report: SavedAutomationReport,
  filter: ReportResultFilter
): AutomationRunResult[] => {
  if (filter === 'ALL') return report.results
  if (filter === 'ABNORMAL') {
    return report.results.filter((result) => result.status === 'failed' || result.status === 'error')
  }
  return report.results.filter((result) => result.status === filter)
}

export interface ReportProjectOption {
  value: string
  count: number
}

export interface ReportHistoryFilters {
  project: string
  environment: string
  keyword: string
}

export interface ReportDetailSummaryItem {
  label: string
  value: string
}

export const buildReportProjectOptions = (reports: SavedAutomationReport[]): ReportProjectOption[] => {
  const counts = new Map<string, number>()
  for (const report of reports) {
    counts.set(report.project, (counts.get(report.project) || 0) + 1)
  }
  return Array.from(counts.entries()).map(([value, count]) => ({ value, count }))
}

export const filterReportsForProject = (
  reports: SavedAutomationReport[],
  filters: ReportHistoryFilters
): SavedAutomationReport[] => {
  if (!filters.project) return []
  const keyword = filters.keyword.trim().toLowerCase()

  return reports.filter((report) => {
    if (report.project !== filters.project) return false
    if (filters.environment !== 'ALL' && report.environmentName !== filters.environment) return false
    if (!keyword) return true
    return [
      report.reportId,
      report.name,
      report.project,
      report.environmentName,
      report.moduleScope.join(' ')
    ].join(' ').toLowerCase().includes(keyword)
  })
}

export const reportResultKind = (report: SavedAutomationReport): 'api' | 'web' => {
  const first = report.results[0]
  return first?.method === 'WEB' ? 'web' : 'api'
}

export const buildReportDetailSummary = (report: SavedAutomationReport): ReportDetailSummaryItem[] => [
  {
    label: '结果类型',
    value: reportResultKind(report) === 'web' ? 'Web 自动化' : 'API 自动化'
  },
  {
    label: '执行时间',
    value: report.createdAt.slice(0, 16).replace('T', ' ')
  },
  {
    label: '模块范围',
    value: report.moduleScope.join(' / ')
  },
  {
    label: '运行环境',
    value: report.environmentName
  },
  {
    label: '开始时间',
    value: report.startedAt.slice(11, 19)
  },
  {
    label: '结束时间',
    value: report.endedAt.slice(11, 19)
  }
]

export const collectReportEnvironmentOptions = (
  reports: SavedAutomationReport[],
  project: string
): string[] => Array.from(new Set(reports.filter((report) => report.project === project).map((report) => report.environmentName)))

export const selectActiveReportId = (
  reports: SavedAutomationReport[],
  currentReportId?: string
): string | undefined =>
  reports.some((report) => report.reportId === currentReportId) ? currentReportId : reports[0]?.reportId

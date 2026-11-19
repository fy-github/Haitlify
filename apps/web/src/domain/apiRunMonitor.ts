import type {
  ApiAutomationCaseDraft, AutomationRunProgress, AutomationRunReport, AutomationRunResult,
  AutomationRunStatus
} from './apiAutomation'
import type { ApiRunSnapshot } from './apiExecutionPreparation'

export interface ApiMonitorQueueRow {
  caseId: string
  originalCaseId: string
  testCase: ApiAutomationCaseDraft
  loop: number
  status: AutomationRunStatus | 'pending' | 'running' | 'unreturned'
  startedAt?: string
  endedAt?: string
  result?: AutomationRunResult
}

/** In-memory frontend observation only: no server task ID, queue or synthetic response. */
export interface ApiRunMonitor {
  snapshot: ApiRunSnapshot
  status: 'running' | 'completed' | 'error'
  startedAt: string
  endedAt?: string
  errorMessage?: string
  progress: AutomationRunProgress
  rows: ApiMonitorQueueRow[]
}

export const createApiRunMonitor = (snapshot: ApiRunSnapshot, startedAt = new Date().toISOString()): ApiRunMonitor => {
  // Match the existing executor's normalization and expanded identity contract exactly.
  const loops = Math.max(1, Math.min(100, Math.floor(snapshot.options.loopCount || 1)))
  const rows = Array.from({ length: loops }).flatMap((_, index) => snapshot.cases.map(testCase => ({
    caseId: loops > 1 ? `${testCase.id}#${index + 1}` : testCase.id,
    originalCaseId: testCase.id,
    testCase,
    loop: index + 1,
    status: 'pending' as const
  })))
  return { snapshot, status: 'running', startedAt, progress: { completed: 0, total: rows.length }, rows }
}

export const recordApiCaseStart = (monitor: ApiRunMonitor, testCase: ApiAutomationCaseDraft, at = new Date().toISOString()) => {
  const row = monitor.rows.find(item => item.caseId === testCase.id)
  if (!row || row.result || monitor.status !== 'running') return
  row.status = 'running'
  row.startedAt = at
}

export const recordApiCaseResult = (monitor: ApiRunMonitor, result: AutomationRunResult, at = new Date().toISOString()) => {
  const row = monitor.rows.find(item => item.caseId === result.caseId)
  if (!row || row.result || monitor.status !== 'running') return
  row.result = result
  row.status = result.status
  row.endedAt = at
  // Concurrent siblings may settle before the next onProgress emission.
  // Advance only from actual onResult receipts, never from elapsed time.
  monitor.progress.completed = monitor.rows.filter(item => item.result).length
}

export const completeApiRunMonitor = (monitor: ApiRunMonitor, report: AutomationRunReport) => {
  monitor.status = 'completed'
  monitor.startedAt = report.startedAt
  monitor.endedAt = report.endedAt
  // Results are deliberately NOT reconstructed here: each row is filled by onResult.
}

export const apiMonitorCounts = (monitor: ApiRunMonitor) => monitor.rows.reduce((counts, row) => {
  counts[row.status] += 1
  if (row.result) counts.completed += 1
  return counts
}, { completed: 0, passed: 0, failed: 0, error: 0, pending: 0, running: 0, unreturned: 0 })

export const apiMonitorStatusLabel = (status: ApiMonitorQueueRow['status']) => ({
  pending: '待执行', running: '运行中', passed: '通过', failed: '断言失败', error: '请求错误', unreturned: '结果未回传'
}[status])

export const apiMonitorLogs = (row: ApiMonitorQueueRow): Array<{ at?: string; text: string; error?: boolean }> => {
  if (row.status === 'pending') return [{ text: '待执行，尚无请求和响应记录。' }]
  const lines: Array<{ at?: string; text: string; error?: boolean }> = [
    { at: row.startedAt, text: `开始执行 ${row.caseId} · ${row.testCase.method} ${row.testCase.path}` }
  ]
  if (!row.result) return [...lines, { text: row.status === 'unreturned' ? '本次执行异常结束，该用例没有回传结果。' : '正在执行，等待实际结果；暂无请求快照和响应记录。' }]
  const result = row.result
  lines.push({ at: row.endedAt, text: `执行结束 · ${apiMonitorStatusLabel(result.status)} · ${result.durationMs} ms` })
  if (result.actualStatus !== undefined) lines.push({ at: row.endedAt, text: `实际状态 HTTP ${result.actualStatus} · 期望 ${result.expectedStatus}` })
  if (result.errorMessage) lines.push({ at: row.endedAt, text: result.errorMessage, error: true })
  if (result.responseBodyMatch) lines.push({ at: row.endedAt, text: `返回体参数一致度 ${result.responseBodyMatch.ratio}% · 阈值由本次配置快照确定` })
  return lines
}

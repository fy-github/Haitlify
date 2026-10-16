import { describe, expect, it } from 'vitest'
import {
  AUTOMATION_REPORT_STORAGE_KEY,
  appendAutomationReport,
  buildSavedAutomationReportRecord,
  deleteAutomationReport,
  listAutomationReports,
  type SavedAutomationReport
} from './automationReportRepository'
import type { WebAutomationRunReport } from './webAutomation'

class MemoryStorage implements Storage {
  private values = new Map<string, string>()
  get length() { return this.values.size }
  clear(): void { this.values.clear() }
  getItem(key: string): string | null { return this.values.get(key) ?? null }
  key(index: number): string | null { return Array.from(this.values.keys())[index] ?? null }
  removeItem(key: string): void { this.values.delete(key) }
  setItem(key: string, value: string): void { this.values.set(key, value) }
}

const report = (id: string, project = 'Haitlify'): SavedAutomationReport => ({
  reportId: id,
  name: `${project} 执行报告`,
  project,
  environmentName: '测试环境',
  moduleScope: ['项目成员'],
  createdAt: '2026-06-11T08:00:00.000Z',
  mode: 'sequential',
  startedAt: '2026-06-11T08:00:00.000Z',
  endedAt: '2026-06-11T08:00:10.000Z',
  summary: {
    total: 2,
    passed: 1,
    failed: 1,
    error: 0
  },
  results: []
})

describe('automationReportRepository', () => {
  it('builds a saved report record from a web automation run report', () => {
    const report: WebAutomationRunReport = {
      mode: 'sequential',
      startedAt: '2026-06-22T10:00:00.000Z',
      endedAt: '2026-06-22T10:00:10.000Z',
      summary: {
        total: 1,
        passed: 0,
        failed: 1,
        error: 0
      },
      results: [
        {
          caseId: 'WEB-AUTO-WEB-0001',
          sourceCaseId: 'WEB-0001',
          name: '登录失败',
          module: '登录',
          pageName: '登录页',
          startUrl: '/login/error',
          status: 'failed',
          durationMs: 120,
          errorMessage: '断言失败',
          failedStepId: 'step-1',
          pageHtmlSnippet: '<div>snapshot</div>',
          screenshotPath: '/artifacts/web-auto-1.png',
          stepEvidence: [{
            actionId: 'a1',
            actionType: 'goto',
            evidenceId: 'ev1',
            before: { pageUrl: '', pageKey: '', pageFingerprint: '' },
            after: { pageUrl: '/login/error', pageKey: '/login/error', pageFingerprint: 'fp' },
            execution: { status: 'passed', durationMs: 25 }
          }],
          stepLogs: [
            {
              stepId: 'step-1',
              name: '打开登录页',
              action: 'goto',
              target: '/login/error',
              status: 'passed',
              durationMs: 25
            }
          ]
        }
      ]
    }

    const saved = buildSavedAutomationReportRecord(report, {
      reportId: 'web-report-1',
      name: 'Web 登录回归',
      project: 'Haitlify',
      environmentName: 'Mock 环境',
      moduleScope: ['登录'],
      createdAt: '2026-06-22T10:00:10.000Z'
    })

    expect(saved).toMatchObject({
      reportId: 'web-report-1',
      project: 'Haitlify',
      summary: {
        failed: 1
      }
    })
    expect(saved.results[0]).toMatchObject({
      method: 'WEB',
      path: '/login/error'
    })
    expect((saved.results[0] as { stepLogs?: Array<{ status: string; durationMs: number }> }).stepLogs?.[0]).toMatchObject({
      status: 'passed',
      durationMs: 25
    })
    expect((saved.results[0] as { stepEvidence?: Array<{ evidenceId: string }> }).stepEvidence?.[0].evidenceId).toBe('ev1')
    expect(saved.results[0]).toMatchObject({
      failedStepId: 'step-1',
      screenshotPath: '/artifacts/web-auto-1.png'
    })
  })

  it('appends and lists saved reports in reverse chronological order', () => {
    const storage = new MemoryStorage()
    appendAutomationReport(storage, report('report-1'))
    appendAutomationReport(storage, { ...report('report-2', 'Ucenter'), createdAt: '2026-06-11T09:00:00.000Z' })

    expect(listAutomationReports(storage).map((item) => item.reportId)).toEqual(['report-2', 'report-1'])
  })

  it('deletes one saved report without touching others', () => {
    const storage = new MemoryStorage()
    appendAutomationReport(storage, report('report-1'))
    appendAutomationReport(storage, report('report-2'))

    expect(deleteAutomationReport(storage, 'report-1')).toBe(true)
    expect(listAutomationReports(storage).map((item) => item.reportId)).toEqual(['report-2'])
    expect(storage.getItem(AUTOMATION_REPORT_STORAGE_KEY)).toContain('report-2')
  })
})

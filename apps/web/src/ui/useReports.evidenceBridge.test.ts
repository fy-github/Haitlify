// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { useReports } from './useReports'
import { buildSavedAutomationReportRecord } from '../domain/automationReportRepository'
import { source, TestStorage } from '../domain/defectEvidence.testFixtures'
import { DEFECT_EVIDENCE_KEYS } from '../domain/defectEvidence'
const mocks = vi.hoisted(() => ({ create: vi.fn() }))
vi.mock('./useDefects', () => ({ useDefects: () => ({ createDefectsFromWebResults: mocks.create, jiraProjectName: { value: 'p' } }) }))
vi.mock('element-plus', () => ({ ElMessage: { info: vi.fn(), warning: vi.fn(), success: vi.fn() } }))
const make = () => buildSavedAutomationReportRecord({ mode: 'sequential', startedAt: '', endedAt: '', summary: { total: 1, passed: 0, failed: 1, error: 0 }, results: [source] }, { reportId: 'r', name: 'report', project: 'p', environmentName: '', moduleScope: [] })

describe('complete report evidence bridge', () => {
  it('copies every source own evidence field, empty strings/arrays/zero and diagnostics both directions', () => {
    const report = make(); const row = report.results[0]
    for (const key of DEFECT_EVIDENCE_KEYS) expect(row[key], key).toStrictEqual(source[key])
    const reports = useReports({ storage: new TestStorage() })
    reports.transferReportResultToDefect(row)
    const transferred = mocks.create.mock.lastCall![0][0]
    for (const key of DEFECT_EVIDENCE_KEYS) expect(transferred[key], key).toStrictEqual(source[key])
  })
  it('passes source values by presence into the create error boundary, without validating outside it', () => {
    const row = make().results[0]
    row.screenshotPath = undefined
    const reports = useReports({ storage: new TestStorage() })
    expect(() => reports.transferReportResultToDefect(row)).not.toThrow()
    const transferred = mocks.create.mock.lastCall![0][0]
    expect(Object.prototype.hasOwnProperty.call(transferred, 'screenshotPath')).toBe(true)
    expect(transferred.screenshotPath).toBeUndefined()
  })
  it('defers invalid accessor evidence into the create boundary without invoking the getter', () => {
    const row = make().results[0]
    let invoked = 0
    Object.defineProperty(row, 'screenshotPath', { enumerable: true, get() { invoked++; throw new Error('must not evaluate') } })
    const reports = useReports({ storage: new TestStorage() })
    expect(() => reports.transferReportResultToDefect(row)).not.toThrow()
    expect(invoked).toBe(0)
    const transferred = mocks.create.mock.lastCall![0][0]
    expect(Object.getOwnPropertyDescriptor(transferred, 'screenshotPath')?.get).toEqual(expect.any(Function))
  })
  it('does not turn missing historical source fields into display fallback', () => {
    const row = make().results[0]
    delete row.pageName; delete row.startUrl; Reflect.deleteProperty(row, 'durationMs'); delete row.steps
    delete row.stepLogs; delete row.consoleErrors; delete row.failedStepId
    row.path = '/display-only'; row.name = 'display-only'
    const reports = useReports({ storage: new TestStorage() })
    reports.transferReportResultToDefect(row)
    const transferred = mocks.create.mock.lastCall![0][0]
    for (const key of ['pageName', 'startUrl', 'durationMs', 'steps', 'stepLogs', 'consoleErrors', 'failedStepId']) expect(Object.prototype.hasOwnProperty.call(transferred, key), key).toBe(false)
  })
})

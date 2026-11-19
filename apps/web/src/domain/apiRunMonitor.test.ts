import { describe, expect, it } from 'vitest'
import { buildApiRunSnapshot } from './apiExecutionPreparation'
import { runAutomationCases, type AutomationResponse, type AutomationRunMode } from './apiAutomation'
import { apiMonitorCounts, apiMonitorLogs, completeApiRunMonitor, createApiRunMonitor, recordApiCaseResult, recordApiCaseStart } from './apiRunMonitor'
import type { SavedAutomationCase } from './automationRepository'

const cases: SavedAutomationCase[] = ['A', 'B', 'C'].map((id, index) => ({
  id, sourceCaseId: id, project: 'Fixture', module: index < 2 ? 'M1' : 'M2', name: id,
  method: 'POST', path: `/${id}`, headers: {}, pathParams: {}, queryParams: {}, body: { id },
  expectedStatus: 200, assertions: ['body.ok = true'], runOrder: index,
  exportTarget: 'apifox', automationLibraryId: id, savedAt: '2026-01-01T00:00:00Z'
}))
const snapshot = (mode: AutomationRunMode = 'sequential', loops = 2) => buildApiRunSnapshot({
  cases, environment: { baseUrl: 'https://fixture.invalid' }, options: { mode, loopCount: loops, concurrencyLimit: 2 },
  scopeLabel: '执行筛选 3 条', frozenAt: '2026-01-01T00:00:00Z'
})
const settle = async () => { for (let i = 0; i < 15; i++) await Promise.resolve() }

describe('API run monitor model', () => {
  it.each(['sequential', 'concurrent', 'module'] as const)('observes the actual %s runner loop identities and each result in settlement order', async mode => {
    const frozen = snapshot(mode)
    const monitor = createApiRunMonitor(frozen)
    const inFlight = new Map<string, (v: AutomationResponse) => void>()
    const observed: string[] = []
    const finished = runAutomationCases([...frozen.cases], frozen.environment, frozen.options,
      (_request, testCase) => new Promise(resolve => { inFlight.set(testCase.id, resolve) }), {
        onProgress: progress => { monitor.progress = progress },
        onCaseStart: testCase => recordApiCaseStart(monitor, testCase),
        onResult: result => { observed.push(result.caseId); recordApiCaseResult(monitor, result) }
      })
    expect(monitor.rows.map(r => r.caseId)).toEqual(['A#1', 'B#1', 'C#1', 'A#2', 'B#2', 'C#2'])
    expect(monitor.progress.total).toBe(6)
    expect(apiMonitorCounts(monitor).running).toBe(mode === 'sequential' ? 1 : 2)
    expect(monitor.rows.every(r => !r.result)).toBe(true)
    for (let n = 0; n < 6; n++) {
      await settle()
      // Concurrent results are deliberately resolved in reverse launch order.
      const ids = [...inFlight.keys()]
      const id = ids[ids.length - 1]!
      expect(id).toBeTruthy()
      const resolve = inFlight.get(id)!
      inFlight.delete(id)
      resolve({ status: 200, body: { ok: n !== 0 } })
      await settle()
      const row = monitor.rows.find(r => r.caseId === id)!
      expect(row.result?.caseId).toBe(id)
      expect(row.result?.actualStatus).toBe(200)
      expect(row.status).toBe(n === 0 ? 'failed' : 'passed')
      expect(apiMonitorCounts(monitor).completed).toBe(n + 1)
      expect(monitor.progress.completed).toBe(n + 1)
      expect(row.result?.durationMs).toBeGreaterThanOrEqual(0)
    }
    const report = await finished
    completeApiRunMonitor(monitor, report)
    expect(new Set(observed).size).toBe(6)
    expect(monitor.status).toBe('completed')
    expect(monitor.progress).toEqual({ completed: 6, total: 6 })
    expect(apiMonitorCounts(monitor)).toMatchObject({ passed: 5, failed: 1, running: 0, pending: 0 })
  })
  it('does not invent request or response evidence for pending and running work', () => {
    const monitor = createApiRunMonitor(snapshot())
    expect(apiMonitorLogs(monitor.rows[0]!)).toEqual([{ text: '待执行，尚无请求和响应记录。' }])
    recordApiCaseStart(monitor, { ...cases[0]!, id: 'A#1' }, '2026-01-01T00:01:00Z')
    expect(monitor.rows[0]?.result).toBeUndefined()
    expect(apiMonitorLogs(monitor.rows[0]!).map(l => l.text).join('\n')).toContain('等待实际结果')
    expect(apiMonitorLogs(monitor.rows[0]!).map(l => l.text).join('\n')).not.toContain('HTTP 200')
  })
  it('keeps single-loop IDs unchanged and matches the existing runner normalization at 100 loops', () => {
    expect(createApiRunMonitor(snapshot('sequential', 1)).rows.map(r => r.caseId)).toEqual(['A', 'B', 'C'])
    expect(createApiRunMonitor(snapshot('sequential', 101)).rows).toHaveLength(300)
  })
  it('rejects unknown and post-terminal result events without inventing records', async () => {
    const monitor = createApiRunMonitor(snapshot('sequential', 1))
    const report = await runAutomationCases([cases[0]!], monitor.snapshot.environment, { mode: 'sequential' }, async () => { throw new Error('fixture timeout') })
    const result = report.results[0]!
    recordApiCaseResult(monitor, { ...result, caseId: 'unknown' })
    expect(monitor.rows.every(r => !r.result)).toBe(true)
    recordApiCaseResult(monitor, result)
    expect(monitor.rows[0]?.result?.errorMessage).toBe('fixture timeout')
    expect(monitor.rows[0]?.result?.actualStatus).toBeUndefined()
    monitor.status = 'error'
    recordApiCaseResult(monitor, { ...result, caseId: 'B' })
    recordApiCaseStart(monitor, cases[1]!)
    expect(monitor.rows[1]?.result).toBeUndefined()
    expect(monitor.rows[1]?.status).toBe('pending')
  })
})

import { describe, expect, it } from 'vitest'
import { runAutomationCases, type ApiAutomationCaseDraft, type AutomationRunResult } from './apiAutomation'

const cases = (ids: string[]): ApiAutomationCaseDraft[] => ids.map((id, index) => ({
  id, sourceCaseId: id, project: 'Fixture', module: index < 2 ? 'A' : 'B', name: id,
  method: 'GET', path: `/fixture/${id}`, headers: {}, pathParams: {}, queryParams: {},
  expectedStatus: 200, assertions: ['body.ok = true'], runOrder: index + 1, exportTarget: 'apifox'
}))

// These callbacks are deliberately passed to the existing entry point, so RED is an
// assertion on missing behavior, not an unresolved import or a compilation failure.
describe('API frontend lifecycle events', () => {
  it('publishes each expanded identity before execution and its actual result before the next case', async () => {
    const events: string[] = []
    const received: AutomationRunResult[] = []
    const runtime = {
      onCaseStart: (testCase: ApiAutomationCaseDraft) => events.push(`start:${testCase.id}`),
      onResult: (result: AutomationRunResult) => { events.push(`result:${result.caseId}:${result.status}`); received.push(result) }
    }
    const report = await runAutomationCases(cases(['A']), { baseUrl: 'https://fixture.invalid' }, {
      mode: 'sequential', loopCount: 2
    }, async (_request, testCase) => {
      events.push(`request:${testCase.id}`)
      return { status: 200, body: { ok: testCase.id === 'A#1' } }
    }, runtime)
    expect(events).toEqual([
      'start:A#1', 'request:A#1', 'result:A#1:passed',
      'start:A#2', 'request:A#2', 'result:A#2:failed'
    ])
    expect(received).toEqual(report.results)
    expect(received[1]?.actualStatus).toBe(200)
    expect(received[1]?.errorMessage).toContain('断言失败')
    expect(received[1]?.durationMs).toBeGreaterThanOrEqual(0)
  })
})

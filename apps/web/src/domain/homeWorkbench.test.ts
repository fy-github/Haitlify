import { describe, expect, it } from 'vitest'
import { buildHomeWorkbench, type HomeRun, type HomeWorkItem } from './homeWorkbench'

const run = (id: string, state: HomeRun['state'], project = 'one'): HomeRun => ({
  id, name: id, state, project, kind: 'api', target: { navKey: 'automation', automationKind: 'api', runId: id }
})
const item = (id: string, savedAt: string | undefined, kind: HomeWorkItem['kind'] = 'case', project = 'one'): HomeWorkItem => ({
  id, name: id, savedAt, kind, project, target: { navKey: 'cases', caseId: id }
})

describe('buildHomeWorkbench', () => {
  it('scopes nonterminal runs and saved entries without manufacturing recent visits, and keeps the complete actionable queue independent of the recent type filter', () => {
    const cases = item('old-case', '2026-10-01T00:00:00Z')
    const report = { ...item('new-report', '2026-10-02T00:00:00Z', 'report'), target: { navKey: 'reports' as const, reportId: 'new-report' } }
    const queue = Array.from({ length: 5 }, (_, i) => ({ ...item(`draft-${i}`, undefined, 'defect'), pending: { label: '待导出', actionLabel: '查看草稿' } }))
    const all = [...queue, report, cases, item('other-project', '2026-10-03T00:00:00Z', 'case', 'two'), item('not-saved', undefined), item('invalid-date', 'not-a-date')]
    const before = JSON.stringify(all)
    const result = buildHomeWorkbench({
      runs: [run('active', 'running'), run('done', 'completed'), run('other', 'running', 'two')],
      items: all, project: 'one', filter: 'case'
    })
    expect(result.runs.map((value) => value.id)).toEqual(['active'])
    expect(result.recent.map((value) => value.id)).toEqual(['old-case'])
    expect(result.pending.map((value) => value.id)).toEqual(queue.map((value) => value.id))
    expect(JSON.stringify(all)).toBe(before)
    expect(buildHomeWorkbench({ runs: [], items: all, project: 'one' }).recent.map((value) => value.id))
      .toEqual(['new-report', 'old-case'])
  })
})

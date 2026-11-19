import { describe, expect, it } from 'vitest'
import { buildDefectListJsonArtifact } from './defectExport'
import type { DefectDraft } from './defectRepository'

const draft = (patch: Partial<DefectDraft> = {}): DefectDraft => ({
  defectId: 'defect-1',
  sourceReportCaseId: 'report-1',
  sourceCaseId: 'case-1',
  projectName: 'Haitlify',
  issueType: '故障',
  summary: '失败草稿',
  priority: 'P1',
  assignee: '',
  description: '',
  blocksIssueKey: '',
  faultCategory: 'Web自动化失败',
  status: 'pending_jira_export',
  createdAt: '2026-11-10T00:00:00.000Z',
  updatedAt: '2026-11-10T00:00:00.000Z',
  jiraFieldValues: {},
  evidenceVersion: 1,
  stepEvidence: [{ actionId: 'a-1', actionType: 'click', evidenceId: 'e-1', before: { pageUrl: '', pageKey: '', pageFingerprint: '' }, after: { pageUrl: '', pageKey: '', pageFingerprint: '' }, execution: { status: 'failed', durationMs: 0 }, resolvedTarget: { visible: false }, observationStatus: 'failed', failureType: '' }],
  consoleErrors: [],
  pageErrors: [],
  failedRequests: [],
  ...patch
})

describe('defectExport', () => {
  it('exports the complete filtered defect scope without projecting evidence or additive fields', () => {
    const records = [draft({ defectId: 'defect-1', jiraFieldValues: { custom: '' }, extraEvidence: { zero: 0, flag: false } } as never), draft({ defectId: 'defect-2', status: 'created_in_jira' })]
    const artifact = buildDefectListJsonArtifact(records, {
      project: 'Haitlify', status: 'ALL', issueType: 'ALL', priority: 'ALL', assignee: 'ALL', keyword: '失败'
    }, 'filtered', '2026-11-10T01:00:00.000Z')
    const parsed = JSON.parse(artifact.content)
    expect(artifact.filename).toBe('缺陷草稿列表.json')
    expect(artifact.mimeType).toBe('application/json;charset=utf-8')
    expect(parsed.kind).toBe('ai-test-platform.defect-draft-list')
    expect(parsed.scope).toBe('filtered')
    expect(parsed.filters.keyword).toBe('失败')
    expect(parsed.drafts).toHaveLength(2)
    expect(parsed.drafts[0].stepEvidence[0].execution.durationMs).toBe(0)
    expect(parsed.drafts[0].jiraFieldValues.custom).toBe('')
    expect(parsed.drafts[0].extraEvidence).toEqual({ zero: 0, flag: false })
    expect(parsed.drafts[1].status).toBe('created_in_jira')
  })

  it('does not mutate source records and rejects an empty scope', () => {
    const records = [draft()]
    const before = JSON.stringify(records)
    expect(() => buildDefectListJsonArtifact([], { project: 'ALL', status: 'ALL', issueType: 'ALL', priority: 'ALL', assignee: 'ALL', keyword: '' })).toThrow()
    buildDefectListJsonArtifact(records, { project: 'ALL', status: 'ALL', issueType: 'ALL', priority: 'ALL', assignee: 'ALL', keyword: '' })
    expect(JSON.stringify(records)).toBe(before)
  })

  it('rejects non-JSON values before serialization instead of silently dropping them', () => {
    const invalid = draft() as DefectDraft & { future?: unknown }
    invalid.future = undefined
    expect(() => buildDefectListJsonArtifact([invalid], {
      project: 'ALL', status: 'ALL', issueType: 'ALL', priority: 'ALL', assignee: 'ALL', keyword: ''
    })).toThrow()
  })
})

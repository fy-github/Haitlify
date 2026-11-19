import { describe, expect, it } from 'vitest'
import { parseDefectImportArtifact } from './defectImport'
import type { DefectDraft } from './defectRepository'

const draft = (id = 'd-1'): DefectDraft => ({
  defectId: id,
  sourceReportCaseId: `r-${id}`,
  sourceCaseId: `c-${id}`,
  projectName: 'Fixture',
  issueType: '故障',
  summary: '失败草稿',
  priority: 'P1',
  assignee: '',
  description: '',
  blocksIssueKey: '',
  faultCategory: 'Web',
  status: 'pending_jira_export',
  createdAt: '2026-11-11T00:00:00.000Z',
  updatedAt: '2026-11-11T00:00:00.000Z',
  jiraFieldValues: {},
  evidenceVersion: 1,
  stepEvidence: []
})

describe('defectImport', () => {
  it('parses the exported list package and preserves full drafts', () => {
    const record = draft()
    const parsed = parseDefectImportArtifact(JSON.stringify({
      kind: 'ai-test-platform.defect-draft-list', version: 1, exportedAt: '2026-11-11T01:00:00.000Z',
      scope: 'filtered', filters: { project: 'ALL', status: 'ALL', issueType: 'ALL', priority: 'ALL', assignee: 'ALL', keyword: '' },
      drafts: [record]
    }))
    expect(parsed).toEqual([record])
  })

  it('accepts a recovery package and rejects unknown/versioned packages', () => {
    expect(parseDefectImportArtifact(JSON.stringify({ kind: 'ai-test-platform.defect-recovery', version: 1, drafts: [draft()] }))).toHaveLength(1)
    expect(() => parseDefectImportArtifact(JSON.stringify({ kind: 'other', version: 1, drafts: [draft()] }))).toThrow()
    expect(() => parseDefectImportArtifact(JSON.stringify({ kind: 'ai-test-platform.defect-recovery', version: 2, drafts: [draft()] }))).toThrow()
  })

  it('rejects malformed JSON and duplicate draft ids', () => {
    expect(() => parseDefectImportArtifact('{broken')).toThrow()
    expect(() => parseDefectImportArtifact(JSON.stringify({ kind: 'ai-test-platform.defect-recovery', version: 1, drafts: [draft(), draft()] }))).toThrow()
  })

  it('rejects incomplete drafts through the full repository contract', () => {
    const invalid = { ...draft() } as Partial<DefectDraft>
    delete invalid.summary
    expect(() => parseDefectImportArtifact(JSON.stringify({ kind: 'ai-test-platform.defect-recovery', version: 1, drafts: [invalid] }))).toThrow()
  })
})

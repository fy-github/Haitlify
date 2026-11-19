import { describe, expect, it } from 'vitest'
import type { WebAutomationRunResult } from './webAutomation'
import {
  DEFECT_DRAFT_STORAGE_KEY,
  buildDefectDraftsFromWebAutomationResults,
  listDefectDrafts,
  saveDefectDrafts,
  type DefectDraft
} from './defectRepository'

class MemoryStorage implements Storage {
  private values = new Map<string, string>()
  get length() { return this.values.size }
  clear(): void { this.values.clear() }
  getItem(key: string): string | null { return this.values.get(key) ?? null }
  key(index: number): string | null { return Array.from(this.values.keys())[index] ?? null }
  removeItem(key: string): void { this.values.delete(key) }
  setItem(key: string, value: string): void { this.values.set(key, value) }
}

const webFailure: WebAutomationRunResult = {
  caseId: 'web-result-1',
  sourceCaseId: 'WEB-0001',
  name: 'Fixture browser failure',
  module: 'Module web',
  pageName: 'Fixture page',
  startUrl: 'https://example.invalid/fixture-page',
  status: 'failed',
  durationMs: 4200,
  failedStepId: 'step-2',
  pageHtmlSnippet: '<main class="marker">snippet</main>',
  screenshotPath: 'data:image/png;base64,AAA',
  stepLogs: [
    { stepId: 'step-1', name: '打开页面', action: 'goto', target: '/fixture-page', status: 'passed', durationMs: 800 },
    { stepId: 'step-2', name: '点击提交', action: 'click', target: '#submit', status: 'failed', durationMs: 3400, errorMessage: 'element detached' }
  ],
  stepEvidence: [
    { actionId: 'step-2', actionType: 'click', before: { pageUrl: 'a', pageKey: 'k', pageFingerprint: 'f' }, evidenceId: 'ev-1', execution: { status: 'failed', durationMs: 3400 }, after: { pageUrl: 'b', pageKey: 'k2', pageFingerprint: 'f2' } }
  ],
  errorMessage: 'Fixture assertion failure on #submit'
}

const options = { projectName: 'Fixture', issueType: 'Bug' }

describe('defectRepository web evidence', () => {
  it('keeps failed step, step logs, step evidence, screenshot and DOM snippet on the draft', () => {
    const [draft] = buildDefectDraftsFromWebAutomationResults([webFailure], options)
    expect(draft.failedStepId).toBe('step-2')
    expect(draft.stepLogs?.map((log) => log.stepId)).toEqual(['step-1', 'step-2'])
    expect(draft.stepLogs?.[1]).toMatchObject({ action: 'click', target: '#submit', status: 'failed', errorMessage: 'element detached' })
    expect(draft.stepEvidence?.[0]).toMatchObject({ actionId: 'step-2', evidenceId: 'ev-1' })
    expect(draft.screenshotPath).toBe('data:image/png;base64,AAA')
    expect(draft.pageHtmlSnippet).toBe('<main class="marker">snippet</main>')
    expect(draft.faultCategory).toBe('Web自动化失败')
    expect(draft.description).toContain('失败步骤: step-2')
  })

  it('survives a local save and read-back without losing the evidence', () => {
    const storage = new MemoryStorage()
    const [draft] = buildDefectDraftsFromWebAutomationResults([webFailure], options)
    saveDefectDrafts(storage, [draft])
    const [restored] = listDefectDrafts(storage)
    expect(restored.failedStepId).toBe('step-2')
    expect(restored.stepLogs).toEqual(draft.stepLogs)
    expect(restored.stepEvidence).toEqual(draft.stepEvidence)
    expect(restored.screenshotPath).toBe(draft.screenshotPath)
    expect(restored.pageHtmlSnippet).toBe(draft.pageHtmlSnippet)
  })

  it('drops nothing when a result carries no evidence at all', () => {
    const withoutEvidence = { ...webFailure }
    for (const key of ['stepLogs', 'stepEvidence', 'failedStepId', 'screenshotPath', 'pageHtmlSnippet'] as const) delete withoutEvidence[key]
    const [draft] = buildDefectDraftsFromWebAutomationResults([withoutEvidence], options)
    expect(draft.failedStepId).toBeUndefined()
    expect(draft.stepLogs).toBeUndefined()
    expect(draft.description).not.toContain('失败步骤')
  })

  it('rejects explicit undefined rather than silently erasing a supplied evidence field', () => {
    expect(() => buildDefectDraftsFromWebAutomationResults([{ ...webFailure, stepLogs: undefined }], options))
      .toThrow('证据数据格式无效')
  })

  it('still reads a legacy draft persisted before the evidence fields existed', () => {
    const legacy: DefectDraft = {
      defectId: 'legacy-1',
      sourceCaseId: 'WEB-0001',
      sourceReportCaseId: 'web-result-1',
      projectName: 'Fixture',
      issueType: 'Bug',
      summary: 'Legacy draft',
      priority: 'Low',
      assignee: '',
      description: '页面: Fixture page',
      blocksIssueKey: '',
      faultCategory: 'Web自动化失败',
      status: 'pending_jira_export',
      createdAt: '2026-10-29',
      updatedAt: '2026-10-29',
      jiraFieldValues: {}
    }
    const storage = new MemoryStorage()
    storage.setItem(DEFECT_DRAFT_STORAGE_KEY, JSON.stringify([legacy]))
    const [restored] = listDefectDrafts(storage)
    expect(restored.summary).toBe('Legacy draft')
    expect(restored.failedStepId).toBeUndefined()
    expect(restored.stepLogs).toBeUndefined()
  })
})

// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { DEFECT_DRAFT_STORAGE_KEY, type DefectDraft } from '../domain/defectRepository'

vi.mock('../domain/jiraIssueRepository', () => ({ loadJiraIssues: vi.fn(async () => ({ items: [] })) }))
vi.mock('../domain/csvExport', () => ({ downloadCsv: vi.fn() }))
export const fixtureDraft = (n: number): DefectDraft => ({ defectId: `d${n}`, sourceCaseId: `API-${n}`, sourceReportCaseId: `r${n}`, projectName: 'Fixture', issueType: 'Bug', summary: `Draft ${n}`, priority: 'High', assignee: 'alice', description: 'Description', faultCategory: 'API', blocksIssueKey: '', status: 'pending_jira_export', jiraFieldValues: {}, createdAt: '2026-10-29', updatedAt: '2026-10-29' })
async function setup(count = 14) {
  const { useDefects } = await import('./useDefects')
  localStorage.setItem(DEFECT_DRAFT_STORAGE_KEY, JSON.stringify(Array.from({ length: count }, (_, n) => fixtureDraft(n + 1))))
  const confirmDanger = vi.fn(async () => true)
  return { state: useDefects({ storage: localStorage, confirmDanger }), confirmDanger }
}
beforeEach(() => { vi.resetModules(); localStorage.clear(); vi.stubGlobal('fetch', vi.fn(() => { throw Error('Network forbidden') })) })
describe('defect source isolation', () => {
  it('preserves separate source filters, page and both scroll axes', async () => {
    const { state } = await setup()
    state.defectFilter.keyword = 'Draft'
    state.defectPage.value = 2
    state.defectListContexts.draft.scrollTop = 95
    state.defectListContexts.draft.scrollLeft = 180
    await state.switchDefectSource('jira')
    expect(state.defectFilter.keyword).toBe('')
    expect(state.defectPage.value).toBe(1)
    state.defectFilter.keyword = 'Remote'
    await state.switchDefectSource('draft')
    expect(state.defectFilter.keyword).toBe('Draft')
    expect(state.defectPage.value).toBe(2)
    expect(state.defectListContexts.draft).toMatchObject({ scrollTop: 95, scrollLeft: 180 })
  })
  it.each(['keyword', 'priority', 'assignee', 'issueType', 'status', 'project'] as const)('applies the Jira %s filter to rows and counts', async key => {
    const { state } = await setup()
    await state.switchDefectSource('jira')
    state.jiraIssues.value = [{ id: 'j1', projectName: 'Fixture', issueKey: 'FIX-1', summary: 'Remote', priority: 'High', assignee: 'alice', issueType: 'Bug', status: 'Open', updatedAt: '' }]
    expect(state.filteredDefectIssues.value).toHaveLength(1)
    Object.assign(state.defectFilter, { [key]: 'no-match' })
    await nextTick()
    expect(state.filteredDefectIssues.value).toHaveLength(0)
    expect(state.pagedDefectIssues.value).toHaveLength(0)
  })
  it('denies hidden draft bulk and single writes in Jira, even when invoked directly', async () => {
    const { state, confirmDanger } = await setup()
    state.handleDefectSelectionChange(state.filteredDefectIssues.value.slice(0, 1))
    const before = localStorage.getItem(DEFECT_DRAFT_STORAGE_KEY)
    await state.switchDefectSource('jira')
    await state.removeScopedDefects()
    await state.exportSelectedDefectsToJiraCsv()
    await state.removeDefect(state.defectDrafts.value[0])
    expect(confirmDanger).not.toHaveBeenCalled()
    expect(state.activeDefectScope.value).toEqual([])
    expect(localStorage.getItem(DEFECT_DRAFT_STORAGE_KEY)).toBe(before)
  })
  it('stages CSV template edits until a valid Apply and never clears persistence', async () => {
    const { state } = await setup()
    const templateKey = 'ai-test-platform.jira-csv-template.v1'
    state.jiraTemplateVisible.value = true
    state.updateJiraCsvTemplateField('summary', { header: 'Staged header' })
    expect(state.enabledJiraCsvTemplate.value[0].header).toBe('概要')
    expect(localStorage.getItem(templateKey)).toBeNull()
    expect(state.saveJiraCsvTemplateConfig()).not.toBe(false)
    expect(JSON.parse(localStorage.getItem(templateKey)!)[0].header).toBe('Staged header')
    expect(state.jiraTemplateVisible.value).toBe(false)
    state.jiraTemplateVisible.value = true
    state.updateJiraCsvTemplateField('summary', { header: '   ' })
    expect(state.saveJiraCsvTemplateConfig()).toBe(false)
    expect(state.jiraTemplateVisible.value).toBe(true)
    expect(JSON.parse(localStorage.getItem(templateKey)!)[0].header).toBe('Staged header')
  })
  it('requires non-blank summary/project/type before saving a draft edit', async () => {
    const { state } = await setup()
    const before = localStorage.getItem(DEFECT_DRAFT_STORAGE_KEY)
    state.openDefectEdit(state.defectDrafts.value[0])
    state.defectEditForm.projectName = '  '
    expect(state.saveDefectEdit()).toBe(false)
    expect(state.defectEditVisible.value).toBe(true)
    expect(state.defectEditError.value).toContain('项目名称')
    expect(localStorage.getItem(DEFECT_DRAFT_STORAGE_KEY)).toBe(before)
  })
})

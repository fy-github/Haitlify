// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { DEFECT_DRAFT_STORAGE_KEY, type DefectDraft } from '../domain/defectRepository'
vi.mock('../domain/jiraIssueRepository', () => ({ loadJiraIssues: vi.fn(async () => ({ items: [] })) }))
vi.mock('../domain/csvExport', () => ({ downloadCsv: vi.fn() }))
const draft = (id = 'd1'): DefectDraft => ({ defectId: id, sourceCaseId: 'c1', sourceReportCaseId: 'r1', projectName: 'Fixture', issueType: 'Bug', summary: 'Original', priority: 'High', assignee: '', description: '', faultCategory: '', blocksIssueKey: '', status: 'pending_jira_export', jiraFieldValues: {}, createdAt: '2026-10-29', updatedAt: '2026-10-29' })
function fixture() {
  const data = new Map<string, string>([[DEFECT_DRAFT_STORAGE_KEY, JSON.stringify([draft()])]])
  let fail = false
  const storage: Storage = { get length() { return data.size }, key: n => [...data.keys()][n] ?? null, clear: () => data.clear(), removeItem: k => { data.delete(k) }, getItem: k => data.get(k) ?? null, setItem: (k, v) => { if (fail) throw new DOMException('full', 'QuotaExceededError'); data.set(k, v) } }
  return { storage, data, fail: (value: boolean) => { fail = value } }
}
beforeEach(() => { vi.resetModules(); vi.clearAllMocks() })
describe('defect mutation recovery (injected faults, not native quota)', () => {
  it('invalidates a deletion confirmation on reset before any write or UI change', async () => {
    const f = fixture()
    const { useDefects } = await import('./useDefects')
    let confirm!: (value: boolean) => void
    const state = useDefects({ storage: f.storage, confirmDanger: () => new Promise(resolve => { confirm = resolve }) })
    const pending = state.removeDefect(state.defectDrafts.value[0])
    expect(typeof (state as any).resetDefectRecovery).toBe('function')
    ;(state as any).resetDefectRecovery()
    const raw = f.storage.getItem(DEFECT_DRAFT_STORAGE_KEY)
    confirm(true)
    await pending
    expect(f.storage.getItem(DEFECT_DRAFT_STORAGE_KEY)).toBe(raw)
    expect(state.defectDrafts.value).toHaveLength(1)
    expect((state as any).defectFailure.value).toBeNull()
  })
  it('reconciles an uncertain committed edit without a second write and refuses target conflicts', async () => {
    const f = fixture()
    const { useDefects } = await import('./useDefects')
    let afterWrite = false
    const get = f.storage.getItem.bind(f.storage)
    const set = f.storage.setItem.bind(f.storage)
    let writes = 0
    f.storage.setItem = (k, v) => { writes++; set(k, v); afterWrite = true }
    f.storage.getItem = k => { if (afterWrite) throw Error('readback unavailable'); return get(k) }
    const state = useDefects({ storage: f.storage, confirmDanger: async () => true })
    state.openDefectEdit(state.defectDrafts.value[0]); state.defectEditForm.summary = 'Committed'
    expect(state.saveDefectEdit()).toBe(false)
    expect((state as any).defectFailure.value.outcome).toBe('uncertain-commit')
    afterWrite = false
    await (state as any).retryDefectMutation()
    expect(writes).toBe(1)
    expect(state.defectEditVisible.value).toBe(false)
    state.openDefectEdit(state.defectDrafts.value[0]); state.defectEditForm.summary = 'Candidate two'
    f.fail(true); state.saveDefectEdit(); f.fail(false)
    set(DEFECT_DRAFT_STORAGE_KEY, JSON.stringify([{ ...state.defectDrafts.value[0], jiraIssueKey: 'J-7', unknown: false }]))
    await (state as any).retryDefectMutation()
    expect(JSON.parse(get(DEFECT_DRAFT_STORAGE_KEY)!)[0].jiraIssueKey).toBe('J-7')
    expect((state as any).defectFailure.value.code).toBe('conflict')
  })
  it('catches create quota at the initiating surface and treats an unobserved navigation as committed failure', async () => {
    const f = fixture()
    const { useDefects } = await import('./useDefects')
    const state = useDefects({ storage: f.storage, confirmDanger: async () => true, navigateToDefects: () => {} , isDefectsActive: () => false })
    const result = { caseId: 'r2', sourceCaseId: 'w2', name: 'Web', pageName: 'Page', startUrl: '', status: 'failed', durationMs: 0, steps: [], stepLogs: [] }
    f.fail(true)
    expect(() => state.createDefectsFromWebResults([result] as any, 'Fixture')).not.toThrow()
    expect((state as any).defectFailure.value.outcome).toBe('confirmed-no-write')
    f.fail(false)
    await (state as any).retryDefectMutation()
    expect((state as any).defectFailure.value.outcome).toBe('committed-navigation-failed')
    expect((state as any).defectRecoveryCandidate.value).toBeNull()
    expect(state.defectDrafts.value).toHaveLength(2)
  })
  it('catches delete failure, keeps scope/selection, and retries the frozen deletion once', async () => {
    const f = fixture()
    const { useDefects } = await import('./useDefects')
    const state = useDefects({ storage: f.storage, confirmDanger: async () => true })
    state.defectSelection.value = [...state.defectDrafts.value]
    f.fail(true)
    await expect(state.removeScopedDefects()).resolves.toBeDefined()
    expect(state.defectSelection.value).toHaveLength(1)
    expect((state as any).defectFailure.value.outcome).toBe('confirmed-no-write')
    f.fail(false)
    await (state as any).retryDefectMutation()
    expect(state.defectDrafts.value).toHaveLength(0)
    expect(state.defectSelection.value).toHaveLength(0)
  })
  it('keeps CSV download and mark separate, retries only frozen ids while preserving newer Jira and unknown fields', async () => {
    const f = fixture()
    const { downloadCsv } = await import('../domain/csvExport')
    const { useDefects } = await import('./useDefects')
    const state = useDefects({ storage: f.storage, confirmDanger: async () => true })
    state.defectSelection.value = [...state.defectDrafts.value]
    f.fail(true)
    await expect(state.exportSelectedDefectsToJiraCsv()).resolves.toBeDefined()
    expect(downloadCsv).toHaveBeenCalledTimes(1)
    expect((state as any).defectFailure.value.message).toContain('CSV 下载')
    expect(state.defectSelection.value).toHaveLength(1)
    const timestamp = (state as any).defectRecoveryCandidate.value.drafts[0].updatedAt
    f.fail(false)
    f.storage.setItem(DEFECT_DRAFT_STORAGE_KEY, JSON.stringify([{ ...draft(), status: 'created_in_jira', jiraIssueKey: 'J-9', jiraExportMode: 'api', summary: 'New metadata', future: false }, draft('other')]))
    await (state as any).retryDefectMutation()
    expect(downloadCsv).toHaveBeenCalledTimes(1)
    expect(state.defectDrafts.value.find(d => d.defectId === 'd1')).toMatchObject({ summary: 'New metadata', status: 'created_in_jira', jiraIssueKey: 'J-9', jiraExportMode: 'api', future: false, updatedAt: timestamp })
    expect(state.defectDrafts.value.find(d => d.defectId === 'other')?.status).toBe('pending_jira_export')
  })
  it.each([
    ['unavailable', '本地存储不可用'],
    ['corrupt-store', '存储内容损坏'],
    ['incompatible-version', '证据版本暂不支持']
  ] as const)('handles CSV pre-read %s before confirmation without download, write or lost selection', async (code, reason) => {
    const f = fixture()
    const { useDefects } = await import('./useDefects')
    const { downloadCsv } = await import('../domain/csvExport')
    const confirm = vi.fn(async () => true)
    const state = useDefects({ storage: f.storage, confirmDanger: confirm })
    state.defectSelection.value = [...state.defectDrafts.value]
    const selected = [...state.defectSelection.value]
    if (code === 'corrupt-store') f.data.set(DEFECT_DRAFT_STORAGE_KEY, '{sensitive-raw')
    if (code === 'incompatible-version') f.data.set(DEFECT_DRAFT_STORAGE_KEY, JSON.stringify([{ ...draft(), evidenceVersion: 2 }]))
    const raw = f.data.get(DEFECT_DRAFT_STORAGE_KEY)
    const read = vi.spyOn(f.storage, 'getItem')
    if (code === 'unavailable') read.mockImplementation(() => { throw new DOMException('denied', 'SecurityError') })
    const write = vi.spyOn(f.storage, 'setItem')
    const remove = vi.spyOn(f.storage, 'removeItem')
    const clear = vi.spyOn(f.storage, 'clear')

    await expect(state.exportSelectedDefectsToJiraCsv()).resolves.toBe(false)

    expect(confirm).not.toHaveBeenCalled()
    expect(downloadCsv).not.toHaveBeenCalled()
    expect(write).not.toHaveBeenCalled()
    expect(remove).not.toHaveBeenCalled()
    expect(clear).not.toHaveBeenCalled()
    expect(f.data.get(DEFECT_DRAFT_STORAGE_KEY)).toBe(raw)
    expect(state.defectSelection.value).toEqual(selected)
    expect(state.defectDrafts.value).toEqual(selected)
    expect(state.defectRecoveryCandidate.value).toBeNull()
    expect(state.defectFailure.value).toMatchObject({ code, outcome: 'confirmed-no-write' })
    expect(state.defectFailure.value?.message).toContain(reason)
    expect(state.defectFailure.value?.message).not.toContain('sensitive-raw')
    expect(state.defectReadBlocked.value).toBe(true)
    expect(state.defectRawStore.value).toBe(code === 'unavailable' ? null : raw)
  })
  it('exports a valid CSV once, marks only its selected row and clears selection after verified save', async () => {
    const f = fixture()
    f.data.set(DEFECT_DRAFT_STORAGE_KEY, JSON.stringify([draft(), draft('other')]))
    const { useDefects } = await import('./useDefects')
    const { downloadCsv } = await import('../domain/csvExport')
    const confirm = vi.fn(async () => true)
    const state = useDefects({ storage: f.storage, confirmDanger: confirm })
    state.defectSelection.value = [state.defectDrafts.value.find(d => d.defectId === 'd1')!]
    const write = vi.spyOn(f.storage, 'setItem')

    await expect(state.exportSelectedDefectsToJiraCsv()).resolves.toBe(true)

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(downloadCsv).toHaveBeenCalledTimes(1)
    expect(downloadCsv).toHaveBeenCalledWith('Fixture-jira-defects.csv', expect.stringContaining('Original'))
    expect(write).toHaveBeenCalledTimes(1)
    const saved = JSON.parse(f.data.get(DEFECT_DRAFT_STORAGE_KEY)!) as DefectDraft[]
    expect(saved.find(d => d.defectId === 'd1')).toMatchObject({ status: 'exported_jira_csv', jiraExportMode: 'csv' })
    expect(saved.find(d => d.defectId === 'other')).toEqual(draft('other'))
    expect(state.defectSelection.value).toEqual([])
    expect(state.defectFailure.value).toBeNull()
  })
  it('does no further storage read, write or download after CSV confirmation is cancelled', async () => {
    const f = fixture()
    const { useDefects } = await import('./useDefects')
    const { downloadCsv } = await import('../domain/csvExport')
    let cancel!: (value: boolean) => void
    const state = useDefects({ storage: f.storage, confirmDanger: () => new Promise(resolve => { cancel = resolve }) })
    state.defectSelection.value = [...state.defectDrafts.value]
    const selected = [...state.defectSelection.value]
    const raw = f.data.get(DEFECT_DRAFT_STORAGE_KEY)
    const read = vi.spyOn(f.storage, 'getItem')
    const write = vi.spyOn(f.storage, 'setItem')
    const pending = state.exportSelectedDefectsToJiraCsv()
    expect(read).toHaveBeenCalledTimes(1) // The baseline pre-read must fail before opening confirmation.
    read.mockClear()
    cancel(false)

    await expect(pending).resolves.toBe(false)

    expect(read).not.toHaveBeenCalled()
    expect(write).not.toHaveBeenCalled()
    expect(downloadCsv).not.toHaveBeenCalled()
    expect(f.data.get(DEFECT_DRAFT_STORAGE_KEY)).toBe(raw)
    expect(state.defectSelection.value).toEqual(selected)
    expect(state.defectFailure.value).toBeNull()
    expect(state.defectRecoveryCandidate.value).toBeNull()
  })
  it('catches CSV template save errors and never publishes unverified config', async () => {
    const f = fixture()
    const { useDefects } = await import('./useDefects')
    const state = useDefects({ storage: f.storage, confirmDanger: async () => true })
    state.jiraTemplateVisible.value = true
    state.updateJiraCsvTemplateField('summary', { header: 'Staged' })
    f.fail(true)
    expect(() => state.saveJiraCsvTemplateConfig()).not.toThrow()
    expect(state.jiraTemplateVisible.value).toBe(true)
    expect(state.jiraCsvTemplate.value[0].header).not.toBe('Staged')
    expect((state as any).defectFailure.value.outcome).toBe('confirmed-no-write')
    f.fail(false)
    expect(state.saveJiraCsvTemplateConfig()).not.toBe(false)
    expect(state.jiraCsvTemplate.value[0].header).toBe('Staged')
  })
  it('confirms candidate replacement and preserves the previous revision when declined', async () => {
    const f = fixture()
    const { useDefects } = await import('./useDefects')
    const confirm = vi.fn(async () => false)
    const state = useDefects({ storage: f.storage, confirmDanger: confirm })
    state.openDefectEdit(state.defectDrafts.value[0]); state.defectEditForm.summary = 'First'; f.fail(true); state.saveDefectEdit()
    const first = (state as any).defectRecoveryCandidate.value
    state.defectEditForm.summary = 'Second'
    state.saveDefectEdit()
    await Promise.resolve(); await Promise.resolve()
    expect(confirm).toHaveBeenCalledTimes(1)
    expect((state as any).defectRecoveryCandidate.value).toBe(first)
    expect((state as any).defectRecoveryCandidate.value.drafts[0].summary).toBe('First')
  })
  it('protects dirty candidate on leave/discard and invalidates outstanding confirmations on reset', async () => {
    const f = fixture()
    const { useDefects } = await import('./useDefects')
    let resolve!: (value: boolean) => void
    const state = useDefects({ storage: f.storage, confirmDanger: () => new Promise(r => { resolve = r }) })
    state.openDefectEdit(state.defectDrafts.value[0]); state.defectEditForm.summary = 'Unsaved'; f.fail(true); state.saveDefectEdit()
    expect((state as any).defectRecoveryDirty?.value).toBe(true)
    const leaving = (state as any).confirmDiscardDefectChanges()
    resolve(false)
    expect(await leaving).toBe(false)
    expect((state as any).defectRecoveryDirty.value).toBe(true)
    const discarding = (state as any).discardDefectRecoveryCandidate()
    ;(state as any).resetDefectRecovery(); resolve(true); await discarding
    expect((state as any).defectRecoveryCandidate.value).toBeNull()
    expect((state as any).defectFailure.value).toBeNull()
  })
  it('reports initial read failure without crashing and preserves last valid collection on refresh failure', async () => {
    const f = fixture()
    const { useDefects } = await import('./useDefects')
    const read = f.storage.getItem
    f.storage.getItem = () => { throw new DOMException('denied', 'SecurityError') }
    let state!: ReturnType<typeof useDefects>
    expect(() => { state = useDefects({ storage: f.storage }) }).not.toThrow()
    expect((state as any).defectFailure.value.code).toBe('unavailable')
    f.storage.getItem = read; state.refreshDefectDrafts()
    expect(state.defectDrafts.value).toHaveLength(1)
    f.storage.getItem = () => { throw Error('denied') }
    expect(() => state.refreshDefectDrafts()).not.toThrow()
    expect(state.defectDrafts.value).toHaveLength(1)
  })
  it('catches metadata update quota and reports missing targets without resurrecting them', async () => {
    const f = fixture(); const { useDefects } = await import('./useDefects')
    const state = useDefects({ storage: f.storage }); f.fail(true)
    expect(() => state.updateDefectField(state.defectDrafts.value[0], { priority: 'Low' })).not.toThrow()
    expect(state.defectDrafts.value[0].priority).toBe('High')
    expect((state as any).defectFailure.value.outcome).toBe('confirmed-no-write')
    f.fail(false); f.storage.setItem(DEFECT_DRAFT_STORAGE_KEY, '[]')
    await (state as any).retryDefectMutation()
    expect(f.storage.getItem(DEFECT_DRAFT_STORAGE_KEY)).toBe('[]')
    expect((state as any).defectFailure.value.code).toBe('missing-target')
  })
  it('reconciles uncertain baseline before an explicit second retry and refuses changed CSV targets while uncertain', async () => {
    const f = fixture(); const { useDefects } = await import('./useDefects')
    const state = useDefects({ storage: f.storage, confirmDanger: async () => true })
    const originalSet = f.storage.setItem
    let writes = 0
    f.storage.setItem = () => { writes++; throw Error('unknown write failure') }
    state.openDefectEdit(state.defectDrafts.value[0]); state.defectEditForm.summary = 'Candidate'; state.saveDefectEdit()
    expect((state as any).defectFailure.value.outcome).toBe('uncertain-commit')
    f.storage.setItem = (k, v) => { writes++; originalSet(k, v) }
    await (state as any).retryDefectMutation()
    expect(writes).toBe(1)
    expect((state as any).defectFailure.value.code).toBe('reconciled-baseline')
    await (state as any).retryDefectMutation()
    expect(writes).toBe(2)
    expect(state.defectEditVisible.value).toBe(false)
  })
  it.each(['cancel-edit', 'close-detail', 'delete-template-field', 'reset-template'] as const)('does not publish stale %s confirmation after reset', async action => {
    const f = fixture(); const { useDefects } = await import('./useDefects')
    let accept!: (value: boolean) => void
    const state = useDefects({ storage: f.storage, confirmDanger: () => new Promise(resolve => { accept = resolve }) })
    state.openDefectEdit(state.defectDrafts.value[0]); state.defectEditForm.summary = 'Unsaved'; state.jiraTemplateVisible.value = true
    state.updateJiraCsvTemplateField('summary', { header: 'Custom staged header' })
    const pending = action === 'cancel-edit' ? state.cancelDefectEdit() : action === 'close-detail' ? state.closeDefectDetail() : action === 'reset-template' ? state.resetJiraCsvTemplateConfig() : state.deleteJiraCsvTemplateField(state.jiraCsvTemplateDraft.value[0])
    ;(state as any).resetDefectRecovery()
    const form = JSON.stringify(state.defectEditForm), fields = JSON.stringify(state.jiraCsvTemplateDraft.value), detail = state.detailDefectId.value
    accept(true); await pending
    expect(JSON.stringify(state.defectEditForm)).toBe(form)
    expect(JSON.stringify(state.jiraCsvTemplateDraft.value)).toBe(fields)
    expect(state.detailDefectId.value).toBe(detail)
    expect(state.defectEditVisible.value).toBe(false) // reset itself closes account-scoped edits; stale callback must not reopen them
  })
  it('exports corrupt store raw text without writing and keeps writes blocked', async () => {
    const f = fixture(); f.data.set(DEFECT_DRAFT_STORAGE_KEY, '{broken')
    const { useDefects } = await import('./useDefects'); const download = vi.fn()
    const state = useDefects({ storage: f.storage, downloadTextFile: download })
    expect((state as any).defectReadBlocked?.value).toBe(true)
    expect((state as any).defectRawStore?.value).toBe('{broken')
    await (state as any).exportDefectRawStore()
    expect(download).toHaveBeenCalledWith('defect-store-original.txt', '{broken', 'text/plain')
    expect(f.data.get(DEFECT_DRAFT_STORAGE_KEY)).toBe('{broken')
  })
  it('keeps frozen CSV recovery JSON unchanged when retry merges newer metadata', async () => {
    const f = fixture(); const { useDefects } = await import('./useDefects'); const download = vi.fn()
    const state = useDefects({ storage: f.storage, confirmDanger: async () => true, downloadTextFile: download })
    f.fail(true); await state.exportSelectedDefectsToJiraCsv()
    const frozen = JSON.stringify((state as any).defectRecoveryCandidate.value.drafts)
    f.fail(false); f.storage.setItem(DEFECT_DRAFT_STORAGE_KEY, JSON.stringify([{ ...draft(), summary: 'Latest' }]))
    f.fail(true); await (state as any).retryDefectMutation()
    expect(JSON.stringify((state as any).defectRecoveryCandidate.value.drafts)).toBe(frozen)
    await (state as any).exportDefectRecoveryCandidate()
    expect(JSON.stringify(JSON.parse(download.mock.calls[0][1]).drafts)).toBe(frozen)
  })
  it('exports the full cross-page filtered scope as JSON without storage or selection side effects', async () => {
    const f = fixture()
    const records = Array.from({ length: 8 }, (_, index) => ({ ...draft(`d-${index + 1}`), summary: `Export ${index + 1}` }))
    f.data.set(DEFECT_DRAFT_STORAGE_KEY, JSON.stringify(records))
    const { useDefects } = await import('./useDefects')
    const download = vi.fn()
    const state = useDefects({ storage: f.storage, downloadTextFile: download })
    state.defectFilter.keyword = 'Export'
    state.defectSelection.value = [state.defectDrafts.value[0]]
    const beforeStorage = f.data.get(DEFECT_DRAFT_STORAGE_KEY)
    const beforeSelection = state.defectSelection.value.map(item => item.defectId)
    const beforeStatuses = state.defectDrafts.value.map(item => [item.defectId, item.status])
    await expect((state as any).exportDefectListJson()).resolves.toBe(true)
    expect(download).toHaveBeenCalledTimes(1)
    expect(download.mock.calls[0][0]).toBe('缺陷草稿列表.json')
    const artifact = JSON.parse(download.mock.calls[0][1])
    expect(artifact.scope).toBe('selected')
    expect(artifact.drafts).toHaveLength(1)
    expect(artifact.drafts[0].defectId).toBe('d-1')
    expect(f.data.get(DEFECT_DRAFT_STORAGE_KEY)).toBe(beforeStorage)
    expect(state.defectSelection.value.map(item => item.defectId)).toEqual(beforeSelection)
    expect(state.defectDrafts.value.map(item => [item.defectId, item.status])).toEqual(beforeStatuses)
  })
  it('exports all filtered drafts across pages when no draft is selected', async () => {
    const f = fixture()
    const records = Array.from({ length: 8 }, (_, index) => ({ ...draft(`f-${index + 1}`), summary: `Filtered ${index + 1}` }))
    f.data.set(DEFECT_DRAFT_STORAGE_KEY, JSON.stringify(records))
    const { useDefects } = await import('./useDefects')
    const download = vi.fn()
    const state = useDefects({ storage: f.storage, downloadTextFile: download })
    state.defectFilter.keyword = 'Filtered'
    state.defectPage.value = 2
    state.defectSelection.value = []
    await expect((state as any).exportDefectListJson()).resolves.toBe(true)
    const artifact = JSON.parse(download.mock.calls[0][1])
    expect(artifact.scope).toBe('filtered')
    expect(artifact.drafts).toHaveLength(8)
    expect(artifact.drafts.map((item: { defectId: string }) => item.defectId)).toEqual(records.map(item => item.defectId))
    expect(state.defectPage.value).toBe(2)
    expect(state.defectSelection.value).toEqual([])
  })
  it('does not trigger an injected download after the owner authority expires', async () => {
    const f = fixture()
    const { useDefects } = await import('./useDefects')
    const download = vi.fn()
    let owner = 'a'
    const state = useDefects({
      storage: f.storage,
      downloadTextFile: download,
      canTriggerDownload: isCurrent => { owner = 'b'; return isCurrent() && owner === 'a' },
      captureMutation: current => {
        const captured = owner
        return { storage: f.storage, isCurrent: () => current() && captured === owner }
      }
    })
    await expect((state as any).exportDefectListJson()).resolves.toBe(false)
    expect(download).not.toHaveBeenCalled()
  })
  it('rejects undefined and accessor additive evidence on the real export path before download', async () => {
    const f = fixture()
    const stored = { ...draft(), future: undefined } as DefectDraft & { future?: unknown }
    f.data.set(DEFECT_DRAFT_STORAGE_KEY, JSON.stringify([stored]))
    const { useDefects } = await import('./useDefects')
    const download = vi.fn()
    const state = useDefects({ storage: f.storage, downloadTextFile: download })
    const invalid = state.defectDrafts.value[0] as DefectDraft & { future?: unknown }
    Object.defineProperty(invalid, 'future', { enumerable: true, get: () => 'getter-value' })
    state.defectSelection.value = [invalid]
    await expect((state as any).exportDefectListJson()).resolves.toBe(false)
    expect(download).not.toHaveBeenCalled()
    expect((state as any).defectFailure.value.code).toBe('invalid-data')
  })
  it('keeps selected drafts across filter changes so selected scope remains authoritative', async () => {
    const f = fixture()
    const records = [draft('chosen'), { ...draft('other'), summary: 'Other summary' }]
    f.data.set(DEFECT_DRAFT_STORAGE_KEY, JSON.stringify(records))
    const { useDefects } = await import('./useDefects')
    const download = vi.fn()
    const state = useDefects({ storage: f.storage, downloadTextFile: download })
    state.defectSelection.value = [state.defectDrafts.value[0]]
    state.defectFilter.keyword = 'Other summary'
    await Promise.resolve()
    await expect((state as any).exportDefectListJson()).resolves.toBe(true)
    const artifact = JSON.parse(download.mock.calls[0][1])
    expect(artifact.scope).toBe('selected')
    expect(artifact.drafts.map((item: { defectId: string }) => item.defectId)).toEqual(['chosen'])
  })
  it('previews and imports a non-conflicting recovery package only after explicit confirmation', async () => {
    const f = fixture()
    const { useDefects } = await import('./useDefects')
    const state = useDefects({ storage: f.storage, confirmDanger: async () => true })
    const incoming = { ...draft('incoming'), summary: 'Imported' }
    const text = JSON.stringify({ kind: 'ai-test-platform.defect-recovery', version: 1, drafts: [incoming] })
    expect(await (state as any).importDefectJsonText(text)).toBe(true)
    expect((state as any).defectImportPreview.value.drafts).toHaveLength(1)
    expect(JSON.parse(f.data.get(DEFECT_DRAFT_STORAGE_KEY)!)).toHaveLength(1)
    expect(await (state as any).confirmDefectImport()).toBe(true)
    expect(JSON.parse(f.data.get(DEFECT_DRAFT_STORAGE_KEY)!)).toHaveLength(2)
  })
  it('imports into empty storage and preserves a pretty-printed unchanged baseline', async () => {
    const f = fixture(); f.data.delete(DEFECT_DRAFT_STORAGE_KEY)
    const { useDefects } = await import('./useDefects')
    const state = useDefects({ storage: f.storage, confirmDanger: async () => true })
    const incoming = { ...draft('empty-incoming') }
    await (state as any).importDefectJsonText(JSON.stringify({ kind: 'ai-test-platform.defect-recovery', version: 1, drafts: [incoming] }))
    expect(await (state as any).confirmDefectImport()).toBe(true)
    expect(JSON.parse(f.data.get(DEFECT_DRAFT_STORAGE_KEY)!)).toHaveLength(1)

    const pretty = JSON.stringify([draft()], null, 2)
    f.data.set(DEFECT_DRAFT_STORAGE_KEY, pretty)
    const state2 = useDefects({ storage: f.storage, confirmDanger: async () => true })
    const incoming2 = { ...draft('pretty-incoming') }
    await (state2 as any).importDefectJsonText(JSON.stringify({ kind: 'ai-test-platform.defect-recovery', version: 1, drafts: [incoming2] }))
    expect(await (state2 as any).confirmDefectImport()).toBe(true)
    expect(JSON.parse(f.data.get(DEFECT_DRAFT_STORAGE_KEY)!)).toHaveLength(2)
  })
  it('blocks import preview confirmation on an existing conflicting id without writing', async () => {
    const f = fixture()
    const { useDefects } = await import('./useDefects')
    const state = useDefects({ storage: f.storage, confirmDanger: async () => true })
    const incoming = { ...draft('d1'), summary: 'Conflicting' }
    const before = f.data.get(DEFECT_DRAFT_STORAGE_KEY)
    expect(await (state as any).importDefectJsonText(JSON.stringify({ kind: 'ai-test-platform.defect-recovery', version: 1, drafts: [incoming] }))).toBe(true)
    expect((state as any).defectImportPreview.value.conflicts).toHaveLength(1)
    expect(await (state as any).confirmDefectImport()).toBe(false)
    expect(f.data.get(DEFECT_DRAFT_STORAGE_KEY)).toBe(before)
  })
  it('marks even identical existing ids as preview conflicts', async () => {
    const f = fixture()
    const { useDefects } = await import('./useDefects')
    const state = useDefects({ storage: f.storage, confirmDanger: async () => true })
    const existing = JSON.parse(f.data.get(DEFECT_DRAFT_STORAGE_KEY)!)[0]
    await (state as any).importDefectJsonText(JSON.stringify({ kind: 'ai-test-platform.defect-recovery', version: 1, drafts: [existing] }))
    expect((state as any).defectImportPreview.value.conflicts).toHaveLength(1)
    expect(await (state as any).confirmDefectImport()).toBe(false)
  })
  it('rejects source switching during import confirmation and preserves an existing recovery failure', async () => {
    const f = fixture()
    const { useDefects } = await import('./useDefects')
    let accept!: (value: boolean) => void
    const state = useDefects({ storage: f.storage, confirmDanger: () => new Promise(resolve => { accept = resolve }) })
    const incoming = { ...draft('incoming'), summary: 'Imported' }
    await (state as any).importDefectJsonText(JSON.stringify({ kind: 'ai-test-platform.defect-recovery', version: 1, drafts: [incoming] }))
    const pending = (state as any).confirmDefectImport()
    await state.switchDefectSource('jira')
    await state.switchDefectSource('draft')
    accept(true)
    await pending
    expect(f.data.get(DEFECT_DRAFT_STORAGE_KEY)).toContain('d1')
    expect(JSON.parse(f.data.get(DEFECT_DRAFT_STORAGE_KEY)!)).toHaveLength(1)
  })
  it('does not overwrite newer unknown/Jira metadata from an outdated edit snapshot', async () => {
    const f = fixture(); const { useDefects } = await import('./useDefects')
    const state = useDefects({ storage: f.storage })
    state.openDefectEdit(state.defectDrafts.value[0]); state.defectEditForm.summary = 'Unsaved'
    f.storage.setItem(DEFECT_DRAFT_STORAGE_KEY, JSON.stringify([{ ...draft(), jiraIssueKey: 'NEW-1', future: { x: false } }]))
    state.refreshDefectDrafts()
    state.saveDefectEdit()
    expect(JSON.parse(f.storage.getItem(DEFECT_DRAFT_STORAGE_KEY)!)[0].jiraIssueKey).toBe('NEW-1')
    expect((state as any).defectFailure.value.code).toBe('conflict')
  })
  it('reconciles CSV-template unknown commit before retry without another template write', async () => {
    const f = fixture(); const { useDefects } = await import('./useDefects')
    const state = useDefects({ storage: f.storage }); state.jiraTemplateVisible.value = true; state.updateJiraCsvTemplateField('summary', { header: 'Frozen' })
    const set = f.storage.setItem, get = f.storage.getItem; let writes = 0, failRead = false
    f.storage.setItem = (k, v) => { writes++; set(k, v); failRead = true }
    f.storage.getItem = k => { if (failRead) throw Error('readback unavailable'); return get(k) }
    expect(state.saveJiraCsvTemplateConfig()).toBe(false)
    expect((state as any).defectFailure.value.outcome).toBe('uncertain-commit')
    failRead = false
    await (state as any).retryDefectMutation()
    expect(writes).toBe(1)
    expect(state.jiraTemplateVisible.value).toBe(false)
    expect(state.jiraCsvTemplate.value[0].header).toBe('Frozen')
  })
  it.each(['delete-template-field', 'reset-template'] as const)('catches %s confirmation rejection without an unhandled promise', async action => {
    const f = fixture(); const { useDefects } = await import('./useDefects')
    const state = useDefects({ storage: f.storage, confirmDanger: async () => { throw Error('dialog failed') } })
    state.jiraTemplateVisible.value = true
    const pending = action === 'reset-template' ? state.resetJiraCsvTemplateConfig() : state.deleteJiraCsvTemplateField(state.jiraCsvTemplateDraft.value[0])
    await expect(pending).resolves.toBeUndefined()
    expect((state as any).defectFailure.value.outcome).toBe('confirmed-no-write')
  })
  it('catches candidate download failure, retains dirty/revision, and rejects a stale export capability', async () => {
    const f = fixture(); const { useDefects } = await import('./useDefects'); let owner = 'a'; const download = vi.fn(() => { throw Error('download failed') })
    const state = useDefects({ storage: f.storage, downloadTextFile: download, captureMutation: current => { const captured = owner; return { storage: f.storage, isCurrent: () => current() && captured === owner } } })
    state.openDefectEdit(state.defectDrafts.value[0]); state.defectEditForm.summary = 'Candidate'; f.fail(true); state.saveDefectEdit()
    const candidate = (state as any).defectRecoveryCandidate.value
    await (state as any).exportDefectRecoveryCandidate()
    expect((state as any).defectFailure.value.code).toBe('download-failed')
    expect((state as any).defectRecoveryCandidate.value).toBe(candidate)
    expect(state.defectEditDirty.value).toBe(true)
    owner = 'b'; await (state as any).exportDefectRecoveryCandidate(); await (state as any).retryDefectMutation()
    expect(download).toHaveBeenCalledTimes(1)
    expect(state.defectDrafts.value[0].summary).toBe('Original')
  })
  it('keeps an uncertain template candidate fixed and reconciles repeated Apply instead of rewriting', async () => {
    const f = fixture(); const { useDefects } = await import('./useDefects')
    const state = useDefects({ storage: f.storage }); state.jiraTemplateVisible.value = true; state.updateJiraCsvTemplateField('summary', { header: 'Frozen' })
    const set = f.storage.setItem, get = f.storage.getItem; let writes = 0, failRead = false
    f.storage.setItem = (k, v) => { writes++; set(k, v); failRead = true }
    f.storage.getItem = k => { if (failRead) throw Error('readback unavailable'); return get(k) }
    state.saveJiraCsvTemplateConfig(); failRead = false
    state.saveJiraCsvTemplateConfig(); await Promise.resolve(); await Promise.resolve()
    expect(writes).toBe(1)
    expect(state.jiraTemplateVisible.value).toBe(false)
  })
  it('repeated Save on the unchanged failed revision reconciles instead of asking to replace it', async () => {
    const f = fixture(); const { useDefects } = await import('./useDefects'); const confirm = vi.fn(async () => true)
    const state = useDefects({ storage: f.storage, confirmDanger: confirm })
    state.openDefectEdit(state.defectDrafts.value[0]); state.defectEditForm.summary = 'Candidate'; f.fail(true); state.saveDefectEdit()
    const candidate = (state as any).defectRecoveryCandidate.value
    f.fail(false); state.saveDefectEdit(); await Promise.resolve()
    expect(confirm).not.toHaveBeenCalled()
    expect(state.defectDrafts.value[0]).toEqual(candidate.drafts[0])
  })
  it('confirms replacement before a second CSV download and retains the old candidate when cancelled', async () => {
    const f = fixture(); const { useDefects } = await import('./useDefects'); const { downloadCsv } = await import('../domain/csvExport')
    const confirm = vi.fn(async (title: string) => !title.includes('上次失败候选'))
    const state = useDefects({ storage: f.storage, confirmDanger: confirm })
    state.openDefectEdit(state.defectDrafts.value[0]); state.defectEditForm.summary = 'Candidate'; f.fail(true); state.saveDefectEdit()
    const candidate = (state as any).defectRecoveryCandidate.value
    await state.exportSelectedDefectsToJiraCsv(); await Promise.resolve()
    expect(downloadCsv).not.toHaveBeenCalled()
    expect((state as any).defectRecoveryCandidate.value).toBe(candidate)
  })
  it('does not accept void navigation with no observable target as completed', async () => {
    const f = fixture(); const { useDefects } = await import('./useDefects')
    const state = useDefects({ storage: f.storage, navigateToDefects: () => {} })
    state.createDefectsFromWebResults([{ caseId: 'w1', sourceCaseId: 'w1', module: '', name: 'Web', pageName: '', status: 'failed' }], 'Fixture')
    await Promise.resolve()
    expect((state as any).defectFailure.value?.outcome).toBe('committed-navigation-failed')
  })
  it('retains full deleted target records in failure JSON and cleans up a prepared URL if authority expires before click', async () => {
    const f = fixture(); const { useDefects } = await import('./useDefects'); let owner = 'a'
    const state = useDefects({ storage: f.storage, confirmDanger: async () => true, captureMutation: current => { const captured = owner; return { storage: f.storage, isCurrent: () => current() && captured === owner } } })
    f.fail(true); await state.removeDefect(state.defectDrafts.value[0])
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const created: Blob[] = []
    const create = vi.fn((blob: Blob) => { created.push(blob); owner = 'b'; return 'blob:fixture' })
    const revoke = vi.fn(); vi.stubGlobal('URL', { createObjectURL: create, revokeObjectURL: revoke })
    expect(await (state as any).exportDefectRecoveryCandidate()).toBe(false)
    expect(click).not.toHaveBeenCalled(); expect(revoke).toHaveBeenCalledWith('blob:fixture')
    owner = 'a'; const download = vi.fn()
    vi.unstubAllGlobals(); click.mockRestore()
    const state2 = useDefects({ storage: f.storage, confirmDanger: async () => true, downloadTextFile: download })
    await state2.removeDefect(state2.defectDrafts.value[0]); await (state2 as any).exportDefectRecoveryCandidate()
    expect(JSON.parse(download.mock.calls[0][1]).drafts[0]).toEqual(draft())
  })
  it('clears account-scoped detail, pending template and raw store state on reset', async () => {
    const f = fixture(); const { useDefects } = await import('./useDefects')
    const state = useDefects({ storage: f.storage }); state.openDefectEdit(state.defectDrafts.value[0]); state.defectEditForm.summary = 'Unsaved'
    ;(state as any).resetDefectRecovery()
    expect(state.detailDefectId.value).toBeNull()
    expect(state.editingDefect.value).toBeUndefined()
    expect(state.defectEditVisible.value).toBe(false)
    expect((state as any).defectRecoveryDirty.value).toBe(false)
  })
  it('explains quota and unavailable/corrupt-store causes without leaking raw payload into the banner', async () => {
    const f = fixture(); const { useDefects } = await import('./useDefects')
    const state = useDefects({ storage: f.storage }); state.openDefectEdit(state.defectDrafts.value[0]); state.defectEditForm.summary = 'Candidate'; f.fail(true); state.saveDefectEdit()
    expect((state as any).defectFailure.value.message).toContain('空间不足')
    const f2 = fixture(); f2.data.set(DEFECT_DRAFT_STORAGE_KEY, '{sensitive-raw')
    const state2 = useDefects({ storage: f2.storage })
    expect((state2 as any).defectFailure.value.message).toContain('读取失败')
    expect((state2 as any).defectFailure.value.message).not.toContain('sensitive-raw')
  })
  it('keeps later edits dirty after reconciling only the frozen committed revision', async () => {
    const f = fixture(); const { useDefects } = await import('./useDefects')
    const state = useDefects({ storage: f.storage }); const set = f.storage.setItem, get = f.storage.getItem; let failRead = false
    f.storage.setItem = (k, v) => { set(k, v); failRead = true }; f.storage.getItem = k => { if (failRead) throw Error('readback failed'); return get(k) }
    state.openDefectEdit(state.defectDrafts.value[0]); state.defectEditForm.summary = 'Frozen'; state.saveDefectEdit()
    state.defectEditForm.summary = 'Later unsaved'; failRead = false
    await state.retryDefectMutation()
    expect(state.defectDrafts.value[0].summary).toBe('Frozen')
    expect(state.defectEditVisible.value).toBe(true)
    expect(state.defectEditForm.summary).toBe('Later unsaved')
    expect(state.defectEditDirty.value).toBe(true)
    expect(state.defectRecoveryCandidate.value).toBeNull()
  })
  it('does not merge a changed CSV target after an uncertain mark, and never redownloads', async () => {
    const f = fixture(); const { useDefects } = await import('./useDefects'); const { downloadCsv } = await import('../domain/csvExport')
    const state = useDefects({ storage: f.storage, confirmDanger: async () => true }); const set = f.storage.setItem
    f.storage.setItem = () => { throw Error('unknown write failure') }
    await state.exportSelectedDefectsToJiraCsv()
    set(DEFECT_DRAFT_STORAGE_KEY, JSON.stringify([{ ...draft(), jiraIssueKey: 'NEW-1', future: { x: false } }]))
    const raw = f.storage.getItem(DEFECT_DRAFT_STORAGE_KEY)
    await state.retryDefectMutation()
    expect(state.defectFailure.value?.code).toBe('conflict')
    expect(f.storage.getItem(DEFECT_DRAFT_STORAGE_KEY)).toBe(raw)
    expect(downloadCsv).toHaveBeenCalledTimes(1)
  })
  it('handles API create quota with a frozen full candidate and no navigation', async () => {
    const f = fixture(); const { useDefects } = await import('./useDefects'); const navigate = vi.fn()
    const state = useDefects({ storage: f.storage, navigateToDefects: navigate }); f.fail(true)
    const result = { caseId: 'api1', sourceCaseId: 'api1', name: 'API', module: '', method: 'GET', path: '/fixture', status: 'failed', request: { url: '/fixture' }, errorMessage: 'failure' }
    expect(() => state.createDefectsFromResults([result] as any, 'Fixture')).not.toThrow()
    expect(state.defectFailure.value?.outcome).toBe('confirmed-no-write')
    expect(state.defectRecoveryCandidate.value?.drafts[0].defectId).toBe('defect-api1')
    expect(navigate).not.toHaveBeenCalled()
    expect(state.defectDrafts.value).toHaveLength(1)
  })
  it('patches metadata onto the latest row rather than replacing unknown or Jira keys from a stale caller', async () => {
    const f = fixture(); const { useDefects } = await import('./useDefects')
    const state = useDefects({ storage: f.storage }); const stale = state.defectDrafts.value[0]
    f.storage.setItem(DEFECT_DRAFT_STORAGE_KEY, JSON.stringify([{ ...draft(), jiraIssueKey: 'J-7', future: false }]))
    state.updateDefectField(stale, { priority: 'Low' })
    expect(JSON.parse(f.storage.getItem(DEFECT_DRAFT_STORAGE_KEY)!)[0]).toMatchObject({ priority: 'Low', jiraIssueKey: 'J-7', future: false })
  })
  it('clears a failed template candidate when confirmed leaving, while cancellation retains it', async () => {
    const f = fixture(); const { useDefects } = await import('./useDefects'); let accept = false
    const state = useDefects({ storage: f.storage, confirmDanger: async () => accept }); state.jiraTemplateVisible.value = true; f.fail(true); state.saveJiraCsvTemplateConfig()
    expect(await state.confirmDiscardDefectChanges()).toBe(false)
    expect(state.defectTemplateCandidate.value).not.toBeNull()
    accept = true; expect(await state.confirmDiscardDefectChanges()).toBe(true)
    expect(state.defectTemplateCandidate.value).toBeNull()
    expect(state.defectRecoveryDirty.value).toBe(false)
  })
  it.each(['resolve', 'reject'] as const)('ignores stale navigation %s after owner replacement without UI or writes in the new workspace', async settlement => {
    const f = fixture(); const { useDefects } = await import('./useDefects'); let owner = 'a'
    let resolve!: () => void, reject!: () => void
    const navigation = new Promise<void>((yes, no) => { resolve = yes; reject = () => no(Error('navigation failed')) })
    const state = useDefects({ storage: f.storage, navigateToDefects: () => navigation, isDefectsActive: () => true, captureMutation: current => { const captured = owner; return { storage: f.storage, isCurrent: () => current() && owner === captured } } })
    state.createDefectsFromWebResults([{ caseId: 'w1', sourceCaseId: 'w1', module: '', name: 'Web', status: 'failed' }], 'Fixture')
    state.resetDefectRecovery(); owner = 'b'; state.defectDrafts.value = []
    const raw = f.storage.getItem(DEFECT_DRAFT_STORAGE_KEY)
    if (settlement === 'resolve') resolve(); else reject()
    await Promise.resolve(); await Promise.resolve()
    expect(state.defectFailure.value).toBeNull()
    expect(state.defectDrafts.value).toEqual([])
    expect(f.storage.getItem(DEFECT_DRAFT_STORAGE_KEY)).toBe(raw)
  })
  it('freezes new template fields before replacement confirmation and rejects a late confirmation after reset', async () => {
    const f = fixture(); const { useDefects } = await import('./useDefects'); let accept!: (value: boolean) => void
    const state = useDefects({ storage: f.storage, confirmDanger: () => new Promise(resolve => { accept = resolve }) })
    state.jiraTemplateVisible.value = true; state.updateJiraCsvTemplateField('summary', { header: 'First' }); f.fail(true); state.saveJiraCsvTemplateConfig()
    state.updateJiraCsvTemplateField('summary', { header: 'Frozen second' }); state.saveJiraCsvTemplateConfig()
    state.updateJiraCsvTemplateField('summary', { header: 'Later third' }); f.fail(false); accept(true); await Promise.resolve(); await Promise.resolve()
    expect(JSON.parse(f.storage.getItem('ai-test-platform.jira-csv-template.v1')!)[0].header).toBe('Frozen second')
  })
  it('catches edit quota, retaining the form, baseline, stored row and dirty state', async () => {
    const f = fixture()
    const { useDefects } = await import('./useDefects')
    const state = useDefects({ storage: f.storage, confirmDanger: async () => true })
    state.openDefectEdit(state.defectDrafts.value[0])
    state.defectEditForm.summary = 'Unsaved'
    const raw = f.storage.getItem(DEFECT_DRAFT_STORAGE_KEY)
    f.fail(true)
    expect(() => state.saveDefectEdit()).not.toThrow()
    expect(state.defectEditVisible.value).toBe(true)
    expect(state.defectEditDirty.value).toBe(true)
    expect(state.defectEditForm.summary).toBe('Unsaved')
    expect(state.defectEditError.value).toContain('未保存')
    expect(state.defectDrafts.value[0].summary).toBe('Original')
    expect(f.storage.getItem(DEFECT_DRAFT_STORAGE_KEY)).toBe(raw)
  })
  it('routes retry to the candidate that owns the failure when template and defect candidates coexist', async () => {
    const f = fixture(); const { useDefects } = await import('./useDefects')
    const state = useDefects({ storage: f.storage, confirmDanger: async () => true })
    state.jiraTemplateVisible.value = true; state.updateJiraCsvTemplateField('summary', { header: 'Template candidate' }); f.fail(true); state.saveJiraCsvTemplateConfig()
    state.openDefectEdit(state.defectDrafts.value[0]); state.defectEditForm.summary = 'Defect candidate'; state.saveDefectEdit()
    expect((state as any).defectRecoveryCandidate.value).not.toBeNull()
    expect((state as any).defectTemplateCandidate.value).not.toBeNull()
    f.fail(false)
    await (state as any).retryDefectMutation()
    expect(state.defectEditVisible.value).toBe(false)
    expect((state as any).defectRecoveryCandidate.value).toBeNull()
    expect((state as any).defectTemplateCandidate.value).not.toBeNull()
    expect(state.jiraTemplateVisible.value).toBe(true)
    expect(state.jiraCsvTemplate.value[0].header).toBe('概要')
    await (state as any).retryDefectMutation()
    expect(state.jiraTemplateVisible.value).toBe(false)
    expect((state as any).defectTemplateCandidate.value).toBeNull()
    expect(JSON.parse(f.storage.getItem(DEFECT_DRAFT_STORAGE_KEY)!)[0].summary).toBe('Defect candidate')
  })

  it('freezes the CSV batch before replacement confirmation and does not use later live selection or template', async () => {
    const f = fixture(); const { useDefects } = await import('./useDefects'); const { downloadCsv } = await import('../domain/csvExport')
    let accept!: (value: boolean) => void
    const state = useDefects({ storage: f.storage, confirmDanger: () => new Promise(resolve => { accept = resolve }) })
    state.defectSelection.value = [state.defectDrafts.value[0]]
    const pending = state.exportSelectedDefectsToJiraCsv()
    await Promise.resolve()
    state.defectDrafts.value[0].summary = 'Later live summary'
    state.jiraTemplateVisible.value = true
    state.jiraCsvTemplate.value[0].header = 'Later live header'
    accept(true)
    await pending
    expect(downloadCsv).toHaveBeenCalledTimes(1)
    const [filename, csv] = (downloadCsv as any).mock.calls[0]
    expect(filename).toBe('Fixture-jira-defects.csv')
    expect(csv).toContain('概要')
    expect(csv).not.toContain('Later live header')
    expect(csv).not.toContain('Later live summary')
  })

  it('keeps the frozen CSV content across replacement confirmation before the download confirmation', async () => {
    const f = fixture(); const { useDefects } = await import('./useDefects'); const { downloadCsv } = await import('../domain/csvExport')
    let resolveReplacement!: (value: boolean) => void
    let resolveExport!: (value: boolean) => void
    const confirm = vi.fn((title: string) => new Promise<boolean>(resolve => {
      if (title.includes('上次失败候选')) resolveReplacement = resolve
      else resolveExport = resolve
    }))
    const state = useDefects({ storage: f.storage, confirmDanger: confirm })
    state.openDefectEdit(state.defectDrafts.value[0]); state.defectEditForm.summary = 'Old candidate'; f.fail(true); state.saveDefectEdit()
    state.defectSelection.value = [state.defectDrafts.value[0]]
    const pending = state.exportSelectedDefectsToJiraCsv()
    await Promise.resolve()
    resolveReplacement(true)
    await Promise.resolve()
    state.jiraCsvTemplate.value[0].header = 'Changed after replacement confirmation'
    state.defectSelection.value = []
    state.defectDrafts.value[0].summary = 'Changed after replacement confirmation'
    resolveExport(true)
    await pending
    expect(downloadCsv).toHaveBeenCalledTimes(1)
    const csv = (downloadCsv as any).mock.calls[0][1] as string
    expect(csv).toContain('概要')
    expect(csv).not.toContain('Changed after replacement confirmation')
  })

  it('keeps a frozen edit candidate, exports it without writes, then retries without replacing unrelated rows', async () => {
    const f = fixture()
    const { useDefects } = await import('./useDefects')
    const downloaded: string[] = []
    const state = useDefects({ storage: f.storage, confirmDanger: async () => true, downloadTextFile: (_name: string, text: string) => { downloaded.push(text) } } as Parameters<typeof useDefects>[0])
    state.openDefectEdit(state.defectDrafts.value[0])
    state.defectEditForm.summary = 'Frozen candidate'
    f.fail(true)
    state.saveDefectEdit()
    expect((state as any).defectFailure?.value?.outcome).toBe('confirmed-no-write')
    const frozen = (state as any).defectRecoveryCandidate.value.drafts[0]
    await (state as any).exportDefectRecoveryCandidate()
    expect(JSON.parse(downloaded[0]).drafts[0]).toEqual(frozen)
    expect(state.defectEditDirty.value).toBe(true)
    f.fail(false)
    f.storage.setItem(DEFECT_DRAFT_STORAGE_KEY, JSON.stringify([draft(), { ...draft('other'), summary: 'Other tab' }]))
    await (state as any).retryDefectMutation()
    expect(state.defectDrafts.value.find(d => d.defectId === 'd1')).toEqual(frozen)
    expect(state.defectDrafts.value.find(d => d.defectId === 'other')?.summary).toBe('Other tab')
    expect(state.defectEditDirty.value).toBe(false)
    expect((state as any).defectRecoveryCandidate.value).toBeNull()
  })
})

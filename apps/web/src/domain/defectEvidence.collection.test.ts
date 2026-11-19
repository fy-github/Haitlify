import { describe, expect, it } from 'vitest'
import { buildDefectDraftsFromWebAutomationResults, saveDefectDrafts, listDefectDrafts, updateDefectDraft, DEFECT_DRAFT_STORAGE_KEY } from './defectRepository'
import { source, runner, options, TestStorage } from './defectEvidence.testFixtures'

const create = () => buildDefectDraftsFromWebAutomationResults([source], options)[0]
const capture = (fn: () => unknown) => { try { fn(); return undefined } catch (error) { return error } }

describe('defect collection codec blocks destructive reinterpretation', () => {
  it('preserves explicitly accepted historical flat JSON and mixed runner/flat source keys', () => {
    const flat = { stepId: '', name: '', action: 'click', target: '', status: 'failed', capturedAt: '', future: { false: false, zero: 0, empty: [], null: null } }
    const [draft] = buildDefectDraftsFromWebAutomationResults([{ ...source, stepEvidence: [flat] }], options, { evidenceFormat: 'legacy-flat' })
    const storage = new TestStorage(); saveDefectDrafts(storage, [draft])
    expect(listDefectDrafts(storage)[0].stepEvidence).toStrictEqual([flat])
    const mixed = { ...runner, ...flat }
    expect(buildDefectDraftsFromWebAutomationResults([{ ...source, stepEvidence: [mixed] }], options)[0].stepEvidence).toStrictEqual([mixed])
  })
  it('refuses malformed runner even at the explicit flat compatibility entry', () => {
    expect(() => buildDefectDraftsFromWebAutomationResults([{ ...source, stepEvidence: [{ name: '', before: {} }] } as unknown as typeof source], options, { evidenceFormat: 'legacy-flat' })).toThrowError(expect.objectContaining({ code: 'invalid-data' }))
  })
  it.each(['{', '', '{}', '[null]', '[{"defectId":"incomplete"}]'])('refuses corrupt raw %s and blocks save with zero writes', raw => {
    const storage = new TestStorage(); storage.data.set(DEFECT_DRAFT_STORAGE_KEY, raw)
    expect(capture(() => listDefectDrafts(storage))).toMatchObject({ code: 'corrupt-store', outcome: 'confirmed-no-write', raw })
    expect(capture(() => saveDefectDrafts(storage, [create()]))).toMatchObject({ code: 'corrupt-store', outcome: 'confirmed-no-write', operation: 'save' })
    expect(storage.writes).toBe(0); expect(storage.data.get(DEFECT_DRAFT_STORAGE_KEY)).toBe(raw)
  })
  it.each([2, '1', null, 0])('blocks unrecognized evidence version %s instead of treating as legacy', evidenceVersion => {
    const storage = new TestStorage(); const raw = JSON.stringify([{ ...create(), evidenceVersion }]); storage.data.set(DEFECT_DRAFT_STORAGE_KEY, raw)
    expect(capture(() => listDefectDrafts(storage))).toMatchObject({ code: 'incompatible-version', raw })
    expect(capture(() => updateDefectDraft(storage, create()))).toMatchObject({ code: 'incompatible-version', operation: 'update' })
    expect(storage.writes).toBe(0)
  })
  it('blocks corrupt v1 without silently dropping malformed evidence items', () => {
    const storage = new TestStorage(); const raw = JSON.stringify([{ ...create(), stepEvidence: [runner, null] }]); storage.data.set(DEFECT_DRAFT_STORAGE_KEY, raw)
    expect(capture(() => listDefectDrafts(storage))).toMatchObject({ code: 'corrupt-store', raw })
    expect(capture(() => saveDefectDrafts(storage, [create()]))).toMatchObject({ code: 'corrupt-store' }); expect(storage.writes).toBe(0)
  })
  it('reads and edits legacy projected/no-evidence data without upgrade, invented fields or unknown-key loss', () => {
    const storage = new TestStorage(); const legacy = create()
    delete legacy.evidenceVersion; delete legacy.stepEvidence; delete legacy.stepLogs; delete legacy.steps
    const originals = [{ ...legacy, defectId: 'none', unknown: { value: false } }, { ...legacy, defectId: 'projected', stepEvidence: [{ actionId: 'a', status: 'failed', pageUrl: '' }], unknown: 0 }]
    const raw = JSON.stringify(originals); storage.data.set(DEFECT_DRAFT_STORAGE_KEY, raw)
    const read = listDefectDrafts(storage)
    expect(storage.writes).toBe(0)
    expect(read).toHaveLength(2)
    for (const draft of read) updateDefectDraft(storage, { ...draft, summary: 'edit' })
    const after = JSON.parse(storage.data.get(DEFECT_DRAFT_STORAGE_KEY)!)
    expect(after).toStrictEqual(originals.map(draft => ({ ...draft, summary: 'edit' })))
  })
  it('distinguishes genuinely missing storage from read failure', () => {
    const storage = new TestStorage(); expect(listDefectDrafts(storage)).toStrictEqual([])
    storage.getItem = () => { throw new DOMException('blocked', 'SecurityError') }
    expect(capture(() => listDefectDrafts(storage))).toMatchObject({ code: 'unavailable', outcome: 'confirmed-no-write', operation: 'read' })
    expect(capture(() => saveDefectDrafts(storage, [create()]))).toMatchObject({ code: 'unavailable', operation: 'save' })
    expect(storage.writes).toBe(0)
  })
})

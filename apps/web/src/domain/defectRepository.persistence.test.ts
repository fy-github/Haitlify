import { describe, expect, it } from 'vitest'
import * as repo from './defectRepository'
import { source, options, TestStorage } from './defectEvidence.testFixtures'
const make = () => repo.buildDefectDraftsFromWebAutomationResults([source], options)[0]
const caught = (fn: () => unknown) => { try { fn(); return undefined } catch (error) { return error } }
const mutations = [
  ['save', (s: Storage, d: repo.DefectDraft) => repo.saveDefectDrafts(s, [{ ...d, summary: 'new' }])],
  ['update', (s: Storage, d: repo.DefectDraft) => repo.updateDefectDraft(s, { ...d, summary: 'new' })],
  ['delete', (s: Storage, d: repo.DefectDraft) => repo.deleteDefectDraft(s, d.defectId)],
  ['delete-many', (s: Storage, d: repo.DefectDraft) => repo.deleteDefectDrafts(s, [d.defectId])],
  ['mark-exported', (s: Storage, d: repo.DefectDraft) => repo.markDefectsAsExported(s, [d.defectId], 'csv')]
] as const

describe('honest single-key defect persistence', () => {
  it.each(mutations)('%s detects a silent no-op through exact readback', (operation, mutate) => {
    const s = new TestStorage(); const d = make(); s.data.set(repo.DEFECT_DRAFT_STORAGE_KEY, JSON.stringify([d]))
    s.setItem = () => { s.writes++ }
    expect(caught(() => mutate(s, d))).toMatchObject({ code: 'verify-failed', outcome: 'uncertain-commit', operation })
    expect(s.writes).toBe(1); expect(JSON.parse(s.data.get(repo.DEFECT_DRAFT_STORAGE_KEY)!)).toStrictEqual([d])
  })
  it.each(mutations)('%s confirms native Quota did not write, preserves all original raw and never retries', (operation, mutate) => {
    const s = new TestStorage(); const d = make(); const raw = JSON.stringify([d]); s.data.set(repo.DEFECT_DRAFT_STORAGE_KEY, raw)
    s.setItem = () => { s.writes++; throw new DOMException('sensitive payload', 'QuotaExceededError') }
    const error = caught(() => mutate(s, d))
    expect(error).toMatchObject({ code: 'quota', outcome: 'confirmed-no-write', operation })
    expect(String(error)).not.toContain('sensitive payload'); expect(s.writes).toBe(1)
    expect(s.data.get(repo.DEFECT_DRAFT_STORAGE_KEY)).toBe(raw)
  })
  it.each(mutations)('%s treats write-then-throw as uncertain without rollback', (operation, mutate) => {
    const s = new TestStorage(); const d = make(); s.data.set(repo.DEFECT_DRAFT_STORAGE_KEY, JSON.stringify([d]))
    s.setItem = (key, value) => { s.writes++; s.data.set(key, value); throw new Error('unknown') }
    expect(caught(() => mutate(s, d))).toMatchObject({ code: 'unknown', outcome: 'uncertain-commit', operation })
    expect(s.writes).toBe(1)
    expect(s.data.get(repo.DEFECT_DRAFT_STORAGE_KEY)).not.toBe(JSON.stringify([d]))
  })
  it.each(mutations)('%s treats readback exceptions as uncertain', (operation, mutate) => {
    const s = new TestStorage(); const d = make(); s.data.set(repo.DEFECT_DRAFT_STORAGE_KEY, JSON.stringify([d]))
    s.getItem = key => { if (s.writes) throw new Error('readback'); return s.data.get(key) ?? null }
    expect(caught(() => mutate(s, d))).toMatchObject({ code: 'verify-failed', outcome: 'uncertain-commit', operation })
    expect(s.writes).toBe(1)
  })
  it('does not classify a name-only fake quota as confirmed-no-write', () => {
    const s = new TestStorage(); s.setItem = () => { throw { name: 'QuotaExceededError' } }
    expect(caught(() => repo.saveDefectDrafts(s, [make()]))).toMatchObject({ code: 'unknown', outcome: 'uncertain-commit' })
  })
  it('does not accept semantically equal but textually different readback', () => {
    const s = new TestStorage(); s.setItem = (key, value) => { s.writes++; s.data.set(key, JSON.stringify(JSON.parse(value), null, 2)) }
    expect(caught(() => repo.saveDefectDrafts(s, [make()]))).toMatchObject({ code: 'verify-failed', outcome: 'uncertain-commit' }); expect(s.writes).toBe(1)
  })
  it('validates all incoming candidates before any batch write, even a duplicate later replaces invalid input', () => {
    const s = new TestStorage(); const d = make(); const bad = { ...d, summary: undefined } as unknown as repo.DefectDraft
    expect(caught(() => repo.saveDefectDrafts(s, [bad, d]))).toMatchObject({ code: 'invalid-data', outcome: 'confirmed-no-write', operation: 'save' }); expect(s.writes).toBe(0)
  })
  it('commits one full mixed insert/update batch using the same exact storage/key', () => {
    const s = new TestStorage(); const d = make(); repo.saveDefectDrafts(s, [d]); s.writes = 0; s.reads = 0
    const untouched = { ...d, defectId: 'untouched', unknown: { raw: [false, '', null, 0] } }
    const next = { ...d, summary: 'updated' }
    expect(repo.saveDefectDrafts(s, [untouched, next])).toStrictEqual({ inserted: 1, updated: 1, total: 2 })
    expect(s.writes).toBe(1); expect(s.reads).toBe(2); expect(s.length).toBe(1)
    expect(JSON.parse(s.data.get(repo.DEFECT_DRAFT_STORAGE_KEY)!)).toStrictEqual([next, untouched])
  })
  it.each(mutations.slice(1))('%s refuses missing ids before write', (operation, mutate) => {
    const s = new TestStorage()
    expect(caught(() => mutate(s, make()))).toMatchObject({ code: 'missing-target', outcome: 'confirmed-no-write', operation }); expect(s.writes).toBe(0)
  })
  it('does not mark any batch member when one selected id is missing', () => {
    const s = new TestStorage(); const d = make(); repo.saveDefectDrafts(s, [d]); s.writes = 0
    expect(caught(() => repo.markDefectsAsExported(s, [d.defectId, 'missing'], 'csv'))).toMatchObject({ code: 'missing-target' }); expect(s.writes).toBe(0); expect(repo.listDefectDrafts(s)[0]).toStrictEqual(d)
  })
  it('CSV marking preserves created Jira status/key/mode and latest unknown keys', () => {
    const s = new TestStorage(); const d = { ...make(), status: 'created_in_jira' as const, jiraIssueKey: 'P-1', jiraExportMode: 'api' as const, unknown: { false: false } }
    repo.saveDefectDrafts(s, [d]); repo.markDefectsAsExported(s, [d.defectId], 'csv')
    expect(repo.listDefectDrafts(s)[0]).toMatchObject({ status: 'created_in_jira', jiraIssueKey: 'P-1', jiraExportMode: 'api', unknown: { false: false } })
  })
})

import { describe, expect, it } from 'vitest'
import { buildDefectDraftsFromWebAutomationResults, saveDefectDrafts, updateDefectDraft, DEFECT_DRAFT_STORAGE_KEY } from './defectRepository'
import { source, options, TestStorage } from './defectEvidence.testFixtures'
const catchError = (fn: () => unknown) => { try { fn(); return undefined } catch (error) { return error } }

describe('typed validation edge failures', () => {
  it('does not evaluate metadata accessors while validating an update', () => {
    const s = new TestStorage(); const d = buildDefectDraftsFromWebAutomationResults([source], options)[0]
    s.data.set(DEFECT_DRAFT_STORAGE_KEY, JSON.stringify([d]))
    let invoked = 0
    Object.defineProperty(d, 'defectId', { enumerable: true, get() { invoked++; throw new Error('secret getter') } })
    expect(catchError(() => updateDefectDraft(s, d))).toMatchObject({ code: 'invalid-data', operation: 'update', outcome: 'confirmed-no-write' })
    expect(invoked).toBe(0); expect(s.writes).toBe(0)
  })
  it('classifies exceptional runtime objects as invalid input without an untyped escape', () => {
    const s = new TestStorage(); const d = buildDefectDraftsFromWebAutomationResults([source], options)[0]
    const proxy = new Proxy(d, { ownKeys() { throw new Error('secret proxy') } })
    expect(catchError(() => saveDefectDrafts(s, [proxy]))).toMatchObject({ code: 'invalid-data', operation: 'save', outcome: 'confirmed-no-write' })
    expect(s.writes).toBe(0)
  })
})

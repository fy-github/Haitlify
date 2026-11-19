import { describe, expect, it } from 'vitest'
import { buildDefectDraftsFromWebAutomationResults, listDefectDrafts, saveDefectDrafts, updateDefectDraft, DEFECT_DRAFT_STORAGE_KEY } from './defectRepository'

import { source, options, TestStorage } from './defectEvidence.testFixtures'

describe('lossless defect evidence contract', () => {
  it('preserves source evidence JSON across create, actual serialized JSON, fresh read and metadata edit', () => {
    const [draft] = buildDefectDraftsFromWebAutomationResults([source], options)
    const storage = new TestStorage()
    saveDefectDrafts(storage, [draft])
    const persisted = JSON.parse(storage.getItem(DEFECT_DRAFT_STORAGE_KEY)!)[0]
    const evidenceKeys = ['pageName', 'startUrl', 'durationMs', 'steps', 'stepLogs', 'stepEvidence', 'failedStepId', 'pageHtmlSnippet', 'screenshotPath', 'errorMessage', 'consoleErrors', 'pageErrors', 'failedRequests'] as const
    for (const key of evidenceKeys) expect(persisted[key], key).toStrictEqual(source[key])
    expect(persisted.evidenceVersion).toBe(1)
    const [fresh] = listDefectDrafts(storage)
    updateDefectDraft(storage, { ...fresh, summary: 'edited' })
    const [edited] = listDefectDrafts(storage)
    for (const key of evidenceKeys) expect(edited[key as keyof typeof edited], key).toStrictEqual(source[key])
    expect(edited.summary).toBe('edited')
    expect(Object.getPrototypeOf({})).toBe(Object.prototype)
    expect(({} as Record<string, unknown>).inert).toBeUndefined()
  })
})

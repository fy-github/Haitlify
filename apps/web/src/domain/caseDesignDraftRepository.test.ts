import { describe, expect, it } from 'vitest'
import {
  CASE_DESIGN_DRAFT_STORAGE_KEY,
  clearCaseDesignDraft,
  inferRequirementSourceKind,
  loadCaseDesignDraft,
  saveCaseDesignDraft,
  type CaseDesignDraftSnapshot
} from './caseDesignDraftRepository'

class MemoryStorage implements Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> {
  values = new Map<string, string>()
  getItem(key: string): string | null { return this.values.get(key) ?? null }
  setItem(key: string, value: string): void { this.values.set(key, value) }
  removeItem(key: string): void { this.values.delete(key) }
}

const snapshot = (): CaseDesignDraftSnapshot => ({
  version: 1,
  activeStage: 'case-generate',
  generationKind: 'web_automation',
  projectName: '订单服务',
  analysisRunId: 'run-1',
  analysisRun: null,
  requirementReviewRun: null,
  caseReviewRun: {
    runId: 'review-1',
    graphId: 'graph-1',
    status: 'merge_ready',
    nodeCount: 4
  },
  activeReviewStage: 'case',
  reviewAttempted: true,
  selectedCaseId: '',
  sources: [{ name: '退款需求.md', kind: 'document', text: '# 退款需求', needsReattach: false }],
  sourceErrors: {},
  extractedRequirementPoints: [],
  aiDraftPoints: [],
  generatedCases: [],
  generatedWebCases: [],
  analysisError: '',
  generationError: '',
  reviewError: '',
  review: {
    insight: { stage: 'case', summary: '', suggestions: [] },
    qualityScores: [],
    conflicts: [],
    findingDecisions: {}
  },
  updatedAt: '2026-07-16T10:00:00.000Z'
})

describe('caseDesignDraftRepository', () => {
  it('round-trips a versioned draft', () => {
    const storage = new MemoryStorage()
    expect(saveCaseDesignDraft(storage, snapshot()).saved).toBe(true)
    expect(loadCaseDesignDraft(storage)).toEqual(snapshot())
  })

  it('marks oversized source text for reattachment', () => {
    const storage = new MemoryStorage()
    saveCaseDesignDraft(storage, {
      ...snapshot(),
      sources: [{ name: 'large.docx', kind: 'document', text: 'x'.repeat(200_001), needsReattach: false }]
    })
    expect(loadCaseDesignDraft(storage)?.sources[0]).toEqual({
      name: 'large.docx',
      kind: 'document',
      text: '',
      needsReattach: true
    })
  })

  it('reports local-storage quota failures without destroying the last readable draft', () => {
    const storage = new MemoryStorage()
    expect(saveCaseDesignDraft(storage, snapshot()).saved).toBe(true)
    const throwingStorage = {
      getItem: storage.getItem.bind(storage),
      removeItem: storage.removeItem.bind(storage),
      setItem: () => { throw new DOMException('Quota exceeded', 'QuotaExceededError') }
    }

    expect(saveCaseDesignDraft(throwingStorage, snapshot())).toMatchObject({
      saved: false,
      error: 'Quota exceeded'
    })
    expect(loadCaseDesignDraft(storage)).toEqual(snapshot())
  })

  it('normalizes an older v1 draft without finding-level decisions', () => {
    const storage = new MemoryStorage()
    const legacy = snapshot()
    storage.setItem(CASE_DESIGN_DRAFT_STORAGE_KEY, JSON.stringify({
      ...legacy,
      review: {
        insight: legacy.review.insight,
        qualityScores: legacy.review.qualityScores,
        conflicts: legacy.review.conflicts
      }
    }))

    expect(loadCaseDesignDraft(storage)?.review.findingDecisions).toEqual({})
  })

  it('keeps optional generation scope fields backward compatible', () => {
    const storage = new MemoryStorage()
    storage.setItem(CASE_DESIGN_DRAFT_STORAGE_KEY, JSON.stringify({
      ...snapshot(),
      generationScopePointIds: ['point-2', 'point-1', 'point-2', 3],
      generationRetryPointIds: ['point-3'],
      selectedGeneratedWebCaseIds: ['WEB-AI-0002', 'WEB-AI-0002']
    }))

    expect(loadCaseDesignDraft(storage)).toMatchObject({
      generationScopePointIds: ['point-2', 'point-1'],
      generationRetryPointIds: ['point-3'],
      selectedGeneratedWebCaseIds: ['WEB-AI-0002']
    })
  })

  it('drops malformed finding decisions from a readable v1 draft', () => {
    const storage = new MemoryStorage()
    const persisted = snapshot()
    storage.setItem(CASE_DESIGN_DRAFT_STORAGE_KEY, JSON.stringify({
      ...persisted,
      review: {
        ...persisted.review,
        findingDecisions: {
          'TC-1': { 'finding-valid': 'accepted', 'finding-invalid': 'done' },
          'TC-2': 'accepted'
        }
      }
    }))

    expect(loadCaseDesignDraft(storage)?.review.findingDecisions).toEqual({
      'TC-1': { 'finding-valid': 'accepted' }
    })
  })

  it('clears a persisted draft', () => {
    const storage = new MemoryStorage()
    saveCaseDesignDraft(storage, snapshot())
    clearCaseDesignDraft(storage)
    expect(loadCaseDesignDraft(storage)).toBeUndefined()
  })

  it.each([
    ['figma-link.txt', 'figma'],
    ['catalog.openapi.yaml', 'openapi'],
    ['需求文档.docx', 'document']
  ] as const)('infers %s as %s source', (name, expected) => {
    expect(inferRequirementSourceKind(name)).toBe(expected)
  })

  it('ignores malformed or unsupported drafts', () => {
    const storage = new MemoryStorage()
    storage.setItem(CASE_DESIGN_DRAFT_STORAGE_KEY, '{bad json')
    expect(loadCaseDesignDraft(storage)).toBeUndefined()
    storage.setItem(CASE_DESIGN_DRAFT_STORAGE_KEY, JSON.stringify({ version: 2 }))
    expect(loadCaseDesignDraft(storage)).toBeUndefined()
  })
})

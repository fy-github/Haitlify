import type { AiAnalysisRun, AiDraftTestPoint } from './aiPlatformRepository'
import type { AiMultiAgentRunResponse } from './aiMultiAgentRunRepository'
import type {
  AgentConflictView,
  AiReviewInsightView,
  QualityScoreView
} from './aiMultiAgentRunView'
import type { GeneratedInterfaceCase } from './caseGenerator'
import type { CaseDesignStage } from './caseDesignWorkflow'
import type { RequirementPointDraft } from './webCaseImport'
import type { GeneratedWebAutomationCase } from './webAutomationCase'

export const CASE_DESIGN_DRAFT_STORAGE_KEY = 'ai-test-platform.case-design-draft.v1'
export const MAX_PERSISTED_SOURCE_TEXT_LENGTH = 200_000

export type PersistedRequirementSourceKind = 'document' | 'figma' | 'openapi'
export type CaseReviewDecision = 'pending' | 'accepted' | 'modified' | 'ignored'
export type CaseReviewFindingDecisions = Record<string, Record<string, CaseReviewDecision>>

const caseReviewDecisionValues: CaseReviewDecision[] = [
  'pending',
  'accepted',
  'modified',
  'ignored'
]

export interface PersistedRequirementSource {
  name: string
  kind: PersistedRequirementSourceKind
  text: string
  needsReattach: boolean
}

export interface CaseDesignDraftSnapshot {
  version: 1
  activeStage: CaseDesignStage
  generationKind: 'interface' | 'web_automation'
  projectName: string
  analysisRunId: string
  naturalPlanRunId?: string
  analysisRun: AiAnalysisRun | null
  requirementReviewRun: AiMultiAgentRunResponse | null
  caseReviewRun: AiMultiAgentRunResponse | null
  activeReviewStage: 'requirement' | 'case'
  reviewAttempted?: boolean
  selectedCaseId: string
  selectedGeneratedWebCaseIds?: string[]
  generationScopePointIds?: string[]
  generationRetryPointIds?: string[]
  caseGenerationConfirmed?: boolean
  sources: PersistedRequirementSource[]
  sourceErrors: Record<string, string>
  extractedRequirementPoints: RequirementPointDraft[]
  aiDraftPoints: AiDraftTestPoint[]
  generatedCases: GeneratedInterfaceCase[]
  generatedWebCases: GeneratedWebAutomationCase[]
  analysisError: string
  generationError: string
  reviewError: string
  review: {
    insight: AiReviewInsightView
    qualityScores: QualityScoreView[]
    conflicts: AgentConflictView[]
    findingDecisions: CaseReviewFindingDecisions
  }
  updatedAt: string
}

type DraftStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

export const inferRequirementSourceKind = (name: string): PersistedRequirementSourceKind => {
  const normalized = name.toLowerCase()
  if (normalized === 'figma-link.txt') return 'figma'
  if (normalized.includes('openapi') || normalized.endsWith('.yaml') || normalized.endsWith('.yml')) return 'openapi'
  return 'document'
}

export const loadCaseDesignDraft = (
  storage: DraftStorage = localStorage
): CaseDesignDraftSnapshot | undefined => {
  const raw = storage.getItem(CASE_DESIGN_DRAFT_STORAGE_KEY)
  if (!raw) return undefined

  try {
    const parsed = JSON.parse(raw) as Partial<CaseDesignDraftSnapshot>
    const legacyStage = parsed.activeStage as unknown as string | undefined
    if (legacyStage === 'import') parsed.activeStage = 'requirement'
    if (legacyStage === 'parse') parsed.activeStage = 'test-parse'
    if (legacyStage === 'generate') parsed.activeStage = 'case-generate'
    if (!isReadableSnapshot(parsed)) return undefined

    return {
      ...parsed,
      ...(Array.isArray(parsed.generationScopePointIds)
        ? { generationScopePointIds: normalizeStringList(parsed.generationScopePointIds) }
        : {}),
      ...(Array.isArray(parsed.generationRetryPointIds)
        ? { generationRetryPointIds: normalizeStringList(parsed.generationRetryPointIds) }
        : {}),
      ...(Array.isArray(parsed.selectedGeneratedWebCaseIds)
        ? { selectedGeneratedWebCaseIds: normalizeStringList(parsed.selectedGeneratedWebCaseIds) }
        : {}),
      review: {
        ...parsed.review,
        findingDecisions: normalizeCaseReviewFindingDecisions(parsed.review.findingDecisions)
      }
    } as CaseDesignDraftSnapshot
  } catch {
    return undefined
  }
}

export const saveCaseDesignDraft = (
  storage: DraftStorage,
  snapshot: CaseDesignDraftSnapshot
): { saved: boolean; needsReattach: string[]; error?: string } => {
  const needsReattach: string[] = []
  const sources = snapshot.sources.map((source) => {
    if (source.text.length <= MAX_PERSISTED_SOURCE_TEXT_LENGTH) return source
    needsReattach.push(source.name)
    return { ...source, text: '', needsReattach: true }
  })

  try {
    storage.setItem(CASE_DESIGN_DRAFT_STORAGE_KEY, JSON.stringify({ ...snapshot, sources }))
    return { saved: true, needsReattach }
  } catch (error) {
    return {
      saved: false,
      needsReattach,
      error: error instanceof Error ? error.message : '草稿保存失败'
    }
  }
}

export const clearCaseDesignDraft = (storage: DraftStorage = localStorage): void => {
  storage.removeItem(CASE_DESIGN_DRAFT_STORAGE_KEY)
}

const isReadableSnapshot = (
  parsed: Partial<CaseDesignDraftSnapshot>
): parsed is CaseDesignDraftSnapshot => {
  if (parsed.version !== 1 || !Array.isArray(parsed.sources)) return false
  if (!isCaseDesignStage(parsed.activeStage)) return false
  if (parsed.generationKind !== 'interface' && parsed.generationKind !== 'web_automation') return false
  if (typeof parsed.projectName !== 'string' || typeof parsed.analysisRunId !== 'string') return false
  if (!parsed.sources.every(isPersistedRequirementSource)) return false
  if (!Array.isArray(parsed.generatedCases) || !Array.isArray(parsed.generatedWebCases)) return false
  return Boolean(
    parsed.review
    && Array.isArray(parsed.review.qualityScores)
    && Array.isArray(parsed.review.conflicts)
  )
}

const isCaseDesignStage = (value: unknown): value is CaseDesignStage =>
  value === 'requirement'
  || value === 'test-parse'
  || value === 'case-generate'
  || value === 'review'
  || value === 'automation-compile'
  || value === 'deliver'

const isPersistedRequirementSource = (source: unknown): source is PersistedRequirementSource => {
  if (!source || typeof source !== 'object' || Array.isArray(source)) return false
  const value = source as Partial<PersistedRequirementSource>
  return typeof value.name === 'string'
    && (value.kind === 'document' || value.kind === 'figma' || value.kind === 'openapi')
    && typeof value.text === 'string'
    && typeof value.needsReattach === 'boolean'
}

const normalizeCaseReviewFindingDecisions = (value: unknown): CaseReviewFindingDecisions => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}

  const normalized: CaseReviewFindingDecisions = {}
  for (const [caseId, findings] of Object.entries(value)) {
    if (!findings || typeof findings !== 'object' || Array.isArray(findings)) continue
    const decisions: Record<string, CaseReviewDecision> = {}
    for (const [findingId, decision] of Object.entries(findings)) {
      if (caseReviewDecisionValues.includes(decision as CaseReviewDecision)) {
        decisions[findingId] = decision as CaseReviewDecision
      }
    }
    if (Object.keys(decisions).length > 0) normalized[caseId] = decisions
  }
  return normalized
}

const normalizeStringList = (value: unknown): string[] =>
  Array.isArray(value)
    ? Array.from(new Set(value.filter((item): item is string => typeof item === 'string' && item.length > 0)))
    : []

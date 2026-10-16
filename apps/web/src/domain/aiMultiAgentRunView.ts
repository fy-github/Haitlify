import type { AiDraftTestPoint } from './aiPlatformRepository'
import type { AiAgentMergeCandidate } from './aiMultiAgentRunRepository'
import type { RequirementPointDraft } from './webCaseImport'

export interface AgentGraphNodeView {
  nodeId: string
  agentId: string
  agentRole: string
  executionOrder: number
  status: string
}

export interface AgentConflictView {
  conflictId: string
  conflictType: string
  targetKey: string
  conflictJson?: Record<string, unknown>
  resolutionStatus: string
}

export interface AgentConflictGroup {
  conflictType: string
  unresolvedCount: number
  items: AgentConflictView[]
}

export interface QualityScoreView {
  qualityDimension: string
  score: number
  scoreLevel: string
}

export interface AutomationSuggestionView {
  suggestionId: string
  draftPointId: string
  caseKind: string
  automationReadiness: string
}

export interface AutomationSuggestionGroup {
  caseKind: string
  readyCount: number
  items: AutomationSuggestionView[]
}

export type AiReviewFindingCategory = 'coverage_gap' | 'suggestion'

export interface AiReviewSuggestionView {
  findingId: string
  category: AiReviewFindingCategory
  title: string
  detail: string
  priority: string
}

export interface AiReviewInsightView {
  stage: string
  summary: string
  suggestions: AiReviewSuggestionView[]
}

export const sortAgentGraphNodes = (nodes: AgentGraphNodeView[]): AgentGraphNodeView[] =>
  [...nodes].sort((left, right) => left.executionOrder - right.executionOrder || left.agentId.localeCompare(right.agentId))

export const groupAgentConflictsByType = (conflicts: AgentConflictView[]): AgentConflictGroup[] => {
  const groups = new Map<string, AgentConflictGroup>()
  for (const conflict of conflicts) {
    const group = groups.get(conflict.conflictType) || {
      conflictType: conflict.conflictType,
      unresolvedCount: 0,
      items: []
    }
    group.items.push(conflict)
    group.unresolvedCount = group.items.filter((item) => item.resolutionStatus === 'unresolved').length
    groups.set(conflict.conflictType, group)
  }
  return [...groups.values()]
}

export const qualityScoreLevel = (score: QualityScoreView): 'success' | 'warning' | 'danger' | 'info' => {
  if (score.scoreLevel === 'good' || score.score >= 0.8) return 'success'
  if (score.scoreLevel === 'warning' || score.score >= 0.4) return 'warning'
  if (score.scoreLevel === 'risk') return 'danger'
  return 'info'
}

export const groupAutomationSuggestionsByCaseKind = (suggestions: AutomationSuggestionView[]): AutomationSuggestionGroup[] => {
  const groups = new Map<string, AutomationSuggestionGroup>()
  for (const suggestion of suggestions) {
    const group = groups.get(suggestion.caseKind) || {
      caseKind: suggestion.caseKind,
      readyCount: 0,
      items: []
    }
    group.items.push(suggestion)
    group.readyCount = group.items.filter((item) => item.automationReadiness === 'ready').length
    groups.set(suggestion.caseKind, group)
  }
  return [...groups.values()]
}

export const buildAiReviewInsightView = (outputSnapshot?: Record<string, unknown>): AiReviewInsightView => {
  const review = parseReviewRecord(outputSnapshot?.aiReview) || asRecord(outputSnapshot) || {}
  const summary = firstText(
    review.reviewSummary,
    review.summary,
    review.conclusion,
    outputSnapshot?.errorSummary
  )
  const findingValues: Array<{ value: unknown; category: AiReviewFindingCategory }> = [
    ...asArray(review.missingPoints).map((value) => ({ value, category: 'coverage_gap' as const })),
    ...asArray(review.missingCases).map((value) => ({ value, category: 'coverage_gap' as const })),
    ...asArray(review.supplementSuggestions).map((value) => ({ value, category: 'suggestion' as const })),
    ...asArray(review.suggestions).map((value) => ({ value, category: 'suggestion' as const })),
    ...asArray(review.improvements).map((value) => ({ value, category: 'suggestion' as const }))
  ]
  return {
    stage: firstText(outputSnapshot?.reviewStage, review.reviewStage),
    summary,
    suggestions: findingValues
      .map(({ value, category }) => toAiReviewSuggestionView(value, category))
      .filter((item) => item.title || item.detail)
  }
}

const parseReviewRecord = (value: unknown): Record<string, unknown> | undefined => {
  if (typeof value !== 'string') return asRecord(value)
  try {
    const parsed = JSON.parse(value)
    return asRecord(parsed)
  } catch {
    return undefined
  }
}

export const buildMultiAgentMergeCandidatesFromDraftPoints = (points: AiDraftTestPoint[]): AiAgentMergeCandidate[] =>
  points
    .filter((point) => point.reviewStatus !== 'rejected')
    .map((point) => ({
      title: point.title,
      targetKey: point.pageOrFeature || point.title,
      caseKind: point.caseKind || 'web_automation',
      priority: point.priority || 'P2',
      expectedResult: point.expectedResult || point.objective || '按需求验收通过',
      sourceEvidence: compactUnique([point.sourceFile, point.sourceText]),
      suggestedSteps: splitSteps(point.steps)
    }))

export const buildMultiAgentMergeCandidatesFromSuggestions = (
  suggestions: AiReviewSuggestionView[]
): AiAgentMergeCandidate[] => suggestions
  .filter((suggestion) => suggestion.title.trim())
  .map((suggestion) => ({
    title: suggestion.title,
    targetKey: suggestion.title,
    caseKind: 'web_automation',
    priority: suggestion.priority || 'P2',
    expectedResult: suggestion.detail || '按评审建议补充并完成验收',
    sourceEvidence: [],
    suggestedSteps: [suggestion.title]
  }))

export const buildMultiAgentMergeCandidatesFromRequirementPoints = (
  points: RequirementPointDraft[]
): AiAgentMergeCandidate[] =>
  points.map((point) => ({
    title: point.title,
    targetKey: point.pageName || point.title,
    caseKind: 'web_automation',
    priority: 'P2',
    expectedResult: '按需求测试点验收通过',
    sourceEvidence: compactUnique([point.sourceFile]),
    suggestedSteps: [point.title]
  }))

const splitSteps = (steps: string): string[] =>
  steps
    .split(/\r?\n/)
    .map((step) => step.trim())
    .filter(Boolean)

const compactUnique = (values: Array<string | undefined | null>): string[] =>
  Array.from(new Set(values.map((value) => value?.trim()).filter((value): value is string => Boolean(value))))

const asRecord = (value: unknown): Record<string, unknown> | undefined =>
  value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : undefined

const asArray = (value: unknown): unknown[] =>
  Array.isArray(value) ? value : []

const firstText = (...values: unknown[]): string =>
  values
    .map((value) => typeof value === 'string' ? value.trim() : '')
    .find(Boolean) || ''

const toAiReviewSuggestionView = (
  value: unknown,
  category: AiReviewFindingCategory
): AiReviewSuggestionView => {
  const record = asRecord(value)
  const title = typeof value === 'string'
    ? value.trim()
    : firstText(record?.title, record?.name, record?.point, record?.caseTitle, record?.description)
  const detail = record
    ? firstText(record.reason, record.suggestion, record.detail, record.expectedSupplement, record.expectedResult)
    : ''
  const priority = record ? firstText(record.priority, record.level, record.riskLevel) : ''
  const suppliedId = record ? firstText(record.findingId, record.suggestionId, record.id) : ''
  return {
    findingId: stableReviewFindingId(category, title, detail, priority, suppliedId),
    category,
    title,
    detail,
    priority
  }
}

const stableReviewFindingId = (
  category: AiReviewFindingCategory,
  title: string,
  detail: string,
  priority: string,
  suppliedId: string
): string => {
  const normalizedSuppliedId = suppliedId.trim()
  if (normalizedSuppliedId.startsWith(`${category}:`)) return normalizedSuppliedId
  if (normalizedSuppliedId) return `${category}:${encodeURIComponent(normalizedSuppliedId)}`
  return `${category}:${encodeURIComponent(
    [title, detail, priority].map((item) => item.trim().toLowerCase()).join('\u001f')
  )}`
}

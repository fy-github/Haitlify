import type {
  AgentConflictView,
  AiReviewSuggestionView,
  QualityScoreView
} from './aiMultiAgentRunView'
import type {
  CaseReviewDecision,
  CaseReviewFindingDecisions
} from './caseDesignDraftRepository'

export interface CaseDesignReviewViewInput {
  selectedCase?: {
    id: string
    title: string
    module: string
    sourceFile: string
  }
  qualityScores: QualityScoreView[]
  conflicts: AgentConflictView[]
  findings: AiReviewSuggestionView[]
  findingDecisions: CaseReviewFindingDecisions
}

export interface CaseDesignReviewFindingView extends AiReviewSuggestionView {
  decision: CaseReviewDecision
}

export interface CaseDesignReviewView {
  selectedCaseId: string
  selectedCaseTitle: string
  selectedSourceFile: string
  averageScore: number
  unresolvedConflictCount: number
  findingCount: number
  pendingFindingCount: number
  highRiskCount: number
  findings: CaseDesignReviewFindingView[]
  relevantConflictIds: string[]
  relevantFindingIds: string[]
  hasContent: boolean
}

export const buildCaseDesignReviewView = (
  input: CaseDesignReviewViewInput
): CaseDesignReviewView => {
  const selected = input.selectedCase
  const searchTerms = selected
    ? [selected.id, selected.title, selected.module, selected.sourceFile]
      .map((value) => value.trim().toLowerCase())
      .filter(Boolean)
    : []
  const relevantConflicts = selected
    ? input.conflicts.filter((conflict) => searchTerms.some((term) => conflict.targetKey.toLowerCase().includes(term)))
    : input.conflicts
  const relevantFindings = selected
    ? input.findings.filter((finding) => {
      if (finding.category === 'coverage_gap') return true
      const text = `${finding.title} ${finding.detail}`.toLowerCase()
      return searchTerms.some((term) => text.includes(term))
    })
    : input.findings
  const sortedConflicts = [...relevantConflicts].sort((left, right) =>
    Number(left.resolutionStatus !== 'unresolved') - Number(right.resolutionStatus !== 'unresolved')
  )
  const decisionsForCase = selected ? input.findingDecisions[selected.id] || {} : {}
  const sortedFindings = [...relevantFindings]
    .map((finding): CaseDesignReviewFindingView => ({
      ...finding,
      decision: decisionsForCase[finding.findingId] || 'pending'
    }))
    .sort((left, right) =>
      Number(left.decision !== 'pending') - Number(right.decision !== 'pending')
      || Number(left.category !== 'coverage_gap') - Number(right.category !== 'coverage_gap')
      || riskRank(left.priority) - riskRank(right.priority)
      || left.findingId.localeCompare(right.findingId)
    )
  const unresolvedConflictCount = sortedConflicts.filter((conflict) => conflict.resolutionStatus === 'unresolved').length
  const pendingFindingCount = sortedFindings.filter((finding) => finding.decision === 'pending').length

  return {
    selectedCaseId: selected?.id || '',
    selectedCaseTitle: selected?.title || '',
    selectedSourceFile: selected?.sourceFile || '',
    averageScore: input.qualityScores.length > 0
      ? Math.round((input.qualityScores.reduce((total, score) => total + score.score, 0) / input.qualityScores.length) * 100)
      : 0,
    unresolvedConflictCount,
    findingCount: sortedFindings.length,
    pendingFindingCount,
    highRiskCount: unresolvedConflictCount + sortedFindings.filter(
      (finding) => finding.decision === 'pending' && riskRank(finding.priority) <= 1
    ).length,
    findings: sortedFindings,
    relevantConflictIds: sortedConflicts.map((conflict) => conflict.conflictId),
    relevantFindingIds: sortedFindings.map((finding) => finding.findingId),
    hasContent: input.qualityScores.length > 0 || sortedConflicts.length > 0 || sortedFindings.length > 0
  }
}

const riskRank = (priority: string): number => {
  if (priority === 'P0') return 0
  if (priority === 'P1') return 1
  if (priority === 'P2') return 2
  return 3
}

import type { WorkflowAsyncStatus } from './caseDesignWorkflow'

export type CaseDesignDeliveryMode = 'library' | 'automation'
export type CaseDesignDeliveryRiskKind = 'review_failure' | 'conflict' | 'coverage_gap' | 'finding'

export interface CaseDesignDeliveryRiskItem {
  id: string
  kind: CaseDesignDeliveryRiskKind
  priority: string
  message: string
}

export interface CaseDesignDeliveryInput {
  mode: CaseDesignDeliveryMode
  executeNow: boolean
  generatedCount: number
  selectedAutomationCount: number
  executabilityIssues: string[]
  reviewAttempted: boolean
  reviewStatus: WorkflowAsyncStatus
  reviewError: string
  reviewRisks: CaseDesignDeliveryRiskItem[]
  environmentValid: boolean
  environmentMessage: string
}

export interface CaseDesignDeliveryView {
  canSubmit: boolean
  primaryLabel: string
  blockers: string[]
  warnings: string[]
  riskItems: CaseDesignDeliveryRiskItem[]
}

export const buildCaseDesignDeliveryView = (
  input: CaseDesignDeliveryInput
): CaseDesignDeliveryView => {
  const blockers: string[] = []
  const warnings: string[] = []
  const riskItems = [...input.reviewRisks].sort(compareDeliveryRisks)

  if (input.generatedCount === 0) blockers.push('没有可交付的生成用例')
  if (!input.reviewAttempted) blockers.push('请先至少尝试一次 AI 质量评审')
  if (input.reviewAttempted && input.reviewStatus === 'running') blockers.push('AI 质量评审仍在进行中')
  if (input.reviewAttempted && input.reviewStatus === 'idle') blockers.push('AI 质量评审尚未形成可交付结果')
  if (input.mode === 'automation' && input.generatedCount > 0 && input.selectedAutomationCount === 0) {
    blockers.push('请至少选择一条要转换的自动化用例')
  }
  if (input.mode === 'automation') blockers.push(...input.executabilityIssues)
  if (input.mode === 'automation' && input.executeNow && !input.environmentValid) {
    blockers.push(input.environmentMessage || '请先配置有效的运行环境')
  }
  if (input.reviewAttempted && input.reviewStatus === 'failed') {
    warnings.push('AI 质量评审未完成，允许带警告继续交付')
    riskItems.unshift({
      id: 'review:failed',
      kind: 'review_failure',
      priority: 'P0',
      message: input.reviewError || 'AI 质量评审失败，未能取得完整结果'
    })
  }
  if (riskItems.length > 0) warnings.push(`仍有 ${riskItems.length} 项评审风险`)

  return {
    canSubmit: blockers.length === 0,
    primaryLabel: input.mode === 'library'
      ? '保存到用例库'
      : input.executeNow
        ? '保存并执行'
        : '保存并转自动化',
    blockers,
    warnings,
    riskItems
  }
}

const compareDeliveryRisks = (
  left: CaseDesignDeliveryRiskItem,
  right: CaseDesignDeliveryRiskItem
): number => riskKindRank(left.kind) - riskKindRank(right.kind)
  || priorityRank(left.priority) - priorityRank(right.priority)
  || left.id.localeCompare(right.id)

const riskKindRank = (kind: CaseDesignDeliveryRiskKind): number => ({
  review_failure: 0,
  conflict: 1,
  coverage_gap: 2,
  finding: 3
}[kind])

const priorityRank = (priority: string): number => {
  if (priority === 'P0') return 0
  if (priority === 'P1') return 1
  if (priority === 'P2') return 2
  return 3
}

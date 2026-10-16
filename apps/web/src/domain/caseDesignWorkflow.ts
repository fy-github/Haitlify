export type CaseDesignStage = 'requirement' | 'test-parse' | 'case-generate' | 'review' | 'automation-compile' | 'deliver'
export type WorkflowAsyncStatus = 'idle' | 'running' | 'succeeded' | 'failed'
export type WorkflowStageStatus = 'pending' | 'active' | 'done' | 'error'

export const reviewRunStatusToWorkflowStatus = (
  status: string | undefined
): WorkflowAsyncStatus => {
  if (!status) return 'idle'
  if (status === 'failed') return 'failed'
  if (status === 'merge_ready' || status === 'succeeded') return 'succeeded'
  return 'running'
}

export interface CaseDesignWorkflowInput {
  requestedStage: CaseDesignStage
  sourceCount: number
  analysisStatus: WorkflowAsyncStatus
  confirmedDraftCount: number
  pendingDraftCount: number
  generationStatus: WorkflowAsyncStatus
  generatedCaseCount: number
  caseGenerationConfirmed: boolean
  reviewAttempted: boolean
  reviewStatus: WorkflowAsyncStatus
  automationCompileStatus: WorkflowAsyncStatus
  automationCompiledCaseCount: number
  pendingReviewCount: number
  saving: boolean
}

export interface CaseDesignStageView {
  key: CaseDesignStage
  label: string
  enabled: boolean
  status: WorkflowStageStatus
}

export interface CaseDesignWorkflowView {
  activeStage: CaseDesignStage
  stages: CaseDesignStageView[]
  primaryAction: { label: string; disabled: boolean; loading: boolean }
  pendingReviewCount: number
  automationCompileStatus: WorkflowAsyncStatus
  automationCompiledCaseCount: number
}

const stageDefinitions: Array<{ key: CaseDesignStage; label: string }> = [
  { key: 'requirement', label: '需求解析' },
  { key: 'test-parse', label: '测试解析' },
  { key: 'case-generate', label: '用例生成' },
  { key: 'review', label: '质量评审' },
  { key: 'automation-compile', label: '自动化编译' },
  { key: 'deliver', label: '保存执行' }
]

export const buildCaseDesignWorkflowView = (
  input: CaseDesignWorkflowInput
): CaseDesignWorkflowView => {
  const enabled: Record<CaseDesignStage, boolean> = {
    requirement: true,
    'test-parse': input.analysisStatus === 'succeeded' || input.confirmedDraftCount > 0,
    'case-generate': input.requestedStage !== 'automation-compile' && input.confirmedDraftCount > 0,
    review: input.generatedCaseCount > 0 && input.caseGenerationConfirmed,
    'automation-compile': input.generatedCaseCount > 0
      && input.caseGenerationConfirmed
      && input.reviewAttempted
      && (input.reviewStatus === 'succeeded' || input.reviewStatus === 'failed')
      ,
    deliver: input.automationCompiledCaseCount > 0
      && input.automationCompileStatus === 'succeeded'
      && input.reviewAttempted
      && (input.reviewStatus === 'succeeded' || input.reviewStatus === 'failed')
  }
  const activeStage = enabled[input.requestedStage]
    ? input.requestedStage
    : [...stageDefinitions].reverse().find((stage) => enabled[stage.key])?.key || 'requirement'
  const completed: Record<CaseDesignStage, boolean> = {
    requirement: input.analysisStatus === 'succeeded',
    'test-parse': input.confirmedDraftCount > 0,
    'case-generate': input.generationStatus === 'succeeded' || input.generatedCaseCount > 0,
    review: input.reviewStatus === 'succeeded',
    'automation-compile': input.automationCompileStatus === 'succeeded',
    deliver: false
  }

  return {
    activeStage,
    stages: stageDefinitions.map((stage) => ({
      ...stage,
      enabled: enabled[stage.key],
      status: stageStatus(stage.key, activeStage, completed, input)
    })),
    primaryAction: primaryActionFor(activeStage, input),
    pendingReviewCount: input.pendingReviewCount,
    automationCompileStatus: input.automationCompileStatus,
    automationCompiledCaseCount: input.automationCompiledCaseCount
  }
}

const stageStatus = (
  stage: CaseDesignStage,
  activeStage: CaseDesignStage,
  completed: Record<CaseDesignStage, boolean>,
  input: CaseDesignWorkflowInput
): WorkflowStageStatus => {
  if (stage === activeStage) return 'active'
  if (stage === 'requirement' && input.analysisStatus === 'failed') return 'error'
  if (stage === 'case-generate' && input.generationStatus === 'failed') return 'error'
  if (stage === 'review' && input.reviewStatus === 'failed') return 'error'
  if (stage === 'automation-compile' && input.automationCompileStatus === 'failed') return 'error'
  return completed[stage] ? 'done' : 'pending'
}

const primaryActionFor = (
  activeStage: CaseDesignStage,
  input: CaseDesignWorkflowInput
): CaseDesignWorkflowView['primaryAction'] => {
  if (activeStage === 'requirement') {
    return { label: '解析需求', disabled: input.sourceCount === 0, loading: false }
  }
  if (activeStage === 'test-parse') {
    return { label: '确认测试点', disabled: input.pendingDraftCount === 0 && input.confirmedDraftCount === 0, loading: false }
  }
  if (activeStage === 'case-generate') {
    if (input.analysisStatus === 'running') return { label: '正在解析', disabled: true, loading: true }
    return {
      label: '确认用例',
      disabled: input.generatedCaseCount === 0 || input.generationStatus === 'running',
      loading: input.generationStatus === 'running'
    }
  }
  if (activeStage === 'review') {
    return {
      label: input.reviewStatus === 'running' ? '正在评审' : input.reviewStatus === 'failed' ? '重试评审' : '完成评审',
      disabled: input.generatedCaseCount === 0 || input.reviewStatus === 'running',
      loading: input.reviewStatus === 'running'
    }
  }
  if (activeStage === 'automation-compile') {
    return {
      label: input.automationCompileStatus === 'running' ? '正在编译' : input.automationCompileStatus === 'failed' ? '重试编译' : '编译自动化',
      disabled: input.generatedCaseCount === 0 || input.automationCompileStatus === 'running',
      loading: input.automationCompileStatus === 'running'
    }
  }
  return { label: '保存执行', disabled: input.automationCompiledCaseCount === 0 || input.saving, loading: input.saving }
}

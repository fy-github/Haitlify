import type { WorkflowStageStatus } from './caseDesignWorkflow'

export type InterfaceCaseStage = 'import' | 'generate' | 'review' | 'deliver'

export interface InterfaceCaseWorkflowInput {
  requestedStage: InterfaceCaseStage
  importedSourceCount: number
  parsedOperationCount: number
  selectedOperationCount: number
  generatedCaseCount: number
  reviewAttempted: boolean
  saving: boolean
}

export interface InterfaceCaseStageView {
  key: InterfaceCaseStage
  label: string
  enabled: boolean
  status: WorkflowStageStatus
}

export interface InterfaceCaseWorkflowView {
  activeStage: InterfaceCaseStage
  stages: InterfaceCaseStageView[]
  primaryAction: { label: string; disabled: boolean; loading: boolean }
  reviewWarning: string
}

const stageDefinitions: Array<{ key: InterfaceCaseStage; label: string }> = [
  { key: 'import', label: '导入接口' },
  { key: 'generate', label: '配置生成' },
  { key: 'review', label: 'AI 评审' },
  { key: 'deliver', label: '保存 / 交付' }
]

export const buildInterfaceCaseWorkflowView = (
  input: InterfaceCaseWorkflowInput
): InterfaceCaseWorkflowView => {
  const enabled: Record<InterfaceCaseStage, boolean> = {
    import: true,
    generate: input.parsedOperationCount > 0,
    review: input.generatedCaseCount > 0,
    deliver: input.generatedCaseCount > 0
  }
  const activeStage = enabled[input.requestedStage]
    ? input.requestedStage
    : [...stageDefinitions].reverse().find((stage) => enabled[stage.key])?.key || 'import'
  const completed: Record<InterfaceCaseStage, boolean> = {
    import: input.importedSourceCount > 0,
    generate: input.generatedCaseCount > 0,
    review: input.reviewAttempted,
    deliver: false
  }

  return {
    activeStage,
    stages: stageDefinitions.map((stage) => ({
      ...stage,
      enabled: enabled[stage.key],
      status: stageStatus(stage.key, activeStage, completed)
    })),
    primaryAction: primaryActionFor(activeStage, input),
    reviewWarning: input.generatedCaseCount > 0 && !input.reviewAttempted
      ? '尚未运行 AI 评审，仍可保存或交付'
      : ''
  }
}

const stageStatus = (
  stage: InterfaceCaseStage,
  activeStage: InterfaceCaseStage,
  completed: Record<InterfaceCaseStage, boolean>
): WorkflowStageStatus => {
  if (stage === activeStage) return 'active'
  return completed[stage] ? 'done' : 'pending'
}

const primaryActionFor = (
  activeStage: InterfaceCaseStage,
  input: InterfaceCaseWorkflowInput
): InterfaceCaseWorkflowView['primaryAction'] => {
  if (activeStage === 'import') {
    return {
      label: input.importedSourceCount > 0 ? '继续配置' : '导入 OpenAPI',
      disabled: false,
      loading: false
    }
  }
  if (activeStage === 'generate') {
    return { label: '生成用例', disabled: input.selectedOperationCount === 0, loading: false }
  }
  if (activeStage === 'review') {
    return {
      label: input.reviewAttempted ? '继续交付' : '运行 AI 评审',
      disabled: false,
      loading: false
    }
  }
  return {
    label: '保存用例',
    disabled: input.generatedCaseCount === 0 || input.saving,
    loading: input.saving
  }
}

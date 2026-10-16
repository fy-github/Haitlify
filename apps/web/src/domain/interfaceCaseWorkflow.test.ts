import { describe, expect, it } from 'vitest'
import { buildInterfaceCaseWorkflowView } from './interfaceCaseWorkflow'

const readyInput = () => ({
  requestedStage: 'deliver' as const,
  importedSourceCount: 1,
  parsedOperationCount: 2,
  selectedOperationCount: 2,
  generatedCaseCount: 3,
  reviewAttempted: true,
  saving: false
})

describe('interfaceCaseWorkflow', () => {
  it('exposes the exact four-stage interface workflow', () => {
    const view = buildInterfaceCaseWorkflowView({
      requestedStage: 'import',
      importedSourceCount: 0,
      parsedOperationCount: 0,
      selectedOperationCount: 0,
      generatedCaseCount: 0,
      reviewAttempted: false,
      saving: false
    })

    expect(view.activeStage).toBe('import')
    expect(view.stages).toEqual([
      { key: 'import', label: '导入接口', enabled: true, status: 'active' },
      { key: 'generate', label: '配置生成', enabled: false, status: 'pending' },
      { key: 'review', label: 'AI 评审', enabled: false, status: 'pending' },
      { key: 'deliver', label: '保存 / 交付', enabled: false, status: 'pending' }
    ])
    expect(view.primaryAction).toEqual({ label: '导入 OpenAPI', disabled: false, loading: false })
    expect(view.reviewWarning).toBe('')
  })

  it('falls back to the latest enabled stage when the requested stage is locked', () => {
    const view = buildInterfaceCaseWorkflowView({
      requestedStage: 'deliver',
      importedSourceCount: 1,
      parsedOperationCount: 2,
      selectedOperationCount: 0,
      generatedCaseCount: 0,
      reviewAttempted: false,
      saving: false
    })

    expect(view.activeStage).toBe('generate')
    expect(view.stages.find((stage) => stage.key === 'import')?.status).toBe('done')
    expect(view.primaryAction).toEqual({ label: '生成用例', disabled: true, loading: false })
  })

  it('allows delivery without review while warning that AI review has not run', () => {
    const view = buildInterfaceCaseWorkflowView({
      ...readyInput(),
      reviewAttempted: false
    })

    expect(view.activeStage).toBe('deliver')
    expect(view.stages.find((stage) => stage.key === 'review')?.enabled).toBe(true)
    expect(view.stages.find((stage) => stage.key === 'deliver')?.enabled).toBe(true)
    expect(view.primaryAction).toEqual({ label: '保存用例', disabled: false, loading: false })
    expect(view.reviewWarning).toBe('尚未运行 AI 评审，仍可保存或交付')
  })

  it('updates primary actions from imported source through review and saving', () => {
    expect(buildInterfaceCaseWorkflowView({
      ...readyInput(),
      requestedStage: 'import'
    }).primaryAction).toEqual({ label: '继续配置', disabled: false, loading: false })

    expect(buildInterfaceCaseWorkflowView({
      ...readyInput(),
      requestedStage: 'review',
      reviewAttempted: false
    }).primaryAction).toEqual({ label: '运行 AI 评审', disabled: false, loading: false })

    expect(buildInterfaceCaseWorkflowView({
      ...readyInput(),
      requestedStage: 'review'
    }).primaryAction).toEqual({ label: '继续交付', disabled: false, loading: false })

    expect(buildInterfaceCaseWorkflowView({
      ...readyInput(),
      saving: true
    }).primaryAction).toEqual({ label: '保存用例', disabled: true, loading: true })
  })
})

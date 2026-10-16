import { describe, expect, it } from 'vitest'
import { buildCaseDesignWorkflowView, reviewRunStatusToWorkflowStatus } from './caseDesignWorkflow'

const readyInput = () => ({
  requestedStage: 'deliver' as const,
  sourceCount: 1,
  analysisStatus: 'succeeded' as const,
  confirmedDraftCount: 2,
  pendingDraftCount: 0,
  generationStatus: 'succeeded' as const,
  generatedCaseCount: 2,
  caseGenerationConfirmed: true,
  reviewAttempted: true,
  reviewStatus: 'succeeded' as const,
  automationCompileStatus: 'succeeded' as const,
  automationCompiledCaseCount: 2,
  pendingReviewCount: 0,
  saving: false
})

describe('caseDesignWorkflow', () => {
  it('starts at requirement and exposes one requirement parse command', () => {
    const view = buildCaseDesignWorkflowView({
      requestedStage: 'requirement',
      sourceCount: 2,
      analysisStatus: 'idle',
      confirmedDraftCount: 0,
      pendingDraftCount: 0,
      generationStatus: 'idle',
      generatedCaseCount: 0,
      caseGenerationConfirmed: false,
      reviewAttempted: false,
      reviewStatus: 'idle',
      automationCompileStatus: 'idle',
      automationCompiledCaseCount: 0,
      pendingReviewCount: 0,
      saving: false
    })
    expect(view.activeStage).toBe('requirement')
    expect(view.primaryAction).toEqual({ label: '解析需求', disabled: false, loading: false })
    expect(view.stages.find((stage) => stage.key === 'test-parse')?.enabled).toBe(false)
  })

  it('requires generated cases to be confirmed before leaving case generation', () => {
    const pending = buildCaseDesignWorkflowView({ ...readyInput(), requestedStage: 'case-generate', generatedCaseCount: 0, generationStatus: 'idle', caseGenerationConfirmed: false })
    const ready = buildCaseDesignWorkflowView({ ...readyInput(), requestedStage: 'case-generate', generatedCaseCount: 2, generationStatus: 'succeeded', caseGenerationConfirmed: false })
    expect(pending.primaryAction).toEqual({ label: '确认用例', disabled: true, loading: false })
    expect(ready.primaryAction).toEqual({ label: '确认用例', disabled: false, loading: false })
  })

  it('falls back from a locked requested stage', () => {
    const view = buildCaseDesignWorkflowView({
      ...readyInput(),
      requestedStage: 'review',
      analysisStatus: 'running',
      confirmedDraftCount: 0,
      generationStatus: 'idle',
      generatedCaseCount: 0,
      reviewAttempted: false,
      reviewStatus: 'idle'
    })
    expect(view.activeStage).toBe('requirement')
    expect(view.primaryAction).toEqual({ label: '解析需求', disabled: false, loading: false })
  })

  it('locks delivery until review has been attempted', () => {
    const view = buildCaseDesignWorkflowView({
      ...readyInput(),
      reviewAttempted: false,
      reviewStatus: 'idle'
    })
    expect(view.activeStage).toBe('review')
    expect(view.stages.find((stage) => stage.key === 'deliver')?.enabled).toBe(false)
  })

  it.each(['succeeded', 'failed'] as const)(
    'enables automation compilation after a terminal %s review attempt',
    (reviewStatus) => {
      const view = buildCaseDesignWorkflowView({ ...readyInput(), requestedStage: 'automation-compile', automationCompileStatus: 'idle', automationCompiledCaseCount: 0, reviewStatus, pendingReviewCount: 2 })
      expect(view.activeStage).toBe('automation-compile')
      expect(view.primaryAction.label).toBe('编译自动化')
      expect(view.pendingReviewCount).toBe(2)
    }
  )

  it('locks navigation back to case generation after entering automation compile', () => {
    const view = buildCaseDesignWorkflowView({ ...readyInput(), requestedStage: 'automation-compile', automationCompileStatus: 'idle', automationCompiledCaseCount: 0 })
    expect(view.activeStage).toBe('automation-compile')
    expect(view.stages.find((stage) => stage.key === 'case-generate')?.enabled).toBe(false)
  })

  it.each([
    ['queued', 'running'], ['running', 'running'], ['merge_ready', 'succeeded'],
    ['succeeded', 'succeeded'], ['failed', 'failed'], [undefined, 'idle']
  ] as const)('maps backend review status %s to %s', (status, expected) => {
    expect(reviewRunStatusToWorkflowStatus(status)).toBe(expected)
  })

  it('marks completed stages done when navigating backward', () => {
    const view = buildCaseDesignWorkflowView({ ...readyInput(), requestedStage: 'requirement' })
    expect(view.stages.map((stage) => [stage.key, stage.status])).toEqual([
      ['requirement', 'active'], ['test-parse', 'done'], ['case-generate', 'done'], ['review', 'done'], ['automation-compile', 'done'], ['deliver', 'pending']
    ])
  })

  it('relocks delivery after a generated artifact invalidates the previous review', () => {
    const reviewed = readyInput()
    expect(buildCaseDesignWorkflowView(reviewed).activeStage).toBe('deliver')
    expect(buildCaseDesignWorkflowView({
      ...reviewed,
      reviewAttempted: false,
      reviewStatus: 'idle'
    }).activeStage).toBe('review')
  })
})

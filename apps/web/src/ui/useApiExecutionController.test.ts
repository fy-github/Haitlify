import { describe, expect, it } from 'vitest'
import { createApiExecutionController } from './useApiExecutionController'

describe('useApiExecutionController', () => {
  it('validates and synchronizes loop or concurrency input', () => {
    const controller = createApiExecutionController({ getAvailableCaseCount: () => 4 })

    expect(controller.apiRunCountValue.value).toBe(1)
    expect(controller.commitApiRunCount('3')).toBe(true)
    expect(controller.apiAutomationLoopCount.value).toBe(3)
    expect(controller.onApiRunCountInput('bad')).toBe(false)
    expect(controller.apiRunCountError.value).toContain('正整数')

    controller.automationRunMode.value = 'concurrent'
    expect(controller.commitApiRunCount('5')).toBe(true)
    expect(controller.automationConcurrencyLimit.value).toBe(5)
    expect(controller.apiRunCountValue.value).toBe(5)
  })

  it('owns selection, monitor state and run generation reset', () => {
    const controller = createApiExecutionController({ getAvailableCaseCount: () => 3 })
    const firstGeneration = controller.nextRunGeneration()
    const secondGeneration = controller.nextRunGeneration()

    controller.apiAutomationRunning.value = true
    controller.apiRunConfirming.value = true
    controller.apiAutomationWorkspaceMode.value = 'monitor'
    controller.reset()

    expect(secondGeneration).toBe(firstGeneration + 1)
    expect(controller.apiAutomationRunning.value).toBe(false)
    expect(controller.apiRunConfirming.value).toBe(false)
    expect(controller.apiAutomationWorkspaceMode.value).toBe('prepare')
    expect(controller.apiRunSnapshot.value).toBeUndefined()
    expect(controller.apiRunMonitor.value).toBeUndefined()
    expect(controller.apiAutomationProgress).toEqual({ completed: 0, total: 0, runningCaseId: '' })
  })

  it('derives the execution label from selected and filtered case counts', () => {
    const controller = createApiExecutionController({ getAvailableCaseCount: () => 7 })
    expect(controller.apiExecutionScopeLabel.value).toContain('执行筛选 7')
    controller.automationSelection.value = [{ id: 'case-1' } as never, { id: 'case-2' } as never]
    expect(controller.apiExecutionScopeLabel.value).toContain('选中 2')
  })
})

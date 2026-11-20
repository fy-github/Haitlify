import { describe, expect, it } from 'vitest'
import { createWebExecutionState } from './useWebExecutionController'

describe('useWebExecutionController', () => {
  it('owns the Web execution preparation and run state', () => {
    const controller = createWebExecutionState()

    controller.webAutomationRunMode.value = 'concurrent'
    controller.webAutomationLoopCount.value = 3
    controller.webAutomationSelectedIds.value = ['case-1']
    controller.webAutomationWorkspaceMode.value = 'monitor'
    controller.activeWebRunId.value = 'run-1'
    controller.webAutomationRunning.value = true

    controller.reset()

    expect(controller.webAutomationRunMode.value).toBe('sequential')
    expect(controller.webAutomationLoopCount.value).toBe(1)
    expect(controller.webAutomationSelectedIds.value).toEqual([])
    expect(controller.webAutomationWorkspaceMode.value).toBe('workbench')
    expect(controller.activeWebRunId.value).toBe('')
    expect(controller.webAutomationRunning.value).toBe(false)
  })

  it('clears local run messages without touching selected cases', () => {
    const controller = createWebExecutionState()
    controller.webAutomationSelectedIds.value = ['case-1']
    controller.webRunCreationError.value = 'create failed'
    controller.webRunInterruptionMessage.value = 'interrupted'

    controller.clearLocalMessages()

    expect(controller.webAutomationSelectedIds.value).toEqual(['case-1'])
    expect(controller.webRunCreationError.value).toBe('')
    expect(controller.webRunInterruptionMessage.value).toBe('')
  })
})

import { describe, expect, it, vi } from 'vitest'
import { createApiExecutionController } from './useApiExecutionController'
import type { SavedAutomationCase } from '../domain/automationRepository'

const testCase = (): SavedAutomationCase => ({
  id: 'case-1', sourceCaseId: 'source-1', project: 'P', module: 'M', name: 'case', method: 'GET', path: '/original',
  headers: {}, pathParams: {}, queryParams: {}, expectedStatus: 200, assertions: [], runOrder: 1,
  exportTarget: 'apifox', automationLibraryId: 'case-1', savedAt: '2026-01-01T00:00:00Z'
})

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

  it('freezes cases and environment before confirmation and saves one final report', async () => {
    const selected = testCase()
    const environment = { baseUrl: 'https://api.example.test' }
    let confirm!: (value: boolean) => void
    const executeRequest = vi.fn(async () => ({ status: 200, body: {} }))
    const saveReport = vi.fn()
    const enterMonitor = vi.fn()
    const controller = createApiExecutionController({
      getAvailableCaseCount: () => 1, getFilteredCases: () => [selected], getCanEdit: () => true,
      getOwnerId: () => 1, getEnvironment: () => environment, getEnvironmentName: () => 'API',
      beginOperation: () => () => true, captureStorage: () => ({} as Storage),
      confirmSnapshot: () => new Promise(resolve => { confirm = resolve }), executeRequest,
      enterMonitor, saveReport
    })

    const run = controller.executeAllAutomationCases()
    selected.path = '/changed'
    environment.baseUrl = 'https://changed.example.test'
    controller.apiAutomationLoopCount.value = 3
    confirm(true)

    expect(await run).toBe(true)
    expect(controller.apiRunSnapshot.value?.cases[0]?.path).toBe('/original')
    expect(controller.apiRunSnapshot.value?.environment.baseUrl).toBe('https://api.example.test')
    expect(controller.apiRunSnapshot.value?.options.loopCount).toBe(1)
    expect(executeRequest).toHaveBeenCalledTimes(1)
    expect(saveReport).toHaveBeenCalledTimes(1)
    expect(enterMonitor).toHaveBeenCalledTimes(1)
    expect(controller.apiRunMonitor.value?.status).toBe('completed')
  })

  it('does not start after a stale confirmation or persist a late result after reset', async () => {
    let active = true
    let confirm!: (value: boolean) => void
    let finishRequest!: (result: { status: number; body: object }) => void
    const executeRequest = vi.fn(() => new Promise(resolve => { finishRequest = resolve }))
    const saveReport = vi.fn()
    const controller = createApiExecutionController({
      getAvailableCaseCount: () => 1, getFilteredCases: () => [testCase()], getCanEdit: () => true,
      getOwnerId: () => 1, getEnvironment: () => ({ baseUrl: 'https://api.example.test' }),
      beginOperation: () => () => active, captureStorage: () => ({} as Storage), executeRequest,
      confirmSnapshot: () => new Promise(resolve => { confirm = resolve }), saveReport
    })

    const staleConfirmation = controller.executeAllAutomationCases()
    active = false
    confirm(true)
    expect(await staleConfirmation).toBe(false)
    expect(executeRequest).not.toHaveBeenCalled()

    controller.reset()
    active = true
    const run = controller.executeAllAutomationCases()
    confirm(true)
    await vi.waitFor(() => expect(executeRequest).toHaveBeenCalledTimes(1))
    controller.reset()
    finishRequest({ status: 200, body: {} })
    expect(await run).toBe(false)
    expect(saveReport).not.toHaveBeenCalled()
    expect(controller.apiRunMonitor.value).toBeUndefined()
  })
})

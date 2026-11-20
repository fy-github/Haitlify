// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { newSavedWebExecutionEnvironment } from '../domain/executionEnvironmentRepository'
import type { SavedWebAutomationRunCase } from '../domain/webAutomationRepository'
import type { WebAutomationRunSnapshot } from '../domain/webAutomationRunRepository'
import * as runRepository from '../domain/webAutomationRunRepository'
import type { WebRunStartRequest } from '../domain/webExecutionPreparation'
import { createWebExecutionState } from './useWebExecutionController'
import { createWebRunAdmission } from './webRunAdmission'
import type { WebExecutionPorts } from './webExecutionPorts'

afterEach(() => { localStorage.clear(); sessionStorage.clear() })

const makeContext = () => {
  const environment = { ...newSavedWebExecutionEnvironment('Web'), id: 'environment-1', baseUrl: 'https://app.example.test' }
  const request: WebRunStartRequest = {
    scopeLabel: '执行选中 1 条', selectedCaseIds: ['case-1'], loopCount: 2,
    environmentId: environment.id, environmentName: environment.name, environmentBaseUrl: environment.baseUrl,
    browser: 'chromium', mode: 'sequential'
  }
  const testCase = { id: 'case-1', name: 'original', startUrl: '/start' } as SavedWebAutomationRunCase
  const snapshot = {
    runId: 'run-1', status: 'running', mode: 'sequential', selectedBrowser: 'chromium',
    createdAt: '2026-01-01', updatedAt: '2026-01-01',
    progress: { totalCases: 1, completedCases: 0, totalSteps: 1, completedSteps: 0 },
    controls: { canPause: true, canResume: false, canStop: true },
    summary: { total: 1, passed: 0, failed: 0, error: 0 }, stepLogs: [], results: [], diagnostics: []
  } as WebAutomationRunSnapshot
  let active = true
  let release!: (value: number) => void
  const resolveProject = vi.fn(() => new Promise<number>(resolve => { release = resolve }))
  const createRun = vi.fn(async () => snapshot)
  const ports: WebExecutionPorts = {
    begin: () => () => active, operationId: () => 1, storage: localStorage, sessionStorage,
    getSavedCases: () => [], getStartContext: () => ({ request, cases: [testCase], environment, canExecute: true, blockingReason: '' }),
    resolveProject, reportContext: () => ({ project: '', moduleScope: [], environmentName: '' }),
    onReport: vi.fn(), openEnvironment: vi.fn(), writeMonitorQuery: vi.fn(), getSearch: () => '', notify: vi.fn(),
    repository: { ...runRepository, createWebAutomationRun: createRun }
  }
  const lifecycle: Parameters<typeof createWebRunAdmission>[2] = {
    applyWebRunSnapshot: vi.fn(), monitorActiveWebRun: vi.fn(async () => undefined),
    stopPolling: vi.fn(), clearFinalizedRun: vi.fn()
  }
  return { environment, request, testCase, ports, lifecycle, resolveProject, createRun, releaseProject: (id: number) => release(id), revoke: () => { active = false } }
}

it('submits the frozen cases, environment and options after project lookup', async () => {
  const context = makeContext()
  const state = createWebExecutionState()
  const start = createWebRunAdmission(state, context.ports, context.lifecycle)()
  context.testCase.name = 'changed'
  context.request.loopCount = 9
  context.environment.baseUrl = 'https://changed.example.test'
  context.releaseProject(42)

  expect(await start).toBe(true)
  expect(context.createRun).toHaveBeenCalledWith(
    [expect.objectContaining({ name: 'original' })],
    expect.objectContaining({ loopCount: 2, projectId: 42,
      runtime: expect.objectContaining({ baseUrl: 'https://app.example.test' }) })
  )
  expect(state.activeWebRunId.value).toBe('run-1')
  expect(context.lifecycle.monitorActiveWebRun).toHaveBeenCalledTimes(1)
})

it('does not POST a run after the workspace lease expires during project lookup', async () => {
  const context = makeContext()
  const state = createWebExecutionState()
  const start = createWebRunAdmission(state, context.ports, context.lifecycle)()
  context.revoke()
  context.releaseProject(42)

  expect(await start).toBe(false)
  expect(context.createRun).not.toHaveBeenCalled()
  expect(state.activeWebRunId.value).toBe('')
})

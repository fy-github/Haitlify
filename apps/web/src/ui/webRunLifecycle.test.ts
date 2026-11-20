// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { createWebExecutionState } from './useWebExecutionController'
import { createWebRunLifecycle } from './webRunLifecycle'
import type { WebAutomationRunSnapshot } from '../domain/webAutomationRunRepository'
import type { WebExecutionPorts } from './webExecutionPorts'
import * as runRepository from '../domain/webAutomationRunRepository'
import { WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY } from '../domain/webAutomationRunSession'

const snapshot = (status: WebAutomationRunSnapshot['status']): WebAutomationRunSnapshot => ({
  runId: 'run-1', status, mode: 'sequential', selectedBrowser: 'chromium', createdAt: '2026-01-01', updatedAt: '2026-01-01',
  progress: { totalCases: 1, completedCases: 1, totalSteps: 1, completedSteps: 1 },
  controls: { canPause: false, canResume: false, canStop: false },
  summary: { total: 1, passed: 1, failed: 0, error: 0 }, stepLogs: [], results: [], diagnostics: []
})

afterEach(() => { sessionStorage.clear(); localStorage.clear() })

it('persists a completed run once when monitor and control both report the same terminal snapshot', async () => {
  const state = createWebExecutionState()
  state.activeWebRunId.value = 'run-1'
  const completed = snapshot('completed')
  const report = { mode: 'sequential' as const, startedAt: '', endedAt: '', summary: completed.summary,
    results: [{ caseId: 'case-1', sourceCaseId: 'source-1', name: 'case', module: 'module', pageName: 'page', status: 'passed' as const,
      startUrl: 'https://example.test', durationMs: 1, steps: [] }] }
  const onReport = vi.fn()
  const notify = vi.fn()
  const ports: WebExecutionPorts = {
    begin: () => () => true, operationId: () => 1, storage: localStorage, sessionStorage,
    getSavedCases: () => [], getStartContext: () => { throw new Error('unused') },
    resolveProject: async () => undefined, reportContext: () => ({ project: 'P', moduleScope: [], environmentName: 'E' }),
    onReport, openEnvironment: () => undefined, writeMonitorQuery: () => undefined, getSearch: () => '', notify,
    repository: { ...runRepository, snapshotToReport: () => report, getWebAutomationRun: async () => completed,
      isTerminalWebAutomationRun: () => true, stopWebAutomationRun: async () => completed } as WebExecutionPorts['repository']
  }
  const lifecycle = createWebRunLifecycle(state, ports)
  await lifecycle.monitorActiveWebRun('run-1')
  await lifecycle.stopActiveWebRun()
  expect(onReport).toHaveBeenCalledTimes(1)
  expect(notify).toHaveBeenCalledTimes(1)
  expect(state.webAutomationRunning.value).toBe(false)
})

it('drops a late recovery response after the owner lease expires', async () => {
  const state = createWebExecutionState()
  sessionStorage.setItem(WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY, 'run-1')
  let release!: (snapshot: WebAutomationRunSnapshot) => void
  let active = true
  const ports: WebExecutionPorts = {
    begin: () => () => active, operationId: () => 1, storage: localStorage, sessionStorage,
    getSavedCases: () => [], getStartContext: () => { throw new Error('unused') },
    resolveProject: async () => undefined, reportContext: () => ({ project: '', moduleScope: [], environmentName: '' }),
    onReport: vi.fn(), openEnvironment: () => undefined, writeMonitorQuery: vi.fn(), getSearch: () => '?view=monitor&runId=run-1',
    notify: vi.fn(), repository: { ...runRepository, getWebAutomationRun: () => new Promise<WebAutomationRunSnapshot>(resolve => { release = resolve }) }
  }
  const lifecycle = createWebRunLifecycle(state, ports)
  const recovery = lifecycle.recoverWebRunSession()
  active = false
  release(snapshot('completed'))
  await recovery
  expect(state.activeWebRunId.value).toBe('')
  expect(state.activeWebRunSnapshot.value).toBeUndefined()
})

it('rejects a recovered snapshot for a different run without adopting its identity', async () => {
  const state = createWebExecutionState()
  sessionStorage.setItem(WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY, 'run-1')
  const writeMonitorQuery = vi.fn()
  const ports: WebExecutionPorts = {
    begin: () => () => true, operationId: () => 1, storage: localStorage, sessionStorage,
    getSavedCases: () => [], getStartContext: () => { throw new Error('unused') },
    resolveProject: async () => undefined, reportContext: () => ({ project: '', moduleScope: [], environmentName: '' }),
    onReport: vi.fn(), openEnvironment: () => undefined, writeMonitorQuery, getSearch: () => '?view=monitor&runId=run-1',
    notify: vi.fn(), repository: { ...runRepository, getWebAutomationRun: async () => ({ ...snapshot('running'), runId: 'run-2' }) }
  }

  await createWebRunLifecycle(state, ports).recoverWebRunSession()

  expect(state.activeWebRunId.value).toBe('')
  expect(state.activeWebRunSnapshot.value).toBeUndefined()
  expect(sessionStorage.getItem(WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY)).toBeNull()
  expect(writeMonitorQuery).toHaveBeenCalledWith('workbench', '', 'replace')
})

it('retries transient polling failures and resumes with the matching run', async () => {
  const state = createWebExecutionState()
  state.activeWebRunId.value = 'run-1'
  let attempts = 0
  const observedStates: string[] = []
  const ports: WebExecutionPorts = {
    begin: () => () => true, operationId: () => 1, storage: localStorage, sessionStorage,
    getSavedCases: () => [], getStartContext: () => { throw new Error('unused') },
    resolveProject: async () => undefined, reportContext: () => ({ project: '', moduleScope: [], environmentName: '' }),
    onReport: vi.fn(), openEnvironment: () => undefined, writeMonitorQuery: vi.fn(), getSearch: () => '', notify: vi.fn(),
    wait: async () => { observedStates.push(state.webRunConnectionState.value) },
    repository: { ...runRepository, getWebAutomationRun: async () => {
      if (++attempts <= 3) throw new Error('temporary failure')
      return snapshot('completed')
    } }
  }

  await createWebRunLifecycle(state, ports).monitorActiveWebRun('run-1')

  expect(observedStates).toEqual(['retrying', 'retrying', 'interrupted'])
  expect(attempts).toBe(4)
  expect(state.webRunConnectionState.value).toBe('idle')
  expect(state.activeWebRunSnapshot.value?.runId).toBe('run-1')
})

it('ignores a control response for another run without changing the current snapshot', async () => {
  const state = createWebExecutionState()
  state.activeWebRunId.value = 'run-1'
  const notify = vi.fn()
  const ports: WebExecutionPorts = {
    begin: () => () => true, operationId: () => 1, storage: localStorage, sessionStorage,
    getSavedCases: () => [], getStartContext: () => { throw new Error('unused') },
    resolveProject: async () => undefined, reportContext: () => ({ project: '', moduleScope: [], environmentName: '' }),
    onReport: vi.fn(), openEnvironment: () => undefined, writeMonitorQuery: vi.fn(), getSearch: () => '', notify,
    repository: { ...runRepository, pauseWebAutomationRun: async () => ({ ...snapshot('paused'), runId: 'run-2' }) }
  }

  await createWebRunLifecycle(state, ports).pauseActiveWebRun()

  expect(state.activeWebRunId.value).toBe('run-1')
  expect(state.activeWebRunSnapshot.value).toBeUndefined()
  expect(state.webRunControlLoading.value).toBe('')
  expect(notify).toHaveBeenCalledWith('error', expect.stringContaining('任务标识不匹配'))
})

it('discards a control response after the run generation changes', async () => {
  const state = createWebExecutionState()
  state.activeWebRunId.value = 'run-1'
  let operationId = 1
  let release!: (value: WebAutomationRunSnapshot) => void
  const ports: WebExecutionPorts = {
    begin: () => () => true, operationId: () => operationId, storage: localStorage, sessionStorage,
    getSavedCases: () => [], getStartContext: () => { throw new Error('unused') },
    resolveProject: async () => undefined, reportContext: () => ({ project: '', moduleScope: [], environmentName: '' }),
    onReport: vi.fn(), openEnvironment: () => undefined, writeMonitorQuery: vi.fn(), getSearch: () => '', notify: vi.fn(),
    repository: { ...runRepository, pauseWebAutomationRun: () => new Promise(resolve => { release = resolve }) }
  }

  const control = createWebRunLifecycle(state, ports).pauseActiveWebRun()
  operationId = 2
  release(snapshot('paused'))
  await control

  expect(state.activeWebRunSnapshot.value).toBeUndefined()
})

it('stops polling without adopting a snapshot for another run', async () => {
  const state = createWebExecutionState()
  state.activeWebRunId.value = 'run-1'
  const writeMonitorQuery = vi.fn()
  const ports: WebExecutionPorts = {
    begin: () => () => true, operationId: () => 1, storage: localStorage, sessionStorage,
    getSavedCases: () => [], getStartContext: () => { throw new Error('unused') },
    resolveProject: async () => undefined, reportContext: () => ({ project: '', moduleScope: [], environmentName: '' }),
    onReport: vi.fn(), openEnvironment: () => undefined, writeMonitorQuery, getSearch: () => '', notify: vi.fn(),
    repository: { ...runRepository, getWebAutomationRun: async () => ({ ...snapshot('completed'), runId: 'run-2' }) }
  }

  await createWebRunLifecycle(state, ports).monitorActiveWebRun('run-1')

  expect(state.activeWebRunId.value).toBe('')
  expect(state.activeWebRunSnapshot.value).toBeUndefined()
  expect(writeMonitorQuery).toHaveBeenCalledWith('workbench', '', 'replace')
  expect(ports.onReport).not.toHaveBeenCalled()
})

it('keeps local evidence while clearing a run that the runner no longer has', async () => {
  const state = createWebExecutionState()
  state.activeWebRunId.value = 'run-1'
  state.activeWebRunSnapshot.value = snapshot('running')
  sessionStorage.setItem(WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY, 'run-1')
  const writeMonitorQuery = vi.fn()
  const ports: WebExecutionPorts = {
    begin: () => () => true, operationId: () => 1, storage: localStorage, sessionStorage,
    getSavedCases: () => [], getStartContext: () => { throw new Error('unused') },
    resolveProject: async () => undefined, reportContext: () => ({ project: '', moduleScope: [], environmentName: '' }),
    onReport: vi.fn(), openEnvironment: () => undefined, writeMonitorQuery, getSearch: () => '?view=monitor&runId=run-1',
    notify: vi.fn(), repository: { ...runRepository, getWebAutomationRun: async () => { throw new runRepository.WebAutomationRunRequestError(404) } }
  }

  await createWebRunLifecycle(state, ports).recoverWebRunSession()

  expect(state.activeWebRunId.value).toBe('')
  expect(state.activeWebRunSnapshot.value?.runId).toBe('run-1')
  expect(state.webRunConnectionState.value).toBe('interrupted')
  expect(sessionStorage.getItem(WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY)).toBeNull()
  expect(writeMonitorQuery).toHaveBeenCalledWith('workbench', '', 'replace')
})

it('restarts monitoring after resuming an interrupted run', async () => {
  const state = createWebExecutionState()
  state.activeWebRunId.value = 'run-1'
  state.webRunConnectionState.value = 'interrupted'
  const getRun = vi.fn(async () => snapshot('completed'))
  const ports: WebExecutionPorts = {
    begin: () => () => true, operationId: () => 1, storage: localStorage, sessionStorage,
    getSavedCases: () => [], getStartContext: () => { throw new Error('unused') },
    resolveProject: async () => undefined, reportContext: () => ({ project: '', moduleScope: [], environmentName: '' }),
    onReport: vi.fn(), openEnvironment: () => undefined, writeMonitorQuery: vi.fn(), getSearch: () => '', notify: vi.fn(),
    repository: { ...runRepository, resumeWebAutomationRun: async () => snapshot('running'), getWebAutomationRun: getRun }
  }

  await createWebRunLifecycle(state, ports).resumeActiveWebRun()
  await vi.waitFor(() => expect(getRun).toHaveBeenCalledTimes(1))
  expect(state.webRunControlLoading.value).toBe('')
  expect(state.webRunConnectionState.value).toBe('idle')
  expect(state.webAutomationRunning.value).toBe(false)
})

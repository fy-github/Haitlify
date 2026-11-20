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

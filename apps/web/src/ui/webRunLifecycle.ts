import * as repository from '../domain/webAutomationRunRepository'
import { createWebRunPollCoordinator, resolveWebRunRecovery, WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY } from '../domain/webAutomationRunSession'
import { resolveWebRunCaseId } from '../domain/webAutomationRunView'
import { appendAutomationReport, buildSavedAutomationReportRecord } from '../domain/automationReportRepository'
import type { OperationLease } from './appPorts'
import type { WebExecutionPorts, WebExecutionState } from './webExecutionPorts'

export function createWebRunLifecycle(state: WebExecutionState, ports: WebExecutionPorts) {
  const api = ports.repository ?? repository
  const poller = createWebRunPollCoordinator()
  const wait = ports.wait ?? ((delayMs: number) => new Promise(resolve => setTimeout(resolve, delayMs)))
  let finalizedRunId = ''

  const applyWebRunSnapshot = (snapshot: repository.WebAutomationRunSnapshot) => {
    state.activeWebRunSnapshot.value = snapshot
    state.webAutomationRunReport.value = api.snapshotToReport(snapshot)
    const recoveredIds = [...snapshot.results.map(result => result.caseId), snapshot.currentCase?.id]
      .filter((caseId): caseId is string => Boolean(caseId))
    const active = new Map(state.activeWebRunCases.value.map(testCase => [testCase.id, testCase]))
    const saved = new Map(ports.getSavedCases().map(testCase => [testCase.id, testCase]))
    const savedIds = new Set(saved.keys())
    for (const caseId of recoveredIds) {
      const originalId = resolveWebRunCaseId(caseId, savedIds)
      const testCase = originalId ? saved.get(originalId) : undefined
      if (testCase && !active.has(testCase.id)) active.set(testCase.id, testCase)
    }
    state.activeWebRunCases.value = [...active.values()]
  }

  const clearRecoveredWebRun = (message = '执行已中断：Runner 中已找不到当前任务') => {
    ports.sessionStorage.removeItem(WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY)
    state.activeWebRunId.value = ''
    state.webAutomationRunning.value = false
    state.webRunConnectionState.value = 'interrupted'
    state.webRunInterruptionMessage.value = message
    poller.stop()
    state.webAutomationWorkspaceMode.value = 'workbench'
    ports.writeMonitorQuery('workbench', '', 'replace')
  }

  const finalizeWebRunSnapshot = (snapshot: repository.WebAutomationRunSnapshot) => {
    applyWebRunSnapshot(snapshot)
    state.webAutomationRunning.value = false
    state.webRunConnectionState.value = 'idle'
    if (finalizedRunId === snapshot.runId) return
    finalizedRunId = snapshot.runId
    const report = api.snapshotToReport(snapshot)
    if (report.results.length > 0) {
      const record = buildSavedAutomationReportRecord(report, {
        ...ports.reportContext(), reportId: `web-report-${Date.now()}`,
        name: `Web-${new Date().toLocaleString('zh-CN', { hour12: false })}`,
        createdAt: new Date().toISOString()
      })
      appendAutomationReport(ports.storage, record)
      ports.onReport(record)
    }
    if (snapshot.status === 'stopped') {
      ports.notify('info', 'Web 执行已停止，已保留当前日志和截图', true)
    } else if (snapshot.status === 'failed') {
      ports.notify('error', snapshot.diagnostics[snapshot.diagnostics.length - 1] || 'Web 执行任务异常结束', true)
    } else {
      ports.notify('success', `执行完成：通过 ${report.summary.passed}，失败 ${report.summary.failed}，错误 ${report.summary.error}`, true)
    }
  }

  const monitorActiveWebRun = async (runId: string, isCurrent: OperationLease = ports.begin('webRun')) => {
    if (!isCurrent()) return
    poller.stop()
    const poll = poller.start(runId)
    if (!poll.shouldStart) return
    let consecutiveFailures = 0
    const current = () => isCurrent() && poller.isCurrent(runId, poll.token)
    try {
      while (current() && state.activeWebRunId.value === runId) {
        try {
          const snapshot = await api.getWebAutomationRun(runId)
          if (!current()) return
          consecutiveFailures = 0
          state.webRunConnectionState.value = 'polling'
          applyWebRunSnapshot(snapshot)
          if (api.isTerminalWebAutomationRun(snapshot.status)) {
            finalizeWebRunSnapshot(snapshot)
            return
          }
          await wait(500)
        } catch (error) {
          if (!current()) return
          consecutiveFailures += 1
          state.webRunConnectionState.value = consecutiveFailures >= 3 ? 'interrupted' : 'retrying'
          if (error instanceof repository.WebAutomationRunRequestError && error.status === 404) {
            clearRecoveredWebRun()
            return
          }
          await wait(Math.min(3000, 500 * consecutiveFailures))
        }
      }
    } finally {
      if (current()) poller.stop(runId)
    }
  }

  const control = async (action: 'pause' | 'resume' | 'stop') => {
    const operation = ports.begin('webRunControl')
    const runId = state.activeWebRunId.value
    const runOperation = ports.operationId('webRun')
    const current = () => operation() && state.activeWebRunId.value === runId
      && ports.operationId('webRun') === runOperation
    if (!runId || !current()) return
    state.webRunControlLoading.value = action
    try {
      const command = { pause: api.pauseWebAutomationRun, resume: api.resumeWebAutomationRun, stop: api.stopWebAutomationRun }[action]
      const snapshot = await command(runId)
      if (!current()) return
      applyWebRunSnapshot(snapshot)
      if (action === 'stop' && api.isTerminalWebAutomationRun(snapshot.status)) finalizeWebRunSnapshot(snapshot)
      if (action === 'resume' && state.webRunConnectionState.value === 'interrupted') {
        state.webRunControlLoading.value = ''
        void monitorActiveWebRun(runId)
      }
    } catch (error) {
      if (current()) ports.notify('error', error instanceof repository.WebAutomationRunRequestError
        ? error.toFallbackMessage() : { pause: '暂停执行失败', resume: '继续执行失败', stop: '停止执行失败' }[action])
    } finally {
      if (current()) state.webRunControlLoading.value = ''
    }
  }

  const recoverWebRunSession = async (historyMode: 'push' | 'replace' = 'replace') => {
    const current = ports.begin('webRun')
    if (!current()) return
    poller.stop()
    const recovery = resolveWebRunRecovery(ports.getSearch(), ports.sessionStorage.getItem(WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY) || '')
    if (!recovery.runId) {
      state.webAutomationWorkspaceMode.value = 'workbench'
      return
    }
    try {
      const snapshot = await api.getWebAutomationRun(recovery.runId)
      if (!current()) return
      state.activeWebRunId.value = snapshot.runId
      applyWebRunSnapshot(snapshot)
      ports.sessionStorage.setItem(WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY, snapshot.runId)
      state.webRunConnectionState.value = api.isTerminalWebAutomationRun(snapshot.status) ? 'idle' : 'polling'
      state.webAutomationWorkspaceMode.value = recovery.mode
      if (recovery.mode === 'monitor') ports.writeMonitorQuery('monitor', snapshot.runId, historyMode)
      if (!api.isTerminalWebAutomationRun(snapshot.status)) {
        state.webAutomationRunning.value = true
        void monitorActiveWebRun(snapshot.runId, current)
      }
    } catch (error) {
      if (!current()) return
      if (error instanceof repository.WebAutomationRunRequestError && error.status === 404) {
        clearRecoveredWebRun()
        return
      }
      state.webRunConnectionState.value = 'retrying'
      state.webRunInterruptionMessage.value = error instanceof repository.WebAutomationRunRequestError
        ? error.toFallbackMessage() : error instanceof Error ? error.message : '恢复 Web 执行状态失败'
    }
  }
  return {
    applyWebRunSnapshot, monitorActiveWebRun, recoverWebRunSession,
    pauseActiveWebRun: () => control('pause'), resumeActiveWebRun: () => control('resume'), stopActiveWebRun: () => control('stop'),
    stopPolling: () => poller.stop(), clearFinalizedRun: () => { finalizedRunId = '' }
  }
}

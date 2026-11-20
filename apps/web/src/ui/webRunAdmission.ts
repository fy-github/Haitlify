import { buildWebExecutionRuntime, normalizeWebExecutionRuntimeConfig, validateWebExecutionEnvironment } from '../domain/executionEnvironmentRepository'
import type { WebRuntimeBrowser } from '../domain/executionEnvironmentRepository'
import * as repository from '../domain/webAutomationRunRepository'
import { WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY } from '../domain/webAutomationRunSession'
import { freezeWebRunStart, isWebRunStartRequest, type WebRunStartRequest } from '../domain/webExecutionPreparation'
import type { SavedWebAutomationRunCase } from '../domain/webAutomationRepository'
import type { WebExecutionPorts, WebExecutionState } from './webExecutionPorts'
import type { OperationLease } from './appPorts'

interface AdmissionLifecycle {
  applyWebRunSnapshot(snapshot: repository.WebAutomationRunSnapshot): void
  monitorActiveWebRun(runId: string, current: OperationLease): Promise<void>
  stopPolling(): void
  clearFinalizedRun(): void
}

export function createWebRunAdmission(state: WebExecutionState, ports: WebExecutionPorts, lifecycle: AdmissionLifecycle) {
  const api = ports.repository ?? repository
  return async (explicitCases?: SavedWebAutomationRunCase[], confirmedRequest?: WebRunStartRequest): Promise<boolean> => {
    if (state.webAutomationRunning.value) return false
    const current = ports.begin('webRun')
    if (!current()) return false
    const context = ports.getStartContext()
    const request = isWebRunStartRequest(confirmedRequest) ? confirmedRequest : context.request
    const frozen = freezeWebRunStart(request, explicitCases || context.cases)
    if (!explicitCases && !context.canExecute) {
      state.webRunCreationError.value = context.blockingReason
      return false
    }
    if (frozen.cases.length === 0) {
      state.webRunCreationError.value = '没有可执行的 Web 自动化用例'
      return false
    }
    const environment = JSON.parse(JSON.stringify(context.environment)) as typeof context.environment
    if (frozen.environmentId && !environment.id) environment.id = frozen.environmentId
    if (frozen.environmentBaseUrl) environment.baseUrl = frozen.environmentBaseUrl
    if (frozen.browser && frozen.browser !== 'follow-case') {
      environment.web = { ...normalizeWebExecutionRuntimeConfig(environment.web), browser: frozen.browser as WebRuntimeBrowser }
    }
    const validation = validateWebExecutionEnvironment(environment)
    if (!validation.valid) {
      ports.notify('warning', validation.message || 'Web 运行环境配置无效')
      ports.openEnvironment(environment.id)
      return false
    }
    const runtime = buildWebExecutionRuntime(environment)
    state.webRunCreationError.value = ''
    state.webRunInterruptionMessage.value = ''
    state.webAutomationRunning.value = true
    const projectId = await ports.resolveProject(frozen.cases, current)
    if (!current()) return false
    state.activeWebRunCases.value = [...frozen.cases]
    state.webRunConnectionState.value = 'polling'
    lifecycle.clearFinalizedRun()
    lifecycle.stopPolling()
    try {
      const created = await api.createWebAutomationRun(frozen.cases, {
        mode: frozen.mode, loopCount: frozen.loopCount, browserOverride: runtime.browser, runtime, projectId
      })
      if (!current()) return false
      state.activeWebRunId.value = created.runId
      lifecycle.applyWebRunSnapshot(created)
      ports.sessionStorage.setItem(WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY, created.runId)
      state.webAutomationWorkspaceMode.value = 'workbench'
      void lifecycle.monitorActiveWebRun(created.runId, current)
      return true
    } catch (error) {
      if (!current()) return false
      state.webAutomationRunning.value = false
      state.webRunConnectionState.value = 'interrupted'
      const message = error instanceof repository.WebAutomationRunRequestError
        ? error.toFallbackMessage() : error instanceof Error ? error.message : 'Web runner 不可用'
      state.webRunCreationError.value = message
      ports.notify('error', message)
      return false
    }
  }
}

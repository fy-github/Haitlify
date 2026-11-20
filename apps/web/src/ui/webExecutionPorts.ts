import type { Ref, ShallowRef } from 'vue'
import type { SavedExecutionEnvironment } from '../domain/executionEnvironmentRepository'
import type { SavedWebAutomationRunCase } from '../domain/webAutomationRepository'
import type { WebAutomationRunMode, WebAutomationRunReport } from '../domain/webAutomation'
import type { WebAutomationCapabilities, WebAutomationRunSnapshot } from '../domain/webAutomationRunRepository'
import type { WebRunStartRequest } from '../domain/webExecutionPreparation'
import type { SavedAutomationReport } from '../domain/automationReportRepository'
import type { OperationLease } from './appPorts'

export interface WebExecutionState {
  webAutomationRunning: Ref<boolean>
  webAutomationRunMode: Ref<WebAutomationRunMode>
  webAutomationLoopCount: Ref<number>
  webAutomationRunReport: Ref<WebAutomationRunReport | undefined>
  activeWebRunId: Ref<string>
  activeWebRunSnapshot: ShallowRef<WebAutomationRunSnapshot | undefined>
  activeWebRunCases: ShallowRef<SavedWebAutomationRunCase[]>
  webRunCapabilities: ShallowRef<WebAutomationCapabilities | undefined>
  webRunConnectionState: Ref<'idle' | 'polling' | 'retrying' | 'interrupted'>
  webRunControlLoading: Ref<'pause' | 'resume' | 'stop' | ''>
  webAutomationWorkspaceMode: Ref<'workbench' | 'monitor'>
  webRunCreationError: Ref<string>
  webRunInterruptionMessage: Ref<string>
}

export interface WebExecutionPorts {
  begin(resource: string): OperationLease
  operationId(resource: string): number | undefined
  storage: Storage
  sessionStorage: Storage
  getSavedCases(): SavedWebAutomationRunCase[]
  getStartContext(): {
    request: WebRunStartRequest
    cases: SavedWebAutomationRunCase[]
    environment: SavedExecutionEnvironment
    canExecute: boolean
    blockingReason: string
  }
  resolveProject(cases: SavedWebAutomationRunCase[], current: OperationLease): Promise<number | undefined>
  reportContext(): { project: string; moduleScope: string[]; environmentName: string }
  onReport(report: SavedAutomationReport): void
  openEnvironment(environmentId: string): void
  writeMonitorQuery(mode: 'workbench' | 'monitor', runId?: string, historyMode?: 'push' | 'replace'): void
  getSearch(): string
  notify(kind: 'info' | 'warning' | 'success' | 'error', message: string, terminal?: boolean): void
  wait?(delayMs: number): Promise<unknown>
  repository?: typeof import('../domain/webAutomationRunRepository')
}

import { ref, shallowRef } from 'vue'
import type { Ref, ShallowRef } from 'vue'
import { createWebRunPollCoordinator } from '../domain/webAutomationRunSession'
import type { WebAutomationCapabilities, WebAutomationRunSnapshot } from '../domain/webAutomationRunRepository'
import type { SavedWebAutomationRunCase } from '../domain/webAutomationRepository'
import type { WebAutomationRunMode, WebAutomationRunReport } from '../domain/webAutomation'
import type { WebCaseStatusFilter } from '../components/automation/WebAutomationCaseTable.vue'

type WebRunPollCoordinator = ReturnType<typeof createWebRunPollCoordinator>

export interface WebExecutionController {
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
  webAutomationSelectedIds: Ref<string[]>
  webAutomationStatusFilter: Ref<WebCaseStatusFilter>
  webAutomationWorkspaceMode: Ref<'workbench' | 'monitor'>
  webAutomationTableScrollTop: Ref<number>
  webAutomationTableScrollLeft: Ref<number>
  webRunCreationError: Ref<string>
  webRunInterruptionMessage: Ref<string>
  webRunPollCoordinator: WebRunPollCoordinator
  clearLocalMessages(): void
  reset(): void
}

export const createWebExecutionController = (): WebExecutionController => {
  const webAutomationRunning = ref(false)
  const webAutomationRunMode = ref<WebAutomationRunMode>('sequential')
  const webAutomationLoopCount = ref(1)
  const webAutomationRunReport = ref<WebAutomationRunReport>()
  const activeWebRunId = ref('')
  const activeWebRunSnapshot = shallowRef<WebAutomationRunSnapshot>()
  const activeWebRunCases = shallowRef<SavedWebAutomationRunCase[]>([])
  const webRunCapabilities = shallowRef<WebAutomationCapabilities>()
  const webRunConnectionState = ref<'idle' | 'polling' | 'retrying' | 'interrupted'>('idle')
  const webRunControlLoading = ref<'pause' | 'resume' | 'stop' | ''>('')
  const webAutomationSelectedIds = ref<string[]>([])
  const webAutomationStatusFilter = ref<WebCaseStatusFilter>('ALL')
  const webAutomationWorkspaceMode = ref<'workbench' | 'monitor'>('workbench')
  const webAutomationTableScrollTop = ref(0)
  const webAutomationTableScrollLeft = ref(0)
  const webRunCreationError = ref('')
  const webRunInterruptionMessage = ref('')
  const webRunPollCoordinator = createWebRunPollCoordinator()

  const clearLocalMessages = (): void => {
    webRunCreationError.value = ''
    webRunInterruptionMessage.value = ''
  }
  const reset = (): void => {
    webRunPollCoordinator.stop()
    webAutomationRunning.value = false
    webAutomationRunMode.value = 'sequential'
    webAutomationLoopCount.value = 1
    webAutomationRunReport.value = undefined
    activeWebRunId.value = ''
    activeWebRunSnapshot.value = undefined
    activeWebRunCases.value = []
    webRunCapabilities.value = undefined
    webRunConnectionState.value = 'idle'
    webRunControlLoading.value = ''
    webAutomationSelectedIds.value = []
    webAutomationStatusFilter.value = 'ALL'
    webAutomationWorkspaceMode.value = 'workbench'
    webAutomationTableScrollTop.value = 0
    webAutomationTableScrollLeft.value = 0
    clearLocalMessages()
  }

  return {
    webAutomationRunning,
    webAutomationRunMode,
    webAutomationLoopCount,
    webAutomationRunReport,
    activeWebRunId,
    activeWebRunSnapshot,
    activeWebRunCases,
    webRunCapabilities,
    webRunConnectionState,
    webRunControlLoading,
    webAutomationSelectedIds,
    webAutomationStatusFilter,
    webAutomationWorkspaceMode,
    webAutomationTableScrollTop,
    webAutomationTableScrollLeft,
    webRunCreationError,
    webRunInterruptionMessage,
    webRunPollCoordinator,
    clearLocalMessages,
    reset
  }
}

<script setup lang="ts">
import { toRefs, ref } from 'vue'
import type { WebExecutionPageModel } from '../../ui/webExecutionPageModel'
import WebAutomationWorkspace from '../automation/WebAutomationWorkspace.vue'
import type { WebAutomationBrowser } from '../../domain/webAutomationCase'
import type { WebAutomationAction } from '../../domain/webAutomationCase'
import type { WebRuntimeBrowser } from '../../domain/executionEnvironmentRepository'
import type { WebAutomationRunStatus } from '../../domain/webAutomation'
import type { WebAutomationMockOutcome } from '../../domain/webAutomation'
import type { WebAutomationAuthenticationMode } from '../../domain/webAutomation'
import type { SavedWebAutomationRunCase } from '../../domain/webAutomationRepository'
import type { WebRunStartRequest } from '../../domain/webExecutionPreparation'
import type { WebAutomationRunControls } from '../../domain/webAutomationRunRepository'
import type { WebAutomationRunSnapshot } from '../../domain/webAutomationRunRepository'
import type { WebAutomationPreflight } from '../../domain/webAutomationWorkspaceView'
import type { WebRunTimelineItem } from '../../domain/webAutomationRunView'
import type { WebRunBrowserOption } from '../../domain/webAutomationRunView'
const props = defineProps<{ model: WebExecutionPageModel }>()
const { webAutomationWorkspaceMode, isCompactDesktop, webAutomationWorkspaceHeader, webAutomationWorkspacePreparation, webAutomationWorkspaceScope, webAutomationWorkspaceTable, webAutomationPreflight, webRunStatusMessage, activeWebAutomationWorkspaceRun, webRunSuites, webAutomationRunning, webRunRetryFailedCount, openReportsModule, openAutomationGenerateDialog, exportAutomationJson, updateWebAutomationEnvironment, updateWebAutomationBrowser, updateWebAutomationLoopCount, openEnvironmentEditor, startVisualWebAutomationRun, switchAutomationProject, switchWebAutomationModule, toggleWebAutomationCase, selectFilteredWebAutomationCases, clearWebAutomationSelection, updateWebAutomationSourceFilter, updateWebAutomationStatusFilter, updateWebAutomationKeyword, clearWebAutomationFilters, openWebAutomationRunLog, transferWebAutomationRunResultToDefect, deleteWebAutomationCase, loadWebRunCapabilities, enterWebRunMonitor, returnWebRunWorkbench, pauseActiveWebRun, resumeActiveWebRun, stopActiveWebRun, openActiveWebRunReport, saveCurrentWebRunSuite, applyWebRunSuite, deleteWebRunSuiteByName, retryFailedWebRunCases } = toRefs(props.model)
const webAutomationWorkspaceRef = ref<InstanceType<typeof WebAutomationWorkspace>>()
defineExpose({ getTableScrollElement: () => webAutomationWorkspaceRef.value?.getTableScrollElement(), focusMonitorEntry: () => webAutomationWorkspaceRef.value?.focusMonitorEntry() })
</script>

<template>
<WebAutomationWorkspace
          ref="webAutomationWorkspaceRef"
          :mode="webAutomationWorkspaceMode"
          :compact="isCompactDesktop"
          :header="webAutomationWorkspaceHeader"
          :preparation="webAutomationWorkspacePreparation"
          :scope="webAutomationWorkspaceScope"
          :table="webAutomationWorkspaceTable"
          :preflight="webAutomationPreflight"
          :status-message="webRunStatusMessage"
          :run="activeWebAutomationWorkspaceRun"
          :suites="webRunSuites"
          :retry-failed-count="webAutomationRunning ? 0 : webRunRetryFailedCount"
          @open-history="openReportsModule"
          @generate-cases="openAutomationGenerateDialog"
          @export-cases="exportAutomationJson"
          @update-environment-id="updateWebAutomationEnvironment"
          @update-browser="updateWebAutomationBrowser"
          @update-loop-count="updateWebAutomationLoopCount"
          @edit-environment="openEnvironmentEditor(undefined, 'web')"
          @execute="(request) => startVisualWebAutomationRun(undefined, request)"
          @update-project="switchAutomationProject"
          @update-module="switchWebAutomationModule"
          @toggle-case="toggleWebAutomationCase"
          @select-filtered="selectFilteredWebAutomationCases"
          @clear-selected="clearWebAutomationSelection"
          @update-source="updateWebAutomationSourceFilter"
          @update-status="updateWebAutomationStatusFilter"
          @update-keyword="updateWebAutomationKeyword"
          @clear-filters="clearWebAutomationFilters"
          @open-log="openWebAutomationRunLog"
          @transfer-defect="transferWebAutomationRunResultToDefect"
          @delete-case="deleteWebAutomationCase"
          @retry-capabilities="loadWebRunCapabilities"
          @edit-login="openEnvironmentEditor(undefined, 'web')"
          @enter-monitor="enterWebRunMonitor"
          @return-workbench="returnWebRunWorkbench"
          @pause="pauseActiveWebRun"
          @resume="resumeActiveWebRun"
          @stop="stopActiveWebRun"
          @rerun="() => startVisualWebAutomationRun()"
          @open-report="openActiveWebRunReport"
          @save-suite="saveCurrentWebRunSuite"
          @apply-suite="applyWebRunSuite"
          @delete-suite="deleteWebRunSuiteByName"
          @retry-failed="retryFailedWebRunCases"
        />
</template>

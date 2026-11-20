import type { WebAutomationBrowser } from '../domain/webAutomationCase'
import type { WebAutomationAction } from '../domain/webAutomationCase'
import type { WebRuntimeBrowser } from '../domain/executionEnvironmentRepository'
import type { WebAutomationRunStatus } from '../domain/webAutomation'
import type { WebAutomationMockOutcome } from '../domain/webAutomation'
import type { WebAutomationAuthenticationMode } from '../domain/webAutomation'
import type { SavedWebAutomationRunCase } from '../domain/webAutomationRepository'
import type { WebRunStartRequest } from '../domain/webExecutionPreparation'
import type { WebAutomationRunControls } from '../domain/webAutomationRunRepository'
import type { WebAutomationRunSnapshot } from '../domain/webAutomationRunRepository'
import type { WebAutomationPreflight } from '../domain/webAutomationWorkspaceView'
import type { WebRunTimelineItem } from '../domain/webAutomationRunView'
import type { WebRunBrowserOption } from '../domain/webAutomationRunView'
import type WebAutomationWorkspace from '../components/automation/WebAutomationWorkspace.vue'
import type { WebCaseStatusFilter } from '../components/automation/WebAutomationCaseTable.vue'

type WorkspaceProps = InstanceType<typeof WebAutomationWorkspace>['$props']

export interface WebExecutionPageModel {
  webAutomationWorkspaceMode: "workbench" | "monitor"
  isCompactDesktop: boolean
  webAutomationWorkspaceHeader: WorkspaceProps['header']
  webAutomationWorkspacePreparation: WorkspaceProps['preparation']
  webAutomationWorkspaceScope: WorkspaceProps['scope']
  webAutomationWorkspaceTable: WorkspaceProps['table']
  webAutomationPreflight: WebAutomationPreflight
  webRunStatusMessage: string
  activeWebAutomationWorkspaceRun: WorkspaceProps['run']
  webRunSuites: WorkspaceProps['suites']
  webAutomationRunning: boolean
  webRunRetryFailedCount: number
  openReportsModule: () => void
  openAutomationGenerateDialog: () => void
  exportAutomationJson: () => void
  updateWebAutomationEnvironment: (environmentId: string) => void
  updateWebAutomationBrowser: (browser: string) => void
  updateWebAutomationLoopCount: (value: number) => void
  openEnvironmentEditor: (environmentId?: string, kind?: "api" | "web") => void
  startVisualWebAutomationRun: (explicitCases?: SavedWebAutomationRunCase[], confirmedRequest?: WebRunStartRequest) => Promise<boolean>
  switchAutomationProject: (project: string) => void
  switchWebAutomationModule: (module: string) => void
  toggleWebAutomationCase: (testCase: SavedWebAutomationRunCase, checked: boolean) => void
  selectFilteredWebAutomationCases: () => void
  clearWebAutomationSelection: () => void
  updateWebAutomationSourceFilter: (value: "ALL" | "design" | "default") => void
  updateWebAutomationStatusFilter: (value: WebCaseStatusFilter) => void
  updateWebAutomationKeyword: (value: string) => void
  clearWebAutomationFilters: () => void
  openWebAutomationRunLog: (caseOrId: SavedWebAutomationRunCase | string) => void
  transferWebAutomationRunResultToDefect: (caseOrId: SavedWebAutomationRunCase | string) => void
  deleteWebAutomationCase: (testCase: SavedWebAutomationRunCase) => Promise<void>
  loadWebRunCapabilities: () => Promise<void>
  enterWebRunMonitor: () => void
  returnWebRunWorkbench: () => Promise<void>
  pauseActiveWebRun: () => Promise<void>
  resumeActiveWebRun: () => Promise<void>
  stopActiveWebRun: () => Promise<void>
  openActiveWebRunReport: () => void
  saveCurrentWebRunSuite: () => Promise<void>
  applyWebRunSuite: (name: string) => void
  deleteWebRunSuiteByName: (name: string) => void
  retryFailedWebRunCases: () => void
}

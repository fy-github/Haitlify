import type { WebAutomationAction } from '../domain/webAutomationCase'
import type { ExecutionEnvironmentLibrary } from '../domain/executionEnvironmentRepository'
import type { ApiAutomationCaseDraft } from '../domain/apiAutomation'
import type { AutomationRunMode } from '../domain/apiAutomation'
import type { AutomationRunResult } from '../domain/apiAutomation'
import type { AutomationRunSummary } from '../domain/apiAutomation'
import type { SavedAutomationCase } from '../domain/automationRepository'
import type { ApiRunMonitor } from '../domain/apiRunMonitor'
import type { AutomationCaseOption } from '../domain/automationView'
import type { DefectDraftStatus } from '../domain/defectRepository'
import type { JiraExportMode } from '../domain/defectRepository'
import type { DefectDraft } from '../domain/defectRepository'
import type { EnvironmentKind } from './appPorts'

export interface ApiExecutionPageModel {
  automationKind: "api" | "web"
  apiRunMonitor: ApiRunMonitor | undefined
  returnApiRunPreparation: () => Promise<void>
  openReportsModule: () => void
  apiAutomationWorkspaceMode: "prepare" | "monitor"
  filteredAutomationCases: SavedAutomationCase[]
  canEdit: boolean
  openEnvironmentManagement: (kind: "api" | "web") => void
  automationConfigVisible: boolean
  enterApiRunMonitor: () => Promise<void>
  apiAutomationRunning: boolean
  apiRunConfirming: boolean
  executeAllAutomationCases: () => Promise<void>
  apiExecutionScopeLabel: string
  exportAutomationJson: () => void
  automationCases: { automationLibraryId: string; savedAt: string; id: string; sourceCaseId: string; project: string; module: string; name: string; method: string; path: string; headers: Record<string, string>; pathParams: Record<string, string>; queryParams: Record<string, string>; body?: unknown; expectedResponseBody?: unknown; expectedStatus: number; assertions: string[]; runOrder: number; exportTarget: "apifox" | "design-draft"; }[]
  automationProjects: AutomationCaseOption[]
  automationModules: AutomationCaseOption[]
  automationFilter: { project: string; module: string; method: string; source: "ALL" | "design" | "default"; keyword: string; }
  switchAutomationProject: (project: string) => void
  automationMethods: string[]
  hasFilteredDesignOriginAutomationCases: boolean
  webAutomationRunning: boolean
  transferFilteredDesignAutomationResultsToDefects: () => void
  failedDesignOriginSummary: { total: number; failed: number; error: number; }
  designOriginFailureExpanded: boolean
  exportDesignOriginSummary: () => void
  activeAutomationRunSummary: AutomationRunSummary
  deleteAutomationByScope: (scope: { project?: string; module?: string; }) => Promise<void>
  automationSelection: SavedAutomationCase[]
  automationResultMap: Map<string, AutomationRunResult>
  defectDraftByReportCaseId: Map<string, { defectId: string; sourceReportCaseId: string; sourceCaseId: string; projectName: string; issueType: string; summary: string; priority: string; assignee: string; description: string; blocksIssueKey: string; faultCategory: string; status: DefectDraftStatus; jiraIssueKey?: string | undefined; jiraExportMode?: JiraExportMode | undefined; createdAt: string; updatedAt: string; jiraFieldValues: Record<string, string>; evidenceVersion?: 1 | undefined; pageName?: string | undefined; startUrl?: string | undefined; durationMs?: number | undefined; steps?: { id: string; name: string; action: WebAutomationAction; targetType: "url" | "locator" | "elementRef"; target: string; value?: string | undefined; timeoutMs?: number | undefined; screenshot?: "none" | "on_failure" | "always" | undefined; }[] | undefined; failedStepId?: string | undefined; stepLogs?: { stepId: string; phase?: string | undefined; name?: string | undefined; action?: string | undefined; target?: string | undefined; status?: string | undefined; durationMs?: number | undefined; startedAt?: string | undefined; endedAt?: string | undefined; pageUrl?: string | undefined; errorMessage?: string | undefined; errorStack?: string | undefined; }[] | undefined; stepEvidence?: { stepId?: string | undefined; actionId?: string | undefined; actionType?: string | undefined; evidenceId?: string | undefined; name?: string | undefined; action?: string | undefined; target?: string | undefined; status?: string | undefined; screenshotPath?: string | undefined; pageUrl?: string | undefined; afterPageUrl?: string | undefined; capturedAt?: string | undefined; before?: { pageUrl: string; pageKey: string; pageFingerprint: string; } | undefined; after?: { pageUrl: string; pageKey: string; pageFingerprint: string; } | undefined; execution?: { status: "passed" | "failed"; durationMs: number; } | undefined; resolvedTarget?: (Record<string, unknown> | string) | undefined; locator?: (Record<string, unknown> | string) | undefined; visible?: boolean | undefined; enabled?: boolean | undefined; receivesEvents?: boolean | undefined; unique?: boolean | undefined; observationStatus?: "passed" | "failed" | undefined; failureType?: string | undefined; }[] | undefined; screenshotPath?: string | undefined; pageHtmlSnippet?: string | undefined; errorMessage?: string | undefined; consoleErrors?: string[] | undefined; pageErrors?: string[] | undefined; failedRequests?: string[] | undefined; }>
  defectStatusLabel: (caseId: string) => string
  defectStatusTagType: (draft?: DefectDraft) => "warning" | "success" | "info"
  openAutomationCurlPreview: (testCase: ApiAutomationCaseDraft) => void
  openAutomationLog: (testCase: ApiAutomationCaseDraft) => void
  transferAutomationResultToDefect: (testCase: SavedAutomationCase) => void
  deleteAutomationCase: (testCase: SavedAutomationCase) => Promise<void>
  executionEnvironmentLibrary: ExecutionEnvironmentLibrary
  selectExecutionEnvironment: (environmentId: string, kind?: EnvironmentKind) => void
  openEnvironmentEditor: (environmentId?: string, kind?: "api" | "web") => void
  responseBodyMatchThreshold: number
  automationRunMode: AutomationRunMode
  apiRunCountValue: number
  apiRunCountMin: number
  stepApiRunCount: (delta: number) => void
  apiRunCountInput: string
  onApiRunCountInput: (event: Event) => void
  commitApiRunCount: (raw?: string) => boolean
  apiRunCountError: string
}

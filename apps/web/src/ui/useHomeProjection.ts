import { computed } from 'vue'
import { type BuiltWebExecutionRuntime } from '../domain/executionEnvironmentRepository'
import { type SavedAutomationCase } from '../domain/automationRepository'
import { type SavedWebAutomationRunCase } from '../domain/webAutomationRepository'
import { apiMonitorCounts } from '../domain/apiRunMonitor'
import { type WebAutomationRunSnapshot } from '../domain/webAutomationRunRepository'
import { type GenerationGranularity } from '../domain/caseGenerator'
import type { HomeProject, HomeRun, HomeWorkItem } from '../domain/homeWorkbench'
import type { WebAutomationAction } from '../domain/webAutomationCase'
import type { Ref } from 'vue'
import type { ComputedRef } from 'vue'
import type { ShallowRef } from 'vue'
import type { WebAutomationDocumentType } from '../domain/webAutomationCase'
import type { WebAutomationBrowser } from '../domain/webAutomationCase'
import type { WebAutomationMockOutcome } from '../domain/webAutomation'
import type { WebAutomationAuthenticationMode } from '../domain/webAutomation'
import type { AutomationRunMode } from '../domain/apiAutomation'
import type { AutomationRunStatus } from '../domain/apiAutomation'
import type { ApiRunMonitor } from '../domain/apiRunMonitor'
import type { DefectDraft } from '../domain/defectRepository'

interface Dependencies {
  savedCases: { value: { libraryId: string; savedAt: string; id: string; row: { caseId: string; project: string; module: string; interfaceName: string; method: string; path: string; title: string; caseType: string; precondition: string; headers: string; pathParams: string; queryParams: string; body: string; steps: string; expectedResult: string; expectedResponseBody?: string | undefined; actualResponseBody?: string | undefined; assertions: string; priority: string; executionResult: string; executor: string; executedAt: string; defectId: string; tags: string; sourceFile: string; remark: string; }; metadata: { sourceSpecId: string; sourceFile: string; sourceOperationId: string; tag: string; method: string; path: string; baseUrlRef: string; environmentId: string; authProfileId: string; contentType: string; headers: Record<string, string>; pathParams: Record<string, string>; queryParams: Record<string, string>; body: unknown; expectedStatus: number; assertions: string[]; expectedBodyRules: string[]; expectedResponseBody?: unknown; variableExtractions: string[]; dependsOnCaseIds: string[]; runOrder: number; generationMeta: { granularity: GenerationGranularity; strategy: string; }; reviewMeta: { status: "\u5F85\u590D\u6838"; }; exportMeta: { apifoxReady: boolean; }; }; }[] }
  savedWebCases: { value: { libraryId: string; savedAt: string; id: string; row: { caseId: string; project: string; module: string; pageName: string; title: string; naturalSteps?: string | undefined; naturalExpectedResult?: string | undefined; environmentId?: string | undefined; caseType: string; precondition: string; startUrl: string; browser: string; viewport: string; testData: string; steps: string; locators: string; assertions: string; waitStrategy: string; screenshotStrategy: string; retryCount?: number | string | undefined; priority: string; executionResult: string; executor: string; executedAt: string; defectId: string; tags: string; sourceFile: string; remark: string; }; metadata: { sourceFile: string; sourceDocumentType: WebAutomationDocumentType; pageName: string; startUrl: string; browser: WebAutomationBrowser; viewport: string; steps: { id: string; name: string; action: WebAutomationAction; targetType: "url" | "locator" | "elementRef"; target: string; value?: string | undefined; timeoutMs?: number | undefined; screenshot?: "none" | "on_failure" | "always" | undefined; }[]; locators: { id: string; name: string; locatorType: "css" | "xpath" | "text" | "role" | "testId"; value: string; fallbackValue?: string | undefined; confidence?: "high" | "medium" | "low" | undefined; reason?: string | undefined; confirmed?: boolean | undefined; }[]; assertions: { id: string; name: string; type: "visible" | "hidden" | "text" | "url"; target: string; expectedValue?: string | undefined; }[]; variables: Record<string, string>; runOrder: number; agentRunId?: string | undefined; agentSourcePointId?: string | undefined; agentEnvironmentId?: string | undefined; agentBaseUrl?: string | undefined; agentAuthenticationMode?: string | undefined; agentEvidenceIds?: string[] | undefined; agentPageKey?: string | undefined; agentPageFingerprint?: string | undefined; agentStepTraces?: { stepIndex: number; naturalStep: string; intent: string; dsl: string[]; evidenceIds: string[]; pageFingerprint: string; sourcePointId: string; }[] | undefined; agentExpectedResultEvidenceIds?: string[] | undefined; artifactId?: string | undefined; artifactRevision?: number | undefined; artifactResultOrigin?: string | undefined; artifactAcceptanceState?: string | undefined; artifactModelProfileVersion?: string | undefined; artifactFieldOrigins?: string[] | undefined; artifactEvidenceIds?: string[] | undefined; artifactParentArtifactId?: string | undefined; artifactIdempotencyKey?: string | undefined; agentActions?: { [x: string]: unknown; id: string; type: string; target?: (Record<string, unknown> | string) | undefined; assertion?: (Record<string, unknown> | string) | undefined; value?: (Record<string, unknown> | string) | undefined; safety: string; evidenceId?: string | undefined; resolvedTarget?: (Record<string, unknown> | string) | undefined; }[] | undefined; }; }[] }
  automationCases: Ref<{ automationLibraryId: string; savedAt: string; id: string; sourceCaseId: string; project: string; module: string; name: string; method: string; path: string; headers: Record<string, string>; pathParams: Record<string, string>; queryParams: Record<string, string>; body?: unknown; expectedResponseBody?: unknown; expectedStatus: number; assertions: string[]; runOrder: number; exportTarget: "apifox" | "design-draft"; }[], SavedAutomationCase[] | { automationLibraryId: string; savedAt: string; id: string; sourceCaseId: string; project: string; module: string; name: string; method: string; path: string; headers: Record<string, string>; pathParams: Record<string, string>; queryParams: Record<string, string>; body?: unknown; expectedResponseBody?: unknown; expectedStatus: number; assertions: string[]; runOrder: number; exportTarget: "apifox" | "design-draft"; }[]>
  webAutomationCases: { value: { automationLibraryId: string; savedAt: string; id: string; sourceCaseId: string; project: string; module: string; pageName: string; name: string; startUrl: string; environmentId?: string | undefined; browser: WebAutomationBrowser; viewport: string; authenticationMode: WebAutomationAuthenticationMode; steps: { id: string; name: string; action: WebAutomationAction; targetType: "url" | "locator" | "elementRef"; target: string; value?: string | undefined; timeoutMs?: number | undefined; screenshot?: "none" | "on_failure" | "always" | undefined; }[]; actions?: { [x: string]: unknown; id: string; type: string; target?: (Record<string, unknown> | string) | undefined; assertion?: (Record<string, unknown> | string) | undefined; value?: (Record<string, unknown> | string) | undefined; safety: string; evidenceId?: string | undefined; resolvedTarget?: (Record<string, unknown> | string) | undefined; }[] | undefined; runOrder: number; mockOutcome: WebAutomationMockOutcome; agentRunId?: string | undefined; agentSourcePointId?: string | undefined; agentEnvironmentId?: string | undefined; agentBaseUrl?: string | undefined; agentAuthenticationMode?: string | undefined; agentEvidenceIds?: string[] | undefined; agentPageKey?: string | undefined; agentPageFingerprint?: string | undefined; agentStepTraces?: { stepIndex: number; naturalStep: string; intent: string; dsl: string[]; evidenceIds: string[]; pageFingerprint: string; sourcePointId: string; }[] | undefined; agentExpectedResultEvidenceIds?: string[] | undefined; artifactId?: string | undefined; artifactRevision?: number | undefined; artifactResultOrigin?: string | undefined; artifactAcceptanceState?: string | undefined; artifactModelProfileVersion?: string | undefined; artifactFieldOrigins?: string[] | undefined; artifactEvidenceIds?: string[] | undefined; artifactParentArtifactId?: string | undefined; artifactIdempotencyKey?: string | undefined; }[] }
  automationReports: { value: { reportId: string; name: string; project: string; environmentName: string; moduleScope: string[]; createdAt: string; mode: AutomationRunMode; startedAt: string; endedAt: string; summary: { total: number; passed: number; failed: number; error: number; }; results: { caseId: string; sourceCaseId: string; name: string; module: string; pageName?: string | undefined; startUrl?: string | undefined; steps?: { id: string; name: string; action: WebAutomationAction; targetType: "url" | "locator" | "elementRef"; target: string; value?: string | undefined; timeoutMs?: number | undefined; screenshot?: "none" | "on_failure" | "always" | undefined; }[] | undefined; method: string; path: string; status: AutomationRunStatus; request: { method: string; url: string; headers: Record<string, string>; body?: unknown; expectedStatus: number; assertions: string[]; }; expectedStatus: number; expectedResponseBody?: unknown; actualStatus?: number | undefined; durationMs: number; failedStepId?: string | undefined; pageHtmlSnippet?: string | undefined; screenshotPath?: string | undefined; errorMessage?: string | undefined; consoleErrors?: string[] | undefined; pageErrors?: string[] | undefined; failedRequests?: string[] | undefined; stepEvidence?: { actionId: string; actionType: string; before: { pageUrl: string; pageKey: string; pageFingerprint: string; }; resolvedTarget?: (Record<string, unknown> | string) | undefined; evidenceId: string; locator?: (Record<string, unknown> | string) | undefined; visible?: boolean | undefined; enabled?: boolean | undefined; receivesEvents?: boolean | undefined; unique?: boolean | undefined; execution: { status: "passed" | "failed"; durationMs: number; }; after: { pageUrl: string; pageKey: string; pageFingerprint: string; }; observationStatus?: "passed" | "failed" | undefined; failureType?: string | undefined; }[] | undefined; responseBody?: unknown; actualResponseBody?: unknown; stepLogs?: { stepId: string; phase?: "setup" | "test" | "teardown" | undefined; name: string; action: string; target: string; status: "passed" | "failed"; durationMs: number; startedAt?: string | undefined; endedAt?: string | undefined; pageUrl?: string | undefined; errorMessage?: string | undefined; errorStack?: string | undefined; }[] | undefined; responseBodyMatch?: { matched: number; expected: number; ratio: number; missingPaths: string[]; } | undefined; }[]; }[] }
  defects: { defectDrafts: Ref<import('../domain/defectRepository').DefectDraft[]>; defectStatusText(draft: import('../domain/defectRepository').DefectDraft): string }
  apiRunMonitor: Ref<ApiRunMonitor | undefined, ApiRunMonitor | undefined>
  activeWebRunSnapshot: ShallowRef<WebAutomationRunSnapshot | undefined>
  activeWebRunCases: ShallowRef<SavedWebAutomationRunCase[]>
  runnableWebRuntime: ComputedRef<BuiltWebExecutionRuntime | undefined>
}

export function createHomeProjection(deps: Dependencies) {

const homeProjects = computed<HomeProject[]>(() => {
  const names = new Set<string>()
  for (const item of deps.savedCases.value) if (item.row.project) names.add(item.row.project)
  for (const item of deps.savedWebCases.value) if (item.row.project) names.add(item.row.project)
  for (const item of deps.automationCases.value) if (item.project) names.add(item.project)
  for (const item of deps.webAutomationCases.value) if (item.project) names.add(item.project)
  for (const item of deps.automationReports.value) if (item.project) names.add(item.project)
  for (const item of deps.defects.defectDrafts.value) if (item.projectName) names.add(item.projectName)
  return [...names].sort((left, right) => left.localeCompare(right)).map((value) => ({ value, label: value }))
})

const homeRunState = (status: string): HomeRun['state'] => {
  if (status === 'queued') return 'queued'
  if (status === 'pause_requested') return 'pause_requested'
  if (status === 'paused') return 'paused'
  if (status === 'stop_requested') return 'stop_requested'
  if (status === 'stopped') return 'stopped'
  if (status === 'failed') return 'failed'
  if (status === 'completed') return 'completed'
  return 'running'
}

const homeRuns = computed<HomeRun[]>(() => {
  const runs: HomeRun[] = []
  if (deps.apiRunMonitor.value && ['running', 'error'].includes(deps.apiRunMonitor.value.status)) {
    const monitor = deps.apiRunMonitor.value
    const counts = apiMonitorCounts(monitor)
    runs.push({
      id: `api-monitor-${monitor.startedAt}`,
      name: monitor.snapshot.scopeLabel || 'API 自动化执行',
      kind: 'api',
      project: monitor.snapshot.cases[0]?.project || '',
      environmentName: monitor.snapshot.environmentName,
      state: monitor.status === 'error' ? 'failed' : 'running',
      stage: monitor.status === 'error' ? (monitor.errorMessage || '执行异常结束') : (monitor.rows.find((row) => row.status === 'running')?.testCase.name || '执行测试用例'),
      updatedAt: monitor.endedAt || monitor.startedAt,
      completed: monitor.progress.completed,
      total: monitor.progress.total,
      passed: counts.passed,
      failed: counts.failed,
      error: counts.error,
      target: { navKey: 'automation', automationKind: 'api', monitor: true }
    })
  }
  const web = deps.activeWebRunSnapshot.value
  if (web && !['completed', 'failed', 'stopped'].includes(web.status)) {
    runs.push({
      id: `web-run-${web.runId}`,
      name: web.currentCase?.name || 'Web 自动化执行',
      kind: 'web',
      project: deps.activeWebRunCases.value[0]?.project || '',
      environmentName: deps.runnableWebRuntime.value?.baseUrl,
      state: homeRunState(web.status),
      stage: web.currentStep?.name || web.currentCase?.name || '执行测试用例',
      updatedAt: web.updatedAt,
      completed: web.progress.completedCases,
      total: web.progress.totalCases,
      passed: web.summary.passed,
      failed: web.summary.failed,
      error: web.summary.error,
      target: { navKey: 'automation', automationKind: 'web', runId: web.runId }
    })
  }
  return runs
})

const homeWorkItems = computed<HomeWorkItem[]>(() => {
  const items: HomeWorkItem[] = []
  for (const testCase of deps.savedCases.value) {
    items.push({ id: testCase.libraryId, name: testCase.row.title, project: testCase.row.project || '', kind: 'case', savedAt: testCase.savedAt, context: testCase.row.module, target: { navKey: 'cases', caseId: testCase.libraryId, project: testCase.row.project || '' } })
  }
  for (const testCase of deps.savedWebCases.value) {
    items.push({ id: testCase.libraryId, name: testCase.row.title, project: testCase.row.project || '', kind: 'case', savedAt: testCase.savedAt, context: testCase.row.module, target: { navKey: 'webCases', caseId: testCase.libraryId, project: testCase.row.project || '' } })
  }
  for (const testCase of deps.automationCases.value) {
    items.push({ id: testCase.automationLibraryId, name: testCase.name, project: testCase.project || '', kind: 'case', savedAt: testCase.savedAt, context: testCase.module, target: { navKey: 'automation', automationKind: 'api', caseId: testCase.automationLibraryId, project: testCase.project || '' } })
  }
  for (const testCase of deps.webAutomationCases.value) {
    items.push({ id: testCase.automationLibraryId, name: testCase.name, project: testCase.project || '', kind: 'case', savedAt: testCase.savedAt, context: testCase.module, target: { navKey: 'automation', automationKind: 'web', caseId: testCase.automationLibraryId, project: testCase.project || '' } })
  }
  for (const report of deps.automationReports.value) {
    items.push({ id: report.reportId, name: report.name, project: report.project || '', kind: 'report', savedAt: report.createdAt, context: report.environmentName, target: { navKey: 'reports', reportId: report.reportId, project: report.project || '' } })
  }
  for (const draft of deps.defects.defectDrafts.value) {
    items.push({ id: draft.defectId, name: draft.summary, project: draft.projectName || '', kind: 'defect', savedAt: draft.updatedAt || draft.createdAt, statusLabel: deps.defects.defectStatusText(draft), tone: draft.status === 'pending_jira_export' ? 'warning' : draft.status === 'created_in_jira' ? 'success' : 'neutral', context: draft.issueType, pending: draft.status === 'pending_jira_export' ? { label: '待导出 Jira', actionLabel: '处理', reason: '草稿尚未导出' } : undefined, target: { navKey: 'defects', defectId: draft.defectId, project: draft.projectName || '' } })
  }
  return items
})

return {
  homeProjects,
  homeRunState,
  homeRuns,
  homeWorkItems,
}
}

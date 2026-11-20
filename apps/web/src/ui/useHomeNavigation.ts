import { nextTick } from 'vue'
import { type SavedAutomationCase } from '../domain/automationRepository'
import { type GeneratedInterfaceCase, type GenerationGranularity } from '../domain/caseGenerator'
import { type SavedInterfaceCase } from '../domain/caseRepository'
import { type NavKey } from '../domain/appNavigation'
import { type HomeNavigateTarget } from '../components/views/HomeView.vue'
import type { WebCaseStatusFilter } from '../components/automation/WebAutomationCaseTable.vue'
import type { GeneratedWebAutomationCase, WebAutomationAction } from '../domain/webAutomationCase'
import type { SavedWebAutomationCase } from '../domain/webAutomationCase'
import type { Ref } from 'vue'
import type { ComputedRef } from 'vue'
import type { WebAutomationDocumentType } from '../domain/webAutomationCase'
import type { WebAutomationBrowser } from '../domain/webAutomationCase'
import type { WebAutomationMockOutcome } from '../domain/webAutomation'
import type { WebAutomationAuthenticationMode } from '../domain/webAutomation'
import type { ApiRunMonitor } from '../domain/apiRunMonitor'
import type { DefectIssueRecord } from '../domain/defectView'

type JiraConfigTestResult = {
  state: 'idle' | 'success' | 'failed' | 'stale'
  message: string
  target: string
}
interface WorkspaceSyncConflictNotice {
  projectName: string
  template: boolean
  cases: boolean
}
type NavHistoryMode = 'push' | 'replace' | 'skip'
type AuthStatus = 'checking' | 'authenticated' | 'unauthenticated'
type HomeWorkspaceStatus = 'ready' | 'loading' | 'empty' | 'unavailable' | 'forbidden' | 'error'

interface Dependencies {
  automationKind: Ref<"api" | "web", "api" | "web">
  navigateToNav: (next: NavKey, mode?: NavHistoryMode, onNavigated?: () => void) => void
  savedWebCases: { value: { libraryId: string; savedAt: string; id: string; row: { caseId: string; project: string; module: string; pageName: string; title: string; naturalSteps?: string | undefined; naturalExpectedResult?: string | undefined; environmentId?: string | undefined; caseType: string; precondition: string; startUrl: string; browser: string; viewport: string; testData: string; steps: string; locators: string; assertions: string; waitStrategy: string; screenshotStrategy: string; retryCount?: number | string | undefined; priority: string; executionResult: string; executor: string; executedAt: string; defectId: string; tags: string; sourceFile: string; remark: string; }; metadata: { sourceFile: string; sourceDocumentType: WebAutomationDocumentType; pageName: string; startUrl: string; browser: WebAutomationBrowser; viewport: string; steps: { id: string; name: string; action: WebAutomationAction; targetType: "url" | "locator" | "elementRef"; target: string; value?: string | undefined; timeoutMs?: number | undefined; screenshot?: "none" | "on_failure" | "always" | undefined; }[]; locators: { id: string; name: string; locatorType: "css" | "xpath" | "text" | "role" | "testId"; value: string; fallbackValue?: string | undefined; confidence?: "high" | "medium" | "low" | undefined; reason?: string | undefined; confirmed?: boolean | undefined; }[]; assertions: { id: string; name: string; type: "visible" | "hidden" | "text" | "url"; target: string; expectedValue?: string | undefined; }[]; variables: Record<string, string>; runOrder: number; agentRunId?: string | undefined; agentSourcePointId?: string | undefined; agentEnvironmentId?: string | undefined; agentBaseUrl?: string | undefined; agentAuthenticationMode?: string | undefined; agentEvidenceIds?: string[] | undefined; agentPageKey?: string | undefined; agentPageFingerprint?: string | undefined; agentStepTraces?: { stepIndex: number; naturalStep: string; intent: string; dsl: string[]; evidenceIds: string[]; pageFingerprint: string; sourcePointId: string; }[] | undefined; agentExpectedResultEvidenceIds?: string[] | undefined; artifactId?: string | undefined; artifactRevision?: number | undefined; artifactResultOrigin?: string | undefined; artifactAcceptanceState?: string | undefined; artifactModelProfileVersion?: string | undefined; artifactFieldOrigins?: string[] | undefined; artifactEvidenceIds?: string[] | undefined; artifactParentArtifactId?: string | undefined; artifactIdempotencyKey?: string | undefined; agentActions?: { [x: string]: unknown; id: string; type: string; target?: (Record<string, unknown> | string) | undefined; assertion?: (Record<string, unknown> | string) | undefined; value?: (Record<string, unknown> | string) | undefined; safety: string; evidenceId?: string | undefined; resolvedTarget?: (Record<string, unknown> | string) | undefined; }[] | undefined; }; }[] }
  openWebEdit: (testCase: GeneratedWebAutomationCase | SavedWebAutomationCase) => void
  savedCases: { value: { libraryId: string; savedAt: string; id: string; row: { caseId: string; project: string; module: string; interfaceName: string; method: string; path: string; title: string; caseType: string; precondition: string; headers: string; pathParams: string; queryParams: string; body: string; steps: string; expectedResult: string; expectedResponseBody?: string | undefined; actualResponseBody?: string | undefined; assertions: string; priority: string; executionResult: string; executor: string; executedAt: string; defectId: string; tags: string; sourceFile: string; remark: string; }; metadata: { sourceSpecId: string; sourceFile: string; sourceOperationId: string; tag: string; method: string; path: string; baseUrlRef: string; environmentId: string; authProfileId: string; contentType: string; headers: Record<string, string>; pathParams: Record<string, string>; queryParams: Record<string, string>; body: unknown; expectedStatus: number; assertions: string[]; expectedBodyRules: string[]; expectedResponseBody?: unknown; variableExtractions: string[]; dependsOnCaseIds: string[]; runOrder: number; generationMeta: { granularity: GenerationGranularity; strategy: string; }; reviewMeta: { status: "\u5F85\u590D\u6838"; }; exportMeta: { apifoxReady: boolean; }; }; }[] }
  openEdit: (testCase: GeneratedInterfaceCase | SavedInterfaceCase) => void
  apiRunMonitor: Ref<ApiRunMonitor | undefined, ApiRunMonitor | undefined>
  enterApiRunMonitor: () => Promise<void>
  activeWebRunId: Ref<string, string>
  enterWebRunMonitor: () => void
  webAutomationWorkspaceMode: Ref<"monitor" | "workbench", "monitor" | "workbench">
  webAutomationCases: { value: { automationLibraryId: string; savedAt: string; id: string; sourceCaseId: string; project: string; module: string; pageName: string; name: string; startUrl: string; environmentId?: string | undefined; browser: WebAutomationBrowser; viewport: string; authenticationMode: WebAutomationAuthenticationMode; steps: { id: string; name: string; action: WebAutomationAction; targetType: "url" | "locator" | "elementRef"; target: string; value?: string | undefined; timeoutMs?: number | undefined; screenshot?: "none" | "on_failure" | "always" | undefined; }[]; actions?: { [x: string]: unknown; id: string; type: string; target?: (Record<string, unknown> | string) | undefined; assertion?: (Record<string, unknown> | string) | undefined; value?: (Record<string, unknown> | string) | undefined; safety: string; evidenceId?: string | undefined; resolvedTarget?: (Record<string, unknown> | string) | undefined; }[] | undefined; runOrder: number; mockOutcome: WebAutomationMockOutcome; agentRunId?: string | undefined; agentSourcePointId?: string | undefined; agentEnvironmentId?: string | undefined; agentBaseUrl?: string | undefined; agentAuthenticationMode?: string | undefined; agentEvidenceIds?: string[] | undefined; agentPageKey?: string | undefined; agentPageFingerprint?: string | undefined; agentStepTraces?: { stepIndex: number; naturalStep: string; intent: string; dsl: string[]; evidenceIds: string[]; pageFingerprint: string; sourcePointId: string; }[] | undefined; agentExpectedResultEvidenceIds?: string[] | undefined; artifactId?: string | undefined; artifactRevision?: number | undefined; artifactResultOrigin?: string | undefined; artifactAcceptanceState?: string | undefined; artifactModelProfileVersion?: string | undefined; artifactFieldOrigins?: string[] | undefined; artifactEvidenceIds?: string[] | undefined; artifactParentArtifactId?: string | undefined; artifactIdempotencyKey?: string | undefined; }[] }
  automationFilter: { project: string; module: string; method: string; source: "ALL" | "design" | "default"; keyword: string; }
  webAutomationStatusFilter: Ref<WebCaseStatusFilter, WebCaseStatusFilter>
  webAutomationSelectedIds: Ref<string[], string[]>
  apiAutomationWorkspaceMode: Ref<"monitor" | "prepare", "monitor" | "prepare">
  automationCases: Ref<{ automationLibraryId: string; savedAt: string; id: string; sourceCaseId: string; project: string; module: string; name: string; method: string; path: string; headers: Record<string, string>; pathParams: Record<string, string>; queryParams: Record<string, string>; body?: unknown; expectedResponseBody?: unknown; expectedStatus: number; assertions: string[]; runOrder: number; exportTarget: "apifox" | "design-draft"; }[], SavedAutomationCase[] | { automationLibraryId: string; savedAt: string; id: string; sourceCaseId: string; project: string; module: string; name: string; method: string; path: string; headers: Record<string, string>; pathParams: Record<string, string>; queryParams: Record<string, string>; body?: unknown; expectedResponseBody?: unknown; expectedStatus: number; assertions: string[]; runOrder: number; exportTarget: "apifox" | "design-draft"; }[]>
  automationSelection: Ref<SavedAutomationCase[], SavedAutomationCase[]>
  selectedReportProject: Ref<string, string>
  reports: { reportEnvironmentFilter: Ref<string, string>; reportKeyword: Ref<string, string>; reportOpenRequest: Ref<number, number> }
  selectedReportId: Ref<string | undefined, string | undefined>
  defects: { defectIssueRecords: ComputedRef<DefectIssueRecord[]>; openDefectDetail: (row: DefectIssueRecord) => void }
}

export function createHomeNavigation(deps: Dependencies) {


/** 首页阶段模块按钮 → 精确落位(带 kind 先切子视图再导航)。 */
const handleHomeNavigate = (target: HomeNavigateTarget) => {
  if (target.automationKind) deps.automationKind.value = target.automationKind
  if (target.navKey === 'home') return
  if (target.navKey === 'cases' || target.navKey === 'webCases') {
    const { navKey, caseId } = target
    deps.navigateToNav(navKey, 'push', () => {
      if (!caseId) return
      if (navKey === 'webCases') {
        const found = deps.savedWebCases.value.find((item) => item.libraryId === caseId)
        if (found) deps.openWebEdit(found)
      } else {
        const found = deps.savedCases.value.find((item) => item.libraryId === caseId)
        if (found) deps.openEdit(found)
      }
    })
    return
  }
  deps.navigateToNav(target.navKey)
  if (target.navKey === 'automation' && target.automationKind === 'api' && target.monitor && deps.apiRunMonitor.value) {
    void deps.enterApiRunMonitor()
  } else if (target.navKey === 'automation' && target.automationKind === 'web' && target.runId === deps.activeWebRunId.value) {
    deps.enterWebRunMonitor()
  }
  if (target.navKey === 'automation' && target.caseId) {
    if (target.automationKind === 'web') {
      deps.webAutomationWorkspaceMode.value = 'workbench'
      const webCase = deps.webAutomationCases.value.find((item) => item.automationLibraryId === target.caseId)
      if (webCase) {
        deps.automationFilter.project = target.project || webCase.project || 'ALL'
        deps.automationFilter.module = webCase.module || 'ALL'
        deps.automationFilter.source = 'ALL'
        deps.automationFilter.keyword = ''
        deps.webAutomationStatusFilter.value = 'ALL'
        deps.webAutomationSelectedIds.value = [webCase.id]
      }
    } else {
      deps.apiAutomationWorkspaceMode.value = 'prepare'
      const apiCase = deps.automationCases.value.find((item) => item.automationLibraryId === target.caseId)
      if (apiCase) {
        deps.automationFilter.project = target.project || apiCase.project || 'ALL'
        deps.automationFilter.module = apiCase.module || 'ALL'
        deps.automationFilter.method = 'ALL'
        deps.automationFilter.keyword = ''
        deps.automationSelection.value = [apiCase]
      }
    }
  }

  if (target.navKey === 'reports' && target.reportId) {
    deps.selectedReportProject.value = target.project || deps.selectedReportProject.value
    deps.reports.reportEnvironmentFilter.value = 'ALL'
    deps.reports.reportKeyword.value = ''
    deps.selectedReportId.value = target.reportId
    deps.reports.reportOpenRequest.value += 1
  }
  if (target.navKey === 'defects' && target.defectId) {
    const row = deps.defects.defectIssueRecords.value.find((item) => item.id === target.defectId)
    if (row) nextTick(() => deps.defects.openDefectDetail(row))
  }
}

return {
  handleHomeNavigate,
}
}

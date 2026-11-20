import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { type ExecutionEnvironment } from '../domain/apiExecution'
import { deleteAutomationCases, deleteSavedAutomationCase, saveAutomationCases, type SavedAutomationCase } from '../domain/automationRepository'
import { deleteWebAutomationRunCase, deleteWebAutomationRunCases, type SavedWebAutomationRunCase } from '../domain/webAutomationRepository'
import { exportApifoxAutomationDraft, generateAutomationCasesFromInterfaceCases, removeResultsFromAutomationRunReport, type AutomationRunResult } from '../domain/apiAutomation'
import { exportWebAutomationRunCases, removeResultsFromWebAutomationRunReport, type WebAutomationRunReport } from '../domain/webAutomation'
import { type WebRunStartRequest } from '../domain/webExecutionPreparation'
import { type SavedInterfaceCase } from '../domain/caseRepository'
import { type NavKey } from '../domain/appNavigation'
import type { WebAutomationAction } from '../domain/webAutomationCase'
import type { SavedWebAutomationCase } from '../domain/webAutomationCase'
import type { Ref } from 'vue'
import type { ComputedRef } from 'vue'
import type { WebAutomationBrowser } from '../domain/webAutomationCase'
import type { WebAutomationMockOutcome } from '../domain/webAutomation'
import type { WebAutomationAuthenticationMode } from '../domain/webAutomation'
import type { WebAutomationEffectiveScope } from '../domain/webAutomationWorkspaceView'
import type { CaseDesignNavKind } from '../domain/appNavigation'
import type { AutomationRunReport } from '../domain/apiAutomation'
import type { AutomationGenerationScope } from '../domain/automationGenerationScope'

type NavHistoryMode = 'push' | 'replace' | 'skip'

interface Dependencies {
  libraryCaseKind: Ref<CaseDesignNavKind, CaseDesignNavKind>
  convertWebLibraryCasesToAutomation: (cases: SavedWebAutomationCase[], closeDialog: boolean) => void
  automationGenerationScope: ComputedRef<AutomationGenerationScope<SavedInterfaceCase | SavedWebAutomationCase>>
  localStorage: Storage
  refreshAutomationCases: () => void
  resetAutomationFilters: () => void
  navigateToNav: (next: NavKey, mode?: NavHistoryMode, onNavigated?: () => void) => void
  filteredAutomationCases: ComputedRef<SavedAutomationCase[]>
  resolveRunnableEnvironment: () => ExecutionEnvironment | undefined
  downloadTextFile: (filename: string, content: string, type: string) => void
  automationFilter: { project: string; module: string; method: string; source: "ALL" | "design" | "default"; keyword: string; }
  effectiveWebAutomationScope: ComputedRef<WebAutomationEffectiveScope>
  automationKind: Ref<"api" | "web", "api" | "web">
  confirmDangerAction: (title: string, message: string, confirmButtonText?: string) => Promise<boolean>
  automationSelection: Ref<SavedAutomationCase[], SavedAutomationCase[]>
  filteredWebAutomationCases: { value: { automationLibraryId: string; savedAt: string; id: string; sourceCaseId: string; project: string; module: string; pageName: string; name: string; startUrl: string; environmentId?: string | undefined; browser: WebAutomationBrowser; viewport: string; authenticationMode: WebAutomationAuthenticationMode; steps: { id: string; name: string; action: WebAutomationAction; targetType: "url" | "locator" | "elementRef"; target: string; value?: string | undefined; timeoutMs?: number | undefined; screenshot?: "none" | "on_failure" | "always" | undefined; }[]; actions?: { [x: string]: unknown; id: string; type: string; target?: (Record<string, unknown> | string) | undefined; assertion?: (Record<string, unknown> | string) | undefined; value?: (Record<string, unknown> | string) | undefined; safety: string; evidenceId?: string | undefined; resolvedTarget?: (Record<string, unknown> | string) | undefined; }[] | undefined; runOrder: number; mockOutcome: WebAutomationMockOutcome; agentRunId?: string | undefined; agentSourcePointId?: string | undefined; agentEnvironmentId?: string | undefined; agentBaseUrl?: string | undefined; agentAuthenticationMode?: string | undefined; agentEvidenceIds?: string[] | undefined; agentPageKey?: string | undefined; agentPageFingerprint?: string | undefined; agentStepTraces?: { stepIndex: number; naturalStep: string; intent: string; dsl: string[]; evidenceIds: string[]; pageFingerprint: string; sourcePointId: string; }[] | undefined; agentExpectedResultEvidenceIds?: string[] | undefined; artifactId?: string | undefined; artifactRevision?: number | undefined; artifactResultOrigin?: string | undefined; artifactAcceptanceState?: string | undefined; artifactModelProfileVersion?: string | undefined; artifactFieldOrigins?: string[] | undefined; artifactEvidenceIds?: string[] | undefined; artifactParentArtifactId?: string | undefined; artifactIdempotencyKey?: string | undefined; }[] }
  pruneWebAutomationSelection: () => void
  clearWebRunLocalMessages: () => void
  webAutomationRunReport: Ref<WebAutomationRunReport | undefined, WebAutomationRunReport | undefined>
  automationRunReport: Ref<AutomationRunReport | undefined, AutomationRunReport | undefined>
  selectedAutomationResult: Ref<AutomationRunResult | undefined, AutomationRunResult | undefined>
  automationLogVisible: Ref<boolean, boolean>
  startVisualWebAutomationRun: (explicitCases?: SavedWebAutomationRunCase[], confirmedRequest?: WebRunStartRequest) => Promise<boolean>
  apiExecutionController: { executeAllAutomationCases(): Promise<boolean> }
}

export function createExecutionAssets(deps: Dependencies) {

const automationGenerateVisible = ref(false)

const automationGenerateProject = ref('ALL')

const automationGenerateModule = ref('ALL')

const openAutomationGenerateDialog = () => {
  automationGenerateProject.value = 'ALL'
  automationGenerateModule.value = 'ALL'
  automationGenerateVisible.value = true
}

const confirmGenerateAutomationFromLibrary = () => {
  if (deps.libraryCaseKind.value === 'web_automation') {
    deps.convertWebLibraryCasesToAutomation(deps.automationGenerationScope.value.cases as SavedWebAutomationCase[], true)
    return
  }

  const interfaceCases = deps.automationGenerationScope.value.cases as SavedInterfaceCase[]
  if (interfaceCases.length === 0) {
    ElMessage.warning('没有可转换的已保存用例')
    return
  }
  const result = saveAutomationCases(deps.localStorage, generateAutomationCasesFromInterfaceCases(interfaceCases))
  clearAutomationExecutionState('api')
  deps.refreshAutomationCases()
  deps.resetAutomationFilters()
  automationGenerateVisible.value = false
  deps.navigateToNav('automation')
  ElMessage.success(`已保存 ${result.inserted} 条自动化用例，更新 ${result.updated} 条`)
}

const exportApiAutomationJson = () => {
  if (deps.filteredAutomationCases.value.length === 0) {
    ElMessage.warning('没有可导出的自动化用例')
    return
  }

  const environment = deps.resolveRunnableEnvironment()
  if (!environment) return

  const packageData = exportApifoxAutomationDraft(deps.filteredAutomationCases.value, {
    environment
  })
  deps.downloadTextFile(
    `${deps.automationFilter.project !== 'ALL' ? deps.automationFilter.project : '全部项目'}-API自动化用例-apifox草稿.json`,
    JSON.stringify(packageData, null, 2),
    'application/json;charset=utf-8'
  )
}

const exportWebAutomationJson = () => {
  const scope = deps.effectiveWebAutomationScope.value
  if (scope.cases.length === 0) {
    ElMessage.warning('没有可导出的 Web 自动化用例')
    return
  }

  const packageData = exportWebAutomationRunCases(scope.cases)
  deps.downloadTextFile(
    `${deps.automationFilter.project !== 'ALL' ? deps.automationFilter.project : '全部项目'}-Web自动化用例.json`,
    JSON.stringify(packageData, null, 2),
    'application/json;charset=utf-8'
  )
}

const exportAutomationJson = () => {
  if (deps.automationKind.value === 'web') {
    exportWebAutomationJson()
    return
  }
  exportApiAutomationJson()
}

const executeAllAutomationCases = async () => {
  if (deps.automationKind.value === 'web') {
    await deps.startVisualWebAutomationRun()
    return
  }
  await deps.apiExecutionController.executeAllAutomationCases()
}

const deleteWebAutomationCase = async (testCase: SavedWebAutomationRunCase) => {
  const confirmed = await deps.confirmDangerAction('删除 Web 自动化用例', `确认删除 ${testCase.id} 吗？`)
  if (!confirmed) return
  deleteWebAutomationRunCase(deps.localStorage, testCase.automationLibraryId)
  pruneAutomationExecutionState([testCase.id])
  deps.refreshAutomationCases()
  deps.pruneWebAutomationSelection()
  deps.clearWebRunLocalMessages()
  ElMessage.success('已删除 Web 自动化用例')
}

const clearAutomationExecutionState = (kind: 'api' | 'web' = deps.automationKind.value) => {
  if (kind === 'web') {
    deps.webAutomationRunReport.value = undefined
  } else {
    deps.automationRunReport.value = undefined
  }
  deps.selectedAutomationResult.value = undefined
  deps.automationLogVisible.value = false
}

const pruneAutomationExecutionState = (caseIds: string[]) => {
  if (deps.automationKind.value === 'web') {
    deps.webAutomationRunReport.value = removeResultsFromWebAutomationRunReport(deps.webAutomationRunReport.value, caseIds)
    return
  }
  deps.automationRunReport.value = removeResultsFromAutomationRunReport(deps.automationRunReport.value, caseIds)
  if (!deps.automationRunReport.value || (deps.selectedAutomationResult.value && caseIds.includes(deps.selectedAutomationResult.value.caseId))) {
    deps.selectedAutomationResult.value = undefined
    deps.automationLogVisible.value = false
  }
}

const deleteAutomationCase = async (testCase: SavedAutomationCase) => {
  const confirmed = await deps.confirmDangerAction('删除自动化用例', `确认删除 ${testCase.id} 吗？`)
  if (!confirmed) return
  deleteSavedAutomationCase(deps.localStorage, testCase.automationLibraryId)
  pruneAutomationExecutionState([testCase.id])
  deps.refreshAutomationCases()
  deps.automationSelection.value = deps.automationSelection.value.filter((item) => item.automationLibraryId !== testCase.automationLibraryId)
  ElMessage.success('已删除自动化用例')
}

const deleteAutomationByScope = async (scope: { project?: string; module?: string }) => {
  if (deps.automationKind.value === 'web') {
    if (deps.filteredWebAutomationCases.value.length === 0) {
      ElMessage.warning('当前范围没有可删除的 Web 自动化用例')
      return
    }
    const confirmed = await deps.confirmDangerAction(
      '删除当前范围',
      `确认删除当前范围的 ${deps.filteredWebAutomationCases.value.length} 条 Web 自动化用例吗？`
    )
    if (!confirmed) return
    const removingCases = deps.filteredWebAutomationCases.value
    const removingIds = removingCases.map((item) => item.id)
    const deleted = deleteWebAutomationRunCases(deps.localStorage, removingCases.map((item) => item.automationLibraryId))
    pruneAutomationExecutionState(removingIds)
    deps.refreshAutomationCases()
    deps.pruneWebAutomationSelection()
    deps.clearWebRunLocalMessages()
    ElMessage.success(`已删除 ${deleted} 条 Web 自动化用例`)
    return
  }
  if (deps.filteredAutomationCases.value.length === 0) {
    ElMessage.warning('当前范围没有可删除的自动化用例')
    return
  }
  const confirmed = await deps.confirmDangerAction(
    '删除当前范围',
    `确认删除当前范围的 ${deps.filteredAutomationCases.value.length} 条自动化用例吗？`
  )
  if (!confirmed) return
  const removingCases = deps.filteredAutomationCases.value
  const removingIds = removingCases.map((item) => item.id)
  const deleted = deleteAutomationCases(deps.localStorage, removingCases.map((item) => item.automationLibraryId))
  pruneAutomationExecutionState(removingIds)
  deps.refreshAutomationCases()
  deps.automationSelection.value = []
  ElMessage.success(`已删除 ${deleted} 条自动化用例`)
}

const reset = () => { automationGenerateVisible.value = false }
return {
  reset,
  automationGenerateVisible,
  automationGenerateProject,
  automationGenerateModule,
  openAutomationGenerateDialog,
  confirmGenerateAutomationFromLibrary,
  exportApiAutomationJson,
  exportWebAutomationJson,
  exportAutomationJson,
  executeAllAutomationCases,
  deleteWebAutomationCase,
  clearAutomationExecutionState,
  pruneAutomationExecutionState,
  deleteAutomationCase,
  deleteAutomationByScope,
}
}

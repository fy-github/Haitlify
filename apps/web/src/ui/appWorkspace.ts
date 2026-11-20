import { installAppLifecycle } from './appLifecycle'
import { useOverviewAvailability } from './useOverviewAvailability'
import { createProjectPortability } from './useProjectPortability'
import { createHomeNavigation } from './useHomeNavigation'
import { createAppShell } from './useAppShell'
import { createExecutionEvidence } from './useExecutionEvidence'
import AppRouteView from '../components/AppRouteView.vue'
import { createAccountSettings } from './useAccountSettings'
import { createExecutionAssets } from './useExecutionAssets'
import { createWebPreparation } from './useWebPreparation'
import { createWebWorkspaceModel } from './useWebWorkspaceModel'
import { createExecutionCatalog } from './useExecutionCatalog'
import { createEnvironmentEditor } from './useEnvironmentEditor'
import { createBrowserExecution } from './browserExecution'
import { createWorkspaceSync } from './useWorkspaceSync'
import { createHomeProjection } from './useHomeProjection'
import { createCaseEditor } from './useCaseEditor'
import { createWebLoginVerification } from './useWebLoginVerification'
import { createWebLoginDiscoveryState } from './useWebLoginDiscovery'
import { createJiraSettings } from './useJiraSettings'
import { proxyRefs, computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { defaultWebExecutionEnvironmentLibrary, type SavedExecutionEnvironment } from '../domain/executionEnvironmentRepository'
import { listWebRunSuites } from '../domain/webRunSuiteRepository'
import { type GenerationGranularity } from '../domain/caseGenerator'
import { type ParsedOpenApiDocument } from '../domain/openapiParser'
import { fetchCurrentUser, logout, type AuthUser } from '../domain/authRepository'
import { getAuthSessionEpoch } from '../domain/http'
import { useDefects } from './useDefects'
import { useReports } from './useReports'
import { createWorkspaceStorage } from './workspaceStorage'
import { createWorkspaceRuntime } from './useWorkspaceRuntime'
import { createAppSession } from './useAppSession'
import { createTemplateOperationAdapter } from './templateOperationAdapter'
import { createEnvironmentController } from './useEnvironmentController'
import { createApiExecutionController } from './useApiExecutionController'
import { createWebExecutionController } from './useWebExecutionController'
import { createAppNavigation } from './useAppNavigation'
import { useTemplateConfig } from './useTemplateConfig'
import { provideCases, useCases } from './useCases'
import { type ImportedOpenApiProject } from '../domain/openapiProjectRepository'
import { loadJiraCsvTemplate } from '../domain/jiraCsvTemplateRepository'
import { primaryAreas } from './navigation/appSidebar'

export function createAppWorkspace() {

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

const { downloadTextFile, executeBrowserRequest } = createBrowserExecution({})

const authStatus = ref<AuthStatus>('checking')

const currentUser = ref<AuthUser | null>(null)

let authDisposed = false

let invalidateTemplateWorkspace: () => void = () => undefined

const workspaceRuntime = createWorkspaceRuntime({
  storage: createWorkspaceStorage(window.localStorage),
  sessionStorage: createWorkspaceStorage(window.sessionStorage),
  isAuthenticated: () => authStatus.value === 'authenticated',
  isDisposed: () => authDisposed,
  onWorkspaceBegin: (templateLease) => {
    if (!templateLease) invalidateTemplateWorkspace()
  }
})

const workspaceStorage = workspaceRuntime.storage

const workspaceSessionStorage = workspaceRuntime.sessionStorage

const localStorage = workspaceStorage.storage

const sessionStorage = workspaceSessionStorage.storage

const isCurrentAuth = workspaceRuntime.isCurrentAuth

const workspaceOperationId = workspaceRuntime.operationId

const beginWorkspaceOperation = (resource: string, templateLease = false): () => boolean =>
  workspaceRuntime.begin(resource, templateLease)

const workspaceFetch = (isCurrent: () => boolean) => workspaceRuntime.fetch(isCurrent)

const authSession = createAppSession({
  workspace: workspaceRuntime,
  authStatus,
  currentUser,
  fetchCurrentUser,
  logout,
  onBeforeReset: (user) => {
    casesModule.invalidateLibraryActions()
    if (user) writeWebMonitorQuery('workbench', '', 'replace')
  },
  onReset: () => {
    resetWorkspaceState()
    accountSettings.reset()
  },
  onReady: () => {
    templateConfigModule.bindWorkspace()
    loadWorkspaceData()
  },
  onMustChangePassword: () => openChangePasswordDialog(),
  onStop: () => stopBackgroundActivity()
})

const handleSessionExpired = authSession.handleSessionExpired

const onLoginSuccess = authSession.onLoginSuccess

const bootstrapAuth = authSession.bootstrap

const handleLogout = authSession.handleLogout

const appShell = createAppShell({
  get importedProjects() { return importedProjects },
  get activeProjectId() { return activeProjectId },
  get requirementAnalysisProjectName() { return requirementAnalysisProjectName },
  get activeNav() { return activeNav },
  get generationKind() { return generationKind },
  get automationKind() { return automationKind },
  get webAutomationRunning() { return webAutomationRunning },
  get apiAutomationRunning() { return apiAutomationRunning },
  get apiAutomationProgress() { return apiAutomationProgress },
  get activeWebRunId() { return activeWebRunId },
  get navigateToNav() { return navigateToNav },
  get environmentVisible() { return environmentVisible },
  get applyNavChange() { return applyNavChange },
  get enterWebRunMonitor() { return enterWebRunMonitor },
  get apiRunMonitor() { return apiRunMonitor },
  get enterApiRunMonitor() { return enterApiRunMonitor },
  get homeViewRef() { return homeViewRef },
})

const { savedCollapsed, railCollapsed, viewportMode, isOnline, isCompactDesktop, isNarrowViewport, narrowNavigationVisible, helpVisible, compactContextVisible, compactInspectorVisible, globalSearch, homeSection, activeProject, globalProjectLabel, studioProjectLabel, studioSectionLabel, isSettingsCenterNav, isQualityWorkbenchNav, isCaseLibraryNav, isCaseWorkbenchNav, isWorkbenchNav, globalLongTaskLabel, openActiveLongTask, currentNav, activeSidebarItemId, activePrimaryId, sidebarBadges, toggleRail, toggleStudioNavigation, selectSidebarItem, selectPrimaryArea, openAssistantPage, expandStudioNavigation } = appShell

const appNavigation = createAppNavigation({
  guards: [
    {
      applies: (from, to) => from !== to && defects.defectRecoveryDirty.value,
      capture: () => {
        const epoch = getAuthSessionEpoch()
        const owner = currentUser.value?.id
        return () => epoch === getAuthSessionEpoch() && currentUser.value?.id === owner
      },
      confirm: () => defects.confirmDiscardDefectChanges()
    },
    {
      applies: (from, to) => from === 'jiraConfig' && to !== 'jiraConfig' && jiraConfigDirty.value,
      capture: () => captureJiraIntent(),
      confirm: () => confirmDiscardJiraDraft('离开「Jira 配置」会丢弃当前未保存的连接设置与 JQL 映射。')
    },
    {
      applies: (from, to) => from === 'environments' && to !== 'environments' && environmentDraftDirty.value,
      capture: () => captureEnvironmentIntent(),
      confirm: () => confirmDiscardEnvironmentDraft('离开「环境配置」会丢弃当前未保存的环境修改。'),
      discard: () => discardEnvironmentDraft()
    }
  ],
  beforeChange: (from, to) => {
    if (from === to) return
    if (isCaseLibraryNav(from)) {
      casesModule.invalidateLibraryActions()
      editVisible.value = false
      curlVisible.value = false
      automationGenerateVisible.value = false
      selectedCase.value = undefined
      selectedLibraryId.value = undefined
      casesModule.bulkEditVisible.value = false
      casesModule.fieldAssignmentVisible.value = false
    }
    if (isCaseLibraryNav(to)) casesModule.switchLibraryCaseKind(to === 'webCases' ? 'web_automation' : 'interface')
  },
  afterPop: () => {
    if (activeNav.value === 'automation' && automationKind.value === 'web') void recoverWebRunSession('replace')
    else webAutomationWorkspaceMode.value = 'workbench'
  }
})

const { activeNav, automationKind, navigateToNav, syncNavWithLocation,
  restoreLocationForActiveNav, handlePopState, writeWebMonitorQuery, applyNavChange } = appNavigation

const importedProjects = ref<ImportedOpenApiProject[]>([])

const activeProjectId = ref('')

const requirementAnalysisProjectName = ref('默认项目')

const generationKind = ref<'interface' | 'web_automation'>('web_automation')

const executionCatalog = createExecutionCatalog({
  get localStorage() { return localStorage },
  get webAutomationStatusFilter() { return webAutomationStatusFilter },
  get webAutomationResultStatus() { return webAutomationResultStatus },
  get webAutomationSelectedIds() { return webAutomationSelectedIds },
  get libraryCaseKind() { return libraryCaseKind },
  get filteredLibraryCases() { return filteredLibraryCases },
  get filteredWebLibraryCases() { return filteredWebLibraryCases },
  get automationGenerateProject() { return automationGenerateProject },
  get automationGenerateModule() { return automationGenerateModule },
})

const { automationCases, webAutomationCases, automationFilter, automationCaseIndex, automationProjects, webAutomationProjects, automationProjectRecords, webAutomationProjectCases, automationModules, automationMethods, filteredAutomationRecords, filteredAutomationCases, filteredWebAutomationCases, effectiveWebAutomationScope, automationGenerationScope, refreshAutomationCases, resetAutomationFilters, collectWebAutomationProjects } = executionCatalog

const apiExecutionController = createApiExecutionController({
  getAvailableCaseCount: () => filteredAutomationCases.value.length,
  getFilteredCases: () => filteredAutomationCases.value,
  getCanEdit: () => canEdit.value,
  getOwnerId: () => currentUser.value?.id ?? undefined,
  getEnvironment: () => resolveRunnableEnvironment(),
  getEnvironmentName: () => activeApiExecutionEnvironment.value?.name || '默认环境',
  beginOperation: (resource) => beginWorkspaceOperation(resource),
  captureStorage: (isCurrent) => workspaceStorage.capture(isCurrent),
  executeRequest: (request, isCurrent) => executeBrowserRequest(request, isCurrent),
  enterMonitor: () => enterApiRunMonitor(),
  refreshReports: () => refreshAutomationReports(),
  selectReport: (project, reportId) => {
    selectedReportProject.value = project
    selectedReportId.value = reportId
  },
  notify: {
    warning: (message) => ElMessage.warning(message),
    success: (message) => ElMessage.success(message),
    error: (message) => ElMessage.error(message)
  }
})

const {
  automationRunMode,
  automationConcurrencyLimit,
  apiAutomationLoopCount,
  responseBodyMatchThreshold,
  apiRunCountMin,
  apiRunCountValue,
  apiRunCountInput,
  apiRunCountError,
  apiRunSnapshot,
  apiRunMonitor,
  apiAutomationWorkspaceMode,
  apiRunConfirming,
  apiAutomationRunning,
  apiAutomationProgress,
  automationSelection,
  automationRunReport,
  apiRunGeneration,
  apiExecutionScopeLabel,
  commitApiRunCount,
  stepApiRunCount
} = apiExecutionController

const webExecutionController = createWebExecutionController({
  begin: beginWorkspaceOperation,
  operationId: workspaceOperationId,
  storage: localStorage,
  sessionStorage,
  getSavedCases: () => webAutomationCases.value,
  getStartContext: () => ({
    request: captureWebRunStartRequest(),
    cases: effectiveWebAutomationScope.value.cases,
    environment: activeWebExecutionEnvironment.value || defaultWebExecutionEnvironmentLibrary().environments[0],
    canExecute: webAutomationPreflight.value.canExecute,
    blockingReason: webAutomationPreflight.value.blockingReason
  }),
  resolveProject: (cases, current) => resolveVisualWebAutomationRunProjectId(cases, current),
  reportContext: () => ({
    project: automationFilter.project !== 'ALL' ? automationFilter.project : (activeWebRunCases.value[0]?.project || '全部项目'),
    moduleScope: automationFilter.module !== 'ALL' ? [automationFilter.module] : [...new Set(activeWebRunCases.value.map(testCase => testCase.module))],
    environmentName: activeWebExecutionEnvironment.value?.name || 'Web 默认环境'
  }),
  onReport: report => {
    refreshAutomationReports()
    selectedReportProject.value = report.project
    selectedReportId.value = report.reportId
  },
  openEnvironment: environmentId => openEnvironmentEditor(environmentId, 'web'),
  writeMonitorQuery: (...args) => writeWebMonitorQuery(...args),
  getSearch: () => window.location.search,
  notify: (kind, message, terminal) => ElMessage[kind]({ message, ...(terminal ? { customClass: 'web-run-terminal-feedback' } : {}) })
})

const {
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
} = webExecutionController

const { startVisualWebAutomationRun, monitorActiveWebRun, recoverWebRunSession,
  pauseActiveWebRun, resumeActiveWebRun, stopActiveWebRun } = webExecutionController

const onApiRunCountInput = (event: Event) => {
  const target = event.target as HTMLInputElement | null
  apiExecutionController.onApiRunCountInput(target?.value ?? '')
}

const appRouteRef = ref<InstanceType<typeof AppRouteView>>()

const apiExecutionPageRef = computed(() => appRouteRef.value?.apiExecutionPageRef)

const apiPreparationScroll = ref({ top: 0, left: 0 })

const enterApiRunMonitor = async () => {
  if (!apiRunMonitor.value) return
  const isCurrent = beginWorkspaceOperation('apiMonitorNavigation')
  const monitor = apiRunMonitor.value
  const scroll = apiExecutionPageRef.value?.preparationScroll()
  apiPreparationScroll.value = { top: scroll?.scrollTop || 0, left: scroll?.scrollLeft || 0 }
  apiAutomationWorkspaceMode.value = 'monitor'
  await nextTick()
  if (isCurrent() && apiRunMonitor.value === monitor && apiAutomationWorkspaceMode.value === 'monitor') apiExecutionPageRef.value?.focusHeading()
}

const returnApiRunPreparation = async () => {
  const isCurrent = beginWorkspaceOperation('apiMonitorNavigation')
  apiAutomationWorkspaceMode.value = 'prepare'
  await nextTick()
  if (!isCurrent() || apiAutomationWorkspaceMode.value !== 'prepare') return
  const scroll = apiExecutionPageRef.value?.preparationScroll()
  if (scroll) { scroll.scrollTop = apiPreparationScroll.value.top; scroll.scrollLeft = apiPreparationScroll.value.left }
  apiExecutionPageRef.value?.focusMonitorEntry()
}

const executionAssets = createExecutionAssets({
  get libraryCaseKind() { return libraryCaseKind },
  get convertWebLibraryCasesToAutomation() { return convertWebLibraryCasesToAutomation },
  get automationGenerationScope() { return automationGenerationScope },
  get localStorage() { return localStorage },
  get refreshAutomationCases() { return refreshAutomationCases },
  get resetAutomationFilters() { return resetAutomationFilters },
  get navigateToNav() { return navigateToNav },
  get filteredAutomationCases() { return filteredAutomationCases },
  get resolveRunnableEnvironment() { return resolveRunnableEnvironment },
  get downloadTextFile() { return downloadTextFile },
  get automationFilter() { return automationFilter },
  get effectiveWebAutomationScope() { return effectiveWebAutomationScope },
  get automationKind() { return automationKind },
  get confirmDangerAction() { return confirmDangerAction },
  get automationSelection() { return automationSelection },
  get filteredWebAutomationCases() { return filteredWebAutomationCases },
  get pruneWebAutomationSelection() { return pruneWebAutomationSelection },
  get clearWebRunLocalMessages() { return clearWebRunLocalMessages },
  get webAutomationRunReport() { return webAutomationRunReport },
  get automationRunReport() { return automationRunReport },
  get selectedAutomationResult() { return selectedAutomationResult },
  get automationLogVisible() { return automationLogVisible },
  get startVisualWebAutomationRun() { return startVisualWebAutomationRun },
  get apiExecutionController() { return apiExecutionController },
})

const { automationGenerateVisible, automationGenerateProject, automationGenerateModule, openAutomationGenerateDialog, confirmGenerateAutomationFromLibrary, exportApiAutomationJson, exportWebAutomationJson, exportAutomationJson, executeAllAutomationCases, deleteWebAutomationCase, clearAutomationExecutionState, pruneAutomationExecutionState, deleteAutomationCase, deleteAutomationByScope } = executionAssets

const webAutomationWorkspaceRef = computed(() => appRouteRef.value?.webAutomationWorkspaceRef)

const jiraSettings = createJiraSettings({
  get beginWorkspaceOperation() { return beginWorkspaceOperation },
  get canEdit() { return canEdit },
  get confirmDangerAction() { return confirmDangerAction },
})

const { jiraConfigForm, jiraConfigLoading, jiraConfigReadState, jiraConfigTesting, jiraProjectJqlRows, jiraConfigTestResult, jiraConfigSavedSnapshot, jiraConfigValidationError, jiraDraftSnapshot, jiraConfigDirty, applyJiraConfig, syncJiraProjectJqlFormFromRows, currentJiraPayload, confirmDiscardJiraDraft, captureJiraIntent, rejectJiraWrite, addJiraProjectJqlRow, removeJiraProjectJqlRow, loadJiraSettingsFromBackend, saveJiraSettings, testJiraConnection } = jiraSettings

const templateOperationAdapter = createTemplateOperationAdapter({
  beginTransport: () => {
    const isCurrent = beginWorkspaceOperation('workspace', true)
    return { fetch: workspaceFetch(isCurrent), projectName: currentBackendProjectName(), isCurrent }
  }
})

const templateConfigModule = useTemplateConfig({
  storage: localStorage,
  identity: () => workspaceStorage.captureStrict(() => true),
  operationAdapter: templateOperationAdapter,
  confirmDanger: (title, message, confirmLabel) => confirmDangerAction(title, message, confirmLabel),
  getGenerationSelectedCaseTypes: () => generation.selectedCaseTypeIds,
  setGenerationSelectedCaseTypes: (ids) => {
    generation.selectedCaseTypeIds = ids
  }
})

invalidateTemplateWorkspace = () => templateConfigModule.invalidateWorkspace()

const {
  templateConfig,
  selectedTemplateId,
  generationTemplateId,
  interfaceFields,
  webAutomationFields,
  templateOptions,
  interfaceCaseTypes,
  enabledInterfaceCaseTypeIds,
  fieldCellLabel,
  isMultilineField,
  createCaseTemplate,
  syncSelectedCaseTypes
} = templateConfigModule

const casesModule = useCases({
  storage: localStorage,
  initialLibraryKind: activeNav.value === 'webCases' ? 'web_automation' : 'interface',
  confirmDanger: (title, message, confirmLabel) => confirmDangerAction(title, message, confirmLabel),
  navigateToNav: (next) => navigateToNav(next),
  getWebAutomationCases: () => webAutomationCases.value,
  refreshAutomationCases: () => refreshAutomationCases(),
  resetAutomationFilters: () => resetAutomationFilters(),
  setAutomationKind: (kind) => { automationKind.value = kind },
  setAutomationGenerateVisible: (visible) => { automationGenerateVisible.value = visible },
  getEmptyEditRow: () => emptyEditRow(),
  getSelectedCase: () => selectedCase.value,
  setSelectedCase: (testCase) => { selectedCase.value = testCase },
  openEdit: (testCase) => openEdit(testCase),
  openWebEdit: (testCase) => openWebEdit(testCase),
  openCurlPreview: (testCase) => openCurlPreview(testCase),
  openAutomationGenerateDialog: () => openAutomationGenerateDialog(),
  backupProjectData: () => backupProjectData(),
  onProjectDataSelected: (event) => onProjectDataSelected(event)
})

provideCases(casesModule)

const {
  savedCases,
  savedWebCases,
  libraryCaseKind,
  libraryFilter,
  filteredLibraryCases,
  filteredWebLibraryCases,
  libraryProjects,
  libraryModules,
  switchLibraryProject,
  refreshSavedCases,
  convertWebLibraryCasesToAutomation,
  exportDesignOriginSummary,
  visibleInterfaceFields
} = casesModule

const caseEditor = createCaseEditor({
  get webExecutionEnvironmentLibrary() { return webExecutionEnvironmentLibrary },
  get activeWebExecutionEnvironment() { return activeWebExecutionEnvironment },
  get localStorage() { return localStorage },
  get refreshSavedCases() { return refreshSavedCases },
  get interfaceFields() { return interfaceFields },
})

const { selectedCase, selectedLibraryId, editVisible, editRow, webEditRow, openEdit, openWebEdit, applySelectedWebEnvironmentToEditRow, saveEdit, isWebEditableCase, emptyEditRow, emptyWebEditRow } = caseEditor

const executionEvidence = createExecutionEvidence({
  get webAutomationCases() { return webAutomationCases },
  get automationRunReport() { return automationRunReport },
  get webAutomationRunReport() { return webAutomationRunReport },
  get automationKind() { return automationKind },
  get activeWebRunCases() { return activeWebRunCases },
  get defects() { return defects },
  get filteredWebAutomationCases() { return filteredWebAutomationCases },
  get jiraProjectName() { return jiraProjectName },
  get resolveRunnableEnvironment() { return resolveRunnableEnvironment },
  get automationFilter() { return automationFilter },
})

const { curlVisible, automationLogVisible, curlText, executableRequest, selectedAutomationResult, actionIrViewerVisible, selectedActionIrCase, hasFilteredDesignOriginAutomationCases, failedDesignOriginResults, failedDesignOriginSummary, designOriginFailureExpanded, automationResultMap, webAutomationResultMap, automationRunSummary, activeAutomationRunSummary, activeWebRunResultById, openWebAutomationRunLog, transferWebAutomationRunResultToDefect, transferAutomationResultToDefect, transferWebAutomationResultToDefect, transferFilteredDesignAutomationResultsToDefects, openAutomationCurlPreview, openAutomationLog, openWebAutomationLog, openCurlPreview, copyCurl } = executionEvidence

const environmentEditor = createEnvironmentEditor({
  get activeApiExecutionEnvironment() { return activeApiExecutionEnvironment },
  get runnableApiExecutionEnvironment() { return runnableApiExecutionEnvironment },
  get activeWebExecutionEnvironment() { return activeWebExecutionEnvironment },
  get runnableWebRuntime() { return runnableWebRuntime },
  get editingEnvironment() { return editingEnvironment },
  get environmentEditorKind() { return environmentEditorKind },
  get openEnvironmentEditorCore() { return openEnvironmentEditorCore },
  get webEnvironmentVerification() { return webEnvironmentVerification },
  get environmentEditorTarget() { return environmentEditorTarget },
  get navigateToNav() { return navigateToNav },
  get runWithEnvironmentDraftGuard() { return runWithEnvironmentDraftGuard },
  get automationKind() { return automationKind },
  get addEnvironment() { return addEnvironment },
  get deleteEnvironment() { return deleteEnvironment },
  get saveEnvironment() { return saveEnvironment },
})

const { environmentVisible, curlImportText, cancelEnvironmentEditing, resolveRunnableEnvironment, resolveWebRuntimeEnvironment, webLogoutActionOptions, addWebLogoutStep, moveWebLogoutStep, removeWebLogoutStep, updateWebLogoutStepAction, webLogoutTargetPlaceholder, openEnvironmentEditor, openEnvironmentManagement, switchEnvironmentManagementKind, selectEnvironmentFromPage, returnToPreparationFromEnvironments, addExecutionEnvironment, deleteExecutionEnvironment, saveEditingEnvironment, addEnvironmentEntry, deleteEnvironmentEntry, importCurlToEnvironment } = environmentEditor

const automationConfigVisible = ref(false)

const webPreparation = createWebPreparation({
  get localStorage() { return localStorage },
  get activeWebRunCases() { return activeWebRunCases },
  get webAutomationResultStatusById() { return webAutomationResultStatusById },
  get automationFilter() { return automationFilter },
  get webAutomationStatusFilter() { return webAutomationStatusFilter },
  get webAutomationRunning() { return webAutomationRunning },
  get startVisualWebAutomationRun() { return startVisualWebAutomationRun },
  get webRunCreationError() { return webRunCreationError },
  get webRunInterruptionMessage() { return webRunInterruptionMessage },
  get webAutomationSelectedIds() { return webAutomationSelectedIds },
  get filteredWebAutomationCases() { return filteredWebAutomationCases },
  get selectExecutionEnvironment() { return selectExecutionEnvironment },
  get webExecutionEnvironmentLibrary() { return webExecutionEnvironmentLibrary },
  get persistEnvironmentLibrary() { return persistEnvironmentLibrary },
  get webAutomationLoopCount() { return webAutomationLoopCount },
  get beginWorkspaceOperation() { return beginWorkspaceOperation },
  get webRunCapabilities() { return webRunCapabilities },
  get automationKind() { return automationKind },
  get workspaceFetch() { return workspaceFetch },
  get effectiveWebAutomationScope() { return effectiveWebAutomationScope },
  get activeWebExecutionEnvironment() { return activeWebExecutionEnvironment },
  get runnableWebRuntime() { return runnableWebRuntime },
  get webAutomationRunMode() { return webAutomationRunMode },
})

const { webRunSuites, webRunRetryFailedCount, saveCurrentWebRunSuite, applyWebRunSuite, deleteWebRunSuiteByName, retryFailedWebRunCases, switchAutomationProject, switchWebAutomationModule, loadWebRunCapabilities, clearWebRunLocalMessages, toggleWebAutomationCase, selectFilteredWebAutomationCases, clearWebAutomationSelection, pruneWebAutomationSelection, clearWebAutomationFilters, updateWebAutomationSourceFilter, updateWebAutomationStatusFilter, updateWebAutomationKeyword, updateWebAutomationEnvironment, updateWebAutomationBrowser, updateWebAutomationLoopCount, resolveVisualWebAutomationRunProjectId, captureWebRunStartRequest } = webPreparation

const workspaceSync = createWorkspaceSync({
  get activeProject() { return activeProject },
  get libraryFilter() { return libraryFilter },
  get beginWorkspaceOperation() { return beginWorkspaceOperation },
  get workspaceStorage() { return workspaceStorage },
  get workspaceFetch() { return workspaceFetch },
  get templateConfigModule() { return templateConfigModule },
  get savedCases() { return savedCases },
  get syncSelectedCaseTypes() { return syncSelectedCaseTypes },
  get homeWorkItems() { return homeWorkItems },
  get homeRuns() { return homeRuns },
  get confirmDangerAction() { return confirmDangerAction },
})

const { workspaceSyncConflict, workspaceSyncLoading, workspaceSyncConflictText, workspaceSyncState, currentBackendProjectName, loadBackendBackedWorkspace, retryHomeWorkspace, homeWorkspaceFailure, buildWorkspaceSyncConflictNotice, dismissWorkspaceSyncConflict, pushLocalWorkspaceToBackend, reloadBackendWorkspace, homeWorkspaceStatus, homeWorkspaceError } = workspaceSync

const environmentController = createEnvironmentController({
  storage: localStorage,
  beginOperation: (resource) => beginWorkspaceOperation(resource),
  confirmDanger: (title, message, confirmLabel) => confirmDangerAction(title, message, confirmLabel),
  confirmDiscard: async (message) => {
    try {
      await ElMessageBox.confirm(message, '放弃未保存的环境修改？', {
        confirmButtonText: '放弃修改',
        cancelButtonText: '继续编辑',
        type: 'warning',
        customClass: 'theme-confirm-box environment-discard-confirm'
      })
      return true
    } catch {
      return false
    }
  },
  notify: {
    info: (message) => ElMessage.success(message),
    warning: (message) => ElMessage.warning(message),
    error: (message) => ElMessage.error(message)
  }
})

const {
  executionEnvironmentLibrary,
  webExecutionEnvironmentLibrary,
  editingEnvironment,
  editingWebAllowedRequestOriginsText,
  editingWebNetworkPolicyMode,
  environmentEditorTarget,
  environmentEditorKind,
  currentEnvironmentLibrary,
  activeApiExecutionEnvironment,
  activeWebExecutionEnvironment,
  runnableApiExecutionEnvironment,
  runnableWebRuntime,
  environmentDraftDirty,
  bindEditingEnvironment,
  syncEnvironmentEditorToLibrary,
  discardEnvironmentDraft,
  captureDraftIntent: captureEnvironmentIntent,
  confirmDiscardDraft: confirmDiscardEnvironmentDraft,
  runWithDraftGuard: runWithEnvironmentDraftGuard,
  openEditor: openEnvironmentEditorCore,
  persistEnvironmentLibrary,
  selectExecutionEnvironment,
  addEnvironment,
  deleteEnvironment,
  saveEnvironment
} = environmentController

const loginVerification = createWebLoginVerification({
  get editingEnvironment() { return editingEnvironment },
})

const { webEnvironmentVerifying, webEnvironmentVerification, webLoginVerificationVisible, webLoginVerificationRunning, webLoginVerificationSnapshot, webLoginSessionId, verifyEditingWebEnvironmentLogin, clearWebLoginSession, closeWebLoginVerification, migrateUnstableWebLoginSuccessTarget, isUnstableCssModuleLocator, extractStableWebLoginRoute, buildWebLoginProbeCase } = loginVerification

const loginDiscovery = createWebLoginDiscoveryState({
  get editingEnvironment() { return editingEnvironment },
  get webEnvironmentVerification() { return webEnvironmentVerification },
})

const { webLoginDiscoveryDialogVisible, webLoginDiscoveryLoading, webLoginDiscoveryConfirming, webLoginDiscoverySnapshot, webLoginDiscoverySelection, startWebLoginDiscovery, confirmAndApplyWebLoginDiscovery, selectedWebLoginDiscoveryCandidate, closeWebLoginDiscovery, initializeWebLoginDiscoverySelection, resetWebLoginDiscoverySelection, resolveWebLoginDiscoveryUrl, resolveHashAwareWebRuntimeUrl, formatWebLoginDiscoveryError } = loginDiscovery

const generation = reactive({
  granularity: 'risk' as GenerationGranularity,
  selectedCaseTypeIds: templateConfig.value.caseTypes
    .filter((caseType) => caseType.appliesTo === 'interface' || caseType.appliesTo === 'all')
    .filter((caseType) => caseType.enabled)
    .map((caseType) => caseType.id)
})

const parsed = computed<ParsedOpenApiDocument | undefined>(() => activeProject.value?.document)

const modules = computed(() => {
  const counts = new Map<string, number>()
  for (const operation of parsed.value?.operations || []) {
    counts.set(operation.module, (counts.get(operation.module) || 0) + 1)
  }
  return Array.from(counts.entries()).map(([name, count]) => ({ name, count }))
})

const selectedReviewCase = computed(() => {
  const testCase = selectedCase.value
  if (!testCase || !('pageName' in testCase.row)) return undefined
  return {
    id: testCase.row.caseId,
    title: testCase.row.title,
    module: testCase.row.module,
    sourceFile: testCase.row.sourceFile
  }
})

const activeCaseDesignSource = ref(selectedReviewCase.value?.sourceFile || '')

watch(selectedReviewCase, (testCase) => {
  if (testCase?.sourceFile) activeCaseDesignSource.value = testCase.sourceFile
})

watch([viewportMode, generationKind], () => {
  compactContextVisible.value = false
  compactInspectorVisible.value = false
  if (viewportMode.value !== 'narrow') narrowNavigationVisible.value = false
})

watch([activeNav, automationKind], () => {
  if (activeNav.value === 'automation' && automationKind.value === 'web') return
  if (typeof window !== 'undefined') writeWebMonitorQuery('workbench', '', 'replace')
  webAutomationWorkspaceMode.value = 'workbench'
})

const generationTemplateOptions = computed(() =>
  templateOptions.value.filter((option) =>
    generationKind.value === 'interface'
      ? option.kind === 'interface'
      : option.kind === 'web_automation'
  )
)

const visibleEditableFields = computed(() => visibleInterfaceFields.value)

const webWorkspaceModel = createWebWorkspaceModel({
  get effectiveWebAutomationScope() { return effectiveWebAutomationScope },
  get webRunCapabilities() { return webRunCapabilities },
  get runnableWebRuntime() { return runnableWebRuntime },
  get activeWebExecutionEnvironment() { return activeWebExecutionEnvironment },
  get webRunCreationError() { return webRunCreationError },
  get webRunInterruptionMessage() { return webRunInterruptionMessage },
  get webAutomationProjectCases() { return webAutomationProjectCases },
  get activeWebRunSnapshot() { return activeWebRunSnapshot },
  get webExecutionEnvironmentLibrary() { return webExecutionEnvironmentLibrary },
  get webAutomationLoopCount() { return webAutomationLoopCount },
  get webAutomationRunning() { return webAutomationRunning },
  get automationFilter() { return automationFilter },
  get webAutomationCases() { return webAutomationCases },
  get webAutomationProjects() { return webAutomationProjects },
  get webAutomationRunReport() { return webAutomationRunReport },
  get filteredWebAutomationCases() { return filteredWebAutomationCases },
  get webAutomationSelectedIds() { return webAutomationSelectedIds },
  get webAutomationStatusFilter() { return webAutomationStatusFilter },
  get webRunConnectionState() { return webRunConnectionState },
  get webRunControlLoading() { return webRunControlLoading },
})

const { webAutomationPreflight, webRunStatusMessage, webAutomationWorkspaceHeader, webAutomationWorkspacePreparation, webAutomationWorkspaceScope, webAutomationResultStatusById, webAutomationWorkspaceTable, activeWebAutomationWorkspaceRun, activeWebRunView, webEnvironmentBrowserOptions, activeWebRunElapsed, webRunConnectionLabel, resolveWebLoginReadiness, resolveWebLogoutReadiness, webAutomationResultStatus } = webWorkspaceModel

const homeNavigation = createHomeNavigation({
  get automationKind() { return automationKind },
  get navigateToNav() { return navigateToNav },
  get savedWebCases() { return savedWebCases },
  get openWebEdit() { return openWebEdit },
  get savedCases() { return savedCases },
  get openEdit() { return openEdit },
  get apiRunMonitor() { return apiRunMonitor },
  get enterApiRunMonitor() { return enterApiRunMonitor },
  get activeWebRunId() { return activeWebRunId },
  get enterWebRunMonitor() { return enterWebRunMonitor },
  get webAutomationWorkspaceMode() { return webAutomationWorkspaceMode },
  get webAutomationCases() { return webAutomationCases },
  get automationFilter() { return automationFilter },
  get webAutomationStatusFilter() { return webAutomationStatusFilter },
  get webAutomationSelectedIds() { return webAutomationSelectedIds },
  get apiAutomationWorkspaceMode() { return apiAutomationWorkspaceMode },
  get automationCases() { return automationCases },
  get automationSelection() { return automationSelection },
  get selectedReportProject() { return selectedReportProject },
  get reports() { return reports },
  get selectedReportId() { return selectedReportId },
  get defects() { return defects },
})

const { handleHomeNavigate } = homeNavigation

const projectPortability = createProjectPortability({
  get localStorage() { return localStorage },
  get downloadTextFile() { return downloadTextFile },
  get templateConfigModule() { return templateConfigModule },
  get savedCases() { return savedCases },
  get executionEnvironmentLibrary() { return executionEnvironmentLibrary },
  get webExecutionEnvironmentLibrary() { return webExecutionEnvironmentLibrary },
  get syncSelectedCaseTypes() { return syncSelectedCaseTypes },
})

const { backupProjectData, onProjectDataSelected } = projectPortability

const enterWebRunMonitor = () => {
  if (!activeWebRunId.value) return
  const scrollElement = webAutomationWorkspaceRef.value?.getTableScrollElement()
  webAutomationTableScrollTop.value = scrollElement?.scrollTop || 0
  webAutomationTableScrollLeft.value = scrollElement?.scrollLeft || 0
  webAutomationWorkspaceMode.value = 'monitor'
  writeWebMonitorQuery('monitor', activeWebRunId.value)
}

const returnWebRunWorkbench = async () => {
  webAutomationWorkspaceMode.value = 'workbench'
  writeWebMonitorQuery('workbench')
  await nextTick()
  const scrollElement = webAutomationWorkspaceRef.value?.getTableScrollElement()
  if (scrollElement) {
    scrollElement.scrollTop = webAutomationTableScrollTop.value
    scrollElement.scrollLeft = webAutomationTableScrollLeft.value
  }
  webAutomationWorkspaceRef.value?.focusMonitorEntry()
}

const confirmDangerAction = async (
  title: string,
  message: string,
  confirmButtonText = '删除'
): Promise<boolean> => {
  try {
    await ElMessageBox.confirm(message, title, {
      confirmButtonText,
      cancelButtonText: '取消',
      type: 'warning',
      customClass: title === '删除运行环境' ? 'theme-confirm-box environment-danger-confirm' : 'theme-confirm-box'
    })
    return true
  } catch {
    return false
  }
}

const defects = useDefects({
  storage: localStorage,
  captureMutation: (hookCurrent) => {
    const epoch = getAuthSessionEpoch()
    const owner = currentUser.value?.id
    const isCurrent = () => hookCurrent() && epoch === getAuthSessionEpoch() && owner !== undefined
      && currentUser.value?.id === owner && authStatus.value === 'authenticated'
    return { storage: workspaceStorage.capture(isCurrent), isCurrent }
  },
  downloadTextFile,
  canTriggerDownload: (isCurrent) => isCurrent() && authStatus.value === 'authenticated' && currentUser.value !== null,
  beginOperation: () => beginWorkspaceOperation('jiraIssues'),
  candidateProjects: () => [
    ...importedProjects.value.map((project) => project.title),
    ...libraryProjects.value.map((project) => project.value),
    ...automationProjects.value.map((project) => project.value),
    ...reportProjects.value.map((project) => project.value)
  ],
  confirmDanger: confirmDangerAction,
  navigateToDefects: () => navigateToNav('defects'),
  isDefectsActive: () => activeNav.value === 'defects'
})

const {
  defectFilter,
  switchDefectProject,
  defectIssuesForActiveProject,
  defectProjectOptions,
  jiraProjectName,
  defectStatusLabel,
  defectStatusTagType,
  defectDraftByReportCaseId
} = defects

const reports = useReports({
  storage: localStorage,
  confirmDanger: confirmDangerAction,
  navigateToReports: () => navigateToNav('reports'),
  downloadTextFile,
  showResultLog: (result) => {
    selectedAutomationResult.value = result
    automationLogVisible.value = true
  }
})

const {
  reportProjects,
  automationReports,
  selectedReportProject,
  selectedReportId,
  switchReportProject,
  refreshAutomationReports,
  openReportsModule,
  openActiveWebRunReport
} = reports

const homeViewRef = computed(() => appRouteRef.value?.homeViewRef)

const homeProjection = createHomeProjection({
  get savedCases() { return savedCases },
  get savedWebCases() { return savedWebCases },
  get automationCases() { return automationCases },
  get webAutomationCases() { return webAutomationCases },
  get automationReports() { return automationReports },
  get defects() { return defects },
  get apiRunMonitor() { return apiRunMonitor },
  get activeWebRunSnapshot() { return activeWebRunSnapshot },
  get activeWebRunCases() { return activeWebRunCases },
  get runnableWebRuntime() { return runnableWebRuntime },
})

const { homeProjects, homeRunState, homeRuns, homeWorkItems } = homeProjection

watch(generationKind, () => {
  generationTemplateId.value = generationTemplateOptions.value[0]?.value || 'builtin-interface'
})

function cloneEnvironment(environment: SavedExecutionEnvironment): SavedExecutionEnvironment {
  return JSON.parse(JSON.stringify(environment)) as SavedExecutionEnvironment
}

type HomeWorkspaceStatus = 'ready' | 'loading' | 'empty' | 'unavailable' | 'forbidden' | 'error'

const overview = useOverviewAvailability({ authStatus, activeNav, begin: beginWorkspaceOperation,
  hasOperation: workspaceRuntime.hasOperation, fetch: workspaceFetch })
const { overviewStatus, overviewError, overviewAvailabilityChecked, checkOverviewAvailability } = overview

const accountSettings = createAccountSettings({
  get currentUser() { return currentUser },
  get authDisposed() { return authDisposed },
  get authStatus() { return authStatus },
  get isCurrentAuth() { return isCurrentAuth },
})

const { currentUserInitials, canEdit, sidebarGroups, changePasswordDialogVisible, changePasswordForm, openChangePasswordDialog, submitChangePassword } = accountSettings

function resetWorkspaceState(): void {
  templateConfigModule.invalidateWorkspace()
  for (const feature of [jiraSettings, webExecutionController, apiExecutionController, caseEditor,
    executionEvidence, loginVerification, loginDiscovery, appShell, executionCatalog, workspaceSync,
    webPreparation, environmentController, environmentEditor, executionAssets]) feature.reset()
  casesModule.resetWorkspace()
  defects.resetWorkspace()
  reports.resetWorkspace()
  importedProjects.value = []
  activeProjectId.value = ''
  requirementAnalysisProjectName.value = '默认项目'
  overview.reset()
  automationConfigVisible.value = false
  templateConfigModule.bindWorkspace()
}

function loadWorkspaceData(): void {
  // 旧设计草稿仅保留在浏览器存储中，不恢复任务、不轮询已下线的 AI API。
  syncNavWithLocation('replace')
  refreshSavedCases()
  refreshAutomationCases()
  defects.refreshDefectDrafts()
  defects.jiraCsvTemplate.value = loadJiraCsvTemplate(localStorage)
  webRunSuites.value = listWebRunSuites(localStorage)
  environmentController.reload()
  refreshAutomationReports()
  void loadBackendBackedWorkspace()
  void loadJiraSettingsFromBackend()
  void loadWebRunCapabilities()
  void recoverWebRunSession('replace')
}

function stopBackgroundActivity(): void {
  webExecutionController.stopPolling()
  loginDiscovery.webLoginDiscoveryAbortController?.abort()
}

installAppLifecycle({
  jiraDirty: jiraConfigDirty, environmentDirty: environmentDraftDirty, defectDirty: defects.defectRecoveryDirty,
  onSessionExpired: handleSessionExpired, bootstrap: bootstrapAuth, onPopState: handlePopState,
  dispose: () => { authDisposed = true; authSession.dispose() }
})

const apiExecutionPageModel = reactive({ automationKind, apiRunMonitor, returnApiRunPreparation, openReportsModule, apiAutomationWorkspaceMode, filteredAutomationCases, canEdit, openEnvironmentManagement, automationConfigVisible, enterApiRunMonitor, apiAutomationRunning, apiRunConfirming, executeAllAutomationCases, apiExecutionScopeLabel, exportAutomationJson, automationCases, automationProjects, automationModules, automationFilter, switchAutomationProject, automationMethods, hasFilteredDesignOriginAutomationCases, webAutomationRunning, transferFilteredDesignAutomationResultsToDefects, failedDesignOriginSummary, designOriginFailureExpanded, exportDesignOriginSummary, activeAutomationRunSummary, deleteAutomationByScope, automationSelection, automationResultMap, defectDraftByReportCaseId, defectStatusLabel, defectStatusTagType, openAutomationCurlPreview, openAutomationLog, transferAutomationResultToDefect, deleteAutomationCase, executionEnvironmentLibrary, selectExecutionEnvironment, openEnvironmentEditor, responseBodyMatchThreshold, automationRunMode, apiRunCountValue, apiRunCountMin, stepApiRunCount, apiRunCountInput, onApiRunCountInput, commitApiRunCount, apiRunCountError })

const caseEditorModel = reactive({ editVisible, selectedCase, visibleEditableFields, isMultilineField, editRow, saveEdit, webEditRow, webExecutionEnvironmentLibrary, applySelectedWebEnvironmentToEditRow })

const environmentEditorModel = reactive({ authStatus, environmentVisible, activeNav, environmentEditorKind, addExecutionEnvironment, currentEnvironmentLibrary, editingEnvironment, openEnvironmentEditor, editingWebNetworkPolicyMode, editingWebAllowedRequestOriginsText, webLoginDiscoveryLoading, canEdit, startWebLoginDiscovery, webEnvironmentVerifying, verifyEditingWebEnvironmentLogin, addWebLogoutStep, updateWebLogoutStepAction, webLogoutActionOptions, webLogoutTargetPlaceholder, moveWebLogoutStep, removeWebLogoutStep, webEnvironmentVerification, clearWebLoginSession, curlImportText, importCurlToEnvironment, addEnvironmentEntry, deleteEnvironmentEntry, deleteExecutionEnvironment, cancelEnvironmentEditing, saveEditingEnvironment })

const webLoginDiscoveryModel = reactive({ webLoginDiscoveryDialogVisible, closeWebLoginDiscovery, webLoginDiscoveryConfirming, webLoginDiscoverySnapshot, confirmAndApplyWebLoginDiscovery, webLoginDiscoveryLoading, canEdit, startWebLoginDiscovery, webLoginDiscoverySelection, selectedWebLoginDiscoveryCandidate })

const webLoginVerificationModel = reactive({ webLoginVerificationVisible, closeWebLoginVerification, webLoginVerificationRunning, webLoginVerificationSnapshot })

const automationConversionModel = reactive({ automationGenerateVisible, canEdit, confirmGenerateAutomationFromLibrary, automationGenerateProject, automationGenerationScope, automationGenerateModule })

const changePasswordModel = reactive({ changePasswordDialogVisible, currentUser, changePasswordForm, submitChangePassword })

const curlPreviewModel = reactive({ curlVisible, copyCurl, executableRequest, curlText })

const executionLogModel = reactive({ automationLogVisible, selectedAutomationResult, actionIrViewerVisible, selectedActionIrCase })

const webExecutionPageModel = reactive({ webAutomationWorkspaceMode, isCompactDesktop, webAutomationWorkspaceHeader, webAutomationWorkspacePreparation, webAutomationWorkspaceScope, webAutomationWorkspaceTable, webAutomationPreflight, webRunStatusMessage, activeWebAutomationWorkspaceRun, webRunSuites, webAutomationRunning, webRunRetryFailedCount, openReportsModule, openAutomationGenerateDialog, exportAutomationJson, updateWebAutomationEnvironment, updateWebAutomationBrowser, updateWebAutomationLoopCount, openEnvironmentEditor, startVisualWebAutomationRun, switchAutomationProject, switchWebAutomationModule, toggleWebAutomationCase, selectFilteredWebAutomationCases, clearWebAutomationSelection, updateWebAutomationSourceFilter, updateWebAutomationStatusFilter, updateWebAutomationKeyword, clearWebAutomationFilters, openWebAutomationRunLog, transferWebAutomationRunResultToDefect, deleteWebAutomationCase, loadWebRunCapabilities, enterWebRunMonitor, returnWebRunWorkbench, pauseActiveWebRun, resumeActiveWebRun, stopActiveWebRun, openActiveWebRunReport, saveCurrentWebRunSuite, applyWebRunSuite, deleteWebRunSuiteByName, retryFailedWebRunCases })

const appRouteModel = reactive({ isCaseWorkbenchNav, activeNav, homeRuns, homeWorkItems, homeProjects, homeWorkspaceStatus, homeWorkspaceError, handleHomeNavigate, retryHomeWorkspace, currentUser, overviewStatus, overviewError, overviewAvailabilityChecked, checkOverviewAvailability, isSettingsCenterNav, canEdit, jiraConfigForm, jiraConfigLoading, jiraConfigReadState, jiraConfigTesting, jiraProjectJqlRows, loadJiraSettingsFromBackend, saveJiraSettings, testJiraConnection, addJiraProjectJqlRow, removeJiraProjectJqlRow, syncJiraProjectJqlFormFromRows, jiraConfigTestResult, jiraConfigSavedSnapshot, jiraConfigValidationError, navigateToNav, isQualityWorkbenchNav, automationKind, apiExecutionPageModel, webExecutionPageModel, environmentEditorKind, currentEnvironmentLibrary, editingEnvironment, switchEnvironmentManagementKind, selectEnvironmentFromPage, returnToPreparationFromEnvironments, addExecutionEnvironment, openEnvironmentEditor, currentNav })
  const testAccess = import.meta.env.MODE === 'test'
    ? proxyRefs({ authStatus, currentUser, workspaceStorage, localStorage, beginWorkspaceOperation, handleSessionExpired, onLoginSuccess, activeSidebarItemId, activeNav, navigateToNav, automationCases, webAutomationCases, automationFilter, automationRunMode, apiAutomationLoopCount, apiRunSnapshot, apiRunMonitor, apiAutomationWorkspaceMode, apiRunConfirming, apiAutomationRunning, automationSelection, automationRunReport, webAutomationRunning, webAutomationLoopCount, activeWebRunId, activeWebRunSnapshot, activeWebRunCases, webRunCapabilities, webRunConnectionState, webRunControlLoading, webAutomationSelectedIds, webAutomationStatusFilter, webAutomationWorkspaceMode, webRunInterruptionMessage, startVisualWebAutomationRun, monitorActiveWebRun, recoverWebRunSession, resumeActiveWebRun, apiPreparationScroll, returnApiRunPreparation, executeAllAutomationCases, jiraConfigForm, jiraConfigLoading, jiraConfigTesting, jiraProjectJqlRows, jiraConfigValidationError, addJiraProjectJqlRow, removeJiraProjectJqlRow, loadJiraSettingsFromBackend, saveJiraSettings, testJiraConnection, templateOperationAdapter, templateConfigModule, templateConfig, casesModule, savedCases, editRow, webEditRow, environmentVisible, curlImportText, openEnvironmentEditor, loadWebRunCapabilities, workspaceSyncConflict, loadBackendBackedWorkspace, pushLocalWorkspaceToBackend, reloadBackendWorkspace, homeWorkspaceStatus, executionEnvironmentLibrary, webExecutionEnvironmentLibrary, editingEnvironment, environmentEditorKind, currentEnvironmentLibrary, environmentDraftDirty, generation, onProjectDataSelected, defects, reports, automationReports, selectedReportId, refreshAutomationReports, changePasswordDialogVisible, changePasswordForm, resetWorkspaceState, pauseActiveWebRun, stopActiveWebRun })
    : undefined

return {
  testAccess,
  presentation: { authStatus, onLoginSuccess, isNarrowViewport, narrowNavigationVisible, railCollapsed, sidebarGroups, activeSidebarItemId, sidebarBadges, isSettingsCenterNav, selectSidebarItem, expandStudioNavigation, helpVisible, globalSearch, studioProjectLabel, studioSectionLabel, workspaceSyncState, globalLongTaskLabel, currentUserInitials, currentUser, primaryAreas, activePrimaryId, toggleStudioNavigation, openActiveLongTask, selectPrimaryArea, openAssistantPage, handleLogout, openChangePasswordDialog, activeNav, generationKind, automationKind, isWorkbenchNav, templateConfigModule, workspaceSyncConflict, workspaceSyncConflictText, workspaceSyncLoading, pushLocalWorkspaceToBackend, reloadBackendWorkspace, dismissWorkspaceSyncConflict, isOnline, appRouteModel, caseEditorModel, curlPreviewModel, environmentEditorModel, webLoginDiscoveryModel, webLoginVerificationModel, executionLogModel, automationConversionModel, changePasswordModel, appRouteRef },
}
}

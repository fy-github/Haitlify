<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Archive,
  Bug,
  FileText,
  Copy,
  Download,
  LoaderCircle,
  Play,
  Plus,
  Search,
  Settings,
  Trash2,
  Upload,
  X,
  ArrowUp,
  ArrowDown,
} from '@lucide/vue'
import {
  browserSafeHeaders,
  buildCurlCommand,
  buildExecutableRequest,
  buildProxyExecutionPayload,
  type ExecutableRequest,
  type ExecutionEnvironment
} from './domain/apiExecution'
import {
  buildWebExecutionRuntime,
  defaultExecutionEnvironmentLibrary,
  defaultWebExecutionEnvironmentLibrary,
  defaultWebExecutionRuntimeConfig,
  WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY,
  loadExecutionEnvironmentLibrary,
  newEnvironmentEntry,
  normalizeWebExecutionRuntimeConfig,
  parseCurlEnvironmentImport,
  validateExecutionEnvironment,
  validateWebExecutionEnvironment,
  type BuiltWebExecutionRuntime,
  type EnvironmentEntryType,
  type SavedExecutionEnvironment,
  type WebRuntimeBrowser
} from './domain/executionEnvironmentRepository'
import {
  deleteAutomationCases,
  deleteSavedAutomationCase,
  listSavedAutomationCases,
  saveAutomationCases,
  type SavedAutomationCase
} from './domain/automationRepository'
import {
  deleteWebAutomationRunCase,
  deleteWebAutomationRunCases,
  listSavedWebAutomationRunCases,
  type SavedWebAutomationRunCase
} from './domain/webAutomationRepository'
import {
  buildAutomationCurlCommand,
  buildAutomationExecutableRequest,
  exportApifoxAutomationDraft,
  generateAutomationCasesFromInterfaceCases,
  removeResultsFromAutomationRunReport,
  type AutomationRunResult,
  type ApiAutomationCaseDraft
} from './domain/apiAutomation'
import { apiMonitorCounts } from './domain/apiRunMonitor'
import ApiRunMonitorView from './components/automation/ApiRunMonitorView.vue'
import {
  exportWebAutomationRunCases,
  removeResultsFromWebAutomationRunReport,
  type WebAutomationRunCase,
  type WebAutomationRunMode,
  type WebAutomationRunReport,
  type WebAutomationRunStatus
} from './domain/webAutomation'
import { buildAutomationGenerationScope } from './domain/automationGenerationScope'
import {
  WebAutomationRunRequestError,
  createWebAutomationRun,
  getWebAutomationCapabilities,
  getWebAutomationRun,
  isTerminalWebAutomationRun,
  pauseWebAutomationRun,
  resumeWebAutomationRun,
  snapshotToReport,
  stopWebAutomationRun,
  waitForWebAutomationRun,
  type WebAutomationCapabilities,
  type WebAutomationRunSnapshot
} from './domain/webAutomationRunRepository'
import {
  deleteWebRunSuite,
  listWebRunSuites,
  saveWebRunSuite,
  type SavedWebRunSuite
} from './domain/webRunSuiteRepository'
import {
  WebLoginDiscoveryRequestError,
  confirmWebLoginDiscovery,
  createWebLoginDiscovery,
  waitForWebLoginDiscovery,
  type WebLoginDiscoveryCandidate,
  type WebLoginDiscoveryCandidateRole,
  type WebLoginDiscoverySnapshot
} from './domain/webLoginDiscoveryRepository'
import {
  buildBrowserOptions,
  buildWebRunView,
  buildWebRunCaseStatusById,
  buildWebRunCaseResultMap,
  resolveWebRunCaseId
} from './domain/webAutomationRunView'
import {
  WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY,
  createWebRunPollCoordinator,
  resolveWebRunRecovery
} from './domain/webAutomationRunSession'
import {
  buildWebAutomationPreflight,
  buildWebAutomationScope,
  collectWebAutomationModuleCounts
} from './domain/webAutomationWorkspaceView'
import {
  freezeWebRunStart,
  isWebRunStartRequest,
  type WebRunStartRequest
} from './domain/webExecutionPreparation'
import {
  appendAutomationReport,
  buildSavedAutomationReportRecord,
  type SavedAutomationReport
} from './domain/automationReportRepository'
import {
  defaultJiraConfig,
  loadJiraConfigFromPlatform,
  saveJiraConfigToPlatform,
  testJiraConfigConnection,
  type JiraConfig
} from './domain/jiraConfigRepository'
import {
  jiraConnectionSnapshot,
  jiraDraftSnapshot as jiraDraftSnapshotOf,
  jiraProjectJqlFromRows,
  jiraRowsFromProjectJql,
  validateJiraDraft,
  jiraTestTargetError,
  type JiraProjectJqlRow
} from './domain/jiraConfigDraft'

type JiraConfigTestResult = {
  state: 'idle' | 'success' | 'failed' | 'stale'
  message: string
  target: string
}
import { applyInterfaceFieldTemplate } from './domain/caseTemplateApplication'
import { syncMetadataFromRow } from './domain/caseBulkEdit'
import {
  type GeneratedInterfaceCase,
  type GenerationGranularity,
  type InterfaceCaseRow
} from './domain/caseGenerator'
import {
  deleteSavedCase,
  deleteSavedWebAutomationCase,
  listSavedCases,
  saveGeneratedCases,
  saveGeneratedWebAutomationCases,
  type SavedInterfaceCase
} from './domain/caseRepository'
import {
  ensureBackendProject,
  listBackendProjects,
  loadSavedCasesWithBackend,
  loadTemplateConfigWithBackend,
  PlatformBackendRequestError,
  PlatformBackendTransportError,
  saveTemplateConfigWithBackend,
  syncSavedCaseProjectsWithBackend,
  syncCaseOrderingWithBackend
} from './domain/platformBackendRepository'
import {
  buildAutomationCaseIndex,
  collectAutomationMethods,
  collectAutomationModules,
  filterAutomationRecords
} from './domain/automationView'
import {
  type ParsedOpenApiDocument
} from './domain/openapiParser'
import {
  automationKindFromPath,
  automationPathFor,
  navKeyFromPath,
  navPathFor,
  type NavKey
} from './domain/appNavigation'
import { fetchCurrentUser, logout, changePassword, type AuthUser } from './domain/authRepository'
import { getAuthSessionEpoch, setUnauthorizedHandler } from './domain/http'
import AppShell from './components/shell/AppShell.vue'
import ResponsiveDrawer from './components/shell/ResponsiveDrawer.vue'
import LoginScreen from './components/auth/LoginScreen.vue'
import GlobalTopbar from './components/shell/GlobalTopbar.vue'
import PrimarySidebar from './components/shell/PrimarySidebar.vue'
import HelpDrawer from './components/shell/HelpDrawer.vue'
import OverviewView, { type OverviewStatus } from './components/views/OverviewView.vue'
import HomeView, { type HomeNavigateTarget } from './components/views/HomeView.vue'
import type { HomeProject, HomeRun, HomeSection, HomeWorkItem } from './domain/homeWorkbench'
import AssistantChainView from './components/views/AssistantChainView.vue'
import SettingsCenterView from './components/views/SettingsCenterView.vue'
import QualityWorkbenchView from './components/views/QualityWorkbenchView.vue'
import ExecutionWorkbenchView from './components/views/ExecutionWorkbenchView.vue'
import ApiExecutionPage from './components/views/ApiExecutionPage.vue'
import EnvironmentManagementView from './components/views/EnvironmentManagementView.vue'
import EnvironmentEditorSurface from './components/automation/EnvironmentEditorSurface.vue'
import CasesView from './components/views/CasesView.vue'
import ApiAutomationCaseTable from './components/automation/ApiAutomationCaseTable.vue'
import WebAutomationWorkspace from './components/automation/WebAutomationWorkspace.vue'
import WebStepEvidenceCard from './components/automation/WebStepEvidenceCard.vue'
import ActionIrViewerDialog from './components/automation/ActionIrViewerDialog.vue'
import type { WebCaseStatusFilter } from './components/automation/WebAutomationCaseTable.vue'
import { initialSidebarCollapsed, useViewportMode } from './ui/useViewportMode'
import { useOnlineStatus } from './ui/useOnlineStatus'
import { useDefects } from './ui/useDefects'
import DefectFailureBanner from './components/defects/DefectFailureBanner.vue'
import { useReports } from './ui/useReports'
import { createWorkspaceStorage } from './ui/workspaceStorage'
import { createWorkspaceRuntime } from './ui/useWorkspaceRuntime'
import { createAppSession } from './ui/useAppSession'
import { createTemplateOperationAdapter } from './ui/templateOperationAdapter'
import { createEnvironmentController } from './ui/useEnvironmentController'
import { createApiExecutionController } from './ui/useApiExecutionController'
import { createWebExecutionController } from './ui/useWebExecutionController'
import { createAppNavigation } from './ui/useAppNavigation'
import { useTemplateConfig } from './ui/useTemplateConfig'
import { provideCases, useCases } from './ui/useCases'
import {
  automationStatusLabel,
  automationStatusTagType,
  formatLogValue,
  reportStepEvidence,
  reportWebStepLogs,
  webRunResultToAutomationLog
} from './ui/automationFormat'
import {
  primaryAreaForNavKey,
  primaryAreas,
  resolveNavDisplay,
  resolveSidebarActiveItemId,
  sidebarGroupsForRole,
  type SidebarBadges,
  type SidebarItem,
  type PrimaryArea
} from './ui/navigation/appSidebar'
import {
  selectImportedProject,
  type ImportedOpenApiProject
} from './domain/openapiProjectRepository'
import {
  collectFailedDesignOriginResults,
  isDesignOriginAutomationCase,
  summarizeFailedDesignOriginResults
} from './domain/designOrigin'
import { exportProjectData, importProjectData } from './domain/projectDataRepository'
import { loadTemplateConfig, TEMPLATE_CONFIG_STORAGE_KEY } from './domain/templateConfigRepository'
import { loadJiraCsvTemplate } from './domain/jiraCsvTemplateRepository'
import type {
  GeneratedWebAutomationCase,
  WebAutomationAction,
  WebAutomationStep
} from './domain/webAutomationCase'
import type { SavedWebAutomationCase } from './domain/webAutomationCase'
import type { WebAutomationCaseRow } from './domain/webAutomationCase'
import {
  serializeWebAutomationSteps,
  syncWebAutomationMetadataFromRow
} from './domain/webAutomationEditor'

interface WorkspaceSyncConflictNotice {
  projectName: string
  template: boolean
  cases: boolean
}

type NavHistoryMode = 'push' | 'replace' | 'skip'

type AuthStatus = 'checking' | 'authenticated' | 'unauthenticated'
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
    passwordOperation += 1
    changePasswordDialogVisible.value = false
    Object.assign(changePasswordForm, {
      currentPassword: '', newPassword: '', confirmPassword: '', error: '', submitting: false
    })
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
const savedCollapsed = window.localStorage.getItem('ai-test-platform.sidebar-collapsed')
const railCollapsed = ref(initialSidebarCollapsed(savedCollapsed, window.innerWidth))
const viewportMode = useViewportMode()
const isOnline = useOnlineStatus()
const isCompactDesktop = computed(() => viewportMode.value === 'compact' || viewportMode.value === 'narrow')
const isNarrowViewport = computed(() => viewportMode.value === 'narrow')
const narrowNavigationVisible = ref(false)
const helpVisible = ref(false)
const compactContextVisible = ref(false)
const compactInspectorVisible = ref(false)
const globalSearch = ref('')
const appNavigation = createAppNavigation()
const { activeNav, automationKind } = appNavigation
const homeSection = ref<HomeSection>('runs')
const importedProjects = ref<ImportedOpenApiProject[]>([])
const activeProjectId = ref('')
const requirementAnalysisProjectName = ref('默认项目')
const generationKind = ref<'interface' | 'web_automation'>('web_automation')
const automationCases = ref<SavedAutomationCase[]>(listSavedAutomationCases(localStorage))
const webAutomationCases = ref<SavedWebAutomationRunCase[]>(listSavedWebAutomationRunCases(localStorage))
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
const webExecutionController = createWebExecutionController()
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
  webRunPollCoordinator
} = webExecutionController
const onApiRunCountInput = (event: Event) => {
  const target = event.target as HTMLInputElement | null
  apiExecutionController.onApiRunCountInput(target?.value ?? '')
}
const apiRunMonitorRef = ref<InstanceType<typeof ApiRunMonitorView>>()
const apiPreparationElement = ref<HTMLElement>()
const apiMonitorEntry = ref<HTMLElement>()
const apiPreparationScroll = ref({ top: 0, left: 0 })
const enterApiRunMonitor = async () => {
  if (!apiRunMonitor.value) return
  const isCurrent = beginWorkspaceOperation('apiMonitorNavigation')
  const monitor = apiRunMonitor.value
  const scroll = apiPreparationElement.value?.querySelector<HTMLElement>('.el-scrollbar__wrap')
  apiPreparationScroll.value = { top: scroll?.scrollTop || 0, left: scroll?.scrollLeft || 0 }
  apiAutomationWorkspaceMode.value = 'monitor'
  await nextTick()
  if (isCurrent() && apiRunMonitor.value === monitor && apiAutomationWorkspaceMode.value === 'monitor') apiRunMonitorRef.value?.focusHeading()
}
const returnApiRunPreparation = async () => {
  const isCurrent = beginWorkspaceOperation('apiMonitorNavigation')
  apiAutomationWorkspaceMode.value = 'prepare'
  await nextTick()
  if (!isCurrent() || apiAutomationWorkspaceMode.value !== 'prepare') return
  const scroll = apiPreparationElement.value?.querySelector<HTMLElement>('.el-scrollbar__wrap')
  if (scroll) { scroll.scrollTop = apiPreparationScroll.value.top; scroll.scrollLeft = apiPreparationScroll.value.left }
  apiMonitorEntry.value?.focus({ preventScroll: true })
}
const automationGenerateVisible = ref(false)
const automationGenerateProject = ref('ALL')
const automationGenerateModule = ref('ALL')
const webAutomationWorkspaceRef = ref<InstanceType<typeof WebAutomationWorkspace>>()
let finalizedWebRunId = ''
const jiraConfigForm = reactive<JiraConfig>(defaultJiraConfig())
const jiraConfigLoading = ref(false)
const jiraConfigReadState = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const jiraConfigTesting = ref(false)
const jiraProjectJqlRows = ref<JiraProjectJqlRow[]>([])
/** 连接测试的真实结果(供设置页状态块展示;空 result 表示未测试) */
const jiraConfigTestResult = ref<JiraConfigTestResult>({
  state: 'idle',
  message: '',
  target: ''
})
/** 已保存配置的快照(JSON),含 token 与 JQL 行;用于页内与离开守卫判断未保存修改 */
const jiraConfigSavedSnapshot = ref('')
/** 保存前的前端校验结果(页面内 .j-error 呈现) */
const jiraConfigValidationError = ref('')
/** 连接测试世代:参数一变或重新发起,旧响应不得再写状态 */
let jiraTestGeneration = 0

/** 当前草稿快照(含 token 与 JQL 行内输入) */
const jiraDraftSnapshot = computed(() => jiraDraftSnapshotOf(jiraConfigForm, jiraProjectJqlRows.value))
const jiraConfigDirty = computed(() =>
  Boolean(jiraConfigSavedSnapshot.value) && jiraDraftSnapshot.value !== jiraConfigSavedSnapshot.value
)
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
const selectedCase = ref<GeneratedInterfaceCase | GeneratedWebAutomationCase | SavedInterfaceCase | SavedWebAutomationCase>()
const selectedLibraryId = ref<string>()
const editVisible = ref(false)
const curlVisible = ref(false)
const environmentVisible = ref(false)
const automationConfigVisible = ref(false)
const automationLogVisible = ref(false)
const curlText = ref('')
const executableRequest = ref<ExecutableRequest>()
const selectedAutomationResult = ref<AutomationRunResult>()
const actionIrViewerVisible = ref(false)
const webRunSuites = ref<SavedWebRunSuite[]>(listWebRunSuites(localStorage))
const webRunRetryFailedCount = computed(() =>
  activeWebRunCases.value.filter((item) => {
    const status = String(webAutomationResultStatusById.value[item.id] || '')
    return status === 'failed' || status === 'error'
  }).length
)
const saveCurrentWebRunSuite = async () => {
  try {
    const { value } = await ElMessageBox.prompt('套件名称', '保存当前范围为回归套件', {
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputPattern: /\S+/,
      inputErrorMessage: '名称不能为空'
    })
    const name = String(value || '').trim()
    if (!name) return
    webRunSuites.value = saveWebRunSuite(localStorage, {
      name,
      project: automationFilter.project,
      module: automationFilter.module,
      source: automationFilter.source,
      status: webAutomationStatusFilter.value,
      keyword: automationFilter.keyword || '',
      savedAt: new Date().toISOString()
    })
    ElMessage.success(`已保存套件「${name}」`)
  } catch {
    // 取消保存：静默返回。
  }
}
const applyWebRunSuite = (name: string) => {
  const suite = webRunSuites.value.find((item) => item.name === name)
  if (!suite) return
  automationFilter.project = suite.project
  automationFilter.module = suite.module
  automationFilter.source = suite.source as typeof automationFilter.source
  automationFilter.keyword = suite.keyword
  webAutomationStatusFilter.value = suite.status as WebCaseStatusFilter
}
const deleteWebRunSuiteByName = (name: string) => {
  webRunSuites.value = deleteWebRunSuite(localStorage, name)
}
const retryFailedWebRunCases = () => {
  if (webAutomationRunning.value) return
  const failedCases = activeWebRunCases.value.filter((item) => {
    const status = String(webAutomationResultStatusById.value[item.id] || '')
    return status === 'failed' || status === 'error'
  })
  if (!failedCases.length) return
  void startVisualWebAutomationRun(failedCases)
}
const selectedActionIrCase = computed(() => {
  const result = selectedAutomationResult.value
  if (!result) return undefined
  const resultSourceId = (result as { sourceCaseId?: string }).sourceCaseId
  return webAutomationCases.value.find((item) =>
    item.id === result.caseId
    || (!!resultSourceId && item.sourceCaseId === resultSourceId)
    || (!!resultSourceId && item.id === resultSourceId)
  )
})
const workspaceSyncConflict = ref<WorkspaceSyncConflictNotice>()
const workspaceSyncLoading = ref(false)
const editRow = reactive<InterfaceCaseRow>(emptyEditRow())
const webEditRow = reactive<WebAutomationCaseRow>(emptyWebEditRow())
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
const curlImportText = ref('')
const webEnvironmentVerifying = ref(false)
const webEnvironmentVerification = ref<{
  status: 'success' | 'error'
  message: string
  detail?: string
} | undefined>()
const webLoginVerificationVisible = ref(false)
const webLoginVerificationRunning = ref(false)
const webLoginVerificationSnapshot = shallowRef<WebAutomationRunSnapshot>()
const webLoginSessionId = ref('')
let webLoginVerificationAbortController: AbortController | undefined
let webLoginVerificationRunId = ''
const webLoginDiscoveryDialogVisible = ref(false)
const webLoginDiscoveryLoading = ref(false)
const webLoginDiscoveryConfirming = ref(false)
const webLoginDiscoverySnapshot = ref<WebLoginDiscoverySnapshot>()
const webLoginDiscoverySelection = reactive({
  usernameCandidateId: '',
  passwordCandidateId: '',
  submitCandidateId: ''
})
let webLoginDiscoveryAbortController: AbortController | undefined

const generation = reactive({
  granularity: 'risk' as GenerationGranularity,
  selectedCaseTypeIds: templateConfig.value.caseTypes
    .filter((caseType) => caseType.appliesTo === 'interface' || caseType.appliesTo === 'all')
    .filter((caseType) => caseType.enabled)
    .map((caseType) => caseType.id)
})

const automationFilter = reactive({
  project: 'ALL',
  module: 'ALL',
  method: 'ALL',
  source: 'ALL' as 'ALL' | 'design' | 'default',
  keyword: ''
})

function cancelEnvironmentEditing(): void {
  runWithEnvironmentDraftGuard('取消编辑会丢弃当前未保存的环境修改。', () => {
    environmentVisible.value = false
  })
}

const activeProject = computed(() => selectImportedProject(importedProjects.value, activeProjectId.value))
const globalProjectLabel = computed(() =>
  activeProject.value?.title || requirementAnalysisProjectName.value || '默认项目'
)
const studioProjectLabel = computed(() => {
  if (activeNav.value !== 'api' || globalProjectLabel.value !== '默认项目') {
    return globalProjectLabel.value
  }
  return '智能用例设计'
})
const studioSectionLabel = computed(() =>
  activeNav.value === 'api' && generationKind.value === 'web_automation'
    ? '测试设计'
    : currentNav.value.label
)
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
/** 设置中心工作台:设置导航(模板字段/用例类型/Jira 配置/用户管理)合并为单页 + 左分组导航 */
const isSettingsCenterNav = computed(() =>
  ['settings', 'caseTypes', 'jiraConfig', 'users'].includes(activeNav.value)
)
/** 质量工作台:报告与缺陷导航合并为单页 + 左分组导航(转缺陷→缺陷高亮联动) */
const isQualityWorkbenchNav = computed(() =>
  ['reports', 'defects'].includes(activeNav.value)
)
const isCaseLibraryNav = (nav: NavKey) => nav === 'cases' || nav === 'webCases'
const isCaseWorkbenchNav = computed(() =>
  isCaseLibraryNav(activeNav.value)
)
/** 任一工作台壳(设置中心/质量工作台/执行工作台/用例工作台)打开时,workspace 收成单行网格由壳占满;
 * Web 自动化除外——它自管全高布局,仍走 .is-web-automation 工作区。 */
const isWorkbenchNav = computed(() =>
  isSettingsCenterNav.value
  || isQualityWorkbenchNav.value
  || activeNav.value === 'overview'
  || isCaseWorkbenchNav.value
  || (activeNav.value === 'automation' && automationKind.value === 'api')
)

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

const globalLongTaskLabel = computed(() => {
  if (webAutomationRunning.value) return 'Web 自动化执行中'
  if (apiAutomationRunning.value) return `API 自动化 · ${apiAutomationProgress.completed}/${apiAutomationProgress.total}`
  return ''
})

const openActiveLongTask = () => {
  if (webAutomationRunning.value && activeWebRunId.value) {
    const runId = activeWebRunId.value
    navigateToNav('automation', 'push', () => {
      if (activeWebRunId.value !== runId) return
      environmentVisible.value = false
      automationKind.value = 'web'
      applyNavChange('automation', 'replace')
      enterWebRunMonitor()
    })
    return
  }
  if (apiAutomationRunning.value && apiRunMonitor.value) {
    const monitor = apiRunMonitor.value
    navigateToNav('automation', 'push', () => {
      if (apiRunMonitor.value !== monitor) return
      environmentVisible.value = false
      automationKind.value = 'api'
      applyNavChange('automation', 'replace')
      void enterApiRunMonitor()
    })
    return
  }
  navigateToNav('automation')
}
const hasFilteredDesignOriginAutomationCases = computed(() =>
  automationKind.value === 'web'
  && automationFilter.source === 'design'
  && filteredWebAutomationCases.value.length > 0
)
const failedDesignOriginResults = computed(() =>
  collectFailedDesignOriginResults(filteredWebAutomationCases.value, webAutomationRunReport.value)
)
const failedDesignOriginSummary = computed(() =>
  summarizeFailedDesignOriginResults(failedDesignOriginResults.value)
)
const designOriginFailureExpanded = ref(false)

const currentNav = computed(() => resolveNavDisplay(activeNav.value, automationKind.value, generationKind.value))
const activeSidebarItemId = computed(() => {
  if (activeNav.value === 'home') {
    return ({ runs: 'home', pending: 'home-pending', recent: 'home-recent' } as const)[homeSection.value]
  }
  return resolveSidebarActiveItemId(
    activeNav.value,
    automationKind.value,
    generationKind.value
  )
})
/** 顶部一级工作区胶囊:当前 navKey 归属哪个工作区 */
const activePrimaryId = computed(() => primaryAreaForNavKey(activeNav.value))
const sidebarBadges = computed<SidebarBadges>(() => ({}))
const generationTemplateOptions = computed(() =>
  templateOptions.value.filter((option) =>
    generationKind.value === 'interface'
      ? option.kind === 'interface'
      : option.kind === 'web_automation'
  )
)
const visibleEditableFields = computed(() => visibleInterfaceFields.value)

const automationCaseIndex = computed(() => buildAutomationCaseIndex(automationCases.value))
const automationProjects = computed(() => automationCaseIndex.value.projectOptions)
const webAutomationProjects = computed(() => collectWebAutomationProjects(webAutomationCases.value))
const automationProjectRecords = computed(() =>
  automationFilter.project === 'ALL'
    ? automationCaseIndex.value.records
    : automationCaseIndex.value.records.filter((record) => record.project === automationFilter.project)
)
const webAutomationProjectCases = computed(() =>
  automationFilter.project === 'ALL'
    ? webAutomationCases.value
    : webAutomationCases.value.filter((testCase) => testCase.project === automationFilter.project)
)
const automationModules = computed(() => collectAutomationModules(automationProjectRecords.value))
const automationMethods = computed(() => collectAutomationMethods(automationProjectRecords.value))
const filteredAutomationRecords = computed(() =>
  filterAutomationRecords(automationCaseIndex.value.records, automationFilter)
)
const filteredAutomationCases = computed(() => filteredAutomationRecords.value.map((record) => record.caseItem))
const filteredWebAutomationCases = computed(() =>
  webAutomationCases.value.filter((testCase) => {
    if (automationFilter.project !== 'ALL' && testCase.project !== automationFilter.project) return false
    if (automationFilter.module !== 'ALL' && testCase.module !== automationFilter.module) return false
    const source = isDesignOriginAutomationCase(testCase.sourceCaseId) ? 'design' : 'default'
    if (automationFilter.source !== 'ALL' && source !== automationFilter.source) return false
    if (webAutomationStatusFilter.value !== 'ALL' && webAutomationResultStatus(testCase) !== webAutomationStatusFilter.value) return false
    const keyword = automationFilter.keyword.trim().toLowerCase()
    if (!keyword) return true
    return [
      testCase.id,
      testCase.sourceCaseId,
      testCase.name,
      testCase.pageName,
      testCase.startUrl,
      testCase.module,
      testCase.project
    ].join(' ').toLowerCase().includes(keyword)
  })
)
const effectiveWebAutomationScope = computed(() =>
  buildWebAutomationScope(filteredWebAutomationCases.value, webAutomationSelectedIds.value)
)
const webAutomationPreflight = computed(() => buildWebAutomationPreflight({
  scope: effectiveWebAutomationScope.value,
  capabilities: webRunCapabilities.value,
  browserOverride: runnableWebRuntime.value?.browser || 'follow-case',
  environmentName: activeWebExecutionEnvironment.value?.name || '未选择',
  environmentValidation: validateWebExecutionEnvironment(activeWebExecutionEnvironment.value),
  login: resolveWebLoginReadiness(activeWebExecutionEnvironment.value),
  logout: resolveWebLogoutReadiness(activeWebExecutionEnvironment.value)
}))
const webRunStatusMessage = computed(() =>
  webRunCreationError.value || webRunInterruptionMessage.value
)
const webAutomationWorkspaceHeader = computed(() => ({
  executableCount: effectiveWebAutomationScope.value.count,
  moduleCount: collectWebAutomationModuleCounts(webAutomationProjectCases.value).length - 1,
  latestRunLabel: activeWebRunSnapshot.value?.updatedAt || '暂无运行'
}))
const webAutomationWorkspacePreparation = computed(() => ({
  environmentId: webExecutionEnvironmentLibrary.value.activeEnvironmentId,
  environments: webExecutionEnvironmentLibrary.value.environments.map((environment) => ({
    value: environment.id,
    label: environment.name,
    // 抽屉里的「环境地址」必须能跟着草稿所选环境走，所以选项自带地址。
    baseUrl: environment.baseUrl
  })),
  browser: runnableWebRuntime.value?.browser || 'follow-case',
  browsers: webEnvironmentBrowserOptions.value,
  loopCount: webAutomationLoopCount.value,
  loginReadyLabel: resolveWebLoginReadiness(activeWebExecutionEnvironment.value).enabled ? '自动登录已启用' : '无需自动登录',
  actionLabel: effectiveWebAutomationScope.value.actionLabel,
  canExecute: webAutomationPreflight.value.canExecute && !webAutomationRunning.value,
  blockingReason: webRunStatusMessage.value || webAutomationPreflight.value.blockingReason
}))
const webAutomationWorkspaceScope = computed(() => ({
  project: automationFilter.project,
  projects: [
    { value: 'ALL', label: '全部项目', count: webAutomationCases.value.length },
    ...webAutomationProjects.value.map((project) => ({
      value: project.value,
      label: project.value,
      count: project.count
    }))
  ],
  module: automationFilter.module,
  modules: [
    { value: 'ALL', label: '全部模块', count: webAutomationProjectCases.value.length },
    ...collectWebAutomationModuleCounts(webAutomationProjectCases.value).slice(1)
  ]
}))
const webAutomationResultStatusById = computed(() =>
  buildWebRunCaseStatusById(webAutomationCases.value, webAutomationRunReport.value?.results || [])
)
const webAutomationWorkspaceTable = computed(() => ({
  cases: filteredWebAutomationCases.value,
  selectedIds: webAutomationSelectedIds.value,
  source: automationFilter.source,
  status: webAutomationStatusFilter.value,
  keyword: automationFilter.keyword,
  resultStatusById: webAutomationResultStatusById.value,
  emptyKind: webAutomationCases.value.length === 0
    ? 'no-cases' as const
    : filteredWebAutomationCases.value.length === 0 ? 'filtered' as const : 'none' as const
}))
const activeWebAutomationWorkspaceRun = computed(() =>
  activeWebRunSnapshot.value && activeWebRunView.value
    ? {
        snapshot: activeWebRunSnapshot.value,
        view: activeWebRunView.value,
        connectionState: webRunConnectionState.value,
        connectionLabel: webRunConnectionLabel.value,
        elapsed: activeWebRunElapsed.value,
        controlLoading: webRunControlLoading.value
      }
    : undefined
)
const automationGenerationScope = computed(() => buildAutomationGenerationScope(
  libraryCaseKind.value,
  filteredLibraryCases.value,
  filteredWebLibraryCases.value as SavedWebAutomationCase[],
  {
    project: automationGenerateProject.value,
    module: automationGenerateModule.value
  }
))
const automationResultMap = computed(() =>
  new Map((automationRunReport.value?.results || []).map((result) => [result.caseId, result]))
)
const webAutomationResultMap = computed(() =>
  buildWebRunCaseResultMap(webAutomationCases.value, webAutomationRunReport.value?.results || [])
)
const activeWebRunView = computed(() =>
  activeWebRunSnapshot.value ? buildWebRunView(activeWebRunSnapshot.value) : undefined
)
const webEnvironmentBrowserOptions = computed(() => buildBrowserOptions(webRunCapabilities.value))
const activeWebRunElapsed = computed(() => {
  const snapshot = activeWebRunSnapshot.value
  if (!snapshot) return '00:00'
  const start = Date.parse(snapshot.startedAt || snapshot.createdAt)
  const end = Date.parse(snapshot.endedAt || snapshot.updatedAt || new Date().toISOString())
  const totalSeconds = Math.max(0, Math.floor((end - start) / 1000))
  return `${String(Math.floor(totalSeconds / 60)).padStart(2, '0')}:${String(totalSeconds % 60).padStart(2, '0')}`
})
const webRunConnectionLabel = computed(() => ({
  idle: '未连接',
  polling: '实时更新',
  retrying: '正在重连',
  interrupted: '连接中断'
}[webRunConnectionState.value]))
const automationRunSummary = computed(() => automationRunReport.value?.summary || {
  total: 0,
  passed: 0,
  failed: 0,
  error: 0
})
const activeAutomationRunSummary = computed(() =>
  automationKind.value === 'api'
    ? automationRunSummary.value
    : webAutomationRunReport.value?.summary || {
        total: 0,
        passed: 0,
        failed: 0,
        error: 0
      }
)
const workspaceSyncConflictText = computed(() => {
  const notice = workspaceSyncConflict.value
  if (!notice) return ''
  const scopes = [
    notice.template ? '模板配置' : '',
    notice.cases ? '用例库' : ''
  ].filter(Boolean)
  return `${notice.projectName} 的 ${scopes.join('、')} 已保留本地版本`
})

const workspaceSyncState = computed<'synced' | 'syncing' | 'conflict'>(() => {
  if (workspaceSyncConflict.value) return 'conflict'
  if (workspaceSyncLoading.value) return 'syncing'
  return 'synced'
})

const toggleRail = () => {
  railCollapsed.value = !railCollapsed.value
  window.localStorage.setItem('ai-test-platform.sidebar-collapsed', String(railCollapsed.value))
}

const toggleStudioNavigation = () => {
  if (isNarrowViewport.value) {
    narrowNavigationVisible.value = !narrowNavigationVisible.value
    return
  }
  toggleRail()
}

const applyNavChange = (next: NavKey, historyMode: NavHistoryMode) => {
  if (next !== activeNav.value) {
    if (isCaseLibraryNav(activeNav.value)) {
      casesModule.invalidateLibraryActions()
      editVisible.value = false
      curlVisible.value = false
      automationGenerateVisible.value = false
      selectedCase.value = undefined
      selectedLibraryId.value = undefined
      casesModule.bulkEditVisible.value = false
      casesModule.fieldAssignmentVisible.value = false
    }
    if (isCaseLibraryNav(next)) {
      casesModule.switchLibraryCaseKind(next === 'webCases' ? 'web_automation' : 'interface')
    }
  }
  activeNav.value = next

  if (typeof window === 'undefined' || historyMode === 'skip') return

  const targetPath = next === 'automation'
    ? automationPathFor(automationKind.value)
    : navPathFor(next)
  if (window.location.pathname === targetPath) return

  const method = historyMode === 'replace' ? 'replaceState' : 'pushState'
  window.history[method]({}, '', targetPath)
}

const navigateToNav = (next: NavKey, historyMode: NavHistoryMode = 'push', onNavigated?: () => void) => {
  const target: NavKey = next === 'api' ? 'cases' : next
  const applyNavigation = () => {
    applyNavChange(target, historyMode)
    onNavigated?.()
  }
  if (target !== activeNav.value && defects.defectRecoveryDirty.value) {
    const epoch = getAuthSessionEpoch()
    const owner = currentUser.value?.id
    void defects.confirmDiscardDefectChanges().then(confirmed => {
      if (epoch !== getAuthSessionEpoch() || currentUser.value?.id !== owner) return
      if (!confirmed) { restoreLocationForActiveNav(); return }
      applyNavigation()
    })
    return
  }
  // Jira 配置存在未保存修改时,离开本页先确认;取消则留在本页(草稿完整保留)
  if (activeNav.value === 'jiraConfig' && target !== 'jiraConfig' && jiraConfigDirty.value) {
    // 确认框跨 await:发起时固定 Jira 会话 authority,owner 切换/重新登录后旧意图不得覆盖新会话导航
    const isCurrentJiraIntent = captureJiraIntent()
    void confirmDiscardJiraDraft('离开「Jira 配置」会丢弃当前未保存的连接设置与 JQL 映射。').then((confirmed) => {
      if (confirmed && isCurrentJiraIntent()) applyNavigation()
    })
    return
  }
  // 环境配置页存在未保存修改时,离开本页先确认;取消则留在本页(草稿完整保留,地址栏回到本页)
  if (activeNav.value === 'environments' && target !== 'environments' && environmentDraftDirty.value) {
    const isCurrentIntent = captureEnvironmentIntent()
    void confirmDiscardEnvironmentDraft('离开「环境配置」会丢弃当前未保存的环境修改。').then((confirmed) => {
      if (!isCurrentIntent()) return
      if (!confirmed) {
        restoreLocationForActiveNav()
        return
      }
      discardEnvironmentDraft()
      applyNavigation()
    })
    return
  }
  applyNavigation()
}

/** 取消离开时把地址栏恢复到当前页(浏览器前进/后退已经把 URL 改掉的情况)。 */
const restoreLocationForActiveNav = () => {
  if (typeof window === 'undefined') return
  const currentPath = activeNav.value === 'automation'
    ? automationPathFor(automationKind.value)
    : navPathFor(activeNav.value)
  if (window.location.pathname !== currentPath) window.history.pushState({}, '', currentPath)
}

const selectSidebarItem = (item: SidebarItem) => {
  if (item.caseDesignKind) {
    generationKind.value = item.caseDesignKind
  }

  if (item.automationKind) {
    automationKind.value = item.automationKind
  }

  if (item.navKey === 'home') {
    homeSection.value = item.homeSection || 'runs'
    navigateToNav('home')
    void nextTick(() => homeViewRef.value?.focusSection(homeSection.value))
    return
  }
  if (item.navKey) navigateToNav(item.navKey)
}

/** 顶部一级工作区切换:先切子视图种类,再落到该工作区默认页面。 */
const selectPrimaryArea = (area: PrimaryArea) => {
  if (area.caseDesignKind) {
    generationKind.value = area.caseDesignKind
  }

  if (area.automationKind) {
    automationKind.value = area.automationKind
  }

  navigateToNav(area.navKey)
}

const openAssistantPage = () => {
  navigateToNav('assistant')
}

/** 首页阶段模块按钮 → 精确落位(带 kind 先切子视图再导航)。 */
const handleHomeNavigate = (target: HomeNavigateTarget) => {
  if (target.automationKind) automationKind.value = target.automationKind
  if (target.navKey === 'home') return
  if (target.navKey === 'cases' || target.navKey === 'webCases') {
    const { navKey, caseId } = target
    navigateToNav(navKey, 'push', () => {
      if (!caseId) return
      if (navKey === 'webCases') {
        const found = savedWebCases.value.find((item) => item.libraryId === caseId)
        if (found) openWebEdit(found)
      } else {
        const found = savedCases.value.find((item) => item.libraryId === caseId)
        if (found) openEdit(found)
      }
    })
    return
  }
  navigateToNav(target.navKey)
  if (target.navKey === 'automation' && target.automationKind === 'api' && target.monitor && apiRunMonitor.value) {
    void enterApiRunMonitor()
  } else if (target.navKey === 'automation' && target.automationKind === 'web' && target.runId === activeWebRunId.value) {
    enterWebRunMonitor()
  }
  if (target.navKey === 'automation' && target.caseId) {
    if (target.automationKind === 'web') {
      webAutomationWorkspaceMode.value = 'workbench'
      const webCase = webAutomationCases.value.find((item) => item.automationLibraryId === target.caseId)
      if (webCase) {
        automationFilter.project = target.project || webCase.project || 'ALL'
        automationFilter.module = webCase.module || 'ALL'
        automationFilter.source = 'ALL'
        automationFilter.keyword = ''
        webAutomationStatusFilter.value = 'ALL'
        webAutomationSelectedIds.value = [webCase.id]
      }
    } else {
      apiAutomationWorkspaceMode.value = 'prepare'
      const apiCase = automationCases.value.find((item) => item.automationLibraryId === target.caseId)
      if (apiCase) {
        automationFilter.project = target.project || apiCase.project || 'ALL'
        automationFilter.module = apiCase.module || 'ALL'
        automationFilter.method = 'ALL'
        automationFilter.keyword = ''
        automationSelection.value = [apiCase]
      }
    }
  }

  if (target.navKey === 'reports' && target.reportId) {
    selectedReportProject.value = target.project || selectedReportProject.value
    reports.reportEnvironmentFilter.value = 'ALL'
    reports.reportKeyword.value = ''
    selectedReportId.value = target.reportId
    reports.reportOpenRequest.value += 1
  }
  if (target.navKey === 'defects' && target.defectId) {
    const row = defects.defectIssueRecords.value.find((item) => item.id === target.defectId)
    if (row) nextTick(() => defects.openDefectDetail(row))
  }
}

const expandStudioNavigation = () => {
  railCollapsed.value = false
  window.localStorage.setItem('ai-test-platform.sidebar-collapsed', 'false')
}

const syncNavWithLocation = (historyMode: Exclude<NavHistoryMode, 'push'> = 'replace') => {
  if (typeof window === 'undefined') return

  const pathname = normalizeNavPath(window.location.pathname)
  const next = navKeyFromPath(pathname)
  if (next === 'automation') automationKind.value = automationKindFromPath(pathname)
  const targetPath = next === 'automation'
    ? automationPathFor(automationKind.value)
    : navPathFor(next)
  if (isCaseLibraryNav(next)) {
    // 尾斜杠和旧书签也规范化；replace 不增加历史记录。
    navigateToNav(next, window.location.pathname === targetPath ? 'skip' : 'replace')
    return
  }
  navigateToNav(next, pathname === targetPath ? 'skip' : historyMode)
}

const switchAutomationProject = (project: string) => {
  automationFilter.project = project
  automationFilter.module = 'ALL'
  pruneWebAutomationSelection()
  clearWebRunLocalMessages()
}

const switchWebAutomationModule = (module: string) => {
  automationFilter.module = module
  pruneWebAutomationSelection()
  clearWebRunLocalMessages()
}

const backupProjectData = () => {
  const packageData = exportProjectData(localStorage)
  downloadTextFile(
    `AI测试平台数据备份-${packageData.exportedAt.slice(0, 10)}.json`,
    JSON.stringify(packageData, null, 2),
    'application/json;charset=utf-8'
  )
  ElMessage.success('已导出本地数据备份')
}

const onProjectDataSelected = async (event: Event) => {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return

  try {
    const json = JSON.parse(await file.text())
    // Validate the unchanged package API in memory; only the template writer may touch its three keys.
    const validation = new Map<string,string>()
    const result = importProjectData({ setItem: (key: string, value: string) => { validation.set(key,value) } } as Storage, json)
    if (!await templateConfigModule.importConfig(json.payload.templateConfig)) return
    for (const [key,value] of validation) if (key !== TEMPLATE_CONFIG_STORAGE_KEY) localStorage.setItem(key,value)
    savedCases.value = listSavedCases(localStorage)
    executionEnvironmentLibrary.value = loadExecutionEnvironmentLibrary(localStorage)
    webExecutionEnvironmentLibrary.value = loadExecutionEnvironmentLibrary(localStorage, WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY)
    syncSelectedCaseTypes()
    ElMessage.success(`已恢复 ${result.caseCount} 条用例、${result.fieldCount} 个字段、${result.caseTypeCount} 个用例类型`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '恢复失败')
  } finally {
    input.value = ''
  }
}

const openAutomationGenerateDialog = () => {
  automationGenerateProject.value = 'ALL'
  automationGenerateModule.value = 'ALL'
  automationGenerateVisible.value = true
}

const confirmGenerateAutomationFromLibrary = () => {
  if (libraryCaseKind.value === 'web_automation') {
    convertWebLibraryCasesToAutomation(automationGenerationScope.value.cases as SavedWebAutomationCase[], true)
    return
  }

  const interfaceCases = automationGenerationScope.value.cases as SavedInterfaceCase[]
  if (interfaceCases.length === 0) {
    ElMessage.warning('没有可转换的已保存用例')
    return
  }
  const result = saveAutomationCases(localStorage, generateAutomationCasesFromInterfaceCases(interfaceCases))
  clearAutomationExecutionState('api')
  refreshAutomationCases()
  resetAutomationFilters()
  automationGenerateVisible.value = false
  navigateToNav('automation')
  ElMessage.success(`已保存 ${result.inserted} 条自动化用例，更新 ${result.updated} 条`)
}

const exportApiAutomationJson = () => {
  if (filteredAutomationCases.value.length === 0) {
    ElMessage.warning('没有可导出的自动化用例')
    return
  }

  const environment = resolveRunnableEnvironment()
  if (!environment) return

  const packageData = exportApifoxAutomationDraft(filteredAutomationCases.value, {
    environment
  })
  downloadTextFile(
    `${automationFilter.project !== 'ALL' ? automationFilter.project : '全部项目'}-API自动化用例-apifox草稿.json`,
    JSON.stringify(packageData, null, 2),
    'application/json;charset=utf-8'
  )
}

const exportWebAutomationJson = () => {
  const scope = effectiveWebAutomationScope.value
  if (scope.cases.length === 0) {
    ElMessage.warning('没有可导出的 Web 自动化用例')
    return
  }

  const packageData = exportWebAutomationRunCases(scope.cases)
  downloadTextFile(
    `${automationFilter.project !== 'ALL' ? automationFilter.project : '全部项目'}-Web自动化用例.json`,
    JSON.stringify(packageData, null, 2),
    'application/json;charset=utf-8'
  )
}

const exportAutomationJson = () => {
  if (automationKind.value === 'web') {
    exportWebAutomationJson()
    return
  }
  exportApiAutomationJson()
}

const delayWebRunPoll = (delayMs: number) => new Promise((resolve) => window.setTimeout(resolve, delayMs))

const loadWebRunCapabilities = async () => {
  const isCurrent = beginWorkspaceOperation('capabilities')
  if (!isCurrent()) return
  try {
    const capabilities = await getWebAutomationCapabilities()
    if (isCurrent()) webRunCapabilities.value = capabilities
  } catch (error) {
    if (!isCurrent()) return
    webRunCapabilities.value = undefined
    if (automationKind.value === 'web') {
      const message = error instanceof WebAutomationRunRequestError
        ? error.toFallbackMessage()
        : error instanceof Error ? error.message : '无法获取 Web 浏览器能力'
      ElMessage.warning(message)
    }
  }
}

const clearWebRunLocalMessages = () => {
  webRunCreationError.value = ''
  webRunInterruptionMessage.value = ''
}

const resolveWebLoginReadiness = (environment?: SavedExecutionEnvironment) => {
  const login = normalizeWebExecutionRuntimeConfig(environment?.web).login
  if (!login.enabled) return { enabled: false, valid: true }
  const complete = [
    login.loginUrl,
    login.usernameLocator,
    login.passwordLocator,
    login.submitLocator,
    login.username,
    login.password,
    login.successTarget
  ].every((value) => value.trim())
  return {
    enabled: true,
    valid: complete,
    message: complete ? undefined : '登录配置不完整，请补全页面、定位器、账号和成功判定'
  }
}

const resolveWebLogoutReadiness = (environment?: SavedExecutionEnvironment) => {
  const logout = normalizeWebExecutionRuntimeConfig(environment?.web).logout
  if (!logout.enabled) return { enabled: false, valid: true }
  const complete = logout.steps.length > 0 && logout.steps.every((step) => step.target.trim())
  return {
    enabled: true,
    valid: complete,
    message: complete ? undefined : '退出登录配置不完整，请补全后置步骤目标'
  }
}

const toggleWebAutomationCase = (testCase: SavedWebAutomationRunCase, checked: boolean) => {
  const next = new Set(webAutomationSelectedIds.value)
  checked ? next.add(testCase.id) : next.delete(testCase.id)
  webAutomationSelectedIds.value = Array.from(next)
  clearWebRunLocalMessages()
}

const selectFilteredWebAutomationCases = () => {
  webAutomationSelectedIds.value = filteredWebAutomationCases.value.map((item) => item.id)
  clearWebRunLocalMessages()
}

const clearWebAutomationSelection = () => {
  webAutomationSelectedIds.value = []
  clearWebRunLocalMessages()
}

const pruneWebAutomationSelection = () => {
  const visible = new Set(filteredWebAutomationCases.value.map((item) => item.id))
  webAutomationSelectedIds.value = webAutomationSelectedIds.value.filter((id) => visible.has(id))
}

const clearWebAutomationFilters = () => {
  automationFilter.source = 'ALL'
  automationFilter.keyword = ''
  webAutomationStatusFilter.value = 'ALL'
  clearWebRunLocalMessages()
}

const updateWebAutomationSourceFilter = (value: 'ALL' | 'design' | 'default') => {
  automationFilter.source = value
  clearWebRunLocalMessages()
}

const updateWebAutomationStatusFilter = (value: WebCaseStatusFilter) => {
  webAutomationStatusFilter.value = value
  clearWebRunLocalMessages()
}

const updateWebAutomationKeyword = (value: string) => {
  automationFilter.keyword = value
  clearWebRunLocalMessages()
}

const updateWebAutomationEnvironment = (environmentId: string) => {
  selectExecutionEnvironment(environmentId, 'web')
  clearWebRunLocalMessages()
}

const updateWebAutomationBrowser = (browser: string) => {
  const nextBrowser = browser as WebRuntimeBrowser
  const activeId = webExecutionEnvironmentLibrary.value.activeEnvironmentId
  webExecutionEnvironmentLibrary.value = {
    ...webExecutionEnvironmentLibrary.value,
    environments: webExecutionEnvironmentLibrary.value.environments.map((environment) => {
      if (environment.id !== activeId) return environment
      return {
        ...environment,
        web: {
          ...normalizeWebExecutionRuntimeConfig(environment.web),
          browser: nextBrowser
        }
      }
    })
  }
  persistEnvironmentLibrary('web')
  clearWebRunLocalMessages()
}

const updateWebAutomationLoopCount = (value: number) => {
  webAutomationLoopCount.value = value
  clearWebRunLocalMessages()
}

const hydrateActiveWebRunCasesFromSnapshot = (snapshot: WebAutomationRunSnapshot) => {
  const recoveredIds = [
    ...snapshot.results.map((result) => result.caseId),
    snapshot.currentCase?.id
  ].filter((caseId): caseId is string => Boolean(caseId))
  if (recoveredIds.length === 0) return

  const activeById = new Map(activeWebRunCases.value.map((testCase) => [testCase.id, testCase]))
  const savedById = new Map(webAutomationCases.value.map((testCase) => [testCase.id, testCase]))
  const savedIds = new Set(savedById.keys())
  let changed = false

  for (const caseId of recoveredIds) {
    const originalId = resolveWebRunCaseId(caseId, savedIds)
    if (!originalId || activeById.has(originalId)) continue
    const savedCase = savedById.get(originalId)
    if (!savedCase) continue
    activeById.set(originalId, savedCase)
    changed = true
  }

  if (changed) activeWebRunCases.value = Array.from(activeById.values())
}

const applyWebRunSnapshot = (snapshot: WebAutomationRunSnapshot) => {
  activeWebRunSnapshot.value = snapshot
  webAutomationRunReport.value = snapshotToReport(snapshot)
  hydrateActiveWebRunCasesFromSnapshot(snapshot)
}

const writeWebMonitorQuery = (mode: 'workbench' | 'monitor', runId = '', historyMode: 'push' | 'replace' = 'push') => {
  const url = new URL(window.location.href)
  if (mode === 'monitor' && runId) {
    url.searchParams.set('view', 'monitor')
    url.searchParams.set('runId', runId)
  } else {
    url.searchParams.delete('view')
    url.searchParams.delete('runId')
  }
  window.history[historyMode === 'replace' ? 'replaceState' : 'pushState']({}, '', `${url.pathname}${url.search}`)
}

const clearRecoveredWebRun = (message = '执行已中断：Runner 中已找不到当前任务') => {
  sessionStorage.removeItem(WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY)
  activeWebRunId.value = ''
  webAutomationRunning.value = false
  webRunConnectionState.value = 'interrupted'
  webRunInterruptionMessage.value = message
  webRunPollCoordinator.stop()
  webAutomationWorkspaceMode.value = 'workbench'
  writeWebMonitorQuery('workbench', '', 'replace')
}

const finalizeWebRunSnapshot = (snapshot: WebAutomationRunSnapshot) => {
  applyWebRunSnapshot(snapshot)
  webAutomationRunning.value = false
  webRunConnectionState.value = 'idle'
  if (finalizedWebRunId === snapshot.runId) return
  finalizedWebRunId = snapshot.runId

  const report = snapshotToReport(snapshot)
  const casesToRun = activeWebRunCases.value
  if (report.results.length > 0) {
    const reportRecord = buildSavedAutomationReportRecord(report, {
      reportId: `web-report-${Date.now()}`,
      name: `Web-${new Date().toLocaleString('zh-CN', { hour12: false })}`,
      project: automationFilter.project !== 'ALL' ? automationFilter.project : (casesToRun[0]?.project || '全部项目'),
      environmentName: activeWebExecutionEnvironment.value?.name || 'Web 默认环境',
      moduleScope:
        automationFilter.module !== 'ALL'
          ? [automationFilter.module]
          : Array.from(new Set(casesToRun.map((testCase) => testCase.module))),
      createdAt: new Date().toISOString()
    })
    appendAutomationReport(localStorage, reportRecord)
    refreshAutomationReports()
    selectedReportProject.value = reportRecord.project
    selectedReportId.value = reportRecord.reportId
  }

  if (snapshot.status === 'stopped') {
    ElMessage.info({ message: 'Web 执行已停止，已保留当前日志和截图', customClass: 'web-run-terminal-feedback' })
  } else if (snapshot.status === 'failed') {
    ElMessage.error({ message: snapshot.diagnostics[snapshot.diagnostics.length - 1] || 'Web 执行任务异常结束', customClass: 'web-run-terminal-feedback' })
  } else {
    ElMessage.success({
      message: `执行完成：通过 ${report.summary.passed}，失败 ${report.summary.failed}，错误 ${report.summary.error}`,
      customClass: 'web-run-terminal-feedback'
    })
  }
}

const monitorActiveWebRun = async (runId: string, isCurrent = beginWorkspaceOperation('webRun')) => {
  if (!isCurrent()) return
  webRunPollCoordinator.stop()
  const poll = webRunPollCoordinator.start(runId)
  if (!poll.shouldStart) return
  let consecutiveFailures = 0
  try {
    while (isCurrent() && webRunPollCoordinator.isCurrent(runId, poll.token) && activeWebRunId.value === runId) {
      try {
        const snapshot = await getWebAutomationRun(runId)
        if (!isCurrent() || !webRunPollCoordinator.isCurrent(runId, poll.token)) return
        consecutiveFailures = 0
        webRunConnectionState.value = 'polling'
        applyWebRunSnapshot(snapshot)
        if (isTerminalWebAutomationRun(snapshot.status)) {
          finalizeWebRunSnapshot(snapshot)
          return
        }
        await delayWebRunPoll(500)
      } catch (error) {
        if (!isCurrent() || !webRunPollCoordinator.isCurrent(runId, poll.token)) return
        consecutiveFailures += 1
        webRunConnectionState.value = consecutiveFailures >= 3 ? 'interrupted' : 'retrying'
        if (error instanceof WebAutomationRunRequestError && error.status === 404) {
          clearRecoveredWebRun()
          return
        }
        await delayWebRunPoll(Math.min(3000, 500 * consecutiveFailures))
      }
    }
  } finally {
    if (isCurrent() && webRunPollCoordinator.isCurrent(runId, poll.token)) {
      webRunPollCoordinator.stop(runId)
    }
  }
}

const resolveVisualWebAutomationRunProjectId = async (
  cases: SavedWebAutomationRunCase[],
  isCurrent: () => boolean
): Promise<number | undefined> => {
  const projectNames = Array.from(new Set(cases.map((item) => (item.project || '').trim()).filter(Boolean)))
  if (projectNames.length !== 1) {
    return undefined
  }
  try {
    const project = await ensureBackendProject(projectNames[0], { fetchImpl: workspaceFetch(isCurrent) })
    return project.id
  } catch {
    return undefined
  }
}

/** 发起瞬间的 live 值 → 请求快照（只在第一个 await 之前调用）。 */
const captureWebRunStartRequest = (): WebRunStartRequest => ({
  scopeLabel: effectiveWebAutomationScope.value.actionLabel,
  selectedCaseIds: [...webAutomationSelectedIds.value],
  loopCount: webAutomationLoopCount.value,
  environmentId: activeWebExecutionEnvironment.value?.id || '',
  environmentName: activeWebExecutionEnvironment.value?.name || '',
  environmentBaseUrl: activeWebExecutionEnvironment.value?.baseUrl || '',
  browser: runnableWebRuntime.value?.browser || 'follow-case',
  mode: webAutomationRunMode.value
})

const startVisualWebAutomationRun = async (
  explicitCases?: SavedWebAutomationRunCase[],
  confirmedRequest?: WebRunStartRequest
): Promise<boolean> => {
  if (webAutomationRunning.value) return false
  const isCurrent = beginWorkspaceOperation('webRun')
  if (!isCurrent()) return false
  const request = isWebRunStartRequest(confirmedRequest) ? confirmedRequest : captureWebRunStartRequest()
  // 快照：范围用例 / 环境 / 浏览器 / 循环次数 / 运行时配置。之后跨 await 只读这份快照。
  const frozen = freezeWebRunStart(request, explicitCases || effectiveWebAutomationScope.value.cases)
  const casesToRun = frozen.cases
  if (!explicitCases && !webAutomationPreflight.value.canExecute) {
    webRunCreationError.value = webAutomationPreflight.value.blockingReason
    return false
  }
  if (casesToRun.length === 0) {
    webRunCreationError.value = '没有可执行的 Web 自动化用例'
    return false
  }
  const environmentSnapshot = cloneEnvironment(
    activeWebExecutionEnvironment.value || defaultWebExecutionEnvironmentLibrary().environments[0]
  )
  if (frozen.environmentId && !environmentSnapshot.id) environmentSnapshot.id = frozen.environmentId
  // 确认框显示的环境地址就是本次要用的地址；即便 live 环境随后被改动，也只发快照里的地址。
  if (frozen.environmentBaseUrl) environmentSnapshot.baseUrl = frozen.environmentBaseUrl
  // 浏览器同理：确认框里的执行浏览器就是提交的 browserOverride（follow-case 表示跟随用例）。
  if (frozen.browser && frozen.browser !== 'follow-case') {
    environmentSnapshot.web = {
      ...normalizeWebExecutionRuntimeConfig(environmentSnapshot.web),
      browser: frozen.browser as WebRuntimeBrowser
    }
  }
  // 校验与运行时构建都用快照，不再回读 live 环境。
  const validation = validateWebExecutionEnvironment(environmentSnapshot)
  if (!validation.valid) {
    ElMessage.warning(validation.message)
    environmentVisible.value = true
    openEnvironmentEditor(environmentSnapshot.id, 'web')
    return false
  }
  const frozenRuntime = buildWebExecutionRuntime(environmentSnapshot)

  clearWebRunLocalMessages()
  // 先置运行态再解析项目 id：解析含后端调用（await），期间保持互斥，双击不会并发双启动（第二十六轮 C-2）。
  webAutomationRunning.value = true
  const runProjectId = await resolveVisualWebAutomationRunProjectId(casesToRun, isCurrent)
  if (!isCurrent()) return false
  activeWebRunCases.value = [...casesToRun]
  webRunConnectionState.value = 'polling'
  finalizedWebRunId = ''
  webRunPollCoordinator.stop()
  try {
    const created = await createWebAutomationRun(casesToRun, {
      mode: frozen.mode,
      loopCount: frozen.loopCount,
      browserOverride: frozenRuntime.browser,
      runtime: frozenRuntime,
      projectId: runProjectId
    })
    if (!isCurrent()) return false
    activeWebRunId.value = created.runId
    applyWebRunSnapshot(created)
    sessionStorage.setItem(WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY, created.runId)
    webAutomationWorkspaceMode.value = 'workbench'
    void monitorActiveWebRun(created.runId, isCurrent)
    return true
  } catch (error) {
    if (!isCurrent()) return false
    webAutomationRunning.value = false
    webRunConnectionState.value = 'interrupted'
    const message = error instanceof WebAutomationRunRequestError
      ? error.toFallbackMessage()
      : error instanceof Error ? error.message : 'Web runner 不可用'
    webRunCreationError.value = message
    ElMessage.error(message)
    return false
  }
}

const pauseActiveWebRun = async () => {
  const currentOperation = beginWorkspaceOperation('webRunControl')
  const runId = activeWebRunId.value
  const runOperation = workspaceOperationId('webRun')
  const isCurrent = () => currentOperation() && activeWebRunId.value === runId && workspaceOperationId('webRun') === runOperation
  if (!runId || !isCurrent()) return
  webRunControlLoading.value = 'pause'
  try {
    const snapshot = await pauseWebAutomationRun(runId)
    if (!isCurrent()) return
    applyWebRunSnapshot(snapshot)
  } catch (error) {
    if (isCurrent()) ElMessage.error(error instanceof WebAutomationRunRequestError ? error.toFallbackMessage() : '暂停执行失败')
  } finally {
    if (isCurrent()) webRunControlLoading.value = ''
  }
}

const resumeActiveWebRun = async () => {
  const currentOperation = beginWorkspaceOperation('webRunControl')
  const runId = activeWebRunId.value
  const runOperation = workspaceOperationId('webRun')
  const isCurrent = () => currentOperation() && activeWebRunId.value === runId && workspaceOperationId('webRun') === runOperation
  if (!runId || !isCurrent()) return
  webRunControlLoading.value = 'resume'
  try {
    const snapshot = await resumeWebAutomationRun(runId)
    if (!isCurrent()) return
    applyWebRunSnapshot(snapshot)
    if (webRunConnectionState.value === 'interrupted') {
      // Release this control before the restarted monitor advances the run generation.
      webRunControlLoading.value = ''
      void monitorActiveWebRun(activeWebRunId.value)
    }
  } catch (error) {
    if (isCurrent()) ElMessage.error(error instanceof WebAutomationRunRequestError ? error.toFallbackMessage() : '继续执行失败')
  } finally {
    if (isCurrent()) webRunControlLoading.value = ''
  }
}

const stopActiveWebRun = async () => {
  const currentOperation = beginWorkspaceOperation('webRunControl')
  const runId = activeWebRunId.value
  const runOperation = workspaceOperationId('webRun')
  const isCurrent = () => currentOperation() && activeWebRunId.value === runId && workspaceOperationId('webRun') === runOperation
  if (!runId || !isCurrent()) return
  webRunControlLoading.value = 'stop'
  try {
    const snapshot = await stopWebAutomationRun(runId)
    if (!isCurrent()) return
    applyWebRunSnapshot(snapshot)
    if (isTerminalWebAutomationRun(snapshot.status)) finalizeWebRunSnapshot(snapshot)
  } catch (error) {
    if (isCurrent()) ElMessage.error(error instanceof WebAutomationRunRequestError ? error.toFallbackMessage() : '停止执行失败')
  } finally {
    if (isCurrent()) webRunControlLoading.value = ''
  }
}

const activeWebRunResultById = (caseOrId: SavedWebAutomationRunCase | string) => {
  const caseId = typeof caseOrId === 'string' ? caseOrId : caseOrId.id
  const originalId = resolveWebRunCaseId(caseId, new Set(activeWebRunCases.value.map((item) => item.id)))
  const testCase = activeWebRunCases.value.find((item) => item.id === originalId)
  const result = webAutomationRunReport.value?.results.find((row) => row.caseId === caseId)
    || (originalId ? webAutomationResultMap.value.get(originalId) : undefined)
  return testCase && result ? { testCase, result } : undefined
}

const openWebAutomationRunLog = (caseOrId: SavedWebAutomationRunCase | string) => {
  const resolved = activeWebRunResultById(caseOrId)
  if (!resolved) return
  selectedAutomationResult.value = webRunResultToAutomationLog(resolved.result)
  automationLogVisible.value = true
}

const transferWebAutomationRunResultToDefect = (caseOrId: SavedWebAutomationRunCase | string) => {
  const resolved = activeWebRunResultById(caseOrId)
  if (resolved && (resolved.result.status === 'failed' || resolved.result.status === 'error')) {
    defects.createDefectsFromWebResults([resolved.result], resolved.testCase.project)
  }
}

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

const recoverWebRunSession = async (historyMode: 'push' | 'replace' = 'replace') => {
  const isCurrent = beginWorkspaceOperation('webRun')
  if (!isCurrent()) return
  webRunPollCoordinator.stop()
  const recovery = resolveWebRunRecovery(
    window.location.search,
    sessionStorage.getItem(WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY) || ''
  )
  if (!recovery.runId) {
    webAutomationWorkspaceMode.value = 'workbench'
    return
  }

  try {
    const snapshot = await getWebAutomationRun(recovery.runId)
    if (!isCurrent()) return
    activeWebRunId.value = snapshot.runId
    applyWebRunSnapshot(snapshot)
    sessionStorage.setItem(WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY, snapshot.runId)
    webRunConnectionState.value = isTerminalWebAutomationRun(snapshot.status) ? 'idle' : 'polling'
    webAutomationWorkspaceMode.value = recovery.mode
    if (recovery.mode === 'monitor') writeWebMonitorQuery('monitor', snapshot.runId, historyMode)
    if (!isTerminalWebAutomationRun(snapshot.status)) {
      // 刷新恢复的轮询同样属于「运行中」：置位运行态，避免重跑按钮在恢复期间出现（第二十六轮 C-1）。
      webAutomationRunning.value = true
      void monitorActiveWebRun(snapshot.runId, isCurrent)
    }
  } catch (error) {
    if (!isCurrent()) return
    if (error instanceof WebAutomationRunRequestError && error.status === 404) {
      clearRecoveredWebRun()
      return
    }
    webRunConnectionState.value = 'retrying'
    webRunInterruptionMessage.value = error instanceof WebAutomationRunRequestError
      ? error.toFallbackMessage()
      : error instanceof Error ? error.message : '恢复 Web 执行状态失败'
  }
}

const executeAllAutomationCases = async () => {
  if (automationKind.value === 'web') {
    await startVisualWebAutomationRun()
    return
  }
  await apiExecutionController.executeAllAutomationCases()
}

const refreshAutomationCases = () => {
  automationCases.value = listSavedAutomationCases(localStorage)
  webAutomationCases.value = listSavedWebAutomationRunCases(localStorage)
}

const applyJiraConfig = (config: JiraConfig, options: { resetTestResult?: boolean } = {}) => {
  Object.assign(jiraConfigForm, config)
  jiraConfigReadState.value = 'ready'
  jiraProjectJqlRows.value = jiraRowsFromProjectJql(config.projectJql)
  jiraConfigSavedSnapshot.value = jiraDraftSnapshotOf(config, jiraProjectJqlRows.value)
  jiraConfigValidationError.value = ''
  // 连接参数变化后,旧的测试结论不再适用;保存(参数未变)则保留结论
  if (options.resetTestResult !== false) {
    jiraTestGeneration += 1
    jiraConfigTestResult.value = { state: 'idle', message: '', target: '' }
  }
}

const syncJiraProjectJqlFormFromRows = () => {
  jiraConfigForm.projectJql = jiraProjectJqlFromRows(jiraProjectJqlRows.value)
}

/** 提交体:行数组是唯一来源,token/地址做去空格处理后再发请求 */
const currentJiraPayload = (): JiraConfig => ({
  ...jiraConfigForm,
  baseUrl: jiraConfigForm.baseUrl.trim(),
  username: jiraConfigForm.username.trim(),
  projectJql: jiraProjectJqlFromRows(jiraProjectJqlRows.value)
})

/** 丢弃未保存草稿前的统一确认(重新加载 / 离开本页共用同一套文案与语义) */
const confirmDiscardJiraDraft = async (message: string): Promise<boolean> => {
  if (!jiraConfigDirty.value) return true
  try {
    await ElMessageBox.confirm(message, '放弃未保存的 Jira 配置？', {
      confirmButtonText: '放弃修改',
      cancelButtonText: '继续编辑',
      type: 'warning',
      customClass: 'theme-confirm-box'
    })
    return true
  } catch {
    return false
  }
}

/**
 * Jira 侧所有「await 之后仍要落动作」的唯一 authority。
 * 发起时固定会话 epoch + 本资源操作序号,await 结束后用同一口径校验一次:
 * owner 切换/重新登录、或同一资源上有更新的操作,都让旧意图失效(不再每处补 flag)。
 */
function captureJiraIntent(): () => boolean {
  return beginWorkspaceOperation('jiraConfig')
}

/** Jira 写动作(保存 / 连接测试 / 映射增删)的唯一门禁:只读角色一律拒绝;读(重新加载)不受限。 */
const rejectJiraWrite = (): boolean => {
  if (canEdit.value) return false
  jiraConfigValidationError.value = '当前账号为只读角色，不能修改或测试 Jira 连接配置。'
  ElMessage.warning('当前账号为只读角色，不能修改或测试 Jira 连接配置。')
  return true
}

// 编辑即清除上一次的校验提示,避免旧错误留在新草稿上
watch(jiraDraftSnapshot, () => {
  if (jiraConfigValidationError.value) jiraConfigValidationError.value = ''
})

// 连接参数一变,进行中/已完成的连接测试结论立即失效(原型 edited(true) → stale)
watch(
  () => jiraConnectionSnapshot(jiraConfigForm),
  (next, previous) => {
    if (next === previous) return
    const wasTesting = jiraConfigTesting.value
    jiraTestGeneration += 1
    jiraConfigTesting.value = false
    if (!wasTesting && !['success', 'failed'].includes(jiraConfigTestResult.value.state)) return
    jiraConfigTestResult.value = {
      state: 'stale',
      message: '连接参数已改变，原测试状态已失效；请按当前参数重新测试。',
      target: jiraConfigTestResult.value.target
    }
  }
)

/** 存在未保存修改时阻止浏览器关闭/刷新 */
const handleJiraBeforeUnload = (event: BeforeUnloadEvent) => {
  if (!jiraConfigDirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

watch(
  jiraConfigDirty,
  (dirty) => {
    if (typeof window === 'undefined') return
    if (dirty) window.addEventListener('beforeunload', handleJiraBeforeUnload)
    else window.removeEventListener('beforeunload', handleJiraBeforeUnload)
  },
  { immediate: true }
)

/** 环境草稿存在未保存修改时同样阻止关闭/刷新；与 Jira 各自 addEventListener,互不覆盖。 */
const handleEnvironmentBeforeUnload = (event: BeforeUnloadEvent) => {
  if (!environmentDraftDirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

watch(
  environmentDraftDirty,
  (dirty) => {
    if (typeof window === 'undefined') return
    if (dirty) window.addEventListener('beforeunload', handleEnvironmentBeforeUnload)
    else window.removeEventListener('beforeunload', handleEnvironmentBeforeUnload)
  },
  { immediate: true }
)

const addJiraProjectJqlRow = () => {
  if (rejectJiraWrite()) return
  jiraProjectJqlRows.value.push({ key: '', value: '' })
}

const removeJiraProjectJqlRow = async (index: number) => {
  if (rejectJiraWrite()) return
  const row = jiraProjectJqlRows.value[index]
  // 空行是未填写的草稿行，删除无数据损失；有内容的映射删除前需二次确认。
  if (row && (row.key.trim() || row.value.trim())) {
    const label = row.key.trim()
    const message = label ? `确认删除项目「${label}」的 JQL 映射吗？` : '确认删除这条 JQL 映射吗？'
    // 确认框跨 await:用发起时的行内容 + 会话 authority 判定,旧意图不得 splice 新 owner 的行
    const isCurrent = captureJiraIntent()
    const rowKey = row.key
    const rowValue = row.value
    const confirmed = await confirmDangerAction('删除 JQL 映射', message)
    if (!confirmed || !isCurrent()) return
    const currentIndex = jiraProjectJqlRows.value.findIndex(
      (candidate) => candidate.key === rowKey && candidate.value === rowValue
    )
    if (currentIndex < 0) return
    jiraProjectJqlRows.value.splice(currentIndex, 1)
    syncJiraProjectJqlFormFromRows()
    return
  }
  jiraProjectJqlRows.value.splice(index, 1)
  syncJiraProjectJqlFormFromRows()
}

const loadJiraSettingsFromBackend = async () => {
  const isCurrent = beginWorkspaceOperation('jiraConfig')
  if (!isCurrent()) return
  if (!await confirmDiscardJiraDraft('重新加载会丢弃当前未保存的连接设置与 JQL 映射。')) return
  if (!isCurrent()) return
  jiraConfigLoading.value = true
  jiraConfigReadState.value = 'loading'
  try {
    const config = await loadJiraConfigFromPlatform()
    if (isCurrent()) applyJiraConfig(config)
  } catch {
    // Preserve the form, but never present an unverified read as backend data.
    if (isCurrent()) jiraConfigReadState.value = 'error'
  } finally {
    if (isCurrent()) jiraConfigLoading.value = false
  }
}

const saveJiraSettings = async () => {
  if (rejectJiraWrite()) return
  const isCurrent = beginWorkspaceOperation('jiraConfig')
  if (!isCurrent()) return
  const validation = validateJiraDraft(jiraConfigForm, jiraProjectJqlRows.value)
  if (validation) {
    jiraConfigValidationError.value = validation
    ElMessage.error(validation)
    return
  }
  jiraConfigValidationError.value = ''
  jiraConfigLoading.value = true
  try {
    // 发起时固定提交内容:保存期间的新编辑既不能被服务端回显覆盖,也不能被算作已保存
    const payload = currentJiraPayload()
    const submittedSnapshot = jiraDraftSnapshotOf(payload, jiraProjectJqlRows.value)
    const connectionBefore = jiraConnectionSnapshot(payload)
    const config = await saveJiraConfigToPlatform(payload)
    if (!isCurrent()) return
    jiraConfigReadState.value = 'ready'
    if (jiraDraftSnapshot.value === submittedSnapshot) {
      applyJiraConfig(config, {
        resetTestResult: jiraConnectionSnapshot(config) !== connectionBefore
      })
    } else {
      // 保留更新的草稿,只合并服务端的凭据/时间元数据;已保存基线 = 本次真正提交的内容
      jiraConfigSavedSnapshot.value = submittedSnapshot
      jiraConfigForm.credentialConfigured = config.credentialConfigured
      jiraConfigForm.maskedToken = config.maskedToken
      jiraConfigForm.updatedAt = config.updatedAt
    }
    ElMessage.success('已保存 Jira 配置')
  } catch (error) {
    if (isCurrent()) ElMessage.error(error instanceof Error ? error.message : '保存 Jira 配置失败')
  } finally {
    if (isCurrent()) jiraConfigLoading.value = false
  }
}

const testJiraConnection = async () => {
  if (rejectJiraWrite()) return
  const isCurrent = beginWorkspaceOperation('jiraTest')
  if (!isCurrent()) return
  const validation = jiraTestTargetError(jiraConfigForm)
  if (validation) {
    jiraConfigValidationError.value = validation
    ElMessage.error(validation)
    return
  }
  jiraConfigValidationError.value = ''
  const payload = currentJiraPayload()
  const connection = jiraConnectionSnapshot(payload)
  const target = payload.baseUrl
  const generation = ++jiraTestGeneration
  jiraConfigTesting.value = true
  jiraConfigTestResult.value = { state: 'idle', message: '正在请求 Jira;请稍候。', target }
  try {
    const result = await testJiraConfigConnection(payload)
    if (!isCurrent() || generation !== jiraTestGeneration) return
    if (jiraConnectionSnapshot(jiraConfigForm) !== connection) {
      // 参数已改:只在测试目标仍然成立时保留结论,否则就是新地址的假结论
      jiraConfigTestResult.value = {
        state: 'stale',
        message: '连接参数已改变，原测试状态已失效；请按当前参数重新测试。',
        target
      }
      return
    }
    jiraConfigTestResult.value = {
      state: result.success ? 'success' : 'failed',
      message: result.message || (result.success ? '连接测试通过' : '连接测试未通过'),
      target
    }
    if (result.success) {
      ElMessage.success(result.message || '连接测试通过')
    } else {
      ElMessage.warning(result.message || '连接测试未通过')
    }
  } catch (error) {
    if (!isCurrent() || generation !== jiraTestGeneration) return
    jiraConfigTestResult.value = {
      state: 'failed',
      message: error instanceof Error ? error.message : '连接测试失败',
      target
    }
    ElMessage.error(error instanceof Error ? error.message : '连接测试失败')
  } finally {
    if (isCurrent() && generation === jiraTestGeneration) jiraConfigTesting.value = false
  }
}

const transferAutomationResultToDefect = (testCase: SavedAutomationCase) => {
  const result = automationResultMap.value.get(testCase.id)
  if (!result || (result.status !== 'failed' && result.status !== 'error')) {
    ElMessage.info('只有失败或错误的自动化结果可以转缺陷')
    return
  }
  defects.createDefectsFromResults([result], testCase.project)
}

const transferWebAutomationResultToDefect = (testCase: SavedWebAutomationRunCase) => {
  const result = webAutomationResultMap.value.get(testCase.id)
  if (!result || (result.status !== 'failed' && result.status !== 'error')) {
    ElMessage.info('只有失败或错误的 Web 自动化结果可以转缺陷')
    return
  }
  defects.createDefectsFromWebResults([result], testCase.project)
}

const transferFilteredDesignAutomationResultsToDefects = () => {
  const results = collectFailedDesignOriginResults(filteredWebAutomationCases.value, webAutomationRunReport.value)
  if (results.length === 0) {
    ElMessage.info('当前没有可批量转缺陷的设计稿执行结果')
    return
  }
  defects.createDefectsFromWebResults(results, filteredWebAutomationCases.value[0]?.project || jiraProjectName.value)
}

const deleteWebAutomationCase = async (testCase: SavedWebAutomationRunCase) => {
  const confirmed = await confirmDangerAction('删除 Web 自动化用例', `确认删除 ${testCase.id} 吗？`)
  if (!confirmed) return
  deleteWebAutomationRunCase(localStorage, testCase.automationLibraryId)
  pruneAutomationExecutionState([testCase.id])
  refreshAutomationCases()
  pruneWebAutomationSelection()
  clearWebRunLocalMessages()
  ElMessage.success('已删除 Web 自动化用例')
}

const clearAutomationExecutionState = (kind: 'api' | 'web' = automationKind.value) => {
  if (kind === 'web') {
    webAutomationRunReport.value = undefined
  } else {
    automationRunReport.value = undefined
  }
  selectedAutomationResult.value = undefined
  automationLogVisible.value = false
}

const pruneAutomationExecutionState = (caseIds: string[]) => {
  if (automationKind.value === 'web') {
    webAutomationRunReport.value = removeResultsFromWebAutomationRunReport(webAutomationRunReport.value, caseIds)
    return
  }
  automationRunReport.value = removeResultsFromAutomationRunReport(automationRunReport.value, caseIds)
  if (!automationRunReport.value || (selectedAutomationResult.value && caseIds.includes(selectedAutomationResult.value.caseId))) {
    selectedAutomationResult.value = undefined
    automationLogVisible.value = false
  }
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
const handleDefectBeforeUnload = (event: BeforeUnloadEvent) => {
  if (!defects.defectRecoveryDirty.value) return
  event.preventDefault()
  event.returnValue = ''
}
watch(defects.defectRecoveryDirty, dirty => {
  if (dirty) window.addEventListener('beforeunload', handleDefectBeforeUnload)
  else window.removeEventListener('beforeunload', handleDefectBeforeUnload)
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

const homeViewRef = ref<InstanceType<typeof HomeView>>()
const homeProjects = computed<HomeProject[]>(() => {
  const names = new Set<string>()
  for (const item of savedCases.value) if (item.row.project) names.add(item.row.project)
  for (const item of savedWebCases.value) if (item.row.project) names.add(item.row.project)
  for (const item of automationCases.value) if (item.project) names.add(item.project)
  for (const item of webAutomationCases.value) if (item.project) names.add(item.project)
  for (const item of automationReports.value) if (item.project) names.add(item.project)
  for (const item of defects.defectDrafts.value) if (item.projectName) names.add(item.projectName)
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
  if (apiRunMonitor.value && ['running', 'error'].includes(apiRunMonitor.value.status)) {
    const monitor = apiRunMonitor.value
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
  const web = activeWebRunSnapshot.value
  if (web && !['completed', 'failed', 'stopped'].includes(web.status)) {
    runs.push({
      id: `web-run-${web.runId}`,
      name: web.currentCase?.name || 'Web 自动化执行',
      kind: 'web',
      project: activeWebRunCases.value[0]?.project || '',
      environmentName: runnableWebRuntime.value?.baseUrl,
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
  for (const testCase of savedCases.value) {
    items.push({ id: testCase.libraryId, name: testCase.row.title, project: testCase.row.project || '', kind: 'case', savedAt: testCase.savedAt, context: testCase.row.module, target: { navKey: 'cases', caseId: testCase.libraryId, project: testCase.row.project || '' } })
  }
  for (const testCase of savedWebCases.value) {
    items.push({ id: testCase.libraryId, name: testCase.row.title, project: testCase.row.project || '', kind: 'case', savedAt: testCase.savedAt, context: testCase.row.module, target: { navKey: 'webCases', caseId: testCase.libraryId, project: testCase.row.project || '' } })
  }
  for (const testCase of automationCases.value) {
    items.push({ id: testCase.automationLibraryId, name: testCase.name, project: testCase.project || '', kind: 'case', savedAt: testCase.savedAt, context: testCase.module, target: { navKey: 'automation', automationKind: 'api', caseId: testCase.automationLibraryId, project: testCase.project || '' } })
  }
  for (const testCase of webAutomationCases.value) {
    items.push({ id: testCase.automationLibraryId, name: testCase.name, project: testCase.project || '', kind: 'case', savedAt: testCase.savedAt, context: testCase.module, target: { navKey: 'automation', automationKind: 'web', caseId: testCase.automationLibraryId, project: testCase.project || '' } })
  }
  for (const report of automationReports.value) {
    items.push({ id: report.reportId, name: report.name, project: report.project || '', kind: 'report', savedAt: report.createdAt, context: report.environmentName, target: { navKey: 'reports', reportId: report.reportId, project: report.project || '' } })
  }
  for (const draft of defects.defectDrafts.value) {
    items.push({ id: draft.defectId, name: draft.summary, project: draft.projectName || '', kind: 'defect', savedAt: draft.updatedAt || draft.createdAt, statusLabel: defects.defectStatusText(draft), tone: draft.status === 'pending_jira_export' ? 'warning' : draft.status === 'created_in_jira' ? 'success' : 'neutral', context: draft.issueType, pending: draft.status === 'pending_jira_export' ? { label: '待导出 Jira', actionLabel: '处理', reason: '草稿尚未导出' } : undefined, target: { navKey: 'defects', defectId: draft.defectId, project: draft.projectName || '' } })
  }
  return items
})

const deleteAutomationCase = async (testCase: SavedAutomationCase) => {
  const confirmed = await confirmDangerAction('删除自动化用例', `确认删除 ${testCase.id} 吗？`)
  if (!confirmed) return
  deleteSavedAutomationCase(localStorage, testCase.automationLibraryId)
  pruneAutomationExecutionState([testCase.id])
  refreshAutomationCases()
  automationSelection.value = automationSelection.value.filter((item) => item.automationLibraryId !== testCase.automationLibraryId)
  ElMessage.success('已删除自动化用例')
}

const deleteAutomationByScope = async (scope: { project?: string; module?: string }) => {
  if (automationKind.value === 'web') {
    if (filteredWebAutomationCases.value.length === 0) {
      ElMessage.warning('当前范围没有可删除的 Web 自动化用例')
      return
    }
    const confirmed = await confirmDangerAction(
      '删除当前范围',
      `确认删除当前范围的 ${filteredWebAutomationCases.value.length} 条 Web 自动化用例吗？`
    )
    if (!confirmed) return
    const removingCases = filteredWebAutomationCases.value
    const removingIds = removingCases.map((item) => item.id)
    const deleted = deleteWebAutomationRunCases(localStorage, removingCases.map((item) => item.automationLibraryId))
    pruneAutomationExecutionState(removingIds)
    refreshAutomationCases()
    pruneWebAutomationSelection()
    clearWebRunLocalMessages()
    ElMessage.success(`已删除 ${deleted} 条 Web 自动化用例`)
    return
  }
  if (filteredAutomationCases.value.length === 0) {
    ElMessage.warning('当前范围没有可删除的自动化用例')
    return
  }
  const confirmed = await confirmDangerAction(
    '删除当前范围',
    `确认删除当前范围的 ${filteredAutomationCases.value.length} 条自动化用例吗？`
  )
  if (!confirmed) return
  const removingCases = filteredAutomationCases.value
  const removingIds = removingCases.map((item) => item.id)
  const deleted = deleteAutomationCases(localStorage, removingCases.map((item) => item.automationLibraryId))
  pruneAutomationExecutionState(removingIds)
  refreshAutomationCases()
  automationSelection.value = []
  ElMessage.success(`已删除 ${deleted} 条自动化用例`)
}

const currentBackendProjectName = () =>
  activeProject.value?.title
    || (libraryFilter.project !== 'ALL' ? libraryFilter.project : '默认项目')

const openEdit = (testCase: GeneratedInterfaceCase | SavedInterfaceCase) => {
  selectedCase.value = testCase
  selectedLibraryId.value = 'libraryId' in testCase ? testCase.libraryId : undefined
  Object.assign(editRow, emptyEditRow(), testCase.row)
  editVisible.value = true
}

const openWebEdit = (testCase: GeneratedWebAutomationCase | SavedWebAutomationCase) => {
  selectedCase.value = testCase
  selectedLibraryId.value = 'libraryId' in testCase ? testCase.libraryId : undefined
  Object.assign(webEditRow, emptyWebEditRow(), testCase.row, {
    environmentId: testCase.row.environmentId || webExecutionEnvironmentLibrary.value.activeEnvironmentId,
    steps: serializeWebAutomationSteps(testCase.metadata.steps)
  })
  editVisible.value = true
}

const applySelectedWebEnvironmentToEditRow = () => {
  const environment = webExecutionEnvironmentLibrary.value.environments.find((item) => item.id === webEditRow.environmentId)
    || activeWebExecutionEnvironment.value
  if (!environment) {
    ElMessage.warning('请先选择 Web 运行环境')
    return
  }
  const runtime = buildWebExecutionRuntime(environment)
  webEditRow.browser = runtime.browser
  webEditRow.viewport = runtime.viewport
  webEditRow.waitStrategy = runtime.waitUntil
  webEditRow.screenshotStrategy = runtime.screenshotStrategy
  webEditRow.retryCount = runtime.retryCount
  ElMessage.success('已应用 Web 运行环境默认参数')
}

const saveEdit = async () => {
  if (!selectedCase.value) return

  if (isWebEditableCase(selectedCase.value)) {
    const updated = syncWebAutomationMetadataFromRow({
      ...selectedCase.value,
      row: { ...webEditRow }
    })
    selectedCase.value.row = updated.row
    selectedCase.value.metadata = updated.metadata
    if (selectedLibraryId.value) {
      deleteSavedWebAutomationCase(localStorage, selectedLibraryId.value)
      saveGeneratedWebAutomationCases(localStorage, [selectedCase.value])
      refreshSavedCases()
      editVisible.value = false
      ElMessage.success('已更新 Web 自动化用例')
      return
    }
    editVisible.value = false
    ElMessage.success('已更新当前 Web 用例')
    return
  }

  const interfaceCase = selectedCase.value
  const updated = applyInterfaceFieldTemplate(syncMetadataFromRow({
    ...interfaceCase,
    row: { ...editRow }
  }), interfaceFields.value)
  selectedCase.value.row = updated.row
  selectedCase.value.metadata = updated.metadata
  if (selectedLibraryId.value) {
    deleteSavedCase(localStorage, selectedLibraryId.value)
    saveGeneratedCases(localStorage, [selectedCase.value], {
      savedAt: new Date().toISOString()
    })
    refreshSavedCases()
    const savedProject = selectedCase.value.row.project || '默认项目'
    const result = await syncSavedCaseProjectsWithBackend(localStorage, [savedProject])
    editVisible.value = false
    ElMessage.success(result.source === 'backend' ? '已更新当前用例，并同步到后端' : '已更新当前用例')
    return
  }
  editVisible.value = false
  ElMessage.success('已更新当前用例')
}

const isWebEditableCase = (
  testCase: GeneratedInterfaceCase | GeneratedWebAutomationCase | SavedInterfaceCase | SavedWebAutomationCase
): testCase is GeneratedWebAutomationCase | SavedWebAutomationCase => 'pageName' in testCase.row

const loadBackendBackedWorkspace = async (
  options: {
    conflictStrategy?: 'protectLocal' | 'preferBackend'
    projectName?: string
  } = {},
  isCurrent = beginWorkspaceOperation('workspace')
) => {
  try {
    if (!isCurrent()) return
    homeWorkspaceStatus.value = 'loading'
    homeWorkspaceError.value = ''
    const storage = workspaceStorage.capture(isCurrent)
    const fetchImpl = workspaceFetch(isCurrent)
    const projectName = options.projectName || currentBackendProjectName()
    const [templateResult, caseResult] = await Promise.all([
      templateConfigModule.loadBackend({ fetch: fetchImpl, projectName, isCurrent }, { throwOnError: true }),
      loadSavedCasesWithBackend(storage, projectName, {
        fetchImpl,
        conflictStrategy: options.conflictStrategy,
        throwOnError: true
      })
    ])
    if (!isCurrent()) return
    savedCases.value = caseResult.cases
    syncSelectedCaseTypes()
    workspaceSyncConflict.value =
      options.conflictStrategy === 'preferBackend'
        ? undefined
        : buildWorkspaceSyncConflictNotice(projectName, false, caseResult.source === 'conflict')
    homeWorkspaceStatus.value = homeWorkItems.value.length || homeRuns.value.length ? 'ready' : 'empty'
    return { templateResult, caseResult }
  } catch (error) {
    if (!isCurrent()) return
    const failure = homeWorkspaceFailure(error)
    homeWorkspaceStatus.value = failure.status
    homeWorkspaceError.value = failure.message
  }
}

const retryHomeWorkspace = () => {
  void loadBackendBackedWorkspace()
}

const homeWorkspaceFailure = (error: unknown): { status: HomeWorkspaceStatus; message: string } => {
  if (error instanceof PlatformBackendRequestError) {
    if (error.status === 403) return { status: 'forbidden', message: '当前账号没有权限查看工作台数据。' }
    if ([502, 503, 504].includes(error.status)) return { status: 'unavailable', message: '工作台服务暂时不可用，请稍后重试。' }
    return { status: 'error', message: '工作台加载失败，请重试。' }
  }
  if (error instanceof PlatformBackendTransportError || error instanceof TypeError) return { status: 'unavailable', message: '工作台服务暂时不可用，请稍后重试。' }
  return { status: 'error', message: '工作台加载失败，请重试。' }
}

const buildWorkspaceSyncConflictNotice = (
  projectName: string,
  template: boolean,
  cases: boolean
): WorkspaceSyncConflictNotice | undefined =>
  template || cases ? { projectName, template, cases } : undefined

const dismissWorkspaceSyncConflict = () => {
  workspaceSyncConflict.value = undefined
}

const pushLocalWorkspaceToBackend = async () => {
  const isCurrent = beginWorkspaceOperation('workspace')
  if (!isCurrent()) return
  const storage = workspaceStorage.capture(isCurrent)
  const fetchImpl = workspaceFetch(isCurrent)
  const notice = workspaceSyncConflict.value
  if (!notice) return

  workspaceSyncLoading.value = true
  try {
    let synced = true
    if (notice.template) {
      const result = await templateConfigModule.reconcile('push-local')
      if (!result) synced = false
    }
    if (notice.cases) {
      const result = await syncCaseOrderingWithBackend(storage, notice.projectName, { fetchImpl })
      if (!isCurrent()) return
      synced = synced && result.source === 'backend'
    }

    if (synced) {
      workspaceSyncConflict.value = undefined
      ElMessage.success('已将本地版本同步到后端')
    } else {
      ElMessage.warning('后端暂不可用，已继续保留本地版本')
    }
  } finally {
    if (isCurrent()) workspaceSyncLoading.value = false
  }
}

const reloadBackendWorkspace = async () => {
  const isCurrent = beginWorkspaceOperation('workspace')
  if (!isCurrent()) return
  const notice = workspaceSyncConflict.value
  if (!notice) return

  const confirmed = await confirmDangerAction(
    '使用后端数据',
    `确认用后端数据覆盖 ${notice.projectName} 当前较新的本地数据吗？`,
    '使用后端'
  )
  if (!confirmed || !isCurrent()) return

  workspaceSyncLoading.value = true
  try {
    await loadBackendBackedWorkspace({
      conflictStrategy: 'preferBackend',
      projectName: notice.projectName
    }, isCurrent)
    if (isCurrent()) ElMessage.success('已加载后端版本')
  } finally {
    if (isCurrent()) workspaceSyncLoading.value = false
  }
}

const openAutomationCurlPreview = (testCase: ApiAutomationCaseDraft) => {
  const environment = resolveRunnableEnvironment()
  if (!environment) return
  executableRequest.value = buildAutomationExecutableRequest(testCase, environment)
  curlText.value = buildAutomationCurlCommand(testCase, environment)
  curlVisible.value = true
}

const openAutomationLog = (testCase: ApiAutomationCaseDraft) => {
  const result = automationResultMap.value.get(testCase.id)
  if (!result) {
    ElMessage.info('该用例还没有执行日志')
    return
  }
  selectedAutomationResult.value = result
  automationLogVisible.value = true
}

const openWebAutomationLog = (testCase: SavedWebAutomationRunCase) => {
  const result = webAutomationResultMap.value.get(testCase.id)
  if (!result) {
    ElMessage.info('该 Web 用例还没有执行日志')
    return
  }
  selectedAutomationResult.value = webRunResultToAutomationLog(result)
  automationLogVisible.value = true
}

const openCurlPreview = (testCase: SavedInterfaceCase) => {
  const environment = resolveRunnableEnvironment()
  if (!environment) return
  executableRequest.value = buildExecutableRequest(testCase, environment)
  curlText.value = buildCurlCommand(executableRequest.value)
  curlVisible.value = true
}

const resetAutomationFilters = () => {
  automationFilter.project = 'ALL'
  automationFilter.module = 'ALL'
  automationFilter.method = 'ALL'
  automationFilter.keyword = ''
}

watch(generationKind, () => {
  generationTemplateId.value = generationTemplateOptions.value[0]?.value || 'builtin-interface'
})

const webAutomationResultStatus = (testCase: SavedWebAutomationRunCase): WebAutomationRunStatus | 'pending' =>
  webAutomationResultStatusById.value[testCase.id] || 'pending'

const copyCurl = async () => {
  if (!curlText.value) return
  await navigator.clipboard.writeText(curlText.value)
  ElMessage.success('已复制 cURL')
}

const resolveRunnableEnvironment = (): ExecutionEnvironment | undefined => {
  const validation = validateExecutionEnvironment(activeApiExecutionEnvironment.value)
  if (!validation.valid) {
    ElMessage.warning(validation.message)
    environmentVisible.value = true
    openEnvironmentEditor(activeApiExecutionEnvironment.value?.id, 'api')
    return undefined
  }
  return runnableApiExecutionEnvironment.value
}

const resolveWebRuntimeEnvironment = (): BuiltWebExecutionRuntime | undefined => {
  const validation = validateWebExecutionEnvironment(activeWebExecutionEnvironment.value)
  if (!validation.valid) {
    ElMessage.warning(validation.message)
    environmentVisible.value = true
    openEnvironmentEditor(activeWebExecutionEnvironment.value?.id, 'web')
    return undefined
  }
  return runnableWebRuntime.value
}

const webLogoutActionOptions: Array<{ label: string; value: WebAutomationAction }> = [
  { label: '点击元素', value: 'click' },
  { label: '打开地址', value: 'goto' },
  { label: '等待元素', value: 'waitForVisible' },
  { label: '等待地址', value: 'waitForUrl' },
  { label: '断言元素', value: 'assertVisible' },
  { label: '断言地址', value: 'assertUrl' }
]

const addWebLogoutStep = () => {
  if (!editingEnvironment.web) editingEnvironment.web = defaultWebExecutionRuntimeConfig()
  const index = editingEnvironment.web.logout.steps.length
  editingEnvironment.web.logout.steps.push({
    id: `logout-${Date.now()}-${index + 1}`,
    name: index === 0 ? '退出登录' : `退出登录步骤 ${index + 1}`,
    action: 'click',
    targetType: 'locator',
    target: ''
  })
}

const moveWebLogoutStep = (index: number, offset: -1 | 1) => {
  const steps = editingEnvironment.web?.logout.steps
  if (!steps) return
  const targetIndex = index + offset
  if (targetIndex < 0 || targetIndex >= steps.length) return
  const [step] = steps.splice(index, 1)
  steps.splice(targetIndex, 0, step)
}

const removeWebLogoutStep = (index: number) => {
  editingEnvironment.web?.logout.steps.splice(index, 1)
}

const updateWebLogoutStepAction = (step: WebAutomationStep, action: WebAutomationAction) => {
  step.action = action
  step.targetType = action === 'goto' || action === 'waitForUrl' || action === 'assertUrl'
    ? 'url'
    : 'locator'
}

const webLogoutTargetPlaceholder = (step: WebAutomationStep) =>
  step.targetType === 'url' ? '/#/login' : 'testid=logout 或 role=button[name="退出登录"]'

const openEnvironmentEditor = (
  environmentId?: string,
  kind: 'api' | 'web' = environmentEditorKind.value
) => {
  openEnvironmentEditorCore(environmentId, kind)
  curlImportText.value = ''
  webEnvironmentVerification.value = undefined
  environmentVisible.value = true
}

/** 执行准备页 → 环境配置独立页：带上执行类别，并打开同一环境编辑器；返回时回到对应执行准备。 */
const openEnvironmentManagement = (kind: 'api' | 'web') => {
  environmentEditorTarget.value = kind
  navigateToNav('environments')
  openEnvironmentEditor(undefined, kind)
}

/** 环境页内切换 API / Web 类别:只切当前管理类别,不改另一个类别的数据;未保存先确认。 */
const switchEnvironmentManagementKind = (kind: 'api' | 'web') => {
  runWithEnvironmentDraftGuard('切换环境类别会丢弃当前未保存的环境修改。', () => {
    environmentEditorTarget.value = kind
    openEnvironmentEditor(undefined, kind)
  })
}

const selectEnvironmentFromPage = (environmentId: string) => {
  if (environmentId === editingEnvironment.id) return
  runWithEnvironmentDraftGuard('切换到目录里的其他环境会丢弃当前未保存的环境修改。', () => {
    openEnvironmentEditor(environmentId, environmentEditorKind.value)
  })
}

/** 返回执行准备:保留筛选、选中与运行参数上下文;未保存先确认,取消则连页面状态都不动。 */
const returnToPreparationFromEnvironments = (kind: 'api' | 'web') => {
  runWithEnvironmentDraftGuard('返回执行准备会丢弃当前未保存的环境修改。', () => {
    environmentVisible.value = false
    automationKind.value = kind
    navigateToNav('automation')
  })
}

const addExecutionEnvironment = (kind: 'api' | 'web' = environmentEditorKind.value) => {
  const environment = addEnvironment(kind)
  openEnvironmentEditor(environment.id, kind)
}

const deleteExecutionEnvironment = async (environmentId: string, kind: 'api' | 'web' = environmentEditorKind.value) => {
  const deleted = await deleteEnvironment(environmentId, kind)
  if (deleted) openEnvironmentEditor(undefined, kind)
}

const saveEditingEnvironment = (kind: 'api' | 'web' = environmentEditorKind.value) => {
  if (saveEnvironment(kind)) environmentVisible.value = false
}

const startWebLoginDiscovery = async () => {
  const webRuntime = editingEnvironment.web
  if (!webRuntime) return

  let discoveryUrl: string
  try {
    discoveryUrl = resolveWebLoginDiscoveryUrl(
      editingEnvironment.baseUrl,
      webRuntime.login.loginUrl
    )
  } catch (error) {
    ElMessage.warning(error instanceof Error ? error.message : '登录页地址无效')
    return
  }

  webLoginDiscoveryAbortController?.abort()
  webLoginDiscoveryAbortController = new AbortController()
  webLoginDiscoverySnapshot.value = undefined
  resetWebLoginDiscoverySelection()
  webLoginDiscoveryDialogVisible.value = true
  webLoginDiscoveryLoading.value = true

  try {
    const created = await createWebLoginDiscovery({
      url: discoveryUrl,
      browser: webRuntime.browser === 'follow-case' ? 'chromium' : webRuntime.browser,
      viewport: webRuntime.viewport,
      headless: webRuntime.headless,
      timeoutMs: webRuntime.navigationTimeoutMs,
      waitUntil: webRuntime.waitUntil
    })
    webLoginDiscoverySnapshot.value = created

    const completed = created.status === 'completed' || created.status === 'failed'
      ? created
      : await waitForWebLoginDiscovery(created.discoveryId, {
          signal: webLoginDiscoveryAbortController.signal,
          onSnapshot: (snapshot) => {
            webLoginDiscoverySnapshot.value = snapshot
          }
        })
    webLoginDiscoverySnapshot.value = completed

    if (completed.status === 'failed') {
      ElMessage.error(completed.diagnostics?.[0] || '登录页识别失败')
      return
    }
    initializeWebLoginDiscoverySelection(completed)
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') return
    ElMessage.error(formatWebLoginDiscoveryError(error))
  } finally {
    webLoginDiscoveryLoading.value = false
  }
}

const confirmAndApplyWebLoginDiscovery = async () => {
  const snapshot = webLoginDiscoverySnapshot.value
  if (!snapshot || snapshot.status !== 'completed' || !editingEnvironment.web) return
  if (!webLoginDiscoverySelection.usernameCandidateId
    || !webLoginDiscoverySelection.passwordCandidateId
    || !webLoginDiscoverySelection.submitCandidateId) {
    ElMessage.warning('请确认账号、密码和登录按钮候选项')
    return
  }

  webLoginDiscoveryConfirming.value = true
  try {
    const confirmedSnapshot = await confirmWebLoginDiscovery(
      snapshot.discoveryId,
      { ...webLoginDiscoverySelection }
    )
    if (!confirmedSnapshot.confirmed) {
      throw new Error('Runner 未返回已确认的登录定位器')
    }

    webLoginDiscoverySnapshot.value = confirmedSnapshot
    editingEnvironment.web.login.enabled = true
    editingEnvironment.web.login.usernameLocator = confirmedSnapshot.confirmed.usernameLocator
    editingEnvironment.web.login.passwordLocator = confirmedSnapshot.confirmed.passwordLocator
    editingEnvironment.web.login.submitLocator = confirmedSnapshot.confirmed.submitLocator
    webEnvironmentVerification.value = undefined
    webLoginDiscoveryDialogVisible.value = false
    ElMessage.success('登录定位器已回填，请补充账号密码后验证登录')
  } catch (error) {
    ElMessage.error(formatWebLoginDiscoveryError(error))
  } finally {
    webLoginDiscoveryConfirming.value = false
  }
}

const selectedWebLoginDiscoveryCandidate = (
  role: WebLoginDiscoveryCandidateRole
): WebLoginDiscoveryCandidate | undefined => {
  const selectionKey = `${role}CandidateId` as keyof typeof webLoginDiscoverySelection
  return webLoginDiscoverySnapshot.value?.candidates?.[role]?.find(
    (candidate) => candidate.id === webLoginDiscoverySelection[selectionKey]
  )
}

const closeWebLoginDiscovery = () => {
  if (webLoginDiscoveryLoading.value) webLoginDiscoveryAbortController?.abort()
  webLoginDiscoveryAbortController = undefined
  webLoginDiscoveryLoading.value = false
}

const initializeWebLoginDiscoverySelection = (snapshot: WebLoginDiscoverySnapshot) => {
  webLoginDiscoverySelection.usernameCandidateId = snapshot.recommended?.username?.id
    || snapshot.candidates?.username?.[0]?.id
    || ''
  webLoginDiscoverySelection.passwordCandidateId = snapshot.recommended?.password?.id
    || snapshot.candidates?.password?.[0]?.id
    || ''
  webLoginDiscoverySelection.submitCandidateId = snapshot.recommended?.submit?.id
    || snapshot.candidates?.submit?.[0]?.id
    || ''
}

const resetWebLoginDiscoverySelection = () => {
  webLoginDiscoverySelection.usernameCandidateId = ''
  webLoginDiscoverySelection.passwordCandidateId = ''
  webLoginDiscoverySelection.submitCandidateId = ''
}

const resolveWebLoginDiscoveryUrl = (baseUrl: string, loginUrl: string): string => {
  const normalizedBaseUrl = baseUrl.trim()
  const normalizedLoginUrl = loginUrl.trim()
  if (!normalizedBaseUrl && !normalizedLoginUrl) {
    throw new Error('请先填写 Web 访问入口或登录页地址')
  }

  let url: URL
  try {
    if (/^https?:\/\//i.test(normalizedLoginUrl)) {
      url = new URL(normalizedLoginUrl)
    } else {
      if (!normalizedBaseUrl) throw new Error('Relative login URL requires a base URL')
      url = new URL(resolveHashAwareWebRuntimeUrl(normalizedBaseUrl, normalizedLoginUrl || normalizedBaseUrl))
    }
  } catch {
    throw new Error('Web 访问入口或登录页地址格式不正确')
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error('登录页地址仅支持 HTTP 或 HTTPS')
  }
  if (url.username || url.password) {
    throw new Error('登录页地址不能包含账号或密码')
  }
  return url.toString()
}

const resolveHashAwareWebRuntimeUrl = (baseUrl: string, target: string): string => {
  if (/^https?:\/\//i.test(target)) return target
  const url = new URL(baseUrl)
  if (target.startsWith('#/')) {
    url.hash = target.slice(1)
    return url.toString()
  }
  if (target.startsWith('/') && !target.startsWith('/#/') && url.hash.startsWith('#/')) {
    url.hash = target
    return url.toString()
  }
  const baseWithTrailingSlash = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`
  return new URL(target, baseWithTrailingSlash).toString()
}

const formatWebLoginDiscoveryError = (error: unknown): string => {
  if (error instanceof WebLoginDiscoveryRequestError) return error.toDisplayMessage()
  return error instanceof Error ? error.message : '登录页识别失败'
}

const verifyEditingWebEnvironmentLogin = async () => {
  if (!editingEnvironment.web) return
  const validation = validateWebExecutionEnvironment(editingEnvironment)
  if (!validation.valid) {
    webEnvironmentVerification.value = {
      status: 'error',
      message: validation.message || 'Web 自动登录配置不完整'
    }
    ElMessage.warning(validation.message)
    return
  }

  const runtime = buildWebExecutionRuntime(editingEnvironment)
  if (!runtime.login.enabled) {
    webEnvironmentVerification.value = {
      status: 'error',
      message: '请先开启自动登录后再验证'
    }
    ElMessage.warning('请先开启自动登录后再验证')
    return
  }

  webEnvironmentVerifying.value = true
  webEnvironmentVerification.value = undefined
  webLoginVerificationVisible.value = true
  webLoginVerificationRunning.value = true
  webLoginVerificationSnapshot.value = undefined
  webLoginSessionId.value = ''
  webLoginVerificationAbortController?.abort()
  webLoginVerificationAbortController = new AbortController()
  try {
    const verificationRuntime = {
      ...runtime,
      screenshotStrategy: 'always' as const,
      persistSession: true
    }
    const created = await createWebAutomationRun([buildWebLoginProbeCase(runtime)], {
      mode: 'sequential',
      runtime: verificationRuntime
    })
    webLoginVerificationRunId = created.runId
    webLoginVerificationSnapshot.value = created
    const snapshot = isTerminalWebAutomationRun(created.status)
      ? created
      : await waitForWebAutomationRun(created.runId, {
          intervalMs: 350,
          signal: webLoginVerificationAbortController.signal,
          onSnapshot: (next) => { webLoginVerificationSnapshot.value = next }
        })
    webLoginVerificationSnapshot.value = snapshot
    webLoginSessionId.value = snapshot.sessionId || ''
    if (webLoginSessionId.value) {
      // 会话随环境持久化：刷新/重进页面后编译取证仍可复用（runner 内存会话，约 10 分钟有效）。
      if (!editingEnvironment.web) editingEnvironment.web = defaultWebExecutionRuntimeConfig()
      editingEnvironment.web.sessionId = webLoginSessionId.value
    }
    const runResult = snapshotToReport(snapshot).results[0]
    if (runResult?.status === 'passed') {
      const migratedSuccessTarget = migrateUnstableWebLoginSuccessTarget(runResult)
      webEnvironmentVerification.value = {
        status: 'success',
        message: '自动登录验证通过',
        detail: migratedSuccessTarget
          ? `已将不稳定的元素判定替换为 URL ${migratedSuccessTarget}，保存环境后生效`
          : runtime.login.successType === 'url'
          ? `成功跳转到 ${runtime.login.successTarget}`
          : `已识别登录成功元素 ${runtime.login.successTarget}`
      }
      ElMessage.success('自动登录验证通过')
      return
    }

    const failedStep = snapshot.stepLogs?.find((step) => step.status === 'failed') || runResult?.stepLogs?.find((step) => step.status === 'failed')
    webEnvironmentVerification.value = {
      status: 'error',
      message: failedStep ? `自动登录验证失败：${failedStep.name}` : '自动登录验证失败',
      detail: failedStep?.errorMessage || runResult?.errorMessage || snapshot.diagnostics?.[0] || 'Runner 未返回更多错误信息'
    }
    ElMessage.error(webEnvironmentVerification.value.message)
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') return
    const message = error instanceof WebAutomationRunRequestError
      ? error.toFallbackMessage()
      : error instanceof Error ? error.message : '自动登录验证失败'
    webEnvironmentVerification.value = {
      status: 'error',
      message
    }
    ElMessage.error(message)
  } finally {
    webLoginVerificationRunning.value = false
    webEnvironmentVerifying.value = false
  }
}

const clearWebLoginSession = () => {
  if (editingEnvironment.web) editingEnvironment.web.sessionId = undefined
  webLoginSessionId.value = ''
  ElMessage.success('已清除保存的登录会话')
}

const closeWebLoginVerification = async () => {
  webLoginVerificationAbortController?.abort()
  const runId = webLoginVerificationRunId
  webLoginVerificationRunId = ''
  if (runId && webLoginVerificationSnapshot.value && !isTerminalWebAutomationRun(webLoginVerificationSnapshot.value.status)) {
    try { await stopWebAutomationRun(runId) } catch { /* the modal can close even if the runner is already gone */ }
  }
  webLoginVerificationRunning.value = false
}

const migrateUnstableWebLoginSuccessTarget = (runResult: WebAutomationRunReport['results'][number]): string | undefined => {
  if (!editingEnvironment.web || editingEnvironment.web.login.successType !== 'visible') return undefined
  const target = editingEnvironment.web.login.successTarget.trim()
  if (!isUnstableCssModuleLocator(target)) return undefined
  const finalPageUrl = [...(runResult.stepLogs || [])]
    .reverse()
    .find((step) => step.pageUrl)?.pageUrl
  const stableRoute = extractStableWebLoginRoute(finalPageUrl)
  if (!stableRoute) return undefined
  editingEnvironment.web.login.successType = 'url'
  editingEnvironment.web.login.successTarget = stableRoute
  return stableRoute
}

const isUnstableCssModuleLocator = (target: string): boolean =>
  /\._[A-Za-z][\w-]*_[A-Za-z0-9]{4,}(?:_\d+)?/.test(target)

const extractStableWebLoginRoute = (pageUrl?: string): string | undefined => {
  if (!pageUrl) return undefined
  try {
    const url = new URL(pageUrl)
    const route = `${url.pathname}${url.search}${url.hash}`
    return route && route !== '/' ? route : undefined
  } catch {
    return undefined
  }
}


const addEnvironmentEntry = (type: EnvironmentEntryType = 'header') => {
  editingEnvironment.entries.push(newEnvironmentEntry(type))
}

const deleteEnvironmentEntry = (entryId: string) => {
  editingEnvironment.entries = editingEnvironment.entries.filter((entry) => entry.id !== entryId)
}

const importCurlToEnvironment = () => {
  const isWebEnvironment = environmentEditorKind.value === 'web'
  const parsed = parseCurlEnvironmentImport(curlImportText.value, {
    target: isWebEnvironment ? 'web' : 'api'
  })
  const entries = parsed.entries
  if (entries.length === 0) {
    ElMessage.warning('未识别到 Header 或 Cookie')
    return
  }
  if (!isWebEnvironment && parsed.baseUrl) {
    editingEnvironment.baseUrl = parsed.baseUrl
  }
  if (isWebEnvironment && parsed.baseUrl && !editingEnvironment.baseUrl.trim()) {
    editingEnvironment.baseUrl = parsed.baseUrl
  }
  if (isWebEnvironment && editingEnvironment.web && parsed.requestOrigin) {
    const currentOrigins = editingEnvironment.web.cookieOrigins || []
    editingEnvironment.web.cookieOrigins = Array.from(new Set([...currentOrigins, parsed.requestOrigin]))
  }
  editingEnvironment.auth = parsed.auth
  editingEnvironment.entries.push(...entries)
  curlImportText.value = ''
  if (isWebEnvironment) {
    const baseUrlText = parsed.baseUrl
      ? `应用 Base URL ${editingEnvironment.baseUrl.trim() === parsed.baseUrl ? '已使用' : '已保留'} ${parsed.baseUrl}`
      : '未从 curl 中识别到 Web 入口，已保留当前应用 Base URL'
    const requestOriginText = parsed.requestOrigin ? `；接口域名 ${parsed.requestOrigin} 未作为应用入口` : ''
    ElMessage.success(`已导入 ${entries.length} 个登录态参数，${baseUrlText}${requestOriginText}`)
    return
  }
  ElMessage.success(`已解析 ${entries.length} 个参数，并更新环境基础配置`)
}

function cloneEnvironment(environment: SavedExecutionEnvironment): SavedExecutionEnvironment {
  return JSON.parse(JSON.stringify(environment)) as SavedExecutionEnvironment
}

function buildWebLoginProbeCase(runtime: BuiltWebExecutionRuntime): WebAutomationRunCase {
  return {
    id: 'WEB-ENV-LOGIN-CHECK',
    sourceCaseId: 'WEB-ENV-LOGIN-CHECK',
    project: 'Web 环境',
    module: '环境验证',
    pageName: '自动登录',
    name: '验证自动登录配置',
    startUrl: '',
    browser: runtime.browser === 'follow-case' ? 'chromium' : runtime.browser,
    viewport: runtime.viewport,
    authenticationMode: 'environment',
    steps: [],
    runOrder: 1,
    mockOutcome: 'passed'
  }
}

const collectWebAutomationProjects = (cases: SavedWebAutomationRunCase[]) => {
  const counts = new Map<string, number>()
  for (const testCase of cases) {
    counts.set(testCase.project, (counts.get(testCase.project) || 0) + 1)
  }
  return Array.from(counts.entries()).map(([value, count]) => ({ value, count }))
}

function downloadTextFile(filename: string, content: string, type: string) {
  const blob = new Blob([content], { type })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
}

async function executeBrowserRequest(request: ExecutableRequest, isCurrent: () => boolean = () => true) {
  if (!isCurrent()) throw new Error('本次 API 执行上下文已失效')
  const proxiedResponse = await executeViaLocalProxy(request)
  if (!isCurrent()) throw new Error('本次 API 执行上下文已失效')
  if (proxiedResponse) return proxiedResponse

  let response: Response
  try {
    response = await fetch(request.url, {
      method: request.method,
      headers: browserSafeHeaders(request.headers),
      body: request.body === undefined ? undefined : JSON.stringify(request.body)
    })
  } catch (error) {
    const message = error instanceof Error ? error.message : '网络请求失败'
    throw new Error(`${message}。请检查 Base URL、请求头/Cookie、登录态，以及目标接口是否允许当前页面跨域访问。`)
  }
  const contentType = response.headers.get('content-type') || ''
  const body = contentType.includes('application/json')
    ? await response.json().catch(() => undefined)
    : await response.text().catch(() => undefined)

  return {
    status: response.status,
    body
  }
}

async function executeViaLocalProxy(request: ExecutableRequest) {
  try {
    const response = await fetch('/__api-test-platform/execute', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(buildProxyExecutionPayload(request))
    })

    if (response.status === 404) return undefined

    const payload = await response.json().catch(() => undefined)
    if (!response.ok) {
      throw new Error(payload?.error || `本地代理执行失败：HTTP ${response.status}`)
    }
    return {
      status: payload.status,
      body: payload.body
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : '本地代理执行失败'
    throw new Error(`${message}。如果目标接口依赖登录 Cookie，请确认请求头/Cookie 已粘贴，并重启本地开发服务以加载代理。`)
  }
}

function emptyEditRow(): InterfaceCaseRow {
  return {
    caseId: '',
    project: '',
    module: '',
    interfaceName: '',
    method: '',
    path: '',
    title: '',
    caseType: '',
    precondition: '',
    headers: '',
    pathParams: '',
    queryParams: '',
    body: '',
    steps: '',
    expectedResult: '',
    expectedResponseBody: '',
    actualResponseBody: '',
    assertions: '',
    priority: '',
    executionResult: '',
    executor: '',
    executedAt: '',
    defectId: '',
    tags: '',
    sourceFile: '',
    remark: ''
  }
}

function emptyWebEditRow(): WebAutomationCaseRow {
  return {
    caseId: '',
    project: '',
    module: '',
    pageName: '',
    title: '',
    naturalSteps: '',
    naturalExpectedResult: '',
    environmentId: '',
    caseType: '',
    precondition: '',
    startUrl: '',
    browser: 'chromium',
    viewport: '',
    testData: '',
    steps: '',
    locators: '',
    assertions: '',
    waitStrategy: '',
    screenshotStrategy: '',
    retryCount: 0,
    priority: '',
    executionResult: '',
    executor: '',
    executedAt: '',
    defectId: '',
    tags: '',
    sourceFile: '',
    remark: ''
  }
}

function normalizeNavPath(pathname: string): string {
  if (!pathname || pathname === '/') return '/'
  return pathname.endsWith('/') ? pathname.slice(0, -1) || '/' : pathname
}

function handlePopState() {
  syncNavWithLocation('skip')
  if (activeNav.value === 'automation' && automationKind.value === 'web') {
    void recoverWebRunSession('replace')
  } else {
    webAutomationWorkspaceMode.value = 'workbench'
  }
}

type HomeWorkspaceStatus = 'ready' | 'loading' | 'empty' | 'unavailable' | 'forbidden' | 'error'
const homeWorkspaceStatus = ref<HomeWorkspaceStatus>('loading')
const homeWorkspaceError = ref('')
const overviewStatus = ref<OverviewStatus>('unavailable')
const overviewError = ref('')
const overviewAvailabilityChecked = ref(false)
let passwordOperation = 0

// Project discovery proves access only; it is not a team/quality aggregate.
const checkOverviewAvailability = async () => {
  if (authStatus.value !== 'authenticated' || activeNav.value !== 'overview') return
  const isCurrentOperation = beginWorkspaceOperation('overview')
  const current = () => isCurrentOperation() && activeNav.value === 'overview'
  overviewStatus.value = 'loading'
  overviewError.value = ''
  overviewAvailabilityChecked.value = false
  try {
    await listBackendProjects({ fetchImpl: workspaceFetch(current), throwOnError: true })
    if (!current()) return
    overviewAvailabilityChecked.value = true
    overviewStatus.value = 'unavailable'
  } catch (error) {
    if (!current()) return
    overviewStatus.value = error instanceof PlatformBackendRequestError && error.status === 403 ? 'forbidden' : 'error'
    overviewError.value = error instanceof PlatformBackendRequestError && [502, 503, 504].includes(error.status)
      ? '项目服务暂时不可用；尚未接入团队与质量统计，请稍后重新检查。'
      : '项目访问检查失败；尚未确认概览数据可用，请重试。'
  }
}

watch([activeNav, authStatus], () => {
  if (activeNav.value === 'overview' && authStatus.value === 'authenticated') void checkOverviewAvailability()
  else {
    // Keep sequence monotonic: leaving and returning must never reuse an old authority.
    if (workspaceRuntime.hasOperation('overview')) beginWorkspaceOperation('overview')
    overviewStatus.value = 'unavailable'
    overviewError.value = ''
    overviewAvailabilityChecked.value = false
  }
}, { flush: 'post' })

function resetWorkspaceState(): void {
  // Only clear in-memory state. Never delete another account's persistent keys.
  defects.resetDefectRecovery()
  templateConfigModule.invalidateWorkspace()
  applyJiraConfig({ ...defaultJiraConfig(), maskedToken: undefined, updatedAt: undefined })
  jiraConfigReadState.value = 'idle'
  jiraConfigLoading.value = false
  jiraConfigTesting.value = false
  finalizedWebRunId = ''
  webExecutionController.reset()
  apiAutomationRunning.value = false
  apiExecutionController.reset()
  selectedAutomationResult.value = undefined
  selectedCase.value = undefined
  selectedLibraryId.value = undefined
  for (const row of [editRow, webEditRow]) {
    for (const key of Object.keys(row)) delete (row as Record<string, unknown>)[key]
  }
  Object.assign(editRow, emptyEditRow())
  Object.assign(webEditRow, emptyWebEditRow())
  Object.assign(casesModule.bulkEditForm, { matchValue: '', targetValue: '' })
  Object.assign(casesModule.fieldAssignmentForm, { path: '', value: '' })
  defects.jiraCsvTemplate.value = loadJiraCsvTemplate(localStorage)
  // 换账号/退出:草稿回到该类别自己的默认环境(不读上一个 owner 的内存库);真实库载入后再由 syncEnvironmentEditorToLibrary 对齐目录所选。
  const resetKind = environmentEditorTarget.value
  bindEditingEnvironment((resetKind === 'web' ? defaultWebExecutionEnvironmentLibrary() : defaultExecutionEnvironmentLibrary()).environments[0], resetKind)
  curlImportText.value = ''
  webEnvironmentVerification.value = undefined
  webEnvironmentVerifying.value = false
  webLoginVerificationSnapshot.value = undefined
  webLoginVerificationVisible.value = false
  webLoginVerificationRunning.value = false
  webLoginSessionId.value = ''
  webLoginVerificationRunId = ''
  resetWebLoginDiscoverySelection()
  globalSearch.value = ''
  Object.assign(automationFilter, { project: 'ALL', module: 'ALL', method: 'ALL', source: 'ALL', keyword: '' })
  Object.assign(libraryFilter, { project: 'ALL', module: 'ALL', keyword: '' })
  Object.assign(defects.defectFilter, { project: 'ALL', status: 'ALL', issueType: 'ALL', priority: 'ALL', assignee: 'ALL', keyword: '' })
  for (const key of Object.keys(defects.defectEditForm)) (defects.defectEditForm as Record<string, string>)[key] = ''
  defects.recentlyTransferredDefectIds.value = []
  defects.jiraTemplateVisible.value = false
  reports.reportEnvironmentFilter.value = 'ALL'
  executableRequest.value = undefined
  curlText.value = ''
  importedProjects.value = []
  activeProjectId.value = ''
  requirementAnalysisProjectName.value = '默认项目'
  workspaceSyncConflict.value = undefined
  workspaceSyncLoading.value = false
  homeWorkspaceStatus.value = 'loading'
  homeWorkspaceError.value = ''
  automationSelection.value = []
  overviewStatus.value = 'unavailable'
  overviewError.value = ''
  overviewAvailabilityChecked.value = false
  webAutomationSelectedIds.value = []
  casesModule.librarySelection.value = []
  casesModule.bulkEditVisible.value = false
  casesModule.fieldAssignmentVisible.value = false
  refreshSavedCases()
  refreshAutomationCases()
  refreshAutomationReports()
  templateConfigModule.bindWorkspace()
  webRunSuites.value = []
  executionEnvironmentLibrary.value = defaultExecutionEnvironmentLibrary()
  webExecutionEnvironmentLibrary.value = defaultWebExecutionEnvironmentLibrary()
  defects.defectDrafts.value = []
  defects.jiraIssues.value = []
  defects.defectSelection.value = []
  defects.editingDefect.value = undefined
  defects.defectEditVisible.value = false
  reports.closeReportScreenshotPreview()
  reports.expandedReportHtmlCaseIds.value = []
  reports.reportKeyword.value = ''
  editVisible.value = false
  curlVisible.value = false
  helpVisible.value = false
  environmentVisible.value = false
  automationConfigVisible.value = false
  automationLogVisible.value = false
  webLoginDiscoverySnapshot.value = undefined
  webLoginDiscoveryDialogVisible.value = false
  webLoginDiscoveryLoading.value = false
  webLoginDiscoveryConfirming.value = false
}

const currentUserInitials = computed(() => {
  const name = currentUser.value?.displayName || currentUser.value?.username
  if (!name) return 'LY'
  return name.trim().charAt(0).toUpperCase()
})

// VIEWER 只读;EDITOR/ADMIN 可执行写操作(后端仍有角色强制,这里是前端体验门控)
const canEdit = computed(() => currentUser.value?.role !== 'VIEWER')
// 侧边栏按角色过滤:非 ADMIN 隐藏用户管理等 adminOnly 项
const sidebarGroups = computed(() => sidebarGroupsForRole(currentUser.value?.role))

const changePasswordDialogVisible = ref(false)
const changePasswordForm = reactive({
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
  error: '',
  submitting: false
})

function openChangePasswordDialog(): void {
  if (authDisposed || authStatus.value !== 'authenticated') return
  passwordOperation += 1
  changePasswordForm.submitting = false
  changePasswordForm.currentPassword = ''
  changePasswordForm.newPassword = ''
  changePasswordForm.confirmPassword = ''
  changePasswordForm.error = ''
  changePasswordDialogVisible.value = true
}

async function submitChangePassword(): Promise<void> {
  if (authDisposed || authStatus.value !== 'authenticated' || changePasswordForm.submitting) return
  changePasswordForm.error = ''
  if (!changePasswordForm.currentPassword || !changePasswordForm.newPassword) {
    changePasswordForm.error = '请输入当前密码和新密码'
    return
  }
  if (changePasswordForm.newPassword.length < 8) {
    changePasswordForm.error = '新密码长度至少 8 位'
    return
  }
  if (changePasswordForm.newPassword !== changePasswordForm.confirmPassword) {
    changePasswordForm.error = '两次输入的新密码不一致'
    return
  }
  changePasswordForm.submitting = true
  const epoch = getAuthSessionEpoch()
  const operation = ++passwordOperation
  const isCurrent = () => isCurrentAuth(epoch) && operation === passwordOperation
  try {
    const updated = await changePassword(changePasswordForm.currentPassword, changePasswordForm.newPassword)
    if (!isCurrent()) return
    currentUser.value = updated
    changePasswordDialogVisible.value = false
    ElMessage.success('密码已更新')
  } catch (error) {
    if (!isCurrent()) return
    changePasswordForm.error = error instanceof Error
      ? error.message.replace(/^Auth request failed: \d+\s*/, '')
      : '修改失败，请重试'
  } finally {
    if (isCurrent()) changePasswordForm.submitting = false
  }
}

function loadWorkspaceData(): void {
  // 旧设计草稿仅保留在浏览器存储中，不恢复任务、不轮询已下线的 AI API。
  syncNavWithLocation('replace')
  refreshSavedCases()
  refreshAutomationCases()
  defects.refreshDefectDrafts()
  defects.jiraCsvTemplate.value = loadJiraCsvTemplate(localStorage)
  webRunSuites.value = listWebRunSuites(localStorage)
  executionEnvironmentLibrary.value = loadExecutionEnvironmentLibrary(localStorage)
  webExecutionEnvironmentLibrary.value = loadExecutionEnvironmentLibrary(localStorage, WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY)
  // 冷启动/换账号后:环境编辑器绑定目录当前所选环境(而不是默认环境)。
  syncEnvironmentEditorToLibrary()
  refreshAutomationReports()
  void loadBackendBackedWorkspace()
  void loadJiraSettingsFromBackend()
  void loadWebRunCapabilities()
  void recoverWebRunSession('replace')
}

function stopBackgroundActivity(): void {
  webRunPollCoordinator.stop()
  webLoginDiscoveryAbortController?.abort()
}

onMounted(() => {
  setUnauthorizedHandler(handleSessionExpired)
  window.addEventListener('popstate', handlePopState)
  void bootstrapAuth()
})

onBeforeUnmount(() => {
  authDisposed = true
  authSession.dispose()
  window.removeEventListener('popstate', handlePopState)
  window.removeEventListener('beforeunload', handleJiraBeforeUnload)
  window.removeEventListener('beforeunload', handleDefectBeforeUnload)
  window.removeEventListener('beforeunload', handleEnvironmentBeforeUnload)
})
</script>

<template>
  <LoginScreen
    v-if="authStatus === 'unauthenticated'"
    @authenticated="onLoginSuccess"
  />
  <AppShell
    v-else-if="authStatus === 'authenticated'"
    :sidebar-collapsed="isNarrowViewport ? !narrowNavigationVisible : railCollapsed"
    :sidebar-visible="!isNarrowViewport"
    :inspector-visible="false"
  >
    <template #sidebar>
      <PrimarySidebar
        v-if="!isNarrowViewport"
        :groups="sidebarGroups"
        :active-item-id="activeSidebarItemId"
        :collapsed="railCollapsed"
        :badges="sidebarBadges"
        :settings-entry="!isSettingsCenterNav"
        @select="selectSidebarItem"
        @request-expand="expandStudioNavigation"
        @help="helpVisible = true"
      />
    </template>

    <template #topbar>
      <GlobalTopbar
        v-model:search-value="globalSearch"
        :sidebar-collapsed="isNarrowViewport ? !narrowNavigationVisible : railCollapsed"
        :project-label="studioProjectLabel"
        :section-label="studioSectionLabel"
        :sync-state="workspaceSyncState"
        :task-status-label="globalLongTaskLabel"
        :user-initials="currentUserInitials"
        :user-display-name="currentUser?.displayName || currentUser?.username || ''"
        :primary-areas="primaryAreas"
        :active-primary-id="activePrimaryId"
        @toggle-sidebar="toggleStudioNavigation"
        @open-task="openActiveLongTask"
        @select-primary="selectPrimaryArea"
        @open-assistant="openAssistantPage"
        @logout="handleLogout"
        @change-password="openChangePasswordDialog"
      />
    </template>


    <main
      class="workspace"
      :class="{
        'is-home': activeNav === 'home',
        'is-studio-case-design': activeNav === 'api' && generationKind === 'web_automation',
        'is-studio-interface-case': activeNav === 'api' && generationKind === 'interface',
        'is-web-automation': activeNav === 'automation' && automationKind === 'web',
        'is-environment-management': activeNav === 'environments',
        'is-workbench': isWorkbenchNav
      }"
    >
      <section v-if="templateConfigModule.recovery?.reason" data-template-recovery class="studio-sync-notice template-recovery-notice" data-message-level="warning" role="alert" aria-live="assertive">
        <div class="sync-conflict-copy"><strong>模板配置未确认</strong><span>{{ templateConfigModule.recovery.reason }} · {{ templateConfigModule.recovery.owner }} · {{ templateConfigModule.recovery.project }}</span></div>
        <div class="sync-conflict-actions">
          <div class="template-recovery-action-group" data-recovery-group="recover" role="group" aria-label="恢复与重试">
            <span class="template-recovery-action-group__label">恢复与重试</span>
            <button :disabled="templateConfigModule.recovery.phase !== 'idle' || !templateConfigModule.recovery.identityUsable" @click="templateConfigModule.reloadBaseline()">重新读取本地</button>
            <button :disabled="templateConfigModule.recovery.phase !== 'idle' || !templateConfigModule.recovery.identityUsable" @click="templateConfigModule.retryMirror()">重试本地镜像</button>
            <button :disabled="templateConfigModule.recovery.phase !== 'idle' || !templateConfigModule.recovery.identityUsable" @click="templateConfigModule.retrySave()">保留本地并重试保存</button>
          </div>
          <div class="template-recovery-action-group" data-recovery-group="decision" data-risk="destructive" role="group" aria-label="覆盖或丢弃本地修改">
            <span class="template-recovery-action-group__label">覆盖或丢弃本地修改</span>
            <button class="is-destructive" :disabled="templateConfigModule.recovery.phase !== 'idle' || !templateConfigModule.recovery.identityUsable" @click="templateConfigModule.reconcile('push-local')">本地覆盖后端</button>
            <button class="is-destructive" :disabled="templateConfigModule.recovery.phase !== 'idle' || !templateConfigModule.recovery.identityUsable" @click="templateConfigModule.reconcile('take-backend')">采用后端（丢弃本地）</button>
          </div>
        </div>
      </section>
      <DefectFailureBanner v-if="activeNav === 'automation'" />
      <div v-if="workspaceSyncConflict" class="studio-sync-notice" data-message-level="warning" role="status" aria-live="polite">
        <span class="sync-conflict-icon" aria-hidden="true">!</span>
        <div class="sync-conflict-copy">
          <strong>本地数据较新</strong>
          <span>{{ workspaceSyncConflictText }}</span>
          <span>推送本地会更新后端；使用后端会替换本地数据。</span>
        </div>
        <div class="sync-conflict-actions">
          <el-button size="small" data-sync-action="push-local" :loading="workspaceSyncLoading" @click="pushLocalWorkspaceToBackend">
            <Upload :size="14" />
            推送本地
          </el-button>
          <el-button size="small" plain data-sync-action="take-backend" data-risk="destructive" :loading="workspaceSyncLoading" @click="reloadBackendWorkspace">
            <Download :size="14" />
            使用后端
          </el-button>
          <el-button
            class="icon-button"
            text
            :disabled="workspaceSyncLoading"
            aria-label="关闭同步提示"
            title="关闭同步提示"
            @click="dismissWorkspaceSyncConflict"
          >
            <X :size="15" aria-hidden="true" />
          </el-button>
        </div>
      </div>
      <div v-if="!isOnline" class="studio-offline-notice" data-notice-level="info" data-message-level="info" role="status" aria-live="polite">
        <span class="studio-offline-notice__icon" aria-hidden="true">i</span>
        <strong>离线模式</strong>
        <span>当前离线；用例库本地数据保留，联网后可继续同步。</span>
      </div>
      <template v-if="isCaseWorkbenchNav">
        <!-- A3-B2：五段式用例设计流程已下线（旧 AI 接口一并拆除），用例库保留只读/维护能力。 -->
        <CasesView :key="activeNav" />
      </template>

      <template v-else-if="activeNav === 'home'">
        <HomeView
          ref="homeViewRef"
          :runs="homeRuns"
          :items="homeWorkItems"
          :projects="homeProjects"
          :status="homeWorkspaceStatus"
          :error-message="homeWorkspaceError"
          @navigate="handleHomeNavigate"
          @retry="retryHomeWorkspace"
        />
      </template>

      <template v-else-if="activeNav === 'assistant'">
        <AssistantChainView
          :user="currentUser"
        />
      </template>

      <template v-else-if="activeNav === 'overview'">
        <OverviewView
          :status="overviewStatus"
          :error-message="overviewError"
          :availability-checked="overviewAvailabilityChecked"
          @retry="checkOverviewAvailability"
        />
      </template>

      <template v-else-if="isSettingsCenterNav">
        <SettingsCenterView
          :active-nav="activeNav"
          :role="currentUser?.role"
          :can-edit="canEdit"
          :form="jiraConfigForm"
          :loading="jiraConfigLoading"
          :read-state="jiraConfigReadState"
          :testing="jiraConfigTesting"
          :project-jql-rows="jiraProjectJqlRows"
          :load="loadJiraSettingsFromBackend"
          :save="saveJiraSettings"
          :test="testJiraConnection"
          :add-jql-row="addJiraProjectJqlRow"
          :remove-jql-row="removeJiraProjectJqlRow"
          :sync-jql-form="syncJiraProjectJqlFormFromRows"
          :test-result="jiraConfigTestResult"
          :saved-snapshot="jiraConfigSavedSnapshot"
          :validation-error="jiraConfigValidationError"
          :current-username="currentUser?.username || ''"
          @open-section="(section: 'fields' | 'types') => navigateToNav(section === 'types' ? 'caseTypes' : 'settings')"
        />
      </template>
      <template v-else-if="isQualityWorkbenchNav">
        <QualityWorkbenchView
          :active-nav="activeNav"
        />
      </template>

      <template v-else-if="activeNav === 'automation'">
        <ExecutionWorkbenchView
          :active-nav="activeNav"
          :automation-kind="automationKind"
        >
        <template #api>
        <ApiExecutionPage :active-nav="activeNav" :mode="apiAutomationWorkspaceMode">
        <template #monitor>
        <ApiRunMonitorView
          v-if="automationKind === 'api' && apiRunMonitor"
          v-show="apiAutomationWorkspaceMode === 'monitor'"
          ref="apiRunMonitorRef"
          :monitor="apiRunMonitor"
          @return-preparation="returnApiRunPreparation"
          @open-report="openReportsModule"
        />
        </template>
        <template #preparation>
        <header v-if="automationKind === 'api'" v-show="apiAutomationWorkspaceMode === 'prepare'" class="topbar automation-topbar">
          <div class="automation-topbar__title">
            <h1>自动化</h1>
            <p class="automation-topbar__subtitle">
              准备 {{ filteredAutomationCases.length }} 条可用例；运行环境与参数在「运行配置」中维护。
            </p>
          </div>
          <div class="toolbar">
            <el-button :disabled="!canEdit" @click="openEnvironmentManagement('api')">
              <Settings :size="16" />
              环境配置
            </el-button>
            <el-button @click="automationConfigVisible = true">
              <Settings :size="16" />
              运行配置
            </el-button>
            <button v-if="apiRunMonitor" ref="apiMonitorEntry" type="button" class="api-preparation-monitor-entry" data-testid="api-monitor-entry" @click="enterApiRunMonitor">返回运行监控 ↗</button>
            <el-button v-if="automationKind === 'api'" type="primary" :loading="apiAutomationRunning || apiRunConfirming" :disabled="!canEdit || apiAutomationRunning || apiRunConfirming || filteredAutomationCases.length === 0" @click="executeAllAutomationCases">
              <Play :size="16" />
              {{ apiExecutionScopeLabel }}
            </el-button>
            <el-button @click="openReportsModule">
              <FileText :size="16" />
              查看报告
            </el-button>
            <el-button @click="exportAutomationJson">
              <Download :size="16" />
              导出用例
            </el-button>
          </div>
        </header>

        <section
          v-if="automationKind === 'api'"
          v-show="apiAutomationWorkspaceMode === 'prepare'"
          ref="apiPreparationElement"
          class="automation-workspace"
        >
          <div class="library-metrics automation-metrics">
            <div class="metric-card">
              <span>自动化用例数</span>
              <strong>{{ automationCases.length }}</strong>
            </div>
            <div class="metric-card">
              <span>项目数</span>
              <strong>{{ automationProjects.length }}</strong>
            </div>
            <div class="metric-card">
              <span>模块数</span>
              <strong>{{ automationModules.length }}</strong>
            </div>
            <div class="metric-card">
              <span>当前筛选</span>
              <strong>{{ filteredAutomationCases.length }}</strong>
            </div>
          </div>

            <div class="case-panel automation-panel">
              <div class="panel-head stacked automation-panel-head">
                <div class="automation-panel-title-row">
                  <strong>接口自动化用例</strong>
                  <span>当前 {{ filteredAutomationCases.length }} 条</span>
                </div>
                <div class="automation-control-row">
                  <div class="case-filters compact-toolbar automation-filter-row" data-kind="api">
                    <div class="filter-fields">
                      <el-select v-model="automationFilter.project" placeholder="项目" @change="switchAutomationProject">
                        <el-option label="全部项目" value="ALL" />
                        <el-option v-for="project in automationProjects" :key="project.value" :label="project.value" :value="project.value" />
                      </el-select>
                      <el-select v-model="automationFilter.module" placeholder="模块">
                        <el-option label="全部模块" value="ALL" />
                        <el-option v-for="module in automationModules" :key="module.value" :label="module.value" :value="module.value" />
                      </el-select>
                      <el-select v-model="automationFilter.method" placeholder="方法">
                        <el-option label="全部方法" value="ALL" />
                        <el-option v-for="method in automationMethods" :key="method" :label="method" :value="method" />
                      </el-select>
                      <el-input
                        class="web-automation-keyword"
                        v-model="automationFilter.keyword"
                        clearable
                        placeholder="搜索自动化用例 / 期望状态"
                      />
                    </div>
                  </div>
                  <div class="panel-action-bar automation-panel-actions">
                    <div class="panel-action-main">
                    <div
                      v-if="hasFilteredDesignOriginAutomationCases"
                      class="action-cluster action-cluster-accent"
                    >
                      <el-button
                        type="primary"
                        plain
                        :loading="automationKind === 'api' ? apiAutomationRunning : webAutomationRunning"
                        @click="executeAllAutomationCases"
                      >
                        <Play :size="16" />
                        执行设计稿来源
                      </el-button>
                      <el-button
                        type="warning"
                        plain
                        @click="transferFilteredDesignAutomationResultsToDefects"
                      >
                        <Bug :size="16" />
                        失败批量转缺陷
                      </el-button>
                      <button
                        v-if="failedDesignOriginSummary.total > 0"
                        type="button"
                        class="design-origin-failure-summary"
                        @click="designOriginFailureExpanded = !designOriginFailureExpanded"
                      >
                        失败 {{ failedDesignOriginSummary.failed }} · 错误 {{ failedDesignOriginSummary.error }}
                      </button>
                      <el-button
                        plain
                        @click="exportDesignOriginSummary"
                      >
                        <Download :size="16" />
                        导出设计稿报表
                      </el-button>
                    </div>
                    </div>
                    <div v-if="automationKind === 'api'" class="action-cluster action-cluster-danger">
                      <div class="summary-tags summary-tags-inline">
                        <el-tag type="info">总数 {{ activeAutomationRunSummary.total }}</el-tag>
                        <el-tag type="success">通过 {{ activeAutomationRunSummary.passed }}</el-tag>
                        <el-tag type="danger">失败 {{ activeAutomationRunSummary.failed }}</el-tag>
                        <el-tag type="warning">错误 {{ activeAutomationRunSummary.error }}</el-tag>
                      </div>
                      <el-button @click="deleteAutomationByScope({ project: automationFilter.project !== 'ALL' ? automationFilter.project : undefined, module: automationFilter.module !== 'ALL' ? automationFilter.module : undefined })">
                        <Trash2 :size="16" />
                        删除当前范围
                      </el-button>
                    </div>
                  </div>
                </div>
              </div>
            <ApiAutomationCaseTable
              v-if="automationKind === 'api'"
              :cases="filteredAutomationCases"
              :selection="automationSelection"
              :result-map="automationResultMap"
              :defect-draft-by-report-case-id="defectDraftByReportCaseId"
              :defect-status-label="defectStatusLabel"
              :defect-status-tag-type="defectStatusTagType"
              @update:selection="automationSelection = $event"
              @open-curl="openAutomationCurlPreview($event)"
              @open-log="openAutomationLog($event)"
              @transfer-defect="transferAutomationResultToDefect($event)"
              @delete-case="deleteAutomationCase($event)"
            />
          </div>
        </section>
        <ResponsiveDrawer
          v-model="automationConfigVisible"
          class="studio-responsive-drawer automation-config-drawer"
          title="API 运行配置"
          description="配置修改后立即生效，关闭抽屉即可返回列表。"
          size="min(92vw, 920px)"
        >
          <div class="automation-config-drawer__content">
            <div class="automation-config-drawer__grid">
              <div class="execution-control-group execution-control-group-env">
                <span>运行环境</span>
                <div class="execution-control-row">
                  <el-select
                    :model-value="executionEnvironmentLibrary.activeEnvironmentId"
                    class="environment-select"
                    @change="(value: string) => selectExecutionEnvironment(value, 'api')"
                  >
                    <el-option
                      v-for="environment in executionEnvironmentLibrary.environments"
                      :key="environment.id"
                      :label="environment.name"
                      :value="environment.id"
                    />
                  </el-select>
                  <el-button @click="openEnvironmentEditor(undefined, 'api')">
                    <Settings :size="16" />
                    环境配置
                  </el-button>
                </div>
              </div>
              <div class="automation-run-summary execution-summary-block">
                <div class="execution-summary-threshold">
                  <div class="execution-summary-threshold-head">
                    <span class="threshold-label">返回体一致度</span>
                    <el-tag class="threshold-value-tag" type="info">{{ responseBodyMatchThreshold }}%</el-tag>
                  </div>
                  <div class="execution-summary-threshold-body">
                    <el-slider
                      v-model="responseBodyMatchThreshold"
                      class="threshold-slider"
                      :min="0"
                      :max="100"
                      :step="5"
                      :format-tooltip="(value: number) => `${value}%`"
                    />
                  </div>
                </div>
              </div>
              <div class="execution-control-group execution-control-group-mode">
                <span>执行方式</span>
                <div class="execution-control-row">
                  <el-segmented
                    v-model="automationRunMode"
                    :options="[
                      { label: '顺序', value: 'sequential' },
                      { label: '并发', value: 'concurrent' },
                      { label: '按模块', value: 'module' }
                    ]"
                  />
                </div>
              </div>
              <div class="execution-control-group execution-control-group-count">
                <span>{{ automationRunMode === 'concurrent' ? '并发数' : '循环次数' }}</span>
                <div class="execution-control-row">
                  <div class="api-run-count-stepper" data-testid="api-run-count-stepper">
                    <button
                      type="button"
                      class="api-run-count-stepper__button"
                      aria-label="减少"
                      :disabled="apiRunCountValue <= apiRunCountMin"
                      @click="stepApiRunCount(-1)"
                    >−</button>
                    <input
                      :value="apiRunCountInput"
                      class="api-run-count-stepper__input"
                      type="text"
                      inputmode="numeric"
                      aria-label="循环次数"
                      data-testid="api-run-count-input"
                      @input="onApiRunCountInput"
                      @blur="commitApiRunCount()"
                    />
                    <button
                      type="button"
                      class="api-run-count-stepper__button"
                      aria-label="增加"
                      @click="stepApiRunCount(1)"
                    >+</button>
                  </div>
                  <small v-if="apiRunCountError" class="api-run-count-error" role="alert">{{ apiRunCountError }}</small>
                </div>
              </div>
            </div>
          </div>
        </ResponsiveDrawer>
        </template>
        </ApiExecutionPage>
        </template>
        <template #web>
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
        </ExecutionWorkbenchView>
      </template>

      <template v-else-if="activeNav === 'environments'">
        <EnvironmentManagementView
          :kind="environmentEditorKind"
          :environments="currentEnvironmentLibrary.environments"
          :active-environment-id="editingEnvironment.id"
          @update:kind="switchEnvironmentManagementKind"
          @select="selectEnvironmentFromPage"
          @back="returnToPreparationFromEnvironments"
        >
          <template #directory-actions>
            <div class="environment-page__directory-actions">
              <el-button type="primary" :disabled="!canEdit" @click="addExecutionEnvironment()">
                <Plus :size="14" />
                新增环境
              </el-button>
              <el-button :disabled="!canEdit" @click="openEnvironmentEditor(undefined, environmentEditorKind)">
                打开环境编辑器
              </el-button>
            </div>
          </template>
          <div id="environment-inline-editor-host" class="environment-inline-editor-host"></div>
        </EnvironmentManagementView>
      </template>

      <section v-else class="future-workspace">
        <component :is="currentNav.icon" :size="48" />
        <h1>{{ currentNav.label }}</h1>
        <el-tag type="info">后端后期补充</el-tag>
      </section>
    </main>
  </AppShell>

  <ResponsiveDrawer v-model="narrowNavigationVisible" class="studio-navigation-drawer" title="主导航" size="168px" direction="ltr">
    <PrimarySidebar
      :groups="sidebarGroups"
      :active-item-id="activeSidebarItemId"
      :collapsed="false"
      :badges="sidebarBadges"
      :settings-entry="!isSettingsCenterNav"
      @select="selectSidebarItem($event); narrowNavigationVisible = false"
      @help="narrowNavigationVisible = false; helpVisible = true"
    />
  </ResponsiveDrawer>

  <HelpDrawer v-if="authStatus === 'authenticated'" v-model="helpVisible" />

    <ResponsiveDrawer v-model="editVisible" class="case-editor-drawer" :size="selectedCase && 'pageName' in selectedCase.row ? '820px' : '520px'" title="编辑用例">
      <div class="edit-form" v-if="selectedCase && !('pageName' in selectedCase.row)">
        <el-form label-position="top">
          <el-form-item v-for="field in visibleEditableFields" :key="field.key" :label="field.label">
            <el-input
              v-if="isMultilineField(field.type)"
              v-model="editRow[field.key as keyof InterfaceCaseRow]"
              type="textarea"
              :rows="field.key === 'steps' ? 4 : 3"
            />
            <el-input v-else v-model="editRow[field.key as keyof InterfaceCaseRow]" />
          </el-form-item>
        </el-form>
        <div class="drawer-actions">
          <el-button @click="editVisible = false">取消</el-button>
          <el-button type="primary" @click="saveEdit">保存修改</el-button>
        </div>
      </div>
      <div class="edit-form" v-else>
        <el-form label-position="top">
          <div class="web-edit-section">
            <strong>运行配置</strong>
            <div class="web-edit-grid">
              <el-form-item label="运行环境">
                <div class="inline-form-control">
                  <el-select v-model="webEditRow.environmentId" placeholder="选择 Web 运行环境">
                    <el-option
                      v-for="environment in webExecutionEnvironmentLibrary.environments"
                      :key="environment.id"
                      :label="environment.name"
                      :value="environment.id"
                    />
                  </el-select>
                  <el-button @click="applySelectedWebEnvironmentToEditRow">应用运行环境默认值</el-button>
                </div>
              </el-form-item>
              <el-form-item label="应用访问地址">
                <el-input v-model="webEditRow.startUrl" placeholder="/login 或完整 URL" />
              </el-form-item>
              <el-form-item label="浏览器">
                <el-select v-model="webEditRow.browser">
                  <el-option label="Chromium" value="chromium" />
                  <el-option label="Google Chrome" value="chrome" />
                  <el-option label="Microsoft Edge" value="msedge" />
                  <el-option label="Firefox" value="firefox" />
                  <el-option label="WebKit" value="webkit" />
                </el-select>
              </el-form-item>
              <el-form-item label="视口">
                <el-input v-model="webEditRow.viewport" placeholder="1440x900" />
              </el-form-item>
              <el-form-item label="等待策略">
                <el-select v-model="webEditRow.waitStrategy">
                  <el-option label="load" value="load" />
                  <el-option label="domcontentloaded" value="domcontentloaded" />
                  <el-option label="networkidle" value="networkidle" />
                </el-select>
              </el-form-item>
              <el-form-item label="截图策略">
                <el-select v-model="webEditRow.screenshotStrategy">
                  <el-option label="不截图" value="none" />
                  <el-option label="失败截图" value="on_failure" />
                  <el-option label="每步截图" value="always" />
                </el-select>
              </el-form-item>
              <el-form-item label="失败重试次数">
                <el-input-number v-model="webEditRow.retryCount" :min="0" :max="10" controls-position="right" />
              </el-form-item>
            </div>
          </div>
          <div class="web-edit-section">
            <strong>用例信息</strong>
            <div class="web-edit-grid">
              <el-form-item label="项目">
                <el-input v-model="webEditRow.project" />
              </el-form-item>
              <el-form-item label="模块">
                <el-input v-model="webEditRow.module" />
              </el-form-item>
              <el-form-item label="页面">
                <el-input v-model="webEditRow.pageName" />
              </el-form-item>
              <el-form-item label="用例标题">
                <el-input v-model="webEditRow.title" />
              </el-form-item>
              <el-form-item label="用例类型">
                <el-input v-model="webEditRow.caseType" />
              </el-form-item>
              <el-form-item label="优先级">
                <el-select v-model="webEditRow.priority">
                  <el-option label="P0" value="P0" />
                  <el-option label="P1" value="P1" />
                  <el-option label="P2" value="P2" />
                </el-select>
              </el-form-item>
            </div>
          </div>
          <div class="web-edit-section">
            <strong>测试设计</strong>
            <el-form-item label="自然语言步骤">
              <el-input
                v-model="webEditRow.naturalSteps"
                type="textarea"
                :rows="4"
                placeholder="按业务动作逐步描述，例如：进入日志管理页面；切换到权限控制；使用普通成员账号查看菜单"
              />
            </el-form-item>
            <el-form-item label="自然语言预期">
              <el-input
                v-model="webEditRow.naturalExpectedResult"
                type="textarea"
                :rows="3"
                placeholder="描述页面应出现或不应出现的元素、文案、顺序、权限结果"
              />
            </el-form-item>
          </div>
          <div class="web-edit-section">
            <strong>自动化脚本</strong>
            <el-form-item label="操作步骤">
              <el-input v-model="webEditRow.steps" type="textarea" :rows="6" />
            </el-form-item>
            <el-form-item label="元素定位">
              <el-input v-model="webEditRow.locators" type="textarea" :rows="4" />
            </el-form-item>
            <el-form-item label="断言规则">
              <el-input v-model="webEditRow.assertions" type="textarea" :rows="4" />
            </el-form-item>
          </div>
          <el-form-item label="备注">
            <el-input v-model="webEditRow.remark" type="textarea" :rows="3" />
          </el-form-item>
        </el-form>
        <div class="drawer-actions">
          <el-button @click="editVisible = false">取消</el-button>
          <el-button type="primary" @click="saveEdit">保存修改</el-button>
        </div>
      </div>
    </ResponsiveDrawer>

    <el-drawer v-model="curlVisible" class="curl-preview-drawer" size="min(620px, 100vw)" title="可执行请求">
      <div class="curl-preview">
        <div class="curl-preview-head">
          <span>请求预览</span>
          <div class="drawer-actions">
            <el-button @click="curlVisible = false">关闭</el-button>
            <el-button type="primary" @click="copyCurl">
              <Copy :size="16" />
              复制 cURL
            </el-button>
          </div>
        </div>
        <div class="request-summary" v-if="executableRequest">
          <span class="method-pill" :data-method="executableRequest.method">{{ executableRequest.method }}</span>
          <code>{{ executableRequest.url }}</code>
          <el-tag size="small">预期 {{ executableRequest.expectedStatus }}</el-tag>
        </div>
        <pre>{{ curlText }}</pre>
      </div>
    </el-drawer>

    <EnvironmentEditorSurface
      v-if="authStatus === 'authenticated'"
      v-model="environmentVisible"
      :inline="activeNav === 'environments'"
      :web="environmentEditorKind === 'web'"
      :title="environmentEditorKind === 'web' ? 'Web 运行环境配置' : 'API 运行环境配置'"
    >
      <div class="environment-drawer">
        <aside class="environment-list">
          <div class="environment-list-head">
            <strong>环境</strong>
            <el-button type="primary" @click="addExecutionEnvironment()">
              <Plus :size="14" />
              新增
            </el-button>
          </div>
          <button
            v-for="environment in currentEnvironmentLibrary.environments"
            :key="environment.id"
            class="environment-list-item"
            :class="{ active: editingEnvironment.id === environment.id }"
            @click="openEnvironmentEditor(environment.id)"
          >
            <span>{{ environment.name }}</span>
            <code>{{ environment.baseUrl || '未配置 Base URL' }}</code>
          </button>
        </aside>

        <section class="environment-editor">
          <template v-if="environmentEditorKind === 'web' && editingEnvironment.web">
            <section class="environment-section environment-section--runtime">
              <header class="environment-section__head">
                <div>
                  <strong>运行参数</strong>
                  <span>浏览器、视口与执行等待策略</span>
                </div>
              </header>
              <div class="environment-form-grid web-environment-grid">
                <label>
                  <span>环境名称</span>
                  <el-input v-model="editingEnvironment.name" placeholder="Web 测试环境" />
                </label>
                <label class="environment-form-wide">
                  <span>Web 访问入口</span>
                  <el-input v-model="editingEnvironment.baseUrl" placeholder="https://haitlify.example.com" />
                </label>
                <label>
                   <span>浏览器</span>
                   <el-select v-model="editingEnvironment.web.browser">
                     <el-option label="跟随用例" value="follow-case" />
                     <el-option label="Chromium" value="chromium" />
                    <el-option label="Google Chrome" value="chrome" />
                    <el-option label="Microsoft Edge" value="msedge" />
                    <el-option label="Firefox" value="firefox" />
                    <el-option label="WebKit" value="webkit" />
                  </el-select>
                </label>
                <label>
                  <span>视口</span>
                  <el-input v-model="editingEnvironment.web.viewport" placeholder="1440x900" />
                </label>
                <label>
                  <span>Headless</span>
                  <el-switch v-model="editingEnvironment.web.headless" active-text="开启" inactive-text="关闭" />
                </label>
                <label>
                  <span>默认等待超时</span>
                  <el-input-number v-model="editingEnvironment.web.defaultTimeoutMs" :min="1000" :step="1000" controls-position="right" />
                </label>
                <label>
                  <span>页面加载超时</span>
                  <el-input-number v-model="editingEnvironment.web.navigationTimeoutMs" :min="1000" :step="1000" controls-position="right" />
                </label>
                <label>
                  <span>等待页面状态</span>
                  <el-select v-model="editingEnvironment.web.waitUntil">
                    <el-option label="load" value="load" />
                    <el-option label="domcontentloaded" value="domcontentloaded" />
                    <el-option label="networkidle" value="networkidle" />
                  </el-select>
                </label>
                <label>
                  <span>截图策略</span>
                  <el-select v-model="editingEnvironment.web.screenshotStrategy">
                    <el-option label="不截图" value="none" />
                    <el-option label="失败截图" value="on_failure" />
                    <el-option label="每步截图" value="always" />
                  </el-select>
                </label>
                <label>
                  <span>视频录制</span>
                  <el-switch v-model="editingEnvironment.web.videoRecording" active-text="开启" inactive-text="关闭" />
                </label>
                <label>
                  <span>失败重试次数</span>
                  <el-input-number v-model="editingEnvironment.web.retryCount" :min="0" :max="10" controls-position="right" />
                </label>
                <label class="environment-form-wide">
                  <span>网络放行策略</span>
                  <el-select v-model="editingWebNetworkPolicyMode" class="environment-policy-select">
                    <el-option label="黑名单（默认：只拦截名单内的请求，其余放行）" value="blocklist" />
                    <el-option label="白名单（只放行名单内的请求，其余拦截）" value="allowlist" />
                  </el-select>
                </label>
                <label class="environment-form-wide">
                  <span>{{ editingWebNetworkPolicyMode === 'blocklist' ? '拦截名单（每行一个）' : '放行名单（每行一个）' }}</span>
                  <el-input
                    v-model="editingWebAllowedRequestOriginsText"
                    type="textarea"
                    :rows="3"
                    :placeholder="editingWebNetworkPolicyMode === 'blocklist'
                      ? 'https://ads.example.com\nhttps://api.example.com/internal/write（带路径 = 只拦这一条）'
                      : 'https://auth.example.com\nhttps://api.example.com/v4/uc/n/auth/login（带路径 = 精确放行，含 POST）'"
                  />
                  <small class="environment-form-hint">{{ editingWebNetworkPolicyMode === 'blocklist'
                    ? '取证默认不拦截任何请求；仅拦截下列条目（Origin 拦整个来源，带路径 URL 只拦该请求）。适合可信的测试环境。'
                    : '仅放行下列条目（Origin 放行页面资源与 GET 请求；带路径的完整 URL 精确放行，含 POST），其余请求一律拦截，失败信息会点名被拦来源。' }}</small>
                </label>
              </div>
            </section>

            <div class="environment-section environment-section--lifecycle">
              <div class="environment-entries-head">
                <strong>自动登录</strong>
                <div class="environment-inline-actions">
                  <el-switch v-model="editingEnvironment.web.login.enabled" active-text="开启" inactive-text="关闭" />
                  <el-button
                    :loading="webLoginDiscoveryLoading"
                    :disabled="!canEdit"
                    @click="startWebLoginDiscovery"
                  >
                    <Search :size="15" />
                    快速识别登录
                  </el-button>
                  <el-button
                    type="primary"
                    plain
                    :loading="webEnvironmentVerifying"
                    @click="verifyEditingWebEnvironmentLogin"
                  >
                    验证登录
                  </el-button>
                </div>
              </div>

              <div class="environment-login-tip">
                <span>用于真实浏览器先执行登录，再进入 Web 用例步骤。</span>
              </div>

              <div v-if="editingEnvironment.web.login.enabled" class="environment-form-grid web-environment-grid">
                <label class="environment-form-wide">
                  <span>登录页地址</span>
                  <el-input v-model="editingEnvironment.web.login.loginUrl" placeholder="/#/login 或 https://haitlify.example.com/#/login" />
                </label>
                <label>
                  <span>登录账号</span>
                  <el-input v-model="editingEnvironment.web.login.username" placeholder="admin" />
                </label>
                <label>
                  <span>登录密码</span>
                  <el-input v-model="editingEnvironment.web.login.password" show-password placeholder="请输入登录密码" />
                </label>
                <label>
                  <span>账号输入框定位器</span>
                  <el-input v-model="editingEnvironment.web.login.usernameLocator" placeholder="css=input[name='username']" />
                </label>
                <label>
                  <span>密码输入框定位器</span>
                  <el-input v-model="editingEnvironment.web.login.passwordLocator" placeholder="css=input[type='password']" />
                </label>
                <label class="environment-form-wide">
                  <span>登录按钮定位器</span>
                  <el-input v-model="editingEnvironment.web.login.submitLocator" placeholder="role=button[name='登录']" />
                </label>
                <label>
                  <span>成功判定方式</span>
                  <el-select v-model="editingEnvironment.web.login.successType">
                    <el-option label="URL 命中" value="url" />
                    <el-option label="元素可见" value="visible" />
                  </el-select>
                </label>
                <label class="environment-form-wide">
                  <span>成功判定目标</span>
                  <el-input
                    v-model="editingEnvironment.web.login.successTarget"
                    :placeholder="editingEnvironment.web.login.successType === 'url' ? '/user-center' : 'text=用户中心'"
                  />
                </label>
              </div>

              <div class="environment-lifecycle-divider"></div>

              <div class="environment-entries-head">
                <div>
                  <strong>退出登录后置条件</strong>
                  <div class="environment-login-tip">每个浏览器完成全部用例后执行一次。</div>
                </div>
                <div class="environment-inline-actions">
                  <el-switch v-model="editingEnvironment.web.logout.enabled" active-text="开启" inactive-text="关闭" />
                  <el-button
                    :disabled="!editingEnvironment.web.logout.enabled"
                    @click="addWebLogoutStep"
                  >
                    <Plus :size="15" />
                    添加步骤
                  </el-button>
                </div>
              </div>

              <div
                v-if="editingEnvironment.web.logout.enabled"
                class="web-logout-step-list"
              >
                <div
                  v-for="(step, index) in editingEnvironment.web.logout.steps"
                  :key="step.id"
                  class="web-logout-step-row"
                >
                  <span class="web-logout-step-index">{{ index + 1 }}</span>
                  <el-input
                    v-model="step.name"
                    class="web-logout-step-name"
                    aria-label="后置步骤名称"
                    placeholder="步骤名称"
                  />
                  <el-select
                    v-model="step.action"
                    class="web-logout-step-action"
                    aria-label="后置步骤动作"
                    @change="(action: WebAutomationAction) => updateWebLogoutStepAction(step, action)"
                  >
                    <el-option
                      v-for="option in webLogoutActionOptions"
                      :key="option.value"
                      :label="option.label"
                      :value="option.value"
                    />
                  </el-select>
                  <el-input
                    v-model="step.target"
                    class="web-logout-step-target"
                    aria-label="后置步骤目标"
                    :placeholder="webLogoutTargetPlaceholder(step)"
                  />
                  <div class="web-logout-step-actions">
                    <el-button
                      text
                      title="上移"
                      aria-label="上移后置步骤"
                      :disabled="index === 0"
                      @click="moveWebLogoutStep(index, -1)"
                    >
                      <ArrowUp :size="15" />
                    </el-button>
                    <el-button
                      text
                      title="下移"
                      aria-label="下移后置步骤"
                      :disabled="index === editingEnvironment.web.logout.steps.length - 1"
                      @click="moveWebLogoutStep(index, 1)"
                    >
                      <ArrowDown :size="15" />
                    </el-button>
                    <el-button
                      text
                      type="danger"
                      title="删除"
                      aria-label="删除后置步骤"
                      @click="removeWebLogoutStep(index)"
                    >
                      <Trash2 :size="15" />
                    </el-button>
                  </div>
                </div>
                <el-empty
                  v-if="editingEnvironment.web.logout.steps.length === 0"
                  :image-size="48"
                  description="请添加退出登录步骤"
                />
              </div>

              <div
                v-if="webEnvironmentVerification"
                class="environment-verification"
                :data-status="webEnvironmentVerification.status"
              >
                <strong>{{ webEnvironmentVerification.message }}</strong>
                <span v-if="webEnvironmentVerification.detail">{{ webEnvironmentVerification.detail }}</span>
              </div>

              <div
                v-if="editingEnvironment.web && editingEnvironment.web.sessionId"
                class="environment-verification"
                data-status="success"
              >
                <strong>已保存登录会话</strong>
                <span>{{ editingEnvironment.web.sessionId.slice(0, 18) }}…（编译取证优先复用；约 10 分钟有效，过期后自动回退到自动登录）</span>
                <el-button text type="danger" size="small" @click="clearWebLoginSession()">清除会话</el-button>
              </div>
            </div>

          </template>

          <template v-else>
            <div class="environment-form-grid">
              <label>
                <span>环境名称</span>
                <el-input v-model="editingEnvironment.name" placeholder="测试环境" />
              </label>
              <label>
                <span>Base URL</span>
                <el-input v-model="editingEnvironment.baseUrl" placeholder="https://api.example.com" />
              </label>
              <label>
                <span>认证方式</span>
                <el-select v-model="editingEnvironment.auth.type">
                  <el-option label="无" value="none" />
                  <el-option label="Bearer Token" value="bearer" />
                  <el-option label="自定义 Header" value="custom" />
                </el-select>
              </label>
              <label v-if="editingEnvironment.auth.type === 'bearer'">
                <span>Bearer Token</span>
                <el-input v-model="editingEnvironment.auth.token" show-password placeholder="token" />
              </label>
              <label v-if="editingEnvironment.auth.type === 'custom'">
                <span>认证 Header</span>
                <el-input v-model="editingEnvironment.auth.headerName" placeholder="Authorization" />
              </label>
              <label v-if="editingEnvironment.auth.type === 'custom'">
                <span>认证值</span>
                <el-input v-model="editingEnvironment.auth.headerValue" show-password placeholder="Bearer token" />
              </label>
            </div>

            <div class="curl-import-box">
              <el-input
                v-model="curlImportText"
                type="textarea"
                :rows="3"
                placeholder="粘贴 curl 中的 -H 和 -b 行，自动解析为 Header / Cookie"
              />
              <el-button @click="importCurlToEnvironment">解析到环境</el-button>
            </div>

            <div class="environment-entries-head">
              <strong>参数</strong>
              <div>
                <el-button @click="addEnvironmentEntry('header')">新增 Header</el-button>
                <el-button @click="addEnvironmentEntry('cookie')">新增 Cookie</el-button>
                <el-button @click="addEnvironmentEntry('variable')">新增变量</el-button>
              </div>
            </div>

            <el-table class="case-table" :data="editingEnvironment.entries" height="320" border>
            <el-table-column label="启用" width="72">
              <template #default="{ row }">
                <el-switch v-model="row.enabled" />
              </template>
            </el-table-column>
            <el-table-column label="类型" width="120">
              <template #default="{ row }">
                <el-select v-model="row.type">
                  <el-option label="Header" value="header" />
                  <el-option label="Cookie" value="cookie" />
                  <el-option label="变量" value="variable" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="参数名" min-width="160">
              <template #default="{ row }">
                <el-input v-model="row.name" placeholder="参数名" />
              </template>
            </el-table-column>
            <el-table-column label="参数值" min-width="260">
              <template #default="{ row }">
                <el-input v-model="row.value" placeholder="参数值" show-password />
              </template>
            </el-table-column>
            <el-table-column label="说明" min-width="180">
              <template #default="{ row }">
                <el-input v-model="row.description" placeholder="可选" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="82" fixed="right">
              <template #default="{ row }">
                <el-button link type="danger" @click="deleteEnvironmentEntry(row.id)">删除</el-button>
              </template>
            </el-table-column>
            </el-table>
          </template>

          <div class="drawer-actions environment-drawer-actions">
            <el-button type="danger" plain @click="deleteExecutionEnvironment(editingEnvironment.id)">删除环境</el-button>
            <el-button @click="cancelEnvironmentEditing">取消</el-button>
            <el-button type="primary" @click="saveEditingEnvironment()">保存环境</el-button>
          </div>
        </section>
      </div>
    </EnvironmentEditorSurface>

    <el-dialog
      v-model="webLoginDiscoveryDialogVisible"
      class="web-login-discovery-dialog"
      title="快速识别登录"
      width="1040px"
      destroy-on-close
      @closed="closeWebLoginDiscovery"
    >
      <div
        v-if="webLoginDiscoveryLoading && webLoginDiscoverySnapshot?.status !== 'completed'"
        class="web-login-discovery-status"
      >
        <el-progress
          :percentage="webLoginDiscoverySnapshot?.status === 'running' ? 68 : 28"
          :show-text="false"
          :stroke-width="6"
        />
        <strong>{{ webLoginDiscoverySnapshot?.status === 'running' ? '正在分析登录页' : '正在启动浏览器' }}</strong>
        <span>{{ webLoginDiscoverySnapshot?.request?.url || '正在提交识别任务' }}</span>
      </div>

      <div
        v-else-if="webLoginDiscoverySnapshot?.status === 'failed'"
        class="web-login-discovery-status is-failed"
      >
        <strong>登录页识别失败</strong>
        <span>{{ webLoginDiscoverySnapshot.diagnostics?.[0] || 'Runner 未返回错误详情' }}</span>
        <el-button type="primary" plain :disabled="!canEdit" @click="startWebLoginDiscovery">重新识别</el-button>
      </div>

      <div
        v-else-if="webLoginDiscoverySnapshot?.status === 'completed'"
        class="web-login-discovery-layout"
      >
        <section class="web-login-discovery-preview">
          <div class="web-login-discovery-section-head">
            <strong>页面识别结果</strong>
            <el-tag size="small" type="success">已完成</el-tag>
          </div>
          <div class="web-login-discovery-screenshot">
            <img
              v-if="webLoginDiscoverySnapshot.screenshotUrl"
              :src="webLoginDiscoverySnapshot.screenshotUrl"
              alt="登录控件识别截图"
            />
            <el-empty v-else description="无页面截图" :image-size="72" />
          </div>
          <code class="web-login-discovery-page-url">
            {{ webLoginDiscoverySnapshot.pageUrl || webLoginDiscoverySnapshot.request?.url }}
          </code>
        </section>

        <section class="web-login-discovery-candidates">
          <div class="web-login-discovery-section-head">
            <strong>定位器候选</strong>
            <span>规则识别</span>
          </div>

          <label class="web-login-discovery-field">
            <span>账号输入框</span>
            <el-select
              v-model="webLoginDiscoverySelection.usernameCandidateId"
              placeholder="未识别到候选项"
              filterable
            >
              <el-option
                v-for="candidate in webLoginDiscoverySnapshot.candidates?.username || []"
                :key="candidate.id"
                :label="`${candidate.label || candidate.locator} · ${candidate.confidence}%`"
                :value="candidate.id"
              />
            </el-select>
            <div v-if="selectedWebLoginDiscoveryCandidate('username')" class="web-login-discovery-candidate-detail">
              <code>{{ selectedWebLoginDiscoveryCandidate('username')?.locator }}</code>
              <span>{{ selectedWebLoginDiscoveryCandidate('username')?.reason }}</span>
            </div>
          </label>

          <label class="web-login-discovery-field">
            <span>密码输入框</span>
            <el-select
              v-model="webLoginDiscoverySelection.passwordCandidateId"
              placeholder="未识别到候选项"
              filterable
            >
              <el-option
                v-for="candidate in webLoginDiscoverySnapshot.candidates?.password || []"
                :key="candidate.id"
                :label="`${candidate.label || candidate.locator} · ${candidate.confidence}%`"
                :value="candidate.id"
              />
            </el-select>
            <div v-if="selectedWebLoginDiscoveryCandidate('password')" class="web-login-discovery-candidate-detail">
              <code>{{ selectedWebLoginDiscoveryCandidate('password')?.locator }}</code>
              <span>{{ selectedWebLoginDiscoveryCandidate('password')?.reason }}</span>
            </div>
          </label>

          <label class="web-login-discovery-field">
            <span>登录按钮</span>
            <el-select
              v-model="webLoginDiscoverySelection.submitCandidateId"
              placeholder="未识别到候选项"
              filterable
            >
              <el-option
                v-for="candidate in webLoginDiscoverySnapshot.candidates?.submit || []"
                :key="candidate.id"
                :label="`${candidate.label || candidate.locator} · ${candidate.confidence}%`"
                :value="candidate.id"
              />
            </el-select>
            <div v-if="selectedWebLoginDiscoveryCandidate('submit')" class="web-login-discovery-candidate-detail">
              <code>{{ selectedWebLoginDiscoveryCandidate('submit')?.locator }}</code>
              <span>{{ selectedWebLoginDiscoveryCandidate('submit')?.reason }}</span>
            </div>
          </label>

          <div
            v-if="webLoginDiscoverySnapshot.diagnostics?.length"
            class="web-login-discovery-diagnostics"
          >
            <span v-for="diagnostic in webLoginDiscoverySnapshot.diagnostics" :key="diagnostic">
              {{ diagnostic }}
            </span>
          </div>
        </section>
      </div>

      <template #footer>
        <div class="drawer-actions">
          <el-button @click="webLoginDiscoveryDialogVisible = false">取消</el-button>
          <el-button
            type="primary"
            :loading="webLoginDiscoveryConfirming"
            :disabled="webLoginDiscoverySnapshot?.status !== 'completed'"
            @click="confirmAndApplyWebLoginDiscovery"
          >
            确认并回填
          </el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog
      v-model="webLoginVerificationVisible"
      class="web-login-verification-dialog"
      title="验证 Web 自动登录"
      width="min(900px, 92vw)"
      :close-on-click-modal="false"
      destroy-on-close
      @closed="closeWebLoginVerification"
    >
      <div class="web-login-verification-layout">
        <section class="web-login-verification-preview">
          <div class="web-login-verification-section-head">
            <strong>{{ webLoginVerificationRunning ? '浏览器实时操作' : webLoginVerificationSnapshot?.status === 'completed' ? '登录验证完成' : '登录验证结果' }}</strong>
            <el-tag
              size="small"
              :type="webLoginVerificationRunning ? 'warning' : webLoginVerificationSnapshot?.status === 'completed' ? 'success' : 'danger'"
            >
              {{ webLoginVerificationRunning ? '执行中' : webLoginVerificationSnapshot?.status === 'completed' ? '通过' : '失败' }}
            </el-tag>
          </div>
          <div class="web-login-verification-screenshot">
            <img
              v-if="webLoginVerificationSnapshot?.latestScreenshotUrl"
              :src="webLoginVerificationSnapshot.latestScreenshotUrl"
              alt="自动登录实时页面"
            />
            <div v-else class="web-login-verification-placeholder">
              <LoaderCircle v-if="webLoginVerificationRunning" :size="22" class="is-loading" />
              <span>{{ webLoginVerificationRunning ? '等待浏览器页面截图…' : '暂无页面截图' }}</span>
            </div>
          </div>
          <code class="web-login-verification-page-url" :title="webLoginVerificationSnapshot?.currentPageUrl || ''">
            {{ webLoginVerificationSnapshot?.currentPageUrl || '浏览器尚未返回当前页面地址' }}
          </code>
        </section>

        <section class="web-login-verification-details">
          <div class="web-login-verification-current-step">
            <span>当前步骤</span>
            <strong>{{ webLoginVerificationSnapshot?.currentStep?.name || (webLoginVerificationRunning ? '准备浏览器环境' : '已结束') }}</strong>
            <small v-if="webLoginVerificationSnapshot?.currentStep">
              {{ webLoginVerificationSnapshot.currentStep.phase }} · {{ webLoginVerificationSnapshot.currentStep.action }}
            </small>
          </div>
          <div class="web-login-verification-progress">
            <div><span>执行进度</span><strong>{{ webLoginVerificationSnapshot?.progress?.completedSteps || 0 }} / {{ webLoginVerificationSnapshot?.progress?.totalSteps || 0 }} 步</strong></div>
            <el-progress :percentage="webLoginVerificationSnapshot?.progress?.totalSteps ? Math.round((webLoginVerificationSnapshot.progress.completedSteps / webLoginVerificationSnapshot.progress.totalSteps) * 100) : (webLoginVerificationRunning ? 12 : 0)" :show-text="false" />
          </div>
          <div class="web-login-verification-steps">
            <div v-for="step in webLoginVerificationSnapshot?.stepLogs || []" :key="`${step.stepId}-${step.startedAt || ''}`" class="web-login-verification-step" :data-status="step.status">
              <span>{{ step.status === 'passed' ? '✓' : '!' }}</span>
              <div><strong>{{ step.name }}</strong><small>{{ step.pageUrl || '等待页面地址' }}</small><p v-if="step.errorMessage">{{ step.errorMessage }}</p></div>
            </div>
            <el-empty v-if="!(webLoginVerificationSnapshot?.stepLogs?.length)" description="等待浏览器执行步骤" :image-size="56" />
          </div>
          <div v-if="webLoginVerificationSnapshot?.diagnostics?.length" class="web-login-verification-diagnostics">
            <strong>诊断信息</strong>
            <p v-for="diagnostic in webLoginVerificationSnapshot.diagnostics" :key="diagnostic">{{ diagnostic }}</p>
          </div>
        </section>
      </div>
      <template #footer>
        <div class="drawer-actions">
          <el-button v-if="webLoginVerificationRunning" type="danger" plain @click="closeWebLoginVerification">停止验证</el-button>
          <el-button @click="webLoginVerificationVisible = false">关闭</el-button>
        </div>
      </template>
    </el-dialog>

    <el-drawer v-model="automationLogVisible" class="web-automation-drawer web-automation-log-drawer" size="720px" title="执行日志">
      <div class="curl-preview" v-if="selectedAutomationResult">
        <div class="request-summary">
          <span class="method-pill" :data-method="selectedAutomationResult.method">{{ selectedAutomationResult.method }}</span>
          <code>{{ selectedAutomationResult.request.url }}</code>
          <el-tag :type="automationStatusTagType(selectedAutomationResult.status)">
            {{ automationStatusLabel(selectedAutomationResult.status) }}
          </el-tag>
        </div>
        <div v-if="selectedAutomationResult.method === 'WEB'" class="log-grid">
          <div class="log-card">
            <strong>执行摘要</strong>
            <pre>{{ formatLogValue({
              caseId: selectedAutomationResult.caseId,
              sourceCaseId: selectedAutomationResult.sourceCaseId,
              module: selectedAutomationResult.module,
              status: selectedAutomationResult.status,
              durationMs: selectedAutomationResult.durationMs,
              failedStepId: selectedAutomationResult.failedStepId,
              screenshotPath: selectedAutomationResult.screenshotPath
            }) }}</pre>
          </div>
          <div class="log-card">
            <strong>失败原因</strong>
            <pre>{{ formatLogValue(selectedAutomationResult.errorMessage) }}</pre>
          </div>
          <div class="log-card full-width" v-if="selectedAutomationResult.screenshotPath">
            <strong>失败截图</strong>
            <a :href="selectedAutomationResult.screenshotPath" target="_blank" rel="noreferrer">
              {{ selectedAutomationResult.screenshotPath }}
            </a>
          </div>
          <div class="log-card full-width">
            <strong>步骤日志</strong>
            <div v-if="reportWebStepLogs(selectedAutomationResult).length" class="report-web-step-items">
              <div
                v-for="step in reportWebStepLogs(selectedAutomationResult)"
                :key="step.stepId"
                class="report-web-step-item expanded"
              >
                <span class="report-web-step-action">{{ step.action }}</span>
                <el-tag v-if="step.phase" size="small" effect="plain">{{ step.phase }}</el-tag>
                <code class="report-web-step-target">{{ step.target }}</code>
                <el-tag :type="automationStatusTagType(step.status)" effect="light">
                  {{ automationStatusLabel(step.status) }}
                </el-tag>
                <span>{{ step.durationMs }} ms</span>
                <span v-if="step.pageUrl">页面：{{ step.pageUrl }}</span>
                <span v-if="step.name">步骤：{{ step.name }}</span>
                <pre v-if="step.errorMessage" class="report-html-snippet">{{ step.errorMessage }}</pre>
              </div>
            </div>
            <pre v-else>{{ formatLogValue([]) }}</pre>
          </div>
          <div class="log-card full-width" v-if="reportStepEvidence(selectedAutomationResult).length">
            <strong>步骤证据</strong>
            <WebStepEvidenceCard :evidence="reportStepEvidence(selectedAutomationResult)" />
          </div>
          <div class="log-card full-width">
            <strong>Action IR</strong>
            <el-button size="small" data-testid="open-action-ir-viewer" @click="actionIrViewerVisible = true">
              查看 Action IR / 用例步骤
            </el-button>
            <ActionIrViewerDialog
              v-model:visible="actionIrViewerVisible"
              :title="`Action IR / 用例步骤 · ${selectedAutomationResult.name || selectedAutomationResult.caseId}`"
              :actions="selectedActionIrCase?.actions"
              :steps="selectedActionIrCase?.steps"
            />
          </div>
          <div class="log-card full-width" v-if="selectedAutomationResult.pageHtmlSnippet">
            <strong>页面片段</strong>
            <pre>{{ selectedAutomationResult.pageHtmlSnippet }}</pre>
          </div>
          <div class="log-card" v-if="selectedAutomationResult.consoleErrors?.length">
            <strong>Console Errors</strong>
            <pre>{{ formatLogValue(selectedAutomationResult.consoleErrors) }}</pre>
          </div>
          <div class="log-card" v-if="selectedAutomationResult.pageErrors?.length">
            <strong>Page Errors</strong>
            <pre>{{ formatLogValue(selectedAutomationResult.pageErrors) }}</pre>
          </div>
          <div class="log-card full-width" v-if="selectedAutomationResult.failedRequests?.length">
            <strong>Failed Requests</strong>
            <pre>{{ formatLogValue(selectedAutomationResult.failedRequests) }}</pre>
          </div>
        </div>
        <div v-else class="log-grid">
          <div class="log-card">
            <strong>请求头</strong>
            <pre>{{ formatLogValue(selectedAutomationResult.request.headers) }}</pre>
          </div>
          <div class="log-card">
            <strong>请求体</strong>
            <pre>{{ formatLogValue(selectedAutomationResult.request.body) }}</pre>
          </div>
          <div class="log-card">
            <strong>断言规则</strong>
            <pre>{{ formatLogValue(selectedAutomationResult.request.assertions) }}</pre>
          </div>
          <div class="log-card">
            <strong>预期返回体</strong>
            <pre>{{ formatLogValue(selectedAutomationResult.expectedResponseBody) }}</pre>
          </div>
          <div class="log-card">
            <strong>实际返回体</strong>
            <pre>{{ formatLogValue(selectedAutomationResult.actualResponseBody ?? selectedAutomationResult.responseBody) }}</pre>
          </div>
          <div class="log-card">
            <strong>错误信息</strong>
            <pre>{{ formatLogValue(selectedAutomationResult.errorMessage) }}</pre>
          </div>
          <div class="log-card">
            <strong>执行摘要</strong>
            <pre>{{ formatLogValue({
              caseId: selectedAutomationResult.caseId,
              expectedStatus: selectedAutomationResult.expectedStatus,
              actualStatus: selectedAutomationResult.actualStatus,
              durationMs: selectedAutomationResult.durationMs,
              responseBodyMatch: selectedAutomationResult.responseBodyMatch
            }) }}</pre>
          </div>
        </div>
      </div>
    </el-drawer>

    <el-dialog v-model="automationGenerateVisible" class="web-automation-dialog case-conversion-dialog" title="从用例库生成自动化用例" width="520px">
      <div class="edit-form">
        <label>
          <span>项目</span>
          <el-select v-model="automationGenerateProject" placeholder="项目">
            <el-option label="全部项目" value="ALL" />
            <el-option
              v-for="project in automationGenerationScope.projects"
              :key="project.value"
              :label="`${project.value}（${project.count}）`"
              :value="project.value"
            />
          </el-select>
        </label>
        <label>
          <span>模块</span>
          <el-select v-model="automationGenerateModule" placeholder="模块">
            <el-option label="全部模块" value="ALL" />
            <el-option
              v-for="module in automationGenerationScope.modules"
              :key="module.value"
              :label="`${module.value}（${module.count}）`"
              :value="module.value"
            />
          </el-select>
        </label>
        <div class="request-summary">
          <span class="method-pill">范围</span>
          <code>将生成 {{ automationGenerationScope.cases.length }} 条自动化用例</code>
          <el-tag size="small">保存到自动化库</el-tag>
        </div>
      </div>
      <template #footer>
        <div class="drawer-actions">
          <el-button @click="automationGenerateVisible = false">取消</el-button>
          <el-button type="primary" :disabled="!canEdit" @click="confirmGenerateAutomationFromLibrary">确定生成</el-button>
        </div>
      </template>
    </el-dialog>


    <el-dialog
      v-model="changePasswordDialogVisible"
      class="account-password-dialog"
      title="修改密码"
      width="min(92vw, 420px)"
      :close-on-click-modal="false"
      :close-on-press-escape="!currentUser?.mustChangePassword"
      :show-close="!currentUser?.mustChangePassword"
    >
      <div class="change-password-form">
        <p v-if="currentUser?.mustChangePassword" class="change-password-form__hint">
          首次登录需修改初始密码后才能继续使用。
        </p>
        <el-input
          v-model="changePasswordForm.currentPassword"
          type="password"
          placeholder="当前密码"
          autocomplete="current-password"
        />
        <el-input
          v-model="changePasswordForm.newPassword"
          type="password"
          placeholder="新密码（至少 8 位）"
          autocomplete="new-password"
        />
        <el-input
          v-model="changePasswordForm.confirmPassword"
          type="password"
          placeholder="确认新密码"
          autocomplete="new-password"
        />
        <p v-if="changePasswordForm.error" class="change-password-form__error" role="alert">
          {{ changePasswordForm.error }}
        </p>
      </div>
      <template #footer>
        <el-button v-if="!currentUser?.mustChangePassword" @click="changePasswordDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="changePasswordForm.submitting" @click="submitChangePassword">
          确认修改
        </el-button>
      </template>
    </el-dialog>
</template>

<style scoped>
.change-password-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.change-password-form__error {
  margin: 0;
  font-size: 13px;
  color: var(--color-danger);
}

.change-password-form__hint {
  margin: 0;
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 13px;
  color: var(--status-warning-text);
  background: var(--color-warning-soft);
}

/* API 执行准备：页头副标题 + 循环次数步进（仅本页/本抽屉使用，不改全局样式） */
.automation-topbar__subtitle {
  margin: 4px 0 0;
  font-size: 11px;
  color: var(--ink-muted, #6b707a);
}

.api-run-count-stepper {
  display: inline-flex;
  align-items: stretch;
  min-height: 34px;
  border: 1px solid #e8ebee;
  border-radius: 20px;
  background: #fff;
  overflow: hidden;
}

.api-run-count-stepper__button {
  width: 34px;
  border: 0;
  background: transparent;
  color: #3f5a50;
  font-size: 15px;
  line-height: 1;
  cursor: pointer;
}

.api-run-count-stepper__button:disabled {
  color: #b7bfc6;
  cursor: not-allowed;
}

.api-run-count-stepper__button:focus-visible {
  background: #edf2f0;
}

.api-run-count-stepper__input {
  width: 56px;
  border: 0;
  border-inline: 1px solid #eef1f4;
  text-align: center;
  font-size: 12px;
  color: #303740;
  background: #fff;
}

.api-run-count-stepper__input:focus {
  outline: 0;
  background: #fafcfb;
}

.api-run-count-error {
  margin-left: 8px;
  font-size: 11px;
  color: var(--color-danger, #b4423f);
}

@media (max-width: 640px) {
  .automation-config-drawer .execution-control-group-count .execution-control-row {
    flex-wrap: wrap;
  }

  .automation-config-drawer .execution-control-group-count .api-run-count-stepper {
    flex: none;
  }

  .automation-config-drawer .execution-control-group-count .api-run-count-error {
    flex-basis: 100%;
    margin-left: 0;
  }
}

:global(.el-message.web-run-terminal-feedback) {
  z-index: 1999 !important;
  pointer-events: none;
}

/* 环境配置独立页：目录动作（slot 内容在父作用域内提供） */
.environment-page__directory-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
</style>

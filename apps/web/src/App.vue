<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Archive,
  Bug,
  CircleCheck,
  FileText,
  ChevronRight,
  Copy,
  Download,
  LoaderCircle,
  Pause,
  Play,
  Plus,
  Save,
  Search,
  Settings,
  SlidersHorizontal,
  Sparkles,
  Square,
  Terminal,
  Trash2,
  Upload,
  X,
  ArrowUp,
  ArrowDown,
  ArrowLeftRight,
  RotateCcw
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
  buildExecutionEnvironment,
  defaultExecutionEnvironmentLibrary,
  defaultWebExecutionEnvironmentLibrary,
  defaultWebExecutionRuntimeConfig,
  WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY,
  loadExecutionEnvironmentLibrary,
  newEnvironmentEntry,
  newSavedExecutionEnvironment,
  newSavedWebExecutionEnvironment,
  normalizeWebExecutionRuntimeConfig,
  parseCurlEnvironmentImport,
  parseCurlIntoEnvironmentEntries,
  saveExecutionEnvironmentLibrary,
  validateExecutionEnvironment,
  validateWebExecutionEnvironment,
  type BuiltWebExecutionRuntime,
  type EnvironmentAuthType,
  type EnvironmentEntryType,
  type ExecutionEnvironmentEntry,
  type ExecutionEnvironmentLibrary,
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
  saveWebAutomationRunCases,
  type SavedWebAutomationRunCase
} from './domain/webAutomationRepository'
import {
  buildAutomationCurlCommand,
  buildAutomationExecutableRequest,
  exportApifoxAutomationDraft,
  generateAutomationCasesFromInterfaceCases,
  removeResultsFromAutomationRunReport,
  runAutomationCases,
  type AutomationRunResult,
  type AutomationRunMode,
  type AutomationRunReport,
  type AutomationRunStatus,
  type ApiAutomationCaseDraft
} from './domain/apiAutomation'
import {
  generateWebAutomationCasesFromGeneratedCases,
  exportWebAutomationRunCases,
  removeResultsFromWebAutomationRunReport,
  validateWebAutomationConversionCases,
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
  runWebAutomationCasesViaBackend,
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
  buildWebRunView
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
import { reviewGeneratedCases, type CaseReviewFinding } from './domain/caseReview'
import { supplementMissingCaseTypes } from './domain/caseSupplement'
import {
  defaultCaseTypes,
  type CaseTemplateDefinition
} from './domain/caseTemplates'
import { applyInterfaceFieldTemplate, applyInterfaceFieldTemplateToCases } from './domain/caseTemplateApplication'
import { syncMetadataFromRow } from './domain/caseBulkEdit'
import {
  generateInterfaceCases,
  type GeneratedInterfaceCase,
  type GenerationGranularity,
  type InterfaceCaseRow
} from './domain/caseGenerator'
import { markCasesAsAiReviewed } from './domain/caseEnrichment'
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
  loadSavedCasesWithBackend,
  loadTemplateConfigWithBackend,
  saveGeneratedCasesWithBackend,
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
import { downloadCsv, exportCasesToCsv } from './domain/csvExport'
import {
  filterOperations,
  parseOpenApiDocument,
  type HttpMethod,
  type ParsedOpenApiDocument,
  type ParsedOperation
} from './domain/openapiParser'
import {
  automationKindFromPath,
  automationPathFor,
  caseDesignKindFromPath,
  caseDesignPathFor,
  defaultNavKey,
  navKeyFromPath,
  navPathFor,
  type NavKey
} from './domain/appNavigation'
import { fetchCurrentUser, logout, changePassword, type AuthUser } from './domain/authRepository'
import { setUnauthorizedHandler } from './domain/http'
import AppShell from './components/shell/AppShell.vue'
import LoginScreen from './components/auth/LoginScreen.vue'
import GlobalTopbar from './components/shell/GlobalTopbar.vue'
import PrimarySidebar from './components/shell/PrimarySidebar.vue'
import HomeView, { type HomeNavigateTarget } from './components/views/HomeView.vue'
import SettingsCenterView from './components/views/SettingsCenterView.vue'
import QualityWorkbenchView from './components/views/QualityWorkbenchView.vue'
import ExecutionWorkbenchView from './components/views/ExecutionWorkbenchView.vue'
import CaseWorkbenchView from './components/views/CaseWorkbenchView.vue'
import CasesView from './components/views/CasesView.vue'
import WorkflowHeader from './components/workflow/WorkflowHeader.vue'
import RequirementImportStage from './components/workflow/RequirementImportStage.vue'
import AiPolicyDisclosure from './components/platformAi/AiPolicyDisclosure.vue'
import AiRunSummaryView from './components/platformAi/AiRunSummaryView.vue'
import AgentAssetsPanel from './components/platformAi/AgentAssetsPanel.vue'
import RunPolicyEditor from './components/platformAi/RunPolicyEditor.vue'
import GeneratedWebCaseTable from './components/workflow/GeneratedWebCaseTable.vue'
import TestPointParseStage from './components/workflow/TestPointParseStage.vue'
import NaturalLanguageCaseGenerationStage from './components/workflow/NaturalLanguageCaseGenerationStage.vue'
import AutomationCompileStage from './components/workflow/AutomationCompileStage.vue'
import ApiAutomationCaseTable from './components/automation/ApiAutomationCaseTable.vue'
import AiReviewInspector from './components/workflow/AiReviewInspector.vue'
import CaseDesignContextPane from './components/workflow/CaseDesignContextPane.vue'
import CaseDeliveryStage from './components/workflow/CaseDeliveryStage.vue'
import InterfaceCaseContextPane from './components/workflow/InterfaceCaseContextPane.vue'
import InterfaceOperationTable from './components/workflow/InterfaceOperationTable.vue'
import InterfaceCaseTable from './components/workflow/InterfaceCaseTable.vue'
import InterfaceReviewInspector from './components/workflow/InterfaceReviewInspector.vue'
import InterfaceCaseDeliveryStage from './components/workflow/InterfaceCaseDeliveryStage.vue'
import ResponsiveDrawer from './components/workflow/ResponsiveDrawer.vue'
import WorkflowStateView from './components/workflow/WorkflowStateView.vue'
import WebAutomationWorkspace from './components/automation/WebAutomationWorkspace.vue'
import WebStepEvidenceCard from './components/automation/WebStepEvidenceCard.vue'
import ActionIrViewerDialog from './components/automation/ActionIrViewerDialog.vue'
import type { WebCaseStatusFilter } from './components/automation/WebAutomationCaseTable.vue'
import { initialSidebarCollapsed, useViewportMode } from './ui/useViewportMode'
import { useOnlineStatus } from './ui/useOnlineStatus'
import { useDefects } from './ui/useDefects'
import { useReports } from './ui/useReports'
import { useTemplateConfig } from './ui/useTemplateConfig'
import { useAiConfig } from './ui/useAiConfig'
import { useCases } from './ui/useCases'
import {
  automationStatusLabel,
  automationStatusTagType,
  formatLogValue,
  reportStepEvidence,
  reportWebStepLogs,
  webRunResultToAutomationLog
} from './ui/automationFormat'
import {
  resolveNavDisplay,
  resolveSidebarActiveItemId,
  sidebarGroupsForRole,
  type SidebarBadges,
  type SidebarItem
} from './ui/navigation/appSidebar'
import {
  createImportedOpenApiProject,
  selectImportedProject,
  upsertImportedProject,
  type ImportedOpenApiProject
} from './domain/openapiProjectRepository'
import { buildFigmaDesignDraftFromUrl } from './domain/figmaDesignImport'
import { parseFigmaDesignWithBackend } from './domain/figmaDesignRepository'
import {
  importAiFigmaFile,
  listAiFigmaChangeSets,
  listAiFigmaDraftPoints,
  listAiFigmaElementCandidates,
  listAiFigmaNodes,
  startAiFigmaFileAnalysis,
  startAiFigmaNodeAnalysis,
  type AiFigmaAnalysisRunResponse,
  type AiFigmaChangeSetResponse,
  type AiFigmaDraftPointResponse,
  type AiFigmaElementCandidateListResponse,
  type AiFigmaFileResponse,
  type AiFigmaNodeListResponse
} from './domain/aiFigmaRequirementRepository'
import {
  buildFigmaNodeTree,
  figmaDraftEvidenceLabel,
  groupFigmaElementCandidates,
  sortFigmaChangeRows
} from './domain/aiFigmaRequirementView'
import {
  ignoreAiMultiAgentConflict,
  listAiMultiAgentConflicts,
  listAiMultiAgentGraph,
  listAiMultiAgentQualityScores,
  mergeAiMultiAgentRun,
  startAiMultiAgentRun,
  type AiAgentMergeResponse,
  type AiMultiAgentRunResponse
} from './domain/aiMultiAgentRunRepository'
import {
  buildAiReviewInsightView,
  buildMultiAgentMergeCandidatesFromSuggestions,
  type AgentConflictView,
  type AiReviewSuggestionView,
  type QualityScoreView
} from './domain/aiMultiAgentRunView'
import {
  collectFailedDesignOriginResults,
  isDesignOriginAutomationCase,
  isDesignOriginWebCase,
  summarizeFailedDesignOriginResults
} from './domain/designOrigin'
import { mapDesignOriginFailureItems } from './domain/designOriginFailureView'
import {
  applyAnalysisSnapshot,
  createAnalysisProgressState,
  isTerminalAnalysisStatus,
  type AiAnalysisLogEntry
} from './domain/aiAnalysisProgress'
import { checkAiEnvironmentReadiness } from './domain/aiEnvironmentReadiness'
import { mapRunEventsToLog } from './domain/aiRunEventLog'
import {
  confirmAiDraftTestPoint,
  cancelAiAnalysisRun,
  createAiAnalysisRun,
  deleteAiDraftTestPoint,
  editConfirmAiDraftTestPoint,
  getAiAnalysisCoverageSummary,
  getAiAnalysisRun,
  listAiAnalysisToolCalls,
  listAiAnalysisUnclearQuestions,
  listAiCallRecords,
  listAiDraftTestPoints,
  listAiAnalysisRunEvents,
  listAiRunPolicies,
  loadAiConfigFromPlatform,
  rejectAiDraftTestPoint,
  resumeAiAnalysisRun,
  retryAiAnalysisRun,
  uploadAiSourceDocument,
  type AiAnalysisCoverageSummary,
  type AiAnalysisRun,
  type AiDraftTestPoint,
  type GeneratedAiCase,
  type AiRunEvent,
  type AiRunEventList,
  type AiSourceDocument,
  type AiToolCallRecord,
  type AiUnclearQuestion
} from './domain/aiPlatformRepository'
import {
  cancelWebCaseAgentRun,
  getWebCaseAgentRun,
  listWebCaseAgentArtifacts,
  confirmAllWebCasePlanArtifacts,
  listWebCaseAgentEvents,
  recordAgentAssetOutcome,
  startWebCaseAgentRun,
  type WebCaseAgentArtifact,
  type WebCaseAgentEvent,
  type WebCaseAgentRun
} from './domain/webCaseAgentRepository'
import {
  buildAiDraftTestPointView,
  confidenceBucket,
  type AiDraftTestPointFilter,
  type AiDraftTestPointView
} from './domain/aiDraftTestPointView'
import {
  formatVisualRegionCoordinates,
  visualEvidenceForDraftPoint
} from './domain/aiVisualRequirementView'
import {
  buildAiAnalysisCoverageView,
  type AiAnalysisCoverageView
} from './domain/aiAnalysisCoverageView'
import {
  buildDesignAutomationDraftCases,
  finalizeDesignDraftCasesForLibrary,
  toRequirementDesignDraftCases
} from './domain/designAutomationDraft'
import { designLocatorCards } from './domain/designLocatorView'
import {
  buildGeneratedCasesFromAiGeneratedCases,
  buildNaturalLanguageCasesFromAgentArtifacts,
  formatNaturalLanguageText,
  importWebCaseDocuments,
  type RequirementPointDraft,
  summarizeImportedWebCases
} from './domain/webCaseImport'
import {
  buildCaseDesignWorkflowView,
  reviewRunStatusToWorkflowStatus,
  type CaseDesignStage,
  type WorkflowAsyncStatus
} from './domain/caseDesignWorkflow'
import { generatedWebCaseIdOffset } from './domain/caseGenerationBatch'
import {
  buildInterfaceCaseWorkflowView,
  type InterfaceCaseStage
} from './domain/interfaceCaseWorkflow'
import {
  inferRequirementSourceKind,
  clearCaseDesignDraft,
  loadCaseDesignDraft,
  saveCaseDesignDraft,
  type CaseReviewDecision,
  type CaseReviewFindingDecisions,
  type PersistedRequirementSourceKind
} from './domain/caseDesignDraftRepository'
import { buildCaseDesignSourceView } from './domain/caseDesignSourceView'
import { buildCaseDesignReviewView } from './domain/caseDesignReviewView'
import {
  buildCaseDesignDeliveryView,
  type CaseDesignDeliveryMode,
  type CaseDesignDeliveryRiskItem
} from './domain/caseDesignDelivery'
import { parseRequirementDocumentWithBackend } from './domain/requirementDocumentRepository'
import { buildRequirementImportText } from './domain/requirementDocumentPayload'
import { exportProjectData, importProjectData } from './domain/projectDataRepository'
import { loadTemplateConfig } from './domain/templateConfigRepository'
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

interface StagedRequirementFile {
  name: string
  text: string
  kind?: PersistedRequirementSourceKind
  needsReattach?: boolean
}

interface RequirementSourcePickOptions {
  replaceName?: string
  kind?: PersistedRequirementSourceKind
  localRecovery?: boolean
}

const FIGMA_REQUIREMENT_FILE_NAME = 'figma-link.txt'

type NavHistoryMode = 'push' | 'replace' | 'skip'

const savedCollapsed = localStorage.getItem('ai-test-platform.sidebar-collapsed')
const railCollapsed = ref(initialSidebarCollapsed(savedCollapsed, window.innerWidth))
const viewportMode = useViewportMode()
const isOnline = useOnlineStatus()
const isCompactDesktop = computed(() => viewportMode.value === 'compact' || viewportMode.value === 'narrow')
const isNarrowViewport = computed(() => viewportMode.value === 'narrow')
const narrowNavigationVisible = ref(false)
const compactContextVisible = ref(false)
const compactInspectorVisible = ref(false)
const restoredCaseDesignDraft = loadCaseDesignDraft(localStorage)
const globalSearch = ref('')
const activeNav = ref<NavKey>(resolveInitialNav())
const openApiFileInput = ref<HTMLInputElement>()
const webCaseFileInput = ref<HTMLInputElement>()
const pendingRequirementSourcePick = shallowRef<RequirementSourcePickOptions>()
const requirementSourceInputAccept = computed(() =>
  pendingRequirementSourcePick.value?.kind === 'openapi'
    ? '.json,application/json'
    : '.pdf,.md,.markdown,.json,.doc,.docx,.xls,.xlsx,.csv,.yaml,.yml,.txt,.png,.jpg,.jpeg,.webp,.gif,.bmp,application/pdf,text/markdown,application/json,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.ms-excel,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/x-yaml,text/yaml,text/csv,text/plain,image/*'
)
const importedProjects = ref<ImportedOpenApiProject[]>([])
const activeProjectId = ref('')
const selectedOperationIds = ref<string[]>([])
const generatedCases = ref<GeneratedInterfaceCase[]>([])
const activeInterfaceCaseStage = ref<InterfaceCaseStage>('import')
const interfaceReviewAttempted = ref(false)
const ignoredInterfaceReviewFindingIds = ref<string[]>([])
const selectedInterfaceCaseIds = ref<string[]>([])
const interfaceReviewStatusFilter = ref('ALL')
const generatedWebCases = ref<GeneratedWebAutomationCase[]>([])
const stagedRequirementFiles = ref<StagedRequirementFile[]>([])
const stagedRequirementSourceFiles = ref<File[]>([])
const extractedRequirementPoints = ref<RequirementPointDraft[]>([])
const requirementAnalysisProjectName = ref('默认项目')
const aiSourceDocuments = shallowRef<AiSourceDocument[]>([])
const aiAnalysisRun = shallowRef<AiAnalysisRun>()
const aiDraftPoints = shallowRef<AiDraftTestPoint[]>([])
const aiCoverageSummary = shallowRef<AiAnalysisCoverageSummary>()
const aiUnclearQuestions = shallowRef<AiUnclearQuestion[]>([])
const aiToolCalls = shallowRef<AiToolCallRecord[]>([])
const aiAnalysisRunning = ref(false)
const aiCaseGenerationRunning = ref(false)
const aiCaseGenerationCancelling = ref(false)
const webCaseAgentRun = shallowRef<WebCaseAgentRun>()
const naturalPlanRunId = ref(restoredCaseDesignDraft?.naturalPlanRunId || '')
const webCaseAgentEvents = shallowRef<WebCaseAgentEvent[]>([])
const webCaseAgentNaturalPlanCount = ref(0)
const deliveredWebCaseArtifactIds = new Set<string>()
const webCaseAgentPollCoordinator = createWebRunPollCoordinator()
const aiAnalysisDetailLoading = ref(false)
const webRequirementFlowDismissed = ref(false)
const aiAnalysisProgress = reactive({
  percentage: 0,
  text: '等待上传需求文档'
})
const aiCaseGenerationProgress = reactive({
  percentage: 0,
  text: '等待 AI 分析需求完成'
})
const aiAnalysisLog = shallowRef<AiAnalysisLogEntry[]>([])
const aiAnalysisPollCoordinator = createWebRunPollCoordinator()
const generationScopePointIds = ref<string[]>(restoredCaseDesignDraft?.generationScopePointIds || [])
const generationRetryPointIds = ref<string[]>(restoredCaseDesignDraft?.generationRetryPointIds || [])
const selectedGeneratedWebCaseIds = ref<string[]>(restoredCaseDesignDraft?.selectedGeneratedWebCaseIds || [])
const caseGenerationConfirmed = ref(restoredCaseDesignDraft?.caseGenerationConfirmed === true)
const generationScopeAllSelected = ref(true)
const requirementAiReviewRun = shallowRef<AiMultiAgentRunResponse>()
const caseAiReviewRun = shallowRef<AiMultiAgentRunResponse>()
const activeAiReviewStage = ref<'requirement' | 'case'>('requirement')
const aiMultiAgentRun = computed(() => activeAiReviewStage.value === 'case' ? caseAiReviewRun.value : requirementAiReviewRun.value)
const aiMultiAgentConflicts = shallowRef<AgentConflictView[]>([])
const aiMultiAgentQualityScores = shallowRef<QualityScoreView[]>([])
const aiMultiAgentMergeResult = shallowRef<AiAgentMergeResponse>()
const aiMultiAgentLoading = ref(false)
const activeCaseDesignStage = ref<CaseDesignStage>(restoredCaseDesignDraft?.activeStage || 'requirement')
const caseDesignLastSavedAt = ref(restoredCaseDesignDraft?.updatedAt || '')
const caseDesignDraftSaveError = ref('')
const requirementSourceErrors = ref<Record<string, string>>(restoredCaseDesignDraft?.sourceErrors || {})
const aiAnalysisError = ref(restoredCaseDesignDraft?.analysisError || '')
const aiGenerationError = ref(restoredCaseDesignDraft?.generationError || '')
const showAiRunSummary = computed(() => aiAnalysisRun.value?.status === 'failed' || Boolean(aiAnalysisError.value))
const caseReviewError = ref(
  restoredCaseDesignDraft?.reviewError
    || (restoredCaseDesignDraft?.caseReviewRun?.status === 'failed'
      ? restoredCaseDesignDraft.caseReviewRun.errorSummary || 'AI 质量评审未完成'
      : '')
)
const caseReviewAttempted = ref(
  restoredCaseDesignDraft?.reviewAttempted
    ?? Boolean(restoredCaseDesignDraft?.caseReviewRun || restoredCaseDesignDraft?.reviewError)
)
const restoredAiReviewInsight = ref(restoredCaseDesignDraft?.review.insight)
const caseReviewFindingDecisions = ref<CaseReviewFindingDecisions>(
  restoredCaseDesignDraft?.review.findingDecisions || {}
)
const caseDesignDraftPersistenceSuspended = ref(false)
let caseDesignDraftTimer: ReturnType<typeof setTimeout> | undefined
const deliveryMode = ref<CaseDesignDeliveryMode>('library')
const deliveryExecuteNow = ref(false)
const aiDraftSelection = shallowRef<AiDraftTestPoint[]>([])
const aiDraftBulkConfirming = ref(false)
const aiFigmaFile = shallowRef<AiFigmaFileResponse>()
const aiFigmaNodes = shallowRef<AiFigmaNodeListResponse>({ items: [] })
const aiFigmaAnalysisRun = shallowRef<AiFigmaAnalysisRunResponse>()
const aiFigmaDraftPoints = shallowRef<AiFigmaDraftPointResponse[]>([])
const aiFigmaChangeSets = shallowRef<AiFigmaChangeSetResponse[]>([])
const aiFigmaElementCandidates = shallowRef<AiFigmaElementCandidateListResponse>({ items: [] })
const selectedAiFigmaNodeId = ref('')
const aiFigmaLoading = ref(false)
const generationKind = ref<'interface' | 'web_automation'>(
  restoredCaseDesignDraft?.generationKind || 'web_automation'
)
const automationCases = ref<SavedAutomationCase[]>(listSavedAutomationCases(localStorage))
const webAutomationCases = ref<SavedWebAutomationRunCase[]>(listSavedWebAutomationRunCases(localStorage))
const automationKind = ref<'api' | 'web'>(resolveInitialAutomationKind())
const automationRunMode = ref<AutomationRunMode>('sequential')
const webAutomationRunMode = ref<WebAutomationRunMode>('sequential')
const automationConcurrencyLimit = ref(8)
const apiAutomationLoopCount = ref(1)
const webAutomationLoopCount = ref(1)
const responseBodyMatchThreshold = ref(100)
const apiLoopCountOptions = [1, 2, 3, 5, 10, 20].map((value) => ({ label: `${value}`, value }))
const apiConcurrencyOptions = [1, 2, 4, 8, 16, 32].map((value) => ({ label: `${value}`, value }))
const apiRunCountSelection = computed<number>({
  get: () => (automationRunMode.value === 'concurrent' ? automationConcurrencyLimit.value : apiAutomationLoopCount.value),
  set: (value) => {
    if (automationRunMode.value === 'concurrent') {
      automationConcurrencyLimit.value = value
      return
    }
    apiAutomationLoopCount.value = value
  }
})
const apiAutomationRunning = ref(false)
const webAutomationRunning = ref(false)
const apiAutomationProgress = reactive({
  completed: 0,
  total: 0,
  runningCaseId: ''
})
const saveCasesLoading = ref(false)
const automationSelection = ref<SavedAutomationCase[]>([])
const automationGenerateVisible = ref(false)
const automationGenerateProject = ref('ALL')
const automationGenerateModule = ref('ALL')
const automationRunReport = ref<AutomationRunReport>()
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
const webAutomationWorkspaceRef = ref<InstanceType<typeof WebAutomationWorkspace>>()
const webRunPollCoordinator = createWebRunPollCoordinator()
let finalizedWebRunId = ''
const jiraConfigForm = reactive<JiraConfig>(defaultJiraConfig())
const jiraConfigLoading = ref(false)
const jiraConfigTesting = ref(false)
const jiraProjectJqlRows = ref<Array<{ key: string; value: string }>>([])
const templateConfigModule = useTemplateConfig({
  confirmDanger: (title, message, confirmLabel) => confirmDangerAction(title, message, confirmLabel),
  persistTemplateConfig: async (config) => {
    const result = await saveTemplateConfigWithBackend(localStorage, currentBackendProjectName(), config)
    return result.config
  },
  getGenerationSelectedCaseTypes: () => generation.selectedCaseTypeIds,
  setGenerationSelectedCaseTypes: (ids) => {
    generation.selectedCaseTypeIds = ids
  }
})
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
const aiConfigModule = useAiConfig({
  confirmDanger: (title, message, confirmLabel) => confirmDangerAction(title, message, confirmLabel),
  getRequirementProjectName: () => requirementAnalysisProjectName.value
})
const {
  aiConfig,
  aiConfigReady,
  aiRunPolicies,
  aiRunPoliciesError,
  aiPolicyDisclosureDismissed,
  aiRequirementPolicy,
  showAiPolicyDisclosure,
  applyAiConfig,
  refreshAiPlatformConfig
} = aiConfigModule
const casesModule = useCases({
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
const {
  savedCases,
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
const webCasePreviewVisible = ref(false)
const previewedWebCase = computed<GeneratedWebAutomationCase | SavedWebAutomationCase | undefined>(() => {
  const testCase = selectedCase.value
  return testCase && 'pageName' in testCase.row && 'steps' in testCase.metadata
    ? testCase as GeneratedWebAutomationCase | SavedWebAutomationCase
    : undefined
})
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
const focusedWebAutomationCaseId = ref('')
const workspaceSyncConflict = ref<WorkspaceSyncConflictNotice>()
const workspaceSyncLoading = ref(false)
const editRow = reactive<InterfaceCaseRow>(emptyEditRow())
const webEditRow = reactive<WebAutomationCaseRow>(emptyWebEditRow())
const reviewFindings = ref<CaseReviewFinding[]>([])
const executionEnvironmentLibrary = ref<ExecutionEnvironmentLibrary>(loadExecutionEnvironmentLibrary(localStorage))
const webExecutionEnvironmentLibrary = ref<ExecutionEnvironmentLibrary>(
  loadExecutionEnvironmentLibrary(localStorage, WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY)
)
const editingEnvironment = reactive<SavedExecutionEnvironment>(cloneEnvironment(defaultExecutionEnvironmentLibrary().environments[0]))
const editingWebAllowedRequestOriginsText = computed({
  get: () => (editingEnvironment.web?.allowedRequestOrigins || []).join('\n'),
  set: (value: string) => {
    if (!editingEnvironment.web) editingEnvironment.web = defaultWebExecutionRuntimeConfig()
    editingEnvironment.web.allowedRequestOrigins = value.split(/\r?\n/).map((item) => item.trim()).filter(Boolean)
  }
})
const editingWebNetworkPolicyMode = computed({
  get: () => editingEnvironment.web?.networkPolicyMode || 'blocklist',
  set: (value: 'blocklist' | 'allowlist') => {
    if (!editingEnvironment.web) editingEnvironment.web = defaultWebExecutionRuntimeConfig()
    editingEnvironment.web.networkPolicyMode = value === 'allowlist' ? 'allowlist' : 'blocklist'
  }
})
const environmentEditorTarget = ref<'api' | 'web'>('api')
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

const importFilter = reactive({
  module: 'ALL',
  method: 'ALL' as HttpMethod | 'ALL',
  pathKeyword: '',
  summaryKeyword: ''
})

const generation = reactive({
  granularity: 'risk' as GenerationGranularity,
  selectedCaseTypeIds: templateConfig.value.caseTypes
    .filter((caseType) => caseType.appliesTo === 'interface' || caseType.appliesTo === 'all')
    .filter((caseType) => caseType.enabled)
    .map((caseType) => caseType.id)
})
const aiDraftFilter = reactive<AiDraftTestPointFilter>({
  module: 'ALL',
  status: 'ALL',
  confidence: 'ALL',
  sourceDocumentId: 'ALL'
})
const previewFilter = reactive({
  module: 'ALL',
  caseType: 'ALL',
  priority: 'ALL',
  executionResult: 'ALL',
  keyword: ''
})

const webPreviewFilter = reactive({
  module: 'ALL',
  caseType: 'ALL',
  keyword: ''
})

const automationFilter = reactive({
  project: 'ALL',
  module: 'ALL',
  method: 'ALL',
  source: 'ALL' as 'ALL' | 'design' | 'default',
  keyword: ''
})

const environmentEditorKind = computed<'api' | 'web'>(() => environmentEditorTarget.value)
const currentEnvironmentLibrary = computed(() =>
  environmentEditorKind.value === 'web' ? webExecutionEnvironmentLibrary.value : executionEnvironmentLibrary.value
)
const activeApiExecutionEnvironment = computed(() =>
  executionEnvironmentLibrary.value.environments.find((environment) =>
    environment.id === executionEnvironmentLibrary.value.activeEnvironmentId
  )
)
const activeWebExecutionEnvironment = computed(() =>
  webExecutionEnvironmentLibrary.value.environments.find((environment) =>
    environment.id === webExecutionEnvironmentLibrary.value.activeEnvironmentId
  )
)
const runnableApiExecutionEnvironment = computed<ExecutionEnvironment | undefined>(() =>
  activeApiExecutionEnvironment.value ? buildExecutionEnvironment(activeApiExecutionEnvironment.value) : undefined
)
const runnableWebRuntime = computed<BuiltWebExecutionRuntime | undefined>(() =>
  activeWebExecutionEnvironment.value ? buildWebExecutionRuntime(activeWebExecutionEnvironment.value) : undefined
)

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
const isWebAutomationGeneration = computed(() => generationKind.value === 'web_automation')
const parsed = computed<ParsedOpenApiDocument | undefined>(() => activeProject.value?.document)

const interfaceModuleOptions = computed(() => [
  { value: 'ALL', label: '全部模块', count: parsed.value?.operations.length || 0 },
  ...modules.value.map((module) => ({ value: module.name, label: module.name, count: module.count }))
])

const interfaceCaseTaskTitle = computed(() =>
  activeProject.value ? `${activeProject.value.title} · 接口用例` : '新建接口用例'
)
const interfaceCaseSaveLabel = computed(() =>
  generatedCases.value.length > 0 ? `当前 ${generatedCases.value.length} 条用例` : '等待生成'
)
const interfaceCaseSecondaryAction = computed(() =>
  importedProjects.value.length > 0 || generatedCases.value.length > 0
    ? { label: '新建任务', disabled: saveCasesLoading.value || !canEdit.value }
    : undefined
)

const interfaceCaseWorkflow = computed(() => buildInterfaceCaseWorkflowView({
  requestedStage: activeInterfaceCaseStage.value,
  importedSourceCount: importedProjects.value.length,
  parsedOperationCount: parsed.value?.operations.length || 0,
  selectedOperationCount: selectedOperationIds.value.length,
  generatedCaseCount: generatedCases.value.length,
  reviewAttempted: interfaceReviewAttempted.value,
  saving: saveCasesLoading.value
}))

// VIEWER 只读:主生成动作在角色无权写时禁用(后端仍有角色强制)
const interfaceCaseWorkflowHeaderPrimaryAction = computed(() => ({
  ...interfaceCaseWorkflow.value.primaryAction,
  disabled: !canEdit.value || interfaceCaseWorkflow.value.primaryAction.disabled
}))

const modules = computed(() => {
  const counts = new Map<string, number>()
  for (const operation of parsed.value?.operations || []) {
    counts.set(operation.module, (counts.get(operation.module) || 0) + 1)
  }
  return Array.from(counts.entries()).map(([name, count]) => ({ name, count }))
})

const filteredOperations = computed(() =>
  parsed.value
    ? filterOperations(parsed.value.operations, {
        module: importFilter.module === 'ALL' ? undefined : importFilter.module,
        method: importFilter.method,
        pathKeyword: importFilter.pathKeyword.trim(),
        summaryKeyword: importFilter.summaryKeyword.trim()
      })
    : []
)

const selectedOperationIdSet = computed(() => new Set(selectedOperationIds.value))
const selectedOperations = computed(() =>
  (parsed.value?.operations || []).filter((operation) => selectedOperationIdSet.value.has(operation.id))
)
const interfaceOperationSelectionVisible = computed(() =>
  importedProjects.value.length > 0
  && generatedCases.value.length === 0
  && ['import', 'generate'].includes(interfaceCaseWorkflow.value.activeStage)
)
const interfaceOperationSelectionEmpty = computed(() =>
  interfaceOperationSelectionVisible.value && filteredOperations.value.length === 0
)

const interfaceCaseReviewStatus = (testCase: GeneratedInterfaceCase): string =>
  testCase.row.tags.split(',').map((tag) => tag.trim()).includes('AI已评审')
    ? '已评审'
    : testCase.metadata.reviewMeta.status

const filteredCases = computed(() =>
  generatedCases.value.filter((testCase) => {
    const row = testCase.row
    if (previewFilter.module !== 'ALL' && row.module !== previewFilter.module) return false
    if (previewFilter.caseType !== 'ALL' && row.caseType !== previewFilter.caseType) return false
    if (previewFilter.priority !== 'ALL' && row.priority !== previewFilter.priority) return false
    if (previewFilter.executionResult !== 'ALL' && row.executionResult !== previewFilter.executionResult) return false
    if (interfaceReviewStatusFilter.value !== 'ALL' && interfaceCaseReviewStatus(testCase) !== interfaceReviewStatusFilter.value) return false
    const keyword = previewFilter.keyword.trim().toLowerCase()
    if (!keyword) return true
    return [row.caseId, row.title, row.path, row.interfaceName, row.tags]
      .join(' ')
      .toLowerCase()
      .includes(keyword)
  })
)

const previewModules = computed(() => Array.from(new Set(generatedCases.value.map((testCase) => testCase.row.module))))
const previewCaseTypes = computed(() => Array.from(new Set(generatedCases.value.map((testCase) => testCase.row.caseType))))
const previewPriorities = computed(() => Array.from(new Set(generatedCases.value.map((testCase) => testCase.row.priority))))
const previewReviewStatuses = computed(() => Array.from(new Set(generatedCases.value.map(interfaceCaseReviewStatus))))
const webCaseSummary = computed(() => summarizeImportedWebCases(generatedWebCases.value))
const canAnalyzeRequirements = computed(() => readyRequirementSourceCount.value > 0)
const canGenerateWebCases = computed(() => extractedRequirementPoints.value.length > 0)
const aiAnalysisCompleted = computed(() => aiAnalysisRun.value?.status === 'succeeded')
const aiCaseGenerationCompleted = computed(() => generatedWebCases.value.length > 0)
const showWebRequirementFlow = computed(() => generationKind.value === 'web_automation' && !webRequirementFlowDismissed.value)
const uploadedRequirementFileNames = computed(() => stagedRequirementFiles.value.map((file) => file.name))
const visibleUploadedRequirementFileNames = computed(() => uploadedRequirementFileNames.value.slice(0, 4))
const hiddenRequirementFileCount = computed(() => Math.max(0, uploadedRequirementFileNames.value.length - visibleUploadedRequirementFileNames.value.length))
const aiFigmaNodeTree = computed(() => buildFigmaNodeTree(aiFigmaNodes.value.items))
const aiFigmaChangeRows = computed(() =>
  aiFigmaChangeSets.value.flatMap((changeSet) => sortFigmaChangeRows(changeSet.changes))
)
const aiFigmaCandidateGroups = computed(() => groupFigmaElementCandidates(aiFigmaElementCandidates.value.items))
const aiMultiAgentUnresolvedConflicts = computed(() =>
  aiMultiAgentConflicts.value.filter((conflict) => conflict.resolutionStatus === 'unresolved').length
)
const aiMultiAgentMergeCandidateCount = computed(() =>
  aiDraftPoints.value.filter((point) => point.reviewStatus !== 'rejected').length || extractedRequirementPoints.value.length
)
const canMergeMultiAgentReview = computed(() =>
  Boolean(aiMultiAgentRun.value)
    && aiReviewInsight.value.suggestions.length > 0
    && aiMultiAgentUnresolvedConflicts.value === 0
)
const aiReviewInsight = computed(() => buildAiReviewInsightView(aiMultiAgentRun.value?.outputSnapshot))
const caseDesignSourceView = computed(() => buildCaseDesignSourceView(
  stagedRequirementFiles.value.map((source) => ({
    name: source.name,
    kind: source.kind || inferRequirementSourceKind(source.name),
    text: source.text,
    needsReattach: Boolean(source.needsReattach)
  })),
  requirementSourceErrors.value
))
const readyRequirementSourceCount = computed(() =>
  caseDesignSourceView.value.filter((source) => source.status === 'ready').length
)
const caseDesignParsedItems = computed(() => [
  ...aiDraftPoints.value.map((point) => ({ id: `requirement-${point.pointId}`, kindLabel: '需求点', label: point.title })),
  ...aiFigmaNodes.value.items.map((node) => ({ id: `figma-${node.figmaNodeId}`, kindLabel: 'Figma', label: node.nodeName }))
])
const caseReviewStatus = computed<WorkflowAsyncStatus>(() =>
  aiMultiAgentLoading.value && activeAiReviewStage.value === 'case'
    ? 'running'
    : caseReviewError.value
      ? 'failed'
      : reviewRunStatusToWorkflowStatus(caseAiReviewRun.value?.status)
)
const caseDesignWorkflow = computed(() => buildCaseDesignWorkflowView({
  requestedStage: activeCaseDesignStage.value,
  sourceCount: readyRequirementSourceCount.value,
  analysisStatus: aiAnalysisRunning.value
    ? 'running'
    : aiAnalysisError.value
      ? 'failed'
      : aiAnalysisCompleted.value
        ? 'succeeded'
        : 'idle',
  confirmedDraftCount: aiDraftPoints.value.filter((point) =>
    point.reviewStatus === 'confirmed' || point.reviewStatus === 'modified'
  ).length,
  pendingDraftCount: pendingAiDraftPoints.value.length,
  generationStatus: aiCaseGenerationRunning.value
    ? 'running'
    : aiGenerationError.value
      ? 'failed'
      : aiCaseGenerationCompleted.value
        ? 'succeeded'
        : 'idle',
  generatedCaseCount: generatedWebCases.value.length,
  caseGenerationConfirmed: caseGenerationConfirmed.value,
  reviewAttempted: caseReviewAttempted.value,
  reviewStatus: caseReviewStatus.value,
  automationCompileStatus: webCaseAgentRun.value?.mode === 'COMPILE' && (webCaseAgentRun.value.status === 'running' || aiCaseGenerationRunning.value)
    ? 'running'
    : aiGenerationError.value
      ? 'failed'
      : generatedWebCases.value.length > 0 && webCaseAgentRun.value?.mode === 'COMPILE' && webCaseAgentRun.value.status === 'succeeded'
        ? 'succeeded'
        : 'idle',
  automationCompiledCaseCount: webCaseAgentRun.value?.mode === 'COMPILE' && webCaseAgentRun.value.status === 'succeeded'
    ? generatedWebCases.value.length
    : 0,
  pendingReviewCount: aiMultiAgentUnresolvedConflicts.value,
  saving: saveCasesLoading.value
}))
const caseDesignStageTitle = computed(() =>
  caseDesignWorkflow.value.stages.find((stage) => stage.key === caseDesignWorkflow.value.activeStage)?.label
  || '需求解析'
)
const caseDesignSaveLabel = computed(() => {
  if (caseDesignWorkflow.value.activeStage === 'test-parse') {
    return '确认需求解析出的测试点，确认后进入自然语言用例生成'
  }
  if (caseDesignWorkflow.value.activeStage === 'case-generate') {
    return '根据已确认测试点生成自然语言标题、步骤和预期结果'
  }
  if (caseDesignWorkflow.value.activeStage === 'review') {
    return '选择用例后可继续编辑 Locator 和执行步骤'
  }
  if (caseDesignWorkflow.value.activeStage === 'automation-compile') {
    return '根据自然语言步骤定位页面元素，生成自动化步骤、元素断言和执行预期'
  }
  return ''
})
const activeAiReviewInsight = computed(() =>
  (aiReviewInsight.value.summary || aiReviewInsight.value.suggestions.length > 0)
    ? aiReviewInsight.value
    : restoredAiReviewInsight.value || { stage: '', summary: '', suggestions: [] }
)
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
const reviewPriorityForSeverity = (severity: string): string => {
  if (severity === 'critical') return 'P0'
  if (severity === 'high') return 'P1'
  return 'P2'
}
const caseDesignReviewFindings = computed<AiReviewSuggestionView[]>(() => {
  const findings: AiReviewSuggestionView[] = [
    ...activeAiReviewInsight.value.suggestions,
    ...aiCoverageView.value.warningRows.map((row) => ({
      findingId: `coverage_gap:coverage-warning:${row.key}`,
      category: 'coverage_gap' as const,
      title: row.section,
      detail: row.message,
      priority: 'P1'
    })),
    ...aiCoverageView.value.unclearQuestions.map((question) => ({
      findingId: `coverage_gap:coverage-question:${question.questionId}`,
      category: 'coverage_gap' as const,
      title: question.questionText,
      detail: question.sourceRef || '',
      priority: reviewPriorityForSeverity(question.severity)
    }))
  ]
  return [...new Map(findings.map((finding) => [finding.findingId, finding])).values()]
})
const caseDesignReviewView = computed(() => buildCaseDesignReviewView({
  selectedCase: selectedReviewCase.value,
  qualityScores: aiMultiAgentQualityScores.value,
  conflicts: aiMultiAgentConflicts.value,
  findings: caseDesignReviewFindings.value,
  findingDecisions: caseReviewFindingDecisions.value
}))
const deliveryAutomationKind = computed<'api' | 'web'>(() =>
  generationKind.value === 'web_automation' ? 'web' : 'api'
)
const activeDeliveryEnvironment = computed(() =>
  deliveryAutomationKind.value === 'web'
    ? activeWebExecutionEnvironment.value
    : activeApiExecutionEnvironment.value
)
const deliveryEnvironmentValidation = computed(() =>
  deliveryAutomationKind.value === 'web'
    ? validateWebExecutionEnvironment(activeDeliveryEnvironment.value)
    : validateExecutionEnvironment(activeDeliveryEnvironment.value)
)
const selectedWebCasesForAutomation = computed(() => {
  const selectedIds = new Set(selectedGeneratedWebCaseIds.value)
  return selectedIds.size > 0
    ? generatedWebCases.value.filter((testCase) => selectedIds.has(testCase.id))
    : generatedWebCases.value
})
const deliveryExecutabilityIssues = computed(() => {
  if (deliveryAutomationKind.value !== 'web') return []
  const pendingLocatorIssues = selectedWebCasesForAutomation.value.flatMap((testCase) =>
    testCase.metadata.locators
      .filter((locator) => locator.confirmed === false)
      .map((locator) => `${testCase.row.caseId}：定位器 ${locator.name} 尚未确认`)
  )
  const prepared = generateWebAutomationCasesFromGeneratedCases(selectedWebCasesForAutomation.value)
  const conversionIssues = validateWebAutomationConversionCases(prepared).map(
    (issue) => `${issue.caseId}：${issue.message}`
  )
  return [...pendingLocatorIssues, ...conversionIssues]
})
const deliveryReviewRisks = computed<CaseDesignDeliveryRiskItem[]>(() => {
  const risks: CaseDesignDeliveryRiskItem[] = aiMultiAgentConflicts.value
    .filter((conflict) => conflict.resolutionStatus === 'unresolved')
    .map((conflict) => ({
      id: `conflict:${conflict.conflictId}`,
      kind: 'conflict' as const,
      priority: 'P0',
      message: `未处理冲突 · ${conflict.conflictType} · ${conflict.targetKey}`
    }))

  for (const testCase of generatedWebCases.value) {
    const view = buildCaseDesignReviewView({
      selectedCase: {
        id: testCase.row.caseId,
        title: testCase.row.title,
        module: testCase.row.module,
        sourceFile: testCase.row.sourceFile
      },
      qualityScores: aiMultiAgentQualityScores.value,
      conflicts: aiMultiAgentConflicts.value,
      findings: caseDesignReviewFindings.value,
      findingDecisions: caseReviewFindingDecisions.value
    })
    view.findings
      .filter((finding) => finding.decision === 'pending')
      .forEach((finding) => risks.push({
        id: finding.category === 'coverage_gap'
          ? `coverage:${finding.findingId}`
          : `${testCase.row.caseId}:${finding.findingId}`,
        kind: finding.category === 'coverage_gap' ? 'coverage_gap' : 'finding',
        priority: finding.priority || 'P2',
        message: finding.category === 'coverage_gap'
          ? `覆盖缺口 · ${finding.title}${finding.detail ? ` · ${finding.detail}` : ''}`
          : `${testCase.row.caseId} · ${finding.title}${finding.detail ? ` · ${finding.detail}` : ''}`
      }))
  }

  return [...new Map(risks.map((risk) => [risk.id, risk])).values()]
})
const caseDesignDeliveryView = computed(() => buildCaseDesignDeliveryView({
  mode: deliveryMode.value,
  executeNow: deliveryExecuteNow.value,
  generatedCount: generationKind.value === 'web_automation'
    ? generatedWebCases.value.length
    : generatedCases.value.length,
  selectedAutomationCount: deliveryAutomationKind.value === 'web'
    ? selectedWebCasesForAutomation.value.length
    : generatedCases.value.length,
  executabilityIssues: deliveryExecutabilityIssues.value,
  reviewAttempted: caseReviewAttempted.value,
  reviewStatus: caseReviewStatus.value,
  reviewError: caseReviewError.value,
  reviewRisks: deliveryReviewRisks.value,
  environmentValid: deliveryEnvironmentValidation.value.valid,
  environmentMessage: deliveryEnvironmentValidation.value.message || ''
}))
const caseDesignHeaderPrimaryAction = computed(() =>
  caseDesignWorkflow.value.activeStage === 'deliver'
    ? {
        label: caseDesignDeliveryView.value.primaryLabel,
        disabled: !canEdit.value || !caseDesignDeliveryView.value.canSubmit || saveCasesLoading.value,
        loading: saveCasesLoading.value
      }
    : {
        ...caseDesignWorkflow.value.primaryAction,
        disabled: !canEdit.value || caseDesignWorkflow.value.primaryAction.disabled
      }
)
const relevantConflictIdSet = computed(() => new Set(caseDesignReviewView.value.relevantConflictIds))
const caseDesignRelevantConflicts = computed(() => aiMultiAgentConflicts.value.filter(
  (conflict) => relevantConflictIdSet.value.has(conflict.conflictId)
))
const activeCaseDesignSource = ref(selectedReviewCase.value?.sourceFile || '')
watch(selectedReviewCase, (testCase) => {
  if (testCase?.sourceFile) activeCaseDesignSource.value = testCase.sourceFile
})
const caseDesignActiveModule = computed<string>(() =>
  (caseDesignWorkflow.value.activeStage === 'case-generate'
    ? aiDraftFilter.module
    : webPreviewFilter.module) || 'ALL'
)
const selectedReviewCaseCount = computed(() =>
  selectedGeneratedWebCaseIds.value.length || (selectedReviewCase.value ? 1 : 0)
)
const shellInspectorVisible = computed(() =>
  activeNav.value === 'api'
  && generationKind.value === 'web_automation'
  && caseDesignWorkflow.value.activeStage === 'review'
)
const interfaceContextVisible = computed(() =>
  activeNav.value === 'api' && generationKind.value === 'interface'
)
const interfaceInspectorVisible = computed(() =>
  interfaceContextVisible.value && interfaceCaseWorkflow.value.activeStage === 'review'
)
const caseDesignContextVisible = computed(() =>
  activeNav.value === 'api'
  && generationKind.value === 'web_automation'
  && ['case-generate', 'review', 'automation-compile'].includes(caseDesignWorkflow.value.activeStage)
)
/** 设置中心工作台:4 个设置导航(平台 AI/模板与字段/Jira 配置/用户管理)合并为单页 + 左分组导航 */
const isSettingsCenterNav = computed(() =>
  ['aiConfig', 'settings', 'jiraConfig', 'users'].includes(activeNav.value)
)
/** 质量工作台:报告与缺陷导航合并为单页 + 左分组导航(转缺陷→缺陷高亮联动) */
const isQualityWorkbenchNav = computed(() =>
  ['reports', 'defects'].includes(activeNav.value)
)
/** 用例工作台:用例库(cases)与智能用例设计(api)并入单页 + 左分组导航;
 * api 画布保持自身布局(不重排),仅被壳收纳进内容区。 */
const isCaseWorkbenchNav = computed(() =>
  activeNav.value === 'cases' || activeNav.value === 'api'
)
/** 任一工作台壳(设置中心/质量工作台/执行工作台/用例工作台)打开时,workspace 收成单行网格由壳占满;
 * Web 自动化除外——它自管全高布局,仍走 .is-web-automation 工作区。
 * 注:用例工作台的 api 导航不入 is-workbench——api 画布保留 .is-studio-* 修饰(不重排),
 * 由 .is-studio-* 工作区内的 .workbench 壳自适应撑满(见 studio-canvas.css)。 */
const isWorkbenchNav = computed(() =>
  isSettingsCenterNav.value
  || isQualityWorkbenchNav.value
  || activeNav.value === 'cases'
  || (activeNav.value === 'automation' && automationKind.value === 'api')
)
const inlineInspectorVisible = computed(() =>
  !isCompactDesktop.value && (shellInspectorVisible.value || interfaceInspectorVisible.value)
)

watch([viewportMode, activeCaseDesignStage, activeInterfaceCaseStage, generationKind], () => {
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
  if (aiAnalysisRunning.value) return `内容解析 · ${aiAnalysisProgress.text}`
  if (aiCaseGenerationRunning.value) return `AI 生成 · ${aiCaseGenerationProgress.text}`
  if (webAutomationRunning.value) return 'Web 自动化执行中'
  if (apiAutomationRunning.value) return `API 自动化 · ${apiAutomationProgress.completed}/${apiAutomationProgress.total}`
  return ''
})

const openActiveLongTask = () => {
  if (aiAnalysisRunning.value || aiCaseGenerationRunning.value) {
    generationKind.value = 'web_automation'
    activeCaseDesignStage.value = aiAnalysisRunning.value ? 'requirement' : 'case-generate'
    navigateToNav('api')
    return
  }
  navigateToNav('automation')
}

const selectCaseDesignModule = (module: string) => {
  if (caseDesignWorkflow.value.activeStage === 'case-generate') {
    aiDraftFilter.module = module
    return
  }
  webPreviewFilter.module = module
}

const selectCaseDesignSource = (sourceFile: string) => {
  activeCaseDesignSource.value = sourceFile
  if (caseDesignWorkflow.value.activeStage === 'case-generate') {
    const sourceOption = aiDraftPointView.value.sourceOptions.find((option) => option.label === sourceFile)
    if (sourceOption) aiDraftFilter.sourceDocumentId = sourceOption.value
    return
  }
  webPreviewFilter.keyword = sourceFile
}

const decideSelectedReviewFinding = (findingId: string, decision: CaseReviewDecision) => {
  const testCase = selectedReviewCase.value
  if (!testCase) return
  const previousDecision = caseReviewFindingDecisions.value[testCase.id]?.[findingId]
  if (decision === 'accepted' && previousDecision === 'accepted') return
  caseReviewFindingDecisions.value = {
    ...caseReviewFindingDecisions.value,
    [testCase.id]: {
      ...(caseReviewFindingDecisions.value[testCase.id] || {}),
      [findingId]: decision
    }
  }
  if (decision === 'modified' && selectedCase.value && 'pageName' in selectedCase.value.row) {
    openWebEdit(selectedCase.value as GeneratedWebAutomationCase)
  }
  if (decision === 'accepted') void mergeAcceptedReviewFinding(findingId, testCase.id)
}

const bulkDecideReviewCases = (decision: CaseReviewDecision) => {
  const selectedIds = new Set(
    selectedGeneratedWebCaseIds.value.length > 0
      ? selectedGeneratedWebCaseIds.value
      : selectedCase.value ? [selectedCase.value.id] : []
  )
  const next: CaseReviewFindingDecisions = Object.fromEntries(
    Object.entries(caseReviewFindingDecisions.value).map(([caseId, decisions]) => [caseId, { ...decisions }])
  )
  generatedWebCases.value.filter((testCase) => selectedIds.has(testCase.id)).forEach((testCase) => {
    const view = buildCaseDesignReviewView({
      selectedCase: {
        id: testCase.row.caseId,
        title: testCase.row.title,
        module: testCase.row.module,
        sourceFile: testCase.row.sourceFile
      },
      qualityScores: aiMultiAgentQualityScores.value,
      conflicts: aiMultiAgentConflicts.value,
      findings: caseDesignReviewFindings.value,
      findingDecisions: next
    })
    const caseDecisions = { ...(next[testCase.row.caseId] || {}) }
    view.findings.forEach((finding) => { caseDecisions[finding.findingId] = decision })
    next[testCase.row.caseId] = caseDecisions
  })
  caseReviewFindingDecisions.value = next
}

const appendMergedPointsToGeneratedCases = (points: AiDraftTestPoint[]) => {
  if (!points.length) return
  const generated = buildNaturalLanguageCasesFromAgentArtifacts(
    points.map((point) => ({
      sourcePointId: point.pointId,
      candidateJson: {
        title: point.title,
        module: point.module,
        pageName: point.pageOrFeature || '未命名页面',
        naturalSteps: point.steps,
        naturalExpectedResult: point.expectedResult,
        sourceFile: point.sourceFile,
        precondition: ''
      }
    })),
    points,
    requirementAnalysisProjectName.value || '默认项目',
    generatedWebCases.value.length
  )
  generatedWebCases.value = [...generatedWebCases.value, ...generated.cases]
}

const mergeAcceptedReviewFinding = async (findingId: string, caseId: string) => {
  if (!aiMultiAgentRun.value) {
    ElMessage.warning('请先启动 AI 评审')
    return
  }
  const finding = caseDesignReviewFindings.value.find((item) => item.findingId === findingId)
  if (!finding) return
  const candidates = buildMultiAgentMergeCandidatesFromSuggestions([finding])
  if (!candidates.length) return
  aiMultiAgentLoading.value = true
  try {
    const result = await mergeAiMultiAgentRun(aiMultiAgentRun.value.runId, { candidates })
    aiMultiAgentMergeResult.value = result
    await loadMultiAgentReviewDetails(aiMultiAgentRun.value.runId)
    if (result.status === 'blocked') {
      ElMessage.warning(`仍有 ${result.unresolvedConflictCount} 个冲突未处理`)
      return
    }
    if (result.aiEnrichmentFallback) {
      ElMessage.warning('AI 增量补充不可用，已保留原评审候选')
    }
    const mergedRunPoints = await listAiDraftTestPoints(aiMultiAgentRun.value.runId)
    const existingIds = new Set(aiDraftPoints.value.map((point) => point.pointId))
    const appendedPoints = mergedRunPoints.items.filter((point) => !existingIds.has(point.pointId))
    if (appendedPoints.length) {
      aiDraftPoints.value = [...aiDraftPoints.value, ...appendedPoints]
      extractedRequirementPoints.value = aiDraftPoints.value.map(aiDraftPointToRequirementPoint)
      appendMergedPointsToGeneratedCases(appendedPoints)
      ElMessage.success(`已采纳并补充 ${appendedPoints.length} 条用例`)
    } else {
      ElMessage.info('该建议已补充到用例列表')
    }
  } catch (error) {
    const currentDecisions = caseReviewFindingDecisions.value[caseId]
    if (currentDecisions?.[findingId] === 'accepted') {
      caseReviewFindingDecisions.value = {
        ...caseReviewFindingDecisions.value,
        [caseId]: { ...currentDecisions, [findingId]: 'pending' }
      }
    }
    caseReviewError.value = error instanceof Error ? error.message : '采纳建议并补充用例失败'
    ElMessage.error(caseReviewError.value)
  } finally {
    aiMultiAgentLoading.value = false
  }
}
const persistCaseDesignDraft = () => {
  if (caseDesignDraftPersistenceSuspended.value) return
  const updatedAt = new Date().toISOString()
  const result = saveCaseDesignDraft(localStorage, {
    version: 1,
    activeStage: activeCaseDesignStage.value,
    generationKind: generationKind.value,
    projectName: requirementAnalysisProjectName.value,
    analysisRunId: aiAnalysisRun.value?.runId || '',
    naturalPlanRunId: naturalPlanRunId.value,
    analysisRun: aiAnalysisRun.value || null,
    requirementReviewRun: requirementAiReviewRun.value || null,
    caseReviewRun: caseAiReviewRun.value || null,
    activeReviewStage: activeAiReviewStage.value,
    reviewAttempted: caseReviewAttempted.value,
    selectedCaseId: selectedCase.value?.id || '',
    selectedGeneratedWebCaseIds: selectedGeneratedWebCaseIds.value,
    caseGenerationConfirmed: caseGenerationConfirmed.value,
    generationScopePointIds: generationScopePointIds.value,
    generationRetryPointIds: generationRetryPointIds.value,
    sources: stagedRequirementFiles.value.map((source) => ({
      name: source.name,
      kind: source.kind || inferRequirementSourceKind(source.name),
      text: source.text,
      needsReattach: Boolean(source.needsReattach)
    })),
    sourceErrors: requirementSourceErrors.value,
    extractedRequirementPoints: extractedRequirementPoints.value,
    aiDraftPoints: aiDraftPoints.value,
    generatedCases: generatedCases.value,
    generatedWebCases: generatedWebCases.value,
    analysisError: aiAnalysisError.value,
    generationError: aiGenerationError.value,
    reviewError: caseReviewError.value,
    review: {
      insight: activeAiReviewInsight.value,
      qualityScores: aiMultiAgentQualityScores.value,
      conflicts: aiMultiAgentConflicts.value,
      findingDecisions: caseReviewFindingDecisions.value
    },
    updatedAt
  })
  caseDesignDraftSaveError.value = result.error || ''
  if (result.saved) caseDesignLastSavedAt.value = updatedAt
}

watch([
  activeCaseDesignStage,
  generationKind,
  requirementAnalysisProjectName,
  stagedRequirementFiles,
  requirementSourceErrors,
  extractedRequirementPoints,
  aiDraftPoints,
  generatedCases,
  generatedWebCases,
  aiAnalysisRun,
  requirementAiReviewRun,
  caseAiReviewRun,
  activeAiReviewStage,
  selectedCase,
  aiMultiAgentQualityScores,
  aiMultiAgentConflicts,
  activeAiReviewInsight,
  aiAnalysisError,
  aiGenerationError,
  caseReviewError,
  caseReviewAttempted,
  caseReviewFindingDecisions,
  generationScopePointIds,
  generationRetryPointIds,
  selectedGeneratedWebCaseIds,
  caseGenerationConfirmed
], () => {
  if (caseDesignDraftPersistenceSuspended.value) return
  if (caseDesignDraftTimer) clearTimeout(caseDesignDraftTimer)
  caseDesignDraftTimer = setTimeout(persistCaseDesignDraft, 400)
}, { deep: true })
const aiDraftPointView = computed<AiDraftTestPointView>(() => buildAiDraftTestPointView(aiDraftPoints.value, {
  module: aiDraftFilter.module,
  status: aiDraftFilter.status,
  confidence: aiDraftFilter.confidence,
  sourceDocumentId: aiDraftFilter.sourceDocumentId
}))
const aiCoverageView = computed<AiAnalysisCoverageView>(() => buildAiAnalysisCoverageView({
  draftPoints: aiDraftPoints.value,
  warnings: aiCoverageWarnings.value,
  unclearQuestions: aiUnclearQuestions.value
}))
const aiCoverageWarnings = computed(() =>
  (aiCoverageSummary.value?.warnings || []).map((warning) => String(warning)).filter(Boolean)
)
const aiChunkProgressPercentage = computed(() => {
  if (!aiAnalysisRun.value || aiAnalysisRun.value.totalChunks <= 0) return 0
  return Math.min(100, Math.round((aiAnalysisRun.value.completedChunks / aiAnalysisRun.value.totalChunks) * 100))
})
const canGenerateAiWebCases = computed(() =>
  aiDraftPoints.value.some((point) => point.reviewStatus === 'confirmed' || point.reviewStatus === 'modified')
)
const confirmedAiDraftPoints = computed(() => aiDraftPoints.value.filter((point) =>
  point.reviewStatus === 'confirmed' || point.reviewStatus === 'modified'
))
const pendingAiDraftPoints = computed(() => aiDraftPoints.value.filter((point) =>
  point.reviewStatus !== 'confirmed'
  && point.reviewStatus !== 'modified'
  && point.reviewStatus !== 'rejected'
))
const scopedAiDraftPoints = computed(() => {
  const selected = new Set(generationScopePointIds.value)
  return confirmedAiDraftPoints.value.filter((point) => selected.has(point.pointId))
})
watch(
  () => confirmedAiDraftPoints.value.map((point) => point.pointId).join('|'),
  () => {
    const availableIds = confirmedAiDraftPoints.value.map((point) => point.pointId)
    generationScopePointIds.value = generationScopeAllSelected.value
      ? availableIds
      : generationScopePointIds.value.filter((pointId) => availableIds.includes(pointId))
  },
  { immediate: true }
)
const webRequirementFlowHint = computed(() => {
  if (!uploadedRequirementFileNames.value.length) {
    return '第一步：选择需求文件，或导入 Figma 链接准备需求来源'
  }
  if (aiAnalysisRunning.value) {
    return `AI分析需求进行中：${aiAnalysisProgress.text}`
  }
  if (!aiAnalysisCompleted.value) {
    return '下一步：点击 AI分析需求，生成待评审测试点'
  }
  if (aiCaseGenerationRunning.value) {
    return `AI用例生成进行中：${aiCaseGenerationProgress.text}`
  }
  if (!aiCaseGenerationCompleted.value) {
    if (!canGenerateAiWebCases.value && aiDraftPointView.value.items.length > 0) {
      return '下一步：先确认草稿测试点，必要时执行 AI评审，再点击 AI用例生成'
    }
    return '下一步：可执行 AI评审，然后点击 AI用例生成，生成最终用例内容'
  }
  return ''
})
const selectedDesignLocatorCards = computed(() => {
  if (!selectedCase.value || !('pageName' in selectedCase.value.row)) return []
  if (selectedCase.value.row.caseType !== '设计草案') return []
  return designLocatorCards(selectedCase.value as GeneratedWebAutomationCase)
})
const hasSelectedDesignDraftCase = computed(() =>
  !!selectedCase.value
  && 'pageName' in selectedCase.value.row
  && selectedCase.value.row.caseType === '设计草案'
)
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
const designOriginFailureItems = computed(() =>
  mapDesignOriginFailureItems(failedDesignOriginResults.value)
)
const designOriginFailureExpanded = ref(false)
const webPreviewModules = computed(() => webCaseSummary.value.modules)
const webPreviewCaseTypes = computed(() => webCaseSummary.value.caseTypes)
const filteredWebCases = computed(() =>
  generatedWebCases.value.filter((testCase) => {
    if (webPreviewFilter.module !== 'ALL' && testCase.row.module !== webPreviewFilter.module) return false
    if (webPreviewFilter.caseType !== 'ALL' && testCase.row.caseType !== webPreviewFilter.caseType) return false
    const keyword = webPreviewFilter.keyword.trim().toLowerCase()
    if (!keyword) return true
    return [
      testCase.row.caseId,
      testCase.row.title,
      testCase.row.pageName,
      testCase.row.startUrl,
      testCase.row.tags,
      testCase.row.sourceFile
    ]
      .join(' ')
      .toLowerCase()
      .includes(keyword)
  })
)

const currentNav = computed(() => resolveNavDisplay(activeNav.value, automationKind.value, generationKind.value))
const activeSidebarItemId = computed(() => resolveSidebarActiveItemId(
  activeNav.value,
  automationKind.value,
  generationKind.value
))
const sidebarBadges = computed<SidebarBadges>(() => ({}))
const generationTemplateOptions = computed(() =>
  templateOptions.value.filter((option) =>
    generationKind.value === 'interface'
      ? option.kind === 'interface'
      : option.kind === 'web_automation'
  )
)
const generationTemplate = computed<CaseTemplateDefinition | undefined>(() =>
  templateConfig.value.caseTemplates.find((template) => template.id === generationTemplateId.value)
    || templateConfig.value.caseTemplates.find((template) => template.id === generationTemplateOptions.value[0]?.value)
)
const generationTemplateFields = computed(() =>
  generationTemplate.value
    ? generationTemplate.value.builtIn
      ? templateConfig.value.fieldsByKind[generationTemplate.value.kind] || []
      : (templateConfig.value.fieldsByKind[generationTemplate.value.kind] || [])
          .filter((field) => generationTemplate.value?.fieldKeys.includes(field.key))
    : interfaceFields.value
)
const visibleWebAutomationFields = computed(() => webAutomationFields.value.filter((field) => field.visible))
const visibleEditableFields = computed(() => visibleInterfaceFields.value)

const automationCaseIndex = computed(() => buildAutomationCaseIndex(automationCases.value))
const automationProjects = computed(() => automationCaseIndex.value.projectOptions)
const webAutomationProjects = computed(() => collectWebAutomationProjects(webAutomationCases.value))
const activeAutomationProjects = computed(() =>
  automationKind.value === 'api' ? automationProjects.value : webAutomationProjects.value
)
const automationProjectRecords = computed(() =>
  automationFilter.project === 'ALL'
    ? automationCaseIndex.value.records
    : automationCaseIndex.value.records.filter((record) => record.project === automationFilter.project)
)
const automationProjectCases = computed(() => automationProjectRecords.value.map((record) => record.caseItem))
const webAutomationProjectCases = computed(() =>
  automationFilter.project === 'ALL'
    ? webAutomationCases.value
    : webAutomationCases.value.filter((testCase) => testCase.project === automationFilter.project)
)
const automationModules = computed(() => collectAutomationModules(automationProjectRecords.value))
const webAutomationModules = computed(() => collectWebAutomationModules(webAutomationProjectCases.value))
const activeAutomationModules = computed(() =>
  automationKind.value === 'api' ? automationModules.value : webAutomationModules.value
)
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
    label: environment.name
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
  Object.fromEntries(webAutomationCases.value.map((testCase) => [testCase.id, webAutomationResultStatus(testCase)]))
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
  new Map((webAutomationRunReport.value?.results || []).map((result) => [result.caseId, result]))
)
const activeWebRunView = computed(() =>
  activeWebRunSnapshot.value ? buildWebRunView(activeWebRunSnapshot.value) : undefined
)
const webEnvironmentBrowserOptions = computed(() => buildBrowserOptions(webRunCapabilities.value))
const activeWebEnvironmentBrowserLabel = computed(() => {
  const browser = runnableWebRuntime.value?.browser || 'follow-case'
  return webEnvironmentBrowserOptions.value.find((option) => option.value === browser)?.label || browser
})
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

const missingCaseTypeFindings = computed(() => reviewFindings.value.filter((finding) => finding.code === 'missing_case_type'))
const visibleInterfaceReviewFindings = computed(() => {
  const ignored = new Set(ignoredInterfaceReviewFindingIds.value)
  return reviewFindings.value.filter((finding) => !ignored.has(finding.id))
})
const visibleInterfaceReviewSummary = computed(() => ({
  total: visibleInterfaceReviewFindings.value.length,
  high: visibleInterfaceReviewFindings.value.filter((finding) => finding.severity === 'high').length,
  medium: visibleInterfaceReviewFindings.value.filter((finding) => finding.severity === 'medium').length,
  low: visibleInterfaceReviewFindings.value.filter((finding) => finding.severity === 'low').length,
  info: visibleInterfaceReviewFindings.value.filter((finding) => finding.severity === 'info').length
}))
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
  localStorage.setItem('ai-test-platform.sidebar-collapsed', String(railCollapsed.value))
}

const toggleStudioNavigation = () => {
  if (isNarrowViewport.value) {
    narrowNavigationVisible.value = !narrowNavigationVisible.value
    return
  }
  toggleRail()
}

const navigateToNav = (next: NavKey, historyMode: NavHistoryMode = 'push') => {
  activeNav.value = next

  if (typeof window === 'undefined' || historyMode === 'skip') return

  const targetPath = next === 'automation'
    ? automationPathFor(automationKind.value)
    : next === 'api'
      ? caseDesignPathFor(generationKind.value)
      : navPathFor(next)
  if (window.location.pathname === targetPath) return

  const method = historyMode === 'replace' ? 'replaceState' : 'pushState'
  window.history[method]({}, '', targetPath)
}

const selectSidebarItem = (item: SidebarItem) => {
  if (item.caseDesignKind) {
    generationKind.value = item.caseDesignKind
  }

  if (item.automationKind) {
    automationKind.value = item.automationKind
  }

  if (item.navKey) navigateToNav(item.navKey)
}

/** 首页阶段模块按钮 → 精确落位(带 kind 先切子视图再导航)。 */
const handleHomeNavigate = (target: HomeNavigateTarget) => {
  if (target.caseDesignKind) generationKind.value = target.caseDesignKind
  if (target.automationKind) automationKind.value = target.automationKind
  navigateToNav(target.navKey)
}

const expandStudioNavigation = () => {
  railCollapsed.value = false
  localStorage.setItem('ai-test-platform.sidebar-collapsed', 'false')
}

const syncNavWithLocation = (historyMode: Exclude<NavHistoryMode, 'push'> = 'replace') => {
  if (typeof window === 'undefined') return

  const pathname = normalizeNavPath(window.location.pathname)
  const next = navKeyFromPath(pathname)
  if (next === 'automation') automationKind.value = automationKindFromPath(pathname)
  if (next === 'api') generationKind.value = caseDesignKindFromPath(pathname)
  const targetPath = next === 'automation'
    ? automationPathFor(automationKind.value)
    : next === 'api'
      ? caseDesignPathFor(generationKind.value)
      : navPathFor(next)
  navigateToNav(next, pathname === targetPath ? 'skip' : historyMode)
}

const onFileSelected = async (event: Event) => {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  if (files.length === 0) return

  try {
    await importOpenApiFiles(files)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '导入失败')
  } finally {
    input.value = ''
  }
}

const pickOpenApiFiles = async () => {
  const chooserWindow = window as Window & {
    showOpenFilePicker?: (options?: unknown) => Promise<Array<{ getFile: () => Promise<File> }>>
  }

  if (typeof chooserWindow.showOpenFilePicker === 'function') {
    try {
      const handles = await chooserWindow.showOpenFilePicker({
        multiple: true,
        types: [
          {
            description: 'OpenAPI JSON',
            accept: {
              'application/json': ['.json']
            }
          }
        ]
      })
      const files = await Promise.all(handles.map((handle) => handle.getFile()))
      if (files.length > 0) {
        await importOpenApiFiles(files)
      }
      return
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') return
    }
  }

  openApiFileInput.value?.click()
}

const onWebCaseFileSelected = async (event: Event) => {
  const input = event.target as HTMLInputElement
  const pickOptions = pendingRequirementSourcePick.value
  pendingRequirementSourcePick.value = undefined
  const selectedFiles = Array.from(input.files || [])
  const files = pickOptions?.replaceName ? selectedFiles.slice(0, 1) : selectedFiles
  if (files.length === 0) return

  try {
    await importWebCaseFiles(files, pickOptions)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '导入 Web 自动化用例失败')
  } finally {
    input.value = ''
  }
}

const pickRequirementSourceFiles = async (options: RequirementSourcePickOptions = {}) => {
  const chooserWindow = window as Window & {
    showOpenFilePicker?: (options?: unknown) => Promise<Array<{ getFile: () => Promise<File> }>>
  }

  if (typeof chooserWindow.showOpenFilePicker === 'function') {
    try {
      const handles = await chooserWindow.showOpenFilePicker({
        multiple: !options.replaceName,
        types: requirementSourceFilePickerTypes(options.kind)
      })
      const files = await Promise.all(handles.map((handle) => handle.getFile()))
      if (files.length > 0) {
        await importWebCaseFiles(files, options)
      }
      return
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') return
    }
  }

  pendingRequirementSourcePick.value = options
  await nextTick()
  webCaseFileInput.value?.click()
}

const pickWebCaseFiles = async () => pickRequirementSourceFiles()

const requirementSourceFilePickerTypes = (kind: PersistedRequirementSourceKind | undefined) => {
  if (kind === 'openapi') {
    return [{
      description: 'OpenAPI JSON',
      accept: { 'application/json': ['.json'] }
    }]
  }
  return [{
    description: '需求文件',
    accept: {
      'text/markdown': ['.md', '.markdown'],
      'application/json': ['.json'],
      'application/x-yaml': ['.yaml', '.yml'],
      'application/pdf': ['.pdf'],
      'application/msword': ['.doc'],
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document': ['.docx'],
      'application/vnd.ms-excel': ['.xls'],
      'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': ['.xlsx'],
      'text/csv': ['.csv'],
      'text/plain': ['.txt'],
      'image/png': ['.png'],
      'image/jpeg': ['.jpg', '.jpeg'],
      'image/webp': ['.webp'],
      'image/gif': ['.gif'],
      'image/bmp': ['.bmp']
    }
  }]
}

const importOpenApiFiles = async (files: File[]) => {
  const payloads = await Promise.all(
    files.map(async (file) => ({
      name: file.name,
      json: JSON.parse(await file.text())
    }))
  )
  importOpenApiPayloads(payloads)
}

const promptFigmaDesignImport = async (replacementSourceName?: string) => {
  beginCaseDesignTask()
  try {
    const { value } = await ElMessageBox.prompt('请输入 Figma 设计链接', '导入 Figma 链接', {
      confirmButtonText: '导入',
      cancelButtonText: '取消',
      inputPlaceholder: 'https://www.figma.com/design/...'
    })
    if (!value?.trim()) return

    const draft = await parseFigmaDesignInput(value.trim())
    generationKind.value = 'web_automation'
    const requirementText = buildRequirementImportText(draft)
    upsertStagedRequirementSources(
      [{
        name: replacementSourceName || FIGMA_REQUIREMENT_FILE_NAME,
        text: requirementText,
        kind: 'figma',
        needsReattach: false
      }],
      [new File([requirementText], replacementSourceName || FIGMA_REQUIREMENT_FILE_NAME, { type: 'text/plain' })],
      {},
      Boolean(replacementSourceName)
    )
    if (!replacementSourceName) requirementAnalysisProjectName.value = draft.projectName || '默认项目'
    await importStructuredAiFigma(value.trim(), requirementAnalysisProjectName.value)
    ElMessage.success('已导入 Figma 设计链接，请继续执行 AI 分析')
  } catch {
    return
  }
}

const importStructuredAiFigma = async (url: string, projectName: string) => {
  aiFigmaLoading.value = true
  try {
    const project = await ensureBackendProject(projectName || '默认项目')
    const imported = await importAiFigmaFile({ projectId: project.id, figmaUrl: url })
    aiFigmaFile.value = imported
    await reloadAiFigmaWorkspace(imported.figmaFileId)
    ElMessage.success('已导入结构化 Figma 节点')
  } catch (error) {
    aiFigmaFile.value = undefined
    aiFigmaNodes.value = { items: [] }
    aiFigmaAnalysisRun.value = undefined
    aiFigmaDraftPoints.value = []
    aiFigmaChangeSets.value = []
    aiFigmaElementCandidates.value = { items: [] }
    selectedAiFigmaNodeId.value = ''
    console.warn('Structured Figma import unavailable', error)
  } finally {
    aiFigmaLoading.value = false
  }
}

const reloadAiFigmaWorkspace = async (figmaFileId: string) => {
  const [nodes, changeSets, candidates] = await Promise.all([
    listAiFigmaNodes(figmaFileId),
    listAiFigmaChangeSets(figmaFileId),
    listAiFigmaElementCandidates(figmaFileId)
  ])
  aiFigmaNodes.value = nodes
  aiFigmaChangeSets.value = changeSets.items
  aiFigmaElementCandidates.value = candidates
  selectedAiFigmaNodeId.value = nodes.items[0]?.figmaNodeId || ''
}

const analyzeAiFigmaFile = async () => {
  if (!aiFigmaFile.value) {
    ElMessage.warning('请先导入 Figma 链接')
    return
  }
  aiFigmaLoading.value = true
  try {
    const run = await startAiFigmaFileAnalysis(aiFigmaFile.value.figmaFileId)
    aiFigmaAnalysisRun.value = run
    const draftList = await listAiFigmaDraftPoints(run.runId)
    aiFigmaDraftPoints.value = draftList.items
    ElMessage.success(`Figma 已生成 ${draftList.items.length} 条草稿测试点`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Figma 分析失败')
  } finally {
    aiFigmaLoading.value = false
  }
}

const analyzeSelectedAiFigmaNode = async () => {
  if (!selectedAiFigmaNodeId.value) {
    ElMessage.warning('请先选择 Figma 节点')
    return
  }
  aiFigmaLoading.value = true
  try {
    const run = await startAiFigmaNodeAnalysis(selectedAiFigmaNodeId.value)
    aiFigmaAnalysisRun.value = run
    const draftList = await listAiFigmaDraftPoints(run.runId)
    aiFigmaDraftPoints.value = draftList.items
    ElMessage.success(`Figma 节点已生成 ${draftList.items.length} 条草稿测试点`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Figma 节点分析失败')
  } finally {
    aiFigmaLoading.value = false
  }
}

const parseFigmaDesignInput = async (url: string) => {
  try {
    const parsed = await parseFigmaDesignWithBackend(url)
    return {
      ...parsed,
      designDraftCases: toRequirementDesignDraftCases(buildDesignAutomationDraftCases(parsed))
    }
  } catch {
    const draft = buildFigmaDesignDraftFromUrl(url)
    return {
      ...draft,
      designDraftCases: toRequirementDesignDraftCases(buildDesignAutomationDraftCases(draft))
    }
  }
}

const copyText = async (value: string, message: string) => {
  await navigator.clipboard.writeText(value)
  ElMessage.success(message)
}

const toggleDesignLocatorConfirmed = (locatorId: string) => {
  if (!selectedCase.value || !('pageName' in selectedCase.value.row)) return
  const webCase = selectedCase.value as GeneratedWebAutomationCase
  const locator = webCase.metadata.locators.find((item) => item.id === locatorId)
  if (!locator) return
  locator.confirmed = !locator.confirmed
  if (isWebEditableCase(webCase)) {
    saveGeneratedWebAutomationCases(localStorage, [webCase])
  }
}

const importWebCaseFiles = async (
  files: File[],
  options: RequirementSourcePickOptions = {}
) => {
  beginCaseDesignTask()
  const settled = await Promise.allSettled(files.map(async (file) => ({
    file,
    text: await readRequirementSourceText(file, options.kind)
  })))
  const payloads: StagedRequirementFile[] = []
  const sourceFiles: File[] = []
  const sourceErrors: Record<string, string> = {}

  settled.forEach((result, index) => {
    const originalFile = files[index]
    const name = options.replaceName || originalFile.name
    const kind = options.kind || inferRequirementSourceKind(name)
    if (result.status === 'fulfilled') {
      payloads.push({ name, text: result.value.text, kind, needsReattach: false })
      sourceFiles.push(new File([originalFile], name, { type: originalFile.type }))
      return
    }
    payloads.push({ name, text: '', kind, needsReattach: false })
    sourceFiles.push(new File([originalFile], name, { type: originalFile.type }))
    sourceErrors[name] = presentRequirementSourceError(result.reason, kind)
  })

  if (payloads.length === 0) return
  generationKind.value = 'web_automation'
  upsertStagedRequirementSources(payloads, sourceFiles, sourceErrors, options.localRecovery === true)
  if (!options.localRecovery) requirementAnalysisProjectName.value = '默认项目'

  const readyCount = payloads.length - Object.keys(sourceErrors).length
  if (readyCount > 0) {
    ElMessage.success(
      options.localRecovery
        ? `已恢复 ${readyCount} 个来源，其他分析结果保持不变`
        : `已上传 ${readyCount} 个需求文件，请继续执行 AI 分析`
    )
  }
  if (Object.keys(sourceErrors).length > 0) {
    ElMessage.error(`有 ${Object.keys(sourceErrors).length} 个来源无法读取，可单独重试或替换`)
  }
}

const readRequirementSourceText = async (
  file: File,
  kind: PersistedRequirementSourceKind | undefined
): Promise<string> => {
  const text = await readRequirementFileText(file)
  if (kind !== 'openapi') return text
  try {
    parseOpenApiDocument(JSON.parse(text), file.name)
    return text
  } catch (error) {
    throw new Error(error instanceof Error ? error.message : 'OpenAPI 文件格式无效')
  }
}

const presentRequirementSourceError = (error: unknown, kind: PersistedRequirementSourceKind): string => {
  const message = error instanceof Error ? error.message : '文件读取失败'
  return kind === 'openapi' ? `OpenAPI 导入失败：${message}` : `来源读取失败：${message}`
}

const upsertStagedRequirementSources = (
  payloads: StagedRequirementFile[],
  files: File[],
  sourceErrors: Record<string, string> = {},
  preserveWorkflow = false
) => {
  const incomingNames = new Set(payloads.map((file) => file.name))
  stagedRequirementFiles.value = [
    ...stagedRequirementFiles.value.filter((file) => !incomingNames.has(file.name)),
    ...payloads
  ]
  stagedRequirementSourceFiles.value = [
    ...stagedRequirementSourceFiles.value.filter((file) => !incomingNames.has(file.name)),
    ...files
  ]
  requirementSourceErrors.value = {
    ...Object.fromEntries(Object.entries(requirementSourceErrors.value).filter(([name]) => !incomingNames.has(name))),
    ...sourceErrors
  }
  if (preserveWorkflow) return
  extractedRequirementPoints.value = []
  resetAiRequirementAnalysis()
  generatedWebCases.value = []
  selectedCase.value = undefined
  resetCaseGenerationSelectionState()
  webPreviewFilter.module = 'ALL'
  webPreviewFilter.caseType = 'ALL'
  webPreviewFilter.keyword = ''
  resetWebRequirementFlowProgress()
}

const retryRequirementSource = async (name: string) => {
  const source = stagedRequirementFiles.value.find((item) => item.name === name)
  if (!source) return
  const kind = source.kind || inferRequirementSourceKind(source.name)
  const originalFile = stagedRequirementSourceFiles.value.find((file) => file.name === name)
  if (source.needsReattach || !originalFile) {
    await replaceRequirementSource(name)
    return
  }
  await importWebCaseFiles([originalFile], { replaceName: name, kind, localRecovery: true })
}

const replaceRequirementSource = async (name: string) => {
  const source = stagedRequirementFiles.value.find((item) => item.name === name)
  if (!source) return
  const kind = source.kind || inferRequirementSourceKind(source.name)
  if (kind === 'figma') {
    await promptFigmaDesignImport(name)
    return
  }
  await pickRequirementSourceFiles({ replaceName: name, kind, localRecovery: true })
}

const removeRequirementSource = (name: string) => {
  stagedRequirementFiles.value = stagedRequirementFiles.value.filter((source) => source.name !== name)
  stagedRequirementSourceFiles.value = stagedRequirementSourceFiles.value.filter((file) => file.name !== name)
  requirementSourceErrors.value = Object.fromEntries(
    Object.entries(requirementSourceErrors.value).filter(([sourceName]) => sourceName !== name)
  )
  ElMessage.success(`已移除来源 ${name}，其他分析结果保持不变`)
}

const resetWebRequirementFlowProgress = () => {
  webRequirementFlowDismissed.value = false
  aiAnalysisProgress.percentage = 0
  aiAnalysisProgress.text = stagedRequirementFiles.value.length > 0 ? '等待点击 AI分析需求' : '等待上传需求文档'
  aiCaseGenerationProgress.percentage = 0
  aiCaseGenerationProgress.text = aiAnalysisCompleted.value ? '等待点击 AI用例生成' : '等待 AI 分析需求完成'
  aiCaseGenerationRunning.value = false
}

const resetCaseGenerationSelectionState = () => {
  generationScopePointIds.value = []
  generationScopeAllSelected.value = true
  generationRetryPointIds.value = []
  selectedGeneratedWebCaseIds.value = []
  naturalPlanRunId.value = ''
}

const setGenerationScopeAll = (checked: boolean) => {
  generationScopeAllSelected.value = checked
  generationScopePointIds.value = checked
    ? confirmedAiDraftPoints.value.map((point) => point.pointId)
    : []
}

const setGenerationScopePointIds = (pointIds: string[]) => {
  generationScopePointIds.value = pointIds
}

const setAiAnalysisProgress = (percentage: number, text: string) => {
  aiAnalysisProgress.percentage = percentage
  aiAnalysisProgress.text = text
}

const setAiCaseGenerationProgress = (percentage: number, text: string) => {
  aiCaseGenerationProgress.percentage = percentage
  aiCaseGenerationProgress.text = text
}

const presentAiWorkflowError = (error: unknown, fallback: string) => {
  const message = error instanceof Error ? error.message : fallback
  if (/timed out|timeout|超时/i.test(message)) {
    return `${fallback}：请求超时，请稍后重试或在 AI 配置中提高超时时间`
  }
  return message
}

const AI_ANALYSIS_POLL_INTERVAL_MS = 1500
const AI_ANALYSIS_MAX_CONSECUTIVE_FAILURES = 5
const AI_ANALYSIS_HEARTBEAT_STALE_MS = 45 * 1000

const analysisProgressTextWithHeartbeat = (run: AiAnalysisRun, fallback: string): string => {
  if (run.status !== 'running' || !run.heartbeatAt) return fallback
  const heartbeatAt = Date.parse(run.heartbeatAt)
  if (Number.isNaN(heartbeatAt)) return fallback
  const ageSeconds = Math.max(0, Math.round((Date.now() - heartbeatAt) / 1000))
  if (ageSeconds > AI_ANALYSIS_HEARTBEAT_STALE_MS / 1000) {
    return `${fallback} · 暂未收到新的工作器心跳，可继续等待或取消`
  }
  return `${fallback} · 后端仍在等待模型响应（${ageSeconds} 秒前心跳）`
}

/**
 * Polls an AI analysis run until it reaches a terminal status, streaming the
 * AI's processing actions (chunk progress, draft points, model calls) into the
 * progress bar + live log. Resolves with the terminal run on success, throws
 * (so the caller surfaces `presentAiWorkflowError`) on failure/cancel, and
 * keeps polling while the task remains active, including intentionally long-running model calls.
 * A newer run supersedes an in-flight poll via the coordinator token.
 */
const monitorAiAnalysisRun = async (runId: string, prefixLog: AiAnalysisLogEntry[] = []): Promise<AiAnalysisRun | null> => {
  const poll = aiAnalysisPollCoordinator.start(runId)
  if (!poll.shouldStart) return null
  const state = createAnalysisProgressState()
  state.log = [...prefixLog]
  state.seq = prefixLog.length
  let consecutiveFailures = 0
  try {
    while (aiAnalysisPollCoordinator.isCurrent(runId, poll.token)) {
      let run!: AiAnalysisRun
      try {
        const [fetchedRun, draftList, callList, eventList] = await Promise.all([
          getAiAnalysisRun(runId),
          listAiDraftTestPoints(runId),
          listAiCallRecords(runId),
          listAiAnalysisRunEvents(runId).catch(() => null)
        ])
        if (!aiAnalysisPollCoordinator.isCurrent(runId, poll.token)) return fetchedRun
        consecutiveFailures = 0
        run = fetchedRun
        aiAnalysisRun.value = run
        aiDraftPoints.value = draftList.items
        extractedRequirementPoints.value = draftList.items.map(aiDraftPointToRequirementPoint)
        const view = applyAnalysisSnapshot(state, {
          run,
          draftPoints: draftList.items,
          callRecords: callList.items
        })
        setAiAnalysisProgress(
          Math.max(aiAnalysisProgress.percentage, view.percentage),
          analysisProgressTextWithHeartbeat(run, view.text)
        )
        aiAnalysisLog.value = eventList && eventList.items.length > 0
          ? [...prefixLog, ...mapRunEventsToLog(eventList.items)]
          : view.log
      } catch (error) {
        if (!aiAnalysisPollCoordinator.isCurrent(runId, poll.token)) throw error
        consecutiveFailures += 1
        if (consecutiveFailures >= AI_ANALYSIS_MAX_CONSECUTIVE_FAILURES) throw error
        await delayWebRunPoll(Math.min(6000, AI_ANALYSIS_POLL_INTERVAL_MS * consecutiveFailures))
        continue
      }
      if (isTerminalAnalysisStatus(run.status)) {
        await loadAiAnalysisDetails(runId)
        if (run.status === 'failed' || run.status === 'cancelled') {
          throw new Error(run.errorSummary || 'AI 分析需求失败')
        }
        return run
      }
      await delayWebRunPoll(AI_ANALYSIS_POLL_INTERVAL_MS)
    }
  } finally {
    if (aiAnalysisPollCoordinator.isCurrent(runId, poll.token)) aiAnalysisPollCoordinator.stop(runId)
  }
  return aiAnalysisRun.value ?? null
}

const cancelCurrentAiAnalysisRun = async () => {
  if (!aiAnalysisRun.value || !aiAnalysisRunning.value) return
  try {
    const run = await cancelAiAnalysisRun(aiAnalysisRun.value.runId)
    aiAnalysisRun.value = run
    aiAnalysisError.value = '分析任务已取消'
    setAiAnalysisProgress(aiAnalysisProgress.percentage, '分析任务已取消')
    ElMessage.info('已请求取消分析任务')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '取消 AI 分析任务失败')
  }
}

const checkAiEnvironmentBeforeAnalysis = async (): Promise<{ ready: boolean; logs: AiAnalysisLogEntry[] }> => {
  const checkingLog: AiAnalysisLogEntry = {
    id: 'ai-environment-check-pending',
    kind: 'info',
    text: '正在检查 Platform AI 环境…'
  }
  aiAnalysisLog.value = [checkingLog]
  setAiAnalysisProgress(4, '正在检查 AI 环境')
  try {
    const [config, policies] = await Promise.all([
      loadAiConfigFromPlatform(),
      listAiRunPolicies()
    ])
    applyAiConfig(config)
    aiRunPolicies.value = policies
    aiRunPoliciesError.value = ''
    const readiness = checkAiEnvironmentReadiness(config, policies.items.find((policy) => policy.taskType === 'requirement_analysis'))
    aiAnalysisLog.value = readiness.logs
    if (!readiness.ready) {
      aiAnalysisError.value = readiness.message
      setAiAnalysisProgress(0, 'AI 环境未就绪，未开始分析')
      ElMessage.error(readiness.message)
    }
    return { ready: readiness.ready, logs: readiness.logs }
  } catch (error) {
    const detail = error instanceof Error ? error.message : '无法读取 AI 环境配置'
    const message = /404/.test(detail)
      ? 'Platform AI 未就绪：运行策略接口不可用，请先重启后端服务'
      : `Platform AI 未就绪：${detail}`
    const failureLog: AiAnalysisLogEntry = {
      id: 'ai-environment-check-failed',
      kind: 'error',
      text: message
    }
    aiAnalysisLog.value = [checkingLog, failureLog]
    aiAnalysisError.value = message
    setAiAnalysisProgress(0, 'AI 环境检查失败，未开始分析')
    ElMessage.error(message)
    return { ready: false, logs: aiAnalysisLog.value }
  }
}

const analyzeRequirementPointsWithAi = async () => {
  if (!canAnalyzeRequirements.value) {
    ElMessage.warning('请先上传可用的需求来源')
    return
  }
  aiAnalysisRunning.value = true
  aiAnalysisError.value = ''
  try {
    const readiness = await checkAiEnvironmentBeforeAnalysis()
    if (!readiness.ready) return
    setAiAnalysisProgress(8, '准备上传需求文档')
    const project = await ensureBackendProject(requirementAnalysisProjectName.value || '默认项目')
    const sourceFiles = requirementSourceFilesForAiUpload()
    const uploaded = []
    setAiAnalysisProgress(22, `正在上传 ${sourceFiles.length} 个需求文档`)
    for (const file of sourceFiles) {
      uploaded.push(await uploadAiSourceDocument(project.id, file))
    }
    aiSourceDocuments.value = uploaded
    setAiAnalysisProgress(55, '已上传文档，正在创建 AI 分析任务')
    const run = await createAiAnalysisRun({
      projectId: project.id,
      documentIds: uploaded.map((document) => document.documentId),
      targetCaseKind: 'web_automation',
      templateId: generationTemplateId.value,
      analysisStrategy: 'full'
    })
    aiAnalysisRun.value = run
    const finishedRun = await monitorAiAnalysisRun(run.runId, readiness.logs)
    if (!finishedRun) return
    completeAiRequirementAnalysis('AI分析需求完成')
  } catch (error) {
    setAiAnalysisProgress(0, 'AI分析需求未完成')
    aiAnalysisError.value = presentAiWorkflowError(error, 'AI 分析需求失败')
    ElMessage.error(aiAnalysisError.value)
  } finally {
    aiAnalysisRunning.value = false
  }
}

const completeAiRequirementAnalysis = (progressPrefix: string) => {
  generatedWebCases.value = []
  selectedCase.value = undefined
  resetCaseGenerationSelectionState()

  const draftPointCount = aiDraftPoints.value.length
  setAiAnalysisProgress(100, `${progressPrefix}，共生成 ${draftPointCount} 条草稿测试点`)
  setAiCaseGenerationProgress(0, '等待确认草稿测试点并点击 AI用例生成')

  // The parsed points are the generation input. Persist the completed state now
  // so a refresh cannot strand the user on the parsing step or lose the draft.
  if (draftPointCount > 0) activeCaseDesignStage.value = 'test-parse'
  if (caseDesignDraftTimer) clearTimeout(caseDesignDraftTimer)
  persistCaseDesignDraft()

  ElMessage.success(
    draftPointCount > 0
      ? `AI 已生成 ${draftPointCount} 条草稿测试点，已进入测试解析`
      : '内容解析完成，但未提取到可生成的测试点'
  )
}

const loadAiAnalysisDetails = async (runId: string) => {
  aiAnalysisDetailLoading.value = true
  try {
    const [coverage, questions, toolCalls] = await Promise.allSettled([
      getAiAnalysisCoverageSummary(runId),
      listAiAnalysisUnclearQuestions(runId),
      listAiAnalysisToolCalls(runId)
    ])
    aiCoverageSummary.value = coverage.status === 'fulfilled' ? coverage.value : undefined
    aiUnclearQuestions.value = questions.status === 'fulfilled' ? questions.value.items : []
    aiToolCalls.value = toolCalls.status === 'fulfilled' ? toolCalls.value.items : []
  } finally {
    aiAnalysisDetailLoading.value = false
  }
}

const startRequirementAiReview = async () => {
  if (stagedRequirementFiles.value.length === 0 && aiDraftPoints.value.length === 0 && extractedRequirementPoints.value.length === 0) {
    ElMessage.warning('请先完成 AI分析需求或准备需求内容')
    return
  }
  await startAiReview('requirement')
}

const startCaseAiReview = async () => {
  if (generatedWebCases.value.length === 0) {
    ElMessage.warning('请先完成自然语言用例生成')
    return
  }
  caseReviewAttempted.value = true
  await startAiReview('case')
}

const invalidateCaseReview = () => {
  caseAiReviewRun.value = undefined
  activeAiReviewStage.value = 'case'
  aiMultiAgentConflicts.value = []
  aiMultiAgentQualityScores.value = []
  aiMultiAgentMergeResult.value = undefined
  caseReviewAttempted.value = false
  caseReviewFindingDecisions.value = {}
  restoredAiReviewInsight.value = undefined
  caseReviewError.value = ''
  if (activeCaseDesignStage.value === 'deliver' || activeCaseDesignStage.value === 'automation-compile') activeCaseDesignStage.value = 'review'
}

const startAiReview = async (stage: 'requirement' | 'case') => {
  activeAiReviewStage.value = stage
  aiMultiAgentLoading.value = true
  try {
    const existingProjectId = webCaseAgentRun.value?.projectId
      || aiAnalysisRun.value?.projectId
      || aiDraftPoints.value[0]?.projectId
    const projectId = existingProjectId
      || (await ensureBackendProject(requirementAnalysisProjectName.value || '默认项目')).id
    const reviewStage = stage
    const run = await startAiMultiAgentRun({
      projectId,
      targetCaseKind: 'web_automation',
      inputSummary: {
        reviewStage,
        artifacts: stage === 'case' ? aiReviewCaseArtifacts() : aiReviewRequirementArtifacts(),
        sourceFiles: stagedRequirementFiles.value.map((file) => file.name),
        draftPointCount: aiDraftPoints.value.length,
        requirementPointCount: extractedRequirementPoints.value.length,
        generatedCaseCount: generatedWebCases.value.length
      }
    })
    if (stage === 'case') {
      caseAiReviewRun.value = run
    } else {
      requirementAiReviewRun.value = run
    }
    aiMultiAgentMergeResult.value = undefined
    await loadMultiAgentReviewDetails(run.runId)
    restoredAiReviewInsight.value = undefined
    if (stage === 'case') activeCaseDesignStage.value = 'review'
    const effectiveRun = stage === 'case' ? caseAiReviewRun.value || run : run
    const effectiveStatus = reviewRunStatusToWorkflowStatus(effectiveRun.status)
    if (stage === 'case' && effectiveStatus === 'failed') {
      caseReviewError.value = effectiveRun.errorSummary || 'AI 质量评审未完成'
      ElMessage.warning(caseReviewError.value)
    } else if (stage === 'case' && effectiveStatus === 'running') {
      caseReviewError.value = ''
      ElMessage.info('用例阶段 AI 评审已启动')
    } else {
      if (stage === 'case') caseReviewError.value = ''
      ElMessage.success(`${stage === 'case' ? '用例阶段' : '需求阶段'}AI评审已完成`)
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : 'AI评审失败'
    if (stage === 'case') caseReviewError.value = message
    ElMessage.error(message)
  } finally {
    aiMultiAgentLoading.value = false
  }
}

const aiReviewRequirementArtifacts = () => {
  if (aiDraftPoints.value.length > 0) {
    return aiDraftPoints.value.map((point) => ({
      pointId: point.pointId,
      title: point.title,
      module: point.module,
      pageOrFeature: point.pageOrFeature,
      objective: point.objective,
      steps: point.steps,
      expectedResult: point.expectedResult,
      reviewStatus: point.reviewStatus,
      sourceFile: point.sourceFile
    }))
  }
  return extractedRequirementPoints.value.map((point) => ({
    title: point.title,
    module: point.module,
    pageName: point.pageName,
    sourceFile: point.sourceFile
  }))
}

const aiReviewCaseArtifacts = () =>
  generatedWebCases.value.map((testCase) => ({
    caseId: testCase.row.caseId,
    title: testCase.row.title,
    module: testCase.row.module,
    pageName: testCase.row.pageName,
    caseType: testCase.row.caseType,
    startUrl: testCase.row.startUrl,
    steps: testCase.metadata.steps.map((step) => `${step.name || step.action} ${step.target}`.trim()).join('\n'),
    expectedResult: testCase.row.assertions,
    sourceFile: testCase.row.sourceFile
  }))

const loadMultiAgentReviewDetails = async (runId: string) => {
  const [graph, conflicts, scores] = await Promise.allSettled([
    listAiMultiAgentGraph(runId),
    listAiMultiAgentConflicts(runId),
    listAiMultiAgentQualityScores(runId)
  ])
  if (graph.status === 'fulfilled') {
    if (requirementAiReviewRun.value?.runId === runId) {
      requirementAiReviewRun.value = {
        ...requirementAiReviewRun.value,
        status: graph.value.status,
        inputSnapshot: graph.value.inputSnapshot,
        outputSnapshot: graph.value.outputSnapshot,
        errorSummary: graph.value.errorSummary
      }
    }
    if (caseAiReviewRun.value?.runId === runId) {
      caseAiReviewRun.value = {
        ...caseAiReviewRun.value,
        status: graph.value.status,
        inputSnapshot: graph.value.inputSnapshot,
        outputSnapshot: graph.value.outputSnapshot,
        errorSummary: graph.value.errorSummary
      }
    }
  }
  if (conflicts.status === 'fulfilled') aiMultiAgentConflicts.value = conflicts.value.items
  if (scores.status === 'fulfilled') aiMultiAgentQualityScores.value = scores.value.items
}

const ignoreUnresolvedMultiAgentConflicts = async () => {
  if (!aiMultiAgentRun.value) return
  const unresolved = aiMultiAgentConflicts.value.filter((conflict) => conflict.resolutionStatus === 'unresolved')
  if (unresolved.length === 0) {
    ElMessage.info('暂无未处理冲突')
    return
  }
  aiMultiAgentLoading.value = true
  try {
    await Promise.all(unresolved.map((conflict) =>
      ignoreAiMultiAgentConflict(conflict.conflictId, '前端评审选择忽略该冲突，继续合并')
    ))
    await loadMultiAgentReviewDetails(aiMultiAgentRun.value.runId)
    ElMessage.success(`已忽略 ${unresolved.length} 个未处理冲突`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '处理冲突失败')
  } finally {
    aiMultiAgentLoading.value = false
  }
}

const ignoreSingleReviewConflict = async (conflictId: string) => {
  try {
    await ignoreAiMultiAgentConflict(conflictId, '前端评审选择忽略该冲突')
    if (caseAiReviewRun.value) await loadMultiAgentReviewDetails(caseAiReviewRun.value.runId)
  } catch (error) {
    caseReviewError.value = error instanceof Error ? error.message : '处理评审冲突失败'
    ElMessage.error(caseReviewError.value)
  }
}

const mergeMultiAgentReview = async () => {
  if (!aiMultiAgentRun.value) {
    ElMessage.warning('暂无可合并的 AI评审')
    return
  }
  if (!canMergeMultiAgentReview.value) {
    ElMessage.warning(
      aiMultiAgentUnresolvedConflicts.value > 0
        ? '请先处理未解决的冲突'
        : '暂无可合并的测试点'
    )
    return
  }
  const candidates = buildMultiAgentMergeCandidatesFromSuggestions(aiReviewInsight.value.suggestions)
  if (candidates.length === 0) {
    ElMessage.warning('暂无可合并的测试点')
    return
  }
  aiMultiAgentLoading.value = true
  try {
    const result = await mergeAiMultiAgentRun(aiMultiAgentRun.value.runId, { candidates })
    aiMultiAgentMergeResult.value = result
    await loadMultiAgentReviewDetails(aiMultiAgentRun.value.runId)
    if (result.status === 'blocked') {
      ElMessage.warning(`仍有 ${result.unresolvedConflictCount} 个冲突未处理`)
      return
    }
    if (result.aiEnrichmentFallback) {
      ElMessage.warning('AI 增量补充不可用，已保留原评审候选')
    }
    const mergedRunPoints = await listAiDraftTestPoints(aiMultiAgentRun.value.runId)
    const existingIds = new Set(aiDraftPoints.value.map((point) => point.pointId))
    const appendedPoints = mergedRunPoints.items.filter((point) => !existingIds.has(point.pointId))
    if (appendedPoints.length) {
      aiDraftPoints.value = [...aiDraftPoints.value, ...appendedPoints]
      extractedRequirementPoints.value = aiDraftPoints.value.map(aiDraftPointToRequirementPoint)
      appendMergedPointsToGeneratedCases(appendedPoints)
    }
    ElMessage.success(`已合并 ${result.mergedCount} 个测试点到待评审草稿`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '对齐补充失败')
  } finally {
    aiMultiAgentLoading.value = false
  }
}

const refreshAiAnalysisRunData = async (runId: string) => {
  const [run, draftList] = await Promise.all([
    getAiAnalysisRun(runId),
    listAiDraftTestPoints(runId)
  ])
  aiAnalysisRun.value = run
  aiDraftPoints.value = draftList.items
  extractedRequirementPoints.value = draftList.items.map(aiDraftPointToRequirementPoint)
  await loadAiAnalysisDetails(runId)
}

const resumeCurrentAiAnalysisRun = async () => {
  if (!aiAnalysisRun.value) return
  aiAnalysisRunning.value = true
  aiAnalysisError.value = ''
  setAiAnalysisProgress(46, '正在恢复 AI 分析任务')
  try {
    const result = await resumeAiAnalysisRun(aiAnalysisRun.value.runId)
    const targetRunId = result.runId || aiAnalysisRun.value.runId
    const finishedRun = await monitorAiAnalysisRun(targetRunId)
    if (!finishedRun) return
    completeAiRequirementAnalysis('AI分析需求已恢复完成')
  } catch (error) {
    setAiAnalysisProgress(0, 'AI分析需求未完成')
    aiAnalysisError.value = presentAiWorkflowError(error, '恢复 AI 分析失败')
    ElMessage.error(aiAnalysisError.value)
  } finally {
    aiAnalysisRunning.value = false
  }
}

const retryCurrentAiAnalysisRun = async () => {
  if (!aiAnalysisRun.value) return
  aiAnalysisRunning.value = true
  aiAnalysisError.value = ''
  try {
    const readiness = await checkAiEnvironmentBeforeAnalysis()
    if (!readiness.ready) return
    setAiAnalysisProgress(18, '正在重试 AI 分析任务')
    const run = await retryAiAnalysisRun(aiAnalysisRun.value.runId)
    aiAnalysisRun.value = run
    const finishedRun = await monitorAiAnalysisRun(run.runId, readiness.logs)
    if (!finishedRun) return
    completeAiRequirementAnalysis('AI分析需求重试完成')
  } catch (error) {
    setAiAnalysisProgress(0, 'AI分析需求未完成')
    aiAnalysisError.value = presentAiWorkflowError(error, '重试 AI 分析失败')
    ElMessage.error(aiAnalysisError.value)
  } finally {
    aiAnalysisRunning.value = false
  }
}

const generateNaturalLanguageCasesWithAi = async (retryFailedBatches = false) => {
  const confirmedPointIds = (retryFailedBatches && generationRetryPointIds.value.length > 0
    ? generationRetryPointIds.value
    : scopedAiDraftPoints.value.map((point) => point.pointId))
  if (!confirmedPointIds.length) {
    ElMessage.warning('请先确认至少一个测试点')
    return
  }
  aiGenerationError.value = ''
  aiCaseGenerationRunning.value = true
  webCaseAgentEvents.value = []
  try {
    const project = await ensureBackendProject(requirementAnalysisProjectName.value || '默认项目')
    const run = await startWebCaseAgentRun({
      projectId: project.id,
      analysisRunId: aiAnalysisRun.value?.runId,
      pointIds: confirmedPointIds,
      environmentId: '',
      baseUrl: '',
      browser: 'chromium',
      viewport: '1440x900',
      mode: 'PLAN'
    })
    webCaseAgentRun.value = run
    naturalPlanRunId.value = run.runId
    await monitorWebCaseAgentRun(run.runId)
    return
  } catch (error) {
    aiGenerationError.value = presentAiWorkflowError(error, '自然语言用例生成失败')
    ElMessage.error(aiGenerationError.value)
    aiCaseGenerationRunning.value = false
    return
  }

}

const appendAgentArtifacts = (artifacts: WebCaseAgentArtifact[]) => {
  const fresh = artifacts.filter((artifact) => {
    // 正式用例仍严格只取 ACCEPTED；自然语言计划在生成完成后即作为「待确认」用例展示，
    // 由用户点击「确认用例」触发 confirmAll 完成人工确认（b241e2d 收紧 acceptance 时
    // 连同这条展示链路一起丢掉了：计划不展示 → 表格为空 → 「确认用例」永远不可达）。
    const deliverable = artifact.acceptanceState === 'ACCEPTED' && artifact.accepted
    const reviewablePlan = artifact.artifactType === 'natural_web_case_plan'
      && artifact.acceptanceState === 'PENDING_HUMAN_CONFIRMATION'
    return (deliverable || reviewablePlan) && !deliveredWebCaseArtifactIds.has(`${artifact.artifactId}:${artifact.revision ?? 1}`)
  })
  if (!fresh.length) return
  const naturalPlans = fresh.filter((artifact) => artifact.artifactType === 'natural_web_case_plan')
  if (naturalPlans.length > 0) {
    const generatedPlans = buildNaturalLanguageCasesFromAgentArtifacts(
      naturalPlans,
      aiDraftPoints.value,
      requirementAnalysisProjectName.value || '默认项目',
      generatedWebCases.value.length
    )
    generatedWebCases.value = [...generatedWebCases.value, ...generatedPlans.cases]
    generatedPlans.cases.forEach((testCase, index) => {
      const artifact = naturalPlans[index]
      if (artifact) {
        testCase.metadata.agentRunId = artifact.runId
        testCase.metadata.agentSourcePointId = artifact.sourcePointId
        testCase.metadata.artifactId = artifact.artifactId
        testCase.metadata.artifactRevision = artifact.revision
        testCase.metadata.artifactResultOrigin = artifact.resultOrigin
        testCase.metadata.artifactAcceptanceState = artifact.acceptanceState
        testCase.metadata.artifactModelProfileVersion = artifact.modelProfileVersion
        testCase.metadata.artifactFieldOrigins = artifact.fieldOrigins
        testCase.metadata.artifactEvidenceIds = artifact.evidenceIds
        testCase.metadata.artifactParentArtifactId = artifact.parentArtifactId
        testCase.metadata.artifactIdempotencyKey = artifact.idempotencyKey
      }
    })
    naturalPlanRunId.value = naturalPlans[0]?.runId || naturalPlanRunId.value
    naturalPlans.forEach((artifact) => deliveredWebCaseArtifactIds.add(`${artifact.artifactId}:${artifact.revision ?? 1}`))
    selectedCase.value ||= generatedPlans.cases[0]
    persistCaseDesignDraft()
  }
  const freshAutomation = fresh.filter((artifact) => artifact.artifactType !== 'natural_web_case_plan')
  if (!freshAutomation.length) return
  const cases = freshAutomation.map((artifact) => ({
    ...artifact.candidateJson,
    sourcePointId: artifact.sourcePointId,
    sourceText: ''
  })) as unknown as GeneratedAiCase[]
  const generated = buildGeneratedCasesFromAiGeneratedCases(
    cases,
    aiDraftPoints.value,
    requirementAnalysisProjectName.value || '默认项目',
    generatedWebCaseIdOffset(generatedWebCases.value)
  )
  if (!generated.cases.length) return
  generated.cases.forEach((testCase, index) => {
    const source = freshAutomation[index]
    if (source) {
      testCase.metadata.agentRunId = source.runId
      testCase.metadata.agentSourcePointId = source.sourcePointId
    }
    // 证据闭环:把用例绑定的页面证据(locator entry_id)带到表格,供「证据 N」徽章与追溯。
    testCase.metadata.agentEvidenceIds = source?.evidenceIds
    testCase.metadata.agentExpectedResultEvidenceIds = freshAutomation[index]?.expectedResultEvidenceIds
    testCase.metadata.artifactId = source?.artifactId
    testCase.metadata.artifactRevision = source?.revision
    testCase.metadata.artifactResultOrigin = source?.resultOrigin
    testCase.metadata.artifactAcceptanceState = source?.acceptanceState
    testCase.metadata.artifactModelProfileVersion = source?.modelProfileVersion
    testCase.metadata.artifactFieldOrigins = source?.fieldOrigins
    testCase.metadata.artifactEvidenceIds = source?.evidenceIds
    testCase.metadata.artifactParentArtifactId = source?.parentArtifactId
    testCase.metadata.artifactIdempotencyKey = source?.idempotencyKey
  })
  const compiledByPointId = new Map(generated.cases.map((testCase) => [testCase.metadata.agentSourcePointId, testCase]))
  const updatedCaseIds = new Set<string>()
  generatedWebCases.value = generatedWebCases.value.map((existingCase) => {
    const compiledCase = compiledByPointId.get(existingCase.metadata.agentSourcePointId)
    if (!compiledCase) return existingCase
    updatedCaseIds.add(existingCase.id)
    return {
      ...compiledCase,
      id: existingCase.id,
      row: {
        ...compiledCase.row,
        caseId: existingCase.row.caseId,
        naturalSteps: existingCase.row.naturalSteps,
        naturalExpectedResult: existingCase.row.naturalExpectedResult
      }
    }
  })
  generatedWebCases.value.push(
    ...generated.cases.filter((testCase) => !updatedCaseIds.has(testCase.id) && !generatedWebCases.value.some((item) => item.metadata.agentSourcePointId === testCase.metadata.agentSourcePointId))
  )
  const refreshedSelectedCase = generatedWebCases.value.find((testCase) => testCase.id === selectedCase.value?.id)
  if (refreshedSelectedCase) selectedCase.value = refreshedSelectedCase
  freshAutomation.forEach((artifact) => deliveredWebCaseArtifactIds.add(`${artifact.artifactId}:${artifact.revision ?? 1}`))
  selectedCase.value ||= generated.cases[0]
  if (webCaseAgentRun.value?.mode !== 'COMPILE') invalidateCaseReview()
  persistCaseDesignDraft()
}

const agentNaturalPlanCount = (artifacts: WebCaseAgentArtifact[]) =>
  artifacts.filter((artifact) => artifact.artifactType === 'natural_web_case_plan').length

const monitorWebCaseAgentRun = async (runId: string) => {
  const poll = webCaseAgentPollCoordinator.start(runId)
  if (!poll.shouldStart) return
  try {
    while (webCaseAgentPollCoordinator.isCurrent(runId, poll.token)) {
      const after = webCaseAgentEvents.value[webCaseAgentEvents.value.length - 1]?.sequenceNo || 0
      const [run, events, artifacts] = await Promise.all([
        getWebCaseAgentRun(runId),
        listWebCaseAgentEvents(runId, after),
        listWebCaseAgentArtifacts(runId)
      ])
      if (!webCaseAgentPollCoordinator.isCurrent(runId, poll.token)) return
      webCaseAgentRun.value = run
      if (events.length) webCaseAgentEvents.value = [...webCaseAgentEvents.value, ...events]
      webCaseAgentNaturalPlanCount.value = agentNaturalPlanCount(artifacts)
      const pendingPlans = artifacts.filter((artifact) => artifact.artifactType === 'natural_web_case_plan' && artifact.acceptanceState !== 'ACCEPTED')
      if (pendingPlans.length > 0) webCaseAgentNaturalPlanCount.value = pendingPlans.length
      appendAgentArtifacts(artifacts)
      const total = Math.max(run.requestedPointIds.length, 1)
      setAiCaseGenerationProgress(
        Math.min(96, Math.round((run.completedPointIds.length / total) * 92) + 4),
        `Agent ${run.currentStage} · 已完成 ${run.completedPointIds.length} / ${run.requestedPointIds.length} 个测试点`
      )
      if (run.status === 'succeeded') {
        generationRetryPointIds.value = []
        setAiCaseGenerationProgress(100, `Agent 已完成，累计生成 ${generatedWebCases.value.length} 条 Web 用例`)
        ElMessage.success(`Agent 已生成 ${generatedWebCases.value.length} 条可评审 Web 用例`)
        return
      }
      if (run.status === 'failed' || run.status === 'blocked' || run.status === 'partial' || run.status === 'cancelled') {
        const pending = run.requestedPointIds.filter((pointId) => !run.completedPointIds.includes(pointId))
        // PLAN 任务在「计划已生成、等待人工确认」时也以 blocked 落库——这不是失败，
        // 不应把用户引向重试，而应展示待确认用例并提示点「确认用例」。
        if (run.mode === 'PLAN' && run.status === 'blocked' && webCaseAgentNaturalPlanCount.value > 0) {
          generationRetryPointIds.value = []
          aiGenerationError.value = ''
          setAiCaseGenerationProgress(100, `已生成 ${webCaseAgentNaturalPlanCount.value} 条待确认用例，点击「确认用例」后进入质量评审`)
          ElMessage.success(`已生成 ${webCaseAgentNaturalPlanCount.value} 条待确认用例，点击「确认用例」完成确认`)
          return
        }
        generationRetryPointIds.value = pending
        aiGenerationError.value = run.status === 'cancelled'
          ? ''
          : run.errorSummary || (run.status === 'blocked'
            ? `Agent 已阻塞，${pending.length} 个测试点未完成；请先检查 Web 环境配置和登录状态后重试`
            : `Agent 生成失败，可重试 ${pending.length} 个未完成测试点`)
        setAiCaseGenerationProgress(
          Math.round((run.completedPointIds.length / total) * 100),
          run.status === 'cancelled'
            ? `已终止，已保留 ${generatedWebCases.value.length} 条用例`
            : `Agent 未完成，已保留 ${generatedWebCases.value.length} 条用例`
        )
        return
      }
      await new Promise((resolve) => window.setTimeout(resolve, 900))
    }
  } finally {
    if (webCaseAgentPollCoordinator.isCurrent(runId, poll.token)) {
      webCaseAgentPollCoordinator.stop(runId)
      aiCaseGenerationRunning.value = false
      aiCaseGenerationCancelling.value = false
    }
  }
}

const generateWebCasesWithAi = async (retryFailedAgentRun = false, preserveCurrentCases = false) => {
  const webEnvironment = activeWebExecutionEnvironment.value
  const webRuntime = webEnvironment ? buildWebExecutionRuntime(webEnvironment) : undefined
  if (!webEnvironment?.baseUrl.trim() || !webRuntime) {
    ElMessage.warning('请先在 Web 运行环境中配置 Base URL，再由浏览器采集真实页面定位器')
    return
  }
  const confirmedById = new Set(confirmedAiDraftPoints.value.map((point) => point.pointId))
  const requestedPointIds = (retryFailedAgentRun && generationRetryPointIds.value.length
    ? generationRetryPointIds.value
    : scopedAiDraftPoints.value.map((point) => point.pointId)
  ).filter((pointId) => confirmedById.has(pointId))
  if (!requestedPointIds.length && !retryFailedAgentRun && pendingAiDraftPoints.value.length) {
    const confirmedCount = await confirmAiDraftPoints(pendingAiDraftPoints.value, '确认并生成')
    if (confirmedCount) return compileWebAutomationCases()
  }
  if (!requestedPointIds.length) {
    ElMessage.warning('请先确认至少一个 AI 草稿测试点')
    return
  }
  if (!retryFailedAgentRun && !preserveCurrentCases) {
    generatedWebCases.value = []
    caseGenerationConfirmed.value = false
    selectedCase.value = undefined
    generationRetryPointIds.value = []
    selectedGeneratedWebCaseIds.value = []
    deliveredWebCaseArtifactIds.clear()
    webCaseAgentNaturalPlanCount.value = 0
    invalidateCaseReview()
  }
  aiGenerationError.value = ''
  aiCaseGenerationRunning.value = true
  aiCaseGenerationCancelling.value = false
  // 用户 2026-10-14：重试编译时清理上一次「素材取证 · 已阻塞」的运行展示——
  // 先清空旧运行对象与旧时间线，再创建新任务，工作区不再残留上一次的标题/阻塞统计/日志内容。
  webCaseAgentRun.value = undefined
  webCaseAgentEvents.value = []
  setAiCaseGenerationProgress(4, `正在创建 Agent 任务（${requestedPointIds.length} 个测试点）`)
  try {
    const project = await ensureBackendProject(requirementAnalysisProjectName.value || '默认项目')
    const login = webRuntime.login.enabled ? {
      enabled: true,
      url: resolveWebLoginDiscoveryUrl(webEnvironment.baseUrl, webRuntime.login.loginUrl),
      usernameLocator: webRuntime.login.usernameLocator,
      passwordLocator: webRuntime.login.passwordLocator,
      submitLocator: webRuntime.login.submitLocator,
      username: webRuntime.login.username,
      password: webRuntime.login.password,
      successType: webRuntime.login.successType,
      successTarget: webRuntime.login.successTarget
    } : undefined
    const planRunId = naturalPlanRunId.value || generatedWebCases.value
      .map((testCase) => testCase.metadata.agentRunId)
      .find((runId): runId is string => Boolean(runId)) || ''
    if (!planRunId) {
      throw new Error('缺少自然语言用例计划，请返回用例生成阶段重新确认用例')
    }
    // The confirmation endpoint is idempotent. Reconfirm here so a refresh or
    // an interrupted UI request cannot send COMPILE with unaccepted plan artifacts.
    await confirmAllWebCasePlanArtifacts(planRunId)
    naturalPlanRunId.value = planRunId
    const run = await startWebCaseAgentRun({
      projectId: project.id,
      analysisRunId: aiAnalysisRun.value?.runId,
      pointIds: requestedPointIds,
      environmentId: webEnvironment.id,
      baseUrl: webEnvironment.baseUrl.trim(),
      browser: webRuntime.browser === 'follow-case' ? 'chromium' : webRuntime.browser,
      viewport: webRuntime.viewport,
      mode: 'COMPILE',
      planRunId,
      browserDiscovery: {
        headless: webRuntime.headless,
        timeoutMs: webRuntime.navigationTimeoutMs,
        waitUntil: webRuntime.waitUntil,
        authToken: webRuntime.authToken,
        extraHeaders: webRuntime.extraHeaders,
        cookieOrigins: webRuntime.cookieOrigins,
        allowedRequestOrigins: webRuntime.allowedRequestOrigins,
        networkPolicyMode: webRuntime.networkPolicyMode,
        reuseSession: true,
        ...((webRuntime.sessionId || webLoginSessionId.value) ? { sessionId: webRuntime.sessionId || webLoginSessionId.value } : {}),
        ...(login ? { login } : {})
      }
    })
    webCaseAgentRun.value = run
    await monitorWebCaseAgentRun(run.runId)
  } catch (error) {
    aiGenerationError.value = presentAiWorkflowError(error, 'Agent 用例生成失败')
    setAiCaseGenerationProgress(0, 'Agent 用例生成未完成')
    ElMessage.error(aiGenerationError.value)
    aiCaseGenerationRunning.value = false
  }
}

const compileWebAutomationCases = async (retryFailedAgentRun = false) => {
  await generateWebCasesWithAi(retryFailedAgentRun, true)
}

const cancelAiCaseGeneration = async () => {
  if (!webCaseAgentRun.value || !aiCaseGenerationRunning.value) return
  aiCaseGenerationCancelling.value = true
  setAiCaseGenerationProgress(aiCaseGenerationProgress.percentage, '正在请求终止 Agent 任务')
  try {
    webCaseAgentRun.value = await cancelWebCaseAgentRun(webCaseAgentRun.value.runId)
  } catch (error) {
    aiCaseGenerationCancelling.value = false
    ElMessage.error(error instanceof Error ? error.message : '终止 Agent 任务失败')
  }
}

const isConfirmableAiDraftPoint = (point: AiDraftTestPoint) =>
  point.reviewStatus !== 'confirmed' && point.reviewStatus !== 'modified' && point.reviewStatus !== 'rejected'

const confirmAiDraftPoint = async (point: AiDraftTestPoint) => {
  try {
    const updated = await confirmAiDraftTestPoint(point.pointId, '确认生成')
    replaceAiDraftPoint(updated)
    ElMessage.success('已确认 AI 草稿测试点')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '确认失败')
  }
}

const confirmAiDraftPoints = async (points: AiDraftTestPoint[], comment: string) => {
  const targets = points.filter(isConfirmableAiDraftPoint)
  if (targets.length === 0) {
    ElMessage.info('暂无可确认的 AI 草稿测试点')
    return 0
  }
  aiDraftBulkConfirming.value = true
  let confirmedCount = 0
  try {
    for (const point of targets) {
      const updated = await confirmAiDraftTestPoint(point.pointId, comment)
      replaceAiDraftPoint(updated)
      confirmedCount += 1
    }
    ElMessage.success(`已确认 ${confirmedCount} 条 AI 草稿测试点`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '批量确认失败')
  } finally {
    aiDraftBulkConfirming.value = false
  }
  return confirmedCount
}

const confirmSelectedAiDraftPoints = async () => {
  await confirmAiDraftPoints(aiDraftSelection.value, '批量确认')
}

const confirmAllVisibleAiDraftPoints = async () => {
  await confirmAiDraftPoints(aiDraftPointView.value.items, '全部确认')
}

const editConfirmAiDraftPoint = async (point: AiDraftTestPoint) => {
  try {
    const { value } = await ElMessageBox.prompt('请输入测试点标题', '编辑确认', {
      confirmButtonText: '确认',
      cancelButtonText: '取消',
      inputValue: point.title
    })
    if (!value?.trim()) return
    const updated = await editConfirmAiDraftTestPoint(point.pointId, {
      title: value.trim(),
      objective: point.objective,
      steps: point.steps,
      expectedResult: point.expectedResult,
      comment: '编辑确认'
    })
    replaceAiDraftPoint(updated)
    ElMessage.success('已编辑确认 AI 草稿测试点')
  } catch {
    return
  }
}

const editAiDraftPointFromDetails = async (payload: {
  point: AiDraftTestPoint
  title: string
  objective: string
  steps: string
  expectedResult: string
}) => {
  try {
    const updated = await editConfirmAiDraftTestPoint(payload.point.pointId, {
      title: payload.title,
      objective: payload.objective,
      steps: payload.steps,
      expectedResult: payload.expectedResult,
      comment: '详情编辑确认'
    })
    replaceAiDraftPoint(updated)
    ElMessage.success('已保存测试点修改')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存测试点修改失败')
  }
}

const rejectAiDraftPoint = async (point: AiDraftTestPoint) => {
  try {
    const updated = await rejectAiDraftTestPoint(point.pointId, '拒绝生成')
    replaceAiDraftPoint(updated)
    ElMessage.success('已拒绝 AI 草稿测试点')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '拒绝失败')
  }
}

const deleteAiDraftPoint = async (point: AiDraftTestPoint) => {
  const confirmed = await confirmDangerAction('删除测试点', `确认删除测试点“${point.title}”吗？`)
  if (!confirmed) return
  try {
    await deleteAiDraftTestPoint(point.pointId)
    aiDraftPoints.value = aiDraftPoints.value.filter((item) => item.pointId !== point.pointId)
    aiDraftSelection.value = aiDraftSelection.value.filter((item) => item.pointId !== point.pointId)
    generationScopePointIds.value = generationScopePointIds.value.filter((pointId) => pointId !== point.pointId)
    extractedRequirementPoints.value = aiDraftPoints.value.map(aiDraftPointToRequirementPoint)
    ElMessage.success('已删除测试点')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除测试点失败')
  }
}

const replaceAiDraftPoint = (point: AiDraftTestPoint) => {
  aiDraftPoints.value = aiDraftPoints.value.map((item) => item.pointId === point.pointId ? point : item)
  extractedRequirementPoints.value = aiDraftPoints.value.map(aiDraftPointToRequirementPoint)
}

const resetAiRequirementAnalysis = () => {
  aiSourceDocuments.value = []
  aiAnalysisRun.value = undefined
  aiDraftPoints.value = []
  aiDraftSelection.value = []
  aiCoverageSummary.value = undefined
  aiUnclearQuestions.value = []
  aiToolCalls.value = []
  resetAiReviewState()
  aiDraftFilter.module = 'ALL'
  aiDraftFilter.status = 'ALL'
  aiDraftFilter.confidence = 'ALL'
  aiDraftFilter.sourceDocumentId = 'ALL'
  resetCaseGenerationSelectionState()
  resetWebRequirementFlowProgress()
}

const resetAiReviewState = () => {
  requirementAiReviewRun.value = undefined
  caseAiReviewRun.value = undefined
  activeAiReviewStage.value = 'requirement'
  aiMultiAgentConflicts.value = []
  aiMultiAgentQualityScores.value = []
  aiMultiAgentMergeResult.value = undefined
  caseReviewAttempted.value = false
  caseReviewFindingDecisions.value = {}
  restoredAiReviewInsight.value = undefined
  caseReviewError.value = ''
}

const requirementSourceFilesForAiUpload = (): File[] => {
  const fileByName = new Map(stagedRequirementSourceFiles.value.map((file) => [file.name, file]))
  return stagedRequirementFiles.value
    .filter((source) => !source.needsReattach && !requirementSourceErrors.value[source.name])
    .map((source) => fileByName.get(source.name) || new File([source.text], source.name, { type: 'text/plain' }))
}

const aiDraftPointToRequirementPoint = (point: AiDraftTestPoint): RequirementPointDraft => ({
  project: requirementAnalysisProjectName.value || '默认项目',
  module: point.module || '默认模块',
  pageName: point.pageOrFeature || 'AI分析',
  title: point.title,
  sourceFile: point.sourceFile || 'AI草稿测试点',
  sourceDocumentType: 'markdown'
})

const aiDraftStatusText = (status: string): string => {
  if (status === 'confirmed') return '已确认'
  if (status === 'modified') return '已修改'
  if (status === 'rejected') return '已拒绝'
  return 'AI生成'
}

const aiDraftStatusTagType = (status: string): 'success' | 'warning' | 'info' | 'danger' => {
  if (status === 'confirmed' || status === 'modified') return 'success'
  if (status === 'rejected') return 'danger'
  if (status === 'ai_generated') return 'warning'
  return 'info'
}

const aiDraftConfidenceText = (confidenceScore: number): string =>
  `${Math.round(confidenceScore * 100)}%`

const aiDraftConfidenceTagType = (confidenceScore: number): 'success' | 'warning' | 'info' => {
  const bucket = confidenceBucket(confidenceScore)
  if (bucket === 'high') return 'success'
  if (bucket === 'medium') return 'warning'
  return 'info'
}

const aiDraftVisualEvidence = (point: AiDraftTestPoint) => visualEvidenceForDraftPoint(point)

const aiDraftVisualCoordinateText = (point: AiDraftTestPoint): string => {
  const evidence = aiDraftVisualEvidence(point)
  return evidence?.coordinates ? formatVisualRegionCoordinates(evidence.coordinates) : '-'
}

const aiQuestionSeverityTagType = (severity: string): 'success' | 'warning' | 'info' | 'danger' => {
  if (severity === 'critical' || severity === 'high') return 'danger'
  if (severity === 'medium') return 'warning'
  if (severity === 'low') return 'info'
  return 'info'
}

const aiToolCallStatusTagType = (status: string): 'success' | 'warning' | 'info' | 'danger' => {
  if (status === 'succeeded') return 'success'
  if (status === 'failed' || status === 'blocked') return 'danger'
  if (status === 'pending') return 'warning'
  return 'info'
}

const readRequirementFileText = async (file: File): Promise<string> => {
  const extension = file.name.toLowerCase().split('.').pop() || ''
  if (shouldParseRequirementFileViaBackend(extension)) {
    try {
      const parsed = await parseRequirementDocumentWithBackend(file)
      return buildRequirementImportText(parsed)
    } catch {
      return fallbackRequirementFileText(file, extension)
    }
  }
  return await file.text()
}

const shouldParseRequirementFileViaBackend = (extension: string): boolean =>
  ['pdf', 'doc', 'docx', 'xls', 'xlsx', 'png', 'jpg', 'jpeg', 'webp', 'gif', 'bmp'].includes(extension)

const fallbackRequirementFileText = async (file: File, extension: string): Promise<string> => {
  if (['txt', 'csv', 'md', 'markdown', 'json', 'yaml', 'yml'].includes(extension)) {
    return await file.text()
  }
  return ''
}

const importOpenApiPayloads = (payloads: Array<{ name: string; json: unknown }>) => {
  let latestProject: ImportedOpenApiProject | undefined
  for (const payload of payloads) {
    latestProject = createImportedOpenApiProject(parseOpenApiDocument(payload.json, payload.name))
    importedProjects.value = upsertImportedProject(importedProjects.value, latestProject)
  }
  if (latestProject) {
    switchImportedProject(latestProject.id)
    ElMessage.success(`已导入 ${payloads.length} 个项目，当前项目 ${latestProject.title}`)
  }
}

const switchImportedProject = (projectId: string) => {
  const project = selectImportedProject(importedProjects.value, projectId)
  if (!project) return

  activeProjectId.value = project.id
  importFilter.module = 'ALL'
  importFilter.method = 'ALL'
  importFilter.pathKeyword = ''
  importFilter.summaryKeyword = ''
  selectedOperationIds.value = project.document.operations.map((operation) => operation.id)
  reviewFindings.value = []
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
    const result = importProjectData(localStorage, json)
    savedCases.value = listSavedCases(localStorage)
    templateConfig.value = loadTemplateConfig(localStorage)
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

const toggleOperation = (operation: ParsedOperation, checked: boolean) => {
  const selected = new Set(selectedOperationIds.value)
  if (checked) {
    selected.add(operation.id)
  } else {
    selected.delete(operation.id)
  }
  selectedOperationIds.value = Array.from(selected)
}

const selectFilteredOperations = () => {
  selectedOperationIds.value = Array.from(new Set([
    ...selectedOperationIds.value,
    ...filteredOperations.value.map((operation) => operation.id)
  ]))
}

const clearSelectedOperations = () => {
  selectedOperationIds.value = []
}

const generateCases = () => {
  if (!parsed.value || selectedOperations.value.length === 0) {
    ElMessage.warning('请选择接口')
    return
  }

  generatedCases.value = generateInterfaceCases({
    project: parsed.value.title,
    sourceFile: parsed.value.sourceFile,
    operations: selectedOperations.value,
    granularity: generation.granularity
  })
    .filter((testCase) => {
      const definition = defaultCaseTypes.find((caseType) => caseType.name === testCase.row.caseType)
      return definition
        ? generation.selectedCaseTypeIds.includes(definition.id) && enabledInterfaceCaseTypeIds.value.has(definition.id)
        : true
    })
    .map(applyConfiguredCaseTypeName)
  generatedCases.value = applyInterfaceFieldTemplateToCases(generatedCases.value, generationTemplateFields.value)
  reviewFindings.value = []
  interfaceReviewAttempted.value = false
  ignoredInterfaceReviewFindingIds.value = []
  selectedInterfaceCaseIds.value = []
  selectedCase.value = generatedCases.value[0]
  ElMessage.success(`已生成 ${generatedCases.value.length} 条用例`)
}

const runAiReview = () => {
  if (generatedCases.value.length === 0) {
    ElMessage.warning('请先生成用例')
    return
  }

  generatedCases.value = markCasesAsAiReviewed(generatedCases.value)
  reviewFindings.value = actionableReviewFindings(reviewGeneratedCases(generatedCases.value, {
    expectedCaseTypes: interfaceCaseTypes.value
      .filter((caseType) => caseType.enabled)
      .map((caseType) => caseType.name)
  }))
  interfaceReviewAttempted.value = true
  ignoredInterfaceReviewFindingIds.value = []
  ElMessage.success(reviewFindings.value.length ? 'AI 评审已生成' : 'AI 评审通过')
}

const supplementMissingCases = () => {
  if (!parsed.value || missingCaseTypeFindings.value.length === 0) return

  const supplements = applyInterfaceFieldTemplateToCases(supplementMissingCaseTypes({
    existingCases: generatedCases.value,
    operations: parsed.value.operations,
    findings: missingCaseTypeFindings.value
  }).map(applyConfiguredCaseTypeName), interfaceFields.value)

  if (supplements.length === 0) {
    ElMessage.info('没有需要补充的缺失用例')
    reviewFindings.value = actionableReviewFindings(reviewGeneratedCases(generatedCases.value, {
      expectedCaseTypes: interfaceCaseTypes.value
        .filter((caseType) => caseType.enabled)
        .map((caseType) => caseType.name)
    }))
    return
  }

  generatedCases.value = [...generatedCases.value, ...supplements]
  reviewFindings.value = actionableReviewFindings(reviewGeneratedCases(generatedCases.value, {
    expectedCaseTypes: interfaceCaseTypes.value
      .filter((caseType) => caseType.enabled)
      .map((caseType) => caseType.name)
  }))
  ElMessage.success(`已补充 ${supplements.length} 条缺失用例`)
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

const exportAutomationCurlScript = () => {
  if (filteredAutomationCases.value.length === 0) {
    ElMessage.warning('没有可导出的自动化用例')
    return
  }
  const environment = resolveRunnableEnvironment()
  if (!environment) return

  const script = [
    '#!/usr/bin/env bash',
    'set -euo pipefail',
    '',
    ...filteredAutomationCases.value.flatMap((testCase) => [
      `echo ${JSON.stringify(`Running ${testCase.id} ${testCase.name}`)}`,
      buildAutomationCurlCommand(testCase, environment),
      ''
    ])
  ].join('\n')
  downloadTextFile('API自动化-curl执行脚本.sh', script, 'text/x-shellscript;charset=utf-8')
}

const delayWebRunPoll = (delayMs: number) => new Promise((resolve) => window.setTimeout(resolve, delayMs))

const loadWebRunCapabilities = async () => {
  try {
    webRunCapabilities.value = await getWebAutomationCapabilities()
  } catch (error) {
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
  let changed = false

  for (const caseId of recoveredIds) {
    if (activeById.has(caseId)) continue
    const savedCase = savedById.get(caseId)
    if (!savedCase) continue
    activeById.set(caseId, savedCase)
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
  for (const result of report.results) {
    const source = casesToRun.find((testCase) => testCase.id === result.caseId || testCase.sourceCaseId === result.sourceCaseId)
    const agentRunId = source?.agentRunId
    const pointId = source?.agentSourcePointId
    if (agentRunId && pointId) void recordAgentAssetOutcome(agentRunId, pointId, result.status === 'passed').catch(() => undefined)
  }
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
    ElMessage.info('Web 执行已停止，已保留当前日志和截图')
  } else if (snapshot.status === 'failed') {
    ElMessage.error(snapshot.diagnostics[snapshot.diagnostics.length - 1] || 'Web 执行任务异常结束')
  } else {
    ElMessage.success(
      `执行完成：通过 ${report.summary.passed}，失败 ${report.summary.failed}，错误 ${report.summary.error}`
    )
  }
}

const monitorActiveWebRun = async (runId: string) => {
  const poll = webRunPollCoordinator.start(runId)
  if (!poll.shouldStart) return
  let consecutiveFailures = 0
  try {
    while (webRunPollCoordinator.isCurrent(runId, poll.token) && activeWebRunId.value === runId) {
      try {
        const snapshot = await getWebAutomationRun(runId)
        if (!webRunPollCoordinator.isCurrent(runId, poll.token)) return
        consecutiveFailures = 0
        webRunConnectionState.value = 'polling'
        applyWebRunSnapshot(snapshot)
        if (isTerminalWebAutomationRun(snapshot.status)) {
          finalizeWebRunSnapshot(snapshot)
          return
        }
        await delayWebRunPoll(500)
      } catch (error) {
        if (!webRunPollCoordinator.isCurrent(runId, poll.token)) return
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
    if (webRunPollCoordinator.isCurrent(runId, poll.token)) {
      webRunPollCoordinator.stop(runId)
    }
  }
}

const resolveVisualWebAutomationRunProjectId = async (
  cases: SavedWebAutomationRunCase[]
): Promise<number | undefined> => {
  const projectNames = Array.from(new Set(cases.map((item) => (item.project || '').trim()).filter(Boolean)))
  if (projectNames.length !== 1) {
    return undefined
  }
  try {
    const project = await ensureBackendProject(projectNames[0])
    return project.id
  } catch {
    return undefined
  }
}

const startVisualWebAutomationRun = async (
  explicitCases?: SavedWebAutomationRunCase[]
): Promise<boolean> => {
  if (webAutomationRunning.value) return false
  const casesToRun = explicitCases || effectiveWebAutomationScope.value.cases
  if (!explicitCases && !webAutomationPreflight.value.canExecute) {
    webRunCreationError.value = webAutomationPreflight.value.blockingReason
    return false
  }
  if (casesToRun.length === 0) {
    webRunCreationError.value = '没有可执行的 Web 自动化用例'
    return false
  }
  const webRuntime = resolveWebRuntimeEnvironment()
  if (!webRuntime) return false

  clearWebRunLocalMessages()
  // 先置运行态再解析项目 id：解析含后端调用（await），期间保持互斥，双击不会并发双启动（第二十六轮 C-2）。
  webAutomationRunning.value = true
  const runProjectId = await resolveVisualWebAutomationRunProjectId(casesToRun)
  activeWebRunCases.value = [...casesToRun]
  webRunConnectionState.value = 'polling'
  finalizedWebRunId = ''
  webRunPollCoordinator.stop()
  try {
    const created = await createWebAutomationRun(casesToRun, {
      mode: webAutomationRunMode.value,
      loopCount: webAutomationLoopCount.value,
      browserOverride: webRuntime.browser,
      runtime: webRuntime,
      projectId: runProjectId
    })
    activeWebRunId.value = created.runId
    applyWebRunSnapshot(created)
    sessionStorage.setItem(WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY, created.runId)
    webAutomationWorkspaceMode.value = 'workbench'
    void monitorActiveWebRun(created.runId)
    return true
  } catch (error) {
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
  if (!activeWebRunId.value) return
  webRunControlLoading.value = 'pause'
  try {
    applyWebRunSnapshot(await pauseWebAutomationRun(activeWebRunId.value))
  } catch (error) {
    ElMessage.error(error instanceof WebAutomationRunRequestError ? error.toFallbackMessage() : '暂停执行失败')
  } finally {
    webRunControlLoading.value = ''
  }
}

const resumeActiveWebRun = async () => {
  if (!activeWebRunId.value) return
  webRunControlLoading.value = 'resume'
  try {
    const snapshot = await resumeWebAutomationRun(activeWebRunId.value)
    applyWebRunSnapshot(snapshot)
    if (webRunConnectionState.value === 'interrupted') void monitorActiveWebRun(activeWebRunId.value)
  } catch (error) {
    ElMessage.error(error instanceof WebAutomationRunRequestError ? error.toFallbackMessage() : '继续执行失败')
  } finally {
    webRunControlLoading.value = ''
  }
}

const stopActiveWebRun = async () => {
  if (!activeWebRunId.value) return
  webRunControlLoading.value = 'stop'
  try {
    const snapshot = await stopWebAutomationRun(activeWebRunId.value)
    applyWebRunSnapshot(snapshot)
    if (isTerminalWebAutomationRun(snapshot.status)) finalizeWebRunSnapshot(snapshot)
  } catch (error) {
    ElMessage.error(error instanceof WebAutomationRunRequestError ? error.toFallbackMessage() : '停止执行失败')
  } finally {
    webRunControlLoading.value = ''
  }
}

const activeWebRunCaseById = (caseOrId: SavedWebAutomationRunCase | string) => {
  const caseId = typeof caseOrId === 'string' ? caseOrId : caseOrId.id
  return activeWebRunCases.value.find((testCase) => testCase.id === caseId)
}

const openWebAutomationRunLog = (caseOrId: SavedWebAutomationRunCase | string) => {
  const testCase = activeWebRunCaseById(caseOrId)
  if (testCase) openWebAutomationLog(testCase)
}

const transferWebAutomationRunResultToDefect = (caseOrId: SavedWebAutomationRunCase | string) => {
  const testCase = activeWebRunCaseById(caseOrId)
  if (testCase) transferWebAutomationResultToDefect(testCase)
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
    activeWebRunId.value = snapshot.runId
    applyWebRunSnapshot(snapshot)
    sessionStorage.setItem(WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY, snapshot.runId)
    webRunConnectionState.value = isTerminalWebAutomationRun(snapshot.status) ? 'idle' : 'polling'
    webAutomationWorkspaceMode.value = recovery.mode
    if (recovery.mode === 'monitor') writeWebMonitorQuery('monitor', snapshot.runId, historyMode)
    if (!isTerminalWebAutomationRun(snapshot.status)) {
      // 刷新恢复的轮询同样属于「运行中」：置位运行态，避免重跑按钮在恢复期间出现（第二十六轮 C-1）。
      webAutomationRunning.value = true
      void monitorActiveWebRun(snapshot.runId)
    }
  } catch (error) {
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

  const casesToRun = automationSelection.value.length > 0 ? automationSelection.value : filteredAutomationCases.value
  await executeApiAutomationCases(casesToRun)
}

const executeApiAutomationCases = async (
  casesToRun: SavedAutomationCase[]
): Promise<boolean> => {
  if (casesToRun.length === 0) {
    ElMessage.warning('没有可执行的自动化用例')
    return false
  }
  const environment = resolveRunnableEnvironment()
  if (!environment) return false

  apiAutomationRunning.value = true
  apiAutomationProgress.completed = 0
  apiAutomationProgress.total = casesToRun.length
  apiAutomationProgress.runningCaseId = ''
  try {
    automationRunReport.value = await runAutomationCases(
      casesToRun,
      environment,
      {
        mode: automationRunMode.value,
        concurrencyLimit: automationConcurrencyLimit.value,
        responseBodyMatchThreshold: responseBodyMatchThreshold.value,
        loopCount: apiAutomationLoopCount.value
      },
      executeBrowserRequest,
      {
        onProgress: (progress) => {
          apiAutomationProgress.completed = progress.completed
          apiAutomationProgress.total = progress.total
          apiAutomationProgress.runningCaseId = progress.runningCaseId || ''
        }
      }
    )
    const reportRecord: SavedAutomationReport = {
      ...automationRunReport.value,
      reportId: `report-${Date.now()}`,
      name: `${activeApiExecutionEnvironment.value?.name || '默认环境'}-${new Date().toLocaleString('zh-CN', {
        hour12: false
      })}`,
      project: automationFilter.project !== 'ALL' ? automationFilter.project : (casesToRun[0]?.project || '全部项目'),
      environmentName: activeApiExecutionEnvironment.value?.name || '默认环境',
      moduleScope:
        automationFilter.module !== 'ALL'
          ? [automationFilter.module]
          : Array.from(new Set(casesToRun.map((testCase) => testCase.module))),
      createdAt: new Date().toISOString()
    }
    appendAutomationReport(localStorage, reportRecord)
    refreshAutomationReports()
    selectedReportProject.value = reportRecord.project
    selectedReportId.value = reportRecord.reportId
    ElMessage.success(`执行完成：通过 ${automationRunReport.value.summary.passed}，失败 ${automationRunReport.value.summary.failed}，错误 ${automationRunReport.value.summary.error}`)
    return true
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'API 自动化执行失败')
    return false
  } finally {
    apiAutomationRunning.value = false
    apiAutomationProgress.runningCaseId = ''
  }
}

const refreshAutomationCases = () => {
  automationCases.value = listSavedAutomationCases(localStorage)
  webAutomationCases.value = listSavedWebAutomationRunCases(localStorage)
}

const applyJiraConfig = (config: JiraConfig) => {
  Object.assign(jiraConfigForm, config)
  syncJiraProjectJqlRowsFromForm()
}

const syncJiraProjectJqlRowsFromForm = () => {
  jiraProjectJqlRows.value = Object.entries(jiraConfigForm.projectJql).map(([key, value]) => ({ key, value }))
}

const syncJiraProjectJqlFormFromRows = () => {
  const next: Record<string, string> = {}
  for (const row of jiraProjectJqlRows.value) {
    const key = row.key.trim()
    if (key) next[key] = row.value
  }
  jiraConfigForm.projectJql = next
}

const addJiraProjectJqlRow = () => {
  jiraProjectJqlRows.value.push({ key: '', value: '' })
}

const removeJiraProjectJqlRow = async (index: number) => {
  const row = jiraProjectJqlRows.value[index]
  // 空行是未填写的草稿行，删除无数据损失；有内容的映射删除前需二次确认。
  if (row && (row.key.trim() || row.value.trim())) {
    const label = row.key.trim()
    const message = label ? `确认删除项目「${label}」的 JQL 映射吗？` : '确认删除这条 JQL 映射吗？'
    const confirmed = await confirmDangerAction('删除 JQL 映射', message)
    if (!confirmed) return
  }
  jiraProjectJqlRows.value.splice(index, 1)
  syncJiraProjectJqlFormFromRows()
}

const loadJiraSettingsFromBackend = async () => {
  jiraConfigLoading.value = true
  try {
    applyJiraConfig(await loadJiraConfigFromPlatform())
  } catch {
    // 后端不可用时保留默认配置
  } finally {
    jiraConfigLoading.value = false
  }
}

const saveJiraSettings = async () => {
  jiraConfigLoading.value = true
  try {
    syncJiraProjectJqlFormFromRows()
    applyJiraConfig(await saveJiraConfigToPlatform(jiraConfigForm))
    ElMessage.success('已保存 Jira 配置')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存 Jira 配置失败')
  } finally {
    jiraConfigLoading.value = false
  }
}

const testJiraConnection = async () => {
  jiraConfigTesting.value = true
  try {
    syncJiraProjectJqlFormFromRows()
    const result = await testJiraConfigConnection(jiraConfigForm)
    if (result.success) {
      ElMessage.success(result.message || '连接测试通过')
    } else {
      ElMessage.warning(result.message || '连接测试未通过')
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '连接测试失败')
  } finally {
    jiraConfigTesting.value = false
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

const transferDesignOriginResultToDefect = (result: WebAutomationRunReport['results'][number] | undefined) => {
  if (!result || (result.status !== 'failed' && result.status !== 'error')) {
    ElMessage.info('只有失败或错误的设计稿执行结果可以转缺陷')
    return
  }
  defects.createDefectsFromWebResults([result], filteredWebAutomationCases.value[0]?.project || jiraProjectName.value)
}

const focusDesignOriginAutomationCase = (caseId: string) => {
  focusedWebAutomationCaseId.value = caseId
}

const webAutomationRowClassName = ({ row }: { row: SavedWebAutomationRunCase }) =>
  row.id === focusedWebAutomationCaseId.value ? 'is-design-focused-row' : ''

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
      customClass: 'theme-confirm-box'
    })
    return true
  } catch {
    return false
  }
}

const defects = useDefects({
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

const actionableReviewFindings = (findings: CaseReviewFinding[]) =>
  findings.filter((finding) => finding.code !== 'review_passed')

const clearGeneratedCaseWorkspace = () => {
  generatedCases.value = []
  generatedWebCases.value = []
  selectedCase.value = undefined
  resetCaseGenerationSelectionState()
  reviewFindings.value = []
  interfaceReviewAttempted.value = false
  ignoredInterfaceReviewFindingIds.value = []
  selectedInterfaceCaseIds.value = []
  interfaceReviewStatusFilter.value = 'ALL'
  activeInterfaceCaseStage.value = 'import'
}

const currentBackendProjectName = () =>
  activeProject.value?.title
    || generatedCases.value[0]?.row.project
    || (libraryFilter.project !== 'ALL' ? libraryFilter.project : '默认项目')

const persistGeneratedCasesToLibrary = async (showMessage = true) => {
  if (generationKind.value === 'web_automation') {
    if (generatedWebCases.value.length === 0) {
      if (showMessage) ElMessage.warning('请先导入 Web 自动化用例')
      return false
    }
    const result = saveGeneratedWebAutomationCases(localStorage, generatedWebCases.value)
    refreshSavedCases()
    if (showMessage) {
      ElMessage.success(`已保存 ${result.inserted} 条，更新 ${result.updated} 条 Web 自动化用例`)
    }
    return result
  }
  if (generatedCases.value.length === 0) {
    if (showMessage) ElMessage.warning('请先生成用例')
    return false
  }
  const syncResult = await saveGeneratedCasesWithBackend(localStorage, currentBackendProjectName(), generatedCases.value)
  const result = syncResult.result
  refreshSavedCases()
  if (showMessage) {
    ElMessage.success(
      syncResult.source === 'backend'
        ? `已保存 ${result.inserted} 条，更新 ${result.updated} 条，并同步到后端`
        : `已保存 ${result.inserted} 条，更新 ${result.updated} 条`
    )
  }
  return result
}

const saveCases = async () => {
  saveCasesLoading.value = true
  try {
    const result = await persistGeneratedCasesToLibrary(false)
    if (!result) return
    clearGeneratedCaseWorkspace()
    ElMessage.success(
      generationKind.value === 'web_automation'
        ? `已保存 ${result.inserted} 条，更新 ${result.updated} 条 Web 自动化用例，请前往用例库查看`
        : `已保存 ${result.inserted} 条，更新 ${result.updated} 条，请前往用例库查看`
    )
  } finally {
    saveCasesLoading.value = false
  }
}

const beginCaseDesignTask = () => {
  caseDesignDraftPersistenceSuspended.value = false
  caseDesignDraftSaveError.value = ''
}

const clearCompletedCaseDesignTask = () => {
  caseDesignDraftPersistenceSuspended.value = true
  if (caseDesignDraftTimer) clearTimeout(caseDesignDraftTimer)
  clearGeneratedCaseWorkspace()
  stagedRequirementFiles.value = []
  stagedRequirementSourceFiles.value = []
  requirementSourceErrors.value = {}
  extractedRequirementPoints.value = []
  resetAiRequirementAnalysis()
  aiAnalysisError.value = ''
  aiGenerationError.value = ''
  caseReviewError.value = ''
  caseReviewAttempted.value = false
  caseReviewFindingDecisions.value = {}
  selectedGeneratedWebCaseIds.value = []
  generationScopePointIds.value = []
  activeCaseDesignStage.value = 'requirement'
  caseDesignLastSavedAt.value = ''
  deliveryMode.value = 'library'
  deliveryExecuteNow.value = false
  clearCaseDesignDraft(localStorage)
}

const deliverGeneratedCases = async () => {
  if (!caseDesignDeliveryView.value.canSubmit) return
  saveCasesLoading.value = true
  try {
    if (deliveryMode.value === 'library') {
      const saved = await persistGeneratedCasesToLibrary(false)
      if (!saved) return
      clearCompletedCaseDesignTask()
      navigateToNav('cases')
      ElMessage.success('已保存到用例库')
      return
    }

    if (deliveryAutomationKind.value === 'web') {
      generatedWebCases.value = finalizeDesignDraftCasesForLibrary(generatedWebCases.value)
    }
    const saved = await persistGeneratedCasesToLibrary(false)
    if (!saved) return

    if (deliveryAutomationKind.value === 'web') {
      const selectedIds = new Set(selectedWebCasesForAutomation.value.map((testCase) => testCase.id))
      const prepared = generateWebAutomationCasesFromGeneratedCases(
        generatedWebCases.value.filter((testCase) => selectedIds.has(testCase.id))
      )
      const preparedIds = new Set(prepared.map((testCase) => testCase.id))
      saveWebAutomationRunCases(localStorage, prepared)
      refreshAutomationCases()
      const exactCases = webAutomationCases.value.filter((testCase) => preparedIds.has(testCase.id))
      clearCompletedCaseDesignTask()
      automationKind.value = 'web'
      if (deliveryExecuteNow.value) {
        await startVisualWebAutomationRun(exactCases)
      } else {
        navigateToNav('automation')
      }
      return
    }

    const prepared = generateAutomationCasesFromInterfaceCases(generatedCases.value)
    const preparedIds = new Set(prepared.map((testCase) => testCase.id))
    saveAutomationCases(localStorage, prepared)
    refreshAutomationCases()
    const exactCases = automationCases.value.filter((testCase) => preparedIds.has(testCase.id))
    clearCompletedCaseDesignTask()
    automationKind.value = 'api'
    if (deliveryExecuteNow.value) {
      await executeApiAutomationCases(exactCases)
    } else {
      navigateToNav('automation')
    }
  } finally {
    saveCasesLoading.value = false
  }
}

const prepareCaseGenerationEntry = () => {
  // A persisted COMPILE failure belongs to the later automation stage. Do not
  // show it when opening a fresh natural-language generation stage.
  const currentRun = webCaseAgentRun.value
  const isPlanFailure = currentRun?.mode === 'PLAN'
    && (currentRun.status === 'failed' || currentRun.status === 'blocked' || currentRun.status === 'cancelled')
  if (generatedWebCases.value.length === 0 && !isPlanFailure) {
    aiGenerationError.value = ''
    generationRetryPointIds.value = []
  }
}

const handleCaseDesignPrimaryAction = async () => {
  const stage = caseDesignWorkflow.value.activeStage
  if (stage === 'requirement') {
    activeCaseDesignStage.value = 'requirement'
    await analyzeRequirementPointsWithAi()
    if (aiAnalysisCompleted.value) activeCaseDesignStage.value = 'test-parse'
    return
  }
  if (stage === 'test-parse') {
    if (aiAnalysisError.value) await retryCurrentAiAnalysisRun()
    if (aiAnalysisCompleted.value && confirmedAiDraftPoints.value.length > 0) {
      prepareCaseGenerationEntry()
      activeCaseDesignStage.value = 'case-generate'
    }
    return
  }
  if (stage === 'case-generate') {
    if (generatedWebCases.value.length > 0 && !aiCaseGenerationRunning.value) {
      try {
        if (naturalPlanRunId.value) await confirmAllWebCasePlanArtifacts(naturalPlanRunId.value)
        aiGenerationError.value = ''
        caseGenerationConfirmed.value = true
        invalidateCaseReview()
        activeCaseDesignStage.value = 'review'
        await startCaseAiReview()
      } catch (error) {
        aiGenerationError.value = presentAiWorkflowError(error, '确认自然语言用例失败')
        ElMessage.error(aiGenerationError.value)
      }
    }
    return
  }
  if (stage === 'review') {
    if (caseReviewStatus.value === 'failed' || !caseAiReviewRun.value) {
      await startCaseAiReview()
      return
    }
    caseReviewError.value = ''
    caseReviewAttempted.value = true
    activeCaseDesignStage.value = 'automation-compile'
    return
  }
  if (stage === 'automation-compile') {
    await compileWebAutomationCases()
    return
  }
  await deliverGeneratedCases()
}

const caseDesignStageKeys: CaseDesignStage[] = ['requirement', 'test-parse', 'case-generate', 'review', 'automation-compile', 'deliver']
const navigateCaseDesignStage = (stage: string) => {
  if (caseDesignWorkflow.value.activeStage === 'automation-compile' && stage === 'case-generate') return
  if (caseDesignStageKeys.includes(stage as CaseDesignStage)) {
    if (stage === 'case-generate') prepareCaseGenerationEntry()
    activeCaseDesignStage.value = stage as CaseDesignStage
  }
}

const interfaceCaseStageKeys: InterfaceCaseStage[] = ['import', 'generate', 'review', 'deliver']
const navigateInterfaceCaseStage = (stage: string) => {
  if (interfaceCaseStageKeys.includes(stage as InterfaceCaseStage)) {
    activeInterfaceCaseStage.value = stage as InterfaceCaseStage
  }
}

const resetInterfaceCaseFilters = () => {
  previewFilter.module = 'ALL'
  previewFilter.caseType = 'ALL'
  previewFilter.priority = 'ALL'
  previewFilter.executionResult = 'ALL'
  previewFilter.keyword = ''
  interfaceReviewStatusFilter.value = 'ALL'
}

const resetInterfaceOperationFilters = () => {
  importFilter.module = 'ALL'
  importFilter.method = 'ALL'
  importFilter.pathKeyword = ''
  importFilter.summaryKeyword = ''
}

const handleInterfacePrimaryAction = async () => {
  const stage = interfaceCaseWorkflow.value.activeStage
  if (stage === 'import') {
    if (importedProjects.value.length === 0) await pickOpenApiFiles()
    else activeInterfaceCaseStage.value = 'generate'
    return
  }
  if (stage === 'generate') {
    generateCases()
    if (generatedCases.value.length > 0) activeInterfaceCaseStage.value = 'review'
    return
  }
  if (stage === 'review') {
    if (!interfaceReviewAttempted.value) runAiReview()
    else activeInterfaceCaseStage.value = 'deliver'
    return
  }
  await saveCases()
}

const startNewInterfaceCaseTask = async () => {
  const confirmed = await confirmDangerAction(
    '新建接口用例任务',
    '确认清空当前接口来源和生成结果并开始新任务吗？',
    '新建任务'
  )
  if (!confirmed) return
  clearGeneratedCaseWorkspace()
  importedProjects.value = []
  activeProjectId.value = ''
  selectedOperationIds.value = []
  importFilter.module = 'ALL'
  importFilter.method = 'ALL'
  importFilter.pathKeyword = ''
  importFilter.summaryKeyword = ''
  resetInterfaceCaseFilters()
}

const ignoreInterfaceReviewFinding = (findingId: string) => {
  if (!ignoredInterfaceReviewFindingIds.value.includes(findingId)) {
    ignoredInterfaceReviewFindingIds.value = [...ignoredInterfaceReviewFindingIds.value, findingId]
  }
}

const locateInterfaceReviewFinding = (finding: CaseReviewFinding) => {
  const testCase = generatedCases.value.find((item) => finding.id.startsWith(`${item.id}:`))
  if (!testCase) return
  selectedCase.value = testCase
  openEdit(testCase)
}

const transferGeneratedCasesToAutomation = async () => {
  if (generationKind.value === 'web_automation') {
    if (generatedWebCases.value.length === 0) {
      ElMessage.warning('请先生成 Web 用例')
      return
    }
    saveCasesLoading.value = true
    try {
      const previousCases = generatedWebCases.value
      generatedWebCases.value = finalizeDesignDraftCasesForLibrary(generatedWebCases.value)
      const savedResult = await persistGeneratedCasesToLibrary(false)
      if (!savedResult) return
      const automationResult = saveWebAutomationRunCases(
        localStorage,
        generateWebAutomationCasesFromGeneratedCases(generatedWebCases.value)
      )
      refreshSavedCases()
      refreshAutomationCases()
      clearGeneratedCaseWorkspace()
      clearAutomationExecutionState('web')
      resetAutomationFilters()
      automationKind.value = 'web'
      navigateToNav('automation')
      ElMessage.success(`已保存到用例库，并生成 ${automationResult.inserted} 条 Web 执行用例，更新 ${automationResult.updated} 条`)
      generatedWebCases.value = previousCases
    } finally {
      saveCasesLoading.value = false
    }
    return
  }
  if (generatedCases.value.length === 0) {
    ElMessage.warning('请先生成用例')
    return
  }

  saveCasesLoading.value = true
  try {
    const savedResult = await persistGeneratedCasesToLibrary(false)
    if (!savedResult) return
    const automationResult = saveAutomationCases(localStorage, generateAutomationCasesFromInterfaceCases(generatedCases.value))
    refreshSavedCases()
    refreshAutomationCases()
    clearGeneratedCaseWorkspace()
    clearAutomationExecutionState('api')
    resetAutomationFilters()
    navigateToNav('automation')
    ElMessage.success(`已保存到用例库，并生成 ${automationResult.inserted} 条自动化用例，更新 ${automationResult.updated} 条`)
  } finally {
    saveCasesLoading.value = false
  }
}

const exportCsv = () => {
  if (filteredCases.value.length === 0) {
    ElMessage.warning('没有可导出的用例')
    return
  }
  const csv = exportCasesToCsv(
    interfaceFields.value,
    filteredCases.value.map((testCase) => testCase.row as unknown as Record<string, unknown>)
  )
  downloadCsv(`${parsed.value?.title || activeProject.value?.title || '接口用例'}-接口测试用例.csv`, csv)
}

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

const openWebCasePreview = (testCase: GeneratedWebAutomationCase) => {
  selectedCase.value = testCase
  webCasePreviewVisible.value = true
}

const removeGeneratedWebCase = (testCase: GeneratedWebAutomationCase) => {
  generatedWebCases.value = generatedWebCases.value.filter((item) => item.id !== testCase.id)
  caseGenerationConfirmed.value = false
  selectedGeneratedWebCaseIds.value = selectedGeneratedWebCaseIds.value.filter((id) => id !== testCase.id)
  if (selectedCase.value?.id === testCase.id) selectedCase.value = generatedWebCases.value[0]
  invalidateCaseReview()
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
    invalidateCaseReview()
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

const deleteCase = async (testCase: GeneratedInterfaceCase) => {
  const confirmed = await confirmDangerAction('删除生成用例', `确认删除 ${testCase.row.caseId} 吗？`)
  if (!confirmed) return
  generatedCases.value = generatedCases.value.filter((item) => item.id !== testCase.id)
  normalizeGeneratedCaseRunOrder()
  if (selectedCase.value?.id === testCase.id) {
    selectedCase.value = generatedCases.value[0]
  }
}

const moveGeneratedCase = (testCase: GeneratedInterfaceCase, offset: -1 | 1) => {
  const index = generatedCases.value.findIndex((item) => item.id === testCase.id)
  const targetIndex = index + offset
  if (index < 0 || targetIndex < 0 || targetIndex >= generatedCases.value.length) return
  generatedCases.value = moveArrayItem(generatedCases.value, index, targetIndex)
  normalizeGeneratedCaseRunOrder()
}

const loadBackendBackedWorkspace = async (
  options: {
    conflictStrategy?: 'protectLocal' | 'preferBackend'
    projectName?: string
  } = {}
) => {
  const projectName = options.projectName || currentBackendProjectName()
  const [templateResult, caseResult] = await Promise.all([
    loadTemplateConfigWithBackend(localStorage, projectName, {
      conflictStrategy: options.conflictStrategy
    }),
    loadSavedCasesWithBackend(localStorage, projectName, {
      conflictStrategy: options.conflictStrategy
    })
  ])
  templateConfig.value = templateResult.config
  savedCases.value = caseResult.cases
  syncSelectedCaseTypes()
  workspaceSyncConflict.value =
    options.conflictStrategy === 'preferBackend'
      ? undefined
      : buildWorkspaceSyncConflictNotice(projectName, templateResult.source === 'conflict', caseResult.source === 'conflict')
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
  const notice = workspaceSyncConflict.value
  if (!notice) return

  workspaceSyncLoading.value = true
  try {
    let synced = true
    if (notice.template) {
      const result = await saveTemplateConfigWithBackend(localStorage, notice.projectName, templateConfig.value)
      templateConfig.value = result.config
      synced = synced && result.source === 'backend'
    }
    if (notice.cases) {
      const result = await syncCaseOrderingWithBackend(localStorage, notice.projectName)
      synced = synced && result.source === 'backend'
    }

    if (synced) {
      workspaceSyncConflict.value = undefined
      ElMessage.success('已将本地版本同步到后端')
    } else {
      ElMessage.warning('后端暂不可用，已继续保留本地版本')
    }
  } finally {
    workspaceSyncLoading.value = false
  }
}

const reloadBackendWorkspace = async () => {
  const notice = workspaceSyncConflict.value
  if (!notice) return

  const confirmed = await confirmDangerAction(
    '使用后端数据',
    `确认用后端数据覆盖 ${notice.projectName} 当前较新的本地数据吗？`,
    '使用后端'
  )
  if (!confirmed) return

  workspaceSyncLoading.value = true
  try {
    await loadBackendBackedWorkspace({
      conflictStrategy: 'preferBackend',
      projectName: notice.projectName
    })
    ElMessage.success('已加载后端版本')
  } finally {
    workspaceSyncLoading.value = false
  }
}

const normalizeGeneratedCaseRunOrder = () => {
  generatedCases.value = generatedCases.value.map((testCase, index) => ({
    ...testCase,
    metadata: {
      ...testCase.metadata,
      runOrder: index + 1
    }
  }))
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
  webAutomationResultMap.value.get(testCase.id)?.status || 'pending'

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

const persistEnvironmentLibrary = (kind: 'api' | 'web' = environmentEditorKind.value) => {
  if (kind === 'web') {
    saveExecutionEnvironmentLibrary(localStorage, webExecutionEnvironmentLibrary.value, WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY)
    return
  }
  saveExecutionEnvironmentLibrary(localStorage, executionEnvironmentLibrary.value)
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
  environmentEditorTarget.value = kind
  const library = kind === 'web' ? webExecutionEnvironmentLibrary.value : executionEnvironmentLibrary.value
  const targetEnvironmentId = environmentId || library.activeEnvironmentId
  const environment = library.environments.find((item) => item.id === targetEnvironmentId)
    || library.environments[0]
    || (kind === 'web' ? defaultWebExecutionEnvironmentLibrary() : defaultExecutionEnvironmentLibrary()).environments[0]
  Object.assign(editingEnvironment, cloneEnvironment(environment))
  if (kind === 'web') {
    editingEnvironment.auth = editingEnvironment.auth || { type: 'none' }
    editingEnvironment.web = normalizeWebExecutionRuntimeConfig(editingEnvironment.web)
  }
  curlImportText.value = ''
  webEnvironmentVerification.value = undefined
  environmentVisible.value = true
}

const selectExecutionEnvironment = (environmentId: string, kind: 'api' | 'web' = environmentEditorKind.value) => {
  if (kind === 'web') {
    webExecutionEnvironmentLibrary.value = {
      ...webExecutionEnvironmentLibrary.value,
      activeEnvironmentId: environmentId
    }
    persistEnvironmentLibrary('web')
    return
  }
  executionEnvironmentLibrary.value = {
    ...executionEnvironmentLibrary.value,
    activeEnvironmentId: environmentId
  }
  persistEnvironmentLibrary('api')
}

const addExecutionEnvironment = (kind: 'api' | 'web' = environmentEditorKind.value) => {
  const library = kind === 'web' ? webExecutionEnvironmentLibrary.value : executionEnvironmentLibrary.value
  const environment = kind === 'web'
    ? newSavedWebExecutionEnvironment(`Web 环境 ${library.environments.length + 1}`)
    : newSavedExecutionEnvironment(`环境 ${library.environments.length + 1}`)
  const updatedLibrary = {
    activeEnvironmentId: environment.id,
    environments: [...library.environments, environment]
  }
  if (kind === 'web') {
    webExecutionEnvironmentLibrary.value = updatedLibrary
  } else {
    executionEnvironmentLibrary.value = updatedLibrary
  }
  persistEnvironmentLibrary(kind)
  openEnvironmentEditor(environment.id, kind)
}

const deleteExecutionEnvironment = async (environmentId: string, kind: 'api' | 'web' = environmentEditorKind.value) => {
  const library = kind === 'web' ? webExecutionEnvironmentLibrary.value : executionEnvironmentLibrary.value
  if (library.environments.length <= 1) {
    ElMessage.warning('至少保留一个运行环境')
    return
  }
  const target = library.environments.find((environment) => environment.id === environmentId)
  const confirmed = await confirmDangerAction(
    '删除运行环境',
    `确认删除运行环境 ${target?.name || ''} 吗？`
  )
  if (!confirmed) return
  const environments = library.environments.filter((environment) => environment.id !== environmentId)
  const updatedLibrary = {
    activeEnvironmentId: environments[0].id,
    environments
  }
  if (kind === 'web') {
    webExecutionEnvironmentLibrary.value = updatedLibrary
  } else {
    executionEnvironmentLibrary.value = updatedLibrary
  }
  persistEnvironmentLibrary(kind)
  openEnvironmentEditor(environments[0].id, kind)
  ElMessage.success('已删除运行环境')
}

const saveEditingEnvironment = (kind: 'api' | 'web' = environmentEditorKind.value) => {
  const validation = kind === 'web'
    ? validateWebExecutionEnvironment(editingEnvironment)
    : validateExecutionEnvironment(editingEnvironment)
  if (!validation.valid) {
    ElMessage.warning(validation.message)
    return
  }
  const updated = {
    ...cloneEnvironment(editingEnvironment),
    updatedAt: new Date().toISOString()
  }
  if (kind === 'web') {
    updated.web = normalizeWebExecutionRuntimeConfig(updated.web)
    updated.web.cookieOrigins = []
    updated.auth = { type: 'none' }
    updated.entries = []
  }
  const library = kind === 'web' ? webExecutionEnvironmentLibrary.value : executionEnvironmentLibrary.value
  const updatedLibrary = {
    activeEnvironmentId: updated.id,
    environments: library.environments.map((environment) =>
      environment.id === updated.id ? updated : environment
    )
  }
  if (kind === 'web') {
    webExecutionEnvironmentLibrary.value = updatedLibrary
  } else {
    executionEnvironmentLibrary.value = updatedLibrary
  }
  persistEnvironmentLibrary(kind)
  environmentVisible.value = false
  ElMessage.success('运行环境已保存')
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

const applyConfiguredCaseTypeName = (testCase: GeneratedInterfaceCase): GeneratedInterfaceCase => {
  const defaultDefinition = defaultCaseTypes.find((caseType) => caseType.name === testCase.row.caseType)
  const configuredDefinition = defaultDefinition
    ? templateConfig.value.caseTypes.find((caseType) => caseType.id === defaultDefinition.id)
    : undefined
  if (!configuredDefinition || configuredDefinition.name === testCase.row.caseType) return testCase

  return {
    ...testCase,
    row: {
      ...testCase.row,
      caseType: configuredDefinition.name,
      tags: testCase.row.tags.replace(testCase.row.caseType, configuredDefinition.name)
    }
  }
}

const collectWebAutomationProjects = (cases: SavedWebAutomationRunCase[]) => {
  const counts = new Map<string, number>()
  for (const testCase of cases) {
    counts.set(testCase.project, (counts.get(testCase.project) || 0) + 1)
  }
  return Array.from(counts.entries()).map(([value, count]) => ({ value, count }))
}

const collectWebAutomationModules = (cases: SavedWebAutomationRunCase[]) => {
  const counts = new Map<string, number>()
  for (const testCase of cases) {
    counts.set(testCase.module, (counts.get(testCase.module) || 0) + 1)
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

function moveArrayItem<T>(items: T[], from: number, to: number): T[] {
  const next = [...items]
  const [item] = next.splice(from, 1)
  next.splice(to, 0, item)
  return next
}

async function executeBrowserRequest(request: ExecutableRequest) {
  const proxiedResponse = await executeViaLocalProxy(request)
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

function resolveInitialNav(): NavKey {
  if (typeof window === 'undefined') return defaultNavKey
  return navKeyFromPath(window.location.pathname)
}

function resolveInitialAutomationKind(): 'api' | 'web' {
  if (typeof window === 'undefined') return 'api'
  return automationKindFromPath(window.location.pathname)
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

type AuthStatus = 'checking' | 'authenticated' | 'unauthenticated'
const authStatus = ref<AuthStatus>('checking')
const currentUser = ref<AuthUser | null>(null)

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
  changePasswordForm.currentPassword = ''
  changePasswordForm.newPassword = ''
  changePasswordForm.confirmPassword = ''
  changePasswordForm.error = ''
  changePasswordDialogVisible.value = true
}

async function submitChangePassword(): Promise<void> {
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
  try {
    const updated = await changePassword(changePasswordForm.currentPassword, changePasswordForm.newPassword)
    currentUser.value = updated
    changePasswordDialogVisible.value = false
    ElMessage.success('密码已更新')
  } catch (error) {
    changePasswordForm.error = error instanceof Error
      ? error.message.replace(/^Auth request failed: \d+\s*/, '')
      : '修改失败，请重试'
  } finally {
    changePasswordForm.submitting = false
  }
}

function loadWorkspaceData(): void {
  if (restoredCaseDesignDraft) {
    generationKind.value = restoredCaseDesignDraft.generationKind
    requirementAnalysisProjectName.value = restoredCaseDesignDraft.projectName
    stagedRequirementFiles.value = restoredCaseDesignDraft.sources.map(({ name, kind, text, needsReattach }) => ({
      name,
      kind,
      text,
      needsReattach
    }))
    extractedRequirementPoints.value = restoredCaseDesignDraft.extractedRequirementPoints
    aiDraftPoints.value = restoredCaseDesignDraft.aiDraftPoints
    generatedCases.value = restoredCaseDesignDraft.generatedCases
    generatedWebCases.value = restoredCaseDesignDraft.generatedWebCases
    aiAnalysisRun.value = restoredCaseDesignDraft.analysisRun || undefined
    aiAnalysisRunning.value = restoredCaseDesignDraft.analysisRun?.status === 'running'
    if (restoredCaseDesignDraft.analysisRun) {
      const run = restoredCaseDesignDraft.analysisRun
      aiAnalysisProgress.percentage = run.totalChunks > 0
        ? Math.round((run.completedChunks / run.totalChunks) * 100)
        : run.status === 'succeeded' ? 100 : 0
      aiAnalysisProgress.text = run.status === 'running'
        ? `正在恢复内容解析 · ${run.completedChunks}/${run.totalChunks} 分片`
        : run.status === 'succeeded'
          ? '内容解析已完成'
          : run.errorSummary || '内容解析未完成'
    }
    requirementAiReviewRun.value = restoredCaseDesignDraft.requirementReviewRun || undefined
    caseAiReviewRun.value = restoredCaseDesignDraft.caseReviewRun || undefined
    activeAiReviewStage.value = restoredCaseDesignDraft.activeReviewStage
    aiMultiAgentQualityScores.value = restoredCaseDesignDraft.review.qualityScores
    aiMultiAgentConflicts.value = restoredCaseDesignDraft.review.conflicts
    selectedCase.value = restoredCaseDesignDraft.generatedWebCases.find(
      (testCase) => testCase.id === restoredCaseDesignDraft.selectedCaseId
    ) || restoredCaseDesignDraft.generatedWebCases[0]
  }

  syncNavWithLocation('replace')
  refreshAutomationReports()
  void loadBackendBackedWorkspace()
  void refreshAiPlatformConfig()
  void loadJiraSettingsFromBackend()
  void loadWebRunCapabilities()
  void recoverWebRunSession('replace')
  const restoredReviewRun = restoredCaseDesignDraft?.activeReviewStage === 'case'
    ? restoredCaseDesignDraft.caseReviewRun
    : restoredCaseDesignDraft?.requirementReviewRun
  void Promise.allSettled([
    restoredCaseDesignDraft?.analysisRunId
      ? refreshAiAnalysisRunData(restoredCaseDesignDraft.analysisRunId)
      : Promise.resolve(),
    restoredReviewRun?.runId
      ? loadMultiAgentReviewDetails(restoredReviewRun.runId)
      : Promise.resolve()
  ]).then((results) => {
    if (results[0].status === 'rejected') {
      aiAnalysisError.value = results[0].reason instanceof Error
        ? results[0].reason.message
        : '后端暂不可用，已恢复本地 AI 分析草稿'
    }
    if (results[1].status === 'rejected') {
      caseReviewError.value = results[1].reason instanceof Error
        ? results[1].reason.message
        : '后端暂不可用，已恢复本地评审结果'
    }
  })
}

function stopBackgroundActivity(): void {
  webRunPollCoordinator.stop()
  aiAnalysisPollCoordinator.stop()
  webLoginDiscoveryAbortController?.abort()
}

function handleSessionExpired(): void {
  stopBackgroundActivity()
  currentUser.value = null
  authStatus.value = 'unauthenticated'
}

function onLoginSuccess(user: AuthUser): void {
  currentUser.value = user
  authStatus.value = 'authenticated'
  loadWorkspaceData()
  if (user.mustChangePassword) {
    openChangePasswordDialog()
  }
}

async function bootstrapAuth(): Promise<void> {
  try {
    const user = await fetchCurrentUser()
    if (user) {
      currentUser.value = user
      authStatus.value = 'authenticated'
      loadWorkspaceData()
      if (user.mustChangePassword) {
        openChangePasswordDialog()
      }
    } else {
      authStatus.value = 'unauthenticated'
    }
  } catch {
    authStatus.value = 'unauthenticated'
  }
}

async function handleLogout(): Promise<void> {
  stopBackgroundActivity()
  try {
    await logout()
  } catch {
    // Ignore logout failures — always clear the local session so the user can leave.
  }
  currentUser.value = null
  authStatus.value = 'unauthenticated'
}

onMounted(() => {
  setUnauthorizedHandler(handleSessionExpired)
  window.addEventListener('popstate', handlePopState)
  void bootstrapAuth()
})

onBeforeUnmount(() => {
  if (caseDesignDraftTimer) clearTimeout(caseDesignDraftTimer)
  stopBackgroundActivity()
  window.removeEventListener('popstate', handlePopState)
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
    :inspector-visible="inlineInspectorVisible"
  >
    <template #sidebar>
      <PrimarySidebar
        v-if="!isNarrowViewport"
        :groups="sidebarGroups"
        :active-item-id="activeSidebarItemId"
        :collapsed="railCollapsed"
        :badges="sidebarBadges"
        @select="selectSidebarItem"
        @request-expand="expandStudioNavigation"
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
        @toggle-sidebar="toggleStudioNavigation"
        @open-task="openActiveLongTask"
        @logout="handleLogout"
        @change-password="openChangePasswordDialog"
      />
    </template>

    <template #inspector>
      <InterfaceReviewInspector
        v-if="interfaceInspectorVisible"
        :findings="visibleInterfaceReviewFindings"
        :summary="visibleInterfaceReviewSummary"
        :reviewing="false"
        :review-attempted="interfaceReviewAttempted"
        @review="runAiReview"
        @supplement-missing="supplementMissingCases"
        @locate-finding="locateInterfaceReviewFinding"
        @ignore-finding="ignoreInterfaceReviewFinding"
      />
      <AiReviewInspector
        v-else
        :selected-count="selectedReviewCaseCount"
        :high-risk-count="caseDesignReviewView.highRiskCount"
        :pending-finding-count="caseDesignReviewView.pendingFindingCount"
        :average-score="caseDesignReviewView.averageScore"
        :summary="activeAiReviewInsight.summary"
        :findings="caseDesignReviewView.findings"
        :conflicts="caseDesignRelevantConflicts"
        :can-merge="canMergeMultiAgentReview"
        :loading="aiMultiAgentLoading"
        @ignore-conflicts="ignoreUnresolvedMultiAgentConflicts"
        @ignore-conflict="ignoreSingleReviewConflict"
        @merge="mergeMultiAgentReview"
        @decide="decideSelectedReviewFinding"
        @bulk-decide="bulkDecideReviewCases"
      />
    </template>

    <main
      class="workspace"
      :class="{
        'is-home': activeNav === 'home',
        'is-studio-case-design': activeNav === 'api' && generationKind === 'web_automation',
        'is-studio-interface-case': activeNav === 'api' && generationKind === 'interface',
        'is-web-automation': activeNav === 'automation' && automationKind === 'web',
        'is-workbench': isWorkbenchNav
      }"
    >
      <div v-if="workspaceSyncConflict" class="studio-sync-notice" role="status">
        <div class="sync-conflict-copy">
          <strong>本地数据较新</strong>
          <span>{{ workspaceSyncConflictText }}</span>
        </div>
        <div class="sync-conflict-actions">
          <el-button size="small" :loading="workspaceSyncLoading" @click="pushLocalWorkspaceToBackend">
            <Upload :size="14" />
            推送本地
          </el-button>
          <el-button size="small" plain :loading="workspaceSyncLoading" @click="reloadBackendWorkspace">
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
      <WorkflowStateView
        v-if="!isOnline"
        class="studio-offline-notice"
        variant="offline"
        title="当前离线"
        description="草稿仍会保存在本机，联网后可继续同步和恢复长任务"
      />
      <template v-if="isCaseWorkbenchNav">
        <CaseWorkbenchView
          :active-nav="activeNav"
        >
        <template #library>
          <CasesView />
        </template>
        <template #canvas>
        <section v-if="generationKind === 'interface'" class="studio-interface-case-canvas">
          <WorkflowHeader
            :title="interfaceCaseTaskTitle"
            :save-label="interfaceCaseSaveLabel"
            :stages="interfaceCaseWorkflow.stages"
            :primary-action="interfaceCaseWorkflowHeaderPrimaryAction"
            :secondary-action="interfaceCaseSecondaryAction"
            @navigate-stage="navigateInterfaceCaseStage"
            @primary="handleInterfacePrimaryAction"
            @secondary="startNewInterfaceCaseTask"
          />
          <div class="studio-interface-case-canvas__stage">
            <div v-if="isCompactDesktop" class="studio-interface-case-canvas__drawer-actions">
              <el-button data-testid="interface-context-pane-drawer-button" @click="compactContextVisible = true">
                接口与配置
              </el-button>
              <el-button
                v-if="interfaceCaseWorkflow.activeStage === 'review'"
                data-testid="interface-inspector-drawer-button"
                @click="compactInspectorVisible = true"
              >
                AI 评审
              </el-button>
            </div>
            <WorkflowStateView
              v-if="interfaceCaseWorkflow.activeStage === 'import' && importedProjects.length === 0"
              variant="empty"
              title="导入 OpenAPI 开始接口用例设计"
              description="导入后可筛选接口并配置生成策略"
              action-label="导入 OpenAPI"
              @action="pickOpenApiFiles"
            />
            <WorkflowStateView
              v-else-if="interfaceOperationSelectionEmpty"
              variant="filtered-empty"
              title="没有符合条件的接口"
              description="当前筛选条件没有匹配结果"
              action-label="清除筛选"
              @action="resetInterfaceOperationFilters"
            />
            <InterfaceOperationTable
              v-else-if="interfaceOperationSelectionVisible"
              :operations="filteredOperations"
              :selected-ids="selectedOperationIds"
              @toggle-operation="toggleOperation"
              @select-filtered="selectFilteredOperations"
              @clear-selected="clearSelectedOperations"
            />
            <InterfaceCaseTable
              v-else-if="['import', 'generate', 'review'].includes(interfaceCaseWorkflow.activeStage) && filteredCases.length > 0"
              :cases="filteredCases"
              :visible-fields="visibleInterfaceFields"
              :selected-ids="selectedInterfaceCaseIds"
              :keyword="previewFilter.keyword"
              :module="previewFilter.module === 'ALL' ? '' : previewFilter.module"
              :case-type="previewFilter.caseType === 'ALL' ? '' : previewFilter.caseType"
              :priority="previewFilter.priority === 'ALL' ? '' : previewFilter.priority"
              :review-status="interfaceReviewStatusFilter === 'ALL' ? '' : interfaceReviewStatusFilter"
              :module-options="previewModules"
              :case-type-options="previewCaseTypes"
              :priority-options="previewPriorities"
              :review-status-options="previewReviewStatuses"
              @selection-change="selectedInterfaceCaseIds = $event"
              @update-keyword="previewFilter.keyword = $event"
              @update-module="previewFilter.module = $event || 'ALL'"
              @update-case-type="previewFilter.caseType = $event || 'ALL'"
              @update-priority="previewFilter.priority = $event || 'ALL'"
              @update-review-status="interfaceReviewStatusFilter = $event || 'ALL'"
              @clear-filters="resetInterfaceCaseFilters"
              @activate="selectedCase = $event"
              @move="moveGeneratedCase"
              @edit="openEdit"
              @remove="deleteCase"
              @bulk-review="runAiReview"
            />
            <WorkflowStateView
              v-else-if="['import', 'generate', 'review'].includes(interfaceCaseWorkflow.activeStage) && generatedCases.length > 0"
              variant="filtered-empty"
              title="没有符合条件的用例"
              description="当前筛选条件没有匹配结果"
              action-label="清除筛选"
              @action="resetInterfaceCaseFilters"
            />
            <WorkflowStateView
              v-else-if="interfaceCaseWorkflow.activeStage === 'generate'"
              variant="empty"
              title="选择接口并生成用例"
              description="使用左侧接口与生成配置选择范围"
            />
            <InterfaceCaseDeliveryStage
              v-else-if="interfaceCaseWorkflow.activeStage === 'deliver'"
              :generated-count="generatedCases.length"
              :review-attempted="interfaceReviewAttempted"
              :review-finding-count="visibleInterfaceReviewFindings.length"
              :saving="saveCasesLoading"
              @save="saveCases"
              @transfer="transferGeneratedCasesToAutomation"
              @export="exportCsv"
            />
          </div>
        </section>

        <section v-else class="studio-case-design-canvas">
          <WorkflowHeader
            :title="caseDesignStageTitle"
            :save-label="caseDesignSaveLabel"
            :stages="caseDesignWorkflow.stages"
            :primary-action="caseDesignHeaderPrimaryAction"
            @navigate-stage="navigateCaseDesignStage"
            @primary="handleCaseDesignPrimaryAction"
          />
          <div class="studio-case-design-canvas__stage">
          <AiPolicyDisclosure
            v-if="showAiPolicyDisclosure && !aiPolicyDisclosureDismissed"
            :policy="aiRequirementPolicy"
            :ready="aiConfigReady"
            :error="aiRunPoliciesError"
            @view-details="activeNav = 'aiConfig'"
            @dismiss="aiPolicyDisclosureDismissed = true"
          />
          <template v-if="caseDesignWorkflow.activeStage === 'requirement'">
            <RequirementImportStage
              :sources="caseDesignSourceView"
              :analysis-status="aiAnalysisRunning ? 'running' : aiAnalysisError ? 'failed' : aiAnalysisCompleted ? 'succeeded' : 'idle'"
              :progress-percentage="aiAnalysisProgress.percentage"
              :progress-text="aiAnalysisProgress.text"
              :error-message="aiAnalysisError"
              :parsed-items="caseDesignParsedItems"
              :completed-source-count="aiAnalysisCompleted ? readyRequirementSourceCount : 0"
              :total-source-count="readyRequirementSourceCount"
              :analysis-log="aiAnalysisLog"
              :compact="isCompactDesktop"
              @pick-files="pickWebCaseFiles"
              @import-figma="promptFigmaDesignImport"
              @retry-source="retryRequirementSource"
              @replace-source="replaceRequirementSource"
              @remove-source="removeRequirementSource"
              @cancel-analysis="cancelCurrentAiAnalysisRun"
            />
            <AiRunSummaryView
              v-if="showAiRunSummary"
              :summary="aiAnalysisRun?.outputSummary"
              :status="aiAnalysisRun?.status"
            />
          </template>
          <TestPointParseStage
            v-else-if="caseDesignWorkflow.activeStage === 'test-parse'"
            :points="aiDraftPoints"
            :selected-ids="generationScopePointIds"
            :pending-count="pendingAiDraftPoints.length"
            :confirming="aiDraftBulkConfirming"
            @update-selection="generationScopePointIds = $event"
            @confirm-all="confirmAiDraftPoints(pendingAiDraftPoints, '确认测试点')"
            @confirm-one="confirmAiDraftPoint"
            @edit-point="editAiDraftPointFromDetails"
            @delete-point="deleteAiDraftPoint"
          />
          <NaturalLanguageCaseGenerationStage
            v-else-if="caseDesignWorkflow.activeStage === 'case-generate'"
            :status="aiCaseGenerationRunning ? 'running' : aiGenerationError ? 'failed' : aiCaseGenerationCompleted ? 'succeeded' : 'idle'"
            :progress-percentage="aiCaseGenerationProgress.percentage"
            :progress-text="aiCaseGenerationProgress.text"
            :error-message="aiGenerationError"
            :cases="generatedWebCases"
            :selected-ids="selectedGeneratedWebCaseIds"
            :project-id="webCaseAgentRun?.projectId"
            :retryable-point-count="generationRetryPointIds.length"
            :generatable-point-count="scopedAiDraftPoints.length"
            @generate="generateNaturalLanguageCasesWithAi()"
            @retry="generateNaturalLanguageCasesWithAi(true)"
            @cancel="cancelAiCaseGeneration"
            @selection-change="selectedGeneratedWebCaseIds = $event"
            @activate="selectedCase = $event"
            @preview="openWebCasePreview"
            @edit="openWebEdit($event)"
            @remove="removeGeneratedWebCase"
          />
          <AutomationCompileStage
            v-else-if="caseDesignWorkflow.activeStage === 'automation-compile'"
            :status="caseDesignWorkflow.automationCompileStatus"
            :progress-text="aiCaseGenerationProgress.text"
            :cases="generatedWebCases"
            :selected-ids="selectedGeneratedWebCaseIds"
            :run="webCaseAgentRun"
            :events="webCaseAgentEvents"
            :natural-plan-count="webCaseAgentNaturalPlanCount"
            @compile="compileWebAutomationCases()"
            @cancel="cancelAiCaseGeneration"
            @retry="compileWebAutomationCases(true)"
            @resume="compileWebAutomationCases(true)"
            @selection-change="selectedGeneratedWebCaseIds = $event"
            @activate="selectedCase = $event"
            @preview="openWebCasePreview"
            @edit="openWebEdit($event)"
            @remove="removeGeneratedWebCase"
            @configure-environment="openEnvironmentEditor(undefined, 'web')"
          />
          <section v-else-if="caseDesignWorkflow.activeStage === 'review'" class="studio-review-stage">
            <el-button v-if="isCompactDesktop && caseDesignContextVisible" data-testid="context-pane-drawer-button" @click="compactContextVisible = true">查看需求与模块</el-button>
            <el-button v-if="isCompactDesktop && caseDesignWorkflow.activeStage === 'review'" data-testid="inspector-drawer-button" @click="compactInspectorVisible = true">查看 AI 评审</el-button>
            <header class="studio-review-stage__toolbar">
              <div class="studio-review-stage__filters">
                <el-select v-model="webPreviewFilter.module" aria-label="评审模块">
                  <el-option label="全部模块" value="ALL" />
                  <el-option v-for="module in webPreviewModules" :key="module.value" :label="module.value" :value="module.value" />
                </el-select>
                <el-select v-model="webPreviewFilter.caseType" aria-label="评审用例类型">
                  <el-option label="全部类型" value="ALL" />
                  <el-option v-for="caseType in webPreviewCaseTypes" :key="caseType.value" :label="caseType.value" :value="caseType.value" />
                </el-select>
                <el-input v-model="webPreviewFilter.keyword" clearable placeholder="搜索 Web 用例" />
              </div>
              <el-button class="studio-review-stage__review-button" type="primary" :loading="aiMultiAgentLoading && activeAiReviewStage === 'case'" @click="startCaseAiReview">
                <Sparkles :size="15" />{{ caseAiReviewRun ? '重新评审' : '启动 AI 评审' }}
              </el-button>
            </header>
            <div v-if="caseReviewError" class="studio-generation-status is-failed" role="status"><span>{{ caseReviewError }}</span></div>
            <GeneratedWebCaseTable
              v-if="filteredWebCases.length"
              :cases="filteredWebCases"
              :selected-ids="selectedGeneratedWebCaseIds"
              :project-id="caseAiReviewRun?.projectId"
              table-height="100%"
              selectable
              @selection-change="selectedGeneratedWebCaseIds = $event"
              @activate="selectedCase = $event"
              @preview="openWebCasePreview"
              @edit="openWebEdit($event)"
              @remove="removeGeneratedWebCase"
            />
            <WorkflowStateView v-else variant="filtered-empty" title="没有符合条件的用例" description="当前筛选条件没有匹配结果" action-label="清除筛选" @action="Object.assign(webPreviewFilter, { module: 'ALL', caseType: 'ALL', keyword: '' })" />
          </section>
          <CaseDeliveryStage
            v-else-if="caseDesignWorkflow.activeStage === 'deliver'"
            v-model:mode="deliveryMode"
            v-model:execute-now="deliveryExecuteNow"
            :automation-kind="deliveryAutomationKind"
            :generated-count="generatedWebCases.length"
            :selected-automation-count="selectedWebCasesForAutomation.length"
            :environment-name="activeDeliveryEnvironment?.name || ''"
            :preflight="caseDesignDeliveryView"
            @configure-environment="openEnvironmentEditor(undefined, deliveryAutomationKind)"
          />
          </div>
        </section>
        </template>
        </CaseWorkbenchView>
      </template>

      <template v-else-if="activeNav === 'home'">
        <HomeView @navigate="handleHomeNavigate" />
      </template>

      <template v-else-if="isSettingsCenterNav">
        <SettingsCenterView
          :active-nav="activeNav"
          :role="currentUser?.role"
          :form="jiraConfigForm"
          :loading="jiraConfigLoading"
          :testing="jiraConfigTesting"
          :project-jql-rows="jiraProjectJqlRows"
          :load="loadJiraSettingsFromBackend"
          :save="saveJiraSettings"
          :test="testJiraConnection"
          :add-jql-row="addJiraProjectJqlRow"
          :remove-jql-row="removeJiraProjectJqlRow"
          :sync-jql-form="syncJiraProjectJqlFormFromRows"
          :current-username="currentUser?.username || ''"
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
        <header v-if="automationKind === 'api'" class="topbar automation-topbar">
          <div class="automation-topbar__title">
            <h1>自动化</h1>
            <div class="automation-topbar__runtime" aria-label="API 运行状态">
              <span class="automation-topbar__runtime-item">
                <small>运行环境</small>
                <strong>{{ activeApiExecutionEnvironment?.name || '未选择' }}</strong>
              </span>
              <span class="automation-topbar__runtime-item">
                <small>执行方式</small>
                <strong>{{ automationRunMode === 'sequential' ? '顺序' : automationRunMode === 'concurrent' ? '并发' : '按模块' }}</strong>
              </span>
              <span class="automation-topbar__runtime-item">
                <small>循环次数</small>
                <strong>{{ apiAutomationLoopCount }} 次</strong>
              </span>
              <span class="automation-topbar__runtime-item">
                <small>并发数</small>
                <strong>{{ automationConcurrencyLimit }}</strong>
              </span>
              <span class="automation-topbar__runtime-item">
                <small>返回体一致度</small>
                <strong>{{ responseBodyMatchThreshold }}%</strong>
              </span>
            </div>
          </div>
          <div class="toolbar">
            <el-button type="primary" plain @click="automationConfigVisible = true">
              <Settings :size="16" />
              运行配置
            </el-button>
            <el-button :disabled="!canEdit" @click="openAutomationGenerateDialog">
              <Archive :size="16" />
              从用例库生成
            </el-button>
            <el-button v-if="automationKind === 'api'" type="success" :loading="apiAutomationRunning" :disabled="!canEdit" @click="executeAllAutomationCases">
              <Play :size="16" />
              执行全部
            </el-button>
            <el-button @click="openReportsModule">
              <FileText :size="16" />
              查看报告
            </el-button>
            <el-button type="primary" @click="exportAutomationJson">
              <Download :size="16" />
              导出用例
            </el-button>
          </div>
        </header>

        <section
          v-if="automationKind === 'api'"
          class="automation-workspace"
          :class="{ 'automation-workspace--with-progress': apiAutomationRunning || apiAutomationProgress.total > 0 }"
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

          <div v-if="apiAutomationRunning || apiAutomationProgress.total > 0" class="automation-progress-strip">
            <div class="automation-progress-meta">
              <strong>执行进度</strong>
              <span>{{ apiAutomationProgress.completed }} / {{ apiAutomationProgress.total }}</span>
              <code v-if="apiAutomationProgress.runningCaseId">{{ apiAutomationProgress.runningCaseId }}</code>
            </div>
            <el-progress
              :percentage="apiAutomationProgress.total ? Math.round((apiAutomationProgress.completed / apiAutomationProgress.total) * 100) : 0"
              :stroke-width="8"
              :show-text="false"
            />
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
              <div class="execution-control-group execution-control-group-count execution-control-group-count-select">
                <span>{{ automationRunMode === 'concurrent' ? '并发数' : '循环次数' }}</span>
                <div class="execution-control-row">
                  <el-segmented
                    v-model="apiRunCountSelection"
                    class="automation-config-count-segmented"
                    :options="automationRunMode === 'concurrent' ? apiConcurrencyOptions : apiLoopCountOptions"
                  />
                </div>
              </div>
            </div>
          </div>
        </ResponsiveDrawer>
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
          @execute="startVisualWebAutomationRun"
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
          @rerun="startVisualWebAutomationRun"
          @open-report="openActiveWebRunReport"
          @save-suite="saveCurrentWebRunSuite"
          @apply-suite="applyWebRunSuite"
          @delete-suite="deleteWebRunSuiteByName"
          @retry-failed="retryFailedWebRunCases"
        />
        </template>
        </ExecutionWorkbenchView>
      </template>

      <section v-else class="future-workspace">
        <component :is="currentNav.icon" :size="48" />
        <h1>{{ currentNav.label }}</h1>
        <el-tag type="info">后端后期补充</el-tag>
      </section>
    </main>
  </AppShell>

  <input
    ref="openApiFileInput"
    class="file-input-proxy"
    type="file"
    multiple
    accept=".json,application/json"
    aria-label="导入 OpenAPI"
    @change="onFileSelected"
  />
  <input
    ref="webCaseFileInput"
    class="file-input-proxy"
    type="file"
    :multiple="!pendingRequirementSourcePick?.replaceName"
    :accept="requirementSourceInputAccept"
    aria-label="选择需求文件"
    @change="onWebCaseFileSelected"
  />

  <ResponsiveDrawer v-model="narrowNavigationVisible" title="主导航" size="168px" direction="ltr">
    <PrimarySidebar
      :groups="sidebarGroups"
      :active-item-id="activeSidebarItemId"
      :collapsed="false"
      :badges="sidebarBadges"
      @select="selectSidebarItem($event); narrowNavigationVisible = false"
    />
  </ResponsiveDrawer>

  <ResponsiveDrawer
    v-model="compactInspectorVisible"
    :title="interfaceInspectorVisible ? 'AI 评审' : 'AI 质量评审'"
    size="min(88vw, 360px)"
  >
    <InterfaceReviewInspector
      v-if="interfaceInspectorVisible"
      :findings="visibleInterfaceReviewFindings"
      :summary="visibleInterfaceReviewSummary"
      :reviewing="false"
      :review-attempted="interfaceReviewAttempted"
      @review="runAiReview"
      @supplement-missing="supplementMissingCases"
      @locate-finding="locateInterfaceReviewFinding"
      @ignore-finding="ignoreInterfaceReviewFinding"
    />
    <AiReviewInspector
      v-else
      :selected-count="selectedReviewCaseCount"
      :high-risk-count="caseDesignReviewView.highRiskCount"
      :pending-finding-count="caseDesignReviewView.pendingFindingCount"
      :average-score="caseDesignReviewView.averageScore"
      :summary="activeAiReviewInsight.summary"
      :findings="caseDesignReviewView.findings"
      :conflicts="caseDesignRelevantConflicts"
      :can-merge="canMergeMultiAgentReview"
      :loading="aiMultiAgentLoading"
      @ignore-conflicts="ignoreUnresolvedMultiAgentConflicts"
      @ignore-conflict="ignoreSingleReviewConflict"
      @merge="mergeMultiAgentReview"
      @decide="decideSelectedReviewFinding"
      @bulk-decide="bulkDecideReviewCases"
    />
  </ResponsiveDrawer>

  <ResponsiveDrawer
    v-model="compactContextVisible"
    :title="interfaceContextVisible ? '接口与配置' : '需求与模块'"
    size="min(82vw, 320px)"
    direction="ltr"
  >
    <InterfaceCaseContextPane
      v-if="interfaceContextVisible"
      :module="importFilter.module"
      :module-options="interfaceModuleOptions"
      :path-keyword="importFilter.pathKeyword"
      :summary-keyword="importFilter.summaryKeyword"
      :method="importFilter.method"
      :template-id="generationTemplateId"
      :template-options="generationTemplateOptions"
      :granularity="generation.granularity"
      @update-module="importFilter.module = $event"
      @update-path-keyword="importFilter.pathKeyword = $event"
      @update-summary-keyword="importFilter.summaryKeyword = $event"
      @update-method="importFilter.method = $event"
      @update-template-id="generationTemplateId = $event"
      @update-granularity="generation.granularity = $event"
    />
    <CaseDesignContextPane
      v-else
      :sources="caseDesignSourceView"
      :modules="aiDraftPointView.moduleOptions"
      :active-module="caseDesignActiveModule"
      :active-source="activeCaseDesignSource"
      @select-source="selectCaseDesignSource"
      @select-module="selectCaseDesignModule"
    />
  </ResponsiveDrawer>

    <ResponsiveDrawer v-model="webCasePreviewVisible" size="560px" title="用例预览">
      <article v-if="previewedWebCase" class="studio-web-case-preview" data-testid="web-case-preview">
        <header>
          <span>{{ previewedWebCase.row.caseId }}</span>
          <strong>{{ previewedWebCase.row.title }}</strong>
          <small>{{ previewedWebCase.row.module }} · {{ previewedWebCase.row.pageName }} · {{ previewedWebCase.row.priority }}</small>
        </header>
        <dl>
          <div><dt>访问地址</dt><dd>{{ previewedWebCase.row.startUrl || '-' }}</dd></div>
          <div><dt>浏览器</dt><dd>{{ previewedWebCase.row.browser || '-' }} · {{ previewedWebCase.row.viewport || '-' }}</dd></div>
          <div><dt>前置条件</dt><dd>{{ previewedWebCase.row.precondition || '-' }}</dd></div>
          <div><dt>自然语言步骤</dt><dd class="studio-web-case-preview__multiline">{{ formatNaturalLanguageText(previewedWebCase.row.naturalSteps) || '未生成自然语言步骤，请重新生成该用例' }}</dd></div>
          <div><dt>自然语言预期</dt><dd class="studio-web-case-preview__multiline">{{ formatNaturalLanguageText(previewedWebCase.row.naturalExpectedResult) || '未生成自然语言预期，请重新生成该用例' }}</dd></div>
          <div><dt>自动化断言</dt><dd class="studio-web-case-preview__multiline">{{ previewedWebCase.row.assertions || '-' }}</dd></div>
        </dl>
        <section>
          <header><strong>执行步骤</strong><span>{{ previewedWebCase.metadata.steps.length }} 步</span></header>
          <ol>
            <li v-for="step in previewedWebCase.metadata.steps" :key="step.id"><code>{{ step.action }}</code><span>{{ step.target }}</span></li>
          </ol>
        </section>
      </article>
    </ResponsiveDrawer>

    <ResponsiveDrawer v-model="editVisible" :size="selectedCase && 'pageName' in selectedCase.row ? '820px' : '520px'" title="编辑用例">
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

    <el-drawer v-model="curlVisible" size="620px" title="可执行请求">
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

    <el-drawer
      v-model="environmentVisible"
      class="web-automation-drawer"
      :class="{ 'is-web-environment': environmentEditorKind === 'web' }"
      size="860px"
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
            <el-button @click="environmentVisible = false">取消</el-button>
            <el-button type="primary" @click="saveEditingEnvironment()">保存环境</el-button>
          </div>
        </section>
      </div>
    </el-drawer>

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

    <el-dialog v-model="automationGenerateVisible" class="web-automation-dialog" title="从用例库生成自动化用例" width="520px">
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
</style>

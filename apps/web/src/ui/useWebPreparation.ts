import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { normalizeWebExecutionRuntimeConfig, type BuiltWebExecutionRuntime, type SavedExecutionEnvironment, type WebRuntimeBrowser } from '../domain/executionEnvironmentRepository'
import { type SavedWebAutomationRunCase } from '../domain/webAutomationRepository'
import { type WebAutomationRunMode, type WebAutomationRunStatus } from '../domain/webAutomation'
import { WebAutomationRunRequestError, getWebAutomationCapabilities, type WebAutomationCapabilities } from '../domain/webAutomationRunRepository'
import { deleteWebRunSuite, listWebRunSuites, saveWebRunSuite, type SavedWebRunSuite } from '../domain/webRunSuiteRepository'
import { type WebRunStartRequest } from '../domain/webExecutionPreparation'
import { ensureBackendProject } from '../domain/platformBackendRepository'
import type { WebCaseStatusFilter } from '../components/automation/WebAutomationCaseTable.vue'
import type { WebAutomationAction } from '../domain/webAutomationCase'
import type { Ref } from 'vue'
import type { ComputedRef } from 'vue'
import type { ShallowRef } from 'vue'
import type { WebAutomationBrowser } from '../domain/webAutomationCase'
import type { ExecutionEnvironmentLibrary } from '../domain/executionEnvironmentRepository'
import type { WebAutomationMockOutcome } from '../domain/webAutomation'
import type { WebAutomationAuthenticationMode } from '../domain/webAutomation'
import type { FetchLike } from '../domain/http'
import type { WebAutomationEffectiveScope } from '../domain/webAutomationWorkspaceView'
import type { EnvironmentKind } from './appPorts'

interface Dependencies {
  localStorage: Storage
  activeWebRunCases: ShallowRef<SavedWebAutomationRunCase[]>
  webAutomationResultStatusById: ComputedRef<Record<string, WebAutomationRunStatus | "pending">>
  automationFilter: { project: string; module: string; method: string; source: "ALL" | "design" | "default"; keyword: string; }
  webAutomationStatusFilter: Ref<WebCaseStatusFilter, WebCaseStatusFilter>
  webAutomationRunning: Ref<boolean, boolean>
  startVisualWebAutomationRun: (explicitCases?: SavedWebAutomationRunCase[], confirmedRequest?: WebRunStartRequest) => Promise<boolean>
  webRunCreationError: Ref<string, string>
  webRunInterruptionMessage: Ref<string, string>
  webAutomationSelectedIds: Ref<string[], string[]>
  filteredWebAutomationCases: { value: { automationLibraryId: string; savedAt: string; id: string; sourceCaseId: string; project: string; module: string; pageName: string; name: string; startUrl: string; environmentId?: string | undefined; browser: WebAutomationBrowser; viewport: string; authenticationMode: WebAutomationAuthenticationMode; steps: { id: string; name: string; action: WebAutomationAction; targetType: "url" | "locator" | "elementRef"; target: string; value?: string | undefined; timeoutMs?: number | undefined; screenshot?: "none" | "on_failure" | "always" | undefined; }[]; actions?: { [x: string]: unknown; id: string; type: string; target?: (Record<string, unknown> | string) | undefined; assertion?: (Record<string, unknown> | string) | undefined; value?: (Record<string, unknown> | string) | undefined; safety: string; evidenceId?: string | undefined; resolvedTarget?: (Record<string, unknown> | string) | undefined; }[] | undefined; runOrder: number; mockOutcome: WebAutomationMockOutcome; agentRunId?: string | undefined; agentSourcePointId?: string | undefined; agentEnvironmentId?: string | undefined; agentBaseUrl?: string | undefined; agentAuthenticationMode?: string | undefined; agentEvidenceIds?: string[] | undefined; agentPageKey?: string | undefined; agentPageFingerprint?: string | undefined; agentStepTraces?: { stepIndex: number; naturalStep: string; intent: string; dsl: string[]; evidenceIds: string[]; pageFingerprint: string; sourcePointId: string; }[] | undefined; agentExpectedResultEvidenceIds?: string[] | undefined; artifactId?: string | undefined; artifactRevision?: number | undefined; artifactResultOrigin?: string | undefined; artifactAcceptanceState?: string | undefined; artifactModelProfileVersion?: string | undefined; artifactFieldOrigins?: string[] | undefined; artifactEvidenceIds?: string[] | undefined; artifactParentArtifactId?: string | undefined; artifactIdempotencyKey?: string | undefined; }[] }
  selectExecutionEnvironment: (environmentId: string, kind?: EnvironmentKind) => void
  webExecutionEnvironmentLibrary: Ref<ExecutionEnvironmentLibrary, ExecutionEnvironmentLibrary>
  persistEnvironmentLibrary: (kind?: EnvironmentKind) => void
  webAutomationLoopCount: Ref<number, number>
  beginWorkspaceOperation: (resource: string, templateLease?: boolean) => () => boolean
  webRunCapabilities: ShallowRef<WebAutomationCapabilities | undefined>
  automationKind: Ref<"api" | "web", "api" | "web">
  workspaceFetch: (isCurrent: () => boolean) => FetchLike
  effectiveWebAutomationScope: ComputedRef<WebAutomationEffectiveScope>
  activeWebExecutionEnvironment: ComputedRef<SavedExecutionEnvironment | undefined>
  runnableWebRuntime: ComputedRef<BuiltWebExecutionRuntime | undefined>
  webAutomationRunMode: Ref<WebAutomationRunMode, WebAutomationRunMode>
}

export function createWebPreparation(deps: Dependencies) {

const webRunSuites = ref<SavedWebRunSuite[]>(listWebRunSuites(deps.localStorage))

const webRunRetryFailedCount = computed(() =>
  deps.activeWebRunCases.value.filter((item) => {
    const status = String(deps.webAutomationResultStatusById.value[item.id] || '')
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
    webRunSuites.value = saveWebRunSuite(deps.localStorage, {
      name,
      project: deps.automationFilter.project,
      module: deps.automationFilter.module,
      source: deps.automationFilter.source,
      status: deps.webAutomationStatusFilter.value,
      keyword: deps.automationFilter.keyword || '',
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
  deps.automationFilter.project = suite.project
  deps.automationFilter.module = suite.module
  deps.automationFilter.source = suite.source as typeof deps.automationFilter.source
  deps.automationFilter.keyword = suite.keyword
  deps.webAutomationStatusFilter.value = suite.status as WebCaseStatusFilter
}

const deleteWebRunSuiteByName = (name: string) => {
  webRunSuites.value = deleteWebRunSuite(deps.localStorage, name)
}

const retryFailedWebRunCases = () => {
  if (deps.webAutomationRunning.value) return
  const failedCases = deps.activeWebRunCases.value.filter((item) => {
    const status = String(deps.webAutomationResultStatusById.value[item.id] || '')
    return status === 'failed' || status === 'error'
  })
  if (!failedCases.length) return
  void deps.startVisualWebAutomationRun(failedCases)
}

const switchAutomationProject = (project: string) => {
  deps.automationFilter.project = project
  deps.automationFilter.module = 'ALL'
  pruneWebAutomationSelection()
  clearWebRunLocalMessages()
}

const switchWebAutomationModule = (module: string) => {
  deps.automationFilter.module = module
  pruneWebAutomationSelection()
  clearWebRunLocalMessages()
}

const loadWebRunCapabilities = async () => {
  const isCurrent = deps.beginWorkspaceOperation('capabilities')
  if (!isCurrent()) return
  try {
    const capabilities = await getWebAutomationCapabilities()
    if (isCurrent()) deps.webRunCapabilities.value = capabilities
  } catch (error) {
    if (!isCurrent()) return
    deps.webRunCapabilities.value = undefined
    if (deps.automationKind.value === 'web') {
      const message = error instanceof WebAutomationRunRequestError
        ? error.toFallbackMessage()
        : error instanceof Error ? error.message : '无法获取 Web 浏览器能力'
      ElMessage.warning(message)
    }
  }
}

const clearWebRunLocalMessages = () => {
  deps.webRunCreationError.value = ''
  deps.webRunInterruptionMessage.value = ''
}

const toggleWebAutomationCase = (testCase: SavedWebAutomationRunCase, checked: boolean) => {
  const next = new Set(deps.webAutomationSelectedIds.value)
  checked ? next.add(testCase.id) : next.delete(testCase.id)
  deps.webAutomationSelectedIds.value = Array.from(next)
  clearWebRunLocalMessages()
}

const selectFilteredWebAutomationCases = () => {
  deps.webAutomationSelectedIds.value = deps.filteredWebAutomationCases.value.map((item) => item.id)
  clearWebRunLocalMessages()
}

const clearWebAutomationSelection = () => {
  deps.webAutomationSelectedIds.value = []
  clearWebRunLocalMessages()
}

const pruneWebAutomationSelection = () => {
  const visible = new Set(deps.filteredWebAutomationCases.value.map((item) => item.id))
  deps.webAutomationSelectedIds.value = deps.webAutomationSelectedIds.value.filter((id) => visible.has(id))
}

const clearWebAutomationFilters = () => {
  deps.automationFilter.source = 'ALL'
  deps.automationFilter.keyword = ''
  deps.webAutomationStatusFilter.value = 'ALL'
  clearWebRunLocalMessages()
}

const updateWebAutomationSourceFilter = (value: 'ALL' | 'design' | 'default') => {
  deps.automationFilter.source = value
  clearWebRunLocalMessages()
}

const updateWebAutomationStatusFilter = (value: WebCaseStatusFilter) => {
  deps.webAutomationStatusFilter.value = value
  clearWebRunLocalMessages()
}

const updateWebAutomationKeyword = (value: string) => {
  deps.automationFilter.keyword = value
  clearWebRunLocalMessages()
}

const updateWebAutomationEnvironment = (environmentId: string) => {
  deps.selectExecutionEnvironment(environmentId, 'web')
  clearWebRunLocalMessages()
}

const updateWebAutomationBrowser = (browser: string) => {
  const nextBrowser = browser as WebRuntimeBrowser
  const activeId = deps.webExecutionEnvironmentLibrary.value.activeEnvironmentId
  deps.webExecutionEnvironmentLibrary.value = {
    ...deps.webExecutionEnvironmentLibrary.value,
    environments: deps.webExecutionEnvironmentLibrary.value.environments.map((environment) => {
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
  deps.persistEnvironmentLibrary('web')
  clearWebRunLocalMessages()
}

const updateWebAutomationLoopCount = (value: number) => {
  deps.webAutomationLoopCount.value = value
  clearWebRunLocalMessages()
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
    const project = await ensureBackendProject(projectNames[0], { fetchImpl: deps.workspaceFetch(isCurrent) })
    return project.id
  } catch {
    return undefined
  }
}

/** 发起瞬间的 live 值 → 请求快照（只在第一个 await 之前调用）。 */
const captureWebRunStartRequest = (): WebRunStartRequest => ({
  scopeLabel: deps.effectiveWebAutomationScope.value.actionLabel,
  selectedCaseIds: [...deps.webAutomationSelectedIds.value],
  loopCount: deps.webAutomationLoopCount.value,
  environmentId: deps.activeWebExecutionEnvironment.value?.id || '',
  environmentName: deps.activeWebExecutionEnvironment.value?.name || '',
  environmentBaseUrl: deps.activeWebExecutionEnvironment.value?.baseUrl || '',
  browser: deps.runnableWebRuntime.value?.browser || 'follow-case',
  mode: deps.webAutomationRunMode.value
})

const reset = () => { webRunSuites.value = [] }
return {
  reset,
  webRunSuites,
  webRunRetryFailedCount,
  saveCurrentWebRunSuite,
  applyWebRunSuite,
  deleteWebRunSuiteByName,
  retryFailedWebRunCases,
  switchAutomationProject,
  switchWebAutomationModule,
  loadWebRunCapabilities,
  clearWebRunLocalMessages,
  toggleWebAutomationCase,
  selectFilteredWebAutomationCases,
  clearWebAutomationSelection,
  pruneWebAutomationSelection,
  clearWebAutomationFilters,
  updateWebAutomationSourceFilter,
  updateWebAutomationStatusFilter,
  updateWebAutomationKeyword,
  updateWebAutomationEnvironment,
  updateWebAutomationBrowser,
  updateWebAutomationLoopCount,
  resolveVisualWebAutomationRunProjectId,
  captureWebRunStartRequest,
}
}

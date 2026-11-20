import { computed } from 'vue'
import { normalizeWebExecutionRuntimeConfig, validateWebExecutionEnvironment, type BuiltWebExecutionRuntime, type SavedExecutionEnvironment } from '../domain/executionEnvironmentRepository'
import { type SavedWebAutomationRunCase } from '../domain/webAutomationRepository'
import { type WebAutomationRunReport, type WebAutomationRunStatus } from '../domain/webAutomation'
import { type WebAutomationCapabilities, type WebAutomationRunSnapshot } from '../domain/webAutomationRunRepository'
import { buildBrowserOptions, buildWebRunView, buildWebRunCaseStatusById } from '../domain/webAutomationRunView'
import { buildWebAutomationPreflight, collectWebAutomationModuleCounts } from '../domain/webAutomationWorkspaceView'
import type { WebCaseStatusFilter } from '../components/automation/WebAutomationCaseTable.vue'
import type { WebAutomationAction } from '../domain/webAutomationCase'
import type { Ref } from 'vue'
import type { ComputedRef } from 'vue'
import type { ShallowRef } from 'vue'
import type { WebAutomationBrowser } from '../domain/webAutomationCase'
import type { ExecutionEnvironmentLibrary } from '../domain/executionEnvironmentRepository'
import type { WebAutomationMockOutcome } from '../domain/webAutomation'
import type { WebAutomationAuthenticationMode } from '../domain/webAutomation'
import type { WebAutomationEffectiveScope } from '../domain/webAutomationWorkspaceView'

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
  effectiveWebAutomationScope: ComputedRef<WebAutomationEffectiveScope>
  webRunCapabilities: ShallowRef<WebAutomationCapabilities | undefined>
  runnableWebRuntime: ComputedRef<BuiltWebExecutionRuntime | undefined>
  activeWebExecutionEnvironment: ComputedRef<SavedExecutionEnvironment | undefined>
  webRunCreationError: Ref<string, string>
  webRunInterruptionMessage: Ref<string, string>
  webAutomationProjectCases: { value: { automationLibraryId: string; savedAt: string; id: string; sourceCaseId: string; project: string; module: string; pageName: string; name: string; startUrl: string; environmentId?: string | undefined; browser: WebAutomationBrowser; viewport: string; authenticationMode: WebAutomationAuthenticationMode; steps: { id: string; name: string; action: WebAutomationAction; targetType: "url" | "locator" | "elementRef"; target: string; value?: string | undefined; timeoutMs?: number | undefined; screenshot?: "none" | "on_failure" | "always" | undefined; }[]; actions?: { [x: string]: unknown; id: string; type: string; target?: (Record<string, unknown> | string) | undefined; assertion?: (Record<string, unknown> | string) | undefined; value?: (Record<string, unknown> | string) | undefined; safety: string; evidenceId?: string | undefined; resolvedTarget?: (Record<string, unknown> | string) | undefined; }[] | undefined; runOrder: number; mockOutcome: WebAutomationMockOutcome; agentRunId?: string | undefined; agentSourcePointId?: string | undefined; agentEnvironmentId?: string | undefined; agentBaseUrl?: string | undefined; agentAuthenticationMode?: string | undefined; agentEvidenceIds?: string[] | undefined; agentPageKey?: string | undefined; agentPageFingerprint?: string | undefined; agentStepTraces?: { stepIndex: number; naturalStep: string; intent: string; dsl: string[]; evidenceIds: string[]; pageFingerprint: string; sourcePointId: string; }[] | undefined; agentExpectedResultEvidenceIds?: string[] | undefined; artifactId?: string | undefined; artifactRevision?: number | undefined; artifactResultOrigin?: string | undefined; artifactAcceptanceState?: string | undefined; artifactModelProfileVersion?: string | undefined; artifactFieldOrigins?: string[] | undefined; artifactEvidenceIds?: string[] | undefined; artifactParentArtifactId?: string | undefined; artifactIdempotencyKey?: string | undefined; }[] }
  activeWebRunSnapshot: ShallowRef<WebAutomationRunSnapshot | undefined>
  webExecutionEnvironmentLibrary: Ref<ExecutionEnvironmentLibrary, ExecutionEnvironmentLibrary>
  webAutomationLoopCount: Ref<number, number>
  webAutomationRunning: Ref<boolean, boolean>
  automationFilter: { project: string; module: string; method: string; source: "ALL" | "design" | "default"; keyword: string; }
  webAutomationCases: { value: { automationLibraryId: string; savedAt: string; id: string; sourceCaseId: string; project: string; module: string; pageName: string; name: string; startUrl: string; environmentId?: string | undefined; browser: WebAutomationBrowser; viewport: string; authenticationMode: WebAutomationAuthenticationMode; steps: { id: string; name: string; action: WebAutomationAction; targetType: "url" | "locator" | "elementRef"; target: string; value?: string | undefined; timeoutMs?: number | undefined; screenshot?: "none" | "on_failure" | "always" | undefined; }[]; actions?: { [x: string]: unknown; id: string; type: string; target?: (Record<string, unknown> | string) | undefined; assertion?: (Record<string, unknown> | string) | undefined; value?: (Record<string, unknown> | string) | undefined; safety: string; evidenceId?: string | undefined; resolvedTarget?: (Record<string, unknown> | string) | undefined; }[] | undefined; runOrder: number; mockOutcome: WebAutomationMockOutcome; agentRunId?: string | undefined; agentSourcePointId?: string | undefined; agentEnvironmentId?: string | undefined; agentBaseUrl?: string | undefined; agentAuthenticationMode?: string | undefined; agentEvidenceIds?: string[] | undefined; agentPageKey?: string | undefined; agentPageFingerprint?: string | undefined; agentStepTraces?: { stepIndex: number; naturalStep: string; intent: string; dsl: string[]; evidenceIds: string[]; pageFingerprint: string; sourcePointId: string; }[] | undefined; agentExpectedResultEvidenceIds?: string[] | undefined; artifactId?: string | undefined; artifactRevision?: number | undefined; artifactResultOrigin?: string | undefined; artifactAcceptanceState?: string | undefined; artifactModelProfileVersion?: string | undefined; artifactFieldOrigins?: string[] | undefined; artifactEvidenceIds?: string[] | undefined; artifactParentArtifactId?: string | undefined; artifactIdempotencyKey?: string | undefined; }[] }
  webAutomationProjects: ComputedRef<{ value: string; count: number; }[]>
  webAutomationRunReport: Ref<WebAutomationRunReport | undefined, WebAutomationRunReport | undefined>
  filteredWebAutomationCases: { value: { automationLibraryId: string; savedAt: string; id: string; sourceCaseId: string; project: string; module: string; pageName: string; name: string; startUrl: string; environmentId?: string | undefined; browser: WebAutomationBrowser; viewport: string; authenticationMode: WebAutomationAuthenticationMode; steps: { id: string; name: string; action: WebAutomationAction; targetType: "url" | "locator" | "elementRef"; target: string; value?: string | undefined; timeoutMs?: number | undefined; screenshot?: "none" | "on_failure" | "always" | undefined; }[]; actions?: { [x: string]: unknown; id: string; type: string; target?: (Record<string, unknown> | string) | undefined; assertion?: (Record<string, unknown> | string) | undefined; value?: (Record<string, unknown> | string) | undefined; safety: string; evidenceId?: string | undefined; resolvedTarget?: (Record<string, unknown> | string) | undefined; }[] | undefined; runOrder: number; mockOutcome: WebAutomationMockOutcome; agentRunId?: string | undefined; agentSourcePointId?: string | undefined; agentEnvironmentId?: string | undefined; agentBaseUrl?: string | undefined; agentAuthenticationMode?: string | undefined; agentEvidenceIds?: string[] | undefined; agentPageKey?: string | undefined; agentPageFingerprint?: string | undefined; agentStepTraces?: { stepIndex: number; naturalStep: string; intent: string; dsl: string[]; evidenceIds: string[]; pageFingerprint: string; sourcePointId: string; }[] | undefined; agentExpectedResultEvidenceIds?: string[] | undefined; artifactId?: string | undefined; artifactRevision?: number | undefined; artifactResultOrigin?: string | undefined; artifactAcceptanceState?: string | undefined; artifactModelProfileVersion?: string | undefined; artifactFieldOrigins?: string[] | undefined; artifactEvidenceIds?: string[] | undefined; artifactParentArtifactId?: string | undefined; artifactIdempotencyKey?: string | undefined; }[] }
  webAutomationSelectedIds: Ref<string[], string[]>
  webAutomationStatusFilter: Ref<WebCaseStatusFilter, WebCaseStatusFilter>
  webRunConnectionState: Ref<"idle" | "polling" | "retrying" | "interrupted", "idle" | "polling" | "retrying" | "interrupted">
  webRunControlLoading: Ref<"" | "pause" | "resume" | "stop", "" | "pause" | "resume" | "stop">
}

export function createWebWorkspaceModel(deps: Dependencies) {

const webAutomationPreflight = computed(() => buildWebAutomationPreflight({
  scope: deps.effectiveWebAutomationScope.value,
  capabilities: deps.webRunCapabilities.value,
  browserOverride: deps.runnableWebRuntime.value?.browser || 'follow-case',
  environmentName: deps.activeWebExecutionEnvironment.value?.name || '未选择',
  environmentValidation: validateWebExecutionEnvironment(deps.activeWebExecutionEnvironment.value),
  login: resolveWebLoginReadiness(deps.activeWebExecutionEnvironment.value),
  logout: resolveWebLogoutReadiness(deps.activeWebExecutionEnvironment.value)
}))

const webRunStatusMessage = computed(() =>
  deps.webRunCreationError.value || deps.webRunInterruptionMessage.value
)

const webAutomationWorkspaceHeader = computed(() => ({
  executableCount: deps.effectiveWebAutomationScope.value.count,
  moduleCount: collectWebAutomationModuleCounts(deps.webAutomationProjectCases.value).length - 1,
  latestRunLabel: deps.activeWebRunSnapshot.value?.updatedAt || '暂无运行'
}))

const webAutomationWorkspacePreparation = computed(() => ({
  environmentId: deps.webExecutionEnvironmentLibrary.value.activeEnvironmentId,
  environments: deps.webExecutionEnvironmentLibrary.value.environments.map((environment) => ({
    value: environment.id,
    label: environment.name,
    // 抽屉里的「环境地址」必须能跟着草稿所选环境走，所以选项自带地址。
    baseUrl: environment.baseUrl
  })),
  browser: deps.runnableWebRuntime.value?.browser || 'follow-case',
  browsers: webEnvironmentBrowserOptions.value,
  loopCount: deps.webAutomationLoopCount.value,
  loginReadyLabel: resolveWebLoginReadiness(deps.activeWebExecutionEnvironment.value).enabled ? '自动登录已启用' : '无需自动登录',
  actionLabel: deps.effectiveWebAutomationScope.value.actionLabel,
  canExecute: webAutomationPreflight.value.canExecute && !deps.webAutomationRunning.value,
  blockingReason: webRunStatusMessage.value || webAutomationPreflight.value.blockingReason
}))

const webAutomationWorkspaceScope = computed(() => ({
  project: deps.automationFilter.project,
  projects: [
    { value: 'ALL', label: '全部项目', count: deps.webAutomationCases.value.length },
    ...deps.webAutomationProjects.value.map((project) => ({
      value: project.value,
      label: project.value,
      count: project.count
    }))
  ],
  module: deps.automationFilter.module,
  modules: [
    { value: 'ALL', label: '全部模块', count: deps.webAutomationProjectCases.value.length },
    ...collectWebAutomationModuleCounts(deps.webAutomationProjectCases.value).slice(1)
  ]
}))

const webAutomationResultStatusById = computed(() =>
  buildWebRunCaseStatusById(deps.webAutomationCases.value, deps.webAutomationRunReport.value?.results || [])
)

const webAutomationWorkspaceTable = computed(() => ({
  cases: deps.filteredWebAutomationCases.value,
  selectedIds: deps.webAutomationSelectedIds.value,
  source: deps.automationFilter.source,
  status: deps.webAutomationStatusFilter.value,
  keyword: deps.automationFilter.keyword,
  resultStatusById: webAutomationResultStatusById.value,
  emptyKind: deps.webAutomationCases.value.length === 0
    ? 'no-cases' as const
    : deps.filteredWebAutomationCases.value.length === 0 ? 'filtered' as const : 'none' as const
}))

const activeWebAutomationWorkspaceRun = computed(() =>
  deps.activeWebRunSnapshot.value && activeWebRunView.value
    ? {
        snapshot: deps.activeWebRunSnapshot.value,
        view: activeWebRunView.value,
        connectionState: deps.webRunConnectionState.value,
        connectionLabel: webRunConnectionLabel.value,
        elapsed: activeWebRunElapsed.value,
        controlLoading: deps.webRunControlLoading.value
      }
    : undefined
)

const activeWebRunView = computed(() =>
  deps.activeWebRunSnapshot.value ? buildWebRunView(deps.activeWebRunSnapshot.value) : undefined
)

const webEnvironmentBrowserOptions = computed(() => buildBrowserOptions(deps.webRunCapabilities.value))

const activeWebRunElapsed = computed(() => {
  const snapshot = deps.activeWebRunSnapshot.value
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
}[deps.webRunConnectionState.value]))


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


const webAutomationResultStatus = (testCase: SavedWebAutomationRunCase): WebAutomationRunStatus | 'pending' =>
  webAutomationResultStatusById.value[testCase.id] || 'pending'

return {
  webAutomationPreflight,
  webRunStatusMessage,
  webAutomationWorkspaceHeader,
  webAutomationWorkspacePreparation,
  webAutomationWorkspaceScope,
  webAutomationResultStatusById,
  webAutomationWorkspaceTable,
  activeWebAutomationWorkspaceRun,
  activeWebRunView,
  webEnvironmentBrowserOptions,
  activeWebRunElapsed,
  webRunConnectionLabel,
  resolveWebLoginReadiness,
  resolveWebLogoutReadiness,
  webAutomationResultStatus,
}
}

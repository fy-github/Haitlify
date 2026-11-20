import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { buildCurlCommand, buildExecutableRequest, type ExecutableRequest, type ExecutionEnvironment } from '../domain/apiExecution'
import { type SavedAutomationCase } from '../domain/automationRepository'
import { type SavedWebAutomationRunCase } from '../domain/webAutomationRepository'
import { buildAutomationCurlCommand, buildAutomationExecutableRequest, type AutomationRunResult, type ApiAutomationCaseDraft } from '../domain/apiAutomation'
import { type WebAutomationRunReport } from '../domain/webAutomation'
import { buildWebRunCaseResultMap, resolveWebRunCaseId } from '../domain/webAutomationRunView'
import { type SavedInterfaceCase } from '../domain/caseRepository'
import { webRunResultToAutomationLog } from './automationFormat'
import { collectFailedDesignOriginResults, summarizeFailedDesignOriginResults } from '../domain/designOrigin'
import type { WebAutomationAction } from '../domain/webAutomationCase'
import type { Ref } from 'vue'
import type { ShallowRef } from 'vue'
import type { WebAutomationBrowser } from '../domain/webAutomationCase'
import type { WebAutomationMockOutcome } from '../domain/webAutomation'
import type { WebAutomationAuthenticationMode } from '../domain/webAutomation'
import type { AutomationRunReport } from '../domain/apiAutomation'
import type { DefectWebResultInput } from '../domain/defectEvidence'

interface Dependencies {
  webAutomationCases: { value: { automationLibraryId: string; savedAt: string; id: string; sourceCaseId: string; project: string; module: string; pageName: string; name: string; startUrl: string; environmentId?: string | undefined; browser: WebAutomationBrowser; viewport: string; authenticationMode: WebAutomationAuthenticationMode; steps: { id: string; name: string; action: WebAutomationAction; targetType: "url" | "locator" | "elementRef"; target: string; value?: string | undefined; timeoutMs?: number | undefined; screenshot?: "none" | "on_failure" | "always" | undefined; }[]; actions?: { [x: string]: unknown; id: string; type: string; target?: (Record<string, unknown> | string) | undefined; assertion?: (Record<string, unknown> | string) | undefined; value?: (Record<string, unknown> | string) | undefined; safety: string; evidenceId?: string | undefined; resolvedTarget?: (Record<string, unknown> | string) | undefined; }[] | undefined; runOrder: number; mockOutcome: WebAutomationMockOutcome; agentRunId?: string | undefined; agentSourcePointId?: string | undefined; agentEnvironmentId?: string | undefined; agentBaseUrl?: string | undefined; agentAuthenticationMode?: string | undefined; agentEvidenceIds?: string[] | undefined; agentPageKey?: string | undefined; agentPageFingerprint?: string | undefined; agentStepTraces?: { stepIndex: number; naturalStep: string; intent: string; dsl: string[]; evidenceIds: string[]; pageFingerprint: string; sourcePointId: string; }[] | undefined; agentExpectedResultEvidenceIds?: string[] | undefined; artifactId?: string | undefined; artifactRevision?: number | undefined; artifactResultOrigin?: string | undefined; artifactAcceptanceState?: string | undefined; artifactModelProfileVersion?: string | undefined; artifactFieldOrigins?: string[] | undefined; artifactEvidenceIds?: string[] | undefined; artifactParentArtifactId?: string | undefined; artifactIdempotencyKey?: string | undefined; }[] }
  automationRunReport: Ref<AutomationRunReport | undefined, AutomationRunReport | undefined>
  webAutomationRunReport: Ref<WebAutomationRunReport | undefined, WebAutomationRunReport | undefined>
  automationKind: Ref<"api" | "web", "api" | "web">
  activeWebRunCases: ShallowRef<SavedWebAutomationRunCase[]>
  defects: { createDefectsFromWebResults: (results: DefectWebResultInput[], projectName: string) => boolean; createDefectsFromResults: (results: AutomationRunResult[], projectName: string) => boolean }
  filteredWebAutomationCases: { value: { automationLibraryId: string; savedAt: string; id: string; sourceCaseId: string; project: string; module: string; pageName: string; name: string; startUrl: string; environmentId?: string | undefined; browser: WebAutomationBrowser; viewport: string; authenticationMode: WebAutomationAuthenticationMode; steps: { id: string; name: string; action: WebAutomationAction; targetType: "url" | "locator" | "elementRef"; target: string; value?: string | undefined; timeoutMs?: number | undefined; screenshot?: "none" | "on_failure" | "always" | undefined; }[]; actions?: { [x: string]: unknown; id: string; type: string; target?: (Record<string, unknown> | string) | undefined; assertion?: (Record<string, unknown> | string) | undefined; value?: (Record<string, unknown> | string) | undefined; safety: string; evidenceId?: string | undefined; resolvedTarget?: (Record<string, unknown> | string) | undefined; }[] | undefined; runOrder: number; mockOutcome: WebAutomationMockOutcome; agentRunId?: string | undefined; agentSourcePointId?: string | undefined; agentEnvironmentId?: string | undefined; agentBaseUrl?: string | undefined; agentAuthenticationMode?: string | undefined; agentEvidenceIds?: string[] | undefined; agentPageKey?: string | undefined; agentPageFingerprint?: string | undefined; agentStepTraces?: { stepIndex: number; naturalStep: string; intent: string; dsl: string[]; evidenceIds: string[]; pageFingerprint: string; sourcePointId: string; }[] | undefined; agentExpectedResultEvidenceIds?: string[] | undefined; artifactId?: string | undefined; artifactRevision?: number | undefined; artifactResultOrigin?: string | undefined; artifactAcceptanceState?: string | undefined; artifactModelProfileVersion?: string | undefined; artifactFieldOrigins?: string[] | undefined; artifactEvidenceIds?: string[] | undefined; artifactParentArtifactId?: string | undefined; artifactIdempotencyKey?: string | undefined; }[] }
  jiraProjectName: Ref<string, string>
  resolveRunnableEnvironment: () => ExecutionEnvironment | undefined
  automationFilter: { project: string; module: string; method: string; source: "ALL" | "design" | "default"; keyword: string; }
}

export function createExecutionEvidence(deps: Dependencies) {

const curlVisible = ref(false)

const automationLogVisible = ref(false)

const curlText = ref('')

const executableRequest = ref<ExecutableRequest>()

const selectedAutomationResult = ref<AutomationRunResult>()

const actionIrViewerVisible = ref(false)

const selectedActionIrCase = computed(() => {
  const result = selectedAutomationResult.value
  if (!result) return undefined
  const resultSourceId = (result as { sourceCaseId?: string }).sourceCaseId
  return deps.webAutomationCases.value.find((item) =>
    item.id === result.caseId
    || (!!resultSourceId && item.sourceCaseId === resultSourceId)
    || (!!resultSourceId && item.id === resultSourceId)
  )
})

const hasFilteredDesignOriginAutomationCases = computed(() =>
  deps.automationKind.value === 'web'
  && deps.automationFilter.source === 'design'
  && deps.filteredWebAutomationCases.value.length > 0
)

const failedDesignOriginResults = computed(() =>
  collectFailedDesignOriginResults(deps.filteredWebAutomationCases.value, deps.webAutomationRunReport.value)
)

const failedDesignOriginSummary = computed(() =>
  summarizeFailedDesignOriginResults(failedDesignOriginResults.value)
)

const designOriginFailureExpanded = ref(false)

const automationResultMap = computed(() =>
  new Map((deps.automationRunReport.value?.results || []).map((result) => [result.caseId, result]))
)

const webAutomationResultMap = computed(() =>
  buildWebRunCaseResultMap(deps.webAutomationCases.value, deps.webAutomationRunReport.value?.results || [])
)

const automationRunSummary = computed(() => deps.automationRunReport.value?.summary || {
  total: 0,
  passed: 0,
  failed: 0,
  error: 0
})

const activeAutomationRunSummary = computed(() =>
  deps.automationKind.value === 'api'
    ? automationRunSummary.value
    : deps.webAutomationRunReport.value?.summary || {
        total: 0,
        passed: 0,
        failed: 0,
        error: 0
      }
)

const activeWebRunResultById = (caseOrId: SavedWebAutomationRunCase | string) => {
  const caseId = typeof caseOrId === 'string' ? caseOrId : caseOrId.id
  const originalId = resolveWebRunCaseId(caseId, new Set(deps.activeWebRunCases.value.map((item) => item.id)))
  const testCase = deps.activeWebRunCases.value.find((item) => item.id === originalId)
  const result = deps.webAutomationRunReport.value?.results.find((row) => row.caseId === caseId)
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
    deps.defects.createDefectsFromWebResults([resolved.result], resolved.testCase.project)
  }
}

const transferAutomationResultToDefect = (testCase: SavedAutomationCase) => {
  const result = automationResultMap.value.get(testCase.id)
  if (!result || (result.status !== 'failed' && result.status !== 'error')) {
    ElMessage.info('只有失败或错误的自动化结果可以转缺陷')
    return
  }
  deps.defects.createDefectsFromResults([result], testCase.project)
}

const transferWebAutomationResultToDefect = (testCase: SavedWebAutomationRunCase) => {
  const result = webAutomationResultMap.value.get(testCase.id)
  if (!result || (result.status !== 'failed' && result.status !== 'error')) {
    ElMessage.info('只有失败或错误的 Web 自动化结果可以转缺陷')
    return
  }
  deps.defects.createDefectsFromWebResults([result], testCase.project)
}

const transferFilteredDesignAutomationResultsToDefects = () => {
  const results = collectFailedDesignOriginResults(deps.filteredWebAutomationCases.value, deps.webAutomationRunReport.value)
  if (results.length === 0) {
    ElMessage.info('当前没有可批量转缺陷的设计稿执行结果')
    return
  }
  deps.defects.createDefectsFromWebResults(results, deps.filteredWebAutomationCases.value[0]?.project || deps.jiraProjectName.value)
}

const openAutomationCurlPreview = (testCase: ApiAutomationCaseDraft) => {
  const environment = deps.resolveRunnableEnvironment()
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
  const environment = deps.resolveRunnableEnvironment()
  if (!environment) return
  executableRequest.value = buildExecutableRequest(testCase, environment)
  curlText.value = buildCurlCommand(executableRequest.value)
  curlVisible.value = true
}

const copyCurl = async () => {
  if (!curlText.value) return
  await navigator.clipboard.writeText(curlText.value)
  ElMessage.success('已复制 cURL')
}

const reset = () => {
  selectedAutomationResult.value = undefined
  executableRequest.value = undefined
  curlText.value = ''
  curlVisible.value = false
  automationLogVisible.value = false
  actionIrViewerVisible.value = false
}
return {
  reset,
  curlVisible,
  automationLogVisible,
  curlText,
  executableRequest,
  selectedAutomationResult,
  actionIrViewerVisible,
  selectedActionIrCase,
  hasFilteredDesignOriginAutomationCases,
  failedDesignOriginResults,
  failedDesignOriginSummary,
  designOriginFailureExpanded,
  automationResultMap,
  webAutomationResultMap,
  automationRunSummary,
  activeAutomationRunSummary,
  activeWebRunResultById,
  openWebAutomationRunLog,
  transferWebAutomationRunResultToDefect,
  transferAutomationResultToDefect,
  transferWebAutomationResultToDefect,
  transferFilteredDesignAutomationResultsToDefects,
  openAutomationCurlPreview,
  openAutomationLog,
  openWebAutomationLog,
  openCurlPreview,
  copyCurl,
}
}

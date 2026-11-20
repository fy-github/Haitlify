import { ref, shallowRef } from 'vue'
import { ElMessage } from 'element-plus'
import { buildWebExecutionRuntime, defaultWebExecutionRuntimeConfig, validateWebExecutionEnvironment, type BuiltWebExecutionRuntime, type SavedExecutionEnvironment } from '../domain/executionEnvironmentRepository'
import { type WebAutomationRunCase, type WebAutomationRunReport } from '../domain/webAutomation'
import { WebAutomationRunRequestError, createWebAutomationRun, isTerminalWebAutomationRun, snapshotToReport, stopWebAutomationRun, waitForWebAutomationRun, type WebAutomationRunSnapshot } from '../domain/webAutomationRunRepository'

interface Dependencies {
  editingEnvironment: SavedExecutionEnvironment
}

export function createWebLoginVerification(deps: Dependencies) {

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

const verifyEditingWebEnvironmentLogin = async () => {
  if (!deps.editingEnvironment.web) return
  const validation = validateWebExecutionEnvironment(deps.editingEnvironment)
  if (!validation.valid) {
    webEnvironmentVerification.value = {
      status: 'error',
      message: validation.message || 'Web 自动登录配置不完整'
    }
    ElMessage.warning(validation.message)
    return
  }

  const runtime = buildWebExecutionRuntime(deps.editingEnvironment)
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
      if (!deps.editingEnvironment.web) deps.editingEnvironment.web = defaultWebExecutionRuntimeConfig()
      deps.editingEnvironment.web.sessionId = webLoginSessionId.value
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
  if (deps.editingEnvironment.web) deps.editingEnvironment.web.sessionId = undefined
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
  if (!deps.editingEnvironment.web || deps.editingEnvironment.web.login.successType !== 'visible') return undefined
  const target = deps.editingEnvironment.web.login.successTarget.trim()
  if (!isUnstableCssModuleLocator(target)) return undefined
  const finalPageUrl = [...(runResult.stepLogs || [])]
    .reverse()
    .find((step) => step.pageUrl)?.pageUrl
  const stableRoute = extractStableWebLoginRoute(finalPageUrl)
  if (!stableRoute) return undefined
  deps.editingEnvironment.web.login.successType = 'url'
  deps.editingEnvironment.web.login.successTarget = stableRoute
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

const reset = () => {
  webLoginVerificationAbortController?.abort()
  webEnvironmentVerifying.value = false
  webEnvironmentVerification.value = undefined
  webLoginVerificationVisible.value = false
  webLoginVerificationRunning.value = false
  webLoginVerificationSnapshot.value = undefined
  webLoginSessionId.value = ''
  webLoginVerificationRunId = ''
}
return {
  reset,
  webEnvironmentVerifying,
  webEnvironmentVerification,
  webLoginVerificationVisible,
  webLoginVerificationRunning,
  webLoginVerificationSnapshot,
  webLoginSessionId,
  get webLoginVerificationAbortController() { return webLoginVerificationAbortController }, set webLoginVerificationAbortController(value: typeof webLoginVerificationAbortController) { webLoginVerificationAbortController = value },
  get webLoginVerificationRunId() { return webLoginVerificationRunId }, set webLoginVerificationRunId(value: typeof webLoginVerificationRunId) { webLoginVerificationRunId = value },
  verifyEditingWebEnvironmentLogin,
  clearWebLoginSession,
  closeWebLoginVerification,
  migrateUnstableWebLoginSuccessTarget,
  isUnstableCssModuleLocator,
  extractStableWebLoginRoute,
  buildWebLoginProbeCase,
}
}

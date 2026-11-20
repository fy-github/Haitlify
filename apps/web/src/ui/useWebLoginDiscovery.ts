import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { type SavedExecutionEnvironment } from '../domain/executionEnvironmentRepository'
import { WebLoginDiscoveryRequestError, confirmWebLoginDiscovery, createWebLoginDiscovery, waitForWebLoginDiscovery, type WebLoginDiscoveryCandidate, type WebLoginDiscoveryCandidateRole, type WebLoginDiscoverySnapshot } from '../domain/webLoginDiscoveryRepository'
import type { Ref } from 'vue'

interface Dependencies {
  editingEnvironment: SavedExecutionEnvironment
  webEnvironmentVerification: Ref<{ status: "success" | "error"; message: string; detail?: string; } | undefined, { status: "success" | "error"; message: string; detail?: string; } | undefined>
}

export function createWebLoginDiscoveryState(deps: Dependencies) {

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

const startWebLoginDiscovery = async () => {
  const webRuntime = deps.editingEnvironment.web
  if (!webRuntime) return

  let discoveryUrl: string
  try {
    discoveryUrl = resolveWebLoginDiscoveryUrl(
      deps.editingEnvironment.baseUrl,
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
  if (!snapshot || snapshot.status !== 'completed' || !deps.editingEnvironment.web) return
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
    deps.editingEnvironment.web.login.enabled = true
    deps.editingEnvironment.web.login.usernameLocator = confirmedSnapshot.confirmed.usernameLocator
    deps.editingEnvironment.web.login.passwordLocator = confirmedSnapshot.confirmed.passwordLocator
    deps.editingEnvironment.web.login.submitLocator = confirmedSnapshot.confirmed.submitLocator
    deps.webEnvironmentVerification.value = undefined
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

const reset = () => {
  webLoginDiscoveryAbortController?.abort()
  webLoginDiscoveryDialogVisible.value = false
  webLoginDiscoveryLoading.value = false
  webLoginDiscoveryConfirming.value = false
  webLoginDiscoverySnapshot.value = undefined
  resetWebLoginDiscoverySelection()
}
return {
  reset,
  webLoginDiscoveryDialogVisible,
  webLoginDiscoveryLoading,
  webLoginDiscoveryConfirming,
  webLoginDiscoverySnapshot,
  webLoginDiscoverySelection,
  get webLoginDiscoveryAbortController() { return webLoginDiscoveryAbortController }, set webLoginDiscoveryAbortController(value: typeof webLoginDiscoveryAbortController) { webLoginDiscoveryAbortController = value },
  startWebLoginDiscovery,
  confirmAndApplyWebLoginDiscovery,
  selectedWebLoginDiscoveryCandidate,
  closeWebLoginDiscovery,
  initializeWebLoginDiscoverySelection,
  resetWebLoginDiscoverySelection,
  resolveWebLoginDiscoveryUrl,
  resolveHashAwareWebRuntimeUrl,
  formatWebLoginDiscoveryError,
}
}

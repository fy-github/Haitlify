import http from 'node:http'
import { sanitizeBlockedRequestDiagnostic } from './page-network-policy.mjs'

export const createWebRunnerServer = (options = {}) => {
  const jobManager = options.jobManager
  const executeJob = options.executeJob || (async () => {})
  const healthProvider = options.healthProvider || (() => ({ status: 'UP' }))
  const capabilitiesProvider = options.capabilitiesProvider || (async () => ({ status: 'UP', browsers: [] }))
  const sessionsProvider = options.sessionsProvider || (() => [])
  const schedule = options.schedule || ((task) => setImmediate(task))
  const staticFileHandler = options.staticFileHandler
  const loginDiscoveryManager = options.loginDiscoveryManager
  const executeLoginDiscovery = options.executeLoginDiscovery || (async () => {})
  const executePageLocatorDiscovery = options.executePageLocatorDiscovery || (async () => {})
  // 证据链接必须在「调用方看到的地址」上可用：未配置 PUBLIC_URL 时按请求的 Host 还原，
  // 否则容器内 9323 会被写进 URL，宿主映射到 19324 时链接全部 404/拒连。
  const configuredPublicBaseUrl = options.publicBaseUrl ? String(options.publicBaseUrl).replace(/\/$/, '') : null

  if (!jobManager) throw new Error('jobManager is required')

  return http.createServer(async (request, response) => {
    const url = new URL(request.url || '/', `http://${request.headers.host || '127.0.0.1'}`)
    const publicBase = configuredPublicBaseUrl || `${url.protocol}//${url.host}`
    const rewriteEvidence = (payload) => rewriteArtifactUrls(payload, publicBase)

    try {
      if (request.method === 'GET' && url.pathname === '/health') {
        const health = await healthProvider()
        sendJson(response, health.status === 'UP' ? 200 : 503, health)
        return
      }

      if (request.method === 'GET' && url.pathname === '/capabilities') {
        const capabilities = await capabilitiesProvider()
        sendJson(response, 200, capabilities)
        return
      }

      if (request.method === 'GET' && url.pathname === '/sessions') {
        // 阶段 7 增量 3：会话状态面板数据（只读元数据，已脱敏）。
        sendJson(response, 200, { sessions: await sessionsProvider() })
        return
      }

      if (request.method === 'POST' && url.pathname === '/runs') {
        const payload = await readJsonBody(request)
        if (!Array.isArray(payload.cases) || payload.cases.length === 0) {
          sendJson(response, 422, { message: 'At least one Web automation case is required' })
          return
        }
        const job = jobManager.create(payload)
        schedule(() => {
          Promise.resolve(executeJob(job)).catch((error) => job.fail(error))
        })
        sendJson(response, 202, rewriteArtifactUrls(job.snapshot(), publicBase))
        return
      }

      if (request.method === 'POST' && url.pathname === '/login-discovery') {
        if (!loginDiscoveryManager) {
          sendJson(response, 501, { message: 'Login discovery is not enabled' })
          return
        }
        const payload = await readJsonBody(request)
        if (!isHttpUrl(payload.url)) {
          sendJson(response, 422, { message: 'A valid HTTP login URL is required' })
          return
        }
        const job = loginDiscoveryManager.create(payload)
        schedule(() => {
          Promise.resolve(executeLoginDiscovery(job)).catch((error) => job.fail(error))
        })
        sendJson(response, 202, rewriteArtifactUrls(job.snapshot(), publicBase))
        return
      }

      if (request.method === 'POST' && url.pathname === '/page-locator-discovery') {
        const payload = await readJsonBody(request)
        if (!isHttpUrl(payload.url)) {
          sendJson(response, 422, { message: 'A valid HTTP page URL is required' })
          return
        }
        sendJson(response, 200, await executePageLocatorDiscovery(payload))
        return
      }

      const discoveryConfirmMatch = url.pathname.match(/^\/login-discovery\/([^/]+)\/confirm$/)
      if (request.method === 'POST' && discoveryConfirmMatch) {
        const job = loginDiscoveryManager?.get(decodeURIComponent(discoveryConfirmMatch[1]))
        if (!job) {
          sendJson(response, 404, { message: 'Login discovery task not found' })
          return
        }
        try {
          sendJson(response, 200, rewriteArtifactUrls(job.confirm(await readJsonBody(request)), publicBase))
        } catch (error) {
          sendJson(response, 422, { message: error instanceof Error ? error.message : String(error) })
        }
        return
      }

      const discoveryMatch = url.pathname.match(/^\/login-discovery\/([^/]+)$/)
      if (request.method === 'GET' && discoveryMatch) {
        const job = loginDiscoveryManager?.get(decodeURIComponent(discoveryMatch[1]))
        if (!job) {
          sendJson(response, 404, { message: 'Login discovery task not found' })
          return
        }
        sendJson(response, 200, rewriteArtifactUrls(job.snapshot(), publicBase))
        return
      }

      const controlMatch = url.pathname.match(/^\/runs\/([^/]+)\/(pause|resume|stop)$/)
      if (request.method === 'POST' && controlMatch) {
        const job = jobManager.get(decodeURIComponent(controlMatch[1]))
        if (!job) {
          sendJson(response, 404, { message: 'Web automation run not found' })
          return
        }
        const action = controlMatch[2]
        const snapshot = action === 'pause'
          ? job.requestPause()
          : action === 'resume'
            ? job.resume()
            : job.requestStop()
        sendJson(response, 200, rewriteArtifactUrls(snapshot, publicBase))
        return
      }

      const runMatch = url.pathname.match(/^\/runs\/([^/]+)$/)
      if (request.method === 'GET' && runMatch) {
        const job = jobManager.get(decodeURIComponent(runMatch[1]))
        if (!job) {
          sendJson(response, 404, { message: 'Web automation run not found' })
          return
        }
        sendJson(response, 200, rewriteArtifactUrls(job.snapshot(), publicBase))
        return
      }

      if (staticFileHandler && await staticFileHandler(request, response, url)) {
        return
      }

      sendJson(response, 404, { message: 'Not found' })
    } catch (error) {
      // 分类必须同时接受 category 与 failureType：fail-closed 错误（导航目标非法、不可观测子树等）
      // 只设置 failureType，若这里只读 category，它们会被降级成 503「runner 不可用」并丢失分类。
      const category = safeCategory(error?.category || error?.failureType)
      // 未分类错误也必须带 details：503 分支此前只有 message，连已经采集到的步骤证据都不返回。
      const details = safeErrorDetails(error, category || 'action_failed')
      sendJson(response, category ? 422 : 503, {
        message: sanitizeErrorMessage(error instanceof Error ? error.message : String(error)),
        ...(category ? { category } : {}),
        ...details
      })
    }
  })
}

const EVIDENCE_CATEGORIES = new Set([
  'auth_context_lost', 'login_failed', 'page_not_ready', 'network_blocked_critical',
  'network_blocked_optional', 'network_blocked_unknown', 'business_api_failed',
  'element_not_found', 'ambiguous_locator', 'unsafe_overlay', 'unsafe_action', 'navigation_failed', 'locator_persistence',
  'duration_exhausted', 'captcha_required', 'manual_required', 'session_busy', 'pagination_not_changed', 'invalid_action_ir', 'observation_failed',
  // 步骤级取证与 Action IR 的 fail-closed 分类：只设 failureType 的错误也必须能被平台识别，
  // 否则它们会以 503「runner 不可用」到达平台，真实原因（不可观测子树、非法导航目标）全部丢失。
  'assertion_failed', 'unsupported_frame', 'unsupported_shadow_dom', 'unsupported_action_target',
  'action_evidence_missing', 'action_evidence_mismatch', 'settle_timeout', 'overlay_not_ready', 'auth_dependency',
  // 未分类错误的兜底分类（执行期抛出的 Playwright/TypeError 等裸错误）。
  'action_failed',
  // DSL 侧未知/未实现的动作名（此前静默 return，整步记 passed）。
  'unsupported_action'
])

const safeCategory = (value) => EVIDENCE_CATEGORIES.has(String(value || '')) ? String(value) : ''

const sanitizeUrl = (value) => {
  try {
    const parsed = new URL(String(value || ''))
    const fragment = parsed.hash ? parsed.hash.replace(/^#/, '').split(/[?#]/, 1)[0].replace(/[^A-Za-z0-9/_-]/g, '') : ''
    return `${parsed.origin}${parsed.pathname || '/'}${fragment ? `#${fragment}` : ''}`
  } catch {
    return String(value || '').split(/[?#]/, 1)[0]
  }
}

const sanitizeErrorMessage = (value) => String(value || '')
  .replace(/https?:\/\/[^\s'"`]+/gi, (url) => sanitizeUrl(url))
  .replace(/(password|token|cookie|authorization|apiKey|secretKey)\s*[:=]\s*[^\s,;]+/gi, '$1=[redacted]')
  .replace(/(usernameLocator|passwordLocator|submitLocator|locator|selector)\s*[:=]\s*[^\s,;]+/gi, '$1=[redacted]')

const safeErrorDetails = (error, category) => {
  const details = { retryable: Boolean(error?.retryable) }
  if (error?.pageUrl) details.pageUrl = sanitizeUrl(error.pageUrl)
  if (Array.isArray(error?.repairTrail)) details.repairTrail = error.repairTrail.slice(-5)
  // 动作级失败必须能让调用方知道是哪条目标失效：授权重试/重绑需要这个 id（实测 2026-10-14）。
  if (typeof error?.targetEvidenceId === 'string' && error.targetEvidenceId) details.targetEvidenceId = error.targetEvidenceId
  if (Number.isFinite(error?.matchedElementCount)) details.matchedElementCount = error.matchedElementCount
  if (typeof error?.actionId === 'string' && error.actionId) details.actionId = error.actionId
  if (error?.handoff && typeof error.handoff === 'object') {
    details.handoff = {
      required: Boolean(error.handoff.required),
      mode: String(error.handoff.mode || 'manual'),
      reason: String(error.handoff.reason || category || ''),
      resumeSupported: Boolean(error.handoff.resumeSupported),
      ...(typeof error.handoff.handoffId === 'string' && error.handoff.handoffId ? { handoffId: error.handoff.handoffId } : {}),
      ...(typeof error.handoff.sessionId === 'string' && error.handoff.sessionId ? { sessionId: error.handoff.sessionId } : {})
    }
  }
  if (error?.network && typeof error.network === 'object') {
    details.network = {
      criticalBlocked: safeBlocked(error.network.criticalBlocked),
      optionalBlocked: safeBlocked(error.network.optionalBlocked),
      unknownBlocked: safeBlocked(error.network.unknownBlocked),
      authDependencies: safeBlocked(error.network.authDependencies)
    }
  }
  // 失败响应同样必须带步骤级证据：阶段 3 要求每个 action 都有 before/action/after，
  // 而最需要证据的恰恰是失败用例——之前只有成功响应才序列化 stepEvidence，失败时证据全丢。
  if (Array.isArray(error?.stepEvidence) && error.stepEvidence.length) details.stepEvidence = error.stepEvidence
  if (Array.isArray(error?.pageSnapshots) && error.pageSnapshots.length) details.pageSnapshots = error.pageSnapshots
  return details
}

const safeBlocked = (entries) => (Array.isArray(entries) ? entries : []).slice(0, 20).map((entry) => sanitizeBlockedRequestDiagnostic(entry))

const isHttpUrl = (value) => {
  try {
    const parsed = new URL(String(value || ''))
    return (parsed.protocol === 'http:' || parsed.protocol === 'https:')
      && !parsed.username
      && !parsed.password
  } catch {
    return false
  }
}

const readJsonBody = async (request) => {
  const chunks = []
  for await (const chunk of request) {
    chunks.push(Buffer.from(chunk))
  }
  const body = Buffer.concat(chunks).toString('utf8')
  try {
    return JSON.parse(body || '{}')
  } catch {
    throw new Error('Invalid JSON request body')
  }
}

export const rewriteArtifactUrls = (value, base) => {
  if (typeof value === 'string') {
    return value.replace(/^http:\/\/127\.0\.0\.1:\d+\/artifacts\//, `${base}/artifacts/`)
  }
  if (Array.isArray(value)) return value.map((item) => rewriteArtifactUrls(item, base))
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, rewriteArtifactUrls(item, base)]))
  }
  return value
}

const sendJson = (response, status, body) => {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' })
  response.end(JSON.stringify(body))
}

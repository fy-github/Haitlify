import { resolveLocator, urlMatchesExpected, waitForUrlMatch } from './locator-utils.mjs'
import { hasChangedRoutePath, hasLeftLoginRoute, waitForLoginRouteExit } from './login-route.mjs'
import { PAGINATION_CLICK_RESPONSE_MS, PAGINATION_CLICK_RESPONSE_SLACK_MS, PAGINATION_MAX_TIMEOUT_MS, PAGINATION_SETTLE_CAP_MS, armPaginationProbe, isPaginationProbeAlive, paginationContentUnchanged, paginationNavigatedAwayError, paginationPartsOf, paginationSettleHorizonMs, paginationSettleTracker, paginationTrackedChange } from './pagination-signature.mjs'
import { buildBrowserContextCookies, buildBrowserContextOptions } from './runner-core.mjs'
import { runPageExploration } from './page-explorer.mjs'
import { isForbiddenBusinessAction, normalizeIntent, normalizeRepairCategory } from './page-exploration-policy.mjs'
import { normalizeAiRepairSuggestionCategory } from './page-repair-policy.mjs'
import { SAFE_REPAIR_ACTIONS, normalizeRepairActionType, shouldAttemptReauthenticate } from './page-repair-policy.mjs'
import { normalizePageKey, observePageEvidence, resolveStepTargetEvidence, pageFingerprint, sweepForEvidenceTarget, verifyHistoricalLocators } from './page-evidence.mjs'
import {
  classifyRequestCriticality,
  createPageNetworkPolicy,
  sanitizeBlockedDiagnostic,
  sanitizeBlockedRequestDiagnostic
} from './page-network-policy.mjs'
import { createHandoff, createSession, destroyHandoff, destroyHandoffSession, destroySession, getHandoff, getSession, handoffState, touchSession, sessionState, acquireSessionLease, releaseSessionLease } from './session-manager.mjs'
import { startBoundedTracing, stopBoundedTracing } from './browser-tracing.mjs'

export { classifyRequestCriticality } from './page-network-policy.mjs'

const DISCOVERY_ROUTE_PATTERN = '**/*'

const captureDiscoveryScreenshot = async (page, input, options, suffix) => {
  if (!options?.artifactDir || !options?.publicBaseUrl || !page?.screenshot) return ''
  try {
    await fs.mkdir(options.artifactDir, { recursive: true })
    const point = String(input.sourcePointId || 'page').replace(/[^\w.-]+/g, '_')
    const fileName = `evidence-${point}-${suffix}-${randomUUID()}.png`
    await page.screenshot({ path: path.join(options.artifactDir, fileName), fullPage: false })
    return `${String(options.publicBaseUrl).replace(/\/$/, '')}/artifacts/${fileName}`
  } catch {
    return ''
  }
}

const removeDiscoveryHooks = async (session) => {
  const hooks = session?.pageLocatorDiscoveryHooks
  if (!hooks) return
  const page = hooks.page
  const context = hooks.context
  const removeListener = typeof page?.off === 'function'
    ? page.off.bind(page)
    : typeof page?.removeListener === 'function'
      ? page.removeListener.bind(page)
      : null
  if (removeListener) {
    for (const [eventName, handler] of [
      ['requestfailed', hooks.requestfailed],
      ['request', hooks.request],
      ['response', hooks.response]
    ]) {
      if (handler) {
        try {
          removeListener(eventName, handler)
        } catch {}
      }
    }
  }
  if (typeof context?.unroute === 'function' && hooks.routeHandler) {
    try {
      await context.unroute(hooks.routePattern || DISCOVERY_ROUTE_PATTERN, hooks.routeHandler)
    } catch {}
  }
  delete session.pageLocatorDiscoveryHooks
}

export const discoverPageLocators = async (input, options) => {
  validateActionIr(input.actions)
  const sessionKey = String(input.sessionKey || input.baseUrl || input.url || '').trim()
  const resumeFromHandoff = input.resumeFromHandoff === true
  const inputPageKey = normalizePageKey(input.url || input.baseUrl || '')
  const inputPageUrlPath = normalizeHandoffPageUrlPath(input.url || input.baseUrl || '')
  const inputPageFingerprint = String(input.pageFingerprint || '').trim()
  const handoffKey = {
    projectId: input.projectId,
    environmentId: String(input.environmentId || ''),
    browser: String(input.browser || 'chromium'),
    pageKey: inputPageKey,
    pageUrlPath: inputPageUrlPath,
    ...(inputPageFingerprint ? { pageFingerprint: inputPageFingerprint } : {})
  }
  const handoffStatus = resumeFromHandoff && input.handoffId
    ? handoffState(input.handoffId, handoffKey)
    : 'missing'
  if (resumeFromHandoff && handoffStatus !== 'active') {
    if (input.handoffId) destroyHandoffSession(input.handoffId)
    throw buildEvidenceFailure(
      'manual_required',
      String(input.url || ''),
      [],
      [],
      {
        required: true,
        mode: 'manual',
        reason: handoffStatus === 'expired' ? 'handoff_expired' : 'handoff_mismatch',
        resumeSupported: false,
        handoffId: String(input.handoffId || '')
      }
    )
  }
  const handoff = resumeFromHandoff && input.handoffId
    ? getHandoff(input.handoffId, handoffKey)
    : undefined
  const resumeSessionId = resumeFromHandoff ? handoff?.sessionId : input.sessionId
  const canUseSession = Boolean(resumeSessionId && (input.reuseSession || resumeFromHandoff))
  const requestedSessionState = canUseSession
    ? sessionState(resumeSessionId, {
      projectId: input.projectId,
      environmentId: String(input.environmentId || ''),
      ownerId: input.ownerId,
      sessionKey,
      browser: String(input.browser || 'chromium')
    })
    : 'missing'
  if (requestedSessionState === 'mismatch') {
    if (input.handoffId) destroyHandoffSession(input.handoffId)
    if (resumeSessionId) destroySession(resumeSessionId)
    throw buildEvidenceFailure('auth_context_lost', String(input.url || ''), [], [], {
      required: true,
      mode: 'manual',
      reason: 'manual_required',
      resumeSupported: false,
      ...(typeof input.handoffId === 'string' && input.handoffId ? { handoffId: input.handoffId } : {})
    })
  }
    const existingSession = canUseSession
    ? getSession(resumeSessionId, {
      projectId: input.projectId,
      environmentId: String(input.environmentId || ''),
      ownerId: input.ownerId,
      sessionKey,
      browser: String(input.browser || 'chromium')
    })
    : undefined
  if (resumeFromHandoff && existingSession && handoff) {
    const existingPage = existingSession.page
    let existingPageUrl = ''
    try {
      existingPageUrl = typeof existingPage?.url === 'function' ? existingPage.url() : ''
    } catch {}
    const existingPageEvidence = existingPage ? await observePageEvidence(existingPage).catch(() => null) : null
    const existingPageKey = normalizePageKey(existingPageEvidence?.pageKey || existingPageUrl || '')
    const existingPageUrlPath = normalizeHandoffPageUrlPath(existingPageEvidence?.pageKey || existingPageUrl || '')
    const existingPageFingerprint = String(existingPageEvidence?.pageFingerprint || existingPageEvidence?.fingerprint || '').trim()
    const expectedPageKey = String(handoff.pageKey || '').trim()
    const expectedPageUrlPath = String(handoff.pageUrlPath || '').trim()
    const expectedPageFingerprint = String(handoff.pageFingerprint || '').trim()
    const enforceFingerprintMatch = expectedPageFingerprint && !isCaptchaFailure(handoff.status)
    const pageMismatch = (expectedPageKey && existingPageKey !== expectedPageKey)
      || (expectedPageUrlPath && existingPageUrlPath !== expectedPageUrlPath)
      || (enforceFingerprintMatch && (!existingPageFingerprint || existingPageFingerprint !== expectedPageFingerprint))
    if (pageMismatch) {
      if (input.handoffId) destroyHandoffSession(input.handoffId)
      throw buildEvidenceFailure('manual_required', existingPageUrl || String(input.url || ''), [], [], {
        required: true,
        mode: 'manual',
        reason: 'handoff_mismatch',
        resumeSupported: false,
        handoffId: String(input.handoffId || '')
      })
    }
  }
  if (resumeFromHandoff && !existingSession) {
    if (input.handoffId) destroyHandoffSession(input.handoffId)
    throw buildEvidenceFailure(
      'manual_required',
      String(input.url || ''),
      [],
      [],
      {
        required: true,
        mode: 'manual',
        reason: 'handoff_session_missing',
        resumeSupported: false,
        handoffId: String(input.handoffId || '')
      }
    )
  }
  let sessionLease
  const sessionLeaseKey = { projectId: input.projectId, environmentId: String(input.environmentId || ''), ownerId: input.ownerId, sessionKey, browser: String(input.browser || 'chromium') }
  if (existingSession) {
    sessionLease = acquireSessionLease(existingSession.sessionId, sessionLeaseKey, String(input.operationId || input.runId || randomUUID()))
    if (!sessionLease.ok) throw Object.assign(new Error('浏览器会话繁忙：当前任务正在使用该会话'), { category: 'session_busy', reason: 'session_busy', retryable: true })
  }
  const browser = existingSession ? null : await options.launchBrowser(input.browser, {
    headless: input.headless ?? true,
    allowLocalNetworkAccess: options.allowLocalNetworkAccess
  })
  // 保留语义 = 确有会话持有这些浏览器对象（reuseSession 只是「成功后允许创建会话供后续复用」）；
  // 失败发生在建会话之前时必须走 finally 关闭，否则留下无主浏览器（用户核查 2026-10-14，已实测复现）。
  let retainSession = Boolean(existingSession)
  let context
  let page
  let manualHandoffDetails = null
  let discoveryHooks = null
  let trace = null
  let traceReason = 'completed'
  try {
    const runtime = {
      ...input,
      baseUrl: String(input.baseUrl || input.url || '').trim()
    }
    context = existingSession?.context || await browser.newContext(buildBrowserContextOptions(runtime, { viewport: input.viewport }))
    trace = existingSession ? null : await startBoundedTracing(context, input).catch(() => null)
    if (!existingSession) {
      const cookies = buildBrowserContextCookies(runtime)
      if (cookies.length) await context.addCookies(cookies)
    }
    page = existingSession?.page || await context.newPage()
    if (existingSession) await removeDiscoveryHooks(existingSession)
    const networkBlocked = []
    const authDependencies = []
    const loginDiagnostics = []
    const originFromUrl = (value) => {
      try {
        const parsed = new URL(String(value || ''))
        return ['http:', 'https:'].includes(parsed.protocol) ? `${parsed.protocol}//${parsed.host}`.toLowerCase() : ''
      } catch {
        return ''
      }
    }
    const networkPolicy = createPageNetworkPolicy({
      pageOrigins: [originFromUrl(runtime.baseUrl), originFromUrl(input.url)],
      allowedRequestOrigins: input.allowedOrigins || input.allowedRequestOrigins || [],
      authOrigins: [originFromUrl(input.login?.url), ...(input.authOrigins || [])],
      allowedRequestUrls: [input.login?.url, ...(input.allowedRequestUrls || [])],
      allowedRequestPaths: input.allowedRequestPaths || [],
      networkPolicyMode: input.networkPolicyMode,
      targetUrl: input.url,
      loginUrl: input.login?.url,
      observedBusinessSignals: new Set(input.observedBusinessSignals || []),
      criticalityAware: input.criticalityAwareNetworkPolicy !== false
    })
    const requestFailedHandler = (request) => {
      const failure = request.failure?.()
      const criticality = networkPolicy.classifyRequestCriticality(request)
      if (criticality === 'auth_dependency' || criticality === 'critical') {
        loginDiagnostics.push(formatSafeRequestDiagnostic(request.method(), request.url(), failure?.errorText))
      }
    }
    const requestHandler = (request) => {
      const criticality = networkPolicy.classifyRequestCriticality(request)
      if (criticality !== 'auth_dependency') return
      const requestUrl = request.url()
      let parsed
      try {
        parsed = new URL(requestUrl)
      } catch {
        parsed = null
      }
      authDependencies.push(sanitizeBlockedRequestDiagnostic({
        url: requestUrl,
        origin: parsed ? `${parsed.protocol}//${parsed.host}`.toLowerCase() : requestUrl,
        path: parsed ? parsed.pathname : requestUrl,
        resourceType: request.resourceType?.() || 'unknown',
        criticality,
        failureType: 'auth_dependency'
      }))
    }
    const responseHandler = (response) => {
      if (response.status() < 400) return
      const criticality = networkPolicy.classifyRequestCriticality(response.request())
      const request = response.request()
      const optionalNotFound = isOptionalNotFoundResponse({
        status: response.status(),
        criticality,
        resourceType: request.resourceType?.(),
        url: response.url(),
        loginUrl: input.login?.url,
        observedBusinessSignals: input.observedBusinessSignals || []
      })
      if (criticality === 'auth_dependency' || (criticality === 'critical' && !optionalNotFound)) {
        loginDiagnostics.push(formatSafeRequestDiagnostic(response.request().method(), response.url(), `HTTP ${response.status()}`))
      }
    }
    const routeHandlerRef = { current: null }
    page.on('requestfailed', requestFailedHandler)
    page.on('request', requestHandler)
    page.on('response', responseHandler)
    await networkPolicy.apply(context, page, (entry) => networkBlocked.push(entry), options.assertAllowedUrl, routeHandlerRef)
    discoveryHooks = {
      page,
      context,
      requestfailed: requestFailedHandler,
      request: requestHandler,
      response: responseHandler,
      routePattern: DISCOVERY_ROUTE_PATTERN,
      routeHandler: routeHandlerRef.current
    }
    if (existingSession) existingSession.pageLocatorDiscoveryHooks = discoveryHooks
    // 时限归一（复核方 C-2/C-9）：非有限或 ≤0 一律取观察上限；超大值夹在定时器上限内，
    // 否则 `page.goto: Timeout Infinityms exceeded` 这类未分类错误会在入口就把整轮判死。
    const requestedTimeout = Number(input.timeoutMs)
    const timeout = Number.isFinite(requestedTimeout) && requestedTimeout > 0
      ? Math.min(Math.max(1_000, requestedTimeout), PAGINATION_MAX_TIMEOUT_MS)
      : PAGINATION_SETTLE_CAP_MS
    page.setDefaultTimeout(timeout)
    page.setDefaultNavigationTimeout(timeout)
    let loginCompleted = false
    // 复核方 15 轮 A-4/C-⑤：重采 readiness 的触发必须挂在**采集内部真实发生过的重登**上，
    // 不能挂 `input.rounds`（平台 DTO 没有这个字段 ⇒ 生产永不触发，修复等于死代码）。
    let reauthenticatedDuringRun = false
    if (!existingSession && !resumeFromHandoff) {
      try {
        await loginIfConfigured(page, input.login, timeout, loginDiagnostics, input.budget?.maxLoginRetries)
        loginCompleted = Boolean(input.login?.enabled)
      } catch (error) {
        throw Object.assign(error instanceof Error ? error : new Error(String(error)), {
          category: 'login_failed',
          pageUrl: page.url(),
          retryable: false,
          network: networkDiagnostic(networkBlocked),
          repairTrail: []
        })
      }
    }
    if (!resumeFromHandoff) {
      await options.assertAllowedUrl?.(input.url)
      await page.goto(input.url, { waitUntil: input.waitUntil || 'domcontentloaded', timeout })
      await options.assertAllowedUrl?.(page.url())
    } else {
      await options.assertAllowedUrl?.(page.url())
    }
    const baseIntent = input.intent || {
      pageAliases: input.navigationHints || [],
      routeHints: input.routeHints || [],
      elementTerms: input.elementTerms || input.targetElements || [],
      actionTerms: input.actionTerms || [],
      stateTerms: input.stateTerms || [],
      expectedTexts: input.expectedTexts || []
    }
    const historicalLocators = Array.isArray(input.historicalLocators)
      ? input.historicalLocators.filter((candidate) => Number(candidate?.confidenceScore || 0) >= 50).slice(0, 20)
      : []
    const verifiableHistoricalLocators = input.sourcePointId
      ? historicalLocators.filter((candidate) => !candidate?.sourcePointId || candidate.sourcePointId === input.sourcePointId)
      : historicalLocators
    const intent = baseIntent
    // legacy direct-navigation 的两个执行闸门（请求校验与探索轮次执行）必须看到同一份 hint 集合：
    // routeHints + navigationHints + 计划里的路由线索。两边口径不一致时，同一个动作会在校验期放行、
    // 在执行期被拒（或反之），复核实测过这种不对称。
    const legacyNavigationHints = [
      ...(Array.isArray(input.routeHints) ? input.routeHints : []),
      ...(Array.isArray(input.navigationHints) ? input.navigationHints : []),
      ...normalizedRouteHints(intent)
    ]
    if (!resumeFromHandoff) validateRequestedActions(page, input.actions, legacyNavigationHints, input.history || [])
    // 阶段 3：定向采集的语义线索来自 intent，采集预算可由调用方覆盖（超过预算必须返回诊断）。
    const observationOptions = {
      ...(Array.isArray(intent.elementTerms) && intent.elementTerms.length ? { targetedTerms: intent.elementTerms } : {}),
      ...(Number(input.elementBudget) > 0 ? { budget: Number(input.elementBudget) } : {}),
      ...(Number(input.partitionBudget) > 0 ? { partitionBudget: Number(input.partitionBudget) } : {})
    }
    const historicalMatchedSet = new Set()
    const stepEvidence = []
    // 阶段 3：跨页动作的 before/after 属于不同页面，平台必须按页面分别保存证据，
    // 只保存最终页面会让「点击菜单 → 断言目标页标题」无法在真实链路里编译。
    const stepPageSnapshots = []
    const createManualHandoff = (reason, pageUrl, networkBlocked = [], pageEvidence = null) => {
      if (manualHandoffDetails) return manualHandoffDetails
      const savedPageFingerprint = String(pageEvidence?.pageFingerprint || pageFingerprint(pageEvidence || {}) || '').trim()
      const session = existingSession || createSession({
        sessionKey,
        projectId: input.projectId,
        environmentId: String(input.environmentId || ''),
        browser: String(input.browser || 'chromium'),
        // 会话必须持有浏览器句柄，否则 destroySession 只关 page/context、进程常驻（用户核查 2026-10-14）。
        browserInstance: browser,
        context,
        page,
        pageKey: normalizePageKey(pageUrl || input.url || input.baseUrl),
        pageUrlPath: normalizeHandoffPageUrlPath(pageUrl || input.url || input.baseUrl),
        pageFingerprint: savedPageFingerprint,
        observedOrigins: networkBlocked.map((entry) => entry.origin)
      }, 5 * 60 * 1000)
      if (discoveryHooks && !session.pageLocatorDiscoveryHooks) session.pageLocatorDiscoveryHooks = discoveryHooks
      const handoffRecord = createHandoff({
        sessionId: session.sessionId,
        projectId: input.projectId,
        environmentId: String(input.environmentId || ''),
        browser: String(input.browser || 'chromium'),
        status: reason,
        runId: input.runId,
        sourcePointId: input.sourcePointId,
        pageKey: normalizePageKey(pageUrl || input.url || input.baseUrl),
        pageUrlPath: normalizeHandoffPageUrlPath(pageUrl || input.url || input.baseUrl),
        ...(savedPageFingerprint ? { pageFingerprint: savedPageFingerprint } : {})
      }, 5 * 60 * 1000)
      if (resumeFromHandoff && input.handoffId) destroyHandoff(input.handoffId)
      retainSession = true
      manualHandoffDetails = {
        required: true,
        mode: 'manual',
        reason,
        resumeSupported: Boolean(savedPageFingerprint),
        handoffId: handoffRecord.handoffId,
        sessionId: session.sessionId
      }
      return manualHandoffDetails
    }
    let readiness = await waitForPageReadiness(page, intent, networkBlocked, Math.min(timeout, 5_000))
    if (resumeFromHandoff) {
      const secondReadiness = await waitForPageReadiness(page, intent, networkBlocked, Math.min(timeout, 5_000))
      if (!readiness.ready || !secondReadiness.ready) {
        if (input.handoffId) destroyHandoffSession(input.handoffId)
        retainSession = false
        throw buildEvidenceFailure(readiness.failureReason || secondReadiness.failureReason || 'page_not_ready', page.url(), networkBlocked, [], {
          required: true,
          mode: 'manual',
          reason: readiness.failureReason || secondReadiness.failureReason || 'page_not_ready',
          resumeSupported: false,
          handoffId: String(input.handoffId || '')
        })
      }
      readiness = secondReadiness
    }
    if (isCaptchaFailure(readiness.failureReason)) {
      throw buildEvidenceFailure(readiness.failureReason, page.url(), networkBlocked, [], createManualHandoff(readiness.failureReason, page.url(), networkBlocked))
    }
    if (Array.isArray(input.actions) && input.actions.some((action) => action?.id || action?.actionId)) {
      try {
        await executeActionIr(page, input.actions, stepEvidence, {
          sourcePointId: input.sourcePointId,
          timeout,
          pageSnapshots: stepPageSnapshots,
          // 值来源解析要用到的运行时数据（与平台 ActionIrValidator 的词表对应）。
          variables: input.variables,
          maskedSecrets: input.maskedSecrets,
          observe: async () => observePageEvidence(page, observationOptions),
          assertAllowedUrl: options.assertAllowedUrl
        })
      } catch (error) {
        error.stepEvidence = stepEvidence
        // 页面级证据同样要随失败一起返回：跨页用例失败时，before/after 分属不同页面。
        error.pageSnapshots = stepPageSnapshots
        throw error
      }
      readiness = await waitForPageReadiness(page, intent, networkBlocked, Math.min(timeout, 5_000))
    }
    let observedSafeTabLocators = new Set()
    const explorationResult = await runPageExploration({
      intent: {
        ...intent,
        expectedTexts: intent.expectedTexts?.length ? intent.expectedTexts : (intent.elementTerms || input.targetElements || [])
      },
      sourcePointId: input.sourcePointId,
      budget: {
        ...(input.budget || {}),
        // A page heading plus a uniquely matching element is sufficient for
        // locator discovery; callers can retain the stricter policy threshold.
        minimumTotalScore: input.budget?.minimumTotalScore ?? 40
      },
      routeHints: input.routeHints,
      history: input.history,
      preferredEvidenceLocators: historicalLocators.map((candidate) => candidate.locator)
    }, {
      observe: async () => {
        const captchaFailure = await detectCaptchaState(page)
        const observation = await observePageEvidence(page, observationOptions)
        if (captchaFailure) {
          observation.failureCategory = captchaFailure
          observation.handoff = createManualHandoff(captchaFailure, page.url(), networkBlocked, observation)
        }
        const verified = readiness.ready && !observation.overlays?.some((overlay) => overlay.blocking)
          ? await verifyHistoricalLocators(page, verifiableHistoricalLocators)
          : []
        const verifiedByLocator = new Map(verified.map((item) => [item.locator, item]))
        observation.elements = (observation.elements || []).map((element) => {
          const match = verifiedByLocator.get(element.locator)
          return match ? { ...element, historicalVerified: true, historicalEntryId: match.entryId } : element
        })
        for (const match of verified) historicalMatchedSet.add(match.entryId)
        observation.readiness = readiness
        if (!readiness.ready && !observation.failureCategory) observation.failureCategory = readiness.failureReason
        if (networkBlocked.some((entry) => entry.criticality === 'critical')) {
          observation.network = {
            criticalBlocked: networkBlocked.filter((entry) => entry.criticality === 'critical'),
            optionalBlocked: networkBlocked.filter((entry) => entry.criticality === 'optional'),
            unknownBlocked: networkBlocked.filter((entry) => entry.criticality === 'unknown'),
            authDependencies
          }
          if (!observation.failureCategory) observation.failureCategory = 'network_blocked_critical'
        }
        observedSafeTabLocators = new Set((observation.tabs || []).filter((tab) => tab.enabled && !isForbiddenBusinessAction(`${tab.label || ''} ${tab.accessibleName || ''}`) && tab.locator).map((tab) => tab.locator))
        return observation
      },
      chooseAction: ({ observation, scores, intent: normalized, rounds, budget, lastFailureCategory }) => chooseSafeAction(page, observation, scores, normalized, {
        ...input,
        actions: rounds.length === 1 ? input.actions : undefined,
        budget,
        rounds,
        lastFailureCategory
      }),
      performAction: (action) => performSafeAction(page, action, {
        baseUrl: runtime.baseUrl || input.url,
        routeHints: legacyNavigationHints,
        history: input.history,
        observedSafeTabLocators,
        assertAllowedUrl: options.assertAllowedUrl,
        reauthenticate: async () => {
          await loginIfConfigured(page, input.login, timeout, loginDiagnostics, 0)
          reauthenticatedDuringRun = true
        },
        updateReadiness: async () => { readiness = await waitForPageReadiness(page, intent, networkBlocked, Math.min(timeout, 5_000)); return readiness },
        timeout
      }),
      navigationTrail: () => navigationTrail(page),
      now: Date.now
    })
    const lastFailure = explorationResult.rounds?.at(-1)?.actionFailure?.failureType
    const ambiguousSeen = explorationResult.rounds?.some((round) => round.actionFailure?.failureType === 'ambiguous_locator')
    const exploration = lastFailure === 'unsafe_overlay' || (ambiguousSeen && explorationResult.status !== 'matched')
      ? { ...explorationResult, status: 'blocked', failureReason: lastFailure === 'unsafe_overlay' ? 'unsafe_overlay' : 'ambiguous_locator' }
      : explorationResult
    const captchaFailureReason = isCaptchaFailure(readiness.failureReason)
      ? readiness.failureReason
      : isCaptchaFailure(exploration.finalObservation?.failureCategory)
        ? exploration.finalObservation.failureCategory
        : ''
    if (captchaFailureReason) {
      throw buildEvidenceFailure(captchaFailureReason, page.url(), networkBlocked, exploration.repairTrail || [], manualHandoffDetails || createManualHandoff(captchaFailureReason, page.url(), networkBlocked))
    }
    const criticalBlocked = networkBlocked.filter((entry) => entry.criticality === 'critical')
    const optionalBlocked = networkBlocked.filter((entry) => entry.criticality === 'optional')
    const unknownBlocked = networkBlocked.filter((entry) => entry.criticality === 'unknown')
    const structuredAuthDependencies = authDependencies
    const hasBusinessEvidence = exploration.status === 'matched' && (exploration.scores?.elementScore || 0) > 0
    const networkFailureReason = criticalBlocked.length && !hasBusinessEvidence
      ? 'network_blocked_critical'
      : unknownBlocked.length && !hasBusinessEvidence
        ? 'network_blocked_unknown'
        : exploration.failureReason
    const explorationWithNetwork = {
      ...exploration,
      networkBlocked,
      network: { criticalBlocked, optionalBlocked, unknownBlocked, authDependencies: structuredAuthDependencies },
      readiness,
      ...(networkFailureReason && networkFailureReason !== exploration.failureReason
        ? { failureReason: networkFailureReason }
        : {})
    }
    const finalObservation = explorationWithNetwork.finalObservation || await observePageEvidence(page)
    // 平台按页面保存证据：把最终页面的完整元素并入同一页面快照，其余页面只保留被步骤引用的元素。
    const pageSnapshotLimit = 8
    const finalSnapshotKey = `${finalObservation.pageKey || ''}|${finalObservation.pageFingerprint || finalObservation.fingerprint || ''}`
    const pageSnapshots = stepPageSnapshots.slice(0, pageSnapshotLimit)
    const finalSnapshot = pageSnapshots.find((snapshot) => `${snapshot.pageKey}|${snapshot.pageFingerprint}` === finalSnapshotKey)
    if (finalSnapshot) {
      const seenEvidenceIds = new Set(finalSnapshot.elements.map((element) => element.evidenceId))
      for (const element of (Array.isArray(finalObservation.elements) ? finalObservation.elements : [])) {
        if (element?.evidenceId && !seenEvidenceIds.has(element.evidenceId)) {
          finalSnapshot.elements.push(element)
          seenEvidenceIds.add(element.evidenceId)
        }
      }
      if (!finalSnapshot.roles.includes('final')) finalSnapshot.roles.push('final')
    }
    const screenshotUrl = await captureDiscoveryScreenshot(page, input, options, 'final')
    if (isCaptchaFailure(captchaFailureReason)) {
      throw buildEvidenceFailure(captchaFailureReason, page.url(), networkBlocked, exploration.repairTrail || [], manualHandoffDetails || createManualHandoff(captchaFailureReason, page.url(), networkBlocked, finalObservation))
    }
    const historicalMatchedIds = Array.from(historicalMatchedSet)
    const validationEligible = readiness.ready
      && exploration.status !== 'blocked'
      && criticalBlocked.length === 0
      && unknownBlocked.length === 0
      && (exploration.scores?.elementScore || 0) > 0
    const historicalRejectedIds = validationEligible
      ? historicalLocators.filter((candidate) => candidate?.entryId && !historicalMatchedSet.has(candidate.entryId)).map((candidate) => candidate.entryId)
      : []
    await options.assertAllowedUrl?.(page.url())
    const session = existingSession || (input.reuseSession
      ? createSession({
        sessionKey,
        projectId: input.projectId,
        ownerId: input.ownerId,
        environmentId: String(input.environmentId || ''),
        browser: String(input.browser || 'chromium'),
        // 同上：会话不记浏览器句柄会让 TTL 回收只关页面、不关进程。
        browserInstance: browser,
        context,
        page,
        observedOrigins: networkBlocked.map((entry) => entry.origin)
      })
      : undefined)
    if (session && discoveryHooks && !session.pageLocatorDiscoveryHooks) session.pageLocatorDiscoveryHooks = discoveryHooks
    if (session) touchSession(session.sessionId, {
      projectId: input.projectId,
      environmentId: String(input.environmentId || ''),
      ownerId: input.ownerId,
      sessionKey,
      browser: String(input.browser || 'chromium')
    })
    // 成功路径此刻才真正"持有"会话：此后失败/完成都保留会话供复用（TTL 回收）。
    if (!existingSession && session) retainSession = true
    if (resumeFromHandoff && input.handoffId) destroyHandoff(input.handoffId)
    // 采集侧最后一道登录门（复核方 D2）：登录失败、复用会话过期或 cookie 门未过时，页面会被挡回登录页，
    // readiness 给出 ready=false + auth_context_lost。此前这条路径仍可能 resolve，下游拿到的是登录页元素。
    // 与入口页同一口径 fail-closed；分类沿用 login_failed（平台/前端词表内）。
    // 复核方 14 轮 low-⑤ / 15 轮 A-4：重登修复成功后的 payload 里可能还带着登录阶段采到的陈旧 readiness
    // 样本（最后一次观测发生在重登之前，pageKey 停在登录路由）。触发条件必须是**采集内部真实发生过的
    // 重登**（`reauthenticatedDuringRun`，在 reauthenticate 动作成功后置位）——此前的 `input.rounds`
    // 判定在生产不可达（平台 DTO 无该字段），复核方实测 v1（生产形态）仍是陈旧样本。
    if (finalObservation && reauthenticatedDuringRun) {
      finalObservation.readiness = await waitForPageReadiness(page, intent, networkBlocked, Math.min(timeout, 5_000))
    }
    const finalReadiness = finalObservation?.readiness
    // 只对「配置了登录的用例」启用这道门，并且要求**采集结束的页面不是起始路由**（复核方 C5 + 12 轮 high-2）：
    //   · 页面里出现密码框 +「退出登录」这类文本（改密页/新增用户/凭据配置页）会让 readiness 也判成
    //     loginShell，但那与登录失败无关 —— 未配置登录、或采集就结束在起始页（业务页）都不能丢弃整轮采集；
    //   · 判定用**证据页**（readiness 自己的 pageKey），不是实时 URL：会话失效回跳时证据页在登录路由、
    //     而应用可能已把实时 URL 重定向回 #/app，用实时 URL 会把门跳过，登录页元素照样被采（复核方 high-2）。
    const evidencePageUrl = String(finalReadiness?.pageKey || finalObservation?.pageKey || page.url() || '')
    const endedAwayFromStart = hasChangedRoutePath(input.url || input.login?.url || '', evidencePageUrl)
    // 门 1（复核方 D2 + 12 轮 high-2 + 13 轮 C-4）：配置了登录、采集结束在非起始路由、本轮没有真的走通
    // 凭据登录，且 readiness 显式给出会话上下文丢失 ⇒ fail-closed。
    const bouncedToLogin = Boolean(input.login?.enabled
      && endedAwayFromStart
      && !loginCompleted
      && finalReadiness?.ready === false
      && finalReadiness?.failureReason === 'auth_context_lost')
    // 门 2（复核方 C-4/C-5 + 14 轮 C-②）：证据页本身就是**登录表单**时同样 fail-closed —— URL 可能没有
    // 变化（同路由渲染登录视图），也可能凭据登录刚「成功」又被挡回，或者登录墙没有密码框（短信/扫码登录，
    // 其文本命中 intent 词表时 readiness 会判 ready=true）。因此这里**不要求 readiness 非 ready**：
    // 只看「配置了登录 + 证据元素是登录表单」。业务页里的「退出登录 / 修改密码 / 保存 / 用户中心」不命中。
    const evidenceIsLoginShell = Boolean(input.login?.enabled
      && evidenceLooksLikeLoginShell(finalObservation?.elements))
    if (bouncedToLogin || evidenceIsLoginShell) {
      throw Object.assign(
        new Error(`自动登录失败：采集结束时页面仍停留在登录态（readiness=${finalReadiness?.failureReason || 'not_ready'}，url=${page.url()}），不产出登录页证据`),
        {
          failureType: 'login_failed',
          category: 'login_failed',
          pageUrl: page.url(),
          retryable: false,
          network: networkDiagnostic(networkBlocked),
          repairTrail: []
        }
      )
    }
    return {
      pageUrl: page.url(),
      sourcePointId: input.sourcePointId,
      elements: finalObservation.elements || [],
      exploration: explorationWithNetwork,
      finalObservation,
      rounds: explorationWithNetwork.rounds,
      status: explorationWithNetwork.status,
      failureReason: explorationWithNetwork.failureReason,
      sessionId: session?.sessionId,
      // authenticated 的语义是「应用真的可用」，不是「有会话」：复用会话（生产固定 reuseSession=true）时
      // 页面可能已被登录门挡回登录页，readiness 会给出 ready=false + auth_context_lost（复核方 D2）。
      // 登录门判定只在**配置了登录**、且**采集结束的页面不是起始路由**时成立（复核方 C5 + high-2）；
      // 未复用会话但登录流程真的走通时，session 对象不存在不代表未认证（复核方 low-3）。
      authenticated: Boolean(input.login?.enabled ? (session || loginCompleted) : true)
        && !bouncedToLogin
        && !evidenceIsLoginShell,
      historicalMatchedIds,
      historicalRejectedIds,
      stepEvidence,
      pageSnapshots,
      // 不可观测子树的诊断（unsupported frames / closed shadow hosts / 无法唯一寻址的 shadow host）：
      // 平台 DTO 必须带上它，否则「某棵子树根本没被采集」在传输中丢失，只剩间接报错。
      unsupported: finalObservation.unsupported || {},
      traceId: trace?.traceId,
      screenshotUrl
    }
  } catch (error) {
    const resumedCaptchaFailure = resumeFromHandoff
      && input.handoffId
      && isCaptchaFailure(error?.category || error?.handoff?.reason)
      && error?.handoff?.resumeSupported === true
    if (resumeFromHandoff && input.handoffId && !resumedCaptchaFailure) {
      destroyHandoffSession(input.handoffId)
      retainSession = false
    } else if (resumedCaptchaFailure) {
      retainSession = true
    }
    traceReason = error?.category || error?.handoff?.reason || 'exception'
    if (error && typeof error === 'object') {
      const screenshotUrl = await captureDiscoveryScreenshot(page, input, options, 'failure')
      if (screenshotUrl) error.screenshotUrl = screenshotUrl
      if (trace?.traceId) error.traceId = trace.traceId
    }
    throw error
  } finally {
    if (sessionLease?.ok) {
      releaseSessionLease(sessionLease.session.sessionId, sessionLease.operationId)
    }
    await stopBoundedTracing(trace, manualHandoffDetails ? 'manual_required' : traceReason).catch(() => {})
    if (!retainSession) {
      await page?.close?.().catch(() => {})
      await context?.close?.().catch(() => {})
      if (browser) await browser.close().catch(() => {})
    }
  }
}

const normalizeHandoffPageUrlPath = (value) => {
  const raw = String(value || '').trim()
  if (!raw) return ''
  try {
    const parsed = new URL(raw)
    const pathname = parsed.pathname || '/'
    const hash = parsed.hash ? `#${parsed.hash.slice(1).split('?', 1)[0]}` : ''
    return `${pathname}${hash}`
  } catch {
    const [beforeHash, hashPart = ''] = raw.split('#', 2)
    const pathname = beforeHash.split('?', 1)[0]
    const hash = hashPart ? `#${hashPart.split('?', 1)[0]}` : ''
    return `${pathname}${hash}`
  }
}

export const waitForPageReadiness = async (page, intentOrTimeout, networkStateOrTimeout, maybeTimeoutMs) => {
  const hasIntent = intentOrTimeout && typeof intentOrTimeout === 'object'
  const intent = hasIntent ? normalizeIntent(intentOrTimeout) : normalizeIntent({})
  const timeoutMs = hasIntent ? (maybeTimeoutMs ?? networkStateOrTimeout) : intentOrTimeout
  const networkState = hasIntent && Array.isArray(networkStateOrTimeout) ? networkStateOrTimeout : []
  const started = Date.now()
  const deadline = started + Math.max(250, Math.min(5_000, Number(timeoutMs) || 5_000))
  const normalizeReadinessText = (value) => String(value || '').trim().toLowerCase().replace(/\s+/g, '')
  let previousSample
  let latest = {
    ready: false,
    failureReason: 'page_not_ready',
    pageKey: '',
    pageFingerprint: '',
    shellFingerprint: ''
  }
  while (Date.now() < deadline) {
    const rawState = await page.evaluate(() => {
      const visible = (element) => {
        if (!element) return false
        const style = getComputedStyle(element)
        const rect = element.getBoundingClientRect()
        return style.display !== 'none'
          && style.visibility !== 'hidden'
          && Number(style.opacity) !== 0
          && rect.width > 0
          && rect.height > 0
      }
      const semanticText = (element) => String([
        element.getAttribute?.('aria-label'),
        element.getAttribute?.('title'),
        element.getAttribute?.('placeholder'),
        element.innerText,
        element.textContent
      ].filter(Boolean).join(' ')).trim()
      const headingElements = Array.from(document.querySelectorAll('h1,h2,h3,h4,[role="heading"]')).filter(visible)
      const headings = headingElements.map((element) => String(element.innerText || element.textContent || '').trim()).filter(Boolean).slice(0, 12)
      const controls = Array.from(document.querySelectorAll('a,button,input,select,textarea,[role="button"],[role="link"],[role="menuitem"],[role="tab"],label')).filter(visible)
      const controlTexts = controls.map((element) => semanticText(element)).filter(Boolean).slice(0, 20)
      const controlRecords = controls.map((element) => ({
        label: semanticText(element),
        role: String(element.getAttribute('role') || '').trim().toLowerCase() || String(element.tagName || '').trim().toLowerCase(),
        tagName: String(element.tagName || '').trim().toLowerCase(),
        pageScope: String(element.closest('main,nav,aside,form,section,article,[role="dialog"],[aria-modal="true"]')?.tagName || '').trim().toLowerCase(),
        rowContext: String(element.closest('tr,[role="row"],li,[role="listitem"]')?.innerText || '').trim()
      })).filter((item) => item.label)
      const targetSignals = Array.from(new Set([...headings, ...controlTexts, String(document.title || '').trim()].filter(Boolean)))
      const loginFields = Array.from(document.querySelectorAll('input[type="password"],input[name*="password" i]')).filter(visible).length
      const loginShell = loginFields > 0 && targetSignals.some((value) => /登录|登陆|sign in|log in/i.test(value))
      const errorPage = targetSignals.some((value) => /404|500|错误|出错|异常|not found|failed|failure|拒绝|unauthori[zs]ed|forbidden/i.test(value))
      const bodyText = String(document.body?.innerText || '').trim().slice(0, 500)
      const emptyShell = headings.length === 0 && controlTexts.length === 0 && !bodyText
      const readyMarker = Array.from(document.querySelectorAll('[data-ready="true"],[data-state="ready"],[aria-busy="false"]')).some(visible)
      const captchaIframe = Array.from(document.querySelectorAll('iframe[src*="captcha" i]')).some(visible)
      const captchaMarked = Array.from(document.querySelectorAll('[id*="captcha" i],[class*="captcha" i]')).some(visible)
      const captchaSemantic = Array.from(document.querySelectorAll('button,input,select,textarea,[role],label')).some((element) => {
        if (!visible(element)) return false
        return /captcha|验证码|安全验证|人机验证/i.test(semanticText(element))
      })
      const pageKey = location.href
      return {
        pageKey,
        title: String(document.title || '').trim(),
        headings,
        controlTexts,
        controlRecords,
        targetSignals,
        bodyText,
        loginShell,
        errorPage,
        emptyShell,
        readyMarker,
        captchaState: captchaIframe || captchaMarked
          ? 'captcha_required'
          : captchaSemantic
            ? 'manual_required'
            : ''
      }
    }).catch(() => ({
      pageKey: normalizePageKey(page.url()),
      title: '',
      headings: [],
      controlTexts: [],
      controlRecords: [],
      targetSignals: [],
      bodyText: '',
      loginShell: false,
      errorPage: false,
      emptyShell: true,
      readyMarker: false,
      captchaState: ''
    }))

    const state = rawState && typeof rawState === 'object' && !Array.isArray(rawState)
      ? rawState
      : {
        pageKey: normalizePageKey(page.url()),
        title: '',
        headings: [],
        controlTexts: [],
        controlRecords: [],
        targetSignals: [],
        bodyText: '',
        loginShell: false,
        errorPage: false,
        emptyShell: true,
        readyMarker: false,
        captchaState: ''
      }
    const headings = Array.isArray(state.headings) ? state.headings : []
    const controlTexts = Array.isArray(state.controlTexts) ? state.controlTexts : []
    const bodyText = String(state.bodyText || '').trim().slice(0, 500)
    const shellFingerprint = pageFingerprint({
      pageKey: state.pageKey,
      title: state.title,
      headings,
      activeMenus: controlTexts.filter((value) => /菜单|导航|首页|管理|列表/.test(value)),
      elements: Array.isArray(state.controlRecords) ? state.controlRecords : []
    })
    const readinessText = normalizeReadinessText([
      ...headings,
      ...controlTexts,
      bodyText,
      state.title,
      state.pageKey
    ].join(' '))
    const targetTerms = [...intent.pageAliases, ...intent.routeHints, ...intent.elementTerms, ...intent.expectedTexts]
      .map(normalizeReadinessText)
      .filter(Boolean)
    const targetMatched = targetTerms.length > 0 && targetTerms.some((term) => [
        normalizeReadinessText(state.pageKey),
        normalizeReadinessText(state.title),
        readinessText
      ].some((text) => text.includes(term)))
    const businessMatched = [...intent.elementTerms, ...intent.expectedTexts]
      .map(normalizeReadinessText)
      .filter(Boolean)
      .some((term) => readinessText.includes(term))
    const routeMatched = [...intent.routeHints].map(normalizeReadinessText).filter(Boolean)
      .some((term) => normalizeReadinessText(state.pageKey).includes(term))
    const emptyShell = !bodyText && headings.length === 0 && controlTexts.length === 0
    const successSignal = Boolean(state.readyMarker || targetMatched || businessMatched || routeMatched || (!targetTerms.length && Boolean(bodyText)))
    const stableSample = previousSample
      && previousSample.pageKey === state.pageKey
      && previousSample.shellFingerprint === shellFingerprint
      && Date.now() - previousSample.at >= 100
    latest = state.loginShell
      ? { ...state, shellFingerprint, pageFingerprint: shellFingerprint, ready: false, failureReason: 'auth_context_lost' }
        : state.captchaState
          ? { ...state, shellFingerprint, pageFingerprint: shellFingerprint, ready: false, failureReason: state.captchaState }
          : state.errorPage
            ? { ...state, shellFingerprint, pageFingerprint: shellFingerprint, ready: false, failureReason: 'page_error' }
          : emptyShell
            ? { ...state, shellFingerprint, pageFingerprint: shellFingerprint, ready: false, failureReason: 'page_not_ready' }
            : stableSample && successSignal
              ? { ...state, shellFingerprint, pageFingerprint: shellFingerprint, ready: true, failureReason: '' }
              : { ...state, shellFingerprint, pageFingerprint: shellFingerprint, ready: false, failureReason: networkState.some((entry) => entry.criticality === 'critical') ? 'business_api_failed' : 'page_not_ready' }
    if (latest.ready) {
      return { ...latest, durationMs: Date.now() - started, pageFingerprint: shellFingerprint, shellFingerprint }
    }
    previousSample = { pageKey: state.pageKey, shellFingerprint, at: Date.now() }
    await page.waitForTimeout(100)
  }
  return { ...latest, durationMs: Date.now() - started }
}

const GENERIC_NAVIGATION_LABELS = new Set(['项目', '设备', '应用', '系统', '服务', '用户', '首页', '管理'])

const normalizeNavigationText = (value) => String(value || '')
  .trim()
  .replace(/\s+/g, '')
  .replace(/[，。；、：:（）()【】\[\]]/g, '')
  .replace(/业务列表页|业务页面|列表页面|列表页|页面|模块|业务$/g, '')

const followNavigationHint = async (page, rawHints) => {
  const hints = Array.from(new Set((Array.isArray(rawHints) ? rawHints : [])
    .map(normalizeNavigationText)
    .filter((hint) => hint.length >= 2 && !GENERIC_NAVIGATION_LABELS.has(hint))))
  if (!hints.length) return

  let selected = await clickMatchingNavigation(page, hints)
  if (!selected) {
    const menuSearch = page.locator([
      'input[placeholder*="搜索菜单"]',
      'input[aria-label*="搜索菜单"]',
      'input[placeholder*="菜单"]'
    ].join(',')).first()
    if (await menuSearch.count() > 0) {
      await menuSearch.fill(hints[0])
      await page.waitForTimeout(250)
      selected = await clickMatchingNavigation(page, hints)
    }
  }
  if (selected) await page.waitForTimeout(500)
}

const clickMatchingNavigation = async (page, hints) => {
  // 阶段 5（5.1）：DOM 只用于「找候选 + 打分」，点击一律走 Playwright locator
  // （自动等待 + actionability：被遮挡/禁用的候选不会像原生 click() 那样绕过去）。
  const match = await page.evaluate(({ normalizedHints, genericLabels }) => {
    const normalize = (value) => String(value || '')
      .trim()
      .replace(/\s+/g, '')
      .replace(/[，。；、：:（）()【】\[\]]/g, '')
      .replace(/业务列表页|业务页面|列表页面|列表页|页面|模块|业务$/g, '')
    const visible = (element) => {
      const style = getComputedStyle(element)
      const rect = element.getBoundingClientRect()
      return style.display !== 'none' && style.visibility !== 'hidden'
        && Number(style.opacity) !== 0 && rect.width >= 2 && rect.height >= 2
    }
    const candidates = Array.from(document.querySelectorAll([
      '[role="menuitem"]',
      'nav a',
      'nav button',
      'aside a',
      'aside button',
      '.ant-menu-item',
      '.el-menu-item',
      '[class*="menu-item"]',
      'button[class*="menuSearchResult"]',
      'button[class*="menu-search-result"]',
      '[role="option"]'
    ].join(','))).filter(visible)
    const matches = candidates.flatMap((element) => {
      if (element.tagName === 'A' && element.href) {
        try {
          if (new URL(element.href, location.href).origin !== location.origin) return []
        } catch {
          return []
        }
      }
      const label = String(element.getAttribute('aria-label') || element.getAttribute('title') || element.innerText || '').trim().replace(/\s+/g, ' ')
      const normalizedLabel = normalize(label)
      if (normalizedLabel.length < 2 || genericLabels.includes(normalizedLabel)) return []
      const score = normalizedHints.reduce((best, hint) => {
        if (hint === normalizedLabel) return Math.max(best, 10_000 + normalizedLabel.length)
        if (hint.includes(normalizedLabel) || normalizedLabel.includes(hint)) return Math.max(best, normalizedLabel.length)
        return best
      }, 0)
      return score > 0 ? [{ label, score, role: element.getAttribute('role') || '', tag: element.tagName.toLowerCase() }] : []
    }).sort((left, right) => right.score - left.score)
    if (!matches.length) return null
    return matches[0]
  }, { normalizedHints: hints, genericLabels: Array.from(GENERIC_NAVIGATION_LABELS) })
  if (!match?.label) return undefined

  const implicitRole = { a: 'link', button: 'button', li: 'menuitem' }[match.tag] || ''
  const roleName = match.role && match.role !== 'presentation' ? match.role : implicitRole
  const locators = []
  if (roleName) locators.push(page.getByRole(roleName, { name: match.label, exact: true }))
  locators.push(page.getByText(match.label, { exact: true }))
  for (const locator of locators) {
    try {
      if (await locator.count() === 1 && await locator.first().isVisible()) {
        await locator.first().click()
        return match.label
      }
    } catch {
      // 候选定位器不可用（角色不合法/不可见）：继续尝试下一个口径，全部失败则不点击。
    }
  }
  return undefined
}

const loginIfConfigured = async (page, login, timeout, diagnostics = [], configuredRetries = 1) => {
  if (!login?.enabled) return
  if (!login.url || !login.usernameLocator || !login.passwordLocator || !login.submitLocator) {
    throw new Error('Browser locator discovery login configuration is incomplete')
  }
  // 登录入口同样不能是描述文字：非路由形状/非绝对地址一律 fail-closed（不校验同源，登录页常在另一域）。
  const loginEntryUrl = navigationTargetFromAction({ target: login.url }, { requireRouteShape: true })
  const maxRetries = Math.max(0, Math.min(2, Number(configuredRetries ?? 1)))
  let lastError
  for (let attempt = 0; attempt <= maxRetries; attempt += 1) {
    try {
      await page.goto(loginEntryUrl, { waitUntil: 'domcontentloaded', timeout })
      const loginPageUrl = page.url()
      await resolveLocator(page, login.usernameLocator).fill(login.username || '')
      await resolveLocator(page, login.passwordLocator).fill(login.password || '')
      await resolveLocator(page, login.submitLocator).click()
      if (login.successTarget) {
        try {
          if (login.successType === 'url') await waitForUrlMatch(page, login.successTarget, { timeout })
          else await resolveLocator(page, login.successTarget).waitFor({ state: 'visible', timeout })
        } catch (error) {
          if (hasLeftLoginRoute(loginPageUrl, page.url())) return
          throw error
        }
        return
      }
      // 没有 successTarget 时也必须校验「是否离开登录路由」（复核方 D2）：这里曾经直接 `return`
      // 当成功，于是采集侧对登录失败给出 outcome=resolved + 采集到登录页元素，而同一份配置在
      // DSL 侧是 fail-closed。判定与 DSL 共用 login-route.mjs，两侧只有一份实现。
      // 复核方 14 轮 A4-7a / C-①：这里必须**轮询**（与 DSL 的 waitForLoginExit 同一条共享实现），
      // 单发检查对异步提交是竞态 —— 点击后 174ms 判「没离开」导致真实登录成功被报 login_failed。
      // 预算取 5s：DSL 侧该等待的默认值（`step.timeoutMs || 5000`）；step 级 timeoutMs 采集侧不可见，
      // 对齐默认值可保证两侧对常见异步提交（几百毫秒级跳转）判定一致。
      await waitForLoginRouteExit(page, loginPageUrl, Math.max(1_000, Math.min(5_000, timeout)))
      if (!hasLeftLoginRoute(loginPageUrl, page.url())) {
        throw new Error(`login did not leave the login route: still on "${page.url()}"`)
      }
      return
    } catch (error) {
      lastError = error
      if (attempt >= maxRetries || hasLeftLoginRoute(login.url, page.url())) break
    }
  }
  throw new Error(formatLoginFailureMessage(login, page, lastError, diagnostics))
}

const formatLoginFailureMessage = (login, page, error, diagnostics = []) => {
  const originalMessage = error instanceof Error ? error.message : String(error || '自动登录失败')
  return [
    '自动登录失败：Web 浏览器已尝试执行运行环境中的登录步骤，但未达到登录成功判定。',
    `登录页=${sanitizeBlockedDiagnostic({ url: login.url }).url || ''}`,
    `当前页面=${sanitizeBlockedDiagnostic({ url: typeof page.url === 'function' ? page.url() : '' }).url || ''}`,
    `usernameLocator=${sanitizeLocatorReference(login.usernameLocator)}`,
    `passwordLocator=${sanitizeLocatorReference(login.passwordLocator)}`,
    `submitLocator=${sanitizeLocatorReference(login.submitLocator)}`,
    `successType=${login.successType || 'url'}`,
    `successTarget=${login.successType === 'url' ? (sanitizeBlockedDiagnostic({ url: login.successTarget }).url || '') : sanitizeLocatorReference(login.successTarget)}`,
    diagnostics.length ? `网络诊断=${diagnostics.slice(-5).join('；')}` : '',
    `原始错误=${originalMessage}`
  ].filter(Boolean).join(' ')
}

const isOptionalNotFoundResponse = ({ status, criticality, resourceType, url, loginUrl, observedBusinessSignals = [] } = {}) => {
  if (Number(status) !== 404 || criticality !== 'critical' || !['xhr', 'fetch'].includes(String(resourceType || '').toLowerCase())) return false
  let request
  try {
    request = new URL(String(url || ''))
  } catch {
    return false
  }
  if (sameOriginPath(request, loginUrl)) return false
  const businessSignals = observedBusinessSignals instanceof Set ? observedBusinessSignals : new Set(observedBusinessSignals || [])
  return !Array.from(businessSignals).some((signal) => normalizeRequestPath(signal) === request.pathname)
}

const sameOriginPath = (left, right) => {
  if (!right) return false
  try {
    const expected = new URL(String(right))
    return left.origin === expected.origin && left.pathname === expected.pathname
  } catch {
    return false
  }
}

const normalizeRequestPath = (value) => {
  try {
    return new URL(String(value || ''), 'http://placeholder.local').pathname
  } catch {
    return String(value || '').split(/[?#]/, 1)[0] || ''
  }
}

const sanitizeLocatorReference = (value) => {
  const text = String(value || '').trim()
  const prefix = text.match(/^([a-zA-Z][a-zA-Z0-9_-]*)=/)?.[1]
  return prefix ? `${prefix}=[redacted]` : '[redacted]'
}

const formatSafeRequestDiagnostic = (method, url, detail = '') => {
  const safe = sanitizeBlockedDiagnostic({
    url,
    detail,
    resourceType: 'xhr',
    criticality: 'unknown',
    failureType: 'network'
  })
  return `${String(method || 'GET').toUpperCase()} ${safe.url || safe.origin || ''}${safe.detail ? ` -> ${safe.detail}` : ''}`
}

const networkDiagnostic = (entries = []) => ({
  criticalBlocked: entries.filter((entry) => entry.criticality === 'critical'),
  optionalBlocked: entries.filter((entry) => entry.criticality === 'optional'),
  unknownBlocked: entries.filter((entry) => entry.criticality === 'unknown'),
  authDependencies: entries.filter((entry) => entry.criticality === 'auth_dependency')
})

const normalizedRouteHints = (intent) => normalizeIntent(intent).routeHints

const ACTION_IR_TYPES = new Set([
  'navigate', 'goto', 'click', 'fill', 'input', 'select', 'hover', 'assert',
  'assertVisible', 'assertHidden', 'assertText', 'assertUrl', 'assertEnabled', 'assertDisabled',
  'waitForVisible', 'waitForUrl', 'scrollIntoView', 'paginate', 'screenshot'
])
const ACTION_IR_ASSERTIONS = new Set(['visible', 'hidden', 'text', 'url', 'enabled', 'disabled'])
// 断言类型别名：平台侧（ActionIrValidator）词表与本仓库规范写法（snake_case）都必须被接受，
// 否则 {type:'assert', assertion:{type:'assert_url'}} 在平台侧合法、在 runner 侧被整条判成 unsupported。
const ACTION_IR_ASSERTION_ALIASES = new Map([
  ['assertvisible', 'visible'], ['assert_visible', 'visible'],
  ['asserthidden', 'hidden'], ['assert_hidden', 'hidden'],
  ['asserttext', 'text'], ['assert_text', 'text'],
  ['asserturl', 'url'], ['assert_url', 'url'],
  ['assertenabled', 'enabled'], ['assert_enabled', 'enabled'],
  ['assertdisabled', 'disabled'], ['assert_disabled', 'disabled']
])
const canonicalAssertionType = (value) => {
  const normalized = String(value || '').trim().toLowerCase()
  const alias = ACTION_IR_ASSERTION_ALIASES.get(normalized)
  if (alias) return alias
  return ACTION_IR_ASSERTIONS.has(normalized) ? normalized : ''
}
const ASSERTION_ACTION_TYPES = { visible: 'assertVisible', hidden: 'assertHidden', text: 'assertText', url: 'assertUrl', enabled: 'assertEnabled', disabled: 'assertDisabled' }
const ACTION_IR_SAFETY = new Set(['readonly', 'mutating', 'dangerous'])
const ACTION_IR_TYPE_ALIASES = new Map([
  ['wait_for_visible', 'waitForVisible'], ['wait_for_url', 'waitForUrl'],
  ['assert_visible', 'assertVisible'], ['assert_hidden', 'assertHidden'],
  ['assert_text', 'assertText'], ['assert_url', 'assertUrl'],
  ['assert_enabled', 'assertEnabled'], ['assert_disabled', 'assertDisabled'],
  ['scroll_into_view', 'scrollIntoView']
])
const canonicalActionIrType = (type) => ACTION_IR_TYPE_ALIASES.get(String(type || '').trim()) || String(type || '').trim()
const actionIdForIr = (action = {}) => String(action.id || action.actionId || '').trim()
const actionTargetForContract = (action = {}) => action.type === 'assert'
  ? (action.assertion?.target || action.assertion?.semanticTarget)
  : action.target

export const validateActionIr = (actions) => {
  if (!Array.isArray(actions)) return
  for (const [index, action] of actions.entries()) {
    if (!action || typeof action !== 'object' || Array.isArray(action)) {
      throw Object.assign(new Error(`Action IR action ${index + 1} must be an object`), { failureType: 'invalid_action_ir', category: 'invalid_action_ir' })
    }
    const type = canonicalActionIrType(action.type)
    if (!actionIdForIr(action)) continue
    if (!ACTION_IR_TYPES.has(type)) throw Object.assign(new Error(`Unsupported Action IR type: ${type || '(empty)'}`), { failureType: 'invalid_action_ir', category: 'invalid_action_ir' })
    if (!ACTION_IR_SAFETY.has(String(action.safety || '').trim())) throw Object.assign(new Error(`Action IR ${actionIdForIr(action)} has invalid safety`), { failureType: 'invalid_action_ir', category: 'invalid_action_ir' })
    const assertionKey = type === 'assert' ? canonicalAssertionType(action.assertion?.type) : ''
    if (type === 'assert' && !assertionKey) throw Object.assign(new Error(`Unsupported Action IR assertion: ${String(action.assertion?.type || '').trim() || '(empty)'}`), { failureType: 'invalid_action_ir', category: 'invalid_action_ir' })
    const needsElementTarget = !['navigate', 'goto', 'waitForUrl', 'screenshot', 'assertUrl'].includes(type)
      && !(type === 'assert' && assertionKey === 'url')
    if (needsElementTarget) {
      // 元素型动作收到 route 目标：平台 ActionIrValidator 曾放行，执行器随后在 7 处解引用 undefined（TypeError → 503）。
      const target = actionTargetForContract(action)
      if (target && typeof target === 'object' && !Array.isArray(target) && String(target.kind || '').toLowerCase() === 'route') {
        throw Object.assign(new Error(`Action IR ${actionIdForIr(action)} requires an element target but received a route target`), { failureType: 'invalid_action_ir', category: 'invalid_action_ir' })
      }
      validateActionTarget(target, actionIdForIr(action))
    }
    if (type === 'navigate' || type === 'goto' || type === 'assertUrl' || type === 'waitForUrl') {
      if (!action.target || typeof action.target !== 'object' || action.target.kind !== 'route' || !String(action.target.value || '').trim()) throw Object.assign(new Error(`Action IR ${actionIdForIr(action)} requires a route target`), { failureType: 'invalid_action_ir', category: 'invalid_action_ir' })
      // 值存在但不具备路由形状（'not a url'、'威胁情报'）：执行时会 fail-closed，这里提前拦下，
      // 避免「计划校验放行 → 执行期才炸」以及层间口径不一致。
      if (!isRouteShaped(action.target.value)) throw Object.assign(new Error(`Action IR ${actionIdForIr(action)} route target is neither a route nor an absolute url: ${String(action.target.value)}`), { failureType: 'unsupported_action_target', category: 'unsupported_action_target' })
    }
    if (type === 'assertText' && !String(action.expected || action.assertion?.expected || '').trim()) throw Object.assign(new Error(`Action IR ${actionIdForIr(action)} requires expected text`), { failureType: 'invalid_action_ir', category: 'invalid_action_ir' })
    if (type === 'assert' && assertionKey === 'text' && !String(action.assertion?.expected || '').trim()) throw Object.assign(new Error(`Action IR ${actionIdForIr(action)} requires expected text`), { failureType: 'invalid_action_ir', category: 'invalid_action_ir' })
    if (type === 'assert' && assertionKey === 'url') {
      const routeTarget = action.assertion?.target || action.assertion?.semanticTarget
      if (!routeTarget || routeTarget.kind !== 'route' || !String(routeTarget.value || '').trim()) throw Object.assign(new Error(`Action IR ${actionIdForIr(action)} requires a route assertion target`), { failureType: 'invalid_action_ir', category: 'invalid_action_ir' })
      // 与 navigate/goto/assertUrl 同口径：值存在但没有路由形状（'设备详情页'）不能留到执行期。
      if (!isRouteShaped(routeTarget.value)) throw Object.assign(new Error(`Action IR ${actionIdForIr(action)} route assertion target is neither a route nor an absolute url: ${String(routeTarget.value)}`), { failureType: 'unsupported_action_target', category: 'unsupported_action_target' })
    }
  }
}

const validateActionTarget = (target, actionId) => {
  if (!target || typeof target !== 'object' || Array.isArray(target)) throw Object.assign(new Error(`Action IR ${actionId} requires an evidence target`), { failureType: 'invalid_action_ir', category: 'invalid_action_ir' })
  if (target.locator || target.selector || target.css || target.xpath) throw Object.assign(new Error(`Action IR ${actionId} cannot provide an executable locator target`), { failureType: 'invalid_action_ir', category: 'invalid_action_ir' })
  if (!String(target.evidenceId || '').trim()) throw Object.assign(new Error(`Action IR ${actionId} target must contain evidenceId`), { failureType: 'invalid_action_ir', category: 'invalid_action_ir' })
}

const isStructuredActionTarget = (target) => Boolean(target) && typeof target === 'object' && ('kind' in target || 'value' in target)
const DANGEROUS_NAVIGATION_SCHEME = /^(?:javascript|data|vbscript|file|blob):/i

// 与平台 looksLikeRouteTarget / ActionIrValidator.requireRouteTarget 同口径的「路由形状」判定。
// 接受：绝对 http(s) 地址、/ # ? ./ ../ 前缀（其后可以是任意非空白字符，含中文路径）、ASCII 路径片段（detail、users/detail）。
// 拒绝：含空白的描述文字（'not a url'、'a b'）、纯自然语言（'威胁情报'）、字符串化产物（'[object Object]'）、危险 scheme。
// 少了这一层，runner 会把描述文字百分号编码后真的导航到 /not%20a%20url 并记 passed——层间口径也会不一致。
const ROUTE_TARGET_PREFIX = /^(?:https?:\/\/|[/#?]|\.\.?\/)/i
const ROUTE_TARGET_SLUG = /^[A-Za-z0-9][A-Za-z0-9._~%/-]*$/
const isRouteShaped = (value) => {
  const candidate = typeof value === 'string' ? value.trim() : ''
  if (!candidate) return false
  if (/\s/.test(candidate) || /\[object /i.test(candidate) || DANGEROUS_NAVIGATION_SCHEME.test(candidate)) return false
  return ROUTE_TARGET_PREFIX.test(candidate) || ROUTE_TARGET_SLUG.test(candidate)
}

// 导航类动作的 URL 只能来自字符串：结构化 target（{kind:'route', value:…}）必须取 value，
// 直接 String(对象) 会得到 '[object Object]' 并真的把页面导航到 /[object%20Object]。
// 校验刻意「宽进严出」：合法的同源相对路径（detail / users/detail / ?q=1 / #/x）必须接受，
// 而字符串化产物、危险 scheme、非字符串 target 一律 fail-closed（unsupported_action_target）。
const navigationTargetFromAction = (action = {}, { requireRouteShape = false } = {}) => {
  const rawTarget = action.target
  if (rawTarget != null && typeof rawTarget === 'object' && !isStructuredActionTarget(rawTarget)) {
    throw Object.assign(new Error(`navigation target must be a string or a route target: ${actionIdForIr(action) || action.type || 'action'}`), { failureType: 'unsupported_action_target' })
  }
  const candidate = typeof action.url === 'string'
    ? action.url
    : (isStructuredActionTarget(rawTarget) ? rawTarget.value : rawTarget)
  const value = typeof candidate === 'string' ? candidate.trim() : ''
  if (!value) throw Object.assign(new Error(`navigation target is missing: ${actionIdForIr(action) || action.type || 'action'}`), { failureType: 'unsupported_action_target' })
  if (/\[object /i.test(value) || DANGEROUS_NAVIGATION_SCHEME.test(value)) {
    throw Object.assign(new Error(`navigation target is not a URL: ${value}`), { failureType: 'unsupported_action_target' })
  }
  if (requireRouteShape && !isRouteShaped(value)) {
    throw Object.assign(new Error(`navigation target is neither a route nor an absolute url: ${value}`), { failureType: 'unsupported_action_target' })
  }
  return value
}

// 统一入口：解析 + scheme 白名单 + 同源校验。所有导航/地址断言都必须走这里，
// 否则就会出现「一个通路 fail-closed、另一个通路把垃圾地址真的导航出去」的不一致。
// 默认要求「路由形状」；只有 legacy direct-navigation 允许先取裸标签（随后必须用 route hints/history 证明）。
const resolveNavigationUrl = (action, baseUrl, { requireRouteShape = true } = {}) => {
  const value = navigationTargetFromAction(action, { requireRouteShape })
  let url
  let base
  try {
    url = new URL(value, baseUrl)
    base = new URL(baseUrl)
  } catch {
    throw Object.assign(new Error(`navigation target is not a valid URL: ${value}`), { failureType: 'unsupported_action_target' })
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw Object.assign(new Error(`navigation target uses an unsupported scheme: ${url.protocol}`), { failureType: 'unsupported_action_target' })
  }
  if (url.origin !== base.origin) {
    throw Object.assign(new Error(`navigation must stay on ${base.origin}: ${url.href}`), { failureType: 'navigation_failed' })
  }
  // 站点入口语义：target '/' 这类根路径在解析时会丢掉 hash 路由（base '#/home' → 根文档），
  // 用例会 goto 到不渲染的 SPA 空壳并导致后续断言全部 element_not_found（实测 2026-10-14）。
  // 基址本身带 hash 路由时，沿用基址作为导航目标。
  if (url.pathname === '/' && !url.search && !url.hash && base.hash) {
    return base
  }
  return url
}

// legacy direct-navigation 的 target 有两种合法形态：① 路由形状的地址/路径；② 页面标签，
// 但标签必须能映射到 routeHints/history 里的某条路由，且**导航目标是那条路由**——
// 把标签百分号编码后当作地址打开（'detail page' → GET /detail%20page）是真实缺陷：
// 描述文字只要命中一个 ASCII 子串 hint 就会被执行，探索轮次还记 ok。
const resolveLegacyNavigationTarget = (action, baseUrl, { routeHints = [], history = [] } = {}) => {
  const bare = navigationTargetFromAction(action, { requireRouteShape: false })
  if (isRouteShaped(bare)) return resolveNavigationUrl(action, baseUrl, { requireRouteShape: false })
  const normalize = (value) => String(value || '').trim().toLowerCase().replace(/\s+/g, ' ')
  // 紧凑比较（去掉空白与斜杠）：'list page' 这类标签要能命中 '/list' 这类 hint——
  // 但匹配之后导航的是 **hint 指向的路由**，不是标签本身。
  const compact = (value) => normalize(value).replace(/[/]+/g, '')
  const normalizedBare = normalize(bare)
  const compactBare = compact(bare)
  const hints = [...(Array.isArray(routeHints) ? routeHints : []), ...(Array.isArray(history) ? history : [])]
    .filter((hint) => String(hint || '').trim())
    .map((hint) => ({ raw: String(hint).trim(), normalized: normalize(hint), compact: compact(hint) }))
  const matchedHint = compactBare
    ? hints.find((hint) => hint.normalized === normalizedBare
      || hint.compact === compactBare
      || (hint.compact.length >= 2 && (compactBare.includes(hint.compact) || hint.compact.includes(compactBare))))
    : undefined
  if (!matchedHint) {
    throw Object.assign(new Error(`direct navigation target is neither a route nor a known route hint: ${bare}`), { failureType: 'unsupported_action_target' })
  }
  // 命中标签 → 路由映射：导航到 hint 指向的路由，而不是标签本身。
  return resolveNavigationUrl({ target: matchedHint.raw }, baseUrl, { requireRouteShape: true })
}

const validateRequestedActions = (page, actions, routeHints, history) => {
  for (const action of Array.isArray(actions) ? actions : []) {
    if (action?.force === true) throw Object.assign(new Error('force click is not allowed'), { failureType: 'unsafe_action' })
    if (action?.type !== 'direct-navigation') continue
    // legacy direct-navigation 的 target 可能是裸标签（页面名）：只有能映射到一条已知路由时才放行，
    // 且导航目标是那条路由，绝不是把标签百分号编码后当地址打开。
    resolveLegacyNavigationTarget(action, page.url(), { routeHints, history })
  }
}

const executeActionIr = async (page, actions, evidence, context) => {
  const observedTargets = new Map()
  const pageSnapshots = Array.isArray(context.pageSnapshots) ? context.pageSnapshots : []
  // 每个页面快照最多带多少个元素：既要让平台能为该页装配出可用证据，又不能把载荷撑大。
  const pageSnapshotElementLimit = 20
  const snapshotIndex = new Map()
  // 每个步骤的 before/after 各自记录一份页面快照（含该步骤引用的元素），供平台按页面保存证据。
  const recordPageSnapshot = (observation, actionId, role, element) => {
    const pageKey = String(observation?.pageKey || '').trim()
    const pageFingerprint = String(observation?.pageFingerprint || observation?.fingerprint || '').trim()
    if (!pageKey || !pageFingerprint) return
    const key = `${pageKey}|${pageFingerprint}`
    let snapshot = snapshotIndex.get(key)
    if (!snapshot) {
      snapshot = { pageKey, pageFingerprint, pageUrl: String(observation?.url || ''), roles: [], actionIds: [], elements: [] }
      snapshotIndex.set(key, snapshot)
      pageSnapshots.push(snapshot)
    }
    if (role && !snapshot.roles.includes(role)) snapshot.roles.push(role)
    if (actionId && !snapshot.actionIds.includes(actionId)) snapshot.actionIds.push(actionId)
    const referenced = element?.evidenceId ? [element] : []
    // 没有引用具体元素的步骤（例如 navigate/route）也必须让页面快照带上可用元素，
    // 否则平台按页装配时会因为「空页面证据」丢弃起始页，跨页导航型用例直接编译失败。
    const fallbackPool = referenced.length ? [] : (Array.isArray(observation?.elements) ? observation.elements : [])
    for (const candidate of [...referenced, ...fallbackPool.slice(0, pageSnapshotElementLimit)]) {
      if (candidate?.evidenceId && !snapshot.elements.some((item) => item.evidenceId === candidate.evidenceId)) {
        snapshot.elements.push(candidate)
      }
    }
    // 兜底集合也可能为空（页面确实没有任何可采集元素）：这时必须留下显式诊断，
    // 否则下游只能看到一个空的 elements 数组，无法区分「没有元素」和「采集失败」。
    if (snapshot.elements.length === 0) {
      snapshot.elementsEmpty = true
      snapshot.elementsEmptyReason = 'no_collectable_elements'
    }
  }
  const observedElementFor = (observation, evidenceId) => {
    if (!evidenceId) return undefined
    const pool = Array.isArray(observation?.allElements) && observation.allElements.length
      ? observation.allElements
      : (Array.isArray(observation?.elements) ? observation.elements : [])
    return pool.find((item) => item?.evidenceId === evidenceId)
  }
  for (const action of actions) {
    if (!actionIdForIr(action)) continue
    const canonicalType = canonicalActionIrType(action.type)
    // 别名形状（{type:'assert', assertion:{type:'assert_visible'|'assert_url'|…}}）也要映射到规范动作类型，
    // 之前只是把首字母大写拼前缀，会得到 assertAssert_url 这种不存在的类型。
    const actionType = canonicalType === 'assert'
      ? (ASSERTION_ACTION_TYPES[canonicalAssertionType(action.assertion?.type)] || '')
      : canonicalType
    if (!actionType) throw Object.assign(new Error(`Unsupported Action IR assertion type: ${String(action.assertion?.type || '').trim() || '(empty)'}`), { failureType: 'invalid_action_ir', category: 'invalid_action_ir' })
    let beforeResult = await captureActionObservation(context.observe)
    let resolvedTarget
    try {
      if (!beforeResult.ok) throw Object.assign(new Error('Unable to observe Action IR before state'), { failureType: 'observation_failed' })
      // 首屏之外的证据目标：探索阶段滚动过页面，动作阶段的观测不会——直接判 action_evidence_missing
      // 会把真实存在的元素报成「观测不到」（复核方 X5）。这里先做一次有界滚动扫描再接受观测结果；
      // 滚动后的观测同时用于 resolvedTarget、before 证据与 postcondition 比较（同一份数据）。
      const evidenceTargetId = String(actionTargetForContract(action)?.evidenceId || '').trim()
      if (evidenceTargetId && !observedElementFor(beforeResult.data, evidenceTargetId)) {
        const swept = await sweepForEvidenceTarget(page, context.observe, evidenceTargetId)
        if (swept) beforeResult = { ...beforeResult, data: swept }
      }
      const before = beforeResult.data
      resolvedTarget = await resolveObservedActionTarget(page, action, before, observedTargets)
      if (action.safety !== 'readonly') {
        throw Object.assign(new Error('Action IR discovery only permits readonly actions'), { failureType: 'unsafe_action' })
      }
      if (actionType === 'navigate' || actionType === 'goto') {
        // 必须走 resolveNavigationUrl：这里曾经直接 String(action.target?.value || action.target)，
        // 于是 route.value 为 '[object Object]'/'not a url' 时页面真的被导航到同源垃圾地址且记 passed。
        const url = resolveNavigationUrl(action, page.url())
        await context.assertAllowedUrl?.(url.href)
        await page.goto(url.href, { waitUntil: 'domcontentloaded', timeout: context.timeout })
      } else if (actionType === 'assertUrl') {
        const routeTarget = action.type === 'assert' ? (action.assertion?.target || action.assertion?.semanticTarget) : action.target
        const expectedCandidate = typeof routeTarget === 'string'
          ? routeTarget
          : (typeof routeTarget?.value === 'string' ? routeTarget.value : action.expected)
        // 期望地址同样要 fail-closed：缺目标或字符串化产物不能静默变成「断言当前地址」。
        const expectedUrl = resolveNavigationUrl({ target: expectedCandidate, id: actionIdForIr(action) }, page.url())
        if (!urlMatchesExpected(page.url(), expectedUrl.href)) throw Object.assign(new Error('Action IR URL assertion failed'), { failureType: 'assertion_failed' })
      } else if (actionType === 'click') {
        if (!resolvedTarget?.unique || !resolvedTarget.visible || !resolvedTarget.enabled || !resolvedTarget.receivesEvents) throw Object.assign(new Error('Action IR click target is not actionable'), { failureType: 'ambiguous_locator' })
        await resolveLocator(page, requireElementTarget(action, resolvedTarget)).click()
      } else if (actionType === 'assertVisible') {
        if (!resolvedTarget?.unique || !resolvedTarget.visible) throw Object.assign(new Error('Action IR assertion target is not visible'), { failureType: 'element_not_found' })
      } else if (actionType === 'assertHidden') {
        if (resolvedTarget?.visible) throw Object.assign(new Error('Action IR assertion target is visible'), { failureType: 'assertion_failed' })
      } else if (actionType === 'assertEnabled') {
        // 与 assertVisible 同口径：目标必须唯一且可见，再做可用性判断。
        if (!resolvedTarget?.unique || !resolvedTarget.visible) throw Object.assign(new Error('Action IR assertion target is not visible'), { failureType: 'element_not_found' })
        if (!(await resolveLocator(page, requireElementTarget(action, resolvedTarget)).isEnabled())) throw Object.assign(new Error('Action IR enabled assertion failed'), { failureType: 'assertion_failed' })
      } else if (actionType === 'assertDisabled') {
        if (!resolvedTarget?.unique || !resolvedTarget.visible) throw Object.assign(new Error('Action IR assertion target is not visible'), { failureType: 'element_not_found' })
        if (await resolveLocator(page, requireElementTarget(action, resolvedTarget)).isEnabled()) throw Object.assign(new Error('Action IR disabled assertion failed'), { failureType: 'assertion_failed' })
      } else if (actionType === 'assertText') {
        const actual = await resolveLocator(page, requireElementTarget(action, resolvedTarget)).innerText()
        if (!actual.includes(String(action.expected || action.assertion?.expected || ''))) throw Object.assign(new Error('Action IR text assertion failed'), { failureType: 'assertion_failed' })
      } else if (actionType === 'waitForVisible') {
        await resolveLocator(page, requireElementTarget(action, resolvedTarget)).waitFor({ state: 'visible', timeout: context.timeout })
      } else if (actionType === 'waitForUrl') {
        // 结构化取 value 并做形状校验：直接 String(action.target?.value || '') 既会把结构化 target 读成空串，
        // 也会让空期望值的 waitForUrl 立即“通过”（includes('') 恒真）。
        // 期望值必须与 navigate/assertUrl 同口径解析（站点入口 '/' + hash 路由基址 → 基址），
        // 否则原始 '/' 与 normalizeRoute 后的实际路由（'/home'）永远不相等，waitForUrl 必然超时（实测 2026-10-14）。
        const expectedUrl = resolveNavigationUrl(action, page.url()).href
        await waitForUrlMatch(page, expectedUrl, { timeout: context.timeout })
      } else if (actionType === 'fill' || actionType === 'input') {
        await resolveLocator(page, requireElementTarget(action, resolvedTarget)).fill(resolveActionValue(action, context))
      } else if (actionType === 'select') {
        await resolveLocator(page, requireElementTarget(action, resolvedTarget)).selectOption(resolveActionValue(action, context))
      } else if (actionType === 'hover') {
        await resolveLocator(page, requireElementTarget(action, resolvedTarget)).hover()
      } else if (actionType === 'paginate') {
        const beforeState = pageState(before)
        // 观测哨兵（与 DSL 侧同一个标记）：整页导航会丢掉它 ⇒ 被观测的列表已不在原文档。
        await armPaginationProbe(page)
        await resolveLocator(page, requireElementTarget(action, resolvedTarget)).click()
        // 归因完全交给节律判据（waitForObservedPageChange）：点击前 250ms 的两次采样挡不住
        // 慢刷新（复核方 X2），而「预采样发现自更新就只认指示器」又把真实异步翻页误杀（复核方 D1）。
        await waitForObservedPageChange(context.observe, beforeState, context.timeout, actionIdForIr(action), () => isPaginationProbeAlive(page))
      } else if (actionType === 'scrollIntoView') {
        await resolveLocator(page, requireElementTarget(action, resolvedTarget)).scrollIntoViewIfNeeded()
      } else if (actionType !== 'screenshot') {
        throw Object.assign(new Error(`Unsupported Action IR type: ${actionType}`), { failureType: 'unsafe_action' })
      }
      const afterResult = EXECUTION_WAITS_FOR_CHANGE.has(actionType)
        ? await waitForActionPostcondition(context.observe, before, context.timeout, true)
        : await waitForActionPostcondition(context.observe, before, context.timeout, false)
      if (!afterResult.ok) throw Object.assign(new Error('Unable to observe Action IR after state'), { failureType: 'observation_failed' })
      if (resolvedTarget?.evidenceId) observedTargets.set(resolvedTarget.evidenceId, String(resolvedTarget.pageKey || before?.pageKey || ''))
      recordPageSnapshot(before, actionIdForIr(action), 'before', observedElementFor(before, resolvedTarget?.evidenceId))
      recordPageSnapshot(afterResult.data, actionIdForIr(action), 'after', observedElementFor(afterResult.data, resolvedTarget?.evidenceId))
      evidence.push(buildActionEvidence(action, actionType, beforeResult, resolvedTarget, {
        status: 'passed',
        postcondition: afterResult.postcondition,
        // 只读发现阶段不做业务断言，因此「预期变化却没有变化」仍记 passed；
        // 但必须显式带上 warning，避免下游把它当成「交互被证实生效」。
        ...(afterResult.postcondition?.kind === 'no_page_change'
          ? { warning: 'no_page_change', warningMessage: '动作执行成功但页面未发生变化（pageFingerprint 未变）' }
          : {})
      }, afterResult, context))
    } catch (error) {
      const afterResult = await captureActionObservation(context.observe)
      recordPageSnapshot(beforeResult?.ok ? beforeResult.data : null, actionIdForIr(action), 'before')
      // 未分类错误必须有兜底分类：Playwright/TypeError 这类裸错误冒泡到 HTTP 层只剩 503「runner 不可用」，
      // 分类与已采集的步骤证据一起丢掉（复核方实测 7 种动作全部 503 且响应体只有 message）。
      const failureCategory = error?.failureType || error?.category || 'action_failed'
      if (error && typeof error === 'object') {
        if (!error.failureType) error.failureType = failureCategory
        if (!error.category) error.category = failureCategory
      }
      evidence.push(buildActionEvidence(action, actionType, beforeResult, resolvedTarget, { status: 'failed', failureType: failureCategory, failureCategory, message: String(error?.message || error) }, afterResult, context))
      throw error
    }
  }
}

const EXECUTION_WAITS_FOR_CHANGE = new Set(['click', 'navigate', 'goto', 'paginate'])

const waitForActionPostcondition = async (observe, before, timeout, expectChange) => {
  const startedAt = Date.now()
  const deadline = startedAt + Math.max(250, Math.min(2_000, Number(timeout) || 1_000))
  const beforeState = pageState(before)
  const minimumPolls = expectChange ? 2 : 3
  let previousState = null
  let polls = 0
  let last = null
  let changed = false
  while (Date.now() < deadline) {
    const result = await captureActionObservation(observe)
    if (!result.ok) {
      return { ...result, postcondition: { kind: 'observation_failed', polls, durationMs: Date.now() - startedAt, pageChanged: false } }
    }
    polls += 1
    last = result
    const state = pageState(result.data)
    changed = changed
      || state.pageKey !== beforeState.pageKey
      || state.pageFingerprint !== beforeState.pageFingerprint
      || state.contentSignature !== beforeState.contentSignature
    const stable = previousState
      && previousState.pageKey === state.pageKey
      && previousState.pageFingerprint === state.pageFingerprint
      && previousState.contentSignature === state.contentSignature
    if (stable && polls >= minimumPolls && (changed || !expectChange)) {
      return { ok: true, data: result.data, postcondition: { kind: 'settled', polls, durationMs: Date.now() - startedAt, pageChanged: changed } }
    }
    previousState = state
    await new Promise((resolve) => setTimeout(resolve, 50))
  }
  return {
    ok: true,
    data: last?.data || {},
    postcondition: { kind: expectChange && !changed ? 'no_page_change' : 'settle_timeout', polls, durationMs: Date.now() - startedAt, pageChanged: changed }
  }
}

const captureActionObservation = async (observe) => {
  try {
    const observed = await observe()
    if (observed?.observationStatus === 'failed' || observed?.failureCategory === 'observation_failed') {
      return { ok: false, failureCategory: 'observation_failed', data: observed }
    }
    return { ok: true, data: observed }
  } catch (error) {
    return { ok: false, failureCategory: 'observation_failed', error, data: {} }
  }
}

const resolveActionValue = (action = {}, runtime = {}) => {
  const value = action.value
  if (!value || typeof value !== 'object' || Array.isArray(value)) return String(value || '')
  const kind = String(value.kind || '').toLowerCase()
  const reference = value.key ?? value.value ?? ''
  // 与平台 ActionIrValidator.validateValue 的词表保持一致：平台放行的 kind 这里必须能解析，
  // 否则同一份计划「计划期通过、取证期 422」。literal/testdata 是内联值（编译器把它们都编成字面量），
  // runtimevariable/maskedsecret 需要运行时来源：请求里带了就用，没带就给出指名到变量的分类错误。
  if (kind === 'literal') return String(value.value ?? value.key ?? '')
  if (kind === 'testdata') {
    // testdata 只有 key（指向外部数据集）时不能把 key 当值填入：平台侧没有数据集来源通道，
    // 静默填入键名会让页面收到错误数据、步骤却记 passed（复核方实测页面回显 'deviceName'）。
    const inline = value.value
    if (inline === undefined || inline === null || String(inline) === '') {
      throw Object.assign(new Error(`Action IR testdata value has no inline value: ${String(value.key || '(empty)')}`), { failureType: 'invalid_action_ir', category: 'invalid_action_ir' })
    }
    return String(inline)
  }
  const variables = runtime.variables && typeof runtime.variables === 'object' ? runtime.variables : {}
  const maskedSecrets = runtime.maskedSecrets && typeof runtime.maskedSecrets === 'object' ? runtime.maskedSecrets : {}
  if (kind === 'runtimevariable' || kind === 'runtime_variable') {
    const key = String(reference || '').trim()
    if (key && Object.hasOwn(variables, key)) return String(variables[key] ?? '')
    if (key && Object.hasOwn(maskedSecrets, key)) return String(maskedSecrets[key] ?? '')
    throw Object.assign(new Error(`Action IR value source ${kind} has no runtime value in the discovery request: ${key || '(empty)'}`), { failureType: 'invalid_action_ir', category: 'invalid_action_ir' })
  }
  if (kind === 'maskedsecret') {
    const key = String(reference || '').trim()
    if (key && Object.hasOwn(maskedSecrets, key)) return String(maskedSecrets[key] ?? '')
    throw Object.assign(new Error(`Action IR masked secret is not provided by the discovery request: ${key || '(empty)'}`), { failureType: 'invalid_action_ir', category: 'invalid_action_ir' })
  }
  throw Object.assign(new Error(`Action IR value.kind is not supported: ${kind || '(empty)'}`), { failureType: 'invalid_action_ir', category: 'invalid_action_ir' })
}

// 元素型动作拿不到元素目标时必须 fail-closed：解析器对 route 目标返回 undefined，
// 直接解引用会在 7 处抛 TypeError（未分类 → HTTP 503，证据与分类一起丢）。
const requireElementTarget = (action, resolvedTarget) => {
  if (resolvedTarget?.locator) return resolvedTarget.locator
  throw Object.assign(new Error(`Action IR ${actionIdForIr(action)} requires an element target`), { failureType: 'invalid_action_ir', category: 'invalid_action_ir' })
}

// 归因（复核方 X2 / D1 / D3 + C1–C4，12 轮收敛）：与 DSL 侧同一个三段模型、同一个 tracker。
//   · 加载态响应（第一个样本或 1s 窗口内的加载态翻转）⇒ 地板 1s（数据晚到也照样归因）；
//   · 同步内容响应（第一个样本内容已变，≈点击同拍）⇒ 地板 2.5s（短于它的「临时数据→回滚」会被回退检查抓住，medium-1）；
//   · 无响应 / 只有异步晚到的内容变化 ⇒ 必须静默 11s，自刷新周期 ≤10.5s 永远攒不出来（high-1）；
//   · 内容回退到点击前 ⇒ 立即失败；
//   · 页码指示器**不参与归因**（按钮点击态/轮播/向导步骤都会命中通用指示器选择器）。
// 登录表单元素识别（复核方 C-4/C-5：证据页是登录壳时必须 fail-closed）。只看**恰好是登录字样**的控件，
// 业务页里的「退出登录 / 修改密码 / 保存」不命中（复核方 A2-7/A2-9/A2-11 仍必须 resolved）。
const LOGIN_CONTROL_TEXTS = new Set(['登录', '登陆', '登 录', '立即登录', '马上登录', 'login', 'log in', 'sign in', 'signin'])
const CREDENTIAL_NAME_PATTERN = /密码|password|用户名|账号|帐号|手机|邮箱|username|account|phone|email/i

const waitForObservedPageChange = async (observe, before, timeout, actionId = '', probeAlive = null) => {
  const clickAt = Date.now()
  const budget = Number(timeout) > 0 ? Number(timeout) : PAGINATION_SETTLE_CAP_MS
  const horizon = clickAt + paginationSettleHorizonMs(budget)
  const settle = paginationSettleTracker({ startedAt: clickAt })
  const viewOf = (state) => ({ ...(paginationPartsOf(state.contentSignature) || {}) })
  const beforeView = viewOf(before)
  let responseKind = ''
  let firstSampleSeen = false
  let tracked = null
  let changed = false
  // 观察何时结束（与 DSL 侧同一条规则，复核方 medium-2）：预算到点后，若最近仍有变化且归因还可能达成，
  // 继续观察到「该变化 + 所需静默」为止；没有任何变化则按预算结束。硬上限 PAGINATION_SETTLE_CAP_MS（30s）。
  const observationEnded = () => {
    if (Date.now() >= clickAt + PAGINATION_SETTLE_CAP_MS) return true
    if (Date.now() < horizon) return false
    return !(settle.count > 0 && Date.now() - settle.lastAt < settle.requirement(responseKind))
  }
  let lastResult = null
  let lastCurrent = before
  for (;;) {
    // 验收必须先于「观察结束」判定：settled 用 `>=`、结束判定用 `<`，两者在同一拍翻转，先判结束
    // 就等于永远走不到验收（复核方 medium-2 实测：无遮罩真实慢翻页跑到「数据到达 + 需静默」仍报 failed）。
    if (lastResult && !lastCurrent.paginationLoading && !lastCurrent.paginationPlaceholderOnly && settle.settled(Date.now(), responseKind)) return lastResult.data
    if (observationEnded()) break
    const result = await captureActionObservation(observe)
    if (!result.ok) throw Object.assign(new Error('Unable to observe pagination result'), { failureType: 'observation_failed', category: 'observation_failed' })
    const current = pageState(result.data)
    // 观测哨兵：整页导航后新文档里没有它 ⇒ 被观测的列表已不在原文档，不能把新文档算成翻页结果
    // （复核方 C-rest：点击后 400ms 跳 /blank，DSL 与 IR 必须同一分类、不能 fail-open）。
    if (probeAlive && !(await probeAlive())) {
      // 整页导航：新文档里出现了不同的列表内容 ⇒ 真实的服务端翻页（复核方 C-6），归因并返回；
      // 否则（跳走、刷新回原样、页面自己清了标记）⇒ 不把新文档当成翻页结果。
      const navigatedView = viewOf(current)
      if (Number(navigatedView?.count || 0) > 0 && !paginationContentUnchanged(beforeView, navigatedView)) {
        settle.record(Date.now())
        return result.data
      }
      throw paginationNavigatedAwayError(`Action IR ${actionId || '(unknown)'}`)
    }
    // 点击响应判定（与 DSL 侧同一个三段模型，复核方 12 轮收敛）：
    //   · 第一个观测样本 ≈「点击同拍」快照：加载态翻转 ⇒ loading；内容已变 ⇒ content；
    //   · 之后的 1s 窗口（含采样宽限）内出现加载态翻转 ⇒ 升级为 loading（复核方 low-1）；
    //   · 异步出现的纯内容变化与「相位对齐到点击的自刷新」不可区分 ⇒ 按无响应处理（11s 长静默，high-1）。
    const loadingFlipped = current.paginationLoading !== before.paginationLoading
    if (!firstSampleSeen) {
      firstSampleSeen = true
      if (loadingFlipped) responseKind = 'loading'
      else if (current.contentSignature !== before.contentSignature) responseKind = 'content'
    } else if (responseKind !== 'loading'
      && Date.now() - clickAt <= PAGINATION_CLICK_RESPONSE_MS + PAGINATION_CLICK_RESPONSE_SLACK_MS
      && loadingFlipped) {
      responseKind = 'loading'
    }
    // 加载中不判定变化（与 DSL 谓词的 `if (snapshot.loading) return false` 同口径，复核方 X3）；
    // 但容器里只剩占位文本时要记住「页面确实变过」—— 随后回到点击前的内容就是回滚（C3），
    // 与 DSL 侧同一条规则（否则 IR 侧的回滚会以超时而非「回退」失败）。
    if (current.paginationLoading) {
      if (current.paginationPlaceholderOnly) changed = true
      // 加载态里不得保留「可验收」的旧样本：否则「内容先变、遮罩随后常驻」的页面会在陈旧样本上被
      // 误验收成 resolved（复核方 C-1）。
      lastResult = null
      lastCurrent = current
      await new Promise((resolve) => setTimeout(resolve, 100))
      continue
    }
    const currentView = viewOf(current)
    // 只认**内容**变化：指示器（点击态/轮播/向导步骤，即使类名带 pagination）单独变化不是翻页证据（复核方 C4）。
    if (!paginationContentUnchanged(beforeView, currentView)) {
      const next = paginationTrackedChange(beforeView, currentView)
      if (next !== tracked) {
        tracked = next
        changed = true
        settle.record(Date.now())
      }
    } else if (changed) {
      // 变化过又回到点击前的状态（先出占位/临时数据再回滚）：不算翻页成功（复核方 C3）。
      throw Object.assign(new Error(`pagination_not_changed: Action IR ${actionId || '(unknown)'} 点击后列表变化又回退到点击前的状态，最终 DOM 与点击前相同`), { failureType: 'pagination_not_changed', category: 'pagination_not_changed' })
    }
    // 不得停在占位态上归因（复核方 C3：占位后回滚到原样 ⇒ 内容最终未变，必须失败）。
    // 本轮把验收挪到循环顶部（每轮先验收再判结束），这里只记录最新样本供顶部验收使用。
    lastResult = result
    lastCurrent = current
    await new Promise((resolve) => setTimeout(resolve, 50))
  }
  // 与 DSL 的 paginate 用同一个分类词（pagination_not_changed），避免同一语义两种分类。
  throw Object.assign(
    new Error(`pagination_not_changed: Action IR ${actionId || '(unknown)'} 点击后列表未变化或仍在自行变化（${settle.describe(responseKind)}）`),
    { failureType: 'pagination_not_changed', category: 'pagination_not_changed' }
  )
}

const buildActionEvidence = (action, actionType, beforeResult, resolvedTarget, execution, afterResult, context) => ({
  actionId: actionIdForIr(action),
  actionType,
  sourcePointId: context.sourcePointId || '',
  before: pageState(beforeResult?.data),
  resolvedTarget: resolvedTarget || {},
  ...(resolvedTarget?.evidenceId ? { evidenceId: resolvedTarget.evidenceId } : {}),
  execution,
  after: pageState(afterResult?.data),
  ...(beforeResult?.ok === false || afterResult?.ok === false
    ? { observationStatus: 'failed', observationFailureCategory: 'observation_failed' }
    : {})
})

const resolveObservedActionTarget = async (page, action, observation, observedTargets = new Map()) => {
  const target = actionTargetForContract(action)
  if (!target || target.kind === 'route') return undefined
  const elements = Array.isArray(observation?.elements) ? observation.elements : []
  const evidenceId = String(target.evidenceId || '').trim()
  const matches = elements.filter((element) => element.evidenceId === evidenceId)
  if (matches.length > 1) throw Object.assign(new Error('Action IR target is ambiguous in the observed page'), { failureType: 'ambiguous_locator' })
  const previousPageKey = observedTargets.get(evidenceId)
  if (previousPageKey && previousPageKey !== String(observation?.pageKey || '')) {
    // 同一 evidenceId 在同一页面内可以复用（例如先点击再断言同一元素）；
    // 跨页面复用仍然拒绝：证据必须绑定到它被观察到的那个页面。
    throw Object.assign(
      new Error(`Action IR target ${evidenceId} was observed on another page observation (${previousPageKey} → ${observation?.pageKey || ''})`),
      { failureType: 'action_evidence_mismatch' }
    )
  }
  const observed = matches[0]
  if (!observed) {
    const unsupportedFrames = (Array.isArray(observation?.unsupported?.frames) ? observation.unsupported.frames : [])
      .filter((frame) => frame.supported !== true)
    if (unsupportedFrames.length) {
      // 该分类是「可观测页面里找不到目标 + 页面存在不可观测子树」的推断，不是已证实的归属：
      // 目标也可能就是不在可观测页面里，因此附带 scopeUncertain 供调用方区分。
      throw Object.assign(
        new Error(`Action IR target ${evidenceId || '(unknown)'} was not observable in the current page observation; the page contains unsupported frames (${unsupportedFrames.map((frame) => frame.framePath).join(', ')}) — the target may live inside one of them, or be missing from the observable page`),
        { failureType: 'unsupported_frame', category: 'unsupported_frame', scopeUncertain: true, unsupportedScopes: unsupportedFrames.map((frame) => frame.framePath) }
      )
    }
    const closedShadowHosts = Array.isArray(observation?.unsupported?.closedShadowHosts) ? observation.unsupported.closedShadowHosts : []
    if (closedShadowHosts.length) {
      throw Object.assign(
        new Error(`Action IR target ${evidenceId || '(unknown)'} was not observable in the current page observation; the page contains closed shadow roots (${closedShadowHosts.map((host) => host.hostPath).join(', ')}) that cannot be inspected — the target may live inside one of them, or be missing from the observable page`),
        { failureType: 'unsupported_shadow_dom', category: 'unsupported_shadow_dom', scopeUncertain: true, unsupportedScopes: closedShadowHosts.map((host) => host.hostPath) }
      )
    }
    const unresolvedShadowHosts = Array.isArray(observation?.unsupported?.unresolvedShadowHosts) ? observation.unsupported.unresolvedShadowHosts : []
    if (unresolvedShadowHosts.length) {
      throw Object.assign(
        new Error(`Action IR target ${evidenceId || '(unknown)'} was not observable in the current page observation; the page contains shadow hosts whose scope cannot be addressed uniquely (${unresolvedShadowHosts.map((host) => host.tagName || 'host').join(', ')}) and their subtree was not collected — the target may live inside one of them, or be missing from the observable page`),
        { failureType: 'unsupported_shadow_dom', category: 'unsupported_shadow_dom', scopeUncertain: true, unsupportedScopes: unresolvedShadowHosts.map((host) => host.hostPath || host.tagName || 'shadow-host') }
      )
    }
    throw Object.assign(new Error(`Action IR target ${evidenceId || '(unknown)'} was not observed in the current page`), { failureType: 'action_evidence_missing', targetEvidenceId: evidenceId || '' })
  }
  const resolved = await resolveStepTargetEvidence(page, observed.locator)
  if (!resolved.unique) {
    // 「不再唯一」这个措辞在 count=0 时是误导的（例如采集器给出的 role 名在 Playwright 侧匹配不到）：
    // 诊断必须带上实际匹配数，否则排查只能看到一句笼统的 ambiguous_locator。
    // 同时点名目标 evidenceId 与 locator：否则去重多条动作时无法判断是哪一条失效（实测 2026-10-14）。
    throw Object.assign(new Error(`Observed Action IR target ${observed.evidenceId || evidenceId || '(unknown)'} ("${String(observed.locator || '').slice(0, 80)}") is not resolvable to exactly one element (matched ${resolved.matchedElementCount ?? 'unknown'})`), { failureType: 'ambiguous_locator', category: 'ambiguous_locator', matchedElementCount: resolved.matchedElementCount, targetEvidenceId: observed.evidenceId || evidenceId || '' })
  }
  return {
    ...resolved,
    role: resolved.role,
    evidenceId: observed.evidenceId,
    pageKey: observation.pageKey || '',
    pageFingerprint: observation.pageFingerprint || observation.fingerprint || '',
    ...(observed.locatorSpec ? { locatorSpec: observed.locatorSpec } : {}),
    framePath: observed.framePath || '',
    shadowPath: observed.shadowPath || '',
    observedElement: {
      evidenceId: observed.evidenceId,
      locator: observed.locator,
      role: observed.role,
      accessibleName: observed.accessibleName,
      pageScope: observed.pageScope,
      rowContext: observed.rowContext,
      ...(observed.locatorSpec ? { locatorSpec: observed.locatorSpec } : {})
    }
  }
}

// 变化检测除页面身份外还要看列表内容：pageFingerprint 的元素键不含文本，只换表格行数据的
// 真实翻页会被判成「未变化」（复核方实测 /paginate-live：before/after 指纹完全相同）。
const pageState = (observation = {}) => ({
  pageUrl: observation.url || '',
  pageKey: observation.pageKey || '',
  pageFingerprint: observation.pageFingerprint || observation.fingerprint || '',
  // 优先用观测里浏览器侧算好的共享签名（与 DSL paginate 同一份源码与投影），没有时退回元素文本签名。
  contentSignature: String(observation.listSignature || '').trim() || observationContentSignature(observation),
  paginationIndicator: String(observation.paginationIndicator || '').trim(),
  paginationLoading: observation.paginationLoading === true,
  paginationPlaceholderOnly: observation.paginationPlaceholderOnly === true
})

const observationContentSignature = (observation) => (Array.isArray(observation?.elements) ? observation.elements : [])
  .map((element) => `${element?.locator || element?.evidenceId || ''}::${String(element?.text || element?.accessibleName || '').trim()}`)
  .join('|')
  .slice(0, 4000)

const navigationTrail = async (page) => page.evaluate(() => Array.isArray(globalThis.__codexNavigationTrail) ? globalThis.__codexNavigationTrail : [])

const chooseSafeAction = async (page, observation, scores, intent, input) => {
  if (isCaptchaFailure(observation?.failureCategory) || isCaptchaFailure(observation?.readiness?.failureReason)) {
    return undefined
  }
  if (Array.isArray(input.actions) && input.actions.length) {
    // 只接旧式动作（没有 id 且没有结构化 target）：这类动作由探索轮次逐个安全执行。
    // Action IR 动作由 executeActionIr 负责，若在这里再返回一次会被第二个执行器重复执行，
    // 结构化 target 还会被当成 URL 字符串（曾把页面导航到 /[object Object]）。
    const legacy = input.actions.filter((action) => !(action?.id || action?.actionId) && !isStructuredActionTarget(actionTargetForContract(action)))
    if (legacy.length) return legacy[0]
    return undefined
  }
  const overlay = observation.overlays?.find((item) => item.blocking)
  if (overlay) return { type: 'dismiss-overlay', target: overlay.locator, overlayText: overlay.text }
  const failureCategory = String(observation.failureCategory || observation.readiness?.failureReason || observation.failureReason || '')
  const reauthCount = (input.rounds || []).filter((round) => normalizeRepairCategory(round.repairCategory || round.action?.repairCategory || round.action?.type) === 'reauthenticate').length
  const lastFailureCategory = String(input.lastFailureCategory || '')
  if (failureCategory === 'auth_context_lost' && shouldAttemptReauthenticate(reauthCount, input.budget, lastFailureCategory)) {
    return { type: 'reauthenticate', repairCategory: 'reauthenticate' }
  }
  if (failureCategory === 'auth_context_lost') return undefined
  if (failureCategory === 'login_failed') return undefined
  const suggestedCategory = normalizeAiRepairSuggestionCategory(input.repairSuggestion?.category)
  if (suggestedCategory && !(input.rounds || []).some((round) => round.repairCategory === suggestedCategory)) {
    // Suggestions that require a concrete Locator/URL are ignored because the AI
    // contract intentionally does not carry executable targets. Deterministic
    // observation below selects those targets from the current page.
    const suggestedType = {
      wait_ready: 'wait-ready',
      reauthenticate: shouldAttemptReauthenticate(reauthCount, input.budget, lastFailureCategory) ? 'reauthenticate' : undefined,
      reobserve: 'reobserve'
    }[suggestedCategory]
    if (suggestedType) return { type: suggestedType, repairCategory: suggestedCategory, reason: String(input.repairSuggestion.reason || '').slice(0, 160) }
  }
  if (failureCategory === 'network_blocked_critical' || (failureCategory === 'page_not_ready' && (scores.pageScore || 0) >= 20)) {
    const waited = (input.rounds || []).some((round) => normalizeRepairCategory(round.repairCategory || round.action?.repairCategory || round.action?.type) === 'wait_ready')
    if (!waited) return { type: 'wait-ready', repairCategory: 'wait_ready' }
    return { type: 'reobserve', repairCategory: 'reobserve' }
  }
  const hints = [...new Set([...(intent.pageAliases || []), ...(intent.routeHints || []), ...(input.navigationHints || [])])]
  if ((!scores.pageScore || scores.pageScore < 20) && hints.length) return { type: 'menu-search', target: hints[0] }
  if (!scores.relevantElements?.length) {
    if (observation.scrollContainers > 0) return { type: 'scroll-container' }
    if (observation.hasInactiveTab) {
      const terms = [...(intent.pageAliases || []), ...(intent.elementTerms || []), ...(intent.stateTerms || [])]
      const candidates = (observation.tabs || []).filter((tab) => !tab.selected && tab.enabled && !isForbiddenBusinessAction(`${tab.label || ''} ${tab.accessibleName || ''}`))
      const ranked = candidates.map((tab) => ({
        ...tab,
        score: terms.reduce((score, term) => score + (term && tab.label.toLowerCase().includes(term) ? 10 : 0), 0)
      })).sort((left, right) => right.score - left.score)
      const selected = ranked.find((tab) => tab.score > 0) || ranked[0]
      if (selected) return { type: 'switch-tab', target: selected.locator, label: selected.label }
    }
    const paginationCount = (input.rounds || []).filter((round) => round.action?.type === 'paginate').length
    if (input.allowPagination !== false && observation.hasNextPage && paginationCount < (input.budget?.maxPaginationSteps ?? 3)) return { type: 'paginate' }
    return { type: 'scroll-page' }
  }
  return undefined
}

// 证据页是否是「登录表单」（复核方 C-4/C-5 的判定核心，抽成纯函数以便锁测）：
// 需要**同时**出现一个凭据输入（password 类型，或名字像 用户名/密码/账号/手机/邮箱 的输入控件）
// 与一个「恰好是登录字样」的控件。业务页里的「退出登录 / 修改密码 / 保存 / 用户中心」不命中。
export const evidenceLooksLikeLoginShell = (elements) => {
  const list = Array.isArray(elements) ? elements : []
  const hasLoginControl = list.some((element) => LOGIN_CONTROL_TEXTS.has(String(element?.accessibleName || '').trim().toLowerCase()))
  if (!hasLoginControl) return false
  return list.some((element) => {
    const semantic = String(element?.semanticType || '')
    const tag = String(element?.tagName || '').toLowerCase()
    const isInput = semantic === 'field' || tag === 'input' || tag === 'textarea'
    if (!isInput) return false
    if (String(element?.type || '').toLowerCase() === 'password') return true
    return CREDENTIAL_NAME_PATTERN.test(String(element?.accessibleName || ''))
  })
}

export const __testables = Object.freeze({
  evidenceLooksLikeLoginShell,
  chooseSafeAction,
  validateActionIr,
  validateRequestedActions,
  isStructuredActionTarget,
  navigationTargetFromAction,
  resolveNavigationUrl,
  resolveLegacyNavigationTarget,
  isRouteShaped,
  canonicalAssertionType,
  isOptionalNotFoundResponse,
  normalizeHandoffPageUrlPath,
  formatSafeRequestDiagnostic,
  networkDiagnostic
})

const isCaptchaFailure = (value) => ['captcha_required', 'manual_required'].includes(String(value || ''))

const buildEvidenceFailure = (category, pageUrl, networkBlocked = [], repairTrail = [], handoff = null) => Object.assign(new Error(
  category === 'manual_required'
    ? '页面需要人工接管'
    : '检测到验证码，禁止自动绕过'
), {
  category,
  pageUrl,
  retryable: false,
  network: networkDiagnostic(networkBlocked),
  repairTrail,
  handoff: handoff || {
    required: true,
    mode: 'manual',
    reason: category,
    resumeSupported: false
  }
})

const detectCaptchaState = async (page) => page.evaluate(() => {
  const visible = (element) => {
    if (!element) return false
    const style = getComputedStyle(element)
    const rect = element.getBoundingClientRect()
    return style.display !== 'none'
      && style.visibility !== 'hidden'
      && Number(style.opacity) !== 0
      && rect.width > 0
      && rect.height > 0
  }
  const semanticText = (element) => String([
    element.getAttribute?.('aria-label'),
    element.getAttribute?.('title'),
    element.getAttribute?.('placeholder'),
    element.innerText,
    element.textContent
  ].filter(Boolean).join(' ')).trim()
  const captchaIframe = Array.from(document.querySelectorAll('iframe[src*="captcha" i]')).find(visible)
  const captchaMarked = Array.from(document.querySelectorAll('[id*="captcha" i],[class*="captcha" i]')).find(visible)
  if (captchaIframe || captchaMarked) return 'captcha_required'
  const captchaSemantic = Array.from(document.querySelectorAll('button,input,select,textarea,[role],label')).find((element) => {
    if (!visible(element)) return false
    return /captcha|验证码|安全验证|人机验证/i.test(semanticText(element))
  })
  return captchaSemantic ? 'manual_required' : ''
}).catch(() => '')

const performSafeAction = async (page, action, { baseUrl, routeHints = [], history = [], assertAllowedUrl, timeout, observedSafeTabLocators = new Set(), reauthenticate, updateReadiness }) => {
      const category = normalizeRepairCategory(action?.repairCategory || action?.category)
  const type = normalizeRepairActionType(action?.type)
    || normalizeRepairActionType(category)
    || String(action?.type || (category === 'wait_ready' ? SAFE_REPAIR_ACTIONS.WAIT_READY : category === 'dismiss_safe_overlay' ? SAFE_REPAIR_ACTIONS.DISMISS_OVERLAY : category === 'menu_search' ? SAFE_REPAIR_ACTIONS.MENU_SEARCH : category === 'same_origin_navigation' ? SAFE_REPAIR_ACTIONS.DIRECT_NAVIGATION : category === 'switch_readonly_tab' ? SAFE_REPAIR_ACTIONS.SWITCH_TAB : category === 'scroll_container' ? SAFE_REPAIR_ACTIONS.SCROLL_CONTAINER : category || ''))
  const allowed = new Set(Object.values(SAFE_REPAIR_ACTIONS))
  if (!allowed.has(type)) throw Object.assign(new Error(`unsafe action: ${type}`), { failureType: 'navigation_failed' })
  if (isForbiddenBusinessAction(action?.label || action?.text || action?.target || '') && type !== 'dismiss-overlay') {
    throw Object.assign(new Error('business action is forbidden'), { failureType: 'unsafe_overlay' })
  }
  if (type === 'dismiss-overlay') {
    const safe = page.getByRole('button', { name: /^(关闭|取消|稍后|知道了)$/ }).first()
    if (await safe.count()) { await safe.click(); await page.waitForTimeout(100); await updateReadiness?.(); return }
    await page.keyboard.press('Escape').catch(() => {})
    const stillOpen = await page.locator('[role="dialog"][aria-modal="true"], [aria-modal="true"]').filter({ visible: true }).count().catch(() => 0)
    if (stillOpen) throw Object.assign(new Error('unsafe overlay requires confirmation'), { failureType: 'unsafe_overlay' })
    await updateReadiness?.()
    return
  }
  if (type === 'wait-ready') {
    const readiness = typeof updateReadiness === 'function'
      ? await updateReadiness()
      : await waitForPageReadiness(page, Math.min(timeout || 5_000, 5_000))
    if (!readiness.ready) throw Object.assign(new Error(readiness.failureReason || 'page is not ready'), { failureType: readiness.failureReason || 'page_not_ready' })
    return
  }
  if (type === 'reobserve') { await page.waitForTimeout(100); return }
  if (type === 'reauthenticate') {
    if (typeof reauthenticate !== 'function') throw Object.assign(new Error('reauthentication is unavailable'), { failureType: 'auth_context_lost' })
    try {
      await reauthenticate()
    } catch (error) {
      throw Object.assign(error instanceof Error ? error : new Error(String(error)), {
        failureType: error?.failureType || error?.category || 'login_failed'
      })
    }
    return
  }
  if (type === 'menu-search') {
    const hints = [action.target, ...routeHints].filter(Boolean)
    const selected = await clickMatchingNavigation(page, hints)
    if (!selected) {
      const search = page.locator('input[placeholder*="搜索菜单"],input[aria-label*="搜索菜单"]').first()
      if (await search.count()) {
        await search.fill(String(action.target || hints[0] || ''))
        await page.waitForTimeout(200)
        if (!await clickMatchingNavigation(page, hints)) throw Object.assign(new Error('menu target not found'), { failureType: 'element_not_found' })
      } else throw Object.assign(new Error('menu search unavailable'), { failureType: 'element_not_found' })
    }
    await page.waitForTimeout(300); await updateReadiness?.(); return
  }
  if (type === 'scroll-page') { await page.evaluate(() => window.scrollBy(0, Math.max(300, innerHeight * 0.8))); return }
  if (type === 'scroll-container') { await page.evaluate(() => { const candidates = [...document.querySelectorAll('*')].filter((el) => el.scrollHeight > el.clientHeight + 20); candidates.sort((a, b) => b.scrollHeight - a.scrollHeight)[0]?.scrollBy(0, 400) }); return }
  if (type === 'switch-tab') {
    let tab
    if (!action.target) throw Object.assign(new Error('switch-tab target is empty or ambiguous'), { failureType: 'ambiguous_locator' })
    if (!observedSafeTabLocators.has(action.target)) throw Object.assign(new Error('switch-tab target was not produced by safe observation'), { failureType: 'unsafe_action' })
    tab = await resolveTabLocator(page, action.target)
    if (!tab || await tab.count() !== 1) throw Object.assign(new Error('switch-tab locator is missing or ambiguous'), { failureType: 'ambiguous_locator' })
    const tabDetails = await tab.evaluate((element) => ({
      role: element.getAttribute('role') || (element.matches('.ant-tabs-tab,.el-tabs__item') ? 'tab' : ''),
      visibleLabel: String(element.innerText || element.textContent || '').trim().replace(/\s+/g, ' '),
      accessibleLabel: String(element.getAttribute('aria-label') || element.getAttribute('title') || '').trim().replace(/\s+/g, ' '),
      enabled: !element.matches(':disabled,[aria-disabled="true"]'),
      selected: element.getAttribute('aria-selected') === 'true' || element.classList.contains('is-active')
    })).catch(() => undefined)
    if (!tabDetails || tabDetails.role !== 'tab') throw Object.assign(new Error('switch-tab target is not an observed tab'), { failureType: 'unsafe_action' })
    if (!tabDetails.enabled) throw Object.assign(new Error('switch-tab target is disabled'), { failureType: 'unsafe_action' })
    if (tabDetails.selected) throw Object.assign(new Error('switch-tab target is already selected'), { failureType: 'unsafe_action' })
    const tabLabel = tabDetails.visibleLabel || tabDetails.accessibleLabel
    if (isForbiddenBusinessAction(`${tabDetails.visibleLabel} ${tabDetails.accessibleLabel}`)) throw Object.assign(new Error('switch-tab target is a forbidden business action'), { failureType: 'unsafe_action' })
    const textTarget = String(action.target).match(/^text=(.*)$/s)
    const expectedText = textTarget?.[1]?.split('@@scope=', 1)[0]
    if (textTarget && tabLabel !== expectedText) throw Object.assign(new Error('switch-tab text target is ambiguous'), { failureType: 'ambiguous_locator' })
    await tab.click()
    await updateReadiness?.()
    return
  }
  if (type === 'paginate') { const next = page.getByRole('button', { name: /^(下一页|下一步|Next)$/i }).first(); if (await next.count() && await next.isEnabled()) await next.click(); await updateReadiness?.(); return }
  if (type === 'expand-navigation' || type === 'expand-section') { const expand = page.locator('[aria-expanded="false"]').filter({ visible: true }).first(); if (await expand.count()) await expand.click(); return }
  if (type === 'reload') { await page.reload({ waitUntil: 'domcontentloaded', timeout }); return }
  if (type === 'back') { await page.goBack({ waitUntil: 'domcontentloaded', timeout }).catch(() => {}); return }
  if (type === 'direct-navigation') {
    // 与 validateRequestedActions 同一道闸（同一个 helper）：非路由形状的裸标签必须映射到一条已知路由，
    // 并且**导航到那条路由**——曾经把标签百分号编码后真的导航出去（'detail page' → GET /detail%20page）。
    const target = resolveLegacyNavigationTarget(action, page.url(), { routeHints, history })
    const current = new URL(page.url())
    if (target.origin !== current.origin) throw Object.assign(new Error('direct navigation must be same-origin'), { failureType: 'navigation_failed' })
    await assertAllowedUrl?.(target.href); await page.goto(target.href, { waitUntil: 'domcontentloaded', timeout }); await updateReadiness?.()
  }
}

const resolveTabLocator = async (page, target) => {
  const value = String(target || '').trim()
  const scopedTextMatch = value.match(/^text=(.*)@@scope=(.+)$/s)
  if (scopedTextMatch) {
    const scope = resolveLocator(page, scopedTextMatch[2])
    return scope.locator('[role="tab"],.ant-tabs-tab,.el-tabs__item').filter({ hasText: scopedTextMatch[1] })
  }
  const textMatch = value.match(/^text=(.*)$/s)
  if (textMatch) return page.locator('[role="tab"],.ant-tabs-tab,.el-tabs__item').filter({ hasText: textMatch[1] })
  const roleMatch = value.match(/^role=tab\[name="((?:\\.|[^"\\])*)"(?:,\s*exact=(true|false))?\]$/i)
  if (!roleMatch) return resolveLocator(page, value)
  const name = roleMatch[1].replaceAll('\\"', '"').replaceAll('\\\\', '\\')
  // 不带后缀时沿用历史语义（精确匹配）；带后缀时必须按后缀执行，否则「唯一性声明」与执行不一致。
  const exact = roleMatch[2] === undefined ? true : roleMatch[2].toLowerCase() === 'true'
  return page.getByRole('tab', { name, exact })
}

const normalizeViewport = (value) => {
  const match = String(value || '').match(/(\d+)\s*[xX*×]\s*(\d+)/)
  return match ? { width: Number(match[1]), height: Number(match[2]) } : { width: 1440, height: 900 }
}
import fs from 'node:fs/promises'
import path from 'node:path'
import { randomUUID } from 'node:crypto'

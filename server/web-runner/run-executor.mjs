import {
  buildBrowserContextCookies,
  buildBrowserContextOptions,
  buildCaseTestSteps,
  buildLoginSetupSteps,
  buildLogoutTeardownSteps,
  executeStep as executeRunnerStep,
  formatStepFailureMessage
} from './runner-core.mjs'
import { normalizeBrowserId } from './browser-launcher.mjs'
import { RunStoppedError } from './run-job-manager.mjs'
import {
  acquireSessionLease,
  createSession,
  getSession,
  releaseSessionLease,
  touchSession
} from './session-manager.mjs'
import { clearPageDiagnostics } from './page-diagnostics.mjs'
import { observePageEvidence, resolveStepTargetEvidence } from './page-evidence.mjs'

export const executeRunJobByBrowserGroups = async (job, dependencies = {}) => {
  const payload = job.input()
  const runtime = payload.runtime || {}
  const runnableCases = expandRunCases(payload.cases || [], normalizeLoopCount(payload.loopCount))
  const groups = groupCasesByBrowser(runnableCases, payload, runtime, dependencies.defaultBrowserId)
  const totalSteps = groups.reduce((count, group) => (
    count
      + (groupRequiresLogin(group, runtime) ? buildLoginSetupSteps(runtime).length : 0)
      + group.cases.reduce((caseCount, item) => caseCount + buildCaseTestSteps(item, runtime).length, 0)
      + buildLogoutTeardownSteps(runtime).length
  ), 0)

  job.markRunning()
  job.setPlan({
    totalCases: runnableCases.length,
    totalSteps,
    selectedBrowser: groups.length === 1 ? groups[0].browserId : 'mixed'
  })

  const results = []
  try {
    for (const group of groups) {
      await job.beforeNextStep()
      const groupResults = await executeBrowserGroup(group, runtime, job, dependencies)
      for (const result of groupResults) {
        results.push(result)
      }
    }
    job.complete(buildSummary(results))
  } catch (error) {
    if (error instanceof RunStoppedError) {
      job.markStopped()
      return
    }
    job.fail(error)
  }
}

export const groupCasesByBrowser = (cases, payload = {}, runtime = {}, defaultBrowserId = 'chromium') => {
  const groups = new Map()
  for (const item of cases) {
    const browserId = normalizeBrowserId(
      payload.browserOverride || item.browser || runtime.browser || defaultBrowserId
    )
    if (!groups.has(browserId)) groups.set(browserId, { browserId, cases: [] })
    groups.get(browserId).cases.push(item)
  }
  return [...groups.values()]
}

const executeBrowserGroup = async (group, runtime, job, dependencies) => {
  const launchBrowser = dependencies.launchBrowser
  if (typeof launchBrowser !== 'function') throw new Error('Web run executor requires launchBrowser')

  let browser
  let context
  let page
  let setupFailure
  let session
  let sessionLease
  const results = []
  let cleanupDiagnostics
  const groupEvidence = {
    consoleErrors: [],
    pageErrors: [],
    failedRequests: [],
    job
  }

  try {
    session = runtime.sessionId
      ? getSession(runtime.sessionId, {
        projectId: runtime.projectId,
        environmentId: runtime.environmentId,
        ownerId: runtime.ownerId,
        sessionKey: runtime.sessionKey || runtime.baseUrl,
        browser: group.browserId
      })
      : undefined
    if (session) {
      sessionLease = acquireSessionLease(session.sessionId, {
        projectId: runtime.projectId,
        environmentId: runtime.environmentId,
        ownerId: runtime.ownerId,
        sessionKey: runtime.sessionKey || runtime.baseUrl,
        browser: group.browserId
      })
      if (!sessionLease.ok) throw new Error(`浏览器会话不可用：${sessionLease.reason}`)
      session = sessionLease.session
      context = session.context
      page = session.page
      touchSession(session.sessionId, {
        projectId: runtime.projectId,
        environmentId: runtime.environmentId,
        ownerId: runtime.ownerId,
        sessionKey: runtime.sessionKey || runtime.baseUrl,
        browser: group.browserId
      })
      job.updateSession?.(session.sessionId)
    } else {
      browser = await launchBrowser(group.browserId, {
        headless: runtime.headless ?? dependencies.browserHeadless,
        allowLocalNetworkAccess: dependencies.allowLocalNetworkAccess
      })
      context = await browser.newContext(buildBrowserContextOptions(runtime, group.cases[0]))
      const cookies = buildBrowserContextCookies(runtime)
      if (cookies.length) await context.addCookies(cookies)
      page = await context.newPage()
    }
    cleanupDiagnostics = dependencies.registerPageDiagnostics?.(page, groupEvidence)
    configurePageTimeouts(page, runtime)

    if (!session && groupRequiresLogin(group, runtime)) {
      for (const step of buildLoginSetupSteps(runtime)) {
        const outcome = await executeLoggedStep({
          page,
          step,
          runtime,
          job,
          dependencies,
          browserId: group.browserId,
          caseItem: lifecycleCase(group.browserId, 'setup'),
          failedRequests: groupEvidence.failedRequests
        })
        if (!outcome.ok) {
          setupFailure = outcome
          break
        }
      }
    }

    if (setupFailure) {
      for (const item of group.cases) {
        const result = createGroupErrorResult(item, group.browserId, setupFailure, groupEvidence)
        results.push(result)
        job.appendCaseResult(result)
      }
    } else if (!session && runtime.persistSession === true) {
      session = createSession({
        sessionKey: runtime.sessionKey || runtime.baseUrl,
        projectId: runtime.projectId,
        ownerId: runtime.ownerId,
        environmentId: runtime.environmentId,
        browser: group.browserId,
        browserInstance: browser,
        context,
        page,
        pageUrl: page.url()
      })
      job.updateSession?.(session.sessionId)
    }

    if (!setupFailure) {
      for (const item of group.cases) {
        await job.beforeNextStep()
        const result = await executeCaseInSession(item, group.browserId, page, runtime, job, dependencies, groupEvidence)
        results.push(result)
        job.appendCaseResult(result)
      }
    }
  } catch (error) {
    if (error instanceof RunStoppedError) throw error
    const message = error instanceof Error ? error.message : 'Web 浏览器会话初始化失败'
    job.appendDiagnostic(`${group.browserId} 浏览器会话失败：${message}`)
    if (results.length === 0) {
      for (const item of group.cases) {
        const result = createGroupErrorResult(item, group.browserId, {
          step: { id: 'runtime', name: '启动浏览器会话' },
          error,
          message
        }, groupEvidence)
        results.push(result)
        job.appendCaseResult(result)
      }
    }
  } finally {
    if (page) clearPageDiagnostics(page)
    if (page && !session) {
      await executeTeardown(page, group, runtime, job, dependencies)
    }
    cleanupDiagnostics?.()
    if (sessionLease?.ok) {
      releaseSessionLease(sessionLease.session.sessionId, sessionLease.operationId)
    }
    if (!session) {
      await page?.close?.().catch(() => {})
      await context?.close?.().catch(() => {})
      await browser?.close?.().catch(() => {})
    }
  }

  return results
}

const executeCaseInSession = async (item, browserId, page, runtime, job, dependencies, groupEvidence) => {
  const startedAtMs = Date.now()
  const stepLogs = []
  const stepEvidence = []
  const evidenceStart = {
    consoleErrors: groupEvidence.consoleErrors.length,
    pageErrors: groupEvidence.pageErrors.length,
    failedRequests: groupEvidence.failedRequests.length
  }
  let status = 'passed'
  let errorMessage
  let failedStepId
  let pageHtmlSnippet
  let screenshotPath

  for (const step of buildExecutableCaseSteps(item, runtime)) {
    const outcome = await executeLoggedStep({
      page,
      step,
      runtime,
      job,
      dependencies,
      browserId,
      caseItem: item,
      failedRequests: groupEvidence.failedRequests
    })
    stepLogs.push(outcome.log)
    stepEvidence.push(outcome.evidence)
    if (!outcome.ok) {
      status = 'failed'
      errorMessage = outcome.message
      failedStepId = step.id
      pageHtmlSnippet = await page.content?.().then((html) => html.slice(0, 2000)).catch(() => undefined)
      screenshotPath = await dependencies.captureFailureScreenshot?.(page, item.id)
      if (screenshotPath) {
        job.updateScreenshot({ url: screenshotPath, capturedAt: new Date().toISOString() })
      }
      break
    }
  }

  return {
    caseId: item.id,
    sourceCaseId: item.sourceCaseId,
    name: item.name,
    module: item.module,
    pageName: item.pageName,
    startUrl: item.startUrl,
    browser: browserId,
    status,
    durationMs: Date.now() - startedAtMs,
    errorMessage,
    failedStepId,
    pageHtmlSnippet,
    screenshotPath,
    stepLogs,
    stepEvidence,
    consoleErrors: groupEvidence.consoleErrors.slice(evidenceStart.consoleErrors),
    pageErrors: groupEvidence.pageErrors.slice(evidenceStart.pageErrors),
    failedRequests: groupEvidence.failedRequests.slice(evidenceStart.failedRequests)
  }
}

const executeTeardown = async (page, group, runtime, job, dependencies) => {
  for (const step of buildLogoutTeardownSteps(runtime)) {
    const outcome = await executeLoggedStep({
      page,
      step,
      runtime,
      job,
      dependencies,
      browserId: group.browserId,
      caseItem: lifecycleCase(group.browserId, 'teardown'),
      ignoreRunControl: true
    })
    if (!outcome.ok) {
      job.appendDiagnostic(`退出登录后置条件失败：${step.name} - ${outcome.message}`)
    }
  }
}

const executeLoggedStep = async ({
  page,
  step,
  runtime,
  job,
  dependencies,
  browserId,
  caseItem,
  failedRequests = [],
  ignoreRunControl = false
}) => {
  if (!ignoreRunControl) await job.beforeNextStep()
  job.updateCurrent({
    caseId: caseItem.id,
    caseName: caseItem.name,
    stepId: step.id,
    stepName: step.name,
    action: step.action,
    phase: step.phase || 'test',
    pageUrl: page.url()
  })
  const stepStart = Date.now()
  const startedAt = new Date().toISOString()
  const observe = dependencies.observePageEvidence || observePageEvidence
  const resolveTarget = dependencies.resolveStepTargetEvidence || resolveStepTargetEvidence
  const beforeObservation = await captureObservation(observe, page)
  const resolvedTarget = beforeObservation.status === 'failed'
    ? undefined
    : isLocatorBackedStep(step)
      ? await resolveTarget(page, step.target).catch(() => undefined)
      : resolveNonLocatorTarget(step)
  try {
    await (dependencies.executeStep || executeRunnerStep)(page, step, runtime)
    const log = buildStepLog(step, page, stepStart, startedAt, 'passed')
    const afterObservation = await captureObservation(observe, page)
    const evidence = buildStepEvidence(step, beforeObservation, afterObservation, resolvedTarget, 'passed', Date.now() - stepStart)
    job.appendStepLog(log)
    job.updatePageUrl(page.url())
    await dependencies.captureLiveScreenshot?.(page, job, browserId)
    return { ok: true, step, log, evidence }
  } catch (error) {
    const message = formatStepFailureMessage(step, error, failedRequests, runtime)
    const log = buildStepLog(step, page, stepStart, startedAt, 'failed', message, error)
    const afterObservation = await captureObservation(observe, page)
    const evidence = buildStepEvidence(step, beforeObservation, afterObservation, resolvedTarget, 'failed', Date.now() - stepStart)
    job.appendStepLog(log)
    return { ok: false, step, log, error, message, evidence }
  }
}

const buildStepLog = (step, page, stepStart, startedAt, status, errorMessage, error) => ({
  stepId: step.id,
  phase: step.phase || 'test',
  name: step.name,
  action: step.action,
  target: step.target,
  status,
  durationMs: Date.now() - stepStart,
  startedAt,
  endedAt: new Date().toISOString(),
  pageUrl: page.url(),
  errorMessage,
  errorStack: error instanceof Error ? error.stack : undefined
})

const captureObservation = async (observe, page) => {
  try {
    return { status: 'passed', data: await observe(page) }
  } catch (error) {
    return {
      status: 'failed',
      failureType: safeObservationFailureType(error?.failureType),
      data: {}
    }
  }
}

const safeObservationFailureType = (value) => {
  const failureType = String(value || '')
  return /^[a-zA-Z0-9_-]{1,64}$/.test(failureType) ? failureType : 'observation_failed'
}

const buildStepEvidence = (step, beforeObservation, afterObservation, resolvedTarget, status, durationMs) => ({
  actionId: step.actionId || step.id || '',
  actionType: step.action,
  before: {
    pageUrl: beforeObservation.data?.url || '',
    pageKey: beforeObservation.data?.pageKey || '',
    pageFingerprint: beforeObservation.data?.pageFingerprint || beforeObservation.data?.fingerprint || ''
  },
  resolvedTarget: beforeObservation.status === 'failed' || afterObservation.status === 'failed'
    ? observationFailureTarget(beforeObservation, afterObservation)
    : normalizeResolvedTarget(resolvedTarget || resolveNonLocatorTarget(step) || unresolvedTarget(step.target)),
  evidenceId: step.evidenceId || '',
  execution: { status, durationMs },
  after: {
    pageUrl: afterObservation.data?.url || '',
    pageKey: afterObservation.data?.pageKey || '',
    pageFingerprint: afterObservation.data?.pageFingerprint || afterObservation.data?.fingerprint || ''
  },
  ...(beforeObservation.status === 'failed' || afterObservation.status === 'failed'
    ? {
        observationStatus: 'failed',
        failureType: beforeObservation.failureType || afterObservation.failureType || 'observation_failed'
      }
    : {})
})

const observationFailureTarget = (beforeObservation, afterObservation) => ({
  observationStatus: 'failed',
  failureType: beforeObservation.failureType || afterObservation.failureType || 'observation_failed'
})

const resolveNonLocatorTarget = (step = {}) => {
  if (step.action === 'goto' || step.action === 'waitForUrl' || step.action === 'assertUrl') {
    return withTargetState({ kind: 'route', value: step.target || '' })
  }
  if (step.action === 'manual') return withTargetState({ kind: 'manual', value: step.target || '' })
  return undefined
}

const withTargetState = (target = {}) => ({
  ...target,
  visible: target.visible === true,
  enabled: target.enabled === true,
  receivesEvents: target.receivesEvents === true,
  unique: target.unique === true
})

const unresolvedTarget = (target) => withTargetState({
  strategy: 'unknown',
  selector: String(target || '').trim()
})

const normalizeResolvedTarget = (target) => {
  if (typeof target === 'string') return unresolvedTarget(target)
  if (!target || typeof target !== 'object' || Array.isArray(target)) return unresolvedTarget('')
  return {
    ...target,
    visible: target.visible === true,
    enabled: target.enabled === true,
    receivesEvents: target.receivesEvents === true,
    unique: target.unique === true
  }
}

const isLocatorBackedStep = (step = {}) => ![
  'goto',
  'waitForUrl',
  'assertUrl',
  'manual'
].includes(step.action)

const buildExecutableCaseSteps = (item, runtime) => {
  const originalSteps = Array.isArray(item.steps) ? item.steps : []
  const actionSteps = Array.isArray(item.actions)
    ? item.actions
    : Array.isArray(item.agentActions)
      ? item.agentActions
      : Array.isArray(item.metadata?.agentActions)
        ? item.metadata.agentActions
        : []
  const actionById = new Map(actionSteps
    .map((action) => [String(action?.id || action?.actionId || '').trim(), action])
    .filter(([id]) => id))

  return buildCaseTestSteps(item, runtime).map((step) => {
    const originalIndex = originalSteps.findIndex((candidate) => candidate?.id === step.id)
    const action = actionById.get(String(step.id || '').trim()) || (originalIndex >= 0 ? actionSteps[originalIndex] : undefined)
    const actionId = step.actionId || action?.actionId || action?.id
    const evidenceId = step.evidenceId || action?.evidenceId || action?.target?.evidenceId || action?.assertion?.target?.evidenceId
    return {
      ...step,
      ...(actionId ? { actionId: String(actionId) } : {}),
      ...(evidenceId ? { evidenceId: String(evidenceId) } : {})
    }
  })
}

const createGroupErrorResult = (item, browserId, failure, evidence) => ({
  caseId: item.id,
  sourceCaseId: item.sourceCaseId,
  name: item.name,
  module: item.module,
  pageName: item.pageName,
  startUrl: item.startUrl,
  browser: browserId,
  status: 'error',
  durationMs: 0,
  errorMessage: failure.message || (failure.error instanceof Error ? failure.error.message : '浏览器环境准备失败'),
  failedStepId: failure.step?.id || 'runtime',
  stepLogs: [],
  consoleErrors: [...evidence.consoleErrors],
  pageErrors: [...evidence.pageErrors],
  failedRequests: [...evidence.failedRequests]
})

const groupRequiresLogin = (group, runtime) =>
  Boolean(runtime.login?.enabled) && group.cases.some((item) => item.authenticationMode !== 'skip')

const lifecycleCase = (browserId, phase) => ({
  id: `${phase}:${browserId}`,
  name: phase === 'setup' ? `${browserId} 环境准备` : `${browserId} 环境清理`
})

const configurePageTimeouts = (page, runtime) => {
  if (Number.isFinite(runtime.defaultTimeoutMs)) {
    page.setDefaultTimeout(Math.max(1000, Number(runtime.defaultTimeoutMs)))
  }
  if (Number.isFinite(runtime.navigationTimeoutMs)) {
    page.setDefaultNavigationTimeout(Math.max(1000, Number(runtime.navigationTimeoutMs)))
  }
}

const buildSummary = (results) => ({
  total: results.length,
  passed: results.filter((item) => item.status === 'passed').length,
  failed: results.filter((item) => item.status === 'failed').length,
  error: results.filter((item) => item.status === 'error').length
})

const normalizeLoopCount = (value) => {
  const count = Math.floor(Number(value || 1))
  if (!Number.isFinite(count)) return 1
  return Math.max(1, Math.min(100, count))
}

const expandRunCases = (cases, loopCount) => {
  if (loopCount <= 1) return cases
  return Array.from({ length: loopCount }).flatMap((_, loopIndex) =>
    cases.map((item) => ({ ...item, id: `${item.id}#${loopIndex + 1}` }))
  )
}

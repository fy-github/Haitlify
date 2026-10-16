import crypto from 'node:crypto'

const TERMINAL_STATUSES = new Set(['stopped', 'completed', 'failed'])

export class RunStoppedError extends Error {
  constructor(message = 'Web automation run stopped') {
    super(message)
    this.name = 'RunStoppedError'
  }
}

export const createRunJobManager = (options = {}) => {
  const jobs = new Map()
  const now = options.now || Date.now
  const idFactory = options.idFactory || (() => crypto.randomUUID())
  const retentionMs = positiveNumber(options.retentionMs, 60 * 60 * 1000)
  const maxTerminalJobs = positiveNumber(options.maxTerminalJobs, 50)

  const cleanup = () => {
    const currentTime = now()
    const terminalJobs = [...jobs.values()]
      .filter((job) => job.isTerminal())
      .sort((left, right) => left.endedAtMs() - right.endedAtMs())

    for (const job of terminalJobs) {
      if (currentTime - job.endedAtMs() >= retentionMs) {
        jobs.delete(job.id)
      }
    }

    const remainingTerminalJobs = [...jobs.values()]
      .filter((job) => job.isTerminal())
      .sort((left, right) => left.endedAtMs() - right.endedAtMs())
    while (remainingTerminalJobs.length > maxTerminalJobs) {
      const oldest = remainingTerminalJobs.shift()
      jobs.delete(oldest.id)
    }
  }

  return {
    create(payload = {}) {
      cleanup()
      const id = idFactory()
      const job = createRunJob(id, payload, now)
      jobs.set(id, job)
      return job
    },
    get(id) {
      return jobs.get(id)
    },
    cleanup
  }
}

const createRunJob = (id, payload, now) => {
  const createdAtMs = now()
  const secrets = collectSecrets(payload)
  let status = 'queued'
  let startedAtMs
  let endedAtMs
  let updatedAtMs = createdAtMs
  let selectedBrowser = ''
  let current = {}
  let latestScreenshot = {}
  let sessionId = ''
  let summary = { total: 0, passed: 0, failed: 0, error: 0 }
  let totalCases = Array.isArray(payload.cases) ? payload.cases.length : 0
  let totalSteps = (payload.cases || []).reduce((count, item) => count + (item.steps?.length || 0), 0)
  const stepLogs = []
  const results = []
  const diagnostics = []
  let pauseWaiter

  const touch = () => {
    updatedAtMs = now()
  }

  const markStopped = () => {
    status = 'stopped'
    endedAtMs = now()
    touch()
  }

  const rejectPauseWaiter = (error) => {
    const waiter = pauseWaiter
    pauseWaiter = undefined
    waiter?.reject(error)
  }

  const job = {
    id,
    input: () => payload,
    isTerminal: () => TERMINAL_STATUSES.has(status),
    endedAtMs: () => endedAtMs || Number.POSITIVE_INFINITY,
    markRunning() {
      if (status !== 'queued') return
      status = 'running'
      startedAtMs = now()
      touch()
    },
    setPlan(plan = {}) {
      totalCases = nonNegativeNumber(plan.totalCases, totalCases)
      totalSteps = nonNegativeNumber(plan.totalSteps, totalSteps)
      selectedBrowser = String(plan.selectedBrowser || selectedBrowser)
      touch()
    },
    updateCurrent(next = {}) {
      current = {
        caseId: next.caseId,
        caseName: next.caseName,
        stepId: next.stepId,
        stepName: next.stepName,
        action: next.action,
        phase: next.phase,
        pageUrl: next.pageUrl
      }
      touch()
    },
    updatePageUrl(pageUrl) {
      current = { ...current, pageUrl }
      touch()
    },
    appendStepLog(log = {}) {
      stepLogs.push(sanitizeStepLog(log, secrets))
      touch()
    },
    appendCaseResult(result = {}) {
      results.push(sanitizeEvidence(result, secrets))
      touch()
    },
    appendDiagnostic(message) {
      diagnostics.push(redactText(message, secrets))
      touch()
    },
    updateScreenshot(next = {}) {
      latestScreenshot = {
        url: String(next.url || ''),
        capturedAt: String(next.capturedAt || '')
      }
      touch()
    },
    updateSession(nextSessionId) {
      sessionId = String(nextSessionId || '')
      touch()
    },
    requestPause() {
      if (status === 'running') {
        status = 'pause_requested'
        touch()
      }
      return job.snapshot()
    },
    resume() {
      if (status === 'pause_requested') {
        status = 'running'
        touch()
      } else if (status === 'paused') {
        status = 'running'
        touch()
        const waiter = pauseWaiter
        pauseWaiter = undefined
        waiter?.resolve()
      }
      return job.snapshot()
    },
    requestStop() {
      if (TERMINAL_STATUSES.has(status)) return job.snapshot()
      if (status === 'paused') {
        markStopped()
        rejectPauseWaiter(new RunStoppedError())
      } else {
        status = 'stop_requested'
        touch()
      }
      return job.snapshot()
    },
    async beforeNextStep() {
      if (status === 'stop_requested') {
        markStopped()
        throw new RunStoppedError()
      }
      if (status === 'stopped') {
        throw new RunStoppedError()
      }
      if (status !== 'pause_requested') return

      status = 'paused'
      touch()
      await new Promise((resolve, reject) => {
        pauseWaiter = { resolve, reject }
      })
      if (status === 'stopped' || status === 'stop_requested') {
        if (status === 'stop_requested') markStopped()
        throw new RunStoppedError()
      }
    },
    markStopped,
    complete(nextSummary = summary) {
      if (status === 'stopped') return
      status = 'completed'
      summary = normalizeSummary(nextSummary)
      endedAtMs = now()
      current = {}
      touch()
    },
    fail(error) {
      if (status === 'stopped') return
      status = 'failed'
      endedAtMs = now()
      diagnostics.push(redactText(error instanceof Error ? error.message : error, secrets))
      touch()
    },
    snapshot() {
      return {
        runId: id,
        status,
        mode: String(payload.mode || 'sequential'),
        selectedBrowser,
        createdAt: toIso(createdAtMs),
        startedAt: toIso(startedAtMs),
        updatedAt: toIso(updatedAtMs),
        endedAt: toIso(endedAtMs),
        progress: {
          totalCases,
          completedCases: results.length,
          totalSteps,
          completedSteps: stepLogs.length
        },
        currentCase: current.caseId ? {
          id: current.caseId,
          name: redactText(current.caseName, secrets)
        } : undefined,
        currentStep: current.stepId ? {
          id: current.stepId,
          name: redactText(current.stepName, secrets),
          action: current.action,
          phase: current.phase
        } : undefined,
        currentPageUrl: redactText(current.pageUrl, secrets),
      latestScreenshotUrl: latestScreenshot.url,
      latestScreenshotAt: latestScreenshot.capturedAt,
      sessionId: sessionId || undefined,
        controls: controlsFor(status),
        summary,
        stepLogs: stepLogs.map((item) => ({ ...item })),
        results: results.map((item) => structuredClone(item)),
        diagnostics: [...diagnostics]
      }
    }
  }
  return job
}

const controlsFor = (status) => ({
  canPause: status === 'running',
  canResume: status === 'paused',
  canStop: ['queued', 'running', 'pause_requested', 'paused'].includes(status)
})

const sanitizeStepLog = (log, secrets) => ({
  stepId: log.stepId,
  phase: log.phase,
  name: redactText(log.name, secrets),
  action: log.action,
  target: redactText(log.target, secrets),
  status: log.status,
  durationMs: log.durationMs,
  startedAt: log.startedAt,
  endedAt: log.endedAt,
  pageUrl: redactText(log.pageUrl, secrets),
  errorMessage: redactText(log.errorMessage, secrets),
  errorStack: redactText(log.errorStack, secrets)
})

const blockedEvidenceKeys = /^(value|password|username|authToken|extraHeaders|variables|runtime|cookies?)$/i

// 路由目标的 value 是地址（与 pageKey/URL 同级可见），不是输入值：整表按 key 屏蔽会把
// stepEvidence.resolvedTarget.value 一起剔掉（复核方实测只剩 {kind:'route',visible:…}），证据链断裂。
const isRouteTargetObject = (node) => Boolean(node)
  && typeof node === 'object'
  && !Array.isArray(node)
  && String(node.kind || '').toLowerCase() === 'route'
  && typeof node.value === 'string'

const sanitizeEvidence = (value, secrets) => {
  if (Array.isArray(value)) return value.map((item) => sanitizeEvidence(item, secrets))
  if (!value || typeof value !== 'object') return typeof value === 'string' ? redactText(value, secrets) : value
  const routeTarget = isRouteTargetObject(value)
  return Object.fromEntries(
    Object.entries(value)
      .filter(([key]) => (key === 'value' ? routeTarget : !blockedEvidenceKeys.test(key)))
      .map(([key, item]) => [key, sanitizeEvidence(item, secrets)])
  )
}

// 步骤值可能含密码，但单字符/极短值（'x'、'1'）当 secret 会把报文里所有同名子串替换掉，
// 失败诊断直接不可读（实测 'e[REDACTED]ceeded'、'#does-not-e[REDACTED]ist'）。
const MIN_REDACT_LENGTH = 4

const collectSecrets = (payload = {}) => {
  const runtime = payload.runtime || {}
  const values = [
    runtime.authToken,
    ...Object.values(runtime.extraHeaders || {}),
    ...Object.values(runtime.variables || {}),
    runtime.login?.username,
    runtime.login?.password,
    ...(payload.cases || []).flatMap((item) => (item.steps || []).map((step) => step.value))
  ]
  return [...new Set(values.map((value) => String(value || '')).filter((value) => value.length >= MIN_REDACT_LENGTH))]
    .sort((left, right) => right.length - left.length)
}

const redactText = (value, secrets) => {
  if (value === undefined || value === null) return value
  let text = String(value)
  for (const secret of secrets) {
    if (!secret || secret.length < MIN_REDACT_LENGTH) continue
    text = text.split(secret).join('[REDACTED]')
  }
  return text
}

const normalizeSummary = (summary = {}) => ({
  total: nonNegativeNumber(summary.total, 0),
  passed: nonNegativeNumber(summary.passed, 0),
  failed: nonNegativeNumber(summary.failed, 0),
  error: nonNegativeNumber(summary.error, 0)
})

const positiveNumber = (value, fallback) => {
  const number = Number(value)
  return Number.isFinite(number) && number > 0 ? number : fallback
}

const nonNegativeNumber = (value, fallback) => {
  const number = Number(value)
  return Number.isFinite(number) && number >= 0 ? number : fallback
}

const toIso = (value) => Number.isFinite(value) ? new Date(value).toISOString() : undefined

export const __testables = Object.freeze({ sanitizeEvidence, collectSecrets, redactText })

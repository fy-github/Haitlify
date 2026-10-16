import test from 'node:test'
import assert from 'node:assert/strict'
import { executeRunJobByBrowserGroups } from './run-executor.mjs'
import { RunStoppedError } from './run-job-manager.mjs'
import { clearSessions, getSession } from './session-manager.mjs'

const runtime = {
  baseUrl: 'https://example.com',
  browser: 'follow-case',
  headless: true,
  viewport: '1280x720',
  defaultTimeoutMs: 5000,
  navigationTimeoutMs: 5000,
  login: {
    enabled: true,
    loginUrl: '/login',
    usernameLocator: 'testid=username',
    passwordLocator: 'testid=password',
    submitLocator: 'testid=login-submit',
    username: 'admin',
    password: 'secret',
    successType: 'url',
    successTarget: '/users'
  },
  logout: {
    enabled: true,
    steps: [
      { id: 'logout-click', name: '退出登录', action: 'click', target: 'testid=logout' },
      { id: 'logout-success', name: '确认退出', action: 'waitForUrl', target: '/login' }
    ]
  }
}

const makeCase = (id, browser = 'chromium') => ({
  id,
  sourceCaseId: id,
  name: `用例 ${id}`,
  module: '用户管理',
  pageName: '用户列表',
  startUrl: '/users',
  browser,
  authenticationMode: 'environment',
  steps: [
    { id: `${id}-assert`, name: `执行 ${id}`, action: 'assertVisible', target: `testid=${id}` }
  ]
})

const createJob = (payload) => {
  const state = {
    plan: undefined,
    logs: [],
    results: [],
    diagnostics: [],
    summary: undefined,
    status: 'queued'
  }
  return {
    state,
    input: () => payload,
    markRunning: () => { state.status = 'running' },
    setPlan: (plan) => { state.plan = plan },
    beforeNextStep: async () => {},
    updateCurrent: () => {},
    updatePageUrl: () => {},
    updateScreenshot: () => {},
    updateSession: (sessionId) => { state.sessionId = sessionId },
    appendStepLog: (log) => state.logs.push(log),
    appendCaseResult: (result) => state.results.push(result),
    appendDiagnostic: (message) => state.diagnostics.push(message),
    complete: (summary) => {
      state.status = 'completed'
      state.summary = summary
    },
    markStopped: () => { state.status = 'stopped' },
    fail: (error) => {
      state.status = 'failed'
      state.diagnostics.push(error instanceof Error ? error.message : String(error))
    }
  }
}

const createDependencies = ({ failingStepIds = [] } = {}) => {
  const state = {
    launches: [],
    contextCloses: 0,
    browserCloses: 0,
    pageCloses: 0,
    executedStepIds: []
  }
  const pages = new Map()
  return {
    state,
    dependencies: {
      defaultBrowserId: 'chromium',
      browserHeadless: true,
      allowLocalNetworkAccess: false,
      launchBrowser: async (browserId) => {
        state.launches.push(browserId)
        const page = {
          currentUrl: 'about:blank',
          url() { return this.currentUrl },
          setDefaultTimeout() {},
          setDefaultNavigationTimeout() {},
          close: async () => { state.pageCloses += 1 }
        }
        pages.set(browserId, page)
        return {
          newContext: async () => ({
            addCookies: async () => {},
            newPage: async () => page,
            close: async () => { state.contextCloses += 1 }
          }),
          close: async () => { state.browserCloses += 1 }
        }
      },
      executeStep: async (page, step) => {
        state.executedStepIds.push(step.id)
        if (step.action === 'goto' || step.action === 'waitForUrl') page.currentUrl = String(step.target)
        if (failingStepIds.includes(step.id)) throw new Error(`failed ${step.id}`)
      },
      registerPageDiagnostics: () => {},
      captureLiveScreenshot: async () => {},
      captureFailureScreenshot: async () => 'http://runner/failure.png'
    }
  }
}

test('one browser group logs in once, reuses one session for all cases, and logs out once', async () => {
  const payload = { mode: 'sequential', runtime, cases: [makeCase('case-1'), makeCase('case-2')] }
  const job = createJob(payload)
  const { state, dependencies } = createDependencies()

  await executeRunJobByBrowserGroups(job, dependencies)

  assert.deepEqual(state.launches, ['chromium'])
  assert.equal(state.executedStepIds.filter((id) => id === 'setup-login-submit').length, 1)
  assert.equal(state.executedStepIds.filter((id) => id === 'logout-click').length, 1)
  assert.deepEqual(state.executedStepIds.filter((id) => id.endsWith('-assert')), ['case-1-assert', 'case-2-assert'])
  assert.deepEqual(job.state.results.map((result) => [result.caseId, result.status]), [
    ['case-1', 'passed'],
    ['case-2', 'passed']
  ])
  assert.equal(state.contextCloses, 1)
  assert.equal(state.browserCloses, 1)
  assert.equal(state.pageCloses, 1)
  assert.equal(job.state.summary.passed, 2)
  assert.equal(job.state.logs.filter((log) => log.phase === 'setup').length, 5)
  assert.equal(job.state.logs.filter((log) => log.phase === 'teardown').length, 2)
})

test('persistSession keeps the verified browser session for later evidence reuse', async () => {
  clearSessions()
  const payload = {
    mode: 'sequential',
    runtime: { ...runtime, persistSession: true, sessionKey: 'env-1:chromium', environmentId: 'env-1', logout: { enabled: false, steps: [] } },
    cases: [makeCase('persisted-case')]
  }
  const job = createJob(payload)
  const { state: dependencyState, dependencies } = createDependencies()

  await executeRunJobByBrowserGroups(job, dependencies)

  assert.match(job.state.sessionId, /^session-/)
  assert.ok(getSession(job.state.sessionId, {
    environmentId: 'env-1',
    sessionKey: 'env-1:chromium',
    browser: 'chromium'
  }))
  assert.equal(dependencyState.contextCloses, 0)
  assert.equal(dependencyState.browserCloses, 0)
  clearSessions()
})

test('mixed browsers receive independent setup and teardown sessions', async () => {
  const payload = {
    mode: 'sequential',
    runtime,
    cases: [makeCase('chromium-1', 'chromium'), makeCase('firefox-1', 'firefox'), makeCase('chromium-2', 'chromium')]
  }
  const job = createJob(payload)
  const { state, dependencies } = createDependencies()

  await executeRunJobByBrowserGroups(job, dependencies)

  assert.deepEqual(state.launches, ['chromium', 'firefox'])
  assert.equal(state.executedStepIds.filter((id) => id === 'setup-login-submit').length, 2)
  assert.equal(state.executedStepIds.filter((id) => id === 'logout-click').length, 2)
  assert.equal(job.state.results.length, 3)
})

test('a case failure does not stop later cases and teardown still runs', async () => {
  const payload = { mode: 'sequential', runtime, cases: [makeCase('case-1'), makeCase('case-2')] }
  const job = createJob(payload)
  const { state, dependencies } = createDependencies({ failingStepIds: ['case-1-assert'] })

  await executeRunJobByBrowserGroups(job, dependencies)

  assert.deepEqual(job.state.results.map((result) => [result.caseId, result.status]), [
    ['case-1', 'failed'],
    ['case-2', 'passed']
  ])
  assert.equal(state.executedStepIds.includes('case-2-assert'), true)
  assert.equal(state.executedStepIds.includes('logout-click'), true)
})

test('setup failure marks the browser group as error and teardown failure only adds diagnostics', async () => {
  const setupPayload = { mode: 'sequential', runtime, cases: [makeCase('case-1'), makeCase('case-2')] }
  const setupJob = createJob(setupPayload)
  const setupDependencies = createDependencies({ failingStepIds: ['setup-login-submit'] })

  await executeRunJobByBrowserGroups(setupJob, setupDependencies.dependencies)

  assert.deepEqual(setupJob.state.results.map((result) => result.status), ['error', 'error'])
  assert.equal(setupDependencies.state.executedStepIds.includes('case-1-assert'), false)
  assert.equal(setupDependencies.state.executedStepIds.includes('logout-click'), true)

  const teardownPayload = { mode: 'sequential', runtime, cases: [makeCase('case-1')] }
  const teardownJob = createJob(teardownPayload)
  const teardownDependencies = createDependencies({ failingStepIds: ['logout-click'] })

  await executeRunJobByBrowserGroups(teardownJob, teardownDependencies.dependencies)

  assert.equal(teardownJob.state.results[0].status, 'passed')
  assert.equal(teardownJob.state.logs.some((log) => log.phase === 'teardown' && log.status === 'failed'), true)
  assert.equal(teardownJob.state.diagnostics.some((message) => message.includes('退出登录')), true)
})

test('stopping between cases preserves results already completed in the browser group', async () => {
  const payload = { mode: 'sequential', runtime, cases: [makeCase('case-1'), makeCase('case-2')] }
  const job = createJob(payload)
  job.beforeNextStep = async () => {
    if (job.state.results.length === 1) throw new RunStoppedError()
  }
  const { state, dependencies } = createDependencies()

  await executeRunJobByBrowserGroups(job, dependencies)

  assert.equal(job.state.status, 'stopped')
  assert.deepEqual(job.state.results.map((result) => result.caseId), ['case-1'])
  assert.equal(state.executedStepIds.includes('case-2-assert'), false)
  assert.equal(state.executedStepIds.includes('logout-click'), true)
})

test('case results contain step evidence from the executed locator without input values', async () => {
  const payload = {
    mode: 'sequential',
    runtime: { baseUrl: 'https://example.com', headless: true, login: { enabled: false } },
    cases: [{
      id: 'evidence-case',
      name: '证据用例',
      steps: [{
        id: 'click-action',
        actionId: 'action-from-step',
        evidenceId: 'evidence-from-step',
        action: 'click',
        target: 'role=button[name="查询", exact=true]',
        value: 'do-not-leak'
      }]
    }]
  }
  const job = createJob(payload)
  const page = {
    currentUrl: 'https://example.com/users',
    url() { return this.currentUrl },
    goto: async (target) => { page.currentUrl = target },
    setDefaultTimeout() {},
    setDefaultNavigationTimeout() {},
    locator: () => { throw new Error('unexpected generic locator') },
    getByRole: () => ({
      count: async () => 1,
      isVisible: async () => true,
      isEnabled: async () => true,
      waitFor: async () => {},
      evaluate: async () => ({ receivesEvents: true }),
      click: async () => {}
    }),
    close: async () => {}
  }
  const dependencies = {
    defaultBrowserId: 'chromium',
    launchBrowser: async () => ({
      newContext: async () => ({ newPage: async () => page, close: async () => {} }),
      close: async () => {}
    }),
    executeStep: async () => {},
    observePageEvidence: async () => ({
      url: 'https://example.com/users',
      pageKey: 'https://example.com/users',
      pageFingerprint: 'fingerprint-users'
    }),
    captureLiveScreenshot: async () => {}
  }

  await executeRunJobByBrowserGroups(job, dependencies)

  const evidence = job.state.results[0].stepEvidence.find((item) => item.actionId === 'action-from-step')
  assert.ok(evidence)
  assert.equal(evidence.actionId, 'action-from-step')
  assert.equal(evidence.evidenceId, 'evidence-from-step')
  assert.deepEqual(evidence.resolvedTarget, {
    strategy: 'role',
    role: 'button',
    name: '查询',
    exact: true,
    locator: 'role=button[name="查询", exact=true]',
    visible: true,
    enabled: true,
    receivesEvents: true,
    unique: true
  })
  assert.equal(JSON.stringify(job.state.results).includes('do-not-leak'), false)
})

test('step evidence keeps action-provided trace ids and does not generate evidence ids', async () => {
  const payload = {
    mode: 'sequential',
    runtime: { baseUrl: 'https://example.com', headless: true, login: { enabled: false } },
    cases: [{
      id: 'trace-case',
      steps: [{ id: 'step-1', action: 'click', target: '#ready' }],
      actions: [{ id: 'action-1', evidenceId: 'evidence-1', type: 'click' }]
    }]
  }
  const job = createJob(payload)
  const page = {
    currentUrl: 'https://example.com/users',
    url() { return this.currentUrl },
    setDefaultTimeout() {},
    setDefaultNavigationTimeout() {},
    close: async () => {}
  }
  const dependencies = {
    defaultBrowserId: 'chromium',
    launchBrowser: async () => ({
      newContext: async () => ({ newPage: async () => page, close: async () => {} }),
      close: async () => {}
    }),
    executeStep: async () => {},
    observePageEvidence: async () => ({ url: page.url(), pageKey: page.url(), pageFingerprint: 'fp' }),
    resolveStepTargetEvidence: async () => ({
      strategy: 'css',
      selector: '#ready',
      locator: 'css=#ready',
      visible: true,
      enabled: true,
      receivesEvents: true,
      unique: true
    })
  }

  await executeRunJobByBrowserGroups(job, dependencies)

  const evidence = job.state.results[0].stepEvidence.find((item) => item.actionType === 'click')
  assert.equal(evidence.actionId, 'action-1')
  assert.equal(evidence.evidenceId, 'evidence-1')
  assert.equal(JSON.stringify(evidence).includes('step-1'), false)
})

test('step evidence reports observation failures separately from execution status', async () => {
  const payload = {
    mode: 'sequential',
    runtime: { baseUrl: 'https://example.com', headless: true, login: { enabled: false } },
    cases: [{ id: 'observation-case', steps: [{ id: 'assert-1', action: 'assertVisible', target: '#ready' }] }]
  }
  const job = createJob(payload)
  const page = {
    currentUrl: 'https://example.com',
    url() { return this.currentUrl },
    setDefaultTimeout() {},
    setDefaultNavigationTimeout() {},
    locator: () => ({ waitFor: async () => {} }),
    close: async () => {}
  }
  let observations = 0
  const dependencies = {
    defaultBrowserId: 'chromium',
    launchBrowser: async () => ({
      newContext: async () => ({ newPage: async () => page, close: async () => {} }),
      close: async () => {}
    }),
    executeStep: async () => {},
    observePageEvidence: async () => {
      observations += 1
      if (observations === 4) throw Object.assign(new Error('observer unavailable'), { failureType: 'page_not_ready' })
      return { url: 'https://example.com', pageKey: 'https://example.com', pageFingerprint: 'fp' }
    }
  }

  await executeRunJobByBrowserGroups(job, dependencies)

  const evidence = job.state.results[0].stepEvidence.find((item) => item.actionId === 'assert-1')
  assert.ok(evidence)
  assert.equal(evidence.execution.status, 'passed')
  assert.equal(evidence.observationStatus, 'failed')
  assert.equal(evidence.failureType, 'page_not_ready')
})

test('step evidence preserves fingerprints when an action changes pages', async () => {
  const payload = {
    mode: 'sequential',
    runtime: { baseUrl: 'https://example.com', headless: true, login: { enabled: false } },
    cases: [{ id: 'navigation-case', steps: [{ id: 'go-users', action: 'goto', target: '/users' }] }]
  }
  const job = createJob(payload)
  const page = {
    currentUrl: 'https://example.com',
    url() { return this.currentUrl },
    setDefaultTimeout() {},
    setDefaultNavigationTimeout() {},
    close: async () => {}
  }
  let observations = 0
  const dependencies = {
    defaultBrowserId: 'chromium',
    launchBrowser: async () => ({
      newContext: async () => ({ newPage: async () => page, close: async () => {} }),
      close: async () => {}
    }),
    executeStep: async () => { page.currentUrl = 'https://example.com/users' },
    observePageEvidence: async () => {
      observations += 1
      const pageKey = observations === 1 ? 'https://example.com' : 'https://example.com/users'
      return { url: pageKey, pageKey, pageFingerprint: observations === 1 ? 'fp-home' : 'fp-users' }
    }
  }

  await executeRunJobByBrowserGroups(job, dependencies)

  const evidence = job.state.results[0].stepEvidence[0]
  assert.equal(evidence.before.pageKey, 'https://example.com')
  assert.equal(evidence.before.pageFingerprint, 'fp-home')
  assert.equal(evidence.after.pageKey, 'https://example.com/users')
  assert.equal(evidence.after.pageFingerprint, 'fp-users')
  assert.equal(evidence.resolvedTarget.kind, 'route')
  assert.equal(evidence.resolvedTarget.visible, false)
  assert.equal(evidence.resolvedTarget.enabled, false)
  assert.equal(evidence.resolvedTarget.receivesEvents, false)
  assert.equal(evidence.resolvedTarget.unique, false)
})

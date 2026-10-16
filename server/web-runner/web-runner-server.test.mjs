import test from 'node:test'
import assert from 'node:assert/strict'
import { createRunJobManager } from './run-job-manager.mjs'
import { createLoginDiscoveryManager } from './login-discovery.mjs'
import { createWebRunnerServer } from './web-runner-server.mjs'

const startServer = async (options = {}) => {
  const server = createWebRunnerServer(options)
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const address = server.address()
  return {
    baseUrl: `http://127.0.0.1:${address.port}`,
    close: () => new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()))
  }
}

const requestJson = async (baseUrl, path, init = {}) => {
  const response = await fetch(`${baseUrl}${path}`, {
    ...init,
    headers: {
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...(init.headers || {})
    }
  })
  return {
    status: response.status,
    body: await response.json()
  }
}

const waitFor = async (condition, timeoutMs = 1_000) => {
  const startedAt = Date.now()
  while (!condition()) {
    if (Date.now() - startedAt > timeoutMs) throw new Error('condition timed out')
    await new Promise((resolve) => setTimeout(resolve, 5))
  }
}

test('async run API creates immediately and supports query pause resume and stop', async (context) => {
  const manager = createRunJobManager({ idFactory: () => 'run-http-1' })
  let releaseWorker
  const workerBlocked = new Promise((resolve) => { releaseWorker = resolve })
  const runner = await startServer({
    jobManager: manager,
    healthProvider: () => ({ status: 'UP', service: 'test-runner' }),
    capabilitiesProvider: async () => ({
      status: 'UP',
      browsers: [{ id: 'chromium', label: 'Chromium', available: true, recommendation: '' }]
    }),
    executeJob: async (job) => {
      job.markRunning()
      await workerBlocked
      await job.beforeNextStep()
      job.complete({ total: 1, passed: 1, failed: 0, error: 0 })
    }
  })
  context.after(async () => {
    releaseWorker()
    await runner.close()
  })

  const startedAt = Date.now()
  const created = await requestJson(runner.baseUrl, '/runs', {
    method: 'POST',
    body: JSON.stringify({
      mode: 'sequential',
      runtime: { login: { password: 'secret' } },
      cases: [{ id: 'WEB-1', name: '登录', steps: [] }]
    })
  })
  assert.equal(created.status, 202)
  assert.equal(created.body.runId, 'run-http-1')
  assert.ok(Date.now() - startedAt < 500)

  await waitFor(() => manager.get('run-http-1')?.snapshot().status === 'running')
  const queried = await requestJson(runner.baseUrl, '/runs/run-http-1')
  assert.equal(queried.status, 200)
  assert.equal(queried.body.status, 'running')
  assert.equal(JSON.stringify(queried.body).includes('secret'), false)

  const paused = await requestJson(runner.baseUrl, '/runs/run-http-1/pause', { method: 'POST' })
  assert.equal(paused.status, 200)
  assert.equal(paused.body.status, 'pause_requested')

  const resumed = await requestJson(runner.baseUrl, '/runs/run-http-1/resume', { method: 'POST' })
  assert.equal(resumed.status, 200)
  assert.equal(resumed.body.status, 'running')

  const stopped = await requestJson(runner.baseUrl, '/runs/run-http-1/stop', { method: 'POST' })
  assert.equal(stopped.status, 200)
  assert.equal(stopped.body.status, 'stop_requested')
  releaseWorker()
  await waitFor(() => manager.get('run-http-1')?.snapshot().status === 'stopped')
})

test('async run API exposes capabilities and controlled errors', async (context) => {
  const runner = await startServer({
    jobManager: createRunJobManager(),
    healthProvider: () => ({ status: 'UP', service: 'test-runner' }),
    capabilitiesProvider: async () => ({
      status: 'UP',
      browsers: [
        { id: 'chromium', label: 'Chromium', available: true, recommendation: '' },
        { id: 'firefox', label: 'Firefox', available: false, recommendation: 'install firefox' }
      ]
    }),
    executeJob: async () => {}
  })
  context.after(() => runner.close())

  const health = await requestJson(runner.baseUrl, '/health')
  assert.equal(health.status, 200)
  assert.equal(health.body.status, 'UP')

  const capabilities = await requestJson(runner.baseUrl, '/capabilities')
  assert.equal(capabilities.status, 200)
  assert.equal(capabilities.body.browsers[1].available, false)

  const missing = await requestJson(runner.baseUrl, '/runs/not-found')
  assert.equal(missing.status, 404)
  assert.match(missing.body.message, /not found/i)

  const invalid = await requestJson(runner.baseUrl, '/runs', {
    method: 'POST',
    body: JSON.stringify({ cases: [] })
  })
  assert.equal(invalid.status, 422)
  assert.match(invalid.body.message, /case/i)
})

test('page locator discovery does not log request, response, or error payloads', async (context) => {
  const runnerMode = { shouldFail: false }
  const runner = await startServer({
    jobManager: createRunJobManager(),
    executePageLocatorDiscovery: async (payload) => {
      if (runnerMode.shouldFail) {
        throw new Error(`arbitrary-sensitive-error-string ${payload.url}`)
      }
      return {
        pageUrl: payload.url,
        elements: [{ text: 'Alice Example' }],
        exploration: { status: 'completed', rounds: [{ pageUrl: payload.url }], scores: { email: 'alice@example.test' } }
      }
    }
  })
  context.after(() => runner.close())

  const calls = []
  const originalLog = console.log
  const originalError = console.error
  console.log = (...args) => calls.push({ method: 'log', text: args.map((arg) => String(arg)).join(' ') })
  console.error = (...args) => calls.push({ method: 'error', text: args.map((arg) => String(arg)).join(' ') })

  try {
    const observationControl = 'console-capture-control-marker'
    console.log(observationControl)

    const success = await requestJson(runner.baseUrl, '/page-locator-discovery', {
      method: 'POST',
      body: JSON.stringify({
        url: 'https://example.test/target?email=alice@example.test&secret=request-secret',
        extraHeaders: { 'X-Api-Key': 'api-key-secret' },
        pageHints: { email: 'alice@example.test', note: 'request-private-note' }
      })
    })
    assert.equal(success.status, 200)

    runnerMode.shouldFail = true
    const failure = await requestJson(runner.baseUrl, '/page-locator-discovery', {
      method: 'POST',
      body: JSON.stringify({
        url: 'https://example.test/failure?token=error-token',
        pageHints: { email: 'bob@example.test', note: 'error-private-note' }
      })
    })
    assert.equal(failure.status, 503)
  } finally {
    console.log = originalLog
    console.error = originalError
  }

  assert.equal(calls.some(({ text }) => text.includes('console-capture-control-marker')), true)
  assert.deepEqual(calls.filter(({ text }) => !text.includes('console-capture-control-marker')), [])
  const captured = calls.map(({ text }) => text).join('\\n')
  for (const secret of [
    'alice@example.test',
    'bob@example.test',
    'request-secret',
    'api-key-secret',
    'request-private-note',
    'error-token',
    'error-private-note',
    'arbitrary-sensitive-error-string'
  ]) {
    assert.equal(captured.includes(secret), false, `console output leaked ${secret}`)
  }
})

test('page locator discovery returns structured evidence failures instead of a generic 503', async (context) => {
  const runner = await startServer({
    jobManager: createRunJobManager(),
    executePageLocatorDiscovery: async () => {
      throw Object.assign(new Error('login failed'), {
        category: 'auth_context_lost',
        pageUrl: 'https://example.test/#/login?token=secret',
        retryable: true,
        network: {
          criticalBlocked: [{ origin: 'https://api.example.test', path: '/api/list?token=secret', resourceType: 'xhr', criticality: 'critical', failureType: 'network_blocked' }],
          authDependencies: [{ origin: 'https://api.example.test', path: '/auth/login?token=secret', resourceType: 'xhr', criticality: 'auth_dependency', failureType: 'auth_dependency', url: 'https://api.example.test/auth/login?token=secret', detail: 'should not leak' }]
        },
        repairTrail: [{ category: 'reauthenticate', round: 1, changedFingerprint: null }],
        handoff: { required: true, mode: 'manual', reason: 'auth_context_lost', resumeSupported: true, handoffId: 'handoff-1', sessionId: 'session-1' }
      })
    }
  })
  context.after(() => runner.close())

  const response = await requestJson(runner.baseUrl, '/page-locator-discovery', {
    method: 'POST', body: JSON.stringify({ url: 'https://example.test/target' })
  })
  assert.equal(response.status, 422)
  assert.equal(response.body.category, 'auth_context_lost')
  assert.equal(response.body.retryable, true)
  assert.equal(response.body.pageUrl, 'https://example.test/#/login')
  assert.equal(response.body.network.criticalBlocked[0].origin, 'https://api.example.test')
  assert.equal(response.body.network.criticalBlocked[0].path, '/api/list')
  assert.equal(response.body.network.criticalBlocked[0].resourceType, 'xhr')
  assert.equal(response.body.network.criticalBlocked[0].criticality, 'critical')
  assert.equal(response.body.network.criticalBlocked[0].failureType, 'network_blocked')
  assert.equal('url' in response.body.network.criticalBlocked[0], false)
  assert.equal(response.body.network.authDependencies[0].origin, 'https://api.example.test')
  assert.equal(response.body.network.authDependencies[0].path, '/auth/login')
  assert.equal(response.body.network.authDependencies[0].resourceType, 'xhr')
  assert.equal(response.body.network.authDependencies[0].criticality, 'auth_dependency')
  assert.equal(response.body.network.authDependencies[0].failureType, 'auth_dependency')
  assert.equal('url' in response.body.network.authDependencies[0], false)
  assert.equal('detail' in response.body.network.authDependencies[0], false)
  assert.equal(response.body.handoff.handoffId, 'handoff-1')
  assert.equal(response.body.handoff.sessionId, 'session-1')
  assert.equal('input' in response.body.handoff, false)
  assert.equal('extraHeaders' in response.body.handoff, false)
  assert.equal(JSON.stringify(response.body).includes('secret'), false)
  assert.deepEqual(response.body.repairTrail, [{ category: 'reauthenticate', round: 1, changedFingerprint: null }])
})

test('page locator discovery returns navigation failures as structured 422 evidence errors', async (context) => {
  const runner = await startServer({
    jobManager: createRunJobManager(),
    executePageLocatorDiscovery: async () => {
      throw Object.assign(new Error('direct navigation must be same-origin'), {
        category: 'navigation_failed',
        pageUrl: 'https://example.test/target',
        retryable: false
      })
    }
  })
  context.after(() => runner.close())

  const response = await requestJson(runner.baseUrl, '/page-locator-discovery', {
    method: 'POST', body: JSON.stringify({ url: 'https://example.test/target' })
  })
  assert.equal(response.status, 422)
  assert.equal(response.body.category, 'navigation_failed')
  assert.equal(response.body.retryable, false)
})

test('page locator discovery keeps a failureType-only error classified instead of a generic 503', async (context) => {
  // fail-closed 错误（非法导航目标、不可观测子树）只设置 failureType：
  // 若 HTTP 层只读 category，它们会以 503「runner 不可用」到达平台，真实原因全部丢失。
  const runner = await startServer({
    jobManager: createRunJobManager(),
    executePageLocatorDiscovery: async () => {
      throw Object.assign(new Error('navigation target is not a URL: [object Object]'), {
        failureType: 'unsupported_action_target',
        retryable: false
      })
    }
  })
  context.after(() => runner.close())

  const response = await requestJson(runner.baseUrl, '/page-locator-discovery', {
    method: 'POST', body: JSON.stringify({ url: 'https://example.test/target' })
  })
  assert.equal(response.status, 422)
  assert.equal(response.body.category, 'unsupported_action_target')
  assert.match(response.body.message, /navigation target is not a URL/)
})

test('page locator discovery keeps step evidence on a failed run', async (context) => {
  // 阶段 3 要求每个 action 都有 before/action/after 证据；最需要证据的恰恰是失败用例。
  // 之前只有成功响应才序列化 stepEvidence，失败响应把前一步已通过的证据全丢了。
  const runner = await startServer({
    jobManager: createRunJobManager(),
    executePageLocatorDiscovery: async () => {
      throw Object.assign(new Error('Action IR text assertion failed'), {
        failureType: 'assertion_failed',
        retryable: false,
        stepEvidence: [
          { actionId: 'go-ok', execution: { status: 'passed' } },
          { actionId: 'assert-bad', execution: { status: 'failed', failure: 'assertion_failed' } }
        ],
        pageSnapshots: [{ pageKey: 'http://127.0.0.1:41827/list', elementsEmpty: true }]
      })
    }
  })
  context.after(() => runner.close())

  const response = await requestJson(runner.baseUrl, '/page-locator-discovery', {
    method: 'POST', body: JSON.stringify({ url: 'https://example.test/target' })
  })
  assert.equal(response.status, 422)
  assert.equal(response.body.category, 'assertion_failed')
  assert.deepEqual(response.body.stepEvidence?.map((step) => step.actionId), ['go-ok', 'assert-bad'])
  assert.equal(response.body.pageSnapshots?.length, 1)
})

test('an unclassified failure still returns classified details instead of a bare 503', async (context) => {
  // 裸错误（Playwright/TypeError）此前走 503 分支且响应体只有 message：分类和已采集的证据一起丢，
  // 平台侧只能看到「runner 不可用」。现在未分类错误也带 details，分类用兜底 action_failed。
  const runner = await startServer({
    jobManager: createRunJobManager(),
    executePageLocatorDiscovery: async () => {
      throw Object.assign(new TypeError('Cannot read properties of undefined'), {
        stepEvidence: [{ actionId: 'a1', execution: { status: 'failed' } }],
        pageSnapshots: [{ pageKey: 'http://127.0.0.1:41827/list' }]
      })
    }
  })
  context.after(() => runner.close())

  const response = await requestJson(runner.baseUrl, '/page-locator-discovery', {
    method: 'POST', body: JSON.stringify({ url: 'https://example.test/target' })
  })
  assert.equal(response.status, 503)
  assert.equal(response.body.category, undefined)
  assert.deepEqual(response.body.stepEvidence?.map((step) => step.actionId), ['a1'], '503 也必须带已采集的步骤证据')
  assert.equal(response.body.pageSnapshots?.length, 1)
  assert.match(response.body.message, /Cannot read properties/)
})

test('a failed step request keeps its evidence category instead of degrading to 503', async (context) => {
  // 只设 failureType 的错误（fail-closed 分类）不能被降级成 503：平台会把它当成基础设施故障。
  const runner = await startServer({
    jobManager: createRunJobManager(),
    executePageLocatorDiscovery: async () => {
      throw Object.assign(new Error('pagination_not_changed: no page change observed'), { failureType: 'pagination_not_changed' })
    }
  })
  context.after(() => runner.close())

  const response = await requestJson(runner.baseUrl, '/page-locator-discovery', {
    method: 'POST', body: JSON.stringify({ url: 'https://example.test/target' })
  })
  assert.equal(response.status, 422)
  assert.equal(response.body.category, 'pagination_not_changed')
})

test('login discovery API creates queries and confirms an isolated discovery task', async (context) => {
  const discoveryManager = createLoginDiscoveryManager({ idFactory: () => 'discovery-http-1' })
  const runner = await startServer({
    jobManager: createRunJobManager(),
    loginDiscoveryManager: discoveryManager,
    executeLoginDiscovery: async (job) => {
      job.markRunning()
      job.complete({
        pageUrl: 'https://example.com/login',
        screenshotUrl: 'http://127.0.0.1:9323/artifacts/login.png',
        candidates: {
          username: [{ id: 'username-1', locator: 'css=#username', label: '账号', confidence: 98 }],
          password: [{ id: 'password-1', locator: 'css=#password', label: '密码', confidence: 100 }],
          submit: [{ id: 'submit-1', locator: 'role=button[name="登录"]', label: '登录', confidence: 96 }]
        },
        recommended: {
          username: { id: 'username-1', locator: 'css=#username', label: '账号', confidence: 98 },
          password: { id: 'password-1', locator: 'css=#password', label: '密码', confidence: 100 },
          submit: { id: 'submit-1', locator: 'role=button[name="登录"]', label: '登录', confidence: 96 }
        },
        diagnostics: []
      })
    }
  })
  context.after(() => runner.close())

  const created = await requestJson(runner.baseUrl, '/login-discovery', {
    method: 'POST',
    body: JSON.stringify({ url: 'https://example.com/login', browser: 'chromium' })
  })
  assert.equal(created.status, 202)
  assert.equal(created.body.discoveryId, 'discovery-http-1')
  assert.deepEqual(created.body.candidates, { username: [], password: [], submit: [] })
  assert.deepEqual(created.body.recommended, {})
  assert.deepEqual(created.body.diagnostics, [])

  const credentialUrl = await requestJson(runner.baseUrl, '/login-discovery', {
    method: 'POST',
    body: JSON.stringify({ url: 'https://user:secret@example.com/login', browser: 'chromium' })
  })
  assert.equal(credentialUrl.status, 422)

  await waitFor(() => discoveryManager.get('discovery-http-1')?.snapshot().status === 'completed')
  const queried = await requestJson(runner.baseUrl, '/login-discovery/discovery-http-1')
  assert.equal(queried.body.recommended.password.locator, 'css=#password')

  const confirmed = await requestJson(runner.baseUrl, '/login-discovery/discovery-http-1/confirm', {
    method: 'POST',
    body: JSON.stringify({
      usernameCandidateId: 'username-1',
      passwordCandidateId: 'password-1',
      submitCandidateId: 'submit-1'
    })
  })
  assert.equal(confirmed.status, 200)
  assert.equal(confirmed.body.confirmed.submitLocator, 'role=button[name="登录"]')
})

test('session listing route returns provider data and an empty default', async (context) => {
  const manager = createRunJobManager({ idFactory: () => 'run-sessions-1' })
  const listed = [
    { sessionId: 'session-1', projectId: 1, environmentId: 'env-1', browser: 'chromium', state: 'READY_FOR_COMPILE', leased: false, pageKey: 'http://example.com/x', createdAt: 1, expiresAt: 2, lastUsedAt: 3 }
  ]
  const withSessions = await startServer({ jobManager: manager, sessionsProvider: () => listed })
  context.after(async () => { await withSessions.close() })
  const response = await requestJson(withSessions.baseUrl, '/sessions')
  assert.equal(response.status, 200)
  assert.deepEqual(response.body.sessions, listed)

  const plain = await startServer({ jobManager: manager })
  const fallback = await requestJson(plain.baseUrl, '/sessions')
  assert.equal(fallback.status, 200)
  assert.deepEqual(fallback.body.sessions, [])
  await plain.close()
})

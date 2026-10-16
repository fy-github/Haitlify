import test from 'node:test'
import assert from 'node:assert/strict'
import net from 'node:net'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { createLoginFixtureServer } from './fixtures/login-app.mjs'

const getFreePort = async () => {
  const server = net.createServer()
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const port = server.address().port
  await new Promise((resolve) => server.close(resolve))
  return port
}

const waitForJson = async (url, predicate, timeoutMs = 30_000) => {
  const startedAt = Date.now()
  let lastError
  while (Date.now() - startedAt < timeoutMs) {
    try {
      const response = await fetch(url)
      if (response.ok) {
        const body = await response.json()
        if (predicate(body)) return body
      }
    } catch (error) {
      lastError = error
    }
    await new Promise((resolve) => setTimeout(resolve, 50))
  }
  throw new Error(`Timed out waiting for ${url}: ${lastError?.message || 'predicate not satisfied'}`)
}

const postJson = async (url, body) => {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) })
  })
  return { status: response.status, body: await response.json() }
}

const stopChild = async (child) => {
  if (child.exitCode !== null) return
  const exited = once(child, 'exit')
  child.kill('SIGTERM')
  const graceful = await Promise.race([
    exited.then(() => true),
    new Promise((resolve) => setTimeout(() => resolve(false), 2_000))
  ])
  if (graceful || child.exitCode !== null) return
  const forcedExit = once(child, 'exit')
  child.kill('SIGKILL')
  await forcedExit
}

const makePayload = (baseUrl) => ({
  mode: 'sequential',
  browserOverride: 'chromium',
  runtime: {
    baseUrl,
    browser: 'chromium',
    headless: true,
    viewport: '1024x720',
    defaultTimeoutMs: 5_000,
    navigationTimeoutMs: 5_000,
    waitUntil: 'domcontentloaded',
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
        {
          id: 'teardown-logout',
          name: '退出登录',
          action: 'click',
          targetType: 'locator',
          target: 'testid=logout'
        },
        {
          id: 'teardown-login-page',
          name: '确认返回登录页',
          action: 'waitForUrl',
          targetType: 'url',
          target: '/login'
        }
      ]
    }
  },
  cases: [
    {
      id: 'WEB-LOGIN-1',
      name: '登录后执行用户管理操作',
      browser: 'chromium',
      authenticationMode: 'environment',
      startUrl: '',
      steps: [
        {
          id: 'step-wait-dashboard',
          name: '等待用户管理加载',
          action: 'waitForVisible',
          targetType: 'locator',
          target: 'testid=delayed-dashboard'
        },
        {
          id: 'step-click-action',
          name: '执行用户操作',
          action: 'click',
          targetType: 'locator',
          target: 'testid=slow-action'
        },
        {
          id: 'step-assert-done',
          name: '确认操作完成',
          action: 'waitForVisible',
          targetType: 'locator',
          target: 'testid=done'
        }
      ]
    },
    {
      id: 'WEB-LOGIN-2',
      name: '复用登录态执行第二条用例',
      browser: 'chromium',
      authenticationMode: 'environment',
      startUrl: '',
      steps: [
        {
          id: 'step-second-case',
          name: '确认第二条用例仍处于登录态',
          action: 'waitForVisible',
          targetType: 'locator',
          target: 'testid=second-case'
        }
      ]
    }
  ]
})

test('real Chromium login run supports visual snapshots pause resume and stop', { timeout: 120_000 }, async (context) => {
  const fixturePort = await getFreePort()
  const runnerPort = await getFreePort()
  const fixture = createLoginFixtureServer()
  await new Promise((resolve) => fixture.listen(fixturePort, '127.0.0.1', resolve))
  const fixtureBaseUrl = `http://127.0.0.1:${fixturePort}`
  const runnerBaseUrl = `http://127.0.0.1:${runnerPort}`
  const runnerOutput = []
  const runner = spawn(process.execPath, ['server.mjs'], {
    cwd: new URL('.', import.meta.url),
    env: {
      ...process.env,
      APP_WEB_AUTOMATION_RUNNER_PORT: String(runnerPort),
      APP_WEB_AUTOMATION_RUNNER_PUBLIC_URL: runnerBaseUrl,
      APP_WEB_AUTOMATION_RUNNER_BROWSER_CHANNEL: 'chromium',
      APP_WEB_AUTOMATION_RUNNER_HEADLESS: 'true'
    },
    stdio: ['ignore', 'pipe', 'pipe']
  })
  runner.stdout.on('data', (chunk) => runnerOutput.push(String(chunk)))
  runner.stderr.on('data', (chunk) => runnerOutput.push(String(chunk)))
  context.after(async () => {
    await stopChild(runner)
    await new Promise((resolve) => fixture.close(resolve))
  })

  await waitForJson(`${runnerBaseUrl}/health`, (body) => body.status === 'UP')
  const created = await postJson(`${runnerBaseUrl}/runs`, makePayload(fixtureBaseUrl))
  assert.equal(created.status, 202, runnerOutput.join(''))
  const runId = created.body.runId

  await waitForJson(`${runnerBaseUrl}/runs/${runId}`, (snapshot) =>
    snapshot.currentStep?.id === 'step-wait-dashboard' && snapshot.status === 'running'
  )
  const pauseResponse = await postJson(`${runnerBaseUrl}/runs/${runId}/pause`)
  assert.equal(pauseResponse.status, 200)
  const paused = await waitForJson(`${runnerBaseUrl}/runs/${runId}`, (snapshot) => snapshot.status === 'paused')
  assert.equal(paused.currentStep.id, 'step-wait-dashboard')
  assert.equal(paused.stepLogs.some((log) => log.stepId === 'step-click-action'), false)
  assert.ok(paused.latestScreenshotUrl)
  const screenshot = await fetch(paused.latestScreenshotUrl)
  assert.equal(screenshot.ok, true)
  assert.ok((await screenshot.arrayBuffer()).byteLength > 1_000)

  const resumed = await postJson(`${runnerBaseUrl}/runs/${runId}/resume`)
  assert.equal(resumed.body.status, 'running')
  const completed = await waitForJson(`${runnerBaseUrl}/runs/${runId}`, (snapshot) => snapshot.status === 'completed')
  assert.equal(completed.summary.passed, 2)
  assert.equal(completed.progress.completedCases, 2)
  assert.equal(completed.stepLogs.filter((log) => log.stepId === 'setup-login-submit').length, 1)
  assert.equal(completed.stepLogs.filter((log) => log.stepId === 'teardown-logout').length, 1)
  assert.equal(completed.stepLogs.some((log) => log.stepId === 'step-assert-done' && log.status === 'passed'), true)
  assert.equal(completed.stepLogs.some((log) => log.stepId === 'step-second-case' && log.status === 'passed'), true)
  assert.equal(completed.stepLogs.some((log) => log.phase === 'teardown' && log.status === 'passed'), true)
  assert.match(completed.latestScreenshotUrl, /artifacts/)

  const firstMetrics = await waitForJson(`${fixtureBaseUrl}/metrics`, (body) => body.logoutCount === 1)
  assert.deepEqual(firstMetrics, { loginCount: 1, logoutCount: 1 })

  const stopCreated = await postJson(`${runnerBaseUrl}/runs`, makePayload(fixtureBaseUrl))
  const stopRunId = stopCreated.body.runId
  await waitForJson(`${runnerBaseUrl}/runs/${stopRunId}`, (snapshot) =>
    snapshot.currentStep?.id === 'step-wait-dashboard' && snapshot.status === 'running'
  )
  await postJson(`${runnerBaseUrl}/runs/${stopRunId}/pause`)
  await waitForJson(`${runnerBaseUrl}/runs/${stopRunId}`, (snapshot) => snapshot.status === 'paused')
  const stopped = await postJson(`${runnerBaseUrl}/runs/${stopRunId}/stop`)
  assert.equal(stopped.body.status, 'stopped')
  assert.equal(stopped.body.controls.canResume, false)
  assert.ok(stopped.body.endedAt)
  const finalMetrics = await waitForJson(`${fixtureBaseUrl}/metrics`, (body) => body.logoutCount === 2)
  assert.deepEqual(finalMetrics, { loginCount: 2, logoutCount: 2 })
})

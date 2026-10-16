import test from 'node:test'
import assert from 'node:assert/strict'
import {
  RunStoppedError,
  createRunJobManager
} from './run-job-manager.mjs'

const makePayload = () => ({
  mode: 'sequential',
  browserOverride: 'firefox',
  runtime: {
    baseUrl: 'https://example.com',
    authToken: 'token-secret',
    extraHeaders: {
      Cookie: 'session=cookie-secret'
    },
    variables: {
      tenantId: 'tenant-secret'
    },
    login: {
      enabled: true,
      username: 'admin-secret',
      password: 'password-secret'
    }
  },
  cases: [
    {
      id: 'WEB-1',
      name: '登录并打开用户管理',
      steps: [
        { id: 'step-1', action: 'input', target: '#password', value: 'typed-password' },
        { id: 'step-2', action: 'click', target: '#submit' }
      ]
    }
  ]
})

test('run job transitions through pause and resume at a step boundary', async () => {
  let timestamp = 1_000
  const manager = createRunJobManager({
    idFactory: () => 'run-1',
    now: () => timestamp
  })
  const job = manager.create(makePayload())

  assert.equal(job.snapshot().status, 'queued')
  assert.deepEqual(job.snapshot().controls, {
    canPause: false,
    canResume: false,
    canStop: true
  })

  timestamp += 100
  job.markRunning()
  job.setPlan({ totalCases: 1, totalSteps: 7, selectedBrowser: 'firefox' })
  job.updateCurrent({
    caseId: 'WEB-1',
    caseName: '登录并打开用户管理',
    stepId: 'step-1',
    stepName: '输入密码',
    action: 'input',
    phase: 'setup',
    pageUrl: 'https://example.com/login'
  })
  assert.equal(job.snapshot().status, 'running')
  assert.equal(job.snapshot().progress.totalSteps, 7)

  job.requestPause()
  assert.equal(job.snapshot().status, 'pause_requested')
  const boundaryWait = job.beforeNextStep()
  assert.equal(job.snapshot().status, 'paused')
  assert.equal(job.snapshot().controls.canResume, true)

  let resumed = false
  boundaryWait.then(() => { resumed = true })
  await Promise.resolve()
  assert.equal(resumed, false)

  job.resume()
  await boundaryWait
  assert.equal(job.snapshot().status, 'running')
  assert.equal(resumed, true)
})

test('run job stops at the next boundary and stops immediately while paused', async () => {
  const manager = createRunJobManager({ idFactory: () => 'run-stop' })
  const runningJob = manager.create(makePayload())
  runningJob.markRunning()
  runningJob.requestStop()
  assert.equal(runningJob.snapshot().status, 'stop_requested')
  await assert.rejects(() => runningJob.beforeNextStep(), RunStoppedError)
  assert.equal(runningJob.snapshot().status, 'stopped')
  assert.ok(runningJob.snapshot().endedAt)

  const pausedJob = manager.create(makePayload())
  pausedJob.markRunning()
  pausedJob.requestPause()
  const pausedWait = pausedJob.beforeNextStep()
  assert.equal(pausedJob.snapshot().status, 'paused')
  pausedJob.requestStop()
  await assert.rejects(() => pausedWait, RunStoppedError)
  assert.equal(pausedJob.snapshot().status, 'stopped')
})

test('run snapshot exposes progress and evidence without runtime secrets or input values', () => {
  const manager = createRunJobManager({ idFactory: () => 'run-safe' })
  const job = manager.create(makePayload())
  job.markRunning()
  job.setPlan({ totalCases: 1, totalSteps: 2, selectedBrowser: 'firefox' })
  job.appendStepLog({
    stepId: 'step-1',
    name: '输入密码',
    action: 'input',
    target: '#password',
    value: 'typed-password',
    status: 'passed',
    errorMessage: 'password-secret should never leak'
  })
  job.updateScreenshot({
    url: 'http://127.0.0.1:9323/artifacts/run-safe.png',
    capturedAt: '2026-07-13T12:00:00.000Z'
  })
  job.appendCaseResult({
    caseId: 'WEB-1',
    name: '登录并打开用户管理',
    status: 'passed',
    stepLogs: []
  })
  job.complete({ total: 1, passed: 1, failed: 0, error: 0 })

  const snapshot = job.snapshot()
  const serialized = JSON.stringify(snapshot)
  for (const secret of [
    'token-secret',
    'cookie-secret',
    'tenant-secret',
    'admin-secret',
    'password-secret',
    'typed-password'
  ]) {
    assert.equal(serialized.includes(secret), false, `${secret} leaked into snapshot`)
  }
  assert.equal(snapshot.status, 'completed')
  assert.equal(snapshot.progress.completedCases, 1)
  assert.equal(snapshot.progress.completedSteps, 1)
  assert.equal(snapshot.latestScreenshotUrl, 'http://127.0.0.1:9323/artifacts/run-safe.png')
  assert.equal(snapshot.stepLogs[0].value, undefined)
  assert.match(snapshot.stepLogs[0].errorMessage, /REDACTED/)
})

test('run manager evicts expired and excess terminal jobs but never active jobs', () => {
  let timestamp = 10_000
  let sequence = 0
  const manager = createRunJobManager({
    idFactory: () => `run-${++sequence}`,
    now: () => timestamp,
    retentionMs: 1_000,
    maxTerminalJobs: 2
  })

  const active = manager.create(makePayload())
  active.markRunning()
  const terminal1 = manager.create(makePayload())
  terminal1.markRunning()
  terminal1.complete({ total: 1, passed: 1, failed: 0, error: 0 })
  timestamp += 100
  const terminal2 = manager.create(makePayload())
  terminal2.markRunning()
  terminal2.complete({ total: 1, passed: 1, failed: 0, error: 0 })
  timestamp += 100
  const terminal3 = manager.create(makePayload())
  terminal3.markRunning()
  terminal3.complete({ total: 1, passed: 1, failed: 0, error: 0 })

  manager.cleanup()
  assert.ok(manager.get(active.id))
  assert.equal(manager.get(terminal1.id), undefined)
  assert.ok(manager.get(terminal2.id))
  assert.ok(manager.get(terminal3.id))

  timestamp += 1_100
  manager.cleanup()
  assert.ok(manager.get(active.id))
  assert.equal(manager.get(terminal2.id), undefined)
  assert.equal(manager.get(terminal3.id), undefined)
})

test('evidence sanitizing keeps route targets while dropping input values', async () => {
  const { __testables } = await import('./run-job-manager.mjs')
  const evidence = {
    stepEvidence: [
      {
        actionId: 'go-detail',
        resolvedTarget: { kind: 'route', value: 'http://127.0.0.1:41827/detail', pageKey: 'http://127.0.0.1:41827/detail' },
        execution: { status: 'passed' }
      },
      {
        actionId: 'fill-name',
        resolvedTarget: { strategy: 'css', locator: 'css=#name' },
        execution: { status: 'passed', value: '张三' }
      }
    ],
    runtime: { variables: { password: 'secret' } }
  }
  const sanitized = __testables.sanitizeEvidence(evidence, [])
  // 路由目标的 value 是地址：整表按 key 屏蔽会把它一起剔掉（复核方实测只剩 {kind:'route',visible:…}），证据链断裂。
  assert.equal(sanitized.stepEvidence[0].resolvedTarget.value, 'http://127.0.0.1:41827/detail')
  assert.equal(sanitized.stepEvidence[0].resolvedTarget.kind, 'route')
  // 输入值与 runtime/variables 仍然必须屏蔽。
  assert.equal(sanitized.stepEvidence[1].execution.value, undefined)
  assert.equal(sanitized.runtime, undefined)
})

test('short step values are not treated as redaction secrets', async () => {
  // 复核方实测：单字符步骤值被当 secret 时会把报文里所有同名子串替换掉（e[REDACTED]ceeded、
  // #does-not-e[REDACTED]ist），失败诊断直接不可读。
  const { __testables } = await import('./run-job-manager.mjs')
  const secrets = __testables.collectSecrets({
    cases: [{ steps: [{ value: 'x' }, { value: '00000000000' }] }],
    runtime: { authToken: 'Bearer abcdefghijklmnop', variables: { note: 'ab' } }
  })
  assert.deepEqual(secrets, ['Bearer abcdefghijklmnop', '00000000000'])
  assert.equal(__testables.redactText('exceeded', secrets), 'exceeded')
  assert.equal(__testables.redactText('token=Bearer abcdefghijklmnop', secrets), 'token=[REDACTED]')
})

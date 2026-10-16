import { describe, expect, it, vi } from 'vitest'
import {
  WebAutomationRunRequestError,
  createWebAutomationRun,
  getWebAutomationCapabilities,
  getWebAutomationRun,
  listWebAutomationSessions,
  waitForWebAutomationRun,
  pauseWebAutomationRun,
  resumeWebAutomationRun,
  stopWebAutomationRun
} from './webAutomationRunRepository'
import type { WebAutomationRunCase } from './webAutomation'

const jsonResponse = (body: unknown, init: ResponseInit = {}) =>
  Promise.resolve(new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init
  }))

const makeRunCase = (): WebAutomationRunCase => ({
  id: 'WEB-AUTO-WEB-0001',
  sourceCaseId: 'WEB-0001',
  project: 'Haitlify',
  module: '登录',
  pageName: '登录页',
  name: '登录成功',
  startUrl: '/login',
  browser: 'chromium',
  viewport: '1440x900',
  authenticationMode: 'environment',
  steps: [
    {
      id: 'step-1',
      name: '打开登录页',
      action: 'goto',
      targetType: 'url',
      target: '/login'
    }
  ],
  runOrder: 1,
  mockOutcome: 'passed'
})

const snapshot = (status = 'running') => ({
  runId: 'run-1',
  status,
  mode: 'sequential',
  selectedBrowser: 'firefox',
  createdAt: '2026-07-13T12:00:00.000Z',
  startedAt: '2026-07-13T12:00:01.000Z',
  updatedAt: '2026-07-13T12:00:02.000Z',
  progress: { totalCases: 1, completedCases: 0, totalSteps: 3, completedSteps: 1 },
  currentCase: { id: 'WEB-AUTO-WEB-0001', name: '登录成功' },
  currentStep: { id: 'step-1', name: '打开登录页', action: 'goto', phase: 'setup' },
  currentPageUrl: 'https://example.com/login',
  latestScreenshotUrl: 'http://127.0.0.1:9323/artifacts/run-1.png',
  latestScreenshotAt: '2026-07-13T12:00:02.000Z',
  controls: { canPause: true, canResume: false, canStop: true },
  summary: { total: 0, passed: 0, failed: 0, error: 0 },
  stepLogs: [],
  results: [],
  diagnostics: []
})

describe('webAutomationRunRepository', () => {
  it('creates an asynchronous run and serializes a real browser override', async () => {
    const fetchImpl = vi.fn<(input: string, init?: RequestInit) => Promise<Response>>(
      () => jsonResponse(snapshot('queued'), { status: 202 })
    )

    const result = await createWebAutomationRun([makeRunCase()], {
      mode: 'sequential',
      loopCount: 2,
      browserOverride: 'firefox'
    }, { fetchImpl })

    expect(result.runId).toBe('run-1')
    expect(fetchImpl).toHaveBeenCalledTimes(1)
    const [url, init] = fetchImpl.mock.calls[0]
    expect(new URL(url, 'http://test').pathname).toBe('/api/web-automation/runs')
    expect(init?.method).toBe('POST')
    expect(JSON.parse(String(init?.body))).toMatchObject({
      mode: 'sequential',
      loopCount: 2,
      browserOverride: 'firefox',
      cases: [{ id: 'WEB-AUTO-WEB-0001' }]
    })
  })

  it('serializes the platform project id when provided and omits it otherwise', async () => {
    const fetchImpl = vi.fn<(input: string, init?: RequestInit) => Promise<Response>>(
      () => jsonResponse(snapshot('queued'), { status: 202 })
    )

    await createWebAutomationRun([makeRunCase()], { mode: 'sequential', projectId: 12 }, { fetchImpl })
    expect(JSON.parse(String(fetchImpl.mock.calls[0][1]?.body))).toMatchObject({ projectId: 12 })

    fetchImpl.mockClear()
    await createWebAutomationRun([makeRunCase()], { mode: 'sequential' }, { fetchImpl })
    expect(JSON.parse(String(fetchImpl.mock.calls[0][1]?.body))).not.toHaveProperty('projectId')
  })

  it('lists runner sessions through the platform proxy', async () => {
    const fetchImpl = vi.fn<(input: string, init?: RequestInit) => Promise<Response>>(
      () => jsonResponse({ sessions: [{ sessionId: 'session-1', browser: 'chromium', state: 'READY_FOR_COMPILE', leased: false }] })
    )

    const sessions = await listWebAutomationSessions({ fetchImpl })

    expect(sessions).toHaveLength(1)
    expect(sessions[0].sessionId).toBe('session-1')
    const [url] = fetchImpl.mock.calls[0]
    expect(new URL(String(url), 'http://test').pathname).toBe('/api/web-automation/sessions')
  })

  it('omits browser override when following case settings', async () => {
    const fetchImpl = vi.fn<(input: string, init?: RequestInit) => Promise<Response>>(
      () => jsonResponse(snapshot('queued'), { status: 202 })
    )
    await createWebAutomationRun([makeRunCase()], {
      mode: 'sequential',
      browserOverride: 'follow-case'
    }, { fetchImpl })

    const body = JSON.parse(String(fetchImpl.mock.calls[0][1]?.body))
    expect(body).not.toHaveProperty('browserOverride')
  })

  it('queries and controls a run through exact HTTP paths', async () => {
    const fetchImpl = vi.fn<(input: string, init?: RequestInit) => Promise<Response>>(
      () => jsonResponse(snapshot())
    )
    const options = { fetchImpl }

    await getWebAutomationRun('run/with space', options)
    await pauseWebAutomationRun('run-1', options)
    await resumeWebAutomationRun('run-1', options)
    await stopWebAutomationRun('run-1', options)

    expect(fetchImpl.mock.calls.map(([url, init]) => [new URL(url, 'http://test').pathname, init?.method || 'GET'])).toEqual([
      ['/api/web-automation/runs/run%2Fwith%20space', 'GET'],
      ['/api/web-automation/runs/run-1/pause', 'POST'],
      ['/api/web-automation/runs/run-1/resume', 'POST'],
      ['/api/web-automation/runs/run-1/stop', 'POST']
    ])
  })

  it('streams live page, screenshot, and step updates until the run is terminal', async () => {
    let reads = 0
    const fetchImpl = vi.fn<(input: string, init?: RequestInit) => Promise<Response>>(() => {
      reads += 1
      return jsonResponse(snapshot(reads === 1 ? 'running' : 'completed'))
    })
    const updates: string[] = []
    const result = await waitForWebAutomationRun('run-1', {
      intervalMs: 50,
      onSnapshot: (value) => updates.push(`${value.currentPageUrl}|${value.latestScreenshotUrl}|${value.currentStep?.name || ''}`)
    }, { fetchImpl })

    expect(result.status).toBe('completed')
    expect(updates.length).toBeGreaterThanOrEqual(2)
    expect(updates[0]).toContain('https://example.com/login')
    expect(updates[0]).toContain('http://127.0.0.1:9323/artifacts/run-1.png')
    expect(updates[0]).toContain('打开登录页')
  })

  it('loads exact browser capabilities', async () => {
    const fetchImpl = vi.fn<(input: string, init?: RequestInit) => Promise<Response>>(() => jsonResponse({
      status: 'UP',
      checkedAt: '2026-07-13T12:00:00.000Z',
      browsers: [
        { id: 'chromium', label: 'Chromium', available: true, recommendation: '' },
        { id: 'chrome', label: 'Google Chrome', available: false, recommendation: '安装 Chrome' }
      ]
    }))

    const result = await getWebAutomationCapabilities({ fetchImpl })
    expect(new URL(fetchImpl.mock.calls[0][0], 'http://test').pathname).toBe('/api/web-automation/capabilities')
    expect(result.browsers[1]).toMatchObject({ id: 'chrome', available: false })
  })

  it('preserves backend error details without treating command timeout as total run timeout', async () => {
    const fetchImpl = vi.fn<(input: string, init?: RequestInit) => Promise<Response>>(() => Promise.resolve(new Response(JSON.stringify({
      message: 'Web automation runner command timed out after 5000 ms',
      recommendations: ['check runner health']
    }), {
      status: 503,
      headers: { 'Content-Type': 'application/json' }
    })))

    await expect(getWebAutomationRun('run-1', { fetchImpl })).rejects.toMatchObject({
      name: 'WebAutomationRunRequestError',
      status: 503,
      backendMessage: 'Web automation runner command timed out after 5000 ms',
      recommendations: ['check runner health']
    })

    const error = new WebAutomationRunRequestError(503, 'Web automation runner command timed out after 5000 ms')
    expect(error.toFallbackMessage()).toContain('命令请求超时')
    expect(error.toFallbackMessage()).not.toContain('提高 APP_WEB_AUTOMATION_RUNNER_TIMEOUT_MS')
    expect(error.message).toContain('Web automation runner command timed out after 5000 ms')
  })

  it('surfaces safe takeover suggestions for auth loss without echoing raw locator data', async () => {
    const fetchImpl = vi.fn<(input: string, init?: RequestInit) => Promise<Response>>(() => Promise.resolve(new Response(JSON.stringify({
      message: 'login failed token=secret locator=button[data-token="secret"]',
      recommendations: [
        '先重新登录并恢复同一会话，再继续当前操作。',
        '如果页面仍停留在登录态，请切回原业务页后重试。'
      ]
    }), {
      status: 422,
      headers: { 'Content-Type': 'application/json' }
    })))

    await expect(getWebAutomationRun('run-1', { fetchImpl })).rejects.toMatchObject({
      name: 'WebAutomationRunRequestError',
      status: 422,
      backendMessage: 'login failed token=[redacted] locator=[redacted]',
      recommendations: [
        '先重新登录并恢复同一会话，再继续当前操作。',
        '如果页面仍停留在登录态，请切回原业务页后重试。'
      ]
    })

    const error = new WebAutomationRunRequestError(
      422,
      'login failed token=secret locator=button[data-token="secret"]',
      [
        '先重新登录并恢复同一会话，再继续当前操作。',
        '如果页面仍停留在登录态，请切回原业务页后重试。'
      ]
    )
    expect(error.toFallbackMessage()).toContain('先重新登录并恢复同一会话')
    expect(error.toFallbackMessage()).not.toContain('token=secret')
    expect(error.toFallbackMessage()).not.toContain('button[data-token')
    expect(error.message).not.toContain('token=secret')
    expect(error.message).not.toContain('button[data-token')
    expect(error.backendMessage).toBe('login failed token=[redacted] locator=[redacted]')
  })
})

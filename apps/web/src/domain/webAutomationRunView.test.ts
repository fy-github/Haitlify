import { describe, expect, it } from 'vitest'
import {
  buildBrowserOptions,
  buildWebRunView,
  buildWebRunCaseResultMap,
  buildWebRunCaseStatusById,
  resolveWebRunCaseId
} from './webAutomationRunView'
import type { WebAutomationRunSnapshot } from './webAutomationRunRepository'
import type { WebAutomationRunResult } from './webAutomation'

const makeSnapshot = (overrides: Partial<WebAutomationRunSnapshot> = {}): WebAutomationRunSnapshot => ({
  runId: 'run-1',
  status: 'running',
  mode: 'sequential',
  selectedBrowser: 'chromium',
  createdAt: '2026-07-13T12:00:00.000Z',
  startedAt: '2026-07-13T12:00:01.000Z',
  updatedAt: '2026-07-13T12:00:02.000Z',
  progress: { totalCases: 2, completedCases: 0, totalSteps: 4, completedSteps: 1 },
  currentCase: { id: 'WEB-1', name: '登录' },
  currentStep: { id: 'step-2', name: '输入账号', action: 'input', phase: 'setup' },
  currentPageUrl: 'https://example.com/login',
  latestScreenshotUrl: 'http://127.0.0.1:9323/artifacts/run-1.png',
  latestScreenshotAt: '2026-07-13T12:00:02.000Z',
  controls: { canPause: true, canResume: false, canStop: true },
  summary: { total: 0, passed: 0, failed: 0, error: 0 },
  stepLogs: [
    {
      stepId: 'step-1', phase: 'setup', name: '打开登录页', action: 'goto', target: '/login',
      status: 'passed', durationMs: 120
    }
  ],
  results: [],
  diagnostics: [],
  ...overrides
})

describe('webAutomationRunView', () => {
  it('resolves only an exact saved id or one positive loop suffix', () => {
    const ids = new Set(['WEB-1', 'WEB-1#2'])
    expect(resolveWebRunCaseId('WEB-1#2', ids)).toBe('WEB-1#2')
    expect(resolveWebRunCaseId('WEB-1#2#3', ids)).toBe('WEB-1#2')
    expect(resolveWebRunCaseId('WEB-1#3', ids)).toBe('WEB-1')
    for (const id of ['WEB-1#0', 'WEB-1#01', 'WEB-1#x', 'WEB-1#3#4', 'WEB-2#1']) {
      expect(resolveWebRunCaseId(id, ids), id).toBeUndefined()
    }
  })

  it.each([
    ['failed', 'passed', 'failed'],
    ['passed', 'failed', 'failed'],
    ['error', 'passed', 'error'],
    ['failed', 'error', 'error']
  ] as const)('keeps the worst round of %s and %s as %s without altering evidence', (first, second, expected) => {
    const results: WebAutomationRunResult[] = [first, second].map((status, index) => ({
      caseId: `WEB-1#${index + 1}`, sourceCaseId: 'SHARED-SOURCE', name: 'fixture',
      module: 'fixture', pageName: 'fixture', startUrl: '/fixture', durationMs: 10,
      status, errorMessage: `round ${index + 1}`
    }))
    const before = JSON.stringify(results)
    const cases = [{ id: 'WEB-1' }, { id: 'WEB-2' }]
    const mapped = buildWebRunCaseResultMap(cases, results)
    expect(mapped.get('WEB-1')?.status).toBe(expected)
    expect(results).toContain(mapped.get('WEB-1'))
    expect(mapped.has('WEB-2')).toBe(false)
    expect(buildWebRunCaseStatusById(cases, results)).toEqual({ 'WEB-1': expected, 'WEB-2': 'pending' })
    expect(JSON.stringify(results)).toBe(before)
  })

  it('maps running progress controls and current step timeline', () => {
    const view = buildWebRunView(makeSnapshot())
    expect(view.statusLabel).toBe('执行中')
    expect(view.progressPercent).toBe(25)
    expect(view.controls).toEqual({ canPause: true, canResume: false, canStop: true })
    expect(view.timeline.map((item) => [item.id, item.status])).toEqual([
      ['step-1', 'passed'],
      ['step-2', 'running']
    ])
    expect(view.phaseLabel).toBe('环境准备')
  })

  it('maps paused and terminal states without relying on stale backend controls', () => {
    const paused = buildWebRunView(makeSnapshot({
      status: 'paused',
      controls: { canPause: false, canResume: true, canStop: true }
    }))
    expect(paused.statusLabel).toBe('已暂停')
    expect(paused.controls.canResume).toBe(true)

    const completed = buildWebRunView(makeSnapshot({
      status: 'completed',
      endedAt: '2026-07-13T12:00:10.000Z',
      progress: { totalCases: 2, completedCases: 2, totalSteps: 4, completedSteps: 4 },
      currentCase: undefined,
      currentStep: undefined,
      controls: { canPause: true, canResume: true, canStop: true }
    }))
    expect(completed.statusLabel).toBe('执行完成')
    expect(completed.progressPercent).toBe(100)
    expect(completed.controls).toEqual({ canPause: false, canResume: false, canStop: false })
  })

  it('maps browser-group teardown steps as environment cleanup', () => {
    const view = buildWebRunView(makeSnapshot({
      currentCase: { id: 'teardown:chromium', name: 'chromium 环境清理' },
      currentStep: {
        id: 'logout',
        name: '退出登录',
        action: 'click',
        phase: 'teardown'
      },
      stepLogs: [
        {
          stepId: 'logout',
          phase: 'teardown',
          name: '退出登录',
          action: 'click',
          target: 'testid=logout',
          status: 'passed',
          durationMs: 80
        }
      ]
    }))

    expect(view.phaseLabel).toBe('环境清理')
    expect(view.timeline[0].phase).toBe('teardown')
  })

  it('builds browser options and disables unavailable drivers', () => {
    const options = buildBrowserOptions({
      status: 'UP',
      checkedAt: '2026-07-13T12:00:00.000Z',
      browsers: [
        { id: 'chromium', label: 'Chromium', available: true, recommendation: '' },
        { id: 'chrome', label: 'Google Chrome', available: false, recommendation: '请安装 Chrome' },
        { id: 'msedge', label: 'Microsoft Edge', available: true, recommendation: '' }
      ]
    })

    expect(options[0]).toMatchObject({ value: 'follow-case', label: '跟随用例', disabled: false })
    expect(options.find((item) => item.value === 'chrome')).toMatchObject({
      disabled: true,
      recommendation: '请安装 Chrome'
    })
    expect(options.find((item) => item.value === 'msedge')?.disabled).toBe(false)
  })
})

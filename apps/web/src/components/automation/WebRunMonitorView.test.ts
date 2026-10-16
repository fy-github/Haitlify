// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { WebAutomationRunResult } from '../../domain/webAutomation'
import type { WebAutomationRunSnapshot } from '../../domain/webAutomationRunRepository'
import { buildWebRunView } from '../../domain/webAutomationRunView'
import WebRunMonitorView from './WebRunMonitorView.vue'

const result = (overrides: Partial<WebAutomationRunResult> = {}): WebAutomationRunResult => ({
  caseId: 'WEB-001',
  sourceCaseId: 'SRC-001',
  name: '登录成功',
  module: '账号',
  pageName: '登录页',
  startUrl: 'https://example.com/login',
  status: 'passed',
  durationMs: 1280,
  ...overrides
})

const makeSnapshot = (overrides: Partial<WebAutomationRunSnapshot> = {}): WebAutomationRunSnapshot => ({
  runId: 'run-1',
  status: 'running',
  mode: 'sequential',
  selectedBrowser: 'Chromium',
  createdAt: '2026-07-27T10:00:00Z',
  startedAt: '2026-07-27T10:00:01Z',
  updatedAt: '2026-07-27T10:00:08Z',
  progress: { totalCases: 4, completedCases: 1, totalSteps: 8, completedSteps: 3 },
  currentCase: { id: 'WEB-002', name: '提交退款申请' },
  currentStep: { id: 'step-3', name: '点击提交按钮', action: 'click', phase: 'test' },
  currentPageUrl: 'https://example.com/refund/apply?requestId=1234567890',
  latestScreenshotUrl: 'http://127.0.0.1:9323/artifacts/run-1/latest.png',
  latestScreenshotAt: '2026-07-27T10:00:08Z',
  controls: { canPause: true, canResume: false, canStop: true },
  summary: { total: 4, passed: 1, failed: 0, error: 0 },
  stepLogs: [
    { stepId: 'setup-1', phase: 'setup', name: '启动浏览器', action: 'goto', target: 'about:blank', status: 'passed', durationMs: 220 },
    { stepId: 'step-1', phase: 'test', name: '打开退款页', action: 'goto', target: '/refund', status: 'passed', durationMs: 520 },
    { stepId: 'teardown-1', phase: 'teardown', name: '关闭浏览器', action: 'click', target: 'browser.close', status: 'passed', durationMs: 80 }
  ],
  results: [
    result()
  ],
  diagnostics: ['Runner 心跳正常'],
  ...overrides
})

const mountMonitor = (
  snapshot = makeSnapshot(),
  overrides: Partial<{
    connectionState: 'idle' | 'polling' | 'retrying' | 'interrupted'
    connectionLabel: string
    elapsed: string
    controlLoading: 'pause' | 'resume' | 'stop' | ''
  }> = {}
) => mount(WebRunMonitorView, {
  props: {
    snapshot,
    view: buildWebRunView(snapshot),
    connectionState: overrides.connectionState || 'polling',
    connectionLabel: overrides.connectionLabel || '实时同步',
    elapsed: overrides.elapsed || '00:42',
    controlLoading: overrides.controlLoading || ''
  }
})

const visibleControls = (wrapper: ReturnType<typeof mountMonitor>) =>
  wrapper.findAll('[data-control-testid]').map((button) => button.text())

describe('WebRunMonitorView', () => {
  it('renders the running full-canvas monitor and emits active run commands', async () => {
    const snapshot = makeSnapshot()
    const wrapper = mountMonitor(snapshot)

    expect(wrapper.get('h1').text()).toBe('实时监控')
    expect(wrapper.text()).toContain('run-1')
    expect(wrapper.text()).toContain('Chromium')
    expect(wrapper.text()).toContain('环境准备')
    expect(wrapper.text()).toContain('用例执行')
    expect(wrapper.text()).toContain('环境清理')
    expect(wrapper.get('img[alt="Web 自动化实时页面截图"]').attributes('src')).toBe(snapshot.latestScreenshotUrl)
    expect(wrapper.text()).toContain('提交退款申请')
    expect(wrapper.text()).toContain('点击提交按钮')
    expect(wrapper.text()).toContain('https://example.com/refund/apply?requestId=1234567890')
    expect(wrapper.text()).toContain('click')
    expect(wrapper.text()).toContain('2026-07-27T10:00:08Z')
    expect(wrapper.text()).toContain('通过 1')
    expect(wrapper.text()).toContain('运行中 1')
    expect(wrapper.text()).toContain('待执行 2')
    expect(wrapper.text()).toContain('登录成功')
    expect(wrapper.get('[data-testid="latest-web-run-diagnostic"]').text()).toContain('最新诊断')
    expect(wrapper.get('[data-testid="latest-web-run-diagnostic"]').text()).toContain('Runner 心跳正常')

    await wrapper.get('[aria-label="返回 Web 自动化工作台"]').trigger('click')
    await wrapper.get('[data-testid="pause-web-run"]').trigger('click')
    await wrapper.get('[data-testid="stop-web-run"]').trigger('click')
    await wrapper.get('[aria-label="查看 Web 用例 WEB-001 日志"]').trigger('click')

    expect(wrapper.emitted('returnWorkbench')).toEqual([[]])
    expect(wrapper.emitted('pause')).toEqual([[]])
    expect(wrapper.emitted('stop')).toEqual([[]])
    expect(wrapper.emitted('openLog')).toEqual([['WEB-001']])
  })

  it('keeps every colored status state icon-backed while preserving visible labels', () => {
    const wrapper = mountMonitor()

    expect(wrapper.get('[data-testid="web-run-lifecycle-status"]').text()).toContain('执行中')
    expect(wrapper.get('[data-testid="web-run-lifecycle-status"] svg').attributes('aria-hidden')).toBe('true')
    expect(wrapper.get('[data-testid="web-run-connection-status"]').text()).toContain('实时同步')
    expect(wrapper.get('[data-testid="web-run-connection-status"] svg').attributes('aria-hidden')).toBe('true')
    expect(wrapper.findAll('[data-testid^="web-run-count-"]')).toHaveLength(5)
    expect(wrapper.findAll('[data-testid^="web-run-count-"] svg')).toHaveLength(5)
    expect(wrapper.get('[data-testid="web-run-result-status-WEB-001"]').text()).toContain('通过')
    expect(wrapper.get('[data-testid="web-run-result-status-WEB-001"] svg').attributes('aria-hidden')).toBe('true')
    expect(wrapper.findAll('[data-testid^="web-run-timeline-status-"]')).toHaveLength(4)
    expect(wrapper.findAll('[data-testid^="web-run-timeline-status-"] svg')).toHaveLength(4)
  })

  it('keeps long case names, URLs and diagnostics in dedicated wrapping hooks', () => {
    const longCaseName = '超长中文用例名称用于验证实时监控详情不会挤压布局且应该在专用区域内自然换行'
    const longUrl = 'https://example.com/refund/apply?requestId=1234567890&tenantId=tenant-very-long-value&redirect=https%3A%2F%2Fconsole.example.com%2Forders%2Frefund%2Fdetail%2FWEB-002'
    const longDiagnostic = '诊断信息包含很长的中文描述和 runner-timeout-stack-trace-segment-abcdef1234567890，必须留在诊断区域内换行展示'
    const wrapper = mountMonitor(makeSnapshot({
      currentCase: { id: 'WEB-002', name: longCaseName },
      currentPageUrl: longUrl,
      diagnostics: [longDiagnostic]
    }))

    expect(wrapper.get('[data-testid="current-web-run-case"]').classes()).toContain('web-run-monitor-view__wrap-text')
    expect(wrapper.get('[data-testid="current-web-run-case"]').text()).toBe(longCaseName)
    expect(wrapper.get('[data-testid="current-web-run-url"]').classes()).toContain('web-run-monitor-view__wrap-text')
    expect(wrapper.get('[data-testid="current-web-run-url"]').text()).toBe(longUrl)
    expect(wrapper.get('[data-testid="latest-web-run-diagnostic"] dd').classes()).toContain('web-run-monitor-view__wrap-text')
    expect(wrapper.get('[data-testid="latest-web-run-diagnostic"] dd').text()).toBe(longDiagnostic)
  })

  it('shows a queued waiting state without screenshots or timeline entries', () => {
    const snapshot = makeSnapshot({
      status: 'queued',
      currentCase: undefined,
      currentStep: undefined,
      latestScreenshotUrl: undefined,
      latestScreenshotAt: undefined,
      controls: { canPause: false, canResume: false, canStop: true },
      stepLogs: [],
      results: [],
      progress: { totalCases: 3, completedCases: 0, totalSteps: 0, completedSteps: 0 },
      summary: { total: 3, passed: 0, failed: 0, error: 0 }
    })

    const wrapper = mountMonitor(snapshot)

    expect(wrapper.text()).toContain('等待浏览器启动')
    expect(wrapper.text()).toContain('当前阶段')
    expect(wrapper.text()).toContain('用例执行')
    expect(wrapper.text()).toContain('待执行 3')
    expect(wrapper.find('img[alt="Web 自动化实时页面截图"]').exists()).toBe(false)
  })

  it('derives active counts from live results when the run summary is stale', () => {
    const wrapper = mountMonitor(makeSnapshot({
      summary: { total: 4, passed: 0, failed: 0, error: 0 },
      results: [
        result({ caseId: 'WEB-PASSED', status: 'passed', name: '已完成用例' })
      ]
    }))

    expect(wrapper.get('[data-testid="web-run-count-passed"]').text()).toContain('通过 1')
    expect(wrapper.get('[data-testid="web-run-count-running"]').text()).toContain('运行中 1')
    expect(wrapper.get('[data-testid="web-run-count-pending"]').text()).toContain('待执行 2')
  })

  it.each([
    ['paused', '已暂停', ['继续执行', '停止'], true],
    ['completed', '执行完成', ['重新执行', '查看报告'], true],
    ['failed', '执行异常', ['重新执行', '查看报告'], true]
  ] as const)('offers exact controls for %s snapshots', async (status, label, controls, terminal) => {
    const snapshot = makeSnapshot({
      status,
      controls: status === 'paused'
        ? { canPause: false, canResume: true, canStop: true }
        : { canPause: false, canResume: false, canStop: false },
      currentCase: terminal ? undefined : { id: 'WEB-002', name: '提交退款申请' },
      currentStep: terminal ? undefined : { id: 'step-3', name: '点击提交按钮', action: 'click', phase: 'test' },
      progress: { totalCases: 2, completedCases: 2, totalSteps: 8, completedSteps: 8 },
      summary: { total: 2, passed: status === 'completed' ? 1 : 0, failed: status === 'failed' ? 1 : 0, error: 0 },
      results: [
        result({ caseId: 'WEB-001', status: status === 'failed' ? 'failed' : 'passed', errorMessage: status === 'failed' ? '按钮不可见' : undefined })
      ]
    })
    const wrapper = mountMonitor(snapshot)

    expect(wrapper.text()).toContain(label)
    expect(visibleControls(wrapper)).toEqual(controls)

    if (status === 'paused') {
      await wrapper.get('[data-testid="resume-web-run"]').trigger('click')
      expect(wrapper.emitted('resume')).toEqual([[]])
    } else {
      await wrapper.get('[data-testid="rerun-web-run"]').trigger('click')
      await wrapper.get('[data-testid="open-web-run-report"]').trigger('click')
      expect(wrapper.emitted('rerun')).toEqual([[]])
      expect(wrapper.emitted('openReport')).toEqual([[]])
    }

    expect(wrapper.find('[aria-label="查看 Web 用例 WEB-001 日志"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="将 Web 用例 WEB-001 转为缺陷"]').exists()).toBe(status === 'failed')
  })

  it('keeps the last visual context while connection is interrupted and limits controls', async () => {
    const snapshot = makeSnapshot()
    const wrapper = mountMonitor(snapshot, { connectionState: 'interrupted', connectionLabel: '连接中断' })

    expect(wrapper.get('img[alt="Web 自动化实时页面截图"]').attributes('src')).toBe(snapshot.latestScreenshotUrl)
    expect(wrapper.text()).toContain('启动浏览器')
    expect(wrapper.text()).toContain('连接中断')
    expect(visibleControls(wrapper)).toEqual(['停止'])

    await wrapper.get('[data-testid="stop-web-run"]').trigger('click')
    expect(wrapper.emitted('stop')).toEqual([[]])
  })

  it('preserves the last screenshot and timeline when a retry snapshot omits them', async () => {
    const initial = makeSnapshot()
    const wrapper = mountMonitor(initial)
    const retrySnapshot = makeSnapshot({
      latestScreenshotUrl: undefined,
      stepLogs: [],
      currentStep: undefined
    })

    await wrapper.setProps({
      snapshot: retrySnapshot,
      view: buildWebRunView(retrySnapshot),
      connectionState: 'retrying',
      connectionLabel: '正在重连'
    })

    expect(wrapper.get('img[alt="Web 自动化实时页面截图"]').attributes('src')).toBe(initial.latestScreenshotUrl)
    expect(wrapper.text()).toContain('启动浏览器')
    expect(wrapper.text()).toContain('正在重连')
  })

  it('resets cached screenshot and timeline evidence when switching to a new run id', async () => {
    const wrapper = mountMonitor(makeSnapshot())
    const runTwo = makeSnapshot({
      runId: 'run-2',
      latestScreenshotUrl: undefined,
      latestScreenshotAt: undefined,
      stepLogs: [],
      currentStep: undefined,
      results: [],
      summary: { total: 4, passed: 0, failed: 0, error: 0 }
    })

    await wrapper.setProps({
      snapshot: runTwo,
      view: buildWebRunView(runTwo),
      connectionState: 'retrying',
      connectionLabel: '正在重连'
    })

    expect(wrapper.text()).toContain('run-2')
    expect(wrapper.find('img[alt="Web 自动化实时页面截图"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('等待首个页面截图')
    expect(wrapper.text()).toContain('当前阶段')
    expect(wrapper.text()).toContain('用例执行')
    expect(wrapper.text()).not.toContain('启动浏览器')
  })

  it('exposes failed and error result rows for defect transfer by case id only', async () => {
    const wrapper = mountMonitor(makeSnapshot({
      status: 'failed',
      controls: { canPause: false, canResume: false, canStop: false },
      results: [
        result({ caseId: 'WEB-FAILED', status: 'failed', name: '断言失败' }),
        result({ caseId: 'WEB-ERROR', status: 'error', name: '脚本异常' }),
        result({ caseId: 'WEB-PASSED', status: 'passed', name: '登录成功' })
      ]
    }))

    await wrapper.get('[aria-label="将 Web 用例 WEB-FAILED 转为缺陷"]').trigger('click')
    await wrapper.get('[aria-label="将 Web 用例 WEB-ERROR 转为缺陷"]').trigger('click')

    expect(wrapper.emitted('transferDefect')).toEqual([['WEB-FAILED'], ['WEB-ERROR']])
    expect(wrapper.find('[aria-label="将 Web 用例 WEB-PASSED 转为缺陷"]').exists()).toBe(false)
  })
})

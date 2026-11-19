// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { nextTick } from 'vue'
import { describe, expect, it } from 'vitest'
import type { WebAutomationRunResult } from '../../domain/webAutomation'
import type { WebAutomationRunSnapshot } from '../../domain/webAutomationRunRepository'
import { buildWebRunView } from '../../domain/webAutomationRunView'
import WebRunMonitorView from './WebRunMonitorView.vue'
// This test runs in Vitest's jsdom environment, where Vite rewrites import.meta.url to a
// non-file URL, so the design CSS is read from disk relative to the project root instead.
// @ts-expect-error Node's test-only filesystem API is intentionally not part of the browser type set.
import { readFileSync } from 'node:fs'
declare const process: { cwd: () => string }

const designSource = (() => {
  const candidates = [
    `${process.cwd()}/src/components/automation/web-execution-design.css`,
    `${process.cwd()}/apps/web/src/components/automation/web-execution-design.css`
  ]
  for (const candidate of candidates) {
    try {
      return readFileSync(candidate, 'utf8')
    } catch {
      // try the next candidate
    }
  }
  throw new Error(`web-execution-design.css not found from ${process.cwd()}`)
})()

const designRule = (selector: string): string => {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = designSource.match(new RegExp(`(?:^|\\n)\\s*${escaped}\\s*\\{([^}]*)\\}`))
  return match?.[1] || ''
}

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
  },
  global: { plugins: [ElementPlus] },
  attachTo: document.body
})

const visibleControls = (wrapper: ReturnType<typeof mountMonitor>) =>
  wrapper.findAll('[data-control-testid]').map((button) => button.text())

const settle = async () => {
  await nextTick()
  await nextTick()
}

const logText = (wrapper: ReturnType<typeof mountMonitor>) =>
  wrapper.get('[data-testid="web-monitor-log"]').text()

describe('WebRunMonitorView layout', () => {
  it('renders the confirmed three-column monitor at the measured widths', () => {
    const wrapper = mountMonitor()

    expect(wrapper.get('h1').text()).toBe('Web 运行监控')
    expect(wrapper.find('[data-testid="web-monitor-grid"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="web-monitor-queue"]').text()).toContain('执行队列')
    expect(wrapper.get('[data-testid="web-monitor-screen"]').text()).toContain('页面截图')
    expect(wrapper.get('[data-testid="web-monitor-log"]').text()).toContain('步骤与日志')

    // 定稿实测：218px / minmax(280px,1fr) / 310px；H1 23px；面板 H2 12px。
    expect(designRule('.web-ed-monitor__grid')).toMatch(/grid-template-columns:\s*218px minmax\(280px, 1fr\) 310px/)
    expect(designRule('.web-ed-monitor .web-ed-monitor__title')).toMatch(/font-size:\s*23px/)
    expect(designRule('.web-ed-monitor__panel-head h2')).toMatch(/font-size:\s*12px/)
    expect(designRule('.web-ed-monitor__actions button')).toMatch(/font-size:\s*11px/)
    expect(designRule('.web-ed-monitor__actions button')).toMatch(/min-height:\s*34px/)
    expect(designRule('.web-ed-monitor__actions button')).toMatch(/border-radius:\s*19px/)
    expect(designRule('.web-ed-monitor__queue-item')).toMatch(/border-radius:\s*10px/)
  })

  it('derives the execution queue from the real snapshot and keeps pending work anonymous', () => {
    const wrapper = mountMonitor()

    const rows = wrapper.findAll('[data-testid^="web-monitor-queue-"]')
    // WEB-001 已完成 + WEB-002 运行中 + 一条待执行汇总行（快照未返回待执行身份）
    expect(rows).toHaveLength(3)
    expect(wrapper.get('[data-testid="web-monitor-queue-WEB-001"]').text()).toContain('登录成功')
    expect(wrapper.get('[data-testid="web-monitor-queue-WEB-001"]').text()).toContain('通过')
    expect(wrapper.get('[data-testid="web-monitor-queue-WEB-002"]').text()).toContain('提交退款申请')
    expect(wrapper.get('[data-testid="web-monitor-queue-WEB-002"]').text()).toContain('运行中')

    const pending = wrapper.get('[data-testid="web-monitor-queue-pending"]')
    expect(pending.text()).toContain('待执行 2 条')
    expect(pending.text()).toContain('运行快照未返回待执行用例身份')
    expect(pending.text()).not.toContain('通过')
  })

  it('marks loop-expanded queue identities with their loop round', () => {
    const wrapper = mountMonitor(makeSnapshot({
      results: [
        result({ caseId: 'WEB-001#2', name: '登录成功', status: 'passed' })
      ],
      progress: { totalCases: 4, completedCases: 1, totalSteps: 8, completedSteps: 3 }
    }))

    expect(wrapper.get('[data-testid="web-monitor-queue-WEB-001#2"]').text()).toContain('第 2 轮')
  })

  it('shows the selected case steps and logs from that result instead of fabricating them', () => {
    const wrapper = mountMonitor(makeSnapshot({
      results: [
        result({
          caseId: 'WEB-001',
          name: '断言失败用例',
          status: 'failed',
          errorMessage: '按钮不可见',
          stepLogs: [
            { stepId: 'r-1', phase: 'test', name: '断言订单状态', action: 'assertText', target: 'order.status', status: 'passed', durationMs: 300 },
            { stepId: 'r-2', phase: 'test', name: '点击提交按钮', action: 'click', target: '#submit', status: 'failed', durationMs: 120, errorMessage: '按钮不可见' }
          ]
        })
      ]
    }))

    // 默认选中所选用例（运行中）→ 展示实时轨迹。
    expect(logText(wrapper)).toContain('启动浏览器')

    wrapper.get('[data-testid="web-monitor-queue-WEB-001"] .web-ed-queue-item__select').trigger('click')
    return nextTick().then(() => {
      expect(logText(wrapper)).toContain('断言订单状态')
      expect(logText(wrapper)).toContain('按钮不可见')
      expect(logText(wrapper)).not.toContain('启动浏览器')
    })
  })

  it('states that a pending queue item has no steps or logs yet', async () => {
    const wrapper = mountMonitor()

    await wrapper.get('[data-testid="web-monitor-queue-pending"] .web-ed-queue-item__select').trigger('click')

    expect(logText(wrapper)).toContain('该用例尚未执行，暂无步骤日志')
    expect(logText(wrapper)).toContain('不会用假步骤或假截图冒充执行证据')
    expect(logText(wrapper)).not.toContain('启动浏览器')
    expect(wrapper.get('[data-testid="web-monitor-panel-name"]').text()).toContain('待执行')
  })

  it('labels the central screenshot as the run-wide latest frame when another case is selected', async () => {
    const wrapper = mountMonitor()

    expect(wrapper.get('[data-testid="web-monitor-screen"]').text()).toContain('本次运行最新截图')
    expect(wrapper.get('[data-testid="web-monitor-screen"]').text()).not.toContain('非所选用例')

    await wrapper.get('[data-testid="web-monitor-queue-WEB-001"] .web-ed-queue-item__select').trigger('click')
    await settle()

    expect(wrapper.get('[data-testid="web-monitor-screen"]').text()).toContain('非所选用例')
  })

  it('switches panels explicitly on narrow screens', async () => {
    const wrapper = mountMonitor()
    const nav = wrapper.get('[data-testid="web-monitor-pane-nav"]')
    const grid = wrapper.get('[data-testid="web-monitor-grid"]')

    expect(nav.findAll('button').map((button) => button.text())).toEqual(['执行队列', '页面截图', '步骤与日志'])
    expect(grid.attributes('data-pane')).toBe('screen')
    expect(wrapper.get('[data-testid="web-monitor-pane-screen"]').attributes('aria-pressed')).toBe('true')

    await wrapper.get('[data-testid="web-monitor-pane-queue"]').trigger('click')
    expect(grid.attributes('data-pane')).toBe('queue')
    expect(wrapper.get('[data-testid="web-monitor-pane-queue"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('[data-testid="web-monitor-pane-screen"]').attributes('aria-pressed')).toBe('false')

    await wrapper.get('[data-testid="web-monitor-pane-log"]').trigger('click')
    expect(grid.attributes('data-pane')).toBe('log')
    expect(designSource).toContain('.web-ed-monitor__grid[data-pane="log"]')
  })
})

describe('WebRunMonitorView detail drawer', () => {
  it('opens an independent drawer with the real case and result fields', async () => {
    const wrapper = mountMonitor(makeSnapshot({
      status: 'failed',
      controls: { canPause: false, canResume: false, canStop: false },
      results: [
        result({
          caseId: 'WEB-001',
          name: '断言失败用例',
          module: '退款',
          pageName: '退款申请',
          startUrl: 'https://example.com/refund',
          status: 'failed',
          durationMs: 1280,
          failedStepId: 'r-2',
          errorMessage: '按钮不可见',
          consoleErrors: ['TypeError: x is undefined'],
          stepLogs: [
            { stepId: 'r-2', phase: 'test', name: '点击提交按钮', action: 'click', target: '#submit', status: 'failed', durationMs: 120, errorMessage: '按钮不可见' }
          ]
        })
      ]
    }))

    await wrapper.get('[data-testid="web-monitor-queue-WEB-001"] .web-ed-queue-item__select').trigger('click')
    await wrapper.get('[data-testid="open-web-run-detail"]').trigger('click')
    await settle()

    const detail = wrapper.get('[data-testid="web-run-detail"]')
    expect(detail.text()).toContain('WEB-001')
    expect(detail.text()).toContain('断言失败用例')
    expect(detail.text()).toContain('退款申请')
    expect(detail.text()).toContain('https://example.com/refund')
    expect(detail.text()).toContain('失败步骤')
    expect(detail.text()).toContain('r-2')
    expect(detail.text()).toContain('按钮不可见')
    expect(detail.text()).toContain('TypeError: x is undefined')
    expect(detail.find('[aria-label="将 Web 用例 WEB-001 转为缺陷"]').exists()).toBe(true)

    await wrapper.get('[data-testid="close-web-run-detail"]').trigger('click')
    await settle()
    expect(wrapper.get('[data-testid="web-run-detail"]').isVisible()).toBe(false)
  })

  it('does not invent a defect action for a passing result', async () => {
    const wrapper = mountMonitor()

    await wrapper.get('[data-testid="web-monitor-queue-WEB-001"] .web-ed-queue-item__select').trigger('click')
    await wrapper.get('[data-testid="open-web-run-detail"]').trigger('click')
    await settle()

    const detail = wrapper.get('[data-testid="web-run-detail"]')
    expect(detail.text()).toContain('登录成功')
    expect(detail.find('[aria-label="将 Web 用例 WEB-001 转为缺陷"]').exists()).toBe(false)
  })
})

describe('WebRunMonitorView live run semantics', () => {
  it('renders the running full-canvas monitor and emits active run commands', async () => {
    const snapshot = makeSnapshot()
    const wrapper = mountMonitor(snapshot)

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

    await wrapper.get('[data-testid="back-to-preparation"]').trigger('click')
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
      currentCase: undefined,
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

// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { WebAutomationRunSnapshot } from '../../domain/webAutomationRunRepository'
import { buildWebRunView } from '../../domain/webAutomationRunView'
import WebRunMonitorSummary from './WebRunMonitorSummary.vue'

const makeSnapshot = (overrides: Partial<WebAutomationRunSnapshot> = {}): WebAutomationRunSnapshot => ({
  runId: 'run-1', status: 'running', mode: 'sequential', selectedBrowser: 'Chromium', createdAt: '2026-07-27T10:00:00Z', startedAt: '2026-07-27T10:00:01Z', updatedAt: '2026-07-27T10:00:05Z',
  progress: { totalCases: 4, completedCases: 2, totalSteps: 10, completedSteps: 5 },
  currentCase: { id: 'WEB-1', name: '用户登录' }, currentStep: { id: 'step-2', name: '提交表单', action: 'click', phase: 'test' },
  latestScreenshotUrl: 'http://127.0.0.1/artifacts/run-1.png', controls: { canPause: true, canResume: false, canStop: true },
  summary: { total: 4, passed: 2, failed: 0, error: 0 }, stepLogs: [], results: [], diagnostics: ['正在重连 Runner'], ...overrides
})

const mountSummary = (snapshot = makeSnapshot()) => mount(WebRunMonitorSummary, {
  props: { snapshot, view: buildWebRunView(snapshot), connectionLabel: '正在重连', elapsed: '00:42' }
})

describe('WebRunMonitorSummary', () => {
  it('renders the active run status, accessible progress, screenshot and latest context', async () => {
    const snapshot = makeSnapshot()
    const before = structuredClone(snapshot)
    const wrapper = mountSummary(snapshot)

    expect(wrapper.text()).toContain('执行中')
    expect(wrapper.text()).toContain('正在重连')
    expect(wrapper.text()).toContain('00:42')
    expect(wrapper.text()).toContain('Chromium')
    expect(wrapper.text()).toContain('2 / 4')
    expect(wrapper.get('[role="progressbar"]').attributes()).toMatchObject({ 'aria-valuenow': '50', 'aria-valuemin': '0', 'aria-valuemax': '100' })
    expect(wrapper.get('img').attributes('alt')).toBe('Web 自动化运行截图')
    expect(wrapper.text()).toContain('用户登录')
    expect(wrapper.text()).toContain('提交表单')
    expect(wrapper.text()).toContain('正在重连 Runner')
    await wrapper.get('[data-testid="enter-web-run-monitor"]').trigger('click')
    expect(wrapper.emitted('enterMonitor')).toHaveLength(1)
    expect(snapshot).toEqual(before)
  })

  it.each([
    ['queued', '等待浏览器启动'],
    ['running', '等待首个页面截图'],
    ['completed', '本次执行没有页面截图']
  ] as const)('shows the %s no-image message', (status, message) => {
    const wrapper = mountSummary(makeSnapshot({ status, latestScreenshotUrl: undefined }))
    expect(wrapper.text()).toContain(message)
    expect(wrapper.find('img').exists()).toBe(false)
  })

  it('keeps a terminal result summary visible and emits rerun and report commands', async () => {
    const snapshot = makeSnapshot({ status: 'completed', currentCase: undefined, currentStep: undefined, results: [{} as WebAutomationRunSnapshot['results'][number]] })
    const wrapper = mountSummary(snapshot)

    expect(wrapper.text()).toContain('执行完成')
    expect(wrapper.text()).toContain('当前用例')
    expect(wrapper.text()).toContain('当前步骤')
    expect(wrapper.get('[data-testid="enter-web-run-monitor"]').text()).toBe('进入实时监控')
    await wrapper.get('[data-testid="rerun-web-automation"]').trigger('click')
    await wrapper.get('[data-testid="open-web-run-report"]').trigger('click')
    expect(wrapper.emitted('rerun')).toHaveLength(1)
    expect(wrapper.emitted('openReport')).toHaveLength(1)
  })

  it('does not offer terminal commands for an active snapshot', () => {
    const wrapper = mountSummary()
    expect(wrapper.find('[data-testid="rerun-web-automation"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="open-web-run-report"]').exists()).toBe(false)
  })
})

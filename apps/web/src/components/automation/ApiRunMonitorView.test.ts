// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { reactive } from 'vue'
import ApiRunMonitorView from './ApiRunMonitorView.vue'
import { createApiRunMonitor, recordApiCaseResult, recordApiCaseStart } from '../../domain/apiRunMonitor'
import { buildApiRunSnapshot } from '../../domain/apiExecutionPreparation'
import type { SavedAutomationCase } from '../../domain/automationRepository'

const one: SavedAutomationCase = {
  id: 'A', sourceCaseId: 'source-A', project: 'Fixture', module: 'M', name: 'Fixture case',
  method: 'GET', path: '/fixture', headers: {}, pathParams: {}, queryParams: {},
  expectedStatus: 200, assertions: ['body.ok = true'], runOrder: 1, exportTarget: 'apifox', automationLibraryId: 'A', savedAt: ''
}
const wrappers: VueWrapper[] = []
const setup = () => {
  const monitor = reactive(createApiRunMonitor(buildApiRunSnapshot({ cases: [one], environment: { baseUrl: 'https://fixture.invalid' }, options: { mode: 'sequential', loopCount: 2 }, scopeLabel: '执行筛选 1 条', frozenAt: '' })))
  const wrapper = mount(ApiRunMonitorView, { props: { monitor }, attachTo: document.body, global: { plugins: [ElementPlus] } })
  wrappers.push(wrapper)
  return { wrapper, monitor }
}
const drawer = () => document.querySelector('[data-testid="api-monitor-detail"]')!

describe('ApiRunMonitorView', () => {
  afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); document.body.innerHTML = '' })
  it('selects a queue identity and opens separate pending details without synthesized payloads', async () => {
    const { wrapper, monitor } = setup()
    await wrapper.findAll('[data-testid="api-monitor-queue-row"]')[1]!.trigger('click')
    expect(wrapper.get('[data-testid="api-monitor-log"]').text()).toContain('A#2')
    expect(wrapper.get('[data-testid="api-monitor-log"]').text()).toContain('待执行，尚无请求和响应记录')
    await wrapper.get('[data-testid="api-monitor-open-detail"]').trigger('click'); await flushPromises()
    expect(drawer().textContent).toContain('A#2')
    expect(drawer().textContent).toContain('尚未开始执行，没有请求记录')
    expect(drawer().textContent).toContain('尚未开始执行，没有响应')
    expect(drawer().textContent).not.toContain('https://fixture.invalid')
    expect(monitor.rows.every(row => !row.result)).toBe(true)
    expect(wrapper.text()).not.toMatch(/暂停|继续执行|停止|RUN-/)
  })
  it('shows running as waiting, then the real result including null response, timing and assertion failure', async () => {
    const { wrapper, monitor } = setup()
    recordApiCaseStart(monitor, { ...one, id: 'A#1' })
    await wrapper.get('[data-testid="api-monitor-open-detail"]').trigger('click'); await flushPromises()
    expect(drawer().textContent).toContain('正在执行，尚无响应')
    expect(drawer().textContent).not.toContain('HTTP 200')
    recordApiCaseResult(monitor, {
      caseId: 'A#1', sourceCaseId: one.sourceCaseId, name: one.name, module: one.module,
      method: 'GET', path: '/fixture', status: 'failed', expectedStatus: 200, actualStatus: 200,
      durationMs: 42, actualResponseBody: null, errorMessage: '断言失败: body.ok 期望 true，实际 undefined',
      request: { method: 'GET', url: 'https://fixture.invalid/fixture', headers: {}, expectedStatus: 200, assertions: one.assertions }
    })
    await flushPromises()
    expect(drawer().textContent).toContain('HTTP 200 · 耗时 42 ms')
    expect(drawer().textContent).toContain('断言失败: body.ok')
    expect(drawer().querySelectorAll('pre')[1]?.textContent).toBe('null')
    expect(wrapper.get('[data-testid="api-monitor-log"]').text()).toContain('42 ms')
    expect(wrapper.findAll('[data-testid="api-monitor-queue-row"]')[1]!.attributes('data-status')).toBe('pending')
  })
  it('exposes explicit mobile pane selection and keeps the selected queue identity', async () => {
    const { wrapper } = setup()
    await wrapper.get('[data-testid="api-monitor-pane-queue"]').trigger('click')
    expect(wrapper.get('[data-testid="api-monitor-pane-queue"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('[data-testid="api-monitor-split"]').attributes('data-mobile-pane')).toBe('queue')
    await wrapper.findAll('[data-testid="api-monitor-queue-row"]')[1]!.trigger('click')
    expect(wrapper.get('[data-testid="api-monitor-split"]').attributes('data-mobile-pane')).toBe('log')
    expect(wrapper.findAll('[data-testid="api-monitor-queue-row"]')[1]!.attributes('aria-pressed')).toBe('true')
    await wrapper.get('[data-testid="api-monitor-back"]').trigger('click')
    expect(wrapper.emitted('returnPreparation')).toHaveLength(1)
  })
})

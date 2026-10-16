// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { CircleCheck, CircleX, TriangleAlert } from '@lucide/vue'
import { describe, expect, it } from 'vitest'
import type { WebAutomationPreflight } from '../../domain/webAutomationWorkspaceView'
import WebRunPreflightPanel from './WebRunPreflightPanel.vue'

const preflight = (): WebAutomationPreflight => ({
  effectiveCount: 3,
  canExecute: false,
  blockingReason: '执行环境无效',
  items: [
    { id: 'runner', label: 'Runner', status: 'ready', detail: 'Runner 已连接' },
    { id: 'browser', label: '浏览器', status: 'warning', detail: 'Runner 部分能力不可用', repairAction: 'retry-capabilities' },
    { id: 'environment', label: '执行环境', status: 'blocked', detail: '执行环境无效', repairAction: 'edit-environment' },
    { id: 'login', label: '自动登录', status: 'blocked', detail: '登录配置不完整', repairAction: 'edit-login' },
    { id: 'teardown', label: '自动清理', status: 'ready', detail: '无需自动清理' },
    { id: 'cases', label: '执行范围', status: 'blocked', detail: '没有可执行用例', repairAction: 'clear-filters' }
  ]
})

describe('WebRunPreflightPanel', () => {
  it('renders ordered ready, warning and blocked checks with visible states and icons', () => {
    const wrapper = mount(WebRunPreflightPanel, { props: { preflight: preflight() } })
    const rows = wrapper.findAll('[data-preflight-item]')

    expect(wrapper.get('h2').text()).toBe('执行前检查')
    expect(rows.map((row) => row.find('[data-preflight-label]').text())).toEqual(['Runner', '浏览器', '执行环境', '自动登录', '自动清理', '执行范围'])
    expect(rows[0].text()).toContain('已就绪')
    expect(rows[1].text()).toContain('需注意')
    expect(rows[2].text()).toContain('需处理')
    expect(rows[0].findComponent(CircleCheck).exists()).toBe(true)
    expect(rows[1].findComponent(TriangleAlert).exists()).toBe(true)
    expect(rows[2].findComponent(CircleX).exists()).toBe(true)
    expect(rows.every((row) => row.find('svg').attributes('aria-hidden') === 'true')).toBe(true)
  })

  it('emits each supported repair command from its real button without mutating props', async () => {
    const value = preflight()
    const before = structuredClone(value)
    const wrapper = mount(WebRunPreflightPanel, { props: { preflight: value } })

    await wrapper.get('[data-testid="repair-preflight-browser"]').trigger('click')
    await wrapper.get('[data-testid="repair-preflight-environment"]').trigger('click')
    await wrapper.get('[data-testid="repair-preflight-login"]').trigger('click')
    await wrapper.get('[data-testid="repair-preflight-cases"]').trigger('click')

    expect(wrapper.emitted('retryCapabilities')).toHaveLength(1)
    expect(wrapper.emitted('editEnvironment')).toHaveLength(1)
    expect(wrapper.emitted('editLogin')).toHaveLength(1)
    expect(wrapper.emitted('clearFilters')).toHaveLength(1)
    expect(value).toEqual(before)
  })

  it('shows an optional interruption message and a polite blocked summary', () => {
    const wrapper = mount(WebRunPreflightPanel, { props: { preflight: preflight(), message: '能力检测请求中断，请重试' } })

    expect(wrapper.text()).toContain('能力检测请求中断，请重试')
    const summary = wrapper.get('[aria-live="polite"]')
    expect(summary.text()).toBe('暂不可执行：执行环境无效')
    expect(summary.classes()).toContain('web-preflight__summary--blocked')
  })

  it('shows the passed live summary and omits repair controls when no action exists', () => {
    const value = preflight()
    value.canExecute = true
    value.blockingReason = ''
    value.items = [{ id: 'runner', label: 'Runner', status: 'ready', detail: 'Runner 已连接' }]
    const wrapper = mount(WebRunPreflightPanel, { props: { preflight: value } })

    const summary = wrapper.get('[aria-live="polite"]')
    expect(summary.text()).toBe('执行检查已通过')
    expect(summary.classes()).toContain('web-preflight__summary--ready')
    expect(wrapper.find('[data-testid="repair-preflight-runner"]').exists()).toBe(false)
  })
})

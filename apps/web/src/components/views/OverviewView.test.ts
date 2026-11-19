// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import OverviewView from './OverviewView.vue'

describe('OverviewView data-state contract', () => {
  it('keeps unavailable data explicit and does not render fabricated metric values', () => {
    const wrapper = mount(OverviewView)

    expect(wrapper.get('[data-overview-state]').attributes('data-overview-state')).toBe('unavailable')
    expect(wrapper.get('[data-testid="overview-unavailable"]').text()).toContain('未接入真实统计数据')
    expect(wrapper.find('[data-testid="overview-loading"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="overview-metric-value"]').exists()).toBe(false)
  })

  it('renders loading, empty, forbidden and error states with the correct live region', async () => {
    const wrapper = mount(OverviewView, { props: { status: 'loading' } })
    expect(wrapper.get('[data-overview-state]').attributes('data-overview-state')).toBe('loading')
    expect(wrapper.get('[data-testid="overview-loading"]').attributes('role')).toBe('status')
    expect(wrapper.get('[data-testid="overview-loading"]').attributes('aria-busy')).toBe('true')

    await wrapper.setProps({ status: 'empty' })
    expect(wrapper.get('[data-testid="overview-empty"]').text()).toContain('暂无可用概览数据')
    expect(wrapper.get('[data-testid="overview-empty"]').attributes('role')).toBe('status')

    await wrapper.setProps({ status: 'forbidden' })
    expect(wrapper.get('[data-testid="overview-forbidden"]').text()).toContain('没有权限')
    expect(wrapper.get('[data-testid="overview-forbidden"]').attributes('role')).toBe('status')

    await wrapper.setProps({ status: 'error', errorMessage: '概览数据暂时不可用' })
    expect(wrapper.get('[data-testid="overview-error"]').text()).toContain('概览数据暂时不可用')
    expect(wrapper.get('[data-testid="overview-error"]').attributes('role')).toBe('alert')
  })

  it.each([
    ['unavailable', '重新检查数据可用性'],
    ['forbidden', '重新检查权限'],
    ['error', '重试加载']
  ] as const)('keeps the %s retry action on the overview panel button style', async (status, label) => {
    const wrapper = mount(OverviewView, { props: { status } })
    const retry = wrapper.get('button[data-testid="overview-retry"]')

    expect(retry.text()).toBe(label)
    expect(retry.classes()).toContain('overview-state-panel__action')
    await retry.trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('renders only supplied metric values and emits retry from the error state', async () => {
    const wrapper = mount(OverviewView, {
      props: {
        status: 'ready',
        cards: [{
          id: 'projects',
          title: '项目健康度',
          description: '来自执行报告聚合。',
          dependencies: ['执行报告聚合'],
          value: '12 个项目'
        }]
      }
    })
    expect(wrapper.get('[data-testid="overview-metric-value"]').text()).toBe('12 个项目')
    expect(wrapper.findAll('[data-testid="overview-metric-value"]')).toHaveLength(1)

    await wrapper.setProps({ status: 'error' })
    await wrapper.get('[data-testid="overview-retry"]').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })
})

// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import HomeView from './HomeView.vue'
import type { HomeRun, HomeWorkItem } from '../../domain/homeWorkbench'

const activeRun: HomeRun = {
  id: 'web-run-real', name: '设备回归', kind: 'web', project: 'cloud', state: 'running',
  environmentName: '测试环境', completed: 3, total: 8, passed: 2, failed: 1, error: 0,
  target: { navKey: 'automation', automationKind: 'web', runId: 'web-run-real', project: 'cloud' }
}
const preparingRun: HomeRun = {
  id: 'api-current', name: '接口回归', kind: 'api', project: 'cloud', state: 'preparing',
  stage: '准备执行环境', target: { navKey: 'automation', automationKind: 'api', project: 'cloud' }
}
const saved: HomeWorkItem = {
  id: 'case-1', name: '登录检查', kind: 'case', project: 'cloud', savedAt: '2026-10-20T09:30:00Z',
  statusLabel: '已保存', target: { navKey: 'webCases', caseId: 'case-1', project: 'cloud' }
}

afterEach(() => { document.body.innerHTML = '' })

describe('HomeView workbench', () => {
  it('renders the archived monitor / pending / wide recent workbench, promoting saved work in the empty DOM order', () => {
    const wrapper = mount(HomeView)
    expect(wrapper.find('.home-workbench').exists()).toBe(true)
    expect(wrapper.text()).toContain('继续上次工作')
    expect(wrapper.text()).toContain('最近保存')
    expect(wrapper.findAll('.home-layout > section').map((section) => section.attributes('data-section')))
      .toEqual(['recent', 'runs', 'pending'])
    expect(wrapper.text()).not.toContain('旧 AI 助手页面已下线')
    expect(wrapper.find('[data-testid="home-assistant-input"]').exists()).toBe(false)
  })

  it('shows saved records with exact typed destinations and the full real pending queue without inventing assignment', async () => {
    const pending = Array.from({ length: 5 }, (_, i): HomeWorkItem => ({
      id: `draft-${i}`, name: `待导出缺陷 ${i}`, project: 'cloud', kind: 'defect',
      savedAt: `2026-10-${10 + i}T00:00:00Z`, statusLabel: '待导出 Jira',
      pending: { label: '待导出 Jira', actionLabel: '查看草稿' },
      target: { navKey: 'defects', defectId: `draft-${i}`, project: 'cloud' }
    }))
    const report: HomeWorkItem = { ...saved, id: 'report-1', kind: 'report', name: '回归报告', savedAt: '2026-10-21T00:00:00Z', target: { navKey: 'reports', reportId: 'report-1', project: 'cloud' } }
    const wrapper = mount(HomeView, { props: { items: [saved, report, ...pending] } })
    expect(wrapper.get('.home-recent-count').text()).toBe('7')
    expect(wrapper.get('.home-recent tbody tr:first-child').text()).toContain('回归报告')
    expect(wrapper.get('.home-pending-count').text()).toBe('5')
    expect(wrapper.findAll('.home-pending-list .home-todo')).toHaveLength(5)
    expect(wrapper.get('.home-pending-more').text()).toBe('共 5 条 · 向下滚动查看其余 2 条')
    expect(wrapper.text()).not.toContain('分配给我')
    await wrapper.get('[data-work-id="report-1"] button').trigger('click')
    await wrapper.get('[data-pending-id="draft-4"] button').trigger('click')
    expect(wrapper.emitted('navigate')).toEqual([[report.target], [pending[4]!.target]])
    await wrapper.get('[data-work-filter="case"]').trigger('click')
    expect(wrapper.get('[data-work-filter="case"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.findAll('.home-recent tbody tr')).toHaveLength(1)
    expect(wrapper.get('.home-recent-count').text()).toBe('1')
    expect(wrapper.get('.home-pending-count').text()).toBe('5')
    await wrapper.get('[data-work-id="case-1"] button').trigger('click')
    expect(wrapper.emitted('navigate')?.[2]).toEqual([saved.target])
  })

  it('filters all regions from the supplied projects and returns keyboard focus to the project trigger', async () => {
    const wrapper = mount(HomeView, { attachTo: document.body, props: {
      runs: [activeRun], items: [saved, { ...saved, id: 'portal-report', kind: 'report', project: 'portal', name: '门户报告', pending: { label: '待核对', actionLabel: '查看报告' } }],
      projects: [{ value: 'cloud', label: '云管理平台' }, { value: 'portal', label: '服务门户' }]
    } })
    const trigger = wrapper.find('[data-testid="home-project-trigger"]')
    expect(trigger.exists()).toBe(true)
    await trigger.trigger('keydown', { key: 'End' })
    expect(trigger.attributes('aria-expanded')).toBe('true')
    await trigger.trigger('keydown', { key: 'Enter' })
    expect(trigger.text()).toContain('服务门户')
    expect(document.activeElement).toBe(trigger.element)
    expect(wrapper.get('.home-run-count').text()).toBe('0')
    expect(wrapper.get('.home-recent-count').text()).toBe('1')
    expect(wrapper.get('.home-pending-count').text()).toBe('1')
    expect(wrapper.findAll('.home-layout > section')[0]!.attributes('data-section')).toBe('recent')
    await trigger.trigger('keydown', { key: 'Home' })
    await trigger.trigger('keydown', { key: ' ' })
    expect(trigger.text()).toContain('全部项目')
    expect(wrapper.get('.home-run-count').text()).toBe('1')
    await trigger.trigger('click')
    await trigger.trigger('keydown', { key: 'Escape' })
    expect(trigger.attributes('aria-expanded')).toBe('false')
    wrapper.unmount()
  })

  it('exposes section-scoped focus without global querying and preserves the new assistant navigation entry', async () => {
    const wrapper = mount(HomeView, { attachTo: document.body })
    const vm = wrapper.vm as unknown as { focusSection: (section: 'runs' | 'pending' | 'recent') => void }
    expect(typeof vm.focusSection).toBe('function')
    for (const section of ['runs', 'pending', 'recent'] as const) {
      vm.focusSection(section)
      expect(document.activeElement).toBe(wrapper.get(`[data-section="${section}"]`).element)
      expect(wrapper.get(`[data-section="${section}"]`).attributes('tabindex')).toBe('-1')
    }
    await wrapper.get('[data-testid="home-open-chain"]').trigger('click')
    expect(wrapper.emitted('navigate')).toEqual([[{ navKey: 'assistant' }]])
    wrapper.unmount()
  })

  it('never turns paused work into waiting for human and only renders waiting when the producer explicitly supplies it', async () => {
    const wrapper = mount(HomeView, { props: { runs: [{ ...activeRun, state: 'paused', completed: undefined, total: undefined, passed: undefined, failed: undefined, error: undefined }] } })
    expect(wrapper.find('.home-waiting-context').exists()).toBe(false)
    expect(wrapper.find('[role="progressbar"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('38%')
    await wrapper.setProps({ runs: [{ ...activeRun, state: 'waiting_user', stage: '需要本人完成验证' }] })
    expect(wrapper.find('.home-waiting-context').exists()).toBe(true)
    expect(wrapper.get('.home-waiting-context').text()).toContain('需要本人完成验证')
    expect(wrapper.find('[role="progressbar"]').exists()).toBe(false)
  })

  it('never invents percentages for preparation or invalid progress and retains every additional run navigation', async () => {
    const third: HomeRun = { ...preparingRun, id: 'third-run', name: '第三项执行', target: { navKey: 'automation', automationKind: 'api', runId: 'third-run' } }
    const wrapper = mount(HomeView, { props: { runs: [{ ...activeRun, completed: 10, total: 8 }, preparingRun, third] } })
    expect(wrapper.find('[role="progressbar"]').exists()).toBe(false)
    expect(wrapper.get('.home-run-count').text()).toBe('3')
    await wrapper.get('[data-additional-run="third-run"]').trigger('click')
    expect(wrapper.emitted('navigate')).toEqual([[third.target]])
    await wrapper.setProps({ runs: [{ ...activeRun, state: 'preparing', completed: 0, total: 8 }] })
    expect(wrapper.find('[role="progressbar"]').exists()).toBe(false)
    expect(wrapper.get('.home-progress-summary').text()).toContain('准备中')
  })

  it('renders real running and preparing tasks in the feature/side composition and emits the exact run identity', async () => {
    const wrapper = mount(HomeView, { props: { runs: [activeRun, preparingRun], items: [saved] } })
    expect(wrapper.findAll('.home-layout > section').map((section) => section.attributes('data-section')))
      .toEqual(['runs', 'recent', 'pending'])
    expect(wrapper.get('.home-run-feature h3').text()).toBe('设备回归')
    expect(wrapper.get('.home-run-count').text()).toBe('2')
    expect(wrapper.get('[role="progressbar"]').attributes('aria-valuenow')).toBe('3')
    expect(wrapper.get('[role="progressbar"]').attributes('aria-valuemax')).toBe('8')
    expect(wrapper.get('.home-progress-percentage').text()).toBe('38%')
    expect(wrapper.get('.home-run-side').text()).toContain('接口回归')
    expect(wrapper.get('.home-run-side').text()).toContain('准备执行环境')
    expect(wrapper.get('.home-result-summary').text()).toContain('非最终结论')
    expect(wrapper.findAll('.home-result-summary [data-result]').map((item) => item.text()))
      .toEqual(['2已通过', '1断言失败', '0执行错误'])
    await wrapper.get('[data-testid="home-open-primary-run"]').trigger('click')
    expect(wrapper.emitted('navigate')).toEqual([[activeRun.target]])
  })

  it('renders explicit loading and unavailable states without deriving fake workbench counts', async () => {
    const wrapper = mount(HomeView, { props: { status: 'loading' } })
    expect(wrapper.get('[data-home-state="loading"]').attributes('role')).toBe('status')
    expect(wrapper.get('[data-home-state="loading"]').attributes('aria-live')).toBe('polite')
    expect(wrapper.find('.home-layout').exists()).toBe(false)
    await wrapper.setProps({ status: 'unavailable' })
    expect(wrapper.get('[data-home-state="unavailable"]').text()).toContain('工作台数据暂不可用')
    expect(wrapper.find('.home-run-count').exists()).toBe(false)
    await wrapper.get('[data-home-state="unavailable"] [data-testid="home-retry"]').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('shows an actionable error state and emits retry without inventing pending work', async () => {
    const wrapper = mount(HomeView, { props: { status: 'error', errorMessage: '工作台加载失败' } })
    const state = wrapper.get('[data-home-state="error"]')
    expect(state.attributes('role')).toBe('alert')
    expect(state.text()).toContain('工作台加载失败')
    await state.get('[data-testid="home-retry"]').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
    expect(wrapper.find('.home-pending-count').exists()).toBe(false)
  })

  it('renders permission denial as a distinct state from an empty ready workbench', () => {
    const wrapper = mount(HomeView, { props: { status: 'forbidden' } })
    expect(wrapper.get('[data-home-state="forbidden"]').text()).toContain('没有权限查看工作台数据')
    expect(wrapper.find('.home-empty').exists()).toBe(false)
  })
})

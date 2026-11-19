// @vitest-environment jsdom
import { config, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import { nextTick } from 'vue'
import GlobalTopbar from './GlobalTopbar.vue'

config.global.plugins.push(ElementPlus)

describe('GlobalTopbar', () => {
  it('keeps a long task visible and recoverable after module navigation', async () => {
    const wrapper = mount(GlobalTopbar, { props: { sidebarCollapsed: false, projectLabel: '订单服务', sectionLabel: '执行报告', searchValue: '', taskStatusLabel: '内容解析 · 2/3' } })
    await wrapper.get('.studio-global-task-status').trigger('click')
    expect(wrapper.emitted('openTask')).toHaveLength(1)
  })
  it('announces task status via a role=status span instead of aria-live on the button', () => {
    const wrapper = mount(GlobalTopbar, {
      props: {
        sidebarCollapsed: false,
        projectLabel: '默认项目',
        sectionLabel: '用例生成',
        searchValue: '',
        taskStatusLabel: '内容解析 · 2/3'
      }
    })

    const button = wrapper.get('.studio-global-task-status')
    expect(button.attributes('aria-live')).toBeUndefined()
    const statusTexts = wrapper.findAll('[role="status"]').map((el) => el.text())
    expect(statusTexts).toContain('内容解析 · 2/3')
  })

  it('emits toggleSidebar from the mobile navigation button', async () => {
    const wrapper = mount(GlobalTopbar, {
      props: {
        sidebarCollapsed: false,
        projectLabel: '默认项目',
        sectionLabel: '用例生成',
        searchValue: ''
      }
    })

    const button = wrapper.get('.studio-global-topbar__mobile-menu')
    expect(button.attributes('aria-label')).toBe('打开主导航')

    await button.trigger('click')
    expect(wrapper.emitted('toggleSidebar')).toHaveLength(1)
  })

  it('shows the breadcrumb and updates the controlled global search value', async () => {
    const wrapper = mount(GlobalTopbar, {
      props: {
        sidebarCollapsed: false,
        projectLabel: '支付平台',
        sectionLabel: '用例库',
        searchValue: '登录'
      }
    })

    expect(wrapper.text()).toContain('支付平台')
    expect(wrapper.text()).toContain('用例库')
    expect(wrapper.get('input').element.value).toBe('登录')
    expect(wrapper.get('input').attributes('aria-label')).toBe('搜索项目、用例或任务')

    await wrapper.get('input').setValue('订单')
    expect(wrapper.emitted('update:searchValue')?.[0]).toEqual(['订单'])
  })

  it('renders the explicit sync state and defaults to synced', () => {
    const defaultState = mount(GlobalTopbar, {
      props: {
        sidebarCollapsed: false,
        projectLabel: '默认项目',
        sectionLabel: '用例生成',
        searchValue: ''
      }
    })
    const syncing = mount(GlobalTopbar, {
      props: {
        sidebarCollapsed: false,
        projectLabel: '默认项目',
        sectionLabel: '用例生成',
        searchValue: '',
        syncState: 'syncing'
      }
    })
    const conflict = mount(GlobalTopbar, {
      props: {
        sidebarCollapsed: false,
        projectLabel: '默认项目',
        sectionLabel: '用例生成',
        searchValue: '',
        syncState: 'conflict'
      }
    })

    expect(defaultState.get('[role="status"]').text()).toBe('已同步')
    expect(syncing.get('[role="status"]').text()).toBe('同步中')
    expect(conflict.get('[role="status"]').text()).toBe('同步冲突')
    expect(conflict.text()).not.toContain('已同步')
  })

  it('shows an honest search capability message when expanded and on Enter', async () => {
    const wrapper = mount(GlobalTopbar, {
      attachTo: document.body,
      props: {
        sidebarCollapsed: false,
        projectLabel: '默认项目',
        sectionLabel: '用例生成',
        searchValue: ''
      }
    })
    const searchToggle = wrapper.get('.studio-global-topbar__search-toggle')

    await searchToggle.trigger('click')

    const input = wrapper.get('input[type="search"]')
    const status = wrapper.get('[data-testid="global-search-status"]')
    expect(input.attributes('aria-describedby')).toBe('studio-global-search-status')
    expect(status.attributes('role')).toBe('status')
    expect(status.text()).toBe('全局搜索暂未接入，请使用各模块筛选')

    await input.trigger('keydown', { key: 'Enter' })
    expect(wrapper.get('[data-testid="global-search-status"]').text()).toBe('全局搜索暂未接入，请使用各模块筛选')

    await input.trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('[data-testid="global-search-status"]').exists()).toBe(false)
    expect(document.activeElement).toBe(searchToggle.element)
  })

  it('opens a local notification notice with close, Escape, outside-click, and mutual exclusion behavior', async () => {
    const wrapper = mount(GlobalTopbar, {
      attachTo: document.body,
      props: {
        sidebarCollapsed: false,
        projectLabel: '默认项目',
        sectionLabel: '用例生成',
        searchValue: '',
        userDisplayName: '测试用户'
      }
    })
    const notificationToggle = wrapper.get('[data-testid="global-notifications-toggle"]')

    expect(notificationToggle.attributes('title')).toBe('通知')
    expect(notificationToggle.attributes('aria-expanded')).toBe('false')
    expect(notificationToggle.attributes('aria-controls')).toBe('studio-global-notifications-panel')

    await notificationToggle.trigger('click')

    const panel = wrapper.get('#studio-global-notifications-panel')
    await nextTick()
    expect(notificationToggle.attributes('aria-expanded')).toBe('true')
    expect(panel.attributes('role')).toBe('dialog')
    expect(panel.text()).toContain('通知数据源尚未接入，暂不展示通知')
    expect(panel.text()).not.toContain('0')
    expect(panel.text()).not.toContain('已读')
    expect(panel.attributes('tabindex')).toBe('-1')
    expect(panel.find('button[aria-label="关闭通知面板"]').exists()).toBe(true)
    expect(document.activeElement).toBe(panel.element)
    expect(panel.get('button[aria-label="关闭通知面板"]').attributes('tabindex')).not.toBe('-1')

    await panel.get('button[aria-label="关闭通知面板"]').trigger('click')
    expect(wrapper.find('#studio-global-notifications-panel').exists()).toBe(false)

    await notificationToggle.trigger('click')
    await nextTick()
    document.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await nextTick()
    expect(wrapper.find('#studio-global-notifications-panel').exists()).toBe(false)

    await notificationToggle.trigger('click')
    await wrapper.get('#studio-global-notifications-panel').trigger('keydown', { key: 'Escape' })
    await nextTick()
    expect(wrapper.find('#studio-global-notifications-panel').exists()).toBe(false)
    expect(document.activeElement).toBe(notificationToggle.element)

    await wrapper.get('.studio-global-topbar__search-toggle').trigger('click')
    await notificationToggle.trigger('click')
    expect(wrapper.find('[data-testid="global-search-status"]').exists()).toBe(false)
    expect(wrapper.find('#studio-global-notifications-panel').exists()).toBe(true)

    await wrapper.get('.studio-assistant-entry').trigger('click')
    expect(wrapper.emitted('openAssistant')).toHaveLength(1)
  })
})

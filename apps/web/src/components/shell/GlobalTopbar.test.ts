// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import GlobalTopbar from './GlobalTopbar.vue'

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

  it('labels and emits the first toggle button from the collapsed state', async () => {
    const expanded = mount(GlobalTopbar, {
      props: {
        sidebarCollapsed: false,
        projectLabel: '默认项目',
        sectionLabel: '用例生成',
        searchValue: ''
      }
    })
    const collapsed = mount(GlobalTopbar, {
      props: {
        sidebarCollapsed: true,
        projectLabel: '默认项目',
        sectionLabel: '用例生成',
        searchValue: ''
      }
    })

    expect(expanded.find('button').attributes('aria-label')).toBe('收起侧边栏')
    expect(collapsed.find('button').attributes('aria-label')).toBe('展开侧边栏')

    await expanded.find('button').trigger('click')
    expect(expanded.emitted('toggleSidebar')).toHaveLength(1)
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
})

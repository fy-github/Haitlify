// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import PrimarySidebar from './PrimarySidebar.vue'
import { studioSidebarGroups } from '../../ui/navigation/appSidebar'

describe('PrimarySidebar', () => {
  it('opens local help through an enabled control without selecting a business page', async () => {
    const wrapper = mount(PrimarySidebar, {
      props: { groups: studioSidebarGroups, activeItemId: 'case-library', collapsed: false }
    })
    try {
      const help = wrapper.get('button[aria-label="帮助文档"]')
      expect(help.attributes('disabled')).toBeUndefined()
      expect(help.attributes('title')).toBe('帮助文档')
      await help.trigger('click')
      expect(wrapper.emitted('help')).toEqual([[]])
      expect(wrapper.emitted('select')).toBeUndefined()
    } finally { wrapper.unmount() }
  })

  it('shows the active workbench group, badges, and the current page', () => {
    const wrapper = mount(PrimarySidebar, {
      props: {
        groups: studioSidebarGroups,
        activeItemId: 'case-library',
        collapsed: false,
        badges: { 'case-library': 12 }
      }
    })

    expect(wrapper.get('.studio-sidebar__group-label').text()).toBe('用例工作台')
    expect(wrapper.get('button[aria-label="接口用例"]').attributes('aria-current')).toBe('page')
    expect(wrapper.get('button[aria-label="接口用例"] .studio-sidebar__badge').text()).toBe('12')
    expect(wrapper.get('button[aria-label="Web 用例"]').attributes('aria-current')).toBeUndefined()
  })

  it('renders only the sub-functions of the active workbench', () => {
    const wrapper = mount(PrimarySidebar, {
      props: {
        groups: studioSidebarGroups,
        activeItemId: 'case-library',
        collapsed: false
      }
    })

    expect(wrapper.find('button[aria-label="执行报告"]').exists()).toBe(false)
    expect(wrapper.find('button[aria-label="Jira 配置"]').exists()).toBe(false)

    const settingsWrapper = mount(PrimarySidebar, {
      props: {
        groups: studioSidebarGroups,
        activeItemId: 'jira-config',
        collapsed: false
      }
    })

    expect(settingsWrapper.get('.studio-sidebar__group-label').text()).toBe('设置中心')
    expect(settingsWrapper.get('button[aria-label="Jira 配置"]').attributes('aria-current')).toBe('page')
    expect(settingsWrapper.find('button[aria-label="接口用例"]').exists()).toBe(false)
    expect(settingsWrapper.find('button[aria-label="Web 用例"]').exists()).toBe(false)
  })

  it('keeps a docked settings shortcut in every workbench', () => {
    const wrapper = mount(PrimarySidebar, {
      props: {
        groups: studioSidebarGroups,
        activeItemId: 'api-automation',
        collapsed: false
      }
    })

    const tool = wrapper.get('.studio-sidebar__tools button[aria-label="设置中心"]')
    expect(tool.text()).toBe('设置中心')

    const settingsWrapper = mount(PrimarySidebar, {
      props: {
        groups: studioSidebarGroups,
        activeItemId: 'templates',
        collapsed: false
      }
    })

    expect(settingsWrapper.get('.studio-sidebar__tools button[aria-label="设置中心"]').text()).toBe('设置中心')
  })

  it('emits the selected Web automation item', async () => {
    const wrapper = mount(PrimarySidebar, {
      props: {
        groups: studioSidebarGroups,
        activeItemId: 'api-automation',
        collapsed: false
      }
    })

    await wrapper.get('button[aria-label="Web 自动化"]').trigger('click')

    expect(wrapper.emitted('select')?.[0]).toEqual([
      expect.objectContaining({ id: 'web-automation', label: 'Web 自动化', navKey: 'automation' })
    ])
  })

  it('applies accent metadata to item icons', () => {
    const wrapper = mount(PrimarySidebar, {
      props: {
        groups: studioSidebarGroups,
        activeItemId: 'api-automation',
        collapsed: false
      }
    })

    expect(wrapper.get('button[aria-label="API 自动化"] .studio-sidebar__icon').classes()).toContain('studio-sidebar__icon--cyan')

    const qualityWrapper = mount(PrimarySidebar, {
      props: {
        groups: studioSidebarGroups,
        activeItemId: 'reports',
        collapsed: false
      }
    })

    expect(qualityWrapper.get('button[aria-label="执行报告"] .studio-sidebar__icon').classes()).toContain('studio-sidebar__icon--amber')
  })

  it('does not render stage numbers in the menu labels', () => {
    const wrapper = mount(PrimarySidebar, {
      props: {
        groups: studioSidebarGroups,
        activeItemId: 'case-library',
        collapsed: false
      }
    })

    expect(wrapper.get('button[aria-label="接口用例"]').text()).toBe('接口用例')
    expect(wrapper.get('button[aria-label="Web 用例"]').text()).toBe('Web 用例')
    expect(wrapper.find('.studio-sidebar__stage-mark').exists()).toBe(false)
  })
})

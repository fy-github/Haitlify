// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import PrimarySidebar from './PrimarySidebar.vue'
import { studioSidebarGroups } from '../../ui/navigation/appSidebar'

describe('PrimarySidebar', () => {
  it('shows labels, badges, and the current page when expanded', () => {
    const wrapper = mount(PrimarySidebar, {
      props: {
        groups: studioSidebarGroups,
        activeItemId: 'case-library',
        collapsed: false,
        badges: { 'case-library': 12 }
      }
    })

    expect(wrapper.get('.studio-sidebar__group-label').text()).toBe('首页')
    expect(wrapper.get('button[aria-label="用例库"]').attributes('aria-current')).toBe('page')
    expect(wrapper.get('button[aria-label="用例库"] .studio-sidebar__badge').text()).toBe('12')
    expect(wrapper.find('[aria-label="Web 用例"]').exists()).toBe(false)
  })

  it('expands case-design children and emits the selected subpage', async () => {
    const wrapper = mount(PrimarySidebar, {
      props: {
        groups: studioSidebarGroups,
        activeItemId: 'case-library',
        collapsed: false
      }
    })

    const parent = wrapper.get('button[aria-label="智能用例设计"]')
    expect(parent.attributes('aria-expanded')).toBe('false')

    await parent.trigger('click')
    expect(parent.attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('[role="group"][aria-label="智能用例设计子菜单"]')).toBeTruthy()

    await wrapper.get('button[aria-label="接口用例"]').trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual([
      expect.objectContaining({ id: 'case-design-interface', navKey: 'api', caseDesignKind: 'interface' })
    ])
  })

  it('keeps collapsed navigation accessible and requests the full rail before revealing children', async () => {
    const wrapper = mount(PrimarySidebar, {
      props: {
        groups: studioSidebarGroups,
        activeItemId: 'case-design-web',
        collapsed: true
      }
    })

    const button = wrapper.get('button[aria-label="智能用例设计"]')
    expect(button.attributes('title')).toBe('智能用例设计')
    expect(wrapper.find('.studio-sidebar__label').exists()).toBe(false)
    expect(wrapper.find('[aria-label="Web 用例"]').exists()).toBe(false)

    await button.trigger('click')
    expect(wrapper.emitted('requestExpand')).toEqual([[]])
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

  it('applies accent metadata to item and subitem icons', async () => {
    const wrapper = mount(PrimarySidebar, {
      props: {
        groups: studioSidebarGroups,
        activeItemId: 'api-automation',
        collapsed: false
      }
    })

    expect(wrapper.get('button[aria-label="API 自动化"] .studio-sidebar__icon').classes()).toContain('studio-sidebar__icon--cyan')
    expect(wrapper.get('button[aria-label="执行报告"] .studio-sidebar__icon').classes()).toContain('studio-sidebar__icon--amber')
    expect(wrapper.get('button[aria-label="AI 设置"] .studio-sidebar__icon').classes()).toContain('studio-sidebar__icon--info')

    await wrapper.get('button[aria-label="智能用例设计"]').trigger('click')
    expect(wrapper.get('button[aria-label="接口用例"] .studio-sidebar__subitem-icon').classes()).toContain('studio-sidebar__subitem-icon--teal')
  })

  it('does not render stage numbers in the menu labels', () => {
    const wrapper = mount(PrimarySidebar, {
      props: {
        groups: studioSidebarGroups,
        activeItemId: 'case-library',
        collapsed: false
      }
    })

    for (const label of ['用例库', '执行报告', 'AI 设置']) {
      expect(wrapper.get(`button[aria-label="${label}"]`).text()).toBe(label)
      expect(wrapper.find(`button[aria-label="${label}"] .studio-sidebar__stage-mark`).exists()).toBe(false)
    }
    expect(wrapper.find('.studio-sidebar__stage-mark').exists()).toBe(false)
  })
})

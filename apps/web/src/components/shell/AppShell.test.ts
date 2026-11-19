// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AppShell from './AppShell.vue'

describe('AppShell', () => {
  it('renders the shell slots in a stable topbar, sidebar, canvas, inspector order', () => {
    const wrapper = mount(AppShell, {
      props: {
        sidebarCollapsed: true,
        inspectorVisible: true
      },
      slots: {
        sidebar: '<aside data-slot="sidebar">sidebar</aside>',
        topbar: '<header data-slot="topbar">topbar</header>',
        default: '<main data-slot="canvas">canvas</main>',
        inspector: '<aside data-slot="inspector">inspector</aside>'
      }
    })

    expect(wrapper.classes()).toEqual(expect.arrayContaining([
      'studio-app-shell',
      'is-sidebar-collapsed',
      'has-inspector'
    ]))
    // 设计稿把顶栏放在左侧工具栏之上横跨整个外壳,因此 topbar 是外壳首个子区域
    expect(wrapper.findAll('[data-slot]').map((node) => node.attributes('data-slot'))).toEqual([
      'topbar',
      'sidebar',
      'canvas',
      'inspector'
    ])
    expect(wrapper.classes()).not.toContain('has-context')
    expect(wrapper.find('.studio-app-shell__context').exists()).toBe(false)
  })

  it('omits optional regions while keeping the default canvas slot', () => {
    const wrapper = mount(AppShell, {
      props: {
        sidebarCollapsed: false,
        inspectorVisible: false
      },
      slots: {
        sidebar: '<aside data-slot="sidebar" />',
        topbar: '<header data-slot="topbar" />',
        default: '<main data-slot="canvas" />',
        inspector: '<aside data-slot="inspector" />'
      }
    })

    expect(wrapper.classes()).not.toContain('has-context')
    expect(wrapper.classes()).not.toContain('has-inspector')
    expect(wrapper.find('[data-slot="context"]').exists()).toBe(false)
    expect(wrapper.find('[data-slot="canvas"]').exists()).toBe(true)
    expect(wrapper.find('[data-slot="inspector"]').exists()).toBe(false)
  })

  it('removes the sidebar region entirely on narrow viewports', () => {
    const wrapper = mount(AppShell, {
      props: {
        sidebarCollapsed: true,
        sidebarVisible: false,
        inspectorVisible: false
      },
      slots: {
        sidebar: '<aside data-slot="sidebar" />',
        topbar: '<header data-slot="topbar" />',
        default: '<main data-slot="canvas" />'
      }
    })

    expect(wrapper.find('.studio-app-shell__sidebar').exists()).toBe(false)
    expect(wrapper.find('[data-slot="sidebar"]').exists()).toBe(false)
    expect(wrapper.find('[data-slot="canvas"]').exists()).toBe(true)
  })
})

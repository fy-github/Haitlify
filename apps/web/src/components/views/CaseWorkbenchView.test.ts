// @vitest-environment jsdom
// 用例工作台壳:内部分组导航已去掉(与主侧边栏重复),用例库/画布槽按 activeNav 切换;
// 子类切换(interface/web_automation)由父级 selectSidebarItem 统一处理,generationKind 仅透传。
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import type { NavKey } from '../../domain/appNavigation'

interface Props {
  activeNav: NavKey
}

const makeProps = (overrides: Partial<Props> = {}): Props => ({
  activeNav: 'cases',
  ...overrides
})

const mountView = async (props: Props) => {
  vi.resetModules()
  const { default: Component } = await import('./CaseWorkbenchView.vue')
  return mount(Component, {
    props,
    slots: {
      library: '<div data-testid="library-content">用例库内容</div>',
      canvas: '<div data-testid="canvas-content">画布内容</div>'
    },
    global: { plugins: [ElementPlus] }
  })
}

describe('CaseWorkbenchView', () => {
  it('renders the library slot inside the standard pane for the cases nav', async () => {
    const wrapper = await mountView(makeProps({ activeNav: 'cases' }))
    expect(wrapper.find('[data-testid="library-content"]').exists()).toBe(true)
    expect(wrapper.find('.workbench__pane').exists()).toBe(true)
    expect(wrapper.find('[data-testid="canvas-content"]').exists()).toBe(false)
  })

  it('renders the canvas slot inside the full-height pane for the api nav', async () => {
    const wrapper = await mountView(makeProps({ activeNav: 'api' }))
    expect(wrapper.find('[data-testid="canvas-content"]').exists()).toBe(true)
    expect(wrapper.find('.workbench__pane--full').exists()).toBe(true)
    expect(wrapper.find('[data-testid="library-content"]').exists()).toBe(false)
  })
})

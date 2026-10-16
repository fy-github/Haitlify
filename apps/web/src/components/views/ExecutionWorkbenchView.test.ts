// @vitest-environment jsdom
// 执行工作台壳:内部分组导航已去掉(与主侧边栏重复),靠 automationKind 切换 API/Web 内容槽;
// 分组切换全部走主侧边栏 selectSidebarItem,automationKind 由父级传入。
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import type { NavKey } from '../../domain/appNavigation'

interface Props {
  activeNav: NavKey
  automationKind?: 'api' | 'web'
}

const makeProps = (overrides: Partial<Props> = {}): Props => ({
  activeNav: 'automation',
  automationKind: 'api',
  ...overrides
})

const mountView = async (props: Props) => {
  vi.resetModules()
  const { default: Component } = await import('./ExecutionWorkbenchView.vue')
  return mount(Component, {
    props,
    slots: {
      api: '<div data-testid="api-pane-content">API 面板</div>',
      web: '<div data-testid="web-pane-content">Web 面板</div>'
    },
    global: { plugins: [ElementPlus] }
  })
}

describe('ExecutionWorkbenchView', () => {
  it('renders the API slot inside the standard pane for the api kind', async () => {
    const wrapper = await mountView(makeProps({ automationKind: 'api' }))
    expect(wrapper.find('[data-testid="api-pane-content"]').exists()).toBe(true)
    expect(wrapper.find('.workbench__pane--api').exists()).toBe(true)
    expect(wrapper.find('[data-testid="web-pane-content"]').exists()).toBe(false)
  })

  it('renders the Web slot inside the full-height pane for the web kind', async () => {
    const wrapper = await mountView(makeProps({ automationKind: 'web' }))
    expect(wrapper.find('[data-testid="web-pane-content"]').exists()).toBe(true)
    expect(wrapper.find('.workbench__pane--full').exists()).toBe(true)
    expect(wrapper.find('[data-testid="api-pane-content"]').exists()).toBe(false)
  })
})

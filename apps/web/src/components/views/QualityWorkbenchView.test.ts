// @vitest-environment jsdom
// 质量工作台壳:内部分组导航已去掉(与主侧边栏重复),按 activeNav 分节渲染报告/缺陷两视图;
// 分组切换全部走主侧边栏 selectSidebarItem,activeNav 由父级传入。
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import type { NavKey } from '../../domain/appNavigation'

interface Props {
  activeNav: NavKey
}

const makeProps = (overrides: Partial<Props> = {}): Props => ({
  activeNav: 'reports',
  ...overrides
})

const mountView = async (props: Props) => {
  vi.resetModules()
  const { default: Component } = await import('./QualityWorkbenchView.vue')
  return mount(Component, { props, global: { plugins: [ElementPlus] } })
}

describe('QualityWorkbenchView', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('renders the reports section from the quality nav', async () => {
    const wrapper = await mountView(makeProps({ activeNav: 'reports' }))
    expect(wrapper.find('h1').text()).toContain('执行报告')
    expect(wrapper.find('.workbench__pane').exists()).toBe(true)
  })

  it('renders the defects section from the quality nav', async () => {
    const wrapper = await mountView(makeProps({ activeNav: 'defects' }))
    expect(wrapper.find('h1').text()).toContain('缺陷')
    expect(wrapper.find('.workbench__pane').exists()).toBe(true)
  })

  it('renders a placeholder for nav keys outside the quality group', async () => {
    const wrapper = await mountView(makeProps({ activeNav: 'cases' }))
    expect(wrapper.find('.workbench__placeholder').exists()).toBe(true)
    expect(wrapper.find('.workbench__pane--full').exists()).toBe(true)
  })
})

// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it } from 'vitest'
import GlobalTopbar from './GlobalTopbar.vue'

const mounted: ReturnType<typeof mount>[] = []
const render = () => {
  const wrapper = mount(GlobalTopbar, {
    attachTo: document.body,
    props: { sidebarCollapsed: false, projectLabel: 'fixture', sectionLabel: 'controls', searchValue: '', userDisplayName: 'UI 测试账号' },
    global: { plugins: [ElementPlus] }
  })
  mounted.push(wrapper)
  return wrapper
}
afterEach(() => { mounted.splice(0).forEach((wrapper) => wrapper.unmount()); document.body.innerHTML = '' })

describe('GlobalTopbar control closeout', () => {
  it('keeps the visual tools in the same AI → search → notification → account keyboard order', () => {
    const wrapper = render()
    const buttons = wrapper.get('.studio-topbar__tools').findAll('button')
    expect(buttons.map((button) => button.attributes('aria-label') || button.text())).toEqual(['AI 助手', '搜索', '通知', '当前用户 UI 测试账号'])
  })

  it('does not put the closed search input into the keyboard sequence', async () => {
    const wrapper = render()
    expect(wrapper.get('input[type="search"]').attributes('tabindex')).toBe('-1')
    await wrapper.get('.studio-global-topbar__search-toggle').trigger('click')
    expect(wrapper.get('.studio-global-topbar__search-toggle').attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('input[type="search"]').attributes('tabindex')).toBe('0')
    await wrapper.get('input[type="search"]').trigger('keydown', { key: 'Escape' })
    expect(wrapper.get('.studio-global-topbar__search-toggle').attributes('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(wrapper.get('.studio-global-topbar__search-toggle').element)
  })

  it('closes the expanded search on outside pointer without losing controlled value', async () => {
    const wrapper = render()
    await wrapper.get('.studio-global-topbar__search-toggle').trigger('click')
    await wrapper.get('input[type="search"]').setValue('保留关键词')
    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await wrapper.vm.$nextTick()
    expect(wrapper.get('.studio-global-topbar__search').classes()).not.toContain('is-open')
    expect(wrapper.emitted('update:searchValue')).toEqual([['保留关键词']])
  })

  it('uses an account-only teleported popper and preserves both existing account emits', async () => {
    const wrapper = render()
    const dropdown = wrapper.findComponent({ name: 'ElDropdown' })
    expect(dropdown.props('popperClass')).toBe('studio-account-popper')
    expect(dropdown.props('placement')).toBe('bottom-end')
    dropdown.vm.$emit('command', 'change-password')
    dropdown.vm.$emit('command', 'logout')
    dropdown.vm.$emit('command', 'invented-profile-action')
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('change-password')).toEqual([[]])
    expect(wrapper.emitted('logout')).toEqual([[]])
  })
})

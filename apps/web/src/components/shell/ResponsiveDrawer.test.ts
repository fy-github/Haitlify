// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ResponsiveDrawer from './ResponsiveDrawer.vue'

describe('shared drawer retained after legacy workflow retirement', () => {
  afterEach(() => { document.body.innerHTML = '' })

  it('keeps closed content out of the page and opens the retained editor on demand', async () => {
    const wrapper = mount(ResponsiveDrawer, {
      props: { modelValue: false, title: '编辑用例' },
      slots: { default: '<div data-testid="retained-editor">用例编辑内容</div>' },
      global: { plugins: [ElementPlus] }
    })
    try {
      expect(document.querySelector('[data-testid="retained-editor"]')).toBeNull()
      await wrapper.setProps({ modelValue: true })
      await flushPromises()
      expect(document.querySelector('[data-testid="retained-editor"]')?.textContent).toBe('用例编辑内容')
      const close = document.querySelector('button[aria-label="关闭编辑用例"]') as HTMLButtonElement
      close.click()
      await flushPromises()
      expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
    } finally { wrapper.unmount() }
  })
})

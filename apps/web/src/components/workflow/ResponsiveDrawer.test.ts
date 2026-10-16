// @vitest-environment jsdom
import { defineComponent } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ResponsiveDrawer from './ResponsiveDrawer.vue'

const DrawerStub = defineComponent({ props: ['modelValue', 'title'], emits: ['update:modelValue'], template: '<section role="dialog" :aria-label="title"><slot/><button data-close @click="$emit(\'update:modelValue\', false)">关闭</button></section>' })

describe('ResponsiveDrawer', () => {
  it('provides a labelled dialog and forwards close state', async () => {
    const wrapper = mount(ResponsiveDrawer, {
      props: { modelValue: true, title: 'AI 质量评审', description: '配置修改后立即生效' },
      slots: { default: '<button>评审动作</button>' },
      global: { stubs: { ElDrawer: DrawerStub } }
    })
    expect(wrapper.get('[role="dialog"]').attributes('aria-label')).toBe('AI 质量评审')
    expect(wrapper.text()).toContain('配置修改后立即生效')
    await wrapper.get('[data-close]').trigger('click')
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
  })
})

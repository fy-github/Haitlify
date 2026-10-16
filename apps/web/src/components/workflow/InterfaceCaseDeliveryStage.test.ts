// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import InterfaceCaseDeliveryStage from './InterfaceCaseDeliveryStage.vue'

describe('InterfaceCaseDeliveryStage', () => {
  it('keeps saving and delivery available before AI review', async () => {
    const wrapper = mount(InterfaceCaseDeliveryStage, {
      props: {
        generatedCount: 12,
        reviewAttempted: false,
        reviewFindingCount: 0,
        saving: false
      }
    })

    expect(wrapper.text()).toContain('12 条接口用例待交付')
    expect(wrapper.text()).toContain('尚未运行 AI 评审，仍可保存或交付')
    expect(wrapper.get('[data-testid="interface-save-action"]').attributes('disabled')).toBeUndefined()

    await wrapper.get('[data-testid="interface-save-action"]').trigger('click')
    await wrapper.get('[data-testid="interface-transfer-action"]').trigger('click')
    await wrapper.get('[data-testid="interface-export-action"]').trigger('click')

    expect(wrapper.emitted('save')).toHaveLength(1)
    expect(wrapper.emitted('transfer')).toHaveLength(1)
    expect(wrapper.emitted('export')).toHaveLength(1)
  })

  it('reports completed review findings and disables save only while saving', () => {
    const wrapper = mount(InterfaceCaseDeliveryStage, {
      props: {
        generatedCount: 12,
        reviewAttempted: true,
        reviewFindingCount: 3,
        saving: true
      }
    })

    expect(wrapper.text()).toContain('AI 评审发现 3 项待处理内容')
    expect(wrapper.text()).not.toContain('尚未运行 AI 评审')
    expect(wrapper.get('[data-testid="interface-save-action"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="interface-save-action"]').text()).toContain('保存中')
  })
})

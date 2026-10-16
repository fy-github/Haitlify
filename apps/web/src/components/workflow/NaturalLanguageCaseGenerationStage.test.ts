// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import NaturalLanguageCaseGenerationStage from './NaturalLanguageCaseGenerationStage.vue'

describe('NaturalLanguageCaseGenerationStage', () => {
  const baseProps = {
    progressPercentage: 42,
    progressText: '正在生成自然语言用例',
    errorMessage: '',
    cases: [],
    selectedIds: []
  }

  it('shows running status and hides idle status wiring', async () => {
    const wrapper = mount(NaturalLanguageCaseGenerationStage, {
      props: { ...baseProps, status: 'running' },
      global: { plugins: [ElementPlus] }
    })

    expect(wrapper.find('.studio-natural-case-stage').classes()).toContain('has-status')
    expect(wrapper.get('.studio-natural-case-stage__status').text()).toContain('正在生成自然语言用例')
    expect(wrapper.find('.studio-natural-case-stage__status').classes()).toContain('is-running')
  })

  it('keeps first generation clickable when points are confirmed but no cases exist yet', () => {
    // 回归护栏（用户实测）：按钮曾以 !cases.length 禁用——用例数为 0 时首次生成永远点不动，
    // 页面卡在空态死锁。可点击性必须由「可生成的点数」决定。
    const wrapper = mount(NaturalLanguageCaseGenerationStage, {
      props: { ...baseProps, status: 'idle', generatablePointCount: 3 },
      global: { plugins: [ElementPlus] }
    })

    const button = wrapper.get('.studio-natural-case-stage__toolbar button')
    expect(button.text()).toContain('生成自然语言用例')
    expect(button.attributes('disabled')).toBeUndefined()
  })

  it('disables generation when no confirmed point is available', () => {
    const wrapper = mount(NaturalLanguageCaseGenerationStage, {
      props: { ...baseProps, status: 'idle', generatablePointCount: 0 },
      global: { plugins: [ElementPlus] }
    })

    const button = wrapper.get('.studio-natural-case-stage__toolbar button')
    expect(button.attributes('disabled')).toBeDefined()
  })

  it('uses the error message when the stage failed', async () => {
    const wrapper = mount(NaturalLanguageCaseGenerationStage, {
      props: { ...baseProps, status: 'failed', errorMessage: '生成失败' },
      global: { plugins: [ElementPlus] }
    })

    expect(wrapper.find('.studio-natural-case-stage').classes()).toContain('has-status')
    expect(wrapper.get('.studio-natural-case-stage__status').text()).toContain('生成失败')
    expect(wrapper.find('.studio-natural-case-stage__status').classes()).toContain('is-failed')
  })
})

// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import appSource from '../../App.vue?raw'
import AutomationCompileStage from './AutomationCompileStage.vue'
import naturalLanguageStageSource from './NaturalLanguageCaseGenerationStage.vue?raw'
import testPointStageSource from './TestPointParseStage.vue?raw'

describe('AutomationCompileStage', () => {
  it('offers Web environment configuration from the compile page', async () => {
    const wrapper = mount(AutomationCompileStage, {
      props: {
        status: 'idle',
        progressText: '等待编译',
        cases: [],
        selectedIds: [],
        events: []
      },
      global: { plugins: [ElementPlus] }
    })

    await wrapper.get('[data-testid="configure-web-environment"]').trigger('click')
    expect(wrapper.emitted('configureEnvironment')).toHaveLength(1)
  })

  it('uses the review-style table shell and a single-line compile description', () => {
    const wrapper = mount(AutomationCompileStage, {
      props: {
        status: 'idle',
        progressText: '等待编译',
        cases: [],
        selectedIds: [],
        events: []
      },
      global: { plugins: [ElementPlus] }
    })

    expect(wrapper.text()).not.toContain('根据自然语言步骤定位页面元素')
    expect(wrapper.find('.studio-automation-compile-stage__summary').exists()).toBe(false)
    expect(appSource).toContain("if (caseDesignWorkflow.value.activeStage === 'automation-compile')")
    expect(appSource).toContain('根据自然语言步骤定位页面元素，生成自动化步骤、元素断言和执行预期')
    expect(testPointStageSource).toContain('height: calc(100% - 20px)')
    expect(testPointStageSource).toContain('border-radius: 8px')
    expect(naturalLanguageStageSource).toContain('height: calc(100% - 20px)')
    expect(naturalLanguageStageSource).toContain('border-radius: 8px')
  })
})

// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import WebCaseQualityRatingDialog from './WebCaseQualityRatingDialog.vue'
import type { WebCaseAgentRun } from '../../domain/webCaseAgentRepository'

const run: WebCaseAgentRun = {
  runId: 'run-1',
  projectId: 1,
  status: 'succeeded',
  currentStage: 'DELIVERY',
  requestedPointIds: [],
  completedPointIds: [],
  blockedPointIds: []
}

describe('WebCaseQualityRatingDialog', () => {
  it('renders overall score and six quality dimensions', async () => {
    const wrapper = mount(WebCaseQualityRatingDialog, {
      props: { run, visible: true },
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('总体评分')
    expect(wrapper.text()).toContain('覆盖度')
    expect(wrapper.text()).toContain('来源可追溯')
    expect(wrapper.text()).toContain('可测试性')
    expect(wrapper.text()).toContain('去重质量')
    expect(wrapper.text()).toContain('自动化就绪')
    expect(wrapper.text()).toContain('评审把握')
  })
})

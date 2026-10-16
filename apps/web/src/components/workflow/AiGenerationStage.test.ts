// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import AiGenerationStage from './AiGenerationStage.vue'

const mountStage = (props: InstanceType<typeof AiGenerationStage>['$props']) => mount(AiGenerationStage, {
  props,
  global: { plugins: [ElementPlus] },
  slots: { drafts: '<div>确认测试点</div>', results: '<div>生成结果</div>' }
})

describe('AiGenerationStage', () => {
  it('disables generation without a selected confirmed point', () => {
    const wrapper = mountStage({ status: 'idle', progressPercentage: 0, progressText: '等待生成', errorMessage: '', selectedDraftCount: 0, totalDraftCount: 2, generatedCaseCount: 0 })
    expect(wrapper.get('[data-testid="generation-start"]').attributes('disabled')).toBeDefined()
  })

  it('enables confirmation and generation for parsed points awaiting confirmation', () => {
    const wrapper = mountStage({ status: 'idle', progressPercentage: 0, progressText: '等待生成', errorMessage: '', selectedDraftCount: 0, totalDraftCount: 0, pendingDraftCount: 3, generatedCaseCount: 0 })
    expect(wrapper.get('[data-testid="generation-start"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.get('[data-testid="generation-start"]').text()).toContain('确认并生成用例')
    expect(wrapper.text()).toContain('阶段 1 · 测试设计')
    expect(wrapper.text()).toContain('阶段 2 · 自动化落地')
    expect(wrapper.text()).toContain('3 个待确认')
  })

  it('renders running progress and partial results together', () => {
    const wrapper = mountStage({ status: 'running', progressPercentage: 50, progressText: '已完成 1 / 2 批', errorMessage: '', selectedDraftCount: 12, totalDraftCount: 12, generatedCaseCount: 8 })
    expect(wrapper.text()).toContain('已完成 1 / 2 批')
    expect(wrapper.text()).toContain('生成结果')
    expect(wrapper.text()).toContain('8')
  })

  it('explains the visible agent workspace when agent events are shown', () => {
    const wrapper = mountStage({ status: 'running', progressPercentage: 50, progressText: '正在生成', errorMessage: '', selectedDraftCount: 12, totalDraftCount: 12, generatedCaseCount: 8, agentVisible: true })
    expect(wrapper.text()).toContain('规划、素材命中、取证、编译和阻塞原因')
  })

  it('offers an explicit cancellation action while generation is running', async () => {
    const wrapper = mountStage({ status: 'running', progressPercentage: 50, progressText: '正在生成', errorMessage: '', selectedDraftCount: 12, totalDraftCount: 12, generatedCaseCount: 3 })
    await wrapper.get('[data-testid="generation-cancel"]').trigger('click')
    expect(wrapper.emitted('cancel')).toHaveLength(1)
    expect(wrapper.get('[data-testid="generation-cancel"]').text()).toContain('终止生成')
  })

  it('makes a repeat generation action explicit once results exist', () => {
    const wrapper = mountStage({ status: 'succeeded', progressPercentage: 100, progressText: '已完成', errorMessage: '', selectedDraftCount: 2, totalDraftCount: 2, generatedCaseCount: 3 })
    expect(wrapper.get('[data-testid="generation-start"]').text()).toContain('重新生成用例')
  })

  it('emits retry after a partial failure', async () => {
    const wrapper = mountStage({ status: 'failed', progressPercentage: 50, progressText: '第 2 批未完成', errorMessage: '服务暂不可用', selectedDraftCount: 2, totalDraftCount: 2, generatedCaseCount: 5, retryablePointCount: 2 })
    await wrapper.get('[data-testid="generation-retry"]').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
    expect(wrapper.text()).toContain('服务暂不可用')
  })
})

// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import WorkflowHeader from './WorkflowHeader.vue'

const stages = [
  { key: 'requirement' as const, label: '需求解析', enabled: true, status: 'done' as const },
  { key: 'test-parse' as const, label: '测试解析', enabled: true, status: 'done' as const },
  { key: 'case-generate' as const, label: '用例生成', enabled: true, status: 'active' as const },
  { key: 'review' as const, label: '质量评审', enabled: false, status: 'pending' as const },
  { key: 'automation-compile' as const, label: '自动化编译', enabled: false, status: 'pending' as const },
  { key: 'deliver' as const, label: '保存执行', enabled: false, status: 'pending' as const }
]

const mountHeader = (props: InstanceType<typeof WorkflowHeader>['$props']) => mount(WorkflowHeader, {
  props,
  global: { plugins: [ElementPlus] }
})

describe('WorkflowHeader', () => {
  it('emits navigation only for enabled stages', async () => {
    const wrapper = mountHeader({
      title: '订单退款需求',
      saveLabel: '自动保存 · 刚刚',
      stages,
      primaryAction: { label: '生成用例', disabled: false, loading: false }
    })

    await wrapper.get('button[data-stage="test-parse"]').trigger('click')
    await wrapper.get('button[data-stage="review"]').trigger('click')
    expect(wrapper.emitted('navigateStage')).toEqual([['test-parse']])
  })

  it('emits the single primary action', async () => {
    const wrapper = mountHeader({
      title: '订单退款需求',
      saveLabel: '自动保存 · 刚刚',
      stages,
      primaryAction: { label: '生成用例', disabled: false, loading: false }
    })

    await wrapper.get('[data-testid="workflow-primary-action"]').trigger('click')
    expect(wrapper.emitted('primary')).toHaveLength(1)
  })

  it('keeps an optional draft command visually secondary', async () => {
    const wrapper = mountHeader({
      title: '订单退款需求',
      saveLabel: '自动保存 · 刚刚',
      stages,
      primaryAction: { label: '生成用例', disabled: false, loading: false },
      secondaryAction: { label: '新建任务', disabled: false }
    })
    await wrapper.get('[data-testid="workflow-secondary-action"]').trigger('click')
    expect(wrapper.emitted('secondary')).toHaveLength(1)
  })

  it('supports the four-stage interface workflow with structural stage keys', async () => {
    const wrapper = mountHeader({
      title: '接口用例',
      saveLabel: '自动保存 · 刚刚',
      stages: [
        { key: 'import', label: '导入接口', enabled: true, status: 'done' },
        { key: 'generate', label: '配置生成', enabled: true, status: 'done' },
        { key: 'review', label: 'AI 评审', enabled: true, status: 'active' },
        { key: 'deliver', label: '保存 / 交付', enabled: true, status: 'pending' }
      ],
      primaryAction: { label: '继续交付', disabled: false, loading: false }
    })

    const buttons = wrapper.findAll('.studio-workflow-step')
    const reviewButton = wrapper.get('button[data-stage="review"]')

    expect(buttons).toHaveLength(4)
    expect(reviewButton.attributes('aria-label')).toBe('AI 评审，当前阶段')
    expect(reviewButton.attributes('aria-current')).toBe('step')
    await reviewButton.trigger('click')
    expect(wrapper.emitted('navigateStage')).toEqual([['review']])
  })
})

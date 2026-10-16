// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import AiReviewInspector from './AiReviewInspector.vue'
import inspectorSource from './AiReviewInspector.vue?raw'

const findings = [
  { findingId: 'coverage_gap:gap-refund-timeout', category: 'coverage_gap' as const, title: '退款超时路径缺少异常回补用例', detail: '覆盖退款状态最终一致性', priority: 'P0', decision: 'pending' as const },
  { findingId: 'suggestion:suggestion-consistency', category: 'suggestion' as const, title: '补充最终一致性检查', detail: '校验订单和退款单状态', priority: 'P1', decision: 'accepted' as const }
]

const props = () => ({
  selectedCount: 2,
  highRiskCount: 2, pendingFindingCount: 1, averageScore: 92, summary: '存在 2 项待确认', findings,
  conflicts: [{ conflictId: 'c1', conflictType: 'timeout', targetKey: 'TC-003', conflictJson: { values: ['P0', 'P1'] }, resolutionStatus: 'unresolved' }], loading: false,
  canMerge: true
})

describe('AiReviewInspector', () => {
  it('shows the selected case and contextual review content', () => {
    const wrapper = mount(AiReviewInspector, { props: props(), global: { plugins: [ElementPlus] } })
    expect(wrapper.text()).not.toContain('当前用例')
    expect(wrapper.text()).not.toContain('退款处理超时后的状态回补')
    expect(wrapper.text()).toContain('92%')
    expect(wrapper.text()).toContain('退款超时路径缺少异常回补用例')
    expect(wrapper.text()).not.toContain('补充最终一致性检查')
  })

  it('emits decision actions without exposing a redundant refresh control', async () => {
    const wrapper = mount(AiReviewInspector, { props: props(), global: { plugins: [ElementPlus] } })
    expect(wrapper.find('[data-testid="refresh-review"]').exists()).toBe(false)
    await wrapper.get('[data-testid="accept-finding-coverage_gap:gap-refund-timeout"]').trigger('click')
    await wrapper.get('[data-testid="ignore-conflict-c1"]').trigger('click')
    await wrapper.get('[data-testid="bulk-pending"]').trigger('click')
    await wrapper.get('[data-testid="ignore-conflicts"]').trigger('click')
    await wrapper.get('[data-testid="merge-review"]').trigger('click')
    expect(wrapper.emitted('locateSource')).toBeUndefined()
    expect(wrapper.emitted('decide')).toEqual([['coverage_gap:gap-refund-timeout', 'accepted']])
    expect(wrapper.emitted('ignoreConflict')).toEqual([['c1']])
    expect(wrapper.emitted('bulkDecide')).toEqual([['pending']])
    expect(wrapper.emitted('ignoreConflicts')).toHaveLength(1)
    expect(wrapper.emitted('merge')).toHaveLength(1)
  })

  it('disables merge when the review is not eligible to merge', () => {
    const wrapper = mount(AiReviewInspector, {
      props: { ...props(), canMerge: false },
      global: { plugins: [ElementPlus] }
    })

    expect(wrapper.get('[data-testid="merge-review"]').attributes('disabled')).toBeDefined()
  })

  it('folds sections and presents conflict details', async () => {
    const wrapper = mount(AiReviewInspector, { props: props(), global: { plugins: [ElementPlus] } })
    await wrapper.get('.studio-review-inspector__conflict-detail').trigger('click')
    expect(wrapper.text()).toContain('P0')
    expect(wrapper.text()).toContain('P1')
    await wrapper.get('.studio-review-inspector__section .studio-review-inspector__collapse').trigger('click')
    expect(wrapper.find('.studio-review-inspector__conflict-detail').exists()).toBe(false)
    await wrapper.get('.studio-review-inspector__findings-header .studio-review-inspector__collapse').trigger('click')
    expect(wrapper.find('.review-finding-card').exists()).toBe(false)
  })

  it('fills the findings region and keeps its content independently scrollable', () => {
    expect(inspectorSource).toContain('.studio-review-inspector__findings-section :deep(.review-finding-list__body)')
    expect(inspectorSource).toContain('overflow-y: auto')
    expect(inspectorSource).toContain('finding.decision !== \'accepted\'')
    expect(inspectorSource).toContain('minmax(360px, 1fr)')
  })

  it('clamps a long review summary and exposes the full text on hover', () => {
    const longSummary = '很长的评审摘要'.repeat(80)
    const wrapper = mount(AiReviewInspector, {
      props: { ...props(), summary: longSummary },
      global: { plugins: [ElementPlus] }
    })

    const summary = wrapper.get('.studio-review-inspector__summary')
    expect(summary.attributes('title')).toBe(longSummary)
    expect(inspectorSource).toContain('-webkit-line-clamp: 4')
  })
})

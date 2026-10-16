// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { computed, defineComponent, h, inject, nextTick, provide, type ComputedRef } from 'vue'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import type { AiDraftTestPoint } from '../../domain/aiPlatformRepository'
import TestPointParseStage from './TestPointParseStage.vue'

const point = (id: string, status = 'pending'): AiDraftTestPoint => ({
  pointId: id,
  runId: 'run-1',
  projectId: 1,
  sourceDocumentId: 'doc-1',
  sourceChunkId: `chunk-${id}`,
  title: `测试点 ${id}`,
  module: '退款流程',
  pageOrFeature: '退款详情',
  caseKind: 'web_automation',
  testType: 'functional',
  priority: 'P1',
  objective: '验证退款流程',
  steps: '提交退款申请\n查看退款状态',
  expectedResult: '退款状态正确更新',
  sourceFile: '退款需求.docx',
  sourceText: '退款需求原文',
  confidenceScore: 0.9,
  inferred: false,
  reviewStatus: status,
  metadata: {},
  createdAt: '2026-08-25T10:00:00Z',
  updatedAt: '2026-08-25T10:00:00Z'
})

const tableStubs = {
  ElTable: defineComponent({
    props: { data: { type: Array, default: () => [] } },
    setup(props, { slots }) {
      provide('test-point-rows', computed(() => props.data as AiDraftTestPoint[]))
      return () => h('table', slots.default?.())
    }
  }),
  ElTableColumn: defineComponent({
    setup(_, { slots }) {
      const rows = inject<ComputedRef<AiDraftTestPoint[]>>('test-point-rows', computed(() => []))
      return () => h('th', rows.value.flatMap((row) => slots.default?.({ row }) || []))
    }
  })
}

const mountStage = (props: Partial<InstanceType<typeof TestPointParseStage>['$props']> = {}) =>
  mount(TestPointParseStage, {
    props: {
      points: Array.from({ length: 12 }, (_, index) => point(`point-${index + 1}`)),
      selectedIds: [],
      pendingCount: 12,
      ...props
    },
    global: { plugins: [ElementPlus], stubs: tableStubs }
  })

describe('TestPointParseStage', () => {
  it('uses explicit selection, supports point details and single confirmation, and paginates the list', async () => {
    const wrapper = mountStage()
    expect(wrapper.findAll('[data-testid^="test-point-row-"]')).toHaveLength(10)

    await wrapper.get('[data-testid="test-point-checkbox-point-1"] input').setValue(true)
    const selectionEvents = wrapper.emitted('updateSelection') || []
    expect(selectionEvents[selectionEvents.length - 1]).toEqual([['point-1']])

    await wrapper.get('[data-testid="test-point-detail-point-1"]').trigger('click')
    expect(wrapper.emitted('openDetails')?.[0]).toEqual([expect.objectContaining({ pointId: 'point-1' })])

    expect(wrapper.get('[data-testid="test-point-details-readonly"]')).toBeTruthy()
    expect(wrapper.get('[data-testid="test-point-details-readonly"]').text()).toContain('1. 提交退款申请\n2. 查看退款状态')
    await wrapper.get('[data-testid="test-point-edit-details"]').trigger('click')
    await wrapper.get('[data-testid="test-point-edit-title"]').setValue('更新后的测试点')
    await wrapper.get('[data-testid="test-point-edit-objective"]').setValue('更新后的测试目标')
    await wrapper.get('[data-testid="test-point-edit-steps"]').setValue('更新后的步骤')
    await wrapper.get('[data-testid="test-point-edit-expected-result"]').setValue('更新后的预期')
    await wrapper.get('[data-testid="test-point-save-details"]').trigger('click')
    expect(wrapper.emitted('editPoint')?.[0]).toEqual([{
      point: expect.objectContaining({ pointId: 'point-1' }),
      title: '更新后的测试点',
      objective: '更新后的测试目标',
      steps: '更新后的步骤',
      expectedResult: '更新后的预期'
    }])

    await wrapper.get('[data-testid="test-point-confirm-point-1"]').trigger('click')
    expect(wrapper.emitted('confirmOne')?.[0]).toEqual([expect.objectContaining({ pointId: 'point-1' })])

    await wrapper.get('[data-testid="test-point-delete-point-1"]').trigger('click')
    expect(wrapper.emitted('deletePoint')?.[0]).toEqual([expect.objectContaining({ pointId: 'point-1' })])

    await wrapper.get('.btn-next').trigger('click')
    await nextTick()
    expect(wrapper.findAll('[data-testid^="test-point-row-"]')).toHaveLength(2)
    expect(wrapper.text()).toContain('测试点 point-12')
    expect(wrapper.get('.studio-test-parse-stage__pagination').attributes('style')).toBeUndefined()
  })
})

// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import WebCaseRegressionComparisonDialog from './WebCaseRegressionComparisonDialog.vue'
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

const comparisonBody = {
  runId: 'run-1',
  baselineRunId: 'baseline-run-1',
  baselineOverallScore: 8,
  available: true,
  points: [{
    pointId: 'P-1',
    baselineCount: 2,
    recalledCount: 1,
    addedCount: 1,
    lostCount: 1,
    added: [{ title: '新增用例', assertions: '期望结果新增' }],
    lost: [{ title: '丢失用例标题', assertions: '期望结果丢失' }]
  }],
  summary: { baselineTotal: 2, recalled: 1, added: 1, lost: 1, recallRatio: 0.5 }
}

describe('WebCaseRegressionComparisonDialog', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders summary and per-point regression comparison', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(comparisonBody), { status: 200, headers: { 'Content-Type': 'application/json' } })))

    const wrapper = mount(WebCaseRegressionComparisonDialog, {
      props: { run, visible: true },
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('回归集对比')
    expect(wrapper.text()).toContain('召回')
    expect(wrapper.text()).toContain('新增')
    expect(wrapper.text()).toContain('丢失')
    expect(wrapper.text()).toContain('召回率')
    expect(wrapper.text()).toContain('丢失用例标题')
  })
})

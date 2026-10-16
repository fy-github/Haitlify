// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import WebCaseQualityInsightDialog from './WebCaseQualityInsightDialog.vue'

describe('WebCaseQualityInsightDialog', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders aggregated metrics, weakest dimensions, and lost cases', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      projectId: 7,
      ratedCount: 3,
      baselineCount: 2,
      overallAvg: 7.0,
      recallAvg: 0.5,
      lostCaseCount: 1,
      dimensionAvgs: { coverage: 6.0, source_traceability: 7.0, testability: 0, duplicate_risk: 0, automation_readiness: 0, review_risk: 0 },
      weakestDimensions: [{ key: 'coverage', avg: 6.0 }],
      lostCases: [{ title: '重置密码', count: 2 }],
      humanCount: 1,
      objectiveCount: 2
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })))

    const wrapper = mount(WebCaseQualityInsightDialog, {
      props: { projectId: 7, visible: true },
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('质量洞察')
    expect(wrapper.text()).toContain('平均总分')
    expect(wrapper.text()).toContain('平均召回率')
    expect(wrapper.text()).toContain('覆盖度')
    expect(wrapper.text()).toContain('优先迭代')
    expect(wrapper.text()).toContain('重置密码')
    expect(wrapper.get('[data-testid="quality-insight-source"]').text()).toContain('自动基线 2 次 · 人工校准 1 次')
  })

  it('renders a pure objective baseline when no human rating exists', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      projectId: 7,
      ratedCount: 2,
      baselineCount: 1,
      overallAvg: 6.5,
      recallAvg: 0,
      lostCaseCount: 0,
      dimensionAvgs: { coverage: 7.0, source_traceability: 5.0, testability: 6.0, duplicate_risk: 8.0, automation_readiness: 6.0, review_risk: 7.0 },
      weakestDimensions: [{ key: 'source_traceability', avg: 5.0 }],
      lostCases: [],
      humanCount: 0,
      objectiveCount: 2
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })))

    const wrapper = mount(WebCaseQualityInsightDialog, {
      props: { projectId: 7, visible: true },
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    expect(wrapper.get('[data-testid="quality-insight-source"]').text()).toContain('自动基线 2 次 · 人工校准 0 次')
    expect(wrapper.text()).toContain('优先迭代')
  })

  it('shows an empty state when the project has no ratings', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      projectId: 7,
      ratedCount: 0,
      baselineCount: 0,
      overallAvg: 0,
      recallAvg: 0,
      lostCaseCount: 0,
      dimensionAvgs: {},
      weakestDimensions: [],
      lostCases: [],
      humanCount: 0,
      objectiveCount: 0
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })))

    const wrapper = mount(WebCaseQualityInsightDialog, {
      props: { projectId: 7, visible: true },
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="quality-insight-empty"]').exists()).toBe(true)
  })
})

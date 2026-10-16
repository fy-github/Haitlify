// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import WebStepEvidenceCard from './WebStepEvidenceCard.vue'
import type { WebAutomationStepEvidence } from '../../domain/webAutomation'

// 夹具按 runner 真实产物形状（run-executor.buildStepEvidence + page-evidence.withTargetState）：
// locator 与 visible/enabled/receivesEvents/unique/matchedElementCount 都归在 resolvedTarget 对象内，
// 顶层没有这些扁平字段（第二十四轮复核 C-① 指出旧夹具虚构了扁平形状）。
const locatorBackedEvidence = (): WebAutomationStepEvidence => ({
  actionId: 'click-search',
  actionType: 'click',
  before: { pageUrl: 'https://example.com/list', pageKey: 'https://example.com/list', pageFingerprint: 'fp-before' },
  resolvedTarget: {
    strategy: 'role',
    locator: 'role=button[name="查询"]',
    visible: true,
    enabled: true,
    receivesEvents: true,
    unique: true
  },
  evidenceId: 'ev-1',
  execution: { status: 'passed', durationMs: 120 },
  after: { pageUrl: 'https://example.com/list?page=2', pageKey: 'https://example.com/list', pageFingerprint: 'fp-after' }
})

const routeEvidence = (): WebAutomationStepEvidence => ({
  actionId: 'go-detail',
  actionType: 'goto',
  before: { pageUrl: 'https://example.com/list', pageKey: 'https://example.com/list', pageFingerprint: 'fp-list' },
  // 真实管道里 route/non-locator 目标经 withTargetState 归零状态位（run-executor.test.mjs 自证）。
  resolvedTarget: { kind: 'route', value: 'https://example.com/detail', visible: false, enabled: false, receivesEvents: false, unique: false },
  evidenceId: 'ev-2',
  execution: { status: 'passed', durationMs: 90 },
  after: { pageUrl: 'https://example.com/detail', pageKey: 'https://example.com/detail', pageFingerprint: 'fp-detail' }
})

const observationFailedEvidence = (): WebAutomationStepEvidence => ({
  actionId: 'assert-title',
  actionType: 'assertText',
  before: { pageUrl: 'https://example.com/detail', pageKey: 'https://example.com/detail', pageFingerprint: 'fp-detail' },
  resolvedTarget: { observationStatus: 'failed', failureType: 'observation_failed' },
  evidenceId: 'ev-3',
  execution: { status: 'failed', durationMs: 30 },
  observationStatus: 'failed',
  failureType: 'observation_failed',
  after: { pageUrl: 'https://example.com/detail', pageKey: 'https://example.com/detail', pageFingerprint: 'fp-detail' }
})

describe('WebStepEvidenceCard', () => {
  it('reads locator and state flags from the real resolvedTarget shape on expand', async () => {
    const wrapper = mount(WebStepEvidenceCard, {
      props: { evidence: [locatorBackedEvidence()] },
      global: { plugins: [ElementPlus] }
    })

    expect(wrapper.text()).toContain('click-search')
    expect(wrapper.text()).toContain('通过')
    expect(wrapper.text()).toContain('120 ms')
    expect(wrapper.find('[data-testid="web-step-evidence-detail-ev-1"]').exists()).toBe(false)

    await wrapper.get('[data-testid="web-step-evidence-toggle-ev-1"]').trigger('click')
    const detail = wrapper.get('[data-testid="web-step-evidence-detail-ev-1"]')
    expect(wrapper.get('[data-testid="step-evidence-locator"]').text()).toBe('role=button[name="查询"]')
    expect(wrapper.get('[data-testid="step-evidence-flags"]').text()).toContain('visible=true')
    expect(wrapper.get('[data-testid="step-evidence-flags"]').text()).toContain('enabled=true')
    expect(wrapper.get('[data-testid="step-evidence-flags"]').text()).toContain('receivesEvents=true')
    expect(wrapper.get('[data-testid="step-evidence-flags"]').text()).toContain('unique=true')
    expect(wrapper.get('[data-testid="step-evidence-flags"]').text()).toContain('matchedElementCount=—')
    expect(detail.text()).toContain('fp-before')
    expect(detail.text()).toContain('fp-after')
  })

  it('shows route targets as route values without inventing a locator', async () => {
    const wrapper = mount(WebStepEvidenceCard, {
      props: { evidence: [routeEvidence()] },
      global: { plugins: [ElementPlus] }
    })
    await wrapper.get('[data-testid="web-step-evidence-toggle-ev-2"]').trigger('click')
    expect(wrapper.get('[data-testid="step-evidence-locator"]').text()).toBe('—')
    expect(wrapper.get('[data-testid="web-step-evidence-detail-ev-2"]').text()).toContain('"kind": "route"')
    expect(wrapper.get('[data-testid="web-step-evidence-detail-ev-2"]').text()).toContain('https://example.com/detail')
  })

  it('renders observation-failed evidence with explicit — flags instead of false', async () => {
    const wrapper = mount(WebStepEvidenceCard, {
      props: { evidence: [observationFailedEvidence()] },
      global: { plugins: [ElementPlus] }
    })
    expect(wrapper.text()).toContain('失败')
    expect(wrapper.text()).toContain('observation_failed')
    await wrapper.get('[data-testid="web-step-evidence-toggle-ev-3"]').trigger('click')
    const flags = wrapper.get('[data-testid="step-evidence-flags"]').text()
    expect(flags).toContain('visible=—')
    expect(flags).toContain('enabled=—')
    expect(flags).not.toContain('visible=false')
  })

  it('keeps rows with an empty evidenceId addressable and toggleable', async () => {
    const withoutId = { ...locatorBackedEvidence(), evidenceId: '' }
    const wrapper = mount(WebStepEvidenceCard, {
      props: { evidence: [withoutId] },
      global: { plugins: [ElementPlus] }
    })
    await wrapper.get('[data-testid="web-step-evidence-toggle-row-0"]').trigger('click')
    expect(wrapper.find('[data-testid="web-step-evidence-detail-row-0"]').exists()).toBe(true)
    await wrapper.get('[data-testid="web-step-evidence-toggle-row-0"]').trigger('click')
    expect(wrapper.find('[data-testid="web-step-evidence-detail-row-0"]').exists()).toBe(false)
  })
})

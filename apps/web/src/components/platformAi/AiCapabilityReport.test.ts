// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AiCapabilityReport from './AiCapabilityReport.vue'

const report = (overrides: Record<string, unknown> = {}) => ({
  profileId: 'conn-1',
  profileVersion: 3,
  configHash: 'hash-abcdef1234567890',
  checkedAt: '2026-10-10T10:00:00Z',
  ttlSeconds: 3600,
  overall: 'pass',
  probes: [
    { probe: 'text_generation', status: 'pass', detail: null },
    { probe: 'structured_output', status: 'pass', detail: null },
    { probe: 'vision', status: 'unsupported', detail: '模型未声明视觉能力' },
    { probe: 'tool_calling', status: 'pass', detail: null },
    { probe: 'streaming', status: 'pass', detail: null }
  ],
  ...overrides
}) as never

describe('AiCapabilityReport', () => {
  it('labels pass/partial/fail overall states explicitly', () => {
    const pass = mount(AiCapabilityReport, { props: { report: report(), fresh: true } })
    expect(pass.get('[data-testid="ai-capability-overall-pass"]').text()).toBe('全部通过')

    const partial = mount(AiCapabilityReport, {
      props: { report: report({ overall: 'partial' }), fresh: true }
    })
    expect(partial.get('[data-testid="ai-capability-overall-partial"]').text()).toContain('部分通过')

    const none = mount(AiCapabilityReport, { props: { report: null } })
    expect(none.get('[data-testid="ai-capability-overall-unknown"]').text()).toBe('尚未验证')
  })

  it('invalidates a stale probe when the protocol/model/credential config hash changed', () => {
    const wrapper = mount(AiCapabilityReport, {
      props: { report: report(), fresh: false, currentConfigHash: 'hash-changed-999' }
    })
    const stale = wrapper.get('[data-testid="ai-capability-stale"]').text()
    expect(stale).toContain('已更新')
    expect(stale).toContain('旧报告作废')
  })

  it('explains an expired probe separately from a config change', () => {
    const wrapper = mount(AiCapabilityReport, {
      props: { report: report(), fresh: false, currentConfigHash: 'hash-abcdef1234567890' }
    })
    expect(wrapper.get('[data-testid="ai-capability-stale"]').text()).toContain('报告已过期')
  })

  it('shows every probe with its own status and asks for a rerun when none exists', () => {
    const wrapper = mount(AiCapabilityReport, { props: { report: report(), fresh: true } })
    expect(wrapper.get('[data-testid="ai-capability-probe-text_generation"]').text()).toContain('文本生成')
    expect(wrapper.get('[data-testid="ai-capability-probe-vision"]').text()).toContain('unsupported')

    const none = mount(AiCapabilityReport, { props: { report: null } })
    expect(none.get('[data-testid="ai-capability-stale"]').text()).toContain('请先运行能力检查')
  })

  it('emits run from the explicit rerun button and reflects the loading state', async () => {
    const wrapper = mount(AiCapabilityReport, { props: { report: report(), fresh: true } })
    await wrapper.get('[data-testid="ai-capability-run"]').trigger('click')
    expect(wrapper.emitted('run')).toEqual([[]])

    const loading = mount(AiCapabilityReport, { props: { report: report(), fresh: true, loading: true } })
    expect(loading.get('[data-testid="ai-capability-run"]').text()).toContain('验证中')
    expect((loading.get('[data-testid="ai-capability-run"]').element as HTMLButtonElement).disabled).toBe(true)
  })

  it('renders the config fingerprint so operators can correlate the probe with the profile version', () => {
    const wrapper = mount(AiCapabilityReport, { props: { report: report(), fresh: true } })
    expect(wrapper.get('[data-testid="ai-capability-config-hash"]').text()).toContain('hash-abcdef1')
  })
})

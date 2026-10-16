// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import AiEffectiveOptionsCard from './AiEffectiveOptionsCard.vue'

const effective = (overrides: Record<string, unknown> = {}) => ({
  taskType: 'requirement_analysis',
  profileId: 'p-1',
  profileVersion: 2,
  timeout: { value: 180000, source: 'task_override', sourceVersion: 'policy@task_override', present: true },
  declaredCapabilities: ['TEXT'],
  verificationJson: '{"status":"VERIFIED"}',
  taskTypes: ['requirement_analysis', 'bug_analysis'],
  notes: { timeout: '显式任务覆盖 > 连接默认（legacy 全局超时）' },
  ...overrides
})

const mountCard = (props: Record<string, unknown> = {}) =>
  mount(AiEffectiveOptionsCard, {
    props: {
      effective: effective(),
      taskTypes: ['requirement_analysis', 'bug_analysis'],
      profiles: [{
        profileId: 'p-1',
        version: 2,
        connectionId: 'conn-a',
        modelId: 'gpt-x',
        capabilities: ['TEXT'],
        parameterPolicyJson: '{}',
        verificationJson: '{"status":"VERIFIED"}'
      }],
      ...props
    },
    global: { plugins: [ElementPlus] }
  })

describe('AiEffectiveOptionsCard', () => {
  it('shows the effective timeout with its provenance source and version', () => {
    const wrapper = mountCard()
    expect(wrapper.get('[data-testid="effective-timeout-value"]').text()).toBe('180000 ms')
    expect(wrapper.get('[data-testid="effective-timeout-source"]').text()).toBe('任务显式覆盖')
    expect(wrapper.get('[data-testid="effective-timeout-version"]').text()).toBe('policy@task_override')
    expect(wrapper.get('[data-testid="effective-verification"]').text()).toBe('已验证')
  })

  it('renders an absent timeout as not-sent instead of a fabricated default', () => {
    const wrapper = mountCard({
      effective: effective({ timeout: { value: null, source: 'omitted', sourceVersion: 'legacy-config@default', present: false } })
    })
    expect(wrapper.get('[data-testid="effective-timeout-value"]').text()).toBe('未设置（不发送）')
    expect(wrapper.get('[data-testid="effective-timeout-source"]').text()).toBe('未设置（不发送）')
  })

  it('emits the load request with the selected task type and optional profile', async () => {
    const wrapper = mountCard()
    await wrapper.get('[data-testid="effective-load"]').trigger('click')
    const emitted = wrapper.emitted('load')
    expect(emitted).toBeTruthy()
    expect((emitted as unknown[][])[0][0]).toBe('requirement_analysis')
  })
})

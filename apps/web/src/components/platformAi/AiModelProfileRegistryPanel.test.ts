// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { computed, defineComponent, h, inject, provide, type ComputedRef } from 'vue'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import AiModelProfileRegistryPanel from './AiModelProfileRegistryPanel.vue'

const tableStubs = {
  ElTable: defineComponent({
    props: { data: { type: Array, default: () => [] } },
    setup(props, { slots }) {
      provide('registry-rows', computed(() => props.data as Array<Record<string, unknown>>))
      return () => h('table', slots.default?.() || [])
    }
  }),
  ElTableColumn: defineComponent({
    props: { prop: { type: String, default: '' }, label: { type: String, default: '' } },
    setup(props, { slots }) {
      const rows = inject<ComputedRef<Array<Record<string, unknown>>>>('registry-rows', computed(() => []))
      return () => h('div', rows.value.flatMap((row) =>
        slots.default ? (slots.default({ row }) as never[]) : [String(row[props.prop] ?? '')]
      ))
    }
  })
}

const profile = (overrides: Record<string, unknown> = {}) => ({
  profileId: 'prod-openai-text',
  version: 3,
  connectionId: 'prod-openai',
  modelId: 'gpt-x',
  capabilities: ['TEXT', 'JSON_OBJECT'],
  parameterPolicyJson: '{}',
  verificationJson: '{"status":"UNKNOWN"}',
  ...overrides
})

const mountPanel = (props: Record<string, unknown> = {}) =>
  mount(AiModelProfileRegistryPanel, {
    props: {
      profiles: [profile()],
      connections: [{
        connectionId: 'prod-openai',
        version: 2,
        baseUrl: 'https://api.example.com/v1',
        credentialRef: 'alias:prod',
        protocol: 'CHAT_COMPLETIONS',
        enabled: true,
        allowedProjectIds: [1]
      }],
      ...props
    },
    global: { plugins: [ElementPlus], stubs: tableStubs }
  })

describe('AiModelProfileRegistryPanel', () => {
  it('renders capabilities and maps verification states to explicit labels', () => {
    const wrapper = mountPanel()
    const text = wrapper.text()
    expect(text).toContain('prod-openai-text')
    expect(text).toContain('TEXT, JSON_OBJECT')
    expect(text).toContain('未验证')

    const verified = mountPanel({
      profiles: [profile({ verificationJson: '{"status":"VERIFIED"}' })]
    })
    expect(verified.text()).toContain('已验证')
  })

  it('treats an empty capability list as undeclared rather than assumed support', () => {
    const wrapper = mountPanel({ profiles: [profile({ capabilities: [] })] })
    expect(wrapper.text()).toContain('未声明（不发送可选参数）')
  })

  it('emits a create request carrying only declared capabilities', async () => {
    const wrapper = mountPanel()
    await wrapper.get('[data-testid="profile-form-toggle"]').trigger('click')
    await wrapper.get('[data-testid="profile-id"]').setValue('p-new')
    await wrapper.get('[data-testid="profile-model"]').setValue('gpt-new')
    // 勾选 TEXT 能力（目录内唯一合法来源；未知能力不可声明）。
    const capabilityInputs = wrapper.findAll('[data-testid="profile-capabilities"] input[type="checkbox"]')
    await capabilityInputs[0]?.setValue(true)
    await wrapper.get('[data-testid="profile-submit"]').trigger('click')

    const emitted = wrapper.emitted('create')
    expect(emitted).toBeTruthy()
    const request = (emitted as unknown[][])[0][0] as Record<string, unknown>
    expect(request.profileId).toBe('p-new')
    expect(request.modelId).toBe('gpt-new')
    expect(request.capabilities).toEqual(['TEXT'])
  })
})

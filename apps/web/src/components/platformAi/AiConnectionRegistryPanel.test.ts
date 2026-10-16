// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { computed, defineComponent, h, inject, provide, type ComputedRef } from 'vue'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import AiConnectionRegistryPanel from './AiConnectionRegistryPanel.vue'

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

const connection = (overrides: Record<string, unknown> = {}) => ({
  connectionId: 'prod-openai',
  version: 2,
  baseUrl: 'https://api.example.com/v1',
  credentialRef: 'alias:prod',
  protocol: 'CHAT_COMPLETIONS',
  enabled: true,
  allowedProjectIds: [1, 2],
  ...overrides
})

const mountPanel = (props: Record<string, unknown> = {}) =>
  mount(AiConnectionRegistryPanel, {
    props: { connections: [connection()], ...props },
    global: { plugins: [ElementPlus], stubs: tableStubs }
  })

describe('AiConnectionRegistryPanel', () => {
  it('renders versioned rows with protocol, pinned version and credential refs', () => {
    const wrapper = mountPanel()
    const text = wrapper.text()
    expect(text).toContain('prod-openai')
    expect(text).toContain('v2')
    expect(text).toContain('CHAT_COMPLETIONS')
    expect(text).toContain('alias:prod')
    expect(text).toContain('1, 2')
  })

  it('shows an explicit empty state and the opaque-ref hint without key material', () => {
    const wrapper = mountPanel({ connections: [] })
    expect(wrapper.find('[data-testid="ai-connection-empty"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('opaque')
    expect(wrapper.text()).not.toContain('sk-')
  })

  it('surfaces a 409 version conflict as an actionable refresh prompt', () => {
    const wrapper = mountPanel({ conflict: 'connection version conflict for conn-a: expected 1, actual 2' })
    const conflict = wrapper.get('[data-testid="ai-connection-conflict"]').text()
    expect(conflict).toContain('版本冲突')
    expect(conflict).toContain('刷新后基于最新版本重试')
  })

  it('emits a create request with parsed project ids and the chosen protocol', async () => {
    const wrapper = mountPanel()
    await wrapper.get('[data-testid="connection-form-toggle"]').trigger('click')
    await wrapper.get('[data-testid="connection-id"]').setValue('new-conn')
    await wrapper.get('[data-testid="connection-base-url"]').setValue('https://new.test/v1')
    await wrapper.get('[data-testid="connection-credential-ref"]').setValue('alias:new')
    await wrapper.get('[data-testid="connection-projects"]').setValue('3, 4')
    await wrapper.get('[data-testid="connection-submit"]').trigger('click')

    const emitted = wrapper.emitted('create')
    expect(emitted).toBeTruthy()
    const request = (emitted as unknown[][])[0][0] as Record<string, unknown>
    expect(request.connectionId).toBe('new-conn')
    expect(request.protocol).toBe('CHAT_COMPLETIONS')
    expect(request.allowedProjectIds).toEqual([3, 4])
    expect(request.credentialRef).toBe('alias:new')
  })
})

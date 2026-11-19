// @vitest-environment jsdom
// 独立状态信息契约:不触碰模板恢复分组,只验证保存条的状态等级、可读语义和恢复动作。
import type { Component } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { defaultTemplateConfig, saveTemplateConfig, type TemplateConfig } from '../../domain/templateConfigRepository'

type MountedStatus = {
  wrapper: ReturnType<typeof mount>
  state: ReturnType<(typeof import('../../ui/useTemplateConfig'))['useTemplateConfig']>
}

const wrappers: Array<ReturnType<typeof mount>> = []
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T

afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.restoreAllMocks()
})

async function mountStatus(persistImpl: (config: TemplateConfig) => Promise<TemplateConfig>): Promise<MountedStatus> {
  vi.resetModules()
  localStorage.clear()
  saveTemplateConfig(localStorage, defaultTemplateConfig())
  const { useTemplateConfig } = await import('../../ui/useTemplateConfig')
  const state = useTemplateConfig({
    storage: localStorage,
    confirmDanger: async () => true,
    persistTemplateConfig: vi.fn(persistImpl),
    getGenerationSelectedCaseTypes: () => [],
    setGenerationSelectedCaseTypes: () => {}
  })
  const { default: SettingsView } = await import('./SettingsView.vue')
  const wrapper = mount(SettingsView as unknown as Component, {
    props: { section: 'fields' },
    attachTo: document.body,
    global: { plugins: [ElementPlus] }
  })
  wrappers.push(wrapper)
  return { wrapper, state }
}

describe('Settings 保存状态语义契约', () => {
  it('exposes saving as a distinct live status while persistence is pending', async () => {
    let resolvePersist!: (config: TemplateConfig) => void
    const pending = new Promise<TemplateConfig>((resolve) => { resolvePersist = resolve })
    const { wrapper, state } = await mountStatus(async () => pending)

    state.patchField('project', { label: '保存中名称' })
    await wrapper.vm.$nextTick()

    const status = wrapper.get('[data-save-status]')
    expect(status.attributes('data-state')).toBe('saving')
    expect(status.attributes('role')).toBe('status')
    expect(status.attributes('aria-live')).toBe('polite')
    expect(status.text()).toContain('正在自动保存')

    resolvePersist(clone(state.templateConfig.value))
    await flushPromises()
  })

  it('marks a failed save as an error-level live status and keeps recovery actions', async () => {
    const { wrapper, state } = await mountStatus(async () => { throw new Error('后端保存失败') })

    state.patchField('project', { label: '失败名称' })
    await flushPromises()

    const status = wrapper.get('[data-save-status]')
    expect(status.attributes('data-state')).toBe('failed')
    expect(status.attributes('role')).toBe('status')
    expect(status.attributes('aria-live')).toBe('polite')
    expect(status.text()).toContain('后端保存失败')
    expect(wrapper.find('[data-action="retry-save"]').exists()).toBe(true)
    expect(wrapper.find('[data-action="discard-save"]').exists()).toBe(true)
  })

  it('marks a confirmed save as a success-level live status', async () => {
    const { wrapper, state } = await mountStatus(async (config) => clone(config))

    state.patchField('project', { label: '成功名称' })
    await flushPromises()

    const status = wrapper.get('[data-save-status]')
    expect(status.attributes('data-state')).toBe('saved')
    expect(status.attributes('role')).toBe('status')
    expect(status.attributes('aria-live')).toBe('polite')
    expect(status.text()).toContain('已自动保存')
    expect(wrapper.find('[data-action="retry-save"]').exists()).toBe(false)
    expect(wrapper.find('[data-action="discard-save"]').exists()).toBe(false)
  })
})

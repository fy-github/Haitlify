// @vitest-environment jsdom
//
// 分节切换:「模板字段 / 用例类型」共用同一个 SettingsView 实例,抽屉挂在分节之外。
// 切换分节必须关闭/取消未确认的抽屉草稿(草稿确认会写进另一张页面的数据),并把焦点交给新分节入口。
import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import type { Component } from 'vue'
import { defaultTemplateConfig, loadTemplateConfig, saveTemplateConfig, type TemplateConfig } from '../../domain/templateConfigRepository'
import { useTemplateConfig } from '../../ui/useTemplateConfig'
import SettingsView from './SettingsView.vue'

const labelOf = (config: TemplateConfig) => config.interfaceFields.find((field) => field.key === 'project')!.label

const mountSection = async (section: 'fields' | 'types') => {
  localStorage.clear()
  saveTemplateConfig(localStorage, defaultTemplateConfig())
  const persist = vi.fn(async (config: TemplateConfig) => {
    // 和 App 一样的本地先行:确认后才落库
    saveTemplateConfig(localStorage, config)
    return JSON.parse(JSON.stringify(config)) as TemplateConfig
  })
  const state = useTemplateConfig({ storage: localStorage, confirmDanger: async () => true, persistTemplateConfig: persist })
  const wrapper = mount(SettingsView as unknown as Component, {
    props: { section }, attachTo: document.body, global: { plugins: [ElementPlus] }
  })
  await flushPromises()
  return { wrapper, state, persist }
}

describe('分节切换不带走抽屉草稿', () => {
  it('字段页打开的抽屉切到类型页后关闭,且不写入 interface 字段', async () => {
    const { wrapper, state } = await mountSection('fields')
    await wrapper.find('[data-action="add-field"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-drawer="field"]').exists()).toBe(true)
    expect(state.fieldDraft.value).not.toBeNull()

    await wrapper.setProps({ section: 'types' })
    await flushPromises()

    expect(wrapper.find('[data-drawer="field"]').exists()).toBe(false)
    // 草稿被取消(不是隐藏),不会在类型页被确认
    expect(state.fieldDraft.value).toBeNull()
    expect(labelOf(loadTemplateConfig(localStorage))).toBe('项目')
    expect(loadTemplateConfig(localStorage).interfaceFields.some((field) => field.key === 'leaked_field')).toBe(false)
    wrapper.unmount()
  })

  it('关闭抽屉后焦点交给新分节的入口按钮', async () => {
    const { wrapper } = await mountSection('fields')
    const trigger = wrapper.find('[data-action="add-field"]')
    await trigger.trigger('click')
    await flushPromises()
    expect(document.activeElement?.getAttribute('data-field')).toBe('key')

    await wrapper.setProps({ section: 'types' })
    await flushPromises()

    expect(document.activeElement?.getAttribute('data-action')).toBe('add-type')
    wrapper.unmount()
  })

  it('类型页打开的抽屉切到字段页后关闭,焦点回到字段页入口', async () => {
    const { wrapper, state } = await mountSection('types')
    await wrapper.find('[data-action="add-type"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-drawer="type"]').exists()).toBe(true)

    await wrapper.setProps({ section: 'fields' })
    await flushPromises()

    expect(wrapper.find('[data-drawer="type"]').exists()).toBe(false)
    expect(state.caseTypeDraft.value).toBeNull()
    expect(document.activeElement?.getAttribute('data-action')).toBe('add-field')
    wrapper.unmount()
  })

  it('草稿路径在分节外仍然可用:同一分节内打开/确认不受影响', async () => {
    const { wrapper, state } = await mountSection('fields')
    await wrapper.find('[data-action="add-field"]').trigger('click')
    await flushPromises()
    state.fieldDraft.value!.key = 'custom_note'
    state.fieldDraft.value!.label = '自定义备注'
    await wrapper.find('[data-action="submit-field"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-drawer="field"]').exists()).toBe(false)
    expect(loadTemplateConfig(localStorage).interfaceFields.some((field) => field.key === 'custom_note')).toBe(true)
    wrapper.unmount()
  })
})

// @vitest-environment jsdom
// 视图层:新增字段/新增类型走右侧抽屉草稿,保存条按页面独立且失败可重试/放弃。
import type { Component } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { defaultTemplateConfig, saveTemplateConfig, type TemplateConfig } from '../../domain/templateConfigRepository'
import type * as TemplateConfigModule from '../../ui/useTemplateConfig'

type TemplateState = ReturnType<typeof TemplateConfigModule.useTemplateConfig>

const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
const wrappers: Array<ReturnType<typeof mount>> = []

afterEach(() => { wrappers.splice(0).forEach((wrapper) => wrapper.unmount()); vi.restoreAllMocks() })

async function mountView(section: 'fields' | 'types', persistImpl?: (config: TemplateConfig) => Promise<TemplateConfig>) {
  vi.resetModules()
  localStorage.clear()
  const config = defaultTemplateConfig()
  config.caseTypes.push({ id: 'web_only', name: 'Web 专属', appliesTo: 'web_automation', enabled: true })
  saveTemplateConfig(localStorage, config)
  const persist = vi.fn(persistImpl ?? (async (value: TemplateConfig) => clone(value)))
  const { useTemplateConfig } = await import('../../ui/useTemplateConfig')
  const state: TemplateState = useTemplateConfig({
    storage: localStorage,
    confirmDanger: async () => true,
    persistTemplateConfig: persist,
    getGenerationSelectedCaseTypes: () => [],
    setGenerationSelectedCaseTypes: () => {}
  })
  const { default: SettingsView } = await import('./SettingsView.vue')
  const wrapper = mount(SettingsView as unknown as Component, {
    props: { section },
    attachTo: document.body,
    global: { plugins: [ElementPlus] }
  })
  wrappers.push(wrapper)
  return { wrapper, state, persist }
}

const rows = (wrapper: ReturnType<typeof mount>) => wrapper.findAll('tbody tr').filter((row) => !row.classes('s-empty'))

describe('模板字段页的新增字段抽屉', () => {
  it('opens a draft drawer instead of inserting a row immediately', async () => {
    const { wrapper } = await mountView('fields')
    const before = rows(wrapper).length
    await wrapper.find('[data-action="add-field"]').trigger('click')
    const drawer = wrapper.find('[data-drawer="field"]')
    expect(drawer.exists()).toBe(true)
    expect(drawer.attributes('role')).toBe('dialog')
    expect(drawer.attributes('aria-modal')).toBe('true')
    expect(drawer.find('[data-field="key"]').exists()).toBe(true)
    expect(drawer.find('[data-field="label"]').exists()).toBe(true)
    expect(drawer.find('[data-field="type"]').exists()).toBe(true)
    expect(drawer.findAll('[data-flag]').length).toBe(4)
    expect(rows(wrapper).length).toBe(before)
    expect(wrapper.text()).not.toContain('customField')
  })

  it('keeps the drawer open and shows an alert for an empty key', async () => {
    const { wrapper, persist } = await mountView('fields')
    const before = rows(wrapper).length
    await wrapper.find('[data-action="add-field"]').trigger('click')
    await wrapper.find('[data-field="label"]').setValue('备注')
    await wrapper.find('[data-action="submit-field"]').trigger('click')
    await flushPromises()
    const alert = wrapper.find('[data-drawer="field"] [role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('Key')
    expect(wrapper.find('[data-drawer="field"]').exists()).toBe(true)
    expect(rows(wrapper).length).toBe(before)
    expect(persist).not.toHaveBeenCalled()
  })

  it('creates the field on confirm and closes the drawer', async () => {
    const { wrapper, persist } = await mountView('fields')
    const before = rows(wrapper).length
    await wrapper.find('[data-action="add-field"]').trigger('click')
    await wrapper.find('[data-field="key"]').setValue('custom_note')
    await wrapper.find('[data-field="label"]').setValue('备注说明')
    await wrapper.find('[data-flag="required"]').setValue(true)
    await wrapper.find('[data-action="submit-field"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-drawer="field"]').exists()).toBe(false)
    expect(rows(wrapper).length).toBe(before + 1)
    expect(wrapper.text()).toContain('custom_note')
    expect(persist).toHaveBeenCalledTimes(1)
  })

  it('cancels without writing anything', async () => {
    const { wrapper, persist, state } = await mountView('fields')
    const before = rows(wrapper).length
    await wrapper.find('[data-action="add-field"]').trigger('click')
    await wrapper.find('[data-field="key"]').setValue('dropped_key')
    await wrapper.find('[data-field="label"]').setValue('丢弃')
    await wrapper.find('[data-action="cancel-field"]').trigger('click')
    expect(wrapper.find('[data-drawer="field"]').exists()).toBe(false)
    expect(state.fieldDraft.value).toBeNull()
    expect(rows(wrapper).length).toBe(before)
    expect(persist).not.toHaveBeenCalled()
  })

  it('keeps Tab focus inside the drawer', async () => {
    const { wrapper } = await mountView('fields')
    await wrapper.find('[data-action="add-field"]').trigger('click')
    await flushPromises()
    const drawer = wrapper.find('[data-drawer="field"]')
    const first = drawer.element.querySelector('.s-drawer-close') as HTMLElement
    const last = drawer.element.querySelector('[data-action="submit-field"]') as HTMLElement
    last.focus()
    await wrapper.find('[data-action="submit-field"]').trigger('keydown', { key: 'Tab' })
    expect(document.activeElement).toBe(first)
    first.focus()
    await wrapper.find('.s-drawer-close').trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(last)
  })

  it('closes the drawer with Escape', async () => {
    const { wrapper, persist } = await mountView('fields')
    await wrapper.find('[data-action="add-field"]').trigger('click')
    await wrapper.find('[data-drawer="field"]').trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('[data-drawer="field"]').exists()).toBe(false)
    expect(persist).not.toHaveBeenCalled()
  })
})

describe('用例类型页的新增类型抽屉', () => {
  it('offers a scope control in the drawer and lists non-interface types', async () => {
    const { wrapper } = await mountView('types')
    expect(wrapper.text()).toContain('web_only')
    await wrapper.find('[data-action="add-type"]').trigger('click')
    const drawer = wrapper.find('[data-drawer="type"]')
    expect(drawer.exists()).toBe(true)
    expect(drawer.find('[data-field="type-id"]').exists()).toBe(true)
    expect(drawer.find('[data-field="type-name"]').exists()).toBe(true)
    expect(drawer.find('[data-field="scope"]').exists()).toBe(true)
  })

  it('rejects a duplicate id and writes nothing', async () => {
    const { wrapper, persist } = await mountView('types')
    const before = rows(wrapper).length
    await wrapper.find('[data-action="add-type"]').trigger('click')
    await wrapper.find('[data-field="type-id"]').setValue('positive')
    await wrapper.find('[data-field="type-name"]').setValue('重复类型')
    await wrapper.find('[data-action="submit-type"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-drawer="type"] [role="alert"]').text()).toContain('已存在')
    expect(rows(wrapper).length).toBe(before)
    expect(persist).not.toHaveBeenCalled()
  })

  it('creates a type for a non-interface category', async () => {
    const { wrapper, persist, state } = await mountView('types')
    const before = rows(wrapper).length
    await wrapper.find('[data-action="add-type"]').trigger('click')
    await wrapper.find('[data-field="type-id"]').setValue('web_timeout')
    await wrapper.find('[data-field="type-name"]').setValue('页面超时')
    state.caseTypeDraft.value!.appliesTo = 'web_automation'
    await wrapper.find('[data-action="submit-type"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-drawer="type"]').exists()).toBe(false)
    expect(rows(wrapper).length).toBe(before + 1)
    const names = wrapper.findAll('tbody input[type="text"]').map((input) => (input.element as HTMLInputElement).value)
    expect(names).toContain('页面超时')
    expect(persist).toHaveBeenCalledTimes(1)
  })
})

describe('保存条', () => {
  it('shows retry and discard on failure and recovers after retry', async () => {
    let fail = true
    const { wrapper, state } = await mountView('fields', async (config) => {
      if (fail) throw new Error('后端 500')
      return clone(config)
    })
    state.patchField('project', { label: '失败名称' })
    await flushPromises()
    const status = wrapper.find('[data-save-status]')
    expect(status.attributes('data-state')).toBe('failed')
    expect(status.text()).toContain('后端 500')
    expect(wrapper.find('[data-action="retry-save"]').exists()).toBe(true)
    expect(wrapper.find('[data-action="discard-save"]').exists()).toBe(true)
    fail = false
    await wrapper.find('[data-action="retry-save"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-save-status]').attributes('data-state')).toBe('saved')
    expect(wrapper.find('[data-save-status]').text()).toContain('已自动保存')
    expect(wrapper.find('[data-action="retry-save"]').exists()).toBe(false)
  })

  it('does not leak the fields save state onto the types page', async () => {
    const { wrapper, state } = await mountView('fields')
    state.patchField('project', { label: '字段页' })
    await flushPromises()
    expect(wrapper.find('[data-save-status]').attributes('data-state')).toBe('saved')
    await wrapper.setProps({ section: 'types' })
    expect(wrapper.find('[data-save-status]').attributes('data-state')).toBe('idle')
    expect(wrapper.find('[data-save-status]').text()).toBe('')
  })

  it('shows the App-side local degrade as a failure with retry, not as saved', async () => {
    const { wrapper, state } = await mountView('fields', async () => {
      throw new Error('后端暂不可用，修改仅保留在本地')
    })
    state.patchField('project', { label: '仅本地' })
    await flushPromises()
    const status = wrapper.find('[data-save-status]')
    expect(status.attributes('data-state')).toBe('failed')
    expect(status.text()).not.toContain('已自动保存')
    expect(status.text()).toContain('仅保留在本地')
    expect(wrapper.find('[data-action="retry-save"]').exists()).toBe(true)
  })
})

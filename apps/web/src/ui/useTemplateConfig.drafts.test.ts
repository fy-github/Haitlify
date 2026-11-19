// @vitest-environment jsdom
// 新增字段 / 新增类型:必须走右侧抽屉草稿,校验 Key/名称/类型/标记/适用范围,
// 取消不落库,确认才写入并自动保存一次;用例类型覆盖全部类别而非仅 interface。
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import {
  defaultTemplateConfig,
  saveTemplateConfig,
  TEMPLATE_CONFIG_STORAGE_KEY,
  type TemplateConfig
} from '../domain/templateConfigRepository'
import { useTemplateConfig } from './useTemplateConfig'

const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T

afterEach(() => { vi.restoreAllMocks() })

function setup() {
  localStorage.clear()
  const config = defaultTemplateConfig()
  config.caseTemplates.push({
    id: 'custom-template', name: '自定义模板', kind: 'interface', builtIn: false, enabled: true, fieldKeys: []
  })
  config.caseTypes.push({ id: 'web_only', name: 'Web 专属', appliesTo: 'web_automation', enabled: true })
  saveTemplateConfig(localStorage, config)
  const persist = vi.fn(async (value: TemplateConfig) => {
    saveTemplateConfig(localStorage, value, { updatedAt: 'confirmed' })
    return clone(value)
  })
  const state = useTemplateConfig({
    storage: localStorage,
    confirmDanger: async () => true,
    persistTemplateConfig: persist,
    getGenerationSelectedCaseTypes: () => [],
    setGenerationSelectedCaseTypes: () => {}
  })
  const interfaceFields = () => state.templateConfig.value.fieldsByKind.interface
  return { state, persist, interfaceFields }
}

describe('新增字段抽屉草稿', () => {
  it('opens a draft without writing anything', () => {
    const { state, persist, interfaceFields } = setup()
    const before = interfaceFields().length
    state.openFieldDraft()
    expect(state.fieldDraft.value).not.toBeNull()
    expect(state.fieldDraft.value!.visible).toBe(true)
    expect(state.fieldDraft.value!.exportable).toBe(true)
    expect(state.fieldDraft.value!.required).toBe(false)
    expect(interfaceFields().length).toBe(before)
    expect(persist).not.toHaveBeenCalled()
  })

  it('rejects an empty key and shows an error without writing', async () => {
    const { state, persist, interfaceFields } = setup()
    const before = interfaceFields().length
    state.openFieldDraft()
    state.fieldDraft.value!.key = '   '
    state.fieldDraft.value!.label = '自定义字段'
    expect(state.submitFieldDraft()).toBe(false)
    await flushPromises()
    expect(state.fieldDraft.value!.error).toContain('Key')
    expect(interfaceFields().length).toBe(before)
    expect(persist).not.toHaveBeenCalled()
  })

  it('rejects a key with illegal characters', () => {
    const { state } = setup()
    state.openFieldDraft()
    state.fieldDraft.value!.key = 'custom note'
    state.fieldDraft.value!.label = '备注'
    expect(state.submitFieldDraft()).toBe(false)
    expect(state.fieldDraft.value!.error).toContain('字母')
  })

  it('rejects a duplicate key inside the active category only', () => {
    const { state } = setup()
    state.openFieldDraft()
    state.fieldDraft.value!.key = 'project'
    state.fieldDraft.value!.label = '重复 Key'
    expect(state.submitFieldDraft()).toBe(false)
    expect(state.fieldDraft.value!.error).toContain('已存在')

    // 该 Key 只存在于 Web 类别,interface 下允许复用
    state.fieldDraft.value!.key = 'pageName'
    state.fieldDraft.value!.error = ''
    state.fieldDraft.value!.label = '页面'
    expect(state.submitFieldDraft()).toBe(true)
  })

  it('rejects an empty label', () => {
    const { state } = setup()
    state.openFieldDraft()
    state.fieldDraft.value!.key = 'custom_note'
    state.fieldDraft.value!.label = '  '
    expect(state.submitFieldDraft()).toBe(false)
    expect(state.fieldDraft.value!.error).toContain('中文表头')
  })

  it('creates the field, appends it to a custom template and saves once', async () => {
    const { state, persist, interfaceFields } = setup()
    state.selectedTemplateId.value = 'custom-template'
    state.openFieldDraft()
    Object.assign(state.fieldDraft.value!, {
      key: 'custom_note', label: '备注说明', type: 'textarea', required: true, visible: false, exportable: true, aiGenerated: false
    })
    expect(state.submitFieldDraft()).toBe(true)
    await flushPromises()
    const created = interfaceFields().find((field) => field.key === 'custom_note')
    expect(created).toMatchObject({ label: '备注说明', type: 'textarea', required: true, visible: false, exportable: true, aiGenerated: false })
    expect(state.fieldDraft.value).toBeNull()
    expect(persist).toHaveBeenCalledTimes(1)
    expect(state.saveStatusFor('fields').state).toBe('saved')
    expect(state.templateConfig.value.caseTemplates.find((template) => template.id === 'custom-template')!.fieldKeys)
      .toContain('custom_note')
    expect(localStorage.getItem(TEMPLATE_CONFIG_STORAGE_KEY)).toContain('custom_note')
  })

  it('cancelling discards the draft without writing', () => {
    const { state, persist, interfaceFields } = setup()
    const before = interfaceFields().length
    state.openFieldDraft()
    state.fieldDraft.value!.key = 'custom_note'
    state.fieldDraft.value!.label = '备注'
    state.cancelFieldDraft()
    expect(state.fieldDraft.value).toBeNull()
    expect(interfaceFields().length).toBe(before)
    expect(persist).not.toHaveBeenCalled()
  })

  it('applies a draft to the category captured when the drawer opened', () => {
    const { state } = setup()
    state.openFieldDraft()
    expect(state.fieldDraft.value!.kind).toBe('interface')
    state.selectedTemplateId.value = 'builtin-web'
    state.fieldDraft.value!.key = 'captured_kind_field'
    state.fieldDraft.value!.label = '打开时类别'
    expect(state.submitFieldDraft()).toBe(true)
    expect(state.templateConfig.value.fieldsByKind.interface.some((field) => field.key === 'captured_kind_field')).toBe(true)
    expect(state.templateConfig.value.fieldsByKind.web_automation.some((field) => field.key === 'captured_kind_field')).toBe(false)
  })
})

describe('新增类型抽屉草稿', () => {
  it('opens a draft without writing a type', () => {
    const { state, persist } = setup()
    const before = state.templateConfig.value.caseTypes.length
    state.openCaseTypeDraft()
    expect(state.caseTypeDraft.value).not.toBeNull()
    expect(state.caseTypeDraft.value!.enabled).toBe(true)
    expect(state.templateConfig.value.caseTypes.length).toBe(before)
    expect(persist).not.toHaveBeenCalled()
  })

  it('rejects empty id and empty name', () => {
    const { state } = setup()
    state.openCaseTypeDraft()
    state.caseTypeDraft.value!.id = '  '
    state.caseTypeDraft.value!.name = '新类型'
    expect(state.submitCaseTypeDraft()).toBe(false)
    expect(state.caseTypeDraft.value!.error).toContain('ID')
    state.caseTypeDraft.value!.id = 'new_type'
    state.caseTypeDraft.value!.name = '   '
    expect(state.submitCaseTypeDraft()).toBe(false)
    expect(state.caseTypeDraft.value!.error).toContain('名称')
  })

  it('rejects a duplicate id and a duplicate name', () => {
    const { state } = setup()
    state.openCaseTypeDraft()
    state.caseTypeDraft.value!.id = 'positive'
    state.caseTypeDraft.value!.name = '新类型'
    expect(state.submitCaseTypeDraft()).toBe(false)
    expect(state.caseTypeDraft.value!.error).toContain('已存在')
    state.caseTypeDraft.value!.id = 'brand_new'
    state.caseTypeDraft.value!.name = '  正向 '
    expect(state.submitCaseTypeDraft()).toBe(false)
    expect(state.caseTypeDraft.value!.error).toContain('已存在')
  })

  it('rejects an out-of-range scope', () => {
    const { state } = setup()
    state.openCaseTypeDraft()
    state.caseTypeDraft.value!.id = 'valid_id'
    state.caseTypeDraft.value!.name = '合法类型'
    state.caseTypeDraft.value!.appliesTo = 'unknown_kind' as never
    expect(state.submitCaseTypeDraft()).toBe(false)
    expect(state.caseTypeDraft.value!.error).toContain('适用范围')
  })

  it('creates a type for a non-interface category and saves once', async () => {
    const { state, persist } = setup()
    state.openCaseTypeDraft()
    Object.assign(state.caseTypeDraft.value!, { id: 'web_timeout', name: '页面超时', appliesTo: 'web_automation', enabled: false })
    expect(state.submitCaseTypeDraft()).toBe(true)
    await flushPromises()
    const created = state.templateConfig.value.caseTypes.find((caseType) => caseType.id === 'web_timeout')
    expect(created).toMatchObject({ name: '页面超时', appliesTo: 'web_automation', enabled: false })
    expect(state.caseTypeDraft.value).toBeNull()
    expect(persist).toHaveBeenCalledTimes(1)
    expect(state.saveStatusFor('types').state).toBe('saved')
    expect(localStorage.getItem(TEMPLATE_CONFIG_STORAGE_KEY)).toContain('web_timeout')
  })

  it('cancelling the type draft writes nothing', () => {
    const { state, persist } = setup()
    state.openCaseTypeDraft()
    state.caseTypeDraft.value!.id = 'dropped_type'
    state.cancelCaseTypeDraft()
    expect(state.caseTypeDraft.value).toBeNull()
    expect(state.templateConfig.value.caseTypes.some((caseType) => caseType.id === 'dropped_type')).toBe(false)
    expect(persist).not.toHaveBeenCalled()
  })
})

describe('用例类型覆盖全部类别', () => {
  it('lists types of every category, not just interface', () => {
    const { state } = setup()
    const scopes = new Set(state.allCaseTypes.value.map((caseType) => caseType.appliesTo))
    expect(scopes.has('web_automation')).toBe(true)
    expect(state.allCaseTypes.value.some((caseType) => caseType.id === 'web_only')).toBe(true)
    expect(state.allCaseTypes.value.length).toBe(state.templateConfig.value.caseTypes.length)
  })

  it('keeps the generation selection limited to interface-applicable types', async () => {
    const { state } = setup()
    state.openCaseTypeDraft()
    Object.assign(state.caseTypeDraft.value!, { id: 'app_perf', name: 'APP 卡顿', appliesTo: 'app_automation', enabled: true })
    state.submitCaseTypeDraft()
    await flushPromises()
    expect(state.interfaceCaseTypes.value.some((caseType) => caseType.id === 'app_perf')).toBe(false)
  })

  it('edits the scope of a non-interface type', async () => {
    const { state, persist } = setup()
    state.patchCaseType('web_only', { appliesTo: 'performance' })
    await flushPromises()
    expect(state.templateConfig.value.caseTypes.find((caseType) => caseType.id === 'web_only')!.appliesTo).toBe('performance')
    expect(persist).toHaveBeenCalledTimes(1)
    expect(state.saveStatusFor('types').state).toBe('saved')
  })
})

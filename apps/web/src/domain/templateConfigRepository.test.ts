import { describe, expect, it } from 'vitest'
import {
  addCaseField,
  addCaseFieldByKind,
  addCaseTemplate,
  addCaseType,
  deleteCaseField,
  deleteCaseTemplate,
  deleteCaseType,
  loadTemplateConfig,
  loadTemplateConfigMeta,
  moveCaseFieldByKind,
  moveCaseField,
  resetTemplateConfig,
  saveTemplateConfig,
  templateKindOptions,
  updateCaseFieldByKind,
  updateCaseField,
  updateCaseTemplate,
  updateCaseType
} from './templateConfigRepository'

class MemoryStorage implements Storage {
  private values = new Map<string, string>()

  get length() {
    return this.values.size
  }

  clear(): void {
    this.values.clear()
  }

  getItem(key: string): string | null {
    return this.values.get(key) ?? null
  }

  key(index: number): string | null {
    return Array.from(this.values.keys())[index] ?? null
  }

  removeItem(key: string): void {
    this.values.delete(key)
  }

  setItem(key: string, value: string): void {
    this.values.set(key, value)
  }
}

describe('templateConfigRepository', () => {
  it('loads default interface fields and case types when storage is empty', () => {
    const config = loadTemplateConfig(new MemoryStorage())

    expect(config.interfaceFields.map((field) => field.label).slice(0, 4)).toEqual([
      '用例ID',
      '项目',
      '模块',
      '接口名称'
    ])
    expect(config.fieldsByKind.web_automation.map((field) => field.label).slice(0, 4)).toEqual([
      '用例ID',
      '项目',
      '模块',
      '页面'
    ])
    expect(config.fieldsByKind.app_automation.map((field) => field.label).slice(0, 4)).toEqual([
      '用例ID',
      '项目',
      '模块',
      '应用'
    ])
    expect(config.caseTemplates.map((template) => template.name)).toEqual([
      '接口用例',
      'Web 用例',
      'APP 用例',
      'AI 用例',
      '功能用例',
      '性能用例'
    ])
    expect(config.caseTypes.map((caseType) => caseType.name)).toContain('鉴权缺失')
  })

  it('updates, adds, deletes, and moves interface fields', () => {
    const config = loadTemplateConfig(new MemoryStorage())
    const updated = updateCaseField(config, 'sourceFile', {
      label: '来源',
      exportable: false
    })
    const added = addCaseField(updated, {
      key: 'customReviewer',
      label: '评审人',
      type: 'text',
      required: false,
      visible: true,
      exportable: true,
      aiGenerated: false
    })
    const moved = moveCaseField(added, 'customReviewer', -1)
    expect(moved.interfaceFields.findIndex((field) => field.key === 'customReviewer')).toBeLessThan(
      moved.interfaceFields.findIndex((field) => field.key === 'remark')
    )

    const deleted = deleteCaseField(moved, 'remark')

    expect(deleted.interfaceFields.find((field) => field.key === 'sourceFile')).toMatchObject({
      label: '来源',
      exportable: false
    })
    expect(deleted.interfaceFields.some((field) => field.key === 'remark')).toBe(false)
  })

  it('merges newly added built-in fields into older saved template config', () => {
    const storage = new MemoryStorage()
    storage.setItem('ai-test-platform.template-config.v1', JSON.stringify({
      interfaceFields: [
        {
          key: 'caseId',
          label: '用例ID',
          type: 'text',
          required: true,
          visible: true,
          exportable: true,
          aiGenerated: false
        },
        {
          key: 'expectedResult',
          label: '预期结果',
          type: 'textarea',
          required: false,
          visible: true,
          exportable: true,
          aiGenerated: true
        },
        {
          key: 'assertions',
          label: '断言规则',
          type: 'textarea',
          required: false,
          visible: true,
          exportable: true,
          aiGenerated: true
        }
      ],
      caseTypes: []
    }))

    const loaded = loadTemplateConfig(storage)

    expect(loaded.interfaceFields.some((field) => field.key === 'expectedResponseBody')).toBe(true)
    expect(loaded.interfaceFields.some((field) => field.key === 'actualResponseBody')).toBe(true)
    expect(loaded.interfaceFields.find((field) => field.key === 'actualResponseBody')).toMatchObject({
      label: '实际返回体',
      type: 'json',
      aiGenerated: false,
      visible: true
    })
    expect(loaded.fieldsByKind.interface.some((field) => field.key === 'expectedResponseBody')).toBe(true)
    expect(loaded.fieldsByKind.web_automation.some((field) => field.key === 'pageName')).toBe(true)
  })

  it('updates and reorders web automation fields independently from interface fields', () => {
    const config = loadTemplateConfig(new MemoryStorage())

    const updated = updateCaseFieldByKind(config, 'web_automation', 'pageName', {
      label: '页面名称',
      exportable: false
    })
    const moved = moveCaseFieldByKind(updated, 'web_automation', 'pageName', 1)

    expect(moved.fieldsByKind.web_automation.find((field) => field.key === 'pageName')).toMatchObject({
      label: '页面名称',
      exportable: false
    })
    expect(moved.interfaceFields.find((field) => field.key === 'interfaceName')?.label).toBe('接口名称')
  })

  it('adds fields to future case kinds and exposes built-in template options', () => {
    const config = loadTemplateConfig(new MemoryStorage())
    const added = addCaseFieldByKind(config, 'ai_test', {
      key: 'riskScore',
      label: '风险评分',
      type: 'text',
      required: false,
      visible: true,
      exportable: true,
      aiGenerated: true
    })

    expect(added.fieldsByKind.ai_test.some((field) => field.key === 'riskScore')).toBe(true)
    expect(added.fieldsByKind.interface.some((field) => field.key === 'riskScore')).toBe(false)
    expect(templateKindOptions(added).map((option) => option.label)).toEqual([
      '接口用例（内置）',
      'Web 用例（内置）',
      'APP 用例（内置）',
      'AI 用例（内置）',
      '功能用例（内置）',
      '性能用例（内置）'
    ])
  })

  it('persists custom case templates independently from built-in templates', () => {
    const storage = new MemoryStorage()
    const config = loadTemplateConfig(storage)
    const added = addCaseTemplate(config, {
      id: 'custom-regression',
      name: '回归冒烟模板',
      kind: 'web_automation',
      builtIn: false,
      enabled: true,
      fieldKeys: ['caseId', 'project', 'module', 'title', 'steps']
    })
    const renamed = updateCaseTemplate(added, 'custom-regression', {
      name: 'Web 回归模板'
    })
    const builtInDeleted = deleteCaseTemplate(renamed, 'builtin-web')

    expect(builtInDeleted.caseTemplates.some((template) => template.id === 'builtin-web')).toBe(true)

    saveTemplateConfig(storage, builtInDeleted)
    const saved = loadTemplateConfig(storage)

    expect(saved.caseTemplates.find((template) => template.id === 'custom-regression')).toMatchObject({
      name: 'Web 回归模板',
      builtIn: false,
      kind: 'web_automation'
    })
    expect(deleteCaseTemplate(saved, 'custom-regression').caseTemplates.some((template) => template.id === 'custom-regression')).toBe(false)
  })

  it('updates, adds, deletes, saves, and resets case type config', () => {
    const storage = new MemoryStorage()
    const config = loadTemplateConfig(storage)
    const updated = updateCaseType(config, 'auth_missing', {
      enabled: false,
      name: '未登录'
    })
    const added = addCaseType(updated, {
      id: 'tenant_isolation',
      name: '租户隔离',
      appliesTo: 'web_automation',
      enabled: true
    })
    const deleted = deleteCaseType(added, 'duplicate_submit')

    saveTemplateConfig(storage, deleted)

    const saved = loadTemplateConfig(storage)
    expect(saved.caseTypes.find((caseType) => caseType.id === 'auth_missing')).toMatchObject({
      enabled: false,
      name: '未登录'
    })
    expect(saved.caseTypes.some((caseType) => caseType.id === 'tenant_isolation')).toBe(true)
    expect(saved.caseTypes.some((caseType) => caseType.id === 'duplicate_submit')).toBe(false)

    resetTemplateConfig(storage)
    expect(loadTemplateConfig(storage).caseTypes.some((caseType) => caseType.id === 'duplicate_submit')).toBe(true)
  })

  it('tracks template config update metadata and clears it on reset', () => {
    const storage = new MemoryStorage()
    const config = loadTemplateConfig(storage)

    saveTemplateConfig(storage, config, { updatedAt: '2026-06-15T10:00:00.000Z' })

    expect(loadTemplateConfigMeta(storage).updatedAt).toBe('2026-06-15T10:00:00.000Z')

    resetTemplateConfig(storage)
    expect(loadTemplateConfigMeta(storage).updatedAt).toBeUndefined()
  })
})

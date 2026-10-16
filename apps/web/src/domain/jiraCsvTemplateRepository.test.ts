import { describe, expect, it } from 'vitest'
import {
  JIRA_CSV_TEMPLATE_STORAGE_KEY,
  loadJiraCsvTemplate,
  resetJiraCsvTemplate,
  saveJiraCsvTemplate
} from './jiraCsvTemplateRepository'

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

describe('jiraCsvTemplateRepository', () => {
  it('loads the default jira csv template when storage is empty', () => {
    const template = loadJiraCsvTemplate(new MemoryStorage())
    expect(template.map((field) => field.header)).toEqual([
      '概要',
      '问题类型',
      '项目名称',
      '优先级',
      '经办人',
      '描述',
      '向外链接的问题（BLOCK）',
      '自定义字段（ 故障分类）'
    ])
  })

  it('saves and loads a customized jira csv template with extra fields', () => {
    const storage = new MemoryStorage()
    const customized = [
      {
        key: 'summary',
        header: '问题概要',
        source: {
          kind: 'draftField' as const,
          fieldKey: 'summary' as const
        },
        enabled: true
      },
      {
        key: 'affectedVersion',
        header: '自定义字段（影响版本）',
        source: {
          kind: 'jiraFieldValue' as const,
          fieldKey: '自定义字段（影响版本）'
        },
        enabled: true
      },
      {
        key: 'assignee',
        header: '经办人',
        source: {
          kind: 'draftField' as const,
          fieldKey: 'assignee' as const
        },
        enabled: false
      }
    ]

    saveJiraCsvTemplate(storage, customized)

    expect(loadJiraCsvTemplate(storage)).toEqual(customized)
  })

  it('falls back to the default template when stored data is invalid and supports reset', () => {
    const storage = new MemoryStorage()
    storage.setItem(JIRA_CSV_TEMPLATE_STORAGE_KEY, JSON.stringify([{ bad: true }]))

    expect(loadJiraCsvTemplate(storage).map((field) => field.header)).toContain('概要')

    saveJiraCsvTemplate(storage, [{
      key: 'summary',
      header: '问题概要',
      source: {
        kind: 'draftField' as const,
        fieldKey: 'summary' as const
      },
      enabled: true
    }])
    expect(resetJiraCsvTemplate(storage).map((field) => field.header)).toContain('概要')
  })
})

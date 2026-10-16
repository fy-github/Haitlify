import { describe, expect, it } from 'vitest'
import type { ApiAutomationCaseDraft } from './apiAutomation'
import {
  deleteAutomationCases,
  deleteAutomationCasesByScope,
  deleteSavedAutomationCase,
  listSavedAutomationCases,
  saveAutomationCases
} from './automationRepository'

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

const makeAutomationCase = (
  id: string,
  project = 'Haitlify',
  module = '项目成员'
): ApiAutomationCaseDraft => ({
  id,
  sourceCaseId: id.replace(/^AUTO-/, ''),
  project,
  module,
  name: `${id} 自动化用例`,
  method: 'GET',
  path: `/api/${id}`,
  headers: {},
  pathParams: {},
  queryParams: {},
  expectedStatus: 200,
  assertions: ['响应状态码为 200'],
  runOrder: Number(id.replace(/\D/g, '')) || 1,
  exportTarget: 'apifox'
})

describe('automationRepository', () => {
  it('saves automation cases and avoids duplicates on repeated conversion', () => {
    const storage = new MemoryStorage()

    const first = saveAutomationCases(storage, [makeAutomationCase('AUTO-1'), makeAutomationCase('AUTO-2')], {
      savedAt: '2026-06-11T08:00:00.000Z'
    })
    const second = saveAutomationCases(storage, [makeAutomationCase('AUTO-1')], {
      savedAt: '2026-06-11T09:00:00.000Z'
    })

    expect(first).toEqual({
      inserted: 2,
      updated: 0,
      total: 2
    })
    expect(second).toEqual({
      inserted: 0,
      updated: 1,
      total: 2
    })
  })

  it('deletes single automation cases and bulk deletes by project/module', () => {
    const storage = new MemoryStorage()
    saveAutomationCases(storage, [
      makeAutomationCase('AUTO-1', 'Haitlify', '项目成员'),
      makeAutomationCase('AUTO-2', 'Haitlify', '项目管理'),
      makeAutomationCase('AUTO-3', 'Ucenter', '用户')
    ])

    const saved = listSavedAutomationCases(storage)
    expect(deleteSavedAutomationCase(storage, saved[0].automationLibraryId)).toBe(true)
    expect(deleteAutomationCasesByScope(storage, { project: 'Haitlify' })).toBe(1)
    expect(deleteAutomationCasesByScope(storage, { project: 'Ucenter', module: '用户' })).toBe(1)
    expect(listSavedAutomationCases(storage)).toEqual([])
  })

  it('bulk deletes automation cases by selected ids', () => {
    const storage = new MemoryStorage()
    saveAutomationCases(storage, [
      makeAutomationCase('AUTO-1', 'Haitlify', '项目成员'),
      makeAutomationCase('AUTO-2', 'Haitlify', '项目管理'),
      makeAutomationCase('AUTO-3', 'Ucenter', '用户')
    ])

    const saved = listSavedAutomationCases(storage)
    expect(deleteAutomationCases(storage, saved.slice(0, 2).map((item) => item.automationLibraryId))).toBe(2)
    expect(listSavedAutomationCases(storage).map((item) => item.id)).toEqual(['AUTO-3'])
  })
})

import { describe, expect, it } from 'vitest'
import {
  listSavedWebAutomationRunCases,
  saveWebAutomationRunCases,
  deleteWebAutomationRunCase,
  deleteWebAutomationRunCases
} from './webAutomationRepository'
import type { WebAutomationRunCase } from './webAutomation'

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

const makeRunCase = (id: string, sourceCaseId: string): WebAutomationRunCase => ({
  id,
  sourceCaseId,
  project: 'Haitlify',
  module: '登录',
  pageName: '登录页',
  name: '登录成功',
  startUrl: '/login',
  browser: 'chromium',
  viewport: '1440x900',
  authenticationMode: 'environment',
  steps: [],
  runOrder: 1,
  mockOutcome: 'passed'
})

describe('webAutomationRepository', () => {
  it('saves, updates, and deletes runnable web automation cases', () => {
    const storage = new MemoryStorage()

    const first = saveWebAutomationRunCases(storage, [makeRunCase('WEB-AUTO-1', 'WEB-0001')], {
      savedAt: '2026-06-22T10:00:00.000Z'
    })
    const second = saveWebAutomationRunCases(storage, [{
      ...makeRunCase('WEB-AUTO-1', 'WEB-0001'),
      name: '登录成功-更新'
    }], {
      savedAt: '2026-06-22T11:00:00.000Z'
    })

    expect(first.inserted).toBe(1)
    expect(second.updated).toBe(1)
    expect(listSavedWebAutomationRunCases(storage)[0].name).toBe('登录成功-更新')
    expect(deleteWebAutomationRunCase(storage, listSavedWebAutomationRunCases(storage)[0].automationLibraryId)).toBe(true)
    expect(listSavedWebAutomationRunCases(storage)).toEqual([])
  })

  it('bulk deletes runnable web automation cases', () => {
    const storage = new MemoryStorage()
    saveWebAutomationRunCases(storage, [
      makeRunCase('WEB-AUTO-1', 'WEB-0001'),
      makeRunCase('WEB-AUTO-2', 'WEB-0002')
    ])

    const saved = listSavedWebAutomationRunCases(storage)
    expect(deleteWebAutomationRunCases(storage, saved.map((item) => item.automationLibraryId))).toBe(2)
    expect(listSavedWebAutomationRunCases(storage)).toEqual([])
  })

  it('preserves design-draft source ids so design-origin automation can be identified', () => {
    const storage = new MemoryStorage()
    saveWebAutomationRunCases(storage, [
      makeRunCase('WEB-AUTO-WEB-DESIGN-0001', 'WEB-DESIGN-0001')
    ])

    const saved = listSavedWebAutomationRunCases(storage)

    expect(saved[0].sourceCaseId).toBe('WEB-DESIGN-0001')
  })
})

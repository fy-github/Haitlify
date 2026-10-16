import { describe, expect, it } from 'vitest'
import {
  WEB_RUN_SUITE_STORAGE_KEY,
  deleteWebRunSuite,
  listWebRunSuites,
  saveWebRunSuite
} from './webRunSuiteRepository'

const makeStorage = (): Storage => {
  const map = new Map<string, string>()
  return {
    get length() { return map.size },
    clear: () => map.clear(),
    getItem: (key: string) => map.get(key) ?? null,
    key: (index: number) => Array.from(map.keys())[index] ?? null,
    removeItem: (key: string) => { map.delete(key) },
    setItem: (key: string, value: string) => { map.set(key, value) }
  }
}

const suite = (overrides: Partial<Parameters<typeof saveWebRunSuite>[1]> = {}) => ({
  name: '冒烟回归',
  project: 'Haitlify',
  module: '登录',
  source: 'ALL' as const,
  status: 'ALL',
  keyword: '',
  savedAt: '2026-10-08T10:00:00Z',
  ...overrides
})

describe('webRunSuiteRepository', () => {
  it('saves, lists (newest first) and deletes suites from storage', () => {
    const storage = makeStorage()

    saveWebRunSuite(storage, suite({ name: '旧套件', savedAt: '2026-10-07T00:00:00Z' }))
    saveWebRunSuite(storage, suite({ name: '新套件', savedAt: '2026-10-08T00:00:00Z' }))

    expect(listWebRunSuites(storage).map((item) => item.name)).toEqual(['新套件', '旧套件'])

    deleteWebRunSuite(storage, '新套件')
    expect(listWebRunSuites(storage).map((item) => item.name)).toEqual(['旧套件'])
  })

  it('overwrites suites with the same name instead of duplicating them', () => {
    const storage = makeStorage()
    saveWebRunSuite(storage, suite({ status: 'ALL' }))
    saveWebRunSuite(storage, suite({ status: 'FAILED', savedAt: '2026-10-09T00:00:00Z' }))

    const suites = listWebRunSuites(storage)
    expect(suites).toHaveLength(1)
    expect(suites[0].status).toBe('FAILED')
  })

  it('tolerates corrupt storage without throwing', () => {
    const storage = makeStorage()
    storage.setItem(WEB_RUN_SUITE_STORAGE_KEY, '{not-json')
    expect(listWebRunSuites(storage)).toEqual([])
    storage.setItem(WEB_RUN_SUITE_STORAGE_KEY, JSON.stringify([{ nothing: true }, 'junk', { name: '  ' }, { name: 42 }]))
    expect(listWebRunSuites(storage)).toEqual([])
  })
})

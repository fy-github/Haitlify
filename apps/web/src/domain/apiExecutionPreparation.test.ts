import { describe, expect, it } from 'vitest'
import {
  buildApiRunSnapshot,
  parseApiLoopCount,
  resolveApiExecutionScopeLabel
} from './apiExecutionPreparation'
import type { SavedAutomationCase } from './automationRepository'

const savedCase = (id: string): SavedAutomationCase => ({
  id, sourceCaseId: id, project: '项目A', module: '模块A', name: id, method: 'GET', path: `/${id}`,
  headers: {}, pathParams: {}, queryParams: {}, expectedStatus: 200, assertions: [], runOrder: 1,
  exportTarget: 'apifox', automationLibraryId: 'lib', savedAt: '2026-01-01T00:00:00Z'
})

describe('api execution preparation', () => {
  it('accepts only positive integer loop counts and rejects blank/zero/fractional input', () => {
    expect(parseApiLoopCount('3')).toEqual({ valid: true, value: 3 })
    expect(parseApiLoopCount(' 7 ')).toEqual({ valid: true, value: 7 })
    expect(parseApiLoopCount('')).toMatchObject({ valid: false })
    expect(parseApiLoopCount('0')).toMatchObject({ valid: false })
    expect(parseApiLoopCount('1.5')).toMatchObject({ valid: false })
    expect(parseApiLoopCount('-2')).toMatchObject({ valid: false })
    expect(parseApiLoopCount('abc')).toMatchObject({ valid: false })
  })

  it('labels the real execution scope instead of calling everything "全部"', () => {
    expect(resolveApiExecutionScopeLabel(0, 12)).toBe('执行筛选 12 条')
    expect(resolveApiExecutionScopeLabel(3, 12)).toBe('执行选中 3 条')
    expect(resolveApiExecutionScopeLabel(0, 0)).toBe('无可执行用例')
  })

  it('freezes cases, environment and options so later preparation edits cannot rewrite the run', () => {
    const cases = [savedCase('a'), savedCase('b')]
    const environment = { baseUrl: 'https://fixture.test', auth: { type: 'none' as const }, entries: [] }
    const snapshot = buildApiRunSnapshot({
      cases, environment, options: { mode: 'sequential', loopCount: 1, concurrencyLimit: 8, responseBodyMatchThreshold: 100 },
      scopeLabel: '执行筛选 2 条', frozenAt: '2026-01-01T00:00:00Z'
    })
    cases[0].path = '/mutated'
    environment.baseUrl = 'https://changed.test'
    expect(snapshot.cases[0].path).toBe('/a')
    expect(snapshot.environment.baseUrl).toBe('https://fixture.test')
    expect(snapshot.scopeLabel).toBe('执行筛选 2 条')
    expect(Object.isFrozen(snapshot.cases[0])).toBe(true)
  })
})

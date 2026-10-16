import { describe, expect, it } from 'vitest'
import type { SavedAutomationCase } from './automationRepository'
import {
  buildAutomationCaseIndex,
  collectAutomationMethods,
  collectAutomationModules,
  filterAutomationRecords
} from './automationView'

const makeCase = (
  id: string,
  project: string,
  module: string,
  method: string,
  name: string
): SavedAutomationCase => ({
  id,
  sourceCaseId: id.replace(/^AUTO-/, ''),
  project,
  module,
  name,
  method,
  path: `/api/${id}`,
  headers: {},
  pathParams: {},
  queryParams: {},
  expectedStatus: 200,
  assertions: ['响应状态码为 200'],
  runOrder: 1,
  exportTarget: 'apifox',
  automationLibraryId: `${project}:${module}:${id}`,
  savedAt: '2026-06-15T00:00:00.000Z'
})

describe('automationView', () => {
  it('builds project counts once for sidebar rendering', () => {
    const index = buildAutomationCaseIndex([
      makeCase('AUTO-1', 'Haitlify', '成员', 'GET', '成员查询'),
      makeCase('AUTO-2', 'Haitlify', '项目', 'POST', '项目创建'),
      makeCase('AUTO-3', 'Ucenter', '用户', 'GET', '用户查询')
    ])

    expect(index.projectOptions).toEqual([
      { value: 'Haitlify', count: 2 },
      { value: 'Ucenter', count: 1 }
    ])
  })

  it('filters records and derives modules and methods from the filtered project scope', () => {
    const index = buildAutomationCaseIndex([
      makeCase('AUTO-1', 'Haitlify', '成员', 'GET', '成员查询'),
      makeCase('AUTO-2', 'Haitlify', '项目', 'POST', '项目创建'),
      makeCase('AUTO-3', 'Ucenter', '用户', 'GET', '用户查询')
    ])

    const filtered = filterAutomationRecords(index.records, {
      project: 'Haitlify',
      module: 'ALL',
      method: 'ALL',
      source: 'ALL',
      keyword: '项目'
    })

    expect(filtered.map((record) => record.caseItem.id)).toEqual(['AUTO-2'])
    expect(collectAutomationModules(index.records.filter((record) => record.project === 'Haitlify'))).toEqual([
      { value: '成员', count: 1 },
      { value: '项目', count: 1 }
    ])
    expect(collectAutomationMethods(index.records.filter((record) => record.project === 'Haitlify'))).toEqual(['GET', 'POST'])
  })

  it('indexes design-origin automation records in search text', () => {
    const caseItem = makeCase('AUTO-9', 'Haitlify', '设计稿交互', 'GET', '设计稿-主按钮跳转')
    caseItem.exportTarget = 'design-draft'

    const index = buildAutomationCaseIndex([caseItem])

    expect(index.records[0].searchText).toContain('design-draft')
    expect(index.records[0].searchText).toContain('设计稿-主按钮跳转')
    expect(index.records[0].source).toBe('design')
  })

  it('filters automation records by source marker', () => {
    const designCase = makeCase('AUTO-9', 'Haitlify', '设计稿交互', 'GET', '设计稿-主按钮跳转')
    designCase.exportTarget = 'design-draft'
    const normalCase = makeCase('AUTO-2', 'Haitlify', '项目', 'POST', '项目创建')

    const index = buildAutomationCaseIndex([designCase, normalCase])
    const filtered = filterAutomationRecords(index.records, {
      project: 'ALL',
      module: 'ALL',
      method: 'ALL',
      source: 'design',
      keyword: ''
    })

    expect(filtered.map((record) => record.caseItem.id)).toEqual(['AUTO-9'])
  })
})

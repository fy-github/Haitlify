import { describe, expect, it } from 'vitest'
import type { SavedInterfaceCase } from './caseRepository'
import {
  applyBulkCaseFieldUpdate,
  applyJsonFieldAssignment,
  collectJsonFieldAssignments
} from './caseBulkEdit'

describe('caseBulkEdit', () => {
  it('updates the target field only when the match field contains the expected value', () => {
    const cases = [makeCase('API-0001', '项目'), makeCase('API-0002', '成员')]

    const result = applyBulkCaseFieldUpdate(cases, {
      matchField: 'module',
      matchMode: 'contains',
      matchValue: '项目',
      targetField: 'priority',
      targetValue: 'P0'
    })

    expect(result.updated).toBe(1)
    expect(result.cases.map((testCase) => testCase.row.priority)).toEqual(['P0', 'P1'])
  })

  it('collects JSON paths from large fields and assigns a new value into rows and metadata', () => {
    const cases = [makeCase('API-0001', '项目')]

    const assignments = collectJsonFieldAssignments(cases)
    expect(assignments.map((item) => `${item.fieldKey}:${item.path}`)).toContain('queryParams:keyword')
    expect(assignments.map((item) => `${item.fieldKey}:${item.path}`)).toContain('body:filter.owner.id')

    const result = applyJsonFieldAssignment(cases, {
      fieldKey: 'body',
      path: 'filter.owner.id',
      value: 'u-2'
    })

    expect(result.updated).toBe(1)
    expect(JSON.parse(result.cases[0].row.body).filter.owner.id).toBe('u-2')
    expect(result.cases[0].metadata.body).toMatchObject({
      filter: {
        owner: {
          id: 'u-2'
        }
      }
    })
  })
})

function makeCase(caseId: string, module: string): SavedInterfaceCase {
  return {
    id: caseId,
    libraryId: `library-${caseId}`,
    savedAt: '2026-06-15T00:00:00.000Z',
    row: {
      caseId,
      project: 'Haitlify',
      module,
      interfaceName: '查询项目',
      method: 'GET',
      path: '/projects',
      title: `${module}-查询项目`,
      caseType: '正向',
      precondition: '',
      headers: '{}',
      pathParams: '{"projectId":"p-1"}',
      queryParams: '{"keyword":"旧项目"}',
      body: '{"filter":{"owner":{"id":"u-1"}}}',
      steps: '',
      expectedResult: '',
      assertions: '',
      expectedResponseBody: '{"code":0}',
      actualResponseBody: '',
      priority: 'P1',
      executionResult: '未执行',
      executor: '',
      executedAt: '',
      defectId: '',
      tags: '',
      sourceFile: 'openapi.json',
      remark: ''
    },
    metadata: {
      sourceSpecId: `openapi.json:${caseId}`,
      sourceFile: 'openapi.json',
      sourceOperationId: caseId,
      tag: module,
      method: 'GET',
      path: '/projects',
      baseUrlRef: '',
      environmentId: '',
      authProfileId: '',
      contentType: 'application/json',
      headers: {},
      pathParams: { projectId: 'p-1' },
      queryParams: { keyword: '旧项目' },
      body: {
        filter: {
          owner: {
            id: 'u-1'
          }
        }
      },
      expectedStatus: 200,
      assertions: ['响应状态码为 200'],
      expectedBodyRules: [],
      expectedResponseBody: { code: 0 },
      variableExtractions: [],
      dependsOnCaseIds: [],
      runOrder: 1,
      generationMeta: {
        granularity: 'risk',
        strategy: 'deterministic-risk-rules'
      },
      reviewMeta: {
        status: '待复核'
      },
      exportMeta: {
        apifoxReady: false
      }
    }
  }
}

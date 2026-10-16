import { describe, expect, it } from 'vitest'
import type { CaseField } from './caseTemplates'
import type { GeneratedInterfaceCase } from './caseGenerator'
import { applyInterfaceFieldTemplate } from './caseTemplateApplication'

const fields: CaseField[] = [
  field('caseId', true),
  field('project', true),
  field('module', true),
  field('title', true),
  field('headers', false),
  field('queryParams', false),
  field('body', false),
  field('expectedResponseBody', false),
  field('actualResponseBody', false)
]

describe('caseTemplateApplication', () => {
  it('clears hidden generated fields from rows and executable metadata', () => {
    const testCase = makeCase()

    const result = applyInterfaceFieldTemplate(testCase, fields)

    expect(result.row.headers).toBe('')
    expect(result.row.queryParams).toBe('')
    expect(result.row.body).toBe('')
    expect(result.row.expectedResponseBody).toBe('')
    expect(result.metadata.headers).toEqual({})
    expect(result.metadata.queryParams).toEqual({})
    expect(result.metadata.body).toBeUndefined()
    expect(result.metadata.expectedResponseBody).toBeUndefined()
  })
})

function field(key: string, visible: boolean): CaseField {
  return {
    key,
    label: key,
    type: 'text',
    required: false,
    visible,
    exportable: true,
    aiGenerated: true
  }
}

function makeCase(): GeneratedInterfaceCase {
  return {
    id: 'API-0001',
    row: {
      caseId: 'API-0001',
      project: 'Haitlify',
      module: '项目',
      interfaceName: '项目详情',
      method: 'GET',
      path: '/projects/{id}',
      title: '项目详情-正常请求成功',
      caseType: '正向',
      precondition: '',
      headers: '{"Authorization":"Bearer token"}',
      pathParams: '{"id":"p-1"}',
      queryParams: '{"keyword":"项目"}',
      body: '{"name":"项目"}',
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
      sourceSpecId: 'openapi.json:getProject',
      sourceFile: 'openapi.json',
      sourceOperationId: 'getProject',
      tag: '项目',
      method: 'GET',
      path: '/projects/{id}',
      baseUrlRef: '',
      environmentId: '',
      authProfileId: '',
      contentType: 'application/json',
      headers: { Authorization: 'Bearer token' },
      pathParams: { id: 'p-1' },
      queryParams: { keyword: '项目' },
      body: { name: '项目' },
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

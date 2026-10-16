import { describe, expect, it } from 'vitest'
import type { GeneratedInterfaceCase } from './caseGenerator'
import { markCasesAsAiReviewed } from './caseEnrichment'

const makeCase = (overrides: Partial<GeneratedInterfaceCase['row']> = {}): GeneratedInterfaceCase => ({
  id: 'API-0001',
  row: {
    caseId: 'API-0001',
    project: 'Haitlify',
    module: '项目成员',
    interfaceName: '新增项目成员',
    method: 'POST',
    path: '/api/projects/{projectId}/members',
    title: '新增项目成员-正常请求成功',
    caseType: '正向',
    precondition: '',
    headers: '{}',
    pathParams: '{}',
    queryParams: '{}',
    body: '{}',
    steps: '1. 请求接口',
    expectedResult: '返回成功',
    assertions: '响应状态码为 201',
    priority: 'P1',
    executionResult: '未执行',
    executor: '',
    executedAt: '',
    defectId: '',
    tags: '项目成员,正向,AI生成',
    sourceFile: 'Haitlify.openapi.json',
    remark: '',
    ...overrides
  },
  metadata: {
    sourceSpecId: 'Haitlify.openapi.json:createProjectMember',
    sourceFile: 'Haitlify.openapi.json',
    sourceOperationId: 'createProjectMember',
    tag: '项目成员',
    method: 'POST',
    path: '/api/projects/{projectId}/members',
    baseUrlRef: '',
    environmentId: '',
    authProfileId: '',
    contentType: 'application/json',
    headers: {},
    pathParams: {},
    queryParams: {},
    body: {},
    expectedStatus: 201,
    assertions: ['响应状态码为 201'],
    expectedBodyRules: [],
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
})

describe('caseEnrichment', () => {
  it('adds AI reviewed tag only once', () => {
    const [reviewed] = markCasesAsAiReviewed([
      makeCase(),
      makeCase({ caseId: 'API-0002', tags: '项目成员,AI生成,AI已评审' })
    ])

    expect(reviewed.row.tags).toContain('AI已评审')
    expect(reviewed.row.tags.split('AI已评审')).toHaveLength(2)
  })
})

import { describe, expect, it } from 'vitest'
import { generateInterfaceCases } from './caseGenerator'
import type { CaseReviewFinding } from './caseReview'
import { supplementMissingCaseTypes } from './caseSupplement'
import type { ParsedOperation } from './openapiParser'

const operation: ParsedOperation = {
  id: 'post /api/projects/{projectId}/members',
  module: '项目成员',
  method: 'POST',
  path: '/api/projects/{projectId}/members',
  summary: '新增项目成员',
  operationId: 'createProjectMember',
  parameters: [
    { name: 'projectId', in: 'path', required: true, type: 'string', description: '项目ID' },
    { name: 'Authorization', in: 'header', required: true, type: 'string', description: 'Token' }
  ],
  requestBody: {
    required: true,
    contentTypes: ['application/json'],
    requiredFields: ['userId'],
    properties: {
      userId: 'string'
    },
    example: {
      userId: 'u-1'
    }
  },
  responseStatuses: ['201', '400', '401', '403', '404', '409']
}

const getOperationWithoutNotFound: ParsedOperation = {
  ...operation,
  id: 'get /api/projects',
  method: 'GET',
  path: '/api/projects',
  summary: '项目列表',
  operationId: 'listProjects',
  responseStatuses: ['200', '400', '401', '403']
}

const finding = (caseType: string): CaseReviewFinding => ({
  id: `项目成员/新增项目成员:missing:${caseType}`,
  code: 'missing_case_type',
  severity: 'medium',
  scope: '项目成员/新增项目成员',
  message: `项目成员/新增项目成员 缺少 ${caseType} 用例`
})

describe('supplementMissingCaseTypes', () => {
  it('generates missing case type supplements for matching operations', () => {
    const existingCases = generateInterfaceCases({
      project: 'Haitlify',
      sourceFile: 'Haitlify.openapi.json',
      operations: [operation],
      granularity: 'basic'
    })

    const supplemented = supplementMissingCaseTypes({
      existingCases,
      operations: [operation],
      findings: [finding('权限不足'), finding('重复提交')]
    })

    expect(supplemented.map((testCase) => testCase.row.caseType)).toEqual(['权限不足', '重复提交'])
    expect(supplemented[0].row.caseId).toBe('API-0004')
    expect(supplemented[1].row.caseId).toBe('API-0005')
  })

  it('does not duplicate case types that already exist', () => {
    const existingCases = generateInterfaceCases({
      project: 'Haitlify',
      sourceFile: 'Haitlify.openapi.json',
      operations: [operation],
      granularity: 'risk'
    })

    const supplemented = supplementMissingCaseTypes({
      existingCases,
      operations: [operation],
      findings: [finding('权限不足')]
    })

    expect(supplemented).toEqual([])
  })

  it('falls back to generic supplements for case types not produced by the normal generator', () => {
    const existingCases = generateInterfaceCases({
      project: 'Haitlify',
      sourceFile: 'Haitlify.openapi.json',
      operations: [getOperationWithoutNotFound],
      granularity: 'risk'
    })

    const supplemented = supplementMissingCaseTypes({
      existingCases,
      operations: [getOperationWithoutNotFound],
      findings: [
        {
          ...finding('数据不存在'),
          id: '项目成员/项目列表:missing:数据不存在',
          scope: '项目成员/项目列表',
          message: '项目成员/项目列表 缺少 数据不存在 用例'
        }
      ]
    })

    expect(supplemented).toHaveLength(1)
    expect(supplemented[0].row.caseType).toBe('数据不存在')
    expect(supplemented[0].metadata.expectedStatus).toBe(404)
  })
})

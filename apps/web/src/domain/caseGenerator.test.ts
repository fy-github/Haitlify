import { describe, expect, it } from 'vitest'
import type { ParsedOperation } from './openapiParser'
import { generateInterfaceCases } from './caseGenerator'

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
      userId: 'string',
      role: 'string'
    },
    example: {
      userId: 'u-1',
      role: 'admin'
    }
  },
  responseStatuses: ['201', '400', '401', '403', '404', '409'],
  expectedResponseBody: {
    code: 0,
    data: {
      userId: 'u-1'
    }
  }
}

describe('generateInterfaceCases', () => {
  it('generates risk-based interface cases with Chinese visible rows and executable metadata', () => {
    const cases = generateInterfaceCases({
      project: 'Haitlify',
      sourceFile: 'Haitlify.openapi.json',
      operations: [operation],
      granularity: 'risk'
    })

    expect(cases.map((testCase) => testCase.row.caseType)).toEqual([
      '正向',
      '必填缺失',
      '参数类型错误',
      '鉴权缺失',
      '权限不足',
      '数据不存在',
      '重复提交',
      '业务规则异常'
    ])
    expect(cases[0].row).toMatchObject({
      caseId: 'API-0001',
      project: 'Haitlify',
      module: '项目成员',
      interfaceName: '新增项目成员',
      method: 'POST',
      path: '/api/projects/{projectId}/members',
      executionResult: '未执行',
      sourceFile: 'Haitlify.openapi.json'
    })
    expect(cases[0].metadata).toMatchObject({
      sourceFile: 'Haitlify.openapi.json',
      sourceOperationId: 'createProjectMember',
      method: 'POST',
      path: '/api/projects/{projectId}/members',
      expectedStatus: 201,
      expectedResponseBody: {
        code: 0,
        data: {
          userId: 'u-1'
        }
      }
    })
    expect(cases[0].row.expectedResponseBody).toContain('"userId": "u-1"')
    expect(cases[0].row.actualResponseBody).toBe('')
    expect(cases[3].metadata.headers.Authorization).toBe('')
  })

  it('uses fewer cases for basic and adds boundary cases for full granularity', () => {
    const basicCases = generateInterfaceCases({
      project: 'Haitlify',
      sourceFile: 'Haitlify.openapi.json',
      operations: [operation],
      granularity: 'basic'
    })
    const fullCases = generateInterfaceCases({
      project: 'Haitlify',
      sourceFile: 'Haitlify.openapi.json',
      operations: [operation],
      granularity: 'full'
    })

    expect(basicCases.map((testCase) => testCase.row.caseType)).toEqual([
      '正向',
      '必填缺失',
      '鉴权缺失'
    ])
    expect(fullCases.some((testCase) => testCase.row.caseType === '边界值')).toBe(true)
    expect(fullCases.length).toBeGreaterThan(basicCases.length)
  })
})

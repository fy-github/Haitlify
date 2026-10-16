import { describe, expect, it } from 'vitest'
import type { GeneratedInterfaceCase } from './caseGenerator'
import { generateInterfaceCases } from './caseGenerator'
import { reviewGeneratedCases } from './caseReview'
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
      userId: 'string',
      role: 'string'
    },
    example: {
      userId: 'u-1',
      role: 'admin'
    }
  },
  responseStatuses: ['201', '400', '401', '403', '404', '409']
}

const expectedTypes = [
  '正向',
  '必填缺失',
  '参数类型错误',
  '鉴权缺失',
  '权限不足',
  '数据不存在',
  '重复提交',
  '业务规则异常'
]

describe('reviewGeneratedCases', () => {
  it('reports missing case type coverage per interface', () => {
    const cases = generateInterfaceCases({
      project: 'Haitlify',
      sourceFile: 'Haitlify.openapi.json',
      operations: [operation],
      granularity: 'basic'
    })

    const findings = reviewGeneratedCases(cases, { expectedCaseTypes: expectedTypes })

    expect(findings.some((finding) => finding.message.includes('缺少 参数类型错误'))).toBe(true)
    expect(findings.some((finding) => finding.message.includes('缺少 权限不足'))).toBe(true)
    expect(findings.some((finding) => finding.severity === 'high')).toBe(true)
  })

  it('reports weak assertions and expected results', () => {
    const [testCase] = generateInterfaceCases({
      project: 'Haitlify',
      sourceFile: 'Haitlify.openapi.json',
      operations: [operation],
      granularity: 'risk'
    })
    const weakCase: GeneratedInterfaceCase = {
      ...testCase,
      row: {
        ...testCase.row,
        assertions: '',
        expectedResult: '成功'
      },
      metadata: {
        ...testCase.metadata,
        assertions: []
      }
    }

    const findings = reviewGeneratedCases([weakCase], { expectedCaseTypes: ['正向'] })

    expect(findings.map((finding) => finding.code)).toContain('weak_assertion')
    expect(findings.map((finding) => finding.code)).toContain('weak_expected_result')
  })

  it('returns an info summary when generated cases pass rule review', () => {
    const cases = generateInterfaceCases({
      project: 'Haitlify',
      sourceFile: 'Haitlify.openapi.json',
      operations: [operation],
      granularity: 'risk'
    })

    const findings = reviewGeneratedCases(cases, { expectedCaseTypes: expectedTypes })

    expect(findings.every((finding) => finding.severity === 'info')).toBe(true)
    expect(findings[0].message).toContain('规则复核通过')
  })
})

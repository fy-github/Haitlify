import {
  generateInterfaceCases,
  type GeneratedInterfaceCase
} from './caseGenerator'
import type { CaseReviewFinding } from './caseReview'
import type { ParsedOperation } from './openapiParser'

export interface SupplementMissingCaseTypesInput {
  existingCases: GeneratedInterfaceCase[]
  operations: ParsedOperation[]
  findings: CaseReviewFinding[]
}

export const supplementMissingCaseTypes = (
  input: SupplementMissingCaseTypesInput
): GeneratedInterfaceCase[] => {
  const missingFindings = input.findings.filter((finding) => finding.code === 'missing_case_type')
  if (missingFindings.length === 0 || input.existingCases.length === 0) return []

  const existingKeys = new Set(input.existingCases.map(caseKey))
  const nextCaseNumber = maxCaseNumber(input.existingCases) + 1
  let supplementIndex = 0
  const supplements: GeneratedInterfaceCase[] = []
  const sampleCase = input.existingCases[0]

  for (const finding of missingFindings) {
    const caseType = missingCaseType(finding)
    const operation = input.operations.find((item) => operationScope(item) === finding.scope)
    if (!caseType || !operation) continue

    const generatedCandidate = generateInterfaceCases({
      project: sampleCase.row.project,
      sourceFile: sampleCase.metadata.sourceFile,
      operations: [operation],
      granularity: 'full'
    }).find((testCase) => testCase.row.caseType === caseType)
    const candidate = generatedCandidate || fallbackSupplementCase(input.existingCases, operation, caseType)
    if (!candidate) continue

    const key = caseKey(candidate)
    if (existingKeys.has(key)) continue
    existingKeys.add(key)

    const caseId = `API-${String(nextCaseNumber + supplementIndex).padStart(4, '0')}`
    supplements.push({
      ...candidate,
      id: caseId,
      row: {
        ...candidate.row,
        caseId,
        remark: 'AI复核补充生成'
      }
    })
    supplementIndex += 1
  }

  return supplements
}

const operationScope = (operation: ParsedOperation): string =>
  `${operation.module}/${operation.summary}`

const caseKey = (testCase: GeneratedInterfaceCase): string =>
  [
    testCase.metadata.sourceOperationId,
    testCase.row.path,
    testCase.row.caseType
  ].join(':')

const missingCaseType = (finding: CaseReviewFinding): string => {
  const marker = ' 缺少 '
  if (finding.message.includes(marker)) {
    return (finding.message.split(marker).pop() || '').replace(/\s*用例$/, '').trim()
  }
  const [, caseType = ''] = finding.id.split(':missing:')
  return caseType
}

const maxCaseNumber = (cases: GeneratedInterfaceCase[]): number =>
  cases.reduce((max, testCase) => {
    const match = testCase.row.caseId.match(/(\d+)$/)
    return Math.max(max, match ? Number(match[1]) : 0)
  }, 0)

const fallbackSupplementCase = (
  existingCases: GeneratedInterfaceCase[],
  operation: ParsedOperation,
  caseType: string
): GeneratedInterfaceCase | undefined => {
  const baseCase = existingCases.find((testCase) => testCase.metadata.sourceOperationId === operation.operationId)
  if (!baseCase) return undefined

  const expectation = fallbackExpectation(caseType)
  return {
    ...baseCase,
    row: {
      ...baseCase.row,
      title: `${operation.summary}-${caseType}`,
      caseType,
      expectedResult: expectation.expectedResult,
      assertions: `响应状态码为 ${expectation.expectedStatus}`,
      priority: expectation.priority,
      tags: [operation.module, caseType, 'AI复核补充'].join(',')
    },
    metadata: {
      ...baseCase.metadata,
      expectedStatus: expectation.expectedStatus,
      assertions: [`响应状态码为 ${expectation.expectedStatus}`]
    }
  }
}

const fallbackExpectation = (caseType: string): {
  expectedStatus: number
  expectedResult: string
  priority: string
} => {
  const expectations: Record<string, { expectedStatus: number; expectedResult: string; priority: string }> = {
    数据不存在: {
      expectedStatus: 404,
      expectedResult: '接口返回数据不存在错误，不产生脏数据。',
      priority: 'P1'
    },
    重复提交: {
      expectedStatus: 409,
      expectedResult: '接口识别重复请求并返回冲突或幂等处理结果。',
      priority: 'P1'
    },
    边界值: {
      expectedStatus: 400,
      expectedResult: '接口对边界值进行明确校验，不产生异常数据。',
      priority: 'P2'
    }
  }
  return expectations[caseType] || {
    expectedStatus: 400,
    expectedResult: '接口返回明确的业务错误，不产生异常数据。',
    priority: 'P1'
  }
}

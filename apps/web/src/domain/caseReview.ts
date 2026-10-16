import type { GeneratedInterfaceCase } from './caseGenerator'

export type ReviewSeverity = 'high' | 'medium' | 'low' | 'info'

export interface CaseReviewFinding {
  id: string
  code: 'missing_case_type' | 'weak_assertion' | 'weak_expected_result' | 'review_passed'
  severity: ReviewSeverity
  scope: string
  message: string
}

export interface ReviewGeneratedCasesOptions {
  expectedCaseTypes: string[]
}

export const reviewGeneratedCases = (
  cases: GeneratedInterfaceCase[],
  options: ReviewGeneratedCasesOptions
): CaseReviewFinding[] => {
  const findings: CaseReviewFinding[] = []
  const grouped = groupByOperation(cases)

  for (const [scope, operationCases] of grouped.entries()) {
    const existingTypes = new Set(operationCases.map((testCase) => testCase.row.caseType))

    for (const expectedType of options.expectedCaseTypes) {
      if (!existingTypes.has(expectedType)) {
        findings.push({
          id: `${scope}:missing:${expectedType}`,
          code: 'missing_case_type',
          severity: severityForCaseType(expectedType),
          scope,
          message: `${scope} 缺少 ${expectedType} 用例`
        })
      }
    }
  }

  for (const testCase of cases) {
    const scope = caseScope(testCase)
    if (!testCase.row.assertions.trim() || testCase.metadata.assertions.length === 0) {
      findings.push({
        id: `${testCase.id}:weak_assertion`,
        code: 'weak_assertion',
        severity: 'medium',
        scope,
        message: `${testCase.row.caseId} 断言规则为空或过弱`
      })
    }

    if (testCase.row.expectedResult.trim().length < 6) {
      findings.push({
        id: `${testCase.id}:weak_expected_result`,
        code: 'weak_expected_result',
        severity: 'low',
        scope,
        message: `${testCase.row.caseId} 预期结果过于简略`
      })
    }
  }

  if (findings.length === 0) {
    return [
      {
        id: 'review_passed',
        code: 'review_passed',
        severity: 'info',
        scope: '全部接口',
        message: `规则复核通过，已检查 ${cases.length} 条用例`
      }
    ]
  }

  return findings
}

const groupByOperation = (cases: GeneratedInterfaceCase[]): Map<string, GeneratedInterfaceCase[]> => {
  const grouped = new Map<string, GeneratedInterfaceCase[]>()
  for (const testCase of cases) {
    const scope = caseScope(testCase)
    grouped.set(scope, [...(grouped.get(scope) || []), testCase])
  }
  return grouped
}

const caseScope = (testCase: GeneratedInterfaceCase): string =>
  `${testCase.row.module}/${testCase.row.interfaceName}`

const severityForCaseType = (caseType: string): ReviewSeverity => {
  if (['鉴权缺失', '权限不足'].includes(caseType)) return 'high'
  if (['必填缺失', '参数类型错误', '数据不存在', '重复提交', '业务规则异常'].includes(caseType)) {
    return 'medium'
  }
  return 'low'
}

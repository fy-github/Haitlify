import type { WebAutomationRunReport } from './webAutomation'

type DesignOriginFailureResult = WebAutomationRunReport['results'][number] & {
  status: 'failed' | 'error'
}

export interface DesignOriginFailureItem {
  caseId: string
  name: string
  module: string
  pageName: string
  status: 'failed' | 'error'
  errorMessage: string
}

export const mapDesignOriginFailureItems = (
  results: WebAutomationRunReport['results']
): DesignOriginFailureItem[] =>
  results.filter(isDesignOriginFailureResult).map((result) => ({
    caseId: result.caseId,
    name: result.name,
    module: result.module,
    pageName: result.pageName,
    status: result.status,
    errorMessage: result.errorMessage || (result.status === 'failed' ? '断言失败' : '执行异常')
  }))

const isDesignOriginFailureResult = (
  result: WebAutomationRunReport['results'][number]
): result is DesignOriginFailureResult =>
  result.status === 'failed' || result.status === 'error'

import type { SavedWebAutomationRunCase } from './webAutomationRepository'
import type { WebAutomationRunReport } from './webAutomation'
import { resolveWebRunCaseId } from './webAutomationRunView'

export const isDesignOriginWebCase = (caseType: string, tags: string, remark = ''): boolean =>
  caseType === '设计自动化' || tags.includes('设计自动化') || remark.includes('来源于设计稿')

export const isDesignOriginAutomationCase = (sourceCaseId: string, exportTarget = ''): boolean =>
  sourceCaseId.startsWith('WEB-DESIGN-') || exportTarget === 'design-draft'

export const collectFailedDesignOriginResults = (
  cases: SavedWebAutomationRunCase[],
  report: WebAutomationRunReport | undefined
): WebAutomationRunReport['results'] => {
  if (!report) return []
  const designCaseIds = new Set(
    cases
      .filter((testCase) => isDesignOriginAutomationCase(testCase.sourceCaseId))
      .map((testCase) => testCase.id)
  )
  return report.results.filter((result) =>
    resolveWebRunCaseId(result.caseId, designCaseIds) !== undefined && (result.status === 'failed' || result.status === 'error')
  )
}

export const summarizeFailedDesignOriginResults = (
  results: WebAutomationRunReport['results']
): { total: number; failed: number; error: number } => ({
  total: results.length,
  failed: results.filter((item) => item.status === 'failed').length,
  error: results.filter((item) => item.status === 'error').length
})

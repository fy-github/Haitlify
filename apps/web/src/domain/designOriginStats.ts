import { isDesignOriginAutomationCase, isDesignOriginWebCase } from './designOrigin'
import type { SavedWebAutomationCase } from './webAutomationCase'
import type { SavedWebAutomationRunCase } from './webAutomationRepository'

export interface DesignOriginStats {
  designLibraryCases: number
  otherLibraryCases: number
  designAutomationCases: number
  otherAutomationCases: number
}

export const summarizeDesignOriginStats = (
  libraryCases: SavedWebAutomationCase[],
  automationCases: SavedWebAutomationRunCase[]
): DesignOriginStats => ({
  designLibraryCases: libraryCases.filter((testCase) =>
    isDesignOriginWebCase(testCase.row.caseType, testCase.row.tags, testCase.row.remark)
  ).length,
  otherLibraryCases: libraryCases.filter((testCase) =>
    !isDesignOriginWebCase(testCase.row.caseType, testCase.row.tags, testCase.row.remark)
  ).length,
  designAutomationCases: automationCases.filter((testCase) =>
    isDesignOriginAutomationCase(testCase.sourceCaseId)
  ).length,
  otherAutomationCases: automationCases.filter((testCase) =>
    !isDesignOriginAutomationCase(testCase.sourceCaseId)
  ).length
})

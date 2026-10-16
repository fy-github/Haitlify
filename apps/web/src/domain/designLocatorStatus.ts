import type { SavedWebAutomationCase } from './webAutomationCase'

export const hasPendingDesignLocators = (cases: SavedWebAutomationCase[]): boolean =>
  cases.some((testCase) => testCase.metadata.locators.some((locator) => !locator.confirmed))

export const countPendingDesignLocators = (cases: SavedWebAutomationCase[]): number =>
  cases.filter((testCase) => testCase.metadata.locators.some((locator) => !locator.confirmed)).length

export const confirmLocatorCandidates = (cases: SavedWebAutomationCase[]): SavedWebAutomationCase[] =>
  cases.map((testCase) => ({
    ...testCase,
    metadata: {
      ...testCase.metadata,
      locators: testCase.metadata.locators.map((locator) => ({
        ...locator,
        confirmed: true
      }))
    }
  }))

export const summarizeDesignLocatorProgress = (cases: SavedWebAutomationCase[]): {
  totalCases: number
  confirmedCases: number
  pendingCases: number
  totalLocators: number
  confirmedLocators: number
} => {
  const totalCases = cases.length
  const confirmedCases = cases.filter((testCase) =>
    testCase.metadata.locators.length > 0 && testCase.metadata.locators.every((locator) => locator.confirmed)
  ).length
  const totalLocators = cases.reduce((sum, testCase) => sum + testCase.metadata.locators.length, 0)
  const confirmedLocators = cases.reduce(
    (sum, testCase) => sum + testCase.metadata.locators.filter((locator) => locator.confirmed).length,
    0
  )

  return {
    totalCases,
    confirmedCases,
    pendingCases: totalCases - confirmedCases,
    totalLocators,
    confirmedLocators
  }
}

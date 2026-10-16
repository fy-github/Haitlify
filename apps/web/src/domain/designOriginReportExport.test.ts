import { describe, expect, it } from 'vitest'
import { exportDesignOriginSummaryCsv } from './designOriginReportExport'

describe('designOriginReportExport', () => {
  it('exports design-origin summary stats and locator progress as csv', () => {
    const csv = exportDesignOriginSummaryCsv(
      {
        designLibraryCases: 8,
        otherLibraryCases: 12,
        designAutomationCases: 5,
        otherAutomationCases: 9
      },
      {
        totalCases: 8,
        confirmedCases: 6,
        pendingCases: 2,
        totalLocators: 24,
        confirmedLocators: 20
      }
    )

    expect(csv).toContain('设计稿用例,8')
    expect(csv).toContain('设计稿自动化,5')
    expect(csv).toContain('已确认用例,6')
    expect(csv).toContain('已确认 Locator,20 / 24')
  })
})

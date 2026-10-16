import type { DesignOriginStats } from './designOriginStats'

export interface DesignOriginLocatorProgress {
  totalCases: number
  confirmedCases: number
  pendingCases: number
  totalLocators: number
  confirmedLocators: number
}

export const exportDesignOriginSummaryCsv = (
  stats: DesignOriginStats,
  progress: DesignOriginLocatorProgress
): string => {
  const rows = [
    ['指标', '数值'],
    ['设计稿用例', String(stats.designLibraryCases)],
    ['非设计稿用例', String(stats.otherLibraryCases)],
    ['设计稿自动化', String(stats.designAutomationCases)],
    ['非设计稿自动化', String(stats.otherAutomationCases)],
    ['已确认用例', String(progress.confirmedCases)],
    ['待确认用例', String(progress.pendingCases)],
    ['已确认 Locator', `${progress.confirmedLocators} / ${progress.totalLocators}`]
  ]

  return `\ufeff${rows.map((row) => row.map(escapeCsv).join(',')).join('\n')}`
}

const escapeCsv = (value: string): string => {
  if (/[",\n\r]/.test(value)) {
    return `"${value.replace(/"/g, '""')}"`
  }
  return value
}

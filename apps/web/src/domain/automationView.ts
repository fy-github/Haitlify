import type { SavedAutomationCase } from './automationRepository'

export interface AutomationCaseOption {
  value: string
  count: number
}

export interface AutomationFilters {
  project: string
  module: string
  method: string
  source: 'ALL' | 'design' | 'default'
  keyword: string
}

export interface IndexedAutomationCase {
  caseItem: SavedAutomationCase
  project: string
  module: string
  method: string
  source: 'design' | 'default'
  searchText: string
}

export interface AutomationCaseIndex {
  records: IndexedAutomationCase[]
  projectOptions: AutomationCaseOption[]
}

export const buildAutomationCaseIndex = (cases: SavedAutomationCase[]): AutomationCaseIndex => {
  const projectCounts = new Map<string, number>()
  const records = cases.map((testCase) => {
    projectCounts.set(testCase.project, (projectCounts.get(testCase.project) || 0) + 1)
    const source: IndexedAutomationCase['source'] = testCase.exportTarget === 'design-draft' ? 'design' : 'default'
    return {
      caseItem: testCase,
      project: testCase.project,
      module: testCase.module,
      method: testCase.method,
      source,
      searchText: [
        testCase.id,
        testCase.sourceCaseId,
        testCase.name,
        testCase.path,
        testCase.module,
        testCase.project,
        String(testCase.expectedStatus),
        testCase.exportTarget
      ].join(' ').toLowerCase()
    }
  })

  return {
    records,
    projectOptions: Array.from(projectCounts.entries()).map(([value, count]) => ({ value, count }))
  }
}

export const filterAutomationRecords = (
  records: IndexedAutomationCase[],
  filters: AutomationFilters
): IndexedAutomationCase[] => {
  const keyword = filters.keyword.trim().toLowerCase()
  return records.filter((record) => {
    if (filters.project !== 'ALL' && record.project !== filters.project) return false
    if (filters.module !== 'ALL' && record.module !== filters.module) return false
    if (filters.method !== 'ALL' && record.method !== filters.method) return false
    if (filters.source !== 'ALL' && record.source !== filters.source) return false
    if (!keyword) return true
    return record.searchText.includes(keyword)
  })
}

export const collectAutomationModules = (records: IndexedAutomationCase[]): AutomationCaseOption[] => {
  const counts = new Map<string, number>()
  for (const record of records) {
    counts.set(record.module, (counts.get(record.module) || 0) + 1)
  }
  return Array.from(counts.entries()).map(([value, count]) => ({ value, count }))
}

export const collectAutomationMethods = (records: IndexedAutomationCase[]): string[] =>
  Array.from(new Set(records.map((record) => record.method)))

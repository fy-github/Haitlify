import type { CaseKind } from './caseTemplates'
import type { SavedInterfaceCase } from './caseRepository'
import type { SavedWebAutomationCase } from './webAutomationCase'

export type SupportedCaseLibraryKind =
  | CaseKind
  | 'app_automation'
  | 'ai_test'
  | 'performance'

export interface CaseLibraryKindOption {
  value: SupportedCaseLibraryKind
  label: string
  enabled: boolean
}

export interface CaseLibraryFilters {
  project: string
  module: string
  caseType: string
  source: '' | 'design' | 'default'
  keyword: string
}

export interface CaseLibraryOption {
  value: string
  count: number
}

export type SavedCaseLibraryItem = SavedInterfaceCase | SavedWebAutomationCase

export interface IndexedSavedCase<TCase extends SavedCaseLibraryItem = SavedCaseLibraryItem> {
  caseItem: TCase
  project: string
  module: string
  caseType: string
  source: 'design' | 'default'
  searchText: string
}

export interface CaseLibraryIndex<TCase extends SavedCaseLibraryItem = SavedCaseLibraryItem> {
  records: Array<IndexedSavedCase<TCase>>
  projectOptions: CaseLibraryOption[]
}

export const buildSupportedCaseKinds = (): CaseLibraryKindOption[] => [
  { value: 'interface', label: '接口用例', enabled: true },
  { value: 'web_automation', label: 'Web 用例', enabled: true },
  { value: 'functional', label: '功能用例', enabled: false },
  { value: 'app_automation', label: 'APP 用例', enabled: false },
  { value: 'ai_test', label: 'AI 用例', enabled: false },
  { value: 'performance', label: '性能用例', enabled: false }
]

export const buildCaseLibraryIndex = <TCase extends SavedCaseLibraryItem>(
  cases: TCase[],
  kind: SupportedCaseLibraryKind
): CaseLibraryIndex<TCase> => {
  const projectCounts = new Map<string, number>()
  const records = cases.map((testCase) => {
    const normalized = normalizeLibraryRecord(testCase, kind)

    projectCounts.set(normalized.project, (projectCounts.get(normalized.project) || 0) + 1)

    return {
      caseItem: testCase,
      project: normalized.project,
      module: normalized.module,
      caseType: normalized.caseType,
      source: normalized.source,
      searchText: normalized.searchText
    }
  })

  return {
    records,
    projectOptions: Array.from(projectCounts.entries()).map(([value, count]) => ({ value, count }))
  }
}

export const collectCaseLibraryOptions = (
  records: Array<IndexedSavedCase>,
  field: 'module' | 'caseType'
): CaseLibraryOption[] => {
  const counts = new Map<string, number>()

  for (const record of records) {
    const value = record[field]
    counts.set(value, (counts.get(value) || 0) + 1)
  }

  return Array.from(counts.entries()).map(([value, count]) => ({ value, count }))
}

export const filterCaseLibraryRecords = (
  records: Array<IndexedSavedCase>,
  filters: CaseLibraryFilters
): Array<IndexedSavedCase> => {
  const keyword = filters.keyword.trim().toLowerCase()

  return records.filter((record) => {
    if (filters.project !== 'ALL' && record.project !== filters.project) return false
    if (filters.module !== 'ALL' && record.module !== filters.module) return false
    if (filters.caseType !== 'ALL' && record.caseType !== filters.caseType) return false
    if (filters.source && record.source !== filters.source) return false
    if (!keyword) return true
    return record.searchText.includes(keyword)
  })
}

export const paginateSavedCases = <T>(items: T[], page: number, pageSize: number): T[] => {
  const normalizedPage = Math.max(1, page)
  const normalizedPageSize = Math.max(1, pageSize)
  const start = (normalizedPage - 1) * normalizedPageSize
  return items.slice(start, start + normalizedPageSize)
}

const normalizeLibraryRecord = (testCase: SavedCaseLibraryItem, kind: SupportedCaseLibraryKind) => {
  if (kind === 'web_automation') {
    const webCase = testCase as SavedWebAutomationCase
    return {
      project: webCase.row.project,
      module: webCase.row.module,
      caseType: webCase.row.caseType,
      source: webCase.row.tags.includes('设计自动化') || webCase.row.remark.includes('来源于设计稿') ? 'design' as const : 'default' as const,
      searchText: [
        webCase.row.caseId,
        webCase.row.title,
        webCase.row.pageName,
        webCase.row.startUrl,
        webCase.row.tags,
        webCase.row.sourceFile,
        webCase.row.remark
      ]
        .join(' ')
        .toLowerCase()
    }
  }

  const interfaceCase = testCase as SavedInterfaceCase
  return {
    project: interfaceCase.row.project,
    module: interfaceCase.row.module,
    caseType: interfaceCase.row.caseType,
    source: interfaceCase.row.tags.includes('设计自动化') || interfaceCase.row.remark.includes('来源于设计稿') ? 'design' as const : 'default' as const,
    searchText: [
      interfaceCase.row.caseId,
      interfaceCase.row.title,
      interfaceCase.row.path,
      interfaceCase.row.interfaceName,
      interfaceCase.row.tags,
      interfaceCase.row.sourceFile,
      interfaceCase.row.remark
    ]
      .join(' ')
      .toLowerCase()
  }
}

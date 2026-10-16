import type { ApiAutomationCaseDraft } from './apiAutomation'

export const AUTOMATION_LIBRARY_STORAGE_KEY = 'ai-test-platform.automation-library.v1'

export interface SavedAutomationCase extends ApiAutomationCaseDraft {
  automationLibraryId: string
  savedAt: string
}

export interface SaveAutomationCasesOptions {
  savedAt?: string
}

export interface SaveAutomationCasesResult {
  inserted: number
  updated: number
  total: number
}

export const listSavedAutomationCases = (storage: Storage): SavedAutomationCase[] =>
  readAutomationCases(storage).sort((left, right) => {
    const orderDelta = left.runOrder - right.runOrder
    return orderDelta === 0 ? right.savedAt.localeCompare(left.savedAt) : orderDelta
  })

export const saveAutomationCases = (
  storage: Storage,
  cases: ApiAutomationCaseDraft[],
  options: SaveAutomationCasesOptions = {}
): SaveAutomationCasesResult => {
  const savedAt = options.savedAt || new Date().toISOString()
  const current = readAutomationCases(storage)
  const byId = new Map(current.map((testCase) => [testCase.automationLibraryId, testCase]))
  let inserted = 0
  let updated = 0

  for (const testCase of cases) {
    const automationLibraryId = buildAutomationLibraryId(testCase)
    if (byId.has(automationLibraryId)) {
      updated += 1
    } else {
      inserted += 1
    }
    byId.set(automationLibraryId, {
      ...testCase,
      automationLibraryId,
      savedAt
    })
  }

  const next = Array.from(byId.values())
  writeAutomationCases(storage, next)

  return {
    inserted,
    updated,
    total: next.length
  }
}

export const deleteSavedAutomationCase = (storage: Storage, automationLibraryId: string): boolean => {
  const current = readAutomationCases(storage)
  const next = current.filter((testCase) => testCase.automationLibraryId !== automationLibraryId)
  writeAutomationCases(storage, next)
  return next.length !== current.length
}

export const deleteAutomationCases = (storage: Storage, automationLibraryIds: string[]): number => {
  if (automationLibraryIds.length === 0) return 0

  const deleting = new Set(automationLibraryIds)
  const current = readAutomationCases(storage)
  const next = current.filter((testCase) => !deleting.has(testCase.automationLibraryId))
  writeAutomationCases(storage, next)
  return current.length - next.length
}

export const deleteAutomationCasesByScope = (
  storage: Storage,
  scope: { project?: string; module?: string }
): number => {
  const current = readAutomationCases(storage)
  const next = current.filter((testCase) => {
    if (scope.project && testCase.project !== scope.project) return true
    if (scope.module && testCase.module !== scope.module) return true
    return false
  })
  writeAutomationCases(storage, next)
  return current.length - next.length
}

const buildAutomationLibraryId = (testCase: ApiAutomationCaseDraft): string =>
  [testCase.project, testCase.module, testCase.path, testCase.method, testCase.sourceCaseId].join(':')

const readAutomationCases = (storage: Storage): SavedAutomationCase[] => {
  const raw = storage.getItem(AUTOMATION_LIBRARY_STORAGE_KEY)
  if (!raw) return []

  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter(isSavedAutomationCase) : []
  } catch {
    return []
  }
}

const writeAutomationCases = (storage: Storage, cases: SavedAutomationCase[]) => {
  storage.setItem(AUTOMATION_LIBRARY_STORAGE_KEY, JSON.stringify(cases))
}

const isSavedAutomationCase = (value: unknown): value is SavedAutomationCase => {
  if (!value || typeof value !== 'object') return false
  const record = value as Record<string, unknown>
  return typeof record.automationLibraryId === 'string' && typeof record.savedAt === 'string'
}

import type { GeneratedInterfaceCase } from './caseGenerator'
import {
  buildWebAutomationLibraryId,
  type GeneratedWebAutomationCase,
  type SavedWebAutomationCase
} from './webAutomationCase'

export const CASE_LIBRARY_STORAGE_KEY = 'ai-test-platform.case-library.v1'
export const WEB_AUTOMATION_CASE_LIBRARY_STORAGE_KEY = 'ai-test-platform.web-automation-case-library.v1'

export interface SavedInterfaceCase extends GeneratedInterfaceCase {
  libraryId: string
  savedAt: string
}

export interface SaveCasesOptions {
  savedAt?: string
}

export interface SaveCasesResult {
  inserted: number
  updated: number
  total: number
}

export const listSavedCases = (storage: Storage): SavedInterfaceCase[] =>
  readCases(storage).sort(compareSavedCases)

export const saveGeneratedCases = (
  storage: Storage,
  cases: GeneratedInterfaceCase[],
  options: SaveCasesOptions = {}
): SaveCasesResult => {
  const savedAt = options.savedAt || new Date().toISOString()
  const current = readCases(storage)
  const byId = new Map(current.map((testCase) => [testCase.libraryId, testCase]))
  let inserted = 0
  let updated = 0

  for (const testCase of cases) {
    const libraryId = buildLibraryId(testCase)
    if (byId.has(libraryId)) {
      updated += 1
    } else {
      inserted += 1
    }
    byId.set(libraryId, {
      ...testCase,
      libraryId,
      savedAt
    })
  }

  const next = Array.from(byId.values())
  writeCases(storage, next)

  return {
    inserted,
    updated,
    total: next.length
  }
}

export const listSavedWebAutomationCases = (storage: Storage): SavedWebAutomationCase[] =>
  readWebAutomationCases(storage).sort(compareWebAutomationCases)

export const saveGeneratedWebAutomationCases = (
  storage: Storage,
  cases: GeneratedWebAutomationCase[],
  options: SaveCasesOptions = {}
): SaveCasesResult => {
  const savedAt = options.savedAt || new Date().toISOString()
  const current = readWebAutomationCases(storage)
  const byId = new Map(current.map((testCase) => [testCase.libraryId, testCase]))
  let inserted = 0
  let updated = 0

  for (const testCase of cases) {
    const libraryId = buildWebAutomationLibraryId(testCase)
    if (byId.has(libraryId)) {
      updated += 1
    } else {
      inserted += 1
    }
    byId.set(libraryId, {
      ...testCase,
      libraryId,
      savedAt
    })
  }

  const next = Array.from(byId.values())
  writeWebAutomationCases(storage, next)

  return {
    inserted,
    updated,
    total: next.length
  }
}

export const deleteSavedWebAutomationCase = (storage: Storage, libraryId: string): boolean => {
  const current = readWebAutomationCases(storage)
  const next = current.filter((testCase) => testCase.libraryId !== libraryId)
  writeWebAutomationCases(storage, next)
  return next.length !== current.length
}

export const deleteSavedWebAutomationCases = (storage: Storage, libraryIds: string[]): number => {
  if (libraryIds.length === 0) return 0

  const current = readWebAutomationCases(storage)
  const deleting = new Set(libraryIds)
  const next = current.filter((testCase) => !deleting.has(testCase.libraryId))
  writeWebAutomationCases(storage, next)
  return current.length - next.length
}

export const deleteSavedCase = (storage: Storage, libraryId: string): boolean => {
  const current = readCases(storage)
  const next = current.filter((testCase) => testCase.libraryId !== libraryId)
  writeCases(storage, next)
  return next.length !== current.length
}

export const deleteSavedCases = (storage: Storage, libraryIds: string[]): number => {
  if (libraryIds.length === 0) return 0

  const current = readCases(storage)
  const deleting = new Set(libraryIds)
  const next = current.filter((testCase) => !deleting.has(testCase.libraryId))
  writeCases(storage, next)
  return current.length - next.length
}

export const clearSavedCases = (storage: Storage): void => {
  storage.removeItem(CASE_LIBRARY_STORAGE_KEY)
}

export const saveCaseOrdering = (storage: Storage, cases: SavedInterfaceCase[]): void => {
  writeCases(storage, normalizeRunOrder(cases))
}

export const saveWebAutomationCaseOrdering = (storage: Storage, cases: SavedWebAutomationCase[]): void => {
  writeWebAutomationCases(storage, normalizeWebAutomationRunOrder(cases))
}

export const replaceSavedCasesForProject = (
  storage: Storage,
  projectName: string,
  cases: SavedInterfaceCase[]
): void => {
  const otherProjects = readCases(storage).filter((testCase) => projectNameForCase(testCase) !== projectName)
  writeCases(storage, [...otherProjects, ...cases])
}

const buildLibraryId = (testCase: GeneratedInterfaceCase): string =>
  [
    testCase.metadata.sourceFile,
    testCase.metadata.sourceOperationId,
    testCase.row.path,
    testCase.row.caseType
  ].join(':')

const readCases = (storage: Storage): SavedInterfaceCase[] => {
  const raw = storage.getItem(CASE_LIBRARY_STORAGE_KEY)
  if (!raw) return []

  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter(isSavedInterfaceCase) : []
  } catch {
    return []
  }
}

const compareSavedCases = (left: SavedInterfaceCase, right: SavedInterfaceCase): number => {
  const orderDelta = left.metadata.runOrder - right.metadata.runOrder
  return orderDelta === 0 ? right.savedAt.localeCompare(left.savedAt) : orderDelta
}

const compareWebAutomationCases = (left: SavedWebAutomationCase, right: SavedWebAutomationCase): number => {
  const orderDelta = left.metadata.runOrder - right.metadata.runOrder
  return orderDelta === 0 ? right.savedAt.localeCompare(left.savedAt) : orderDelta
}

const normalizeRunOrder = (cases: SavedInterfaceCase[]): SavedInterfaceCase[] =>
  cases.map((testCase, index) => ({
    ...testCase,
    metadata: {
      ...testCase.metadata,
      runOrder: index + 1
    }
  }))

const normalizeWebAutomationRunOrder = (cases: SavedWebAutomationCase[]): SavedWebAutomationCase[] =>
  cases.map((testCase, index) => ({
    ...testCase,
    metadata: {
      ...testCase.metadata,
      runOrder: index + 1
    }
  }))

const projectNameForCase = (testCase: SavedInterfaceCase): string =>
  testCase.row.project || '默认项目'

const writeCases = (storage: Storage, cases: SavedInterfaceCase[]) => {
  storage.setItem(CASE_LIBRARY_STORAGE_KEY, JSON.stringify(cases))
}

const readWebAutomationCases = (storage: Storage): SavedWebAutomationCase[] => {
  const raw = storage.getItem(WEB_AUTOMATION_CASE_LIBRARY_STORAGE_KEY)
  if (!raw) return []

  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter(isSavedWebAutomationCase) : []
  } catch {
    return []
  }
}

const writeWebAutomationCases = (storage: Storage, cases: SavedWebAutomationCase[]) => {
  storage.setItem(WEB_AUTOMATION_CASE_LIBRARY_STORAGE_KEY, JSON.stringify(cases))
}

const isSavedInterfaceCase = (value: unknown): value is SavedInterfaceCase => {
  if (!value || typeof value !== 'object') return false
  const record = value as Record<string, unknown>
  return typeof record.libraryId === 'string' && typeof record.savedAt === 'string'
}

const isSavedWebAutomationCase = (value: unknown): value is SavedWebAutomationCase => {
  if (!value || typeof value !== 'object') return false
  const record = value as Record<string, unknown>
  return typeof record.libraryId === 'string' && typeof record.savedAt === 'string'
}

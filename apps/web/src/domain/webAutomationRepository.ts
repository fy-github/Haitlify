import type { SaveCasesOptions, SaveCasesResult } from './caseRepository'
import type { WebAutomationRunCase } from './webAutomation'

export const WEB_AUTOMATION_LIBRARY_STORAGE_KEY = 'ai-test-platform.web-automation-library.v1'

export interface SavedWebAutomationRunCase extends WebAutomationRunCase {
  automationLibraryId: string
  savedAt: string
}

export const listSavedWebAutomationRunCases = (storage: Storage): SavedWebAutomationRunCase[] =>
  readCases(storage).sort((left, right) => {
    const orderDelta = left.runOrder - right.runOrder
    return orderDelta === 0 ? right.savedAt.localeCompare(left.savedAt) : orderDelta
  })

export const saveWebAutomationRunCases = (
  storage: Storage,
  cases: WebAutomationRunCase[],
  options: SaveCasesOptions = {}
): SaveCasesResult => {
  const savedAt = options.savedAt || new Date().toISOString()
  const current = readCases(storage)
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
  writeCases(storage, next)

  return {
    inserted,
    updated,
    total: next.length
  }
}

export const deleteWebAutomationRunCase = (storage: Storage, automationLibraryId: string): boolean => {
  const current = readCases(storage)
  const next = current.filter((testCase) => testCase.automationLibraryId !== automationLibraryId)
  writeCases(storage, next)
  return next.length !== current.length
}

export const deleteWebAutomationRunCases = (storage: Storage, automationLibraryIds: string[]): number => {
  if (automationLibraryIds.length === 0) return 0

  const deleting = new Set(automationLibraryIds)
  const current = readCases(storage)
  const next = current.filter((testCase) => !deleting.has(testCase.automationLibraryId))
  writeCases(storage, next)
  return current.length - next.length
}

const buildAutomationLibraryId = (testCase: WebAutomationRunCase): string =>
  [testCase.project, testCase.module, testCase.pageName, testCase.startUrl, testCase.sourceCaseId].join(':')

const readCases = (storage: Storage): SavedWebAutomationRunCase[] => {
  const raw = storage.getItem(WEB_AUTOMATION_LIBRARY_STORAGE_KEY)
  if (!raw) return []

  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter(isSavedRunCase) : []
  } catch {
    return []
  }
}

const writeCases = (storage: Storage, cases: SavedWebAutomationRunCase[]) => {
  storage.setItem(WEB_AUTOMATION_LIBRARY_STORAGE_KEY, JSON.stringify(cases))
}

const isSavedRunCase = (value: unknown): value is SavedWebAutomationRunCase => {
  if (!value || typeof value !== 'object') return false
  const record = value as Record<string, unknown>
  return typeof record.automationLibraryId === 'string' && typeof record.savedAt === 'string'
}

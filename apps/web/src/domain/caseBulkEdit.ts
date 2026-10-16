import type { GeneratedInterfaceCase, InterfaceCaseMetadata, InterfaceCaseRow } from './caseGenerator'
import type { SavedInterfaceCase } from './caseRepository'

export type BulkEditableCase = GeneratedInterfaceCase | SavedInterfaceCase
export type MatchMode = 'equals' | 'contains'
export type JsonAssignableField = 'pathParams' | 'queryParams' | 'body'

export interface BulkCaseFieldUpdateOptions {
  matchField: keyof InterfaceCaseRow
  matchMode: MatchMode
  matchValue: string
  targetField: keyof InterfaceCaseRow
  targetValue: string
}

export interface JsonFieldAssignmentOption {
  fieldKey: JsonAssignableField
  path: string
  value: string
}

export interface JsonFieldAssignment {
  fieldKey: JsonAssignableField
  path: string
  value: string
  count: number
}

export const applyBulkCaseFieldUpdate = <T extends BulkEditableCase>(
  cases: T[],
  options: BulkCaseFieldUpdateOptions
): { cases: T[]; updated: number } => {
  let updated = 0
  const next = cases.map((testCase) => {
    const matchValue = String(testCase.row[options.matchField] ?? '')
    const matched = options.matchMode === 'equals'
      ? matchValue === options.matchValue
      : matchValue.includes(options.matchValue)
    if (!matched) return testCase

    updated += 1
    return syncMetadataFromRow({
      ...testCase,
      row: {
        ...testCase.row,
        [options.targetField]: options.targetValue
      }
    }) as T
  })

  return { cases: next, updated }
}

export const collectJsonFieldAssignments = (
  cases: BulkEditableCase[],
  fieldKeys: JsonAssignableField[] = ['pathParams', 'queryParams', 'body']
): JsonFieldAssignment[] => {
  const counts = new Map<string, JsonFieldAssignment>()

  for (const testCase of cases) {
    for (const fieldKey of fieldKeys) {
      const value = parseJsonObject(testCase.row[fieldKey])
      for (const path of collectLeafPaths(value)) {
        const key = `${fieldKey}:${path}`
        const existing = counts.get(key)
        if (existing) {
          existing.count += 1
          continue
        }
        counts.set(key, {
          fieldKey,
          path,
          value: String(readPath(value, path) ?? ''),
          count: 1
        })
      }
    }
  }

  return Array.from(counts.values()).sort((left, right) =>
    left.fieldKey === right.fieldKey ? left.path.localeCompare(right.path) : left.fieldKey.localeCompare(right.fieldKey)
  )
}

export const applyJsonFieldAssignment = <T extends BulkEditableCase>(
  cases: T[],
  options: JsonFieldAssignmentOption
): { cases: T[]; updated: number } => {
  let updated = 0
  const next = cases.map((testCase) => {
    const value = parseJsonObject(testCase.row[options.fieldKey])
    if (readPath(value, options.path) === undefined) return testCase

    updated += 1
    const nextValue = setPath(value, options.path, options.value)
    return syncMetadataFromRow({
      ...testCase,
      row: {
        ...testCase.row,
        [options.fieldKey]: JSON.stringify(nextValue, null, 2)
      }
    }) as T
  })

  return { cases: next, updated }
}

export const syncMetadataFromRow = <T extends BulkEditableCase>(testCase: T): T => {
  const metadata: InterfaceCaseMetadata = {
    ...testCase.metadata,
    headers: parseJsonRecord(testCase.row.headers),
    pathParams: parseJsonRecord(testCase.row.pathParams),
    queryParams: parseJsonRecord(testCase.row.queryParams),
    body: parseJsonValue(testCase.row.body),
    assertions: testCase.row.assertions.split(/\r?\n/).map((line) => line.trim()).filter(Boolean),
    expectedResponseBody: parseJsonValue(testCase.row.expectedResponseBody || '')
  }
  return {
    ...testCase,
    metadata
  }
}

const parseJsonRecord = (value: string): Record<string, string> =>
  Object.fromEntries(
    Object.entries(parseJsonObject(value)).map(([key, item]) => [key, typeof item === 'string' ? item : JSON.stringify(item)])
  )

const parseJsonValue = (value: string): unknown => {
  const trimmed = value.trim()
  if (!trimmed) return undefined
  try {
    return JSON.parse(trimmed)
  } catch {
    return trimmed
  }
}

const parseJsonObject = (value: string): Record<string, unknown> => {
  const parsed = parseJsonValue(value)
  return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed as Record<string, unknown> : {}
}

const collectLeafPaths = (value: unknown, prefix = ''): string[] => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return prefix ? [prefix] : []
  return Object.entries(value as Record<string, unknown>).flatMap(([key, child]) =>
    collectLeafPaths(child, prefix ? `${prefix}.${key}` : key)
  )
}

const readPath = (value: unknown, path: string): unknown =>
  path.split('.').reduce<unknown>((current, segment) => {
    if (!current || typeof current !== 'object' || Array.isArray(current)) return undefined
    return (current as Record<string, unknown>)[segment]
  }, value)

const setPath = (value: Record<string, unknown>, path: string, nextValue: string): Record<string, unknown> => {
  const clone = JSON.parse(JSON.stringify(value)) as Record<string, unknown>
  const segments = path.split('.')
  let current: Record<string, unknown> = clone

  for (const segment of segments.slice(0, -1)) {
    const child = current[segment]
    if (!child || typeof child !== 'object' || Array.isArray(child)) current[segment] = {}
    current = current[segment] as Record<string, unknown>
  }

  current[segments[segments.length - 1]] = nextValue
  return clone
}

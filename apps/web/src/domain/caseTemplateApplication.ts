import type { CaseField } from './caseTemplates'
import type { GeneratedInterfaceCase, InterfaceCaseRow } from './caseGenerator'

const metadataFieldClearers: Record<string, (testCase: GeneratedInterfaceCase) => GeneratedInterfaceCase> = {
  headers: (testCase) => ({
    ...testCase,
    metadata: {
      ...testCase.metadata,
      headers: {}
    }
  }),
  pathParams: (testCase) => ({
    ...testCase,
    metadata: {
      ...testCase.metadata,
      pathParams: {}
    }
  }),
  queryParams: (testCase) => ({
    ...testCase,
    metadata: {
      ...testCase.metadata,
      queryParams: {}
    }
  }),
  body: (testCase) => ({
    ...testCase,
    metadata: {
      ...testCase.metadata,
      body: undefined
    }
  }),
  expectedResponseBody: (testCase) => ({
    ...testCase,
    metadata: {
      ...testCase.metadata,
      expectedResponseBody: undefined
    }
  })
}

export const applyInterfaceFieldTemplate = (
  testCase: GeneratedInterfaceCase,
  fields: CaseField[]
): GeneratedInterfaceCase => {
  const visibleKeys = new Set(fields.filter((field) => field.visible).map((field) => field.key))
  const nextRow = { ...testCase.row }

  for (const field of fields) {
    if (!visibleKeys.has(field.key) && field.key in nextRow) {
      nextRow[field.key as keyof InterfaceCaseRow] = ''
    }
  }

  let next: GeneratedInterfaceCase = {
    ...testCase,
    row: nextRow
  }

  for (const field of fields) {
    if (!field.visible) {
      next = metadataFieldClearers[field.key]?.(next) || next
    }
  }

  return next
}

export const applyInterfaceFieldTemplateToCases = (
  cases: GeneratedInterfaceCase[],
  fields: CaseField[]
): GeneratedInterfaceCase[] => cases.map((testCase) => applyInterfaceFieldTemplate(testCase, fields))

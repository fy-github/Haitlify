import type { CaseField } from './caseTemplates'
import { visibleExportFields } from './caseTemplates'

export type ExportableCaseRow = Record<string, unknown>

export const exportCasesToCsv = (fields: CaseField[], rows: ExportableCaseRow[]): string => {
  const exportFields = visibleExportFields(fields)
  const header = exportFields.map((field) => escapeCsvValue(field.label)).join(',')
  const body = rows
    .map((row) =>
      exportFields.map((field) => escapeCsvValue(row[field.key] === undefined ? '' : row[field.key])).join(',')
    )
    .join('\n')

  return `\ufeff${[header, body].filter(Boolean).join('\n')}`
}

export const downloadCsv = (filename: string, csv: string): void => {
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
}

const escapeCsvValue = (value: unknown): string => {
  const text = String(value ?? '')
  if (/[",\n\r]/.test(text)) {
    return `"${text.replace(/"/g, '""')}"`
  }
  return text
}

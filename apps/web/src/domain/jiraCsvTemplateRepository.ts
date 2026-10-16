import {
  buildDefaultJiraCsvTemplate,
  type JiraCsvTemplateField
} from './jiraCsvTemplate'

export const JIRA_CSV_TEMPLATE_STORAGE_KEY = 'ai-test-platform.jira-csv-template.v1'

export const loadJiraCsvTemplate = (storage: Storage): JiraCsvTemplateField[] => {
  const raw = storage.getItem(JIRA_CSV_TEMPLATE_STORAGE_KEY)
  if (!raw) return buildDefaultJiraCsvTemplate()

  try {
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return buildDefaultJiraCsvTemplate()
    const fields = parsed.filter(isJiraCsvTemplateField)
    return fields.length > 0 ? fields : buildDefaultJiraCsvTemplate()
  } catch {
    return buildDefaultJiraCsvTemplate()
  }
}

export const saveJiraCsvTemplate = (storage: Storage, template: JiraCsvTemplateField[]): void => {
  storage.setItem(JIRA_CSV_TEMPLATE_STORAGE_KEY, JSON.stringify(template))
}

export const resetJiraCsvTemplate = (storage: Storage): JiraCsvTemplateField[] => {
  storage.removeItem(JIRA_CSV_TEMPLATE_STORAGE_KEY)
  return buildDefaultJiraCsvTemplate()
}

const isJiraCsvTemplateField = (value: unknown): value is JiraCsvTemplateField => {
  if (!value || typeof value !== 'object') return false
  const record = value as Record<string, unknown>
  if (typeof record.key !== 'string' || typeof record.header !== 'string') return false
  if (!record.source || typeof record.source !== 'object') return false
  const source = record.source as Record<string, unknown>
  if (source.kind !== 'draftField' && source.kind !== 'jiraFieldValue') return false
  return typeof source.fieldKey === 'string'
}

import type { DefectDraft } from './defectRepository'

export type JiraCsvDraftFieldKey =
  | 'summary'
  | 'issueType'
  | 'projectName'
  | 'priority'
  | 'assignee'
  | 'description'
  | 'blocksIssueKey'
  | 'faultCategory'
  | 'jiraIssueKey'

export interface JiraCsvTemplateField {
  key: string
  header: string
  enabled?: boolean
  custom?: boolean
  source:
    | {
        kind: 'draftField'
        fieldKey: JiraCsvDraftFieldKey
      }
    | {
        kind: 'jiraFieldValue'
        fieldKey: string
      }
}

export const buildDefaultJiraCsvTemplate = (): JiraCsvTemplateField[] => [
  {
    key: 'summary',
    header: '概要',
    enabled: true,
    source: { kind: 'draftField', fieldKey: 'summary' }
  },
  {
    key: 'issueType',
    header: '问题类型',
    enabled: true,
    source: { kind: 'draftField', fieldKey: 'issueType' }
  },
  {
    key: 'projectName',
    header: '项目名称',
    enabled: true,
    source: { kind: 'draftField', fieldKey: 'projectName' }
  },
  {
    key: 'priority',
    header: '优先级',
    enabled: true,
    source: { kind: 'draftField', fieldKey: 'priority' }
  },
  {
    key: 'assignee',
    header: '经办人',
    enabled: true,
    source: { kind: 'draftField', fieldKey: 'assignee' }
  },
  {
    key: 'description',
    header: '描述',
    enabled: true,
    source: { kind: 'draftField', fieldKey: 'description' }
  },
  {
    key: 'blocksIssueKey',
    header: '向外链接的问题（BLOCK）',
    enabled: true,
    source: { kind: 'draftField', fieldKey: 'blocksIssueKey' }
  },
  {
    key: 'faultCategory',
    header: '自定义字段（ 故障分类）',
    enabled: true,
    source: { kind: 'draftField', fieldKey: 'faultCategory' }
  }
]

export const buildEnabledJiraCsvTemplate = (template: JiraCsvTemplateField[]): JiraCsvTemplateField[] =>
  template.filter((field) => field.enabled !== false)

export const newCustomJiraCsvTemplateField = (index = 1): JiraCsvTemplateField => ({
  key: `customField-${Date.now()}-${index}`,
  header: `自定义字段（新字段${index}）`,
  enabled: true,
  custom: true,
  source: {
    kind: 'jiraFieldValue',
    fieldKey: `自定义字段（新字段${index}）`
  }
})

export const renderJiraCsvRows = (
  drafts: DefectDraft[],
  template: JiraCsvTemplateField[]
): string[][] =>
  drafts.map((draft) =>
    template.map((field) => {
      if (field.source.kind === 'draftField') {
        return String(draft[field.source.fieldKey] ?? '')
      }
      return String(draft.jiraFieldValues[field.source.fieldKey] ?? '')
    })
  )

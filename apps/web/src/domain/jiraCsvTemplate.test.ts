import { describe, expect, it } from 'vitest'
import type { DefectDraft } from './defectRepository'
import {
  buildDefaultJiraCsvTemplate,
  buildEnabledJiraCsvTemplate,
  newCustomJiraCsvTemplateField,
  renderJiraCsvRows,
  type JiraCsvTemplateField
} from './jiraCsvTemplate'

const draft: DefectDraft = {
  defectId: 'defect-AUTO-1',
  sourceReportCaseId: 'AUTO-1',
  sourceCaseId: 'API-1',
  projectName: 'Haitlify',
  issueType: '故障',
  summary: '项目树接口失败',
  priority: 'P1',
  assignee: '张三',
  description: '返回体缺少 children 字段',
  blocksIssueKey: 'CLOUD-12',
  faultCategory: '接口自动化失败',
  status: 'pending_jira_export',
  createdAt: '2026-06-15T10:00:00.000Z',
  updatedAt: '2026-06-15T10:00:00.000Z',
  jiraFieldValues: {
    '自定义字段（影响版本）': 'v4.6.1'
  }
}

describe('jiraCsvTemplate', () => {
  it('provides the agreed default jira csv headers in order', () => {
    expect(buildDefaultJiraCsvTemplate().map((field) => field.header)).toEqual([
      '概要',
      '问题类型',
      '项目名称',
      '优先级',
      '经办人',
      '描述',
      '向外链接的问题（BLOCK）',
      '自定义字段（ 故障分类）'
    ])
  })

  it('renders rows from both core draft fields and jiraFieldValues', () => {
    const template: JiraCsvTemplateField[] = [
      ...buildDefaultJiraCsvTemplate(),
      {
        key: 'affectedVersion',
        header: '自定义字段（影响版本）',
        source: {
          kind: 'jiraFieldValue',
          fieldKey: '自定义字段（影响版本）'
        }
      }
    ]

    expect(renderJiraCsvRows([draft], template)).toEqual([
      [
        '项目树接口失败',
        '故障',
        'Haitlify',
        'P1',
        '张三',
        '返回体缺少 children 字段',
        'CLOUD-12',
        '接口自动化失败',
        'v4.6.1'
      ]
    ])
  })

  it('filters disabled fields out of the active export template', () => {
    const template: JiraCsvTemplateField[] = [
      {
        key: 'summary',
        header: '概要',
        source: {
          kind: 'draftField',
          fieldKey: 'summary'
        },
        enabled: true
      },
      {
        key: 'assignee',
        header: '经办人',
        source: {
          kind: 'draftField',
          fieldKey: 'assignee'
        },
        enabled: false
      }
    ]

    expect(buildEnabledJiraCsvTemplate(template).map((field) => field.key)).toEqual(['summary'])
  })

  it('builds a custom jira field template row for jiraFieldValues', () => {
    const field = newCustomJiraCsvTemplateField(3)
    expect(field).toMatchObject({
      enabled: true,
      custom: true,
      header: '自定义字段（新字段3）',
      source: {
        kind: 'jiraFieldValue',
        fieldKey: '自定义字段（新字段3）'
      }
    })
  })
})

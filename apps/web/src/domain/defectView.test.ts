import { describe, expect, it } from 'vitest'
import type { DefectDraft } from './defectRepository'
import {
  buildDefectFilterOptions,
  buildDefectFilterOptionsFromIssues,
  buildDefectIssueRecords,
  buildDefectIssueRecordsFromDrafts,
  buildDefectIssueRecordsFromJiraIssues,
  buildDefectMetrics,
  mergeDefectProjectOptions,
  buildDefectProjectGroups,
  filterDefectIssuesByProject,
  filterDefectDrafts,
  mergeDefectIssueRecords
} from './defectView'

const makeDraft = (
  defectId: string,
  patch: Partial<DefectDraft> = {}
): DefectDraft => ({
  defectId,
  sourceReportCaseId: `AUTO-${defectId}`,
  sourceCaseId: `API-${defectId}`,
  projectName: 'Haitlify',
  issueType: '故障',
  summary: '项目树接口执行失败',
  priority: 'P2',
  assignee: '张三',
  description: '接口自动化执行失败，返回体字段缺失',
  blocksIssueKey: '',
  faultCategory: '接口自动化失败',
  status: 'pending_jira_export',
  createdAt: '2026-06-15T10:00:00.000Z',
  updatedAt: '2026-06-15T10:00:00.000Z',
  jiraFieldValues: {},
  ...patch
})

describe('defectView', () => {
  it('filters drafts by project, status, issue type, priority, assignee, and keyword', () => {
    const drafts = [
      makeDraft('1'),
      makeDraft('2', {
        projectName: 'Ucenter',
        issueType: '任务',
        priority: 'P1',
        assignee: '李四',
        summary: '用户登录接口执行失败',
        status: 'exported_jira_csv',
        jiraIssueKey: 'JIRA-22',
        faultCategory: '鉴权失败'
      })
    ]

    const filtered = filterDefectDrafts(drafts, {
      project: 'Ucenter',
      status: 'exported_jira_csv',
      issueType: '任务',
      priority: 'P1',
      assignee: '李四',
      keyword: 'JIRA-22'
    })

    expect(filtered.map((draft) => draft.defectId)).toEqual(['2'])
  })

  it('uses defect id in the same keyword predicate as the visible list', () => {
    expect(filterDefectDrafts([makeDraft('ID-ONLY')], {
      project: 'ALL', status: 'ALL', issueType: 'ALL', priority: 'ALL', assignee: 'ALL', keyword: 'ID-ONLY'
    }).map(draft => draft.defectId)).toEqual(['ID-ONLY'])
  })

  it('builds metrics and filter options from the current drafts', () => {
    const drafts = [
      makeDraft('1'),
      makeDraft('2', {
        projectName: 'Ucenter',
        assignee: '李四',
        status: 'exported_jira_csv',
        issueType: '任务'
      }),
      makeDraft('3', {
        status: 'created_in_jira',
        jiraIssueKey: 'JIRA-9'
      })
    ]

    expect(buildDefectMetrics(drafts)).toEqual({
      total: 3,
      pending: 1,
      exported: 1,
      created: 1
    })

    expect(buildDefectFilterOptions(drafts)).toMatchObject({
      projectOptions: [
        { value: 'Haitlify', count: 2 },
        { value: 'Ucenter', count: 1 }
      ],
      issueTypeOptions: ['任务', '故障'],
      priorityOptions: ['P2'],
      assigneeOptions: ['李四', '张三']
    })
  })

  it('builds filter options from the unified issue list so jira-only projects can appear', () => {
    const issues = buildDefectIssueRecordsFromJiraIssues([
      {
        id: '10001',
        projectName: 'Haitlify API',
        issueKey: 'HAITLIFY-12',
        summary: '项目树接口查询超时',
        status: '处理中',
        priority: 'High',
        assignee: '张三',
        issueType: '故障',
        description: '',
        updatedAt: '2026-06-16T15:00:00.000Z'
      }
    ])

    expect(buildDefectFilterOptionsFromIssues(issues)).toMatchObject({
      projectOptions: [
        { value: 'Haitlify API', count: 1 }
      ],
      issueTypeOptions: ['故障'],
      priorityOptions: ['High'],
      assigneeOptions: ['张三']
    })
  })

  it('merges candidate project names with issue-backed project counts', () => {
    expect(mergeDefectProjectOptions(
      [{ value: 'Haitlify API', count: 1 }],
      ['UcenterAPI', 'Haitlify API']
    )).toEqual([
      { value: 'UcenterAPI', count: 0 },
      { value: 'Haitlify API', count: 1 }
    ])
  })

  it('builds defect issue records and project groups from local drafts', () => {
    const drafts = [
      makeDraft('1', { projectName: 'Haitlify' }),
      makeDraft('2', { projectName: 'Haitlify', jiraIssueKey: 'IKOS-22', status: 'created_in_jira' }),
      makeDraft('3', { projectName: 'Ucenter' })
    ]

    const issues = buildDefectIssueRecords(drafts)
    const groups = buildDefectProjectGroups(issues)

    expect(issues.map((issue) => issue.sourceKind)).toEqual(['draft', 'draft', 'draft'])
    expect(groups).toEqual([
      { value: 'Haitlify', count: 2 },
      { value: 'Ucenter', count: 1 }
    ])
  })

  it('filters defect issue records by selected project while preserving ALL behavior', () => {
    const issues = buildDefectIssueRecords([
      makeDraft('1', { projectName: 'Haitlify' }),
      makeDraft('2', { projectName: 'Ucenter' })
    ])

    expect(filterDefectIssuesByProject(issues, 'Haitlify').map((issue) => issue.id)).toEqual(['1'])
    expect(filterDefectIssuesByProject(issues, 'ALL').map((issue) => issue.id)).toEqual(['1', '2'])
  })

  it('maps local drafts into Jira-ready issue records with source labels and display keys', () => {
    const [pendingIssue, createdIssue] = buildDefectIssueRecords([
      makeDraft('1'),
      makeDraft('2', {
        jiraIssueKey: 'IKOS-22',
        status: 'created_in_jira',
        assignee: '李四'
      })
    ])

    expect(pendingIssue).toMatchObject({
      id: '1',
      sourceKind: 'draft',
      sourceLabel: '本地草稿',
      issueKey: '',
      displayStatus: '待导出 Jira',
      summary: '项目树接口执行失败',
      description: '接口自动化执行失败，返回体字段缺失',
      assignee: '张三'
    })

    expect(createdIssue).toMatchObject({
      id: '2',
      sourceKind: 'draft',
      sourceLabel: '本地草稿',
      issueKey: 'IKOS-22',
      displayStatus: '已建 Jira IKOS-22',
      assignee: '李四'
    })
  })

  it('merges local draft issue records into the current unified issue list', () => {
    const drafts = [
      makeDraft('1', { projectName: 'Haitlify' }),
      makeDraft('2', { projectName: 'Ucenter', jiraIssueKey: 'IKOS-22' })
    ]

    const issues = buildDefectIssueRecordsFromDrafts(drafts)
    const merged = mergeDefectIssueRecords(issues)

    expect(merged.map((issue) => ({
      id: issue.id,
      sourceKind: issue.sourceKind,
      sourceLabel: issue.sourceLabel,
      issueKey: issue.issueKey
    }))).toEqual([
      { id: '1', sourceKind: 'draft', sourceLabel: '本地草稿', issueKey: '' },
      { id: '2', sourceKind: 'draft', sourceLabel: '本地草稿', issueKey: 'IKOS-22' }
    ])
  })

  it('maps Jira issues into unified read-only issue records', () => {
    const [issue] = buildDefectIssueRecordsFromJiraIssues([
      {
        id: '10001',
        projectName: 'Haitlify API',
        issueKey: 'HAITLIFY-12',
        summary: '项目树接口查询超时',
        status: '处理中',
        priority: 'High',
        assignee: '张三',
        issueType: '故障',
        description: '来自 Jira 的描述',
        updatedAt: '2026-06-16T15:00:00.000Z'
      }
    ])

    expect(issue).toMatchObject({
      id: '10001',
      sourceKind: 'jira',
      sourceLabel: 'Jira',
      issueKey: 'HAITLIFY-12',
      displayStatus: '处理中',
      description: '来自 Jira 的描述'
    })
  })

  it('keeps merged issue records ordered when a future Jira source is empty', () => {
    const drafts = buildDefectIssueRecordsFromDrafts([
      makeDraft('1', { projectName: 'Haitlify' }),
      makeDraft('2', { projectName: 'Ucenter' })
    ])

    const merged = mergeDefectIssueRecords(drafts, buildDefectIssueRecordsFromJiraIssues([]))

    expect(merged.map((issue) => issue.id)).toEqual(['1', '2'])
  })

  it('keeps merged issue records ordered when Jira issues are appended', () => {
    const drafts = buildDefectIssueRecordsFromDrafts([
      makeDraft('1', { projectName: 'Haitlify' })
    ])
    const jiraIssues = buildDefectIssueRecordsFromJiraIssues([
      {
        id: '10001',
        projectName: 'Haitlify API',
        issueKey: 'HAITLIFY-12',
        summary: '项目树接口查询超时',
        status: '处理中',
        priority: 'High',
        assignee: '张三',
        issueType: '故障',
        description: '',
        updatedAt: '2026-06-16T15:00:00.000Z'
      }
    ])

    const merged = mergeDefectIssueRecords(drafts, jiraIssues)

    expect(merged.map((issue) => issue.sourceKind)).toEqual(['draft', 'jira'])
  })
})

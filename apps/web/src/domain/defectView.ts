import type { DefectDraft, DefectDraftStatus } from './defectRepository'

export interface DefectFilterOption {
  value: string
  count: number
}

export interface DefectFilters {
  project: string
  status: DefectDraftStatus | 'ALL'
  issueType: string
  priority: string
  assignee: string
  keyword: string
}

export interface DefectMetrics {
  total: number
  pending: number
  exported: number
  created: number
}

export interface DefectFilterOptions {
  projectOptions: DefectFilterOption[]
  issueTypeOptions: string[]
  priorityOptions: string[]
  assigneeOptions: string[]
}

export interface DefectIssueRecord {
  id: string
  projectName: string
  sourceKind: 'draft' | 'jira'
  sourceLabel: string
  status: string
  displayStatus: string
  summary: string
  description: string
  priority: string
  assignee: string
  issueType: string
  issueKey: string
  draft: DefectDraft
}

export const buildDefectIssueRecordsFromDrafts = (drafts: DefectDraft[]): DefectIssueRecord[] =>
  drafts.map((draft) => ({
    id: draft.defectId,
    projectName: draft.projectName,
    sourceKind: 'draft',
    sourceLabel: '本地草稿',
    status: draft.status,
    displayStatus: displayStatusForDraft(draft),
    summary: draft.summary,
    description: draft.description,
    priority: draft.priority,
    assignee: draft.assignee,
    issueType: draft.issueType,
    issueKey: draft.jiraIssueKey || '',
    draft
  }))

export interface JiraIssueSource {
  id: string
  projectName: string
  issueKey: string
  summary: string
  status: string
  priority: string
  assignee: string
  issueType: string
  description?: string
  updatedAt: string
}

export const buildDefectIssueRecordsFromJiraIssues = (issues: JiraIssueSource[]): DefectIssueRecord[] =>
  issues.map((issue) => ({
    id: issue.id,
    projectName: issue.projectName,
    sourceKind: 'jira',
    sourceLabel: 'Jira',
    status: issue.status,
    displayStatus: issue.status,
    summary: issue.summary,
    description: issue.description || '',
    priority: issue.priority,
    assignee: issue.assignee,
    issueType: issue.issueType,
    issueKey: issue.issueKey,
    draft: emptyDefectDraft(issue)
  }))

export const mergeDefectIssueRecords = (...groups: DefectIssueRecord[][]): DefectIssueRecord[] =>
  groups.flat()

export const buildDefectIssueRecords = (drafts: DefectDraft[]): DefectIssueRecord[] =>
  mergeDefectIssueRecords(buildDefectIssueRecordsFromDrafts(drafts))

export const buildDefectProjectGroups = (issues: DefectIssueRecord[]): DefectFilterOption[] => {
  const counts = new Map<string, number>()
  for (const issue of issues) {
    counts.set(issue.projectName, (counts.get(issue.projectName) || 0) + 1)
  }

  return Array.from(counts.entries()).map(([value, count]) => ({ value, count }))
}

export const buildDefectFilterOptionsFromIssues = (issues: DefectIssueRecord[]): DefectFilterOptions => {
  const projectCounts = new Map<string, number>()
  const issueTypes = new Set<string>()
  const priorities = new Set<string>()
  const assignees = new Set<string>()

  for (const issue of issues) {
    projectCounts.set(issue.projectName, (projectCounts.get(issue.projectName) || 0) + 1)
    if (issue.issueType) issueTypes.add(issue.issueType)
    if (issue.priority) priorities.add(issue.priority)
    if (issue.assignee) assignees.add(issue.assignee)
  }

  return {
    projectOptions: Array.from(projectCounts.entries()).map(([value, count]) => ({ value, count })),
    issueTypeOptions: Array.from(issueTypes).sort((left, right) => right.localeCompare(left, 'zh-CN')),
    priorityOptions: Array.from(priorities).sort((left, right) => left.localeCompare(right, 'zh-CN')),
    assigneeOptions: Array.from(assignees).sort((left, right) => left.localeCompare(right, 'zh-CN'))
  }
}

export const mergeDefectProjectOptions = (
  issueProjects: DefectFilterOption[],
  candidateProjects: string[]
): DefectFilterOption[] => {
  const counts = new Map(issueProjects.map((project) => [project.value, project.count]))
  const ordered = new Set<string>()

  for (const project of candidateProjects) {
    const trimmed = project.trim()
    if (trimmed) ordered.add(trimmed)
  }

  for (const project of issueProjects) {
    ordered.add(project.value)
  }

  return Array.from(ordered).map((value) => ({
    value,
    count: counts.get(value) || 0
  }))
}

export const filterDefectIssuesByProject = (
  issues: DefectIssueRecord[],
  project: string
): DefectIssueRecord[] => (project === 'ALL' ? issues : issues.filter((issue) => issue.projectName === project))

const displayStatusForDraft = (draft: DefectDraft): string => {
  if (draft.status === 'created_in_jira') return draft.jiraIssueKey ? `已建 Jira ${draft.jiraIssueKey}` : '已建 Jira'
  if (draft.status === 'exported_jira_csv') return '已导出 Jira CSV'
  return '待导出 Jira'
}

const emptyDefectDraft = (issue: JiraIssueSource): DefectDraft => ({
  defectId: `jira-${issue.id}`,
  sourceReportCaseId: '',
  sourceCaseId: '',
  projectName: issue.projectName,
  issueType: issue.issueType,
  summary: issue.summary,
  priority: issue.priority,
  assignee: issue.assignee,
  description: issue.description || '',
  blocksIssueKey: '',
  faultCategory: '',
  status: 'created_in_jira',
  jiraIssueKey: issue.issueKey,
  createdAt: issue.updatedAt,
  updatedAt: issue.updatedAt,
  jiraFieldValues: {}
})

export const filterDefectDrafts = (drafts: DefectDraft[], filters: DefectFilters): DefectDraft[] => {
  const keyword = filters.keyword.trim().toLowerCase()

  return drafts.filter((draft) => {
    if (filters.project !== 'ALL' && draft.projectName !== filters.project) return false
    if (filters.status !== 'ALL' && draft.status !== filters.status) return false
    if (filters.issueType !== 'ALL' && draft.issueType !== filters.issueType) return false
    if (filters.priority !== 'ALL' && draft.priority !== filters.priority) return false
    if (filters.assignee !== 'ALL' && draft.assignee !== filters.assignee) return false
    if (!keyword) return true

    return [
      draft.defectId,
      draft.summary,
      draft.description,
      draft.sourceCaseId,
      draft.sourceReportCaseId,
      draft.jiraIssueKey || '',
      draft.faultCategory
    ].join(' ').toLowerCase().includes(keyword)
  })
}

export const buildDefectMetrics = (drafts: DefectDraft[]): DefectMetrics => ({
  total: drafts.length,
  pending: drafts.filter((draft) => draft.status === 'pending_jira_export').length,
  exported: drafts.filter((draft) => draft.status === 'exported_jira_csv').length,
  created: drafts.filter((draft) => draft.status === 'created_in_jira').length
})

export const buildDefectFilterOptions = (drafts: DefectDraft[]): DefectFilterOptions => {
  const projectCounts = new Map<string, number>()
  const issueTypes = new Set<string>()
  const priorities = new Set<string>()
  const assignees = new Set<string>()

  for (const draft of drafts) {
    projectCounts.set(draft.projectName, (projectCounts.get(draft.projectName) || 0) + 1)
    if (draft.issueType) issueTypes.add(draft.issueType)
    if (draft.priority) priorities.add(draft.priority)
    if (draft.assignee) assignees.add(draft.assignee)
  }

  return {
    projectOptions: Array.from(projectCounts.entries()).map(([value, count]) => ({ value, count })),
    issueTypeOptions: Array.from(issueTypes).sort((left, right) => right.localeCompare(left, 'zh-CN')),
    priorityOptions: Array.from(priorities).sort((left, right) => left.localeCompare(right, 'zh-CN')),
    assigneeOptions: Array.from(assignees).sort((left, right) => left.localeCompare(right, 'zh-CN'))
  }
}

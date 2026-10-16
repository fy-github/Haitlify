import type { AutomationRunResult } from './apiAutomation'
import type { WebAutomationRunResult } from './webAutomation'
import {
  buildDefaultJiraCsvTemplate,
  renderJiraCsvRows,
  type JiraCsvTemplateField
} from './jiraCsvTemplate'

export const DEFECT_DRAFT_STORAGE_KEY = 'ai-test-platform.defect-drafts.v1'

export type DefectDraftStatus = 'pending_jira_export' | 'exported_jira_csv' | 'created_in_jira'
export type JiraExportMode = 'csv' | 'api'

export interface DefectDraft {
  defectId: string
  sourceReportCaseId: string
  sourceCaseId: string
  projectName: string
  issueType: string
  summary: string
  priority: string
  assignee: string
  description: string
  blocksIssueKey: string
  faultCategory: string
  status: DefectDraftStatus
  jiraIssueKey?: string
  jiraExportMode?: JiraExportMode
  createdAt: string
  updatedAt: string
  jiraFieldValues: Record<string, string>
}

export interface BuildDefectDraftOptions {
  projectName: string
  issueType: string
  priority?: string
  assignee?: string
  faultCategory?: string
}

export interface SaveDefectDraftsResult {
  inserted: number
  updated: number
  total: number
}

export interface DeleteDefectDraftsResult {
  deleted: number
  remaining: number
}

export const buildDefectDraftsFromAutomationResults = (
  results: AutomationRunResult[],
  options: BuildDefectDraftOptions
): DefectDraft[] =>
  results
    .filter((result) => result.status === 'failed' || result.status === 'error')
    .map((result, index) => {
      const now = new Date().toISOString()
      return {
        defectId: `defect-${result.caseId}`,
        sourceReportCaseId: result.caseId,
        sourceCaseId: result.sourceCaseId,
        projectName: options.projectName,
        issueType: options.issueType,
        summary: `【${options.projectName}】${result.name} - ${result.method} ${result.path}`,
        priority: options.priority || 'Low',
        assignee: options.assignee || '',
        description: buildDefectDescription(result),
        blocksIssueKey: '',
        faultCategory: options.faultCategory || '接口自动化失败',
        status: 'pending_jira_export',
        createdAt: now,
        updatedAt: now,
        jiraFieldValues: {}
      }
    })

export const buildDefectDraftsFromWebAutomationResults = (
  results: WebAutomationRunResult[],
  options: BuildDefectDraftOptions
): DefectDraft[] =>
  results
    .filter((result) => result.status === 'failed' || result.status === 'error')
    .map((result) => {
      const now = new Date().toISOString()
      return {
        defectId: `defect-${result.caseId}`,
        sourceReportCaseId: result.caseId,
        sourceCaseId: result.sourceCaseId,
        projectName: options.projectName,
        issueType: options.issueType,
        summary: `【${options.projectName}】${result.name} - ${result.pageName}`,
        priority: options.priority || 'Low',
        assignee: options.assignee || '',
        description: [
          `页面: ${result.pageName}`,
          `访问地址: ${result.startUrl}`,
          `执行耗时: ${result.durationMs} ms`,
          `失败原因: ${result.errorMessage || '执行失败'}`
        ].join('\n'),
        blocksIssueKey: '',
        faultCategory: options.faultCategory || 'Web自动化失败',
        status: 'pending_jira_export',
        createdAt: now,
        updatedAt: now,
        jiraFieldValues: {}
      }
    })

export const listDefectDrafts = (storage: Storage): DefectDraft[] =>
  readDefectDrafts(storage).sort((left, right) => right.createdAt.localeCompare(left.createdAt))

export const saveDefectDrafts = (storage: Storage, drafts: DefectDraft[]): SaveDefectDraftsResult => {
  const current = readDefectDrafts(storage)
  const byId = new Map(current.map((draft) => [draft.defectId, draft]))
  let inserted = 0
  let updated = 0

  for (const draft of drafts) {
    const key = draft.defectId
    if (byId.has(key)) {
      updated += 1
    } else {
      inserted += 1
    }
    byId.set(key, draft)
  }

  const next = Array.from(byId.values())
  writeDefectDrafts(storage, next)
  return {
    inserted,
    updated,
    total: next.length
  }
}

export const updateDefectDraft = (storage: Storage, draft: DefectDraft): void => {
  const current = readDefectDrafts(storage)
  const next = current.map((item) => (item.defectId === draft.defectId ? draft : item))
  writeDefectDrafts(storage, next)
}

export const deleteDefectDraft = (storage: Storage, defectId: string): boolean => {
  const current = readDefectDrafts(storage)
  const next = current.filter((draft) => draft.defectId !== defectId)
  writeDefectDrafts(storage, next)
  return next.length !== current.length
}

export const deleteDefectDrafts = (storage: Storage, defectIds: string[]): DeleteDefectDraftsResult => {
  const deleting = new Set(defectIds)
  const current = readDefectDrafts(storage)
  const next = current.filter((draft) => !deleting.has(draft.defectId))
  writeDefectDrafts(storage, next)
  return {
    deleted: current.length - next.length,
    remaining: next.length
  }
}

export const markDefectsAsExported = (
  storage: Storage,
  defectIds: string[],
  mode: JiraExportMode,
  jiraIssueKeyMap: Record<string, string> = {}
): void => {
  const nextStatus: DefectDraftStatus = mode === 'api' ? 'created_in_jira' : 'exported_jira_csv'
  const next = readDefectDrafts(storage).map((draft) =>
    defectIds.includes(draft.defectId)
      ? {
          ...draft,
          status: nextStatus,
          jiraExportMode: mode,
          jiraIssueKey: jiraIssueKeyMap[draft.defectId] || draft.jiraIssueKey,
          updatedAt: new Date().toISOString()
        }
      : draft
  )
  writeDefectDrafts(storage, next)
}

export const exportDefectsToJiraCsv = (
  drafts: DefectDraft[],
  template: JiraCsvTemplateField[] = buildDefaultJiraCsvTemplate()
): string => {
  const header = template.map((field) => field.header)
  const rows = renderJiraCsvRows(drafts, template)
  return `\ufeff${[header, ...rows].map((row) => row.map(csvEscape).join(',')).join('\n')}`
}

const buildDefectDescription = (result: AutomationRunResult): string => [
  `请求地址: ${result.request.url}`,
  `请求方法: ${result.method}`,
  `请求体: ${JSON.stringify(result.request.body ?? '', null, 2)}`,
  `响应结果: ${JSON.stringify(result.actualResponseBody ?? result.responseBody ?? '', null, 2)}`,
  `失败原因: ${result.errorMessage || '执行失败'}`
].join('\n')

const readDefectDrafts = (storage: Storage): DefectDraft[] => {
  const raw = storage.getItem(DEFECT_DRAFT_STORAGE_KEY)
  if (!raw) return []

  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter(isDefectDraft) : []
  } catch {
    return []
  }
}

const writeDefectDrafts = (storage: Storage, drafts: DefectDraft[]) => {
  storage.setItem(DEFECT_DRAFT_STORAGE_KEY, JSON.stringify(drafts))
}

const isDefectDraft = (value: unknown): value is DefectDraft => {
  if (!value || typeof value !== 'object') return false
  const record = value as Record<string, unknown>
  return (
    typeof record.defectId === 'string' &&
    typeof record.projectName === 'string' &&
    typeof record.issueType === 'string' &&
    typeof record.summary === 'string' &&
    typeof record.status === 'string'
  )
}

const csvEscape = (value: string): string => `"${String(value ?? '').replace(/"/g, '""')}"`

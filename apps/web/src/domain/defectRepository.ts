import type { AutomationRunResult } from './apiAutomation'
import { copyDefectEvidence, type DefectEvidence, type DefectWebResultInput } from './defectEvidence'
export type { DefectStepEvidence, DefectStepLog, DefectWebResultInput } from './defectEvidence'
import { validateDefectEvidence, validateDefectJson, requireEvidenceObject, type DefectEvidenceFormat } from './defectEvidenceCodec'
import { DefectPersistenceError, isDefectPersistenceError } from './defectEvidenceError'
export { DefectPersistenceError, isDefectPersistenceError } from './defectEvidenceError'
import {
  buildDefaultJiraCsvTemplate,
  renderJiraCsvRows,
  type JiraCsvTemplateField
} from './jiraCsvTemplate'

export const DEFECT_DRAFT_STORAGE_KEY = 'ai-test-platform.defect-drafts.v1'

export type DefectDraftStatus = 'pending_jira_export' | 'exported_jira_csv' | 'created_in_jira'
export type JiraExportMode = 'csv' | 'api'

export interface DefectDraft extends DefectEvidence {
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
  results: DefectWebResultInput[],
  options: BuildDefectDraftOptions,
  compatibility: { evidenceFormat?: DefectEvidenceFormat } = {}
): DefectDraft[] =>
  results
    .filter((result) => result.status === 'failed' || result.status === 'error')
    .map((result) => {
      const now = new Date().toISOString()
      const evidence = copyDefectEvidence(result)
      validateDefectEvidence(evidence, 'build-web', compatibility.evidenceFormat)
      const { stepLogs, stepEvidence } = evidence
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
          `失败原因: ${result.errorMessage || '执行失败'}`,
          ...(result.failedStepId ? [`失败步骤: ${result.failedStepId}`] : []),
          ...(stepLogs ? [`步骤日志: ${stepLogs.length} 条`] : []),
          ...(stepEvidence ? [`步骤证据: ${stepEvidence.length} 条`] : [])
        ].join('\n'),
        blocksIssueKey: '',
        faultCategory: options.faultCategory || 'Web自动化失败',
        status: 'pending_jira_export',
        createdAt: now,
        updatedAt: now,
        jiraFieldValues: {},
        evidenceVersion: 1,
        ...evidence
      }
    })

export const listDefectDrafts = (storage: Storage): DefectDraft[] =>
  readDefectDrafts(storage).sort((left, right) => right.createdAt.localeCompare(left.createdAt))

export const saveDefectDrafts = (storage: Storage, drafts: DefectDraft[]): SaveDefectDraftsResult => {
  const current = readDefectDrafts(storage, 'save')
  for (const draft of drafts) validateDraftCollection([draft], 'save')
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

/** Import-only insert: never resubmits existing rows and rejects a stale read before writing. */
export const insertDefectDrafts = (storage: Storage, drafts: DefectDraft[]): SaveDefectDraftsResult => {
  let expectedRaw: string | null
  try { expectedRaw = storage.getItem(DEFECT_DRAFT_STORAGE_KEY) }
  catch { throw new DefectPersistenceError('unavailable', 'confirmed-no-write', 'import', '无法读取导入前草稿，未保存') }
  const current = readDefectDrafts(storage, 'import')
  validateDraftCollection(drafts, 'import')
  const ids = new Set(current.map(draft => draft.defectId))
  if (drafts.some(draft => ids.has(draft.defectId))) {
    throw new DefectPersistenceError('conflict', 'confirmed-no-write', 'import', '导入草稿与现有标识冲突，未覆盖')
  }
  const next = [...current, ...drafts]
  writeDefectDrafts(storage, next, 'import', expectedRaw)
  return { inserted: drafts.length, updated: 0, total: next.length }
}

export const updateDefectDraft = (storage: Storage, draft: DefectDraft): void => {
  const current = readDefectDrafts(storage, 'update')
  validateDraftCollection([draft], 'update')
  requireTargets(current, [draft.defectId], 'update')
  const next = current.map((item) => (item.defectId === draft.defectId ? draft : item))
  writeDefectDrafts(storage, next, 'update')
}

export const deleteDefectDraft = (storage: Storage, defectId: string): boolean => {
  const current = readDefectDrafts(storage, 'delete')
  requireTargets(current, [defectId], 'delete')
  const next = current.filter((draft) => draft.defectId !== defectId)
  writeDefectDrafts(storage, next, 'delete')
  return next.length !== current.length
}

export const deleteDefectDrafts = (storage: Storage, defectIds: string[]): DeleteDefectDraftsResult => {
  const deleting = new Set(defectIds)
  const current = readDefectDrafts(storage, 'delete-many')
  requireTargets(current, defectIds, 'delete-many')
  const next = current.filter((draft) => !deleting.has(draft.defectId))
  writeDefectDrafts(storage, next, 'delete-many')
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
  const current = readDefectDrafts(storage, 'mark-exported')
  requireTargets(current, defectIds, 'mark-exported')
  const next = current.map((draft) =>
    defectIds.includes(draft.defectId)
      ? {
          ...draft,
          // CSV is an export event, not a rollback of the Jira creation/link state.
          status: mode === 'csv' && draft.status === 'created_in_jira' ? draft.status : nextStatus,
          ...(mode === 'csv' && draft.status === 'created_in_jira' ? {} : { jiraExportMode: mode }),
          ...(jiraIssueKeyMap[draft.defectId] ? { jiraIssueKey: jiraIssueKeyMap[draft.defectId] } : {}),
          updatedAt: new Date().toISOString()
        }
      : draft
  )
  writeDefectDrafts(storage, next, 'mark-exported')
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

const own = (value: object, key: PropertyKey): boolean => Object.prototype.hasOwnProperty.call(value, key)

const validateDraftCollectionUnchecked = (value: unknown, operation: string): DefectDraft[] => {
  validateDefectJson(value, operation)
  if (!Array.isArray(value)) throw new DefectPersistenceError('invalid-data', 'confirmed-no-write', operation, '缺陷集合格式无效，未保存', '$')
  const ids = new Set<string>()
  value.forEach((item, index) => {
    const path = `$.${index}`
    const r = requireEvidenceObject(item, operation, path)
    if (own(r, 'evidenceVersion') && r.evidenceVersion !== 1) throw new DefectPersistenceError('incompatible-version', 'confirmed-no-write', operation, '证据版本暂不支持，已停止写入', `${path}.evidenceVersion`)
    for (const key of ['defectId', 'sourceReportCaseId', 'sourceCaseId', 'projectName', 'issueType', 'summary', 'priority', 'assignee', 'description', 'blocksIssueKey', 'faultCategory', 'status', 'createdAt', 'updatedAt']) {
      if (!own(r, key) || typeof r[key] !== 'string') throw new DefectPersistenceError('invalid-data', 'confirmed-no-write', operation, '缺陷数据格式无效，未保存', `${path}.${key}`)
    }
    if (!['pending_jira_export', 'exported_jira_csv', 'created_in_jira'].includes(r.status as string) || ids.has(r.defectId as string)) throw new DefectPersistenceError('invalid-data', 'confirmed-no-write', operation, '缺陷状态或标识无效，未保存', path)
    ids.add(r.defectId as string)
    const fields = requireEvidenceObject(r.jiraFieldValues, operation, `${path}.jiraFieldValues`)
    if (Object.values(fields).some(value => typeof value !== 'string')) throw new DefectPersistenceError('invalid-data', 'confirmed-no-write', operation, 'Jira 字段格式无效，未保存', `${path}.jiraFieldValues`)
    for (const key of ['jiraIssueKey', 'jiraExportMode']) if (own(r, key) && typeof r[key] !== 'string') throw new DefectPersistenceError('invalid-data', 'confirmed-no-write', operation, 'Jira 字段格式无效，未保存', `${path}.${key}`)
    if (own(r, 'jiraExportMode') && r.jiraExportMode !== 'csv' && r.jiraExportMode !== 'api') throw new DefectPersistenceError('invalid-data', 'confirmed-no-write', operation, 'Jira 导出模式无效，未保存', `${path}.jiraExportMode`)
    // Legacy is independently read, never upgraded. Both formats remain inert JSON data.
    validateDefectEvidence(r, operation, 'legacy-flat', path)
  })
  return value as DefectDraft[]
}

const validateDraftCollection = (value: unknown, operation: string): DefectDraft[] => {
  try { return validateDraftCollectionUnchecked(value, operation) }
  catch (error) {
    if (isDefectPersistenceError(error)) throw error
    throw new DefectPersistenceError('invalid-data', 'confirmed-no-write', operation, '缺陷数据格式无效，未保存')
  }
}

export const validateDefectDraftsForImport = (value: unknown): DefectDraft[] =>
  validateDraftCollection(value, 'import')

const readDefectDrafts = (storage: Storage, operation = 'read'): DefectDraft[] => {
  let raw: string | null
  try { raw = storage.getItem(DEFECT_DRAFT_STORAGE_KEY) }
  catch { throw new DefectPersistenceError('unavailable', 'confirmed-no-write', operation, '无法读取本地缺陷，已停止写入') }
  if (raw === null) return []
  try {
    return validateDraftCollection(JSON.parse(raw), operation)
  } catch (error) {
    const incompatible = isDefectPersistenceError(error) && error.code === 'incompatible-version'
    throw new DefectPersistenceError(incompatible ? 'incompatible-version' : 'corrupt-store', 'confirmed-no-write', operation, incompatible ? '证据版本暂不支持，已停止写入' : '本地缺陷数据损坏，已停止写入', isDefectPersistenceError(error) ? error.path : '$', raw)
  }
}

const requireTargets = (drafts: DefectDraft[], ids: string[], operation: string) => {
  if (ids.some(id => !drafts.some(draft => draft.defectId === id))) throw new DefectPersistenceError('missing-target', 'confirmed-no-write', operation, '目标缺陷已不存在，未保存')
}

const writeDefectDrafts = (storage: Storage, drafts: DefectDraft[], operation = 'save', expectedRaw?: string | null) => {
  let serialized: string
  try {
    validateDraftCollection(drafts, operation)
    serialized = JSON.stringify(drafts)
  } catch (error) {
    if (isDefectPersistenceError(error)) throw error
    throw new DefectPersistenceError('invalid-data', 'confirmed-no-write', operation, '缺陷数据无法序列化，未保存')
  }
  if (expectedRaw !== undefined) {
    let currentRaw: string | null
    try { currentRaw = storage.getItem(DEFECT_DRAFT_STORAGE_KEY) }
    catch { throw new DefectPersistenceError('verify-failed', 'uncertain-commit', operation, '无法确认导入前数据，请重试') }
    if (currentRaw !== expectedRaw) throw new DefectPersistenceError('conflict', 'confirmed-no-write', operation, '导入前草稿已变化，未覆盖')
  }
  try { storage.setItem(DEFECT_DRAFT_STORAGE_KEY, serialized) }
  catch (error) {
    // Only a native quota rejection has the single-key atomic no-write guarantee.
    const nativeQuota = typeof DOMException !== 'undefined' && error instanceof DOMException && error.name === 'QuotaExceededError'
    const unavailable = typeof DOMException !== 'undefined' && error instanceof DOMException && error.name === 'SecurityError'
    throw new DefectPersistenceError(nativeQuota ? 'quota' : unavailable ? 'unavailable' : 'unknown', nativeQuota ? 'confirmed-no-write' : 'uncertain-commit', operation, nativeQuota ? '本地空间不足，未保存；可下载本次候选 JSON' : '无法确认保存结果，请核对后重试')
  }
  let actual: string | null
  try { actual = storage.getItem(DEFECT_DRAFT_STORAGE_KEY) }
  catch { throw new DefectPersistenceError('verify-failed', 'uncertain-commit', operation, '无法确认保存结果，请核对后重试') }
  if (actual !== serialized) throw new DefectPersistenceError('verify-failed', 'uncertain-commit', operation, '无法确认保存结果，请核对后重试')
}

const csvEscape = (value: string): string => `"${String(value ?? '').replace(/"/g, '""')}"`

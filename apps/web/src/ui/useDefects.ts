import { computed, reactive, ref, shallowRef, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { missingDep } from './depGuard'
import {
  buildDefectDraftsFromAutomationResults,
  buildDefectDraftsFromWebAutomationResults,
  deleteDefectDrafts,
  exportDefectsToJiraCsv,
  insertDefectDrafts,
  listDefectDrafts,
  saveDefectDrafts,
  type DefectWebResultInput,
  type DefectDraftStatus,
  type DefectDraft
} from '../domain/defectRepository'
import {
  buildDefectFilterOptionsFromIssues,
  buildDefectIssueRecordsFromDrafts,
  buildDefectIssueRecordsFromJiraIssues,
  buildDefectMetrics,
  buildDefectProjectGroups,
  filterDefectIssuesByProject,
  filterDefectDrafts,
  mergeDefectProjectOptions,
  mergeDefectIssueRecords,
  type DefectIssueRecord,
  type JiraIssueSource
} from '../domain/defectView'
import { loadJiraIssues } from '../domain/jiraIssueRepository'
import {
  buildEnabledJiraCsvTemplate,
  buildDefaultJiraCsvTemplate,
  newCustomJiraCsvTemplateField,
  type JiraCsvTemplateField
} from '../domain/jiraCsvTemplate'
import {
  JIRA_CSV_TEMPLATE_STORAGE_KEY,
  loadJiraCsvTemplate,
  saveJiraCsvTemplate
} from '../domain/jiraCsvTemplateRepository'
import { downloadCsv } from '../domain/csvExport'
import type { AutomationRunResult } from '../domain/apiAutomation'
import { buildDefectListJsonArtifact } from '../domain/defectExport'
import { parseDefectImportArtifact } from '../domain/defectImport'

export interface DefectsDeps {
  storage?: Storage
  captureMutation?: (isCurrent: () => boolean) => { storage: Storage; isCurrent: () => boolean }
  downloadTextFile?: (filename: string, text: string, mimeType: string) => void
  canTriggerDownload?: (isCurrent: () => boolean) => boolean
  beginOperation?: () => () => boolean
  // Cross-cutting project name sources (imported/library/automation/report projects).
  candidateProjects?: () => string[]
  confirmDanger?: (title: string, message: string, confirmLabel?: string) => Promise<boolean>
  navigateToDefects?: () => void | boolean | Promise<void | boolean>
  isDefectsActive?: () => boolean
}

// Module-level singleton so App.vue (context pane + cross-view status tags) and
// DefectsView.vue (main view) share the same defect state.
let singleton: ReturnType<typeof createDefectsState> | null = null

function createDefectsState(deps: DefectsDeps) {
  const localStorage = deps.storage ?? window.localStorage
  const confirmDanger = deps.confirmDanger ?? (async () => missingDep('useDefects', 'confirmDanger'))
  const navigateToDefects = deps.navigateToDefects ?? (() => missingDep('useDefects', 'navigateToDefects'))
  const isDefectsActive = deps.isDefectsActive ?? (() => true)

  type FailureOutcome = 'confirmed-no-write' | 'uncertain-commit' | 'committed-navigation-failed'
  type Authority = { storage: Storage; isCurrent: () => boolean }
  type FailureOwner = 'defect' | 'template'
  type FailureRecord = { outcome: FailureOutcome; message: string; code: string }
  type Candidate = { revision: number; operation: string; drafts: DefectDraft[]; ids: string[]; baseline: DefectDraft[]; authority: Authority; finish: () => void; formSnapshot?: string }
  const defectFailure = ref<FailureRecord | null>(null)
  let failureOwner: FailureOwner = 'defect'
  const pendingFailures: Record<FailureOwner, FailureRecord | null> = { defect: null, template: null }
  const defectRecoveryCandidate = shallowRef<Candidate | null>(null)
  type DefectImportPreview = { authority: Authority; drafts: DefectDraft[]; conflicts: DefectDraft[] }
  const defectImportPreview = shallowRef<DefectImportPreview | null>(null)
  const defectTemplateCandidate = shallowRef<{ authority: Authority; fields: JiraCsvTemplateField[]; baseline: string | null; serialized: string } | null>(null)
  let mutationGeneration = 0
  let revision = 0
  const captureMutation = (): Authority => {
    const generation = mutationGeneration
    const current = () => generation === mutationGeneration
    const captured = deps.captureMutation?.(current)
    return captured ?? { storage: localStorage, isCurrent: current }
  }
  const defectRawStore = ref<string | null>(null)
  const setFailure = (owner: FailureOwner, failure: FailureRecord | null) => {
    pendingFailures[owner] = failure
    failureOwner = owner
    defectFailure.value = failure
  }
  const restorePendingFailure = () => {
    const owner: FailureOwner | null = pendingFailures.defect ? 'defect' : pendingFailures.template ? 'template' : null
    setFailure(owner ?? 'defect', owner ? pendingFailures[owner] : null)
  }
  const publishFailure = (error: unknown, authority: Authority, fallback = 'uncertain-commit' as FailureOutcome, owner: FailureOwner = 'defect') => {
    if (!authority.isCurrent()) return
    const record = error as { outcome?: FailureOutcome; code?: string; name?: string; raw?: string }
    if (typeof record?.raw === 'string') defectRawStore.value = record.raw
    const outcome = record?.outcome ?? (record?.name === 'QuotaExceededError' ? 'confirmed-no-write' : fallback)
    const code = record?.code ?? (record?.name === 'QuotaExceededError' ? 'quota' : 'unknown')
    const reasons: Record<string, string> = { quota: '本地空间不足', unavailable: '本地存储不可用/读取失败', 'corrupt-store': '草稿读取失败：存储内容损坏，写入已停止', 'incompatible-version': '草稿读取失败：证据版本暂不支持，写入已停止', 'invalid-data': '数据格式无效，未裁剪或丢弃证据', 'missing-target': '目标草稿已不存在', 'verify-failed': '写入后读取核对失败', unknown: '本地操作失败' }
    const reason = reasons[code] ?? reasons.unknown
    const message = outcome === 'confirmed-no-write' ? `未保存：${reason}。请保留候选后重试。`
      : outcome === 'committed-navigation-failed' ? '已保存，但页面未更新/跳转失败。请仅重试跳转。'
      : `无法确认保存结果，请先核对后重试。${reason}。`
    setFailure(owner, { outcome, code, message })
    if (defectEditVisible.value) defectEditError.value = message
  }
  const defectDrafts = ref<DefectDraft[]>([])
  const defectSelection = ref<DefectDraft[]>([])
  const jiraIssues = ref<JiraIssueSource[]>([])
  const jiraProjectName = ref('IKOS')
  const jiraIssueType = ref('故障')
  const jiraCsvTemplate = ref<JiraCsvTemplateField[]>(buildDefaultJiraCsvTemplate())
  const jiraTemplateVisible = ref(false)
  const jiraCsvTemplateDraft = ref<JiraCsvTemplateField[]>([])
  const cloneTemplate = (fields: JiraCsvTemplateField[]) => fields.map(field => ({ ...field, source: { ...field.source } }))
  watch(jiraTemplateVisible, visible => {
    jiraCsvTemplateDraft.value = visible ? cloneTemplate(jiraCsvTemplate.value) : []
  }, { flush: 'sync' })
  const defectEditVisible = ref(false)
  const editingDefect = ref<DefectDraft>()
  /** 最近一次「转缺陷」创建的草稿 id,质量工作台缺陷列表用它做行高亮;用户交互后清除 */
  const recentlyTransferredDefectIds = ref<string[]>([])
  const defectFilter = reactive({
    project: 'ALL',
    status: 'ALL' as DefectDraftStatus | 'ALL',
    issueType: 'ALL',
    priority: 'ALL',
    assignee: 'ALL',
    keyword: ''
  })
  const defectSource = ref<'draft' | 'jira'>('draft')
  const newListContext = () => ({ filters: { ...defectFilter }, page: 1, scrollTop: 0, scrollLeft: 0 })
  const defectListContexts = reactive({ draft: newListContext(), jira: newListContext() })
  const defectPage = computed({
    get: () => defectListContexts[defectSource.value].page,
    set: (page: number) => { defectListContexts[defectSource.value].page = page }
  })
  const defectPageSize = 6
  const defectEditForm = reactive({
    summary: '',
    projectName: '',
    issueType: '',
    priority: '',
    assignee: '',
    description: '',
    faultCategory: '',
    blocksIssueKey: ''
  })

  const defectMetrics = computed(() => buildDefectMetrics(defectDrafts.value))
  const exportedDefectCount = computed(() => defectMetrics.value.exported + defectMetrics.value.created)
  const defectIssueRecords = computed(() =>
    mergeDefectIssueRecords(
      buildDefectIssueRecordsFromDrafts(defectDrafts.value),
      buildDefectIssueRecordsFromJiraIssues(jiraIssues.value)
    )
  )
  const defectProjectGroups = computed(() => buildDefectProjectGroups(defectIssueRecords.value))
  const defectIssuesForActiveProject = computed(() =>
    filterDefectIssuesByProject(defectIssueRecords.value, defectFilter.project)
  )
  const sourceDefectIssues = computed(() => defectIssueRecords.value.filter(row => row.sourceKind === defectSource.value))
  const defectFilterOptions = computed(() => buildDefectFilterOptionsFromIssues(sourceDefectIssues.value))
  const filteredDefectDrafts = computed(() => filterDefectDrafts(defectDrafts.value, defectFilter))
  const filteredDefectIssues = computed(() => sourceDefectIssues.value.filter(row => {
    for (const key of ['project', 'status', 'issueType', 'priority', 'assignee'] as const) {
      const value = defectFilter[key]
      if (value !== 'ALL' && value !== (key === 'project' ? row.projectName : row[key])) return false
    }
    const keyword = defectFilter.keyword.trim().toLowerCase()
    return !keyword || [row.id, row.summary, row.description, row.issueKey, row.draft.sourceCaseId,
      row.draft.sourceReportCaseId, row.draft.faultCategory].join(' ').toLowerCase().includes(keyword)
  }))
  const defectPageCount = computed(() => Math.max(1, Math.ceil(filteredDefectIssues.value.length / defectPageSize)))
  const pagedDefectIssues = computed(() => filteredDefectIssues.value.slice((defectPage.value - 1) * defectPageSize, defectPage.value * defectPageSize))
  const activeDefectScope = computed(() =>
    defectSource.value !== 'draft' ? [] : defectSelection.value.length > 0 ? defectSelection.value : filteredDefectDrafts.value
  )
  const defectCandidateProjects = computed(() => deps.candidateProjects?.() ?? [])
  const defectProjectOptions = computed(() =>
    mergeDefectProjectOptions(defectFilterOptions.value.projectOptions, defectCandidateProjects.value)
  )
  const enabledJiraCsvTemplate = computed(() => buildEnabledJiraCsvTemplate(jiraCsvTemplate.value))
  const defectDraftByReportCaseId = computed(
    () => new Map(defectDrafts.value.map((draft) => [draft.sourceReportCaseId, draft]))
  )

  const defectReadBlocked = ref(false)
  const refreshDefectDrafts = () => {
    const authority = captureMutation()
    if (!authority.isCurrent()) return false
    try {
      const current = listDefectDrafts(authority.storage)
      if (!authority.isCurrent()) return false
      defectDrafts.value = current
      defectReadBlocked.value = false
      return true
    } catch (error) { defectReadBlocked.value = true; publishFailure(error, authority, 'confirmed-no-write'); return false }
  }

  const refreshJiraIssues = async () => {
    const currentOperation = deps.beginOperation?.() ?? (() => true)
    const project = defectFilter.project
    const isCurrent = () => currentOperation() && project === defectFilter.project && isDefectsActive()
    if (!isCurrent()) return
    if (defectFilter.project === 'ALL') {
      jiraIssues.value = []
      return
    }

    try {
      const result = await loadJiraIssues({
        projectName: defectFilter.project,
        authMode: 'service-account'
      })
      if (isCurrent()) jiraIssues.value = result.items
    } catch {
      if (isCurrent()) jiraIssues.value = []
    }
  }

  const defectStatusText = (draft?: DefectDraft): string => {
    if (!draft) return '未转缺陷'
    if (draft.status === 'created_in_jira') return draft.jiraIssueKey ? `已建 Jira ${draft.jiraIssueKey}` : '已建 Jira'
    if (draft.status === 'exported_jira_csv') return '已导出 Jira CSV'
    return '待导出 Jira'
  }

  const defectStatusTagType = (draft?: DefectDraft): 'warning' | 'success' | 'info' => {
    if (!draft) return 'info'
    if (draft.status === 'pending_jira_export') return 'warning'
    if (draft.status === 'exported_jira_csv') return 'success'
    return 'info'
  }

  const defectIssueStatusTagType = (sourceKind: 'draft' | 'jira', draft?: DefectDraft): 'warning' | 'success' | 'info' => {
    if (sourceKind === 'jira') return 'info'
    return defectStatusTagType(draft)
  }

  const defectStatusLabel = (caseId: string): string => {
    const draft = defectDraftByReportCaseId.value.get(caseId)
    return defectStatusText(draft)
  }

  const createDefects = (build: () => DefectDraft[]) => {
    const authority = captureMutation()
    if (!authority.isCurrent()) return false
    try {
      const drafts = build()
      if (!drafts.length) { ElMessage.info('当前没有可转缺陷的失败用例'); return false }
      const baseline = listDefectDrafts(authority.storage)
      const candidate: Candidate = { revision: ++revision, operation: 'create', authority, drafts, ids: drafts.map(d => d.defectId), baseline,
        finish: () => {
          recentlyTransferredDefectIds.value = drafts.map(d => d.defectId)
          void navigateCommittedCandidate(candidate)
        }
      }
      return commitCandidate(candidate)
    } catch (error) { publishFailure(error, authority, 'confirmed-no-write'); return false }
  }
  const createDefectsFromResults = (results: AutomationRunResult[], projectName: string) => createDefects(() => buildDefectDraftsFromAutomationResults(results, {
    projectName: projectName || jiraProjectName.value || 'IKOS', issueType: jiraIssueType.value || '故障'
  }))
  const createDefectsFromWebResults = (results: DefectWebResultInput[], projectName: string) => createDefects(() => buildDefectDraftsFromWebAutomationResults(results, {
    projectName: projectName || jiraProjectName.value || 'IKOS', issueType: jiraIssueType.value || '故障'
  }))

  const updateDefectField = (draft: DefectDraft, patch: Partial<DefectDraft>) => {
    const authority = captureMutation()
    if (!authority.isCurrent()) return false
    try {
      const baseline = listDefectDrafts(authority.storage)
      const target = baseline.find(item => item.defectId === draft.defectId)
      if (!target) throw { code: 'missing-target', outcome: 'confirmed-no-write' }
      return commitCandidate({ revision: ++revision, operation: 'update', authority, ids: [draft.defectId], baseline,
        drafts: [{ ...target, ...patch, updatedAt: new Date().toISOString() }], finish: () => {} })
    } catch (error) { publishFailure(error, authority, 'confirmed-no-write'); return false }
  }

  const defectEditBaseline = ref('')

  const snapshotDefectForm = () => JSON.stringify({ ...defectEditForm })
  const defectEditDirty = computed(() => defectEditVisible.value && snapshotDefectForm() !== defectEditBaseline.value)
  const defectEditError = ref('')
  try { defectDrafts.value = listDefectDrafts(localStorage) } catch (error) {
    defectReadBlocked.value = true
    publishFailure(error, { storage: localStorage, isCurrent: () => true }, 'confirmed-no-write')
  }
  try { jiraCsvTemplate.value = loadJiraCsvTemplate(localStorage) } catch { /* The draft read already exposes unavailable storage; do not publish a second error. */ }
  const semantic = (value: unknown): string => JSON.stringify(value, (_key, item) => item && typeof item === 'object' && !Array.isArray(item)
    ? Object.fromEntries(Object.keys(item).sort().map(key => [key, item[key]])) : item)
  const sameTargets = (left: DefectDraft[], right: DefectDraft[], ids: string[]) => ids.every(id => semantic(left.find(d => d.defectId === id)) === semantic(right.find(d => d.defectId === id)))
  const completeCandidate = (candidate: Candidate, current: DefectDraft[]) => {
    if (!candidate.authority.isCurrent()) return false
    defectDrafts.value = current
    if (defectRecoveryCandidate.value?.revision === candidate.revision) defectRecoveryCandidate.value = null
    pendingFailures.defect = null
    restorePendingFailure()
    candidate.finish()
    return true
  }
  const attemptDrafts = new WeakMap<Candidate, DefectDraft[]>()
  const attemptBaseline = new WeakMap<Candidate, DefectDraft[]>()
  const csvMark = (draft: DefectDraft, timestamp: string): DefectDraft => ({ ...draft,
    status: draft.status === 'created_in_jira' ? draft.status : 'exported_jira_csv',
    ...(draft.status === 'created_in_jira' ? {} : { jiraExportMode: 'csv' as const }), updatedAt: timestamp
  })
  const commitCandidate = (candidate: Candidate): boolean => {
    const { authority } = candidate
    if (!authority.isCurrent()) return false
    const previous = defectRecoveryCandidate.value
    if (previous && previous.revision !== candidate.revision) {
      void (async () => {
        try {
          const confirmed = await confirmDanger('放弃上次失败候选？', '替换将丢弃上次未保存候选；请先下载需要保留的数据。', '放弃并替换')
          if (!confirmed || !authority.isCurrent() || !previous.authority.isCurrent() || defectRecoveryCandidate.value !== previous) return
          defectRecoveryCandidate.value = null
          commitCandidate(candidate)
        } catch (error) { publishFailure(error, authority, 'confirmed-no-write') }
      })()
      return false
    }
    defectRecoveryCandidate.value = candidate
    try {
      const current = listDefectDrafts(authority.storage)
      if (!authority.isCurrent()) return false
      if (['edit', 'update'].includes(candidate.operation) && candidate.ids.some(id => !current.some(d => d.defectId === id))) throw { code: 'missing-target', outcome: 'confirmed-no-write' }
      if (candidate.operation === 'csv-mark') {
        if (candidate.ids.some(id => !current.some(d => d.defectId === id))) throw { code: 'missing-target', outcome: 'confirmed-no-write' }
        const timestamp = candidate.drafts[0].updatedAt
        attemptDrafts.set(candidate, current.filter(d => candidate.ids.includes(d.defectId)).map(d => csvMark(d, timestamp)))
      } else if (!sameTargets(current, candidate.baseline, candidate.ids)) {
        setFailure('defect', { outcome: 'uncertain-commit', code: 'conflict', message: '当前草稿已变化，未覆盖。请下载或放弃本次候选。' })
        return false
      }
      const expected = attemptDrafts.get(candidate) ?? candidate.drafts
      attemptBaseline.set(candidate, current)
      if (candidate.operation === 'delete') deleteDefectDrafts(authority.storage, candidate.ids)
      else saveDefectDrafts(authority.storage, expected)
      if (!authority.isCurrent()) return false
      const readback = listDefectDrafts(authority.storage)
      if (!authority.isCurrent()) return false
      if (!sameTargets(readback, expected, candidate.ids)) throw { code: 'verify-failed', outcome: 'uncertain-commit' }
      return completeCandidate(candidate, readback)
    } catch (error) { publishFailure(error, authority); return false }
  }
  let committedNavigation: Candidate | null = null
  const navigateCommittedCandidate = async (candidate: Candidate) => {
    if (!candidate.authority.isCurrent()) return false
    try {
      const result = await navigateToDefects()
      if (!candidate.authority.isCurrent()) return false
      if (result === false || !(deps.isDefectsActive ? deps.isDefectsActive() : result === true)) throw Error('navigation not observed')
      committedNavigation = null
      pendingFailures.defect = null
      restorePendingFailure()
      ElMessage.success(`已转入缺陷 ${candidate.ids.length} 条`)
      return true
    } catch {
      if (candidate.authority.isCurrent()) {
        committedNavigation = candidate
        publishFailure({ outcome: 'committed-navigation-failed' }, candidate.authority)
      }
      return false
    }
  }
  const retryDefectMutation = async (requestedOwner?: FailureOwner) => {
    const retryOwner = requestedOwner ?? failureOwner
    const templateCandidate = defectTemplateCandidate.value
    if (templateCandidate && retryOwner === 'template') {
      const { authority, serialized, fields, baseline } = templateCandidate
      if (!authority.isCurrent()) return false
      try {
        const current = authority.storage.getItem(JIRA_CSV_TEMPLATE_STORAGE_KEY)
        if (!authority.isCurrent()) return false
        if (current !== serialized) {
          if (current !== baseline) { setFailure('template', { outcome: 'uncertain-commit', code: 'conflict', message: '当前 CSV 配置已变化，未覆盖。' }); return false }
          if (pendingFailures.template?.outcome === 'uncertain-commit') { setFailure('template', { outcome: 'confirmed-no-write', code: 'reconciled-baseline', message: '已核对 CSV 配置仍为原基线，请再次明确重试保存。' }); return false }
          saveJiraCsvTemplate(authority.storage, fields)
          if (!authority.isCurrent()) return false
          if (authority.storage.getItem(JIRA_CSV_TEMPLATE_STORAGE_KEY) !== serialized) throw { outcome: 'uncertain-commit', code: 'verify-failed' }
        }
        if (!authority.isCurrent()) return false
        jiraCsvTemplate.value = cloneTemplate(fields)
        jiraTemplateVisible.value = false
        defectTemplateCandidate.value = null
        pendingFailures.template = null
        restorePendingFailure()
        return true
      } catch (error) { publishFailure(error, authority, undefined, 'template'); return false }
    }
    if (committedNavigation) return navigateCommittedCandidate(committedNavigation)
    const candidate = defectRecoveryCandidate.value
    if (!candidate?.authority.isCurrent()) return false
    try {
      const current = listDefectDrafts(candidate.authority.storage)
      if (!candidate.authority.isCurrent()) return false
      if (['edit', 'update'].includes(candidate.operation) && candidate.ids.some(id => !current.some(d => d.defectId === id))) throw { code: 'missing-target', outcome: 'confirmed-no-write' }
      if (sameTargets(current, attemptDrafts.get(candidate) ?? candidate.drafts, candidate.ids)) return completeCandidate(candidate, current)
      if (pendingFailures.defect?.outcome === 'uncertain-commit') {
        if (!sameTargets(current, attemptBaseline.get(candidate) ?? candidate.baseline, candidate.ids)) {
          setFailure('defect', { outcome: 'uncertain-commit', code: 'conflict', message: '当前草稿已变化，未覆盖。请下载或放弃本次候选。' })
          return false
        }
        setFailure('defect', { outcome: 'confirmed-no-write', code: 'reconciled-baseline', message: '已核对：目标仍为原基线。请再次明确点击重试保存。' })
        return false
      }
      if (candidate.operation === 'csv-mark') {
        if (candidate.ids.some(id => !current.some(d => d.defectId === id))) throw { code: 'missing-target', outcome: 'confirmed-no-write' }
        const timestamp = candidate.drafts[0].updatedAt
        attemptDrafts.set(candidate, current.filter(d => candidate.ids.includes(d.defectId)).map(d => csvMark(d, timestamp)))
      } else if (!sameTargets(current, candidate.baseline, candidate.ids)) {
        setFailure('defect', { outcome: 'uncertain-commit', code: 'conflict', message: '当前草稿已变化，未覆盖。请下载或放弃本次候选。' })
        return false
      }
      return commitCandidate(candidate)
    } catch (error) { publishFailure(error, candidate.authority, undefined, 'defect'); return false }
  }
  const downloadRecoveryText = (authority: Authority, filename: string, text: string, mime: string) => {
    if (!authority.isCurrent()) return false
    if (deps.downloadTextFile) {
      if (deps.canTriggerDownload && !deps.canTriggerDownload(authority.isCurrent)) return false
      deps.downloadTextFile(filename, text, mime)
    }
    else {
      const url = URL.createObjectURL(new Blob([text], { type: mime }))
      try {
        if (!authority.isCurrent()) return false
        const anchor = document.createElement('a'); anchor.href = url; anchor.download = filename; anchor.click()
      } finally { URL.revokeObjectURL(url) }
    }
    return authority.isCurrent()
  }
  const exportDefectRawStore = async () => {
    const authority = captureMutation(), raw = defectRawStore.value
    if (!authority.isCurrent() || raw === null) return false
    try { return downloadRecoveryText(authority, 'defect-store-original.txt', raw, 'text/plain') }
    catch { if (authority.isCurrent()) defectFailure.value = { outcome: 'confirmed-no-write', code: 'download-failed', message: '原始文本导出失败/未确认下载；原始数据仍保留。' }; return false }
  }
  const exportDefectRecoveryCandidate = async () => {
    const candidate = defectRecoveryCandidate.value
    if (!candidate?.authority.isCurrent()) return false
    try {
      const drafts = candidate.operation === 'delete' ? candidate.baseline.filter(d => candidate.ids.includes(d.defectId)) : candidate.drafts
      const text = JSON.stringify({ kind: 'ai-test-platform.defect-recovery', version: 1, exportedAt: new Date().toISOString(), operation: candidate.operation, revision: candidate.revision, drafts }, null, 2)
      if (!candidate.authority.isCurrent()) return false
      if (!downloadRecoveryText(candidate.authority, 'defect-recovery.json', text, 'application/json')) return false
      if (candidate.authority.isCurrent()) ElMessage.info('已发起下载，请确认文件保存；未保存数据仍需保留。')
      return true
    } catch { if (candidate.authority.isCurrent()) defectFailure.value = { outcome: defectFailure.value?.outcome ?? 'confirmed-no-write', code: 'download-failed', message: '导出失败/未确认下载；候选仍保留。' }; return false }
  }

  const fillDefectEditForm = (draft: DefectDraft) => {
    Object.assign(defectEditForm, {
      summary: draft.summary,
      projectName: draft.projectName,
      issueType: draft.issueType,
      priority: draft.priority,
      assignee: draft.assignee,
      description: draft.description,
      faultCategory: draft.faultCategory,
      blocksIssueKey: draft.blocksIssueKey
    })
    defectEditBaseline.value = snapshotDefectForm()
  }

  const openDefectEdit = (draft: DefectDraft) => {
    if (!defectDrafts.value.some(item => item.defectId === draft.defectId)) return false
    detailDefectId.value = draft.defectId
    editingDefect.value = draft
    defectEditError.value = ''
    fillDefectEditForm(draft)
    defectEditVisible.value = true
    return true
  }

  const validateDefectEditForm = (): boolean => {
    if (!defectEditForm.summary.trim()) defectEditError.value = '概要不能为空。'
    else if (!defectEditForm.projectName.trim() || !defectEditForm.issueType.trim()) defectEditError.value = '项目名称和问题类型不能为空。'
    else defectEditError.value = ''
    return !defectEditError.value
  }

  const saveDefectEdit = (): boolean => {
    if (!editingDefect.value || !defectEditVisible.value) return false
    if (!validateDefectEditForm()) return false
    const authority = captureMutation()
    const target = editingDefect.value
    const formSnapshot = snapshotDefectForm()
    const previous = defectRecoveryCandidate.value
    if (previous?.operation === 'edit' && previous.formSnapshot === formSnapshot && previous.ids[0] === target.defectId) {
      void retryDefectMutation()
      return !defectEditVisible.value
    }
    const candidate: Candidate = {
      revision: ++revision, operation: 'edit', authority, ids: [target.defectId], baseline: [JSON.parse(JSON.stringify(target))],
      drafts: [JSON.parse(JSON.stringify({ ...target, ...defectEditForm, updatedAt: new Date().toISOString() }))], formSnapshot,
      finish: () => {
        if (snapshotDefectForm() !== formSnapshot) return
        editingDefect.value = defectDrafts.value.find(item => item.defectId === target.defectId)
        if (editingDefect.value) fillDefectEditForm(editingDefect.value)
        defectEditVisible.value = false
        defectEditError.value = ''
        ElMessage.success('已保存缺陷草稿')
      }
    }
    return commitCandidate(candidate)
  }

  const discardDefectEdit = () => {
    defectEditVisible.value = false
    defectEditError.value = ''
    if (editingDefect.value) fillDefectEditForm(editingDefect.value)
  }

  const confirmDiscardDefectEdit = async (): Promise<boolean> => {
    const authority = captureMutation()
    if (!authority.isCurrent()) return false
    if (!defectEditDirty.value) return true
    try {
      const confirmed = await confirmDanger('放弃未保存修改？', '离开将丢弃本次未保存的修改；已保存的草稿保持不变。', '放弃修改')
      return authority.isCurrent() && confirmed
    } catch (error) { publishFailure(error, authority, 'confirmed-no-write'); return false }
  }

  const cancelDefectEdit = async () => {
    if (!defectEditVisible.value) return
    if (!(await confirmDiscardDefectEdit())) return
    discardDefectEdit()
  }

  const detailDefectId = ref<string | null>(null)
  const detailDefect = computed(() =>
    detailDefectId.value === null ? undefined : defectIssueRecords.value.find(row => row.id === detailDefectId.value)
  )

  const openDefectDetail = (row: DefectIssueRecord) => {
    detailDefectId.value = row.id
    editingDefect.value = undefined
    defectEditVisible.value = false
    defectEditError.value = ''
  }

  const closeDefectDetail = async (): Promise<boolean> => {
    if (!(await confirmDiscardDefectEdit())) return false
    discardDefectEdit()
    detailDefectId.value = null
    return true
  }

  const defectScopeLabel = (): string => defectSelection.value.length > 0 ? '选中' : '筛选（跨页）'

  const defectExportFileName = (drafts: DefectDraft[]): string => {
    const projects = Array.from(new Set(drafts.map((draft) => draft.projectName).filter(Boolean)))
    if (projects.length === 1) return `${projects[0]}-jira-defects.csv`
    // 多项目导出不能借单个 Jira 项目名冒充范围
    return 'multi-project-jira-defects.csv'
  }

  const exportSelectedDefectsToJiraCsv = async () => {
    const authority = captureMutation()
    if (!authority.isCurrent() || defectSource.value !== 'draft') return false
    let downloaded = false
    let preflight = true
    try {
      const drafts = JSON.parse(JSON.stringify(activeDefectScope.value)) as DefectDraft[]
      if (!drafts.length) { ElMessage.warning('当前没有可导出的缺陷'); return false }
      const baseline = listDefectDrafts(authority.storage)
      const frozenTemplate = cloneTemplate(enabledJiraCsvTemplate.value)
      const csv = exportDefectsToJiraCsv(drafts, frozenTemplate)
      const ids = drafts.map(d => d.defectId)
      const timestamp = new Date().toISOString()
      const scopeLabel = defectScopeLabel()
      const previousCandidate = defectRecoveryCandidate.value
      preflight = false
      if (previousCandidate) {
        const replace = await confirmDanger('放弃上次失败候选？', '下载新的 CSV 前请确认放弃上次未保存候选。', '放弃并继续')
        if (!replace || !authority.isCurrent() || !previousCandidate.authority.isCurrent() || defectRecoveryCandidate.value !== previousCandidate) return false
        defectRecoveryCandidate.value = null
      }
      const confirmed = await confirmDanger('导出 Jira CSV', `确认导出${scopeLabel} ${drafts.length} 条缺陷为 Jira CSV 吗？`, '导出')
      if (!authority.isCurrent() || !confirmed || defectSource.value !== 'draft') return false
      downloadCsv(defectExportFileName(drafts), csv)
      downloaded = true
      if (!authority.isCurrent()) return false
      const candidate: Candidate = { revision: ++revision, operation: 'csv-mark', authority, baseline, ids,
        drafts: drafts.map(d => csvMark(d, timestamp)),
        finish: () => { defectSelection.value = defectSelection.value.filter(d => !ids.includes(d.defectId)); ElMessage.success(`已发起 ${drafts.length} 条 Jira CSV 下载并保存导出状态`) }
      }
      const ok = commitCandidate(candidate)
      if (!ok && authority.isCurrent() && defectFailure.value) defectFailure.value.message = `已发起 CSV 下载，但导出状态${defectFailure.value.outcome === 'confirmed-no-write' ? '未保存' : '保存结果未知，请先核对'}。`
      return ok
    } catch (error) {
      if (authority.isCurrent() && preflight && ['unavailable', 'corrupt-store', 'incompatible-version'].includes((error as { code?: string })?.code ?? '')) defectReadBlocked.value = true
      publishFailure(error, authority, 'confirmed-no-write', 'defect')
      if (authority.isCurrent() && defectFailure.value && !downloaded && !preflight) defectFailure.value.message = 'CSV 下载失败/未确认下载；未保存导出状态。'
      return false
    }
  }

  const exportDefectListJson = async () => {
    const authority = captureMutation()
    if (!authority.isCurrent() || defectSource.value !== 'draft') return false
    const frozenScope = activeDefectScope.value.slice()
    const frozenFilters = { ...defectFilter }
    const scope: 'selected' | 'filtered' = defectSelection.value.length > 0 ? 'selected' : 'filtered'
    try {
      const artifact = buildDefectListJsonArtifact(frozenScope, frozenFilters, scope)
      if (!authority.isCurrent() || defectSource.value !== 'draft') return false
      if (!downloadRecoveryText(authority, artifact.filename, artifact.content, artifact.mimeType)) return false
      return authority.isCurrent()
    } catch (error) {
      if (authority.isCurrent()) publishFailure(error, authority, 'confirmed-no-write', 'defect')
      return false
    }
  }

  const importDefectJsonText = async (text: string, authority = captureMutation()) => {
    if (!authority.isCurrent() || defectSource.value !== 'draft') return false
    try {
      const drafts = parseDefectImportArtifact(text)
      if (!authority.isCurrent()) return false
      const current = listDefectDrafts(authority.storage)
      const byId = new Map(current.map(draft => [draft.defectId, draft]))
      const conflicts = drafts.filter(draft => byId.has(draft.defectId))
      defectImportPreview.value = { authority, drafts, conflicts }
      return true
    } catch (error) {
      if (authority.isCurrent()) publishFailure(error, authority, 'confirmed-no-write', 'defect')
      return false
    }
  }

  const cancelDefectImport = () => { defectImportPreview.value = null }
  const beginDefectImport = () => {
    const authority = captureMutation()
    const generation = importGeneration
    return { ...authority, isCurrent: () => authority.isCurrent() && generation === importGeneration }
  }

  const confirmDefectImport = async () => {
    const preview = defectImportPreview.value
    if (!preview || !preview.authority.isCurrent() || defectSource.value !== 'draft') return false
    if (preview.conflicts.length > 0) {
      setFailure('defect', { outcome: 'confirmed-no-write', code: 'conflict', message: '导入包中存在同标识草稿冲突，未覆盖现有数据。请修改导入包后重试。' })
      return false
    }
    try {
      const confirmed = await confirmDanger('导入缺陷草稿', `确认导入 ${preview.drafts.length} 条缺陷草稿吗？同标识数据不会覆盖。`, '确认导入')
      if (!confirmed || !preview.authority.isCurrent() || defectImportPreview.value !== preview) return false
      if (defectSource.value !== 'draft') return false
      insertDefectDrafts(preview.authority.storage, preview.drafts)
      if (!preview.authority.isCurrent()) return false
      defectDrafts.value = listDefectDrafts(preview.authority.storage)
      defectImportPreview.value = null
      restorePendingFailure()
      ElMessage.success(`已导入 ${preview.drafts.length} 条缺陷草稿`)
      return true
    } catch (error) {
      if (preview.authority.isCurrent()) publishFailure(error, preview.authority, 'confirmed-no-write', 'defect')
      return false
    }
  }

  const defectRecoveryDirty = computed(() => defectEditDirty.value || !!defectRecoveryCandidate.value || !!defectTemplateCandidate.value)
  const confirmDiscardDefectChanges = async (): Promise<boolean> => {
    const authority = captureMutation()
    if (!authority.isCurrent()) return false
    const candidate = defectRecoveryCandidate.value
    const templateCandidate = defectTemplateCandidate.value
    if (!defectRecoveryDirty.value) return true
    try {
      const confirmed = await confirmDanger('放弃未保存缺陷数据？', '刷新/离开将丢弃未保存表单及失败候选；请先下载需要保留的数据。', '放弃并离开')
      if (!confirmed || !authority.isCurrent() || defectRecoveryCandidate.value !== candidate || defectTemplateCandidate.value !== templateCandidate) return false
      defectRecoveryCandidate.value = null
      defectTemplateCandidate.value = null
      pendingFailures.defect = null
      pendingFailures.template = null
      defectFailure.value = null
      discardDefectEdit()
      return true
    } catch (error) { publishFailure(error, authority, 'confirmed-no-write'); return false }
  }
  const discardDefectRecoveryCandidate = async () => {
    const authority = captureMutation()
    const candidate = defectRecoveryCandidate.value
    if (!candidate || !authority.isCurrent()) return false
    try {
      const confirmed = await confirmDanger('放弃本次失败候选？', '未保存候选将丢失，已保存草稿不变。', '放弃候选')
      if (!confirmed || !authority.isCurrent() || !candidate.authority.isCurrent() || defectRecoveryCandidate.value !== candidate) return false
      defectRecoveryCandidate.value = null
      pendingFailures.defect = null
      restorePendingFailure()
      return true
    } catch (error) { publishFailure(error, authority, 'confirmed-no-write'); return false }
  }
  const resetDefectRecovery = () => {
    mutationGeneration += 1
    importGeneration += 1
    committedNavigation = null
    defectTemplateCandidate.value = null
    defectImportPreview.value = null
    defectRawStore.value = null
    defectReadBlocked.value = false
    defectRecoveryCandidate.value = null
    pendingFailures.defect = null
    pendingFailures.template = null
    failureOwner = 'defect'
    defectFailure.value = null
    defectEditError.value = ''
    detailDefectId.value = null
    editingDefect.value = undefined
    defectEditVisible.value = false
    defectEditBaseline.value = ''
    for (const key of Object.keys(defectEditForm)) (defectEditForm as Record<string, string>)[key] = ''
  }
  const removeDefect = async (draft: DefectDraft) => {
    const authority = captureMutation()
    if (!authority.isCurrent() || defectSource.value !== 'draft' || !defectDrafts.value.some(item => item.defectId === draft.defectId)) return false
    try {
      const baseline = listDefectDrafts(authority.storage)
      const confirmed = await confirmDanger('删除缺陷草稿', `确认删除缺陷 ${draft.summary} 吗？`)
      if (!authority.isCurrent() || !confirmed || defectSource.value !== 'draft') return false
      return commitCandidate({ revision: ++revision, operation: 'delete', authority, ids: [draft.defectId], drafts: [], baseline,
        finish: () => {
          if (detailDefectId.value === draft.defectId) detailDefectId.value = null
          defectSelection.value = defectSelection.value.filter(item => item.defectId !== draft.defectId)
          ElMessage.success('已删除缺陷草稿')
        }
      })
    } catch (error) { publishFailure(error, authority, 'confirmed-no-write'); return false }
  }

  const removeScopedDefects = async () => {
    const authority = captureMutation()
    if (!authority.isCurrent() || defectSource.value !== 'draft') return false
    const ids = activeDefectScope.value.map(d => d.defectId)
    if (!ids.length) { ElMessage.warning('当前没有可删除的缺陷'); return false }
    try {
      const baseline = listDefectDrafts(authority.storage)
      const confirmed = await confirmDanger('批量删除缺陷草稿', `确认删除${defectScopeLabel()} ${ids.length} 条缺陷草稿吗？`)
      if (!authority.isCurrent() || !confirmed || defectSource.value !== 'draft') return false
      return commitCandidate({ revision: ++revision, operation: 'delete', authority, ids, drafts: [], baseline,
        finish: () => { defectSelection.value = defectSelection.value.filter(d => !ids.includes(d.defectId)); ElMessage.success(`已删除 ${ids.length} 条缺陷草稿`) }
      })
    } catch (error) { publishFailure(error, authority, 'confirmed-no-write'); return false }
  }

  const isEditableDefectIssue = (sourceKind: 'draft' | 'jira') => sourceKind === 'draft'

  const handleDefectSelectionChange = (rows: ReturnType<typeof mergeDefectIssueRecords>) => {
    defectSelection.value = rows
      .filter((row) => row.sourceKind === 'draft')
      .map((row) => row.draft)
    // 用户开始勾选后,「转缺陷」行高亮使命结束
    recentlyTransferredDefectIds.value = []
  }

  const addJiraCsvTemplateField = () => {
    jiraCsvTemplateDraft.value = [...jiraCsvTemplateDraft.value, newCustomJiraCsvTemplateField(jiraCsvTemplateDraft.value.length + 1)]
  }

  const updateJiraCsvTemplateField = (key: string, patch: Partial<JiraCsvTemplateField>) => {
    jiraCsvTemplateDraft.value = jiraCsvTemplateDraft.value.map((field) =>
      field.key === key
        ? {
            ...field,
            ...patch,
            source: {
              ...field.source,
              ...(patch.source || {})
            }
          }
        : field
    )
  }

  const deleteJiraCsvTemplateField = async (field: JiraCsvTemplateField) => {
    const authority = captureMutation()
    if (!authority.isCurrent()) return
    try {
      const confirmed = await confirmDanger('删除 Jira 导出字段', `确认删除导出字段 ${field.header} 吗？`)
      if (!confirmed || !authority.isCurrent()) return
      jiraCsvTemplateDraft.value = jiraCsvTemplateDraft.value.filter((item) => item.key !== field.key)
      ElMessage.success('已删除导出字段')
    } catch (error) { publishFailure(error, authority, 'confirmed-no-write') }
  }

  const saveJiraCsvTemplateConfig = (frozenFields?: JiraCsvTemplateField[], frozenAuthority?: Authority) => {
    const requestedFields = cloneTemplate(frozenFields ?? jiraCsvTemplateDraft.value)
    const requestedAuthority = frozenAuthority ?? captureMutation()
    if (!requestedAuthority.isCurrent()) return false
    const previous = defectTemplateCandidate.value
    if (previous) {
      if (semantic(jiraCsvTemplateDraft.value) === semantic(previous.fields)) { void retryDefectMutation('template'); return !jiraTemplateVisible.value }
      const authority = captureMutation()
      void (async () => {
        try {
          const confirmed = await confirmDanger('放弃上次 CSV 配置候选？', '新配置将替换尚未确认保存的配置候选。', '放弃并替换')
          if (!confirmed || !authority.isCurrent() || !previous.authority.isCurrent() || defectTemplateCandidate.value !== previous) return
          defectTemplateCandidate.value = null
          saveJiraCsvTemplateConfig(requestedFields, requestedAuthority)
        } catch (error) { publishFailure(error, authority, 'confirmed-no-write') }
      })()
      return false
    }
    const fields = requestedFields
    if (!fields.length || fields.some(field => !field.header.trim() || !field.source.fieldKey.trim()) ||
        !fields.some(field => field.enabled !== false)) {
      ElMessage.warning('请保留至少一个启用字段，并填写表头与取值键')
      return false
    }
    const authority = requestedAuthority
    if (!authority.isCurrent()) return false
    try {
      const frozen = cloneTemplate(fields)
      const serialized = JSON.stringify(frozen)
      defectTemplateCandidate.value = { authority, fields: frozen, baseline: authority.storage.getItem(JIRA_CSV_TEMPLATE_STORAGE_KEY), serialized }
      saveJiraCsvTemplate(authority.storage, frozen)
      if (!authority.isCurrent()) return false
      if (authority.storage.getItem(JIRA_CSV_TEMPLATE_STORAGE_KEY) !== serialized) throw { outcome: 'uncertain-commit', code: 'verify-failed' }
      if (!authority.isCurrent()) return false
      jiraCsvTemplate.value = frozen
      defectTemplateCandidate.value = null
      jiraTemplateVisible.value = false
      pendingFailures.template = null
      restorePendingFailure()
      ElMessage.success('已保存 Jira 导出字段配置')
      return true
    } catch (error) { publishFailure(error, authority, undefined, 'template'); return false }
  }

  const resetJiraCsvTemplateConfig = async () => {
    const authority = captureMutation()
    if (!authority.isCurrent()) return
    try {
      const confirmed = await confirmDanger('重置 Jira 导出字段', '确认恢复默认 Jira CSV 导出字段吗？')
      if (!confirmed || !authority.isCurrent()) return
      jiraCsvTemplateDraft.value = buildDefaultJiraCsvTemplate()
      ElMessage.success('已恢复默认导出字段')
    } catch (error) { publishFailure(error, authority, 'confirmed-no-write') }
  }

  let switchingSource = false
  let importGeneration = 0
  const switchDefectSource = async (source: 'draft' | 'jira') => {
    if (source === defectSource.value) return
    importGeneration += 1
    defectImportPreview.value = null
    Object.assign(defectListContexts[defectSource.value].filters, defectFilter)
    switchingSource = true
    defectSource.value = source
    Object.assign(defectFilter, defectListContexts[source].filters)
    switchingSource = false
  }
  watch(() => ({ ...defectFilter }), () => {
    if (switchingSource) return
    Object.assign(defectListContexts[defectSource.value].filters, defectFilter)
    defectPage.value = 1
    defectListContexts[defectSource.value].scrollTop = 0
    defectListContexts[defectSource.value].scrollLeft = 0
  }, { flush: 'sync' })
  watch(defectPageCount, count => { defectPage.value = Math.min(defectPage.value, count) })

  const switchDefectProject = (project: string) => {
    defectFilter.project = project
  }

  watch(
    () => defectDrafts.value,
    (drafts) => {
      const selectedIds = new Set(defectSelection.value.map((draft) => draft.defectId))
      if (selectedIds.size === 0) return
      defectSelection.value = drafts.filter((draft) => selectedIds.has(draft.defectId))
    },
    { deep: true }
  )

  watch(
    () => [isDefectsActive(), defectFilter.project],
    () => {
      void refreshJiraIssues()
    }
  )

  return {
    defectTemplateCandidate, defectReadBlocked, defectRawStore, exportDefectRawStore,
    defectImportPreview, beginDefectImport, importDefectJsonText, confirmDefectImport, cancelDefectImport,
    defectRecoveryDirty, confirmDiscardDefectChanges, discardDefectRecoveryCandidate,
    resetDefectRecovery, defectFailure, defectRecoveryCandidate, retryDefectMutation, exportDefectRecoveryCandidate,
    defectSource, defectListContexts, defectPage, defectPageSize, defectPageCount, pagedDefectIssues, switchDefectSource,
    defectDrafts,
    defectSelection,
    recentlyTransferredDefectIds,
    jiraIssues,
    jiraProjectName,
    jiraIssueType,
    jiraCsvTemplate,
    jiraCsvTemplateDraft,
    jiraTemplateVisible,
    defectEditVisible,
    defectEditDirty,
    defectEditError,
    detailDefectId,
    detailDefect,
    openDefectDetail,
    closeDefectDetail,
    cancelDefectEdit,
    editingDefect,
    defectFilter,
    defectEditForm,
    defectMetrics,
    exportedDefectCount,
    defectIssueRecords,
    defectProjectGroups,
    defectIssuesForActiveProject,
    defectFilterOptions,
    filteredDefectDrafts,
    filteredDefectIssues,
    activeDefectScope,
    defectCandidateProjects,
    defectProjectOptions,
    enabledJiraCsvTemplate,
    defectDraftByReportCaseId,
    defectStatusText,
    defectStatusTagType,
    defectIssueStatusTagType,
    defectStatusLabel,
    createDefectsFromResults,
    createDefectsFromWebResults,
    updateDefectField,
    openDefectEdit,
    saveDefectEdit,
    defectScopeLabel,
    defectExportFileName,
    exportSelectedDefectsToJiraCsv,
    exportDefectListJson,
    removeDefect,
    removeScopedDefects,
    isEditableDefectIssue,
    handleDefectSelectionChange,
    addJiraCsvTemplateField,
    updateJiraCsvTemplateField,
    deleteJiraCsvTemplateField,
    saveJiraCsvTemplateConfig,
    resetJiraCsvTemplateConfig,
    refreshDefectDrafts,
    refreshJiraIssues,
    switchDefectProject
  }
}

export const useDefects = (deps: DefectsDeps = {}): ReturnType<typeof createDefectsState> => {
  if (!singleton || deps.storage) singleton = createDefectsState(deps)
  return singleton
}

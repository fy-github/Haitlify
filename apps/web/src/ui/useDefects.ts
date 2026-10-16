import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { missingDep } from './depGuard'
import {
  buildDefectDraftsFromAutomationResults,
  buildDefectDraftsFromWebAutomationResults,
  deleteDefectDrafts,
  deleteDefectDraft,
  exportDefectsToJiraCsv,
  listDefectDrafts,
  markDefectsAsExported,
  saveDefectDrafts,
  updateDefectDraft,
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
  type JiraIssueSource
} from '../domain/defectView'
import { loadJiraIssues } from '../domain/jiraIssueRepository'
import {
  buildEnabledJiraCsvTemplate,
  newCustomJiraCsvTemplateField,
  type JiraCsvTemplateField
} from '../domain/jiraCsvTemplate'
import {
  loadJiraCsvTemplate,
  resetJiraCsvTemplate,
  saveJiraCsvTemplate
} from '../domain/jiraCsvTemplateRepository'
import { downloadCsv } from '../domain/csvExport'
import type { AutomationRunResult } from '../domain/apiAutomation'
import type { WebAutomationRunReport } from '../domain/webAutomation'

export interface DefectsDeps {
  // Cross-cutting project name sources (imported/library/automation/report projects).
  candidateProjects?: () => string[]
  confirmDanger?: (title: string, message: string, confirmLabel?: string) => Promise<boolean>
  navigateToDefects?: () => void
  isDefectsActive?: () => boolean
}

// Module-level singleton so App.vue (context pane + cross-view status tags) and
// DefectsView.vue (main view) share the same defect state.
let singleton: ReturnType<typeof createDefectsState> | null = null

function createDefectsState(deps: DefectsDeps) {
  const confirmDanger = deps.confirmDanger ?? (async () => missingDep('useDefects', 'confirmDanger'))
  const navigateToDefects = deps.navigateToDefects ?? (() => missingDep('useDefects', 'navigateToDefects'))
  const isDefectsActive = deps.isDefectsActive ?? (() => true)

  const defectDrafts = ref<DefectDraft[]>(listDefectDrafts(localStorage))
  const defectSelection = ref<DefectDraft[]>([])
  const jiraIssues = ref<JiraIssueSource[]>([])
  const jiraProjectName = ref('IKOS')
  const jiraIssueType = ref('故障')
  const jiraCsvTemplate = ref<JiraCsvTemplateField[]>(loadJiraCsvTemplate(localStorage))
  const jiraTemplateVisible = ref(false)
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
  const defectFilterOptions = computed(() => buildDefectFilterOptionsFromIssues(defectIssueRecords.value))
  const filteredDefectDrafts = computed(() => filterDefectDrafts(defectDrafts.value, defectFilter))
  const filteredDefectIssues = computed(() =>
    mergeDefectIssueRecords(
      buildDefectIssueRecordsFromDrafts(filteredDefectDrafts.value),
      buildDefectIssueRecordsFromJiraIssues(jiraIssues.value)
    )
  )
  const activeDefectScope = computed(() =>
    defectSelection.value.length > 0 ? defectSelection.value : filteredDefectDrafts.value
  )
  const defectCandidateProjects = computed(() => deps.candidateProjects?.() ?? [])
  const defectProjectOptions = computed(() =>
    mergeDefectProjectOptions(defectFilterOptions.value.projectOptions, defectCandidateProjects.value)
  )
  const enabledJiraCsvTemplate = computed(() => buildEnabledJiraCsvTemplate(jiraCsvTemplate.value))
  const defectDraftByReportCaseId = computed(
    () => new Map(defectDrafts.value.map((draft) => [draft.sourceReportCaseId, draft]))
  )

  const refreshDefectDrafts = () => {
    defectDrafts.value = listDefectDrafts(localStorage)
  }

  const refreshJiraIssues = async () => {
    if (!isDefectsActive()) return
    if (defectFilter.project === 'ALL') {
      jiraIssues.value = []
      return
    }

    try {
      const result = await loadJiraIssues({
        projectName: defectFilter.project,
        authMode: 'service-account'
      })
      jiraIssues.value = result.items
    } catch {
      jiraIssues.value = []
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

  const createDefectsFromResults = (results: AutomationRunResult[], projectName: string) => {
    const drafts = buildDefectDraftsFromAutomationResults(results, {
      projectName: projectName || jiraProjectName.value || 'IKOS',
      issueType: jiraIssueType.value || '故障'
    })
    if (drafts.length === 0) {
      ElMessage.info('当前没有可转缺陷的失败用例')
      return
    }
    const result = saveDefectDrafts(localStorage, drafts)
    refreshDefectDrafts()
    recentlyTransferredDefectIds.value = drafts.map((draft) => draft.defectId)
    navigateToDefects()
    ElMessage.success(`已转入缺陷 ${result.inserted} 条，更新 ${result.updated} 条`)
  }

  const createDefectsFromWebResults = (results: WebAutomationRunReport['results'], projectName: string) => {
    const drafts = buildDefectDraftsFromWebAutomationResults(results, {
      projectName: projectName || jiraProjectName.value || 'IKOS',
      issueType: jiraIssueType.value || '故障'
    })
    if (drafts.length === 0) {
      ElMessage.info('当前没有可转缺陷的 Web 失败用例')
      return
    }
    const result = saveDefectDrafts(localStorage, drafts)
    refreshDefectDrafts()
    recentlyTransferredDefectIds.value = drafts.map((draft) => draft.defectId)
    navigateToDefects()
    ElMessage.success(`已转入缺陷 ${result.inserted} 条，更新 ${result.updated} 条`)
  }

  const updateDefectField = (draft: DefectDraft, patch: Partial<DefectDraft>) => {
    const next = {
      ...draft,
      ...patch,
      updatedAt: new Date().toISOString()
    }
    updateDefectDraft(localStorage, next)
    refreshDefectDrafts()
  }

  const openDefectEdit = (draft: DefectDraft) => {
    editingDefect.value = draft
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
    defectEditVisible.value = true
  }

  const saveDefectEdit = () => {
    if (!editingDefect.value) return
    updateDefectField(editingDefect.value, { ...defectEditForm })
    defectEditVisible.value = false
    ElMessage.success('已更新缺陷草稿')
  }

  const defectScopeLabel = (): string => defectSelection.value.length > 0 ? '选中' : '当前筛选'

  const defectExportFileName = (drafts: DefectDraft[]): string => {
    const projects = Array.from(new Set(drafts.map((draft) => draft.projectName).filter(Boolean)))
    if (projects.length === 1) return `${projects[0]}-jira-defects.csv`
    return `${jiraProjectName.value || 'multi-project'}-jira-defects.csv`
  }

  const exportSelectedDefectsToJiraCsv = async () => {
    const drafts = activeDefectScope.value
    if (drafts.length === 0) {
      ElMessage.warning('当前没有可导出的缺陷')
      return
    }
    const confirmed = await confirmDanger(
      '导出 Jira CSV',
      `确认导出${defectScopeLabel()} ${drafts.length} 条缺陷为 Jira CSV 吗？`,
      '导出'
    )
    if (!confirmed) return

    const csv = exportDefectsToJiraCsv(drafts, enabledJiraCsvTemplate.value)
    downloadCsv(defectExportFileName(drafts), csv)
    markDefectsAsExported(localStorage, drafts.map((draft) => draft.defectId), 'csv')
    refreshDefectDrafts()
    defectSelection.value = []
    ElMessage.success(`已导出 ${drafts.length} 条 Jira CSV`)
  }

  const removeDefect = async (draft: DefectDraft) => {
    const confirmed = await confirmDanger('删除缺陷草稿', `确认删除缺陷 ${draft.summary} 吗？`)
    if (!confirmed) return
    deleteDefectDraft(localStorage, draft.defectId)
    refreshDefectDrafts()
    defectSelection.value = defectSelection.value.filter((item) => item.defectId !== draft.defectId)
    ElMessage.success('已删除缺陷草稿')
  }

  const removeScopedDefects = async () => {
    const drafts = activeDefectScope.value
    if (drafts.length === 0) {
      ElMessage.warning('当前没有可删除的缺陷')
      return
    }
    const confirmed = await confirmDanger(
      '批量删除缺陷草稿',
      `确认删除${defectScopeLabel()} ${drafts.length} 条缺陷草稿吗？`
    )
    if (!confirmed) return
    const result = deleteDefectDrafts(localStorage, drafts.map((draft) => draft.defectId))
    refreshDefectDrafts()
    defectSelection.value = []
    ElMessage.success(`已删除 ${result.deleted} 条缺陷草稿`)
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
    jiraCsvTemplate.value = [...jiraCsvTemplate.value, newCustomJiraCsvTemplateField(jiraCsvTemplate.value.length + 1)]
  }

  const updateJiraCsvTemplateField = (key: string, patch: Partial<JiraCsvTemplateField>) => {
    jiraCsvTemplate.value = jiraCsvTemplate.value.map((field) =>
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
    const confirmed = await confirmDanger('删除 Jira 导出字段', `确认删除导出字段 ${field.header} 吗？`)
    if (!confirmed) return
    jiraCsvTemplate.value = jiraCsvTemplate.value.filter((item) => item.key !== field.key)
    ElMessage.success('已删除导出字段')
  }

  const saveJiraCsvTemplateConfig = () => {
    saveJiraCsvTemplate(localStorage, jiraCsvTemplate.value)
    jiraTemplateVisible.value = false
    ElMessage.success('已保存 Jira 导出字段配置')
  }

  const resetJiraCsvTemplateConfig = async () => {
    const confirmed = await confirmDanger('重置 Jira 导出字段', '确认恢复默认 Jira CSV 导出字段吗？')
    if (!confirmed) return
    jiraCsvTemplate.value = resetJiraCsvTemplate(localStorage)
    ElMessage.success('已恢复默认导出字段')
  }

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
    () => filteredDefectDrafts.value.map((draft) => draft.defectId).join('|'),
    () => {
      const visibleIds = new Set(filteredDefectDrafts.value.map((draft) => draft.defectId))
      defectSelection.value = defectSelection.value.filter((draft) => visibleIds.has(draft.defectId))
    }
  )

  watch(
    () => [isDefectsActive(), defectFilter.project],
    () => {
      void refreshJiraIssues()
    }
  )

  return {
    defectDrafts,
    defectSelection,
    recentlyTransferredDefectIds,
    jiraIssues,
    jiraProjectName,
    jiraIssueType,
    jiraCsvTemplate,
    jiraTemplateVisible,
    defectEditVisible,
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
  if (!singleton) singleton = createDefectsState(deps)
  return singleton
}

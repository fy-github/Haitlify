import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { missingDep } from './depGuard'
import {
  buildReportDetailSummary,
  buildReportProjectOptions,
  collectReportEnvironmentOptions,
  countReportResults,
  filterReportResults,
  filterReportsForProject,
  reportResultKind,
  type ReportResultFilter,
  selectActiveReportId
} from '../domain/reportView'
import {
  buildAutomationReportListJsonArtifact,
  buildAutomationReportExportArtifact,
  type AutomationReportExportFormat
} from '../domain/automationReportExport'
import {
  deleteAutomationReport,
  listAutomationReports,
  type SavedAutomationReport
} from '../domain/automationReportRepository'
import type { AutomationRunResult } from '../domain/apiAutomation'
import { copyDefectEvidence } from '../domain/defectEvidence'
import {
  automationStatusLabel,
  automationStatusTagType,
  reportFailedStepId,
  reportHtmlSnippet,
  reportScreenshotPath,
  reportWebStepLogs
} from './automationFormat'
import { useDefects } from './useDefects'

export interface ReportsDeps {
  storage?: Storage
  // Cross-cutting confirm dialog (same shape as App.vue confirmDangerAction).
  confirmDanger?: (title: string, message: string, confirmLabel?: string) => Promise<boolean>
  // Navigation to the reports view.
  navigateToReports?: () => void
  // Generic text-file download (owned by App.vue; shared across curl/backup/export).
  downloadTextFile?: (filename: string, content: string, type: string) => void
  // Opens the shared execution-log drawer (state stays in App.vue).
  showResultLog?: (result: AutomationRunResult) => void
}

// Module-level singleton so App.vue (context pane + automation report writes) and
// ReportsView.vue (main view) share the same report state.
let singleton: ReturnType<typeof createReportsState> | null = null

function createReportsState(deps: ReportsDeps) {
  const localStorage = deps.storage ?? window.localStorage
  const confirmDanger = deps.confirmDanger ?? (async () => missingDep('useReports', 'confirmDanger'))
  const navigateToReports = deps.navigateToReports ?? (() => missingDep('useReports', 'navigateToReports'))
  const downloadTextFile = deps.downloadTextFile ?? (() => missingDep('useReports', 'downloadTextFile'))
  const showResultLog = deps.showResultLog ?? (() => missingDep('useReports', 'showResultLog'))

  const automationReports = ref<SavedAutomationReport[]>(listAutomationReports(localStorage))
  const selectedReportProject = ref('')
  const selectedReportId = ref<string>()
  const reportExportFormat = ref<AutomationReportExportFormat>('json')
  const reportStatusFilter = ref<ReportResultFilter>('ALL')
  const reportEnvironmentFilter = ref('ALL')
  const reportKeyword = ref('')
  const reportWebStepFilter = ref<'ALL' | 'FAILED'>('ALL')
  const reportScreenshotPreviewVisible = ref(false)
  const reportScreenshotPreviewUrl = ref('')
  const expandedReportHtmlCaseIds = ref<string[]>([])
  // App 的「查看报告」入口在导航前发起一次性打开请求,视图挂载/响应后消费一次。
  const reportOpenRequest = ref(0)

  const reportProjects = computed(() => buildReportProjectOptions(automationReports.value))
  const selectedProjectReportTotal = computed(() =>
    reportProjects.value.find((project) => project.value === selectedReportProject.value)?.count || 0
  )
  const filteredAutomationReports = computed(() =>
    filterReportsForProject(automationReports.value, {
      project: selectedReportProject.value,
      environment: reportEnvironmentFilter.value,
      keyword: reportKeyword.value
    })
  )
  const reportEnvironments = computed(() =>
    selectedReportProject.value ? collectReportEnvironmentOptions(automationReports.value, selectedReportProject.value) : []
  )
  const selectedAutomationReport = computed(() =>
    filteredAutomationReports.value.find((report) => report.reportId === selectedReportId.value)
  )
  const selectedReportKind = computed(() =>
    selectedAutomationReport.value ? reportResultKind(selectedAutomationReport.value) : 'api'
  )
  const selectedReportSummaryItems = computed(() =>
    selectedAutomationReport.value ? buildReportDetailSummary(selectedAutomationReport.value) : []
  )
  const filteredReportResults = computed(() => {
    if (!selectedAutomationReport.value) return []
    return filterReportResults(selectedAutomationReport.value, reportStatusFilter.value)
  })

  const reportTotals = countReportResults

  const switchReportProject = (project: string) => {
    selectedReportProject.value = project
    reportEnvironmentFilter.value = 'ALL'
    reportKeyword.value = ''
  }

  const refreshAutomationReports = () => {
    automationReports.value = listAutomationReports(localStorage)
    if (!selectedReportProject.value || !reportProjects.value.some((project) => project.value === selectedReportProject.value)) {
      selectedReportProject.value = reportProjects.value[0]?.value || ''
    }
    selectedReportId.value = selectActiveReportId(filteredAutomationReports.value, selectedReportId.value)
  }

  const openReportsModule = () => {
    refreshAutomationReports()
    navigateToReports()
  }

  const openActiveWebRunReport = () => {
    refreshAutomationReports()
    if (selectedReportId.value) {
      reportOpenRequest.value += 1
      navigateToReports()
    }
  }

  const consumeReportOpenRequest = (): boolean => {
    if (!reportOpenRequest.value) return false
    reportOpenRequest.value = 0
    return true
  }

  const exportAutomationReport = (
    report: SavedAutomationReport,
    format: AutomationReportExportFormat
  ) => {
    const artifact = buildAutomationReportExportArtifact(report, format)
    if (artifact.kind === 'download') {
      downloadTextFile(artifact.filename, artifact.content, artifact.mimeType)
      return
    }

    // noopener/noreferrer make window.open return null even after a successful
    // open. Keep the same-origin blank handle, then sever its opener before
    // writing any report content. No remote navigation is involved.
    let printWindow: Window | null = null
    try {
      printWindow = window.open('', '_blank', 'width=960,height=720')
      if (!printWindow) {
        ElMessage.warning('浏览器拦截了打印窗口，请允许弹窗后重试')
        return
      }
      printWindow.opener = null
      if (printWindow.opener !== null) throw new Error('打印窗口隔离失败')
      printWindow.document.write(artifact.content)
      printWindow.document.close()
      printWindow.focus()
      // Native print waits for document loading; no stale callback or timer.
      printWindow.print()
    } catch {
      try { printWindow?.close() } catch { /* Closing is best effort after a print failure. */ }
      ElMessage.warning('报告打印失败，请重试或改用 HTML 导出')
    }
  }

  const exportAutomationRunReport = () => {
    if (!selectedAutomationReport.value) {
      ElMessage.warning('还没有可导出的执行报告')
      return
    }
    exportAutomationReport(selectedAutomationReport.value, reportExportFormat.value)
  }

  const exportFilteredAutomationReportsJson = () => {
    if (!filteredAutomationReports.value.length) {
      ElMessage.warning('当前没有可导出的报告')
      return
    }
    const artifact = buildAutomationReportListJsonArtifact(filteredAutomationReports.value, {
      project: selectedReportProject.value,
      environment: reportEnvironmentFilter.value,
      keyword: reportKeyword.value
    })
    downloadTextFile(artifact.filename, artifact.content, artifact.mimeType)
  }

  const removeAutomationReport = async (reportId: string) => {
    const confirmed = await confirmDanger('删除执行报告', '确认删除这份执行报告吗？')
    if (!confirmed) return
    deleteAutomationReport(localStorage, reportId)
    refreshAutomationReports()
    ElMessage.success('已删除执行报告')
  }

  const defects = useDefects()
  const transferReportResultToDefect = (result: AutomationRunResult) => {
    if (result.status !== 'failed' && result.status !== 'error') {
      ElMessage.info('只有失败或错误的执行结果可以转缺陷')
      return
    }
    if (result.method === 'WEB') {
      defects.createDefectsFromWebResults([Object.defineProperties({
        caseId: result.caseId,
        sourceCaseId: result.sourceCaseId,
        name: result.name,
        module: result.module,
        status: result.status
      }, Object.getOwnPropertyDescriptors(copyDefectEvidence(result, 'report-to-defect', false)))], selectedAutomationReport.value?.project || defects.jiraProjectName.value)
      return
    }
    defects.createDefectsFromResults([result], selectedAutomationReport.value?.project || defects.jiraProjectName.value)
  }

  const toggleReportHtmlSnippet = (caseId: string) => {
    expandedReportHtmlCaseIds.value = expandedReportHtmlCaseIds.value.includes(caseId)
      ? expandedReportHtmlCaseIds.value.filter((item) => item !== caseId)
      : [...expandedReportHtmlCaseIds.value, caseId]
  }

  const reportHtmlSnippetExpanded = (caseId: string) =>
    expandedReportHtmlCaseIds.value.includes(caseId)

  const openReportScreenshotPreview = (url: string) => {
    if (!url) return
    reportScreenshotPreviewUrl.value = url
    reportScreenshotPreviewVisible.value = true
  }

  const closeReportScreenshotPreview = () => {
    reportScreenshotPreviewVisible.value = false
    reportScreenshotPreviewUrl.value = ''
  }

  const openReportScreenshotInNewWindow = () => {
    if (!reportScreenshotPreviewUrl.value) return
    window.open(reportScreenshotPreviewUrl.value, '_blank', 'noopener,noreferrer')
  }

  const openReportResultLog = (caseId: string) => {
    const result = selectedAutomationReport.value?.results.find((item) => item.caseId === caseId)
    if (!result) {
      ElMessage.info('未找到该条执行日志')
      return
    }
    showResultLog(result)
  }

  const filteredReportWebStepLogs = (row: AutomationRunResult) =>
    reportWebStepFilter.value === 'FAILED'
      ? reportWebStepLogs(row).filter((step) => step.status === 'failed')
      : reportWebStepLogs(row)

  watch(
    () => [selectedReportProject.value, reportEnvironmentFilter.value, reportKeyword.value, automationReports.value.length],
    () => {
      if (!selectedReportProject.value && reportProjects.value.length) {
        selectedReportProject.value = reportProjects.value[0]?.value || ''
      }
      selectedReportId.value = selectActiveReportId(filteredAutomationReports.value, selectedReportId.value)
    },
    { immediate: true }
  )

  return {
    resetWorkspace: () => {
      reportEnvironmentFilter.value = 'ALL'
      closeReportScreenshotPreview()
      expandedReportHtmlCaseIds.value = []
      reportKeyword.value = ''
      refreshAutomationReports()
    },
    automationReports,
    selectedReportProject,
    selectedReportId,
    reportExportFormat,
    reportStatusFilter,
    reportEnvironmentFilter,
    reportKeyword,
    reportWebStepFilter,
    reportScreenshotPreviewVisible,
    reportScreenshotPreviewUrl,
    expandedReportHtmlCaseIds,
    reportOpenRequest,
    reportProjects,
    selectedProjectReportTotal,
    filteredAutomationReports,
    reportEnvironments,
    selectedAutomationReport,
    selectedReportKind,
    selectedReportSummaryItems,
    filteredReportResults,
    reportTotals,
    switchReportProject,
    refreshAutomationReports,
    openReportsModule,
    openActiveWebRunReport,
    consumeReportOpenRequest,
    exportAutomationReport,
    exportAutomationRunReport,
    exportFilteredAutomationReportsJson,
    removeAutomationReport,
    transferReportResultToDefect,
    toggleReportHtmlSnippet,
    reportHtmlSnippetExpanded,
    openReportScreenshotPreview,
    closeReportScreenshotPreview,
    openReportScreenshotInNewWindow,
    openReportResultLog,
    filteredReportWebStepLogs,
    automationStatusLabel,
    automationStatusTagType,
    reportFailedStepId,
    reportHtmlSnippet,
    reportScreenshotPath
  }
}

export const useReports = (deps: ReportsDeps = {}): ReturnType<typeof createReportsState> => {
  if (!singleton || deps.storage) singleton = createReportsState(deps)
  return singleton
}

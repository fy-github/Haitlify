import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { missingDep } from './depGuard'
import {
  buildReportDetailSummary,
  buildReportProjectOptions,
  collectReportEnvironmentOptions,
  filterReportsForProject,
  reportResultKind,
  selectActiveReportId
} from '../domain/reportView'
import {
  buildAutomationReportExportArtifact,
  type AutomationReportExportFormat
} from '../domain/automationReportExport'
import {
  deleteAutomationReport,
  listAutomationReports,
  type SavedAutomationReport
} from '../domain/automationReportRepository'
import type { AutomationRunResult, AutomationRunStatus } from '../domain/apiAutomation'
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
  const confirmDanger = deps.confirmDanger ?? (async () => missingDep('useReports', 'confirmDanger'))
  const navigateToReports = deps.navigateToReports ?? (() => missingDep('useReports', 'navigateToReports'))
  const downloadTextFile = deps.downloadTextFile ?? (() => missingDep('useReports', 'downloadTextFile'))
  const showResultLog = deps.showResultLog ?? (() => missingDep('useReports', 'showResultLog'))

  const automationReports = ref<SavedAutomationReport[]>(listAutomationReports(localStorage))
  const selectedReportProject = ref('')
  const selectedReportId = ref<string>()
  const reportExportFormat = ref<AutomationReportExportFormat>('json')
  const reportStatusFilter = ref<'ALL' | AutomationRunStatus>('ALL')
  const reportEnvironmentFilter = ref('ALL')
  const reportKeyword = ref('')
  const reportWebStepFilter = ref<'ALL' | 'FAILED'>('ALL')
  const reportScreenshotPreviewVisible = ref(false)
  const reportScreenshotPreviewUrl = ref('')
  const expandedReportHtmlCaseIds = ref<string[]>([])

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
    if (reportStatusFilter.value === 'ALL') return selectedAutomationReport.value.results
    return selectedAutomationReport.value.results.filter((result) => result.status === reportStatusFilter.value)
  })

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
    if (selectedReportId.value) navigateToReports()
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

    const printWindow = window.open('', '_blank', 'noopener,noreferrer,width=960,height=720')
    if (!printWindow) {
      ElMessage.warning('浏览器拦截了打印窗口，请允许弹窗后重试')
      return
    }
    printWindow.document.write(artifact.content)
    printWindow.document.close()
    printWindow.focus()
    setTimeout(() => printWindow.print(), 150)
  }

  const exportAutomationRunReport = () => {
    if (!selectedAutomationReport.value) {
      ElMessage.warning('还没有可导出的执行报告')
      return
    }
    exportAutomationReport(selectedAutomationReport.value, reportExportFormat.value)
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
    reportProjects,
    selectedProjectReportTotal,
    filteredAutomationReports,
    reportEnvironments,
    selectedAutomationReport,
    selectedReportKind,
    selectedReportSummaryItems,
    filteredReportResults,
    switchReportProject,
    refreshAutomationReports,
    openReportsModule,
    openActiveWebRunReport,
    exportAutomationReport,
    exportAutomationRunReport,
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
  if (!singleton) singleton = createReportsState(deps)
  return singleton
}

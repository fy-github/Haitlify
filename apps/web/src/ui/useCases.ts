import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { missingDep } from './depGuard'
import {
  deleteSavedCases,
  deleteSavedWebAutomationCase,
  listSavedCases,
  listSavedWebAutomationCases,
  saveCaseOrdering,
  saveGeneratedCases,
  saveGeneratedWebAutomationCases,
  saveWebAutomationCaseOrdering,
  type SavedInterfaceCase
} from '../domain/caseRepository'
import { applyInterfaceFieldTemplate } from '../domain/caseTemplateApplication'
import {
  applyBulkCaseFieldUpdate,
  applyJsonFieldAssignment,
  collectJsonFieldAssignments,
  type JsonAssignableField,
  type MatchMode
} from '../domain/caseBulkEdit'
import {
  buildCaseLibraryIndex,
  buildSupportedCaseKinds,
  collectCaseLibraryOptions,
  filterCaseLibraryRecords,
  paginateSavedCases
} from '../domain/caseLibraryView'
import { downloadCsv, exportCasesToCsv } from '../domain/csvExport'
import { isDesignOriginWebCase } from '../domain/designOrigin'
import { summarizeDesignOriginStats } from '../domain/designOriginStats'
import { exportDesignOriginSummaryCsv } from '../domain/designOriginReportExport'
import {
  confirmLocatorCandidates,
  countPendingDesignLocators,
  hasPendingDesignLocators,
  summarizeDesignLocatorProgress
} from '../domain/designLocatorStatus'
import {
  deleteSavedCasesWithBackend,
  syncCaseOrderingWithBackend,
  syncSavedCaseProjectsWithBackend
} from '../domain/platformBackendRepository'
import {
  generateWebAutomationCasesFromSavedCases,
  validateWebAutomationConversionCases
} from '../domain/webAutomation'
import { saveWebAutomationRunCases } from '../domain/webAutomationRepository'
import type { GeneratedWebAutomationCase, SavedWebAutomationCase } from '../domain/webAutomationCase'
import type { SavedWebAutomationRunCase } from '../domain/webAutomationRepository'
import type { GeneratedInterfaceCase, InterfaceCaseRow } from '../domain/caseGenerator'
import type { NavKey } from '../domain/appNavigation'
import { useTemplateConfig } from './useTemplateConfig'

type SelectedLibraryCase =
  | GeneratedInterfaceCase
  | GeneratedWebAutomationCase
  | SavedInterfaceCase
  | SavedWebAutomationCase

export interface CasesDeps {
  confirmDanger?: (title: string, message: string, confirmLabel?: string) => Promise<boolean>
  navigateToNav?: (next: NavKey) => void
  getWebAutomationCases?: () => SavedWebAutomationRunCase[]
  refreshAutomationCases?: () => void
  resetAutomationFilters?: () => void
  setAutomationKind?: (kind: 'api' | 'web') => void
  setAutomationGenerateVisible?: (visible: boolean) => void
  getEmptyEditRow?: () => InterfaceCaseRow
  getSelectedCase?: () => SelectedLibraryCase | undefined
  setSelectedCase?: (testCase: SelectedLibraryCase | undefined) => void
  openEdit?: (testCase: GeneratedInterfaceCase | SavedInterfaceCase) => void
  openWebEdit?: (testCase: GeneratedWebAutomationCase | SavedWebAutomationCase) => void
  openCurlPreview?: (testCase: SavedInterfaceCase) => void
  openAutomationGenerateDialog?: () => void
  backupProjectData?: () => void
  onProjectDataSelected?: (event: Event) => void
}

// Module-level singleton so App.vue (sidebar pane + generation/automation
// bridges) and CasesView.vue share the same case-library state.
let singleton: ReturnType<typeof createCasesState> | null = null

function moveArrayItem<T>(items: T[], from: number, to: number): T[] {
  const next = [...items]
  const [item] = next.splice(from, 1)
  next.splice(to, 0, item)
  return next
}

function createCasesState(deps: CasesDeps) {
  const confirmDanger = deps.confirmDanger ?? (async () => missingDep('useCases', 'confirmDanger'))
  const navigateToNav = deps.navigateToNav ?? (() => missingDep('useCases', 'navigateToNav'))
  const openEdit = deps.openEdit ?? (() => missingDep('useCases', 'openEdit'))
  const openWebEdit = deps.openWebEdit ?? (() => missingDep('useCases', 'openWebEdit'))
  const openCurlPreview = deps.openCurlPreview ?? (() => missingDep('useCases', 'openCurlPreview'))
  const openAutomationGenerateDialog = deps.openAutomationGenerateDialog ?? (() => missingDep('useCases', 'openAutomationGenerateDialog'))
  const backupProjectData = deps.backupProjectData ?? (() => missingDep('useCases', 'backupProjectData'))
  const onProjectDataSelected = deps.onProjectDataSelected ?? (() => missingDep('useCases', 'onProjectDataSelected'))
  const { interfaceFields, fieldCellLabel } = useTemplateConfig()

  const librarySelection = ref<SavedInterfaceCase[]>([])
  const libraryPage = ref(1)
  const libraryPageSize = ref(50)
  const libraryCaseKind = ref<'interface' | 'web_automation'>('interface')
  const savedCases = ref<SavedInterfaceCase[]>(listSavedCases(localStorage))
  const savedWebCases = ref<SavedWebAutomationCase[]>(listSavedWebAutomationCases(localStorage))
  const bulkEditVisible = ref(false)
  const fieldAssignmentVisible = ref(false)
  const bulkEditForm = reactive({
    matchField: 'module' as keyof InterfaceCaseRow,
    matchMode: 'contains' as MatchMode,
    matchValue: '',
    targetField: 'priority' as keyof InterfaceCaseRow,
    targetValue: ''
  })
  const fieldAssignmentForm = reactive({
    fieldKey: 'queryParams' as JsonAssignableField,
    path: '',
    value: ''
  })
  const libraryFilter = reactive({
    project: 'ALL',
    module: 'ALL',
    caseType: 'ALL',
    source: '' as '' | 'design' | 'default',
    keyword: ''
  })

  const selectedCase = computed<SelectedLibraryCase | undefined>({
    get: () => deps.getSelectedCase?.(),
    set: (value) => deps.setSelectedCase?.(value)
  })

  const caseKindOptions = computed(() => buildSupportedCaseKinds())
  const caseLibraryIndex = computed(() => buildCaseLibraryIndex(savedCases.value, 'interface'))
  const webCaseLibraryIndex = computed(() => buildCaseLibraryIndex(savedWebCases.value, 'web_automation'))
  const libraryProjects = computed(() => caseLibraryIndex.value.projectOptions)
  const activeLibraryProjectOptions = computed(() =>
    libraryCaseKind.value === 'interface' ? caseLibraryIndex.value.projectOptions : webCaseLibraryIndex.value.projectOptions
  )
  const libraryProjectRecords = computed(() =>
    libraryFilter.project === 'ALL'
      ? caseLibraryIndex.value.records
      : caseLibraryIndex.value.records.filter((record) => record.project === libraryFilter.project)
  )
  const webLibraryProjectRecords = computed(() =>
    libraryFilter.project === 'ALL'
      ? webCaseLibraryIndex.value.records
      : webCaseLibraryIndex.value.records.filter((record) => record.project === libraryFilter.project)
  )
  const libraryModules = computed(() => collectCaseLibraryOptions(libraryProjectRecords.value, 'module'))
  const libraryCaseTypes = computed(() => collectCaseLibraryOptions(libraryProjectRecords.value, 'caseType'))
  const webLibraryModules = computed(() => collectCaseLibraryOptions(webLibraryProjectRecords.value, 'module'))
  const webLibraryCaseTypes = computed(() => collectCaseLibraryOptions(webLibraryProjectRecords.value, 'caseType'))
  const filteredLibraryRecords = computed(() => filterCaseLibraryRecords(caseLibraryIndex.value.records, libraryFilter))
  const filteredLibraryCases = computed<SavedInterfaceCase[]>(() =>
    filteredLibraryRecords.value.map((record) => record.caseItem as SavedInterfaceCase)
  )
  const filteredWebLibraryRecords = computed(() => filterCaseLibraryRecords(webCaseLibraryIndex.value.records, libraryFilter))
  const filteredWebLibraryCases = computed(() => filteredWebLibraryRecords.value.map((record) => record.caseItem))
  const activeLibraryModules = computed(() => (libraryCaseKind.value === 'interface' ? libraryModules.value : webLibraryModules.value))
  const activeLibraryCaseTypes = computed(() =>
    libraryCaseKind.value === 'interface' ? libraryCaseTypes.value : webLibraryCaseTypes.value
  )
  const activeLibraryTotal = computed(() =>
    libraryCaseKind.value === 'interface' ? filteredLibraryCases.value.length : filteredWebLibraryCases.value.length
  )
  const interfaceLibrarySelection = computed(() => librarySelection.value as SavedInterfaceCase[])
  const bulkTargetCases = computed<SavedInterfaceCase[]>(() =>
    interfaceLibrarySelection.value.length > 0 ? interfaceLibrarySelection.value : filteredLibraryCases.value
  )
  const jsonFieldAssignments = computed(() => collectJsonFieldAssignments(bulkTargetCases.value))
  const pagedLibraryCases = computed(() => paginateSavedCases(filteredLibraryCases.value, libraryPage.value, libraryPageSize.value))
  const pagedWebLibraryCases = computed(() => paginateSavedCases(filteredWebLibraryCases.value, libraryPage.value, libraryPageSize.value))
  const activeLibraryPageCount = computed(() =>
    Math.max(1, Math.ceil(activeLibraryTotal.value / libraryPageSize.value))
  )

  const visibleInterfaceFields = computed(() => interfaceFields.value.filter((field) => field.visible))
  const bulkEditableFields = computed(() =>
    visibleInterfaceFields.value.filter((field) => field.key in (deps.getEmptyEditRow?.() ?? {}))
  )

  const designOriginStats = computed(() =>
    summarizeDesignOriginStats(savedWebCases.value, deps.getWebAutomationCases?.() ?? [])
  )
  const filteredDesignOriginWebLibraryCases = computed(() =>
    filteredWebLibraryCases.value.filter((testCase) =>
      isDesignOriginWebCase(testCase.row.caseType, testCase.row.tags, testCase.row.remark)
    )
  )
  const hasFilteredDesignOriginWebLibraryCases = computed(() => filteredDesignOriginWebLibraryCases.value.length > 0)
  const designLocatorProgress = computed(() =>
    summarizeDesignLocatorProgress(filteredDesignOriginWebLibraryCases.value as SavedWebAutomationCase[])
  )

  const switchLibraryProject = (project: string) => {
    libraryFilter.project = project
    libraryFilter.module = 'ALL'
    libraryPage.value = 1
  }

  const switchLibraryCaseKind = (value: 'interface' | 'web_automation') => {
    libraryCaseKind.value = value
    libraryFilter.project = 'ALL'
    libraryFilter.module = 'ALL'
    libraryFilter.caseType = 'ALL'
    libraryFilter.source = ''
    libraryFilter.keyword = ''
    libraryPage.value = 1
    librarySelection.value = []
  }

  const refreshSavedCases = () => {
    savedCases.value = listSavedCases(localStorage)
    savedWebCases.value = listSavedWebAutomationCases(localStorage)
  }

  const syncDeleteLibraryCases = async (cases: SavedInterfaceCase[]) => {
    const result = await deleteSavedCasesWithBackend(localStorage, cases)
    const deletedIds = new Set(cases.map((item) => item.libraryId))
    refreshSavedCases()
    librarySelection.value = librarySelection.value.filter((item) => !deletedIds.has(item.libraryId))
    return result
  }

  const confirmFilteredDesignLocators = () => {
    if (libraryCaseKind.value !== 'web_automation') return
    if (filteredDesignOriginWebLibraryCases.value.length === 0) {
      ElMessage.info('当前没有可确认的设计稿 Web 用例')
      return
    }
    const updated = confirmLocatorCandidates(filteredDesignOriginWebLibraryCases.value as SavedWebAutomationCase[])
    saveGeneratedWebAutomationCases(localStorage, updated)
    refreshSavedCases()
    ElMessage.success(`已批量确认 ${updated.length} 条设计稿用例的 locator`)
  }

  const convertWebLibraryCasesToAutomation = (
    cases: SavedWebAutomationCase[],
    closeDialog: boolean
  ) => {
    if (cases.length === 0) {
      ElMessage.warning('没有可转换的 Web 自动化用例')
      return
    }
    if (hasPendingDesignLocators(cases)) {
      ElMessage.warning(`当前仍有 ${countPendingDesignLocators(cases)} 条设计稿用例存在未确认 locator，请先批量确认`)
      return
    }

    const automationCases = generateWebAutomationCasesFromSavedCases(cases)
    const validationErrors = validateWebAutomationConversionCases(automationCases)
    if (validationErrors.length > 0) {
      const first = validationErrors[0]
      const suffix = validationErrors.length > 1 ? `，另有 ${validationErrors.length - 1} 个错误` : ''
      ElMessage.error(`${first.caseId}：${first.message}${suffix}`)
      return
    }

    const result = saveWebAutomationRunCases(localStorage, automationCases)
    deps.refreshAutomationCases?.()
    deps.resetAutomationFilters?.()
    if (closeDialog) deps.setAutomationGenerateVisible?.(false)
    deps.setAutomationKind?.('web')
    navigateToNav('automation')
    ElMessage.success(`已保存 ${result.inserted} 条 Web 自动化执行用例，更新 ${result.updated} 条`)
  }

  const convertWebLibraryCaseToAutomation = (testCase: SavedWebAutomationCase) => {
    convertWebLibraryCasesToAutomation([testCase], false)
  }

  const exportSavedCsv = () => {
    if (libraryCaseKind.value !== 'interface') {
      ElMessage.info('Web 自动化导出将在下一步接入')
      return
    }
    if (filteredLibraryCases.value.length === 0) {
      ElMessage.warning('没有可导出的已保存用例')
      return
    }
    const csv = exportCasesToCsv(
      interfaceFields.value,
      filteredLibraryCases.value.map((testCase) => testCase.row as unknown as Record<string, unknown>)
    )
    downloadCsv(`${libraryFilter.project !== 'ALL' ? libraryFilter.project : '全部项目'}-用例库-接口测试用例.csv`, csv)
  }

  const exportDesignOriginSummary = () => {
    const csv = exportDesignOriginSummaryCsv(designOriginStats.value, designLocatorProgress.value)
    downloadCsv('设计稿来源汇总报表.csv', csv)
    ElMessage.success('已导出设计稿来源汇总报表')
  }

  const projectNameForLibraryCase = (testCase: SavedInterfaceCase): string =>
    testCase.row.project || '默认项目'

  const openBulkEditDialog = () => {
    if (libraryCaseKind.value !== 'interface') {
      ElMessage.info('Web 自动化批量字段维护将在后续版本接入')
      return
    }
    bulkEditForm.matchField = 'module'
    bulkEditForm.matchMode = 'contains'
    bulkEditForm.matchValue = ''
    bulkEditForm.targetField = 'priority'
    bulkEditForm.targetValue = ''
    bulkEditVisible.value = true
  }

  const persistUpdatedLibraryCases = async (cases: SavedInterfaceCase[]) => {
    deleteSavedCases(localStorage, cases.map((item) => item.libraryId))
    saveGeneratedCases(localStorage, cases.map((testCase) => applyInterfaceFieldTemplate(testCase, interfaceFields.value)))
    refreshSavedCases()
    librarySelection.value = []
    const projectNames = Array.from(new Set(cases.map((testCase) => testCase.row.project || '默认项目')))
    return await syncSavedCaseProjectsWithBackend(localStorage, projectNames)
  }

  const confirmBulkEdit = async () => {
    if (bulkTargetCases.value.length === 0) {
      ElMessage.warning('当前没有可批量修改的用例')
      return
    }
    const bulkResult = applyBulkCaseFieldUpdate(bulkTargetCases.value, {
      ...bulkEditForm
    })
    if (bulkResult.updated === 0) {
      ElMessage.info('没有匹配到需要修改的用例')
      return
    }
    const syncResult = await persistUpdatedLibraryCases(bulkResult.cases)
    bulkEditVisible.value = false
    ElMessage.success(
      syncResult.source === 'backend'
        ? `已批量修改 ${bulkResult.updated} 条用例，并同步到后端`
        : `已批量修改 ${bulkResult.updated} 条用例`
    )
  }

  const openFieldAssignmentDialog = () => {
    if (libraryCaseKind.value !== 'interface') {
      ElMessage.info('Web 自动化字段赋值将在后续版本接入')
      return
    }
    const first = jsonFieldAssignments.value[0]
    fieldAssignmentForm.fieldKey = first?.fieldKey || 'queryParams'
    fieldAssignmentForm.path = first?.path || ''
    fieldAssignmentForm.value = first?.value || ''
    fieldAssignmentVisible.value = true
  }

  const selectFieldAssignment = (value: string) => {
    const [fieldKey, ...pathParts] = value.split(':')
    const option = jsonFieldAssignments.value.find((item) =>
      item.fieldKey === fieldKey && item.path === pathParts.join(':')
    )
    if (!option) return
    fieldAssignmentForm.fieldKey = option.fieldKey
    fieldAssignmentForm.path = option.path
    fieldAssignmentForm.value = option.value
  }

  const confirmFieldAssignment = async () => {
    if (!fieldAssignmentForm.path) {
      ElMessage.warning('请选择要赋值的字段')
      return
    }
    const fieldResult = applyJsonFieldAssignment(bulkTargetCases.value, {
      ...fieldAssignmentForm
    })
    if (fieldResult.updated === 0) {
      ElMessage.info('没有匹配到需要赋值的字段')
      return
    }
    const syncResult = await persistUpdatedLibraryCases(fieldResult.cases)
    fieldAssignmentVisible.value = false
    ElMessage.success(
      syncResult.source === 'backend'
        ? `已更新 ${fieldResult.updated} 条用例字段，并同步到后端`
        : `已更新 ${fieldResult.updated} 条用例字段`
    )
  }

  const deleteLibraryCase = async (testCase: SavedInterfaceCase) => {
    const confirmed = await confirmDanger('删除用例', `确认删除 ${testCase.row.caseId} 吗？`)
    if (!confirmed) return
    const result = await syncDeleteLibraryCases([testCase])
    ElMessage.success(result.source === 'backend' ? '已删除已保存用例，并同步到后端' : '已删除已保存用例')
  }

  const deleteWebLibraryCase = async (testCase: SavedWebAutomationCase) => {
    const confirmed = await confirmDanger('删除 Web 用例', `确认删除 ${testCase.row.caseId} 吗？`)
    if (!confirmed) return
    deleteSavedWebAutomationCase(localStorage, testCase.libraryId)
    refreshSavedCases()
    ElMessage.success('已删除 Web 自动化用例')
  }

  const moveLibraryCase = async (testCase: SavedInterfaceCase, offset: -1 | 1) => {
    const visibleIndex = filteredLibraryCases.value.findIndex((item) => item.libraryId === testCase.libraryId)
    const targetVisibleIndex = visibleIndex + offset
    if (visibleIndex < 0 || targetVisibleIndex < 0 || targetVisibleIndex >= filteredLibraryCases.value.length) return

    const visibleIds = filteredLibraryCases.value.map((item) => item.libraryId)
    const currentOrder = [...savedCases.value]
    const sourceIndex = currentOrder.findIndex((item) => item.libraryId === visibleIds[visibleIndex])
    const targetIndex = currentOrder.findIndex((item) => item.libraryId === visibleIds[targetVisibleIndex])
    if (sourceIndex < 0 || targetIndex < 0) return
    const sourceProject = projectNameForLibraryCase(currentOrder[sourceIndex])
    const targetProject = projectNameForLibraryCase(currentOrder[targetIndex])
    if (sourceProject !== targetProject) {
      ElMessage.warning('请先筛选到单个项目后再调整排序')
      return
    }

    const reordered = moveArrayItem(currentOrder, sourceIndex, targetIndex)
    saveCaseOrdering(localStorage, reordered)
    refreshSavedCases()
    const result = await syncCaseOrderingWithBackend(localStorage, sourceProject)
    ElMessage.success(result.source === 'backend' ? '已更新排序，并同步到后端' : '已更新排序')
  }

  const moveWebLibraryCase = (testCase: SavedWebAutomationCase, offset: -1 | 1) => {
    const visibleIndex = filteredWebLibraryCases.value.findIndex((item) => item.libraryId === testCase.libraryId)
    const targetVisibleIndex = visibleIndex + offset
    if (visibleIndex < 0 || targetVisibleIndex < 0 || targetVisibleIndex >= filteredWebLibraryCases.value.length) return

    const visibleIds = filteredWebLibraryCases.value.map((item) => item.libraryId)
    const currentOrder = [...savedWebCases.value]
    const sourceIndex = currentOrder.findIndex((item) => item.libraryId === visibleIds[visibleIndex])
    const targetIndex = currentOrder.findIndex((item) => item.libraryId === visibleIds[targetVisibleIndex])
    if (sourceIndex < 0 || targetIndex < 0) return

    const reordered = moveArrayItem(currentOrder, sourceIndex, targetIndex)
    saveWebAutomationCaseOrdering(localStorage, reordered)
    refreshSavedCases()
    ElMessage.success('已更新 Web 自动化用例排序')
  }

  const deleteSelectedLibraryCases = async () => {
    if (libraryCaseKind.value !== 'interface') {
      ElMessage.info('Web 自动化批量删除将在下一步接入')
      return
    }
    if (librarySelection.value.length === 0) {
      ElMessage.warning('请先勾选要删除的用例')
      return
    }
    const confirmed = await confirmDanger(
      '删除勾选用例',
      `确认删除已勾选的 ${librarySelection.value.length} 条用例吗？`
    )
    if (!confirmed) return
    const result = await syncDeleteLibraryCases([...librarySelection.value])
    ElMessage.success(
      result.source === 'backend'
        ? `已删除 ${result.deleted} 条用例，并同步到后端`
        : `已删除 ${result.deleted} 条用例`
    )
  }

  const deleteLibraryCasesByScope = async () => {
    if (libraryCaseKind.value !== 'interface') {
      ElMessage.info('Web 自动化按筛选删除将在下一步接入')
      return
    }
    if (filteredLibraryCases.value.length === 0) {
      ElMessage.warning('当前筛选范围没有可删除的用例')
      return
    }
    const confirmed = await confirmDanger(
      '删除当前筛选',
      `确认删除当前筛选范围的 ${filteredLibraryCases.value.length} 条用例吗？`
    )
    if (!confirmed) return
    const result = await syncDeleteLibraryCases([...filteredLibraryCases.value])
    ElMessage.success(
      result.source === 'backend'
        ? `已删除 ${result.deleted} 条用例，并同步到后端`
        : `已删除 ${result.deleted} 条用例`
    )
  }

  const paginationTotalText = (total: number): string => `共 ${total} 条`

  const fieldColumnWidth = (key: string) => {
    const widths: Record<string, number> = {
      caseId: 108,
      project: 120,
      module: 110,
      interfaceName: 180,
      method: 92,
      path: 260,
      precondition: 260,
      headers: 300,
      pathParams: 300,
      queryParams: 300,
      body: 360,
      steps: 360,
      expectedResult: 360,
      expectedResponseBody: 360,
      actualResponseBody: 360,
      assertions: 360,
      caseType: 120,
      priority: 90,
      executionResult: 110,
      tags: 180,
      sourceFile: 160
    }
    return widths[key]
  }

  const fieldCellText = (row: InterfaceCaseRow, key: string): string => {
    const value = row[key as keyof InterfaceCaseRow]
    return value === undefined || value === null ? '' : String(value)
  }

  watch(
    () => [libraryFilter.project, libraryFilter.module, libraryFilter.caseType, libraryFilter.keyword],
    () => {
      libraryPage.value = 1
    }
  )

  watch(
    () => [filteredLibraryCases.value.length, libraryPageSize.value],
    () => {
      if (libraryPage.value > activeLibraryPageCount.value) {
        libraryPage.value = activeLibraryPageCount.value
      }
    }
  )

  return {
    librarySelection,
    libraryPage,
    libraryPageSize,
    libraryCaseKind,
    savedCases,
    savedWebCases,
    bulkEditVisible,
    fieldAssignmentVisible,
    bulkEditForm,
    fieldAssignmentForm,
    libraryFilter,
    selectedCase,
    caseKindOptions,
    caseLibraryIndex,
    webCaseLibraryIndex,
    libraryProjects,
    activeLibraryProjectOptions,
    libraryProjectRecords,
    webLibraryProjectRecords,
    libraryModules,
    libraryCaseTypes,
    webLibraryModules,
    webLibraryCaseTypes,
    filteredLibraryRecords,
    filteredLibraryCases,
    filteredWebLibraryRecords,
    filteredWebLibraryCases,
    filteredDesignOriginWebLibraryCases,
    hasFilteredDesignOriginWebLibraryCases,
    activeLibraryModules,
    activeLibraryCaseTypes,
    activeLibraryTotal,
    interfaceLibrarySelection,
    bulkTargetCases,
    jsonFieldAssignments,
    pagedLibraryCases,
    pagedWebLibraryCases,
    activeLibraryPageCount,
    visibleInterfaceFields,
    bulkEditableFields,
    designOriginStats,
    designLocatorProgress,
    fieldCellLabel,
    switchLibraryProject,
    switchLibraryCaseKind,
    refreshSavedCases,
    syncDeleteLibraryCases,
    confirmFilteredDesignLocators,
    convertWebLibraryCasesToAutomation,
    convertWebLibraryCaseToAutomation,
    exportSavedCsv,
    exportDesignOriginSummary,
    openBulkEditDialog,
    persistUpdatedLibraryCases,
    confirmBulkEdit,
    openFieldAssignmentDialog,
    selectFieldAssignment,
    confirmFieldAssignment,
    deleteLibraryCase,
    deleteWebLibraryCase,
    moveLibraryCase,
    moveWebLibraryCase,
    deleteSelectedLibraryCases,
    deleteLibraryCasesByScope,
    paginationTotalText,
    fieldColumnWidth,
    fieldCellText,
    openEdit,
    openWebEdit,
    openCurlPreview,
    openAutomationGenerateDialog,
    backupProjectData,
    onProjectDataSelected
  }
}

export const useCases = (deps: CasesDeps = {}): ReturnType<typeof createCasesState> => {
  if (!singleton) singleton = createCasesState(deps)
  return singleton
}

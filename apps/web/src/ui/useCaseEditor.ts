import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { buildWebExecutionRuntime, type SavedExecutionEnvironment } from '../domain/executionEnvironmentRepository'
import { applyInterfaceFieldTemplate } from '../domain/caseTemplateApplication'
import { syncMetadataFromRow } from '../domain/caseBulkEdit'
import { type GeneratedInterfaceCase, type InterfaceCaseRow } from '../domain/caseGenerator'
import { deleteSavedCase, deleteSavedWebAutomationCase, saveGeneratedCases, saveGeneratedWebAutomationCases, type SavedInterfaceCase } from '../domain/caseRepository'
import { syncSavedCaseProjectsWithBackend } from '../domain/platformBackendRepository'
import type { GeneratedWebAutomationCase } from '../domain/webAutomationCase'
import type { SavedWebAutomationCase } from '../domain/webAutomationCase'
import type { WebAutomationCaseRow } from '../domain/webAutomationCase'
import { serializeWebAutomationSteps, syncWebAutomationMetadataFromRow } from '../domain/webAutomationEditor'
import type { Ref } from 'vue'
import type { ComputedRef } from 'vue'
import type { ExecutionEnvironmentLibrary } from '../domain/executionEnvironmentRepository'
import type { FieldType } from '../domain/caseTemplates'

interface Dependencies {
  webExecutionEnvironmentLibrary: Ref<ExecutionEnvironmentLibrary, ExecutionEnvironmentLibrary>
  activeWebExecutionEnvironment: ComputedRef<SavedExecutionEnvironment | undefined>
  localStorage: Storage
  refreshSavedCases: () => void
  interfaceFields: ComputedRef<{ key: string; label: string; type: FieldType; required: boolean; visible: boolean; exportable: boolean; aiGenerated: boolean; defaultValue?: string | undefined; }[]>
}

export function createCaseEditor(deps: Dependencies) {

const selectedCase = ref<GeneratedInterfaceCase | GeneratedWebAutomationCase | SavedInterfaceCase | SavedWebAutomationCase>()

const selectedLibraryId = ref<string>()

const editVisible = ref(false)

const editRow = reactive<InterfaceCaseRow>(emptyEditRow())

const webEditRow = reactive<WebAutomationCaseRow>(emptyWebEditRow())

const openEdit = (testCase: GeneratedInterfaceCase | SavedInterfaceCase) => {
  selectedCase.value = testCase
  selectedLibraryId.value = 'libraryId' in testCase ? testCase.libraryId : undefined
  Object.assign(editRow, emptyEditRow(), testCase.row)
  editVisible.value = true
}

const openWebEdit = (testCase: GeneratedWebAutomationCase | SavedWebAutomationCase) => {
  selectedCase.value = testCase
  selectedLibraryId.value = 'libraryId' in testCase ? testCase.libraryId : undefined
  Object.assign(webEditRow, emptyWebEditRow(), testCase.row, {
    environmentId: testCase.row.environmentId || deps.webExecutionEnvironmentLibrary.value.activeEnvironmentId,
    steps: serializeWebAutomationSteps(testCase.metadata.steps)
  })
  editVisible.value = true
}

const applySelectedWebEnvironmentToEditRow = () => {
  const environment = deps.webExecutionEnvironmentLibrary.value.environments.find((item) => item.id === webEditRow.environmentId)
    || deps.activeWebExecutionEnvironment.value
  if (!environment) {
    ElMessage.warning('请先选择 Web 运行环境')
    return
  }
  const runtime = buildWebExecutionRuntime(environment)
  webEditRow.browser = runtime.browser
  webEditRow.viewport = runtime.viewport
  webEditRow.waitStrategy = runtime.waitUntil
  webEditRow.screenshotStrategy = runtime.screenshotStrategy
  webEditRow.retryCount = runtime.retryCount
  ElMessage.success('已应用 Web 运行环境默认参数')
}

const saveEdit = async () => {
  if (!selectedCase.value) return

  if (isWebEditableCase(selectedCase.value)) {
    const updated = syncWebAutomationMetadataFromRow({
      ...selectedCase.value,
      row: { ...webEditRow }
    })
    selectedCase.value.row = updated.row
    selectedCase.value.metadata = updated.metadata
    if (selectedLibraryId.value) {
      deleteSavedWebAutomationCase(deps.localStorage, selectedLibraryId.value)
      saveGeneratedWebAutomationCases(deps.localStorage, [selectedCase.value])
      deps.refreshSavedCases()
      editVisible.value = false
      ElMessage.success('已更新 Web 自动化用例')
      return
    }
    editVisible.value = false
    ElMessage.success('已更新当前 Web 用例')
    return
  }

  const interfaceCase = selectedCase.value
  const updated = applyInterfaceFieldTemplate(syncMetadataFromRow({
    ...interfaceCase,
    row: { ...editRow }
  }), deps.interfaceFields.value)
  selectedCase.value.row = updated.row
  selectedCase.value.metadata = updated.metadata
  if (selectedLibraryId.value) {
    deleteSavedCase(deps.localStorage, selectedLibraryId.value)
    saveGeneratedCases(deps.localStorage, [selectedCase.value], {
      savedAt: new Date().toISOString()
    })
    deps.refreshSavedCases()
    const savedProject = selectedCase.value.row.project || '默认项目'
    const result = await syncSavedCaseProjectsWithBackend(deps.localStorage, [savedProject])
    editVisible.value = false
    ElMessage.success(result.source === 'backend' ? '已更新当前用例，并同步到后端' : '已更新当前用例')
    return
  }
  editVisible.value = false
  ElMessage.success('已更新当前用例')
}

const isWebEditableCase = (
  testCase: GeneratedInterfaceCase | GeneratedWebAutomationCase | SavedInterfaceCase | SavedWebAutomationCase
): testCase is GeneratedWebAutomationCase | SavedWebAutomationCase => 'pageName' in testCase.row

function emptyEditRow(): InterfaceCaseRow {
  return {
    caseId: '',
    project: '',
    module: '',
    interfaceName: '',
    method: '',
    path: '',
    title: '',
    caseType: '',
    precondition: '',
    headers: '',
    pathParams: '',
    queryParams: '',
    body: '',
    steps: '',
    expectedResult: '',
    expectedResponseBody: '',
    actualResponseBody: '',
    assertions: '',
    priority: '',
    executionResult: '',
    executor: '',
    executedAt: '',
    defectId: '',
    tags: '',
    sourceFile: '',
    remark: ''
  }
}

function emptyWebEditRow(): WebAutomationCaseRow {
  return {
    caseId: '',
    project: '',
    module: '',
    pageName: '',
    title: '',
    naturalSteps: '',
    naturalExpectedResult: '',
    environmentId: '',
    caseType: '',
    precondition: '',
    startUrl: '',
    browser: 'chromium',
    viewport: '',
    testData: '',
    steps: '',
    locators: '',
    assertions: '',
    waitStrategy: '',
    screenshotStrategy: '',
    retryCount: 0,
    priority: '',
    executionResult: '',
    executor: '',
    executedAt: '',
    defectId: '',
    tags: '',
    sourceFile: '',
    remark: ''
  }
}

const reset = () => {
  selectedCase.value = undefined
  selectedLibraryId.value = undefined
  editVisible.value = false
  for (const row of [editRow, webEditRow]) {
    for (const key of Object.keys(row)) delete (row as Record<string, unknown>)[key]
  }
  Object.assign(editRow, emptyEditRow())
  Object.assign(webEditRow, emptyWebEditRow())
}
return {
  reset,
  selectedCase,
  selectedLibraryId,
  editVisible,
  editRow,
  webEditRow,
  openEdit,
  openWebEdit,
  applySelectedWebEnvironmentToEditRow,
  saveEdit,
  isWebEditableCase,
  emptyEditRow,
  emptyWebEditRow,
}
}

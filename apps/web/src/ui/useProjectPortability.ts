import { ElMessage } from 'element-plus'
import { WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY, loadExecutionEnvironmentLibrary } from '../domain/executionEnvironmentRepository'
import { type GenerationGranularity } from '../domain/caseGenerator'
import { listSavedCases } from '../domain/caseRepository'
import { exportProjectData, importProjectData } from '../domain/projectDataRepository'
import { TEMPLATE_CONFIG_STORAGE_KEY } from '../domain/templateConfigRepository'
import type { Ref } from 'vue'
import type { ExecutionEnvironmentLibrary } from '../domain/executionEnvironmentRepository'
import type { TemplateConfig } from '../domain/templateConfigRepository'

interface Dependencies {
  localStorage: Storage
  downloadTextFile: (filename: string, content: string, type: string) => void
  templateConfigModule: { importConfig: (config: TemplateConfig) => Promise<boolean> }
  savedCases: { value: { libraryId: string; savedAt: string; id: string; row: { caseId: string; project: string; module: string; interfaceName: string; method: string; path: string; title: string; caseType: string; precondition: string; headers: string; pathParams: string; queryParams: string; body: string; steps: string; expectedResult: string; expectedResponseBody?: string | undefined; actualResponseBody?: string | undefined; assertions: string; priority: string; executionResult: string; executor: string; executedAt: string; defectId: string; tags: string; sourceFile: string; remark: string; }; metadata: { sourceSpecId: string; sourceFile: string; sourceOperationId: string; tag: string; method: string; path: string; baseUrlRef: string; environmentId: string; authProfileId: string; contentType: string; headers: Record<string, string>; pathParams: Record<string, string>; queryParams: Record<string, string>; body: unknown; expectedStatus: number; assertions: string[]; expectedBodyRules: string[]; expectedResponseBody?: unknown; variableExtractions: string[]; dependsOnCaseIds: string[]; runOrder: number; generationMeta: { granularity: GenerationGranularity; strategy: string; }; reviewMeta: { status: "\u5F85\u590D\u6838"; }; exportMeta: { apifoxReady: boolean; }; }; }[] }
  executionEnvironmentLibrary: Ref<ExecutionEnvironmentLibrary, ExecutionEnvironmentLibrary>
  webExecutionEnvironmentLibrary: Ref<ExecutionEnvironmentLibrary, ExecutionEnvironmentLibrary>
  syncSelectedCaseTypes: () => void
}

export function createProjectPortability(deps: Dependencies) {

const backupProjectData = () => {
  const packageData = exportProjectData(deps.localStorage)
  deps.downloadTextFile(
    `AI测试平台数据备份-${packageData.exportedAt.slice(0, 10)}.json`,
    JSON.stringify(packageData, null, 2),
    'application/json;charset=utf-8'
  )
  ElMessage.success('已导出本地数据备份')
}

const onProjectDataSelected = async (event: Event) => {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return

  try {
    const json = JSON.parse(await file.text())
    // Validate the unchanged package API in memory; only the template writer may touch its three keys.
    const validation = new Map<string,string>()
    const result = importProjectData({ setItem: (key: string, value: string) => { validation.set(key,value) } } as Storage, json)
    if (!await deps.templateConfigModule.importConfig(json.payload.templateConfig)) return
    for (const [key,value] of validation) if (key !== TEMPLATE_CONFIG_STORAGE_KEY) deps.localStorage.setItem(key,value)
    deps.savedCases.value = listSavedCases(deps.localStorage)
    deps.executionEnvironmentLibrary.value = loadExecutionEnvironmentLibrary(deps.localStorage)
    deps.webExecutionEnvironmentLibrary.value = loadExecutionEnvironmentLibrary(deps.localStorage, WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY)
    deps.syncSelectedCaseTypes()
    ElMessage.success(`已恢复 ${result.caseCount} 条用例、${result.fieldCount} 个字段、${result.caseTypeCount} 个用例类型`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '恢复失败')
  } finally {
    input.value = ''
  }
}

return {
  backupProjectData,
  onProjectDataSelected,
}
}

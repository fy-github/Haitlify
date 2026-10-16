import { EXECUTION_ENV_STORAGE_KEY, type ExecutionEnvironment } from './apiExecution'
import { CASE_LIBRARY_STORAGE_KEY, type SavedInterfaceCase } from './caseRepository'
import {
  EXECUTION_ENV_LIBRARY_STORAGE_KEY,
  WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY,
  defaultExecutionEnvironmentLibrary,
  type ExecutionEnvironmentLibrary
} from './executionEnvironmentRepository'
import { TEMPLATE_CONFIG_STORAGE_KEY, defaultTemplateConfig, type TemplateConfig } from './templateConfigRepository'

export const PROJECT_DATA_KIND = 'ai-test-platform.project-data'
export const PROJECT_DATA_VERSION = 1

export interface ProjectDataPackage {
  kind: typeof PROJECT_DATA_KIND
  version: typeof PROJECT_DATA_VERSION
  exportedAt: string
  payload: {
    caseLibrary: SavedInterfaceCase[]
    templateConfig: TemplateConfig
    executionEnvironment: ExecutionEnvironment
    executionEnvironmentLibrary: ExecutionEnvironmentLibrary
    webExecutionEnvironmentLibrary: ExecutionEnvironmentLibrary
  }
}

export interface ExportProjectDataOptions {
  exportedAt?: string
}

export interface ImportProjectDataResult {
  caseCount: number
  fieldCount: number
  caseTypeCount: number
}

export const exportProjectData = (
  storage: Storage,
  options: ExportProjectDataOptions = {}
): ProjectDataPackage => ({
  kind: PROJECT_DATA_KIND,
  version: PROJECT_DATA_VERSION,
  exportedAt: options.exportedAt || new Date().toISOString(),
  payload: {
    caseLibrary: readJsonArray<SavedInterfaceCase>(storage, CASE_LIBRARY_STORAGE_KEY),
    templateConfig: readJsonObject<TemplateConfig>(storage, TEMPLATE_CONFIG_STORAGE_KEY) || defaultTemplateConfig(),
    executionEnvironment: readJsonObject<ExecutionEnvironment>(storage, EXECUTION_ENV_STORAGE_KEY) || {
      baseUrl: '',
      authToken: '',
      extraHeaders: {},
      requestHeadersText: ''
    },
    executionEnvironmentLibrary:
      readJsonObject<ExecutionEnvironmentLibrary>(storage, EXECUTION_ENV_LIBRARY_STORAGE_KEY) || defaultExecutionEnvironmentLibrary(),
    webExecutionEnvironmentLibrary:
      readJsonObject<ExecutionEnvironmentLibrary>(storage, WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY) || defaultExecutionEnvironmentLibrary()
  }
})

export const importProjectData = (storage: Storage, value: unknown): ImportProjectDataResult => {
  if (!isProjectDataPackage(value)) {
    throw new Error('无法识别的数据备份文件')
  }

  storage.setItem(CASE_LIBRARY_STORAGE_KEY, JSON.stringify(value.payload.caseLibrary))
  storage.setItem(TEMPLATE_CONFIG_STORAGE_KEY, JSON.stringify(value.payload.templateConfig))
  storage.setItem(EXECUTION_ENV_STORAGE_KEY, JSON.stringify(value.payload.executionEnvironment))
  storage.setItem(
    EXECUTION_ENV_LIBRARY_STORAGE_KEY,
    JSON.stringify(value.payload.executionEnvironmentLibrary || defaultExecutionEnvironmentLibrary())
  )
  storage.setItem(
    WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY,
    JSON.stringify(value.payload.webExecutionEnvironmentLibrary || defaultExecutionEnvironmentLibrary())
  )

  return {
    caseCount: value.payload.caseLibrary.length,
    fieldCount: value.payload.templateConfig.interfaceFields.length,
    caseTypeCount: value.payload.templateConfig.caseTypes.length
  }
}

const readJsonArray = <T>(storage: Storage, key: string): T[] => {
  const raw = storage.getItem(key)
  if (!raw) return []

  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

const readJsonObject = <T>(storage: Storage, key: string): T | undefined => {
  const raw = storage.getItem(key)
  if (!raw) return undefined

  try {
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? (parsed as T) : undefined
  } catch {
    return undefined
  }
}

const isProjectDataPackage = (value: unknown): value is ProjectDataPackage => {
  if (!value || typeof value !== 'object') return false
  const record = value as Record<string, unknown>
  const payload = record.payload as Record<string, unknown> | undefined

  return (
    record.kind === PROJECT_DATA_KIND &&
    record.version === PROJECT_DATA_VERSION &&
    !!payload &&
    Array.isArray(payload.caseLibrary) &&
    !!payload.templateConfig &&
    typeof payload.templateConfig === 'object' &&
    !!payload.executionEnvironment &&
    typeof payload.executionEnvironment === 'object' &&
    (
      payload.executionEnvironmentLibrary === undefined ||
      (
        !!payload.executionEnvironmentLibrary &&
        typeof payload.executionEnvironmentLibrary === 'object'
      )
    ) &&
    (
      payload.webExecutionEnvironmentLibrary === undefined ||
      (
        !!payload.webExecutionEnvironmentLibrary &&
        typeof payload.webExecutionEnvironmentLibrary === 'object'
      )
    )
  )
}

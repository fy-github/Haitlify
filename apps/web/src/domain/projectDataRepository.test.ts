import { describe, expect, it } from 'vitest'
import { EXECUTION_ENV_STORAGE_KEY, type ExecutionEnvironment } from './apiExecution'
import type { GeneratedInterfaceCase } from './caseGenerator'
import { CASE_LIBRARY_STORAGE_KEY, saveGeneratedCases } from './caseRepository'
import {
  EXECUTION_ENV_LIBRARY_STORAGE_KEY,
  WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY,
  defaultExecutionEnvironmentLibrary
} from './executionEnvironmentRepository'
import { TEMPLATE_CONFIG_STORAGE_KEY, loadTemplateConfig, saveTemplateConfig } from './templateConfigRepository'
import {
  PROJECT_DATA_KIND,
  exportProjectData,
  importProjectData
} from './projectDataRepository'

class MemoryStorage implements Storage {
  private values = new Map<string, string>()

  get length() {
    return this.values.size
  }

  clear(): void {
    this.values.clear()
  }

  getItem(key: string): string | null {
    return this.values.get(key) ?? null
  }

  key(index: number): string | null {
    return Array.from(this.values.keys())[index] ?? null
  }

  removeItem(key: string): void {
    this.values.delete(key)
  }

  setItem(key: string, value: string): void {
    this.values.set(key, value)
  }
}

const makeCase = (): GeneratedInterfaceCase => ({
  id: 'API-0001',
  row: {
    caseId: 'API-0001',
    project: 'Haitlify',
    module: '项目成员',
    interfaceName: '新增项目成员',
    method: 'POST',
    path: '/api/projects/{projectId}/members',
    title: '新增项目成员-正常请求成功',
    caseType: '正向',
    precondition: '',
    headers: '{}',
    pathParams: '{}',
    queryParams: '{}',
    body: '{}',
    steps: '1. 请求接口',
    expectedResult: '返回成功',
    assertions: '响应状态码为 201',
    priority: 'P1',
    executionResult: '未执行',
    executor: '',
    executedAt: '',
    defectId: '',
    tags: '项目成员,正向,AI生成',
    sourceFile: 'Haitlify.openapi.json',
    remark: ''
  },
  metadata: {
    sourceSpecId: 'Haitlify.openapi.json:createProjectMember',
    sourceFile: 'Haitlify.openapi.json',
    sourceOperationId: 'createProjectMember',
    tag: '项目成员',
    method: 'POST',
    path: '/api/projects/{projectId}/members',
    baseUrlRef: '',
    environmentId: '',
    authProfileId: '',
    contentType: 'application/json',
    headers: {},
    pathParams: {},
    queryParams: {},
    body: {},
    expectedStatus: 201,
    assertions: ['响应状态码为 201'],
    expectedBodyRules: [],
    variableExtractions: [],
    dependsOnCaseIds: [],
    runOrder: 1,
    generationMeta: {
      granularity: 'risk',
      strategy: 'deterministic-risk-rules'
    },
    reviewMeta: {
      status: '待复核'
    },
    exportMeta: {
      apifoxReady: false
    }
  }
})

describe('projectDataRepository', () => {
  it('exports versioned local project data from storage', () => {
    const storage = new MemoryStorage()
    const config = loadTemplateConfig(storage)
    config.interfaceFields[0] = { ...config.interfaceFields[0], label: '编号' }
    saveTemplateConfig(storage, config)
    saveGeneratedCases(storage, [makeCase()], { savedAt: '2026-06-10T09:00:00.000Z' })
    const environment: ExecutionEnvironment = {
      baseUrl: 'https://api.example.com',
      authToken: 'token',
      extraHeaders: { 'X-Tenant': 'demo' }
    }
    storage.setItem(EXECUTION_ENV_STORAGE_KEY, JSON.stringify(environment))
    const environmentLibrary = defaultExecutionEnvironmentLibrary()
    environmentLibrary.environments[0].baseUrl = 'https://api.library.example.com'
    storage.setItem(EXECUTION_ENV_LIBRARY_STORAGE_KEY, JSON.stringify(environmentLibrary))
    const webEnvironmentLibrary = defaultExecutionEnvironmentLibrary()
    webEnvironmentLibrary.environments[0].baseUrl = 'https://web.library.example.com'
    storage.setItem(WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY, JSON.stringify(webEnvironmentLibrary))

    const exported = exportProjectData(storage, { exportedAt: '2026-06-10T10:00:00.000Z' })

    expect(exported).toMatchObject({
      kind: PROJECT_DATA_KIND,
      version: 1,
      exportedAt: '2026-06-10T10:00:00.000Z',
      payload: {
        executionEnvironment: environment
      }
    })
    expect(exported.payload.templateConfig.interfaceFields[0].label).toBe('编号')
    expect(exported.payload.caseLibrary).toHaveLength(1)
    expect(exported.payload.executionEnvironmentLibrary.environments[0].baseUrl).toBe('https://api.library.example.com')
    expect(exported.payload.webExecutionEnvironmentLibrary.environments[0].baseUrl).toBe('https://web.library.example.com')
  })

  it('imports valid project data and replaces local storage sections', () => {
    const source = new MemoryStorage()
    const target = new MemoryStorage()
    const config = loadTemplateConfig(source)
    config.caseTypes[0] = { ...config.caseTypes[0], name: '成功场景' }
    saveTemplateConfig(source, config)
    saveGeneratedCases(source, [makeCase()], { savedAt: '2026-06-10T09:00:00.000Z' })
    source.setItem(EXECUTION_ENV_STORAGE_KEY, JSON.stringify({ baseUrl: 'https://api.example.com' }))
    const environmentLibrary = defaultExecutionEnvironmentLibrary()
    environmentLibrary.environments[0].baseUrl = 'https://api.library.example.com'
    source.setItem(EXECUTION_ENV_LIBRARY_STORAGE_KEY, JSON.stringify(environmentLibrary))
    const webEnvironmentLibrary = defaultExecutionEnvironmentLibrary()
    webEnvironmentLibrary.environments[0].baseUrl = 'https://web.library.example.com'
    source.setItem(WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY, JSON.stringify(webEnvironmentLibrary))

    const result = importProjectData(target, exportProjectData(source))

    expect(result).toEqual({
      caseCount: 1,
      fieldCount: config.interfaceFields.length,
      caseTypeCount: config.caseTypes.length
    })
    expect(JSON.parse(target.getItem(CASE_LIBRARY_STORAGE_KEY) || '[]')).toHaveLength(1)
    expect(JSON.parse(target.getItem(TEMPLATE_CONFIG_STORAGE_KEY) || '{}').caseTypes[0].name).toBe('成功场景')
    expect(JSON.parse(target.getItem(EXECUTION_ENV_STORAGE_KEY) || '{}').baseUrl).toBe('https://api.example.com')
    expect(JSON.parse(target.getItem(EXECUTION_ENV_LIBRARY_STORAGE_KEY) || '{}').environments[0].baseUrl).toBe('https://api.library.example.com')
    expect(JSON.parse(target.getItem(WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY) || '{}').environments[0].baseUrl).toBe('https://web.library.example.com')
  })

  it('rejects invalid project data packages', () => {
    const storage = new MemoryStorage()

    expect(() => importProjectData(storage, { kind: 'wrong', version: 1 })).toThrow('无法识别的数据备份文件')
    expect(storage.length).toBe(0)
  })
})

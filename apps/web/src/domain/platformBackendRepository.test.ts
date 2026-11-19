import { describe, expect, it, vi } from 'vitest'
import type { GeneratedInterfaceCase } from './caseGenerator'
import {
  listSavedCases,
  saveCaseOrdering,
  saveGeneratedCases,
  type SavedInterfaceCase
} from './caseRepository'
import {
  defaultTemplateConfig,
  loadTemplateConfig,
  saveTemplateConfig
} from './templateConfigRepository'
import {
  deleteSavedCasesWithBackend,
  ensureBackendProject,
  loadSavedCasesWithBackend,
  loadTemplateConfigWithBackend,
  projectCodeFromName,
  saveGeneratedCasesWithBackend,
  saveTemplateConfigWithBackend,
  syncSavedCaseProjectsWithBackend,
  syncCaseOrderingWithBackend
} from './platformBackendRepository'

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

const jsonResponse = (body: unknown, init: ResponseInit = {}) =>
  Promise.resolve(new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init
  }))

const makeCase = (): GeneratedInterfaceCase => ({
  id: 'API-0001',
  row: {
    caseId: 'API-0001',
    project: 'Haitlify API',
    module: '项目管理',
    interfaceName: '获取项目树',
    method: 'GET',
    path: '/v4/pm/project-tree',
    title: '获取项目树-正常请求成功',
    caseType: '正向',
    precondition: '',
    headers: '{}',
    pathParams: '{}',
    queryParams: '{}',
    body: '{}',
    steps: '1. 请求接口',
    expectedResult: '返回成功',
    assertions: '响应状态码为 200',
    priority: 'P1',
    executionResult: '未执行',
    executor: '',
    executedAt: '',
    defectId: '',
    tags: '项目管理,正向,AI生成',
    sourceFile: 'Haitlify.openapi.json',
    remark: ''
  },
  metadata: {
    sourceSpecId: 'Haitlify.openapi.json:getProjectTree',
    sourceFile: 'Haitlify.openapi.json',
    sourceOperationId: 'getProjectTree',
    tag: '项目管理',
    method: 'GET',
    path: '/v4/pm/project-tree',
    baseUrlRef: '',
    environmentId: '',
    authProfileId: '',
    contentType: 'application/json',
    headers: {},
    pathParams: {},
    queryParams: {},
    body: {},
    expectedStatus: 200,
    assertions: ['响应状态码为 200'],
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

const makeGeneratedCase = (patch: {
  id: string
  project?: string
  sourceFile?: string
  sourceOperationId: string
  method?: string
  path: string
  title: string
  caseType?: string
  runOrder: number
}): GeneratedInterfaceCase => {
  const base = makeCase()
  return {
    ...base,
    id: patch.id,
    row: {
      ...base.row,
      caseId: patch.id,
      project: patch.project || base.row.project,
      method: patch.method || base.row.method,
      path: patch.path,
      title: patch.title,
      caseType: patch.caseType || base.row.caseType,
      sourceFile: patch.sourceFile || base.row.sourceFile
    },
    metadata: {
      ...base.metadata,
      sourceFile: patch.sourceFile || base.metadata.sourceFile,
      sourceOperationId: patch.sourceOperationId,
      method: patch.method || base.metadata.method,
      path: patch.path,
      runOrder: patch.runOrder
    }
  }
}

const savedById = (cases: SavedInterfaceCase[], id: string): SavedInterfaceCase => {
  const testCase = cases.find((item) => item.id === id)
  if (!testCase) throw new Error(`Missing saved case ${id}`)
  return testCase
}

describe('platformBackendRepository', () => {
  it('uses same-origin api paths in browser contexts when no base url is configured', async () => {
    const originalWindow = globalThis.window
    Object.defineProperty(globalThis, 'window', {
      value: { location: { origin: 'http://localhost:5174' } },
      configurable: true
    })
    const fetchImpl = vi.fn(() => jsonResponse({
      items: [{ id: 7, code: 'HAITLIFY_API', name: 'Haitlify API' }],
      total: 1
    }))

    try {
      await ensureBackendProject('Haitlify API', { fetchImpl })
      expect(fetchImpl).toHaveBeenCalledWith('/api/projects', undefined)
    } finally {
      if (originalWindow) {
        Object.defineProperty(globalThis, 'window', { value: originalWindow, configurable: true })
      } else {
        Reflect.deleteProperty(globalThis, 'window')
      }
    }
  })

  it('creates a portable project code from Chinese and API project names', () => {
    expect(projectCodeFromName('Haitlify API')).toBe('HAITLIFY_API')
    expect(projectCodeFromName('项目管理')).toBe('PROJECT')
  })

  it('uses an existing backend project before creating a new one', async () => {
    const fetchImpl = vi.fn(() => jsonResponse({
      items: [{ id: 7, code: 'HAITLIFY_API', name: 'Haitlify API' }],
      total: 1
    }))

    const project = await ensureBackendProject('Haitlify API', { fetchImpl })

    expect(project.id).toBe(7)
    expect(fetchImpl).toHaveBeenCalledTimes(1)
    expect(fetchImpl).toHaveBeenCalledWith('http://127.0.0.1:18080/api/projects', undefined)
  })

  it('deduplicates concurrent project creation requests for the same project name', async () => {
    const fetchImpl = vi.fn((input: string, init?: RequestInit) => {
      if (input.endsWith('/api/projects') && !init?.method) {
        return jsonResponse({ items: [], total: 0 })
      }
      return new Promise<Response>((resolve) => {
        setTimeout(() => {
          resolve(new Response(JSON.stringify({
            id: 7,
            code: 'HAITLIFY_API',
            name: 'Haitlify API'
          }), {
            status: 201,
            headers: { 'Content-Type': 'application/json' }
          }))
        }, 10)
      })
    })

    const first = ensureBackendProject('Haitlify API', { fetchImpl })
    const second = ensureBackendProject('Haitlify API', { fetchImpl })

    const [left, right] = await Promise.all([first, second])

    expect(left).toEqual(right)
    expect(fetchImpl).toHaveBeenCalledTimes(2)
    expect(fetchImpl.mock.calls[0]?.[0]).toBe('http://127.0.0.1:18080/api/projects')
    expect(fetchImpl.mock.calls[1]?.[0]).toBe('http://127.0.0.1:18080/api/projects')
    expect(fetchImpl.mock.calls[1]?.[1]).toMatchObject({ method: 'POST' })
  })

  it('preserves a failed project-create status when strict loading is enabled', async () => {
    const storage = new MemoryStorage()
    const fetchImpl = vi.fn((input: string, init?: RequestInit) => {
      if (!init?.method) return jsonResponse({ items: [], total: 0 })
      return jsonResponse({ message: 'forbidden' }, { status: 403 })
    })
    await expect(loadSavedCasesWithBackend(storage, 'Haitlify API', { fetchImpl, throwOnError: true }))
      .rejects.toMatchObject({ name: 'PlatformBackendRequestError', status: 403 })
  })

  it('loads backend template config and mirrors it into local storage', async () => {
    const storage = new MemoryStorage()
    const fetchImpl = vi.fn((input: string) => {
      if (input.endsWith('/api/projects')) {
        return jsonResponse({ items: [{ id: 7, code: 'HAITLIFY_API', name: 'Haitlify API' }], total: 1 })
      }
      return jsonResponse({
        kind: 'interface',
        schemaVersion: 2,
        interfaceFields: [{
          key: 'caseId',
          label: '用例编号',
          type: 'text',
          required: true,
          visible: true,
          exportable: true,
          aiGenerated: false
        }],
        fieldsByKind: {
          interface: [{
            key: 'caseId',
            label: '用例编号',
            type: 'text',
            required: true,
            visible: true,
            exportable: true,
            aiGenerated: false
          }],
          functional: [],
          web_automation: [{
            key: 'pageName',
            label: '页面',
            type: 'text',
            required: true,
            visible: true,
            exportable: true,
            aiGenerated: true
          }]
        },
        caseTypes: [{ id: 'positive', name: '正常场景', appliesTo: 'all', enabled: true }]
      })
    })

    const result = await loadTemplateConfigWithBackend(storage, 'Haitlify API', { fetchImpl })

    expect(result.source).toBe('backend')
    expect(result.config.interfaceFields[0].label).toBe('用例编号')
    expect(result.config.fieldsByKind.web_automation[0].key).toBe('pageName')
    expect(loadTemplateConfig(storage).caseTypes[0].name).toBe('正常场景')
  })

  it('falls back to local template config when backend is unavailable', async () => {
    const storage = new MemoryStorage()
    const fetchImpl = vi.fn(() => Promise.reject(new Error('offline')))

    const result = await loadTemplateConfigWithBackend(storage, 'Haitlify API', { fetchImpl })

    expect(result.source).toBe('local')
    expect(result.config.interfaceFields[0].label).toBe('用例ID')
  })

  it('keeps newer local template config when backend template is older', async () => {
    const storage = new MemoryStorage()
    const localConfig = defaultTemplateConfig()
    localConfig.interfaceFields[0] = { ...localConfig.interfaceFields[0], label: '本地用例编号' }
    saveTemplateConfig(storage, localConfig, { updatedAt: '2026-06-15T12:00:00.000Z' })
    const fetchImpl = vi.fn((input: string) => {
      if (input.endsWith('/api/projects')) {
        return jsonResponse({ items: [{ id: 7, code: 'HAITLIFY_API', name: 'Haitlify API' }], total: 1 })
      }
      return jsonResponse({
        kind: 'interface',
        schemaVersion: 2,
        updatedAt: '2026-06-15T10:00:00.000Z',
        interfaceFields: [{
          key: 'caseId',
          label: '后端用例编号',
          type: 'text',
          required: true,
          visible: true,
          exportable: true,
          aiGenerated: false
        }],
        caseTypes: [{ id: 'positive', name: '正常场景', appliesTo: 'all', enabled: true }]
      })
    })

    const result = await loadTemplateConfigWithBackend(storage, 'Haitlify API', { fetchImpl })

    expect(result.source).toBe('conflict')
    expect(result.config.interfaceFields[0].label).toBe('本地用例编号')
    expect(loadTemplateConfig(storage).interfaceFields[0].label).toBe('本地用例编号')
  })

  it('does not report a conflict when newer local metadata has the same template content', async () => {
    const storage = new MemoryStorage()
    const localConfig = defaultTemplateConfig()
    saveTemplateConfig(storage, localConfig, { updatedAt: '2026-06-15T12:00:00.000Z' })
    const fetchImpl = vi.fn((input: string) => {
      if (input.endsWith('/api/projects')) {
        return jsonResponse({ items: [{ id: 7, code: 'HAITLIFY_API', name: 'Haitlify API' }], total: 1 })
      }
      return jsonResponse({
        kind: 'interface',
        schemaVersion: localConfig.schemaVersion,
        updatedAt: '2026-06-15T10:00:00.000Z',
        interfaceFields: localConfig.interfaceFields,
        fieldsByKind: localConfig.fieldsByKind,
        caseTypes: localConfig.caseTypes,
        caseTemplates: localConfig.caseTemplates
      })
    })

    const result = await loadTemplateConfigWithBackend(storage, 'Haitlify API', { fetchImpl })

    expect(result.source).toBe('backend')
    expect(result.config).toEqual(localConfig)
  })

  it('can reload an older backend template when explicitly requested', async () => {
    const storage = new MemoryStorage()
    const localConfig = defaultTemplateConfig()
    localConfig.interfaceFields[0] = { ...localConfig.interfaceFields[0], label: '本地用例编号' }
    saveTemplateConfig(storage, localConfig, { updatedAt: '2026-06-15T12:00:00.000Z' })
    const fetchImpl = vi.fn((input: string) => {
      if (input.endsWith('/api/projects')) {
        return jsonResponse({ items: [{ id: 7, code: 'HAITLIFY_API', name: 'Haitlify API' }], total: 1 })
      }
      return jsonResponse({
        kind: 'interface',
        schemaVersion: 2,
        updatedAt: '2026-06-15T10:00:00.000Z',
        interfaceFields: [{
          key: 'caseId',
          label: '后端用例编号',
          type: 'text',
          required: true,
          visible: true,
          exportable: true,
          aiGenerated: false
        }],
        caseTypes: [{ id: 'positive', name: '正常场景', appliesTo: 'all', enabled: true }]
      })
    })

    const result = await loadTemplateConfigWithBackend(storage, 'Haitlify API', {
      fetchImpl,
      conflictStrategy: 'preferBackend'
    })

    expect(result.source).toBe('backend')
    expect(result.config.interfaceFields[0].label).toBe('后端用例编号')
    expect(loadTemplateConfig(storage).interfaceFields[0].label).toBe('后端用例编号')
  })

  it('saves template config locally and then syncs it to backend', async () => {
    const storage = new MemoryStorage()
    const config = defaultTemplateConfig()
    config.interfaceFields[0] = { ...config.interfaceFields[0], label: '用例编号' }
    const fetchImpl = vi.fn((input: string, init?: RequestInit) => {
      if (input.endsWith('/api/projects')) {
        return jsonResponse({ items: [{ id: 7, code: 'HAITLIFY_API', name: 'Haitlify API' }], total: 1 })
      }
      expect(init?.method).toBe('PUT')
      return jsonResponse({
        kind: 'interface',
        schemaVersion: 2,
        interfaceFields: config.interfaceFields,
        caseTypes: config.caseTypes
      })
    })

    const result = await saveTemplateConfigWithBackend(storage, 'Haitlify API', config, { fetchImpl })

    expect(result.source).toBe('backend')
    expect(loadTemplateConfig(storage).interfaceFields[0].label).toBe('用例编号')
  })

  it('saves generated cases locally and syncs the saved shape to backend', async () => {
    const storage = new MemoryStorage()
    const fetchImpl = vi.fn((input: string, init?: RequestInit) => {
      if (input.endsWith('/api/projects')) {
        return jsonResponse({ items: [{ id: 7, code: 'HAITLIFY_API', name: 'Haitlify API' }], total: 1 })
      }
      expect(init?.method).toBe('POST')
      const body = JSON.parse(String(init?.body))
      expect(body.cases[0].libraryId).toBe('Haitlify.openapi.json:getProjectTree:/v4/pm/project-tree:正向')
      return jsonResponse({ inserted: 1, updated: 0, total: 1 })
    })

    const result = await saveGeneratedCasesWithBackend(storage, 'Haitlify API', [makeCase()], { fetchImpl })

    expect(result.source).toBe('backend')
    expect(result.result.inserted).toBe(1)
    expect(listSavedCases(storage)).toHaveLength(1)
  })

  it('deletes saved cases locally and groups backend delete sync by project', async () => {
    const storage = new MemoryStorage()
    saveGeneratedCases(storage, [
      makeGeneratedCase({
        id: 'API-0001',
        sourceOperationId: 'getProjectTree',
        path: '/v4/pm/project-tree',
        title: '获取项目树-正常请求成功',
        runOrder: 1
      }),
      makeGeneratedCase({
        id: 'API-0002',
        project: 'Ucenter API',
        sourceFile: 'UcenterAPI.openapi.json',
        sourceOperationId: 'listUsers',
        path: '/ucenter/users',
        title: '查询用户-正常请求成功',
        runOrder: 1
      })
    ])
    const deleteBodies: Array<{ input: string; body: { libraryIds: string[] } }> = []
    const fetchImpl = vi.fn((input: string, init?: RequestInit) => {
      if (input.endsWith('/api/projects')) {
        return jsonResponse({
          items: [
            { id: 7, code: 'HAITLIFY_API', name: 'Haitlify API' },
            { id: 8, code: 'UCENTER_API', name: 'Ucenter API' }
          ],
          total: 2
        })
      }
      if (input.endsWith('/interface-cases/delete')) {
        deleteBodies.push({ input, body: JSON.parse(String(init?.body)) })
        return jsonResponse({ deleted: 1, total: 0 })
      }
      throw new Error(`Unexpected request ${input}`)
    })

    const result = await deleteSavedCasesWithBackend(storage, listSavedCases(storage), { fetchImpl })

    expect(result.source).toBe('backend')
    expect(result.deleted).toBe(2)
    expect(listSavedCases(storage)).toHaveLength(0)
    expect(deleteBodies).toHaveLength(2)
    expect(deleteBodies.map((call) => call.input)).toEqual([
      'http://127.0.0.1:18080/api/projects/7/interface-cases/delete',
      'http://127.0.0.1:18080/api/projects/8/interface-cases/delete'
    ])
    expect(deleteBodies[0].body.libraryIds).toEqual([
      'Haitlify.openapi.json:getProjectTree:/v4/pm/project-tree:正向'
    ])
    expect(deleteBodies[1].body.libraryIds).toEqual([
      'UcenterAPI.openapi.json:listUsers:/ucenter/users:正向'
    ])
  })

  it('keeps local delete when backend delete sync is unavailable', async () => {
    const storage = new MemoryStorage()
    saveGeneratedCases(storage, [makeCase()])
    const fetchImpl = vi.fn(() => Promise.reject(new Error('offline')))

    const result = await deleteSavedCasesWithBackend(storage, listSavedCases(storage), { fetchImpl })

    expect(result.source).toBe('local')
    expect(result.deleted).toBe(1)
    expect(listSavedCases(storage)).toHaveLength(0)
  })

  it('syncs only the affected project order through the save endpoint', async () => {
    const storage = new MemoryStorage()
    saveGeneratedCases(storage, [
      makeGeneratedCase({
        id: 'API-0001',
        sourceOperationId: 'getProjectTree',
        path: '/v4/pm/project-tree',
        title: '获取项目树-正常请求成功',
        runOrder: 1
      }),
      makeGeneratedCase({
        id: 'API-0002',
        sourceOperationId: 'createProject',
        path: '/v4/pm/projects',
        title: '创建项目-正常请求成功',
        runOrder: 2
      }),
      makeGeneratedCase({
        id: 'API-0003',
        project: 'Ucenter API',
        sourceFile: 'UcenterAPI.openapi.json',
        sourceOperationId: 'listUsers',
        path: '/ucenter/users',
        title: '查询用户-正常请求成功',
        runOrder: 1
      })
    ])
    const saved = listSavedCases(storage)
    const movedCase = savedById(saved, 'API-0002')
    saveCaseOrdering(storage, [
      movedCase,
      savedById(saved, 'API-0001'),
      savedById(saved, 'API-0003')
    ])
    let saveBody: { cases: SavedInterfaceCase[] } | undefined
    const fetchImpl = vi.fn((input: string, init?: RequestInit) => {
      if (input.endsWith('/api/projects')) {
        return jsonResponse({ items: [{ id: 7, code: 'HAITLIFY_API', name: 'Haitlify API' }], total: 1 })
      }
      if (input.endsWith('/api/projects/7/interface-cases')) {
        saveBody = JSON.parse(String(init?.body))
        return jsonResponse({ inserted: 0, updated: 2, total: 2 })
      }
      throw new Error(`Unexpected request ${input}`)
    })

    const result = await syncCaseOrderingWithBackend(storage, 'Haitlify API', { fetchImpl })

    expect(result.source).toBe('backend')
    expect(result.value?.updated).toBe(2)
    expect(saveBody?.cases.map((testCase) => testCase.libraryId)).toEqual([
      'Haitlify.openapi.json:createProject:/v4/pm/projects:正向',
      'Haitlify.openapi.json:getProjectTree:/v4/pm/project-tree:正向'
    ])
  })

  it('keeps local ordering when backend order sync is unavailable', async () => {
    const storage = new MemoryStorage()
    saveGeneratedCases(storage, [makeCase()])
    const fetchImpl = vi.fn(() => Promise.reject(new Error('offline')))

    const result = await syncCaseOrderingWithBackend(storage, 'Haitlify API', { fetchImpl })

    expect(result.source).toBe('local')
    expect(listSavedCases(storage)).toHaveLength(1)
  })

  it('syncs the current local snapshot for one edited saved-case project', async () => {
    const storage = new MemoryStorage()
    saveGeneratedCases(storage, [
      makeGeneratedCase({
        id: 'API-0001',
        sourceOperationId: 'getProjectTree',
        path: '/v4/pm/project-tree',
        title: '本地编辑后的标题',
        runOrder: 1
      })
    ], {
      savedAt: '2026-06-16T10:00:00.000Z'
    })

    let saveBody: { cases: SavedInterfaceCase[] } | undefined
    const fetchImpl = vi.fn((input: string, init?: RequestInit) => {
      if (input.endsWith('/api/projects')) {
        return jsonResponse({ items: [{ id: 7, code: 'HAITLIFY_API', name: 'Haitlify API' }], total: 1 })
      }
      if (input.endsWith('/api/projects/7/interface-cases')) {
        saveBody = JSON.parse(String(init?.body))
        return jsonResponse({ inserted: 0, updated: 1, total: 1 })
      }
      throw new Error(`Unexpected request ${input}`)
    })

    const result = await syncSavedCaseProjectsWithBackend(storage, ['Haitlify API'], { fetchImpl })

    expect(result.source).toBe('backend')
    expect(saveBody?.cases).toHaveLength(1)
    expect(saveBody?.cases[0].row.title).toBe('本地编辑后的标题')
  })

  it('partitions bulk saved-case sync by affected project and falls back to local on backend failure', async () => {
    const storage = new MemoryStorage()
    saveGeneratedCases(storage, [
      makeGeneratedCase({
        id: 'API-0001',
        sourceOperationId: 'getProjectTree',
        path: '/v4/pm/project-tree',
        title: 'Haitlify 批量修改后',
        runOrder: 1
      }),
      makeGeneratedCase({
        id: 'API-0002',
        project: 'Ucenter API',
        sourceFile: 'UcenterAPI.openapi.json',
        sourceOperationId: 'listUsers',
        path: '/ucenter/users',
        title: 'Ucenter 批量修改后',
        runOrder: 1
      })
    ], {
      savedAt: '2026-06-16T10:00:00.000Z'
    })

    const fetchImpl = vi.fn((input: string) => {
      if (input.endsWith('/api/projects')) {
        return jsonResponse({
          items: [
            { id: 7, code: 'HAITLIFY_API', name: 'Haitlify API' },
            { id: 8, code: 'UCENTER_API', name: 'Ucenter API' }
          ],
          total: 2
        })
      }
      return Promise.reject(new Error(`offline ${input}`))
    })

    const result = await syncSavedCaseProjectsWithBackend(storage, ['Haitlify API', 'Ucenter API'], { fetchImpl })

    expect(result.source).toBe('local')
    expect(listSavedCases(storage)).toHaveLength(2)
  })

  it('loads backend saved cases into local storage for one project', async () => {
    const storage = new MemoryStorage()
    saveGeneratedCases(storage, [
      makeGeneratedCase({
        id: 'API-0001',
        sourceOperationId: 'getProjectTree',
        path: '/v4/pm/project-tree',
        title: '本地 Haitlify 标题',
        runOrder: 1
      }),
      makeGeneratedCase({
        id: 'API-0002',
        project: 'Ucenter API',
        sourceFile: 'UcenterAPI.openapi.json',
        sourceOperationId: 'listUsers',
        path: '/ucenter/users',
        title: '本地 Ucenter 标题',
        runOrder: 1
      })
    ], {
      savedAt: '2026-06-15T08:00:00.000Z'
    })
    const backendCase = {
      ...savedById(listSavedCases(storage), 'API-0001'),
      row: {
        ...savedById(listSavedCases(storage), 'API-0001').row,
        title: '后端 Haitlify 标题'
      },
      savedAt: '2026-06-15T10:00:00.000Z'
    }
    const fetchImpl = vi.fn((input: string) => {
      if (input.endsWith('/api/projects')) {
        return jsonResponse({ items: [{ id: 7, code: 'HAITLIFY_API', name: 'Haitlify API' }], total: 1 })
      }
      if (input.endsWith('/api/projects/7/interface-cases')) {
        return jsonResponse({ items: [backendCase], total: 1 })
      }
      throw new Error(`Unexpected request ${input}`)
    })

    const result = await loadSavedCasesWithBackend(storage, 'Haitlify API', { fetchImpl })

    expect(result.source).toBe('backend')
    expect(result.cases).toHaveLength(2)
    expect(listSavedCases(storage).find((testCase) => testCase.row.project === 'Haitlify API')?.row.title).toBe('后端 Haitlify 标题')
    expect(listSavedCases(storage).find((testCase) => testCase.row.project === 'Ucenter API')?.row.title).toBe('本地 Ucenter 标题')
  })

  it('reuses one project ensure across concurrent template and case loading', async () => {
    const storage = new MemoryStorage()
    const fetchImpl = vi.fn((input: string, init?: RequestInit) => {
      if (input.endsWith('/api/projects') && !init?.method) {
        return jsonResponse({
          items: [{ id: 7, code: 'HAITLIFY_API', name: 'Haitlify API' }],
          total: 1
        })
      }
      if (input.endsWith('/case-template/interface')) {
        return jsonResponse({
          kind: 'interface',
          schemaVersion: 3,
          interfaceFields: defaultTemplateConfig().interfaceFields,
          fieldsByKind: defaultTemplateConfig().fieldsByKind,
          caseTypes: defaultTemplateConfig().caseTypes,
          caseTemplates: defaultTemplateConfig().caseTemplates
        })
      }
      if (input.endsWith('/interface-cases')) {
        return jsonResponse({ items: [], total: 0 })
      }
      throw new Error(`Unexpected request ${input}`)
    })

    await Promise.all([
      loadTemplateConfigWithBackend(storage, 'Haitlify API', { fetchImpl }),
      loadSavedCasesWithBackend(storage, 'Haitlify API', { fetchImpl })
    ])

    const projectReads = fetchImpl.mock.calls.filter((call) => String(call[0]).endsWith('/api/projects') && !call[1]?.method)
    const projectCreates = fetchImpl.mock.calls.filter((call) => String(call[0]).endsWith('/api/projects') && call[1]?.method === 'POST')

    expect(projectReads).toHaveLength(1)
    expect(projectCreates).toHaveLength(0)
  })

  it('keeps local saved cases when backend case list is empty', async () => {
    const storage = new MemoryStorage()
    saveGeneratedCases(storage, [makeCase()])
    const fetchImpl = vi.fn((input: string) => {
      if (input.endsWith('/api/projects')) {
        return jsonResponse({ items: [{ id: 7, code: 'HAITLIFY_API', name: 'Haitlify API' }], total: 1 })
      }
      return jsonResponse({ items: [], total: 0 })
    })

    const result = await loadSavedCasesWithBackend(storage, 'Haitlify API', { fetchImpl })

    expect(result.source).toBe('local')
    expect(result.cases).toHaveLength(1)
    expect(listSavedCases(storage)[0].row.title).toBe('获取项目树-正常请求成功')
  })

  it('keeps newer local saved cases when backend cases are older', async () => {
    const storage = new MemoryStorage()
    saveGeneratedCases(storage, [makeCase()], {
      savedAt: '2026-06-15T12:00:00.000Z'
    })
    const backendCase = {
      ...listSavedCases(storage)[0],
      row: {
        ...listSavedCases(storage)[0].row,
        title: '后端旧标题'
      },
      savedAt: '2026-06-15T10:00:00.000Z'
    }
    const fetchImpl = vi.fn((input: string) => {
      if (input.endsWith('/api/projects')) {
        return jsonResponse({ items: [{ id: 7, code: 'HAITLIFY_API', name: 'Haitlify API' }], total: 1 })
      }
      return jsonResponse({ items: [backendCase], total: 1 })
    })

    const result = await loadSavedCasesWithBackend(storage, 'Haitlify API', { fetchImpl })

    expect(result.source).toBe('conflict')
    expect(result.cases).toHaveLength(1)
    expect(listSavedCases(storage)[0].row.title).toBe('获取项目树-正常请求成功')
  })

  it('can reload older backend saved cases when explicitly requested', async () => {
    const storage = new MemoryStorage()
    saveGeneratedCases(storage, [makeCase()], {
      savedAt: '2026-06-15T12:00:00.000Z'
    })
    const backendCase = {
      ...listSavedCases(storage)[0],
      row: {
        ...listSavedCases(storage)[0].row,
        title: '后端旧标题'
      },
      savedAt: '2026-06-15T10:00:00.000Z'
    }
    const fetchImpl = vi.fn((input: string) => {
      if (input.endsWith('/api/projects')) {
        return jsonResponse({ items: [{ id: 7, code: 'HAITLIFY_API', name: 'Haitlify API' }], total: 1 })
      }
      return jsonResponse({ items: [backendCase], total: 1 })
    })

    const result = await loadSavedCasesWithBackend(storage, 'Haitlify API', {
      fetchImpl,
      conflictStrategy: 'preferBackend'
    })

    expect(result.source).toBe('backend')
    expect(result.cases).toHaveLength(1)
    expect(listSavedCases(storage)[0].row.title).toBe('后端旧标题')
  })

  it('falls back to local saved cases when backend case loading fails', async () => {
    const storage = new MemoryStorage()
    saveGeneratedCases(storage, [makeCase()])
    const fetchImpl = vi.fn(() => Promise.reject(new Error('offline')))

    const result = await loadSavedCasesWithBackend(storage, 'Haitlify API', { fetchImpl })

    expect(result.source).toBe('local')
    expect(result.cases).toHaveLength(1)
  })
})

import { describe, expect, it } from 'vitest'
import type { GeneratedInterfaceCase } from './caseGenerator'
import {
  clearSavedCases,
  deleteSavedCases,
  deleteSavedCase,
  deleteSavedWebAutomationCase,
  deleteSavedWebAutomationCases,
  listSavedCases,
  listSavedWebAutomationCases,
  replaceSavedCasesForProject,
  saveCaseOrdering,
  saveGeneratedCases,
  saveWebAutomationCaseOrdering,
  saveGeneratedWebAutomationCases
} from './caseRepository'
import type { GeneratedWebAutomationCase } from './webAutomationCase'

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

const makeCase = (caseId: string, title = '新增项目成员-正常请求成功'): GeneratedInterfaceCase => ({
  id: caseId,
  row: {
    caseId,
    project: 'Haitlify',
    module: '项目成员',
    interfaceName: '新增项目成员',
    method: 'POST',
    path: '/api/projects/{projectId}/members',
    title,
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

const makeWebCase = (
  caseId: string,
  title = '用户名密码登录成功',
  startUrl = '/login'
): GeneratedWebAutomationCase => ({
  id: caseId,
  row: {
    caseId,
    project: 'Haitlify',
    module: '登录',
    pageName: '登录页',
    title,
    caseType: '正向',
    precondition: '浏览器已启动',
    startUrl,
    browser: 'chromium',
    viewport: '1440x900',
    testData: '{"username":"admin"}',
    steps: '1. 打开登录页',
    locators: '#username',
    assertions: '显示工作台',
    waitStrategy: 'networkidle',
    screenshotStrategy: 'on_failure',
    priority: 'P1',
    executionResult: '未执行',
    executor: '',
    executedAt: '',
    defectId: '',
    tags: '登录,Web自动化',
    sourceFile: 'login.yaml',
    remark: ''
  },
  metadata: {
    sourceFile: 'login.yaml',
    sourceDocumentType: 'yaml',
    pageName: '登录页',
    startUrl,
    browser: 'chromium',
    viewport: '1440x900',
    steps: [
      {
        id: 'step-1',
        name: '打开登录页',
        action: 'goto',
        targetType: 'url',
        target: '/login'
      }
    ],
    locators: [
      {
        id: 'locator-1',
        name: '用户名输入框',
        locatorType: 'css',
        value: '#username'
      }
    ],
    assertions: [
      {
        id: 'assertion-1',
        name: '工作台可见',
        type: 'visible',
        target: '.dashboard'
      }
    ],
    variables: {},
    runOrder: 1
  }
})

describe('caseRepository', () => {
  it('saves generated cases and lists them newest first with saved metadata', () => {
    const storage = new MemoryStorage()

    const second = makeCase('API-0002')
    second.row.caseType = '鉴权缺失'

    const result = saveGeneratedCases(storage, [makeCase('API-0001'), second], {
      savedAt: '2026-06-10T08:00:00.000Z'
    })

    expect(result.inserted).toBe(2)
    expect(result.updated).toBe(0)
    expect(listSavedCases(storage)).toHaveLength(2)
    expect(listSavedCases(storage)[0]).toMatchObject({
      libraryId: 'Haitlify.openapi.json:createProjectMember:/api/projects/{projectId}/members:正向',
      savedAt: '2026-06-10T08:00:00.000Z',
      row: {
        caseId: 'API-0001',
        project: 'Haitlify'
      }
    })
  })

  it('replaces duplicated generated cases instead of appending copies', () => {
    const storage = new MemoryStorage()

    saveGeneratedCases(storage, [makeCase('API-0001', '旧标题')], {
      savedAt: '2026-06-10T08:00:00.000Z'
    })
    const result = saveGeneratedCases(storage, [makeCase('API-0001', '新标题')], {
      savedAt: '2026-06-10T09:00:00.000Z'
    })

    const saved = listSavedCases(storage)
    expect(result.inserted).toBe(0)
    expect(result.updated).toBe(1)
    expect(saved).toHaveLength(1)
    expect(saved[0].row.title).toBe('新标题')
    expect(saved[0].savedAt).toBe('2026-06-10T09:00:00.000Z')
  })

  it('deletes one saved case and clears the library', () => {
    const storage = new MemoryStorage()
    const first = makeCase('API-0001')
    const second = makeCase('API-0002')
    second.row.caseType = '鉴权缺失'

    saveGeneratedCases(storage, [first, second], {
      savedAt: '2026-06-10T08:00:00.000Z'
    })

    expect(deleteSavedCase(storage, listSavedCases(storage)[0].libraryId)).toBe(true)
    expect(listSavedCases(storage)).toHaveLength(1)

    clearSavedCases(storage)
    expect(listSavedCases(storage)).toEqual([])
  })

  it('bulk deletes saved cases by selected ids', () => {
    const storage = new MemoryStorage()
    const first = makeCase('API-0001')
    const second = makeCase('API-0002')
    second.row.caseType = '鉴权缺失'

    saveGeneratedCases(storage, [first, second], {
      savedAt: '2026-06-10T08:00:00.000Z'
    })

    const saved = listSavedCases(storage)
    expect(deleteSavedCases(storage, saved.map((item) => item.libraryId))).toBe(2)
    expect(listSavedCases(storage)).toEqual([])
  })

  it('persists manual case ordering and normalizes run order', () => {
    const storage = new MemoryStorage()
    const first = makeCase('API-0001')
    const second = makeCase('API-0002')
    second.row.caseType = '鉴权缺失'

    saveGeneratedCases(storage, [first, second], {
      savedAt: '2026-06-10T08:00:00.000Z'
    })

    const reordered = [...listSavedCases(storage)].reverse()
    saveCaseOrdering(storage, reordered)

    expect(listSavedCases(storage).map((item) => item.row.caseId)).toEqual(['API-0002', 'API-0001'])
    expect(listSavedCases(storage).map((item) => item.metadata.runOrder)).toEqual([1, 2])
  })

  it('replaces saved cases for one project and preserves other projects', () => {
    const storage = new MemoryStorage()
    const cloudLocal = makeCase('API-0001', '本地 Haitlify 标题')
    const ucenterLocal = makeCase('API-0002', '本地 Ucenter 标题')
    ucenterLocal.row.project = 'Ucenter'
    ucenterLocal.metadata.sourceFile = 'UcenterAPI.openapi.json'
    ucenterLocal.metadata.sourceOperationId = 'listUsers'
    ucenterLocal.metadata.path = '/api/users'
    ucenterLocal.row.path = '/api/users'

    saveGeneratedCases(storage, [cloudLocal, ucenterLocal], {
      savedAt: '2026-06-10T08:00:00.000Z'
    })

    const backendCloud = {
      ...listSavedCases(storage).find((testCase) => testCase.row.project === 'Haitlify')!,
      row: {
        ...cloudLocal.row,
        title: '后端 Haitlify 标题'
      },
      savedAt: '2026-06-15T10:00:00.000Z'
    }

    replaceSavedCasesForProject(storage, 'Haitlify', [backendCloud])

    expect(listSavedCases(storage).map((testCase) => testCase.row.project).sort()).toEqual(['Haitlify', 'Ucenter'])
    expect(listSavedCases(storage).find((testCase) => testCase.row.project === 'Haitlify')?.row.title).toBe('后端 Haitlify 标题')
    expect(listSavedCases(storage).find((testCase) => testCase.row.project === 'Ucenter')?.row.title).toBe('本地 Ucenter 标题')
  })

  it('saves and updates web automation cases separately from interface cases', () => {
    const storage = new MemoryStorage()

    saveGeneratedCases(storage, [makeCase('API-0001')], {
      savedAt: '2026-06-10T08:00:00.000Z'
    })
    const firstSave = saveGeneratedWebAutomationCases(storage, [makeWebCase('WEB-0001')], {
      savedAt: '2026-06-10T09:00:00.000Z'
    })
    const secondSave = saveGeneratedWebAutomationCases(storage, [makeWebCase('WEB-0001', '用户名密码登录成功-更新')], {
      savedAt: '2026-06-10T10:00:00.000Z'
    })

    expect(firstSave.inserted).toBe(1)
    expect(secondSave.updated).toBe(1)
    expect(listSavedCases(storage)).toHaveLength(1)
    expect(listSavedWebAutomationCases(storage)).toHaveLength(1)
    expect(listSavedWebAutomationCases(storage)[0].row.title).toBe('用户名密码登录成功-更新')
    expect(listSavedWebAutomationCases(storage)[0].savedAt).toBe('2026-06-10T10:00:00.000Z')
  })

  it('deletes and reorders saved web automation cases independently', () => {
    const storage = new MemoryStorage()

    saveGeneratedWebAutomationCases(storage, [
      makeWebCase('WEB-0001', '登录成功', '/login'),
      makeWebCase('WEB-0002', '登录失败', '/login/error')
    ], {
      savedAt: '2026-06-10T09:00:00.000Z'
    })

    const saved = listSavedWebAutomationCases(storage)
    expect(deleteSavedWebAutomationCase(storage, saved[0].libraryId)).toBe(true)
    expect(listSavedWebAutomationCases(storage)).toHaveLength(1)

    saveGeneratedWebAutomationCases(storage, [makeWebCase('WEB-0003', '退出成功', '/logout')], {
      savedAt: '2026-06-10T10:00:00.000Z'
    })
    const reordered = [...listSavedWebAutomationCases(storage)].reverse()
    saveWebAutomationCaseOrdering(storage, reordered)
    expect(listSavedWebAutomationCases(storage).map((item) => item.metadata.runOrder)).toEqual([1, 2])

    const remaining = listSavedWebAutomationCases(storage)
    expect(deleteSavedWebAutomationCases(storage, remaining.map((item) => item.libraryId))).toBe(2)
    expect(listSavedWebAutomationCases(storage)).toEqual([])
  })
})

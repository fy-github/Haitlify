import { describe, expect, it } from 'vitest'
import type { SavedInterfaceCase } from './caseRepository'
import type { SavedWebAutomationCase } from './webAutomationCase'
import {
  buildCaseLibraryIndex,
  buildSupportedCaseKinds,
  collectCaseLibraryOptions,
  filterCaseLibraryRecords,
  paginateSavedCases
} from './caseLibraryView'

const makeSavedCase = (
  caseId: string,
  project: string,
  module: string,
  caseType: string,
  title: string
): SavedInterfaceCase => ({
  id: caseId,
  libraryId: `${project}:${module}:${caseType}:${caseId}`,
  savedAt: '2026-06-11T01:00:00.000Z',
  row: {
    caseId,
    project,
    module,
    interfaceName: title,
    method: 'GET',
    path: `/api/${caseId}`,
    title,
    caseType,
    precondition: '',
    headers: '{}',
    pathParams: '{}',
    queryParams: '{}',
    body: '{}',
    steps: '',
    expectedResult: '',
    assertions: '',
    priority: 'P1',
    executionResult: '未执行',
    executor: '',
    executedAt: '',
    defectId: '',
    tags: `${project},${module}`,
    sourceFile: `${project}.openapi.json`,
    remark: ''
  },
  metadata: {
    sourceSpecId: `${project}:${caseId}`,
    sourceFile: `${project}.openapi.json`,
    sourceOperationId: caseId,
    tag: module,
    method: 'GET',
    path: `/api/${caseId}`,
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

const makeSavedWebCase = (
  caseId: string,
  project: string,
  module: string,
  caseType: string,
  title: string
): SavedWebAutomationCase => ({
  id: caseId,
  libraryId: `${project}:${module}:${caseType}:${caseId}`,
  savedAt: '2026-06-11T01:00:00.000Z',
  row: {
    caseId,
    project,
    module,
    pageName: '登录页',
    title,
    caseType,
    precondition: '',
    startUrl: '/login',
    browser: 'chromium',
    viewport: '1440x900',
    testData: '{}',
    steps: '1. 打开登录页',
    locators: '#username',
    assertions: '工作台可见',
    waitStrategy: 'networkidle',
    screenshotStrategy: 'on_failure',
    priority: 'P1',
    executionResult: '未执行',
    executor: '',
    executedAt: '',
    defectId: '',
    tags: `${project},${module},web`,
    sourceFile: 'login.yaml',
    remark: ''
  },
  metadata: {
    sourceFile: 'login.yaml',
    sourceDocumentType: 'yaml',
    pageName: '登录页',
    startUrl: '/login',
    browser: 'chromium',
    viewport: '1440x900',
    steps: [],
    locators: [],
    assertions: [],
    variables: {},
    runOrder: 1
  }
})

describe('caseLibraryView', () => {
  it('builds supported case kind registry for current and future library views', () => {
    expect(buildSupportedCaseKinds().map((item) => item.value)).toEqual([
      'interface',
      'web_automation',
      'functional',
      'app_automation',
      'ai_test',
      'performance'
    ])
  })

  it('builds project counts and searchable records once', () => {
    const index = buildCaseLibraryIndex([
      makeSavedCase('API-1', 'Haitlify', '成员', '正向', '新增成员成功'),
      makeSavedCase('API-2', 'Haitlify', '成员', '异常', '新增成员失败'),
      makeSavedCase('API-3', 'Ucenter', '用户', '正向', '获取用户成功')
    ], 'interface')

    expect(index.projectOptions).toEqual([
      { value: 'Haitlify', count: 2 },
      { value: 'Ucenter', count: 1 }
    ])
    expect(index.records[0].searchText).toContain('新增成员成功')
    expect(index.records[0].searchText).toContain('haitlify.openapi.json')
  })

  it('filters records by project, module, case type, and keyword', () => {
    const index = buildCaseLibraryIndex([
      makeSavedCase('API-1', 'Haitlify', '成员', '正向', '新增成员成功'),
      makeSavedCase('API-2', 'Haitlify', '项目', '异常', '项目失败'),
      makeSavedCase('API-3', 'Ucenter', '用户', '正向', '获取用户成功')
    ], 'interface')

    const filtered = filterCaseLibraryRecords(index.records, {
      project: 'Haitlify',
      module: '成员',
      caseType: '正向',
      source: '',
      keyword: '新增'
    })

    expect(filtered.map((record) => record.caseItem.row.caseId)).toEqual(['API-1'])
  })

  it('collects facet counts and paginates the visible table data', () => {
    const index = buildCaseLibraryIndex([
      makeSavedCase('API-1', 'Haitlify', '成员', '正向', '新增成员成功'),
      makeSavedCase('API-2', 'Haitlify', '成员', '异常', '新增成员失败'),
      makeSavedCase('API-3', 'Haitlify', '项目', '正向', '项目成功')
    ], 'interface')

    expect(collectCaseLibraryOptions(index.records, 'module')).toEqual([
      { value: '成员', count: 2 },
      { value: '项目', count: 1 }
    ])
    expect(paginateSavedCases(index.records, 2, 2).map((record) => record.caseItem.row.caseId)).toEqual(['API-3'])
  })

  it('indexes web automation cases with web-specific search fields', () => {
    const index = buildCaseLibraryIndex([
      makeSavedWebCase('WEB-1', 'Haitlify', '登录', '正向', '用户名密码登录成功'),
      makeSavedWebCase('WEB-2', 'Haitlify', '登录', '异常', '用户名为空提示错误')
    ], 'web_automation')

    expect(index.projectOptions).toEqual([{ value: 'Haitlify', count: 2 }])
    expect(index.records[0].searchText).toContain('登录页')
    expect(index.records[0].searchText).toContain('/login')
  })

  it('indexes design-origin web cases with source markers in search text', () => {
    const designCase = makeSavedWebCase('WEB-3', 'Haitlify', '设计稿交互', '设计自动化', '设计稿-主按钮跳转')
    designCase.row.tags = '设计稿,设计自动化'
    designCase.row.remark = '来源于设计稿'

    const index = buildCaseLibraryIndex([designCase], 'web_automation')

    expect(index.records[0].searchText).toContain('来源于设计稿')
    expect(index.records[0].searchText).toContain('设计自动化')
    expect(index.records[0].source).toBe('design')
  })

  it('filters saved cases by source marker', () => {
    const designCase = makeSavedWebCase('WEB-3', 'Haitlify', '设计稿交互', '设计自动化', '设计稿-主按钮跳转')
    designCase.row.tags = '设计稿,设计自动化'
    designCase.row.remark = '来源于设计稿'
    const normalCase = makeSavedWebCase('WEB-4', 'Haitlify', '登录', '正向', '用户名密码登录成功')

    const index = buildCaseLibraryIndex([designCase, normalCase], 'web_automation')
    const filtered = filterCaseLibraryRecords(index.records, {
      project: 'ALL',
      module: 'ALL',
      caseType: 'ALL',
      source: 'design',
      keyword: ''
    })

    expect(filtered.map((record) => record.caseItem.row.caseId)).toEqual(['WEB-3'])
  })
})

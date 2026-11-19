// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  CASE_LIBRARY_STORAGE_KEY,
  WEB_AUTOMATION_CASE_LIBRARY_STORAGE_KEY,
  type SavedInterfaceCase
} from '../domain/caseRepository'
import type { SavedWebAutomationCase } from '../domain/webAutomationCase'

// downloadCsv 直接触发 jsdom 的 a.click()(jsdom 未实现导航),这里拦截真实下载、保留 CSV 文本。
const downloads = vi.hoisted(() => [] as Array<{ filename: string; csv: string }>)
vi.mock('../domain/csvExport', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../domain/csvExport')>()
  return {
    ...actual,
    downloadCsv: (filename: string, csv: string) => {
      downloads.push({ filename, csv })
    }
  }
})

const makeSavedCase = (caseId: string, title: string, project = 'Haitlify.openapi.json'): SavedInterfaceCase => ({
  libraryId: `${project}:${caseId}`,
  savedAt: '2026-08-01T08:00:00.000Z',
  id: `case-${caseId}`,
  row: {
    caseId,
    project,
    module: '项目成员',
    interfaceName: '新增项目成员',
    method: 'POST',
    path: `/api/${caseId}`,
    title,
    caseType: '正向',
    precondition: '',
    headers: '',
    pathParams: '',
    queryParams: '',
    body: '{}',
    steps: '',
    expectedResult: '',
    assertions: '',
    priority: 'P1',
    executionResult: '',
    executor: '',
    executedAt: '',
    defectId: '',
    tags: '',
    sourceFile: 'Haitlify.openapi.json',
    remark: ''
  },
  metadata: {
    sourceSpecId: 'spec-1',
    sourceFile: 'Haitlify.openapi.json',
    sourceOperationId: 'createProjectMember',
    tag: '项目成员',
    method: 'POST',
    path: `/api/${caseId}`,
    baseUrlRef: 'default',
    environmentId: 'env-1',
    authProfileId: 'auth-1',
    contentType: 'application/json',
    headers: {},
    pathParams: {},
    queryParams: {},
    body: {},
    expectedStatus: 200,
    assertions: [],
    expectedBodyRules: [],
    variableExtractions: [],
    dependsOnCaseIds: [],
    runOrder: 1,
    generationMeta: { granularity: 'basic', strategy: 'default' },
    reviewMeta: { status: '待复核' },
    exportMeta: { apifoxReady: false }
  }
})

const makeWebCase = (caseId: string, title: string): SavedWebAutomationCase => ({
  libraryId: `web:${caseId}`,
  savedAt: '2026-08-01T08:00:00.000Z',
  id: `web-${caseId}`,
  row: {
    caseId,
    project: 'Haitlify.web',
    module: '登录',
    pageName: '登录页',
    title,
    caseType: '正向',
    precondition: '',
    startUrl: 'https://example.test/login',
    browser: 'chromium',
    viewport: '1280x720',
    testData: '',
    steps: '',
    locators: '',
    assertions: '',
    waitStrategy: 'visible',
    screenshotStrategy: 'on_failure',
    priority: 'P1',
    executionResult: '',
    executor: '',
    executedAt: '',
    defectId: '',
    tags: '',
    sourceFile: 'login.md',
    remark: ''
  },
  metadata: {
    sourceFile: 'login.md',
    sourceDocumentType: 'markdown',
    pageName: '登录页',
    startUrl: 'https://example.test/login',
    browser: 'chromium',
    viewport: '1280x720',
    steps: [],
    locators: [],
    assertions: [],
    variables: {},
    runOrder: 1
  }
})

type CasesState = ReturnType<typeof import('./useCases').useCases>

const loadCases = async (deps: Record<string, unknown> = {}): Promise<CasesState> => {
  vi.resetModules()
  const { useCases } = await import('./useCases')
  return useCases({ storage: window.localStorage, ...deps } as never)
}

const captureDownload = () => {
  downloads.length = 0
  return downloads
}

const csvOf = (csv: string) => csv.replace(/^\ufeff/, '')

describe('useCases library selection and export scope', () => {
  beforeEach(() => {
    localStorage.clear()
    downloads.length = 0
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('exports only the selected interface cases', async () => {
    localStorage.setItem(CASE_LIBRARY_STORAGE_KEY, JSON.stringify([
      makeSavedCase('API-0001', '选中一'),
      makeSavedCase('API-0002', '未选中二'),
      makeSavedCase('API-0003', '选中三')
    ]))
    const state = await loadCases()
    state.toggleLibraryRow(state.savedCases.value[0], true)
    state.toggleLibraryRow(state.savedCases.value[2], true)

    const captured = captureDownload()
    state.exportSavedCsv()

    const rows = csvOf(captured[0].csv).split('\n')
    expect(rows[0]).toContain('用例标题')
    expect(rows).toHaveLength(3)
    expect(rows[1]).toContain('选中一')
    expect(rows[2]).toContain('选中三')
    expect(rows.join('\n')).not.toContain('未选中二')
  })

  it('exports every configured visible field instead of a fixed column whitelist', async () => {
    localStorage.setItem(CASE_LIBRARY_STORAGE_KEY, JSON.stringify([makeSavedCase('API-0004', '字段导出')]))
    const state = await loadCases()

    const captured = captureDownload()
    state.exportSavedCsv()

    const header = csvOf(captured[0].csv).split('\n')[0]
    expect(header).toContain('请求方法')
    expect(header).toContain('请求路径')
    expect(header).toContain('预期结果')
    expect(header).toContain('备注')
  })

  it('exports only the selected web cases', async () => {
    localStorage.setItem(WEB_AUTOMATION_CASE_LIBRARY_STORAGE_KEY, JSON.stringify([
      makeWebCase('WEB-0001', '登录成功'),
      makeWebCase('WEB-0002', '登录失败提示')
    ]))
    const state = await loadCases()
    state.switchLibraryCaseKind('web_automation')
    state.toggleLibraryRow(state.savedWebCases.value[0], true)

    const captured = captureDownload()
    state.exportSavedCsv()

    expect(captured).toHaveLength(1)
    const rows = csvOf(captured[0].csv).split('\n')
    expect(rows[0]).toContain('用例标题')
    expect(rows.some((line) => line.includes('登录成功'))).toBe(true)
    expect(rows.join('\n')).not.toContain('登录失败提示')
  })

  it('selects the current page as a union and deselects it as a difference', async () => {
    localStorage.setItem(CASE_LIBRARY_STORAGE_KEY, JSON.stringify([
      makeSavedCase('API-0011', '第一页一'),
      makeSavedCase('API-0012', '第一页二'),
      makeSavedCase('API-0013', '第二页一')
    ]))
    const state = await loadCases()
    const firstPage = [state.savedCases.value[0], state.savedCases.value[1]]

    state.toggleCurrentPageSelection(firstPage, true)
    expect(state.selectedLibraryIds.value.sort()).toEqual(['Haitlify.openapi.json:API-0011', 'Haitlify.openapi.json:API-0012'])

    // 跨页并集:第二页全选不能把第一页的选中清掉
    state.toggleCurrentPageSelection([state.savedCases.value[2]], true)
    expect(state.selectedLibraryIds.value).toHaveLength(3)

    // 差集:取消当前页只移除本页勾选
    state.toggleCurrentPageSelection(firstPage, false)
    expect(state.selectedLibraryIds.value).toEqual(['Haitlify.openapi.json:API-0013'])
  })

  it('derives the header checkbox state from the current page intersection', async () => {
    localStorage.setItem(CASE_LIBRARY_STORAGE_KEY, JSON.stringify([
      makeSavedCase('API-0021', '第一页一'),
      makeSavedCase('API-0022', '第一页二'),
      makeSavedCase('API-0023', '第二页一')
    ]))
    const state = await loadCases()
    const firstPage = [state.savedCases.value[0], state.savedCases.value[1]]
    const secondPage = [state.savedCases.value[2]]

    expect(state.currentPageSelectionState(firstPage)).toEqual({ checked: false, indeterminate: false })

    state.toggleLibraryRow(firstPage[1], true)
    state.toggleLibraryRow(secondPage[0], true)
    expect(state.currentPageSelectionState(firstPage)).toEqual({ checked: false, indeterminate: true })
    expect(state.currentPageSelectionState(secondPage)).toEqual({ checked: true, indeterminate: false })

    state.toggleCurrentPageSelection(firstPage, true)
    expect(state.currentPageSelectionState(firstPage)).toEqual({ checked: true, indeterminate: false })
  })

  it('keeps web rows selectable and lets the current page select-all work for them', async () => {
    localStorage.setItem(WEB_AUTOMATION_CASE_LIBRARY_STORAGE_KEY, JSON.stringify([
      makeWebCase('WEB-0011', '登录成功'),
      makeWebCase('WEB-0012', '登录失败提示')
    ]))
    const state = await loadCases()
    state.switchLibraryCaseKind('web_automation')
    const page = state.pagedWebLibraryCases.value as unknown as SavedWebAutomationCase[]

    state.toggleCurrentPageSelection(page, true)

    expect(state.selectedLibraryIds.value.sort()).toEqual(['web:WEB-0011', 'web:WEB-0012'])
    expect(state.currentPageSelectionState(page)).toEqual({ checked: true, indeterminate: false })
  })

  it('clears the whole cross-page selection in one call', async () => {
    localStorage.setItem(CASE_LIBRARY_STORAGE_KEY, JSON.stringify([makeSavedCase('API-0031', '清除选择')]))
    const state = await loadCases()
    state.toggleLibraryRow(state.savedCases.value[0], true)
    state.clearLibrarySelection()
    expect(state.selectedLibraryIds.value).toEqual([])
  })

  it.each([
    ['interface', 'deleteSelectedLibraryCases'],
    ['interface', 'deleteLibraryCasesByScope'],
    ['web_automation', 'deleteSelectedLibraryCases']
  ] as const)('ignores a stale %s %s confirmation after leaving the page', async (kind, action) => {
    const interfaceCase = makeSavedCase('API-0041', '接口用例')
    const webCase = makeWebCase('WEB-0041', 'Web 用例')
    const interfaceData = JSON.stringify([interfaceCase])
    const webData = JSON.stringify([webCase])
    localStorage.setItem(CASE_LIBRARY_STORAGE_KEY, interfaceData)
    localStorage.setItem(WEB_AUTOMATION_CASE_LIBRARY_STORAGE_KEY, webData)
    const fetch = vi.fn().mockRejectedValue(new Error('Unexpected request'))
    vi.stubGlobal('fetch', fetch)
    let accept!: (confirmed: boolean) => void
    const confirmDanger = vi.fn(() => new Promise<boolean>((resolve) => { accept = resolve }))
    const state = await loadCases({ confirmDanger, initialLibraryKind: kind })
    const row = kind === 'interface' ? interfaceCase : webCase
    state.toggleLibraryRow(row, true)
    const deletion = state[action]()
    expect(confirmDanger).toHaveBeenCalledOnce()

    state.switchLibraryCaseKind(kind === 'interface' ? 'web_automation' : 'interface')
    state.switchLibraryCaseKind(kind)
    state.toggleLibraryRow(row, true)
    accept(true)
    await deletion

    expect(localStorage.getItem(CASE_LIBRARY_STORAGE_KEY)).toBe(interfaceData)
    expect(localStorage.getItem(WEB_AUTOMATION_CASE_LIBRARY_STORAGE_KEY)).toBe(webData)
    expect(state.selectedLibraryIds.value).toEqual([row.libraryId])
    expect(fetch).not.toHaveBeenCalled()
  })

  it('deletes only the selected web cases through the existing web storage API', async () => {
    localStorage.setItem(WEB_AUTOMATION_CASE_LIBRARY_STORAGE_KEY, JSON.stringify([
      makeWebCase('WEB-0021', '登录成功'),
      makeWebCase('WEB-0022', '登录失败提示')
    ]))
    const state = await loadCases({ confirmDanger: async () => true })
    state.switchLibraryCaseKind('web_automation')
    state.toggleLibraryRow(state.savedWebCases.value[0], true)

    await state.deleteSelectedLibraryCases()

    expect(state.savedWebCases.value.map((item) => item.row.caseId)).toEqual(['WEB-0022'])
    expect(state.selectedLibraryIds.value).toEqual([])
  })
})

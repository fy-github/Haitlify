// @vitest-environment jsdom
// View-level integration test: mounts the real ReportsView with ElementPlus.
// useReports is a module-level singleton (shared with App.vue), so the project selection
// is driven by grabbing the singleton and setting its refs before mounting the view.
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { AUTOMATION_REPORT_STORAGE_KEY, type SavedAutomationReport } from '../../domain/automationReportRepository'

enableAutoUnmount(afterEach)

const makeReport = (): SavedAutomationReport => ({
  reportId: 'r-1',
  name: '回归报告',
  project: 'Haitlify API',
  environmentName: 'dev',
  moduleScope: ['项目成员'],
  createdAt: '2026-08-01T00:00:00Z',
  mode: 'sequential',
  startedAt: '2026-08-01T00:00:00Z',
  endedAt: '2026-08-01T00:01:00Z',
  summary: { total: 3, passed: 1, failed: 1, error: 1 },
  results: [
    {
      caseId: 'API-0001',
      sourceCaseId: 'API-0001',
      name: '登录接口',
      module: '认证',
      method: 'POST',
      path: '/api/login',
      status: 'passed',
      request: { method: 'POST', url: 'http://localhost/api/login', headers: {}, expectedStatus: 200, assertions: [] },
      expectedStatus: 200,
      durationMs: 120
    },
    {
      caseId: 'API-0002',
      sourceCaseId: 'API-0002',
      name: '失败接口',
      module: '认证',
      method: 'POST',
      path: '/api/failure',
      status: 'failed',
      request: { method: 'POST', url: 'http://localhost/api/failure', headers: {}, expectedStatus: 200, assertions: [] },
      expectedStatus: 200,
      actualStatus: 500,
      durationMs: 130,
      errorMessage: '断言失败'
    },
    {
      caseId: 'API-0003',
      sourceCaseId: 'API-0003',
      name: '错误接口',
      module: '认证',
      method: 'GET',
      path: '/api/error',
      status: 'error',
      request: { method: 'GET', url: 'http://localhost/api/error', headers: {}, expectedStatus: 200, assertions: [] },
      expectedStatus: 200,
      durationMs: 140,
      errorMessage: '连接错误'
    }
  ]
})

interface ReportsState {
  selectedReportProject: { value: string }
  selectedReportId: { value?: string }
  reportKeyword: { value: string }
  openActiveWebRunReport: () => void
}

const loadView = async () => {
  vi.resetModules()
  // 顺序与 App.vue 一致：先以完整 deps 建 defects 单例,再取 reports 单例
  //（useReports.createReportsState 会调用 useDefects()，先建可避免缺依赖）。
  const { useDefects } = await import('../../ui/useDefects')
  const defectsNavigate = vi.fn()
  const downloads: Array<{ filename: string; content: string; mimeType: string }> = []
  useDefects({
    navigateToDefects: defectsNavigate,
    confirmDanger: async () => true,
    candidateProjects: () => [],
    isDefectsActive: () => true
  } as never)
  const { useReports } = await import('../../ui/useReports')
  const reports = useReports({
    navigateToReports: () => {},
    confirmDanger: async () => true,
    downloadTextFile: (filename: string, content: string, mimeType: string) => downloads.push({ filename, content, mimeType }),
    showResultLog: () => {}
  } as never) as unknown as ReportsState
  const { default: ReportsView } = await import('./ReportsView.vue')
  return {
    Component: ReportsView as unknown as { new (): unknown },
    reports,
    defectsNavigate,
    downloads
  }
}

const mountView = (Component: { new (): unknown }) =>
  mount(Component as never, { global: { plugins: [ElementPlus] }, attachTo: document.body })

describe('ReportsView integration', () => {
  beforeEach(() => localStorage.clear())

  it('shows the project-empty prompt before a project is selected', async () => {
    const { Component } = await loadView()
    const wrapper = mountView(Component)
    expect(wrapper.find('h1').text()).toContain('执行报告')
    expect(wrapper.text()).toContain('请先选择项目')
  })

  it('renders the seeded report once a project is selected', async () => {
    localStorage.setItem(AUTOMATION_REPORT_STORAGE_KEY, JSON.stringify([makeReport()]))
    const { Component } = await loadView()
    const wrapper = mountView(Component)
    await nextTick()
    const text = wrapper.text()
    expect(wrapper.text()).toContain('回归报告')
    expect(text).toContain('历史报告')
    expect(wrapper.find('[data-testid="report-stat-total"]').exists()).toBe(false)
    await wrapper.get('[data-testid="open-report-r-1"]').trigger('click')
    await nextTick()
    expect(wrapper.get('[data-testid="report-stat-total"]').text()).toContain('3')
    expect(wrapper.get('[data-testid="report-stat-passed"]').text()).toContain('1')
    expect(wrapper.get('[data-testid="report-stat-failed"]').text()).toContain('1')
    expect(wrapper.get('[data-testid="report-stat-error"]').text()).toContain('1')
  })

  it('exports the complete JSON records for the current filtered report list', async () => {
    const first = makeReport()
    const second = { ...makeReport(), reportId: 'r-2', name: '失败专项报告' }
    localStorage.setItem(AUTOMATION_REPORT_STORAGE_KEY, JSON.stringify([first, second]))
    const { Component, reports, downloads } = await loadView()
    const wrapper = mountView(Component)
    await nextTick()
    reports.reportKeyword.value = '失败'
    await nextTick()

    await wrapper.get('[data-testid="export-report-list-json"]').trigger('click')

    expect(downloads).toHaveLength(1)
    expect(downloads[0]!.filename).toContain('报告列表')
    expect(downloads[0]!.mimeType).toBe('application/json;charset=utf-8')
    const packageData = JSON.parse(downloads[0]!.content) as { reports: SavedAutomationReport[] }
    expect(packageData.reports).toEqual([second])
    expect(packageData.reports[0]!.results).toEqual(second.results)
  })

  it('opens an independent result detail page and restores the exact result opener', async () => {
    localStorage.setItem(AUTOMATION_REPORT_STORAGE_KEY, JSON.stringify([makeReport()]))
    const { Component } = await loadView()
    const wrapper = mountView(Component)
    await nextTick()
    await wrapper.get('[data-testid="open-report-r-1"]').trigger('click')
    await nextTick()
    await wrapper.get('[data-testid="result-details-API-0002"]').trigger('click')
    await nextTick()
    expect(wrapper.text()).toContain('单条执行结果详情')
    expect(wrapper.text()).toContain('失败接口')
    expect(wrapper.find('[data-testid="report-result-drawer"]').exists()).toBe(false)
    expect(wrapper.find('.report-result-page').exists()).toBe(true)
    await wrapper.get('[data-testid="back-to-report"]').trigger('click')
    await nextTick()
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(wrapper.find('[data-testid="result-details-API-0002"]').element).toBe(document.activeElement)
  })

  it('resets each report to all results and keeps whole-report totals stable under quick filters', async () => {
    localStorage.setItem(AUTOMATION_REPORT_STORAGE_KEY, JSON.stringify([makeReport()]))
    const { Component } = await loadView()
    const wrapper = mountView(Component)
    await nextTick()
    await wrapper.get('[data-testid="open-report-r-1"]').trigger('click')
    await nextTick()
    await wrapper.get('[data-testid="result-filter-failed"]').trigger('click')
    await nextTick()
    expect(wrapper.text()).toContain('当前 1 条结果')
    expect(wrapper.get('[data-testid="report-stat-total"]').text()).toContain('3')
    await wrapper.get('[data-testid="back-to-history"]').trigger('click')
    await nextTick()
    await wrapper.get('[data-testid="open-report-r-1"]').trigger('click')
    await nextTick()
    expect(wrapper.text()).toContain('当前 3 条结果')
  })

  it('uses the Web defect construction path for Web results', async () => {
    const webReport = makeReport()
    webReport.results = [{
      ...webReport.results[1],
      caseId: 'WEB-0002',
      sourceCaseId: 'WEB-0002',
      method: 'WEB',
      name: 'Web 失败',
      path: 'https://web.example.test/login',
      pageName: '登录页',
      startUrl: 'https://web.example.test/login',
      steps: [{ id: 's1', name: '提交', action: 'click', targetType: 'locator', target: '#submit' }],
      stepEvidence: [{ actionId: 'a1', actionType: 'click', before: { pageUrl: 'https://web.example.test/login', pageKey: 'login', pageFingerprint: 'before' }, evidenceId: 'e1', execution: { status: 'failed', durationMs: 12 }, after: { pageUrl: 'https://web.example.test/login', pageKey: 'login', pageFingerprint: 'after' } }]
    } as never]
    webReport.summary = { total: 1, passed: 0, failed: 1, error: 0 }
    localStorage.setItem(AUTOMATION_REPORT_STORAGE_KEY, JSON.stringify([webReport]))
    const { Component, defectsNavigate } = await loadView()
    const wrapper = mountView(Component)
    await nextTick()
    await wrapper.get('[data-testid="open-report-r-1"]').trigger('click')
    await nextTick()
    await wrapper.get('[data-testid="defect-WEB-0002"]').trigger('click')
    await nextTick()
    const drafts = JSON.parse(localStorage.getItem('ai-test-platform.defect-drafts.v1') || '[]') as Array<{ description: string; summary: string; faultCategory: string }>
    expect(drafts).toHaveLength(1)
    // Web 构造路径的唯一标识：故障分类为 Web自动化失败、摘要带页面名、描述带页面与访问地址。
    expect(drafts[0].faultCategory).toBe('Web自动化失败')
    expect(drafts[0].summary).toContain('登录页')
    expect(drafts[0].description).toContain('页面: 登录页')
    expect(drafts[0].description).toContain('访问地址: https://web.example.test/login')
    expect(defectsNavigate).toHaveBeenCalledTimes(1)
  })

  it('steps through neighbouring results inside the current filter and restores the 查看详情 opener', async () => {
    localStorage.setItem(AUTOMATION_REPORT_STORAGE_KEY, JSON.stringify([makeReport()]))
    const { Component } = await loadView()
    const wrapper = mountView(Component)
    await nextTick()
    await wrapper.get('[data-testid="open-report-r-1"]').trigger('click')
    await nextTick()
    await wrapper.get('[data-testid="result-filter-abnormal"]').trigger('click')
    await nextTick()
    await wrapper.get('[data-testid="result-open-API-0002"]').trigger('click')
    await nextTick()
    expect(wrapper.text()).toContain('1 / 2')
    expect(wrapper.get('[data-testid="previous-result"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="next-result"]').trigger('click')
    await nextTick()
    expect(wrapper.text()).toContain('错误接口')
    expect(wrapper.text()).toContain('2 / 2')
    await wrapper.get('[data-testid="back-to-report"]').trigger('click')
    await nextTick()
    await new Promise((resolve) => setTimeout(resolve, 0))
    // 返回后仍是「失败与错误」筛选,且焦点回到实际发起的那条「查看详情」按钮
    expect(wrapper.get('[data-testid="result-filter-abnormal"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.text()).toContain('当前 2 条结果')
    expect(wrapper.find('[data-testid="result-open-API-0002"]').element).toBe(document.activeElement)
  })

  it('keeps the two scroll axes and page after returning from a result', async () => {
    localStorage.setItem(AUTOMATION_REPORT_STORAGE_KEY, JSON.stringify([makeReport()]))
    const { Component } = await loadView()
    const wrapper = mountView(Component)
    await nextTick()
    await wrapper.get('[data-testid="open-report-r-1"]').trigger('click')
    await nextTick()
    const scroll = wrapper.get('[data-testid="results-scroll"]').element as HTMLElement
    scroll.scrollTop = 96
    scroll.scrollLeft = 140
    await wrapper.get('[data-testid="result-details-API-0002"]').trigger('click')
    await nextTick()
    scroll.scrollTop = 0
    scroll.scrollLeft = 0
    await wrapper.get('[data-testid="back-to-report"]').trigger('click')
    await nextTick()
    await new Promise((resolve) => setTimeout(resolve, 0))
    const restored = wrapper.get('[data-testid="results-scroll"]').element as HTMLElement
    expect(restored.scrollTop).toBe(96)
    expect(restored.scrollLeft).toBe(140)
  })

  it('opens the selected report page when App requests the active run report', async () => {
    localStorage.setItem(AUTOMATION_REPORT_STORAGE_KEY, JSON.stringify([makeReport()]))
    const { Component, reports } = await loadView()
    const wrapper = mountView(Component)
    await nextTick()
    expect(wrapper.find('.report-history-page').exists()).toBe(true)
    reports.openActiveWebRunReport()
    await nextTick()
    await nextTick()
    expect(wrapper.find('.report-detail-page').exists()).toBe(true)
    expect(wrapper.get('[data-testid="report-stat-total"]').text()).toContain('3')
    // 请求只消费一次：重新挂载视图时回到历史列表,不会反复强制进入报告层。
    wrapper.unmount()
    const remounted = mountView(Component)
    await nextTick()
    expect(remounted.find('.report-history-page').exists()).toBe(true)
  })
})

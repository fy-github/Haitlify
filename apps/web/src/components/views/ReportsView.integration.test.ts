// @vitest-environment jsdom
// View-level integration test: mounts the real ReportsView with ElementPlus.
// useReports is a module-level singleton (shared with App.vue), so the project selection
// is driven by grabbing the singleton and setting its refs before mounting the view.
import { nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { AUTOMATION_REPORT_STORAGE_KEY, type SavedAutomationReport } from '../../domain/automationReportRepository'

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
  summary: { total: 1, passed: 1, failed: 0, error: 0 },
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
    }
  ]
})

interface ReportsState {
  selectedReportProject: { value: string }
  selectedReportId: { value?: string }
}

const loadView = async () => {
  vi.resetModules()
  const { useReports } = await import('../../ui/useReports')
  const reports = useReports() as unknown as ReportsState
  const { default: ReportsView } = await import('./ReportsView.vue')
  return {
    Component: ReportsView as unknown as { new (): unknown },
    reports
  }
}

const mountView = (Component: { new (): unknown }) =>
  mount(Component as never, { global: { plugins: [ElementPlus] } })

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
    expect(text).toContain('回归报告')
    expect(text).toContain('1 / 1 条')
    expect(text).toContain('总数 1')
    expect(text).toContain('通过 1')
    expect(text).toContain('筛选')
  })
})

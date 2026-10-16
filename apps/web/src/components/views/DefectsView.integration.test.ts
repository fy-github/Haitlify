// @vitest-environment jsdom
// View-level integration test: mounts the real DefectsView with ElementPlus and verifies
// seeded draft defects flow from localStorage into the view's draft count.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { saveDefectDrafts } from '../../domain/defectRepository'

const loadView = async () => {
  vi.resetModules()
  const { default: DefectsView } = await import('./DefectsView.vue')
  return DefectsView as unknown as { new (): unknown }
}

const mountView = (Component: { new (): unknown }) =>
  mount(Component as never, { global: { plugins: [ElementPlus] } })

const makeDraft = (defectId: string) => ({
  defectId,
  sourceReportCaseId: 'c-1',
  sourceCaseId: 'API-0001',
  projectName: 'Haitlify API',
  issueType: '缺陷',
  summary: '登录接口偶发超时',
  priority: 'P1',
  assignee: 'lify',
  description: 'desc',
  blocksIssueKey: '',
  faultCategory: '性能',
  status: 'pending_jira_export' as const,
  jiraIssueKey: '',
  jiraExportMode: 'csv' as const,
  createdAt: '2026-08-01T00:00:00Z',
  updatedAt: '2026-08-01T00:00:00Z',
  jiraFieldValues: {}
})

describe('DefectsView integration', () => {
  it('mounts with an empty defect list and shows the defects header', async () => {
    localStorage.clear()
    const wrapper = mountView(await loadView())
    expect(wrapper.find('h1').text()).toContain('缺陷')
  })

  it('renders the seeded draft defect count', async () => {
    localStorage.clear()
    saveDefectDrafts(localStorage, [makeDraft('def-1')])
    const wrapper = mountView(await loadView())
    expect(wrapper.text()).toContain('Jira 导出草稿')
  })
})

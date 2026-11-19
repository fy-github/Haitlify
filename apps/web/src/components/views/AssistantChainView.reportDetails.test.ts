// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import AssistantChainView from './AssistantChainView.vue'

const user = { id: 73, username: 'qa', displayName: 'QA', role: 'EDITOR', mustChangePassword: false }
const project = { id: 8, tenantId: 42, code: 'A', name: '项目 A' }
const draft = {
  draftId: 'draft-1', state: 'DRAFT', requirementDigest: 'a'.repeat(64), specDigest: 'b'.repeat(64),
  spec: { id: 'case-ui', name: 'UI 草稿', startUrl: 'https://example.com', steps: [] },
  confirmedBy: null, projectId: 8, actionCode: 'web.case.execute', targetRef: 'project:8/case-ui', targetRevision: 'rev-1'
}
const operation = (reportState = 'NONE') => ({
  operationId: 'op-1', executionState: 'SUCCEEDED', reportState, dispatchGeneration: 1,
  attempts: [{ attemptId: 'attempt-1', generation: 1, runnerRunId: 'runner-1', outcome: 'FAILED' }],
  resources: [{
    resourceId: 'res-1', resourceKind: 'BROWSER', cleanupState: 'ALLOCATED',
    cleanupExecutor: 'platform-cleanup-worker', deadlineAt: '2026-11-18T12:34:56Z', cleanedBy: null, cleanedAt: null
  }]
})
const baseBody = {
    executionState: 'SUCCEEDED',
    summary: { runTotal: 2, passed: 1, failed: 1, error: 0 },
    attempts: [{ generation: 1, outcome: 'FAILED' }],
    resources: [{ resourceId: 'res-1', cleanupState: 'ALLOCATED' }]
}

const report = {
  reportId: 'report-1', operationId: 'op-1', revision: 1,
  bodyJson: JSON.stringify(baseBody),
  createdAt: '2026-11-18T10:00:00Z'
}

const replaceReportBody = (body: Record<string, unknown>) => {
  report.bodyJson = JSON.stringify(body)
}

const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), {
  status, headers: { 'content-type': 'application/json' }
})

const buildFetch = () => vi.fn(async (url: string, init?: RequestInit) => {
  const method = init?.method ?? 'GET'
  if (url.includes('/api/projects')) return json({ items: [project] })
  if (url.endsWith('/drafts') && method === 'POST') return json(draft)
  if (url.endsWith('/confirm') && method === 'POST') return json({ ...draft, state: 'CONFIRMED', confirmedBy: 73 })
  if (url.endsWith('/tasks') && method === 'POST') return json({
    requestId: 'req-1', operationId: 'op-1', executionState: 'SUCCEEDED', runId: 'run-1',
    digest: 'c'.repeat(64), stepCount: 1, created: true
  }, 202)
  if (url.includes('/report/generate?') && method === 'POST') return json(report)
  if (url.includes('/report?') && method === 'GET') return json(report)
  if (url.includes('/operations/op-1?')) return json(operation(url.includes('report-state=accepted') ? 'ACCEPTED' : 'PENDING'))
  return json({})
})

describe('AssistantChainView report and resource details', () => {
  afterEach(() => {
    report.bodyJson = JSON.stringify(baseBody)
    vi.unstubAllGlobals()
  })

  it('shows structured report facts and resource cleanup ownership instead of only opaque IDs', async () => {
    const fetcher = buildFetch()
    vi.stubGlobal('fetch', fetcher)
    const wrapper = mount(AssistantChainView, { props: { user } })
    try {
      await flushPromises()
      await wrapper.get('[data-testid="assistant-project"]').setValue('8')
      const button = (label: string) => wrapper.findAll('button').find(item => item.text() === label)!
      await button('新建草稿').trigger('click'); await flushPromises()
      await button('确认').trigger('click'); await flushPromises()
      await button('执行').trigger('click'); await flushPromises()
      await button('生成报告').trigger('click'); await flushPromises()

      expect(wrapper.text()).toContain('报告摘要')
      const facts = wrapper.get('[data-testid="assistant-report-details"]')
      const pairs = facts.findAll('dt').map((dt, index) => [dt.text(), facts.findAll('dd')[index]?.text()])
      expect(pairs).toEqual([
        ['执行事实', 'SUCCEEDED'], ['总执行', '2'], ['通过', '1'], ['失败', '1'], ['错误', '0']
      ])
      expect(wrapper.text()).toContain('资源清理责任')
      expect(wrapper.text()).toContain('platform-cleanup-worker')
      expect(wrapper.text()).toContain('截止 2026-11-18 12:34:56')
      expect(wrapper.text()).toContain('BROWSER · ALLOCATED')
    } finally { wrapper.unmount() }
  })

  it('surfaces unknown generations in the report instead of letting totals read as all-pass', async () => {
    replaceReportBody({
      executionState: 'UNKNOWN',
      diagnostics: {
        stepTotals: { passed: 3, failed: 0, error: 0 },
        unknownAttempts: [{ generation: 2, runId: 'runner-2' }],
        failedAttempts: [],
        note: '有代次结果无法确认（UNKNOWN，见 unknownAttempts），不能读作全部通过'
      }
    })
    const fetcher = buildFetch()
    vi.stubGlobal('fetch', fetcher)
    const wrapper = mount(AssistantChainView, { props: { user } })
    try {
      await flushPromises()
      await wrapper.get('[data-testid="assistant-project"]').setValue('8')
      const button = (label: string) => wrapper.findAll('button').find(item => item.text() === label)!
      await button('新建草稿').trigger('click'); await flushPromises()
      await button('确认').trigger('click'); await flushPromises()
      await button('执行').trigger('click'); await flushPromises()
      await button('生成报告').trigger('click'); await flushPromises()

      const unresolved = wrapper.get('[data-report-unresolved="true"]')
      expect(unresolved.attributes('role')).toBe('alert')
      expect(unresolved.text()).toContain('第 2 代结果无法确认')
      expect(unresolved.text()).toContain('不能读作全部通过')
      expect(wrapper.text()).not.toContain('全部通过。')
    } finally { wrapper.unmount() }
  })

  it('takes the run total from attempt summaries while pass/fail come from step totals', async () => {
    replaceReportBody({
      executionState: 'SUCCEEDED',
      attempts: [{ generation: 1, outcome: 'FAILED', summary: { total: 3, passed: 2, failed: 1, error: 0 } }],
      diagnostics: { stepTotals: { passed: 2, failed: 1, error: 0 }, unknownAttempts: [], failedAttempts: [] },
      resources: [{ kind: 'BROWSER', cleanupState: 'ALLOCATED' }]
    })
    vi.stubGlobal('fetch', buildFetch())
    const wrapper = mount(AssistantChainView, { props: { user } })
    try {
      await flushPromises()
      await wrapper.get('[data-testid="assistant-project"]').setValue('8')
      const button = (label: string) => wrapper.findAll('button').find(item => item.text() === label)!
      await button('新建草稿').trigger('click'); await flushPromises()
      await button('确认').trigger('click'); await flushPromises()
      await button('执行').trigger('click'); await flushPromises()
      await button('生成报告').trigger('click'); await flushPromises()

      const facts = wrapper.get('[data-testid="assistant-report-details"]')
      const dds = facts.findAll('dd').map(item => item.text())
      expect(dds).toEqual(['SUCCEEDED', '3', '2', '1', '0'])
    } finally { wrapper.unmount() }
  })
})

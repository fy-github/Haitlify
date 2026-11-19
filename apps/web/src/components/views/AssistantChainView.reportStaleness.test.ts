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

const json = (value: unknown) => new Response(JSON.stringify(value), {
  status: 200, headers: { 'content-type': 'application/json' }
})

const ledger = { cleanupState: 'ALLOCATED', reportState: 'PENDING' }
const reportBody = () => JSON.stringify({
  executionState: 'SUCCEEDED',
  diagnostics: { stepTotals: { passed: 1, failed: 0, error: 0 } },
  resources: [{ kind: 'BROWSER', cleanupState: 'ALLOCATED' }]
})
const report = () => ({
  reportId: 'report-1', operationId: 'op-1', revision: 1, bodyJson: reportBody(), createdAt: '2026-11-18T10:00:00Z'
})

const buildFetch = () => vi.fn(async (url: string, init?: RequestInit) => {
  const method = init?.method ?? 'GET'
  if (url.includes('/api/projects')) return json({ items: [project] })
  if (url.endsWith('/drafts') && method === 'POST') return json(draft)
  if (url.endsWith('/confirm') && method === 'POST') return json({ ...draft, state: 'CONFIRMED', confirmedBy: 73 })
  if (url.endsWith('/tasks') && method === 'POST') return json({
    requestId: 'req-1', operationId: 'op-1', executionState: 'SUCCEEDED', runId: 'run-1',
    digest: 'c'.repeat(64), stepCount: 1, created: true
  })
  if (url.includes('/resources/') && url.includes('/clean')) {
    ledger.cleanupState = 'CLEANED'
    return json({ operationId: 'op-1', resourceId: 'res-1', cleanupState: 'CLEANED' })
  }
  if (url.includes('/operations/op-1?')) return json({
    operationId: 'op-1', executionState: 'SUCCEEDED', reportState: ledger.reportState, dispatchGeneration: 1,
    attempts: [], resources: [{
      resourceId: 'res-1', resourceKind: 'BROWSER', cleanupState: ledger.cleanupState,
      cleanupExecutor: 'platform-cleanup-worker', deadlineAt: '2026-11-18T12:34:56Z'
    }]
  })
  if (url.includes('/report/generate') || url.includes('/report?')) return json(report())
  return json({})
})

const setup = async () => {
  vi.stubGlobal('fetch', buildFetch())
  const wrapper = mount(AssistantChainView, { props: { user } })
  await flushPromises()
  await wrapper.get('[data-testid="assistant-project"]').setValue('8')
  const button = (label: string) => wrapper.findAll('button').find(item => item.text() === label)!
  await button('新建草稿').trigger('click'); await flushPromises()
  await button('确认').trigger('click'); await flushPromises()
  await button('执行').trigger('click'); await flushPromises()
  return { wrapper, button }
}

describe('AssistantChainView stale report guard', () => {
  afterEach(() => { ledger.cleanupState = 'ALLOCATED'; ledger.reportState = 'PENDING'; vi.unstubAllGlobals() })

  it('blocks accepting a report whose resource snapshot predates the cleanup', async () => {
    const { wrapper, button } = await setup()
    try {
      await button('生成报告').trigger('click'); await flushPromises()
      expect(button('接受报告').attributes('disabled')).toBeUndefined()

      await button('标记已清理').trigger('click'); await flushPromises()

      expect(wrapper.text()).toContain('CLEANED')
      expect(button('接受报告').attributes('disabled')).toBeDefined()
      expect(wrapper.get('[data-testid="report-stale-notice"]').text())
        .toContain('报告正文的资源快照已过期，请重新生成后再接受')
    } finally { wrapper.unmount() }
  })
})

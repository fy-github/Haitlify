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
const report = {
  reportId: 'report-1', operationId: 'op-1', revision: 1, bodyJson: '{}', createdAt: '2026-11-18T10:00:00Z'
}

const json = (value: unknown) => new Response(JSON.stringify(value), {
  status: 200, headers: { 'content-type': 'application/json' }
})

const state = { execution: 'SUCCEEDED', reportState: 'NONE' }
const buildFetch = () => vi.fn(async (url: string, init?: RequestInit) => {
  const method = init?.method ?? 'GET'
  if (url.includes('/api/projects')) return json({ items: [project] })
  if (url.endsWith('/drafts') && method === 'POST') return json(draft)
  if (url.endsWith('/confirm') && method === 'POST') return json({ ...draft, state: 'CONFIRMED', confirmedBy: 73 })
  if (url.endsWith('/tasks') && method === 'POST') return json({
    requestId: 'req-1', operationId: 'op-1', executionState: state.execution, runId: 'run-1',
    digest: 'c'.repeat(64), stepCount: 1, created: true
  })
  if (url.includes('/waiting-user') && method === 'GET') return json({
    operationId: 'op-1', executionState: 'WAITING_USER', reason: '需要本人完成 MFA',
    requestedAt: '2026-11-18T10:00:00Z', deadlineAt: '2026-11-18T10:30:00Z'
  })
  if (url.includes('/operations/op-1?')) return json({
    operationId: 'op-1', executionState: state.execution, reportState: state.reportState, dispatchGeneration: 1,
    attempts: [], resources: []
  })
  if (url.includes('/report')) return json(report)
  return json({})
})

const setup = async () => {
  const fetcher = buildFetch()
  vi.stubGlobal('fetch', fetcher)
  const wrapper = mount(AssistantChainView, { props: { user } })
  await flushPromises()
  await wrapper.get('[data-testid="assistant-project"]').setValue('8')
  const button = (label: string) => wrapper.findAll('button').find(item => item.text() === label)!
  await button('新建草稿').trigger('click'); await flushPromises()
  await button('确认').trigger('click'); await flushPromises()
  await button('执行').trigger('click'); await flushPromises()
  return { wrapper, button }
}

describe('AssistantChainView report action gates', () => {
  afterEach(() => { state.execution = 'SUCCEEDED'; state.reportState = 'NONE'; vi.unstubAllGlobals() })

  it('blocks report generation while the operation is waiting for the user', async () => {
    state.execution = 'WAITING_USER'
    const { wrapper, button } = await setup()
    try {
      expect(wrapper.find('[data-testid="assistant-waiting-user"]').exists()).toBe(true)
      expect(button('生成报告').attributes('disabled')).toBeDefined()
      // 等待面板本身也写着「等待中不能生成报告」；必须断言按钮旁的状态行，否则删掉等待分支仍会通过。
      expect(wrapper.get('[data-testid="report-generation-block"]').text())
        .toBe('等待中不能生成报告：补齐后需重新执行到终态')
    } finally { wrapper.unmount() }
  })

  it('blocks accepting a report that the ledger already records as accepted', async () => {
    const { wrapper, button } = await setup()
    try {
      await button('生成报告').trigger('click'); await flushPromises()
      expect(wrapper.find('[data-testid="assistant-report-details"]').exists()).toBe(true)
      expect(button('接受报告').attributes('disabled')).toBeUndefined()

      state.reportState = 'ACCEPTED'
      await button('刷新').trigger('click'); await flushPromises()

      expect(button('接受报告').attributes('disabled')).toBeDefined()
      expect(button('生成报告').attributes('disabled')).toBeDefined()
    } finally { wrapper.unmount() }
  })
})

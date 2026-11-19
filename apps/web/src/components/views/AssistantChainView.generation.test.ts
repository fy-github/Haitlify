// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import AssistantChainView from './AssistantChainView.vue'

const user = { id: 73, username: 'qa', displayName: 'QA', role: 'EDITOR', mustChangePassword: false }
const project = { id: 8, tenantId: 42, code: 'A', name: '项目 A' }

describe('AssistantChainView generation entry', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('submits normal requirement inputs with trusted project context, separately from manual JSON', async () => {
    const fetcher = vi.fn(async (url: string, init?: RequestInit) => new Response(JSON.stringify(
      url.includes('/api/projects') ? { items: [project] } : {
        id: 'generation-1', requestId: JSON.parse(String(init?.body)).requestId,
        projectId: 8, state: 'WAITING_WORKER', createdAt: '2026-10-22T00:00:00Z'
      }
    ), { status: url.includes('/api/projects') ? 200 : 202 }))
    vi.stubGlobal('fetch', fetcher)
    const wrapper = mount(AssistantChainView, { props: { user } })
    try {
      await flushPromises()
      const entry = wrapper.get('[data-testid="generation-entry"]')
      expect(entry.findAll('input, textarea')).toHaveLength(4)
      expect(entry.text()).not.toContain('JSON')
      expect(wrapper.text()).toContain('手工 JSON 调试')
      expect(entry.get('button[type="submit"]').attributes('disabled')).toBeDefined()
      await wrapper.get('[data-testid="assistant-project"]').setValue('8')
      await entry.get('[name="requirementRef"]').setValue('jira:QA-1')
      await entry.get('[name="requirementText"]').setValue(' 登录后验证订单列表\n保留原文 ')
      await entry.get('[name="targetUrl"]').setValue('https://example.com/orders')
      await entry.get('[name="targetRevision"]').setValue('release-42')
      await entry.get('form').trigger('submit')
      await flushPromises()
      const calls = fetcher.mock.calls.filter(([url]) => url.includes('/api/assistant/'))
      expect(calls).toHaveLength(1)
      expect(calls[0]![0]).toContain('/api/assistant/generations')
      expect(JSON.parse(String(calls[0]![1]?.body))).toEqual({
        projectId: 8, requestId: expect.any(String), requirementRef: 'jira:QA-1',
        requirementText: ' 登录后验证订单列表\n保留原文 ', targetUrl: 'https://example.com/orders', targetRevision: 'release-42'
      })
      expect(entry.text()).toContain('WAITING_WORKER')
      expect(entry.text()).toContain('待分配隔离执行单元')
      expect(entry.text()).toContain('未生成草稿')
      expect(entry.text()).not.toMatch(/生成成功|AI 成功/)
      expect(wrapper.findAll('button').some(button => button.text() === '新建草稿')).toBe(true)
    } finally { wrapper.unmount() }
  })
})

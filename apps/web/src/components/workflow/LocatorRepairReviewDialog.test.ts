// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import LocatorRepairReviewDialog from './LocatorRepairReviewDialog.vue'

const proposal = {
  proposalId: 'proposal-1',
  entryId: 'locator-1',
  oldLocator: 'button=查询',
  newLocator: 'role=button[name="查询"]',
  confidence: 90,
  status: 'pending_review',
  createdAt: '2026-10-08T10:00:00Z'
}

describe('LocatorRepairReviewDialog', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('renders pending proposals and accepts one after confirmation', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const calls: Array<{ url: string; method?: string }> = []
    vi.stubGlobal('fetch', vi.fn(async (input: unknown, init?: RequestInit) => {
      calls.push({ url: String(input), method: init?.method })
      if (String(input).includes('/accept')) {
        return new Response(JSON.stringify({ proposalId: 'proposal-1', status: 'accepted' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        })
      }
      return new Response(JSON.stringify({ items: [proposal] }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    }))

    const wrapper = mount(LocatorRepairReviewDialog, {
      props: { projectId: 7, visible: true },
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    expect(wrapper.get('[data-testid="locator-repair-count"]').text()).toContain('待审核 1 条')
    expect(wrapper.text()).toContain('button=查询')
    expect(wrapper.text()).toContain('role=button[name="查询"]')

    await wrapper.get('[data-testid="locator-repair-accept-proposal-1"]').trigger('click')
    await flushPromises()

    const accept = calls.find((call) => call.url.includes('/accept'))
    expect(accept?.method).toBe('POST')
    expect(accept?.url).toContain('/repair-proposals/proposal-1/accept')
  })

  it('rejects a proposal through the reject endpoint and refreshes the list', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const calls: Array<{ url: string; method?: string }> = []
    vi.stubGlobal('fetch', vi.fn(async (input: unknown, init?: RequestInit) => {
      calls.push({ url: String(input), method: init?.method })
      if (String(input).includes('/reject')) {
        return new Response(JSON.stringify({ proposalId: 'proposal-1', status: 'rejected' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' }
        })
      }
      return new Response(JSON.stringify({ items: [proposal] }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      })
    }))

    const wrapper = mount(LocatorRepairReviewDialog, {
      props: { projectId: 7, visible: true },
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()
    await wrapper.get('[data-testid="locator-repair-reject-proposal-1"]').trigger('click')
    await flushPromises()

    expect(calls.some((call) => call.url.includes('/reject') && call.method === 'POST')).toBe(true)
    expect(calls.filter((call) => !call.method || call.method === 'GET').length).toBeGreaterThanOrEqual(2)
  })

  it('shows the empty state when there is nothing to review', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ items: [] }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' }
    })))

    const wrapper = mount(LocatorRepairReviewDialog, {
      props: { projectId: 7, visible: true },
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="locator-repair-empty"]').exists()).toBe(true)
  })
})

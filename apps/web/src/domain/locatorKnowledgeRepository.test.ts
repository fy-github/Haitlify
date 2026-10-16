import { describe, expect, it, vi } from 'vitest'
import {
  acceptLocatorRepairProposal,
  listLocatorRepairProposals,
  rejectLocatorRepairProposal
} from './locatorKnowledgeRepository'

const jsonResponse = (body: unknown, init: ResponseInit = {}) =>
  Promise.resolve(new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init
  }))

describe('locatorKnowledgeRepository', () => {
  it('lists repair proposals for a project with an optional status filter', async () => {
    const fetchImpl = vi.fn<(input: string, init?: RequestInit) => Promise<Response>>(async () => jsonResponse({
      items: [{
        proposalId: 'proposal-1',
        entryId: 'locator-1',
        oldLocator: 'button=查询',
        newLocator: 'role=button[name="查询"]',
        confidence: 90,
        status: 'pending_review',
        createdAt: '2026-10-08T10:00:00Z'
      }]
    }))

    const proposals = await listLocatorRepairProposals(7, 'pending_review', { fetchImpl })

    expect(proposals).toHaveLength(1)
    expect(proposals[0].proposalId).toBe('proposal-1')
    const [url] = fetchImpl.mock.calls[0]
    const parsed = new URL(String(url), 'http://test')
    expect(parsed.pathname).toBe('/api/ai/agent-assets/locator-knowledge/repair-proposals')
    expect(parsed.searchParams.get('projectId')).toBe('7')
    expect(parsed.searchParams.get('status')).toBe('pending_review')
  })

  it('accepts and rejects a proposal through POST endpoints with encoded ids', async () => {
    const fetchImpl = vi.fn<(input: string, init?: RequestInit) => Promise<Response>>(
      async () => jsonResponse({ proposalId: 'proposal/1', status: 'accepted' })
    )

    await acceptLocatorRepairProposal('proposal/1', { fetchImpl })
    expect(fetchImpl.mock.calls[0][0]).toContain('/repair-proposals/proposal%2F1/accept')
    expect(fetchImpl.mock.calls[0][1]?.method).toBe('POST')

    await rejectLocatorRepairProposal('proposal/1', { fetchImpl })
    expect(fetchImpl.mock.calls[1][0]).toContain('/repair-proposals/proposal%2F1/reject')
    expect(fetchImpl.mock.calls[1][1]?.method).toBe('POST')
  })

  it('returns an empty list when the payload omits items', async () => {
    const fetchImpl = vi.fn<(input: string, init?: RequestInit) => Promise<Response>>(async () => jsonResponse({}))
    await expect(listLocatorRepairProposals(1, undefined, { fetchImpl })).resolves.toEqual([])
  })
})

import { describe, expect, it, vi } from 'vitest'
import { getGenerationReceipt, submitGeneration } from './generationRepository'

const receipt = {
  id: 'generation-1',
  requestId: 'request-1',
  projectId: 8,
  state: 'WAITING_WORKER',
  createdAt: '2026-10-22T00:00:00Z'
}

const payload = {
  requestId: 'request-1',
  projectId: 8,
  requirementRef: 'jira:QA-1',
  requirementText: '登录后验证订单列表',
  targetUrl: 'https://example.com/orders',
  targetRevision: 'release-42'
}

const successfulFetch = (body: unknown = receipt) =>
  vi.fn(async () => new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'content-type': 'application/json' }
  }))

describe('generation admission repository', () => {
  it('posts only the strict admission payload and returns the receipt', async () => {
    const fetchImpl = successfulFetch()

    const result = await submitGeneration({ fetchImpl, baseUrl: 'http://127.0.0.1:38082' }, payload)

    const [url, init] = fetchImpl.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('http://127.0.0.1:38082/api/assistant/generations')
    expect(init.method).toBe('POST')
    expect(init.headers).toEqual({ 'Content-Type': 'application/json' })
    expect(JSON.parse(String(init.body))).toEqual(payload)
    expect(result).toEqual(receipt)
  })

  it('never serializes caller identity or unrelated fields, and forwards cancellation', async () => {
    const fetchImpl = successfulFetch()
    const controller = new AbortController()
    const supplied = { ...payload, actorId: 73, tenantId: 42, spec: { forged: true } }
    await submitGeneration({ fetchImpl }, supplied, controller.signal)
    const [, init] = fetchImpl.mock.calls[0] as unknown as [string, RequestInit]
    expect(JSON.parse(String(init.body))).toEqual(payload)
    expect(init.signal).toBe(controller.signal)
  })

  it.each([
    null, {}, { ...receipt, id: '' }, { ...receipt, requestId: 'different' },
    { ...receipt, projectId: 9 }, { ...receipt, state: null }, { ...receipt, createdAt: '' }
  ])('rejects invalid or mismatched receipts: %j', async body => {
    await expect(submitGeneration({ fetchImpl: successfulFetch(body) }, payload)).rejects.toThrow('受理回执无效')
  })

  it('reads a generation receipt through the authenticated GET endpoint', async () => {
    const fetchImpl = successfulFetch()

    const result = await getGenerationReceipt({ fetchImpl, baseUrl: 'http://127.0.0.1:38082' }, 'generation-1')

    const [url, init] = fetchImpl.mock.calls[0] as unknown as [string, RequestInit | undefined]
    expect(url).toBe('http://127.0.0.1:38082/api/assistant/generations/generation-1')
    expect(init?.method ?? 'GET').toBe('GET')
    expect(result.state).toBe('WAITING_WORKER')
  })

  it('rejects a GET receipt belonging to another generation', async () => {
    await expect(getGenerationReceipt({ fetchImpl: successfulFetch() }, 'other-generation')).rejects.toThrow('受理回执无效')
  })

  it('surfaces a failed admission instead of inventing a receipt', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({ message: 'invalid' }), {
      status: 400,
      headers: { 'content-type': 'application/json' }
    }))

    await expect(submitGeneration({ fetchImpl }, payload)).rejects.toThrow('400')
  })
})

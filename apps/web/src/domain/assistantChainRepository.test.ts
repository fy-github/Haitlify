import { describe, expect, it, vi } from 'vitest'
import {
  cancelWaitingUser,
  describeWaitingUser,
  requireWaitingUser,
  resumeWaitingUser
} from './assistantChainRepository'

const waitingPayload = {
  operationId: 'op-1',
  executionState: 'WAITING_USER',
  reason: '需要本人完成 MFA',
  requestedAt: '2026-10-21T00:00:00Z',
  deadlineAt: '2026-10-21T00:30:00Z'
}

const fakeFetch = (body: unknown = waitingPayload) =>
  vi.fn(async () => new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } }))

describe('assistantChainRepository waiting-user transport', () => {
  it('posts the waiting request with reason and timeout', async () => {
    const fetchImpl = fakeFetch()

    const view = await requireWaitingUser({ fetchImpl, baseUrl: 'http://127.0.0.1:18080' }, 'op-1', 7,
      '需要本人完成 MFA', 30)

    const [url, init] = fetchImpl.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('http://127.0.0.1:18080/api/assistant/operations/op-1/waiting-user?tenantId=7')
    expect(init.method).toBe('POST')
    expect(JSON.parse(String(init.body))).toEqual({ reason: '需要本人完成 MFA', timeoutMinutes: 30 })
    expect(view.executionState).toBe('WAITING_USER')
  })

  it('reads the waiting state without mutating the operation', async () => {
    const fetchImpl = fakeFetch()

    await describeWaitingUser({ fetchImpl, baseUrl: 'http://127.0.0.1:18080' }, 'op-1', 7)

    const [url, init] = fetchImpl.mock.calls[0] as unknown as [string, RequestInit | undefined]
    expect(url).toBe('http://127.0.0.1:18080/api/assistant/operations/op-1/waiting-user?tenantId=7')
    expect(init?.method ?? 'GET').toBe('GET')
  })

  it('resumes and cancels through explicit endpoints', async () => {
    const resumeFetch = fakeFetch({ ...waitingPayload, executionState: 'PENDING', reason: null })
    await resumeWaitingUser({ fetchImpl: resumeFetch, baseUrl: 'http://127.0.0.1:18080' }, 'op-1', 7)
    expect((resumeFetch.mock.calls[0] as unknown as [string, RequestInit])[0])
      .toBe('http://127.0.0.1:18080/api/assistant/operations/op-1/waiting-user/resume?tenantId=7')

    const cancelFetch = fakeFetch({ ...waitingPayload, executionState: 'CANCELLED' })
    await cancelWaitingUser({ fetchImpl: cancelFetch, baseUrl: 'http://127.0.0.1:18080' }, 'op-1', 7, '放弃等待')
    const [url, init] = cancelFetch.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('http://127.0.0.1:18080/api/assistant/operations/op-1/waiting-user/cancel?tenantId=7')
    expect(JSON.parse(String(init.body))).toEqual({ reason: '放弃等待' })
  })

  it('surfaces backend rejections instead of returning a fake waiting state', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({ message: '只有 WAITING_USER 可以恢复' }), {
      status: 409,
      headers: { 'content-type': 'application/json' }
    }))

    await expect(resumeWaitingUser({ fetchImpl, baseUrl: 'http://127.0.0.1:18080' }, 'op-1', 7))
      .rejects.toThrow('409')
  })
})

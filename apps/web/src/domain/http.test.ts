// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { authedFetch, setUnauthorizedHandler } from './http'

const setCookie = (value: string): void => {
  Object.defineProperty(document, 'cookie', {
    configurable: true,
    writable: true,
    value
  })
}

describe('authedFetch', () => {
  beforeEach(() => {
    setCookie('')
    setUnauthorizedHandler(null)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('adds the decoded CSRF header to state-changing requests when the cookie is present', async () => {
    setCookie('XSRF-TOKEN=abc%2Bdef; other=1')
    const fetchMock = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await authedFetch('/api/foo', { method: 'POST', body: '{}' })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/foo')
    expect((init.headers as Headers).get('X-XSRF-TOKEN')).toBe('abc+def')
    expect(init.credentials).toBe('same-origin')
  })

  it('does not add the CSRF header to GET requests', async () => {
    setCookie('XSRF-TOKEN=abc123')
    const fetchMock = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await authedFetch('/api/foo')

    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect((init.headers as Headers).has('X-XSRF-TOKEN')).toBe(false)
  })

  it('merges caller-provided headers with the CSRF token', async () => {
    setCookie('XSRF-TOKEN=token-1')
    const fetchMock = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await authedFetch('/api/foo', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' }
    })

    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect((init.headers as Headers).get('Content-Type')).toBe('application/json')
    expect((init.headers as Headers).get('X-XSRF-TOKEN')).toBe('token-1')
  })

  it('fires the unauthorized handler on 401', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    const fetchMock = vi.fn().mockResolvedValue(new Response('{}', { status: 401 }))
    vi.stubGlobal('fetch', fetchMock)

    const response = await authedFetch('/api/foo')

    expect(response.status).toBe(401)
    expect(handler).toHaveBeenCalledTimes(1)
  })

  it('does not fire the unauthorized handler on other statuses', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    const fetchMock = vi.fn().mockResolvedValue(new Response('{}', { status: 500 }))
    vi.stubGlobal('fetch', fetchMock)

    await authedFetch('/api/foo')

    expect(handler).not.toHaveBeenCalled()
  })
})

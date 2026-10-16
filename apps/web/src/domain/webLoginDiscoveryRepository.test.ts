import { describe, expect, it, vi } from 'vitest'
import {
  confirmWebLoginDiscovery,
  createWebLoginDiscovery,
  getWebLoginDiscovery
} from './webLoginDiscoveryRepository'

const snapshot = {
  discoveryId: 'discovery-1',
  status: 'completed' as const,
  createdAt: '2026-07-15T00:00:00.000Z',
  pageUrl: 'https://example.com/login',
  screenshotUrl: 'http://127.0.0.1:9323/artifacts/login.png',
  candidates: {
    username: [{ id: 'username-1', role: 'username' as const, locator: 'css=#username', strategy: 'css', label: '账号', confidence: 98, reason: '账号语义匹配' }],
    password: [{ id: 'password-1', role: 'password' as const, locator: 'css=#password', strategy: 'css', label: '密码', confidence: 100, reason: 'type=password' }],
    submit: [{ id: 'submit-1', role: 'submit' as const, locator: 'role=button[name="登录"]', strategy: 'role', label: '登录', confidence: 96, reason: '登录语义匹配' }]
  },
  recommended: {
    username: { id: 'username-1', role: 'username' as const, locator: 'css=#username', strategy: 'css', label: '账号', confidence: 98, reason: '账号语义匹配' },
    password: { id: 'password-1', role: 'password' as const, locator: 'css=#password', strategy: 'css', label: '密码', confidence: 100, reason: 'type=password' },
    submit: { id: 'submit-1', role: 'submit' as const, locator: 'role=button[name="登录"]', strategy: 'role', label: '登录', confidence: 96, reason: '登录语义匹配' }
  },
  diagnostics: []
}

const jsonResponse = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), {
  status,
  headers: { 'Content-Type': 'application/json' }
}))

describe('webLoginDiscoveryRepository', () => {
  it('creates and queries a login discovery task without credentials', async () => {
    const fetchImpl = vi.fn((_input: string, _init?: RequestInit) => jsonResponse(snapshot, 202))
    await createWebLoginDiscovery({
      url: 'https://example.com/login',
      browser: 'chromium',
      viewport: '1440x900',
      headless: true
    }, { fetchImpl })

    const [url, init] = fetchImpl.mock.calls[0]
    const body = JSON.parse(String(init?.body))
    expect(new URL(String(url), 'http://test').pathname).toBe('/api/web-automation/login-discovery')
    expect(body).toMatchObject({ url: 'https://example.com/login', browser: 'chromium' })
    expect(JSON.stringify(body)).not.toContain('password')

    fetchImpl.mockImplementationOnce(() => jsonResponse(snapshot))
    expect((await getWebLoginDiscovery('discovery-1', { fetchImpl })).recommended.password?.locator).toBe('css=#password')
  })

  it('confirms exact candidates and returns executable locators', async () => {
    const fetchImpl = vi.fn((_input: string, _init?: RequestInit) => jsonResponse({
      ...snapshot,
      confirmed: {
        usernameLocator: 'css=#username',
        passwordLocator: 'css=#password',
        submitLocator: 'role=button[name="登录"]'
      }
    }))

    const result = await confirmWebLoginDiscovery('discovery-1', {
      usernameCandidateId: 'username-1',
      passwordCandidateId: 'password-1',
      submitCandidateId: 'submit-1'
    }, { fetchImpl })

    expect(result.confirmed?.submitLocator).toBe('role=button[name="登录"]')
    expect(String(fetchImpl.mock.calls[0][0])).toContain('/login-discovery/discovery-1/confirm')
  })
})

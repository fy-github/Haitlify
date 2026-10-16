import { describe, expect, it, vi } from 'vitest'
import {
  defaultJiraConfig,
  loadJiraConfigFromPlatform,
  saveJiraConfigToPlatform,
  testJiraConfigConnection
} from './jiraConfigRepository'

const jsonResponse = (body: unknown, init: ResponseInit = {}) =>
  Promise.resolve(new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init
  }))

describe('jiraConfigRepository', () => {
  it('loads and normalizes the Jira config, keeping the token blank', async () => {
    const fetchImpl = vi.fn((input: string) => jsonResponse({
      enabled: true,
      baseUrl: 'https://jira.example.test',
      authMode: 'service-account',
      username: 'fixture-user',
      credentialConfigured: true,
      maskedToken: '****oken',
      projectJql: { HAITLIFY_API: 'project = HAITLIFY order by created DESC' }
    }))

    const result = await loadJiraConfigFromPlatform({ fetchImpl })

    expect(String(fetchImpl.mock.calls[0]?.[0] || '')).toContain('/api/jira/config')
    expect(result).toEqual({
      enabled: true,
      baseUrl: 'https://jira.example.test',
      authMode: 'service-account',
      username: 'fixture-user',
      token: '',
      credentialConfigured: true,
      maskedToken: '****oken',
      projectJql: { HAITLIFY_API: 'project = HAITLIFY order by created DESC' },
      updatedAt: undefined
    })
  })

  it('saves the config with PUT and re-applies the masked response', async () => {
    const fetchImpl = vi.fn((input: string, init?: RequestInit) => {
      expect(init?.method).toBe('PUT')
      const body = JSON.parse(String(init?.body || '{}'))
      expect(body.enabled).toBe(true)
      expect(body.baseUrl).toBe('https://jira.example.com')
      expect(body.token).toBe('secret-token')
      expect(body.projectJql).toEqual({ DEMO: 'project = DEMO' })
      return jsonResponse({
        enabled: true,
        baseUrl: 'https://jira.example.com',
        authMode: 'service-account',
        username: 'fixture-user',
        credentialConfigured: true,
        maskedToken: '****oken',
        projectJql: { DEMO: 'project = DEMO' }
      })
    })

    const result = await saveJiraConfigToPlatform({
      ...defaultJiraConfig(),
      enabled: true,
      baseUrl: 'https://jira.example.com',
      username: 'fixture-user',
      token: 'secret-token',
      projectJql: { DEMO: 'project = DEMO' }
    }, { fetchImpl })

    expect(result.token).toBe('')
    expect(result.maskedToken).toBe('****oken')
  })

  it('tests the connection with POST and returns the result', async () => {
    const fetchImpl = vi.fn((input: string, init?: RequestInit) => {
      expect(init?.method).toBe('POST')
      expect(String(input)).toContain('/api/jira/config/test-connection')
      return jsonResponse({ success: true, message: '连接成功' })
    })

    const result = await testJiraConfigConnection({
      ...defaultJiraConfig(),
      baseUrl: 'https://jira.example.com',
      username: 'fixture-user',
      token: 'secret-token'
    }, { fetchImpl })

    expect(result).toEqual({ success: true, message: '连接成功' })
  })

  it('surfaces backend error messages when the request fails', async () => {
    const fetchImpl = vi.fn(() => Promise.resolve(new Response(
      JSON.stringify({ message: 'Jira 返回 HTTP 401' }),
      { status: 401, headers: { 'Content-Type': 'application/json' } }
    )))

    await expect(loadJiraConfigFromPlatform({ fetchImpl })).rejects.toThrow('Jira config request failed: 401 Jira 返回 HTTP 401')
  })
})

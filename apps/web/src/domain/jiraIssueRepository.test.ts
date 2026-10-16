import { describe, expect, it, vi } from 'vitest'
import { loadJiraIssues } from './jiraIssueRepository'

const jsonResponse = (body: unknown, init: ResponseInit = {}) =>
  Promise.resolve(new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init
  }))

describe('jiraIssueRepository', () => {
  it('uses same-origin api paths in browser contexts when no base url is configured', async () => {
    const originalWindow = globalThis.window
    Object.defineProperty(globalThis, 'window', {
      value: { location: { origin: 'http://localhost:5174' } },
      configurable: true
    })
    const fetchImpl = vi.fn((input: string) => jsonResponse({
      authMode: 'service-account',
      items: [],
      total: 0
    }))

    try {
      await loadJiraIssues({ projectName: 'Haitlify API' }, { fetchImpl })
      expect(String(fetchImpl.mock.calls[0]?.[0] || '')).toContain('/api/jira/issues?')
      expect(String(fetchImpl.mock.calls[0]?.[0] || '').startsWith('/api/jira/issues?')).toBe(true)
    } finally {
      if (originalWindow) {
        Object.defineProperty(globalThis, 'window', { value: originalWindow, configurable: true })
      } else {
        Reflect.deleteProperty(globalThis, 'window')
      }
    }
  })

  it('loads and normalizes Jira issues from the backend proxy', async () => {
    const fetchImpl = vi.fn((input: string) => jsonResponse({
      authMode: 'service-account',
      items: [
        {
          id: '10001',
          projectName: 'Haitlify API',
          issueKey: 'HAITLIFY-12',
          summary: '项目树接口查询超时',
          status: '处理中',
          priority: 'High',
          assignee: '张三',
          issueType: '故障',
          updatedAt: '2026-06-16T15:00:00.000Z'
        }
      ],
      total: 1
    }))

    const result = await loadJiraIssues({
      projectName: 'Haitlify API'
    }, {
      fetchImpl
    })

    expect(fetchImpl).toHaveBeenCalledTimes(1)
    expect(result).toEqual({
      authMode: 'service-account',
      items: [
        {
          id: '10001',
          projectName: 'Haitlify API',
          issueKey: 'HAITLIFY-12',
          summary: '项目树接口查询超时',
          status: '处理中',
          priority: 'High',
          assignee: '张三',
          issueType: '故障',
          updatedAt: '2026-06-16T15:00:00.000Z'
        }
      ],
      total: 1
    })
  })

  it('passes keyword and pagination params to the backend proxy', async () => {
    const fetchImpl = vi.fn((input: string) => jsonResponse({
      authMode: 'service-account',
      items: [],
      total: 0
    }))

    await loadJiraIssues({
      projectName: 'Haitlify API',
      keyword: '超时',
      startAt: 20,
      maxResults: 25
    }, {
      fetchImpl
    })

    expect(fetchImpl).toHaveBeenCalledTimes(1)
    const firstCall = fetchImpl.mock.calls[fetchImpl.mock.calls.length - 1]
    const requestUrl = String(firstCall?.[0] || '')
    expect(requestUrl).toContain('projectName=Haitlify+API')
    expect(requestUrl).toContain('keyword=%E8%B6%85%E6%97%B6')
    expect(requestUrl).toContain('startAt=20')
    expect(requestUrl).toContain('maxResults=25')
    expect(requestUrl).toContain('authMode=service-account')
  })
})

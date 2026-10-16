import type { JiraIssueSource } from './defectView'
import { joinBackendPath } from './backendBaseUrl'
import { authedFetch } from './http'

type FetchLike = (input: string, init?: RequestInit) => Promise<Response>

export interface JiraIssueRepositoryOptions {
  baseUrl?: string
  fetchImpl?: FetchLike
}

export interface JiraIssueQueryOptions {
  projectName: string
  keyword?: string
  startAt?: number
  maxResults?: number
  authMode?: 'service-account' | 'user-session'
}

export interface JiraIssueRepositoryResult {
  authMode: 'service-account' | 'user-session'
  items: JiraIssueSource[]
  total: number
}

export const loadJiraIssues = async (
  query: JiraIssueQueryOptions,
  options: JiraIssueRepositoryOptions = {}
): Promise<JiraIssueRepositoryResult> => {
  const params = new URLSearchParams({
    projectName: query.projectName,
    authMode: query.authMode || 'service-account',
    startAt: String(query.startAt ?? 0),
    maxResults: String(query.maxResults ?? 50)
  })

  if (query.keyword?.trim()) {
    params.set('keyword', query.keyword.trim())
  }

  const response = await fetcher(options)(`${joinBackendPath('/api/jira/issues', options.baseUrl)}?${params.toString()}`)
  if (!response.ok) {
    throw new Error(`Jira issue request failed: ${response.status}`)
  }

  const payload = await response.json() as {
    authMode: 'service-account' | 'user-session'
    items: Array<{
      id: string
      projectName: string
      issueKey: string
      summary: string
      status: string
      priority: string
      assignee: string
      issueType: string
      updatedAt: string
    }>
    total: number
  }

  return {
    authMode: payload.authMode,
    items: payload.items.map((item) => ({
      id: item.id,
      projectName: item.projectName,
      issueKey: item.issueKey,
      summary: item.summary,
      status: item.status,
      priority: item.priority,
      assignee: item.assignee,
      issueType: item.issueType,
      updatedAt: item.updatedAt
    })),
    total: payload.total
  }
}

const fetcher = (options: JiraIssueRepositoryOptions): FetchLike => {
  if (options.fetchImpl) return options.fetchImpl
  if (typeof fetch !== 'undefined') return authedFetch
  throw new Error('Fetch API is not available')
}

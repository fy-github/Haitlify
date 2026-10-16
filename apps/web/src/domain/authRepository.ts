import { joinBackendPath } from './backendBaseUrl'
import { authedFetch, type FetchLike } from './http'

export interface AuthUser {
  username: string
  displayName: string
  avatar?: string
  role: string
  mustChangePassword: boolean
}

export interface AuthRepositoryOptions {
  baseUrl?: string
  fetchImpl?: FetchLike
}

interface AuthErrorResponse {
  message?: string
}

export const login = async (
  username: string,
  password: string,
  options: AuthRepositoryOptions = {}
): Promise<AuthUser> =>
  requestJson<AuthUser>(options, '/api/auth/login', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ username, password })
  })

export const logout = async (options: AuthRepositoryOptions = {}): Promise<void> => {
  await requestJson<{ message?: string }>(options, '/api/auth/logout', { method: 'POST' })
}

// Returns null when the session is not authenticated (401) so the caller can show the login
// screen instead of surfacing an error. Throws for other non-OK responses.
export const fetchCurrentUser = async (options: AuthRepositoryOptions = {}): Promise<AuthUser | null> => {
  const response = await fetcher(options)(joinBackendPath('/api/auth/me', options.baseUrl))
  if (response.status === 401) return null
  if (!response.ok) {
    throw await toRequestError(response, 'Auth request failed')
  }
  return await response.json() as AuthUser
}

export const changePassword = async (
  currentPassword: string,
  newPassword: string,
  options: AuthRepositoryOptions = {}
): Promise<AuthUser> =>
  requestJson<AuthUser>(options, '/api/auth/password', {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify({ currentPassword, newPassword })
  })

export type PlatformRole = 'ADMIN' | 'EDITOR' | 'VIEWER'

export interface PlatformUserSummary {
  username: string
  displayName: string
  avatar?: string
  role: PlatformRole
  enabled: boolean
  mustChangePassword: boolean
  createdAt: string
}

export interface CreateUserRequest {
  username: string
  password: string
  displayName?: string
  avatar?: string
  role: PlatformRole
}

export interface UpdateUserRequest {
  displayName?: string
  avatar?: string
  role?: PlatformRole
  enabled?: boolean
  password?: string
}

export const listUsers = async (options: AuthRepositoryOptions = {}): Promise<PlatformUserSummary[]> =>
  requestJson<PlatformUserSummary[]>(options, '/api/users')

export const createUser = async (request: CreateUserRequest, options: AuthRepositoryOptions = {}): Promise<PlatformUserSummary> =>
  requestJson<PlatformUserSummary>(options, '/api/users', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(request)
  })

export const updateUser = async (
  username: string,
  request: UpdateUserRequest,
  options: AuthRepositoryOptions = {}
): Promise<PlatformUserSummary> =>
  requestJson<PlatformUserSummary>(options, `/api/users/${encodeURIComponent(username)}`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify(request)
  })

export const deleteUser = async (username: string, options: AuthRepositoryOptions = {}): Promise<void> => {
  await requestJson<{ message?: string }>(options, `/api/users/${encodeURIComponent(username)}`, {
    method: 'DELETE'
  })
}

const requestJson = async <T>(
  options: AuthRepositoryOptions,
  path: string,
  init?: RequestInit
): Promise<T> => {
  const response = await fetcher(options)(joinBackendPath(path, options.baseUrl), init)
  if (!response.ok) {
    throw await toRequestError(response, 'Auth request failed')
  }
  return await response.json() as T
}

const toRequestError = async (response: Response, prefix: string): Promise<Error> => {
  const payload = await response.json().catch(() => undefined) as AuthErrorResponse | undefined
  const suffix = payload?.message ? ` ${payload.message}` : ''
  return new Error(`${prefix}: ${response.status}${suffix}`)
}

const fetcher = (options: AuthRepositoryOptions): FetchLike => {
  if (options.fetchImpl) return options.fetchImpl
  if (typeof fetch !== 'undefined') return authedFetch
  throw new Error('Fetch API is not available')
}

const jsonHeaders = (): HeadersInit => ({
  'Content-Type': 'application/json'
})

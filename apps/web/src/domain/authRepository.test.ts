import { afterEach, describe, expect, it, vi } from 'vitest'
import { authedFetch, setUnauthorizedHandler } from './http'
import {
  changePassword,
  createUser,
  deleteUser,
  fetchCurrentUser,
  listUsers,
  login,
  logout,
  updateUser
} from './authRepository'

const jsonResponse = (body: unknown, init: ResponseInit = {}) =>
  Promise.resolve(new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init
  }))

const adminUser = {
  username: 'admin',
  displayName: '管理员',
  role: 'ADMIN',
  mustChangePassword: false
}

describe('authRepository', () => {
  afterEach(() => { setUnauthorizedHandler(null); vi.unstubAllGlobals() })

  it('invalidates old 401 callbacks before login without replacing the registered handler', async () => {
    let resolveOld!: (response: Response) => void
    const old = new Promise<Response>(yes => { resolveOld = yes })
    const fetchMock = vi.fn().mockImplementationOnce(() => old)
      .mockImplementation(async () => new Response(JSON.stringify({ message: '用户名或密码错误' }), { status: 401 }))
    vi.stubGlobal('fetch', fetchMock)
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    const oldRequest = authedFetch('/api/previous-account')
    await expect(login('new-account', 'wrong')).rejects.toThrow('用户名或密码错误')
    expect(handler).toHaveBeenCalledTimes(1)
    resolveOld(new Response('{}', { status: 401 }))
    expect((await oldRequest).status).toBe(401)
    expect(handler).toHaveBeenCalledTimes(1)
    await authedFetch('/api/current-account')
    expect(handler).toHaveBeenCalledTimes(2)
  })

  it('logs in with POST and returns the user', async () => {
    const fetchImpl = vi.fn((input: string, init?: RequestInit) => {
      expect(init?.method).toBe('POST')
      expect(String(input)).toContain('/api/auth/login')
      expect(JSON.parse(String(init?.body || '{}'))).toEqual({ username: 'admin', password: 'secret' })
      return jsonResponse(adminUser)
    })

    await expect(login('admin', 'secret', { fetchImpl })).resolves.toEqual(adminUser)
  })

  it('returns null for an unauthenticated current-user check', async () => {
    const fetchImpl = vi.fn(() => Promise.resolve(new Response(
      JSON.stringify({ message: '未登录' }),
      { status: 401, headers: { 'Content-Type': 'application/json' } }
    )))

    await expect(fetchCurrentUser({ fetchImpl })).resolves.toBeNull()
  })

  it('returns the current user when authenticated', async () => {
    const fetchImpl = vi.fn((input: string) => jsonResponse(adminUser))

    await expect(fetchCurrentUser({ fetchImpl })).resolves.toEqual(adminUser)
    expect(String(fetchImpl.mock.calls[0]?.[0] || '')).toContain('/api/auth/me')
  })

  it('throws on a non-401 failure during the current-user check', async () => {
    const fetchImpl = vi.fn(() => Promise.resolve(new Response(
      JSON.stringify({ message: '服务器错误' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    )))

    await expect(fetchCurrentUser({ fetchImpl })).rejects.toThrow('Auth request failed: 500 服务器错误')
  })

  it('invalidates pending callbacks as soon as logout starts, including a failed logout', async () => {
    let resolveOld!: (response: Response) => void
    let resolveLogout!: (response: Response) => void
    const old = new Promise<Response>(yes => { resolveOld = yes })
    const leaving = new Promise<Response>(yes => { resolveLogout = yes })
    vi.stubGlobal('fetch', vi.fn().mockImplementationOnce(() => old).mockImplementationOnce(() => leaving)
      .mockImplementation(async () => new Response('{}', { status: 401 })))
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    const oldRequest = authedFetch('/api/old')
    const logoutRequest = logout()
    resolveOld(new Response('{}', { status: 401 }))
    await oldRequest
    expect(handler).not.toHaveBeenCalled()
    resolveLogout(new Response('{}', { status: 503 }))
    await expect(logoutRequest).rejects.toThrow('503')
    await authedFetch('/api/current')
    expect(handler).toHaveBeenCalledTimes(1)
  })

  it('logs out with POST', async () => {
    const fetchImpl = vi.fn((input: string, init?: RequestInit) => {
      expect(init?.method).toBe('POST')
      expect(String(input)).toContain('/api/auth/logout')
      return jsonResponse({ message: '已退出' })
    })

    await expect(logout({ fetchImpl })).resolves.toBeUndefined()
  })

  it('changes the password with PUT and returns the updated user', async () => {
    const fetchImpl = vi.fn((input: string, init?: RequestInit) => {
      expect(init?.method).toBe('PUT')
      expect(String(input)).toContain('/api/auth/password')
      expect(JSON.parse(String(init?.body || '{}'))).toEqual({ currentPassword: 'old', newPassword: 'new' })
      return jsonResponse(adminUser)
    })

    await expect(changePassword('old', 'new', { fetchImpl })).resolves.toEqual(adminUser)
  })

  it('surfaces backend error messages on login failure', async () => {
    const fetchImpl = vi.fn(() => Promise.resolve(new Response(
      JSON.stringify({ message: '用户名或密码错误' }),
      { status: 401, headers: { 'Content-Type': 'application/json' } }
    )))

    await expect(login('admin', 'wrong', { fetchImpl })).rejects.toThrow('Auth request failed: 401 用户名或密码错误')
  })
})

describe('user management repository', () => {
  const userSummary = {
    username: 'qa.li',
    displayName: '小李',
    role: 'EDITOR',
    enabled: true,
    mustChangePassword: true,
    createdAt: '2026-08-20T00:00:00Z'
  }

  it('lists users with GET', async () => {
    const fetchImpl = vi.fn((input: string) => jsonResponse([userSummary]))

    await expect(listUsers({ fetchImpl })).resolves.toEqual([userSummary])
    expect(String(fetchImpl.mock.calls[0]?.[0] || '')).toContain('/api/users')
  })

  it('creates a user with POST', async () => {
    const fetchImpl = vi.fn((input: string, init?: RequestInit) => {
      expect(init?.method).toBe('POST')
      expect(String(input)).toContain('/api/users')
      expect(JSON.parse(String(init?.body || '{}'))).toEqual({
        username: 'qa.li', password: 'initial-pass-123', role: 'EDITOR'
      })
      return jsonResponse(userSummary)
    })

    await expect(createUser(
      { username: 'qa.li', password: 'initial-pass-123', role: 'EDITOR' },
      { fetchImpl }
    )).resolves.toEqual(userSummary)
  })

  it('updates a user role with PUT', async () => {
    const fetchImpl = vi.fn((input: string, init?: RequestInit) => {
      expect(init?.method).toBe('PUT')
      expect(String(input)).toContain('/api/users/qa.li')
      expect(JSON.parse(String(init?.body || '{}'))).toEqual({ role: 'VIEWER' })
      return jsonResponse({ ...userSummary, role: 'VIEWER' })
    })

    await expect(updateUser('qa.li', { role: 'VIEWER' }, { fetchImpl }))
      .resolves.toEqual({ ...userSummary, role: 'VIEWER' })
  })

  it('deletes a user with DELETE', async () => {
    const fetchImpl = vi.fn((input: string, init?: RequestInit) => {
      expect(init?.method).toBe('DELETE')
      expect(String(input)).toContain('/api/users/qa.li')
      return jsonResponse({ message: '已删除用户' })
    })

    await expect(deleteUser('qa.li', { fetchImpl })).resolves.toBeUndefined()
  })

  it('surfaces backend validation errors on create', async () => {
    const fetchImpl = vi.fn(() => Promise.resolve(new Response(
      JSON.stringify({ message: '用户名已存在' }),
      { status: 400, headers: { 'Content-Type': 'application/json' } }
    )))

    await expect(createUser(
      { username: 'admin', password: 'initial-pass-123', role: 'EDITOR' },
      { fetchImpl }
    )).rejects.toThrow('Auth request failed: 400 用户名已存在')
  })
})

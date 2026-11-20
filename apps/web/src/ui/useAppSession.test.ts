import { describe, expect, it, vi } from 'vitest'
import type { AuthUser } from '../domain/authRepository'
import { createAppSession } from './useAppSession'

const user = (id: number, mustChangePassword = false): AuthUser => ({
  id,
  username: `user-${id}`,
  displayName: `User ${id}`,
  role: 'EDITOR',
  mustChangePassword
})

const createWorkspace = () => {
  return {
    activate: vi.fn(),
    invalidate: vi.fn(),
    isCurrentAuth: () => true
  }
}

describe('useAppSession', () => {
  it('bootstraps an authenticated user and activates the owner workspace', async () => {
    const workspace = createWorkspace()
    const onReset = vi.fn()
    const onReady = vi.fn()
    const onMustChangePassword = vi.fn()
    const session = createAppSession({
      workspace,
      fetchCurrentUser: vi.fn(async () => user(7, true)),
      logout: vi.fn(async () => undefined),
      onReset,
      onReady,
      onMustChangePassword
    })

    await session.bootstrap()

    expect(session.authStatus.value).toBe('authenticated')
    expect(session.currentUser.value?.id).toBe(7)
    expect(workspace.activate).toHaveBeenCalledWith('user:7')
    expect(onReset).toHaveBeenCalledTimes(1)
    expect(onReady).toHaveBeenCalledWith(user(7, true))
    expect(onMustChangePassword).toHaveBeenCalledTimes(1)
  })

  it('fails closed when the user has no stable numeric identity', () => {
    const workspace = createWorkspace()
    const session = createAppSession({
      workspace,
      fetchCurrentUser: vi.fn(),
      logout: vi.fn(async () => undefined),
      onReset: vi.fn(),
      onReady: vi.fn()
    })

    session.onLoginSuccess({ ...user(8), id: 0 })

    expect(session.authStatus.value).toBe('authenticated')
    expect(workspace.activate).toHaveBeenCalledWith(undefined)
  })

  it('invalidates the session before logout and clears it after session expiry', async () => {
    const workspace = createWorkspace()
    const onReset = vi.fn()
    const onStop = vi.fn()
    const logout = vi.fn(async () => undefined)
    const session = createAppSession({
      workspace,
      fetchCurrentUser: vi.fn(async () => user(9)),
      logout,
      onReset,
      onReady: vi.fn(),
      onStop
    })

    session.onLoginSuccess(user(9))
    await session.handleLogout()

    expect(logout).toHaveBeenCalledTimes(1)
    expect(onStop).toHaveBeenCalled()
    expect(workspace.invalidate).toHaveBeenCalled()
    expect(session.currentUser.value).toBeNull()
    expect(session.authStatus.value).toBe('unauthenticated')
  })
})

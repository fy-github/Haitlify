import { ref } from 'vue'
import type { Ref } from 'vue'
import { advanceAuthSessionEpoch, getAuthSessionEpoch } from '../domain/http'
import type { AuthUser } from '../domain/authRepository'
import type { SessionWorkspacePort } from './appPorts'

export type AuthStatus = 'checking' | 'authenticated' | 'unauthenticated'

export interface AppSessionOptions {
  workspace: SessionWorkspacePort
  fetchCurrentUser: () => Promise<AuthUser | null>
  logout: () => Promise<unknown>
  authStatus?: Ref<AuthStatus>
  currentUser?: Ref<AuthUser | null>
  onBeforeReset?: (user: AuthUser | null) => void
  onReset?: () => void
  onReady?: (user: AuthUser) => void
  onMustChangePassword?: () => void
  onStop?: () => void
}

export interface AppSession {
  authStatus: Ref<AuthStatus>
  currentUser: Ref<AuthUser | null>
  readonly disposed: Readonly<Ref<boolean>>
  isCurrent(epoch: number): boolean
  invalidate(): void
  onLoginSuccess(user: AuthUser): void
  handleSessionExpired(): void
  bootstrap(): Promise<void>
  handleLogout(): Promise<void>
  dispose(): void
}

export const createAppSession = (options: AppSessionOptions): AppSession => {
  const authStatus = options.authStatus ?? ref<AuthStatus>('checking')
  const currentUser = options.currentUser ?? ref<AuthUser | null>(null)
  const disposed = ref(false)

  const isCurrent = (epoch: number): boolean =>
    !disposed.value && options.workspace.isCurrentAuth(epoch)

  const invalidate = (): void => {
    advanceAuthSessionEpoch()
    options.onBeforeReset?.(currentUser.value)
    options.workspace.invalidate()
    options.onReset?.()
  }

  const onLoginSuccess = (user: AuthUser): void => {
    if (disposed.value) return
    invalidate()
    currentUser.value = user
    const owner = typeof user.id === 'number' && Number.isSafeInteger(user.id) && user.id > 0
      ? `user:${user.id}`
      : undefined
    options.workspace.activate(owner)
    authStatus.value = 'authenticated'
    options.onReady?.(user)
    if (user.mustChangePassword) options.onMustChangePassword?.()
  }

  const handleSessionExpired = (): void => {
    if (disposed.value || authStatus.value === 'unauthenticated') return
    invalidate()
    options.onStop?.()
    currentUser.value = null
    authStatus.value = 'unauthenticated'
  }

  const bootstrap = async (): Promise<void> => {
    const epoch = getAuthSessionEpoch()
    try {
      const user = await options.fetchCurrentUser()
      if (!isCurrent(epoch)) return
      if (user) onLoginSuccess(user)
      else authStatus.value = 'unauthenticated'
    } catch {
      if (isCurrent(epoch)) authStatus.value = 'unauthenticated'
    }
  }

  const handleLogout = async (): Promise<void> => {
    if (disposed.value) return
    invalidate()
    options.onStop?.()
    const pending = options.logout()
    const epoch = getAuthSessionEpoch()
    try {
      await pending
    } catch {
      // Logout failures still clear the session that initiated the operation.
    }
    if (isCurrent(epoch)) handleSessionExpired()
  }

  const dispose = (): void => {
    if (disposed.value) return
    disposed.value = true
    invalidate()
    options.onStop?.()
  }

  return {
    authStatus,
    currentUser,
    disposed,
    isCurrent,
    invalidate,
    onLoginSuccess,
    handleSessionExpired,
    bootstrap,
    handleLogout,
    dispose
  }
}

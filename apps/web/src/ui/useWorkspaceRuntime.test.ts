// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createWorkspaceStorage } from './workspaceStorage'
import { createWorkspaceRuntime } from './useWorkspaceRuntime'

afterEach(() => {
  vi.restoreAllMocks()
})

describe('useWorkspaceRuntime', () => {
  it('invalidates older operations for the same resource and rejects unauthenticated writes', () => {
    let authenticated = true
    let disposed = false
    const runtime = createWorkspaceRuntime({
      storage: createWorkspaceStorage(localStorage),
      sessionStorage: createWorkspaceStorage(sessionStorage),
      isAuthenticated: () => authenticated,
      isDisposed: () => disposed
    })

    runtime.activate('user:1')
    const first = runtime.begin('cases')
    const second = runtime.begin('cases')
    const other = runtime.begin('reports')

    expect(first()).toBe(false)
    expect(second()).toBe(true)
    expect(other()).toBe(true)

    authenticated = false
    expect(second()).toBe(false)
    expect(other()).toBe(false)

    authenticated = true
    disposed = true
    expect(runtime.begin('cases')()).toBe(false)
  })

  it('isolates storage by owner and invalidates captured storage after logout', () => {
    const backing = window.localStorage
    let authenticated = true
    const runtime = createWorkspaceRuntime({
      storage: createWorkspaceStorage(backing),
      sessionStorage: createWorkspaceStorage(window.sessionStorage),
      isAuthenticated: () => authenticated
    })

    runtime.activate('user:1')
    runtime.storage.storage.setItem('cases', 'one')
    expect(backing.getItem('ai-test-platform.workspace.user%3A1.cases')).toBe('one')

    runtime.invalidate()
    authenticated = false
    expect(runtime.storage.storage.getItem('cases')).toBeNull()
    expect(backing.getItem('ai-test-platform.workspace.user%3A1.cases')).toBe('one')
  })

  it('rejects a guarded fetch after the operation loses authority', async () => {
    let authenticated = true
    const fetcher = vi.fn(async () => new Response('{}', { status: 200 }))
    vi.stubGlobal('fetch', fetcher)
    const runtime = createWorkspaceRuntime({
      storage: createWorkspaceStorage(localStorage),
      sessionStorage: createWorkspaceStorage(sessionStorage),
      isAuthenticated: () => authenticated,
      fetchImpl: fetcher
    })
    runtime.activate('user:1')
    const isCurrent = runtime.begin('workspace')

    authenticated = false
    await expect(runtime.fetch(isCurrent)('/api/projects')).rejects.toThrow('Workspace operation expired')
    expect(fetcher).not.toHaveBeenCalled()

  })
})

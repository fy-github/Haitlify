import { computed, ref } from 'vue'
import type { ComputedRef, Ref } from 'vue'
import { authedFetch, getAuthSessionEpoch, type FetchLike } from '../domain/http'
import type { OperationLease } from './appPorts'
import { createWorkspaceStorage } from './workspaceStorage'

type WorkspaceStorage = ReturnType<typeof createWorkspaceStorage>

export interface WorkspaceRuntimeOptions {
  storage: WorkspaceStorage
  sessionStorage: WorkspaceStorage
  isAuthenticated: () => boolean
  isDisposed?: () => boolean
  fetchImpl?: FetchLike
  onWorkspaceBegin?: (templateLease: boolean) => void
}

export interface WorkspaceRuntime {
  readonly storage: WorkspaceStorage
  readonly sessionStorage: WorkspaceStorage
  readonly ownerId: Ref<string | undefined>
  readonly authenticated: ComputedRef<boolean>
  activate(identity?: string): void
  invalidate(): void
  begin(resource: string, templateLease?: boolean): OperationLease
  fetch(isCurrent: OperationLease): FetchLike
  isCurrentAuth(epoch: number): boolean
  hasOperation(resource: string): boolean
  operationId(resource: string): number | undefined
}

export const createWorkspaceRuntime = (options: WorkspaceRuntimeOptions): WorkspaceRuntime => {
  const ownerId = ref<string | undefined>()
  let generation = 0
  const authenticated = computed(options.isAuthenticated)
  const operations = new Map<string, number>()
  const fetchImpl = options.fetchImpl ?? authedFetch

  const isCurrentAuth = (epoch: number): boolean =>
    !options.isDisposed?.() && epoch === getAuthSessionEpoch()

  const activate = (identity?: string): void => {
    generation += 1
    ownerId.value = identity
    options.storage.activate(identity)
    options.sessionStorage.activate(identity)
  }

  const invalidate = (): void => {
    generation += 1
    ownerId.value = undefined
    options.storage.invalidate()
    options.sessionStorage.invalidate()
    operations.clear()
  }

  const begin = (resource: string, templateLease = false): OperationLease => {
    if (resource === 'workspace') options.onWorkspaceBegin?.(templateLease)
    const epoch = getAuthSessionEpoch()
    const capturedGeneration = generation
    const operation = (operations.get(resource) || 0) + 1
    operations.set(resource, operation)
    return () => isCurrentAuth(epoch)
      && generation === capturedGeneration
      && options.isAuthenticated()
      && operations.get(resource) === operation
  }

  const guardedFetch = (isCurrent: OperationLease): FetchLike => async (input, init) => {
    if (!isCurrent()) throw new Error('Workspace operation expired')
    const response = await fetchImpl(input, init)
    if (!isCurrent()) throw new Error('Workspace operation expired')
    return response
  }

  return {
    storage: options.storage,
    sessionStorage: options.sessionStorage,
    ownerId,
    authenticated,
    activate,
    invalidate,
    begin,
    fetch: guardedFetch,
    isCurrentAuth,
    hasOperation: (resource) => operations.has(resource),
    operationId: (resource) => operations.get(resource)
  }
}

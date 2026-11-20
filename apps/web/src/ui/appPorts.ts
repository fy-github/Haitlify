import type { ComputedRef, InjectionKey, Ref } from 'vue'
import type { NavKey } from '../domain/appNavigation'

export type OperationLease = () => boolean
export type NavHistoryMode = 'push' | 'replace' | 'skip'

export interface NavigationPort {
  currentNav: Readonly<Ref<NavKey>>
  navigate(next: NavKey, mode?: NavHistoryMode): void
}

export interface WorkspacePort {
  ownerId: Readonly<Ref<string | undefined>>
  authenticated: Readonly<Ref<boolean>>
  begin(resource: string, templateLease?: boolean): OperationLease
  fetch(input: string, init?: RequestInit): Promise<Response>
}

export interface NoticePort {
  info(message: string): void
  warning(message: string): void
  error(message: string): void
}

export interface ReadonlyViewState<T> {
  readonly value: T
}

export type ReadonlyComputed<T> = Readonly<ComputedRef<T>>

export const navigationPortKey: InjectionKey<NavigationPort> = Symbol('navigation-port')
export const workspacePortKey: InjectionKey<WorkspacePort> = Symbol('workspace-port')
export const noticePortKey: InjectionKey<NoticePort> = Symbol('notice-port')

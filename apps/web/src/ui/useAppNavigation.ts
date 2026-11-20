import { ref } from 'vue'
import type { Ref } from 'vue'
import {
  automationKindFromPath,
  automationPathFor,
  defaultNavKey,
  navKeyFromPath,
  navPathFor,
  type NavKey
} from '../domain/appNavigation'
import type { NavHistoryMode, OperationLease } from './appPorts'

export interface NavigationGuard {
  applies(from: NavKey, to: NavKey): boolean
  capture(): OperationLease
  confirm(): Promise<boolean>
  discard?(): void
}

export interface AppNavigationOptions {
  pathname?: string
  location?: Pick<Location, 'pathname' | 'href'>
  history?: Pick<History, 'pushState' | 'replaceState'>
  guards?: NavigationGuard[]
  beforeChange?(from: NavKey, to: NavKey): void
  afterPop?(): void
}

export interface AppNavigation {
  activeNav: Ref<NavKey>
  automationKind: Ref<'api' | 'web'>
  setActiveNav(next: NavKey, onChange?: () => void): void
  setAutomationKind(next: 'api' | 'web', onChange?: () => void): void
  navigateToNav(next: NavKey, mode?: NavHistoryMode, onNavigated?: () => void): void
  applyNavChange(next: NavKey, mode: NavHistoryMode): void
  syncNavWithLocation(mode?: 'replace' | 'skip', onNavigated?: () => void): void
  restoreLocationForActiveNav(): void
  handlePopState(): void
  writeWebMonitorQuery(mode: 'workbench' | 'monitor', runId?: string, historyMode?: 'push' | 'replace'): void
}

export const createAppNavigation = (options: AppNavigationOptions = {}): AppNavigation => {
  const pathname = options.pathname ?? (typeof window === 'undefined' ? '/' : window.location.pathname)
  const activeNav = ref<NavKey>(navKeyFromPath(pathname) || defaultNavKey)
  const automationKind = ref<'api' | 'web'>(automationKindFromPath(pathname))
  const location = options.location ?? (typeof window === 'undefined' ? undefined : window.location)
  const history = options.history ?? (typeof window === 'undefined' ? undefined : window.history)
  let intent = 0
  const pathFor = (nav: NavKey) => nav === 'automation' ? automationPathFor(automationKind.value) : navPathFor(nav)
  const restoreLocationForActiveNav = () => {
    const path = pathFor(activeNav.value)
    if (location && history && location.pathname !== path) history.pushState({}, '', path)
  }
  const applyNavChange = (target: NavKey, mode: NavHistoryMode) => {
    options.beforeChange?.(activeNav.value, target)
    activeNav.value = target
    const path = pathFor(target)
    if (location && history && mode !== 'skip' && location.pathname !== path) {
      history[mode === 'replace' ? 'replaceState' : 'pushState']({}, '', path)
    }
  }
  const navigateToNav = (next: NavKey, mode: NavHistoryMode = 'push', onNavigated?: () => void) => {
    const target = next === 'api' ? 'cases' : next
    const currentIntent = ++intent
    const apply = () => {
      if (currentIntent !== intent) return
      applyNavChange(target, mode)
      onNavigated?.()
    }
    const guard = options.guards?.find(candidate => candidate.applies(activeNav.value, target))
    if (!guard) { apply(); return }
    const current = guard.capture()
    void guard.confirm().then(confirmed => {
      if (!current() || currentIntent !== intent) return
      if (!confirmed) { restoreLocationForActiveNav(); return }
      guard.discard?.()
      apply()
    })
  }
  const syncNavWithLocation = (mode: 'replace' | 'skip' = 'replace', onNavigated?: () => void) => {
    if (!location) return
    const path = location.pathname.replace(/\/$/, '') || '/'
    const next = navKeyFromPath(path)
    if (next === 'automation') automationKind.value = automationKindFromPath(path)
    const target = pathFor(next)
    if (next === 'cases' || next === 'webCases') {
      navigateToNav(next, location.pathname === target ? 'skip' : 'replace', onNavigated)
    } else navigateToNav(next, path === target ? 'skip' : mode, onNavigated)
  }
  const handlePopState = () => {
    syncNavWithLocation('skip', options.afterPop)
  }
  const writeWebMonitorQuery = (mode: 'workbench' | 'monitor', runId = '', historyMode: 'push' | 'replace' = 'push') => {
    if (!location || !history) return
    const url = new URL(location.href)
    if (mode === 'monitor' && runId) {
      url.searchParams.set('view', 'monitor')
      url.searchParams.set('runId', runId)
    } else {
      url.searchParams.delete('view')
      url.searchParams.delete('runId')
    }
    history[historyMode === 'replace' ? 'replaceState' : 'pushState']({}, '', `${url.pathname}${url.search}`)
  }

  return {
    activeNav,
    automationKind,
    navigateToNav, applyNavChange, syncNavWithLocation, restoreLocationForActiveNav, handlePopState, writeWebMonitorQuery,
    setActiveNav: (next, onChange) => {
      activeNav.value = next
      onChange?.()
    },
    setAutomationKind: (next, onChange) => {
      automationKind.value = next
      onChange?.()
    }
  }
}

import { ref } from 'vue'
import type { Ref } from 'vue'
import {
  automationKindFromPath,
  defaultNavKey,
  navKeyFromPath,
  type NavKey
} from '../domain/appNavigation'

export interface AppNavigationOptions {
  pathname?: string
}

export interface AppNavigation {
  activeNav: Ref<NavKey>
  automationKind: Ref<'api' | 'web'>
  setActiveNav(next: NavKey, onChange?: () => void): void
  setAutomationKind(next: 'api' | 'web', onChange?: () => void): void
}

export const createAppNavigation = (options: AppNavigationOptions = {}): AppNavigation => {
  const pathname = options.pathname ?? (typeof window === 'undefined' ? '/' : window.location.pathname)
  const activeNav = ref<NavKey>(navKeyFromPath(pathname) || defaultNavKey)
  const automationKind = ref<'api' | 'web'>(automationKindFromPath(pathname))

  return {
    activeNav,
    automationKind,
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

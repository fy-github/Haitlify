import { onBeforeUnmount, onMounted } from 'vue'
import type { Ref } from 'vue'
import type { NavKey } from '../domain/appNavigation'
import { setUnauthorizedHandler } from '../domain/http'
import { useUnsavedChanges } from './useUnsavedChanges'

interface AppLifecycle {
  jiraDirty: Readonly<Ref<boolean>>
  environmentDirty: Readonly<Ref<boolean>>
  defectDirty: Readonly<Ref<boolean>>
  onSessionExpired(): void
  bootstrap(): Promise<void>
  onPopState(): void
  dispose(): void
}

export function installAppLifecycle(lifecycle: AppLifecycle) {
  useUnsavedChanges(lifecycle.jiraDirty)
  useUnsavedChanges(lifecycle.environmentDirty)
  useUnsavedChanges(lifecycle.defectDirty)

  onMounted(() => {
    setUnauthorizedHandler(lifecycle.onSessionExpired)
    window.addEventListener('popstate', lifecycle.onPopState)
    void lifecycle.bootstrap()
  })

  onBeforeUnmount(() => {
    lifecycle.dispose()
    window.removeEventListener('popstate', lifecycle.onPopState)
  })
}

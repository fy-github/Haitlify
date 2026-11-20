import { onScopeDispose, watch } from 'vue'
import type { Ref } from 'vue'

export function useUnsavedChanges(dirty: Readonly<Ref<boolean>>) {
  const onBeforeUnload = (event: BeforeUnloadEvent) => {
    if (!dirty.value) return
    event.preventDefault()
    event.returnValue = ''
  }
  const stop = watch(dirty, value => {
    if (value) window.addEventListener('beforeunload', onBeforeUnload)
    else window.removeEventListener('beforeunload', onBeforeUnload)
  }, { immediate: true })
  onScopeDispose(() => {
    stop()
    window.removeEventListener('beforeunload', onBeforeUnload)
  })
}

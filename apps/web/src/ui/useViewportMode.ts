import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

export type ViewportMode = 'wide' | 'standard' | 'compact' | 'narrow'

export const viewportModeForWidth = (width: number): ViewportMode => {
  if (width >= 1440) return 'wide'
  if (width >= 1180) return 'standard'
  if (width >= 900) return 'compact'
  return 'narrow'
}

export const initialSidebarCollapsed = (storedValue: string | null, width: number): boolean =>
  storedValue === null ? width < 1180 : storedValue === 'true'

export const useViewportMode = (): Ref<ViewportMode> => {
  const mode = ref<ViewportMode>(viewportModeForWidth(typeof window === 'undefined' ? 1440 : window.innerWidth))
  const update = () => { mode.value = viewportModeForWidth(window.innerWidth) }
  onMounted(() => window.addEventListener('resize', update, { passive: true }))
  onBeforeUnmount(() => window.removeEventListener('resize', update))
  return mode
}

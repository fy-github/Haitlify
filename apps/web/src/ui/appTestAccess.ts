import type { VueWrapper } from '@vue/test-utils'

export function getAppState(wrapper: VueWrapper): Record<string, any> {
  const state = (wrapper.vm.$.exposed as { testAccess?: Record<string, unknown> } | null)?.testAccess
  if (!state) throw new Error('App test state unavailable')
  return state
}

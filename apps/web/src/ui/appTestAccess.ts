import type { VueWrapper } from '@vue/test-utils'
import type { createAppWorkspace } from './appWorkspace'

type AppTestAccess = NonNullable<ReturnType<typeof createAppWorkspace>['testAccess']>

export function getAppState(wrapper: VueWrapper): AppTestAccess {
  const state = (wrapper.vm.$.exposed as { testAccess?: AppTestAccess } | null)?.testAccess
  if (!state) throw new Error('App test state unavailable')
  return state
}

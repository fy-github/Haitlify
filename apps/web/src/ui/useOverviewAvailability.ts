import { ref, watch } from 'vue'
import type { Ref } from 'vue'
import type { NavKey } from '../domain/appNavigation'
import type { OverviewStatus } from '../components/views/OverviewView.vue'
import { listBackendProjects, PlatformBackendRequestError } from '../domain/platformBackendRepository'
import type { OperationLease } from './appPorts'

interface OverviewAccess {
  authStatus: Readonly<Ref<string>>
  activeNav: Readonly<Ref<NavKey>>
  begin(resource: string): OperationLease
  hasOperation(resource: string): boolean
  fetch(current: OperationLease): (input: string, init?: RequestInit) => Promise<Response>
}

export function useOverviewAvailability(access: OverviewAccess) {
  const overviewStatus = ref<OverviewStatus>('unavailable')
  const overviewError = ref('')
  const overviewAvailabilityChecked = ref(false)

  const reset = () => {
    overviewStatus.value = 'unavailable'
    overviewError.value = ''
    overviewAvailabilityChecked.value = false
  }

  const checkOverviewAvailability = async () => {
    if (access.authStatus.value !== 'authenticated' || access.activeNav.value !== 'overview') return
    const operation = access.begin('overview')
    const current = () => operation() && access.activeNav.value === 'overview'
    overviewStatus.value = 'loading'
    overviewError.value = ''
    overviewAvailabilityChecked.value = false
    try {
      await listBackendProjects({ fetchImpl: access.fetch(current), throwOnError: true })
      if (!current()) return
      overviewAvailabilityChecked.value = true
      overviewStatus.value = 'unavailable'
    } catch (error) {
      if (!current()) return
      overviewStatus.value = error instanceof PlatformBackendRequestError && error.status === 403 ? 'forbidden' : 'error'
      overviewError.value = error instanceof PlatformBackendRequestError && [502, 503, 504].includes(error.status)
        ? '项目服务暂时不可用；尚未接入团队与质量统计，请稍后重新检查。'
        : '项目访问检查失败；尚未确认概览数据可用，请重试。'
    }
  }

  watch([access.activeNav, access.authStatus], () => {
    if (access.activeNav.value === 'overview' && access.authStatus.value === 'authenticated') {
      void checkOverviewAvailability()
    } else {
      if (access.hasOperation('overview')) access.begin('overview')
      reset()
    }
  }, { flush: 'post' })

  return { overviewStatus, overviewError, overviewAvailabilityChecked, checkOverviewAvailability, reset }
}

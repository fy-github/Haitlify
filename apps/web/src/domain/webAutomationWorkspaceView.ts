import type { EnvironmentValidationResult } from './executionEnvironmentRepository'
import type { SavedWebAutomationRunCase } from './webAutomationRepository'
import type { WebAutomationCapabilities } from './webAutomationRunRepository'

export type WebAutomationScopeSource = 'selected' | 'filtered'
export type WebPreflightStatus = 'ready' | 'warning' | 'blocked'
export type WebPreflightItemId = 'runner' | 'browser' | 'environment' | 'login' | 'teardown' | 'cases'
type WebPreflightRepairAction =
  | 'retry-capabilities'
  | 'edit-environment'
  | 'edit-login'
  | 'clear-filters'

export interface WebAutomationEffectiveScope {
  source: WebAutomationScopeSource
  cases: SavedWebAutomationRunCase[]
  caseIds: string[]
  count: number
  actionLabel: string
  canExecute: boolean
  blockingReason: string
}

export interface WebPreflightItem {
  id: WebPreflightItemId
  label: string
  status: WebPreflightStatus
  detail: string
  repairAction?: WebPreflightRepairAction
}

export interface WebAutomationPreflight {
  items: WebPreflightItem[]
  effectiveCount: number
  canExecute: boolean
  blockingReason: string
}

export interface WebAutomationPreflightInput {
  scope: WebAutomationEffectiveScope
  capabilities?: WebAutomationCapabilities
  browserOverride: string
  environmentName: string
  environmentValidation: EnvironmentValidationResult
  login: { enabled: boolean; valid: boolean; message?: string }
  logout: { enabled: boolean; valid: boolean; message?: string }
}

interface WebAutomationModuleCount {
  value: string
  label: string
  count: number
}

export const buildWebAutomationScope = (
  filteredCases: readonly SavedWebAutomationRunCase[],
  selectedIds: readonly string[]
): WebAutomationEffectiveScope => {
  const selectedIdSet = new Set(selectedIds)
  const visibleSelected = uniqueCases(filteredCases.filter((testCase) => selectedIdSet.has(testCase.id)))
  const source: WebAutomationScopeSource = visibleSelected.length > 0 ? 'selected' : 'filtered'
  const cases = source === 'selected' ? visibleSelected : uniqueCases(filteredCases)
  const count = cases.length
  const blockingReason = count === 0 ? '当前筛选结果中没有可执行用例' : ''

  return {
    source,
    cases,
    caseIds: cases.map((testCase) => testCase.id),
    count,
    actionLabel: source === 'selected' ? '执行选中' : '执行当前',
    canExecute: count > 0,
    blockingReason
  }
}

export const collectWebAutomationModuleCounts = (
  cases: readonly SavedWebAutomationRunCase[]
): WebAutomationModuleCount[] => {
  const counts = new Map<string, number>()
  for (const testCase of cases) {
    counts.set(testCase.module, (counts.get(testCase.module) || 0) + 1)
  }

  return [
    { value: 'ALL', label: '全部模块', count: cases.length },
    ...Array.from(counts, ([module, count]) => ({ value: module, label: module, count }))
  ]
}

export const buildWebAutomationPreflight = (
  input: WebAutomationPreflightInput
): WebAutomationPreflight => {
  const items: WebPreflightItem[] = [
    buildRunnerItem(input.capabilities),
    buildBrowserItem(input.scope, input.browserOverride, input.capabilities),
    buildEnvironmentItem(input.environmentName, input.environmentValidation),
    buildReadinessItem('login', input.login),
    buildReadinessItem('teardown', input.logout),
    buildCasesItem(input.scope)
  ]
  const firstBlocked = items.find((item) => item.status === 'blocked')

  return {
    items,
    effectiveCount: input.scope.count,
    canExecute: !firstBlocked,
    blockingReason: firstBlocked?.detail || ''
  }
}

const uniqueCases = (
  cases: readonly SavedWebAutomationRunCase[]
): SavedWebAutomationRunCase[] => {
  const seen = new Set<string>()
  return cases.filter((testCase) => {
    if (seen.has(testCase.id)) return false
    seen.add(testCase.id)
    return true
  })
}

const buildRunnerItem = (capabilities?: WebAutomationCapabilities): WebPreflightItem => {
  if (!capabilities) {
    return blockedItem('runner', 'Runner', 'Runner 未连接', 'retry-capabilities')
  }
  if (capabilities.status === 'DEGRADED') {
    return {
      id: 'runner',
      label: 'Runner',
      status: 'warning',
      detail: 'Runner 部分能力不可用',
      repairAction: 'retry-capabilities'
    }
  }
  return { id: 'runner', label: 'Runner', status: 'ready', detail: 'Runner 已连接' }
}

const buildBrowserItem = (
  scope: WebAutomationEffectiveScope,
  browserOverride: string,
  capabilities?: WebAutomationCapabilities
): WebPreflightItem => {
  if (!capabilities) {
    return blockedItem('browser', '浏览器', '浏览器能力未检测', 'retry-capabilities')
  }

  const requiredIds = browserOverride === 'follow-case'
    ? Array.from(new Set(scope.cases.map((testCase) => testCase.browser)))
    : [browserOverride]
  if (requiredIds.length === 0) {
    return {
      id: 'browser',
      label: '浏览器',
      status: 'warning',
      detail: '等待选择可执行用例'
    }
  }
  const required = requiredIds.map((id) => capabilities.browsers?.find((browser) => browser.id === id))
  const unavailableIndex = required.findIndex((browser) => !browser?.available)

  if (unavailableIndex >= 0) {
    const browser = required[unavailableIndex]
    const fallbackLabel = browser?.label || requiredIds[unavailableIndex]
    return blockedItem(
      'browser',
      '浏览器',
      browser?.recommendation || `${fallbackLabel} 不可用`,
      'retry-capabilities'
    )
  }

  const labels = required.map((browser) => browser?.label).filter(Boolean)
  return {
    id: 'browser',
    label: '浏览器',
    status: 'ready',
    detail: labels.length === 1 ? `${labels[0]} 已就绪` : `${labels.join('、')} 已就绪`
  }
}

const buildEnvironmentItem = (
  environmentName: string,
  validation: EnvironmentValidationResult
): WebPreflightItem => validation.valid
  ? { id: 'environment', label: '执行环境', status: 'ready', detail: `${environmentName} 已就绪` }
  : blockedItem('environment', '执行环境', validation.message || '执行环境无效', 'edit-environment')

const buildReadinessItem = (
  id: 'login' | 'teardown',
  readiness: { enabled: boolean; valid: boolean; message?: string }
): WebPreflightItem => {
  const login = id === 'login'
  const label = login ? '自动登录' : '自动清理'
  if (!readiness.valid) {
    return blockedItem(
      id,
      label,
      readiness.message || (login ? '登录配置不完整' : '退出步骤不完整'),
      login ? 'edit-login' : 'edit-environment'
    )
  }
  return {
    id,
    label,
    status: 'ready',
    detail: readiness.enabled
      ? login ? '登录配置已就绪' : '退出步骤已就绪'
      : login ? '无需自动登录' : '无需自动清理'
  }
}

const buildCasesItem = (scope: WebAutomationEffectiveScope): WebPreflightItem => {
  if (scope.count === 0) {
    return blockedItem('cases', '执行范围', scope.blockingReason, 'clear-filters')
  }
  const manualCaseCount = scope.cases.filter((testCase) =>
    testCase.steps.some((step) => step.action === 'manual')
  ).length
  if (manualCaseCount > 0) {
    return blockedItem('cases', '执行范围', `${manualCaseCount} 条不可执行，请先补全自动化步骤`)
  }
  return { id: 'cases', label: '执行范围', status: 'ready', detail: '执行范围已就绪' }
}

const blockedItem = (
  id: WebPreflightItemId,
  label: string,
  detail: string,
  repairAction?: WebPreflightRepairAction
): WebPreflightItem => ({ id, label, status: 'blocked', detail, ...(repairAction ? { repairAction } : {}) })

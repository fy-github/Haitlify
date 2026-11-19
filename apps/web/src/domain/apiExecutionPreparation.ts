import type { AutomationRunOptions } from './apiAutomation'
import type { SavedAutomationCase } from './automationRepository'
import type { ExecutionEnvironment } from './apiExecution'

export interface ApiLoopCountParse {
  valid: boolean
  value: number
  message?: string
}

/** 执行准备快照：启动前锁定用例、环境与运行参数，准备页后续修改不得改写已启动的这批任务。 */
export interface ApiRunSnapshot {
  cases: readonly SavedAutomationCase[]
  environment: ExecutionEnvironment
  /** 环境显示名（确认弹窗与报告命名用），不参与执行参数。 */
  environmentName?: string
  options: AutomationRunOptions
  scopeLabel: string
  frozenAt: string
}

export const parseApiLoopCount = (raw: string): ApiLoopCountParse => {
  const text = raw.trim()
  if (!/^\d+$/.test(text)) return { valid: false, value: NaN, message: '循环次数必须是正整数' }
  const value = Number(text)
  if (!Number.isInteger(value) || value < 1) return { valid: false, value: NaN, message: '循环次数必须是正整数' }
  return { valid: true, value }
}

export const resolveApiExecutionScopeLabel = (selected: number, filtered: number): string => {
  if (selected > 0) return `执行选中 ${selected} 条`
  if (filtered > 0) return `执行筛选 ${filtered} 条`
  return '无可执行用例'
}

const deepFreeze = <T>(value: T): T => {
  if (value && typeof value === 'object' && !Object.isFrozen(value)) {
    Object.freeze(value)
    Object.values(value as Record<string, unknown>).forEach((child) => deepFreeze(child))
  }
  return value
}

/** 结构拷贝：App 里的对象是 Vue 响应式代理，structuredClone 会抛 DataCloneError。 */
const clonePlain = <T>(value: T): T => {
  if (Array.isArray(value)) return value.map((item) => clonePlain(item)) as unknown as T
  if (value instanceof Date) return new Date(value.getTime()) as unknown as T
  if (value && typeof value === 'object') {
    const copy: Record<string, unknown> = {}
    Object.entries(value as Record<string, unknown>).forEach(([key, child]) => {
      copy[key] = clonePlain(child)
    })
    return copy as T
  }
  return value
}

export const buildApiRunSnapshot = (input: {
  cases: SavedAutomationCase[]
  environment: ExecutionEnvironment
  environmentName?: string
  options: AutomationRunOptions
  scopeLabel: string
  frozenAt: string
}): ApiRunSnapshot => {
  const cases = clonePlain(input.cases)
  const environment = clonePlain(input.environment)
  const options = { ...input.options }

  cases.forEach((testCase) => deepFreeze(testCase))
  deepFreeze(environment)
  deepFreeze(options)

  const snapshot: ApiRunSnapshot = {
    cases: Object.freeze(cases),
    environment,
    environmentName: input.environmentName,
    options,
    scopeLabel: input.scopeLabel,
    frozenAt: input.frozenAt
  }
  return Object.freeze(snapshot) as ApiRunSnapshot
}

import type { WebAutomationRunMode } from './webAutomation'
import type { SavedWebAutomationRunCase } from './webAutomationRepository'

/**
 * 发起一次 Web 执行时冻结下来的请求快照。
 *
 * 确认框展示的是这些值,POST 提交的也必须是这些值:确认之后到请求落地之间
 * 用户改了用例 / 换了环境 / 改了循环次数,都不能进入本次提交(跨 await 只读快照)。
 */
export interface WebRunStartRequest {
  /** 确认框与监控页展示的范围文案,如「执行选中 1 条」 */
  scopeLabel: string
  /** 发起时被选中的用例 id(用于执行前检查,不参与提交体) */
  selectedCaseIds: string[]
  loopCount: number
  environmentId: string
  environmentName: string
  environmentBaseUrl: string
  browser: string
  mode: WebAutomationRunMode
}

export interface FrozenWebRunStart extends WebRunStartRequest {
  /** 深拷贝后的用例:之后对 live 用例对象的任何修改都不会影响本次提交 */
  cases: SavedWebAutomationRunCase[]
}

// 用例来自 Vue 的响应式代理，structuredClone 会抛 DataCloneError；用例本身是纯 JSON。
const deepClone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T

/** 冻结提交快照:标量按当前值拷贝,用例逐条深拷贝。 */
export const freezeWebRunStart = (
  request: WebRunStartRequest,
  cases: readonly SavedWebAutomationRunCase[]
): FrozenWebRunStart => ({
  scopeLabel: request.scopeLabel,
  selectedCaseIds: [...request.selectedCaseIds],
  loopCount: request.loopCount,
  environmentId: request.environmentId,
  environmentName: request.environmentName,
  environmentBaseUrl: request.environmentBaseUrl,
  browser: request.browser,
  mode: request.mode,
  cases: deepClone([...cases])
})

/** 区分组件 emit 的请求快照与「没有参数」(rerun / 重跑失败用例) 两种调用。 */
export const isWebRunStartRequest = (value: unknown): value is WebRunStartRequest => {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<WebRunStartRequest>
  return typeof candidate.scopeLabel === 'string'
    && Array.isArray(candidate.selectedCaseIds)
    && typeof candidate.loopCount === 'number'
    && typeof candidate.environmentId === 'string'
    && typeof candidate.environmentBaseUrl === 'string'
    && typeof candidate.browser === 'string'
    && (candidate.mode === 'sequential' || candidate.mode === 'concurrent' || candidate.mode === 'module')
}

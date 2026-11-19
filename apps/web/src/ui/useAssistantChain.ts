/**
 * A1 助手链路的界面状态：草稿 → 确认 → 执行 → 报告。
 *
 * 语义约束（与后端一致，前端不兜底）：
 *   - 未确认的草稿不提供执行入口（`canExecute`），但仍由后端做最终裁决；
 *   - 执行只认 `executionState`；`DISPATCHED`/`RUNNING` 不是成功；
 *   - 报告接受/驳回只改 `reportState`，不推断执行结果已变。
 */
import { computed, getCurrentScope, onScopeDispose, ref, watch } from 'vue'
import * as defaultRepository from '../domain/assistantChainRepository'
import type {
  AssistantChainOptions,
  CaseSpec,
  DraftView,
  OperationView,
  ReportView,
  TaskResult,
  WaitingView
} from '../domain/assistantChainRepository'

type Repository = typeof defaultRepository

export interface UseAssistantChainDeps {
  options?: AssistantChainOptions
  repository?: Repository
  tenantId?: number | null
  targetRevision?: string
  inputs?: Record<string, string>
  actorId?: number | null
  projectId?: number | null
  projectRef?: string
  pollIntervalMs?: number
  maxPolls?: number
}

/** 确认页与执行必须用同一个目标标识：先落草稿，再由草稿带回。 */
const draftTargetRef = (projectId: number, projectRef?: string, specId?: string): string =>
  projectRef ?? `project:${projectId}/${specId ?? 'case'}`

export const useAssistantChain = (deps: UseAssistantChainDeps) => {
  const repository = deps.repository ?? defaultRepository
  const options = deps.options ?? {}
  const pollIntervalMs = deps.pollIntervalMs ?? 1000
  const maxPolls = deps.maxPolls ?? 120

  const draft = ref<DraftView | null>(null)
  const operation = ref<OperationView | null>(null)
  const task = ref<TaskResult | null>(null)
  const report = ref<ReportView | null>(null)
  const waiting = ref<WaitingView | null>(null)
  const busy = ref(false)
  /** 同一次执行必须复用同一个 requestId：双击/重试才走幂等重放而不是再派发一次。 */
  let pendingRequestId: string | null = null
  const error = ref('')

  let contextVersion = 0
  let disposed = false
  const resetContext = () => {
    contextVersion += 1
    draft.value = null
    operation.value = null
    task.value = null
    report.value = null
    waiting.value = null
    pendingRequestId = null
    error.value = ''
    busy.value = false
  }
  watch(() => [deps.tenantId, deps.actorId, deps.projectId], resetContext, { flush: 'sync' })
  if (getCurrentScope()) onScopeDispose(() => {
    disposed = true
    resetContext()
  })

  const describeError = (value: unknown): string => (value instanceof Error ? value.message : String(value))
  const canExecute = computed(() => draft.value?.state === 'CONFIRMED')
  const finished = computed(() =>
    ['SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN'].includes(operation.value?.executionState ?? '')
  )
  /** 等待用户不是终态：既不能出报告，也不该被当成「跑完了」。 */
  const isWaitingUser = computed(() => operation.value?.executionState === 'WAITING_USER')

  const guard = async <T>(action: (assertCurrent: () => void, context: { tenantId: number; actorId: number; projectId: number }) => Promise<T>): Promise<T | undefined> => {
    if (disposed) return undefined
    const { tenantId, actorId, projectId } = deps
    const positiveId = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value > 0
    if (!positiveId(tenantId) || !positiveId(actorId) || !positiveId(projectId)) {
      error.value = '认证或项目上下文缺失，禁止提交'
      return undefined
    }
    if (busy.value) {
      error.value = '上一步还在进行中，请稍候'
      return undefined
    }
    busy.value = true
    error.value = ''
    const version = contextVersion
    const assertCurrent = () => {
      if (version !== contextVersion) throw new Error('助手上下文已切换')
    }
    try {
      return await action(assertCurrent, { tenantId, actorId, projectId })
    } catch (value) {
      if (version === contextVersion) error.value = describeError(value)
      return undefined
    } finally {
      if (version === contextVersion) busy.value = false
    }
  }

  const createDraft = (spec: CaseSpec, requirementRef: string, requirementText: string) =>
    guard(async (assertCurrent, context) => {
      const created = await repository.createDraft(options, {
        tenantId: context.tenantId,
        projectId: context.projectId,
        createdBy: context.actorId,
        requirementRef,
        requirementText,
        actionCode: 'web.case.execute',
        targetRef: draftTargetRef(context.projectId, deps.projectRef, spec.id),
        targetRevision: deps.targetRevision ?? 'rev-1',
        inputs: deps.inputs,
        spec
      })
      assertCurrent()
      draft.value = created
      pendingRequestId = null
      operation.value = null
      task.value = null
      report.value = null
      return created
    })

  const confirm = () =>
    guard(async (assertCurrent, context) => {
      if (!draft.value) throw new Error('请先创建草稿')
      const confirmed = await repository.confirmDraft(options, draft.value.draftId, context.tenantId, context.actorId)
      assertCurrent()
      draft.value = confirmed
      return confirmed
    })

  const reject = () =>
    guard(async (assertCurrent, context) => {
      if (!draft.value) throw new Error('请先创建草稿')
      const rejected = await repository.rejectDraft(options, draft.value.draftId, context.tenantId, context.actorId)
      assertCurrent()
      draft.value = rejected
      return rejected
    })

  /** 派生新修订：旧修订会被平台标记 SUPERSEDED，新修订必须重新确认。 */
  const reviseDraft = (spec: CaseSpec) =>
    guard(async (assertCurrent, context) => {
      if (!draft.value) throw new Error('请先创建草稿')
      const revised = await repository.reviseDraft(
        options,
        draft.value.draftId,
        context.tenantId,
        context.actorId,
        spec,
        deps.inputs
      )
      assertCurrent()
      draft.value = revised
      operation.value = null
      report.value = null
      return revised
    })

  const execute = () =>
    guard(async (assertCurrent, context) => {
      if (!draft.value) throw new Error('请先创建草稿')
      const started = await repository.runTask(options, {
        tenantId: context.tenantId,
        actorId: context.actorId,
        projectId: context.projectId,
        requestId: pendingRequestId ?? (pendingRequestId = crypto.randomUUID()),
        draftId: draft.value.draftId,
        actionCode: draft.value.actionCode,
        targetRef: draft.value.targetRef,
        targetRevision: draft.value.targetRevision,
        inputs: deps.inputs,
        spec: draft.value.spec,
        waitSeconds: 0
      })
      assertCurrent()
      task.value = started
      const operationSnapshot = await repository.getOperation(options, started.operationId, context.tenantId)
      assertCurrent()
      operation.value = operationSnapshot
      return started
    })

  /** 轮询到终态；`UNKNOWN` 也停（需要人工处置，不自动重试）。 */
  const refresh = () =>
    guard(async (assertCurrent, context) => {
      if (!operation.value) throw new Error('还没有执行记录')
      let snapshot = operation.value
      for (let attempt = 0; attempt < maxPolls; attempt += 1) {
        // 账本的执行轴只有 /sync（或 waitSeconds>0）才会推进：只读 GET 永远停在 DISPATCHED。
        if (['DISPATCHED', 'RUNNING'].includes(snapshot.executionState)) {
          await repository.syncOperation(options, snapshot.operationId, context.tenantId)
          assertCurrent()
        }
        const operationSnapshot = await repository.getOperation(options, snapshot.operationId, context.tenantId)
        assertCurrent()
        snapshot = operationSnapshot
        operation.value = snapshot
        if (['SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN'].includes(snapshot.executionState)) {
          pendingRequestId = null
          // 到了终态就不再是「等人」：不清掉会让面板一直显示等待按钮（点了必然 409）。
          waiting.value = null
          break
        }
        // 等人不是「继续轮询就会自己好」：停下来把原因展示给用户。
        if (snapshot.executionState === 'WAITING_USER') {
          const waitingSnapshot = await repository.describeWaitingUser(options, snapshot.operationId, context.tenantId)
          assertCurrent()
          waiting.value = waitingSnapshot
          break
        }
        await new Promise((resolve) => setTimeout(resolve, pollIntervalMs))
        assertCurrent()
      }
      return snapshot
    })

  /** 资源收尾：报告接受的前置条件（每个资源要么已清理，要么人工判定不可恢复）。 */
  const cleanResource = (resourceId: string, evidence: string) =>
    guard(async (assertCurrent, context) => {
      if (!operation.value) throw new Error('还没有执行记录')
      const result = await repository.cleanResource(
        options,
        operation.value.operationId,
        resourceId,
        context.tenantId,
        evidence,
        context.actorId
      )
      assertCurrent()
      const operationSnapshot = await repository.getOperation(options, operation.value.operationId, context.tenantId)
      assertCurrent()
      operation.value = operationSnapshot
      return result
    })

  const markUnrecoverable = (resourceId: string, reason: string) =>
    guard(async (assertCurrent, context) => {
      if (!operation.value) throw new Error('还没有执行记录')
      const result = await repository.markResourceUnrecoverable(
        options,
        operation.value.operationId,
        resourceId,
        context.tenantId,
        reason
      )
      assertCurrent()
      const operationSnapshot = await repository.getOperation(options, operation.value.operationId, context.tenantId)
      assertCurrent()
      operation.value = operationSnapshot
      return result
    })

  /** 人工处置：只用于 PENDING / 无 runId 的 UNKNOWN，且只能判失败或取消。 */
  const resolveStalled = (outcome: 'FAILED' | 'CANCELLED', reason: string) =>
    guard(async (assertCurrent, context) => {
      if (!operation.value) throw new Error('还没有执行记录')
      const result = await repository.resolveStalled(
        options,
        operation.value.operationId,
        context.tenantId,
        outcome,
        reason,
        context.actorId
      )
      assertCurrent()
      const operationSnapshot = await repository.getOperation(options, operation.value.operationId, context.tenantId)
      assertCurrent()
      operation.value = operationSnapshot
      return result
    })

  const loadWaiting = () =>
    guard(async (assertCurrent, context) => {
      if (!operation.value) throw new Error('还没有执行记录')
      const waitingSnapshot = await repository.describeWaitingUser(options, operation.value.operationId, context.tenantId)
      assertCurrent()
      waiting.value = waitingSnapshot
      return waiting.value
    })

  /** 需要本人完成 MFA/人工登录时显式进入等待：状态与到期时间写进账本，不是前端提示。 */
  const beginWaiting = (reason: string, timeoutMinutes?: number) =>
    guard(async (assertCurrent, context) => {
      if (!operation.value) throw new Error('还没有执行记录')
      const waitingSnapshot = await repository.requireWaitingUser(
        options, operation.value.operationId, context.tenantId, reason, timeoutMinutes)
      assertCurrent()
      waiting.value = waitingSnapshot
      const operationSnapshot = await repository.getOperation(options, operation.value.operationId, context.tenantId)
      assertCurrent()
      operation.value = operationSnapshot
      return waiting.value
    })

  /** 用户补齐后回到 PENDING：必须重新派发，不能续跑旧代次。 */
  const resumeFromWaiting = () =>
    guard(async (assertCurrent, context) => {
      if (!operation.value) throw new Error('还没有执行记录')
      const resumed = await repository.resumeWaitingUser(options, operation.value.operationId, context.tenantId)
      assertCurrent()
      waiting.value = null
      pendingRequestId = null
      const operationSnapshot = await repository.getOperation(options, operation.value.operationId, context.tenantId)
      assertCurrent()
      operation.value = operationSnapshot
      return resumed
    })

  const abandonWaiting = (reason: string) =>
    guard(async (assertCurrent, context) => {
      if (!operation.value) throw new Error('还没有执行记录')
      const cancelled = await repository.cancelWaitingUser(
        options, operation.value.operationId, context.tenantId, reason)
      assertCurrent()
      waiting.value = null
      const operationSnapshot = await repository.getOperation(options, operation.value.operationId, context.tenantId)
      assertCurrent()
      operation.value = operationSnapshot
      return cancelled
    })

  /** 真正停掉 Runner run 之后才在账面取消：停不掉会报错，不会假装成功。 */
  const stopRun = (reason: string) =>
    guard(async (assertCurrent, context) => {
      if (!operation.value) throw new Error('还没有执行记录')
      const result = await repository.stopOperation(
        options,
        operation.value.operationId,
        context.tenantId,
        reason,
        context.actorId
      )
      assertCurrent()
      const operationSnapshot = await repository.getOperation(options, operation.value.operationId, context.tenantId)
      assertCurrent()
      operation.value = operationSnapshot
      return result
    })

  const makeReport = () =>
    guard(async (assertCurrent, context) => {
      if (!operation.value) throw new Error('还没有执行记录')
      const generated = await repository.generateReport(
        options,
        operation.value.operationId,
        context.tenantId,
        context.actorId
      )
      assertCurrent()
      report.value = generated
      const operationSnapshot = await repository.getOperation(options, operation.value.operationId, context.tenantId)
      assertCurrent()
      operation.value = operationSnapshot
      return generated
    })

  const decide = (accepted: boolean) =>
    guard(async (assertCurrent, context) => {
      if (!operation.value) throw new Error('还没有执行记录')
      if (!report.value) throw new Error('请先生成报告')
      const result = await repository.decideReport(
        options,
        operation.value.operationId,
        context.tenantId,
        report.value.reportId,
        accepted
      )
      assertCurrent()
      const reportSnapshot = await repository.latestReport(options, operation.value.operationId, context.tenantId)
      assertCurrent()
      report.value = reportSnapshot
      const operationSnapshot = await repository.getOperation(options, operation.value.operationId, context.tenantId)
      assertCurrent()
      operation.value = operationSnapshot
      return result
    })

  return {
    decideReport: decide,
    reviseDraft,
    resources: computed(() => operation.value?.resources ?? []),
    cleanResource,
    markUnrecoverable,
    resolveStalled,
    stopRun,
    waiting,
    isWaitingUser,
    loadWaiting,
    beginWaiting,
    resumeFromWaiting,
    abandonWaiting,
    draft,
    operation,
    task,
    report,
    busy,
    error,
    canExecute,
    finished,
    createDraft,
    confirm,
    reject,
    execute,
    refresh,
    makeReport,
    decide
  }
}

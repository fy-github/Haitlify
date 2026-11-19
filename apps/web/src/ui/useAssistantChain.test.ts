import { describe, expect, it, vi } from 'vitest'
import { effectScope, reactive } from 'vue'
import { useAssistantChain } from './useAssistantChain'
import type { CaseSpec, DraftView, OperationView, ReportView, TaskResult } from '../domain/assistantChainRepository'

const spec: CaseSpec = {
  id: 'case-ui',
  name: 'UI 夹具',
  startUrl: 'http://127.0.0.1:19399/f.html',
  steps: [{ id: 's1', action: 'goto', target: '/f.html' }]
}

const draft = (state: string): DraftView => ({
  draftId: 'draft-1',
  state,
  requirementDigest: 'a'.repeat(64),
  specDigest: 'b'.repeat(64),
  spec,
  confirmedBy: state === 'CONFIRMED' ? 7 : null,
  projectId: 2,
  actionCode: 'web.case.execute',
  targetRef: 'project:2/case-ui',
  targetRevision: 'rev-1'
})

const operation = (executionState: string): OperationView => ({
  operationId: 'op-1',
  executionState,
  reportState: 'NONE',
  dispatchGeneration: 1,
  attempts: [],
  resources: [
    { resourceId: 'res-1', resourceKind: 'BROWSER', cleanupState: 'ALLOCATED', deadlineAt: '2026-10-20T02:00:00Z' }
  ]
})

const taskResult: TaskResult = {
  requestId: 'req-1',
  operationId: 'op-1',
  executionState: 'DISPATCHED',
  runId: 'run-1',
  digest: 'c'.repeat(64),
  stepCount: 1,
  created: true
}

const report = (revision: number): ReportView => ({
  reportId: `report-${revision}`,
  operationId: 'op-1',
  revision,
  bodyJson: '{"executionState":"SUCCEEDED"}',
  createdAt: '2026-10-20T00:00:00Z'
})

const buildRepository = () => ({
  createDraft: vi.fn(async () => draft('DRAFT')),
  confirmDraft: vi.fn(async () => draft('CONFIRMED')),
  rejectDraft: vi.fn(async () => draft('REJECTED')),
  getDraft: vi.fn(async () => draft('DRAFT')),
  runTask: vi.fn(async (_options: unknown, _body: { requestId: string }) => taskResult),
  getOperation: vi.fn(async () => operation('SUCCEEDED')),
  syncOperation: vi.fn(async () => ({ executionState: 'RUNNING' })),
  generateReport: vi.fn(async () => report(1)),
  latestReport: vi.fn(async () => report(2)),
  decideReport: vi.fn(async () => ({ operationId: 'op-1', reportState: 'ACCEPTED' })),
  cleanResource: vi.fn(async () => ({ operationId: 'op-1', resourceId: 'res-1', cleanupState: 'CLEANED' })),
  markResourceUnrecoverable: vi.fn(async () => ({
    operationId: 'op-1',
    resourceId: 'res-1',
    cleanupState: 'UNRECOVERABLE'
  })),
  resolveStalled: vi.fn(async () => ({ operationId: 'op-1', executionState: 'FAILED' })),
  stopOperation: vi.fn(async () => ({ operationId: 'op-1', executionState: 'CANCELLED' })),
  reviseDraft: vi.fn(async () => ({ ...draft('DRAFT'), draftId: 'draft-2' })),
  listDrafts: vi.fn(async () => [draft('DRAFT')]),
  listDraftRevisions: vi.fn(async () => [draft('SUPERSEDED'), draft('DRAFT')]),
  describeWaitingUser: vi.fn(async () => ({
    operationId: 'op-1', executionState: 'WAITING_USER', reason: '需要本人完成 MFA',
    requestedAt: '2026-10-21T00:00:00Z', deadlineAt: '2026-10-21T00:30:00Z'
  })),
  requireWaitingUser: vi.fn(async () => ({
    operationId: 'op-1', executionState: 'WAITING_USER', reason: '需要本人完成 MFA',
    requestedAt: '2026-10-21T00:00:00Z', deadlineAt: '2026-10-21T00:30:00Z'
  })),
  resumeWaitingUser: vi.fn(async () => ({
    operationId: 'op-1', executionState: 'PENDING', reason: null, requestedAt: null, deadlineAt: null
  })),
  cancelWaitingUser: vi.fn(async () => ({
    operationId: 'op-1', executionState: 'CANCELLED', reason: '需要本人完成 MFA -> 放弃',
    requestedAt: '2026-10-21T00:00:00Z', deadlineAt: '2026-10-21T00:30:00Z'
  }))
})

describe('useAssistantChain', () => {
  it('invalidates confirmed state synchronously when the project changes', async () => {
    const repository = buildRepository()
    const identity = reactive({ tenantId: 42, actorId: 73, projectId: 8 })
    const chain = useAssistantChain({ repository,
      get tenantId() { return identity.tenantId }, get actorId() { return identity.actorId },
      get projectId() { return identity.projectId }
    })
    await chain.createDraft(spec, 'req', '需求')
    await chain.confirm()
    await chain.execute()
    await chain.makeReport()
    identity.projectId = 9
    expect(chain.draft.value).toBeNull()
    expect(chain.operation.value).toBeNull()
    expect(chain.report.value).toBeNull()
    expect(chain.task.value).toBeNull()
    expect(chain.canExecute.value).toBe(false)
    await chain.execute()
    expect(repository.runTask).toHaveBeenCalledTimes(1)
    await chain.createDraft(spec, 'new', '新需求')
    expect(repository.createDraft).toHaveBeenLastCalledWith(expect.anything(), expect.objectContaining({ projectId: 9, tenantId: 42, createdBy: 73 }))
  })

  it('discards old execution responses and does not read back with the new tenant', async () => {
    const repository = buildRepository()
    let resolve!: (result: TaskResult) => void
    repository.runTask.mockImplementationOnce(() => new Promise(done => { resolve = done }))
    const identity = reactive({ tenantId: 42, actorId: 73, projectId: 8 })
    const chain = useAssistantChain({ repository, get tenantId() { return identity.tenantId },
      get actorId() { return identity.actorId }, get projectId() { return identity.projectId } })
    await chain.createDraft(spec, 'req', '需求')
    await chain.confirm()
    const old = chain.execute()
    identity.tenantId = 53
    identity.projectId = 9
    let resolveNew!: (result: DraftView) => void
    repository.createDraft.mockImplementationOnce(() => new Promise(done => { resolveNew = done }))
    const fresh = chain.createDraft(spec, 'new', '新需求')
    resolve(taskResult)
    await old
    expect(chain.task.value).toBeNull()
    expect(chain.operation.value).toBeNull()
    expect(repository.getOperation).not.toHaveBeenCalled()
    expect(chain.busy.value).toBe(true)
    expect(chain.error.value).toBe('')
    resolveNew({ ...draft('DRAFT'), draftId: 'new' })
    await fresh
    expect(chain.draft.value?.draftId).toBe('new')
  })

  it.each(['tenantId', 'actorId', 'projectId'] as const)('fails closed when %s is missing or invalid', async (field) => {
    for (const invalid of [undefined, null, 0, -1, NaN, 1.5, Number.MAX_SAFE_INTEGER + 1, '7']) {
      const repository = buildRepository()
      const identity = { tenantId: 42, actorId: 73, projectId: 8, [field]: invalid }
      const chain = useAssistantChain({ repository, ...identity } as Parameters<typeof useAssistantChain>[0])
      await chain.createDraft(spec, 'req', '需求')
      expect(repository.createDraft).not.toHaveBeenCalled()
      expect(chain.error.value).toContain('上下文')
    }
  })

  it('stops pending continuations on scope disposal (logout or navigation)', async () => {
    const repository = buildRepository()
    let resolve!: (result: TaskResult) => void
    repository.runTask.mockImplementationOnce(() => new Promise(done => { resolve = done }))
    const scope = effectScope()
    const chain = scope.run(() => useAssistantChain({ repository, tenantId: 42, actorId: 73, projectId: 8 }))!
    await chain.createDraft(spec, 'req', '需求')
    await chain.confirm()
    const pending = chain.execute()
    scope.stop()
    resolve(taskResult)
    await pending
    expect(chain.draft.value).toBeNull()
    expect(chain.task.value).toBeNull()
    expect(repository.getOperation).not.toHaveBeenCalled()
    await chain.createDraft(spec, 'req', '需求')
    expect(repository.createDraft).toHaveBeenCalledTimes(1)
  })

  it.each(['tenantId', 'actorId'] as const)('clears all context state on %s change, including waiting and request id', async (field) => {
    const repository = buildRepository()
    repository.getOperation.mockResolvedValue(operation('WAITING_USER'))
    const identity = reactive({ tenantId: 42, actorId: 73, projectId: 8 })
    const chain = useAssistantChain({ repository, get tenantId() { return identity.tenantId },
      get actorId() { return identity.actorId }, get projectId() { return identity.projectId } })
    await chain.createDraft(spec, 'req', '需求')
    await chain.confirm()
    await chain.execute()
    await chain.refresh()
    identity[field]++
    expect(chain.waiting.value).toBeNull()
    expect(chain.resources.value).toEqual([])
    expect(chain.draft.value).toBeNull()
    await chain.createDraft(spec, 'req', '需求')
    await chain.confirm()
    await chain.execute()
    expect(repository.runTask.mock.calls[1][1].requestId).not.toBe(repository.runTask.mock.calls[0][1].requestId)
  })

  it('does not restore a late confirmation after switching away and back to the same identity', async () => {
    const repository = buildRepository()
    let resolve!: (value: DraftView) => void
    repository.confirmDraft.mockImplementationOnce(() => new Promise(done => { resolve = done }))
    const identity = reactive({ projectId: 8 })
    const chain = useAssistantChain({ repository, tenantId: 42, actorId: 73, get projectId() { return identity.projectId } })
    await chain.createDraft(spec, 'req', '需求')
    const pending = chain.confirm()
    identity.projectId = 9
    identity.projectId = 8
    resolve(draft('CONFIRMED'))
    await pending
    expect(chain.draft.value).toBeNull()
    expect(chain.canExecute.value).toBe(false)
  })

  it('does not overwrite a new context error with a stale request rejection', async () => {
    const repository = buildRepository()
    let reject!: (reason: Error) => void
    repository.createDraft.mockImplementationOnce(() => new Promise((_done, fail) => { reject = fail }))
    const identity = reactive({ projectId: 8 })
    const chain = useAssistantChain({ repository, tenantId: 42, actorId: 73, get projectId() { return identity.projectId } })
    const pending = chain.createDraft(spec, 'req', '需求')
    identity.projectId = 9
    await chain.confirm()
    reject(new Error('old context failure'))
    await pending
    expect(chain.error.value).toBe('请先创建草稿')
  })

  it('does not issue the old polling readback after a pending sync returns in another context', async () => {
    const repository = buildRepository()
    repository.getOperation.mockResolvedValue(operation('RUNNING'))
    let resolve!: (value: { executionState: string }) => void
    repository.syncOperation.mockImplementationOnce(() => new Promise(done => { resolve = done }))
    const identity = reactive({ projectId: 8 })
    const chain = useAssistantChain({ repository, tenantId: 42, actorId: 73, pollIntervalMs: 0, maxPolls: 1,
      get projectId() { return identity.projectId } })
    await chain.createDraft(spec, 'req', '需求')
    await chain.confirm()
    await chain.execute()
    const pending = chain.refresh()
    identity.projectId = 9
    resolve({ executionState: 'SUCCEEDED' })
    await pending
    expect(repository.getOperation).toHaveBeenCalledTimes(1)
    expect(chain.operation.value).toBeNull()
  })

  it('gates execution on confirmation', async () => {
    const repository = buildRepository()
    const chain = useAssistantChain({ repository, tenantId: 1, actorId: 7, projectId: 2, pollIntervalMs: 0 })

    await chain.createDraft(spec, 'req-1', '需求')
    expect(chain.canExecute.value).toBe(false)

    await chain.confirm()
    expect(chain.canExecute.value).toBe(true)
    expect(repository.confirmDraft).toHaveBeenCalledWith(expect.anything(), 'draft-1', 1, 7)
  })

  it('runs with the confirmed draft and reads the operation back', async () => {
    const repository = buildRepository()
    const chain = useAssistantChain({ repository, tenantId: 1, actorId: 7, projectId: 2, pollIntervalMs: 0 })

    await chain.createDraft(spec, 'req-1', '需求')
    await chain.confirm()
    const started = await chain.execute()

    expect(started?.runId).toBe('run-1')
    expect(repository.runTask).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ draftId: 'draft-1', actionCode: 'web.case.execute', spec })
    )
    expect(chain.operation.value?.executionState).toBe('SUCCEEDED')
    expect(chain.finished.value).toBe(true)
  })

  it('surfaces backend rejections instead of swallowing them', async () => {
    const repository = buildRepository()
    repository.runTask.mockRejectedValueOnce(new Error('assistant chain request failed: 409 draft_not_confirmed'))
    const chain = useAssistantChain({ repository, tenantId: 1, actorId: 7, projectId: 2, pollIntervalMs: 0 })

    await chain.createDraft(spec, 'req-1', '需求')
    const result = await chain.execute()

    expect(result).toBeUndefined()
    expect(chain.error.value).toContain('draft_not_confirmed')
  })

  it('generates a report and refreshes it after acceptance', async () => {
    const repository = buildRepository()
    const chain = useAssistantChain({ repository, tenantId: 1, actorId: 7, projectId: 2, pollIntervalMs: 0 })

    await chain.createDraft(spec, 'req-1', '需求')
    await chain.confirm()
    await chain.execute()
    await chain.makeReport()
    expect(chain.report.value?.revision).toBe(1)

    await chain.decide(true)
    expect(repository.decideReport).toHaveBeenCalledWith(expect.anything(), 'op-1', 1, 'report-1', true)
    expect(chain.report.value?.revision).toBe(2)
  })

  it.each(['generated', 'accepted'] as const)('reads back the operation report state after it is %s', async stage => {
    const repository = buildRepository()
    repository.getOperation.mockResolvedValueOnce(operation('SUCCEEDED'))
    repository.getOperation.mockResolvedValueOnce({ ...operation('SUCCEEDED'), reportState: 'PENDING' })
    repository.getOperation.mockResolvedValueOnce({ ...operation('SUCCEEDED'), reportState: 'ACCEPTED' })
    const chain = useAssistantChain({ repository, tenantId: 1, actorId: 7, projectId: 2 })
    await chain.createDraft(spec, 'req', '需求')
    await chain.confirm()
    await chain.execute()
    await chain.makeReport()
    if (stage === 'accepted') await chain.decide(true)
    expect(chain.operation.value?.reportState).toBe(stage === 'accepted' ? 'ACCEPTED' : 'PENDING')
    expect(chain.operation.value?.executionState).toBe('SUCCEEDED')
    expect(repository.getOperation).toHaveBeenCalledTimes(stage === 'accepted' ? 3 : 2)
  })

  it.each([
    ['generated', 'success'], ['generated', 'failure'], ['accepted', 'success'], ['accepted', 'failure']
  ] as const)('discards %s operation readback %s after the project changes', async (stage, outcome) => {
    const repository = buildRepository()
    const identity = reactive({ projectId: 2 })
    const chain = useAssistantChain({ repository, tenantId: 1, actorId: 7, get projectId() { return identity.projectId } })
    await chain.createDraft(spec, 'req', '需求')
    await chain.confirm()
    await chain.execute()
    if (stage === 'accepted') await chain.makeReport()
    let release!: (value: OperationView) => void
    let fail!: (error: Error) => void
    repository.getOperation.mockImplementationOnce(() => new Promise((resolve, reject) => { release = resolve; fail = reject }))
    const pending = stage === 'generated' ? chain.makeReport() : chain.decide(true)
    await vi.waitFor(() => expect(release).toBeTypeOf('function'))
    identity.projectId = 3
    if (outcome === 'success') release({ ...operation('SUCCEEDED'), reportState: 'ACCEPTED' })
    else fail(new Error('obsolete report readback'))
    await pending
    expect(chain.operation.value).toBeNull()
    expect(chain.report.value).toBeNull()
    expect(chain.error.value).toBe('')
    expect(chain.busy.value).toBe(false)
  })

  it('stops polling on UNKNOWN instead of retrying blindly', async () => {
    const repository = buildRepository()
    repository.getOperation.mockResolvedValue(operation('UNKNOWN'))
    const chain = useAssistantChain({ repository, tenantId: 1, actorId: 7, projectId: 2, pollIntervalMs: 0 })

    await chain.createDraft(spec, 'req-1', '需求')
    await chain.confirm()
    await chain.execute()
    const snapshot = await chain.refresh()

    expect(snapshot?.executionState).toBe('UNKNOWN')
    expect(chain.finished.value).toBe(true)
  })

  it('syncs while dispatched so the run can reach a terminal state', async () => {
    const repository = buildRepository()
    let reads = 0
    repository.getOperation = vi.fn(async () => {
      reads += 1
      return operation(reads === 1 ? 'DISPATCHED' : 'SUCCEEDED')
    })
    const chain = useAssistantChain({ repository, tenantId: 1, actorId: 7, projectId: 2, pollIntervalMs: 0 })

    await chain.createDraft(spec, 'req-1', '需求')
    await chain.confirm()
    await chain.execute()
    await chain.refresh()

    expect(repository.syncOperation).toHaveBeenCalledWith(expect.anything(), 'op-1', 1)
    expect(chain.operation.value?.executionState).toBe('SUCCEEDED')
  })

  it('reuses the requestId across retries so the platform can deduplicate', async () => {
    const repository = buildRepository()
    const chain = useAssistantChain({ repository, tenantId: 1, actorId: 7, projectId: 2, pollIntervalMs: 0 })

    await chain.createDraft(spec, 'req-1', '需求')
    await chain.confirm()
    await chain.execute()
    await chain.execute()

    const ids = repository.runTask.mock.calls.map((call) => call[1].requestId)
    expect(ids).toHaveLength(2)
    expect(ids[0]).toBe(ids[1])
  })

  it('stops polling on WAITING_USER and surfaces why the user must act', async () => {
    const repository = buildRepository()
    repository.getOperation = vi.fn(async () => operation('WAITING_USER'))
    const chain = useAssistantChain({ tenantId: 2, actorId: 7, projectId: 2, repository, pollIntervalMs: 1, maxPolls: 5 })
    await chain.createDraft(spec, 'manual://req', '需求')
    await chain.confirm()
    await chain.execute()

    const snapshot = await chain.refresh()

    expect(snapshot?.executionState).toBe('WAITING_USER')
    expect(repository.syncOperation).not.toHaveBeenCalled()
    expect(chain.waiting.value?.reason).toBe('需要本人完成 MFA')
    expect(chain.finished.value).toBe(false)
  })

  it('clears the waiting panel as soon as the operation reaches a terminal state', async () => {
    const repository = buildRepository()
    repository.getOperation = vi.fn(async () => operation('WAITING_USER'))
    const chain = useAssistantChain({ tenantId: 2, actorId: 7, projectId: 2, repository, pollIntervalMs: 1, maxPolls: 5 })
    await chain.createDraft(spec, 'manual://req', '需求')
    await chain.confirm()
    await chain.execute()
    await chain.refresh()
    expect(chain.waiting.value?.reason).toBe('需要本人完成 MFA')

    // 用户处理完（或超时）后到了终态：面板不能还显示「等待用户」并给出必然 409 的按钮。
    repository.getOperation = vi.fn(async () => operation('CANCELLED'))
    await chain.refresh()

    expect(chain.waiting.value).toBeNull()
    expect(chain.finished.value).toBe(true)
  })

  it('resumes waiting into PENDING with a fresh request id for the next dispatch', async () => {
    const repository = buildRepository()
    repository.getOperation = vi.fn(async () => operation('WAITING_USER'))
    const chain = useAssistantChain({ tenantId: 2, actorId: 7, projectId: 2, repository, pollIntervalMs: 1, maxPolls: 5 })
    await chain.createDraft(spec, 'manual://req', '需求')
    await chain.confirm()
    await chain.execute()

    await chain.resumeFromWaiting()

    expect(repository.resumeWaitingUser).toHaveBeenCalledWith(expect.anything(), 'op-1', 2)
    expect(chain.waiting.value).toBeNull()
    expect(chain.operation.value?.executionState).toBe('WAITING_USER')

    // 恢复后必须用新的 requestId 重新派发：复用旧 ID 会被幂等重放成「同一请求」（复核 D8）。
    const firstRequestId = (repository.runTask.mock.calls[0] as unknown as [unknown, { requestId: string }])[1].requestId
    await chain.execute()
const secondRequestId = (repository.runTask.mock.calls[1] as unknown as [unknown, { requestId: string }])[1].requestId
    expect(secondRequestId).not.toBe(firstRequestId)
  })
})

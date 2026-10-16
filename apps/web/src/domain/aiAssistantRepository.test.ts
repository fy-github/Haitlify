import { describe, expect, it, vi } from 'vitest'
import {
  fetchRoutingCandidates,
  nodeActionable,
  recommendTasks,
  reviewAssistantArtifact,
  startAssistantRun,
  startCompositeRun,
  fetchAssistantArtifactExport,
  fetchAssistantArtifacts,
  fetchAssistantDraftPoints,
  type AiRoutingCandidate
} from './aiAssistantRepository'

const okJson = (payload: unknown) => ({ ok: true, status: 200, json: async () => payload })

const makeOptions = () => {
  const fetchMock = vi.fn(async () => okJson({}) as unknown as Response)
  return {
    fetchMock,
    options: { baseUrl: 'http://backend.local', fetchImpl: fetchMock as unknown as typeof fetch }
  }
}

describe('aiAssistantRepository', () => {
  it('starts a run with the explicit task selection and expected payload', async () => {
    const { fetchMock, options } = makeOptions()
    fetchMock.mockResolvedValue(okJson({
      runId: 'assistant-1', taskType: 'bug_analysis', projectId: 1, status: 'waiting_review',
      violations: [], artifactId: 'artifact-1', revision: 1, acceptanceState: 'PENDING_HUMAN_CONFIRMATION'
    }) as unknown as Response)
    const run = await startAssistantRun(options, {
      taskType: 'bug_analysis', projectId: 1, inputs: { symptom: '支付成功但订单未同步' }, actorId: 'qa'
    })
    const call = fetchMock.mock.calls[fetchMock.mock.calls.length - 1] as unknown as [string, RequestInit]
    expect(call[0]).toBe('http://backend.local/api/ai/assistant/runs')
    expect(JSON.parse(String(call[1].body))).toMatchObject({
      taskType: 'bug_analysis', projectId: 1, actorId: 'qa'
    })
    // 产物状态来自后端：前端不把 waiting_review 当作已完成。
    expect(run.status).toBe('waiting_review')
    expect(run.acceptanceState).toBe('PENDING_HUMAN_CONFIRMATION')
  })

  it('sends review decisions with the revision so stale edits answer 409', async () => {
    const { fetchMock, options } = makeOptions()
    fetchMock.mockResolvedValue({ ok: false, status: 409, json: async () => ({ code: 'stale_revision', message: 'stale' }) } as unknown as Response)
    await expect(reviewAssistantArtifact(options, 'artifact-1', { decision: 'approve', revision: 1 }))
      .rejects.toMatchObject({ status: 409 })
    const call = fetchMock.mock.calls[fetchMock.mock.calls.length - 1] as unknown as [string, RequestInit]
    expect(JSON.parse(String(call[1].body))).toEqual({ decision: 'approve', revision: 1 })
  })

  it('routing candidates carry gaps only and never trigger writes', async () => {
    const { fetchMock, options } = makeOptions()
    fetchMock.mockResolvedValue(okJson([
      { taskType: 'bug_analysis', displayName: '缺陷分析', missingInputs: [], evidenceReady: true, complete: true },
      { taskType: 'log_triage', displayName: '日志排查', missingInputs: ['logExcerpt'], evidenceReady: false, complete: false }
    ]) as unknown as Response)
    const candidates = await fetchRoutingCandidates(options, { symptom: '500' })
    expect(candidates).toHaveLength(2)
    const call = fetchMock.mock.calls[fetchMock.mock.calls.length - 1] as unknown as [string, RequestInit]
    expect(call[0]).toBe('http://backend.local/api/ai/assistant/routing-candidates')
    expect(JSON.parse(String(call[1].body))).toEqual({ inputs: { symptom: '500' } })
  })

  it('starts the composite template through the dedicated endpoint', async () => {
    const { fetchMock, options } = makeOptions()
    fetchMock.mockResolvedValue(okJson({
      compositeId: 'composite-1', projectId: 1, template: 'bug_log_regression', status: 'waiting_review',
      nodes: []
    }) as unknown as Response)
    const run = await startCompositeRun(options, {
      template: 'bug_log_regression', projectId: 1,
      inputs: { symptom: 's', logExcerpt: 'l', changeSummary: 'c' }
    })
    expect(run.compositeId).toBe('composite-1')
    const call = fetchMock.mock.calls[fetchMock.mock.calls.length - 1] as unknown as [string, RequestInit]
    expect(call[0]).toBe('http://backend.local/api/ai/assistant/composites')
  })

  it('ranks complete candidates first, then by fewest gaps', () => {
    const candidates: AiRoutingCandidate[] = [
      { taskType: 'a', displayName: 'a', missingInputs: ['x'], evidenceReady: false, complete: false },
      { taskType: 'b', displayName: 'b', missingInputs: [], evidenceReady: true, complete: true },
      { taskType: 'c', displayName: 'c', missingInputs: ['x', 'y'], evidenceReady: false, complete: false }
    ]
    expect(recommendTasks(candidates).map((candidate) => candidate.taskType)).toEqual(['b', 'a', 'c'])
  })

  it('only waiting_review nodes are actionable', () => {
    const base = { nodeId: 'bug', position: 0, taskType: 'bug_analysis', dependsOn: [], runId: '', artifactId: '', skipReason: '' }
    expect(nodeActionable({ ...base, status: 'waiting_review' })).toBe(true)
    expect(nodeActionable({ ...base, status: 'skipped' })).toBe(false)
    expect(nodeActionable({ ...base, status: 'pending' })).toBe(false)
    expect(nodeActionable({ ...base, status: 'accepted' })).toBe(false)
  })


  it('exports only through the accepted artifact endpoint and forwards the revision', async () => {
    const { fetchMock, options } = makeOptions()
    fetchMock.mockResolvedValue(okJson({
      artifactId: 'artifact-1', runId: 'assistant-1', taskType: 'bug_analysis', projectId: 1,
      revision: 2, acceptanceState: 'ACCEPTED', reviewedBy: 'qa-user', exportedAt: '2026-10-12T08:45:00Z',
      output: { summary: '导出内容' }, sourceManifest: { documents: [] }, violations: []
    }) as unknown as Response)
    const exported = await fetchAssistantArtifactExport(options, 'artifact-1', 2)
    const call = fetchMock.mock.calls[fetchMock.mock.calls.length - 1] as unknown as [string, RequestInit]
    expect(call[0]).toBe('http://backend.local/api/ai/assistant/artifacts/artifact-1/export?revision=2')
    // 是否可导出由后端裁决：前端只透传状态，不自行放宽。'
    expect(exported.acceptanceState).toBe('ACCEPTED')
    expect(exported.reviewedBy).toBe('qa-user')
  })

  it('omits the revision parameter when none is pinned', async () => {
    const { fetchMock, options } = makeOptions()
    await fetchAssistantArtifactExport(options, 'artifact-9')
    const call = fetchMock.mock.calls[fetchMock.mock.calls.length - 1] as unknown as [string, RequestInit]
    expect(call[0]).toBe('http://backend.local/api/ai/assistant/artifacts/artifact-9/export')
  })



  it('lists artifacts for the pinned project and forwards the limit', async () => {
    const { fetchMock, options } = makeOptions()
    fetchMock.mockResolvedValue(okJson({
      artifacts: [{
        artifactId: 'artifact-1', runId: 'assistant-1', taskType: 'bug_analysis', projectId: 1,
        revision: 1, acceptanceState: 'PENDING_HUMAN_CONFIRMATION', status: 'waiting_review',
        reviewedBy: '', createdAt: '2026-10-12T08:42:44Z'
      }],
      limit: 5
    }) as unknown as Response)
    const listed = await fetchAssistantArtifacts(options, 1, 5)
    const call = fetchMock.mock.calls[fetchMock.mock.calls.length - 1] as unknown as [string, RequestInit]
    expect(call[0]).toBe('http://backend.local/api/ai/assistant/artifacts?projectId=1&limit=5')
    expect(listed.artifacts[0]?.status).toBe('waiting_review')
  })

  it('omits the limit when the caller does not pin one', async () => {
    const { fetchMock, options } = makeOptions()
    await fetchAssistantArtifacts(options, 7)
    const call = fetchMock.mock.calls[fetchMock.mock.calls.length - 1] as unknown as [string, RequestInit]
    expect(call[0]).toBe('http://backend.local/api/ai/assistant/artifacts?projectId=7')
  })



  it('lists the draft point directory through the assistant endpoint', async () => {
    const { fetchMock, options } = makeOptions()
    fetchMock.mockResolvedValue(okJson({
      items: [{ pointId: 'point-1', title: '登录跳转', moduleName: '认证', caseKind: 'interface',
        reviewStatus: 'confirmed', updatedAt: '2026-10-12T08:00:00Z' }],
      limit: 50
    }) as unknown as Response)
    const listed = await fetchAssistantDraftPoints(options, 1, 50)
    const call = fetchMock.mock.calls[fetchMock.mock.calls.length - 1] as unknown as [string, RequestInit]
    expect(call[0]).toBe('http://backend.local/api/ai/assistant/draft-points?projectId=1&limit=50')
    expect(listed.items[0]?.reviewStatus).toBe('confirmed')
  })

})

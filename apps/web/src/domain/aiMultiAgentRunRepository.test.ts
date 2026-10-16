import { describe, expect, it, vi } from 'vitest'
import {
  ignoreAiMultiAgentConflict,
  listAiMultiAgentGraph,
  listAiMultiAgentQualityScores,
  mergeAiMultiAgentRun,
  startAiMultiAgentRun
} from './aiMultiAgentRunRepository'

describe('aiMultiAgentRunRepository', () => {
  it('starts multi-agent run through backend API', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({
      runId: 'multi-agent-run-1',
      graphId: 'graph-1',
      status: 'merge_ready',
      nodeCount: 5
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))

    const result = await startAiMultiAgentRun({ projectId: 7, targetCaseKind: 'web_automation' }, { fetchImpl })

    const calls = fetchImpl.mock.calls as unknown as [string, RequestInit | undefined][]
    expect(calls[0]?.[0]).toBe('http://127.0.0.1:18080/api/ai/multi-agent-runs')
    expect(calls[0]?.[1]).toMatchObject({ method: 'POST' })
    expect(JSON.parse(String(calls[0]?.[1]?.body))).toMatchObject({ projectId: 7, targetCaseKind: 'web_automation' })
    expect(result.runId).toBe('multi-agent-run-1')
  })

  it('loads graph and quality scores', async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ nodes: [{ nodeId: 'node-1', executionOrder: 1 }] }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ items: [{ qualityDimension: 'coverage', score: 0.9 }] }), { status: 200, headers: { 'Content-Type': 'application/json' } }))

    await listAiMultiAgentGraph('run-1', { fetchImpl })
    await listAiMultiAgentQualityScores('run-1', { fetchImpl })

    const calls = fetchImpl.mock.calls as unknown as [string, RequestInit | undefined][]
    expect(calls[0]?.[0]).toBe('http://127.0.0.1:18080/api/ai/multi-agent-runs/run-1/graph')
    expect(calls[1]?.[0]).toBe('http://127.0.0.1:18080/api/ai/multi-agent-runs/run-1/quality-scores')
  })

  it('merges multi-agent candidates and ignores conflicts through backend APIs', async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        status: 'merged',
        mergedCount: 1,
        unresolvedConflictCount: 0,
        createdDraftPointIds: ['point-1']
      }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        conflictId: 'conflict-1',
        resolutionStatus: 'ignored'
      }), { status: 200, headers: { 'Content-Type': 'application/json' } }))

    const merge = await mergeAiMultiAgentRun('run-1', {
      candidates: [{
        title: '登录成功',
        targetKey: '登录页/提交',
        caseKind: 'web_automation',
        priority: 'P1',
        expectedResult: '进入首页',
        sourceEvidence: ['需求第1段'],
        suggestedSteps: ['打开登录页', '点击登录']
      }]
    }, { fetchImpl })
    const ignored = await ignoreAiMultiAgentConflict('conflict-1', '接受当前候选', { fetchImpl })

    const calls = fetchImpl.mock.calls as unknown as [string, RequestInit | undefined][]
    expect(calls[0]?.[0]).toBe('http://127.0.0.1:18080/api/ai/multi-agent-runs/run-1/merge')
    expect(calls[0]?.[1]).toMatchObject({ method: 'POST' })
    expect(JSON.parse(String(calls[0]?.[1]?.body))).toMatchObject({
      candidates: [{ targetKey: '登录页/提交' }]
    })
    expect(calls[1]?.[0]).toBe('http://127.0.0.1:18080/api/ai/agent-conflicts/conflict-1/ignore')
    expect(merge.createdDraftPointIds).toEqual(['point-1'])
    expect(ignored.resolutionStatus).toBe('ignored')
  })
})

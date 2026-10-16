import { describe, expect, it, vi } from 'vitest'
import {
  cancelWebCaseAgentRun,
  confirmAgentMemory,
  confirmAllWebCasePlanArtifacts,
  createAgentMemory,
  disableAgentMemory,
  deleteAgentMemory,
  getAgentRules,
  getWebCaseQualityInsight,
  getWebCaseQualityRating,
  getWebCaseRegressionComparison,
  listAgentMemories,
  listLocatorKnowledge,
  listWebCaseAgentEvents,
  listWebCaseQualityRatings,
  publishAgentRule,
  rateWebCaseQuality,
  retryWebCaseAgentRun,
  resumeWebCaseAgentRun,
  saveAgentRuleDraft,
  startWebCaseAgentRun
} from './webCaseAgentRepository'

describe('webCaseAgentRepository', () => {
  it('starts a run with typed agent endpoint and body', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({ runId: 'run-1', status: 'QUEUED', currentStage: 'PREFLIGHT' }), { status: 200 }))
    const result = await startWebCaseAgentRun({ projectId: 1, pointIds: ['p-1'], environmentId: 'env', baseUrl: 'http://app', browser: 'chromium', viewport: '1440x900' }, { fetchImpl })
    expect(result.runId).toBe('run-1')
    expect(result.status).toBe('queued')
    expect(fetchImpl).toHaveBeenCalledWith(expect.stringContaining('/api/ai/web-case-agent-runs'), expect.objectContaining({ method: 'POST' }))
  })

  it('normalizes Java enum casing for event timeline rendering', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify([
      { eventId: 'evt-1', runId: 'run-1', sequenceNo: 1, stage: 'GENERATION', severity: 'info', summary: '生成中' }
    ]), { status: 200 }))
    const result = await listWebCaseAgentEvents('run-1', 0, { fetchImpl })
    expect(result[0].stage).toBe('GENERATION')
  })

  it('preserves structured exploration details on legacy and new events', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify([
      { eventId: 'evt-1', runId: 'run-1', sequenceNo: 1, stage: 'EVIDENCE', eventType: 'ACTION', severity: 'info', summary: '探索', details: { round: 2, maxRounds: 5, pageScore: 35, totalScore: 70, actionType: 'menu-search' } },
      { eventId: 'evt-2', runId: 'run-1', sequenceNo: 2, stage: 'EVIDENCE', eventType: 'ACTION', severity: 'info', summary: '旧事件' }
    ]), { status: 200 }))

    const result = await listWebCaseAgentEvents('run-1', 0, { fetchImpl })

    expect(result[0].details?.round).toBe(2)
    expect(result[0].details?.actionType).toBe('menu-search')
    expect(result[1].details).toBeUndefined()
  })

  it('keeps the runner stable failure categories instead of folding them', async () => {
    // navigation_failed / unsafe_action / locator_persistence 是 runner 的稳定分类：
    // 折叠成 wrong_page/unsupported_dom 会让用户看到错误的原因和处理建议（平台侧同步修复）。
    const categories = ['navigation_failed', 'unsafe_action', 'locator_persistence', 'unsupported_action']
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify(categories.map((category, index) => ({
      eventId: `evt-${index}`, runId: 'run-1', sequenceNo: index, stage: 'EVIDENCE', eventType: 'ACTION', severity: 'error', summary: '探索失败',
      details: { category }
    }))), { status: 200 }))

    const result = await listWebCaseAgentEvents('run-1', 0, { fetchImpl })

    expect(result.map((event) => event.details?.category)).toEqual(categories)
    // 历史词仍按旧口径折叠（不在 runner 稳定表里）。
    const legacyFetch = vi.fn(async () => new Response(JSON.stringify([
      { eventId: 'evt-legacy', runId: 'run-1', sequenceNo: 9, stage: 'EVIDENCE', eventType: 'ACTION', severity: 'error', summary: '探索失败', details: { category: 'page_error' } }
    ]), { status: 200 }))
    const legacy = await listWebCaseAgentEvents('run-1', 0, { fetchImpl: legacyFetch })
    expect(legacy[0].details?.category).toBe('unsupported_dom')
  })

  it('maps safe exploration failure diagnostics without dropping aliases', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify([
      {
        eventId: 'evt-failure', runId: 'run-1', sequenceNo: 3, stage: 'EVIDENCE', eventType: 'ACTION', severity: 'info', summary: '探索失败',
        details: {
          round: 3,
          maxRounds: 5,
          scores: { page: 40, element: 12, total: 52 },
          safePageUrl: 'https://example.test/settings',
          category: 'network_blocked',
          retryable: true,
          failureReason: '页面请求被阻断',
          locatorFingerprint: 'abc123',
          accessibleName: '保存',
          accessName: '保存'
        }
      }
    ]), { status: 200 }))

    const result = await listWebCaseAgentEvents('run-1', 0, { fetchImpl })

    expect(result[0].details).toMatchObject({
      category: 'network_blocked_unknown', retryable: true, round: 3, maxRounds: 5,
      safePageUrl: 'https://example.test/settings', locatorFingerprint: 'abc123', accessibleName: '保存', accessName: '保存'
    })
    expect(result[0].details?.scores).toEqual({ page: 40, element: 12, total: 52 })
  })

  it('normalizes structured handoff, network and repair trail details without leaking secrets', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify([
      {
        eventId: 'evt-structured',
        runId: 'run-1',
        sequenceNo: 4,
        stage: 'EVIDENCE',
        eventType: 'ERROR',
        severity: 'error',
        summary: '结构化诊断',
        details: {
          category: 'manual_required',
          failureReason: 'captcha_required token=secret locator=button[data-token="secret"]',
          handoff: { required: true, mode: 'manual', reason: 'manual_required', resumeSupported: false },
          network: {
            criticalBlocked: [{ origin: 'https://api.example.test/', path: '/api/list?token=secret', resourceType: 'xhr', criticality: 'critical', failureType: 'network_blocked' }],
            optionalBlocked: [{ origin: 'https://cdn.example.test', path: '/assets/app.js?token=secret', resourceType: 'script', criticality: 'optional', failureType: 'network_blocked_optional' }]
          },
          repairTrail: [
            { category: 'round-1', round: 1, changedFingerprint: false },
            { category: 'round-2', round: 2, changedFingerprint: true },
            { category: 'round-3', round: 3, changedFingerprint: true },
            { category: 'round-4', round: 4, changedFingerprint: false },
            { category: 'round-5', round: 5, changedFingerprint: false },
            { category: 'round-6', round: 6, changedFingerprint: true }
          ]
        }
      }
    ]), { status: 200 }))

    const result = await listWebCaseAgentEvents('run-1', 0, { fetchImpl })
    const details = result[0].details

    expect(details?.category).toBe('manual_review_required')
    expect(details?.failureReason).not.toContain('secret')
    expect(details?.failureReason).not.toContain('button[data-token')
    expect(details?.handoff).toMatchObject({ required: true, mode: 'manual', reason: 'manual_review_required', resumeSupported: false })
    expect(details?.network?.criticalBlocked?.[0]).toMatchObject({ origin: 'https://api.example.test/', path: '/api/list', failureType: 'network_blocked_unknown' })
    expect(details?.network?.optionalBlocked?.[0]).toMatchObject({ origin: 'https://cdn.example.test/', path: '/assets/app.js', failureType: 'network_blocked_unknown' })
    expect(details?.repairTrail).toHaveLength(5)
    expect(details?.repairTrail?.[0]).toMatchObject({ category: 'round-2', round: 2 })
    expect(JSON.stringify(details)).not.toContain('secret')
  })

  it('normalizes page URLs before exposing exploration details', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify([{
      eventId: 'evt-url', runId: 'run-1', sequenceNo: 4, stage: 'EVIDENCE', eventType: 'ACTION', severity: 'info', summary: '安全路由',
      details: { pageUrl: 'https://user:pass@example.test/settings?token=secret#private-fragment' }
    }]), { status: 200 }))

    const result = await listWebCaseAgentEvents('run-1', 0, { fetchImpl })

    expect(result[0].details?.pageUrl).toBe('https://example.test/settings')
    expect(result[0].details?.safePageUrl).toBe('https://example.test/settings')
  })

  it('accepts an empty successful response from void agent commands', async () => {
    const fetchImpl = vi.fn(async () => new Response('', { status: 200 }))

    await expect(confirmAllWebCasePlanArtifacts('run-1', { fetchImpl })).resolves.toBeUndefined()
  })
})

describe('webCaseAgentRepository quality rating', () => {
  it('posts a human quality rating for a run', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({
      ratingId: 'quality-rating-1',
      runId: 'run-1',
      projectId: 7,
      overallScore: 8,
      dimensionScores: { coverage: 8 },
      comment: '覆盖较全',
      baseline: true,
      createdAt: '2026-08-14T00:00:00Z'
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))

    const result = await rateWebCaseQuality('run-1', { overallScore: 8, dimensionScores: { coverage: 8 }, comment: '覆盖较全' }, { fetchImpl })

    const calls = fetchImpl.mock.calls as unknown as [string, RequestInit | undefined][]
    expect(calls[0]?.[0]).toBe('http://127.0.0.1:18080/api/ai/web-case-agent-runs/run-1/quality-rating')
    expect(calls[0]?.[1]).toMatchObject({ method: 'POST' })
    expect(JSON.parse(String(calls[0]?.[1]?.body))).toMatchObject({ overallScore: 8, comment: '覆盖较全' })
    expect(result.overallScore).toBe(8)
    expect(result.baseline).toBe(true)
  })

  it('lists ratings for a project', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({ items: [] }), { status: 200, headers: { 'Content-Type': 'application/json' } }))

    await listWebCaseQualityRatings(7, { fetchImpl })

    const calls = fetchImpl.mock.calls as unknown as [string, RequestInit | undefined][]
    expect(calls[0]?.[0]).toBe('http://127.0.0.1:18080/api/ai/web-case-agent-runs/quality-ratings?projectId=7')
  })
})

describe('webCaseAgentRepository regression comparison', () => {
  it('gets a regression comparison for a run', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({
      runId: 'run-1',
      baselineRunId: 'baseline-run-1',
      baselineOverallScore: 8,
      available: true,
      points: [{
        pointId: 'P-1',
        baselineCount: 2,
        recalledCount: 1,
        addedCount: 1,
        lostCount: 1,
        added: [{ title: '新增用例', assertions: '期望结果新增' }],
        lost: [{ title: '丢失用例', assertions: '期望结果丢失' }]
      }],
      summary: { baselineTotal: 2, recalled: 1, added: 1, lost: 1, recallRatio: 0.5 }
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))

    const result = await getWebCaseRegressionComparison('run-1', { fetchImpl })

    const calls = fetchImpl.mock.calls as unknown as [string, RequestInit | undefined][]
    expect(calls[0]?.[0]).toBe('http://127.0.0.1:18080/api/ai/web-case-agent-runs/run-1/regression-comparison')
    expect(result.available).toBe(true)
    expect(result.summary?.recalled).toBe(1)
    expect(result.points[0].lost[0].title).toBe('丢失用例')
  })
})

describe('webCaseAgentRepository quality insight', () => {
  it('gets an aggregated quality insight for a project', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({
      projectId: 7,
      ratedCount: 3,
      baselineCount: 2,
      overallAvg: 7.0,
      recallAvg: 0.5,
      lostCaseCount: 1,
      dimensionAvgs: { coverage: 6.0, source_traceability: 7.0 },
      weakestDimensions: [{ key: 'coverage', avg: 6.0 }],
      lostCases: [{ title: '丢失用例', count: 1 }]
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))

    const result = await getWebCaseQualityInsight(7, { fetchImpl })

    const calls = fetchImpl.mock.calls as unknown as [string, RequestInit | undefined][]
    expect(calls[0]?.[0]).toBe('http://127.0.0.1:18080/api/ai/web-case-agent-runs/quality-insight?projectId=7')
    expect(result.ratedCount).toBe(3)
    expect(result.weakestDimensions[0].key).toBe('coverage')
    expect(result.lostCases[0].title).toBe('丢失用例')
  })
})

describe('webCaseAgentRepository run control', () => {
  it.each([
    ['cancel', cancelWebCaseAgentRun, 'cancel'],
    ['retry', retryWebCaseAgentRun, 'retry'],
    ['resume', resumeWebCaseAgentRun, 'resume']
  ] as const)('%s transitions a run and normalizes status', async (_name, action, pathPart) => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({ runId: 'run-1', status: 'RUNNING', currentStage: 'PLANNING' }), { status: 200 }))

    const result = await action('run-1', { fetchImpl })

    const calls = fetchImpl.mock.calls as unknown as [string, RequestInit | undefined][]
    expect(calls[0]?.[0]).toBe(`http://127.0.0.1:18080/api/ai/web-case-agent-runs/run-1/${pathPart}`)
    expect(calls[0]?.[1]?.method).toBe('POST')
    expect(result.status).toBe('running')
    expect(result.currentStage).toBe('PLANNING')
  })

  it('throws a readable error on a failed request', async () => {
    const fetchImpl = vi.fn(async () => new Response('服务异常', { status: 500 }))

    await expect(retryWebCaseAgentRun('run-1', { fetchImpl })).rejects.toThrow('服务异常')
  })
})

describe('webCaseAgentRepository quality rating readback', () => {
  it('reads the persisted rating for a run (P1.3 rating refresh)', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({
      ratingId: 'rating-1', runId: 'run-1', projectId: 7, overallScore: 9,
      dimensionScores: { coverage: 9 }, comment: '高质', baseline: true, createdAt: '2026-08-14T00:00:00Z'
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))

    const result = await getWebCaseQualityRating('run-1', { fetchImpl })

    const calls = fetchImpl.mock.calls as unknown as [string, RequestInit | undefined][]
    expect(calls[0]?.[0]).toBe('http://127.0.0.1:18080/api/ai/web-case-agent-runs/run-1/quality-rating')
    expect(result?.overallScore).toBe(9)
    expect(result?.baseline).toBe(true)
  })

  it('returns null when the run has never been rated', async () => {
    const fetchImpl = vi.fn(async () => new Response(null, { status: 204 }))

    const result = await getWebCaseQualityRating('run-1', { fetchImpl })

    // request() 对 204 返回 undefined;对调用方而言与 null 同为「无评分」
    expect(result).toBeFalsy()
  })
})

describe('webCaseAgentRepository locator knowledge (P1.2 evidence)', () => {
  it('lists locator knowledge for the evidence dialog', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({ items: [{ entryId: 'loc-1', naturalName: '登录按钮' }] }), { status: 200, headers: { 'Content-Type': 'application/json' } }))

    const result = await listLocatorKnowledge(7, { fetchImpl })

    const calls = fetchImpl.mock.calls as unknown as [string, RequestInit | undefined][]
    expect(calls[0]?.[0]).toBe('http://127.0.0.1:18080/api/ai/agent-assets/locator-knowledge?projectId=7')
    expect(result.items[0].naturalName).toBe('登录按钮')
  })
})

describe('webCaseAgentRepository agent memories', () => {
  it('lists pending-only memories for a project', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify([]), { status: 200, headers: { 'Content-Type': 'application/json' } }))

    await listAgentMemories(7, true, { fetchImpl })

    const calls = fetchImpl.mock.calls as unknown as [string, RequestInit | undefined][]
    expect(calls[0]?.[0]).toBe('http://127.0.0.1:18080/api/ai/agent-assets/memories?projectId=7&pendingOnly=true')
  })

  it('creates and confirms a candidate memory', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({ memoryId: 'memory-1', status: 'ACTIVE', scopeType: 'PROJECT' }), { status: 200, headers: { 'Content-Type': 'application/json' } }))

    const created = await createAgentMemory({ projectId: 7, moduleName: '登录', category: 'locator', content: '经验' }, { fetchImpl })
    const confirmed = await confirmAgentMemory('memory-1', 'PROJECT', [], { fetchImpl })

    const calls = fetchImpl.mock.calls as unknown as [string, RequestInit | undefined][]
    expect(calls[0]?.[0]).toBe('http://127.0.0.1:18080/api/ai/agent-assets/memories')
    expect(calls[0]?.[1]?.method).toBe('POST')
    expect(calls[1]?.[0]).toBe('http://127.0.0.1:18080/api/ai/agent-assets/memories/memory-1/confirm')
    expect(confirmed.status).toBe('ACTIVE')
  })

  it('disables and deletes a memory via typed endpoints', async () => {
    const fetchImpl = vi.fn(async () => new Response(null, { status: 204 }))

    await disableAgentMemory('memory-1', { fetchImpl })
    await deleteAgentMemory('memory-1', { fetchImpl })

    const calls = fetchImpl.mock.calls as unknown as [string, RequestInit | undefined][]
    expect(calls[0]?.[0]).toBe('http://127.0.0.1:18080/api/ai/agent-assets/memories/memory-1/disable')
    expect(calls[1]?.[0]).toBe('http://127.0.0.1:18080/api/ai/agent-assets/memories/memory-1')
    expect(calls[1]?.[1]?.method).toBe('DELETE')
  })
})

describe('webCaseAgentRepository agent rules', () => {
  it('reads and publishes agent rules with optional project scope', async () => {
    const fetchImpl = vi.fn(async () => new Response(JSON.stringify({ ruleSetId: 'web-case-agent', version: 2, status: 'published' }), { status: 200, headers: { 'Content-Type': 'application/json' } }))

    await getAgentRules(7, { fetchImpl })
    await saveAgentRuleDraft('content', { '01-preflight': 'x' }, 7, { fetchImpl })
    await publishAgentRule(2, 7, { fetchImpl })

    const calls = fetchImpl.mock.calls as unknown as [string, RequestInit | undefined][]
    expect(calls[0]?.[0]).toBe('http://127.0.0.1:18080/api/ai/agent-assets/rules?projectId=7')
    expect(calls[1]?.[0]).toBe('http://127.0.0.1:18080/api/ai/agent-assets/rules/web-case-agent/draft?projectId=7')
    expect(calls[2]?.[0]).toBe('http://127.0.0.1:18080/api/ai/agent-assets/rules/web-case-agent/publish?projectId=7')
  })
})

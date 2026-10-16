import { describe, expect, it } from 'vitest'
import {
  buildAiReviewInsightView,
  buildMultiAgentMergeCandidatesFromDraftPoints,
  buildMultiAgentMergeCandidatesFromSuggestions,
  groupAutomationSuggestionsByCaseKind,
  groupAgentConflictsByType,
  qualityScoreLevel,
  sortAgentGraphNodes
} from './aiMultiAgentRunView'

describe('aiMultiAgentRunView', () => {
  it('sorts graph nodes by execution order', () => {
    expect(sortAgentGraphNodes([
      { nodeId: 'node-3', agentId: 'quality-review', agentRole: 'quality_review', executionOrder: 3, status: 'succeeded' },
      { nodeId: 'node-1', agentId: 'requirement-understanding', agentRole: 'requirement_understanding', executionOrder: 1, status: 'succeeded' },
      { nodeId: 'node-2', agentId: 'test-design', agentRole: 'test_design', executionOrder: 2, status: 'succeeded' }
    ]).map((node) => node.agentId)).toEqual([
      'requirement-understanding',
      'test-design',
      'quality-review'
    ])
  })

  it('groups conflict rows by type', () => {
    expect(groupAgentConflictsByType([
      { conflictId: 'c1', conflictType: 'priority_conflict', targetKey: 'login', resolutionStatus: 'unresolved' },
      { conflictId: 'c2', conflictType: 'expected_result_conflict', targetKey: 'login', resolutionStatus: 'resolved' },
      { conflictId: 'c3', conflictType: 'priority_conflict', targetKey: 'export', resolutionStatus: 'ignored' }
    ])).toEqual([
      {
        conflictType: 'priority_conflict',
        unresolvedCount: 1,
        items: [
          { conflictId: 'c1', conflictType: 'priority_conflict', targetKey: 'login', resolutionStatus: 'unresolved' },
          { conflictId: 'c3', conflictType: 'priority_conflict', targetKey: 'export', resolutionStatus: 'ignored' }
        ]
      },
      {
        conflictType: 'expected_result_conflict',
        unresolvedCount: 0,
        items: [
          { conflictId: 'c2', conflictType: 'expected_result_conflict', targetKey: 'login', resolutionStatus: 'resolved' }
        ]
      }
    ])
  })

  it('maps quality scores to display levels', () => {
    expect(qualityScoreLevel({ qualityDimension: 'coverage', score: 0.9, scoreLevel: 'good' })).toBe('success')
    expect(qualityScoreLevel({ qualityDimension: 'testability', score: 0.5, scoreLevel: 'warning' })).toBe('warning')
    expect(qualityScoreLevel({ qualityDimension: 'review_risk', score: 0.2, scoreLevel: 'risk' })).toBe('danger')
  })

  it('groups automation suggestions by case kind', () => {
    expect(groupAutomationSuggestionsByCaseKind([
      { suggestionId: 's1', draftPointId: 'p1', caseKind: 'web_automation', automationReadiness: 'ready' },
      { suggestionId: 's2', draftPointId: 'p2', caseKind: 'interface', automationReadiness: 'partial' },
      { suggestionId: 's3', draftPointId: 'p3', caseKind: 'web_automation', automationReadiness: 'ready' }
    ])).toEqual([
      {
        caseKind: 'web_automation',
        readyCount: 2,
        items: [
          { suggestionId: 's1', draftPointId: 'p1', caseKind: 'web_automation', automationReadiness: 'ready' },
          { suggestionId: 's3', draftPointId: 'p3', caseKind: 'web_automation', automationReadiness: 'ready' }
        ]
      },
      {
        caseKind: 'interface',
        readyCount: 0,
        items: [
          { suggestionId: 's2', draftPointId: 'p2', caseKind: 'interface', automationReadiness: 'partial' }
        ]
      }
    ])
  })

  it('normalizes stable finding IDs and distinguishes coverage gaps from suggestions', () => {
    const output = {
      reviewStage: 'requirement',
      aiReview: {
        reviewSummary: '需求点缺少异常登录和权限边界。',
        supplementSuggestions: [
          { findingId: 'suggestion-login-failure', title: '补充登录失败提示校验', reason: '当前只覆盖成功链路', priority: 'P1' },
          '补充账号锁定后的恢复提示'
        ],
        missingPoints: [
          { id: 'gap-permission-page', title: '补充无权限访问页面', suggestion: '验证跳转到无权限页', priority: 'P0' },
          { id: 'shared-review-id', title: '补充共享 ID 的覆盖缺口' }
        ],
        suggestions: [{ id: 'shared-review-id', title: '补充共享 ID 的改进建议' }]
      }
    }
    const first = buildAiReviewInsightView(output)
    const reordered = buildAiReviewInsightView({
      ...output,
      aiReview: { ...output.aiReview, supplementSuggestions: [...output.aiReview.supplementSuggestions].reverse() }
    })

    expect(first.stage).toBe('requirement')
    expect(first.summary).toBe('需求点缺少异常登录和权限边界。')
    expect(first.suggestions).toEqual(expect.arrayContaining([
      expect.objectContaining({ findingId: 'coverage_gap:gap-permission-page', category: 'coverage_gap', title: '补充无权限访问页面' }),
      expect.objectContaining({ findingId: 'suggestion:suggestion-login-failure', category: 'suggestion', title: '补充登录失败提示校验' })
    ]))
    expect(first.suggestions.filter((item) => item.findingId.endsWith('shared-review-id')).map((item) => item.findingId).sort()).toEqual([
      'coverage_gap:shared-review-id',
      'suggestion:shared-review-id'
    ])
    expect(Object.fromEntries(first.suggestions.map((item) => [item.title, item.findingId])))
      .toEqual(Object.fromEntries(reordered.suggestions.map((item) => [item.title, item.findingId])))
  })

  it('parses legacy AI review JSON stored as a string', () => {
    const insight = buildAiReviewInsightView({
      reviewStage: 'case',
      aiReview: JSON.stringify({
        reviewSummary: '用例覆盖摘要',
        missingPoints: [{ title: '补充失败路径', priority: 'P1' }]
      })
    })

    expect(insight.summary).toBe('用例覆盖摘要')
    expect(insight.suggestions).toEqual([
      expect.objectContaining({ title: '补充失败路径', category: 'coverage_gap' })
    ])
  })

  it('builds merge candidates from AI draft test points with source evidence and steps', () => {
    const candidates = buildMultiAgentMergeCandidatesFromDraftPoints([{
      pointId: 'point-1',
      runId: 'run-1',
      projectId: 7,
      sourceDocumentId: 'doc-1',
      sourceChunkId: 'chunk-1',
      title: '登录页提交成功跳转首页',
      module: '登录',
      pageOrFeature: '登录页/提交',
      caseKind: 'web_automation',
      testType: 'multi_agent',
      priority: 'P1',
      objective: '验证登录成功链路',
      steps: '打开登录页\n输入账号密码\n点击登录',
      expectedResult: '系统跳转首页',
      sourceFile: '登录需求.md',
      sourceText: '用户输入有效账号密码后进入首页',
      confidenceScore: 0.9,
      inferred: false,
      reviewStatus: 'ai_generated',
      metadata: {},
      createdAt: '2026-07-07T00:00:00Z',
      updatedAt: '2026-07-07T00:00:00Z'
    }])

    expect(candidates).toEqual([{
      title: '登录页提交成功跳转首页',
      targetKey: '登录页/提交',
      caseKind: 'web_automation',
      priority: 'P1',
      expectedResult: '系统跳转首页',
      sourceEvidence: ['登录需求.md', '用户输入有效账号密码后进入首页'],
      suggestedSteps: ['打开登录页', '输入账号密码', '点击登录']
    }])
  })

  it('builds merge candidates only from review suggestions', () => {
    expect(buildMultiAgentMergeCandidatesFromSuggestions([
      { findingId: 'coverage_gap:g1', category: 'coverage_gap', title: '补充权限拒绝场景', detail: '预期展示无权限提示', priority: 'P1' },
      { findingId: 'suggestion:s1', category: 'suggestion', title: '', detail: '忽略空建议', priority: 'P2' }
    ])).toEqual([{
      title: '补充权限拒绝场景',
      targetKey: '补充权限拒绝场景',
      caseKind: 'web_automation',
      priority: 'P1',
      expectedResult: '预期展示无权限提示',
      sourceEvidence: [],
      suggestedSteps: ['补充权限拒绝场景']
    }])
  })
})

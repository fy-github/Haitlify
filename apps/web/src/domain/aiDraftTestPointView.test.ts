import { describe, expect, it } from 'vitest'
import { buildAiDraftTestPointView, type AiDraftTestPointFilter } from './aiDraftTestPointView'
import type { AiDraftTestPoint } from './aiPlatformRepository'

const makePoint = (patch: Partial<AiDraftTestPoint>): AiDraftTestPoint => ({
  pointId: patch.pointId || 'point-1',
  runId: patch.runId || 'run-1',
  projectId: patch.projectId || 12,
  sourceDocumentId: patch.sourceDocumentId || 'doc-1',
  sourceChunkId: patch.sourceChunkId || 'chunk-1',
  title: patch.title || '登录成功',
  module: patch.module || '登录',
  pageOrFeature: patch.pageOrFeature || '登录页',
  caseKind: patch.caseKind || 'web_automation',
  testType: patch.testType || 'positive',
  priority: patch.priority || 'P1',
  objective: patch.objective || '验证登录成功',
  steps: patch.steps || '输入正确账号密码',
  expectedResult: patch.expectedResult || '进入首页',
  sourceFile: patch.sourceFile || '登录需求.pdf',
  sourceText: patch.sourceText || '用户可以登录',
  confidenceScore: patch.confidenceScore ?? 0.92,
  inferred: patch.inferred ?? false,
  reviewStatus: patch.reviewStatus || 'ai_generated',
  metadata: patch.metadata || {},
  createdAt: patch.createdAt || '2026-07-03T10:00:00Z',
  updatedAt: patch.updatedAt || '2026-07-03T10:00:00Z'
})

describe('aiDraftTestPointView', () => {
  const points = [
    makePoint({ pointId: 'point-1', module: '登录', sourceDocumentId: 'doc-login', sourceFile: '登录需求.pdf', confidenceScore: 0.93, reviewStatus: 'confirmed' }),
    makePoint({ pointId: 'point-2', module: '项目', sourceDocumentId: 'doc-project', sourceFile: '项目需求.pdf', confidenceScore: 0.72, reviewStatus: 'modified', inferred: true }),
    makePoint({ pointId: 'point-3', module: '项目', sourceDocumentId: 'doc-project', sourceFile: '项目需求.pdf', confidenceScore: 0.44, reviewStatus: 'rejected' })
  ]

  it('filters draft points by module status confidence and source document', () => {
    const filter: AiDraftTestPointFilter = {
      module: '项目',
      status: 'modified',
      confidence: 'medium',
      sourceDocumentId: 'doc-project'
    }

    const view = buildAiDraftTestPointView(points, filter)

    expect(view.items.map((point) => point.pointId)).toEqual(['point-2'])
    expect(view.summary.total).toBe(1)
    expect(view.summary.inferred).toBe(1)
  })

  it('builds stable filter options and groups points by module', () => {
    const view = buildAiDraftTestPointView(points, {
      module: 'ALL',
      status: 'ALL',
      confidence: 'ALL',
      sourceDocumentId: 'ALL'
    })

    expect(view.moduleOptions).toEqual([
      { value: '登录', count: 1 },
      { value: '项目', count: 2 }
    ])
    expect(view.sourceOptions).toEqual([
      { value: 'doc-login', label: '登录需求.pdf', count: 1 },
      { value: 'doc-project', label: '项目需求.pdf', count: 2 }
    ])
    expect(view.groups.map((group) => [group.module, group.items.length])).toEqual([
      ['登录', 1],
      ['项目', 2]
    ])
  })

  it('classifies confidence into high medium and low buckets', () => {
    expect(buildAiDraftTestPointView(points, { confidence: 'high' }).items.map((point) => point.pointId)).toEqual(['point-1'])
    expect(buildAiDraftTestPointView(points, { confidence: 'medium' }).items.map((point) => point.pointId)).toEqual(['point-2'])
    expect(buildAiDraftTestPointView(points, { confidence: 'low' }).items.map((point) => point.pointId)).toEqual(['point-3'])
  })
})

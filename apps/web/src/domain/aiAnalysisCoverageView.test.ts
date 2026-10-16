import { describe, expect, it } from 'vitest'
import {
  buildAiAnalysisCoverageView,
  type AiAnalysisChunkProgress,
  type AiUnclearQuestion
} from './aiAnalysisCoverageView'
import type { AiDraftTestPoint } from './aiPlatformRepository'

const makePoint = (patch: Partial<AiDraftTestPoint>): AiDraftTestPoint => ({
  pointId: patch.pointId || 'point-1',
  runId: patch.runId || 'run-1',
  projectId: patch.projectId || 10,
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
  sourceFile: patch.sourceFile || '登录需求.md',
  sourceText: patch.sourceText || '用户登录',
  confidenceScore: patch.confidenceScore ?? 0.9,
  inferred: patch.inferred ?? false,
  reviewStatus: patch.reviewStatus || 'ai_generated',
  metadata: patch.metadata || {},
  createdAt: patch.createdAt || '2026-07-03T10:00:00Z',
  updatedAt: patch.updatedAt || '2026-07-03T10:00:00Z'
})

describe('aiAnalysisCoverageView', () => {
  it('groups coverage rows by module and case kind', () => {
    const view = buildAiAnalysisCoverageView({
      draftPoints: [
        makePoint({ pointId: 'point-1', module: '登录', caseKind: 'web_automation' }),
        makePoint({ pointId: 'point-2', module: '登录', caseKind: 'interface' }),
        makePoint({ pointId: 'point-3', module: '项目', caseKind: 'web_automation' })
      ]
    })

    expect(view.coverageRows).toEqual([
      { key: '登录|interface', module: '登录', caseKind: 'interface', draftPointCount: 1 },
      { key: '登录|web_automation', module: '登录', caseKind: 'web_automation', draftPointCount: 1 },
      { key: '项目|web_automation', module: '项目', caseKind: 'web_automation', draftPointCount: 1 }
    ])
    expect(view.summary.totalDraftPoints).toBe(3)
  })

  it('turns skipped chunks into warning rows', () => {
    const chunks: AiAnalysisChunkProgress[] = [
      { plannedChunkId: 'chunk-1', section: '登录', status: 'succeeded', skipReason: '' },
      { plannedChunkId: 'chunk-2', section: '权限', status: 'skipped', skipReason: 'max_chunks_exceeded' }
    ]

    const view = buildAiAnalysisCoverageView({ draftPoints: [], chunks })

    expect(view.warningRows).toEqual([
      {
        key: 'chunk-2',
        plannedChunkId: 'chunk-2',
        section: '权限',
        message: 'max_chunks_exceeded'
      }
    ])
  })

  it('sorts unclear questions by severity then creation time', () => {
    const questions: AiUnclearQuestion[] = [
      { questionId: 'q-low', severity: 'low', questionText: '低风险问题', sourceRef: 'chunk-3', status: 'open', createdAt: '2026-07-03T10:02:00Z' },
      { questionId: 'q-high-late', severity: 'high', questionText: '高风险问题2', sourceRef: 'chunk-2', status: 'open', createdAt: '2026-07-03T10:03:00Z' },
      { questionId: 'q-high-early', severity: 'high', questionText: '高风险问题1', sourceRef: 'chunk-1', status: 'open', createdAt: '2026-07-03T10:01:00Z' },
      { questionId: 'q-medium', severity: 'medium', questionText: '中风险问题', sourceRef: 'chunk-4', status: 'open', createdAt: '2026-07-03T10:00:00Z' }
    ]

    const view = buildAiAnalysisCoverageView({ draftPoints: [], unclearQuestions: questions })

    expect(view.unclearQuestions.map((question) => question.questionId)).toEqual([
      'q-high-early',
      'q-high-late',
      'q-medium',
      'q-low'
    ])
  })
})

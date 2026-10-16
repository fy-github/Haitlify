import { describe, expect, it } from 'vitest'
import {
  buildAiVisualRequirementView,
  formatVisualRegionCoordinates,
  visualEvidenceForDraftPoint
} from './aiVisualRequirementView'
import type { AiDraftTestPoint } from './aiPlatformRepository'

const makePoint = (patch: Partial<AiDraftTestPoint>): AiDraftTestPoint => ({
  pointId: patch.pointId || 'point-1',
  runId: patch.runId || 'run-1',
  projectId: patch.projectId || 1,
  sourceDocumentId: patch.sourceDocumentId || 'doc-1',
  sourceChunkId: patch.sourceChunkId || '',
  title: patch.title || '按钮可点击',
  module: patch.module || '视觉需求',
  pageOrFeature: patch.pageOrFeature || '登录页',
  caseKind: patch.caseKind || 'web_automation',
  testType: patch.testType || '组件显示/状态测试点',
  priority: patch.priority || 'P2',
  objective: patch.objective || '验证按钮状态',
  steps: patch.steps || '打开页面',
  expectedResult: patch.expectedResult || '按钮正常显示',
  sourceFile: patch.sourceFile || 'login.png',
  sourceText: patch.sourceText || '',
  confidenceScore: patch.confidenceScore ?? 0.86,
  inferred: patch.inferred ?? false,
  reviewStatus: patch.reviewStatus || 'ai_generated',
  metadata: patch.metadata || {
    visualEvidence: {
      assetId: 'vis-1',
      regionId: 'region-1',
      regionType: 'button',
      coordinates: { x: 12, y: 34, width: 160, height: 40 },
      componentHint: 'primary button',
      styleHints: ['blue fill'],
      interactionHints: ['click submits']
    }
  },
  createdAt: patch.createdAt || '2026-07-06T00:00:00Z',
  updatedAt: patch.updatedAt || '2026-07-06T00:00:00Z'
})

describe('aiVisualRequirementView', () => {
  it('groups visual draft points by region type', () => {
    const view = buildAiVisualRequirementView([
      makePoint({ pointId: 'point-1' }),
      makePoint({
        pointId: 'point-2',
        metadata: {
          visualEvidence: {
            assetId: 'vis-1',
            regionId: 'region-2',
            regionType: 'field',
            coordinates: { x: 20, y: 90, width: 240, height: 32 }
          }
        }
      })
    ])

    expect(view.groups.map((group) => [group.regionType, group.items.length])).toEqual([
      ['button', 1],
      ['field', 1]
    ])
  })

  it('renders confidence and inferred status data', () => {
    const view = buildAiVisualRequirementView([
      makePoint({ confidenceScore: 0.91 }),
      makePoint({ pointId: 'point-2', confidenceScore: 0.55, inferred: true })
    ])

    expect(view.items[0].confidenceLabel).toBe('高')
    expect(view.items[0].inferredLabel).toBe('否')
    expect(view.items[1].confidenceLabel).toBe('低')
    expect(view.items[1].inferredLabel).toBe('是')
  })

  it('formats region coordinates for display', () => {
    expect(formatVisualRegionCoordinates({ x: 12, y: 34, width: 160, height: 40 })).toBe('x12 y34 160x40')
  })

  it('extracts visual evidence from draft metadata', () => {
    const evidence = visualEvidenceForDraftPoint(makePoint({}))

    expect(evidence?.assetId).toBe('vis-1')
    expect(evidence?.componentHint).toBe('primary button')
  })
})

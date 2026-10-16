import { describe, expect, it } from 'vitest'
import { buildCaseDesignReviewView } from './caseDesignReviewView'

describe('caseDesignReviewView', () => {
  it('prioritizes pending coverage gaps and keeps finding decisions independent', () => {
    expect(buildCaseDesignReviewView({
      selectedCase: { id: 'TC-003', title: '退款处理超时后的状态回补', module: '退款', sourceFile: '退款需求.md' },
      qualityScores: [
        { qualityDimension: 'coverage', score: 0.92, scoreLevel: 'good' },
        { qualityDimension: 'testability', score: 0.74, scoreLevel: 'warning' }
      ],
      conflicts: [
        { conflictId: 'c1', conflictType: 'timeout', targetKey: 'TC-003', resolutionStatus: 'unresolved' },
        { conflictId: 'c2', conflictType: 'priority', targetKey: 'TC-004', resolutionStatus: 'resolved' }
      ],
      findings: [
        { findingId: 'coverage_gap:gap-refund-timeout', category: 'coverage_gap', title: '退款超时路径缺少异常回补用例', detail: '覆盖退款状态最终一致性', priority: 'P0' },
        { findingId: 'suggestion:suggestion-consistency', category: 'suggestion', title: '补充最终一致性检查', detail: '校验退款单状态', priority: 'P1' },
        { findingId: 'suggestion:suggestion-login', category: 'suggestion', title: '补充登录检查', detail: '校验登录态', priority: 'P2' }
      ],
      findingDecisions: { 'TC-003': { 'suggestion:suggestion-consistency': 'accepted' } }
    })).toMatchObject({
      selectedCaseId: 'TC-003', averageScore: 83, unresolvedConflictCount: 1,
      findingCount: 2, pendingFindingCount: 1, highRiskCount: 2,
      relevantConflictIds: ['c1'],
      relevantFindingIds: ['coverage_gap:gap-refund-timeout', 'suggestion:suggestion-consistency'],
      findings: [
        { findingId: 'coverage_gap:gap-refund-timeout', decision: 'pending' },
        { findingId: 'suggestion:suggestion-consistency', decision: 'accepted' }
      ],
      hasContent: true
    })
  })

  it('returns an empty inspector state without findings', () => {
    expect(buildCaseDesignReviewView({ selectedCase: undefined, qualityScores: [], conflicts: [], findings: [], findingDecisions: {} }).hasContent).toBe(false)
  })
})

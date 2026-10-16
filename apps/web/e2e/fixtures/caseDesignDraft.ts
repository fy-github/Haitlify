import type { AiAnalysisRun, AiDraftTestPoint } from '../../src/domain/aiPlatformRepository'
import type { CaseDesignDraftSnapshot } from '../../src/domain/caseDesignDraftRepository'
import type { GeneratedWebAutomationCase } from '../../src/domain/webAutomationCase'

export type CaseDesignFixtureVariant =
  | 'default'
  | 'first-empty'
  | 'loading'
  | 'local-error'
  | 'partial'

const projectName = '华东区域跨境订单履约与退款协同平台'
const sourceFile = '跨境订单退款状态回补与最终一致性需求说明.md'

const analysisRun = (status: 'running' | 'succeeded'): AiAnalysisRun => ({
  runId: 'analysis-fixture',
  projectId: 1,
  targetCaseKind: 'web_automation',
  templateId: 'web-default',
  analysisStrategy: 'full',
  status,
  workerStatus: status,
  coverageStatus: status === 'succeeded' ? 'complete' : 'running',
  totalChunks: 3,
  completedChunks: status === 'succeeded' ? 3 : 2,
  inputSummary: {},
  outputSummary: {},
  createdAt: '2026-07-16T13:00:00.000Z',
  updatedAt: '2026-07-16T14:30:00.000Z'
})

const draftPoint = (id: string, title: string, pageOrFeature: string): AiDraftTestPoint => ({
  pointId: id,
  runId: 'analysis-fixture',
  projectId: 1,
  sourceDocumentId: 'document-1',
  sourceChunkId: `chunk-${id}`,
  title,
  module: '退款与售后协同处理',
  pageOrFeature,
  caseKind: 'web_automation',
  testType: 'functional',
  priority: 'P1',
  objective: title,
  steps: '打开退款详情页\n检查订单与退款单状态',
  expectedResult: '两个状态最终一致',
  sourceFile,
  sourceText: title,
  confidenceScore: 0.91,
  inferred: false,
  reviewStatus: 'confirmed',
  metadata: {},
  createdAt: '2026-07-16T13:10:00.000Z',
  updatedAt: '2026-07-16T13:20:00.000Z'
})

const generatedWebCase = (
  sequence: number,
  title: string,
  path: string
): GeneratedWebAutomationCase => {
  const caseId = `WEB-AI-${String(sequence).padStart(4, '0')}`
  return {
    id: caseId,
    row: {
      caseId,
      project: projectName,
      module: '退款与售后协同处理',
      pageName: '跨境订单退款详情与状态追踪页面',
      title,
      caseType: 'AI用例',
      precondition: '跨境订单已支付且退款申请已受理',
      startUrl: path,
      browser: 'chromium',
      viewport: '1440x900',
      testData: '{}',
      steps: `goto ${path}\nassertVisible .refund-status`,
      locators: '.refund-status',
      assertions: 'assertVisible .refund-status',
      waitStrategy: 'networkidle',
      screenshotStrategy: 'on_failure',
      priority: 'P1',
      executionResult: '未执行',
      executor: '',
      executedAt: '',
      defectId: '',
      tags: '退款与售后协同处理,Web自动化',
      sourceFile,
      remark: '验证长链路状态最终一致'
    },
    metadata: {
      sourcePointId: `point-${sequence}`,
      sourceFile,
      sourceDocumentType: 'markdown',
      pageName: '跨境订单退款详情与状态追踪页面',
      startUrl: path,
      browser: 'chromium',
      viewport: '1440x900',
      steps: [
        { id: `${caseId}-step-1`, name: '打开退款页', action: 'goto', targetType: 'url', target: path },
        { id: `${caseId}-step-2`, name: '检查退款状态', action: 'assertVisible', targetType: 'locator', target: '.refund-status' }
      ],
      locators: [{ id: `${caseId}-locator-1`, name: '退款状态', locatorType: 'css', value: '.refund-status', confirmed: true }],
      assertions: [{ id: `${caseId}-assertion-1`, name: '退款状态可见', type: 'visible', target: '.refund-status' }],
      variables: {},
      runOrder: sequence
    }
  }
}

const generatedCases = [
  generatedWebCase(1, '退款处理超时后订单状态、退款单状态与支付渠道状态的最终一致性回补', '/refund/1'),
  generatedWebCase(2, '重复回调与网络重试同时发生时仅执行一次退款状态回补', '/refund/2')
]

export const caseDesignDraftFixture = (
  stage: CaseDesignDraftSnapshot['activeStage'],
  variant: CaseDesignFixtureVariant = 'default'
): CaseDesignDraftSnapshot => {
  const empty = variant === 'first-empty'
  const partial = variant === 'partial'
  const hasGenerated = stage === 'review' || stage === 'deliver' || partial
  const parsed = stage !== 'import' || variant === 'loading'
  return {
    version: 1,
    activeStage: stage,
    generationKind: 'web_automation',
    projectName,
    analysisRunId: '',
    analysisRun: parsed ? analysisRun(variant === 'loading' ? 'running' : 'succeeded') : null,
    requirementReviewRun: null,
    caseReviewRun: stage === 'review' || stage === 'deliver' ? {
      runId: 'review-fixture',
      graphId: 'review-graph-fixture',
      status: 'merge_ready',
      nodeCount: 4
    } : null,
    activeReviewStage: 'case',
    reviewAttempted: stage === 'review' || stage === 'deliver',
    selectedCaseId: hasGenerated ? generatedCases[0].id : '',
    sources: empty ? [] : [{ name: sourceFile, kind: 'document', text: '# 退款状态回补', needsReattach: false }],
    sourceErrors: variant === 'local-error' ? { [sourceFile]: '文档授权已过期，请替换或重新授权' } : {},
    extractedRequirementPoints: [],
    aiDraftPoints: parsed ? [
      draftPoint('point-1', '退款状态最终一致性', '退款详情'),
      draftPoint('point-2', '重复回调幂等处理', '支付回调')
    ] : [],
    generatedCases: [],
    generatedWebCases: hasGenerated ? generatedCases : [],
    analysisError: '',
    generationError: partial ? '第二批生成失败，已保留第一批结果' : '',
    reviewError: '',
    review: {
      insight: {
        stage: 'case',
        summary: '核心链路已覆盖，仍需确认退款状态回补时限。',
        suggestions: [{
          findingId: 'coverage_gap:refund-consistency',
          category: 'coverage_gap',
          title: '退款最终一致性覆盖缺口',
          detail: '退款模块需要校验订单与退款单状态一致',
          priority: 'P1'
        }]
      },
      qualityScores: [
        { qualityDimension: 'coverage', score: 0.92, scoreLevel: 'good' },
        { qualityDimension: 'testability', score: 0.84, scoreLevel: 'good' }
      ],
      conflicts: [{ conflictId: 'conflict-1', conflictType: 'timeout_definition', targetKey: 'WEB-AI-0001', resolutionStatus: 'unresolved' }],
      findingDecisions: {
        'WEB-AI-0001': { 'coverage_gap:refund-consistency': 'pending' },
        'WEB-AI-0002': { 'coverage_gap:refund-consistency': 'pending' }
      }
    },
    updatedAt: '2026-07-16T14:32:00.000Z'
  }
}

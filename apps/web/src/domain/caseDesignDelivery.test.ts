import { describe, expect, it } from 'vitest'
import { buildCaseDesignDeliveryView } from './caseDesignDelivery'

describe('caseDesignDelivery', () => {
  it('allows save-only delivery without an execution environment', () => {
    expect(buildCaseDesignDeliveryView({
      mode: 'library',
      executeNow: false,
      generatedCount: 12,
      selectedAutomationCount: 0,
      executabilityIssues: [],
      reviewAttempted: true,
      reviewStatus: 'succeeded',
      reviewError: '',
      reviewRisks: [
        { id: 'conflict:c1', kind: 'conflict', priority: 'P0', message: '未处理冲突 · timeout · WEB-AI-0001' },
        { id: 'gap:g1', kind: 'coverage_gap', priority: 'P1', message: '覆盖缺口 · 退款超时异常路径' }
      ],
      environmentValid: false,
      environmentMessage: '请先选择运行环境'
    })).toEqual({
      canSubmit: true,
      primaryLabel: '保存到用例库',
      blockers: [],
      warnings: ['仍有 2 项评审风险'],
      riskItems: [
        { id: 'conflict:c1', kind: 'conflict', priority: 'P0', message: '未处理冲突 · timeout · WEB-AI-0001' },
        { id: 'gap:g1', kind: 'coverage_gap', priority: 'P1', message: '覆盖缺口 · 退款超时异常路径' }
      ]
    })
  })

  it('requires a valid environment only when immediate execution is selected', () => {
    expect(buildCaseDesignDeliveryView({
      mode: 'automation',
      executeNow: true,
      generatedCount: 12,
      selectedAutomationCount: 4,
      executabilityIssues: [],
      reviewAttempted: true,
      reviewStatus: 'succeeded',
      reviewError: '',
      reviewRisks: [],
      environmentValid: false,
      environmentMessage: 'Web 运行环境的应用 Base URL 不能为空'
    })).toEqual({
      canSubmit: false,
      primaryLabel: '保存并执行',
      blockers: ['Web 运行环境的应用 Base URL 不能为空'],
      warnings: [],
      riskItems: []
    })
  })

  it('blocks every delivery mode when there are no generated cases', () => {
    expect(buildCaseDesignDeliveryView({
      mode: 'automation',
      executeNow: false,
      generatedCount: 0,
      selectedAutomationCount: 0,
      executabilityIssues: [],
      reviewAttempted: true,
      reviewStatus: 'succeeded',
      reviewError: '',
      reviewRisks: [],
      environmentValid: true,
      environmentMessage: ''
    }).blockers).toEqual(['没有可交付的生成用例'])
  })

  it('blocks automation conversion when selected cases are not executable', () => {
    expect(buildCaseDesignDeliveryView({
      mode: 'automation',
      executeNow: false,
      generatedCount: 12,
      selectedAutomationCount: 2,
      executabilityIssues: ['WEB-AI-0003：步骤 2 使用 manual，不能自动执行'],
      reviewAttempted: true,
      reviewStatus: 'succeeded',
      reviewError: '',
      reviewRisks: [],
      environmentValid: true,
      environmentMessage: ''
    })).toMatchObject({
      canSubmit: false,
      blockers: ['WEB-AI-0003：步骤 2 使用 manual，不能自动执行']
    })
  })

  it('blocks delivery before review has been attempted', () => {
    expect(buildCaseDesignDeliveryView({
      mode: 'library',
      executeNow: false,
      generatedCount: 2,
      selectedAutomationCount: 0,
      executabilityIssues: [],
      reviewAttempted: false,
      reviewStatus: 'idle',
      reviewError: '',
      reviewRisks: [],
      environmentValid: false,
      environmentMessage: ''
    }).blockers).toContain('请先至少尝试一次 AI 质量评审')
  })

  it('allows a failed review attempt with an itemized warning', () => {
    const view = buildCaseDesignDeliveryView({
      mode: 'library',
      executeNow: false,
      generatedCount: 2,
      selectedAutomationCount: 0,
      executabilityIssues: [],
      reviewAttempted: true,
      reviewStatus: 'failed',
      reviewError: '评审服务暂不可用',
      reviewRisks: [],
      environmentValid: false,
      environmentMessage: ''
    })

    expect(view.canSubmit).toBe(true)
    expect(view.warnings).toContain('AI 质量评审未完成，允许带警告继续交付')
    expect(view.riskItems).toEqual([{
      id: 'review:failed',
      kind: 'review_failure',
      priority: 'P0',
      message: '评审服务暂不可用'
    }])
  })
})

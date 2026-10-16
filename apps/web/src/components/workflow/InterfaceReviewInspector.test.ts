// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { CaseReviewFinding } from '../../domain/caseReview'
import InterfaceReviewInspector from './InterfaceReviewInspector.vue'

const findings: CaseReviewFinding[] = [
  {
    id: 'orders:missing:permission',
    code: 'missing_case_type',
    severity: 'high',
    scope: '订单/创建订单',
    message: '创建订单缺少权限不足用例'
  },
  {
    id: 'API-TC-002:weak_assertion',
    code: 'weak_assertion',
    severity: 'medium',
    scope: '订单/查询订单',
    message: 'API-TC-002 断言规则为空或过弱'
  },
  {
    id: 'API-TC-003:weak_expected_result',
    code: 'weak_expected_result',
    severity: 'low',
    scope: '订单/删除订单',
    message: 'API-TC-003 预期结果过于简略'
  },
  {
    id: 'review_passed',
    code: 'review_passed',
    severity: 'info',
    scope: '全部接口',
    message: '规则复核通过，已检查 12 条用例'
  }
]

const mountInspector = () => mount(InterfaceReviewInspector, {
  props: {
    findings,
    summary: { total: 4, high: 1, medium: 1, low: 1, info: 1 },
    reviewing: false,
    reviewAttempted: true
  }
})

describe('InterfaceReviewInspector', () => {
  it('renders an accessible review status, severity counts, scopes, and messages', () => {
    const wrapper = mountInspector()

    expect(wrapper.get('[aria-label="AI 质量评审"]').text()).toContain('评审完成')
    const summary = wrapper.get('[aria-label="评审结果统计"]')
    expect(summary.findAll('dt').map((item) => item.text())).toEqual(['高风险', '中风险', '低风险', '信息'])
    expect(summary.findAll('dd').map((item) => item.text())).toEqual(['1', '1', '1', '1'])
    for (const finding of findings) {
      expect(wrapper.text()).toContain(finding.scope)
      expect(wrapper.text()).toContain(finding.message)
    }
    const badges = wrapper.findAll('.review-finding-card__badge').map((item) => item.text())
    expect(badges).toEqual(['高风险', '中风险', '低风险', '信息'])
  })

  it('emits review and finding actions while keeping ignored findings presentation-only', async () => {
    const wrapper = mountInspector()
    const assertionFinding = findings[1]

    await wrapper.get('[data-testid="interface-review-action"]').trigger('click')
    await wrapper.get('[data-testid="supplement-finding-orders:missing:permission"]').trigger('click')
    await wrapper.get('[data-testid="locate-finding-API-TC-002:weak_assertion"]').trigger('click')
    await wrapper.get('[data-testid="ignore-finding-API-TC-002:weak_assertion"]').trigger('click')

    expect(wrapper.emitted('review')).toHaveLength(1)
    expect(wrapper.emitted('supplementMissing')).toHaveLength(1)
    expect(wrapper.emitted('locateFinding')?.[0]).toEqual([assertionFinding])
    expect(wrapper.emitted('ignoreFinding')?.[0]).toEqual([assertionFinding.id])
    expect(findings).toHaveLength(4)
    expect(wrapper.text()).not.toContain(assertionFinding.message)
  })

  it('uses the existing edit path for weak assertion and expected-result findings', () => {
    const wrapper = mountInspector()

    expect(wrapper.get('[data-testid="locate-finding-API-TC-002:weak_assertion"]').text()).toContain('定位用例')
    expect(wrapper.get('[data-testid="locate-finding-API-TC-003:weak_expected_result"]').text()).toContain('定位用例')
  })
})

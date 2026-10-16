// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ReviewFindingList, { type ReviewFindingCardView } from './ReviewFindingList.vue'

const findings: ReviewFindingCardView[] = [
  {
    id: 'gap-1',
    tone: 'danger',
    badge: '覆盖缺口',
    meta: ['P0', '待确认'],
    title: '退款超时缺少异常回补用例',
    detail: '覆盖退款状态最终一致性'
  },
  {
    id: 'sug-1',
    tone: 'primary',
    badge: '改进建议',
    title: '补充一致性检查'
  }
]

describe('ReviewFindingList', () => {
  it('renders the section header, card badges, meta, title and detail', () => {
    const wrapper = mount(ReviewFindingList, {
      props: { findings, title: '评审发现', countText: '2 / 4' }
    })

    expect(wrapper.get('.review-finding-list__header strong').text()).toBe('评审发现')
    expect(wrapper.get('.review-finding-list__header span').text()).toBe('2 / 4')
    expect(wrapper.findAll('.review-finding-card')).toHaveLength(2)
    const first = wrapper.get('.review-finding-card')
    expect(first.get('.review-finding-card__badge').text()).toBe('覆盖缺口')
    expect(first.get('.review-finding-card__badge').classes()).toContain('is-danger')
    expect(first.get('.review-finding-card__title').text()).toBe('退款超时缺少异常回补用例')
    expect(first.get('.review-finding-card__detail').text()).toBe('覆盖退款状态最终一致性')
    expect(first.text()).toContain('P0')
    expect(first.text()).toContain('待确认')
    expect(first.classes()).toContain('is-danger')
  })

  it('renders the actions slot and hides actions when showActions is false', () => {
    const wrapper = mount(ReviewFindingList, {
      props: {
        title: '发现',
        findings: [
          { id: 'a', tone: 'success', badge: '信息', title: '规则复核通过', showActions: false },
          { id: 'b', tone: 'warning', badge: '中风险', title: '断言规则为空或过弱' }
        ]
      },
      slots: { actions: '<button data-testid="act">行动</button>' }
    })

    const cards = wrapper.findAll('.review-finding-card')
    expect(cards[0].find('.review-finding-card__actions').exists()).toBe(false)
    expect(cards[1].find('[data-testid="act"]').exists()).toBe(true)
  })

  it('renders the empty state when no findings are present', () => {
    const wrapper = mount(ReviewFindingList, {
      props: { findings: [], title: '评审发现', emptyText: '当前没有待展示的评审发现' }
    })

    expect(wrapper.get('.review-finding-list__empty').text()).toBe('当前没有待展示的评审发现')
  })
})

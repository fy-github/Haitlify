// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import TwoPhaseIndicator from './TwoPhaseIndicator.vue'

describe('TwoPhaseIndicator', () => {
  it('renders the two stages with unified dotted copy in chips variant', () => {
    const wrapper = mount(TwoPhaseIndicator)
    const chips = wrapper.get('.two-phase-indicator--chips')
    expect(chips.attributes('aria-label')).toBe('Web AI 两阶段模式')
    expect(chips.findAll('span').map((item) => item.text())).toEqual(['阶段 1 · 测试设计', '阶段 2 · 自动化落地'])
    expect(chips.get('.is-primary').text()).toBe('阶段 1 · 测试设计')
  })
})

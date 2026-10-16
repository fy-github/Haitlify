// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AiPolicyDisclosure from './AiPolicyDisclosure.vue'

describe('AiPolicyDisclosure', () => {
  it('allows dismissing a ready policy notice without a fallback route', async () => {
    const wrapper = mount(AiPolicyDisclosure, {
      props: {
        ready: true,
        policy: {
          policyId: 'requirement', scope: 'default', taskType: 'requirement_analysis', name: '需求分析', enabled: true,
          perCallTimeoutMs: 120000, wholeRunDeadlineMs: 900000, retryBudget: 1, maxInputChars: 0,
          routes: [{ position: 0, connectionId: 'default', model: 'gpt-5.5' }], warnings: []
        }
      }
    })
    expect(wrapper.text()).toContain('无备用路由')
    await wrapper.get('[data-testid="ai-policy-dismiss"]').trigger('click')
    expect(wrapper.emitted('dismiss')).toEqual([[]])
  })
})

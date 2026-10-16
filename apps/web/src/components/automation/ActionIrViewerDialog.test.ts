// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import ActionIrViewerDialog from './ActionIrViewerDialog.vue'
import type { WebAutomationActionIR, WebAutomationStep } from '../../domain/webAutomationCase'

const actions: WebAutomationActionIR[] = [
  { id: 'go-list', type: 'navigate', safety: 'readonly', target: { kind: 'route', value: '/list' } },
  { id: 'click-search', type: 'click', safety: 'readonly', target: { kind: 'semantic', value: '查询' } }
]

const steps: WebAutomationStep[] = [
  { id: 'step-1', name: '打开列表', action: 'goto', targetType: 'url', target: '/list' },
  { id: 'step-2', name: '点击查询', action: 'click', targetType: 'locator', target: '查询' }
]

describe('ActionIrViewerDialog', () => {
  it('renders the Action IR json and the source steps', async () => {
    const wrapper = mount(ActionIrViewerDialog, {
      props: { visible: true, actions, steps },
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    expect(wrapper.get('[data-testid="action-ir-pane"]').text()).toContain('Action IR（2 个动作）')
    const json = wrapper.get('[data-testid="action-ir-json"]').text()
    expect(json).toContain('"go-list"')
    expect(json).toContain('"navigate"')
    expect(wrapper.get('[data-testid="action-steps-pane"]').text()).toContain('goto url /list')
    expect(wrapper.get('[data-testid="action-steps-pane"]').text()).toContain('打开列表')
  })

  it('states explicitly when a case has no Action IR instead of faking one', async () => {
    const wrapper = mount(ActionIrViewerDialog, {
      props: { visible: true, steps },
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()
    expect(wrapper.get('[data-testid="action-ir-empty"]').text()).toContain('没有 Action IR')
  })
})

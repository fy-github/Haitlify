// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import CaseDeliveryStage from './CaseDeliveryStage.vue'

describe('CaseDeliveryStage', () => {
  const global = {
    stubs: {
      'el-button': true,
      'el-tag': true
    }
  }

  it('switches between save-only and automation delivery', async () => {
    const wrapper = mount(CaseDeliveryStage, {
      global,
      props: {
        mode: 'library',
        automationKind: 'web',
        executeNow: false,
        generatedCount: 12,
        selectedAutomationCount: 12,
        environmentName: 'Web 测试环境',
        preflight: { canSubmit: true, primaryLabel: '保存到用例库', blockers: [], warnings: [], riskItems: [] }
      }
    })

    await wrapper.get('[data-testid="delivery-automation"]').trigger('click')
    expect(wrapper.emitted('update:mode')).toEqual([['automation']])
  })

  it('leaves confirmation to the single WorkflowHeader primary command', () => {
    const wrapper = mount(CaseDeliveryStage, {
      global,
      props: {
        mode: 'automation',
        automationKind: 'web',
        executeNow: true,
        generatedCount: 12,
        selectedAutomationCount: 8,
        environmentName: 'Web 测试环境',
        preflight: {
          canSubmit: true,
          primaryLabel: '保存并执行',
          blockers: [],
          warnings: ['仍有 2 项评审风险'],
          riskItems: [
            { id: 'conflict:c1', kind: 'conflict', priority: 'P0', message: '未处理冲突 · timeout · WEB-AI-0001' },
            { id: 'gap:g1', kind: 'coverage_gap', priority: 'P1', message: '覆盖缺口 · 退款超时异常路径' }
          ]
        }
      }
    })

    expect(wrapper.find('[data-testid="confirm-delivery"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('已选择 8 条转自动化')
    expect(wrapper.text()).toContain('未处理冲突 · timeout · WEB-AI-0001')
    expect(wrapper.text()).toContain('覆盖缺口 · 退款超时异常路径')
  })

  it('navigates radio options with arrow keys and moves focus (roving tabindex)', async () => {
    const wrapper = mount(CaseDeliveryStage, {
      global,
      attachTo: document.body,
      props: {
        mode: 'library',
        automationKind: 'web',
        executeNow: false,
        generatedCount: 12,
        selectedAutomationCount: 12,
        environmentName: 'Web 测试环境',
        preflight: { canSubmit: true, primaryLabel: '保存到用例库', blockers: [], warnings: [], riskItems: [] }
      }
    })

    const library = wrapper.get('[data-testid="delivery-library"]')
    const automation = wrapper.get('[data-testid="delivery-automation"]')

    // roving tabindex:仅当前选中项在 Tab 序内
    expect(library.attributes('tabindex')).toBe('0')
    expect(automation.attributes('tabindex')).toBe('-1')

    // ArrowDown:选中并聚焦下一项
    await library.trigger('keydown', { key: 'ArrowDown' })
    expect(wrapper.emitted('update:mode')).toEqual([['automation']])
    expect(document.activeElement?.getAttribute('data-testid')).toBe('delivery-automation')

    // ArrowUp:回到上一项
    await automation.trigger('keydown', { key: 'ArrowUp' })
    expect(wrapper.emitted('update:mode')).toEqual([['automation'], ['library']])
    expect(document.activeElement?.getAttribute('data-testid')).toBe('delivery-library')

    wrapper.unmount()
  })

  it('wraps right-arrow from the last option back to the first', async () => {
    const wrapper = mount(CaseDeliveryStage, {
      global,
      props: {
        mode: 'automation',
        automationKind: 'web',
        executeNow: false,
        generatedCount: 12,
        selectedAutomationCount: 12,
        environmentName: 'Web 测试环境',
        preflight: { canSubmit: true, primaryLabel: '保存并执行', blockers: [], warnings: [], riskItems: [] }
      }
    })

    await wrapper.get('[data-testid="delivery-automation"]').trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.emitted('update:mode')).toEqual([['library']])
  })

  it('shows blockers that disable the header confirmation', () => {
    const wrapper = mount(CaseDeliveryStage, {
      global,
      props: {
        mode: 'automation',
        automationKind: 'web',
        executeNow: true,
        generatedCount: 12,
        selectedAutomationCount: 12,
        environmentName: '未配置',
        preflight: { canSubmit: false, primaryLabel: '保存并执行', blockers: ['请先选择 Web 运行环境'], warnings: [], riskItems: [] }
      }
    })

    expect(wrapper.text()).toContain('请先选择 Web 运行环境')
    expect(wrapper.get('[role="alert"]').text()).toContain('请先选择 Web 运行环境')
  })
})

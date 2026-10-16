// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi } from 'vitest'
import { defineComponent, h, inject, nextTick, provide } from 'vue'
import GeneratedWebCaseTable from './GeneratedWebCaseTable.vue'
import type { GeneratedWebAutomationCase } from '../../domain/webAutomationCase'
import { listLocatorKnowledge, type LocatorKnowledgeEntry } from '../../domain/webCaseAgentRepository'

vi.mock('../../domain/webCaseAgentRepository', () => ({
  listLocatorKnowledge: vi.fn()
}))

const testCase: GeneratedWebAutomationCase = {
  id: 'WEB-AI-0001',
  row: { caseId: 'WEB-AI-0001', project: 'Haitlify', module: '登录', pageName: '登录页', title: '登录成功', naturalSteps: '进入登录页\n输入正确账号密码\n点击登录', naturalExpectedResult: '进入工作台并展示导航', caseType: 'AI用例', precondition: '', startUrl: '/login', browser: 'chromium', viewport: '1440x900', testData: '', steps: 'goto /login', locators: '', assertions: 'assertVisible .dashboard', waitStrategy: '', screenshotStrategy: '', priority: 'P1', executionResult: '', executor: '', executedAt: '', defectId: '', tags: '', sourceFile: 'requirements.md', remark: '' },
  metadata: { sourceFile: 'requirements.md', sourceDocumentType: 'markdown', pageName: '登录页', startUrl: '/login', browser: 'chromium', viewport: '1440x900', steps: [{ id: 'step-1', name: 'goto-1', action: 'goto', targetType: 'url', target: '/login' }], locators: [], assertions: [], variables: {}, runOrder: 1 }
}

const tableSlotStubs = {
  ElTable: defineComponent({
    props: {
      data: { type: Array, default: () => [] },
      maxHeight: { type: [String, Number], default: undefined },
      scrollbarAlwaysOn: { type: Boolean, default: false }
    },
    setup(props, { slots }) {
      provide('tableRows', props.data)
      return () => h('table', {
        'data-testid': 'generated-web-case-table',
        'data-max-height': props.maxHeight,
        'data-scrollbar-always-on': String(props.scrollbarAlwaysOn)
      }, slots.default?.())
    }
  }),
  ElTableColumn: defineComponent({
    props: ['label'],
    setup(props, { slots }) {
      const rows = inject<GeneratedWebAutomationCase[]>('tableRows', [])
      return () => h('th', [
        props.label,
        ...rows.flatMap((row) => slots.default?.({ row }) || [])
      ])
    }
  }),
  ElButton: { template: '<button><slot /></button>' }
}

describe('GeneratedWebCaseTable', () => {
  it('renders the essential generated Web-case fields and row actions', () => {
    const wrapper = mount(GeneratedWebCaseTable, {
      props: { cases: [testCase], selectedIds: [], selectable: true },
      global: {
        stubs: {
          ElTable: { template: '<table><slot /></table>' },
          ElTableColumn: { props: ['label'], template: '<th>{{ label }}</th>' },
          ElButton: { template: '<button><slot /></button>' }
        }
      }
    })
    for (const label of ['用例', '测试设计', '自动化编排', '优先级', '来源', '操作']) expect(wrapper.text()).toContain(label)
  })

  it('renders natural-language steps and expected result for generated AI cases', () => {
    const wrapper = mount(GeneratedWebCaseTable, {
      props: { cases: [testCase], selectedIds: [] },
      global: {
        stubs: tableSlotStubs
      }
    })

    expect(wrapper.text()).toContain('1. 进入登录页 / 2. 输入正确账号密码 …')
    expect(wrapper.text()).toContain('1. 进入工作台并展示导航')
    expect(wrapper.text()).toContain('1 步')
  })

  it('lets workflow stages control the generated case table height while keeping it scrollable', () => {
    const wrapper = mount(GeneratedWebCaseTable, {
      props: { cases: [testCase], selectedIds: [] },
      global: {
        stubs: tableSlotStubs
      }
    })

    const table = wrapper.get('[data-testid="generated-web-case-table"]')
    expect(table.attributes('data-max-height')).toBeUndefined()
    expect(table.attributes('data-scrollbar-always-on')).toBe('true')
  })

  it('shows the evidence badge when a case is bound to page evidence', () => {
    const evidencedCase: GeneratedWebAutomationCase = {
      ...testCase,
      metadata: { ...testCase.metadata, agentEvidenceIds: ['locator-1', 'locator-2'] }
    }
    const wrapper = mount(GeneratedWebCaseTable, {
      props: { cases: [evidencedCase], selectedIds: [] },
      global: {
        stubs: tableSlotStubs
      }
    })

    expect(wrapper.text()).toContain('证据 2')
  })

  it('traces bound page evidence details when the evidence badge is clicked', async () => {
    const evidencedCase: GeneratedWebAutomationCase = {
      ...testCase,
      metadata: { ...testCase.metadata, agentEvidenceIds: ['locator-1'] }
    }
    const entry: LocatorKnowledgeEntry = {
      entryId: 'locator-1', projectId: 7, environmentId: 'env-1', moduleName: '登录',
      pageName: '登录页', pageUrl: '/login', naturalName: '登录按钮', locator: '#login-submit',
      roleName: 'button', elementType: 'button', source: 'locatorKnowledge', status: 'ACTIVE',
      lastVerifiedAt: '2026-08-20T00:00:00Z', hitCount: 3, executionSuccessCount: 2,
      executionFailureCount: 1, confidenceScore: 0.9, confidenceLocked: false, lastHitAt: '2026-08-20T00:00:00Z'
    }
    vi.mocked(listLocatorKnowledge).mockResolvedValue({ items: [entry] })
    const wrapper = mount(GeneratedWebCaseTable, {
      props: { cases: [evidencedCase], selectedIds: [], projectId: 7 },
      global: {
        stubs: tableSlotStubs
      }
    })

    await wrapper.get('.studio-generated-case-table__evidence').trigger('click')
    await nextTick()
    await nextTick()

    expect(listLocatorKnowledge).toHaveBeenCalledWith(7)
    expect(wrapper.text()).toContain('#login-submit')
    expect(wrapper.text()).toContain('登录页 · button')
  })

  it('omits the evidence badge when a case has no bound evidence', () => {
    const wrapper = mount(GeneratedWebCaseTable, {
      props: { cases: [testCase], selectedIds: [] },
      global: {
        stubs: tableSlotStubs
      }
    })

    expect(wrapper.text()).not.toContain('证据')
  })

  it('does not present compiled DSL as missing natural-language design', () => {
    const dslOnlyCase: GeneratedWebAutomationCase = {
      ...testCase,
      row: {
        ...testCase.row,
        naturalSteps: '',
        naturalExpectedResult: '',
        steps: 'goto /login\nassertVisible .dashboard',
        assertions: 'assertVisible .dashboard'
      }
    }
    const wrapper = mount(GeneratedWebCaseTable, {
      props: { cases: [dslOnlyCase], selectedIds: [] },
      global: {
        stubs: tableSlotStubs
      }
    })

    expect(wrapper.text()).toContain('未生成自然语言步骤/预期，请重新生成该用例')
    expect(wrapper.text()).not.toContain('goto /login')
  })

  it('emits row activation, edit, and removal commands', async () => {
    const wrapper = mount(GeneratedWebCaseTable, { props: { cases: [testCase], selectedIds: [] }, global: { plugins: [ElementPlus] } })
    await wrapper.get('tbody tr').trigger('click')
    await wrapper.get('button[aria-label="查看 WEB-AI-0001"]').trigger('click')
    await wrapper.get('button[aria-label="编辑 WEB-AI-0001"]').trigger('click')
    await wrapper.get('button[aria-label="删除 WEB-AI-0001"]').trigger('click')
    expect(wrapper.emitted('activate')?.[0]).toEqual([testCase])
    expect(wrapper.emitted('preview')?.[0]).toEqual([testCase])
    expect(wrapper.emitted('edit')?.[0]).toEqual([testCase])
    expect(wrapper.emitted('remove')?.[0]).toEqual([testCase])
  })

  it('does not re-emit selection changes while synchronizing controlled selections', async () => {
    const wrapper = mount(GeneratedWebCaseTable, {
      props: { cases: [testCase], selectedIds: [], selectable: true },
      global: { plugins: [ElementPlus] }
    })

    await wrapper.setProps({ selectedIds: [testCase.id] })
    await nextTick()
    await nextTick()

    expect(wrapper.emitted('selectionChange')).toBeUndefined()
  })
})

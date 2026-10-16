// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { NavKey } from '../../domain/appNavigation'
import WorkspaceContextPane from './WorkspaceContextPane.vue'

type WorkspaceContextPaneProps = InstanceType<typeof WorkspaceContextPane>['$props']

const elementStubs = {
  ElSelect: { template: '<div class="stub-el-select"><slot /></div>' },
  ElOption: { props: ['label'], template: '<div class="stub-el-option">{{ label }}</div>' },
  ElTooltip: { template: '<span><slot /></span>' },
  ElButton: { template: '<button type="button" @click="$emit(\'click\')"><slot /></button>' },
  ElTag: { template: '<span class="stub-el-tag"><slot /></span>' }
}

const makeProps = (overrides: Partial<WorkspaceContextPaneProps> = {}): WorkspaceContextPaneProps => ({
  activeNav: 'api',
  interfaceContextVisible: false,
  caseDesignContextVisible: false,
  importFilterModule: 'ALL',
  interfaceModuleOptions: [],
  importFilterPathKeyword: '',
  importFilterSummaryKeyword: '',
  importFilterMethod: 'ALL',
  generationTemplateId: 'builtin-interface',
  generationTemplateOptions: [],
  generationGranularity: 'basic',
  caseDesignSourceView: [],
  caseDesignModuleOptions: [],
  caseDesignActiveModule: 'ALL',
  activeCaseDesignSource: '',
  importedProjects: [],
  activeProjectId: '',
  apiModules: [],
  apiModuleTotal: 0,
  libraryProjects: [],
  libraryCaseCount: 0,
  libraryModules: [],
  libraryFilterModule: 'ALL',
  libraryFilterProject: 'ALL',
  automationKind: 'api',
  automationCaseCount: 0,
  webAutomationCaseCount: 0,
  automationProjects: [],
  automationModules: [],
  automationProjectCaseCount: 0,
  webAutomationProjectCaseCount: 0,
  automationFilterModule: 'ALL',
  automationFilterProject: 'ALL',
  caseTemplates: [],
  selectedTemplateId: '',
  aiConfigReady: false,
  aiConfigProvider: '',
  aiConfigModel: '',
  jiraEnabled: false,
  jiraBaseUrl: '',
  jiraUsername: '',
  reportCount: 0,
  lastReportAt: '-',
  reportProjects: [],
  selectedReportProject: 'ALL',
  defectProjects: [],
  defectCaseCount: 0,
  defectFilterProject: 'ALL',
  ...overrides
})

describe('WorkspaceContextPane', () => {
  it('starts the generic context pane with actionable content instead of the removed workspace card', () => {
    const wrapper = mount(WorkspaceContextPane, {
      props: makeProps({ activeNav: 'automation' }),
      global: { stubs: elementStubs }
    })

    expect(wrapper.find('.pane-head').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('当前工作区')
    expect(wrapper.get('.resource-pane').element.firstElementChild?.classList.contains('pane-flow-chain')).toBe(true)
  })

  it('renders the resource module list for the api branch and forwards module selection', async () => {
    const wrapper = mount(WorkspaceContextPane, {
      props: makeProps({
        activeNav: 'api',
        apiModules: [{ name: '登录', count: 3 }],
        apiModuleTotal: 3,
        importFilterModule: 'ALL'
      }),
      global: { stubs: elementStubs }
    })

    expect(wrapper.get('.resource-section-title').text()).toBe('模块')
    expect(wrapper.get('.resource-item-label').text()).toBe('全部模块')
    expect(wrapper.text()).toContain('登录')
    expect(wrapper.text()).toContain('3')

    await wrapper.get('.resource-item[class*="active"]').trigger('click')
    expect(wrapper.emitted('update-import-module')).toEqual([['ALL']])
  })

  it('renders the interface context pane when interfaceContextVisible is set', async () => {
    const wrapper = mount(WorkspaceContextPane, {
      props: makeProps({
        activeNav: 'api',
        interfaceContextVisible: true,
        importFilterModule: 'ALL',
        interfaceModuleOptions: [{ value: '支付', label: '支付' }]
      }),
      global: { stubs: elementStubs }
    })

    const moduleSelect = wrapper.get('select[aria-label="模块"]')
    expect(moduleSelect.text()).toContain('支付')

    await moduleSelect.setValue('支付')
    expect(wrapper.emitted('update-import-module')).toEqual([['支付']])
  })

  it('renders the defects branch with a project select', () => {
    const wrapper = mount(WorkspaceContextPane, {
      props: makeProps({
        activeNav: 'defects',
        defectProjects: [{ value: 'Haitlify', count: 4 }],
        defectFilterProject: 'ALL',
        defectCaseCount: 4
      }),
      global: { stubs: elementStubs }
    })

    expect(wrapper.get('.resource-section-title').text()).toBe('项目')
    expect(wrapper.text()).toContain('全部项目')
    expect(wrapper.text()).toContain('Haitlify')
    expect(wrapper.text()).toContain('4')
  })

  it('renders the flow chain for flow navigation and hides it for settings/home/case-library', () => {
    const wrapper = mount(WorkspaceContextPane, {
      props: makeProps({ activeNav: 'automation' }),
      global: { stubs: elementStubs }
    })

    const chain = wrapper.get('.pane-flow-chain')
    expect(chain.findAll('.pane-flow-chain__step').map((step) => step.text())).toEqual([
      '①', '②', '③', '④'
    ])
    expect(chain.get('.pane-flow-chain__step.is-active').text()).toBe('②')
    expect(wrapper.get('button[aria-label="上一步：准备"]').text()).toContain('上一步')
    expect(wrapper.get('button[aria-label="下一步：验证"]').text()).toContain('下一步')

    for (const activeNav of ['settings', 'home', 'cases'] as const satisfies readonly NavKey[]) {
      const nonFlowPane = mount(WorkspaceContextPane, {
        props: makeProps({ activeNav }),
        global: { stubs: elementStubs }
      })
      expect(nonFlowPane.find('.pane-flow-chain').exists()).toBe(false)
    }
  })

  it('navigates forward and backward along the flow chain', async () => {
    const wrapper = mount(WorkspaceContextPane, {
      props: makeProps({ activeNav: 'automation' }),
      global: { stubs: elementStubs }
    })

    await wrapper.get('button[aria-label="上一步：准备"]').trigger('click')
    expect(wrapper.emitted('navigate-to-nav')?.[0]).toEqual(['api'])

    await wrapper.get('button[aria-label="下一步：验证"]').trigger('click')
    expect(wrapper.emitted('navigate-to-nav')?.[1]).toEqual(['reports'])

    await wrapper.get('.pane-flow-chain__step.is-active').trigger('click')
    expect(wrapper.emitted('navigate-to-nav')?.[2]).toEqual(['automation'])
  })
})

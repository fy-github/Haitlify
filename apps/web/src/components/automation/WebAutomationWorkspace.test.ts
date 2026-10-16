// @vitest-environment jsdom
import { defineComponent, nextTick, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { SavedWebAutomationRunCase } from '../../domain/webAutomationRepository'
import type { WebAutomationRunSnapshot } from '../../domain/webAutomationRunRepository'
import { buildWebRunView } from '../../domain/webAutomationRunView'
import WebAutomationWorkspace from './WebAutomationWorkspace.vue'
import workspaceSource from './WebAutomationWorkspace.vue?raw'

type WorkspaceProps = InstanceType<typeof WebAutomationWorkspace>['$props']

const testCase = (overrides: Partial<SavedWebAutomationRunCase> = {}): SavedWebAutomationRunCase => ({
  id: 'WEB-1',
  sourceCaseId: 'SRC-1',
  project: '订单',
  module: '退款',
  pageName: '退款申请',
  name: '提交退款申请',
  startUrl: 'https://example.com/refund',
  browser: 'chromium',
  viewport: '1440x900',
  authenticationMode: 'environment',
  steps: [{ id: 'step-1', name: '打开页面', action: 'goto', targetType: 'url', target: '/refund' }],
  runOrder: 1,
  mockOutcome: 'passed',
  automationLibraryId: 'library-1',
  savedAt: '2026-07-27T10:00:00.000Z',
  ...overrides
})

const snapshot = (overrides: Partial<WebAutomationRunSnapshot> = {}): WebAutomationRunSnapshot => ({
  runId: 'run-1',
  status: 'running',
  mode: 'sequential',
  selectedBrowser: 'Chromium',
  createdAt: '2026-07-27T10:00:00Z',
  startedAt: '2026-07-27T10:00:01Z',
  updatedAt: '2026-07-27T10:00:08Z',
  progress: { totalCases: 1, completedCases: 0, totalSteps: 2, completedSteps: 1 },
  currentCase: { id: 'WEB-1', name: '提交退款申请' },
  currentStep: { id: 'step-1', name: '打开页面', action: 'goto', phase: 'test' },
  controls: { canPause: true, canResume: false, canStop: true },
  summary: { total: 1, passed: 0, failed: 0, error: 0 },
  stepLogs: [],
  results: [],
  diagnostics: ['Runner 正常'],
  ...overrides
})

const runSnapshot = snapshot()
const sampleCase = testCase()
const baseProps: WorkspaceProps = {
  mode: 'workbench' as const,
  compact: false,
  header: {
    executableCount: 1,
    moduleCount: 1,
    latestRunLabel: '最近执行 10:00'
  },
    preparation: {
      environmentId: 'env-1',
      environments: [{ value: 'env-1', label: '测试环境' }],
      browser: 'follow-case',
      browsers: [{ value: 'follow-case', label: '跟随用例', disabled: false, recommendation: '' }],
      loopCount: 1,
      loginReadyLabel: '登录已就绪',
      actionLabel: '执行当前',
      canExecute: true,
      blockingReason: ''
    },
  scope: {
    project: '订单',
    projects: [{ value: '订单', label: '订单', count: 1 }],
    module: '退款',
    modules: [{ value: '退款', label: '退款', count: 1 }]
  },
  table: {
    cases: [sampleCase],
    selectedIds: ['WEB-1'],
    source: 'ALL' as const,
    status: 'ALL' as const,
    keyword: '',
    resultStatusById: { 'WEB-1': 'pending' as const },
    emptyKind: 'none' as const
  },
  preflight: {
    effectiveCount: 1,
    canExecute: true,
    blockingReason: '',
    items: [{ id: 'runner' as const, label: 'Runner', status: 'ready' as const, detail: 'Runner 已连接' }]
  },
  statusMessage: 'Runner 临时不可用'
}

const runProps = {
  snapshot: runSnapshot,
  view: buildWebRunView(runSnapshot),
  connectionState: 'polling' as const,
  connectionLabel: '实时同步',
  elapsed: '00:42',
  controlLoading: '' as const
}

const stub = (name: string, eventMap: Record<string, unknown[]>) => defineComponent({
  name,
  props: ['snapshot', 'view', 'connectionState', 'connectionLabel', 'elapsed', 'controlLoading', 'message', 'compact', 'project', 'projects', 'module', 'modules', 'browser'],
  emits: Object.keys(eventMap),
  setup(props, { emit }) {
    const root = ref<HTMLElement>()
    const entry = ref<HTMLButtonElement>()
    const scroll = ref<HTMLElement>()
    const focusMonitorEntry = vi.fn(() => entry.value?.focus())
    const getScrollElement = vi.fn(() => scroll.value)

    return {
      name,
      eventMap,
      root,
      entry,
      scroll,
      focusMonitorEntry,
      getScrollElement,
      props,
      emitEvent: (eventName: string) => emit(eventName, ...(eventMap[eventName] || []))
    }
  },
  template: `
    <section ref="root" :data-testid="name">
      <span data-testid="stub-props">{{ props.project }} {{ props.module }} {{ props.message }} {{ props.compact }}</span>
      <button ref="entry" type="button" :data-testid="\`\${name}-focus\`">focus</button>
      <div ref="scroll" class="web-automation-case-table__scroll" :data-testid="\`\${name}-scroll\`"></div>
      <button v-if="name === 'WebRunPreparationBar'" type="button" data-testid="execute-web-automation">execute</button>
      <button v-for="eventName in Object.keys(eventMap)" :key="eventName" type="button" :data-testid="\`\${name}-\${eventName}\`" @click="emitEvent(eventName)">
        {{ eventName }}
      </button>
    </section>
  `
})

const HeaderStub = stub('WebAutomationTaskHeader', {
  openHistory: [],
  generateCases: [],
  exportCases: []
})
const PreparationStub = stub('WebRunPreparationBar', {
  updateEnvironmentId: ['env-2'],
  updateBrowser: ['firefox'],
  updateLoopCount: [3],
  editEnvironment: [],
  execute: []
})
const TableStub = stub('WebAutomationCaseTable', {
  updateProject: ['支付'],
  updateModule: ['退款'],
  toggleCase: [sampleCase, false],
  selectFiltered: [],
  clearSelected: [],
  updateSource: ['design'],
  updateStatus: ['failed'],
  updateKeyword: ['退款'],
  clearFilters: [],
  generateCases: [],
  openLog: [sampleCase],
  transferDefect: [sampleCase],
  deleteCase: [sampleCase]
})
const PreflightStub = stub('WebRunPreflightPanel', {
  retryCapabilities: [],
  editEnvironment: [],
  editLogin: [],
  clearFilters: []
})
const SummaryStub = stub('WebRunMonitorSummary', {
  enterMonitor: [],
  rerun: [],
  openReport: []
})
const MonitorStub = stub('WebRunMonitorView', {
  returnWorkbench: [],
  pause: [],
  resume: [],
  stop: [],
  rerun: [],
  openLog: ['WEB-1'],
  openReport: [],
  transferDefect: ['WEB-1']
})
const mountWorkspace = (
  overrides: Partial<WorkspaceProps> = {},
  mountOptions: { attachTo?: HTMLElement } = {}
) => mount(WebAutomationWorkspace, {
  props: { ...baseProps, ...overrides },
  attachTo: mountOptions.attachTo,
  global: {
    stubs: {
      WebAutomationTaskHeader: HeaderStub,
      WebRunPreparationBar: PreparationStub,
      WebAutomationCaseTable: TableStub,
      WebRunPreflightPanel: PreflightStub,
      WebRunMonitorSummary: SummaryStub,
      WebRunMonitorView: MonitorStub
    }
  }
})

describe('WebAutomationWorkspace', () => {
  it('registers every component rendered by the workbench template', () => {
    expect(workspaceSource).toContain("import WebRunPreparationBar from './WebRunPreparationBar.vue'")
  })

  it('composes the wide workbench with exactly one idle contextual pane', () => {
    const wrapper = mountWorkspace()

    expect(wrapper.find('[data-testid="web-automation-workspace"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-testid="WebAutomationTaskHeader"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-testid="WebRunPreparationBar"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-testid="WebAutomationCaseTable"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-testid="WebRunPreflightPanel"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-testid="WebRunMonitorSummary"]')).toHaveLength(0)
    expect(wrapper.findAll('[data-testid="WebRunMonitorView"]')).toHaveLength(0)
    expect(wrapper.get('[data-testid="WebAutomationCaseTable"]').text()).toContain('订单 退款')
  })

  it('switches the wide contextual pane to the summary when a run is present', () => {
    const wrapper = mountWorkspace({ run: runProps })

    expect(wrapper.findAll('[data-testid="WebRunPreflightPanel"]')).toHaveLength(0)
    expect(wrapper.findAll('[data-testid="WebRunMonitorSummary"]')).toHaveLength(1)
  })

  it('renders monitor mode as only the full monitor under the workspace root and forwards controls unchanged', async () => {
    const wrapper = mountWorkspace({ mode: 'monitor', run: runProps })

    expect(wrapper.findAll('[data-testid="WebRunMonitorView"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-testid="WebAutomationTaskHeader"]')).toHaveLength(0)
    expect(wrapper.findAll('[data-testid="WebRunPreparationBar"]')).toHaveLength(0)
    expect(wrapper.findAll('[data-testid="WebAutomationCaseTable"]')).toHaveLength(0)

    for (const eventName of ['returnWorkbench', 'pause', 'resume', 'stop', 'rerun'] as const) {
      await wrapper.get(`[data-testid="WebRunMonitorView-${eventName}"]`).trigger('click')
      expect(wrapper.emitted(eventName)).toEqual([[]])
    }
  })

  it('keeps the workbench compact without auxiliary drawers', () => {
    const wrapper = mountWorkspace({ compact: true })

    expect(wrapper.get('[data-testid="web-automation-workspace"]').classes()).toContain('is-compact')
    expect(wrapper.find('[data-testid="execute-web-automation"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-testid="WebAutomationCaseTable"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-testid="WebRunPreparationBar"]')).toHaveLength(1)
  })

  it('forwards every explicit workbench intent with payloads preserved', async () => {
    const wrapper = mountWorkspace()

    const events = [
      ['WebAutomationTaskHeader', 'openHistory', []],
      ['WebAutomationTaskHeader', 'generateCases', []],
      ['WebAutomationTaskHeader', 'exportCases', []],
      ['WebRunPreparationBar', 'updateEnvironmentId', ['env-2']],
      ['WebRunPreparationBar', 'updateBrowser', ['firefox']],
      ['WebRunPreparationBar', 'updateLoopCount', [3]],
      ['WebRunPreparationBar', 'editEnvironment', []],
      ['WebRunPreparationBar', 'execute', []],
      ['WebAutomationCaseTable', 'updateProject', ['支付']],
      ['WebAutomationCaseTable', 'updateModule', ['退款']],
      ['WebAutomationCaseTable', 'toggleCase', [sampleCase, false]],
      ['WebAutomationCaseTable', 'selectFiltered', []],
      ['WebAutomationCaseTable', 'clearSelected', []],
      ['WebAutomationCaseTable', 'updateSource', ['design']],
      ['WebAutomationCaseTable', 'updateStatus', ['failed']],
      ['WebAutomationCaseTable', 'updateKeyword', ['退款']],
      ['WebAutomationCaseTable', 'clearFilters', []],
      ['WebAutomationCaseTable', 'generateCases', []],
      ['WebAutomationCaseTable', 'openLog', [sampleCase]],
      ['WebAutomationCaseTable', 'transferDefect', [sampleCase]],
      ['WebAutomationCaseTable', 'deleteCase', [sampleCase]],
      ['WebRunPreflightPanel', 'retryCapabilities', []],
      ['WebRunPreflightPanel', 'editEnvironment', []],
      ['WebRunPreflightPanel', 'editLogin', []],
      ['WebRunPreflightPanel', 'clearFilters', []]
    ] as const

    for (const [component, eventName, payload] of events) {
      await wrapper.get(`[data-testid="${component}-${eventName}"]`).trigger('click')
      expect(wrapper.emitted(eventName)).toContainEqual(payload)
    }
  })

  it('forwards summary and full monitor result intents without name collisions', async () => {
    const wrapper = mountWorkspace({ run: runProps })
    await wrapper.get('[data-testid="WebRunMonitorSummary-enterMonitor"]').trigger('click')
    await wrapper.get('[data-testid="WebRunMonitorSummary-rerun"]').trigger('click')
    await wrapper.get('[data-testid="WebRunMonitorSummary-openReport"]').trigger('click')

    expect(wrapper.emitted('enterMonitor')).toEqual([[]])
    expect(wrapper.emitted('rerun')).toEqual([[]])
    expect(wrapper.emitted('openReport')).toEqual([[]])

    const monitor = mountWorkspace({ mode: 'monitor', run: runProps })
    await monitor.get('[data-testid="WebRunMonitorView-openLog"]').trigger('click')
    await monitor.get('[data-testid="WebRunMonitorView-transferDefect"]').trigger('click')

    expect(monitor.emitted('openLog')).toEqual([['WEB-1']])
    expect(monitor.emitted('transferDefect')).toEqual([['WEB-1']])
  })

  it('exposes focus and table scroll hooks from visible child contracts', async () => {
    const wrapper = mountWorkspace({ run: runProps }, { attachTo: document.body })
    const exposed = wrapper.vm as unknown as {
      focusMonitorEntry: () => void
      getTableScrollElement: () => HTMLElement | undefined
    }

    exposed.focusMonitorEntry()
    await nextTick()

    expect(document.activeElement).toBe(wrapper.get('[data-testid="WebRunMonitorSummary-focus"]').element)
    expect(exposed.getTableScrollElement()).toBe(wrapper.get('[data-testid="WebAutomationCaseTable-scroll"]').element)

    wrapper.unmount()
  })

  it('emits suite and retry-failed intents from the workbench tools bar', async () => {
    const wrapper = mountWorkspace({ suites: [{ name: '冒烟回归' }], retryFailedCount: 2 })

    await wrapper.get('[data-testid="save-run-suite"]').trigger('click')
    expect(wrapper.emitted('saveSuite')).toEqual([[]])

    await wrapper.get('[data-testid="retry-failed-cases"]').trigger('click')
    expect(wrapper.emitted('retryFailed')).toEqual([[]])

    // 未选择套件时删除按钮不出现（选择后由 suiteSelection 控制出现）。
    expect(wrapper.find('[data-testid="delete-run-suite"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="web-run-suite-select"]').exists()).toBe(true)
  })

  it('hides suite and retry controls when there is nothing to offer', () => {
    const wrapper = mountWorkspace({ suites: [], retryFailedCount: 0 })
    expect(wrapper.find('[data-testid="retry-failed-cases"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="web-run-suite-select"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="save-run-suite"]').exists()).toBe(true)
  })
})

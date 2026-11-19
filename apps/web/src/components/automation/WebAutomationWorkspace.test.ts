// @vitest-environment jsdom
import { defineComponent, nextTick, ref } from 'vue'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi } from 'vitest'
import type { SavedWebAutomationRunCase } from '../../domain/webAutomationRepository'
import type { WebAutomationRunSnapshot } from '../../domain/webAutomationRunRepository'
import { buildWebRunView } from '../../domain/webAutomationRunView'
import WebAutomationWorkspace from './WebAutomationWorkspace.vue'
import workspaceSource from './WebAutomationWorkspace.vue?raw'
// This test runs in Vitest's jsdom environment, where Vite rewrites import.meta.url to a
// non-file URL, so the design CSS is read from disk relative to the project root instead.
// @ts-expect-error Node's test-only filesystem API is intentionally not part of the browser type set.
import { readFileSync } from 'node:fs'
declare const process: { cwd: () => string }

const designSource = (() => {
  const candidates = [
    `${process.cwd()}/src/components/automation/web-execution-design.css`,
    `${process.cwd()}/apps/web/src/components/automation/web-execution-design.css`
  ]
  for (const candidate of candidates) {
    try {
      return readFileSync(candidate, 'utf8')
    } catch {
      // try the next candidate
    }
  }
  throw new Error(`web-execution-design.css not found from ${process.cwd()}`)
})()

const designRule = (selector: string): string => {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = designSource.match(new RegExp(`(?:^|\\n)\\s*${escaped}\\s*\\{([^}]*)\\}`))
  return match?.[1] || ''
}

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
    environments: [
      { value: 'env-1', label: '测试环境', baseUrl: 'https://fixture.example.test/app' },
      { value: 'env-2', label: '预发布环境', baseUrl: 'https://pre.example.test' }
    ],
    browser: 'follow-case',
    browsers: [
      { value: 'follow-case', label: '跟随用例', disabled: false, recommendation: '' },
      { value: 'firefox', label: 'Firefox', disabled: false, recommendation: '' }
    ],
    loopCount: 2,
    loginReadyLabel: '自动登录已启用',
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
    items: [
      { id: 'runner' as const, label: 'Runner', status: 'ready' as const, detail: 'Runner 已连接' },
      { id: 'environment' as const, label: '运行环境', status: 'ready' as const, detail: '测试环境可用' }
    ]
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
      <button v-for="eventName in Object.keys(eventMap)" :key="eventName" type="button" :data-testid="\`\${name}-\${eventName}\`" @click="emitEvent(eventName)">
        {{ eventName }}
      </button>
    </section>
  `
})

const HeaderStub = stub('WebAutomationTaskHeader', {
  openHistory: [],
  generateCases: [],
  exportCases: [],
  editEnvironment: [],
  openRuntimeConfig: [],
  execute: []
})
const PreparationStub = stub('WebRunPreparationBar', {
  updateEnvironmentId: ['env-2'],
  updateBrowser: ['firefox'],
  updateLoopCount: [3]
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

const stubAllChildren = {
  WebAutomationTaskHeader: HeaderStub,
  WebRunPreparationBar: PreparationStub,
  WebAutomationCaseTable: TableStub,
  WebRunPreflightPanel: PreflightStub,
  WebRunMonitorSummary: SummaryStub,
  WebRunMonitorView: MonitorStub
}

const mountWorkspace = (
  overrides: Partial<WorkspaceProps> = {},
  mountOptions: { attachTo?: HTMLElement; withElementPlus?: boolean } = {}
) => mount(WebAutomationWorkspace, {
  props: { ...baseProps, ...overrides },
  attachTo: mountOptions.attachTo,
  global: mountOptions.withElementPlus
    ? { plugins: [ElementPlus] }
    : { stubs: stubAllChildren }
})

const settle = async () => {
  await nextTick()
  await nextTick()
}

// el-dialog / el-drawer 关闭时仍保留 DOM（v-show / overlay），因此用可见性判断“是否打开”。
const isVisibleTestId = (wrapper: { find: (selector: string) => { exists: () => boolean; isVisible: () => boolean } }, testId: string) => {
  const element = wrapper.find(`[data-testid="${testId}"]`)
  return element.exists() && element.isVisible()
}

describe('WebAutomationWorkspace', () => {
  it('registers every component rendered by the workbench template', () => {
    expect(workspaceSource).toContain("import WebRunPreparationBar from './WebRunPreparationBar.vue'")
  })

  it('composes the wide workbench with exactly one idle contextual pane', () => {
    const wrapper = mountWorkspace()

    expect(wrapper.find('[data-testid="web-automation-workspace"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-testid="WebAutomationTaskHeader"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-testid="WebAutomationCaseTable"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-testid="WebRunPreflightPanel"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-testid="WebRunMonitorSummary"]')).toHaveLength(0)
    expect(wrapper.findAll('[data-testid="WebRunMonitorView"]')).toHaveLength(0)
    expect(wrapper.get('[data-testid="WebAutomationCaseTable"]').text()).toContain('订单 退款')
  })

  it('keeps the runtime parameters out of the inline workbench and inside the drawer only', () => {
    const wrapper = mountWorkspace()

    // 定稿：准备页不再有内联运行参数条，参数只在 740px 右侧抽屉里。
    expect(wrapper.find('[data-testid="web-automation-workspace"] > [data-testid="WebRunPreparationBar"]').exists()).toBe(false)
    expect(wrapper.find('.web-automation-workspace__grid [data-testid="WebRunPreparationBar"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="execute-web-automation"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="WebAutomationTaskHeader-execute"]').exists()).toBe(true)
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
    expect(wrapper.findAll('[data-testid="WebAutomationCaseTable"]')).toHaveLength(1)
  })

  it('keeps a compact mounted run as a visible monitor entry', async () => {
    const wrapper = mountWorkspace({ compact: true, run: runProps })

    expect(wrapper.findAll('[data-testid="WebRunMonitorSummary"]')).toHaveLength(1)
    await wrapper.get('[data-testid="WebRunMonitorSummary-enterMonitor"]').trigger('click')
    expect(wrapper.emitted('enterMonitor')).toEqual([[]])
  })

  it('caps the desktop drawer and confirmation dimensions only at the viewport boundary', () => {
    expect(workspaceSource).toContain('size="min(740px, calc(100vw - 48px))"')
    expect(workspaceSource).toContain('width="min(520px, calc(100vw - 24px))"')
  })

  it('forwards every explicit workbench intent with payloads preserved', async () => {
    const wrapper = mountWorkspace()

    const events = [
      ['WebAutomationTaskHeader', 'openHistory', []],
      ['WebAutomationTaskHeader', 'editEnvironment', []],
      ['WebAutomationTaskHeader', 'exportCases', []],
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

describe('WebAutomationWorkspace runtime configuration drawer', () => {
  it('shows the applied environment address read-only in runtime configuration', async () => {
    const wrapper = mountWorkspace({}, { withElementPlus: true, attachTo: document.body })
    await wrapper.get('[data-testid="open-web-runtime-config"]').trigger('click')
    await settle()
    const address = wrapper.find('[data-testid="web-runtime-environment-address"]')
    expect(address.exists()).toBe(true)
    expect(address.text()).toBe('https://fixture.example.test/app')
    expect(address.find('input').exists()).toBe(false)
    wrapper.unmount()
  })

  const mountReal = (overrides: Partial<WorkspaceProps> = {}) =>
    mountWorkspace(overrides, { withElementPlus: true, attachTo: document.body })

  it('opens the measured 740px right drawer with the parameter form and login/session entries', async () => {
    const wrapper = mountReal()

    // 定稿：Session 只在运行配置内，页面不再有顶层 Session 入口。
    expect(wrapper.find('[data-testid="open-session-panel"]').exists()).toBe(false)

    await wrapper.get('[data-testid="open-web-runtime-config"]').trigger('click')
    await settle()

    const drawer = wrapper.get('[data-testid="web-runtime-drawer"]')
    expect(drawer.text()).toContain('Web 运行配置')
    expect(drawer.text()).toContain('环境、浏览器与登录 / Session 统一管理。')
    expect(drawer.find('[aria-label="Web 执行环境"]').exists()).toBe(true)
    expect(drawer.find('[aria-label="浏览器选择"]').exists()).toBe(true)
    expect(drawer.find('[data-testid="loop-input"]').exists()).toBe(true)
    expect(drawer.find('[data-testid="open-session-panel"]').exists()).toBe(true)
    expect(drawer.find('[data-testid="open-web-runtime-login"]').exists()).toBe(true)
    expect(drawer.text()).toContain('自动登录已启用')
    // 执行方式当前由平台固定为顺序执行，只做只读说明，不伪造可选能力。
    expect(drawer.text()).toContain('顺序')

    expect(designRule('.web-ed-drawer.el-drawer')).toMatch(/width:\s*740px/)
    expect(designRule('.web-ed-drawer.el-drawer')).toMatch(/max-width:\s*calc\(100vw - 48px\)/)
    expect(designRule('.web-ed-drawer.el-drawer')).toMatch(/border-radius:\s*24px 0 0 24px/)
    expect(designRule('.web-ed-drawer__title')).toMatch(/font-size:\s*19px/)
  })

  it('applies staged runtime changes once and closes the drawer', async () => {
    const wrapper = mountReal()
    await wrapper.get('[data-testid="open-web-runtime-config"]').trigger('click')
    await settle()

    await wrapper.get('[data-testid="loop-input"]').setValue('5')
    await wrapper.get('[data-testid="apply-web-runtime-config"]').trigger('click')
    await settle()

    expect(wrapper.emitted('updateLoopCount')).toEqual([[5]])
    expect(wrapper.emitted('updateEnvironmentId')).toBeUndefined()
    expect(wrapper.emitted('updateBrowser')).toBeUndefined()
  })

  it('discards unapplied runtime changes when the drawer is dismissed', async () => {
    const wrapper = mountReal()
    await wrapper.get('[data-testid="open-web-runtime-config"]').trigger('click')
    await settle()

    await wrapper.get('[data-testid="loop-input"]').setValue('9')
    await wrapper.get('[data-testid="close-web-runtime-drawer"]').trigger('click')
    await settle()

    expect(wrapper.emitted('updateLoopCount')).toBeUndefined()

    await wrapper.get('[data-testid="open-web-runtime-config"]').trigger('click')
    await settle()
    expect((wrapper.get('[data-testid="loop-input"]').element as HTMLInputElement).value).toBe('2')
  })

  it('refuses to apply an invalid loop count and explains why', async () => {
    const wrapper = mountReal()
    await wrapper.get('[data-testid="open-web-runtime-config"]').trigger('click')
    await settle()

    await wrapper.get('[data-testid="loop-input"]').setValue('0')
    await wrapper.get('[data-testid="apply-web-runtime-config"]').trigger('click')
    await settle()

    expect(wrapper.emitted('updateLoopCount')).toBeUndefined()
    expect(wrapper.get('[data-testid="web-runtime-error"]').text()).toContain('循环次数需为 1–20 的整数')
    expect(wrapper.find('[data-testid="web-runtime-drawer"]').exists()).toBe(true)
  })

  it.each(['0', '1.5', ''])('discards invalid loop text %j when the runtime drawer is reopened', async (invalid) => {
    const wrapper = mountReal()
    try {
      await wrapper.get('[data-testid="open-web-runtime-config"]').trigger('click')
      await settle()
      await wrapper.get('[data-testid="loop-input"]').setValue(invalid)
      expect(wrapper.get('[data-testid="loop-input"]').attributes('aria-invalid')).toBe('true')
      expect(wrapper.get('[data-testid="loop-error"]').text()).toContain('1–20')

      await wrapper.get('[data-testid="close-web-runtime-drawer"]').trigger('click')
      await settle()
      await wrapper.get('[data-testid="open-web-runtime-config"]').trigger('click')
      await settle()

      expect((wrapper.get('[data-testid="loop-input"]').element as HTMLInputElement).value).toBe('2')
      expect(wrapper.get('[data-testid="loop-input"]').attributes('aria-invalid')).toBe('false')
      expect(wrapper.find('[data-testid="loop-error"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="web-runtime-error"]').exists()).toBe(false)
      expect(wrapper.emitted('updateLoopCount')).toBeUndefined()
      await wrapper.get('[data-testid="apply-web-runtime-config"]').trigger('click')
      await settle()
      expect(wrapper.emitted('updateLoopCount')).toBeUndefined()
    } finally {
      wrapper.unmount()
    }
  })

  it('opens the environment editor from the drawer instead of a second inline entry', async () => {
    const wrapper = mountReal()
    await wrapper.get('[data-testid="open-web-runtime-config"]').trigger('click')
    await settle()

    await wrapper.get('[data-testid="open-web-runtime-login"]').trigger('click')

    expect(wrapper.emitted('editEnvironment')).toHaveLength(1)
  })
})

describe('WebAutomationWorkspace execution confirmation', () => {
  const mountReal = (overrides: Partial<WorkspaceProps> = {}) =>
    mountWorkspace(overrides, { withElementPlus: true, attachTo: document.body })

  it('shows the real scope, environment, loop count and preflight checks before executing', async () => {
    const wrapper = mountReal()

    expect(isVisibleTestId(wrapper, 'web-execute-confirm')).toBe(false)

    await wrapper.get('[data-testid="execute-web-automation"]').trigger('click')
    await settle()

    const dialog = wrapper.get('[data-testid="web-execute-confirm"]')
    expect(dialog.text()).toContain('确认本次执行')
    expect(dialog.text()).toContain('执行选中 1 条')
    expect(dialog.text()).toContain('测试环境')
    expect(dialog.text()).toContain('跟随用例')
    expect(dialog.text()).toContain('循环 2 次')
    expect(dialog.text()).toContain('2 次执行')
    expect(dialog.text()).toContain('Runner 已连接')
    expect(dialog.text()).toContain('测试环境可用')
    // 确认只创建执行任务，不声称后台已成功。
    expect(dialog.text()).toContain('确认后创建执行任务')
    expect(wrapper.emitted('execute')).toBeUndefined()
  })

  it('emits execute only after the confirmation is accepted', async () => {
    const wrapper = mountReal()
    await wrapper.get('[data-testid="execute-web-automation"]').trigger('click')
    await settle()

    await wrapper.get('[data-testid="confirm-web-execute"]').trigger('click')
    await settle()

    expect(wrapper.emitted('execute')).toHaveLength(1)
    expect(isVisibleTestId(wrapper, 'web-execute-confirm')).toBe(false)
  })

  it('cancels without creating a run', async () => {
    const wrapper = mountReal()
    await wrapper.get('[data-testid="execute-web-automation"]').trigger('click')
    await settle()

    await wrapper.get('[data-testid="cancel-web-execute"]').trigger('click')
    await settle()

    expect(wrapper.emitted('execute')).toBeUndefined()
    expect(isVisibleTestId(wrapper, 'web-execute-confirm')).toBe(false)
  })

  it('keeps the launch path closed while execution is blocked', async () => {
    const wrapper = mountReal({
      preparation: { ...baseProps.preparation, canExecute: false, blockingReason: 'Runner 未连接' }
    })

    const button = wrapper.get('[data-testid="execute-web-automation"]')
    expect(button.attributes('disabled')).toBeDefined()
    await button.trigger('click')
    await settle()

    expect(isVisibleTestId(wrapper, 'web-execute-confirm')).toBe(false)
    expect(wrapper.emitted('execute')).toBeUndefined()
  })

  it('labels the launch button from the selected scope count instead of a generic label', () => {
    const selected = mountReal({ table: { ...baseProps.table, selectedIds: ['WEB-1', 'WEB-2'] } })
    expect(selected.get('[data-testid="execute-web-automation"]').text()).toBe('执行选中 2 条')

    const filtered = mountReal({ table: { ...baseProps.table, selectedIds: [] } })
    expect(filtered.get('[data-testid="execute-web-automation"]').text()).toBe('执行筛选 1 条')
  })
})

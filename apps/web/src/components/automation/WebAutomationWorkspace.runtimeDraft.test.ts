// @vitest-environment jsdom
// Web 运行配置抽屉：环境地址必须跟随草稿所选环境，执行确认提交的必须是发起瞬间的冻结快照。
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import type { SavedWebAutomationRunCase } from '../../domain/webAutomationRepository'
import WebAutomationWorkspace from './WebAutomationWorkspace.vue'
import WebRunPreparationBar from './WebRunPreparationBar.vue'

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

const draftProps = (environments: Array<{ value: string; label: string; baseUrl?: string }>, environmentId: string) => ({
  mode: 'workbench' as const,
  compact: false,
  header: { executableCount: 1, moduleCount: 1, latestRunLabel: '最近执行 10:00' },
  preparation: {
    environmentId,
    environments,
    browser: 'follow-case',
    browsers: [
      { value: 'follow-case', label: '跟随用例', disabled: false, recommendation: '' },
      { value: 'firefox', label: 'Firefox', disabled: false, recommendation: '' }
    ],
    loopCount: 1,
    loginReadyLabel: '无需自动登录',
    actionLabel: '执行选中',
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
    cases: [testCase()],
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
  statusMessage: ''
})

const mountDraft = (environments: Array<{ value: string; label: string; baseUrl?: string }>, environmentId: string) =>
  mount(WebAutomationWorkspace, {
    props: draftProps(environments, environmentId),
    global: { plugins: [ElementPlus] },
    attachTo: document.body
  })

const settle = async () => {
  await nextTick()
  await nextTick()
  await nextTick()
}

const ENVIRONMENTS = [
  { value: 'env-a', label: '环境 A', baseUrl: 'https://a.example.test' },
  { value: 'env-b', label: '环境 B', baseUrl: 'https://b.example.test' }
]

describe('Web 运行配置抽屉的环境地址', () => {
  it('follows the draft environment instead of the applied one', async () => {
    const wrapper = mountDraft(ENVIRONMENTS, 'env-a')
    await wrapper.get('[data-testid="open-web-runtime-config"]').trigger('click')
    await settle()

    expect(wrapper.get('[data-testid="web-runtime-environment-address"]').text()).toBe('https://a.example.test')

    wrapper.findComponent(WebRunPreparationBar).vm.$emit('updateEnvironmentId', 'env-b')
    await settle()

    expect(wrapper.get('[data-testid="web-runtime-environment-address"]').text()).toBe('https://b.example.test')
    wrapper.unmount()
  })

  it('never borrows another environment address when the draft environment has none', async () => {
    const wrapper = mountDraft([
      { value: 'env-a', label: '环境 A', baseUrl: 'https://a.example.test' },
      { value: 'env-b', label: '环境 B' }
    ], 'env-a')
    await wrapper.get('[data-testid="open-web-runtime-config"]').trigger('click')
    await settle()

    wrapper.findComponent(WebRunPreparationBar).vm.$emit('updateEnvironmentId', 'env-b')
    await settle()

    expect(wrapper.get('[data-testid="web-runtime-environment-address"]').text()).toBe('未配置环境地址')
    wrapper.unmount()
  })
})

describe('Web 执行确认的冻结快照', () => {
  it('describes and emits the applied configuration, not an unapplied draft', async () => {
    const wrapper = mountDraft(ENVIRONMENTS, 'env-a')
    await wrapper.get('[data-testid="open-web-runtime-config"]').trigger('click')
    await settle()
    wrapper.findComponent(WebRunPreparationBar).vm.$emit('updateEnvironmentId', 'env-b')
    await settle()
    await wrapper.get('[data-testid="close-web-runtime-drawer"]').trigger('click')
    await settle()

    await wrapper.get('[data-testid="execute-web-automation"]').trigger('click')
    await settle()
    const dialog = wrapper.get('[data-testid="web-execute-confirm"]').text()
    expect(dialog).toContain('环境 A')
    expect(dialog).toContain('循环 1 次')

    await wrapper.get('[data-testid="confirm-web-execute"]').trigger('click')
    await settle()

    const emitted = wrapper.emitted('execute')
    expect(emitted).toHaveLength(1)
    const request = (emitted![0][0] as {
      environmentId: string
      environmentName: string
      environmentBaseUrl: string
      loopCount: number
      browser: string
      scopeLabel: string
      selectedCaseIds: string[]
    })
    expect(request.environmentId).toBe('env-a')
    expect(request.environmentName).toBe('环境 A')
    expect(request.environmentBaseUrl).toBe('https://a.example.test')
    expect(request.loopCount).toBe(1)
    expect(request.selectedCaseIds).toEqual(['WEB-1'])
    wrapper.unmount()
  })

  it('keeps the emitted request unchanged when the parent props change afterwards', async () => {
    const wrapper = mountDraft(ENVIRONMENTS, 'env-a')
    await wrapper.get('[data-testid="execute-web-automation"]').trigger('click')
    await settle()
    await wrapper.get('[data-testid="confirm-web-execute"]').trigger('click')
    await settle()

    const request = wrapper.emitted('execute')![0][0] as Record<string, unknown>
    const snapshot = JSON.stringify(request)

    await wrapper.setProps({
      preparation: { ...draftProps(ENVIRONMENTS, 'env-a').preparation, environmentId: 'env-b', loopCount: 7, browser: 'firefox' }
    })
    await settle()

    expect(JSON.stringify(wrapper.emitted('execute')![0][0])).toBe(snapshot)
    expect((request as { environmentId: string }).environmentId).toBe('env-a')
    expect((request as { loopCount: number }).loopCount).toBe(1)
    wrapper.unmount()
  })
})

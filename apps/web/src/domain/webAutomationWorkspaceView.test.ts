import { describe, expect, it } from 'vitest'
import {
  buildWebAutomationPreflight,
  buildWebAutomationScope,
  collectWebAutomationModuleCounts,
  type WebAutomationPreflightInput
} from './webAutomationWorkspaceView'
import type { SavedWebAutomationRunCase } from './webAutomationRepository'
import type { WebAutomationCapabilities } from './webAutomationRunRepository'

const makeCase = (
  id: string,
  module = '登录',
  browser: SavedWebAutomationRunCase['browser'] = 'chromium',
  manual = false
): SavedWebAutomationRunCase => ({
  id,
  sourceCaseId: id,
  project: 'Haitlify',
  module,
  pageName: '登录页',
  name: `${module}-${id}`,
  startUrl: '/login',
  browser,
  viewport: '1440x900',
  authenticationMode: 'environment',
  steps: [
    {
      id: `${id}-step-1`,
      name: '打开页面',
      action: manual ? 'manual' : 'goto',
      targetType: 'url',
      target: '/login'
    }
  ],
  runOrder: 1,
  mockOutcome: 'passed',
  automationLibraryId: `auto:${id}`,
  savedAt: '2026-07-01T00:00:00.000Z'
})

const capabilities = (
  status: WebAutomationCapabilities['status'] = 'UP',
  unavailable: Array<{ id: string; label: string; recommendation?: string; message?: string }> = []
): WebAutomationCapabilities => {
  const unavailableById = new Map(unavailable.map((item) => [item.id, item]))
  return {
    status,
    checkedAt: '2026-07-01T00:00:00.000Z',
    browsers: ['chromium', 'firefox', 'webkit'].map((id) => {
      const unavailableBrowser = unavailableById.get(id)
      return {
        id,
        label: id === 'chromium' ? 'Chromium' : id === 'firefox' ? 'Firefox' : 'WebKit',
        available: !unavailableBrowser,
        message: unavailableBrowser?.message,
        recommendation: unavailableBrowser?.recommendation || ''
      }
    })
  }
}

const preflightInput = (
  overrides: Partial<WebAutomationPreflightInput> = {}
): WebAutomationPreflightInput => ({
  scope: buildWebAutomationScope([makeCase('WEB-1')], []),
  capabilities: capabilities(),
  browserOverride: 'chromium',
  environmentName: '默认环境',
  environmentValidation: { valid: true },
  login: { enabled: false, valid: true },
  logout: { enabled: false, valid: true },
  ...overrides
})

describe('webAutomationWorkspaceView', () => {
  it('uses visible selected rows in filtered order and ignores duplicate or hidden selected ids', () => {
    const filteredCases = [makeCase('WEB-1'), makeCase('WEB-2'), makeCase('WEB-3')]
    const selectedIds = ['WEB-3', 'WEB-hidden', 'WEB-1', 'WEB-1']
    const originalFiltered = JSON.parse(JSON.stringify(filteredCases)) as SavedWebAutomationRunCase[]
    const originalSelected = selectedIds.slice()

    const scope = buildWebAutomationScope(filteredCases, selectedIds)

    expect(scope.source).toBe('selected')
    expect(scope.cases.map((testCase) => testCase.id)).toEqual(['WEB-1', 'WEB-3'])
    expect(scope.caseIds).toEqual(['WEB-1', 'WEB-3'])
    expect(scope.count).toBe(2)
    expect(scope.actionLabel).toBe('执行选中')
    expect(scope.canExecute).toBe(true)
    expect(scope.blockingReason).toBe('')
    expect(filteredCases).toEqual(originalFiltered)
    expect(selectedIds).toEqual(originalSelected)
  })

  it('falls back to current filtered rows when no visible selected rows exist', () => {
    const first = makeCase('WEB-1')
    const scope = buildWebAutomationScope([first, makeCase('WEB-2'), first], ['WEB-hidden'])

    expect(scope.source).toBe('filtered')
    expect(scope.caseIds).toEqual(['WEB-1', 'WEB-2'])
    expect(scope.actionLabel).toBe('执行当前')
    expect(scope.canExecute).toBe(true)
  })

  it('disables an empty scope with exact label and reason', () => {
    const scope = buildWebAutomationScope([], ['WEB-hidden'])

    expect(scope).toMatchObject({
      source: 'filtered',
      cases: [],
      caseIds: [],
      count: 0,
      actionLabel: '执行当前',
      canExecute: false,
      blockingReason: '当前筛选结果中没有可执行用例'
    })
  })

  it('aligns effective, action, and preflight counts', () => {
    const scope = buildWebAutomationScope([makeCase('WEB-1'), makeCase('WEB-2')], ['WEB-2'])
    const preflight = buildWebAutomationPreflight(preflightInput({ scope }))

    expect(scope.count).toBe(1)
    expect(scope.actionLabel).toBe('执行选中')
    expect(preflight.effectiveCount).toBe(1)
    expect(preflight.items.find((item) => item.id === 'cases')?.detail).toBe('执行范围已就绪')
  })

  it('marks UP runner, Chromium, valid environment, login off, and logout off as ready', () => {
    const preflight = buildWebAutomationPreflight(preflightInput())

    expect(preflight.canExecute).toBe(true)
    expect(preflight.blockingReason).toBe('')
    expect(preflight.items.map((item) => [item.id, item.status, item.detail])).toEqual([
      ['runner', 'ready', 'Runner 已连接'],
      ['browser', 'ready', 'Chromium 已就绪'],
      ['environment', 'ready', '默认环境 已就绪'],
      ['login', 'ready', '无需自动登录'],
      ['teardown', 'ready', '无需自动清理'],
      ['cases', 'ready', '执行范围已就绪']
    ])
  })

  it('blocks a missing runner capability response and offers a retry', () => {
    const preflight = buildWebAutomationPreflight(preflightInput({ capabilities: undefined }))

    expect(preflight.canExecute).toBe(false)
    expect(preflight.blockingReason).toBe('Runner 未连接')
    expect(preflight.items[0]).toEqual({
      id: 'runner',
      label: 'Runner',
      status: 'blocked',
      detail: 'Runner 未连接',
      repairAction: 'retry-capabilities'
    })
  })

  it.each([undefined, null])('blocks missing browser capability lists (%s) without throwing or permitting execution', (browsers) => {
    const preflight = buildWebAutomationPreflight(preflightInput({
      capabilities: { ...capabilities(), browsers } as unknown as WebAutomationCapabilities
    }))

    expect(preflight.canExecute).toBe(false)
    expect(preflight.blockingReason).toBe('chromium 不可用')
    expect(preflight.items.find((item) => item.id === 'browser')).toEqual({
      id: 'browser',
      label: '浏览器',
      status: 'blocked',
      detail: 'chromium 不可用',
      repairAction: 'retry-capabilities'
    })
  })

  it('blocks unavailable browser with recommendation and keeps first blocking reason in item order', () => {
    const preflight = buildWebAutomationPreflight(
      preflightInput({
        capabilities: capabilities('UP', [{ id: 'firefox', label: 'Firefox', recommendation: '请安装 Firefox' }]),
        browserOverride: 'firefox',
        environmentValidation: { valid: false, message: '环境缺少 BASE_URL' }
      })
    )

    expect(preflight.canExecute).toBe(false)
    expect(preflight.blockingReason).toBe('请安装 Firefox')
    expect(preflight.items.find((item) => item.id === 'browser')).toMatchObject({
      status: 'blocked',
      detail: '请安装 Firefox'
    })
  })

  it('blocks invalid environment, invalid login, and manual steps with item order as the blocking reason', () => {
    const manualScope = buildWebAutomationScope([
      makeCase('WEB-1', '登录', 'chromium', true),
      makeCase('WEB-2')
    ], [])
    const preflight = buildWebAutomationPreflight(
      preflightInput({
        scope: manualScope,
        environmentValidation: { valid: false, message: '环境缺少 BASE_URL' },
        login: { enabled: true, valid: false, message: '登录配置缺少账号' }
      })
    )

    expect(preflight.canExecute).toBe(false)
    expect(preflight.blockingReason).toBe('环境缺少 BASE_URL')
    expect(preflight.items.find((item) => item.id === 'environment')).toMatchObject({
      status: 'blocked',
      repairAction: 'edit-environment'
    })
    expect(preflight.items.find((item) => item.id === 'login')).toMatchObject({
      status: 'blocked',
      detail: '登录配置缺少账号',
      repairAction: 'edit-login'
    })
    expect(preflight.items.find((item) => item.id === 'cases')).toMatchObject({
      status: 'blocked',
      detail: '1 条不可执行，请先补全自动化步骤'
    })
  })

  it('uses validation fallbacks and reports enabled login and teardown readiness', () => {
    const ready = buildWebAutomationPreflight(preflightInput({
      login: { enabled: true, valid: true },
      logout: { enabled: true, valid: true }
    }))
    const invalid = buildWebAutomationPreflight(preflightInput({
      environmentValidation: { valid: false },
      login: { enabled: true, valid: false },
      logout: { enabled: true, valid: false }
    }))

    expect(ready.items.slice(3, 5).map((item) => item.detail)).toEqual([
      '登录配置已就绪',
      '退出步骤已就绪'
    ])
    expect(invalid.items.slice(2, 5).map((item) => [item.detail, item.repairAction])).toEqual([
      ['执行环境无效', 'edit-environment'],
      ['登录配置不完整', 'edit-login'],
      ['退出步骤不完整', 'edit-environment']
    ])
  })

  it('uses the unavailable browser label when no recommendation is provided', () => {
    const preflight = buildWebAutomationPreflight(preflightInput({
      capabilities: capabilities('UP', [{ id: 'webkit', label: 'WebKit' }]),
      browserOverride: 'webkit'
    }))

    expect(preflight.items.find((item) => item.id === 'browser')).toMatchObject({
      status: 'blocked',
      detail: 'WebKit 不可用',
      repairAction: 'retry-capabilities'
    })
  })

  it('keeps a DEGRADED runner warning nonblocking when the required browser is available', () => {
    const preflight = buildWebAutomationPreflight(preflightInput({ capabilities: capabilities('DEGRADED') }))

    expect(preflight.canExecute).toBe(true)
    expect(preflight.blockingReason).toBe('')
    expect(preflight.items.find((item) => item.id === 'runner')).toMatchObject({
      status: 'warning',
      detail: 'Runner 部分能力不可用',
      repairAction: 'retry-capabilities'
    })
  })

  it('verifies all distinct case browsers for follow-case but only explicit override otherwise', () => {
    const scope = buildWebAutomationScope([makeCase('WEB-1', '登录', 'chromium'), makeCase('WEB-2', '报表', 'firefox')], [])
    const browserCapabilities = capabilities('UP', [
      { id: 'firefox', label: 'Firefox', recommendation: '请安装 Firefox' }
    ])

    const followCase = buildWebAutomationPreflight(
      preflightInput({ scope, capabilities: browserCapabilities, browserOverride: 'follow-case' })
    )
    const explicitOverride = buildWebAutomationPreflight(
      preflightInput({ scope, capabilities: browserCapabilities, browserOverride: 'chromium' })
    )

    expect(followCase.items.find((item) => item.id === 'browser')).toMatchObject({
      status: 'blocked',
      detail: '请安装 Firefox'
    })
    expect(explicitOverride.items.find((item) => item.id === 'browser')).toMatchObject({
      status: 'ready',
      detail: 'Chromium 已就绪'
    })
  })

  it('collects module counts in first appearance order', () => {
    expect(collectWebAutomationModuleCounts([
      makeCase('WEB-1', '登录'),
      makeCase('WEB-2', '报表'),
      makeCase('WEB-3', '登录')
    ])).toEqual([
      { value: 'ALL', label: '全部模块', count: 3 },
      { value: '登录', label: '登录', count: 2 },
      { value: '报表', label: '报表', count: 1 }
    ])
  })

  it('uses the empty scope reason and clear-filter repair in preflight', () => {
    const scope = buildWebAutomationScope([], [])
    const preflight = buildWebAutomationPreflight(preflightInput({ scope, browserOverride: 'follow-case' }))

    expect(preflight.effectiveCount).toBe(0)
    expect(preflight.blockingReason).toBe('当前筛选结果中没有可执行用例')
    expect(preflight.items.find((item) => item.id === 'browser')).toEqual({
      id: 'browser',
      label: '浏览器',
      status: 'warning',
      detail: '等待选择可执行用例'
    })
    expect(preflight.items[preflight.items.length - 1]).toEqual({
      id: 'cases',
      label: '执行范围',
      status: 'blocked',
      detail: '当前筛选结果中没有可执行用例',
      repairAction: 'clear-filters'
    })
  })
})

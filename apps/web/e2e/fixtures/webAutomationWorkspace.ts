import { expect, type Page, type Request } from '@playwright/test'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY } from '../../src/domain/executionEnvironmentRepository'
import type { WebAutomationRunCase, WebAutomationRunResult } from '../../src/domain/webAutomation'
import { WEB_AUTOMATION_LIBRARY_STORAGE_KEY, type SavedWebAutomationRunCase } from '../../src/domain/webAutomationRepository'
import type { WebAutomationRunSnapshot } from '../../src/domain/webAutomationRunRepository'
import { WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY } from '../../src/domain/webAutomationRunSession'

export type WebAutomationFixtureVariant =
  | 'idle-ready'
  | 'preflight-blocked'
  | 'creation-failed'
  | 'running'
  | 'partial-result'
  | 'retrying'
  | 'interrupted'
  | 'completed'

export interface OpenWebAutomationFixtureOptions {
  width: number
  height: number
  variant: WebAutomationFixtureVariant
  restoreRunId?: string
  monitorQuery?: boolean
}

export interface WebAutomationFixtureHandle {
  caseIds: string[]
  getCreateBodies: () => Array<{ cases: WebAutomationRunCase[]; loopCount?: number; mode?: string }>
  getRunGetCount: () => number
  getMaxConcurrentRunGets: () => number
}

const projectName = '华东区域跨境订单履约与退款协同平台'
const screenshotPath = resolve(process.cwd(), '../../docs/superpowers/design-assets/web-automation/03-final-workbench-detail.png')

export const openWebAutomationFixture = async (
  page: Page,
  options: OpenWebAutomationFixtureOptions
): Promise<WebAutomationFixtureHandle> => {
  const createBodies: Array<{ cases: WebAutomationRunCase[]; loopCount?: number; mode?: string }> = []
  let runGetCount = 0
  let concurrentRunGets = 0
  let maxConcurrentRunGets = 0
  let transientFailures = 0
  let staleAfterCreate = false
  let forcedStatus: WebAutomationRunSnapshot['status'] | undefined

  await page.clock.setFixedTime(new Date('2026-07-27T10:30:00.000Z'))
  await page.setViewportSize({ width: options.width, height: options.height })
  await page.addInitScript(({ caseKey, cases, envKey, envLibrary, sessionKey, restoreRunId, sidebarCollapsed }) => {
    localStorage.setItem(caseKey, JSON.stringify(cases))
    localStorage.setItem(envKey, JSON.stringify(envLibrary))
    localStorage.setItem('ai-test-platform.sidebar-collapsed', sidebarCollapsed)
    if (restoreRunId) sessionStorage.setItem(sessionKey, restoreRunId)
  }, {
    caseKey: WEB_AUTOMATION_LIBRARY_STORAGE_KEY,
    cases: fixtureCases(),
    envKey: WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY,
    envLibrary: fixtureEnvironmentLibrary(),
    sessionKey: WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY,
    restoreRunId: options.restoreRunId,
    sidebarCollapsed: options.width < 1180 ? 'true' : 'false'
  })

  await page.route('**/web-automation-fixture/run-fixture.png', async (route) => {
    await route.fulfill({
      contentType: 'image/png',
      body: await readFile(screenshotPath)
    })
  })

  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const pathname = url.pathname

    if (pathname === '/api/web-automation/capabilities') {
      await route.fulfill({ json: capabilities(options.variant !== 'preflight-blocked') })
      return
    }

    if (pathname === '/api/web-automation/runs' && request.method() === 'POST') {
      if (options.variant === 'creation-failed') {
        await route.fulfill({ status: 503, json: { message: 'fixture runner refused creation' } })
        return
      }
      createBodies.push(await request.postDataJSON())
      if (options.variant === 'interrupted') staleAfterCreate = true
      await route.fulfill({ json: snapshotFor(options.variant === 'completed' ? 'completed' : 'running') })
      return
    }

    if (pathname === '/api/web-automation/runs/run-fixture' && request.method() === 'GET') {
      runGetCount += 1
      concurrentRunGets += 1
      maxConcurrentRunGets = Math.max(maxConcurrentRunGets, concurrentRunGets)
      try {
        if (staleAfterCreate) {
          staleAfterCreate = false
          await route.fulfill({ status: 404, json: { message: 'run not found' } })
          return
        }
        if (options.variant === 'retrying' && transientFailures < 1 && (!options.restoreRunId || runGetCount > 1)) {
          transientFailures += 1
          await route.fulfill({ status: 503, json: { message: 'temporary runner outage' } })
          return
        }
        if (options.variant === 'interrupted' && options.restoreRunId) {
          await route.fulfill({ status: 404, json: { message: 'run not found' } })
          return
        }
        await route.fulfill({ json: forcedStatus ? snapshotFor('completed', forcedStatus) : snapshotFor(options.variant) })
      } finally {
        concurrentRunGets -= 1
      }
      return
    }

    if (pathname === '/api/web-automation/runs/run-fixture/pause' && request.method() === 'POST') {
      forcedStatus = 'paused'
      await route.fulfill({ json: snapshotFor('running', 'paused') })
      return
    }
    if (pathname === '/api/web-automation/runs/run-fixture/resume' && request.method() === 'POST') {
      forcedStatus = undefined
      await route.fulfill({ json: snapshotFor('running') })
      return
    }
    if (pathname === '/api/web-automation/runs/run-fixture/stop' && request.method() === 'POST') {
      forcedStatus = 'stopped'
      await route.fulfill({ json: snapshotFor('completed', 'stopped') })
      return
    }

    await route.fulfill({ status: 503, json: { message: 'fixture backend unavailable' } })
  })

  const search = options.monitorQuery && options.restoreRunId
    ? `?view=monitor&runId=${encodeURIComponent(options.restoreRunId)}`
    : ''
  await page.goto(`/automation/web${search}`)
  await selectWebAutomationFromSidebar(page, options.width)
  await expect(page.getByTestId('web-automation-workspace')).toBeVisible()

  return {
    caseIds: fixtureCases().map((testCase) => testCase.id),
    getCreateBodies: () => createBodies,
    getRunGetCount: () => runGetCount,
    getMaxConcurrentRunGets: () => maxConcurrentRunGets
  }
}

const selectWebAutomationFromSidebar = async (page: Page, width: number) => {
  if (width < 900) {
    await page.getByRole('button', { name: '展开侧边栏' }).click()
    await page.getByRole('dialog', { name: '主导航' }).getByRole('button', { name: 'Web 自动化' }).click()
    return
  }
  await page.locator('.studio-sidebar').getByRole('button', { name: 'Web 自动化' }).click()
}

const capabilities = (chromiumAvailable: boolean) => ({
  status: chromiumAvailable ? 'UP' : 'DEGRADED',
  checkedAt: '2026-07-27T10:30:00.000Z',
  browsers: [
    {
      id: 'chromium',
      label: 'Chromium',
      available: chromiumAvailable,
      recommendation: chromiumAvailable ? 'Chromium 已安装' : '请安装 Chromium'
    },
    { id: 'firefox', label: 'Firefox', available: false, recommendation: '本机未安装 Firefox' }
  ]
})

const fixtureEnvironmentLibrary = () => ({
  activeEnvironmentId: 'web-env-chromium',
  environments: [{
    id: 'web-env-chromium',
    name: 'Chromium Web 环境',
    baseUrl: 'https://fixture.example.test',
    auth: { type: 'none' },
    entries: [],
    web: {
      browser: 'chromium',
      viewport: '1440x900',
      headless: true,
      defaultTimeoutMs: 30000,
      navigationTimeoutMs: 45000,
      waitUntil: 'networkidle',
      screenshotStrategy: 'always',
      videoRecording: false,
      retryCount: 0,
      cookieOrigins: [],
      login: {
        enabled: false,
        loginUrl: '',
        usernameLocator: '',
        passwordLocator: '',
        submitLocator: '',
        username: '',
        password: '',
        successType: 'url',
        successTarget: ''
      },
      logout: { enabled: false, steps: [] }
    },
    createdAt: '2026-07-27T10:00:00.000Z',
    updatedAt: '2026-07-27T10:00:00.000Z'
  }]
})

const fixtureCases = (): SavedWebAutomationRunCase[] => [
  runCase(1, 'WEB-E2E-0001', '首页订单状态回补成功后刷新列表数据', '/orders/refund/1', 'passed'),
  runCase(2, 'WEB-E2E-0002', '跨境订单退款状态回补、支付渠道重试、客服备注同步与项目仪表盘红点刷新联动校验', '/orders/refund/2', 'failed'),
  runCase(3, 'WEB-E2E-0003', '重复回调失败时保留诊断证据', '/orders/refund/3', 'error')
]

const runCase = (
  order: number,
  sourceCaseId: string,
  name: string,
  startUrl: string,
  mockOutcome: 'passed' | 'failed' | 'error'
): SavedWebAutomationRunCase => {
  const id = `WEB-AUTO-${sourceCaseId}`
  return {
    id,
    sourceCaseId,
    project: projectName,
    module: order === 3 ? '支付回调' : '退款与售后协同处理',
    pageName: order === 3 ? '支付回调诊断页' : '跨境订单退款详情与状态追踪页面',
    name,
    startUrl,
    environmentId: 'web-env-chromium',
    browser: 'chromium',
    viewport: '1440x900',
    authenticationMode: 'environment',
    steps: [
      { id: `${id}-setup`, name: '打开目标页面', action: 'goto', targetType: 'url', target: startUrl },
      { id: `${id}-assert`, name: '检查状态回补结果', action: 'assertVisible', targetType: 'locator', target: '.refund-status' }
    ],
    runOrder: order,
    mockOutcome,
    automationLibraryId: `${projectName}:退款:${sourceCaseId}`,
    savedAt: '2026-07-27T10:00:00.000Z'
  }
}

const snapshotFor = (
  variant: WebAutomationFixtureVariant,
  status: WebAutomationRunSnapshot['status'] = variant === 'completed' ? 'completed' : 'running'
): WebAutomationRunSnapshot => {
  const cases = fixtureCases()
  const first = result(cases[0], 'passed')
  const second = result(cases[1], 'failed')
  const third = result(cases[2], 'error')
  const terminal = status === 'completed' || status === 'failed' || status === 'stopped'
  const results = variant === 'partial-result'
    ? [first, second]
    : terminal ? [first, second, third] : [first]
  const currentCase = terminal ? undefined : cases[Math.min(results.length, cases.length - 1)]

  return {
    runId: 'run-fixture',
    status,
    mode: 'sequential',
    selectedBrowser: 'chromium',
    createdAt: '2026-07-27T10:30:00.000Z',
    startedAt: '2026-07-27T10:30:01.000Z',
    updatedAt: terminal ? '2026-07-27T10:31:10.000Z' : '2026-07-27T10:30:15.000Z',
    endedAt: terminal ? '2026-07-27T10:31:10.000Z' : undefined,
    progress: {
      totalCases: 3,
      completedCases: results.length,
      totalSteps: 8,
      completedSteps: terminal ? 8 : 4
    },
    currentCase: currentCase ? { id: currentCase.id, name: currentCase.name } : undefined,
    currentStep: terminal ? undefined : {
      id: `${currentCase?.id}-assert`,
      name: '检查状态回补结果',
      action: 'assertVisible',
      phase: 'test'
    },
    currentPageUrl: currentCase?.startUrl || cases[1].startUrl,
    latestScreenshotUrl: '/web-automation-fixture/run-fixture.png',
    latestScreenshotAt: '2026-07-27T10:30:12.000Z',
    controls: {
      canPause: status === 'running',
      canResume: status === 'paused',
      canStop: status === 'running' || status === 'paused'
    },
    summary: {
      total: 3,
      passed: results.filter((item) => item.status === 'passed').length,
      failed: results.filter((item) => item.status === 'failed').length,
      error: results.filter((item) => item.status === 'error').length
    },
    stepLogs: [
      { stepId: 'setup-open', phase: 'setup', name: '打开目标页面', action: 'goto', target: cases[0].startUrl, status: 'passed', durationMs: 480, pageUrl: cases[0].startUrl },
      { stepId: 'case-pass', phase: 'test', name: '检查状态回补结果', action: 'assertVisible', target: '.refund-status', status: 'passed', durationMs: 220, pageUrl: cases[0].startUrl },
      ...(results.some((item) => item.status === 'failed')
        ? [{ stepId: 'case-fail', phase: 'test' as const, name: '检查支付渠道重试', action: 'assertText' as const, target: '.payment-retry-state', status: 'failed' as const, durationMs: 310, pageUrl: cases[1].startUrl, errorMessage: '断言失败：支付渠道状态仍为处理中' }]
        : [])
    ],
    results,
    diagnostics: results.some((item) => item.status !== 'passed')
      ? ['断言失败：支付渠道状态仍为处理中']
      : ['页面截图已采集']
  }
}

const result = (
  testCase: SavedWebAutomationRunCase,
  status: WebAutomationRunResult['status']
): WebAutomationRunResult => ({
  caseId: testCase.id,
  sourceCaseId: testCase.sourceCaseId,
  name: testCase.name,
  module: testCase.module,
  pageName: testCase.pageName,
  startUrl: testCase.startUrl,
  status,
  durationMs: status === 'passed' ? 880 : 1260,
  stepLogs: [
    { stepId: `${testCase.id}-setup`, phase: 'setup', name: '打开目标页面', action: 'goto', target: testCase.startUrl, status: 'passed', durationMs: 420, pageUrl: testCase.startUrl },
    {
      stepId: `${testCase.id}-assert`,
      phase: 'test',
      name: '检查状态回补结果',
      action: 'assertVisible',
      target: '.refund-status',
      status: status === 'passed' ? 'passed' : 'failed',
      durationMs: 460,
      pageUrl: testCase.startUrl,
      errorMessage: status === 'passed' ? undefined : '断言失败：支付渠道状态仍为处理中'
    }
  ],
  failedStepId: status === 'passed' ? undefined : `${testCase.id}-assert`,
  pageHtmlSnippet: status === 'passed' ? undefined : '<main><span class="payment-retry-state">处理中</span></main>',
  screenshotPath: status === 'passed' ? undefined : '/web-automation-fixture/run-fixture.png',
  errorMessage: status === 'passed' ? undefined : '断言失败：支付渠道状态仍为处理中',
  consoleErrors: status === 'error' ? ['Uncaught fixture error'] : [],
  pageErrors: [],
  failedRequests: status === 'error' ? ['GET /api/refund/status 500'] : []
})

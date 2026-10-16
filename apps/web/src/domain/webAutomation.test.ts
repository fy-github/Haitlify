import { describe, expect, it } from 'vitest'
import { defaultWebLoginConfig, defaultWebLogoutConfig } from './executionEnvironmentRepository'
import {
  exportWebAutomationRunCases,
  generateWebAutomationCasesFromGeneratedCases,
  validateWebAutomationConversionCases,
  removeResultsFromWebAutomationRunReport,
  generateWebAutomationCasesFromSavedCases,
  runWebAutomationCases,
  type WebAutomationRunCase
} from './webAutomation'
import type { GeneratedWebAutomationCase, SavedWebAutomationCase } from './webAutomationCase'

const makeSavedWebCase = (
  caseId: string,
  title: string,
  overrides: Partial<SavedWebAutomationCase['row']> = {}
): SavedWebAutomationCase => ({
  id: caseId,
  libraryId: `login.yaml:登录页:${overrides.startUrl || '/login'}:正向`,
  savedAt: '2026-06-22T10:00:00.000Z',
  row: {
    caseId,
    project: 'Haitlify',
    module: '登录',
    pageName: '登录页',
    title,
    caseType: '正向',
    precondition: '',
    startUrl: '/login',
    browser: 'chromium',
    viewport: '1440x900',
    testData: '{}',
    steps: '1. 打开登录页',
    locators: '#username',
    assertions: '工作台可见',
    waitStrategy: 'networkidle',
    screenshotStrategy: 'on_failure',
    priority: 'P1',
    executionResult: '未执行',
    executor: '',
    executedAt: '',
    defectId: '',
    tags: '登录,web',
    sourceFile: 'login.yaml',
    remark: '',
    ...overrides
  },
  metadata: {
    sourceFile: 'login.yaml',
    sourceDocumentType: 'yaml',
    pageName: '登录页',
    startUrl: overrides.startUrl || '/login',
    browser: 'chromium',
    viewport: '1440x900',
    steps: [
      {
        id: 'step-1',
        name: '打开登录页',
        action: 'goto',
        targetType: 'url',
        target: overrides.startUrl || '/login'
      }
    ],
    locators: [],
    assertions: [],
    variables: {},
    runOrder: caseId === 'WEB-0001' ? 1 : 2
  }
})

describe('webAutomation', () => {
  it('converts saved web cases into runnable web automation cases', () => {
    const cases = generateWebAutomationCasesFromSavedCases([
      makeSavedWebCase('WEB-0001', '登录成功')
    ])

    expect(cases).toEqual([
      expect.objectContaining({
        id: 'WEB-AUTO-WEB-0001',
        sourceCaseId: 'WEB-0001',
        project: 'Haitlify',
        module: '登录',
        pageName: '登录页',
        name: '登录成功',
        startUrl: '/login',
        runOrder: 1
      })
    ])
  })

  it('keeps Chrome and Edge selections when converting runnable cases', () => {
    const cases = generateWebAutomationCasesFromSavedCases([
      makeSavedWebCase('WEB-0001', 'Chrome 登录', { browser: 'chrome' }),
      makeSavedWebCase('WEB-0002', 'Edge 登录', { browser: 'msedge' })
    ])

    expect(cases.map((item) => item.browser)).toEqual(['chrome', 'msedge'])
  })

  it('exports current runnable Web cases without local storage metadata', () => {
    const [automationCase] = generateWebAutomationCasesFromSavedCases([
      makeSavedWebCase('WEB-0001', '登录成功')
    ])
    const savedCase = {
      ...automationCase,
      automationLibraryId: 'Haitlify:登录:登录页:/login:WEB-0001',
      savedAt: '2026-07-27T08:00:00.000Z'
    }

    const packageData = exportWebAutomationRunCases([savedCase], '2026-07-27T09:00:00.000Z')

    expect(packageData).toEqual({
      kind: 'ai-test-platform.web-automation-cases',
      version: 1,
      exportedAt: '2026-07-27T09:00:00.000Z',
      cases: [automationCase]
    })
    expect(JSON.stringify(packageData)).not.toContain('automationLibraryId')
    expect(JSON.stringify(packageData)).not.toContain('savedAt')
  })

  it('exports artifact provenance and revision metadata with runnable Web cases', () => {
    const [automationCase] = generateWebAutomationCasesFromSavedCases([
      makeSavedWebCase('WEB-0001', '带追溯信息')
    ])
    const packageData = exportWebAutomationRunCases([{
      ...automationCase,
      agentRunId: 'run-1',
      agentSourcePointId: 'point-1',
      agentEvidenceIds: ['evidence-1'],
      artifactId: 'artifact-1',
      artifactRevision: 2,
      artifactResultOrigin: 'HUMAN_EDITED',
      artifactAcceptanceState: 'ACCEPTED',
      artifactModelProfileVersion: 'profile-v2',
      artifactFieldOrigins: ['MODEL_PROPOSED', 'HUMAN_REQUIRED'],
      artifactEvidenceIds: ['evidence-1'],
      artifactParentArtifactId: 'artifact-0',
      artifactIdempotencyKey: 'run-1:artifact-1:2'
    } as typeof automationCase], '2026-09-22T12:00:00.000Z')

    expect(packageData.cases[0]).toMatchObject({
      agentRunId: 'run-1',
      agentSourcePointId: 'point-1',
      agentEvidenceIds: ['evidence-1'],
      artifactId: 'artifact-1',
      artifactRevision: 2,
      artifactResultOrigin: 'HUMAN_EDITED',
      artifactAcceptanceState: 'ACCEPTED',
      artifactModelProfileVersion: 'profile-v2',
      artifactFieldOrigins: ['MODEL_PROPOSED', 'HUMAN_REQUIRED'],
      artifactEvidenceIds: ['evidence-1'],
      artifactParentArtifactId: 'artifact-0',
      artifactIdempotencyKey: 'run-1:artifact-1:2'
    })
  })

  it('converts generated web cases directly into runnable web automation cases', () => {
    const savedCase = makeSavedWebCase('WEB-0001', '登录成功')
    const generatedCase: GeneratedWebAutomationCase = {
      id: savedCase.id,
      row: savedCase.row,
      metadata: {
        ...savedCase.metadata,
        agentActions: [{
          id: 'action-1',
          type: 'click',
          safety: 'readonly',
          target: { evidenceId: 'evidence-1' }
        }]
      }
    }

    const [automationCase] = generateWebAutomationCasesFromGeneratedCases([generatedCase])

    expect(automationCase).toMatchObject({
      id: 'WEB-AUTO-WEB-0001',
      sourceCaseId: 'WEB-0001',
      project: 'Haitlify',
      module: '登录',
      pageName: '登录页',
      name: '登录成功',
      startUrl: '/login',
      runOrder: 1,
      actions: [{
        id: 'action-1',
        type: 'click',
        safety: 'readonly',
        target: { evidenceId: 'evidence-1' }
      }]
    })
  })

  it('runs web automation cases in mock mode and reports pass/fail/error', async () => {
    const cases: WebAutomationRunCase[] = [
      {
        ...generateWebAutomationCasesFromSavedCases([makeSavedWebCase('WEB-0001', '登录成功')])[0],
        mockOutcome: 'passed'
      },
      {
        ...generateWebAutomationCasesFromSavedCases([makeSavedWebCase('WEB-0002', '登录失败', { startUrl: '/login/error' })])[0],
        mockOutcome: 'failed'
      },
      {
        ...generateWebAutomationCasesFromSavedCases([makeSavedWebCase('WEB-0003', '页面崩溃', { startUrl: '/login/crash' })])[0],
        mockOutcome: 'error'
      }
    ]

    const report = await runWebAutomationCases(cases, {
      mode: 'sequential'
    })

    expect(report.summary).toEqual({
      total: 3,
      passed: 1,
      failed: 1,
      error: 1
    })
    expect(report.results.map((item) => item.status)).toEqual(['passed', 'failed', 'error'])
    expect(report.results[0].stepLogs?.map((item) => item.action)).toEqual(['goto'])
    expect(report.results[0].stepLogs?.[0].status).toBe('passed')
    expect(report.results[0].stepLogs?.[0].durationMs).toBeGreaterThanOrEqual(0)
    expect(report.results[1].failedStepId).toBeTruthy()
    expect(report.results[1].pageHtmlSnippet).toContain('mock')
  })

  it('runs web automation cases repeatedly when loop count is configured', async () => {
    const cases = generateWebAutomationCasesFromSavedCases([
      makeSavedWebCase('WEB-0001', '登录成功'),
      makeSavedWebCase('WEB-0002', '登录失败', { startUrl: '/login/error' })
    ])

    const report = await runWebAutomationCases(cases, {
      mode: 'sequential',
      loopCount: 2
    })

    expect(report.summary.total).toBe(4)
    expect(report.results.map((item) => item.caseId)).toEqual([
      'WEB-AUTO-WEB-0001#1',
      'WEB-AUTO-WEB-0002#1',
      'WEB-AUTO-WEB-0001#2',
      'WEB-AUTO-WEB-0002#2'
    ])
  })

  it('applies Web runtime defaults when running cases in local mock mode', async () => {
    const cases = generateWebAutomationCasesFromSavedCases([
      makeSavedWebCase('WEB-0001', '登录成功', {
        startUrl: '/login',
        browser: 'webkit',
        viewport: '1024x768'
      })
    ])

    const report = await runWebAutomationCases(cases, {
      mode: 'sequential',
      runtime: {
        networkPolicyMode: 'blocklist',
        baseUrl: 'https://haitlify.example.com',
        browser: 'firefox',
        viewport: '1280x720',
        headless: true,
        defaultTimeoutMs: 30000,
        navigationTimeoutMs: 45000,
        waitUntil: 'networkidle',
        screenshotStrategy: 'on_failure',
        videoRecording: false,
        retryCount: 1,
        authToken: '',
        extraHeaders: {},
        cookieOrigins: [],
        variables: {},
        login: defaultWebLoginConfig(),
        logout: defaultWebLogoutConfig()
      }
    })

    expect(report.results[0].startUrl).toBe('https://haitlify.example.com/login')
  })

  it('keeps the Web runtime entry url when a web case has no start url', async () => {
    const cases = generateWebAutomationCasesFromSavedCases([
      makeSavedWebCase('WEB-0001', '登录成功', {
        startUrl: ''
      })
    ])

    const report = await runWebAutomationCases(cases, {
      mode: 'sequential',
      runtime: {
        networkPolicyMode: 'blocklist',
        baseUrl: 'https://haitlify.example.com/login',
        browser: 'chromium',
        viewport: '1440x900',
        headless: true,
        defaultTimeoutMs: 30000,
        navigationTimeoutMs: 45000,
        waitUntil: 'networkidle',
        screenshotStrategy: 'on_failure',
        videoRecording: false,
        retryCount: 0,
        authToken: '',
        extraHeaders: {},
        cookieOrigins: [],
        variables: {},
        login: defaultWebLoginConfig(),
        logout: defaultWebLogoutConfig()
      }
    })

    expect(report.results[0].startUrl).toBe('https://haitlify.example.com/login')
  })

  it('removes deleted web automation results from the current run report', async () => {
    const cases = generateWebAutomationCasesFromSavedCases([
      makeSavedWebCase('WEB-0001', '登录成功'),
      makeSavedWebCase('WEB-0002', '登录失败', { startUrl: '/login/error' })
    ])
    const report = await runWebAutomationCases(cases, { mode: 'sequential' })
    const next = removeResultsFromWebAutomationRunReport(report, [cases[0].id])

    expect(next?.results.map((item) => item.caseId)).toEqual([cases[1].id])
    expect(next?.summary).toEqual({
      total: 1,
      passed: 0,
      failed: 1,
      error: 0
    })
  })

  it('rejects Web cases that cannot execute automatically', () => {
    const valid = generateWebAutomationCasesFromSavedCases([
      makeSavedWebCase('WEB-0001', '登录成功')
    ])[0]
    const invalid = {
      ...valid,
      id: 'WEB-AUTO-WEB-0002',
      sourceCaseId: 'WEB-0002',
      steps: [
        {
          id: 'step-1',
          name: '自然语言步骤',
          action: 'manual' as const,
          targetType: 'locator' as const,
          target: '1. 登录系统并进入用户中心'
        }
      ]
    }

    expect(validateWebAutomationConversionCases([valid])).toEqual([])
    expect(validateWebAutomationConversionCases([invalid])).toEqual([
      {
        caseId: 'WEB-0002',
        message: '步骤 1 使用 manual，不能自动执行'
      }
    ])
  })
})

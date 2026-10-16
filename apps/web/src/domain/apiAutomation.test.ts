import { describe, expect, it } from 'vitest'
import type { GeneratedInterfaceCase } from './caseGenerator'
import {
  buildAutomationCurlCommand,
  buildAutomationExecutableRequest,
  exportApifoxAutomationDraft,
  generateAutomationCasesFromInterfaceCases,
  removeResultsFromAutomationRunReport,
  runAutomationCases,
  type ApiAutomationCaseDraft
} from './apiAutomation'

const generatedCase: GeneratedInterfaceCase = {
  id: 'API-0001',
  row: {
    caseId: 'API-0001',
    project: 'Haitlify',
    module: '项目成员',
    interfaceName: '新增项目成员',
    method: 'POST',
    path: '/api/projects/{projectId}/members',
    title: '新增项目成员-正常请求成功',
    caseType: '正向',
    precondition: '',
    headers: '{}',
    pathParams: '{}',
    queryParams: '{}',
    body: '{}',
    steps: '1. 请求接口',
    expectedResult: '返回成功',
    assertions: '响应状态码为 201',
    priority: 'P1',
    executionResult: '未执行',
    executor: '',
    executedAt: '',
    defectId: '',
    tags: '项目成员,正向,AI生成',
    sourceFile: 'Haitlify.openapi.json',
    remark: ''
  },
  metadata: {
    sourceSpecId: 'Haitlify.openapi.json:createProjectMember',
    sourceFile: 'Haitlify.openapi.json',
    sourceOperationId: 'createProjectMember',
    tag: '项目成员',
    method: 'POST',
    path: '/api/projects/{projectId}/members',
    baseUrlRef: '',
    environmentId: '',
    authProfileId: '',
    contentType: 'application/json',
    headers: { 'Content-Type': 'application/json' },
    pathParams: { projectId: 'projectId_example' },
    queryParams: {},
    body: { userId: 'userId_example' },
    expectedStatus: 201,
    assertions: ['响应状态码为 201'],
    runOrder: 1,
    expectedBodyRules: [],
    variableExtractions: [],
    dependsOnCaseIds: [],
    generationMeta: {
      granularity: 'risk',
      strategy: 'deterministic-risk-rules'
    },
    reviewMeta: {
      status: '待复核'
    },
    exportMeta: {
      apifoxReady: false
    }
  }
}

describe('apiAutomation', () => {
  it('converts generated interface cases into automation case drafts', () => {
    const [automationCase] = generateAutomationCasesFromInterfaceCases([generatedCase])

    expect(automationCase).toMatchObject({
      id: 'AUTO-API-0001',
      sourceCaseId: 'API-0001',
      project: 'Haitlify',
      module: '项目成员',
      name: '新增项目成员-正常请求成功',
      method: 'POST',
      path: '/api/projects/{projectId}/members',
      expectedStatus: 201,
      assertions: ['响应状态码为 201'],
      runOrder: 1,
      exportTarget: 'apifox'
    })
  })

  it('builds executable requests and cURL commands from automation drafts', () => {
    const [automationCase] = generateAutomationCasesFromInterfaceCases([generatedCase])

    const request = buildAutomationExecutableRequest(automationCase, {
      baseUrl: 'https://api.example.com',
      authToken: 'token-1',
      extraHeaders: {
        'X-Tenant-Id': 'tenant-1'
      }
    })

    expect(request).toEqual({
      method: 'POST',
      url: 'https://api.example.com/api/projects/projectId_example/members',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer token-1',
        'X-Tenant-Id': 'tenant-1'
      },
      body: {
        userId: 'userId_example'
      },
      expectedStatus: 201,
      assertions: ['响应状态码为 201']
    })
    expect(buildAutomationCurlCommand(automationCase, { baseUrl: 'https://api.example.com' })).toContain(
      "curl -X POST 'https://api.example.com/api/projects/projectId_example/members'"
    )
  })

  it('builds automation requests from absolute URLs and copied curl headers', () => {
    const automationCase: ApiAutomationCaseDraft = {
      ...generateAutomationCasesFromInterfaceCases([generatedCase])[0],
      method: 'GET',
      path: 'https://api.example.test/v4/pm/project-tree',
      headers: {},
      body: undefined,
      expectedStatus: 200
    }

    const request = buildAutomationExecutableRequest(automationCase, {
      baseUrl: 'https://wrong.example.com',
      requestHeadersText: [
        "-H 'Accept: application/json, text/plain, */*'",
        "-H 'Authorization: Bearer token-from-curl'",
        "-H 'Origin: https://app.example.test'",
        "-H 'Referer: https://app.example.test/'",
        "-H 'isAjax: 1'",
        "-b 'haitlify-token=cookie-token'"
      ].join('\n')
    })

    expect(request).toMatchObject({
      method: 'GET',
      url: 'https://api.example.test/v4/pm/project-tree',
      headers: {
        Accept: 'application/json, text/plain, */*',
        Authorization: 'Bearer token-from-curl',
        Origin: 'https://app.example.test',
        Referer: 'https://app.example.test/',
        isAjax: '1',
        Cookie: 'haitlify-token=cookie-token'
      },
      expectedStatus: 200
    })
  })

  it('exports a versioned Apifox-oriented automation draft package', () => {
    const [automationCase] = generateAutomationCasesFromInterfaceCases([generatedCase])

    const packageData = exportApifoxAutomationDraft([automationCase], {
      exportedAt: '2026-06-10T12:00:00.000Z',
      environment: {
        baseUrl: 'https://api.example.com'
      }
    })

    expect(packageData).toMatchObject({
      kind: 'ai-test-platform.apifox-automation-draft',
      version: 1,
      exportedAt: '2026-06-10T12:00:00.000Z',
      target: 'apifox',
      environment: {
        baseUrl: 'https://api.example.com'
      },
      cases: [
        {
          id: 'AUTO-API-0001',
          sourceCaseId: 'API-0001',
          name: '新增项目成员-正常请求成功',
          request: {
            method: 'POST',
            path: '/api/projects/{projectId}/members',
            pathParams: {
              projectId: 'projectId_example'
            },
            body: {
              userId: 'userId_example'
            }
          },
          assertions: ['响应状态码为 201']
        }
      ]
    })
  })

  it('runs automation cases sequentially and records pass/fail results', async () => {
    const cases = buildAutomationCases(['A-1', 'A-2'])
    const calls: string[] = []
    cases[0].expectedResponseBody = { ok: true, data: { id: 'A-1' } }

    const result = await runAutomationCases(cases, { baseUrl: 'https://api.example.com' }, {
      mode: 'sequential'
    }, async (_request, testCase) => {
      calls.push(testCase.id)
      return {
        status: testCase.id === 'A-1' ? 200 : 500,
        body: testCase.id === 'A-1' ? { ok: true, data: { id: 'A-1' } } : { ok: false }
      }
    })

    expect(calls).toEqual(['A-1', 'A-2'])
    expect(result.summary).toEqual({
      total: 2,
      passed: 1,
      failed: 1,
      error: 0
    })
    expect(result.results.map((item) => item.status)).toEqual(['passed', 'failed'])
    expect(result.results[0].request.url).toBe('https://api.example.com/api/A-1')
    expect(result.results[1].responseBody).toEqual({ ok: false })
    expect(result.results[0]).toMatchObject({
      expectedResponseBody: {
        ok: true,
        data: {
          id: 'A-1'
        }
      },
      actualResponseBody: {
        ok: true
      }
    })
  })

  it('runs automation cases repeatedly when loop count is configured', async () => {
    const cases = buildAutomationCases(['A-1', 'A-2'])
    const calls: string[] = []

    const result = await runAutomationCases(cases, { baseUrl: 'https://api.example.com' }, {
      mode: 'sequential',
      loopCount: 3
    }, async (_request, testCase) => {
      calls.push(testCase.id)
      return { status: 200, body: { ok: true } }
    })

    expect(calls).toEqual(['A-1#1', 'A-2#1', 'A-1#2', 'A-2#2', 'A-1#3', 'A-2#3'])
    expect(result.summary.total).toBe(6)
    expect(result.results.map((item) => item.caseId)).toEqual([
      'A-1#1',
      'A-2#1',
      'A-1#2',
      'A-2#2',
      'A-1#3',
      'A-2#3'
    ])
  })

  it('runs automation cases concurrently', async () => {
    const cases = buildAutomationCases(['A-1', 'A-2', 'A-3'])
    const started: string[] = []
    const resolvers: Array<() => void> = []

    const promise = runAutomationCases(cases, { baseUrl: 'https://api.example.com' }, {
      mode: 'concurrent'
    }, async (_request, testCase) => {
      started.push(testCase.id)
      await new Promise<void>((resolve) => resolvers.push(resolve))
      return { status: 200 }
    })

    await Promise.resolve()
    expect(started).toEqual(['A-1', 'A-2', 'A-3'])
    resolvers.forEach((resolve) => resolve())
    await expect(promise).resolves.toMatchObject({
      summary: {
        total: 3,
        passed: 3
      }
    })
  })

  it('emits progress updates while automation cases are running', async () => {
    const cases = buildAutomationCases(['A-1', 'A-2', 'A-3'])
    const progressEvents: Array<{ completed: number; total: number; runningCaseId?: string }> = []

    await runAutomationCases(cases, { baseUrl: 'https://api.example.com' }, {
      mode: 'sequential'
    }, async () => ({ status: 200 }), {
      onProgress: (progress) => progressEvents.push(progress)
    })

    expect(progressEvents).toEqual([
      { completed: 0, total: 3, runningCaseId: 'A-1' },
      { completed: 1, total: 3, runningCaseId: 'A-2' },
      { completed: 2, total: 3, runningCaseId: 'A-3' },
      { completed: 3, total: 3 }
    ])
  })

  it('limits concurrent automation execution when a concurrency limit is provided', async () => {
    const cases = buildAutomationCases(['A-1', 'A-2', 'A-3', 'A-4'])
    let active = 0
    let maxActive = 0

    await runAutomationCases(cases, { baseUrl: 'https://api.example.com' }, {
      mode: 'concurrent',
      concurrencyLimit: 2
    }, async () => {
      active += 1
      maxActive = Math.max(maxActive, active)
      await Promise.resolve()
      active -= 1
      return { status: 200 }
    })

    expect(maxActive).toBe(2)
  })

  it('runs automation cases by module with sequential module groups', async () => {
    const cases = [
      ...buildAutomationCases(['A-1', 'A-2'], '成员'),
      ...buildAutomationCases(['B-1'], '项目')
    ]
    const events: string[] = []

    await runAutomationCases(cases, { baseUrl: 'https://api.example.com' }, {
      mode: 'module'
    }, async (_request, testCase) => {
      events.push(`start:${testCase.id}`)
      await Promise.resolve()
      events.push(`end:${testCase.id}`)
      return { status: 200 }
    })

    expect(events.indexOf('start:B-1')).toBeGreaterThan(events.indexOf('end:A-2'))
  })

  it('stores request snapshot and error message when execution throws', async () => {
    const [testCase] = buildAutomationCases(['A-1'])

    const report = await runAutomationCases([testCase], { baseUrl: 'https://api.example.com' }, {
      mode: 'sequential'
    }, async () => {
      throw new Error('network timeout')
    })

    expect(report.summary).toEqual({
      total: 1,
      passed: 0,
      failed: 0,
      error: 1
    })
    expect(report.results[0]).toMatchObject({
      status: 'error',
      errorMessage: 'network timeout',
      request: {
        method: 'GET',
        url: 'https://api.example.com/api/A-1',
        assertions: ['响应状态码为 200']
      }
    })
  })

  it('fails when response body assertions do not match expected fields', async () => {
    const [testCase] = buildAutomationCases(['A-1'])
    testCase.assertions = [
      '响应状态码为 200',
      'body.code = 0',
      'body.data.userId = u-1'
    ]

    const report = await runAutomationCases([testCase], { baseUrl: 'https://api.example.com' }, {
      mode: 'sequential'
    }, async () => ({
      status: 200,
      body: {
        code: 0,
        data: {
          userId: 'u-2'
        }
      }
    }))

    expect(report.summary).toEqual({
      total: 1,
      passed: 0,
      failed: 1,
      error: 0
    })
    expect(report.results[0]).toMatchObject({
      status: 'failed',
      errorMessage: '断言失败: body.data.userId 期望 u-1，实际 u-2'
    })
  })

  it('passes response body parameter matching when match score reaches threshold', async () => {
    const [testCase] = buildAutomationCases(['A-1'])
    testCase.expectedResponseBody = {
      code: 0,
      data: {
        id: 'p-1',
        name: '项目',
        owner: 'u-1'
      }
    }

    const report = await runAutomationCases([testCase], { baseUrl: 'https://api.example.com' }, {
      mode: 'sequential',
      responseBodyMatchThreshold: 80
    }, async () => ({
      status: 200,
      body: {
        code: 0,
        data: {
          id: 'p-2',
          name: '新项目'
        }
      }
    }))

    expect(report.results[0]).toMatchObject({
      status: 'passed',
      responseBodyMatch: {
        matched: 4,
        expected: 5,
        ratio: 80
      }
    })
  })

  it('fails response body parameter matching when strict threshold is not reached', async () => {
    const [testCase] = buildAutomationCases(['A-1'])
    testCase.expectedResponseBody = {
      code: 0,
      data: {
        id: 'p-1',
        owner: 'u-1'
      }
    }

    const report = await runAutomationCases([testCase], { baseUrl: 'https://api.example.com' }, {
      mode: 'sequential',
      responseBodyMatchThreshold: 100
    }, async () => ({
      status: 200,
      body: {
        code: 0,
        data: {
          id: 'p-2'
        }
      }
    }))

    expect(report.results[0]).toMatchObject({
      status: 'failed',
      errorMessage: '返回体参数一致度 75%，低于阈值 100%。缺少参数: body.data.owner'
    })
  })

  it('removes deleted case results from the current automation run report', () => {
    const report = {
      mode: 'sequential' as const,
      startedAt: '2026-06-15T10:00:00.000Z',
      endedAt: '2026-06-15T10:01:00.000Z',
      summary: {
        total: 2,
        passed: 1,
        failed: 1,
        error: 0
      },
      results: [
        {
          caseId: 'AUTO-1',
          sourceCaseId: 'API-1',
          name: 'A',
          module: '项目',
          method: 'GET',
          path: '/a',
          status: 'passed' as const,
          request: {
            method: 'GET',
            url: 'https://api.example.com/a',
            headers: {},
            expectedStatus: 200,
            assertions: []
          },
          expectedStatus: 200,
          durationMs: 10
        },
        {
          caseId: 'AUTO-2',
          sourceCaseId: 'API-2',
          name: 'B',
          module: '项目',
          method: 'GET',
          path: '/b',
          status: 'failed' as const,
          request: {
            method: 'GET',
            url: 'https://api.example.com/b',
            headers: {},
            expectedStatus: 200,
            assertions: []
          },
          expectedStatus: 200,
          durationMs: 12,
          errorMessage: 'bad'
        }
      ]
    }

    expect(removeResultsFromAutomationRunReport(report, ['AUTO-2'])).toMatchObject({
      summary: {
        total: 1,
        passed: 1,
        failed: 0,
        error: 0
      },
      results: [
        {
          caseId: 'AUTO-1'
        }
      ]
    })
    expect(removeResultsFromAutomationRunReport(report, ['AUTO-1', 'AUTO-2'])).toBeUndefined()
  })
})

const buildAutomationCases = (ids: string[], module = '项目成员'): ApiAutomationCaseDraft[] =>
  ids.map((id) => ({
    id,
    sourceCaseId: id.replace(/^AUTO-/, ''),
    project: 'Haitlify',
    module,
    name: `${id} 自动化用例`,
    method: 'GET',
    path: `/api/${id}`,
    headers: {},
    pathParams: {},
    queryParams: {},
    expectedStatus: 200,
    assertions: ['响应状态码为 200'],
    runOrder: Number(id.replace(/\D/g, '')) || 1,
    exportTarget: 'apifox'
  }))

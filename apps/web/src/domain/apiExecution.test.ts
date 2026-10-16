import { describe, expect, it } from 'vitest'
import type { SavedInterfaceCase } from './caseRepository'
import {
  buildCurlCommand,
  buildExecutableRequest,
  buildProxyExecutionPayload,
  browserSafeHeaders,
  proxySafeHeaders,
  parseRequestHeadersText
} from './apiExecution'

const savedCase: SavedInterfaceCase = {
  id: 'API-0001',
  libraryId: 'Haitlify:createProjectMember:/api/projects/{projectId}/members:正向',
  savedAt: '2026-06-10T08:00:00.000Z',
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
    steps: '',
    expectedResult: '',
    assertions: '',
    priority: 'P1',
    executionResult: '未执行',
    executor: '',
    executedAt: '',
    defectId: '',
    tags: '',
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
    headers: {
      Authorization: 'Bearer old-token'
    },
    pathParams: {
      projectId: 'project 1'
    },
    queryParams: {
      keyword: '张三'
    },
    body: {
      userId: 'u-1',
      role: 'admin'
    },
    expectedStatus: 201,
    assertions: ['响应状态码为 201'],
    expectedBodyRules: [],
    variableExtractions: [],
    dependsOnCaseIds: [],
    runOrder: 1,
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

describe('buildExecutableRequest', () => {
  it('builds URL, headers, body, and expected status from a saved case and environment', () => {
    const request = buildExecutableRequest(savedCase, {
      baseUrl: 'https://api.example.com',
      authToken: 'new-token',
      extraHeaders: {
        'X-Tenant-Id': 'tenant-1'
      }
    })

    expect(request).toEqual({
      method: 'POST',
      url: 'https://api.example.com/api/projects/project%201/members?keyword=%E5%BC%A0%E4%B8%89',
      headers: {
        Authorization: 'Bearer new-token',
        'Content-Type': 'application/json',
        'X-Tenant-Id': 'tenant-1'
      },
      body: {
        userId: 'u-1',
        role: 'admin'
      },
      expectedStatus: 201,
      assertions: ['响应状态码为 201']
    })
  })

  it('omits request body for GET requests', () => {
    const getCase = {
      ...savedCase,
      metadata: {
        ...savedCase.metadata,
        method: 'GET',
        body: {
          ignored: true
        }
      }
    }

    expect(buildExecutableRequest(getCase, { baseUrl: 'https://api.example.com' }).body).toBeUndefined()
  })

  it('uses an absolute operation URL without prefixing the base URL', () => {
    const absoluteUrlCase: SavedInterfaceCase = {
      ...savedCase,
      metadata: {
        ...savedCase.metadata,
        method: 'GET',
        path: 'https://api.example.test/v4/pm/project-tree',
        queryParams: {}
      }
    }

    const request = buildExecutableRequest(absoluteUrlCase, { baseUrl: 'https://wrong.example.com' })

    expect(request.url).toBe('https://api.example.test/v4/pm/project-tree')
  })

  it('replaces environment variables in path params, query params, headers, and body', () => {
    const variableCase: SavedInterfaceCase = {
      ...savedCase,
      metadata: {
        ...savedCase.metadata,
        headers: {
          'X-Tenant-Id': '{{tenantId}}'
        },
        pathParams: {
          projectId: '{{projectId}}'
        },
        queryParams: {
          keyword: '{{keyword}}'
        },
        body: {
          userId: '{{userId}}'
        }
      }
    }

    const request = buildExecutableRequest(variableCase, {
      baseUrl: 'https://api.example.com',
      variables: {
        tenantId: '100509316',
        projectId: 'p-1',
        keyword: '张三',
        userId: 'u-1'
      }
    })

    expect(request).toMatchObject({
      url: 'https://api.example.com/api/projects/p-1/members?keyword=%E5%BC%A0%E4%B8%89',
      headers: {
        'X-Tenant-Id': '100509316'
      },
      body: {
        userId: 'u-1'
      }
    })
  })

  it('merges request headers parsed from a copied curl snippet', () => {
    const request = buildExecutableRequest(savedCase, {
      baseUrl: 'https://api.example.com',
      requestHeadersText: [
        "curl 'https://api.example.test/v4/pm/project-tree' \\",
        "  -H 'Accept: application/json, text/plain, */*' \\",
        "  -H 'Authorization: Bearer token-from-curl' \\",
        "  -H 'Origin: https://app.example.test' \\",
        "  -H 'Referer: https://app.example.test/' \\",
        "  -H 'isAjax: 1' \\",
        "  -b 'haitlify-token=cookie-token; tenant_switch_sync=tenant-1'"
      ].join('\n')
    })

    expect(request.headers).toMatchObject({
      Accept: 'application/json, text/plain, */*',
      Authorization: 'Bearer token-from-curl',
      Origin: 'https://app.example.test',
      Referer: 'https://app.example.test/',
      isAjax: '1',
      Cookie: 'haitlify-token=cookie-token; tenant_switch_sync=tenant-1'
    })
  })
})

describe('parseRequestHeadersText', () => {
  it('parses JSON, header lines, curl -H lines, and curl cookie lines', () => {
    expect(parseRequestHeadersText('{"X-Tenant-Id":"tenant-1"}')).toEqual({
      'X-Tenant-Id': 'tenant-1'
    })
    expect(parseRequestHeadersText("Accept: application/json\n-H 'isAjax: 1'\n-b 'token=abc'")).toEqual({
      Accept: 'application/json',
      isAjax: '1',
      Cookie: 'token=abc'
    })
  })
})

describe('browserSafeHeaders and buildProxyExecutionPayload', () => {
  it('keeps curl headers for local proxy execution but removes browser-forbidden headers for direct fetch', () => {
    const request = {
      method: 'GET',
      url: 'https://api.example.test/v4/pm/project-tree',
      headers: {
        Accept: 'application/json',
        Authorization: 'Bearer token',
        Cookie: 'haitlify-token=cookie-token',
        Origin: 'https://app.example.test',
        Referer: 'https://app.example.test/',
        Connection: 'keep-alive'
      },
      expectedStatus: 200,
      assertions: ['响应状态码为 200']
    }

    expect(buildProxyExecutionPayload(request).headers).toMatchObject({
      Cookie: 'haitlify-token=cookie-token',
      Origin: 'https://app.example.test',
      Referer: 'https://app.example.test/'
    })
    expect(proxySafeHeaders(request.headers)).toEqual({
      Accept: 'application/json',
      Authorization: 'Bearer token',
      Cookie: 'haitlify-token=cookie-token',
      Origin: 'https://app.example.test',
      Referer: 'https://app.example.test/'
    })
    expect(browserSafeHeaders(request.headers)).toEqual({
      Accept: 'application/json',
      Authorization: 'Bearer token'
    })
  })
})

describe('buildCurlCommand', () => {
  it('exports an executable curl command with escaped JSON body', () => {
    const request = buildExecutableRequest(savedCase, {
      baseUrl: 'https://api.example.com',
      authToken: 'new-token'
    })

    const curl = buildCurlCommand(request)

    expect(curl).toContain("curl -X POST 'https://api.example.com/api/projects/project%201/members?keyword=%E5%BC%A0%E4%B8%89'")
    expect(curl).toContain("-H 'Authorization: Bearer new-token'")
    expect(curl).toContain("-H 'Content-Type: application/json'")
    expect(curl).toContain("--data '{\"userId\":\"u-1\",\"role\":\"admin\"}'")
  })
})

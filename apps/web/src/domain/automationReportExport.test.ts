import { describe, expect, it } from 'vitest'
import {
  buildAutomationReportExportArtifact,
  buildAutomationReportListJsonArtifact,
  type AutomationReportExportFormat
} from './automationReportExport'
import type { SavedAutomationReport } from './automationReportRepository'

const report: SavedAutomationReport = {
  reportId: 'report-1',
  name: 'Haitlify 执行报告',
  project: 'Haitlify',
  environmentName: '测试环境',
  moduleScope: ['项目成员'],
  createdAt: '2026-06-11T08:00:00.000Z',
  mode: 'sequential',
  startedAt: '2026-06-11T08:00:00.000Z',
  endedAt: '2026-06-11T08:00:10.000Z',
  summary: {
    total: 2,
    passed: 1,
    failed: 1,
    error: 0
  },
  results: [
    {
      caseId: 'AUTO-1',
      sourceCaseId: 'API-0001',
      name: '新增成员成功',
      module: '项目成员',
      method: 'POST',
      path: '/api/users',
      status: 'passed',
      request: {
        method: 'POST',
        url: 'https://api.example.com/api/users',
        headers: { Authorization: 'Bearer token' },
        expectedStatus: 200,
        assertions: ['响应状态码为 200']
      },
      expectedStatus: 200,
      actualStatus: 200,
      durationMs: 123,
      responseBody: { code: 0 }
    }
  ]
}

describe('automationReportExport', () => {
  for (const format of ['json', 'html', 'md', 'csv'] as AutomationReportExportFormat[]) {
    it(`builds a ${format} export artifact`, () => {
      const artifact = buildAutomationReportExportArtifact(report, format)
      expect(artifact.filename).toContain('Haitlify')
      expect(artifact.content.length).toBeGreaterThan(10)
    })
  }

  it('builds a printable pdf artifact descriptor', () => {
    const artifact = buildAutomationReportExportArtifact(report, 'pdf')
    expect(artifact.kind).toBe('print')
    expect(artifact.mimeType).toBe('application/pdf')
    expect(artifact.content).toContain('<html')
  })

  it.each(['plain diagnostic', 'cause, "quoted"\nsecond line', ''])('escapes CSV error text exactly once: %j', (errorMessage) => {
    const enriched = { ...report, results: [{ ...report.results[0]!, errorMessage }] }
    const csv = buildAutomationReportExportArtifact(enriched, 'csv').content
    // RFC 4180 field decoding: strip the CSV delimiters and collapse escaped
    // quotes once. Embedded commas/newlines must survive as source text.
    const errorCell = csv.slice(csv.indexOf('\n') + 1).match(/,"((?:[^"]|"")*)"$/)?.[1]
    expect(errorCell).toBeDefined()
    expect(errorCell!.replace(/""/g, '"')).toBe(errorMessage)
  })

  it('does not export request credentials in the report-list JSON artifact', async () => {
    const { buildAutomationReportListJsonArtifact } = await import('./automationReportExport')
    const artifact = buildAutomationReportListJsonArtifact([report], {
      project: 'Haitlify', environment: '测试环境', keyword: ''
    }, '2026-06-11T08:00:00.000Z')
    const parsed = JSON.parse(artifact.content) as { reports: Array<{ results: Array<{ request: { headers: Record<string, string> } }> }> }
    expect(parsed.reports[0]!.results[0]!.request.headers).toEqual({})
    expect(artifact.content).not.toContain('Bearer token')
  })

  it('redacts nested secrets and sensitive URL parameters in individual JSON exports', () => {
    const enriched = JSON.parse(JSON.stringify(report)) as SavedAutomationReport
    enriched.results[0]!.request.url = 'https://api.example.com/users?token=abc&id_token=def&jwt=ghi&visible=1'
    enriched.results[0]!.path = '/users?session_token=path-secret&visible=1'
    enriched.results[0]!.startUrl = 'https://web.example.test/orders?client_secret=start-secret&visible=1'
    enriched.results[0]!.request.headers['X-Session-Token'] = 'header-secret'
    enriched.results[0]!.request.body = { nested: { password: 'secret', idToken: 'id-secret', apiToken: 'api-secret', visible: 'ok' } }
    const artifact = buildAutomationReportExportArtifact(enriched, 'json')
    expect(artifact.content).toContain('visible=1')
    expect(artifact.content).toContain('[REDACTED]')
    expect(artifact.content).not.toContain('token=abc')
    expect(artifact.content).not.toContain('id_token=def')
    expect(artifact.content).not.toContain('jwt=ghi')
    expect(artifact.content).not.toContain('session_token=path-secret')
    expect(artifact.content).not.toContain('client_secret=start-secret')
    expect(artifact.content).not.toContain('header-secret')
    expect(artifact.content).not.toContain('id-secret')
    expect(artifact.content).not.toContain('api-secret')
    expect(artifact.content).not.toContain('"password": "secret"')
  })

  it('sanitizes credential-shaped filters in report-list JSON exports', async () => {
    const { buildAutomationReportListJsonArtifact } = await import('./automationReportExport')
    const artifact = buildAutomationReportListJsonArtifact([report], {
      project: 'Bearer filter-secret password=filter-password',
      environment: 'x-session-token=filter-token',
      keyword: 'client_secret=filter-secret'
    }, '2026-06-11T08:00:00.000Z')
    expect(artifact.content).not.toContain('filter-secret')
    expect(artifact.content).not.toContain('filter-password')
    expect(artifact.content).not.toContain('filter-token')
    expect(artifact.content).toContain('"filters"')
  })

  it('redacts encoded bearer values and credential fragments in URL fields', () => {
    const enriched = JSON.parse(JSON.stringify(report)) as SavedAutomationReport
    enriched.results[0]!.path = '/orders?visible=Bearer%20URL_SECRET#access_token=fragment-secret'
    enriched.results[0]!.startUrl = 'https://web.example.test/orders#jwt=fragment-jwt'
    const artifact = buildAutomationReportListJsonArtifact([enriched], {
      project: 'Haitlify', environment: '测试环境', keyword: ''
    })
    expect(artifact.content).not.toContain('URL_SECRET')
    expect(artifact.content).not.toContain('fragment-secret')
    expect(artifact.content).not.toContain('fragment-jwt')
  })

  it('redacts repeatedly encoded and malformed credentials across URL fields and filters', () => {
    const enriched = JSON.parse(JSON.stringify(report)) as SavedAutomationReport
    enriched.results[0]!.path = '/orders?visible=Bearer%2520DOUBLE_SECRET#access_token=fragment-secret'
    enriched.results[0]!.startUrl = 'https://web.example.test/orders#jwt=AFTER_SECRET'
    ;(enriched.results[0] as unknown as { pageUrl: string }).pageUrl = '/detail?x=Bearer%2520PAGE_SECRET#jwt=PAGE_FRAGMENT'
    const artifact = buildAutomationReportListJsonArtifact([enriched], {
      project: 'Bearer%2520FILTER_SECRET%ZZ',
      environment: 'x-session-token=%E0%A4%A',
      keyword: 'client_secret=FILTER_SECRET'
    })
    for (const secret of ['DOUBLE_SECRET', 'fragment-secret', 'AFTER_SECRET', 'PAGE_SECRET', 'PAGE_FRAGMENT', 'FILTER_SECRET']) {
      expect(artifact.content).not.toContain(secret)
    }
  })

  it('fails closed on malformed encoded bearer values in URLs and filters', () => {
    const enriched = JSON.parse(JSON.stringify(report)) as SavedAutomationReport
    enriched.results[0]!.path = '/orders?visible=Bearer%20URL_SECRET%ZZ&other=Bearer%2SQUERY_SECRET#note=Bearer%20FRAGMENT_SECRET%ZZ'
    ;(enriched.results[0] as unknown as { pageUrl: string }).pageUrl = '/detail#note=Bearer%2520PAGE_SECRET%ZZ&other=Bearer%2GFRAGMENT_SECRET'
    const artifact = buildAutomationReportListJsonArtifact([enriched], {
      project: 'Bearer%20FILTER_SECRET%ZZ',
      environment: '测试环境',
      keyword: 'Bearer%2SFILTER_SECRET Bearer%2FILTER_TWO'
    })
    for (const secret of ['URL_SECRET', 'QUERY_SECRET', 'FRAGMENT_SECRET', 'PAGE_SECRET', 'FRAGMENT_SECRET', 'FILTER_SECRET', 'FILTER_TWO']) {
      expect(artifact.content).not.toContain(secret)
    }
  })

  it('closes the credential class: encoded markers, URL path credentials and userinfo', () => {
    const enriched = JSON.parse(JSON.stringify(report)) as SavedAutomationReport
    enriched.results[0]!.path = '/orders/%42earer%20PATH_SECRET/x'
    enriched.results[0]!.startUrl = 'https://urluser:URL_USERINFO_SECRET@web.example.test/orders'
    ;(enriched.results[0] as unknown as { pageUrl: string }).pageUrl = '/detail#note=%42earer%20FRAGMENT_MARKER_SECRET'
    const artifact = buildAutomationReportListJsonArtifact([enriched], {
      project: '%42earer%20FILTER_MARKER_SECRET',
      environment: '测试环境',
      keyword: '%74oken%3dFILTER_TOKEN_VALUE'
    })
    for (const secret of ['PATH_SECRET', 'URL_USERINFO_SECRET', 'FRAGMENT_MARKER_SECRET', 'FILTER_MARKER_SECRET', 'FILTER_TOKEN_VALUE']) {
      expect(artifact.content).not.toContain(secret)
    }
  })

  it('withholds every adversarial credential spelling while keeping ordinary values', () => {
    const enriched = JSON.parse(JSON.stringify(report)) as SavedAutomationReport
    enriched.results[0]!.path = '/orders/%2SLEAK?x=%2GLEAK#note=%G0LEAK'
    enriched.results[0]!.request.url = 'https://api.example.com/v1/orders?page=2&size=20'
    ;(enriched.results[0] as unknown as { pageUrl: string }).pageUrl = '/detail?tab=logs'
    const artifact = buildAutomationReportListJsonArtifact([enriched], {
      project: 'Haitlify',
      environment: '测试环境',
      keyword: 'orders'
    })
    for (const leak of ['LEAK']) {
      expect(artifact.content).not.toContain(leak)
    }
    expect(artifact.content).toContain('page=2')
    expect(artifact.content).toContain('tab=logs')
    expect(artifact.content).toContain('"project": "Haitlify"')
  })

  it('closes co-located markers, alternate credential names, text DSNs and nested keys', () => {
    const enriched = JSON.parse(JSON.stringify(report)) as SavedAutomationReport
    enriched.results[0]!.errorMessage = 'connect postgres://app:DB_SECRET@db.example.test:5432/app failed'
    enriched.results[0]!.request.url = 'https://api.example.com/v1?sig=SIG_SECRET&auth=AUTH_SECRET&sessionId=SESSION_SECRET&code=CODE_SECRET&key=KEY_SECRET&page=2'
    enriched.results[0]!.request.headers['Authorization'] = 'cookie: COOKIE_SECRET; Bearer BEARER_SECRET'
    enriched.results[0]!.responseBody = {
      'Bearer BODY_SECRET': 'value',
      nested: [{ 'Authorization: Bearer ARRAY_SECRET': 'value' }]
    }
    const artifact = buildAutomationReportListJsonArtifact([enriched], {
      project: 'Haitlify', environment: '测试环境', keyword: ''
    })
    for (const secret of ['DB_SECRET', 'SIG_SECRET', 'AUTH_SECRET', 'SESSION_SECRET', 'CODE_SECRET', 'KEY_SECRET', 'COOKIE_SECRET', 'BEARER_SECRET', 'BODY_SECRET', 'ARRAY_SECRET']) {
      expect(artifact.content).not.toContain(secret)
    }
    expect(artifact.content).toContain('page=2')
  })

  it('redacts URL path/fragment assignments, AWS key variants and encoded object keys', () => {
    const enriched = JSON.parse(JSON.stringify(report)) as SavedAutomationReport
    enriched.results[0]!.path = '/resource/pwd=PATH_PWD?AWS=QUERY_AWS#code=FRAGMENT_CODE'
    enriched.results[0]!.startUrl = '/resource/AWSAccessKeyId=START_AWS'
    enriched.results[0]!.responseBody = {
      '%73ig': 'ENCODED_SIG',
      '%70assword': 'ENCODED_PASSWORD',
      '%63ode': 'ENCODED_CODE',
      AWSAccessKeyId: 'AWS_OBJECT_KEY'
    }
    const artifact = buildAutomationReportListJsonArtifact([enriched], {
      project: 'AWS=FILTER_AWS', environment: '测试环境', keyword: ''
    })
    for (const secret of ['PATH_PWD', 'QUERY_AWS', 'FRAGMENT_CODE', 'START_AWS', 'ENCODED_SIG', 'ENCODED_PASSWORD', 'ENCODED_CODE', 'AWS_OBJECT_KEY', 'FILTER_AWS']) {
      expect(artifact.content).not.toContain(secret)
    }
  })

  it('withholds hash-prefixed assignments and values under over-deeply encoded sensitive keys', () => {
    const enriched = JSON.parse(JSON.stringify(report)) as SavedAutomationReport
    enriched.results[0]!.path = '/orders#pwd=FRAGMENT_PWD&pass=FRAGMENT_PASS&code=FRAGMENT_CODE&AWS=FRAGMENT_AWS'
    enriched.results[0]!.errorMessage = 'visible=ok#pwd=COLOCATED_PWD /visible=ok?AWS=COLOCATED_AWS'
    enriched.results[0]!.startUrl = '/orders/visible=ok{pwd=PATH_COLOCATED_PWD'
    let deeplyEncodedPassword = '%70assword'
    for (let index = 0; index < 16; index += 1) deeplyEncodedPassword = encodeURIComponent(deeplyEncodedPassword)
    enriched.results[0]!.responseBody = { [deeplyEncodedPassword]: 'DEEP_KEY_SECRET' }
    const filters = { project: '#AWS=FILTER_AWS', environment: '测试环境', keyword: '#code=FILTER_CODE visible=ok?AWS=FILTER_COLOCATED_AWS' }
    const listArtifact = buildAutomationReportListJsonArtifact([enriched], filters)
    const individualArtifact = buildAutomationReportExportArtifact(enriched, 'json')
    for (const artifact of [listArtifact, individualArtifact]) {
      for (const secret of ['FRAGMENT_PWD', 'FRAGMENT_PASS', 'FRAGMENT_CODE', 'FRAGMENT_AWS', 'COLOCATED_PWD', 'COLOCATED_AWS', 'PATH_COLOCATED_PWD', 'DEEP_KEY_SECRET', 'FILTER_AWS', 'FILTER_CODE', 'FILTER_COLOCATED_AWS']) {
        expect(artifact.content).not.toContain(secret)
      }
    }
  })

  it('redacts assignments after closing punctuation boundaries', () => {
    const enriched = JSON.parse(JSON.stringify(report)) as SavedAutomationReport
    enriched.results[0]!.errorMessage = 'safe=ok}AWS=BRACE_AWS safe=ok]AWS=BRACKET_AWS safe=ok)AWS=PAREN_AWS'
    enriched.results[0]!.path = '/orders/safe=ok}AWS=PATH_BRACE_AWS'
    const listArtifact = buildAutomationReportListJsonArtifact([enriched], {
      project: 'safe=ok]AWS=FILTER_BRACKET_AWS', environment: '测试环境', keyword: ''
    })
    const individualArtifact = buildAutomationReportExportArtifact(enriched, 'json')
    for (const artifact of [listArtifact, individualArtifact]) {
      for (const secret of ['BRACE_AWS', 'BRACKET_AWS', 'PAREN_AWS', 'PATH_BRACE_AWS', 'FILTER_BRACKET_AWS']) {
        expect(artifact.content).not.toContain(secret)
      }
    }
  })

  it('withholds nested content inside a sensitive assignment value', () => {
    const enriched = JSON.parse(JSON.stringify(report)) as SavedAutomationReport
    enriched.results[0]!.errorMessage = 'pwd={"visible":"NESTED_ASSIGN_SECRET"}'
    enriched.results[0]!.path = '/orders#pwd={"visible":"PATH_NESTED_SECRET"}'
    const listArtifact = buildAutomationReportListJsonArtifact([enriched], {
      project: 'Haitlify', environment: '测试环境', keyword: 'pwd={"visible":"FILTER_NESTED_SECRET"}'
    })
    const individualArtifact = buildAutomationReportExportArtifact(enriched, 'json')
    for (const artifact of [listArtifact, individualArtifact]) {
      for (const secret of ['NESTED_ASSIGN_SECRET', 'PATH_NESTED_SECRET', 'FILTER_NESTED_SECRET']) {
        expect(artifact.content).not.toContain(secret)
      }
    }
  })

  it('preserves ordinary prose while redacting quoted JSON credentials and string code values', () => {
    const enriched = JSON.parse(JSON.stringify(report)) as SavedAutomationReport
    enriched.results[0]!.errorMessage = 'author and passenger; {"sig":"QUOTED_SIG_SECRET"}'
    enriched.results[0]!.responseBody = { code: 'OAUTH_CODE_SECRET', ok: true, note: 'token field is documented' }
    const artifact = buildAutomationReportListJsonArtifact([enriched], {
      project: 'token', environment: '测试环境', keyword: 'author and passenger'
    })
    expect(artifact.content).not.toContain('QUOTED_SIG_SECRET')
    expect(artifact.content).not.toContain('OAUTH_CODE_SECRET')
    expect(artifact.content).toContain('author and passenger')
    expect(artifact.content).toContain('token field is documented')
  })

  it('closes deeply encoded markers without exponential key matching', () => {
    const enriched = JSON.parse(JSON.stringify(report)) as SavedAutomationReport
    let encoded = 'Bearer%20DEPTH_SECRET'
    for (let index = 0; index < 24; index += 1) encoded = encodeURIComponent(encoded)
    enriched.results[0]!.path = `/orders?visible=${encoded}`
    const started = Date.now()
    const artifact = buildAutomationReportExportArtifact(enriched, 'json')
    expect(Date.now() - started).toBeLessThan(1000)
    expect(artifact.content).not.toContain('DEPTH_SECRET')
  })

  it('keeps long non-secret diagnostic strings linear during JSON export', () => {
    const enriched = JSON.parse(JSON.stringify(report)) as SavedAutomationReport
    enriched.results[0]!.errorMessage = 'trace-' + 'a'.repeat(100_000)
    const started = Date.now()
    const artifact = buildAutomationReportExportArtifact(enriched, 'json')
    expect(Date.now() - started).toBeLessThan(1000)
    expect(artifact.content).toContain('trace-')
  })

  it.each(['csv', 'md', 'html', 'pdf'] as AutomationReportExportFormat[])('does not export credentials in %s artifacts', (format) => {
    const enriched = JSON.parse(JSON.stringify(report)) as SavedAutomationReport
    enriched.name = 'Bearer FILE_NAME_SECRET'
    enriched.project = 'AWS=PROJECT_SECRET'
    enriched.results[0]!.path = '/users?token=PATH_TOKEN_SECRET'
    enriched.results[0]!.errorMessage = 'Authorization: Bearer ERROR_BEARER_SECRET'
    const artifact = buildAutomationReportExportArtifact(enriched, format)
    for (const secret of ['FILE_NAME_SECRET', 'PROJECT_SECRET', 'PATH_TOKEN_SECRET', 'ERROR_BEARER_SECRET']) {
      expect(artifact.content).not.toContain(secret)
    }
  })

  it('withholds credential-bearing nested URLs in query values and diagnostics for every format', () => {
    const enriched = JSON.parse(JSON.stringify(report)) as SavedAutomationReport
    enriched.results[0]!.path = '/orders?next=postgres://app:QUERYDSNSECRET_A@db.example.test:5432/app&page=2'
    enriched.results[0]!.errorMessage = 'connect postgres://app:DIAGNOSTIC_DSN_SECRET@db.example.test:5432/app failed'
    for (const format of ['json', 'csv', 'md', 'html', 'pdf'] as AutomationReportExportFormat[]) {
      const artifact = buildAutomationReportExportArtifact(enriched, format)
      expect(artifact.content).not.toContain('QUERYDSNSECRET_A')
      expect(artifact.content).not.toContain('DIAGNOSTIC_DSN_SECRET')
    }
    const listArtifact = buildAutomationReportListJsonArtifact([enriched], { project: 'Haitlify', environment: '测试环境', keyword: '' })
    expect(listArtifact.content).not.toContain('QUERYDSNSECRET_A')
    expect(listArtifact.content).not.toContain('DIAGNOSTIC_DSN_SECRET')
  })

  it('redacts no-separator API key query names across every report export format', () => {
    const enriched = JSON.parse(JSON.stringify(report)) as SavedAutomationReport
    enriched.results[0]!.path = '/orders?apikey=QUERY_APIKEY_LEAK&clientkey=QUERY_CLIENTKEY_LEAK&accesskey=QUERY_ACCESSKEY_LEAK&page=2'
    const secrets = ['QUERY_APIKEY_LEAK', 'QUERY_CLIENTKEY_LEAK', 'QUERY_ACCESSKEY_LEAK']
    for (const format of ['json', 'csv', 'md', 'html', 'pdf'] as AutomationReportExportFormat[]) {
      const artifact = buildAutomationReportExportArtifact(enriched, format)
      for (const secret of secrets) expect(artifact.content).not.toContain(secret)
    }
    const listArtifact = buildAutomationReportListJsonArtifact([enriched], { project: 'Haitlify', environment: '测试环境', keyword: '' })
    for (const secret of secrets) expect(listArtifact.content).not.toContain(secret)
  })

  it('withholds nested DSNs in all URL-bearing path fields', () => {
    const enriched = JSON.parse(JSON.stringify(report)) as SavedAutomationReport
    enriched.results[0]!.path = '/orders/next=postgres://app:PATH_DSN_SECRET@db.example.test:5432/app'
    enriched.results[0]!.startUrl = '/start/next=postgres://app:START_DSN_SECRET@db.example.test:5432/app'
    ;(enriched.results[0] as unknown as { pageUrl: string }).pageUrl = '/page/next=postgres://app:PAGE_DSN_SECRET@db.example.test:5432/app'
    enriched.results[0]!.request.url = '/request/next=postgres://app:REQUEST_DSN_SECRET@db.example.test:5432/app'
    const formats = ['json', 'csv', 'md', 'html', 'pdf'] as AutomationReportExportFormat[]
    for (const format of formats) {
      const artifact = buildAutomationReportExportArtifact(enriched, format)
      for (const secret of ['PATH_DSN_SECRET', 'START_DSN_SECRET', 'PAGE_DSN_SECRET', 'REQUEST_DSN_SECRET']) {
        expect(artifact.content).not.toContain(secret)
      }
    }
    const listArtifact = buildAutomationReportListJsonArtifact([enriched], { project: 'Haitlify', environment: '测试环境', keyword: '' })
    for (const secret of ['PATH_DSN_SECRET', 'START_DSN_SECRET', 'PAGE_DSN_SECRET', 'REQUEST_DSN_SECRET']) expect(listArtifact.content).not.toContain(secret)
  })

  it.each(['path', 'startUrl', 'pageUrl', 'request.url'])('withholds the entire ambiguous nested DSN URL in %s', (field) => {
    const enriched = JSON.parse(JSON.stringify(report)) as SavedAutomationReport
    const password = 'FIXTURE_NESTED_PASSWORD'
    const querySecret = 'FIXTURE_QUERY_KEY_VALUE'
    const value = `/orders/next=postgres://app:${password}@db.example.test/app?api+key=${querySecret}&page=2`
    expect(value).toContain(password)
    expect(value).toContain(querySecret)
    if (field === 'request.url') enriched.results[0]!.request.url = value
    else Object.assign(enriched.results[0]!, { [field]: value })
    for (const format of ['json', 'csv', 'md', 'html', 'pdf'] as AutomationReportExportFormat[]) {
      const artifact = buildAutomationReportExportArtifact(enriched, format)
      expect(artifact.content).not.toContain(password)
      expect(artifact.content).not.toContain(querySecret)
    }
    const individual = JSON.parse(buildAutomationReportExportArtifact(enriched, 'json').content)
    const listArtifact = buildAutomationReportListJsonArtifact([enriched], { project: 'Haitlify', environment: '测试环境', keyword: '' })
    const list = JSON.parse(listArtifact.content)
    expect(list.reports[0]).toEqual(individual)
    const sanitizedResult = individual.results[0]
    expect(field === 'request.url' ? sanitizedResult.request.url : sanitizedResult[field]).toBe('[REDACTED]')
    expect(listArtifact.content).not.toContain(password)
    expect(listArtifact.content).not.toContain(querySecret)
  })

  it.each(['path', 'startUrl', 'pageUrl', 'request.url'])('removes nested URL userinfo across components and encodings in %s', (field) => {
    for (const scheme of ['postgres', 'https', 'redis', 'mongodb+srv']) {
      for (const position of ['path', 'query', 'fragment']) {
        for (const depth of [0, 1, 2]) {
          const enriched = JSON.parse(JSON.stringify(report)) as SavedAutomationReport
          const secret = 'FIXTURE_COMPONENT_PASSWORD'
          let nested = `${scheme}://app:${secret}@db.example.test/app`
          for (let index = 0; index < depth; index += 1) nested = encodeURIComponent(nested)
          expect(nested).toContain(secret)
          const value = position === 'path' ? `/orders/next=${nested}`
            : position === 'query' ? `/orders?next=${nested}&page=2`
              : `/orders#next=${nested}`
          if (field === 'request.url') enriched.results[0]!.request.url = value
          else Object.assign(enriched.results[0]!, { [field]: value })
          const before = JSON.stringify(enriched)
          const individual = JSON.parse(buildAutomationReportExportArtifact(enriched, 'json').content)
          const listArtifact = buildAutomationReportListJsonArtifact([enriched], { project: 'Haitlify', environment: '测试环境', keyword: '' })
          expect(JSON.parse(listArtifact.content).reports[0]).toEqual(individual)
          expect(listArtifact.content).not.toContain(secret)
          for (const format of ['json', 'csv', 'md', 'html', 'pdf'] as AutomationReportExportFormat[]) {
            const artifact = buildAutomationReportExportArtifact(enriched, format)
            expect(artifact.content).not.toContain(secret)
            expect(artifact.filename).not.toContain(secret)
          }
          expect(JSON.stringify(enriched)).toBe(before)
        }
      }
    }
  })

  it('preserves safe URL siblings, report fields and diagnostics while withholding a nested DSN', () => {
    const enriched = JSON.parse(JSON.stringify(report)) as SavedAutomationReport
    const secret = 'FIXTURE_ISOLATED_PASSWORD'
    enriched.results[0]!.path = `/orders/next=postgres://app:${secret}@db.example.test/app`
    enriched.results[0]!.request.url = 'https://api.example.test/orders?page=2#tab=logs'
    enriched.results[0]!.startUrl = '/orders?page=2#tab=logs'
    enriched.results[0]!.errorMessage = 'author and passenger; token field is documented; 50% off'
    const individual = JSON.parse(buildAutomationReportExportArtifact(enriched, 'json').content)
    expect(individual.results[0].path).toBe('[REDACTED]')
    expect(individual.results[0].request.url).toBe(enriched.results[0]!.request.url)
    expect(individual.results[0].startUrl).toBe(enriched.results[0]!.startUrl)
    expect(individual.name).toBe(enriched.name)
    expect(individual.summary).toEqual(enriched.summary)
    for (const format of ['json', 'csv', 'md', 'html', 'pdf'] as AutomationReportExportFormat[]) {
      const artifact = buildAutomationReportExportArtifact(enriched, format)
      expect(artifact.content).not.toContain(secret)
      expect(artifact.content).toContain(enriched.results[0]!.errorMessage)
      expect(artifact.content).toContain(enriched.results[0]!.name)
    }
  })
})

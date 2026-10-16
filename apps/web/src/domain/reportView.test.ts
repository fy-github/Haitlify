import { describe, expect, it } from 'vitest'
import type { SavedAutomationReport } from './automationReportRepository'
import {
  buildReportDetailSummary,
  buildReportProjectOptions,
  collectReportEnvironmentOptions,
  filterReportsForProject,
  reportResultKind,
  selectActiveReportId
} from './reportView'

const makeReport = (
  reportId: string,
  project: string,
  environmentName: string,
  name = `${project}-${reportId}`
): SavedAutomationReport => ({
  reportId,
  name,
  project,
  environmentName,
  moduleScope: ['项目'],
  createdAt: `2026-06-15T10:0${reportId.slice(-1)}:00.000Z`,
  mode: 'sequential',
  startedAt: '2026-06-15T10:00:00.000Z',
  endedAt: '2026-06-15T10:00:10.000Z',
  summary: {
    total: 1,
    passed: 1,
    failed: 0,
    error: 0
  },
  results: []
})

describe('reportView', () => {
  it('detects whether a report contains api or web execution results', () => {
    const apiReport = makeReport('report-1', 'Haitlify', '测试')
    apiReport.results = [
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
          headers: {},
          expectedStatus: 200,
          assertions: []
        },
        expectedStatus: 200,
        actualStatus: 200,
        durationMs: 120
      }
    ]
    const webReport = makeReport('report-2', 'Haitlify', 'Mock 浏览器')
    webReport.results = [
      {
        caseId: 'WEB-AUTO-1',
        sourceCaseId: 'WEB-0001',
        name: '登录失败',
        module: '登录',
        method: 'WEB',
        path: '/login/error',
        status: 'failed',
        request: {
          method: 'GET',
          url: '/login/error',
          headers: {},
          expectedStatus: 0,
          assertions: []
        },
        expectedStatus: 0,
        durationMs: 120,
        errorMessage: '断言失败'
      }
    ]

    expect(reportResultKind(apiReport)).toBe('api')
    expect(reportResultKind(webReport)).toBe('web')
  })

  it('builds a web-focused summary for web execution results', () => {
    const report = makeReport('report-2', 'Haitlify', 'Mock 浏览器')
    report.results = [
      {
        caseId: 'WEB-AUTO-1',
        sourceCaseId: 'WEB-0001',
        name: '登录失败',
        module: '登录',
        method: 'WEB',
        path: '/login/error',
        status: 'failed',
        request: {
          method: 'GET',
          url: '/login/error',
          headers: {},
          expectedStatus: 0,
          assertions: []
        },
        expectedStatus: 0,
        durationMs: 120,
        errorMessage: '断言失败'
      }
    ]

    expect(buildReportDetailSummary(report)).toEqual([
      { label: '结果类型', value: 'Web 自动化' },
      { label: '执行时间', value: '2026-06-15 10:02' },
      { label: '模块范围', value: '项目' },
      { label: '运行环境', value: 'Mock 浏览器' },
      { label: '开始时间', value: '10:00:00' },
      { label: '结束时间', value: '10:00:10' }
    ])
  })

  it('builds project options with report counts', () => {
    expect(buildReportProjectOptions([
      makeReport('report-1', 'Haitlify', '测试'),
      makeReport('report-2', 'Haitlify', '预发'),
      makeReport('report-3', 'Ucenter', '测试')
    ])).toEqual([
      { value: 'Haitlify', count: 2 },
      { value: 'Ucenter', count: 1 }
    ])
  })

  it('filters reports inside a selected project and supports environment and keyword filtering', () => {
    const reports = [
      makeReport('report-1', 'Haitlify', '测试', 'Haitlify-成员回归'),
      makeReport('report-2', 'Haitlify', '预发', 'Haitlify-项目回归'),
      makeReport('report-3', 'Ucenter', '测试', 'Ucenter-登录回归')
    ]

    expect(filterReportsForProject(reports, {
      project: '',
      environment: 'ALL',
      keyword: ''
    })).toEqual([])

    expect(filterReportsForProject(reports, {
      project: 'Haitlify',
      environment: '测试',
      keyword: '成员'
    }).map((report) => report.reportId)).toEqual(['report-1'])
  })

  it('collects environments only from the selected project and keeps active report valid', () => {
    const reports = [
      makeReport('report-1', 'Haitlify', '测试'),
      makeReport('report-2', 'Haitlify', '预发'),
      makeReport('report-3', 'Ucenter', '测试')
    ]

    expect(collectReportEnvironmentOptions(reports, 'Haitlify')).toEqual(['测试', '预发'])
    expect(selectActiveReportId(filterReportsForProject(reports, {
      project: 'Haitlify',
      environment: 'ALL',
      keyword: ''
    }), 'missing')).toBe('report-1')
    expect(selectActiveReportId([], 'missing')).toBeUndefined()
  })
})

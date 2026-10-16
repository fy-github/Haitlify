import { describe, expect, it } from 'vitest'
import {
  buildAutomationReportExportArtifact,
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
})

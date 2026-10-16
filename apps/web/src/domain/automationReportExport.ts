import type { SavedAutomationReport } from './automationReportRepository'

export type AutomationReportExportFormat = 'json' | 'html' | 'md' | 'pdf' | 'csv'

export interface AutomationReportExportArtifact {
  kind: 'download' | 'print'
  filename: string
  mimeType: string
  content: string
}

export const buildAutomationReportExportArtifact = (
  report: SavedAutomationReport,
  format: AutomationReportExportFormat
): AutomationReportExportArtifact => {
  const safeName = sanitizeFileName(report.name)

  if (format === 'json') {
    return {
      kind: 'download',
      filename: `${safeName}.json`,
      mimeType: 'application/json;charset=utf-8',
      content: JSON.stringify(report, null, 2)
    }
  }

  if (format === 'csv') {
    return {
      kind: 'download',
      filename: `${safeName}.csv`,
      mimeType: 'text/csv;charset=utf-8',
      content: [
        '用例ID,来源用例,用例名称,模块,方法,路径,执行状态,期望状态,实际状态,状态匹配,耗时(ms),错误信息',
        ...report.results.map((result) => {
          const statusMatch = result.actualStatus !== undefined && result.actualStatus === result.expectedStatus ? '是' : '否'
          return [
            result.caseId,
            result.sourceCaseId,
            result.name,
            result.module,
            result.method,
            result.path,
            result.status === 'passed' ? '通过' : result.status === 'failed' ? '失败' : '错误',
            result.expectedStatus,
            result.actualStatus ?? '',
            statusMatch,
            result.durationMs,
            csvEscape(result.errorMessage || '')
          ].map(csvEscape).join(',')
        })
      ].join('\n')
    }
  }

  if (format === 'md') {
    return {
      kind: 'download',
      filename: `${safeName}.md`,
      mimeType: 'text/markdown;charset=utf-8',
      content: [
        `# ${report.name}`,
        '',
        `- 项目：${report.project}`,
        `- 执行方式：${report.mode}`,
        `- 开始时间：${report.startedAt}`,
        `- 结束时间：${report.endedAt}`,
        `- 结果：总数 ${report.summary.total}，通过 ${report.summary.passed}，失败 ${report.summary.failed}，错误 ${report.summary.error}`,
        '',
        '| 用例ID | 名称 | 状态 | 期望状态 | 实际状态 | 耗时(ms) | 错误信息 |',
        '| --- | --- | --- | --- | --- | --- | --- |',
        ...report.results.map((result) => {
          const statusLabel = result.status === 'passed' ? '通过' : result.status === 'failed' ? '失败' : '错误'
          const errorInfo = result.errorMessage ? csvEscape(result.errorMessage) : '-'
          return `| ${result.caseId} | ${result.name} | ${statusLabel} | ${result.expectedStatus} | ${result.actualStatus ?? '-'} | ${result.durationMs} | ${errorInfo} |`
        })
      ].join('\n')
    }
  }

  const html = renderReportHtml(report)
  if (format === 'pdf') {
    return {
      kind: 'print',
      filename: `${safeName}.pdf`,
      mimeType: 'application/pdf',
      content: html
    }
  }

  return {
    kind: 'download',
    filename: `${safeName}.html`,
    mimeType: 'text/html;charset=utf-8',
    content: html
  }
}

const renderReportHtml = (report: SavedAutomationReport): string => `
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <title>${escapeHtml(report.name)}</title>
  <style>
    body { font-family: "PingFang SC", "Microsoft YaHei", sans-serif; margin: 24px; color: #222936; }
    h1 { margin: 0 0 12px; font-size: 24px; }
    .summary { display: flex; gap: 12px; margin: 16px 0; flex-wrap: wrap; }
    .pill { border: 1px solid #dce6f0; border-radius: 999px; padding: 6px 10px; font-size: 12px; background: #f7faff; }
    .pill.passed { background: #e7f7f0; color: #0f8173; }
    .pill.failed { background: #fff0f3; color: #b83b52; }
    .pill.error { background: #fff4df; color: #b86b0c; }
    table { width: 100%; border-collapse: collapse; margin-top: 16px; }
    th, td { border: 1px solid #dce6f0; padding: 8px 10px; text-align: left; font-size: 12px; vertical-align: top; }
    th { background: #f7faff; }
    .status-passed { color: #0f8173; }
    .status-failed { color: #b83b52; font-weight: 600; }
    .status-error { color: #b86b0c; font-weight: 600; }
    .status-mismatch { color: #b83b52; }
    .error-cell { color: #596679; word-break: break-all; }
  </style>
</head>
<body>
  <h1>${escapeHtml(report.name)}</h1>
  <div>项目：${escapeHtml(report.project)}</div>
  <div>执行方式：${escapeHtml(report.mode)}</div>
  <div>开始：${escapeHtml(report.startedAt)}</div>
  <div>结束：${escapeHtml(report.endedAt)}</div>
  <div class="summary">
    <div class="pill">总数 ${report.summary.total}</div>
    <div class="pill passed">通过 ${report.summary.passed}</div>
    <div class="pill failed">失败 ${report.summary.failed}</div>
    <div class="pill error">错误 ${report.summary.error}</div>
  </div>
  <table>
    <thead>
      <tr>
        <th>用例ID</th>
        <th>名称</th>
        <th>状态</th>
        <th>期望状态</th>
        <th>实际状态</th>
        <th>耗时(ms)</th>
        <th>错误信息</th>
      </tr>
    </thead>
    <tbody>
      ${report.results.map((result) => {
        const statusLabel = result.status === 'passed' ? '通过' : result.status === 'failed' ? '失败' : '错误'
        const statusClass = `status-${result.status}`
        const mismatchClass = result.actualStatus !== undefined && result.actualStatus !== result.expectedStatus ? 'status-mismatch' : ''
        return `
        <tr>
          <td>${escapeHtml(result.caseId)}</td>
          <td>${escapeHtml(result.name)}</td>
          <td class="${statusClass}">${escapeHtml(statusLabel)}</td>
          <td>${escapeHtml(String(result.expectedStatus))}</td>
          <td class="${mismatchClass}">${escapeHtml(String(result.actualStatus ?? '-'))}</td>
          <td>${escapeHtml(String(result.durationMs))}</td>
          <td class="error-cell">${escapeHtml(result.errorMessage || '-')}</td>
        </tr>`
      }).join('')}
    </tbody>
  </table>
</body>
</html>`.trim()

const sanitizeFileName = (value: string): string => value.replace(/[\\/:*?"<>|]+/g, '-')
const escapeHtml = (value: string): string =>
  value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
const csvEscape = (value: string | number): string => `"${String(value).replace(/"/g, '""')}"`

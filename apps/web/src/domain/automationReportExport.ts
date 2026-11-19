import type { SavedAutomationReport } from './automationReportRepository'

export type AutomationReportExportFormat = 'json' | 'html' | 'md' | 'pdf' | 'csv'

export interface AutomationReportExportArtifact {
  kind: 'download' | 'print'
  filename: string
  mimeType: string
  content: string
}

export interface AutomationReportListExportFilters {
  project: string
  environment: string
  keyword: string
}

export const buildAutomationReportListJsonArtifact = (
  reports: SavedAutomationReport[],
  filters: AutomationReportListExportFilters,
  exportedAt = new Date().toISOString()
): AutomationReportExportArtifact => ({
  kind: 'download',
  filename: '执行报告列表.json',
  mimeType: 'application/json;charset=utf-8',
  content: JSON.stringify({
    kind: 'ai-test-platform.automation-report-list',
    version: 1,
    exportedAt,
    filters: sanitizeExportValue(filters),
    reports: reports.map(sanitizeReportForExport)
  }, null, 2)
})

const normalizeKey = (key: string): string => key
  .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
  .replace(/[^a-z0-9]+/gi, '_')
  .replace(/^_+|_+$/g, '')
  .toLowerCase()
const isSensitiveKey = (key: string): boolean => {
  const normalized = normalizeKey(key)
  return /(?:^|_)(?:authorization|proxy_authorization|cookie|set_cookie|api_key|apikey|client_key|clientkey|access_key|accesskey|auth|authentication|session|session_id|access_token|refresh_token|token|jwt|client_secret|signature|sig|password|passwd|pwd|pass|passcode|secret|credential|key|code|aws|aws_access_key_id|awsaccess_key_id|x_amz_credential|x_amz_security_token|awsaccesskeyid)$/.test(normalized)
}
const isUrlSensitiveKey = (key: string): boolean => {
  const normalized = normalizeKey(key)
  return isSensitiveKey(key) || /(?:^|_)(?:code|awsaccess_key_id|x_amz_security_token|x_amz_credential)$/.test(normalized)
}
interface AssignmentSpan {
  key: string
  valueStart: number
  valueEnd: number
}

const findAssignmentSpans = (value: string): AssignmentSpan[] => {
  const spans: AssignmentSpan[] = []
  ASSIGNMENT_START_PATTERN.lastIndex = 0
  let match: RegExpExecArray | null
  while ((match = ASSIGNMENT_START_PATTERN.exec(value))) {
    const previous = spans[spans.length - 1]
    if (previous) previous.valueEnd = match.index
    spans.push({ key: match[1] || '', valueStart: ASSIGNMENT_START_PATTERN.lastIndex, valueEnd: value.length })
  }
  ASSIGNMENT_START_PATTERN.lastIndex = 0
  return spans
}

const hasSensitiveAssignment = (value: string): boolean =>
  findAssignmentSpans(value).some(({ key }) => isUrlSensitiveKey(key))
const CREDENTIAL_MARKER = /(?:\bbearer(?:\s+[^\s,}"']+)?|\bbasic\s+[^\s,}"']+|\bauthorization\s*[:=]|\bproxy[-_]?authorization\s*[:=]|\bcookie\s*[:=]|\b(?:api|client)[-_]?key\s*[:=]|\bauth(?:entication)?\s*[:=]|\btoken\s*[:=]|\bjwt\s*[:=]|\bsignature\s*[:=]|\bsig\s*[:=]|\bsession(?:id)?\s*[:=]|\bsecret\s*[:=]|\bpassword\s*[:=]|\bpasswd\s*[:=]|\bpasscode\s*[:=]|\bcredential\s*[:=])/i
const ASSIGNMENT_START_PATTERN = /(?:^|[\s?&;,{/#}\]\)])"?([a-z0-9_-]+)"?\s*[:=]\s*/gi
const BEARER_PATTERN = /\bBearer\s+[^\s,}"']+/gi
const BASIC_PATTERN = /\bBasic\s+[^\s,}"']+/gi
const USERINFO_PATTERN = /([a-z][a-z\d+.-]*:\/\/)[^/\s@]+@/gi
const UNREDACTED_BEARER_PATTERN = /\bBearer\s+(?!\[REDACTED\])[^\s,}"']+/i
const UNREDACTED_BASIC_PATTERN = /\bBasic\s+(?!\[REDACTED\])[^\s,}"']+/i
const UNREDACTED_USERINFO_PATTERN = /[a-z][a-z\d+.-]*:\/\/(?!\[REDACTED\]@)[^/\s@]+@/i

const hasUnredactedSensitiveAssignment = (value: string): boolean => {
  return findAssignmentSpans(value).some(({ key, valueStart, valueEnd }) =>
    isUrlSensitiveKey(key) && value.slice(valueStart, valueEnd).trim() !== '[REDACTED]')
}

const hasStructuredSensitiveAssignment = (value: string): boolean => {
  ASSIGNMENT_START_PATTERN.lastIndex = 0
  let match: RegExpExecArray | null
  while ((match = ASSIGNMENT_START_PATTERN.exec(value))) {
    if (isUrlSensitiveKey(match[1] || '') && /^[\s]*[\[{(]/.test(value.slice(ASSIGNMENT_START_PATTERN.lastIndex))) {
      ASSIGNMENT_START_PATTERN.lastIndex = 0
      return true
    }
  }
  ASSIGNMENT_START_PATTERN.lastIndex = 0
  return false
}

const redactSensitiveAssignments = (value: string): string => {
  const spans = findAssignmentSpans(value).filter(({ key }) => isUrlSensitiveKey(key))
  if (spans.length === 0) return value
  let redacted = ''
  let cursor = 0
  for (const { valueStart, valueEnd } of spans) {
    redacted += value.slice(cursor, valueStart)
    redacted += '[REDACTED]'
    cursor = valueEnd
  }
  return redacted + value.slice(cursor)
}

/**
 * Decode every valid %XX escape independently, so a value with a partially
 * malformed escape still reveals the credential marker hiding behind an
 * encoded spelling such as `%42earer`.
 */
const looseDecode = (value: string): string =>
  value.replace(/%([0-9a-f]{2})/gi, (_match, hex: string) => {
    try {
      return decodeURIComponent(`%${hex}`)
    } catch {
      return `%${hex}`
    }
  })

const decodeRepeated = (value: string): string => {
  let current = value
  for (let attempt = 0; attempt < 16; attempt += 1) {
    const decoded = looseDecode(current)
    if (decoded === current) break
    current = decoded
    if (attempt === 15) return '[REDACTED]'
  }
  return current
}

const hasMalformedPercentEncoding = (value: string): boolean => /%(?![0-9a-f]{2})/i.test(value)

const redactInlineSecrets = (value: string): string => {
  if (!CREDENTIAL_MARKER.test(value) && !/[=:?&;%]/.test(value)) return value
  const decoded = decodeRepeated(value)
  if (hasStructuredSensitiveAssignment(decoded)) return '[REDACTED]'
  const nestedSensitiveAssignment = findAssignmentSpans(decoded).some(({ key, valueStart, valueEnd }) => {
    if (!isUrlSensitiveKey(key)) return false
    const assignmentValue = decoded.slice(valueStart, valueEnd)
    return /[\[{(]/.test(assignmentValue) || /[\]}\)]/.test(assignmentValue)
  })
  if (nestedSensitiveAssignment) return '[REDACTED]'
  let redacted = decoded
    .replace(BEARER_PATTERN, 'Bearer [REDACTED]')
    .replace(BASIC_PATTERN, 'Basic [REDACTED]')
  redacted = redactSensitiveAssignments(redacted)
  // Avoid running the URL-userinfo expression over arbitrary long diagnostics;
  // only invoke it when a decoded scheme marker is actually present.
  if (redacted.includes('://')) redacted = redacted.replace(USERINFO_PATTERN, '$1[REDACTED]@')
  // Fail closed: a credential marker that the linear redactions could not
  // neutralise means this field cannot be proven safe.
  if (UNREDACTED_BEARER_PATTERN.test(redacted)
    || UNREDACTED_BASIC_PATTERN.test(redacted)
    || hasUnredactedSensitiveAssignment(redacted)
    || (redacted.includes('://') && UNREDACTED_USERINFO_PATTERN.test(redacted))) return '[REDACTED]'
  if (CREDENTIAL_MARKER.test(redacted) && !redacted.includes('[REDACTED]')) return '[REDACTED]'
  if (CREDENTIAL_MARKER.test(decoded) && hasMalformedPercentEncoding(value)) return '[REDACTED]'
  return redacted
}

const sanitizeUrl = (value: string): string => {
  const decoded = decodeRepeated(value)
  // An unparseable percent escape cannot be classified, so the URL is withheld
  // rather than exported with a tail nobody can account for.
  if (decoded === '[REDACTED]' || hasMalformedPercentEncoding(decoded)) return '[REDACTED]'
  try {
    const isAbsolute = /^[a-z][a-z\d+.-]*:/i.test(decoded)
    const url = new URL(decoded, 'https://export.invalid')
    const hasUserinfo = Boolean(url.username || url.password)
    const hasSensitiveQuery = Array.from(url.searchParams.keys()).some((key) => isUrlSensitiveKey(key))
    const hasCredentialText = CREDENTIAL_MARKER.test(decoded) || hasUnredactedSensitiveAssignment(decoded) || hasSensitiveAssignment(decoded)
    const hasNestedUserinfo = decoded.includes('://') && UNREDACTED_USERINFO_PATTERN.test(decoded)
    if (!hasUserinfo && !hasSensitiveQuery && !hasCredentialText && !hasNestedUserinfo) return value
    // A nested DSN can cross path, query and fragment boundaries. Neither
    // splitting its pathname nor returning an inline-redacted string proves
    // every component safe, so withhold the complete ambiguous URL.
    if (hasNestedUserinfo) return '[REDACTED]'
    // Userinfo is always credential material for an exported artifact.
    if (hasUserinfo) {
      url.username = ''
      url.password = ''
    }
    url.pathname = url.pathname.split('/').map((segment) => redactInlineSecrets(segment)).join('/')
    for (const [key, raw] of Array.from(url.searchParams.entries())) {
      url.searchParams.set(key, isUrlSensitiveKey(key) ? '[REDACTED]' : redactInlineSecrets(raw))
    }
    if (url.hash) url.hash = `#${redactInlineSecrets(url.hash.slice(1))}`
    const result = url.toString()
    return isAbsolute ? result : `${url.pathname}${url.search}${url.hash}`
  } catch {
    return redactInlineSecrets(decoded)
  }
}

const sanitizeExportValue = (value: unknown, key = ''): unknown => {
  if (isSensitiveKey(key)) return '[REDACTED]'
  if (typeof value === 'string') return ['url', 'path', 'pageUrl', 'startUrl'].includes(key) ? sanitizeUrl(value) : redactInlineSecrets(value)
  if (Array.isArray(value)) return value.map((item) => sanitizeExportValue(item, key))
  if (!value || typeof value !== 'object') return value

  const entries = Object.entries(value as Record<string, unknown>)
    .filter(([entryKey]) => key === 'headers' ? !isSensitiveKey(entryKey) : true)
    .map(([entryKey, entryValue]) => {
      const decodedKey = decodeRepeated(entryKey)
      const safeKey = decodedKey === '[REDACTED]' || CREDENTIAL_MARKER.test(decodedKey) || isSensitiveKey(entryKey) || isSensitiveKey(decodedKey)
        ? '[REDACTED]'
        : entryKey
      return [safeKey, safeKey === '[REDACTED]' ? '[REDACTED]' : sanitizeExportValue(entryValue, decodedKey)]
    })
  return Object.fromEntries(entries)
}

const sanitizeReportForExport = (report: SavedAutomationReport): SavedAutomationReport =>
  sanitizeExportValue(report) as SavedAutomationReport

export const buildAutomationReportExportArtifact = (
  report: SavedAutomationReport,
  format: AutomationReportExportFormat
): AutomationReportExportArtifact => {
  const safeReport = sanitizeReportForExport(report)
  const safeName = sanitizeFileName(safeReport.name)

  if (format === 'json') {
    return {
      kind: 'download',
      filename: `${safeName}.json`,
      mimeType: 'application/json;charset=utf-8',
      content: JSON.stringify(safeReport, null, 2)
    }
  }

  if (format === 'csv') {
    return {
      kind: 'download',
      filename: `${safeName}.csv`,
      mimeType: 'text/csv;charset=utf-8',
      content: [
        '用例ID,来源用例,用例名称,模块,方法,路径,执行状态,期望状态,实际状态,状态匹配,耗时(ms),错误信息',
        ...safeReport.results.map((result) => {
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
            result.errorMessage || ''
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
        `# ${safeReport.name}`,
        '',
        `- 项目：${safeReport.project}`,
        `- 执行方式：${safeReport.mode}`,
        `- 开始时间：${safeReport.startedAt}`,
        `- 结束时间：${safeReport.endedAt}`,
        `- 结果：总数 ${safeReport.summary.total}，通过 ${safeReport.summary.passed}，失败 ${safeReport.summary.failed}，错误 ${safeReport.summary.error}`,
        '',
        '| 用例ID | 名称 | 状态 | 期望状态 | 实际状态 | 耗时(ms) | 错误信息 |',
        '| --- | --- | --- | --- | --- | --- | --- |',
        ...safeReport.results.map((result) => {
          const statusLabel = result.status === 'passed' ? '通过' : result.status === 'failed' ? '失败' : '错误'
          const errorInfo = result.errorMessage ? csvEscape(result.errorMessage) : '-'
          return `| ${result.caseId} | ${result.name} | ${statusLabel} | ${result.expectedStatus} | ${result.actualStatus ?? '-'} | ${result.durationMs} | ${errorInfo} |`
        })
      ].join('\n')
    }
  }

  const html = renderReportHtml(safeReport)
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

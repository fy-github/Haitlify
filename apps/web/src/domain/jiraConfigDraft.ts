/**
 * Jira 配置草稿:快照口径与保存前校验的唯一来源。
 *
 * 为什么单独一模块:
 * - 页面内的「未保存修改」与 App 的保存/测试/离开守卫必须用同一口径,
 *   否则 token、JQL 行内输入这类字段会出现"页面说没改、保存却提交了"的分歧。
 * - 快照包含 token 与 JQL 行(含顺序),因为它们是连接配置的一部分;
 *   服务端回显的 token 永远是空串,所以输入 token 必然产生未保存修改。
 */
import type { JiraConfig } from './jiraConfigRepository'

export interface JiraProjectJqlRow {
  key: string
  value: string
}

const normalize = (value: string): string => value.trim()

/** 草稿快照:已保存基线与之比较即为「有未保存修改」。 */
export const jiraDraftSnapshot = (form: JiraConfig, rows: JiraProjectJqlRow[]): string =>
  JSON.stringify({
    enabled: form.enabled,
    baseUrl: normalize(form.baseUrl),
    authMode: form.authMode,
    username: normalize(form.username),
    token: form.token,
    rows: rows.map((row) => [row.key, row.value])
  })

/** 连接参数快照:只要它变了,已有的连接测试结论就失效。 */
export const jiraConnectionSnapshot = (form: JiraConfig): string =>
  JSON.stringify({
    baseUrl: normalize(form.baseUrl),
    authMode: form.authMode,
    username: normalize(form.username),
    token: form.token
  })

export const jiraRowsFromProjectJql = (projectJql: Record<string, string> | undefined): JiraProjectJqlRow[] =>
  Object.entries(projectJql || {}).map(([key, value]) => ({ key, value }))

export const jiraProjectJqlFromRows = (rows: JiraProjectJqlRow[]): Record<string, string> => {
  const next: Record<string, string> = {}
  for (const row of rows) {
    const key = row.key.trim()
    if (key) next[key] = row.value
  }
  return next
}

const BASE_URL_MESSAGE = 'Base URL 需为 HTTP/HTTPS 地址，不包含账号、口令、查询或片段。'

/** Base URL:允许未启用时留空;启用后必须有合法 HTTP/HTTPS 地址。 */
export const jiraBaseUrlError = (value: string, enabled: boolean): string => {
  const trimmed = value.trim()
  if (!trimmed) return enabled ? '请填写 Jira Base URL。' : ''
  try {
    const url = new URL(trimmed)
    if (!['http:', 'https:'].includes(url.protocol)) return BASE_URL_MESSAGE
    if (url.username || url.password || url.search || url.hash) return BASE_URL_MESSAGE
  } catch {
    return BASE_URL_MESSAGE
  }
  return ''
}

/** 映射行:项目名与 JQL 都不能为空,项目名不能重复。 */
export const jiraRowsError = (rows: JiraProjectJqlRow[]): string => {
  const keys = new Set<string>()
  for (const row of rows) {
    const key = row.key.trim()
    if (!key || !row.value.trim()) return '映射项目名与 JQL 不能为空；无需的空行请删除。'
    if (keys.has(key)) return `存在重复项目名「${key}」，请合并或修改映射。`
    keys.add(key)
  }
  return ''
}

export const validateJiraDraft = (form: JiraConfig, rows: JiraProjectJqlRow[]): string =>
  jiraBaseUrlError(form.baseUrl, form.enabled) || jiraRowsError(rows)

/** 连接测试只关心连接目标:JQL 映射不参与连通性判断,但地址必须可用。 */
export const jiraTestTargetError = (form: JiraConfig): string =>
  form.baseUrl.trim() ? jiraBaseUrlError(form.baseUrl, true) : '请填写 Jira Base URL。'

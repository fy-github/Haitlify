export type NavKey =
  | 'home'
  | 'defects'
  | 'jiraConfig'
  | 'api'
  | 'cases'
  | 'webCases'
  | 'automation'
  | 'reports'
  | 'settings'
  | 'users'
  | 'assistant'
  | 'overview'
  | 'caseTypes'
  | 'environments'

export type AutomationNavKind = 'api' | 'web'
export type CaseDesignNavKind = 'interface' | 'web_automation'

/* 默认落点 = 首页 AI 助手;根路径 '/' 解析为 home。 */
export const defaultNavKey: NavKey = 'home'
export const defaultAutomationNavKind: AutomationNavKind = 'api'

const automationPathMap: Record<AutomationNavKind, string> = {
  api: '/automation/api',
  web: '/automation/web'
}

const navPathMap: Record<NavKey, string> = {
  home: '/',
  defects: '/defects',
  jiraConfig: '/jira-config',
  api: '/cases',
  cases: '/cases',
  webCases: '/cases/web',
  automation: automationPathMap.api,
  environments: '/environments',
  reports: '/reports',
  settings: '/settings',
  users: '/users',
  assistant: '/assistant',
  overview: '/overview',
  caseTypes: '/settings/case-types'
}

const pathNavMap = new Map<string, NavKey>([
  ...Object.entries(navPathMap).map(([key, path]) => [path, key as NavKey] as const),
  ['/automation', 'automation'],
  [automationPathMap.api, 'automation'],
  [automationPathMap.web, 'automation'],
  ['/api', 'cases'],
  ['/web-cases', 'webCases'],
  ['/ai-config', 'assistant']
])

export const navPathFor = (key: NavKey): string => navPathMap[key]

export const automationPathFor = (kind: AutomationNavKind): string => automationPathMap[kind]

// 旧设计入口指向对应的用例库，不恢复已下线的设计画布。
export const caseDesignPathFor = (kind: CaseDesignNavKind): string =>
  navPathMap[kind === 'web_automation' ? 'webCases' : 'cases']

export const caseDesignKindFromPath = (pathname: string): CaseDesignNavKind =>
  navKeyFromPath(pathname) === 'webCases' ? 'web_automation' : 'interface'

export const automationKindFromPath = (pathname: string): AutomationNavKind => {
  const normalizedPath = normalizePath(pathname)
  return Object.entries(automationPathMap).find(([, path]) => path === normalizedPath)?.[0] as AutomationNavKind | undefined || defaultAutomationNavKind
}

export const navKeyFromPath = (pathname: string): NavKey => {
  const normalizedPath = normalizePath(pathname)
  return pathNavMap.get(normalizedPath) || defaultNavKey
}

const normalizePath = (pathname: string): string => {
  if (!pathname || pathname === '/') return '/'

  const trimmed = pathname.endsWith('/') ? pathname.slice(0, -1) : pathname
  return trimmed || '/'
}

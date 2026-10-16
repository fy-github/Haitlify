export type NavKey =
  | 'home'
  | 'defects'
  | 'jiraConfig'
  | 'api'
  | 'cases'
  | 'automation'
  | 'reports'
  | 'settings'
  | 'aiConfig'
  | 'users'

export type AutomationNavKind = 'api' | 'web'
export type CaseDesignNavKind = 'interface' | 'web_automation'

/* 默认落点 = 首页 AI 助手;根路径 '/' 解析为 home。 */
export const defaultNavKey: NavKey = 'home'
export const defaultAutomationNavKind: AutomationNavKind = 'api'

const automationPathMap: Record<AutomationNavKind, string> = {
  api: '/automation/api',
  web: '/automation/web'
}

const caseDesignPathMap: Record<CaseDesignNavKind, string> = {
  interface: '/api',
  web_automation: '/web-cases'
}

const navPathMap: Record<NavKey, string> = {
  home: '/',
  defects: '/defects',
  jiraConfig: '/jira-config',
  api: '/api',
  cases: '/cases',
  automation: automationPathMap.api,
  reports: '/reports',
  settings: '/settings',
  aiConfig: '/ai-config',
  users: '/users'
}

const pathNavMap = new Map<string, NavKey>([
  ...Object.entries(navPathMap).map(([key, path]) => [path, key as NavKey] as const),
  ['/automation', 'automation'],
  [automationPathMap.api, 'automation'],
  [automationPathMap.web, 'automation'],
  [caseDesignPathMap.web_automation, 'api']
])

export const navPathFor = (key: NavKey): string => navPathMap[key]

export const automationPathFor = (kind: AutomationNavKind): string => automationPathMap[kind]

export const caseDesignPathFor = (kind: CaseDesignNavKind): string => caseDesignPathMap[kind]

export const caseDesignKindFromPath = (pathname: string): CaseDesignNavKind =>
  normalizePath(pathname) === caseDesignPathMap.web_automation ? 'web_automation' : 'interface'

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

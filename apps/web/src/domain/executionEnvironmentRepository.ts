import { EXECUTION_ENV_STORAGE_KEY, parseRequestHeadersText, type ExecutionEnvironment } from './apiExecution'
import type { WebAutomationAction, WebAutomationStep } from './webAutomationCase'

export const EXECUTION_ENV_LIBRARY_STORAGE_KEY = 'ai-test-platform.execution-environments.v1'
export const WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY = 'ai-test-platform.web-execution-environments.v1'

export type EnvironmentAuthType = 'none' | 'bearer' | 'custom'
export type EnvironmentEntryType = 'header' | 'cookie' | 'variable'
export type WebRuntimeBrowser = 'follow-case' | 'chromium' | 'chrome' | 'msedge' | 'firefox' | 'webkit'
export type WebRuntimeWaitUntil = 'load' | 'domcontentloaded' | 'networkidle'
export type WebRuntimeScreenshotStrategy = 'none' | 'on_failure' | 'always'
export type WebLoginSuccessType = 'url' | 'visible'

export interface EnvironmentAuthConfig {
  type: EnvironmentAuthType
  token?: string
  headerName?: string
  headerValue?: string
}

export interface ExecutionEnvironmentEntry {
  id: string
  enabled: boolean
  type: EnvironmentEntryType
  name: string
  value: string
  description: string
}

export interface WebLoginConfig {
  enabled: boolean
  loginUrl: string
  usernameLocator: string
  passwordLocator: string
  submitLocator: string
  username: string
  password: string
  successType: WebLoginSuccessType
  successTarget: string
}

export interface WebLogoutConfig {
  enabled: boolean
  steps: WebAutomationStep[]
}

export interface WebExecutionRuntimeConfig {
  browser: WebRuntimeBrowser
  viewport: string
  headless: boolean
  defaultTimeoutMs: number
  navigationTimeoutMs: number
  waitUntil: WebRuntimeWaitUntil
  screenshotStrategy: WebRuntimeScreenshotStrategy
  videoRecording: boolean
  retryCount: number
  cookieOrigins?: string[]
  allowedRequestOrigins?: string[]
  /** 网络放行策略：blocklist（默认，只拦截名单内请求）/ allowlist（只放行名单内请求，其余拦截）。 */
  networkPolicyMode?: 'blocklist' | 'allowlist'
  /** 验证自动登录后保存的浏览器会话 id；编译取证时优先复用（runner 内存会话，约 10 分钟有效）。 */
  sessionId?: string
  login: WebLoginConfig
  logout: WebLogoutConfig
}

export interface SavedExecutionEnvironment {
  id: string
  name: string
  baseUrl: string
  auth: EnvironmentAuthConfig
  entries: ExecutionEnvironmentEntry[]
  web?: WebExecutionRuntimeConfig
  createdAt: string
  updatedAt: string
}

export interface ExecutionEnvironmentLibrary {
  activeEnvironmentId: string
  environments: SavedExecutionEnvironment[]
}

export interface EnvironmentValidationResult {
  valid: boolean
  message?: string
}

export interface BuiltExecutionEnvironment extends ExecutionEnvironment {
  variables: Record<string, string>
}

export interface BuiltWebExecutionRuntime {
  environmentId?: string
  sessionKey?: string
  sessionId?: string
  persistSession?: boolean
  baseUrl: string
  browser: WebRuntimeBrowser
  viewport: string
  headless: boolean
  defaultTimeoutMs: number
  navigationTimeoutMs: number
  waitUntil: WebRuntimeWaitUntil
  screenshotStrategy: WebRuntimeScreenshotStrategy
  videoRecording: boolean
  retryCount: number
  authToken: string
  extraHeaders: Record<string, string>
  cookieOrigins: string[]
  allowedRequestOrigins?: string[]
  networkPolicyMode: 'blocklist' | 'allowlist'
  variables: Record<string, string>
  login: WebLoginConfig
  logout: WebLogoutConfig
}

export const defaultExecutionEnvironmentLibrary = (): ExecutionEnvironmentLibrary => {
  const now = new Date().toISOString()
  return {
    activeEnvironmentId: 'env-default',
    environments: [
      {
        id: 'env-default',
        name: '默认环境',
        baseUrl: '',
        auth: {
          type: 'none'
        },
        entries: [],
        createdAt: now,
        updatedAt: now
      }
    ]
  }
}

export const defaultWebExecutionRuntimeConfig = (): WebExecutionRuntimeConfig => ({
  browser: 'chromium',
  viewport: '1440x900',
  headless: true,
  defaultTimeoutMs: 30000,
  navigationTimeoutMs: 45000,
  waitUntil: 'networkidle',
  screenshotStrategy: 'on_failure',
  videoRecording: false,
  retryCount: 0,
  cookieOrigins: [],
  allowedRequestOrigins: [],
  networkPolicyMode: 'blocklist',
  login: defaultWebLoginConfig(),
  logout: defaultWebLogoutConfig()
})

export const defaultWebLoginConfig = (): WebLoginConfig => ({
  enabled: false,
  loginUrl: '',
  usernameLocator: '',
  passwordLocator: '',
  submitLocator: '',
  username: '',
  password: '',
  successType: 'url',
  successTarget: ''
})

export const defaultWebLogoutConfig = (): WebLogoutConfig => ({
  enabled: false,
  steps: []
})

export const defaultWebExecutionEnvironmentLibrary = (): ExecutionEnvironmentLibrary => {
  const now = new Date().toISOString()
  return {
    activeEnvironmentId: 'web-env-default',
    environments: [
      {
        id: 'web-env-default',
        name: 'Web 默认环境',
        baseUrl: '',
        auth: {
          type: 'none'
        },
        entries: [],
        web: defaultWebExecutionRuntimeConfig(),
        createdAt: now,
        updatedAt: now
      }
    ]
  }
}

export const loadExecutionEnvironmentLibrary = (
  storage: Storage,
  storageKey = EXECUTION_ENV_LIBRARY_STORAGE_KEY
): ExecutionEnvironmentLibrary => {
  const raw = storage.getItem(storageKey)
  if (raw) {
    try {
      const parsed = JSON.parse(raw)
      if (isExecutionEnvironmentLibrary(parsed)) {
        return storageKey === WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY
          ? normalizeWebExecutionEnvironmentLibrary(parsed)
          : parsed
      }
    } catch {
      // Fall back to migration/default below.
    }
  }

  if (storageKey === WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY) {
    return defaultWebExecutionEnvironmentLibrary()
  }

  const migrated = migrateLegacyEnvironment(storage)
  if (migrated) return migrated
  return defaultExecutionEnvironmentLibrary()
}

export const saveExecutionEnvironmentLibrary = (
  storage: Storage,
  library: ExecutionEnvironmentLibrary,
  storageKey = EXECUTION_ENV_LIBRARY_STORAGE_KEY
): void => {
  storage.setItem(storageKey, JSON.stringify(library))
}

export const validateExecutionEnvironment = (
  environment: SavedExecutionEnvironment | undefined
): EnvironmentValidationResult => {
  if (!environment) {
    return { valid: false, message: '请先选择运行环境' }
  }
  if (!environment.baseUrl.trim()) {
    return { valid: false, message: `运行环境「${environment.name}」的 Base URL 不能为空` }
  }
  if (environment.auth.type === 'bearer' && !environment.auth.token?.trim()) {
    return { valid: false, message: `运行环境「${environment.name}」的 Bearer Token 不能为空` }
  }
  if (environment.auth.type === 'custom' && (!environment.auth.headerName?.trim() || !environment.auth.headerValue?.trim())) {
    return { valid: false, message: `运行环境「${environment.name}」的自定义认证不能为空` }
  }
  if (environment.entries.some((entry) => entry.enabled && !entry.name.trim())) {
    return { valid: false, message: `运行环境「${environment.name}」存在启用但参数名为空的配置` }
  }

  return { valid: true }
}

export const validateWebExecutionEnvironment = (
  environment: SavedExecutionEnvironment | undefined
): EnvironmentValidationResult => {
  if (!environment) {
    return { valid: false, message: '请先选择 Web 运行环境' }
  }
  if (!environment.baseUrl.trim()) {
    return { valid: false, message: `Web 运行环境「${environment.name}」的应用 Base URL 不能为空` }
  }
  const web = normalizeWebExecutionRuntimeConfig(environment.web)
  if (web.defaultTimeoutMs <= 0) {
    return { valid: false, message: `Web 运行环境「${environment.name}」的默认等待超时必须大于 0` }
  }
  if (web.navigationTimeoutMs <= 0) {
    return { valid: false, message: `Web 运行环境「${environment.name}」的页面加载超时必须大于 0` }
  }
  if (web.retryCount < 0) {
    return { valid: false, message: `Web 运行环境「${environment.name}」的失败重试次数不能小于 0` }
  }
  const configuredOrigins = environment.web?.allowedRequestOrigins || []
  if (configuredOrigins.some((item) => !normalizeAllowedRequestEntry(String(item)))) {
    return { valid: false, message: `Web 运行环境「${environment.name}」存在无效的认证/放行白名单条目，仅支持不含账号/查询/片段的 HTTP/HTTPS Origin，或带路径的完整 URL` }
  }
  if (web.login.enabled) {
    if (!web.login.loginUrl.trim()) {
      return { valid: false, message: `Web 运行环境「${environment.name}」已开启自动登录，请填写登录页面地址` }
    }
    if (!web.login.usernameLocator.trim()) {
      return { valid: false, message: `Web 运行环境「${environment.name}」已开启自动登录，请填写账号输入框定位器` }
    }
    if (!web.login.passwordLocator.trim()) {
      return { valid: false, message: `Web 运行环境「${environment.name}」已开启自动登录，请填写密码输入框定位器` }
    }
    if (!web.login.submitLocator.trim()) {
      return { valid: false, message: `Web 运行环境「${environment.name}」已开启自动登录，请填写登录按钮定位器` }
    }
    if (!web.login.username.trim()) {
      return { valid: false, message: `Web 运行环境「${environment.name}」已开启自动登录，请填写登录账号` }
    }
    if (!web.login.password.trim()) {
      return { valid: false, message: `Web 运行环境「${environment.name}」已开启自动登录，请填写登录密码` }
    }
    if (!web.login.successTarget.trim()) {
      return { valid: false, message: `Web 运行环境「${environment.name}」已开启自动登录，请填写登录成功判定目标` }
    }
  }
  if (web.logout.enabled) {
    if (web.logout.steps.length === 0) {
      return { valid: false, message: `Web 运行环境「${environment.name}」已开启退出登录，请至少添加一个后置步骤` }
    }
    const invalidStep = web.logout.steps.find((step) => !step.target.trim())
    if (invalidStep) {
      return {
        valid: false,
        message: `Web 运行环境「${environment.name}」的退出登录步骤「${invalidStep.name}」缺少目标`
      }
    }
  }
  return { valid: true }
}

export const buildExecutionEnvironment = (environment: SavedExecutionEnvironment): BuiltExecutionEnvironment => {
  const headers: Record<string, string> = {}
  const cookies: string[] = []
  const variables: Record<string, string> = {}

  for (const entry of environment.entries) {
    if (!entry.enabled || !entry.name.trim()) continue
    if (entry.type === 'header') headers[entry.name.trim()] = entry.value
    if (entry.type === 'cookie') cookies.push(`${entry.name.trim()}=${entry.value}`)
    if (entry.type === 'variable') variables[entry.name.trim()] = entry.value
  }

  if (environment.auth.type === 'custom' && environment.auth.headerName?.trim()) {
    headers[environment.auth.headerName.trim()] = environment.auth.headerValue || ''
  }

  if (cookies.length) {
    headers.Cookie = cookies.join('; ')
  }

  return {
    baseUrl: environment.baseUrl.trim(),
    authToken: environment.auth.type === 'bearer' ? environment.auth.token || '' : '',
    extraHeaders: headers,
    variables
  }
}

export const buildWebExecutionRuntime = (environment: SavedExecutionEnvironment): BuiltWebExecutionRuntime => {
  const web = normalizeWebExecutionRuntimeConfig(environment.web)
  const browserState = buildExecutionEnvironment(environment)

  return {
    environmentId: environment.id,
    sessionKey: `${environment.id}:${web.browser}`,
    ...(web.sessionId ? { sessionId: web.sessionId } : {}),
    baseUrl: environment.baseUrl.trim(),
    browser: web.browser,
    viewport: web.viewport,
    headless: web.headless,
    defaultTimeoutMs: web.defaultTimeoutMs,
    navigationTimeoutMs: web.navigationTimeoutMs,
    waitUntil: web.waitUntil,
    screenshotStrategy: web.screenshotStrategy,
    videoRecording: web.videoRecording,
    retryCount: web.retryCount,
    authToken: browserState.authToken || '',
    extraHeaders: browserState.extraHeaders || {},
    cookieOrigins: web.cookieOrigins || [],
    ...(web.allowedRequestOrigins?.length ? { allowedRequestOrigins: web.allowedRequestOrigins } : {}),
    networkPolicyMode: web.networkPolicyMode || 'blocklist',
    variables: browserState.variables,
    login: web.login,
    logout: web.logout
  }
}

export const parseCurlIntoEnvironmentEntries = (text: string): ExecutionEnvironmentEntry[] =>
  Object.entries(parseRequestHeadersText(text)).flatMap(([name, value]) => {
    if (name.toLowerCase() !== 'cookie') {
      return [newEnvironmentEntry('header', name, value)]
    }
    return value
      .split(';')
      .map((part) => part.trim())
      .filter(Boolean)
      .map((cookie) => {
        const separatorIndex = cookie.indexOf('=')
        return newEnvironmentEntry(
          'cookie',
          separatorIndex > -1 ? cookie.slice(0, separatorIndex).trim() : cookie,
          separatorIndex > -1 ? cookie.slice(separatorIndex + 1).trim() : ''
        )
      })
  })

export const parseCurlEnvironmentImport = (
  text: string,
  options: { target?: 'api' | 'web' } = {}
): {
  baseUrl?: string
  requestOrigin?: string
  auth: EnvironmentAuthConfig
  entries: ExecutionEnvironmentEntry[]
} => parseCurlEnvironmentImportWithOptions(text, options)

export const parseCurlEnvironmentImportWithOptions = (
  text: string,
  options: { target?: 'api' | 'web' } = {}
): {
  baseUrl?: string
  requestOrigin?: string
  auth: EnvironmentAuthConfig
  entries: ExecutionEnvironmentEntry[]
} => {
  const lines = text.split(/\r?\n/)
  const curlLine = lines.find((line) => line.trim().startsWith('curl '))
  const urlMatch = curlLine?.match(/curl\s+['"]?(https?:\/\/[^'"\s\\]+)['"]?/)
  const requestOrigin = urlMatch ? new URL(urlMatch[1]).origin : undefined
  const entries = parseCurlIntoEnvironmentEntries(text)
  const baseUrl = options.target === 'web'
    ? detectWebApplicationBaseUrl(entries)
    : requestOrigin
  const authHeaderIndex = entries.findIndex(
    (entry) => entry.type === 'header' && entry.name.toLowerCase() === 'authorization'
  )

  if (authHeaderIndex > -1) {
    const authorization = entries[authHeaderIndex].value
    entries.splice(authHeaderIndex, 1)
    const bearerMatch = authorization.match(/^Bearer\s+(.+)$/i)
    if (bearerMatch) {
      return {
        baseUrl,
        requestOrigin,
        auth: {
          type: 'bearer',
          token: bearerMatch[1].trim()
        },
        entries
      }
    }

    return {
      baseUrl,
      requestOrigin,
      auth: {
        type: 'custom',
        headerName: 'Authorization',
        headerValue: authorization
      },
      entries
    }
  }

  return {
    baseUrl,
    requestOrigin,
    auth: {
      type: 'none'
    },
    entries
  }
}

const detectWebApplicationBaseUrl = (entries: ExecutionEnvironmentEntry[]): string | undefined => {
  const origin = findHeaderValue(entries, 'origin')
  if (origin && isHttpUrl(origin)) return new URL(origin).origin

  const referer = findHeaderValue(entries, 'referer') || findHeaderValue(entries, 'referrer')
  if (referer && isHttpUrl(referer)) return new URL(referer).origin

  return undefined
}

const findHeaderValue = (entries: ExecutionEnvironmentEntry[], name: string): string | undefined =>
  entries.find((entry) => entry.type === 'header' && entry.name.toLowerCase() === name)?.value.trim()

const isHttpUrl = (value: string): boolean =>
  /^https?:\/\//i.test(value)

const normalizeAllowedRequestOrigins = (
  value: Partial<WebExecutionRuntimeConfig> | undefined
): string[] => {
  if (!value) return []
  const legacyText = (value as Partial<WebExecutionRuntimeConfig> & { allowedRequestOriginsText?: unknown }).allowedRequestOriginsText
  const rawValues = [
    ...(Array.isArray(value.allowedRequestOrigins) ? value.allowedRequestOrigins : []),
    ...(typeof legacyText === 'string' ? legacyText.split(/[\s,]+/) : [])
  ]
  return Array.from(new Set(
    rawValues
      .map((item) => String(item).trim())
      .filter(Boolean)
      .map((item) => normalizeAllowedRequestEntry(item))
      .filter((item): item is string => Boolean(item))
  ))
}

/**
 * 认证/放行白名单条目归一化：
 * - 纯 Origin（无路径/查询/片段）→ 作为来源白名单（页面资源与 GET 请求）；
 * - 带路径的完整 URL → 保留 protocol+host+path 原形，平台侧会把它转为「精确放行请求」
 *   （任意方法，含 POST——用于登录/认证接口这类写请求）。
 * 原先这里把所有条目折叠为 Origin，导致「带路径 URL 精确放行」通道在 UI 侧根本不可达
 * （用户实测：自动登录配好、验证能过，取证时登录 POST 仍被网络策略拦截）。
 */
export const normalizeAllowedRequestEntry = (value: string): string => {
  try {
    const parsed = new URL(value.trim())
    if ((parsed.protocol !== 'http:' && parsed.protocol !== 'https:') || parsed.username || parsed.password) return ''
    const path = parsed.pathname.replace(/\/+$/, '')
    return path ? `${parsed.origin}${path}` : parsed.origin
  } catch {
    return ''
  }
}


export const newEnvironmentEntry = (
  type: EnvironmentEntryType = 'header',
  name = '',
  value = ''
): ExecutionEnvironmentEntry => ({
  id: `entry-${Date.now()}-${Math.random().toString(16).slice(2)}`,
  enabled: true,
  type,
  name,
  value,
  description: ''
})

export const newSavedExecutionEnvironment = (name = '新环境'): SavedExecutionEnvironment => {
  const now = new Date().toISOString()
  return {
    id: `env-${Date.now()}-${Math.random().toString(16).slice(2)}`,
    name,
    baseUrl: '',
    auth: {
      type: 'none'
    },
    entries: [],
    createdAt: now,
    updatedAt: now
  }
}

export const newSavedWebExecutionEnvironment = (name = 'Web 新环境'): SavedExecutionEnvironment => {
  const now = new Date().toISOString()
  return {
    id: `web-env-${Date.now()}-${Math.random().toString(16).slice(2)}`,
    name,
    baseUrl: '',
    auth: {
      type: 'none'
    },
    entries: [],
    web: defaultWebExecutionRuntimeConfig(),
    createdAt: now,
    updatedAt: now
  }
}

const migrateLegacyEnvironment = (storage: Storage): ExecutionEnvironmentLibrary | undefined => {
  const raw = storage.getItem(EXECUTION_ENV_STORAGE_KEY)
  if (!raw) return undefined

  try {
    const legacy = JSON.parse(raw) as ExecutionEnvironment
    const now = new Date().toISOString()
    const entries = [
      ...Object.entries(legacy.extraHeaders || {})
        .filter(([name]) => name.toLowerCase() !== 'cookie')
        .map(([name, value]) => newEnvironmentEntry('header', name, String(value))),
      ...parseCurlIntoEnvironmentEntries(legacy.requestHeadersText || '')
    ]
    const cookie = legacy.extraHeaders?.Cookie || legacy.extraHeaders?.cookie
    if (cookie) {
      entries.push(...parseCurlIntoEnvironmentEntries(`-b '${cookie}'`))
    }

    return {
      activeEnvironmentId: 'env-default',
      environments: [
        {
          id: 'env-default',
          name: '默认环境',
          baseUrl: legacy.baseUrl || '',
          auth: legacy.authToken ? { type: 'bearer', token: legacy.authToken } : { type: 'none' },
          entries,
          createdAt: now,
          updatedAt: now
        }
      ]
    }
  } catch {
    return undefined
  }
}

export const normalizeWebExecutionRuntimeConfig = (
  value: Partial<WebExecutionRuntimeConfig> | undefined
): WebExecutionRuntimeConfig => {
  const defaults = defaultWebExecutionRuntimeConfig()
  if (!value) return defaults
  return {
    browser: normalizeWebRuntimeBrowser(value.browser || defaults.browser),
    viewport: value.viewport?.trim() || defaults.viewport,
    headless: value.headless ?? defaults.headless,
    defaultTimeoutMs: typeof value.defaultTimeoutMs === 'number' && Number.isFinite(value.defaultTimeoutMs)
      ? Math.floor(value.defaultTimeoutMs)
      : defaults.defaultTimeoutMs,
    navigationTimeoutMs: typeof value.navigationTimeoutMs === 'number' && Number.isFinite(value.navigationTimeoutMs)
      ? Math.floor(value.navigationTimeoutMs)
      : defaults.navigationTimeoutMs,
    waitUntil:
      value.waitUntil === 'load' || value.waitUntil === 'domcontentloaded' || value.waitUntil === 'networkidle'
        ? value.waitUntil
        : defaults.waitUntil,
    screenshotStrategy:
      value.screenshotStrategy === 'none' || value.screenshotStrategy === 'always'
        ? value.screenshotStrategy
        : defaults.screenshotStrategy,
    videoRecording: value.videoRecording ?? defaults.videoRecording,
    retryCount: typeof value.retryCount === 'number' && Number.isFinite(value.retryCount)
      ? Math.floor(value.retryCount)
      : defaults.retryCount,
    cookieOrigins: Array.isArray(value.cookieOrigins)
      ? Array.from(new Set(value.cookieOrigins.map((item) => String(item).trim()).filter(isHttpUrl).map((item) => new URL(item).origin)))
      : defaults.cookieOrigins,
    allowedRequestOrigins: normalizeAllowedRequestOrigins(value),
    networkPolicyMode: value.networkPolicyMode === 'allowlist' ? 'allowlist' : 'blocklist',
    ...(typeof value.sessionId === 'string' && value.sessionId.trim() ? { sessionId: value.sessionId.trim() } : {}),
    login: normalizeWebLoginConfig(value.login),
    logout: normalizeWebLogoutConfig(value.logout)
  }
}

const normalizeWebRuntimeBrowser = (value: string): WebRuntimeBrowser =>
  value === 'follow-case' || value === 'chrome' || value === 'msedge' || value === 'firefox' || value === 'webkit'
    ? value
    : 'chromium'

export const normalizeWebLoginConfig = (
  value: Partial<WebLoginConfig> | undefined
): WebLoginConfig => {
  const defaults = defaultWebLoginConfig()
  if (!value) return defaults
  return {
    enabled: value.enabled ?? defaults.enabled,
    loginUrl: value.loginUrl?.trim() || defaults.loginUrl,
    usernameLocator: value.usernameLocator?.trim() || defaults.usernameLocator,
    passwordLocator: value.passwordLocator?.trim() || defaults.passwordLocator,
    submitLocator: value.submitLocator?.trim() || defaults.submitLocator,
    username: value.username?.trim() || defaults.username,
    password: value.password?.trim() || defaults.password,
    successType: value.successType === 'visible' ? value.successType : defaults.successType,
    successTarget: value.successTarget?.trim() || defaults.successTarget
  }
}

export const normalizeWebLogoutConfig = (
  value: Partial<WebLogoutConfig> | undefined
): WebLogoutConfig => {
  const defaults = defaultWebLogoutConfig()
  if (!value) return defaults
  return {
    enabled: value.enabled ?? defaults.enabled,
    steps: Array.isArray(value.steps)
      ? value.steps.map(normalizeWebLogoutStep)
      : defaults.steps
  }
}

const normalizeWebLogoutStep = (step: Partial<WebAutomationStep>, index: number): WebAutomationStep => {
  const action = normalizeWebLogoutAction(step.action)
  return {
    id: step.id?.trim() || `logout-${index + 1}`,
    name: step.name?.trim() || `退出登录步骤 ${index + 1}`,
    action,
    targetType: step.targetType === 'url' || step.targetType === 'elementRef'
      ? step.targetType
      : action === 'goto' || action === 'waitForUrl' || action === 'assertUrl'
        ? 'url'
        : 'locator',
    target: step.target?.trim() || '',
    ...(step.value === undefined ? {} : { value: String(step.value) }),
    ...(Number.isFinite(step.timeoutMs) ? { timeoutMs: Number(step.timeoutMs) } : {})
  }
}

const normalizeWebLogoutAction = (action: WebAutomationAction | undefined): WebAutomationAction =>
  action === 'goto'
  || action === 'click'
  || action === 'waitForVisible'
  || action === 'waitForUrl'
  || action === 'assertUrl'
  || action === 'assertVisible'
  || action === 'scrollIntoView'
  || action === 'paginate'
    ? action
    : 'click'

const normalizeWebExecutionEnvironmentLibrary = (
  library: ExecutionEnvironmentLibrary
): ExecutionEnvironmentLibrary => ({
  activeEnvironmentId: library.activeEnvironmentId,
  environments: library.environments.map((environment) => ({
    ...environment,
    auth: environment.auth || { type: 'none' },
    entries: Array.isArray(environment.entries) ? environment.entries : [],
    web: normalizeWebExecutionRuntimeConfig(environment.web)
  }))
})

const isExecutionEnvironmentLibrary = (value: unknown): value is ExecutionEnvironmentLibrary => {
  if (!value || typeof value !== 'object') return false
  const record = value as Record<string, unknown>
  return typeof record.activeEnvironmentId === 'string' && Array.isArray(record.environments)
}

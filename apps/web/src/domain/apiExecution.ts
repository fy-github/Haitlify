import type { SavedInterfaceCase } from './caseRepository'

export const EXECUTION_ENV_STORAGE_KEY = 'ai-test-platform.execution-env.v1'

export interface ExecutionEnvironment {
  baseUrl: string
  authToken?: string
  extraHeaders?: Record<string, string>
  requestHeadersText?: string
  variables?: Record<string, string>
}

export interface ExecutableRequest {
  method: string
  url: string
  headers: Record<string, string>
  body?: unknown
  expectedStatus: number
  assertions: string[]
}

export interface ProxyExecutionPayload {
  method: string
  url: string
  headers: Record<string, string>
  body?: unknown
}

export const buildExecutableRequest = (
  testCase: SavedInterfaceCase,
  environment: ExecutionEnvironment
): ExecutableRequest => {
  const method = testCase.metadata.method.toUpperCase()
  const variables = environment.variables || {}
  const headers = cleanHeaders({
    ...resolveVariables(testCase.metadata.headers, variables),
    'Content-Type': testCase.metadata.contentType || 'application/json',
    ...environment.extraHeaders,
    ...parseRequestHeadersText(environment.requestHeadersText || '')
  })

  if (environment.authToken) {
    headers.Authorization = `Bearer ${environment.authToken}`
  }

  return {
    method,
    url: buildUrl(
      environment.baseUrl,
      resolveVariableText(testCase.metadata.path, variables),
      resolveVariables(testCase.metadata.pathParams, variables),
      resolveVariables(testCase.metadata.queryParams, variables)
    ),
    headers,
    body: methodAllowsBody(method) ? resolveVariables(testCase.metadata.body, variables) : undefined,
    expectedStatus: testCase.metadata.expectedStatus,
    assertions: testCase.metadata.assertions
  }
}

export const buildCurlCommand = (request: ExecutableRequest): string => {
  const lines = [`curl -X ${request.method} ${shellQuote(request.url)}`]

  for (const [name, value] of Object.entries(request.headers)) {
    lines.push(`  -H ${shellQuote(`${name}: ${value}`)}`)
  }

  if (request.body !== undefined) {
    lines.push(`  --data ${shellQuote(JSON.stringify(request.body))}`)
  }

  return lines.join(' \\\n')
}

export const buildProxyExecutionPayload = (request: ExecutableRequest): ProxyExecutionPayload => ({
  method: request.method,
  url: request.url,
  headers: proxySafeHeaders(request.headers),
  body: request.body
})

export const browserSafeHeaders = (headers: Record<string, string>): Record<string, string> =>
  Object.fromEntries(
    Object.entries(headers).filter(([name]) => !browserForbiddenHeaderNames.has(name.toLowerCase()))
  )

export const proxySafeHeaders = (headers: Record<string, string>): Record<string, string> =>
  Object.fromEntries(
    Object.entries(headers).filter(([name]) => !proxyUnsafeHeaderNames.has(name.toLowerCase()))
  )

export const buildUrl = (
  baseUrl: string,
  path: string,
  pathParams: Record<string, string>,
  queryParams: Record<string, string>
): string => {
  const normalizedBase = baseUrl.replace(/\/+$/, '')
  const isAbsoluteUrl = /^https?:\/\//i.test(path)
  let resolvedPath = isAbsoluteUrl || path.startsWith('/') ? path : `/${path}`

  for (const [name, value] of Object.entries(pathParams)) {
    resolvedPath = resolvedPath.split(`{${name}}`).join(encodeURIComponent(value))
  }

  const query = new URLSearchParams()
  for (const [name, value] of Object.entries(queryParams)) {
    if (value !== '') query.set(name, value)
  }

  const prefix = isAbsoluteUrl ? '' : normalizedBase
  return `${prefix}${resolvedPath}${query.size ? `?${query.toString()}` : ''}`
}

const methodAllowsBody = (method: string): boolean => !['GET', 'HEAD'].includes(method)

const cleanHeaders = (headers: Record<string, string>): Record<string, string> =>
  Object.fromEntries(Object.entries(headers).filter(([, value]) => value !== ''))

const shellQuote = (value: string): string => `'${value.replace(/'/g, "'\\''")}'`

const browserForbiddenHeaderNames = new Set([
  'accept-charset',
  'accept-encoding',
  'access-control-request-headers',
  'access-control-request-method',
  'connection',
  'content-length',
  'cookie',
  'cookie2',
  'date',
  'dnt',
  'expect',
  'host',
  'keep-alive',
  'origin',
  'referer',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
  'via'
])

const proxyUnsafeHeaderNames = new Set([
  'accept-encoding',
  'connection',
  'content-length',
  'host',
  'keep-alive',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade'
])

export const parseRequestHeadersText = (text: string): Record<string, string> => {
  const trimmed = text.trim()
  if (!trimmed) return {}

  const jsonHeaders = parseJsonHeaders(trimmed)
  if (jsonHeaders) return jsonHeaders

  const headers: Record<string, string> = {}
  for (const rawLine of trimmed.split(/\r?\n/)) {
    const line = rawLine.trim().replace(/\\$/, '').trim()
    if (!line || line.startsWith('curl ')) continue

    const header = parseCurlHeaderLine(line) || parsePlainHeaderLine(line)
    if (header) {
      headers[header.name] = header.value
      continue
    }

    const cookie = parseCurlCookieLine(line)
    if (cookie) headers.Cookie = cookie
  }

  return headers
}

const parseJsonHeaders = (text: string): Record<string, string> | undefined => {
  if (!text.startsWith('{')) return undefined

  try {
    const parsed = JSON.parse(text)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return undefined
    return Object.fromEntries(
      Object.entries(parsed as Record<string, unknown>)
        .filter(([, value]) => value !== undefined && value !== null && value !== '')
        .map(([name, value]) => [name, String(value)])
    )
  } catch {
    return undefined
  }
}

const parseCurlHeaderLine = (line: string): { name: string; value: string } | undefined => {
  const match = line.match(/^-H\s+(.+)$/)
  if (!match) return undefined
  return parsePlainHeaderLine(stripQuotes(match[1].trim()))
}

const parseCurlCookieLine = (line: string): string | undefined => {
  const match = line.match(/^(?:-b|--cookie)\s+(.+)$/)
  return match ? stripQuotes(match[1].trim()) : undefined
}

const parsePlainHeaderLine = (line: string): { name: string; value: string } | undefined => {
  const normalized = stripQuotes(line.trim())
  const separatorIndex = normalized.indexOf(':')
  if (separatorIndex <= 0) return undefined
  return {
    name: normalized.slice(0, separatorIndex).trim(),
    value: normalized.slice(separatorIndex + 1).trim()
  }
}

const stripQuotes = (value: string): string => {
  const trimmed = value.trim()
  if (
    (trimmed.startsWith("'") && trimmed.endsWith("'")) ||
    (trimmed.startsWith('"') && trimmed.endsWith('"'))
  ) {
    return trimmed.slice(1, -1)
  }
  return trimmed
}

export const resolveVariables = <T>(value: T, variables: Record<string, string>): T => {
  if (typeof value === 'string') return resolveVariableText(value, variables) as T
  if (Array.isArray(value)) return value.map((item) => resolveVariables(item, variables)) as T
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>).map(([key, item]) => [key, resolveVariables(item, variables)])
    ) as T
  }
  return value
}

export const resolveVariableText = (value: string, variables: Record<string, string>): string =>
  value.replace(/\{\{\s*([^}]+?)\s*\}\}/g, (_, name: string) => variables[name.trim()] ?? `{{${name}}}`)

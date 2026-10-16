const HTTP_PROTOCOLS = new Set(['http:', 'https:'])
const OPTIONAL_RESOURCE_TYPES = new Set(['image', 'font', 'media', 'manifest', 'favicon', 'stylesheet'])
const PAGE_ORIGIN_RESOURCE_TYPES = new Set(['document', 'script', ...OPTIONAL_RESOURCE_TYPES])

const toList = (value) => {
  if (Array.isArray(value)) return value
  if (value === undefined || value === null) return []
  if (typeof value === 'string' && value.includes(',')) return value.split(',').map((item) => item.trim()).filter(Boolean)
  return [value]
}

const parseHttpUrl = (value) => {
  let parsed
  try {
    parsed = new URL(String(value || ''))
  } catch {
    return null
  }
  if (!HTTP_PROTOCOLS.has(parsed.protocol)) return null
  if (parsed.username || parsed.password) return null
  return parsed
}

const normalizePath = (value) => {
  const text = String(value || '').trim()
  if (!text || text === '/') return '/'
  return `/${text.replace(/^\/+/, '').replace(/\/+$/, '')}`
}

const normalizeRequestUrl = (value) => {
  const parsed = parseHttpUrl(value)
  if (!parsed) return ''
  return `${parsed.protocol}//${parsed.host}${normalizePath(parsed.pathname)}${parsed.search}`
}

const sanitizeText = (value) => String(value || '')
  .replace(/https?:\/\/[^\s'"`]+/gi, (url) => sanitizeUrl(url))
  .replace(/(authorization|cookie|token|password|secret|apiKey|apikey)\s*[:=]\s*(?:Bearer\s+)?([^\s,;]+)/gi, '$1=[redacted]')
  .replace(/Bearer\s+[^\s,;]+/gi, '[redacted]')
  .replace(/responseBody\s*[:=]\s*(\{[^{}]*\}|\[[^\]]*\]|[^\s,;]+)/gi, 'responseBody=[redacted]')

const BLOCKED_DETAIL_MAX_LENGTH = 160

const sanitizeBlockedDetail = (value, fallback = 'origin/path not allowed') => {
  const text = sanitizeText(value || fallback)
    .replace(/\b(query|token|password|locator|selector)\b\s*[:=]\s*(?:Bearer\s+)?([^\s,;]+)/gi, '$1=[redacted]')
    .replace(/\b(query|token|password|locator|selector)\b/gi, '[redacted]')
    .replace(/\s+/g, ' ')
    .trim()
  if (!text) return fallback
  if (text.length <= BLOCKED_DETAIL_MAX_LENGTH) return text
  return `${text.slice(0, BLOCKED_DETAIL_MAX_LENGTH - 1).trimEnd()}…`
}

export const normalizeOrigin = (value) => {
  const parsed = parseHttpUrl(value)
  if (!parsed) return ''
  if ((parsed.pathname && normalizePath(parsed.pathname) !== '/') || parsed.search || parsed.hash) return ''
  return `${parsed.protocol}//${parsed.host}`.toLowerCase()
}

export const normalizeAllowedOrigins = (values) => Array.from(new Set(
  toList(values).map(normalizeOrigin).filter(Boolean)
))

export const normalizeAllowedRequestUrls = (values) => Array.from(new Set(
  toList(values)
    .map(normalizeRequestUrl)
    .filter(Boolean)
))

export const normalizeAllowedRequestPaths = (values) => Array.from(new Set(
  toList(values)
    .map(sanitizePath)
    .filter(Boolean)
))

export const isAllowedRequestUrl = (value, policy = {}, resourceType = '', method = 'GET') => {
  const parsed = parseHttpUrl(value)
  if (!parsed) return false
  const allowedRequestUrls = new Set(normalizeAllowedRequestUrls(policy.allowedRequestUrls || []))
  const allowedRequestPaths = new Set(normalizeAllowedRequestPaths(policy.allowedRequestPaths || []))
  const requestUrl = normalizeRequestUrl(value)
  if (allowedRequestUrls.has(requestUrl)) return true
  const requestPath = normalizePath(parsed.pathname)
  const pageOrigins = new Set(normalizeAllowedOrigins(policy.pageOrigins || policy.allowedOrigins || []))
  const authOrigins = new Set(normalizeAllowedOrigins(policy.authOrigins || []))
  const boundOrigins = new Set([...pageOrigins, ...authOrigins])
  const requestOrigin = `${parsed.protocol}//${parsed.host}`.toLowerCase()
  const explicitRequestOrigins = new Set(normalizeAllowedOrigins(policy.allowedRequestOrigins || []))
  const normalizedMethod = String(method || 'GET').toUpperCase()
  if (explicitRequestOrigins.has(requestOrigin)) {
    if (PAGE_ORIGIN_RESOURCE_TYPES.has(String(resourceType || '').toLowerCase())) return true
    if ((resourceType === 'xhr' || resourceType === 'fetch') && ['GET', 'HEAD', 'OPTIONS'].includes(normalizedMethod)) return true
  }
  if (boundOrigins.size > 0 && boundOrigins.has(requestOrigin) && allowedRequestPaths.has(requestPath)) return true
  if (!pageOrigins.has(requestOrigin)) return false
  return PAGE_ORIGIN_RESOURCE_TYPES.has(String(resourceType || '').toLowerCase())
}

export const classifyRequestCriticality = (request = {}, context = {}) => {
  const rawResourceType = typeof request.resourceType === 'function' ? request.resourceType() : request.resourceType
  const rawMethod = typeof request.method === 'function' ? request.method() : request.method
  const rawUrl = typeof request.url === 'function' ? request.url() : request.url
  const resourceType = String(rawResourceType || '').toLowerCase()
  const parsed = parseHttpUrl(rawUrl)
  if (!parsed) return 'unknown'
  if (OPTIONAL_RESOURCE_TYPES.has(resourceType)) return 'optional'
  if (resourceType === 'document') return 'critical'

  const requestOrigin = `${parsed.protocol}//${parsed.host}`.toLowerCase()
  const explicitRequestOrigins = new Set(normalizeAllowedOrigins(context.allowedRequestOrigins || []))
  const requestPath = normalizePath(parsed.pathname)
  const targetOrigin = normalizeOrigin(context.targetUrl)
  const loginRequestUrl = normalizeRequestUrl(context.loginUrl)
  const allowedRequestUrls = new Set(normalizeAllowedRequestUrls(context.allowedRequestUrls || []))
  const allowedRequestPaths = new Set(normalizeAllowedRequestPaths(context.allowedRequestPaths || []))
  const authOrigins = new Set(normalizeAllowedOrigins(context.authOrigins || []))
  const pageOrigins = new Set(normalizeAllowedOrigins(context.pageOrigins || context.allowedOrigins || []))
  const boundOrigins = new Set([...pageOrigins, ...authOrigins])
  const observedBusinessSignals = context.observedBusinessSignals instanceof Set
    ? context.observedBusinessSignals
    : new Set(toList(context.observedBusinessSignals || []))

  const requestUrl = normalizeRequestUrl(rawUrl)
  if (allowedRequestUrls.has(requestUrl)) return 'auth_dependency'
  if (boundOrigins.size > 0 && boundOrigins.has(requestOrigin) && allowedRequestPaths.has(requestPath)) return 'auth_dependency'
  if (loginRequestUrl && requestUrl === loginRequestUrl) return 'auth_dependency'
  if (explicitRequestOrigins.has(requestOrigin)
      && (OPTIONAL_RESOURCE_TYPES.has(resourceType) || (['xhr', 'fetch'].includes(resourceType) && ['GET', 'HEAD', 'OPTIONS'].includes(String(rawMethod || 'GET').toUpperCase())))) {
    return 'auth_dependency'
  }

  if (observedBusinessSignals.has(requestPath)) return 'critical'
  if (resourceType === 'script' && !boundOrigins.has(requestOrigin)) return 'optional'
  if (requestOrigin === targetOrigin) {
    if (resourceType === 'script') return 'critical'
    if (resourceType === 'xhr' || resourceType === 'fetch') {
      if (requestPath.startsWith('/api/') || requestPath.startsWith('/v')) return 'critical'
      return 'unknown'
    }
  }
  return 'unknown'
}

export const sanitizeBlockedDiagnostic = (entry = {}) => {
  if (typeof entry === 'string') return sanitizeBlockedDetail(entry)
  const safe = {}
  const safeUrl = sanitizeUrl(entry.url || `${entry.origin || ''}${entry.path || ''}`)
  if (safeUrl) safe.url = safeUrl
  const origin = sanitizeOrigin(entry.origin)
  if (origin) safe.origin = origin
  const path = sanitizePath(entry.path)
  if (path) safe.path = path
  if (entry.resourceType) safe.resourceType = String(entry.resourceType)
  if (entry.criticality) safe.criticality = String(entry.criticality)
  if (entry.failureType) safe.failureType = String(entry.failureType)
  const detail = sanitizeBlockedDetail(entry.detail || entry.message || entry.error || '')
  if (detail) safe.detail = detail
  return safe
}

export const sanitizeBlockedRequestDiagnostic = (entry = {}) => {
  const safe = {}
  const origin = sanitizeOrigin(entry.origin)
  if (origin) safe.origin = origin
  const path = sanitizePath(entry.path)
  if (path) safe.path = path
  if (entry.resourceType) safe.resourceType = String(entry.resourceType)
  if (entry.criticality) safe.criticality = String(entry.criticality)
  if (entry.failureType) safe.failureType = String(entry.failureType)
  return safe
}

export const createPageNetworkPolicy = (policy = {}) => {
  const normalizedPageOrigins = normalizeAllowedOrigins(policy.pageOrigins || policy.allowedOrigins || [])
  const normalizedAuthOrigins = normalizeAllowedOrigins(policy.authOrigins || [])
  const normalizedRequestUrls = normalizeAllowedRequestUrls(policy.allowedRequestUrls || [])
  const normalizedRequestPaths = normalizeAllowedRequestPaths(policy.allowedRequestPaths || [])
  const normalizedRequestOrigins = normalizeAllowedOrigins(policy.allowedRequestOrigins || [])
  // 放行策略模式：'blocklist'（默认不拦截，仅拦截名单内请求）/ 'allowlist'（仅放行名单内请求，其余拦截）。
  // 默认值保持 'allowlist'，未显式传模式的调用方行为不变；产品侧前端默认发送 'blocklist'。
  const networkPolicyMode = policy.networkPolicyMode === 'blocklist' ? 'blocklist' : 'allowlist'
  // 黑名单条目：Origin 条目拦该来源全部请求；带路径的完整 URL 条目精确拦截该请求。
  const blockOriginSet = new Set()
  const blockUrlSet = new Set()
  for (const raw of toList(policy.allowedRequestOrigins || [])) {
    const origin = normalizeOrigin(raw)
    if (origin) {
      blockOriginSet.add(origin)
      continue
    }
    const exactUrl = normalizeRequestUrl(raw)
    if (exactUrl) blockUrlSet.add(exactUrl)
  }
  const isUserBlockedUrl = (value) => {
    const parsed = parseHttpUrl(value)
    if (!parsed) return false
    if (blockOriginSet.has(`${parsed.protocol}//${parsed.host}`.toLowerCase())) return true
    return blockUrlSet.has(normalizeRequestUrl(value))
  }
  const policyContext = {
    ...policy,
    pageOrigins: normalizedPageOrigins,
    allowedOrigins: normalizedPageOrigins,
    authOrigins: normalizedAuthOrigins,
    allowedRequestUrls: normalizedRequestUrls,
    allowedRequestPaths: normalizedRequestPaths
    ,allowedRequestOrigins: normalizedRequestOrigins
  }

  return {
    pageOrigins: normalizedPageOrigins,
    authOrigins: normalizedAuthOrigins,
    allowedOrigins: normalizedPageOrigins,
    allowedRequestUrls: normalizedRequestUrls,
    allowedRequestPaths: normalizedRequestPaths,
    allowedRequestOrigins: normalizedRequestOrigins,
    networkPolicyMode,
    isUserBlockedUrl,
    isAllowedRequestUrl: (value, resourceType, method) => isAllowedRequestUrl(value, policyContext, resourceType, method),
    classifyRequestCriticality: (request, context = {}) => classifyRequestCriticality(request, {
      ...policyContext,
      ...context
    }),
    sanitizeBlockedDiagnostic,
    sanitizeBlockedRequestDiagnostic,
    async apply(context, page, onBlocked, assertAllowedUrl, routeHandlerRef) {
      if (!context || typeof context.route !== 'function') return
      const handler = async (route) => {
        const request = route.request()
        const requestUrl = request.url()
        const resourceType = request.resourceType?.() || 'unknown'
        const method = request.method?.() || 'GET'
        const parsed = parseHttpUrl(requestUrl)
        if (!parsed) {
          await route.continue()
          return
        }
        if (networkPolicyMode === 'blocklist') {
          if (!isUserBlockedUrl(requestUrl)) {
            await route.continue()
            return
          }
          onBlocked?.(sanitizeBlockedRequestDiagnostic({
            url: requestUrl,
            origin: `${parsed.protocol}//${parsed.host}`.toLowerCase(),
            path: normalizePath(parsed.pathname),
            resourceType,
            criticality: classifyRequestCriticality(request, policyContext),
            failureType: 'network_blocked'
          }))
          await route.abort('blockedbyclient')
          return
        }
        const allowed = isAllowedRequestUrl(requestUrl, policyContext, resourceType, method)
        if (allowed) {
          await route.continue()
          return
        }
        if (assertAllowedUrl) {
          try {
            await assertAllowedUrl(requestUrl)
          } catch (error) {
            const reason = error instanceof Error
              ? error.message || error.name || String(error)
              : error?.message || error?.error || String(error || '')
            onBlocked?.(sanitizeBlockedDiagnostic({
              url: requestUrl,
              origin: `${parsed.protocol}//${parsed.host}`.toLowerCase(),
              path: normalizePath(parsed.pathname),
              resourceType,
              criticality: policy.criticalityAware === false ? 'unknown' : classifyRequestCriticality(request, policyContext),
              failureType: 'network_blocked',
              detail: reason
            }))
            await route.abort('blockedbyclient')
            return
          }
        }
        onBlocked?.(sanitizeBlockedRequestDiagnostic({
          url: requestUrl,
          origin: `${parsed.protocol}//${parsed.host}`.toLowerCase(),
          path: normalizePath(parsed.pathname),
          resourceType,
          criticality: policy.criticalityAware === false ? 'unknown' : classifyRequestCriticality(request, policyContext),
          failureType: 'network_blocked'
        }))
        await route.abort('blockedbyclient')
        return
      }
      if (routeHandlerRef && typeof routeHandlerRef === 'object') routeHandlerRef.current = handler
      await context.route('**/*', handler)
      return page
    }
  }
}

export const sanitizeOrigin = (value) => {
  const parsed = parseAnyHttpUrl(value)
  if (!parsed) return ''
  return `${parsed.protocol}//${parsed.host}`.toLowerCase()
}

export const sanitizePath = (value) => {
  const text = String(value || '').replace(/[?#].*$/, '')
  const path = normalizePath(text)
  return path === '/' ? path : path
}

const sanitizeUrl = (value) => {
  const parsed = parseAnyHttpUrl(value)
  if (!parsed) return String(value || '').replace(/[?#].*$/, '')
  return `${parsed.protocol}//${parsed.host}${normalizePath(parsed.pathname)}`
}

const parseAnyHttpUrl = (value) => {
  try {
    const parsed = new URL(String(value || ''))
    return HTTP_PROTOCOLS.has(parsed.protocol) ? parsed : null
  } catch {
    return null
  }
}

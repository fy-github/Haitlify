import { randomUUID } from 'node:crypto'

const sessions = new Map()
const handoffs = new Map()
const DEFAULT_TTL_MS = 10 * 60 * 1000
const DEFAULT_HANDOFF_TTL_MS = 5 * 60 * 1000

const normalizePageKey = (value) => {
  try {
    const parsed = new URL(String(value || ''))
    if (!['http:', 'https:'].includes(parsed.protocol)) return ''
    const origin = `${parsed.protocol}//${parsed.host}`.toLowerCase()
    const pathname = normalizePageUrlPath(parsed.pathname)
    const hashRoute = normalizeHashRoute(parsed.hash)
    return `${origin}${pathname}${hashRoute}`
  } catch {
    return ''
  }
}

const matchesKey = (session, key = {}) =>
  (key.projectId === undefined ? session.projectId === undefined : session.projectId === key.projectId)
  && (key.environmentId === undefined ? session.environmentId === '' : session.environmentId === String(key.environmentId || ''))
  && (key.ownerId === undefined ? session.ownerId === undefined : session.ownerId === String(key.ownerId || ''))
  && (key.sessionKey === undefined || session.sessionKey === String(key.sessionKey || ''))
  && (key.browser === undefined || session.browser === key.browser)
  && (key.pageKey ? session.pageKey === key.pageKey : true)
  && (key.pageUrlPath ? session.pageUrlPath === key.pageUrlPath : true)
  && (key.pageFingerprint ? session.pageFingerprint === key.pageFingerprint : true)

export const createSession = (input, ttlMs = DEFAULT_TTL_MS, now = Date.now()) => {
  const session = {
    sessionId: `session-${randomUUID()}`,
    sessionKey: String(input.sessionKey || ''),
    projectId: input.projectId,
    ownerId: input.ownerId === undefined ? undefined : String(input.ownerId || ''),
    environmentId: String(input.environmentId || ''),
    browser: String(input.browser || 'chromium'),
    state: 'CREATED',
    lease: undefined,
    browserInstance: input.browserInstance,
    context: input.context,
    page: input.page,
    pageKey: String(input.pageKey || '').trim() || normalizePageKey(input.pageUrl || input.url || ''),
    pageUrlPath: normalizePageUrlPath(input.pageUrlPath || input.pageUrl || input.url),
    pageFingerprint: String(input.pageFingerprint || '').trim(),
    observedOrigins: [...new Set(input.observedOrigins || [])],
    createdAt: now,
    expiresAt: now + Math.max(1, Number(ttlMs) || DEFAULT_TTL_MS),
    lastUsedAt: now
  }
  sessions.set(session.sessionId, session)
  return session
}

export const createHandoff = (input, ttlMs = DEFAULT_HANDOFF_TTL_MS, now = Date.now()) => {
  const handoff = {
    handoffId: `handoff-${randomUUID()}`,
    sessionId: String(input.sessionId || ''),
    projectId: input.projectId,
    environmentId: String(input.environmentId || ''),
    browser: String(input.browser || 'chromium'),
    status: String(input.status || 'manual_required'),
    createdAt: now,
    expiresAt: now + Math.max(1, Number(ttlMs) || DEFAULT_HANDOFF_TTL_MS),
    lastUsedAt: now
  }
  const runId = String(input.runId || '').trim()
  if (runId) handoff.runId = runId
  const sourcePointId = String(input.sourcePointId || '').trim()
  if (sourcePointId) handoff.sourcePointId = sourcePointId
  const pageKey = String(input.pageKey || '').trim() || normalizePageKey(input.pageUrlPath || input.pageUrl || input.url)
  if (pageKey) handoff.pageKey = pageKey
  const pageUrlPath = normalizePageUrlPath(input.pageUrlPath || input.pageUrl || input.url)
  if (pageUrlPath) handoff.pageUrlPath = pageUrlPath
  const pageFingerprint = String(input.pageFingerprint || '').trim()
  if (pageFingerprint) handoff.pageFingerprint = pageFingerprint
  handoffs.set(handoff.handoffId, handoff)
  return handoff
}

const normalizePageUrlPath = (value) => {
  const raw = String(value || '').trim()
  if (!raw) return ''
  try {
    const parsed = new URL(raw)
    const pathname = parsed.pathname || '/'
    const hashRoute = normalizeHashRoute(parsed.hash)
    return `${pathname}${hashRoute}` || '/'
  } catch {
    const [pathPart, hashPart = ''] = raw.split('#', 2)
    const pathname = pathPart.replace(/[?#].*$/, '')
    const hashRoute = normalizeHashRoute(hashPart ? `#${hashPart}` : '')
    if (pathname && hashRoute) return `${pathname}${hashRoute}`
    if (pathname) return pathname
    return hashRoute || ''
  }
}

const normalizeHashRoute = (value) => {
  const raw = String(value || '').trim()
  if (!raw) return ''
  const fragment = raw.startsWith('#') ? raw.slice(1) : raw
  const route = fragment.replace(/^!/, '').split(/[?#]/, 1)[0]
  return route.startsWith('/') ? `#${route}` : ''
}

export const getSession = (sessionId, key, now = Date.now()) => {
  const session = sessions.get(String(sessionId || ''))
  if (!session) return undefined
  if (session.expiresAt <= now) {
    destroySession(session.sessionId)
    return undefined
  }
  if (!matchesKey(session, key)) return undefined
  session.lastUsedAt = now
  return session
}

export const acquireSessionLease = (sessionId, key = {}, operationId = randomUUID(), now = Date.now()) => {
  const session = getSession(sessionId, key, now)
  if (!session) return { ok: false, reason: 'missing' }
  if (session.lease && session.lease.operationId !== String(operationId)) {
    return { ok: false, reason: 'busy' }
  }
  session.lease = { operationId: String(operationId), acquiredAt: now }
  session.state = 'IN_USE'
  session.lastUsedAt = now
  return { ok: true, session, operationId: String(operationId) }
}

export const releaseSessionLease = (sessionId, operationId, nextState = 'READY_FOR_COMPILE') => {
  const session = sessions.get(String(sessionId || ''))
  if (!session || !session.lease || session.lease.operationId !== String(operationId || '')) return false
  session.lease = undefined
  session.state = nextState
  session.lastUsedAt = Date.now()
  return true
}

export const updateSessionPage = (sessionId, pageUrl, pageKey, pageFingerprint) => {
  const session = sessions.get(String(sessionId || ''))
  if (!session) return false
  if (pageUrl !== undefined) session.pageUrl = String(pageUrl || '')
  if (pageKey !== undefined) session.pageKey = String(pageKey || '')
  if (pageFingerprint !== undefined) session.pageFingerprint = String(pageFingerprint || '')
  session.lastUsedAt = Date.now()
  return true
}

export const sessionState = (sessionId, key, now = Date.now()) => {
  const session = sessions.get(String(sessionId || ''))
  if (!session) return 'missing'
  if (session.expiresAt <= now) return 'expired'
  return matchesKey(session, key) ? 'active' : 'mismatch'
}

export const getHandoff = (handoffId, key, now = Date.now()) => {
  const handoff = handoffs.get(String(handoffId || ''))
  if (!handoff) return undefined
  if (handoff.expiresAt <= now || !matchesKey(handoff, key)) {
    destroyHandoff(handoff.handoffId)
    return undefined
  }
  handoff.lastUsedAt = now
  return handoff
}

export const handoffState = (handoffId, key, now = Date.now()) => {
  const handoff = handoffs.get(String(handoffId || ''))
  if (!handoff) return 'missing'
  if (handoff.expiresAt <= now) return 'expired'
  return matchesKey(handoff, key) ? 'active' : 'mismatch'
}

export const touchSession = (sessionId, key, ttlMs = DEFAULT_TTL_MS, now = Date.now()) => {
  const session = getSession(sessionId, key, now)
  if (!session) return false
  session.expiresAt = now + Math.max(1, Number(ttlMs) || DEFAULT_TTL_MS)
  return true
}

export const destroySession = (sessionId) => {
  const session = sessions.get(String(sessionId || ''))
  if (!session) return false
  sessions.delete(session.sessionId)
  session.state = 'DESTROYED'
  session.lease = undefined
  const page = session.page
  const context = session.context
  // 取证/接管路径创建的会话可能没有 browserInstance（只记了 context/page）：
  // 只关 page/context 会让 Chromium 进程常驻——销毁必须沿 context/page 兜底取到浏览器句柄
  // 再关（用户核查 2026-10-14：实测 12 个会话被销毁、51 个浏览器实例零减少）。
  const browserHandle = session.browserInstance
    || (typeof context?.browser === 'function' ? context.browser() : undefined)
    || (typeof page?.context === 'function' ? page.context()?.browser?.() : undefined)
  void page?.close?.().catch?.(() => {})
  void context?.close?.().catch?.(() => {})
  void browserHandle?.close?.().catch?.(() => {})
  return true
}

export const destroyHandoff = (handoffId) => {
  const handoff = handoffs.get(String(handoffId || ''))
  if (!handoff) return false
  handoffs.delete(handoff.handoffId)
  return true
}

export const destroyHandoffSession = (handoffId) => {
  const handoff = handoffs.get(String(handoffId || ''))
  if (!handoff) return false
  handoffs.delete(handoff.handoffId)
  if (handoff.sessionId) destroySession(handoff.sessionId)
  return true
}

export const cleanupExpiredSessions = (now = Date.now()) => {
  let removed = 0
  for (const session of sessions.values()) {
    if (session.expiresAt <= now && destroySession(session.sessionId)) removed += 1
  }
  return removed
}

export const cleanupExpiredHandoffs = (now = Date.now()) => {
  let removed = 0
  for (const handoff of handoffs.values()) {
    if (handoff.expiresAt <= now && destroyHandoffSession(handoff.handoffId)) removed += 1
  }
  return removed
}

export const sessionCount = () => sessions.size

/** 会话元数据快照（阶段 7 增量 3）：只暴露列表展示需要的字段，绝不含 page/context/browserInstance 或任何凭据。 */
export const listSessions = (now = Date.now()) => [...sessions.values()]
  .filter((session) => session.expiresAt > now)
  .map((session) => ({
    sessionId: session.sessionId,
    projectId: session.projectId,
    environmentId: session.environmentId,
    browser: session.browser,
    state: session.state,
    leased: Boolean(session.lease),
    pageKey: session.pageKey,
    createdAt: session.createdAt,
    expiresAt: session.expiresAt,
    lastUsedAt: session.lastUsedAt
  }))

export const handoffCount = () => handoffs.size

export const clearSessions = () => {
  for (const session of [...sessions.values()]) destroySession(session.sessionId)
  for (const handoff of [...handoffs.values()]) destroyHandoffSession(handoff.handoffId)
}

export const startSessionCleanup = (intervalMs = 60_000, now = Date.now) => {
  const timer = setInterval(() => {
    cleanupExpiredSessions(now())
    cleanupExpiredHandoffs(now())
  }, Math.max(5_000, Number(intervalMs) || 60_000))
  timer.unref?.()
  return () => clearInterval(timer)
}

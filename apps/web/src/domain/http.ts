// Shared HTTP layer for authenticated backend calls. Every backend repository resolves its
// `fetch` implementation through this module so that session cookies, the CSRF token, and the
// "session expired" redirect are applied consistently instead of being re-implemented per repo.

export type FetchLike = (input: string, init?: RequestInit) => Promise<Response>

let onUnauthorized: (() => void) | null = null
let authSessionEpoch = 0

// Advance at authentication boundaries, not when a callback happens to change identity.
// Capture the epoch before sending a request so a previous session cannot expire its successor.
export const advanceAuthSessionEpoch = (): void => { authSessionEpoch += 1 }
export const getAuthSessionEpoch = (): number => authSessionEpoch

// Registers the callback fired whenever a backend call returns 401 (missing/expired session).
// App.vue registers a handler that flips the app back to the login screen.
export const setUnauthorizedHandler = (handler: (() => void) | null): void => {
  onUnauthorized = handler
}

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS'])

const readCsrfToken = (): string | undefined => {
  if (typeof document === 'undefined') return undefined
  const entries = document.cookie.split(';')
  for (const entry of entries) {
    const trimmed = entry.trim()
    if (!trimmed.startsWith('XSRF-TOKEN=')) continue
    const raw = trimmed.slice('XSRF-TOKEN='.length)
    if (!raw) return undefined
    try {
      return decodeURIComponent(raw)
    } catch {
      return raw
    }
  }
  return undefined
}

export const authedFetch: FetchLike = async (input, init) => {
  const requestEpoch = authSessionEpoch
  const method = (init?.method ?? 'GET').toUpperCase()
  const headers = new Headers(init?.headers)

  if (!SAFE_METHODS.has(method)) {
    const csrfToken = readCsrfToken()
    if (csrfToken) {
      headers.set('X-XSRF-TOKEN', csrfToken)
    }
  }

  const response = await fetch(input, {
    ...init,
    headers,
    credentials: 'same-origin'
  })

  if (response.status === 401 && !init?.signal?.aborted && requestEpoch === authSessionEpoch && onUnauthorized) {
    onUnauthorized()
  }

  return response
}

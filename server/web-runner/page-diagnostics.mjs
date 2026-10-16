const registrations = new WeakMap()

export const registerPageDiagnostics = (page, evidence) => {
  if (!page || typeof page.on !== 'function') return () => {}
  const previous = registrations.get(page)
  if (previous) {
    previous.evidence = evidence
    return previous.cleanup
  }

  const state = { evidence, active: true }
  const append = (bucket, diagnostic) => {
    if (!state.active || !state.evidence) return
    state.evidence[bucket]?.push(diagnostic)
    state.evidence.job?.appendDiagnostic?.(diagnostic)
  }
  const handlers = {
    console: (message) => {
      if (message.type() !== 'error') return
      const location = message.location?.()
      append('consoleErrors', [
        message.text(),
        location?.url ? `@ ${location.url}${location.lineNumber ? `:${location.lineNumber}` : ''}` : ''
      ].filter(Boolean).join(' '))
    },
    pageerror: (error) => append('pageErrors', error instanceof Error ? `${error.message}\n${error.stack || ''}`.trim() : String(error)),
    requestfailed: (request) => {
      const failure = request.failure?.()
      append('failedRequests', `${request.method()} ${request.url()}${failure?.errorText ? ` -> ${failure.errorText}` : ''}`)
    }
  }
  for (const [event, handler] of Object.entries(handlers)) page.on(event, handler)
  const cleanup = () => {
    if (!state.active) return
    state.active = false
    const off = typeof page.off === 'function' ? page.off.bind(page) : page.removeListener?.bind(page)
    if (off) for (const [event, handler] of Object.entries(handlers)) off(event, handler)
    registrations.delete(page)
  }
  state.cleanup = cleanup
  registrations.set(page, state)
  return cleanup
}

export const clearPageDiagnostics = (page) => registrations.get(page)?.cleanup?.()

export const __testables = { registrations }

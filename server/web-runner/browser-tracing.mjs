import { randomUUID } from 'node:crypto'

const TRACEABLE_MODES = new Set(['local', 'fixture'])
const traces = new Map()
const TRACE_TTL_MS = 10 * 60 * 1000

export const shouldEnableTracing = (input = {}) =>
  input.tracingEnabled === true && TRACEABLE_MODES.has(String(input.diagnosticMode || '').trim().toLowerCase())

export const startBoundedTracing = async (context, input = {}) => {
  if (!shouldEnableTracing(input) || !context?.tracing?.start) return null
  const traceId = `trace-${randomUUID()}`
  const trace = { traceId, started: true, stopped: false, context, expiresAt: Date.now() + TRACE_TTL_MS }
  traces.set(traceId, trace)
  await context.tracing.start({ screenshots: false, snapshots: false, sources: false })
  return trace
}

export const stopBoundedTracing = async (trace, reason = 'completed', context = null) => {
  if (!trace || trace.stopped) return trace
  trace.stopped = true
  trace.reason = String(reason || 'completed').slice(0, 64)
  const traceContext = context || trace.context
  // Do not write a trace archive: Playwright traces can contain network metadata.
  // Keep only the opaque id and sanitized lifecycle status for diagnostics.
  if (traceContext?.tracing?.stop) await traceContext.tracing.stop()
  delete trace.context
  trace.status = trace.reason
  return trace
}

export const traceMetadata = (traceId, now = Date.now()) => {
  const trace = traces.get(String(traceId || ''))
  if (!trace || trace.expiresAt <= now) {
    if (trace) traces.delete(trace.traceId)
    return null
  }
  return { traceId: trace.traceId, status: trace.status || (trace.stopped ? trace.reason : 'running') }
}

export const cleanupExpiredTraces = async (now = Date.now()) => {
  let removed = 0
  for (const [traceId, trace] of traces) {
    if (trace.expiresAt <= now) {
      traces.delete(traceId)
      removed += 1
    }
  }
  return removed
}

import test from 'node:test'
import assert from 'node:assert/strict'
import { shouldEnableTracing, startBoundedTracing, stopBoundedTracing, traceMetadata } from './browser-tracing.mjs'

test('enables tracing only for local or fixture diagnostic modes with explicit flag', () => {
  assert.equal(shouldEnableTracing({ diagnosticMode: 'local', tracingEnabled: true }), true)
  assert.equal(shouldEnableTracing({ diagnosticMode: 'fixture', tracingEnabled: true }), true)
  assert.equal(shouldEnableTracing({ diagnosticMode: 'production', tracingEnabled: true }), false)
  assert.equal(shouldEnableTracing({ diagnosticMode: 'local', tracingEnabled: false }), false)
})

test('starts and stops one bounded trace without screenshots or snapshots', async () => {
  const calls = []
  const context = { tracing: {
    start: async (options) => calls.push(['start', options]),
    stop: async (options) => calls.push(['stop', options])
  } }
  const trace = await startBoundedTracing(context, { diagnosticMode: 'local', tracingEnabled: true })
  assert.match(trace.traceId, /^trace-/)
  await stopBoundedTracing(trace, 'blocked')
  assert.equal(calls[0][0], 'start')
  assert.deepEqual(calls[0][1], { screenshots: false, snapshots: false, sources: false })
  assert.equal(calls[1][0], 'stop')
  assert.equal(trace.reason, 'blocked')
  assert.deepEqual(traceMetadata(trace.traceId), { traceId: trace.traceId, status: 'blocked' })
})

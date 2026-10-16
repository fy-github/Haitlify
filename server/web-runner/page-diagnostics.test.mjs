import test from 'node:test'
import assert from 'node:assert/strict'
import { registerPageDiagnostics } from './page-diagnostics.mjs'

test('page diagnostics keeps one listener set and replaces the evidence sink', () => {
  const listeners = new Map()
  const page = {
    on(event, handler) { listeners.set(event, [...(listeners.get(event) || []), handler]) },
    off(event, handler) { listeners.set(event, (listeners.get(event) || []).filter((item) => item !== handler)) },
    listenerCount(event) { return (listeners.get(event) || []).length }
  }
  const first = { consoleErrors: [], pageErrors: [], failedRequests: [], job: { appendDiagnostic() {} } }
  const second = { consoleErrors: [], pageErrors: [], failedRequests: [], job: { appendDiagnostic() {} } }
  const cleanup = registerPageDiagnostics(page, first)
  const sameCleanup = registerPageDiagnostics(page, second)
  assert.equal(sameCleanup, cleanup)
  assert.equal(page.listenerCount('console'), 1)
  assert.equal(page.listenerCount('pageerror'), 1)
  assert.equal(page.listenerCount('requestfailed'), 1)
  listeners.get('console')[0]({ type: () => 'error', text: () => 'boom', location: () => ({}) })
  assert.deepEqual(first.consoleErrors, [])
  assert.deepEqual(second.consoleErrors, ['boom'])
  cleanup()
  assert.equal(page.listenerCount('console'), 0)
  assert.equal(page.listenerCount('pageerror'), 0)
  assert.equal(page.listenerCount('requestfailed'), 0)
})

import { expect, it } from 'vitest'
// @ts-expect-error Test-only Node API is outside the browser type set.
import { readFileSync } from 'node:fs'

const app = readFileSync(new URL('./App.vue', import.meta.url), 'utf8') as string
it('keeps only Web terminal notices below modal surfaces and out of pointer hit testing', () => {
  const start = app.indexOf(':global(.el-message.web-run-terminal-feedback) {')
  expect(start).toBeGreaterThanOrEqual(0)
  const block = app.slice(start, app.indexOf('}', start) + 1)
  expect(block).toContain('z-index: 1999 !important;')
  expect(block).toContain('pointer-events: none;')
})

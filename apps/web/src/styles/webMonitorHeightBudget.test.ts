import { describe, expect, it } from 'vitest'
// @ts-expect-error Test-only Node API is outside the browser type set.
import { readFileSync } from 'node:fs'

const css = readFileSync(new URL('./studio-canvas.css', import.meta.url), 'utf8') as string
const block = (selector: string) => {
  const start = css.indexOf(`${selector} {`)
  expect(start, selector).toBeGreaterThanOrEqual(0)
  return css.slice(start, css.indexOf('}', start) + 1)
}

describe('compact Web monitor height budget', () => {
  it('lets the mounted monitor contribute natural height to its existing outer scrollport', () => {
    const monitor = block('.web-automation-workspace.is-compact > .web-run-monitor-view')
    expect(monitor).toContain('height: auto;')
    expect(monitor).toContain('min-height: 100%;')
    expect(block('.web-automation-workspace.is-compact')).toContain('overflow-y: auto;')
  })

  it('reserves a usable panel scrollport instead of shrinking queue rows under durable notices', () => {
    expect(block('.web-automation-workspace.is-compact .web-ed-monitor__grid')).toContain('min-height: 280px;')
  })
})

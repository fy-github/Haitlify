import { describe, expect, it } from 'vitest'
// @ts-expect-error Test-only Node API is outside the browser type set.
import { readFileSync } from 'node:fs'

const css = readFileSync(new URL('./settings-quality.css', import.meta.url), 'utf8') as string
const mobile = css.slice(css.indexOf('@media (max-width: 760px)'))
const rule = (selector: string) => {
  const start = mobile.indexOf(`${selector} {`)
  expect(start, selector).toBeGreaterThanOrEqual(0)
  return mobile.slice(start, mobile.indexOf('}', start) + 1)
}

describe('mobile report history filter sizing', () => {
  it('does not feed changing result counts into the select columns', () => {
    const panel = rule('.report-page .report-filter-panel')
    expect(panel).toContain('grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);')
  })

  it('gives the result count its own full-width row below the search', () => {
    const count = rule('.report-page .report-filter-count')
    expect(count).toContain('grid-column: 1 / -1;')
    expect(count).toContain('grid-row: 3;')
  })
})

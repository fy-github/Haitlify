// @ts-expect-error Test-only Node API is outside the browser type set.
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
declare const process: { cwd: () => string }

const css = readFileSync(`${process.cwd()}/src/styles.css`, 'utf8')
const mobileStart = css.indexOf('@media (max-width: 768px) {')
const mobileEnd = css.indexOf('@media (max-width: 900px) {', mobileStart)
const mobileBlock = mobileStart >= 0 && mobileEnd > mobileStart ? css.slice(mobileStart, mobileEnd) : ''

describe('API automation mobile layout contract', () => {
  it('keeps the preparation workspace scrollable and the case table usable on narrow screens', () => {
    expect(mobileBlock).toContain('.automation-workspace')
    expect(mobileBlock).toMatch(/\.automation-workspace\s*\{[^}]*overflow:\s*(?:auto|visible)/s)
    expect(mobileBlock).toContain('.automation-panel')
    expect(mobileBlock).toMatch(/\.automation-panel\s*\{[^}]*min-height:\s*\d+px/s)
    expect(mobileBlock).toMatch(/\.automation-panel \.filter-fields\s*\{[^}]*grid-template-columns:\s*minmax\(0, 1fr\)/s)
    expect(mobileBlock).toMatch(/\.automation-panel \.case-table\s*\{[^}]*height:\s*\d+px/s)
  })
})

import { describe, expect, it } from 'vitest'
// @ts-expect-error Test-only Node API is outside the browser type set.
import { readFileSync } from 'node:fs'
declare const process: { cwd: () => string }

const css = readFileSync(`${process.cwd()}/src/styles/cases-library.css`, 'utf8') as string

const balancedBlock = (source: string, selector: string): string => {
  const start = source.indexOf(selector)
  if (start < 0) return ''
  const open = source.indexOf('{', start)
  if (open < 0) return ''
  let depth = 0
  for (let index = open; index < source.length; index += 1) {
    if (source[index] === '{') depth += 1
    if (source[index] === '}') {
      depth -= 1
      if (depth === 0) return source.slice(open + 1, index)
    }
  }
  return ''
}

const narrowMedia = balancedBlock(css, '@media (max-width: 640px)')
const narrowRule = (selector: string): string => balancedBlock(narrowMedia, selector)

 describe('Cases narrow title reachability contract', () => {
  it('moves the sticky action edge back into table flow only on narrow screens', () => {
    expect(css).toContain('.cv-actions-col {')
    expect(balancedBlock(css, '.cv-actions-col')).toContain('position: sticky')
    const narrowActions = balancedBlock(narrowMedia, '.cv-table th.cv-actions-col,')
      || balancedBlock(narrowMedia, '.cv-table th.cv-actions-col')
    const narrowBodyActions = balancedBlock(narrowMedia, '.cv-table td.cv-actions-col')
    expect(narrowActions).toContain('position: static')
    expect(narrowActions).toContain('right: auto')
    expect(narrowActions).toContain('z-index: auto')
    expect(narrowBodyActions).toContain('position: static')
  })

  it('keeps title hit testing independent from the action-column fix', () => {
    expect(narrowRule('.cv-title')).not.toContain('z-index')
    expect(narrowRule('.cv-title')).not.toContain('position: relative')
  })
})

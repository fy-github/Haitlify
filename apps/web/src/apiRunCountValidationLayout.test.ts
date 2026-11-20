// @ts-expect-error Test-only Node API is outside the browser type set.
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
declare const process: { cwd: () => string }

const source = readFileSync(`${process.cwd()}/src/components/views/ApiExecutionPage.vue`, 'utf8') as string
const marker = '@media (max-width: 640px) {\n  .automation-config-drawer .execution-control-group-count'
const start = source.indexOf(marker)
const mobile = start >= 0 ? source.slice(start, source.indexOf('\n}\n', start) + 3) : ''
const block = (selector: string): string => {
  const at = mobile.indexOf(selector)
  if (at < 0) return ''
  return mobile.slice(mobile.indexOf('{', at) + 1, mobile.indexOf('}', at))
}

describe('API run-count validation message containment', () => {
  it('lets the count row wrap locally instead of squeezing the stepper beside its error', () => {
    expect(start).toBeGreaterThanOrEqual(0)
    expect(block('.automation-config-drawer .execution-control-group-count .execution-control-row')).toContain('flex-wrap: wrap')
  })

  it('preserves the archived stepper width when validation text occupies a second row', () => {
    expect(block('.automation-config-drawer .execution-control-group-count .api-run-count-stepper')).toContain('flex: none')
  })

  it('places the complete error below the control without the inline-message offset', () => {
    const error = block('.automation-config-drawer .execution-control-group-count .api-run-count-error')
    expect(error).toContain('flex-basis: 100%')
    expect(error).toContain('margin-left: 0')
  })
})

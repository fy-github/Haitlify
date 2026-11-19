import { describe, expect, it } from 'vitest'
// @ts-expect-error Test-only Node API is outside the browser type set.
import { readFileSync } from 'node:fs'
declare const process: { cwd: () => string }

const css = readFileSync(`${process.cwd()}/src/styles/cases-library.css`, 'utf8') as string
const block = (selector: string): string => {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return new RegExp(`(?:^|\\n)\\s*${escaped}\\s*\\{([^}]*)\\}`).exec(css)?.[1] || ''
}

describe('Cases real consumer containment contract', () => {
  it('lays out the direct Cases fragment in its actual workspace instead of an absent legacy wrapper', () => {
    const layout = block('.workspace.is-workbench:has(> .cases-head)')
    expect(layout).toContain('display: flex')
    expect(layout).toContain('flex-direction: column')
    expect(layout).toContain('overflow-y: auto')
  })

  it('allows the library to shrink while preserving the wide table inside its own scrollport', () => {
    expect(block('.cv-workspace')).toContain('min-width: 0')
    expect(block('.cv-scroll')).toContain('overflow: auto')
    expect(block('.cv-table')).toContain('min-width: 1280px')
  })
})

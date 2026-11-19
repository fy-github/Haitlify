// @ts-expect-error Test-only Node API is outside the browser type set.
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
declare const process: { cwd: () => string }

const css = readFileSync(`${process.cwd()}/src/styles/home-workbench.css`, 'utf8')
const diagnostic = css.match(/\.home-workbench \.home-state-panel span\s*\{([^}]*)\}/)?.[1] || ''

describe('HomeView state panel responsive contract', () => {
  it('contains unbroken diagnostic text inside the owning panel', () => {
    expect(diagnostic).toContain('min-width: 0')
    expect(diagnostic).toContain('overflow-wrap: anywhere')
    expect(diagnostic).toContain('word-break: break-word')
  })
})

import { describe, expect, it } from 'vitest'
// @ts-expect-error Test-only Node API is outside the browser type set.
import { readFileSync } from 'node:fs'

const css = readFileSync(new URL('./settings-pages.css', import.meta.url), 'utf8') as string
const rules = (selector: string): string[] => Array.from(css.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/([^{}]+)\{([^{}]*)\}/g))
  .filter(match => match[1].trim() === selector)
  .map(match => match[2])

describe('user list height budget with persistent workspace notices', () => {
  it('keeps a non-shrinking list card while the owning body can scroll', () => {
    expect(rules('.user-panel').some(rule => rule.includes('min-height: 440px;') && rule.includes('flex-shrink: 0;'))).toBe(true)
    const body = rules('.user-body').find(rule => rule.includes('display: flex !important;')) || ''
    expect(body).toContain('overflow: auto !important;')
    expect(body).not.toContain('overflow: hidden')
  })
})

import { describe, expect, it } from 'vitest'
// @ts-expect-error Test-only Node API is outside the browser type set.
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../components/automation/WebSessionPanel.vue', import.meta.url), 'utf8') as string

describe('Web Session dialog containment', () => {
  it('caps its owning dialog at the viewport without changing the desktop width', () => {
    expect(source).toContain('<el-dialog')
    expect(source).toContain('width="min(720px, calc(100vw - 24px))"')
  })

  it('gives wide metadata columns their own labelled horizontal scroll region', () => {
    expect(source).toMatch(/<div[^>]*class="web-session-panel"[^>]*>/)
    const scrollRule = source.match(/\.web-session-panel__table-scroll\s*\{([^}]+)\}/)?.[1] || ''
    expect(scrollRule).toContain('overflow-x: auto;')
    expect(source).toContain('aria-label="Session 元数据表格"')
    expect(source).toContain('tabindex="0"')
  })
})

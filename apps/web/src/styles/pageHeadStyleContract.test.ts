import { describe, expect, it } from 'vitest'

// Vitest/Vite 下 .css?raw 返回空串，故直接读盘校验定稿 token（不弱化断言）。
// @ts-expect-error Node's test-only filesystem API is intentionally not part of the browser type set.
import { readFileSync } from 'node:fs'
declare const process: { cwd: () => string }

const cssSource = (relative: string): string => {
  for (const candidate of [`${process.cwd()}${relative}`, `${process.cwd()}/apps/web${relative}`]) {
    try {
      return readFileSync(candidate, 'utf8')
    } catch {
      // try the next candidate
    }
  }
  throw new Error(`${relative} not found from ${process.cwd()}`)
}

const normalize = (value: string) => value.replace(/\s+/g, ' ')
const rule = (css: string, selector: string): string => {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return normalize(new RegExp(`(?:^|\\n)\\s*${escaped}\\s*\\{([^}]*)\\}`).exec(css)?.[1] ?? '')
}

describe('frozen page-head and primary-button tokens (each page scoped)', () => {
  it('defect page h1 uses the 定稿 color / letter-spacing inside .defect-page', () => {
    const css = cssSource('/src/styles/defect-design.css')
    const body = rule(css, '.defect-page .page-head h1')
    expect(body).toContain('color: #272a30')
    expect(body).toContain('letter-spacing: -0.6px')
    expect(body).toContain('font-size: 25px')
  })

  it('report page h1 uses 25px / 定稿 color / letter-spacing inside .report-page', () => {
    const css = cssSource('/src/styles/settings-quality.css')
    const body = rule(css, '.report-page .report-head h1')
    expect(body).toContain('font-size: 25px')
    expect(body).toContain('color: #272a30')
    expect(body).toContain('letter-spacing: -0.6px')
  })

  it('defect detail save button uses the frozen primary palette, scoped to the defect footer', () => {
    const css = cssSource('/src/styles/defect-design.css')
    const body = rule(css, '.defect-page .d-edit-footer .btn.primary')
    expect(body).toContain('background: #68d7b3')
    expect(body).toContain('border-color: #62c7a8')
    expect(body).toContain('color: #28533d')
  })

  it('never ships a bare h1 rule from either page stylesheet', () => {
    const defect = normalize(cssSource('/src/styles/defect-design.css'))
    const report = normalize(cssSource('/src/styles/settings-quality.css'))
    expect(defect).not.toMatch(/(^|})\s*h1 \{/)
    expect(report).not.toMatch(/(^|})\s*h1 \{/)
  })
})

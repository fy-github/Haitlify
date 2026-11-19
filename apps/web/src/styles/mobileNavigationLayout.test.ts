import { describe, expect, it } from 'vitest'
// @ts-expect-error Test-only Node API is outside the browser type set.
import { readFileSync } from 'node:fs'
import app from '../App.vue?raw'

const css = readFileSync(new URL('./shell.css', import.meta.url), 'utf8') as string

const block = (selector: string) => {
  const start = css.indexOf(`${selector} {`)
  expect(start, selector).toBeGreaterThanOrEqual(0)
  return css.slice(start, css.indexOf('}', start) + 1)
}

describe('mobile navigation drawer containment', () => {
  it('removes the shared editor padding and gives the sidebar the remaining drawer height', () => {
    expect(app).toContain('class="studio-navigation-drawer"')
    const body = block('.studio-navigation-drawer .el-drawer__body')
    expect(body).toContain('padding: 0;')
    expect(body).toContain('display: flex;')
    expect(body).toContain('flex-direction: column;')
    expect(body).toContain('overflow: hidden;')
    const header = block('.studio-navigation-drawer .studio-responsive-drawer__header')
    expect(header).toContain('margin: 0;')
    expect(header).toContain('flex: 0 0 auto;')
    const sidebar = block('.studio-navigation-drawer .studio-sidebar')
    for (const declaration of ['width: 100%;', 'height: auto;', 'min-height: 0;', 'flex: 1 1 auto;', 'box-sizing: border-box;']) {
      expect(sidebar).toContain(declaration)
    }
  })
})

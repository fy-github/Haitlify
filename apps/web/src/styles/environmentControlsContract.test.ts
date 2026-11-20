import { describe, expect, it } from 'vitest'
// @ts-expect-error Test-only Node API is outside the browser type set.
import { readFileSync, existsSync } from 'node:fs'
declare const process: { cwd: () => string }
const path = `${process.cwd()}/src/styles/environment-controls.css`
const css = existsSync(path) ? readFileSync(path, 'utf8') as string : ''
const app = readFileSync(`${process.cwd()}/src/App.vue`, 'utf8') as string
const apiController = readFileSync(`${process.cwd()}/src/ui/useApiExecutionController.ts`, 'utf8') as string

describe('environment / runtime controls use their own archived variants', () => {
  it('keeps environment-v1 save, cancel and dangerous actions distinct', () => {
    expect(css).toContain('--environment-control-bg: #66d8b5')
    expect(css).toContain('--environment-control-ink: #345a44')
    expect(css).toContain('--environment-control-ink: #b69a8b')
    expect(css).toContain('border-radius: 20px')
    expect(css).toContain('background: transparent')
  })
  it('scopes environment button overrides without repainting all drawer actions', () => {
    expect(css).toContain('.environment-drawer-actions')
    expect(css).toContain('.environment-editor')
    expect(css).not.toMatch(/(?:^|\n)\.drawer-actions\s+\.el-button\s*\{/)
  })
  it('uses the small-btn primary variant for Web runtime apply', () => {
    expect(css).toContain('.web-ed-drawer__foot .web-ed-btn')
    expect(css).toContain('background: #54d9b5')
    expect(css).toContain('color: #184333')
    expect(css).toContain('border-color: #48cbaa')
    expect(css).toContain('border-radius: 18px')
  })
  it('preserves border and semantic colors on hover/focus and distinguishes disabled/loading', () => {
    expect(css).toContain('filter: brightness(0.96)')
    expect(css).toContain(':focus-visible')
    expect(css).toContain('outline: none')
    expect(css).toContain('.is-disabled')
    expect(css).toContain('.is-loading')
  })
  it('keeps API and Web environment footers touch-sized at the archived mobile breakpoint', () => {
    expect(css).toContain('@media (max-width: 640px)')
    expect(css).toContain('min-height: 44px')
    expect(css).toContain('font-size: 10px')
    expect(css).toContain('padding: 7px 9px')
  })
  it('prevents the desktop environment directory trapping a mobile footer in a narrow column', () => {
    expect(css).toContain('.el-drawer.environment-control-surface.web-automation-drawer .environment-drawer')
    expect(css).toContain('flex-direction: column')
    expect(css).toContain('max-height: 120px')
    expect(css).toContain('grid-template-columns: minmax(0, 1fr)')
  })
  it('keeps count steppers and directory buttons on their distinct archive variants', () => {
    expect(css).toContain('.api-run-count-stepper .api-run-count-stepper__button')
    expect(css).toContain('background: #f7faf8')
    expect(css).toContain('color: #bec8c1')
    expect(css).toContain('--environment-directory-bg: #66d8b5')
    expect(css).toContain('border-color: transparent')
  })
  it('assigns explicit classes to run confirmation vs destructive environment confirmation', () => {
    expect(`${app}\n${apiController}`).toContain("customClass: 'api-execution-confirm'")
    expect(app).toContain("environment-danger-confirm")
    expect(app).toContain("environment-discard-confirm")
  })

  it('explicitly preserves secondary confirmation ink and border across hover and keyboard focus', () => {
    const selector = ':is(.environment-danger-confirm, .environment-discard-confirm, .api-execution-confirm) .el-message-box__btns .el-button:not(.el-button--primary):is(:hover, :focus, :focus-visible, :active)'
    const start = css.indexOf(selector)
    expect(start).toBeGreaterThanOrEqual(0)
    const rule = css.slice(start, css.indexOf('}', start) + 1)
    for (const token of ['background: #f5f7f6', 'color: #56655d', 'border-color: #e6ece8', 'box-shadow: none', 'outline: none']) expect(rule).toContain(token)
  })
})

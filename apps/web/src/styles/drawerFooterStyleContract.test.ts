import { describe, expect, it } from 'vitest'
// @ts-expect-error Test-only node filesystem API.
import { readFileSync } from 'node:fs'
declare const process: { cwd: () => string }
const source = (name: string): string => {
  for (const prefix of [process.cwd(), `${process.cwd()}/apps/web`]) {
    try { return readFileSync(`${prefix}/src/${name}`, 'utf8') } catch { /* next root */ }
  }
  return ''
}

describe('non-runtime drawer/footer archived button variants', () => {
  it('case columns has a dedicated footer surface, not the table-tool button family', () => {
    const cases = source('components/views/CasesView.vue')
    expect(cases).toContain('class="case-columns-dialog"')
    expect(cases).toContain('class="drawer-actions case-columns-actions"')
    expect(cases).toContain('class="drawer-actions case-bulk-actions"')
    expect(cases).toContain('class="drawer-actions case-assignment-actions"')
  })

  it('small footer tokens include the quiet restore variant, rather than repainting every primary', () => {
    const css = source('styles/drawer-footer-controls.css')
    expect(css).toContain('.case-columns-actions')
    expect(css).toContain('--footer-button-bg: #54d9b5')
    expect(css).toContain('--footer-button-border: #48cbaa')
    expect(css).toContain('--footer-button-ink: #184333')
    expect(css).toContain('[data-action="restore"]')
    expect(css).toContain('border: 0')
    expect(css).toContain('background: transparent')
    expect(css).not.toContain('linear-gradient')
    expect(css).not.toMatch(/(?:^|})\s*\.el-button\s*\{/)
  })

  it('scopes user, settings, Jira and App editor controls without touching runtime/monitor buttons', () => {
    const css = source('styles/drawer-footer-controls.css')
    for (const scope of ['.user-drawer__buttons', '.s-drawer-actions', '.defect-template-drawer', '.edit-form > .drawer-actions', '.curl-preview-head > .drawer-actions']) expect(css).toContain(scope)
    expect(css).not.toMatch(/\.api-monitor|\.web-ed-|\.environment-drawer/)
    expect(css).toContain('.el-button--danger')
    expect(css).toContain('.is-disabled')
    expect(css).toContain('.is-loading')
    expect(css).toContain('opacity: 0.45')
    expect(css).toContain('outline: none')
    expect(css).toContain('box-shadow: none')
  })

  it('keeps every case dialog footer within the narrow viewport with 44px controls', () => {
    const css = source('styles/drawer-footer-controls.css')
    expect(css).toContain('@media (max-width: 640px)')
    expect(css).toContain('.case-columns-dialog.el-dialog')
    expect(css).toContain('.case-bulk-dialog.el-dialog')
    expect(css).toContain('.case-assignment-dialog.el-dialog')
    expect(css).toContain('max-width: calc(100vw - 24px)')
    expect(css).toContain('min-height: 44px')
    expect(css).toContain('flex-wrap: wrap')
  })

  it('includes account/conversion footers and actual close actions through named non-runtime scopes', () => {
    const css = source('styles/drawer-footer-controls.css')
    expect(css).toContain('.account-password-dialog .el-dialog__footer')
    expect(css).toContain('.case-conversion-dialog .drawer-actions')
    expect(css).toContain('.case-editor-drawer .studio-responsive-drawer__header > button')
    expect(css).toContain('.user-drawer-shell .studio-responsive-drawer__header > button')
    expect(css).toContain('.curl-preview-drawer .el-drawer__close-btn')
    expect(css).toContain('background: #f3f6f4')
    expect(css).toContain('border-radius: 50%')
  })

  it('isolates user-confirmation modal variants from the smaller drawer footer and environment confirmations', () => {
    const css = source('styles/drawer-footer-controls.css')
    const users = source('components/views/UserManagementView.vue')
    expect(users.match(/theme-confirm-box user-management-confirm/g)).toHaveLength(4)
    expect(users).toContain('user-management-danger-confirm')
    expect(css).toContain('.user-management-confirm .el-message-box__btns')
    expect(css).toContain('--user-confirm-bg: #4cd8b4')
    expect(css).toContain('--user-confirm-ink: #163f34')
    expect(css).toContain('.user-management-danger-confirm')
  })
})

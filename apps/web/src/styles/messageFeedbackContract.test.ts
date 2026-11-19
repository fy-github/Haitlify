import { describe, expect, it } from 'vitest'
// @ts-expect-error Test-only Node API is outside the browser type set.
import { readFileSync } from 'node:fs'

const read = (name: string): string => readFileSync(new URL(name, import.meta.url), 'utf8')
const app = read('../App.vue')
const template = read('./settings-template.css')
const canvas = read('./studio-canvas.css')
const defect = read('./defect-design.css')
const bounded = (text: string, from: string, to: string): string => {
  const start = text.indexOf(from)
  const end = text.indexOf(to, start + from.length)
  expect(start).toBeGreaterThanOrEqual(0)
  expect(end).toBeGreaterThan(start)
  return text.slice(start, end)
}
const rule = (text: string, selector: string): string => bounded(text, `${selector} {`, '}')

describe('Persistent message feedback contracts', () => {
  it('binds defect failure surfaces to each write outcome instead of one shared brown state', () => {
    for (const outcome of ['confirmed-no-write', 'uncertain-commit', 'committed-navigation-failed']) {
      const block = rule(defect, `.defect-failure-banner[data-outcome='${outcome}']`)
      expect(block).toContain('--defect-failure-ink:')
      expect(block).toContain('--defect-failure-surface:')
      expect(block).toContain('--defect-failure-action:')
    }
    expect(rule(defect, '.defect-failure-banner__action.is-primary')).toContain('background: var(--defect-failure-action)')
  })

  it('uses a non-edge keyboard cue for recovery actions including pointer overlap', () => {
    const focus = rule(template, '.template-recovery-action-group > button:focus-visible:not(:disabled)')
    expect(focus).toContain('outline: none')
    expect(focus).toContain('box-shadow: none')
    expect(focus).toContain('filter: brightness(0.9)')
    expect(focus).not.toMatch(/border(?:-color)?:/)
    const danger = rule(template, ".template-recovery-action-group[data-risk='destructive'] > button:focus-visible")
    expect(danger).toContain('box-shadow: none')
  })

  it('retains adjacent defect evidence bounds while restyling the recovery banner', () => {
    expect(rule(defect, '.defect-page .d-evidence-raw')).toContain('white-space: pre-wrap')
    expect(rule(defect, '.defect-page .d-evidence-item figure')).toContain('min-width: 0')
    expect(rule(defect, '.defect-page .d-evidence-item img')).toContain('max-height: 320px')
    expect(rule(defect, '.defect-page .d-step-row.is-unknown')).toContain('border-left-color: #9ca3af')
  })

  it('reserves mobile settings row budget when durable notices consume the pane height', () => {
    // Real-App browser RED: at 390px the failed-save scrollport measured 0px.
    const pane = rule(template, '.workspace:has(> .studio-offline-notice) .workbench__pane.settings-pane')
    const workspace = rule(template, '.workspace:has(> .studio-offline-notice) .settings-pane > .s-workspace')
    const rows = rule(template, '.workspace:has(> .studio-offline-notice) .settings-pane .s-scroll')
    expect(pane).toContain('overflow-y: auto')
    expect(workspace).toContain('flex-shrink: 0')
    expect(workspace).toContain('min-height: 420px')
    expect(rows).toContain('min-height: 120px')
  })

  it('reserves the same mobile row budget for the sibling case-types page', () => {
    const pageSelector = '.workspace:has(> .studio-offline-notice) .settings-pane > .s-types-page'
    const rowsSelector = '.workspace:has(> .studio-offline-notice) .settings-pane .s-types-page .s-scroll'
    expect(template).toContain('.workspace:has(> .studio-sync-notice) .settings-pane > .s-types-page')
    expect(template).toContain('.workspace:has(> .studio-sync-notice) .settings-pane .s-types-page .s-scroll')
    const page = rule(template, pageSelector)
    const rows = rule(template, rowsSelector)
    expect(page).toContain('flex-shrink: 0')
    expect(page).toContain('min-height: 420px')
    expect(rows).toContain('min-height: 120px')
  })

  it('does not recolor the existing border on settings recovery focus', () => {
    const focus = rule(template, '.s-save-actions button:focus-visible,\n.s-drawer-btn:focus-visible')
    expect(focus).toContain('outline: none')
    expect(focus).toContain('box-shadow: none')
    expect(focus).toContain('filter: brightness(0.9)')
    expect(focus).not.toContain('border-color:')
  })

  it('gives sync conflict an explicit severity, wrapping consequence and action identities', () => {
    const sync = bounded(app, '<div v-if="workspaceSyncConflict"', '<div v-if="!isOnline"')
    expect(sync).toContain('data-message-level="warning"')
    expect(sync).toContain('aria-live="polite"')
    expect(sync).toContain('sync-conflict-icon')
    expect(sync).toContain('推送本地会更新后端；使用后端会替换本地数据。')
    expect(sync).toContain('data-sync-action="push-local"')
    expect(sync).toContain('data-sync-action="take-backend"')
    expect(sync).toContain('data-risk="destructive"')
    expect(sync).toContain('@click="pushLocalWorkspaceToBackend"')
    expect(sync).toContain('@click="reloadBackendWorkspace"')
    const copy = rule(canvas, '.studio-sync-notice .sync-conflict-copy span')
    expect(copy).toContain('white-space: normal')
    expect(copy).toContain('overflow-wrap: anywhere')
  })

  it('gives sync actions scoped base-border and severity-preserving interaction cues', () => {
    expect(canvas).toContain('.studio-sync-notice:not(.template-recovery-notice)')
    expect(canvas).toContain('[data-sync-action]:not(:disabled):not(.is-disabled):not(.is-loading)')
    expect(canvas).toContain('border-color: var(--border-strong)')
    expect(canvas).toContain("[data-sync-action='take-backend']")
    expect(canvas).toContain('color: var(--status-danger-text)')
    expect(canvas).toContain('filter: brightness(0.96)')
    expect(canvas).toContain('filter: brightness(0.9)')
    expect(canvas).toContain('.studio-sync-notice:not(.template-recovery-notice) .sync-conflict-actions .icon-button')
    expect(canvas).toContain('outline: none')
    expect(canvas).toContain('box-shadow: none')
  })

  it('keeps info and recovery severity on durable notice containers', () => {
    const recovery = bounded(app, '<section v-if="templateConfigModule.recovery?.reason"', '<DefectFailureBanner')
    expect(recovery).toContain('data-message-level="warning"')
    expect(recovery).toContain('aria-live="assertive"')
    const offline = bounded(app, '<div v-if="!isOnline"', '<template v-if="isCaseWorkbenchNav"')
    expect(offline).toContain('data-message-level="info"')
    expect(rule(canvas, '.studio-offline-notice')).toContain('display: grid')
  })
})

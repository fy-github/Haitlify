import { describe, expect, it } from 'vitest'
// @ts-expect-error Test-only Node API is outside the browser type set.
import { readFileSync } from 'node:fs'

const app = readFileSync(new URL('../App.vue', import.meta.url), 'utf8') as string
const css = readFileSync(new URL('../components/views/EnvironmentManagementView.vue', import.meta.url), 'utf8') as string
const surface = readFileSync(new URL('../components/automation/EnvironmentEditorSurface.vue', import.meta.url), 'utf8') as string
const studioCanvas = readFileSync(new URL('./studio-canvas.css', import.meta.url), 'utf8') as string
const styles = readFileSync(new URL('../styles.css', import.meta.url), 'utf8') as string
const block = (text: string, selector: string) => {
  const start = text.indexOf(`${selector} {`)
  expect(start, selector).toBeGreaterThanOrEqual(0)
  return text.slice(start, text.indexOf('}', start) + 1)
}

describe('standalone environment scroll ownership', () => {
  it('gives the environment route its own full-height outer vertical scrollport', () => {
    expect(app).toContain("'is-environment-management': activeNav === 'environments'")
    const workspace = block(css, ':global(.workspace.is-environment-management)')
    for (const declaration of ['display: block;', 'overflow-y: auto;', 'overflow-x: hidden;']) expect(workspace).toContain(declaration)
  })

  it('lets the inline form contribute natural height instead of stranding its footer below a hidden parent', () => {
    expect(block(surface, '.environment-inline-surface')).toContain('height: auto;')
    expect(block(surface, '.environment-inline-surface > .environment-drawer')).toContain('height: auto;')
    const editor = block(surface, '.environment-inline-surface .environment-editor')
    expect(editor).toContain('max-height: none;')
    expect(editor).toContain('overflow: visible;')
  })

  it('gives compact Web preparation a real outer scroll owner for a mounted monitor summary', () => {
    const compactWorkspace = studioCanvas.slice(studioCanvas.indexOf('.web-automation-workspace.is-compact {'))
    expect(compactWorkspace.slice(0, compactWorkspace.indexOf('}', 1) + 1)).toContain('height: 100%;')
    expect(compactWorkspace.slice(0, compactWorkspace.indexOf('}', 1) + 1)).toContain('grid-template-rows: auto auto auto auto;')
    expect(compactWorkspace.slice(0, compactWorkspace.indexOf('}', 1) + 1)).toContain('overflow-y: auto;')
    const compactGrid = studioCanvas.slice(studioCanvas.indexOf('.web-automation-workspace__grid.is-compact {'))
    expect(compactGrid.slice(0, compactGrid.indexOf('}', 1) + 1)).toContain('display: block;')
    expect(compactGrid.slice(0, compactGrid.indexOf('}', 1) + 1)).toContain('min-height: max-content;')
    expect(studioCanvas).not.toContain('.web-automation-workspace__grid.is-compact > *')
    expect(studioCanvas).not.toContain('.web-automation-workspace__grid.is-compact .web-run-summary')
  })

  it('stacks logout step controls on narrow screens so the target input stays inside the form', () => {
    const row = styles.slice(styles.indexOf('.web-logout-step-row'))
    expect(row).toContain('.web-logout-step-row')
    expect(row).toContain('@media (max-width: 640px)')
    expect(row).toContain('grid-template-columns: 28px minmax(0, 1fr);')
    expect(row).toContain('"target target"')
  })

  it('keeps an expanded inline Web logout target above the footer instead of overlaying it', () => {
    expect(surface).toContain('.environment-inline-surface:has(.web-logout-step-row) .environment-drawer-actions')
    const start = surface.indexOf('.environment-inline-surface:has(.web-logout-step-row) .environment-drawer-actions')
    const block = surface.slice(start, surface.indexOf('}', start) + 1)
    expect(block).toContain('position: static;')
  })
})

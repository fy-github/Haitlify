import { describe, expect, it } from 'vitest'
// @ts-expect-error Test-only Node API is outside the browser type set.
import { readFileSync } from 'node:fs'
declare const process: { cwd: () => string }
import viewSource from './ReportsView.vue?raw'

const controlsCss = readFileSync(`${process.cwd()}/src/styles/report-detail-controls.css`, 'utf8')
const qualityCss = readFileSync(`${process.cwd()}/src/styles/settings-quality.css`, 'utf8')

// Match a complete selector inside its own rule/media block, not a file tail that
// can borrow a sibling control's focus or mobile declaration.
const cssRule = (css: string, selector: string, media?: string): Record<string, string> => {
  const rules: Array<{ selectors: string[]; media?: string; body: string }> = []
  const visit = (source: string, currentMedia?: string) => {
    let start = 0
    while (start < source.length) {
      const open = source.indexOf('{', start)
      if (open < 0) break
      const heading = source.slice(start, open).trim()
      let depth = 1
      let end = open + 1
      while (end < source.length && depth) {
        if (source[end] === '{') depth++
        if (source[end] === '}') depth--
        end++
      }
      expect(depth, `balanced CSS block: ${heading}`).toBe(0)
      const body = source.slice(open + 1, end - 1)
      if (heading.startsWith('@media')) visit(body, heading)
      else rules.push({ selectors: heading.split(',').map((item) => item.trim()), media: currentMedia, body })
      start = end
    }
  }
  visit(css.replace(/\/\*[\s\S]*?\*\//g, ''))
  const matches = rules.filter((rule) => rule.media === media && rule.selectors.includes(selector))
  expect(matches, `unique CSS rule: ${media || 'base'} / ${selector}`).toHaveLength(1)
  return Object.fromEntries(matches[0]!.body.split(';').filter((entry) => entry.trim()).map((entry) => {
    const colon = entry.indexOf(':')
    return [entry.slice(0, colon).trim(), entry.slice(colon + 1).trim()]
  }))
}

describe('ReportsView report-detail control visual contract', () => {
  it('attaches the report-only control token sheet and marks semantic action families', () => {
    expect(viewSource).toContain('<style src="../../styles/report-detail-controls.css"></style>')
    expect(viewSource).toContain('report-action-button report-action-button--primary')
    expect(viewSource).toContain('report-action-button report-action-button--danger')
    expect(viewSource).toContain('report-action-button report-action-button--secondary')
    expect(viewSource).toContain('report-detail-dialog')
  })

  it('keeps archive-derived geometry, interaction states, mobile variant, and danger separation scoped', () => {
    expect(controlsCss).toContain('border-radius: 20px')
    expect(controlsCss).toContain('background: #fff')
    expect(controlsCss).toContain('background-image: none')
    expect(controlsCss).toContain('filter: brightness(0.96)')
    expect(controlsCss).toContain('outline: none !important')
    expect(controlsCss).toContain('@media (max-width: 640px)')
    expect(controlsCss).toContain('color: var(--color-danger, #b83b52)')
    expect(controlsCss).not.toContain('report-action-button--danger.el-button {\n  color: #163f34')
  })

  it('never restores an inset focus border on report controls or the evidence comparison close action', () => {
    expect(controlsCss).not.toMatch(/box-shadow:\s*inset/)
    const focusRule = controlsCss.slice(controlsCss.indexOf('.report-page .report-action-button:focus-visible'), controlsCss.indexOf('/* ≤640px'))
    expect(focusRule).toContain('box-shadow: none')
    expect(focusRule).toContain('filter: brightness(0.9)')
    const comparisonFocus = controlsCss.slice(controlsCss.indexOf('.report-detail-compare-dialog .el-dialog__headerbtn:focus-visible'))
    expect(comparisonFocus).toContain('box-shadow: none')
  })

  it('keeps the screenshot preview dialog inside the mobile viewport and preserves its footer tokens on hover', () => {
    const secondaryHoverRule = controlsCss.slice(
      controlsCss.indexOf('.report-detail-dialog .drawer-actions .report-action-button--secondary.el-button:hover'),
      controlsCss.indexOf('.report-detail-dialog .drawer-actions .report-action-button--primary.el-button:hover'),
    )
    const primaryHoverRule = controlsCss.slice(
      controlsCss.indexOf('.report-detail-dialog .drawer-actions .report-action-button--primary.el-button:hover'),
      controlsCss.indexOf('@media (max-width: 640px)', controlsCss.indexOf('.report-detail-dialog .drawer-actions .report-action-button--primary.el-button:hover')),
    )
    expect(controlsCss).toContain('.report-detail-dialog.el-dialog')
    expect(controlsCss).toContain('max-width: calc(100vw - 24px)')
    expect(controlsCss).toContain('.report-detail-dialog .drawer-actions .report-action-button--secondary.el-button:hover')
    expect(controlsCss).toContain('.report-detail-dialog .drawer-actions .report-action-button--primary.el-button:hover')
    expect(controlsCss).toContain('.report-detail-dialog .drawer-actions .report-action-button--secondary.el-button:hover:not(.is-disabled):not(:disabled)')
    expect(controlsCss).toContain('.report-detail-dialog .drawer-actions .report-action-button--primary.el-button:hover:not(.is-disabled):not(:disabled)')
    expect(controlsCss).toContain('.report-detail-dialog.el-dialog .drawer-actions .report-action-button--secondary.el-button')
    expect(controlsCss).toContain('min-height: 40px')
    expect(secondaryHoverRule).toContain('color: #535a64')
    expect(secondaryHoverRule).toContain('border-color: #e8ebee')
    expect(secondaryHoverRule).toContain('background: #fff')
    expect(primaryHoverRule).toContain('color: #163f34')
    expect(primaryHoverRule).toContain('border-color: #4cd8b4')
    expect(primaryHoverRule).toContain('background: #4cd8b4')
  })

  it('reserves the mobile history and report-result table budget instead of flex-shrinking under the pager', () => {
    const mobile = '@media (max-width: 760px)'
    expect(cssRule(qualityCss, '.report-page', mobile)).toMatchObject({ 'overflow-y': 'auto' })
    expect(cssRule(qualityCss, '.report-page .report-table-box', mobile)).toMatchObject({ 'min-height': '240px' })
    expect(cssRule(qualityCss, '.report-page .report-table-box > .r-scroll', mobile)).toMatchObject({ 'min-height': '120px' })
    expect(cssRule(qualityCss, '.report-page .r-scroll')).toMatchObject({ overflow: 'auto' })
    expect(cssRule(qualityCss, '.report-page .r-pagination')).toMatchObject({ flex: 'none' })
    for (const table of ['history-table', 'result-table']) {
      expect(viewSource).toContain(`class="r-table ${table}"`)
      expect(cssRule(qualityCss, `.report-page .${table}`, mobile)['min-width']).toBe(table === 'history-table' ? '900px' : '760px')
    }
  })

  it('keeps mobile select widths independent of the result count in its own row', () => {
    const mobile = '@media (max-width: 760px)'
    expect(cssRule(qualityCss, '.report-page .report-head .toolbar', mobile)).toMatchObject({ width: 'auto' })
    expect(cssRule(qualityCss, '.report-page .report-filter-panel', mobile)).toMatchObject({
      display: 'grid',
      'grid-template-columns': 'minmax(0, 1fr) minmax(0, 1fr)',
    })
    for (const selector of ['.report-page .report-project-select', '.report-page .report-env-select']) {
      expect(cssRule(qualityCss, selector, mobile)).toMatchObject({ width: '100%', 'min-width': '0' })
    }
    expect(cssRule(qualityCss, '.report-page .report-search', mobile)).toMatchObject({
      'grid-column': '1 / 3', 'grid-row': '2', 'min-width': '0', order: '0',
    })
    expect(cssRule(qualityCss, '.report-page .report-filter-count', mobile)).toMatchObject({
      'grid-column': '1 / -1', 'grid-row': '3', 'margin-left': '0', order: '0',
    })
  })

  it.each([
    ['secondary', '#535a64', '#e8ebee', '#fff'],
    ['primary', '#163f34', '#4cd8b4', '#4cd8b4'],
  ])('makes preview footer %s keyboard focus win hover without changing archive tokens', (variant, color, border, background) => {
    const selector = `.report-detail-dialog .drawer-actions .report-action-button--${variant}.el-button`
    const hover = cssRule(controlsCss, `${selector}:hover:not(.is-disabled):not(:disabled)`)
    const focus = cssRule(controlsCss, `${selector}:focus-visible`)
    const tokens = { color, 'border-color': border, background, 'background-image': 'none', 'box-shadow': 'none' }
    expect(hover).toMatchObject({ ...tokens, filter: 'brightness(0.96)' })
    expect(focus).toMatchObject({ ...tokens, filter: 'brightness(0.9)', outline: 'none' })
    const combined = cssRule(controlsCss, `${selector}:focus-visible:hover:not(.is-disabled):not(:disabled)`)
    expect(combined).toEqual(focus)
  })

  it('keeps report back links at the archive weight instead of the filled-button weight', () => {
    const backRule = controlsCss.slice(controlsCss.indexOf('.report-page .report-action-button--back,'), controlsCss.indexOf('.report-page .report-action-button--back:hover'))
    expect(backRule).toContain('font-weight: 400')
  })
})

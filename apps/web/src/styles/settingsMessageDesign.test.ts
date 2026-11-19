import { describe, expect, it } from 'vitest'
// @ts-expect-error Test-only Node API is outside the browser type set.
import { readFileSync } from 'node:fs'
declare const process: { cwd: () => string }

const source = (path: string): string => readFileSync(`${process.cwd()}/${path}`, 'utf8')
const jiraCss = source('src/styles/settings-jira.css')
const templateCss = source('src/styles/settings-template.css')

const rule = (css: string, selector: string): string => {
  const start = css.indexOf(`${selector} {`)
  if (start < 0) return ''
  return css.slice(start, css.indexOf('}', start) + 1)
}

const declaredColor = (css: string, selector: string): string => {
  const match = rule(css, selector).match(/(?:^|\n)\s*color:\s*(#[0-9a-f]{6})/i)
  if (!match) throw new Error(`No declared color for ${selector}`)
  return match[1]
}

const relativeLuminance = (hex: string): number => {
  const channels = [1, 3, 5].map((offset) => Number.parseInt(hex.slice(offset, offset + 2), 16) / 255)
  const linear = channels.map((channel) => channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4)
  return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]
}

const contrast = (foreground: string, background: string): number => {
  const foregroundLuminance = relativeLuminance(foreground)
  const backgroundLuminance = relativeLuminance(background)
  return (Math.max(foregroundLuminance, backgroundLuminance) + 0.05) / (Math.min(foregroundLuminance, backgroundLuminance) + 0.05)
}

const mediaBlockContaining = (css: string, query: string, marker: string): string => {
  let searchFrom = 0
  while (true) {
    const start = css.indexOf(query, searchFrom)
    if (start < 0) return ''
    const open = css.indexOf('{', start)
    let depth = 0
    for (let index = open; index < css.length; index += 1) {
      if (css[index] === '{') depth += 1
      if (css[index] === '}') depth -= 1
      if (depth === 0) {
        const block = css.slice(start, index + 1)
        if (block.includes(marker)) return block
        break
      }
    }
    searchFrom = open + 1
  }
}

describe('Jira / Settings 状态信息层级样式契约', () => {
  it('gives Jira state levels distinct visual rules without replacing data-state', () => {
    for (const level of ['neutral', 'success', 'error', 'warning']) {
      expect(jiraCss).toContain(`.j-test-state[data-message-level='${level}']`)
    }
    expect(jiraCss).toContain(".j-test-state[data-message-level='error']")
    expect(jiraCss).toContain('background: #fff1ed')
    expect(jiraCss).toContain('border-left: 4px solid #b85d47')
    expect(jiraCss).toContain(".j-test-state[data-message-level='warning']")
    expect(jiraCss).toContain('background: #fff9eb')
    expect(jiraCss).toContain('border-left: 4px solid #b88726')
    expect(jiraCss).toContain(".j-test-state[data-state='failed']:not([data-message-level])")
    expect(jiraCss).toContain(".j-test-state[data-state='stale']:not([data-message-level])")
  })

  it('keeps Jira status readable as a status surface on mobile', () => {
    const mobile = mediaBlockContaining(jiraCss, '@media (max-width: 640px)', '.j-test-state')
    expect(mobile).toContain('display: grid')
    expect(mobile).toContain('min-height: 64px')
    expect(mobile).toContain('font-size: 10px')
  })

  it('uses level-specific readable ink for Jira message bodies and validation', () => {
    expect(rule(jiraCss, '.j-test-state p')).toContain('color: inherit')
    expect(rule(jiraCss, '.j-test-state small')).toContain('color: inherit')
    expect(rule(jiraCss, ".j-test-state[data-message-level='error']")).toContain('color: #9c4938')
    expect(rule(jiraCss, ".j-test-state[data-message-level='warning']")).toContain('color: #87651b')
    expect(rule(jiraCss, '.j-error')).toContain('color: #9c4938')
  })

  it('makes Settings save failure a bounded error surface rather than helper text', () => {
    const savebar = rule(templateCss, '.s-savebar')
    const failure = rule(templateCss, ".s-save-status[data-state='failed']")
    const drawerError = rule(templateCss, '.s-drawer-error')
    expect(savebar).toContain('border: 1px solid #e4ece7')
    expect(savebar).toContain('background: #fbfdfb')
    expect(failure).toContain('background: #fff4ef')
    expect(failure).toContain('border: 1px solid #edc9bc')
    expect(failure).toContain('border-left: 4px solid #b85d47')
    expect(failure).toContain('font-weight: 600')
    expect(drawerError).toContain('background: #fff4ef')
    expect(drawerError).toContain('border-left: 4px solid #b85d47')
  })

  it('keeps message label and status ink at readable contrast on their archived surfaces', () => {
    expect(contrast(declaredColor(templateCss, '.template-recovery-action-group__label'), '#ffffff')).toBeGreaterThanOrEqual(4.5)
    expect(contrast(declaredColor(templateCss, ".template-recovery-action-group[data-risk='destructive'] .template-recovery-action-group__label"), '#fffaf7')).toBeGreaterThanOrEqual(4.5)
    expect(contrast(declaredColor(templateCss, ".s-save-status[data-state='saving']"), '#f8faf4')).toBeGreaterThanOrEqual(4.5)
    expect(contrast(declaredColor(jiraCss, '.j-test-state strong'), '#f7faf8')).toBeGreaterThanOrEqual(4.5)
    expect(contrast(declaredColor(templateCss, '.template-recovery-action-group > button.is-destructive'), '#ffffff')).toBeGreaterThanOrEqual(4.5)
  })

  it('uses fill and brightness for keyboard focus without changing the base border', () => {
    const focus = rule(templateCss, '.s-save-actions button:focus-visible,\n.s-drawer-btn:focus-visible')
    expect(focus).toContain('outline: none')
    expect(focus).toContain('box-shadow: none')
    expect(focus).toContain('filter: brightness(0.9)')
    expect(focus).not.toContain('border-color:')
    const mobile = mediaBlockContaining(templateCss, '@media (max-width: 640px)', '.s-savebar')
    expect(mobile).toContain('grid-template-columns: minmax(0, 1fr)')
    expect(mobile).toContain('min-height: 44px')
    expect(mobile).toContain('font-size: 10px')
  })
})

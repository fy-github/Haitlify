import { describe, expect, it } from 'vitest'
// @ts-expect-error Test-only Node API is outside the browser type set.
import { readFileSync } from 'node:fs'
declare const process: { cwd: () => string }

const app = readFileSync(`${process.cwd()}/src/components/views/ApiExecutionPage.vue`, 'utf8') as string
const css = readFileSync(`${process.cwd()}/src/styles/studio-canvas.css`, 'utf8') as string
const block = (selector: string): string => {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return new RegExp(`(?:^|\\n)\\s*${escaped}\\s*\\{([^}]*)\\}`).exec(css)?.[1] || ''
}

const topbarStart = app.indexOf('<header v-if="automationKind === \'api\'"')
const topbar = app.slice(topbarStart, app.indexOf('</header>', topbarStart))
const openTag = (label: string): string => {
  const labelAt = topbar.indexOf(`\n              ${label}\n`)
  const start = topbar.lastIndexOf('<el-button', labelAt)
  return topbar.slice(start, topbar.indexOf('>', start) + 1)
}

describe('API 执行页工具栏按钮契约(定稿 api-v3)', () => {
  it('只有「执行筛选 N 条」是主按钮', () => {
    expect(topbar).toContain('{{ apiExecutionScopeLabel }}')
    expect(openTag('{{ apiExecutionScopeLabel }}')).toContain('type="primary"')
    expect(topbar.split('type="primary"').length - 1).toBe(1)
    expect(topbar).not.toContain('type="success"')
  })

  it('环境配置 / 运行配置 / 查看报告 / 导出用例都是次级按钮', () => {
    for (const label of ['环境配置', '运行配置', '查看报告', '导出用例']) {
      const tag = openTag(label)
      expect(tag, label).toContain('<el-button')
      expect(tag, label).not.toContain('type="primary"')
      expect(tag, label).not.toContain('plain')
    }
  })

  it('常态按钮是定稿的灰底 20px 胶囊,不是旧的半透明白底 11px 圆角', () => {
    const base = block('.automation-topbar .toolbar .el-button:not(.is-link):not(.is-text)')
    expect(base).toContain('border-radius: 20px')
    expect(base).toContain('background: #f4f7f6')
    expect(base).toContain('color: #3f5a50')
    expect(base).toContain('border: 1px solid #e5ece8')
    expect(base).toContain('font-size: 11px')
    expect(base).toContain('font-weight: 500')
    expect(base).toContain('box-shadow: none')
  })

  it('主按钮是薄荷底深字,不再使用深青渐变和投影', () => {
    const primary = block('.automation-topbar .toolbar .el-button--primary:not(.is-plain):not(.is-link):not(.is-text)')
    expect(primary).toContain('background: #54d9b5')
    expect(primary).toContain('color: #153d32')
    expect(primary).toContain('border-color: #42c5a3')
    expect(primary).toContain('box-shadow: none')
    expect(primary).not.toContain('linear-gradient')
  })

  it('hover / 键盘焦点只压亮度,不加投影也不加描边', () => {
    const hover = block('.automation-topbar .toolbar .el-button:not(.is-link):not(.is-text):hover,\n.automation-topbar .toolbar .el-button:not(.is-link):not(.is-text):focus-visible')
    expect(hover).toContain('filter: brightness(0.96)')
    expect(hover).toContain('box-shadow: none')
    expect(hover).not.toContain('outline')
  })

  it('窄屏跟随定稿 36px / 10px 规格,并且不产生横向溢出', () => {
    const mediaAt = css.indexOf('@media (max-width: 640px) {\n  .automation-topbar .toolbar')
    expect(mediaAt).toBeGreaterThan(-1)
    const mobile = css.slice(mediaAt, mediaAt + 600)
    expect(mobile).toContain('gap: 6px')
    expect(mobile).toContain('min-height: 36px')
    expect(mobile).toContain('padding: 7px 10px')
    expect(mobile).toContain('font-size: 10px')
  })
})

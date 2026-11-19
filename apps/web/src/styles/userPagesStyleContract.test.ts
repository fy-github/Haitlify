import { describe, expect, it } from 'vitest'

// 复核轮 2 的 P3 视觉项(纯样式契约,不启动浏览器):
// #23 用户表头 th 必须是定稿 #8a998e / 500 / 40(实盘曾被打成 #717884 / 400 / 44);
// #26 窄屏抽屉操作条的负边距必须与抽屉正文内边距一致,否则容器右缘越界 4px。
// Vitest/Vite 下 .css?raw 返回空串,故直接读盘校验(与 pageHeadStyleContract 同一做法)。
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
/** 去掉注释:历史说明里可以出现旧值,但生效的声明里不允许 */
const withoutComments = (css: string) => css.replace(/\/\*[\s\S]*?\*\//g, '')
const rule = (css: string, selector: string): string => {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return normalize(new RegExp(`(?:^|\\n)\\s*${escaped}\\s*\\{([^}]*)\\}`).exec(css)?.[1] ?? '')
}

/**
 * 取出包含 marker 的 @media 块正文(按花括号配平)。
 * 同一断点可能出现多次(前一处是别的组件),必须按内容定位而不是取第一个。
 */
const mediaBlock = (css: string, query: string, marker: string): string => {
  let start = css.indexOf(query)
  while (start >= 0) {
    const open = css.indexOf('{', start)
    if (open < 0) return ''
    let depth = 0
    for (let index = open; index < css.length; index += 1) {
      if (css[index] === '{') depth += 1
      else if (css[index] === '}') {
        depth -= 1
        if (depth === 0) {
          const block = css.slice(open + 1, index)
          if (block.includes(marker)) return block
          break
        }
      }
    }
    start = css.indexOf(query, start + query.length)
  }
  return ''
}

const numeric = (value: string): number | undefined => {
  const match = /(-?\d+(?:\.\d+)?)px/.exec(value)
  return match ? Number(match[1]) : undefined
}

describe('用户管理定稿视觉契约(R2 #23/#26)', () => {
  it('表头 th 使用定稿的 #8a998e / 500 / 40,且不残留旧值 #717884', () => {
    const css = cssSource('/src/styles/settings-pages.css')
    const th = rule(css, '.user-management-table .el-table__header th')
    expect(th, 'th 覆盖规则缺失').toBeTruthy()
    expect(th).toContain('color: #8a998e')
    expect(th).toContain('font-weight: 500')
    expect(th).toContain('height: 40px')
    expect(th).toContain('background: #f7f9f8')
    expect(th).toContain('border-bottom: 1px solid #e9eeeb')
    expect(withoutComments(css)).not.toContain('#717884')
  })

  it('窄屏抽屉操作条的负边距与抽屉正文内边距一致(不再越界 4px)', () => {
    const pages = cssSource('/src/styles/settings-pages.css')
    const canvas = cssSource('/src/styles/studio-canvas.css')
    // 抽屉正文在窄屏的内边距(ResponsiveDrawer 的实际来源)
    const narrowBody = mediaBlock(canvas, '@media (max-width: 899px)', '.el-drawer.studio-responsive-drawer .el-drawer__body')
    const narrowBodyPadding = numeric(rule(narrowBody, '.el-drawer.studio-responsive-drawer .el-drawer__body'))
    expect(narrowBodyPadding, '抽屉正文窄屏内边距未找到').toBeTypeOf('number')
    const narrow = mediaBlock(pages, '@media (max-width: 900px)', '.user-drawer .drawer-actions')
    const actions = rule(narrow, '.user-drawer .drawer-actions')
    expect(actions, '窄屏操作条覆盖规则缺失').toBeTruthy()
    const left = /margin-left:\s*(-?\d+(?:\.\d+)?)px/.exec(actions)?.[1]
    const right = /margin-right:\s*(-?\d+(?:\.\d+)?)px/.exec(actions)?.[1]
    expect(left, '窄屏操作条缺少 margin-left').toBeTruthy()
    expect(right, '窄屏操作条缺少 margin-right').toBeTruthy()
    expect(Math.abs(Number(left))).toBe(narrowBodyPadding)
    expect(Math.abs(Number(right))).toBe(narrowBodyPadding)
  })
})

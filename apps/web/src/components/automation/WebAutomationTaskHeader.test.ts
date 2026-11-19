// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it } from 'vitest'
import WebAutomationTaskHeader from './WebAutomationTaskHeader.vue'
import headerSource from './WebAutomationTaskHeader.vue?raw'

// This test runs in Vitest's jsdom environment, where Vite rewrites import.meta.url to a
// non-file URL, so the design CSS is read from disk relative to the project root instead.
// @ts-expect-error Node's test-only filesystem API is intentionally not part of the browser type set.
import { readFileSync } from 'node:fs'
declare const process: { cwd: () => string }

const designSource = (() => {
  // ?raw 对 .css 在 Vite/vitest 下返回空串，故读盘校验定稿实测值（不弱化样式断言）。
  const candidates = [
    `${process.cwd()}/src/components/automation/web-execution-design.css`,
    `${process.cwd()}/apps/web/src/components/automation/web-execution-design.css`
  ]
  for (const candidate of candidates) {
    try {
      return readFileSync(candidate, 'utf8')
    } catch {
      // try the next candidate
    }
  }
  throw new Error(`web-execution-design.css not found from ${process.cwd()}`)
})()

const mountedWrappers: Array<{ unmount: () => void }> = []

afterEach(() => {
  mountedWrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})

const designRule = (selector: string): string => {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = designSource.match(new RegExp(`(?:^|\\n)\\s*${escaped}\\s*\\{([^}]*)\\}`))
  return match?.[1] || ''
}

const baseProps = () => ({
  executableCount: 12,
  moduleCount: 3,
  latestRunLabel: '最近执行：今天 10:24',
  executeLabel: '执行筛选 12 条',
  canExecute: true
})

const mountHeader = (overrides: Record<string, unknown> = {}) => {
  const wrapper = mount(WebAutomationTaskHeader, {
    props: { ...baseProps(), ...overrides },
    global: { plugins: [ElementPlus] },
    attachTo: document.body
  })
  mountedWrappers.push(wrapper)
  return wrapper
}

describe('WebAutomationTaskHeader', () => {
  it('renders the confirmed Web page heading at the measured title scale', () => {
    const wrapper = mountHeader()

    expect(wrapper.get('h1').text()).toBe('Web 自动化')
    expect(wrapper.get('h1').classes()).toContain('web-ed-head__title')
    expect(wrapper.text()).toContain('选择执行范围，确认运行配置，再进入任务监控。')
    expect(wrapper.text()).toContain('最近执行：今天 10:24')
    expect(wrapper.find('.eyebrow').exists()).toBe(false)
    expect(wrapper.find('.metric-card').exists()).toBe(false)

    // 定稿实测：Web 页头 H1 24px / 600（修复前为 16px）。
    expect(designRule('.web-ed-head__title')).toMatch(/font-size:\s*24px/)
    expect(designRule('.web-ed-head__title')).toMatch(/font-weight:\s*600/)
  })

  it('keeps the confirmed top action group and drops the retired shortcuts', () => {
    const wrapper = mountHeader()

    const actionLabels = wrapper.findAll('.web-ed-actions button').map((button) => button.text())
    expect(actionLabels).toEqual(['执行历史', '环境配置', '运行配置', '导出', '执行筛选 12 条'])
    expect(wrapper.find('[data-testid="generate-web-cases"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('从用例库生成')
    expect(wrapper.text()).not.toContain('Session')
  })

  it('uses the measured action typography, pill radius and neutral/primary colors', () => {
    const wrapper = mountHeader()

    const light = wrapper.get('[data-testid="open-web-environment"]')
    const primary = wrapper.get('[data-testid="execute-web-automation"]')
    expect(light.classes()).toContain('web-ed-btn')
    expect(primary.classes()).toContain('web-ed-btn--primary')

    // 定稿实测：顶部动作 11px / 34–35px 高 / 20px 圆角，浅底描边 + 薄荷主按钮。
    expect(designRule('.web-ed-btn')).toMatch(/font-size:\s*11px/)
    expect(designRule('.web-ed-btn')).toMatch(/min-height:\s*34px/)
    expect(designRule('.web-ed-btn')).toMatch(/border-radius:\s*20px/)
    expect(designRule('.web-ed-btn')).toMatch(/background:\s*#fff/)
    expect(designRule('.web-ed-btn')).toMatch(/border:\s*1px solid #e8ebee/)
    expect(designRule('.web-ed-btn')).toMatch(/color:\s*#535a64/)
    expect(designRule('.web-ed-btn--primary')).toMatch(/background:\s*#4cd8b4/)
    expect(designRule('.web-ed-btn--primary')).toMatch(/color:\s*#163f34/)
    expect(designRule('.web-ed-actions')).toMatch(/gap:\s*10px/)
  })

  it('emits environment, runtime-config, history, export and execute intents without mutating props', async () => {
    const wrapper = mountHeader()
    const originalProps = { ...wrapper.props() }

    await wrapper.get('[data-testid="open-web-history"]').trigger('click')
    await wrapper.get('[data-testid="open-web-environment"]').trigger('click')
    await wrapper.get('[data-testid="open-web-runtime-config"]').trigger('click')
    await wrapper.get('[data-testid="export-web-cases"]').trigger('click')
    await wrapper.get('[data-testid="execute-web-automation"]').trigger('click')

    expect(wrapper.emitted('openHistory')).toHaveLength(1)
    expect(wrapper.emitted('editEnvironment')).toHaveLength(1)
    expect(wrapper.emitted('openRuntimeConfig')).toHaveLength(1)
    expect(wrapper.emitted('exportCases')).toHaveLength(1)
    expect(wrapper.emitted('execute')).toHaveLength(1)
    expect(wrapper.props()).toMatchObject(originalProps)
  })

  it('labels the launch button from the real scope and blocks it when execution is unavailable', async () => {
    const blocked = mountHeader({ canExecute: false, executeLabel: '执行筛选 0 条' })
    const button = blocked.get('[data-testid="execute-web-automation"]')

    expect(button.text()).toBe('执行筛选 0 条')
    expect(button.attributes('disabled')).toBeDefined()
    await button.trigger('click')
    expect(blocked.emitted('execute')).toBeUndefined()

    const selected = mountHeader({ executeLabel: '执行选中 3 条' })
    expect(selected.get('[data-testid="execute-web-automation"]').text()).toBe('执行选中 3 条')
  })

  it('keeps the retired case-generation event contract available without a header entry', () => {
    const emitsBlock = headerSource.slice(headerSource.indexOf('defineEmits'), headerSource.indexOf('</script>'))
    expect(emitsBlock).toContain('generateCases')
  })
})

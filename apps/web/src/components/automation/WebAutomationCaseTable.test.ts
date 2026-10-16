// @vitest-environment jsdom
import { mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it } from 'vitest'
import type { SavedWebAutomationRunCase } from '../../domain/webAutomationRepository'
import WebAutomationCaseTable from './WebAutomationCaseTable.vue'
import componentSource from './WebAutomationCaseTable.vue?raw'

const makeCase = (id: string, name: string, browser: SavedWebAutomationRunCase['browser']): SavedWebAutomationRunCase => ({
  id,
  automationLibraryId: `library-${id}`,
  savedAt: '2026-07-27T00:00:00.000Z',
  sourceCaseId: `source-${id}`,
  project: '云平台',
  module: '项目管理',
  pageName: `${name}页面`,
  name,
  startUrl: `https://example.test/${id}`,
  browser,
  viewport: '1440x900',
  authenticationMode: 'environment',
  steps: [],
  runOrder: 1,
  mockOutcome: 'passed'
})

const cases = [
  makeCase('WEB-001', '登录成功', 'chromium'),
  makeCase('WEB-002', '保存失败', 'firefox'),
  makeCase('WEB-003', '接口错误', 'webkit'),
  makeCase('WEB-004', '尚未执行', 'chromium')
]
const wrappers: VueWrapper[] = []
const mountTable = (props = {}) => {
  const wrapper = mount(WebAutomationCaseTable, {
    attachTo: document.body,
    props: {
      cases,
      selectedIds: ['WEB-001', 'HIDDEN-ID'],
      project: '云平台',
      projects: [
        { value: '云平台', label: '云平台', count: 4 },
        { value: '订单平台', label: '订单平台', count: 2 }
      ],
      module: '项目管理',
      modules: [
        { value: '项目管理', label: '项目管理', count: 2 },
        { value: '缺陷管理', label: '缺陷管理', count: 1 }
      ],
      source: 'ALL',
      status: 'ALL',
      keyword: '',
      resultStatusById: { 'WEB-001': 'passed', 'WEB-002': 'failed', 'WEB-003': 'error', 'WEB-004': 'pending' },
      emptyKind: 'none',
      ...props
    },
    global: { plugins: [ElementPlus] }
  })
  wrappers.push(wrapper)
  return wrapper
}

const flushTable = async (wrapper: ReturnType<typeof mountTable>) => {
  await wrapper.vm.$nextTick()
  await new Promise((resolve) => setTimeout(resolve, 0))
  await wrapper.vm.$nextTick()
}

const chooseSelectOption = async (wrapper: ReturnType<typeof mountTable>, label: string, optionText: string) => {
  await wrapper.get(`[aria-label="${label}"]`).trigger('click')
  await wrapper.vm.$nextTick()
  const option = Array.from(document.body.querySelectorAll('.el-select-dropdown__item'))
    .find((item) => item.textContent?.includes(optionText)) as HTMLElement | undefined
  expect(option).toBeTruthy()
  option?.click()
  await wrapper.vm.$nextTick()
  await new Promise((resolve) => setTimeout(resolve, 0))
}

afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})

describe('WebAutomationCaseTable', () => {
  it('renders the exact columns, combined case details, and statuses', async () => {
    const wrapper = mountTable()
    await flushTable(wrapper)

    expect(wrapper.get('[data-testid="web-automation-case-table"]').element.tagName).toBe('SECTION')
    const headers = wrapper.findAll('thead th').map((header) => header.text().trim())
    expect(headers).toEqual(['选择', '用例', '页面', '浏览器 / 分辨率', '执行状态', '操作'])
    expect(wrapper.findAll('thead th')).toHaveLength(6)
    for (const value of ['WEB-001', '登录成功', '登录成功页面', 'https://example.test/WEB-001', 'chromium', '1440x900', '通过', '失败']) expect(wrapper.text()).toContain(value)
    expect(wrapper.find('.web-automation-case-table__status.is-passed svg').exists()).toBe(true)
    expect(wrapper.find('.web-automation-case-table__status.is-failed svg').exists()).toBe(true)
    expect(wrapper.find('.web-automation-case-table__status.is-error').text()).toBe('错误')
    expect(wrapper.find('.web-automation-case-table__status.is-error svg').exists()).toBe(true)
    expect(wrapper.get('.web-automation-case-table__scroll').element).toBeInstanceOf(HTMLElement)
  })

  it('marks toolbar groups with stable hooks for responsive layout regressions', async () => {
    const wrapper = mountTable()
    await flushTable(wrapper)

    expect(wrapper.get('[data-testid="web-case-table-toolbar"]').classes()).toContain('web-automation-case-table__toolbar')
    expect(wrapper.get('[data-testid="web-case-table-filters"]').classes()).toContain('web-automation-case-table__filters')
    expect(wrapper.get('[data-testid="web-case-table-commands"]').classes()).toContain('web-automation-case-table__commands')
    expect(wrapper.find('[aria-label="Web 自动化项目"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="Web 自动化模块"]').exists()).toBe(true)
  })

  it('keeps batch commands nonshrinking and nonwrapping when the toolbar wraps', () => {
    expect(componentSource).toMatch(/\.web-automation-case-table\s*\{[^}]*container-type:\s*inline-size;/s)
    expect(componentSource).toMatch(/\.web-automation-case-table__toolbar\s*\{[^}]*flex-wrap:\s*wrap;[^}]*overflow:\s*visible;/s)
    expect(componentSource).toMatch(/\.web-automation-case-table__filters\s*\{[^}]*flex:\s*1 1 520px;[^}]*min-width:\s*min\(100%, 360px\);[^}]*max-width:\s*760px;/s)
    expect(componentSource).toMatch(/\.web-automation-case-table__commands\s*\{[^}]*flex:\s*0 0 auto;[^}]*white-space:\s*nowrap;/s)
    expect(componentSource).toMatch(/\.web-automation-case-table__commands button\s*\{[^}]*flex:\s*0 0 auto;/s)
  })

  it('keeps native checkbox semantics with a polished stable control surface', () => {
    expect(componentSource).toMatch(/input\[type='checkbox'\]\s*\{[^}]*appearance:\s*none;[^}]*width:\s*16px;[^}]*border:\s*1px solid var\(--border-strong\);/s)
    expect(componentSource).toMatch(/input\[type='checkbox'\]:checked\s*\{[^}]*background:\s*var\(--brand-primary\);/s)
    expect(componentSource).toMatch(/input\[type='checkbox'\]:checked::after\s*\{[^}]*transform:\s*rotate\(45deg\) scale\(1\);/s)
  })

  it('emits controlled filters, checkbox, batch commands, and row actions', async () => {
    const wrapper = mountTable()
    await flushTable(wrapper)

    await wrapper.get('input[aria-label="选择 Web 用例 WEB-002"]').setValue(true)
    await chooseSelectOption(wrapper, 'Web 自动化项目', '订单平台')
    await chooseSelectOption(wrapper, 'Web 自动化模块', '缺陷管理')
    await wrapper.get('[aria-label="搜索 Web 用例名称、页面或 URL"]').setValue('失败')
    await wrapper.get('[aria-label="Web 用例状态"]').trigger('click')
    await wrapper.vm.$nextTick()
    const statusOption = Array.from(document.body.querySelectorAll('.el-select-dropdown__item'))
      .find((item) => item.textContent?.trim() === '失败') as HTMLElement
    expect(statusOption).toBeTruthy()
    statusOption.click()
    await wrapper.vm.$nextTick()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await wrapper.get('[data-testid="select-filtered-web-cases"]').trigger('click')
    await wrapper.get('[data-testid="clear-web-case-selection"]').trigger('click')
    await wrapper.get('[aria-label="查看 Web 用例 WEB-001 日志"]').trigger('click')
    await wrapper.get('[aria-label="将 Web 用例 WEB-002 转为缺陷"]').trigger('click')
    await wrapper.get('[aria-label="删除 Web 用例 WEB-001"]').trigger('click')

    expect(wrapper.emitted('toggleCase')?.[0]).toEqual([cases[1], true])
    expect(wrapper.emitted('updateProject')?.[0]).toEqual(['订单平台'])
    expect(wrapper.emitted('updateModule')?.[0]).toEqual(['缺陷管理'])
    const keywordEvents = wrapper.emitted('updateKeyword') ?? []
    expect(keywordEvents[keywordEvents.length - 1]).toEqual(['失败'])
    expect(wrapper.emitted('updateStatus')?.[0]).toEqual(['failed'])
    expect(wrapper.emitted('selectFiltered')?.[0]).toEqual([])
    expect(wrapper.emitted('clearSelected')?.[0]).toEqual([])
    expect(wrapper.emitted('openLog')?.[0]).toEqual([cases[0]])
    expect(wrapper.emitted('transferDefect')?.[0]).toEqual([cases[1]])
    expect(wrapper.emitted('deleteCase')?.[0]).toEqual([cases[0]])
    expect(wrapper.props('keyword')).toBe('')
    expect(wrapper.props('selectedIds')).toEqual(['WEB-001', 'HIDDEN-ID'])
  })

  it('uses status text and icons with exact compact action eligibility and shows project/module controls', async () => {
    const wrapper = mountTable()
    await flushTable(wrapper)

    const eligibility = [
      { id: 'WEB-001', defect: false },
      { id: 'WEB-002', defect: true },
      { id: 'WEB-003', defect: true },
      { id: 'WEB-004', defect: false }
    ]
    for (const row of eligibility) {
      expect(wrapper.find(`[aria-label="查看 Web 用例 ${row.id} 日志"]`).exists()).toBe(true)
      expect(wrapper.find(`[aria-label="删除 Web 用例 ${row.id}"]`).exists()).toBe(true)
      expect(wrapper.find(`[aria-label="将 Web 用例 ${row.id} 转为缺陷"]`).exists()).toBe(row.defect)
    }
    expect(wrapper.find('.web-automation-case-table__status.is-pending').text()).toBe('未执行')
    expect(wrapper.find('.web-automation-case-table__status.is-pending svg').exists()).toBe(true)
    expect(wrapper.find('[aria-label="Web 自动化项目"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="Web 自动化模块"]').exists()).toBe(true)
  })

  it('falls back to pending when no result exists', async () => {
    const wrapper = mountTable({ cases: [cases[0]], resultStatusById: {} })
    await flushTable(wrapper)

    expect(wrapper.get('.web-automation-case-table__status.is-pending').text()).toBe('未执行')
    expect(wrapper.find('[aria-label="将 Web 用例 WEB-001 转为缺陷"]').exists()).toBe(false)
  })

  it('deduplicates duplicate IDs by retaining the first row without mutating input', async () => {
    const duplicate = { ...cases[0], name: '不应显示的重复项', pageName: '重复页面' }
    const input = [cases[0], duplicate]
    const wrapper = mountTable({ cases: input, selectedIds: ['WEB-001'] })
    await flushTable(wrapper)

    expect(wrapper.findAll('tbody tr')).toHaveLength(1)
    expect(wrapper.get('tbody tr').text()).toContain('登录成功')
    expect(wrapper.get('tbody tr').text()).not.toContain('不应显示的重复项')
    expect(input).toEqual([cases[0], duplicate])
  })

  it('keeps row status and actions scoped to each ID and follows controlled prop updates', async () => {
    const wrapper = mountTable({ cases: [cases[0], cases[1]], selectedIds: [], resultStatusById: {} })
    await flushTable(wrapper)
    const rowFor = (id: string) => wrapper.get(`input[aria-label="选择 Web 用例 ${id}"]`).element.closest('tr')!

    expect(rowFor('WEB-001').textContent).toContain('未执行')
    expect(rowFor('WEB-001').querySelector(`[aria-label="将 Web 用例 WEB-001 转为缺陷"]`)).toBeNull()
    expect(rowFor('WEB-002').querySelector(`[aria-label="查看 Web 用例 WEB-002 日志"]`)).not.toBeNull()

    await wrapper.setProps({ selectedIds: ['WEB-001'], resultStatusById: { 'WEB-001': 'error', 'WEB-002': 'passed' } })
    await flushTable(wrapper)

    expect((wrapper.get('input[aria-label="选择 Web 用例 WEB-001"]').element as HTMLInputElement).checked).toBe(true)
    expect(rowFor('WEB-001').textContent).toContain('错误')
    expect(rowFor('WEB-001').querySelector(`[aria-label="将 Web 用例 WEB-001 转为缺陷"]`)).not.toBeNull()
    expect(rowFor('WEB-002').textContent).toContain('通过')
    expect(rowFor('WEB-002').querySelector(`[aria-label="将 Web 用例 WEB-002 转为缺陷"]`)).toBeNull()
  })

  it('disables commands from controlled state and renders each empty-state command', async () => {
    const noCases = mountTable({ cases: [], selectedIds: [], emptyKind: 'no-cases' })
    expect(noCases.get('[data-testid="select-filtered-web-cases"]').attributes('disabled')).toBeDefined()
    expect(noCases.get('[data-testid="clear-web-case-selection"]').attributes('disabled')).toBeDefined()
    expect(noCases.text().match(/从用例库生成/g)).toHaveLength(1)
    await noCases.get('[data-testid="generate-web-cases"]').trigger('click')
    expect(noCases.emitted('generateCases')?.[0]).toEqual([])

    const filtered = mountTable({ cases: [], selectedIds: ['HIDDEN-ID'], emptyKind: 'filtered' })
    expect(filtered.get('[data-testid="clear-web-case-selection"]').attributes('disabled')).toBeUndefined()
    expect(filtered.find('[aria-label="Web 自动化项目"]').exists()).toBe(true)
    expect(filtered.find('[aria-label="Web 自动化模块"]').exists()).toBe(true)
    expect(filtered.find('[aria-label="Web 用例状态"]').exists()).toBe(true)
    expect(filtered.find('[aria-label="搜索 Web 用例名称、页面或 URL"]').exists()).toBe(true)
    await filtered.get('[data-testid="clear-web-case-filters"]').trigger('click')
    expect(filtered.emitted('clearFilters')?.[0]).toEqual([])
  })
})

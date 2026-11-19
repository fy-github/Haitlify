// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import ElementPlus from 'element-plus'
vi.mock('../../domain/jiraIssueRepository', () => ({ loadJiraIssues: vi.fn(async () => ({ items: [] })) }))
vi.mock('../../domain/csvExport', () => ({ downloadCsv: vi.fn() }))
const key = 'ai-test-platform.defect-drafts.v1'
const draft = (n: number) => ({ defectId: `d${n}`, sourceCaseId: `API-${n}`, sourceReportCaseId: `r${n}`, projectName: 'Fixture', issueType: 'Bug', summary: `Draft ${n}`, priority: 'High', assignee: 'alice', description: 'Description', faultCategory: 'API', blocksIssueKey: '', status: 'pending_jira_export', jiraFieldValues: {}, createdAt: '2026-10-29', updatedAt: '2026-10-29' })
let wrapper: VueWrapper
async function setup(count = 14) {
  const { useDefects } = await import('../../ui/useDefects')
  localStorage.setItem(key, JSON.stringify(Array.from({ length: count }, (_, n) => draft(n + 1))))
  const confirmDanger = vi.fn(async () => false)
  const state = useDefects({ storage: localStorage, confirmDanger })
  const { default: View } = await import('./DefectsView.vue')
  wrapper = mount(View, { attachTo: document.body, global: { plugins: [ElementPlus] } })
  await flushPromises()
  return { state, confirmDanger }
}
beforeEach(() => { vi.resetModules(); localStorage.clear(); vi.stubGlobal('fetch', vi.fn(() => { throw Error('Network forbidden') })) })
afterEach(() => { wrapper?.unmount(); vi.unstubAllGlobals() })
describe('defect independent detail contract', () => {
  it('renders a read-only list with no row text inputs', async () => {
    await setup()
    expect(wrapper.findAll('tbody input:not([type="checkbox"])')).toHaveLength(0)
  })
  it('opens an independent read-only detail instead of a dialog, then stages edit/save', async () => {
    const { state } = await setup()
    await wrapper.find('tbody button').trigger('click')
    expect(wrapper.find('[data-testid="defect-detail"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="defect-list"]').exists()).toBe(false)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-testid="defect-detail"] input')).toHaveLength(0)
    await wrapper.find('[data-action="edit-draft"]').trigger('click')
    const before = localStorage.getItem(key)
    await wrapper.find('input[aria-label="概要"]').setValue('Changed draft')
    expect(localStorage.getItem(key)).toBe(before)
    await wrapper.find('[data-action="save-draft"]').trigger('click')
    expect(JSON.parse(localStorage.getItem(key)!)[0].summary).toBe('Changed draft')
    expect(state.defectEditVisible.value).toBe(false)
    expect(wrapper.find('[data-testid="defect-detail"]').exists()).toBe(true)
  })
  it('guards cancel and back when dirty; confirmed discard restores exact opener and both axes', async () => {
    const { state, confirmDanger } = await setup()
    state.defectPage.value = 2; await nextTick()
    const scroll = wrapper.find('[data-testid="defect-list-scroll"]').element as HTMLElement
    scroll.scrollTop = 80; scroll.scrollLeft = 140; await wrapper.find('[data-testid="defect-list-scroll"]').trigger('scroll')
    const opener = wrapper.find('button[data-opener="action:d7"]')
    await opener.trigger('click')
    await wrapper.find('[data-action="edit-draft"]').trigger('click')
    await wrapper.find('input[aria-label="概要"]').setValue('Unsaved')
    await wrapper.find('[data-action="cancel-edit"]').trigger('click'); await flushPromises()
    expect(confirmDanger).toHaveBeenCalled()
    expect(wrapper.find('input[aria-label="概要"]').exists()).toBe(true)
    await wrapper.find('[data-action="back-list"]').trigger('click'); await flushPromises()
    expect(wrapper.find('[data-testid="defect-detail"]').exists()).toBe(true)
    confirmDanger.mockResolvedValue(true)
    await wrapper.find('[data-action="back-list"]').trigger('click'); await flushPromises()
    expect(state.defectPage.value).toBe(2)
    const restored = wrapper.find('[data-testid="defect-list-scroll"]').element as HTMLElement
    expect([restored.scrollTop, restored.scrollLeft]).toEqual([80, 140])
    expect(document.activeElement?.getAttribute('data-opener')).toBe('action:d7')
    expect(JSON.parse(localStorage.getItem(key)!)[6].summary).toBe('Draft 7')
  })
  it('restores the list scroll offset and exact opener after returning from detail', async () => {
    await setup()
    const scroller = wrapper.find('[data-testid="defect-list-scroll"]')
    ;(scroller.element as HTMLElement).scrollLeft = 140
    ;(scroller.element as HTMLElement).scrollTop = 60
    await scroller.trigger('scroll')
    await wrapper.find('tbody .r-link').trigger('click')
    await wrapper.find('[data-action="back-list"]').trigger('click')
    await flushPromises()
    const restored = wrapper.find('[data-testid="defect-list-scroll"]').element as HTMLElement
    expect([restored.scrollLeft, restored.scrollTop]).toEqual([140, 60])
    expect(document.activeElement?.getAttribute('data-opener')).toBe('action:d1')
  })
  it('clamps the page after deleting the last draft on a later page', async () => {
    const { state, confirmDanger } = await setup(7)
    confirmDanger.mockResolvedValue(true)
    state.defectPage.value = 2
    await nextTick()
    await wrapper.find('tbody .r-link').trigger('click')
    await wrapper.find('[data-action="delete-one"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="defect-list"]').exists()).toBe(true)
    expect(wrapper.find('.r-pagination').text()).toContain('1 / 1')
    expect(wrapper.findAll('tbody tr')).toHaveLength(6)
  })
  it('Jira detail stays read-only and exposes no draft writes', async () => {
    const { state } = await setup()
    await state.switchDefectSource('jira')
    state.jiraIssues.value = [{ id: 'j1', projectName: 'Fixture', issueKey: 'FIX-1', summary: 'Remote', status: 'Open', priority: 'High', assignee: 'alice', issueType: 'Bug', updatedAt: '' }]
    await nextTick()
    expect(wrapper.find('[data-action="delete-scope"]').exists()).toBe(false)
    expect(wrapper.find('[data-action="export-scope"]').exists()).toBe(false)
    await wrapper.find('tbody button').trigger('click')
    expect(wrapper.text()).toContain('Jira 问题只读')
    expect(wrapper.find('[data-action="edit-draft"]').exists()).toBe(false)
    expect(wrapper.find('[data-action="delete-one"]').exists()).toBe(false)
  })
})

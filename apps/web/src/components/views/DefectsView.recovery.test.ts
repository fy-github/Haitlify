// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
vi.mock('../../domain/jiraIssueRepository', () => ({ loadJiraIssues: vi.fn(async () => ({ items: [] })) }))
vi.mock('../../domain/csvExport', () => ({ downloadCsv: vi.fn() }))
let wrapper: VueWrapper
beforeEach(() => { vi.resetModules(); localStorage.clear() })
afterEach(() => wrapper?.unmount())
describe('real defect failure surface', () => {
  it('renders full runner and flat evidence as inert text while refusing unsafe and SVG image bindings', async () => {
    const { useDefects } = await import('../../ui/useDefects')
    const evidence = { actionId: 'a1', actionType: 'click', evidenceId: 'e1', before: { pageUrl: '', pageKey: 'before-key', pageFingerprint: 'before-fingerprint' }, after: { pageUrl: '/after', pageKey: 'after-key', pageFingerprint: 'after-fingerprint' }, execution: { status: 'failed', durationMs: 0 }, resolvedTarget: { locator: '<img onerror=evil()>', visible: false, unique: true }, observationStatus: 'failed', failureType: 'locator-error', screenshotPath: 'javascript:evil()' }
    const row = { defectId: 'd1', sourceCaseId: 'c1', sourceReportCaseId: 'r1', projectName: 'Fixture', issueType: 'Bug', summary: 'Evidence', priority: '', assignee: '', description: '', faultCategory: '', blocksIssueKey: '', status: 'pending_jira_export', jiraFieldValues: {}, createdAt: '2026', updatedAt: '2026', stepEvidence: [evidence, { stepId: 'flat', name: 'Flat name', action: 'click', target: '#target', status: 'failed' }], screenshotPath: 'data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=' }
    localStorage.setItem('ai-test-platform.defect-drafts.v1', JSON.stringify([row]))
    useDefects({ storage: localStorage })
    const { default: View } = await import('./DefectsView.vue')
    wrapper = mount(View, { global: { plugins: [ElementPlus] } })
    await wrapper.find('tbody button').trigger('click')
    expect(wrapper.findAll('[data-testid="defect-evidence"] img')).toHaveLength(0)
    const raw = wrapper.find('[data-testid="defect-evidence-raw"]')
    expect(JSON.parse(raw.text()).stepEvidence[0]).toEqual(evidence)
    expect(raw.element.children).toHaveLength(0)
    expect(wrapper.text()).toContain('before-fingerprint')
    expect(wrapper.text()).toContain('Flat name')
    expect(wrapper.text()).toContain('原始')
  })
  it('mounts the same failed-create banner in ReportsView rather than only on the unopened defects page', async () => {
    const { useDefects } = await import('../../ui/useDefects')
    const storage = { getItem: () => null, setItem: () => { throw new DOMException('full', 'QuotaExceededError') } } as unknown as Storage
    const state = useDefects({ storage, navigateToDefects: () => false })
    state.createDefectsFromWebResults([{ caseId: 'c', sourceCaseId: 'c', name: 'Failure', module: '', pageName: '', startUrl: '', status: 'failed', durationMs: 0 }] as any, 'Fixture')
    const reportView = './ReportsView.vue'
    const { default: View } = await import(reportView)
    wrapper = mount(View, { global: { plugins: [ElementPlus] } })
    expect(wrapper.find('[data-testid="defect-failure-banner"]').exists()).toBe(true)
    expect(wrapper.find('[data-action="export-defect-candidate"]').exists()).toBe(true)
  })
  it('shows corrupt-store raw export and disables mutations instead of a normal empty-state claim', async () => {
    const { useDefects } = await import('../../ui/useDefects')
    localStorage.setItem('ai-test-platform.defect-drafts.v1', '{broken')
    useDefects({ storage: localStorage })
    const view = './DefectsView.vue'; const { default: View } = await import(view)
    wrapper = mount(View, { global: { plugins: [ElementPlus] } })
    expect(wrapper.find('[data-action="export-defect-raw"]').exists()).toBe(true)
    expect(wrapper.find('[data-action="open-template"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('.r-empty').text()).toContain('读取失败')
  })
  it('does not publish a stale file-read error after the import authority expires', async () => {
    const { useDefects } = await import('../../ui/useDefects')
    let rejectRead!: (error: unknown) => void
    const row = { defectId: 'd1', sourceCaseId: 'c1', sourceReportCaseId: 'r1', projectName: 'Fixture', issueType: 'Bug', summary: 'Original', priority: '', assignee: '', description: '', faultCategory: '', blocksIssueKey: '', status: 'pending_jira_export', jiraFieldValues: {}, createdAt: '2026', updatedAt: '2026' }
    localStorage.setItem('ai-test-platform.defect-drafts.v1', JSON.stringify([row]))
    useDefects({ storage: localStorage })
    const view = './DefectsView.vue'; const { default: View } = await import(view)
    wrapper = mount(View, { global: { plugins: [ElementPlus] } })
    const input = wrapper.find<HTMLInputElement>('[data-action="import-defect-json"] + input')
    Object.defineProperty(input.element, 'files', { value: [{ text: () => new Promise((_, reject) => { rejectRead = reject }) }] })
    await input.trigger('change')
    await wrapper.find('[data-view="jira"]').trigger('click')
    rejectRead(Error('expired'))
    await flushPromises()
    expect(wrapper.find('[data-testid="defect-import-error"]').exists()).toBe(false)
  })
  it('renders result-only diagnostics and explains decode failure without removing raw evidence', async () => {
    const { useDefects } = await import('../../ui/useDefects')
    const png = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII='
    const row = { defectId: 'd1', sourceCaseId: 'c1', sourceReportCaseId: 'r1', projectName: 'Fixture', issueType: 'Bug', summary: 'Diagnostics', priority: '', assignee: '', description: '', faultCategory: '', blocksIssueKey: '', status: 'pending_jira_export', jiraFieldValues: {}, createdAt: '2026', updatedAt: '2026', consoleErrors: ['console-full'], pageErrors: [], failedRequests: [] }
    localStorage.setItem('ai-test-platform.defect-drafts.v1', JSON.stringify([row]))
    const state = useDefects({ storage: localStorage })
    const view = './DefectsView.vue'; const { default: View } = await import(view)
    wrapper = mount(View, { global: { plugins: [ElementPlus] } }); await wrapper.find('tbody button').trigger('click')
    expect(wrapper.find('[data-testid="defect-evidence-raw"]').text()).toContain('console-full')
    state.defectDrafts.value[0].stepEvidence = [{ stepId: 's1', screenshotPath: png }]
    await flushPromises()
    const image = wrapper.find('[data-testid="defect-step-screenshot-0"]')
    await image.trigger('error')
    expect(wrapper.find('[data-testid="defect-step-screenshot-error-0"]').text()).toContain('截图无法加载')
    expect(wrapper.find('[data-testid="defect-evidence-raw"]').text()).toContain(png)
  })
  it('shows template-save recovery controls inside the actual template drawer', async () => {
    const { useDefects } = await import('../../ui/useDefects')
    let fail = false
    const storage = { getItem: () => null, setItem: () => { if (fail) throw new DOMException('full', 'QuotaExceededError') } } as unknown as Storage
    const state = useDefects({ storage }); const view = './DefectsView.vue'; const { default: View } = await import(view)
    wrapper = mount(View, { global: { plugins: [ElementPlus], stubs: { teleport: true } } })
    await wrapper.find('[data-action="open-template"]').trigger('click'); fail = true
    state.saveJiraCsvTemplateConfig(); await flushPromises()
    const drawer = wrapper.find('.defect-template-drawer')
    expect(drawer.find('[data-action="retry-defect-mutation"]').exists()).toBe(true)
    expect((state as any).defectRecoveryDirty.value).toBe(true)
  })
  it('mounts persistent failure actions on the list and retains dirty edit on failed save', async () => {
    const { useDefects } = await import('../../ui/useDefects')
    const row = { defectId: 'd1', sourceCaseId: 'c1', sourceReportCaseId: 'r1', projectName: 'Fixture', issueType: 'Bug', summary: 'Original', priority: '', assignee: '', description: '', faultCategory: '', blocksIssueKey: '', status: 'pending_jira_export', jiraFieldValues: {}, createdAt: '2026', updatedAt: '2026' }
    localStorage.setItem('ai-test-platform.defect-drafts.v1', JSON.stringify([row]))
    let fail = false
    const storage = { getItem: (key: string) => localStorage.getItem(key), setItem: (key: string, value: string) => { if (fail) throw new DOMException('full', 'QuotaExceededError'); localStorage.setItem(key, value) } } as Storage
    const state = useDefects({ storage, confirmDanger: async () => true })
    const { default: View } = await import('./DefectsView.vue')
    wrapper = mount(View, { global: { plugins: [ElementPlus] } })
    await wrapper.find('tbody button').trigger('click')
    await wrapper.find('[data-action="edit-draft"]').trigger('click')
    await wrapper.find('input[aria-label="概要"]').setValue('Unsaved')
    fail = true
    await wrapper.find('[data-action="save-draft"]').trigger('click'); await flushPromises()
    expect(wrapper.find('[data-testid="defect-failure-banner"]').exists()).toBe(true)
    expect(wrapper.find('[data-action="export-defect-candidate"]').exists()).toBe(true)
    expect(wrapper.find('[data-action="retry-defect-mutation"]').exists()).toBe(true)
    expect(state.defectEditDirty.value).toBe(true)
    expect((wrapper.find('input[aria-label="概要"]').element as HTMLInputElement).value).toBe('Unsaved')
  })
})

// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
let wrapper: VueWrapper
const row = { defectId: 'd1', sourceCaseId: 'c1', sourceReportCaseId: 'r1', projectName: 'Fixture', issueType: 'Bug', summary: 'Original', priority: '', assignee: '', description: '', faultCategory: '', blocksIssueKey: '', status: 'pending_jira_export', jiraFieldValues: {}, createdAt: '2026', updatedAt: '2026' }
async function setup() {
  vi.resetModules(); localStorage.clear(); sessionStorage.clear(); window.history.replaceState({}, '', '/defects')
  const { defaultTemplateConfig } = await import('./domain/templateConfigRepository')
  const template = defaultTemplateConfig()
  vi.stubGlobal('fetch', vi.fn(async (url: string) => ({ ok: true, status: 200, json: async () => String(url).includes('/case-template') ? template : String(url).includes('/api/auth/me') ? { id: 1, username: 'fixture', displayName: 'Fixture', role: 'ADMIN', mustChangePassword: false } : String(url) === '/api/projects' ? { items: [] } : {} })))
  const app = './App.vue'
  const { default: App } = await import(app)
  wrapper = mount(App, { global: { plugins: [ElementPlus] } })
  await flushPromises(); await flushPromises()
  return (wrapper.vm as any).$.setupState
}
afterEach(() => { wrapper?.unmount(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
describe('App defect authority and leaving boundary', () => {
  it('mounts a failed transfer banner on the actual execution initiating page', async () => {
    const s = await setup()
    s.navigateToNav('automation'); await flushPromises()
    const set = Storage.prototype.setItem
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (this: Storage, key, value) {
      if (key.endsWith('ai-test-platform.defect-drafts.v1')) throw new DOMException('full', 'QuotaExceededError')
      return set.call(this, key, value)
    })
    s.defects.createDefectsFromWebResults([{ caseId: 'r2', sourceCaseId: 'w2', module: '', name: 'Web', pageName: '', startUrl: '', status: 'failed', durationMs: 0 }], 'Fixture')
    await flushPromises()
    expect(s.activeNav).toBe('automation')
    expect(wrapper.find('[data-testid="defect-failure-banner"]').exists()).toBe(true)
    expect(wrapper.find('[data-action="export-defect-candidate"]').exists()).toBe(true)
  })
  it('blocks beforeunload and cancelled app navigation for dirty defect edits', async () => {
    const s = await setup()
    localStorage.setItem('ai-test-platform.workspace.user%3A1.ai-test-platform.defect-drafts.v1', JSON.stringify([row]))
    s.defects.refreshDefectDrafts(); s.defects.openDefectEdit(row); s.defects.defectEditForm.summary = 'Unsaved'
    await flushPromises()
    const event = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel')
    s.navigateToNav('reports'); await flushPromises()
    expect(s.activeNav).toBe('defects')
    expect(s.defects.defectEditForm.summary).toBe('Unsaved')
  })
  it('uses an operation-start capability and stops a deferred CSV confirmation after workspace reset', async () => {
    const s = await setup()
    const key = 'ai-test-platform.workspace.user%3A1.ai-test-platform.defect-drafts.v1'
    localStorage.setItem(key, JSON.stringify([row])); s.defects.refreshDefectDrafts()
    let accept!: (value: any) => void
    vi.spyOn(ElMessageBox, 'confirm').mockImplementation(() => new Promise(resolve => { accept = resolve }))
    const clicked = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    const pending = s.defects.exportSelectedDefectsToJiraCsv()
    await flushPromises(); s.resetWorkspaceState(); accept({ action: 'confirm' }); await pending
    expect(clicked).not.toHaveBeenCalled()
    expect(JSON.parse(localStorage.getItem(key)!)[0].status).toBe('pending_jira_export')
    expect(s.defects.defectRecoveryCandidate.value).toBeNull()
  })
})

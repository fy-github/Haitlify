// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
vi.mock('../../domain/jiraIssueRepository', () => ({ loadJiraIssues: vi.fn(async () => ({ items: [] })) }))
vi.mock('../../domain/csvExport', () => ({ downloadCsv: vi.fn() }))

// Approved preview contract: valid PNG replaces the old non-image FIXTURE dataURL.
// Original assertions/fixture are retained in implementation/ui/DefectsView.evidence.before-preview-contract.test.txt.
const png = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII='
const key = 'ai-test-platform.defect-drafts.v1'
const base = {
  projectName: 'Fixture',
  issueType: 'Bug',
  priority: 'High',
  assignee: 'alice',
  blocksIssueKey: '',
  faultCategory: 'Web自动化失败',
  status: 'pending_jira_export',
  jiraFieldValues: {},
  createdAt: '2026-10-29T00:00:00.000Z',
  updatedAt: '2026-10-29T00:00:00.000Z'
}
const evidenceDraft = {
  ...base,
  defectId: 'd-web',
  sourceCaseId: 'WEB-0001',
  sourceReportCaseId: 'web-result-1',
  summary: 'Fixture browser failure',
  description: '页面: Fixture page\n访问地址: https://example.invalid/fixture-page',
  failedStepId: 'step-2',
  screenshotPath: png,
  pageHtmlSnippet: '<main data-marker="dom">snippet</main>',
  stepLogs: [
    { stepId: 'step-1', name: '打开页面', action: 'goto', target: '/fixture-page', status: 'passed', durationMs: 800 },
    { stepId: 'step-2', name: '点击提交', action: 'click', target: '#submit', status: 'failed', durationMs: 3400, errorMessage: 'element detached' }
  ],
  stepEvidence: [
    { stepId: 'step-2', name: '点击提交', action: 'click', target: '#submit', status: 'failed', screenshotPath: png, pageUrl: 'https://example.invalid/fixture-page', capturedAt: '2026-10-29T01:00:04.000Z' }
  ]
}
const legacyDraft = {
  ...base,
  defectId: 'd-legacy',
  sourceCaseId: 'API-1',
  sourceReportCaseId: 'r1',
  summary: 'Legacy draft',
  description: 'legacy description'
}

let wrapper: VueWrapper
async function setup(drafts: unknown[]) {
  const { useDefects } = await import('../../ui/useDefects')
  localStorage.setItem(key, JSON.stringify(drafts))
  const state = useDefects({ storage: localStorage, confirmDanger: async () => false })
  const { default: View } = await import('./DefectsView.vue')
  wrapper = mount(View, { attachTo: document.body, global: { plugins: [ElementPlus] } })
  await flushPromises()
  return state
}

beforeEach(() => { vi.resetModules(); localStorage.clear(); vi.stubGlobal('fetch', vi.fn(() => { throw Error('Network forbidden') })) })
afterEach(() => { wrapper?.unmount(); vi.unstubAllGlobals() })

describe('defect detail evidence', () => {
  it('renders the saved Web steps, evidence, screenshot and DOM snippet read back from storage', async () => {
    await setup([evidenceDraft])
    await wrapper.find('tbody button').trigger('click')
    const detail = wrapper.find('[data-testid="defect-detail"]')
    expect(detail.exists()).toBe(true)
    const section = wrapper.find('[data-testid="defect-evidence"]')
    expect(section.exists()).toBe(true)
    expect(section.text()).toContain('Web 步骤与证据')
    expect(section.text()).toContain('失败步骤: step-2')

    const rows = wrapper.findAll('[data-testid="defect-step-row"]')
    expect(rows).toHaveLength(2)
    expect(rows[1].text()).toContain('click')
    expect(rows[1].text()).toContain('#submit')
    expect(rows[1].text()).toContain('element detached')
    expect(rows[1].classes()).toContain('is-failed')

    const img = wrapper.find('[data-testid="defect-evidence-screenshot"]')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe(png)
    expect(section.text()).toContain(png)

    const snippet = wrapper.find('[data-testid="defect-evidence-dom"]')
    expect(snippet.text()).toBe('<main data-marker="dom">snippet</main>')
    expect(snippet.findAll('main')).toHaveLength(0)
    expect(wrapper.html()).not.toContain('data-marker="dom">snippet</main></pre>')
  })

  it('treats stored HTML as text, never as markup', async () => {
    await setup([{ ...evidenceDraft, summary: 'x', pageHtmlSnippet: '<img src=x onerror="window.__pwned=1">' }])
    await wrapper.find('tbody button').trigger('click')
    expect(wrapper.find('[data-testid="defect-evidence-dom"]').text()).toBe('<img src=x onerror="window.__pwned=1">')
    expect(wrapper.findAll('[data-testid="defect-evidence"] img')).toHaveLength(2) // top-level and per-step screenshots only
    expect((window as unknown as Record<string, unknown>).__pwned).toBeUndefined()
  })

  it('keeps legacy drafts usable and shows no evidence block for them', async () => {
    await setup([legacyDraft])
    await wrapper.find('tbody button').trigger('click')
    expect(wrapper.find('[data-testid="defect-detail"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="defect-evidence"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('legacy description')
  })

  it('marks an unusable screenshot path instead of pretending it rendered', async () => {
    await setup([{ ...evidenceDraft, screenshotPath: 'missing/does-not-exist.png' }])
    await wrapper.find('tbody button').trigger('click')
    const img = wrapper.find('[data-testid="defect-evidence-screenshot"]')
    expect(img.exists()).toBe(false) // no approved static root: must not bind even a relative path
    expect(wrapper.find('[data-testid="defect-evidence-screenshot-missing"]').text()).toContain('原始引用已保留')
    expect(wrapper.find('[data-testid="defect-evidence-screenshot-path"]').text()).toContain('missing/does-not-exist.png')
  })
})

import { getAppState } from './ui/appTestAccess'
// @vitest-environment jsdom
// App-level smoke test: mounts the real App.vue (the single wired creation point for every
// ui/ singleton) with an expired session and verifies it falls back to the login screen.
// fetch is stubbed so bootstrapAuth -> fetchCurrentUser -> authedFetch sees a 401.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'

const expiredSessionResponse = { ok: false, status: 401, json: async () => ({ message: '未登录' }) }

const adminUser = { id: 1, username: 'admin', displayName: '管理员', role: 'ADMIN', mustChangePassword: false }

const backendResponse = (url: string) => {
  if (String(url).includes('/api/auth/me')) {
    return { ok: true, status: 200, json: async () => adminUser }
  }
  if (String(url).includes('/api/ai/run-policies')) {
    return { ok: true, status: 200, json: async () => ({ items: [] }) }
  }
  if (String(url).includes('/api/web-automation/capabilities')) {
    return { ok: true, status: 200, json: async () => ({ status: 'UP', checkedAt: '2026-10-30T10:00:00.000Z', browsers: [{ id: 'chromium', label: 'Chromium', available: true, recommendation: '' }] }) }
  }
  // All other workspace/backend calls return empty data; repositories fall back to local state.
  return { ok: true, status: 200, json: async () => ({}) }
}

describe('App.vue integration', () => {
  afterEach(() => vi.unstubAllGlobals())

  it.each([false, true])('boot never requests retired AI endpoints (stored draft=%s)', async (withDraft) => {
    vi.resetModules()
    localStorage.clear()
    window.history.replaceState({}, '', '/')
    const key = 'ai-test-platform.case-design-draft.v1'
    const archived = JSON.stringify({
      version: 1, activeStage: 'requirement', generationKind: 'web_automation', projectName: '历史项目',
      analysisRunId: 'archived-analysis', analysisRun: null,
      requirementReviewRun: null, caseReviewRun: { runId: 'archived-review', status: 'running' },
      activeReviewStage: 'case', selectedCaseId: '', sources: [], sourceErrors: {},
      extractedRequirementPoints: [], aiDraftPoints: [], generatedCases: [], generatedWebCases: [],
      analysisError: '', generationError: '', reviewError: '',
      review: { insight: { stage: 'case', summary: '', suggestions: [] }, qualityScores: [], conflicts: [], findingDecisions: {} },
      updatedAt: '2026-01-01T00:00:00Z'
    })
    if (withDraft) localStorage.setItem(key, archived)
    const requests: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      requests.push(String(url))
      return backendResponse(url)
    }))
    const { default: App } = await import('./App.vue')
    const wrapper = mount(App as never, { global: { plugins: [ElementPlus] } })
    try {
      await vi.waitFor(() => expect(wrapper.find('[data-testid="home-open-chain"]').exists()).toBe(true))
      await flushPromises()
      expect(requests.filter(url => url.includes('/api/ai/'))).toEqual([])
      if (withDraft) expect(localStorage.getItem(key)).toBe(archived)
    } finally {
      wrapper.unmount()
    }
  })

  it('finishes a restored run with legacy metadata without posting AI feedback and still saves its report', async () => {
    vi.resetModules()
    localStorage.clear()
    sessionStorage.clear()
    window.history.replaceState({}, '', '/')
    const saved = {
      id: 'case-retired', sourceCaseId: 'source-retired', project: '历史项目', module: '回归',
      pageName: '列表', name: '历史用例', startUrl: 'https://example.test', browser: 'chromium',
      viewport: '1280x720', authenticationMode: 'none', steps: [], runOrder: 1, mockOutcome: 'passed',
      automationLibraryId: 'library-retired', savedAt: '2026-01-01T00:00:00Z',
      agentRunId: 'retired-agent', agentSourcePointId: 'retired-point'
    }
    localStorage.setItem('ai-test-platform.workspace.user%3A1.ai-test-platform.web-automation-library.v1', JSON.stringify([saved]))
    sessionStorage.setItem('ai-test-platform.workspace.user%3A1.ai-test-platform.web-active-run.v1', 'run-retired')
    const requests: string[] = []
    let reads = 0
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      requests.push(String(url))
      if (String(url).includes('/api/web-automation/runs/run-retired')) {
        reads++
        return { ok: true, status: 200, json: async () => ({
          runId: 'run-retired', status: reads === 1 ? 'running' : 'completed', mode: 'sequential',
          selectedBrowser: 'chromium', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:01Z',
          currentCase: { id: saved.id, name: saved.name },
          progress: { totalCases: 1, completedCases: 1, totalSteps: 1, completedSteps: 1 },
          controls: { canPause: false, canResume: false, canStop: false },
          summary: { total: 1, passed: 1, failed: 0, error: 0 }, stepLogs: [], diagnostics: [],
          results: [{ caseId: saved.id, sourceCaseId: saved.sourceCaseId, name: saved.name, module: saved.module,
            pageName: saved.pageName, startUrl: saved.startUrl, status: 'passed', durationMs: 10 }]
        }) }
      }
      return backendResponse(url)
    }))
    const { default: App } = await import('./App.vue')
    const wrapper = mount(App as never, { global: { plugins: [ElementPlus] } })
    try {
      await vi.waitFor(() => expect(localStorage.getItem('ai-test-platform.workspace.user%3A1.ai-test-platform.automation-reports.v1')).toContain('case-retired'))
      await flushPromises()
      expect(requests.filter(url => url.includes('/api/ai/'))).toEqual([])
      expect(reads).toBeGreaterThanOrEqual(2)
    } finally {
      wrapper.unmount()
      sessionStorage.clear()
    }
  })

  it('has no unresolved retired components on the home shell', async () => {
    vi.resetModules()
    localStorage.clear()
    sessionStorage.clear()
    window.history.replaceState({}, '', '/')
    vi.stubGlobal('fetch', vi.fn(async (url: string) => backendResponse(url)))
    const warnings: string[] = []
    const { default: App } = await import('./App.vue')
    const wrapper = mount(App as never, { global: {
      plugins: [ElementPlus], config: { warnHandler: message => warnings.push(message) }
    } })
    try {
      await vi.waitFor(() => expect(wrapper.find('[data-testid="home-open-chain"]').exists()).toBe(true))
      expect(warnings.filter(message => message.includes('Failed to resolve component'))).toEqual([])
    } finally { wrapper.unmount() }
  })

  it.each([
    ['/api', '/cases', '接口用例'],
    ['/web-cases/', '/cases/web', 'Web 用例'],
    ['/ai-config', '/assistant', undefined]
  ])('replaces retired URL %s with %s without old AI requests', async (oldPath, expectedPath, heading) => {
    vi.resetModules()
    localStorage.clear()
    sessionStorage.clear()
    window.history.replaceState({}, '', oldPath)
    const requests: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      requests.push(String(url))
      return backendResponse(url)
    }))
    const { default: App } = await import('./App.vue')
    const wrapper = mount(App as never, { global: { plugins: [ElementPlus] } })
    try {
      await vi.waitFor(() => expect(window.location.pathname).toBe(expectedPath))
      await flushPromises()
      if (heading) expect(wrapper.get('.cases-head h1').text()).toBe(heading)
      expect(requests.filter(url => url.includes('/api/ai/'))).toEqual([])
      expect(wrapper.find('.workbench__pane--full:empty').exists()).toBe(false)
    } finally {
      wrapper.unmount()
      window.history.replaceState({}, '', '/')
    }
  })

  it('opens the Web case library directly and navigates between the two sidebar pages', async () => {
    vi.resetModules()
    localStorage.clear()
    sessionStorage.clear()
    window.history.replaceState({}, '', '/cases/web')
    vi.stubGlobal('fetch', vi.fn(async (url: string) => backendResponse(url)))
    const { default: App } = await import('./App.vue')
    const wrapper = mount(App as never, { global: { plugins: [ElementPlus] } })
    try {
      await vi.waitFor(() => expect(wrapper.find('.cases-head h1').exists()).toBe(true))
      expect(window.location.pathname).toBe('/cases/web')
      expect(wrapper.get('.cases-head h1').text()).toBe('Web 用例')
      expect(wrapper.get('.cv-directory h2').text()).toBe('Web 用例目录')
      expect(wrapper.get('.studio-primary-capsule [aria-current="page"]').text()).toBe('用例库')
      expect(wrapper.get('.studio-sidebar__groups [aria-current="page"]').attributes('aria-label')).toBe('Web 用例')
      expect(wrapper.find('[role="tablist"][aria-label="用例类别"]').exists()).toBe(false)
      expect(wrapper.get('.cv-list-tools').text()).not.toContain('字段赋值')

      await wrapper.get('.studio-sidebar__groups button[aria-label="接口用例"]').trigger('click')
      expect(window.location.pathname).toBe('/cases')
      expect(wrapper.get('.cases-head h1').text()).toBe('接口用例')
      expect(wrapper.get('.cv-directory h2').text()).toBe('接口用例目录')
      expect(wrapper.get('.cv-list-tools').text()).toContain('批量修改字段')
      expect(wrapper.get('.cv-list-tools').text()).toContain('字段赋值')
      await wrapper.get('.cv-search input').setValue('only-interface')
      await wrapper.get('.studio-sidebar__groups button[aria-label="Web 用例"]').trigger('click')
      expect(window.location.pathname).toBe('/cases/web')
      expect(wrapper.get('.cv-search input').element).toHaveProperty('value', '')
    } finally {
      wrapper.unmount()
      window.history.replaceState({}, '', '/')
    }
  })

  it('mounts and shows the login screen when the session is expired', async () => {
    vi.resetModules()
    localStorage.clear()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(expiredSessionResponse))
    const { default: App } = await import('./App.vue')
    const wrapper = mount(App as never, { global: { plugins: [ElementPlus] } })
    await vi.waitFor(() => {
      expect(wrapper.find('h1').text()).toContain('Haitlify AI 测试平台')
    })
  })

  it('mounts the authenticated app shell and renders the new home landing by default', async () => {
    vi.resetModules()
    localStorage.clear()
    vi.stubGlobal('fetch', vi.fn(async (url: string) => backendResponse(url)))
    const { default: App } = await import('./App.vue')
    const wrapper = mount(App as never, { global: { plugins: [ElementPlus] } })
    // 根路径 '/' 经 navKeyFromPath 解析为 'home'，默认进入真实工作台。
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('我的工作台')
    })
    expect(wrapper.text()).not.toContain('旧 AI 助手页面已下线')
    // 旧对话入口与旧 AI 配置菜单都不应再出现。
    expect(wrapper.find('[data-testid="home-assistant-input"]').exists()).toBe(false)
    expect(wrapper.find('button[aria-label="AI 设置"]').exists()).toBe(false)
  })

  it('wires HomeView loading/error retry state through the authenticated App shell', async () => {
    vi.resetModules()
    localStorage.clear()
    sessionStorage.clear()
    window.history.replaceState({}, '', '/')
    vi.stubGlobal('fetch', vi.fn(async (url: string) => backendResponse(url)))
    const { default: App } = await import('./App.vue')
    const wrapper = mount(App as never, { global: { plugins: [ElementPlus] } })
    try {
      await vi.waitFor(() => expect(wrapper.find('[data-testid="home-open-chain"]').exists()).toBe(true))
      const state = getAppState(wrapper)
      expect(state.homeWorkspaceStatus).toBe('empty')
      expect(wrapper.find('[data-home-state="empty"]').exists()).toBe(true)
    } finally {
      wrapper.unmount()
    }
  })

  it('shows a backend failure on HomeView instead of silently presenting local fallback data', async () => {
    vi.resetModules()
    localStorage.clear()
    sessionStorage.clear()
    window.history.replaceState({}, '', '/')
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (String(url).includes('/api/projects')) return { ok: false, status: 503, json: async () => ({ message: 'unavailable' }) }
      return backendResponse(url)
    }))
    const { default: App } = await import('./App.vue')
    const wrapper = mount(App as never, { global: { plugins: [ElementPlus] } })
    try {
      await vi.waitFor(() => expect(wrapper.find('[data-home-state="unavailable"]').exists()).toBe(true))
      expect(wrapper.get('[data-home-state="unavailable"]').text()).toContain('工作台服务暂时不可用')
      expect(wrapper.find('.home-layout').exists()).toBe(false)
    } finally {
      wrapper.unmount()
    }
  })

  it('retries a real HomeView backend failure and clears the error after recovery', async () => {
    vi.resetModules()
    localStorage.clear()
    sessionStorage.clear()
    window.history.replaceState({}, '', '/')
    let fail = true
    const requests: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      requests.push(String(url))
      if (fail && String(url).includes('/api/projects')) return { ok: false, status: 503, json: async () => ({}) }
      if (String(url).endsWith('/api/projects')) return { ok: true, status: 200, json: async () => ({ items: [{ id: 7, code: 'DEFAULT_PROJECT', name: '默认项目' }] }) }
      if (String(url).includes('/case-template/interface')) return { ok: true, status: 200, json: async () => ({ kind: 'interface', schemaVersion: 2, interfaceFields: [], fieldsByKind: { interface: [], functional: [], web_automation: [] }, caseTypes: [], caseTemplates: [], updatedAt: '2026-01-01T00:00:00.000Z' }) }
      return backendResponse(url)
    }))
    const { default: App } = await import('./App.vue')
    const wrapper = mount(App as never, { global: { plugins: [ElementPlus] } })
    try {
      await vi.waitFor(() => expect(wrapper.find('[data-home-state="unavailable"]').exists()).toBe(true))
      fail = false
      await wrapper.get('[data-testid="home-retry"]').trigger('click')
      await vi.waitFor(() => expect(wrapper.find('[data-home-state="empty"]').exists()).toBe(true))
      expect(requests.filter((url) => url.includes('/api/projects')).length).toBeGreaterThanOrEqual(2)
    } finally {
      wrapper.unmount()
    }
  })

  it('surfaces transport failures on HomeView instead of presenting empty success', async () => {
    vi.resetModules()
    localStorage.clear()
    sessionStorage.clear()
    window.history.replaceState({}, '', '/')
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (String(url).includes('/api/projects')) throw new TypeError('synthetic network refusal')
      return backendResponse(url)
    }))
    const { default: App } = await import('./App.vue')
    const wrapper = mount(App as never, { global: { plugins: [ElementPlus] } })
    try {
      await vi.waitFor(() => expect(wrapper.find('[data-home-state="unavailable"]').exists()).toBe(true))
      expect(wrapper.find('[data-home-state="empty"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="home-retry"]').exists()).toBe(true)
    } finally {
      wrapper.unmount()
    }
  })

  it('opens an exact saved API target in preparation even when another API monitor exists', async () => {
    vi.resetModules()
    localStorage.clear()
    sessionStorage.clear()
    window.history.replaceState({}, '', '/')
    const apiCases = [1, 2].map((index) => ({
      id: `AUTO-NAV-${index}`, sourceCaseId: `NAV-${index}`, project: '导航项目', module: '导航模块', name: `导航 API ${index}`,
      method: 'GET', path: `/nav/${index}`, headers: {}, pathParams: {}, queryParams: {}, expectedStatus: 200,
      assertions: [], runOrder: index, exportTarget: 'apifox' as const, automationLibraryId: `nav-library-${index}`,
      savedAt: `2026-10-30T10:0${index}:00.000Z`
    }))
    localStorage.setItem('ai-test-platform.workspace.user%3A1.ai-test-platform.automation-library.v1', JSON.stringify(apiCases))
    localStorage.setItem('ai-test-platform.workspace.user%3A1.ai-test-platform.execution-environments.v1', JSON.stringify({
      activeEnvironmentId: 'nav-env', environments: [{
        id: 'nav-env', name: '导航环境', baseUrl: 'https://fixture.invalid', auth: { type: 'none' }, entries: [],
        createdAt: '2026-10-30T10:00:00.000Z', updatedAt: '2026-10-30T10:00:00.000Z'
      }]
    }))
    const executed: Array<{ method: string; url: string }> = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      if (String(url) === '/__api-test-platform/execute') {
        executed.push(JSON.parse(String(init?.body)))
        const receipt = executed[executed.length - 1]!.url
        return { ok: true, status: 200, json: async () => ({ status: 200, body: { receipt } }) }
      }
      return backendResponse(url)
    }))
    const confirmation = vi.spyOn(ElMessageBox, 'confirm').mockImplementation(() => Promise.resolve('confirm') as never)
    const { default: App } = await import('./App.vue')
    const wrapper = mount(App as never, { global: { plugins: [ElementPlus] } })
    try {
      await vi.waitFor(() => expect(wrapper.find('[data-work-id="nav-library-2"]').exists()).toBe(true))
      const state = getAppState(wrapper)
      state.apiRunMonitor = {
        snapshot: { cases: [apiCases[0]!], options: { mode: 'sequential', loopCount: 1 }, scopeLabel: '旧执行', environment: { baseUrl: '' }, frozenAt: '2026' },
        status: 'running', startedAt: '2026-10-30T10:00:00.000Z', progress: { completed: 0, total: 1 }, rows: []
      }
      state.apiAutomationWorkspaceMode = 'monitor'
      expect(state.apiAutomationWorkspaceMode).toBe('monitor')
      await wrapper.find('[data-work-id="nav-library-2"] button.home-item-name').trigger('click')
      await vi.waitFor(() => expect(window.location.pathname).toBe('/automation/api'))
      expect(state.apiAutomationWorkspaceMode).toBe('prepare')
      expect(state.automationSelection.map((item: { id: string }) => item.id)).toEqual(['AUTO-NAV-2'])
      await flushPromises()
      const checkbox = (id: string) => {
        const row = wrapper.findAll('.automation-panel .el-table__body-wrapper tr.el-table__row')
          .find(candidate => candidate.findAll('td')[1]?.text() === id)
        expect(row, `real API consumer row ${id}`).toBeDefined()
        return row!.get<HTMLInputElement>('input[type="checkbox"]')
      }
      expect(wrapper.get('.automation-workspace').isVisible()).toBe(true)
      expect(checkbox('AUTO-NAV-2').element.checked).toBe(true)
      expect(checkbox('AUTO-NAV-1').element.checked).toBe(false)
      expect(wrapper.get('.automation-topbar').text()).toContain('执行选中 1 条')
      const runButton = () => wrapper.findAll('.automation-topbar button').find(button => button.text().includes('执行选中'))!
      await runButton().trigger('click')
      await vi.waitFor(() => expect(state.apiAutomationRunning).toBe(false))
      await flushPromises()
      expect(executed.map(request => request.url)).toEqual(['https://fixture.invalid/nav/2'])
      expect(state.apiRunSnapshot!.cases.map((item: { id: string }) => item.id)).toEqual(['AUTO-NAV-2'])
      expect(state.apiRunMonitor!.rows[0]!.result!.responseBody).toEqual({ receipt: 'https://fixture.invalid/nav/2' })

      await wrapper.get('[data-testid="api-monitor-back"]').trigger('click')
      await flushPromises()
      expect(checkbox('AUTO-NAV-2').element.checked).toBe(true)
      await checkbox('AUTO-NAV-1').setValue(true)
      await flushPromises()
      expect(checkbox('AUTO-NAV-1').element.checked).toBe(true)
      expect(checkbox('AUTO-NAV-2').element.checked).toBe(true)
      expect(state.automationSelection.map((item: { id: string }) => item.id).sort()).toEqual(['AUTO-NAV-1', 'AUTO-NAV-2'])
      expect(wrapper.get('.automation-topbar').text()).toContain('执行选中 2 条')
      await runButton().trigger('click')
      await vi.waitFor(() => expect(executed).toHaveLength(3))
      await vi.waitFor(() => expect(state.apiAutomationRunning).toBe(false))
      expect(executed.slice(1).map(request => [request.method, request.url])).toEqual([
        ['GET', 'https://fixture.invalid/nav/1'], ['GET', 'https://fixture.invalid/nav/2']
      ])
      expect(state.apiRunSnapshot!.cases.map((item: { id: string }) => item.id)).toEqual(['AUTO-NAV-1', 'AUTO-NAV-2'])
    } finally { wrapper.unmount(); confirmation.mockRestore() }
  })

  it('opens an exact report target independently of stale report filters', async () => {
    vi.resetModules()
    localStorage.clear()
    sessionStorage.clear()
    window.history.replaceState({}, '', '/')
    const reports = [
      { reportId: 'report-a', name: '目标报告', project: '报告项目', environmentName: '环境 A', moduleScope: [], createdAt: '2026-10-30T10:00:00.000Z', mode: 'sequential', startedAt: '2026-10-30T09:59:00.000Z', endedAt: '2026-10-30T10:00:00.000Z', summary: { total: 0, passed: 0, failed: 0, error: 0 }, results: [] },
      { reportId: 'report-b', name: '其他报告', project: '报告项目', environmentName: '环境 A', moduleScope: [], createdAt: '2026-10-30T09:00:00.000Z', mode: 'sequential', startedAt: '2026-10-30T08:59:00.000Z', endedAt: '2026-10-30T09:00:00.000Z', summary: { total: 0, passed: 0, failed: 0, error: 0 }, results: [] }
    ]
    localStorage.setItem('ai-test-platform.workspace.user%3A1.ai-test-platform.automation-reports.v1', JSON.stringify(reports))
    vi.stubGlobal('fetch', vi.fn(async (url: string) => backendResponse(url)))
    const { default: App } = await import('./App.vue')
    const wrapper = mount(App as never, { global: { plugins: [ElementPlus] } })
    try {
      await vi.waitFor(() => expect(wrapper.find('[data-work-id="report-a"]').exists()).toBe(true))
      const state = getAppState(wrapper)
      state.reports.reportKeyword.value = '其他报告'
      state.reports.reportEnvironmentFilter.value = '隐藏环境'
      await wrapper.find('[data-work-id="report-a"] button.home-item-name').trigger('click')
      await vi.waitFor(() => expect(window.location.pathname).toBe('/reports'))
      expect(state.reports.reportKeyword.value).toBe('')
      expect(state.reports.reportEnvironmentFilter.value).toBe('ALL')
      expect(state.selectedReportId).toBe('report-a')
      await flushPromises()
      expect(wrapper.find('[data-testid="report-detail"]').text()).toContain('目标报告')
    } finally { wrapper.unmount() }
  })

  it('opens an exact saved Web target in preparation after stale filters and monitor state', async () => {
    vi.resetModules()
    localStorage.clear()
    sessionStorage.clear()
    window.history.replaceState({}, '', '/')
    const webCase = {
      id: 'WEB-NAV-1', sourceCaseId: 'WEB-SOURCE-1', project: 'Web 导航项目', module: 'Web 模块', pageName: '订单页',
      name: 'Web 导航用例', startUrl: '/orders', environmentId: 'web-env-default', browser: 'chromium', viewport: '1280x720',
      authenticationMode: 'skip', steps: [], runOrder: 1, mockOutcome: 'passed',
      automationLibraryId: 'web-nav-library-1', savedAt: '2026-10-30T10:00:00.000Z'
    }
    const webEnvironment = {
      activeEnvironmentId: 'web-env-default',
      environments: [{
        id: 'web-env-default', name: 'Web 导航环境', baseUrl: 'https://fixture.invalid', auth: { type: 'none' }, entries: [],
        web: {
          browser: 'chromium', viewport: '1280x720', headless: true, defaultTimeoutMs: 30000, navigationTimeoutMs: 45000,
          waitUntil: 'networkidle', screenshotStrategy: 'on_failure', videoRecording: false, retryCount: 0,
          cookieOrigins: [], allowedRequestOrigins: [], networkPolicyMode: 'blocklist',
          login: { enabled: false, loginUrl: '', usernameLocator: '', passwordLocator: '', submitLocator: '', username: '', password: '', successType: 'url', successTarget: '' },
          logout: { enabled: false, steps: [] }
        }, createdAt: '2026-10-30T10:00:00.000Z', updatedAt: '2026-10-30T10:00:00.000Z'
      }]
    }
    localStorage.setItem('ai-test-platform.workspace.user%3A1.ai-test-platform.web-automation-library.v1', JSON.stringify([webCase]))
    localStorage.setItem('ai-test-platform.workspace.user%3A1.ai-test-platform.web-execution-environments.v1', JSON.stringify(webEnvironment))
    vi.stubGlobal('fetch', vi.fn(async (url: string) => backendResponse(url)))
    const { default: App } = await import('./App.vue')
    const wrapper = mount(App as never, { global: { plugins: [ElementPlus] } })
    try {
      await vi.waitFor(() => expect(wrapper.find('[data-work-id="web-nav-library-1"]').exists()).toBe(true))
      const state = getAppState(wrapper)
      state.automationFilter.keyword = 'does-not-match'
      state.automationFilter.source = 'design'
      state.webAutomationStatusFilter = 'failed'
      state.webAutomationWorkspaceMode = 'monitor'
      await wrapper.find('[data-work-id="web-nav-library-1"] button.home-item-name').trigger('click')
      await vi.waitFor(() => expect(window.location.pathname).toBe('/automation/web'))
      expect(state.webAutomationWorkspaceMode).toBe('workbench')
      expect(state.automationFilter.keyword).toBe('')
      expect(state.automationFilter.source).toBe('ALL')
      expect(state.webAutomationStatusFilter).toBe('ALL')
      expect(state.webAutomationSelectedIds).toEqual(['WEB-NAV-1'])
      await flushPromises()
      expect(wrapper.find('.web-automation-workspace').text()).toContain('Web 导航用例')
    } finally { wrapper.unmount() }
  })

  it('passes saved API cases into the home recent-work region with exact navigation identity', async () => {
    vi.resetModules()
    localStorage.clear()
    sessionStorage.clear()
    window.history.replaceState({}, '', '/')
    localStorage.setItem('ai-test-platform.workspace.user%3A1.ai-test-platform.automation-library.v1', JSON.stringify([{
      id: 'AUTO-1001', sourceCaseId: 'CASE-1001', project: '支付项目', module: '订单', name: '创建订单',
      method: 'POST', path: '/orders', headers: {}, pathParams: {}, queryParams: {}, expectedStatus: 201,
      assertions: [], runOrder: 1, exportTarget: 'apifox', automationLibraryId: 'api-library-1001',
      savedAt: '2026-10-30T10:00:00.000Z'
    }]))
    vi.stubGlobal('fetch', vi.fn(async (url: string) => backendResponse(url)))
    const { default: App } = await import('./App.vue')
    const wrapper = mount(App as never, { global: { plugins: [ElementPlus] } })
    try {
      await vi.waitFor(() => {
        expect(wrapper.find('[data-work-id="api-library-1001"]').exists()).toBe(true)
      })
      const recentRow = wrapper.find('[data-work-id="api-library-1001"]')
      expect(recentRow.text()).toContain('创建订单')
      await recentRow.find('button.home-item-name').trigger('click')
      await vi.waitFor(() => expect(window.location.pathname).toBe('/automation/api'))
      expect(wrapper.find('.automation-panel').text()).toContain('创建订单')
    } finally {
      wrapper.unmount()
      localStorage.clear()
      sessionStorage.clear()
    }
  })
})

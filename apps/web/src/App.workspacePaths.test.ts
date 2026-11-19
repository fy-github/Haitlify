// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
const A = { id: 1, username: 'a', displayName: 'A', role: 'EDITOR', mustChangePassword: false }
const B = { ...A, id: 2, username: 'b', displayName: 'B' }
const reply = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
function deferred() {
  let resolve!: (value: Response) => void
  let reject!: (error: Error) => void
  const promise = new Promise<Response>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const jira = (name: string) => ({ enabled: true, username: name, baseUrl: `https://${name}.invalid`, credentialConfigured: true, projectJql: {} })
const reportFields = { createdAt: '2026-01-01', moduleScope: [], environmentName: 'test', mode: 'sequential', startedAt: '', endedAt: '' }
const snapshot = (runId: string, status = 'completed') => ({ runId, status, mode: 'sequential', selectedBrowser: 'chromium', createdAt: '', updatedAt: '', progress: { totalCases: 0, completedCases: 0, totalSteps: 0, completedSteps: 0 }, controls: {}, summary: { total: 0, passed: 0, failed: 0, error: 0 }, stepLogs: [], results: [], diagnostics: [] })
const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.restoreAllMocks(); vi.unstubAllGlobals() })
async function setup(route?: (url: string, init?: RequestInit) => Promise<Response> | undefined) {
  vi.resetModules(); localStorage.clear(); sessionStorage.clear(); window.history.replaceState({}, '', '/')
  const { defaultTemplateConfig } = await import('./domain/templateConfigRepository')
  const fetch = vi.fn((url: string, init?: RequestInit) => route?.(url, init) ?? Promise.resolve(reply(
    url.includes('/auth/me') ? A : url.includes('/auth/login') ? B : url.includes('/jira/config') ? jira('current') : url === '/api/projects' ? { items: [{ id: 1, name: '默认项目', code: 'PROJECT' }] } : url.endsWith('/interface-cases') ? { items: [] } : url.includes('/case-template/') ? defaultTemplateConfig() : url.includes('/capabilities') ? { status: 'UP', browsers: [], checkedAt: 'current' } : {}
  )))
  vi.stubGlobal('fetch', fetch)
  const { default: App } = await import('./App.vue')
  const w = mount(App as never, { global: { plugins: [ElementPlus] } }); wrappers.push(w)
  const s = ((w.vm as any).$ as any).setupState
  await flushPromises()
  return { w, s, fetch }
}
async function boundary(kind: string, w: ReturnType<typeof mount>, s: any) {
  if (kind === 'unmount') { w.unmount(); wrappers.splice(wrappers.indexOf(w), 1) }
  else { s.handleSessionExpired(); s.onLoginSuccess(B); await flushPromises(); expect(s.currentUser.id).toBe(2) }
}
const state = (s: any) => JSON.stringify({ jira: s.jiraConfigForm, loading: s.jiraConfigLoading, testing: s.jiraConfigTesting, capabilities: s.webRunCapabilities, cases: s.savedCases, template: s.templateConfig, conflict: s.workspaceSyncConflict, run: s.activeWebRunSnapshot, runId: s.activeWebRunId, running: s.webAutomationRunning, connection: s.webRunConnectionState, error: s.webRunInterruptionMessage, control: s.webRunControlLoading, reports: s.automationReports })
for (const kind of ['switch', 'unmount']) {
  for (const outcome of ['success', 'error']) {
    it(`capabilities ${outcome} after ${kind} cannot mutate current state`, async () => {
      const pending = deferred(); let calls = 0
      const { w, s } = await setup(url => url.includes('/capabilities') && ++calls === 1 ? pending.promise : undefined)
      await boundary(kind, w, s); const before = state(s)
      outcome === 'success' ? pending.resolve(reply({ status: 'DEGRADED', browsers: [], checkedAt: 'old' })) : pending.reject(new Error('old failure'))
      await flushPromises(); expect(state(s)).toBe(before)
    })
    it(`backend second-stage ${outcome} after ${kind} cannot write state or storage`, async () => {
      const pending = deferred(); let calls = 0
      const { w, s } = await setup(url => url.endsWith('/interface-cases') && ++calls === 1 ? pending.promise : undefined)
      expect(calls).toBe(1)
      await boundary(kind, w, s); const before = state(s); const stored = JSON.stringify(localStorage)
      outcome === 'success' ? pending.resolve(reply({ items: [{ libraryId: 'old', savedAt: '2026-01-01', id: 'old', row: { project: '默认项目', module: 'private', caseType: '', path: '/', method: 'GET', title: 'private' }, metadata: { sourceFile: 'old', sourceOperationId: 'old' } }] })) : pending.reject(new Error('old failure'))
      await flushPromises(); expect(state(s)).toBe(before); expect(JSON.stringify(localStorage)).toBe(stored)
    })
    it(`recovery ${outcome} after ${kind} cannot start polling or change query/session`, async () => {
      const pending = deferred()
      const { w, s, fetch } = await setup(url => url.endsWith('/runs/old') ? pending.promise : undefined)
      window.history.replaceState({}, '', '/?view=monitor&runId=old'); void s.recoverWebRunSession(); await flushPromises()
      await boundary(kind, w, s); const before = state(s); const stored = JSON.stringify(sessionStorage); const location = window.location.href
      const requests = fetch.mock.calls.length
      outcome === 'success' ? pending.resolve(reply(snapshot('old', 'running'))) : pending.resolve(reply({}, 404))
      await flushPromises(); expect(state(s)).toBe(before); expect(JSON.stringify(sessionStorage)).toBe(stored); expect(window.location.href).toBe(location); expect(fetch.mock.calls.length).toBe(requests)
    })
    for (const method of ['saveJiraSettings', 'testJiraConnection']) {
      it(`${method} ${outcome} after ${kind} cannot mutate flags or toast`, async () => {
        const pending = deferred()
        const { w, s } = await setup((url, init) => url.includes('/jira/config') && init?.method ? pending.promise : undefined)
        void s[method](); await flushPromises(); await boundary(kind, w, s)
        const before = state(s); const toast = vi.spyOn(ElMessage, 'error'); const success = vi.spyOn(ElMessage, 'success')
        outcome === 'success' ? pending.resolve(reply(method === 'saveJiraSettings' ? jira('old') : { success: true, message: 'old' })) : pending.reject(new Error('old failure'))
        await flushPromises(); expect(state(s)).toBe(before); expect(toast).not.toHaveBeenCalled(); expect(success).not.toHaveBeenCalled()
      })
    }
    it(`poll ${outcome} after ${kind} does not finalize reports or restart`, async () => {
      const pending = deferred()
      const { w, s, fetch } = await setup(url => url.endsWith('/runs/old') ? pending.promise : undefined)
      s.activeWebRunId = 'old'; void s.monitorActiveWebRun('old'); await flushPromises()
      await boundary(kind, w, s); const before = state(s); const requests = fetch.mock.calls.length
      outcome === 'success' ? pending.resolve(reply(snapshot('old'))) : pending.reject(new Error('old failure'))
      await flushPromises(); expect(state(s)).toBe(before); expect(fetch.mock.calls.length).toBe(requests)
    })
  }
  it(`backend project discovery after ${kind} must not dispatch second-stage requests`, async () => {
    const pending = deferred(); let calls = 0
    const { w, s, fetch } = await setup(url => url === '/api/projects' && ++calls === 1 ? pending.promise : undefined)
    await boundary(kind, w, s); const requests = fetch.mock.calls.length
    pending.resolve(reply({ items: [{ id: 99, name: '默认项目', code: 'PROJECT' }] })); await flushPromises()
    expect(fetch.mock.calls.length).toBe(requests)
  })
}
for (const kind of ['switch', 'unmount']) for (const outcome of ['success', 'error']) {
  for (const method of ['pauseActiveWebRun', 'resumeActiveWebRun', 'stopActiveWebRun']) {
    it(`${method} ${outcome} after ${kind} cannot affect current run`, async () => {
      const pending = deferred()
      const { w, s } = await setup(url => /\/runs\/old\/(pause|resume|stop)$/.test(url) ? pending.promise : undefined)
      s.activeWebRunId = 'old'; void s[method](); await flushPromises()
      await boundary(kind, w, s); const before = state(s)
      const toast = vi.spyOn(ElMessage, 'error')
      outcome === 'success' ? pending.resolve(reply(snapshot('old'))) : pending.reject(new Error('old control'))
      await flushPromises(); expect(state(s)).toBe(before); expect(toast).not.toHaveBeenCalled()
    })
  }
}
it('restarting the same run monitor supersedes the old pending read without losing polling', async () => {
  const old = deferred(); let calls = 0
  const { s } = await setup(url => url.endsWith('/runs/live') ? (++calls === 1 ? old.promise : Promise.resolve(reply(snapshot('live')))) : undefined)
  s.activeWebRunId = 'live'; void s.monitorActiveWebRun('live'); await flushPromises()
  await s.monitorActiveWebRun('live'); await flushPromises()
  expect(calls).toBe(2); expect(s.activeWebRunSnapshot.status).toBe('completed')
  const before = state(s); old.resolve(reply(snapshot('live', 'running'))); await flushPromises(); expect(state(s)).toBe(before)
})
it('resuming an interrupted run restarts polling without stranding control busy', async () => {
  const { s } = await setup(url => url.endsWith('/runs/live/resume') ? Promise.resolve(reply(snapshot('live', 'running'))) : url.endsWith('/runs/live') ? Promise.resolve(reply(snapshot('live'))) : undefined)
  s.activeWebRunId = 'live'; s.webRunConnectionState = 'interrupted'
  await s.resumeActiveWebRun(); await flushPromises()
  expect(s.webRunControlLoading).toBe(''); expect(s.activeWebRunSnapshot.runId).toBe('live')
})
it.each(['switch', 'unmount'])('poll timer after %s does not dispatch another read', async kind => {
  const { w, s, fetch } = await setup(url => url.endsWith('/runs/live') ? Promise.resolve(reply(snapshot('live', 'running'))) : undefined)
  vi.useFakeTimers()
  try {
    s.activeWebRunId = 'live'; void s.monitorActiveWebRun('live'); await flushPromises()
    await boundary(kind, w, s); const requests = fetch.mock.calls.length
    await vi.advanceTimersByTimeAsync(4000); expect(fetch.mock.calls.length).toBe(requests)
  } finally { vi.useRealTimers() }
})
it('newest same-session recovery wins and retires the prior poll', async () => {
  const old = deferred()
  const { s, fetch } = await setup(url => url.endsWith('/runs/old') ? old.promise : url.endsWith('/runs/new') ? Promise.resolve(reply(snapshot('new'))) : undefined)
  s.activeWebRunId = 'old'; void s.monitorActiveWebRun('old'); await flushPromises()
  window.history.replaceState({}, '', '/?view=monitor&runId=new'); await s.recoverWebRunSession()
  const before = state(s); const requests = fetch.mock.calls.length
  old.resolve(reply(snapshot('old'))); await flushPromises()
  expect(s.activeWebRunId).toBe('new'); expect(state(s)).toBe(before); expect(fetch.mock.calls.length).toBe(requests)
})
it('newest same-session backend load wins including cache writes', async () => {
  const old = deferred(); let calls = 0
  const { s } = await setup(url => url.endsWith('/case-template/interface') && ++calls === 1 ? old.promise : undefined)
  await s.loadBackendBackedWorkspace(); const before = state(s); const stored = JSON.stringify(localStorage)
  const { defaultTemplateConfig } = await import('./domain/templateConfigRepository')
  const template = defaultTemplateConfig(); template.interfaceFields[0].label = 'old-secret'
  old.resolve(reply(template)); await flushPromises()
  expect(state(s)).toBe(before); expect(JSON.stringify(localStorage)).toBe(stored)
})
it('newest same-session Jira request owns form and busy even when old finally runs', async () => {
  const old = deferred(), latest = deferred(); let calls = 0
  const { s } = await setup(url => url === '/api/jira/config' ? (++calls === 1 ? old.promise : latest.promise) : undefined)
  void s.loadJiraSettingsFromBackend(); old.resolve(reply(jira('old'))); await flushPromises()
  expect(s.jiraConfigLoading).toBe(true); expect(s.jiraConfigForm.username).not.toBe('old')
  latest.resolve(reply(jira('newest'))); await flushPromises(); expect(s.jiraConfigForm.username).toBe('newest'); expect(s.jiraConfigLoading).toBe(false)
})
it('newest same-session capabilities request wins', async () => {
  const old = deferred(); let calls = 0
  const { s } = await setup(url => url.includes('/capabilities') && ++calls === 1 ? old.promise : undefined)
  await s.loadWebRunCapabilities(); old.resolve(reply({ status: 'DEGRADED', checkedAt: 'old', browsers: [] })); await flushPromises()
  expect(s.webRunCapabilities.checkedAt).toBe('current')
})
for (const kind of ['switch', 'unmount']) {
  it(`workspace reload confirmation after ${kind} cannot dispatch a load`, async () => {
    const { w, s, fetch } = await setup()
    let confirm!: () => void
    vi.spyOn(ElMessageBox, 'confirm').mockImplementation(() => new Promise(resolve => { confirm = () => resolve({ value: '', action: 'confirm' } as never) }))
    s.workspaceSyncConflict = { projectName: 'A', template: true, cases: true }
    void s.reloadBackendWorkspace(); await flushPromises(); await boundary(kind, w, s)
    const requests = fetch.mock.calls.length; const before = state(s)
    confirm(); await flushPromises(); expect(fetch.mock.calls.length).toBe(requests); expect(state(s)).toBe(before)
  })
  it(`workspace push after ${kind} cannot start case sync or release current busy`, async () => {
    const pending = deferred()
    const { w, s, fetch } = await setup((url, init) => url.endsWith('/case-template/interface') && init?.method === 'PUT' ? pending.promise : undefined)
    s.workspaceSyncConflict = { projectName: '默认项目', template: true, cases: true }
    void s.pushLocalWorkspaceToBackend(); await flushPromises()
    await boundary(kind, w, s); const before = state(s); const requests = fetch.mock.calls.length
    const { defaultTemplateConfig } = await import('./domain/templateConfigRepository')
    pending.resolve(reply(defaultTemplateConfig())); await flushPromises()
    expect(state(s)).toBe(before); expect(fetch.mock.calls.length).toBe(requests)
  })
  it(`late defect issue load after ${kind} cannot repopulate cleared state`, async () => {
    const pending = deferred()
    const { w, s } = await setup(url => url.includes('/jira/issues') ? pending.promise : undefined)
    s.activeNav = 'defects'; s.defects.defectFilter.project = 'A'; await flushPromises()
    await boundary(kind, w, s)
    pending.resolve(reply({ items: [{ key: 'A-1', summary: 'private', projectName: 'A' }] })); await flushPromises()
    expect(s.defects.jiraIssues.value).toEqual([])
  })
  it(`template persist after ${kind} cannot overwrite current config`, async () => {
    const pending = deferred()
    const { w, s } = await setup((url, init) => url.endsWith('/case-template/interface') && init?.method === 'PUT' ? pending.promise : undefined)
    s.templateConfigModule.patchField('caseId', { label: 'edited' }); await flushPromises()
    await boundary(kind, w, s); const before = state(s); const stored = JSON.stringify(localStorage)
    const { defaultTemplateConfig } = await import('./domain/templateConfigRepository')
    const old = defaultTemplateConfig(); old.interfaceFields[0].label = 'old-save'
    pending.resolve(reply(old)); await flushPromises(); expect(state(s)).toBe(before); expect(JSON.stringify(localStorage)).toBe(stored)
  })
  it(`run creation project stage after ${kind} dispatches no create`, async () => {
    const pending = deferred(); let armed = false
    const { w, s, fetch } = await setup(url => armed && url === '/api/projects' ? pending.promise : undefined)
    s.webExecutionEnvironmentLibrary.environments[0].baseUrl = 'https://test.invalid'; armed = true
    void s.startVisualWebAutomationRun([{ id: 'case', project: '默认项目', steps: [] }]); await flushPromises()
    expect(s.webAutomationRunning).toBe(true)
    armed = false
    await boundary(kind, w, s); const before = state(s); const requests = fetch.mock.calls.length
    pending.resolve(reply({ items: [{ id: 1, name: '默认项目', code: 'PROJECT' }] })); await flushPromises()
    expect(state(s)).toBe(before); expect(fetch.mock.calls.length).toBe(requests)
  })
}
it.each(['switch', 'unmount'])('late template result after %s cannot touch config or cache', async kind => {
  const pending = deferred(); let calls = 0
  const { w, s } = await setup(url => url.endsWith('/case-template/interface') && ++calls === 1 ? pending.promise : undefined)
  await boundary(kind, w, s); const before = state(s); const stored = JSON.stringify(localStorage)
  const { defaultTemplateConfig } = await import('./domain/templateConfigRepository')
  const config = defaultTemplateConfig(); config.interfaceFields[0].label = 'A-private'
  pending.resolve(reply(config)); await flushPromises()
  expect(state(s)).toBe(before); expect(JSON.stringify(localStorage)).toBe(stored)
})
it.each(['switch', 'unmount'])('late Jira failure after %s cannot release a new busy flag', async kind => {
  const old = deferred(), fresh = deferred(); let calls = 0
  const { w, s } = await setup(url => url === '/api/jira/config' ? (++calls === 1 ? old.promise : fresh.promise) : undefined)
  await boundary(kind, w, s); const before = state(s)
  old.reject(new Error('old')); await flushPromises(); expect(state(s)).toBe(before)
  fresh.resolve(reply(jira('fresh'))); await flushPromises()
})
it('expiry clears hidden editors as well as visible workspace refs', async () => {
  const { s } = await setup()
  s.editingEnvironment.baseUrl = 'https://A-private.invalid'; s.editingEnvironment.auth = { type: 'bearer', token: 'A-secret' }
  s.curlImportText = 'A-secret'; s.editRow.title = 'A-private'; s.webEditRow.title = 'A-private'
  s.reports.reportEnvironmentFilter.value = 'A-private'
  s.defects.defectEditForm.summary = 'A-private'
  s.editRow.customSecret = 'A-private'; s.webEditRow.customSecret = 'A-private'
  s.casesModule.bulkEditForm.targetValue = 'A-private'; s.casesModule.fieldAssignmentForm.value = 'A-private'
  s.defects.jiraCsvTemplate.value = [{ id: 'secret', header: 'A-private', source: 'constant', constantValue: 'A-private', enabled: true }]
  s.handleSessionExpired()
  expect(JSON.stringify(s.editingEnvironment)).not.toContain('A-secret'); expect(JSON.stringify(s.editingEnvironment)).not.toContain('A-private')
  expect(s.curlImportText).toBe(''); expect(s.editRow.title).not.toBe('A-private'); expect(s.webEditRow.title).not.toBe('A-private')
  expect(s.defects.defectEditForm.summary).toBe(''); expect(s.reports.reportEnvironmentFilter.value).toBe('ALL')
  expect(JSON.stringify([s.editRow, s.webEditRow, s.casesModule.bulkEditForm, s.casesModule.fieldAssignmentForm, s.defects.jiraCsvTemplate.value])).not.toContain('A-private')
})
it('account-owned reports survive A -> B -> A without exposing A to B', async () => {
  const { s } = await setup(); const { appendAutomationReport } = await import('./domain/automationReportRepository')
  appendAutomationReport(s.localStorage, { ...reportFields, reportId: 'A-only', name: 'private', project: 'A', results: [], summary: {} } as any)
  s.refreshAutomationReports(); expect(s.automationReports[0].reportId).toBe('A-only')
  s.handleSessionExpired(); s.onLoginSuccess(B); await flushPromises()
  expect(s.automationReports).toEqual([])
  appendAutomationReport(s.localStorage, { ...reportFields, reportId: 'B-only', name: 'private', project: 'B', results: [], summary: {} } as any)
  s.refreshAutomationReports(); expect(s.automationReports[0].reportId).toBe('B-only')
  s.handleSessionExpired(); s.onLoginSuccess(A); await flushPromises()
  expect(s.automationReports.map((r: any) => r.reportId)).toEqual(['A-only'])
})
it('workspace singleton state rebinds to a new App owner on remount', async () => {
  const { w, s } = await setup()
  s.handleSessionExpired(); w.unmount(); wrappers.splice(wrappers.indexOf(w), 1)
  const { default: App } = await import('./App.vue')
  const next = mount(App as never, { global: { plugins: [ElementPlus] } }); wrappers.push(next)
  await flushPromises(); const fresh = ((next.vm as any).$ as any).setupState
  const { appendAutomationReport } = await import('./domain/automationReportRepository')
  appendAutomationReport(fresh.localStorage, { ...reportFields, reportId: 'new-app', name: 'new', project: 'new', results: [], summary: {} } as any)
  fresh.refreshAutomationReports()
  expect(fresh.automationReports.map((report: any) => report.reportId)).toEqual(['new-app'])
})
it('expiry clears loaded sensitive state before B loads; legacy unowned storage is not imported or deleted', async () => {
  const { s } = await setup()
  const { appendAutomationReport } = await import('./domain/automationReportRepository')
  const report = { ...reportFields, reportId: 'legacy-secret', project: 'A-private', name: 'private', results: [], summary: { total: 0, passed: 0, failed: 0, error: 0 } } as any
  appendAutomationReport(localStorage, report)
  const legacy = localStorage.getItem('ai-test-platform.automation-reports.v1')
  s.refreshAutomationReports(); expect(s.automationReports).toEqual([])
  s.jiraConfigForm.token = 'old-secret'; s.activeWebRunId = 'old'; s.webRunCapabilities = { checkedAt: 'old' }
  s.handleSessionExpired()
  expect(s.jiraConfigForm.token).toBe(''); expect(s.jiraConfigForm.username).toBe(''); expect(s.activeWebRunId).toBe(''); expect(s.webRunCapabilities).toBeUndefined()
  s.onLoginSuccess(B); await flushPromises(); expect(s.automationReports).toEqual([])
  expect(JSON.stringify(localStorage)).toContain('legacy-secret')
  expect(localStorage.getItem('ai-test-platform.automation-reports.v1')).toBe(legacy)
})

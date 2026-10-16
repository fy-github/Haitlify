import { describe, expect, it } from 'vitest'
import appSource from './App.vue?raw'

describe('Web run monitor structure', () => {
  it('uses the workspace monitor instead of legacy visual drawers', () => {
    expect(appSource).toContain("import WebAutomationWorkspace from './components/automation/WebAutomationWorkspace.vue'")
    expect(appSource).toContain(':mode="webAutomationWorkspaceMode"')
    expect(appSource).toContain('@enter-monitor="enterWebRunMonitor"')
    expect(appSource).toContain('@return-workbench="returnWebRunWorkbench"')
    expect(appSource).toContain('@execute="startVisualWebAutomationRun"')
    expect(appSource).toContain('@export-cases="exportAutomationJson"')
    expect(appSource).toContain("if (automationKind.value === 'web') {")
    expect(appSource).toContain('exportWebAutomationJson()')
    expect(appSource).not.toContain('webRunDrawerVisible')
    expect(appSource).not.toContain('openWebRunDrawer')
    expect(appSource).not.toContain('web-run-visual-drawer')
  })

  it('keeps one active-run session and query recovery boundary', () => {
    expect(appSource).toContain('WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY')
    expect(appSource).toContain('resolveWebRunRecovery')
    expect(appSource).toContain('createWebRunPollCoordinator')
    expect(appSource).toContain('writeWebMonitorQuery')
    expect(appSource).toContain("url.searchParams.set('view', 'monitor')")
    expect(appSource).toContain("url.searchParams.delete('runId')")
  })

  it('uses token based polling for Web case Agent runs so a stale poll cannot freeze preflight', () => {
    expect(appSource).toContain('const webCaseAgentPollCoordinator = createWebRunPollCoordinator()')
    expect(appSource).toContain('const poll = webCaseAgentPollCoordinator.start(runId)')
    expect(appSource).toContain('webCaseAgentPollCoordinator.isCurrent(runId, poll.token)')
    expect(appSource).not.toContain('let webCaseAgentPolling = false')
  })

  it('resolves monitor result actions only from active run cases and clears selection through a handler', () => {
    const hydrationStart = appSource.indexOf('const hydrateActiveWebRunCasesFromSnapshot =')
    const hydrationEnd = appSource.indexOf('const applyWebRunSnapshot =', hydrationStart)
    const hydrationBlock = appSource.slice(hydrationStart, hydrationEnd)
    const resolverStart = appSource.indexOf('const activeWebRunCaseById =')
    const resolverEnd = appSource.indexOf('const openWebAutomationRunLog =', resolverStart)
    const resolverBlock = appSource.slice(resolverStart, resolverEnd)
    const snapshotStart = appSource.indexOf('const applyWebRunSnapshot =')
    const snapshotEnd = appSource.indexOf('const writeWebMonitorQuery =', snapshotStart)
    const snapshotBlock = appSource.slice(snapshotStart, snapshotEnd)

    expect(hydrationBlock).toContain('snapshot.results.map((result) => result.caseId)')
    expect(hydrationBlock).toContain('snapshot.currentCase?.id')
    expect(hydrationBlock).toContain('webAutomationCases.value.map')
    expect(hydrationBlock).toContain('if (!savedCase) continue')
    expect(snapshotBlock).toContain('hydrateActiveWebRunCasesFromSnapshot(snapshot)')
    expect(resolverBlock).toContain('activeWebRunCases.value.find')
    expect(resolverBlock).not.toContain('webAutomationCases.value.find')
    expect(appSource).toContain('const clearWebAutomationSelection = () => {')
    expect(appSource).toContain('webAutomationSelectedIds.value = []')
    expect(appSource).toContain('clearWebRunLocalMessages()')
    expect(appSource).toContain('@clear-selected="clearWebAutomationSelection"')
  })

  it('keeps 404 recovery evidence in workbench mode and clears monitor query state', () => {
    const clearStart = appSource.indexOf('const clearRecoveredWebRun =')
    const clearEnd = appSource.indexOf('const finalizeWebRunSnapshot =', clearStart)
    const clearBlock = appSource.slice(clearStart, clearEnd)

    expect(clearBlock).toContain("webRunConnectionState.value = 'interrupted'")
    expect(clearBlock).toContain('webRunInterruptionMessage.value = message')
    expect(clearBlock).toContain("webAutomationWorkspaceMode.value = 'workbench'")
    expect(clearBlock).toContain("writeWebMonitorQuery('workbench', '', 'replace')")
    expect(clearBlock).not.toContain('activeWebRunSnapshot.value = undefined')
    expect(clearBlock).not.toContain('webAutomationRunReport.value = undefined')
    expect(appSource).toContain("interrupted: '连接中断'")
  })

  it('keeps all supported browsers in environment and case editors', () => {
    expect(appSource).toContain('<el-option label="跟随用例" value="follow-case" />')
    expect(appSource).toContain('<el-option label="Google Chrome" value="chrome" />')
    expect(appSource).toContain('<el-option label="Microsoft Edge" value="msedge" />')
    expect(appSource).toContain('<el-option label="Firefox" value="firefox" />')
    expect(appSource).toContain('<el-option label="WebKit" value="webkit" />')
  })

  it('removes the Web toolbar browser override and advanced login-state editor', () => {
    expect(appSource).not.toContain('<span>测试浏览器</span>')
    expect(appSource).not.toContain('<strong>高级登录态</strong>')
    expect(appSource).not.toContain('addWebEnvironmentVariable')
  })
})

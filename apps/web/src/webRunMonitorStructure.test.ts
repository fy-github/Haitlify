import { describe, expect, it } from 'vitest'
import appSource from './App.vue?raw'
import workspaceSource from './components/views/WebExecutionPage.vue?raw'
import lifecycleSource from './ui/webRunLifecycle.ts?raw'
import navigationSource from './ui/useAppNavigation.ts?raw'
import evidenceSource from './ui/useExecutionEvidence.ts?raw'
import preparationSource from './ui/useWebPreparation.ts?raw'
import modelSource from './ui/useWebWorkspaceModel.ts?raw'
import environmentSource from './components/automation/EnvironmentEditorPanel.vue?raw'
import caseEditorSource from './components/cases/CaseEditorDrawer.vue?raw'
import assetsSource from './ui/useExecutionAssets.ts?raw'

describe('Web run monitor structure', () => {
  it('uses the workspace monitor instead of legacy visual drawers', () => {
    expect(workspaceSource).toContain("import WebAutomationWorkspace from '../automation/WebAutomationWorkspace.vue'")
    expect(workspaceSource).toContain(':mode="webAutomationWorkspaceMode"')
    expect(workspaceSource).toContain('@enter-monitor="enterWebRunMonitor"')
    expect(workspaceSource).toContain('@return-workbench="returnWebRunWorkbench"')
    // 确认 → 冻结快照 → 执行:工作区把确认框里的范围/环境/浏览器/循环作为快照交给 App,
    // App 只发快照(见 App.webRunStartSnapshot.test.ts 的确认冻结与取消零请求用例)。
    expect(workspaceSource).toContain('@execute="(request) => startVisualWebAutomationRun(undefined, request)"')
    expect(workspaceSource).not.toContain('@execute="startVisualWebAutomationRun"')
    expect(workspaceSource).toContain('@rerun="() => startVisualWebAutomationRun()"')
    expect(workspaceSource).toContain('@export-cases="exportAutomationJson"')
    expect(assetsSource).toContain("if (deps.automationKind.value === 'web') {")
    expect(assetsSource).toContain('exportWebAutomationJson()')
    expect(workspaceSource).not.toContain('web-run-visual-drawer')
  })

  it('keeps one active-run session and query recovery boundary', () => {
    expect(lifecycleSource).toContain('WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY')
    expect(lifecycleSource).toContain('resolveWebRunRecovery')
    expect(lifecycleSource).toContain('createWebRunPollCoordinator')
    expect(lifecycleSource).toContain('writeMonitorQuery')
    expect(navigationSource).toContain("url.searchParams.set('view', 'monitor')")
    expect(navigationSource).toContain("url.searchParams.delete('runId')")
  })

  it('retires Agent polling while retaining token based normal Runner polling', () => {
    expect(lifecycleSource).not.toContain('webCaseAgentPollCoordinator')
    expect(lifecycleSource).toContain('const poll = poller.start(runId)')
    expect(lifecycleSource).toContain('poller.isCurrent(runId, poll.token)')
    expect(lifecycleSource).not.toContain('let webCaseAgentPolling = false')
  })

  it('resolves monitor result actions only from active run cases and clears selection through a handler', () => {
    const hydrationBlock = lifecycleSource.slice(lifecycleSource.indexOf('const applyWebRunSnapshot ='), lifecycleSource.indexOf('const clearRecoveredWebRun ='))
    const resolverStart = evidenceSource.indexOf('const activeWebRunResultById =')
    expect(resolverStart).toBeGreaterThanOrEqual(0)
    const resolverEnd = evidenceSource.indexOf('const openWebAutomationRunLog =', resolverStart)
    const resolverBlock = evidenceSource.slice(resolverStart, resolverEnd)

    expect(hydrationBlock).toContain('snapshot.results.map(result => result.caseId)')
    expect(hydrationBlock).toContain('snapshot.currentCase?.id')
    expect(hydrationBlock).toContain('ports.getSavedCases().map')
    expect(hydrationBlock).toContain('if (testCase && !active.has')
    expect(resolverBlock).toContain('deps.activeWebRunCases.value.find')
    expect(resolverBlock).not.toContain('webAutomationCases.value.find')
    expect(resolverBlock).toContain('resolveWebRunCaseId(caseId, new Set(deps.activeWebRunCases.value.map')
    expect(resolverBlock).toContain('results.find((row) => row.caseId === caseId)')
    expect(resolverBlock).toContain('return testCase && result ? { testCase, result } : undefined')
    expect(preparationSource).toContain('const clearWebAutomationSelection = () => {')
    expect(preparationSource).toContain('deps.webAutomationSelectedIds.value = []')
    expect(preparationSource).toContain('clearWebRunLocalMessages()')
    expect(workspaceSource).toContain('@clear-selected="clearWebAutomationSelection"')
  })

  it('keeps 404 recovery evidence in workbench mode and clears monitor query state', () => {
    const clearStart = lifecycleSource.indexOf('const clearRecoveredWebRun =')
    const clearEnd = lifecycleSource.indexOf('const finalizeWebRunSnapshot =', clearStart)
    const clearBlock = lifecycleSource.slice(clearStart, clearEnd)

    expect(clearBlock).toContain("state.webRunConnectionState.value = 'interrupted'")
    expect(clearBlock).toContain('state.webRunInterruptionMessage.value = message')
    expect(clearBlock).toContain("state.webAutomationWorkspaceMode.value = 'workbench'")
    expect(clearBlock).toContain("ports.writeMonitorQuery('workbench', '', 'replace')")
    expect(clearBlock).not.toContain('activeWebRunSnapshot.value = undefined')
    expect(clearBlock).not.toContain('webAutomationRunReport.value = undefined')
    expect(modelSource).toContain("interrupted: '连接中断'")
  })

  it('keeps all supported browsers in environment and case editors', () => {
    expect(environmentSource).toContain('<el-option label="跟随用例" value="follow-case" />')
    for (const [label, value] of [['Google Chrome', 'chrome'], ['Microsoft Edge', 'msedge'], ['Firefox', 'firefox'], ['WebKit', 'webkit']]) {
      expect(`${environmentSource}\n${caseEditorSource}`).toContain(`<el-option label="${label}" value="${value}" />`)
    }
  })

  it('removes the Web toolbar browser override and advanced login-state editor', () => {
    expect(appSource).not.toContain('<span>测试浏览器</span>')
    expect(appSource).not.toContain('<strong>高级登录态</strong>')
    expect(appSource).not.toContain('addWebEnvironmentVariable')
  })
})

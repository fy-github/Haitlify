// This test runs in Vitest's Node environment; the application tsconfig only includes browser types.
// @ts-expect-error Node's test-only filesystem API is intentionally not part of the browser type set.
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import appSource from './App.vue?raw'
import aiConfigViewSource from './components/views/AiConfigView.vue?raw'
import casesViewSource from './components/views/CasesView.vue?raw'
import settingsViewSource from './components/views/SettingsView.vue?raw'
import appShellSource from './components/shell/AppShell.vue?raw'

const studioCanvasStyles = readFileSync(new URL('./styles/studio-canvas.css', import.meta.url), 'utf8')
const studioTokens = readFileSync(new URL('./styles/tokens.css', import.meta.url), 'utf8')
const webAutomationScopeSource = readFileSync(new URL('./components/automation/WebAutomationScopePane.vue', import.meta.url), 'utf8')
const webRunPreflightSource = readFileSync(new URL('./components/automation/WebRunPreflightPanel.vue', import.meta.url), 'utf8')
const webRunSummarySource = readFileSync(new URL('./components/automation/WebRunMonitorSummary.vue', import.meta.url), 'utf8')
const webRunPreparationSource = readFileSync(new URL('./components/automation/WebRunPreparationBar.vue', import.meta.url), 'utf8')

const extractCssBlock = (source: string, marker: string, startAt = 0) => {
  const start = source.indexOf(marker, startAt)
  if (start === -1) throw new Error(`Cannot find CSS block: ${marker}`)
  const openingBrace = source.indexOf('{', start)
  let depth = 0
  for (let index = openingBrace; index < source.length; index += 1) {
    if (source[index] === '{') depth += 1
    if (source[index] === '}') depth -= 1
    if (depth === 0) return source.slice(start, index + 1)
  }
  throw new Error(`Cannot close CSS block: ${marker}`)
}

const extractApiWorkspaceBlock = () => {
  const workspaceClass = 'class="workspace"'
  const workspaceClassIndex = appSource.indexOf(workspaceClass)
  expect(workspaceClassIndex).toBeGreaterThan(-1)
  const workspaceStart = appSource.lastIndexOf('<main', workspaceClassIndex)
  const workspaceOpeningEnd = appSource.indexOf('>', workspaceStart)
  const start = appSource.indexOf(`<template v-if="isCaseWorkbenchNav">`, workspaceOpeningEnd)
  const end = appSource.indexOf(`<template v-else-if="isSettingsCenterNav">`, start)
  return appSource.slice(start, end)
}

const extractWebStudioCanvasBlock = () => {
  const mainBlock = extractApiWorkspaceBlock()
  const startMarker = '<section v-else class="studio-case-design-canvas">'
  const endMarker = `</CaseWorkbenchView>`
  const start = mainBlock.indexOf(startMarker)
  const end = mainBlock.indexOf(endMarker, start)

  if (start === -1 || end === -1 || end <= start) {
    throw new Error('Cannot isolate the Web Studio Canvas branch')
  }

  const branch = mainBlock.slice(start, end)
  const outerSectionEnd = branch.lastIndexOf('</section>')
  if (outerSectionEnd === -1) throw new Error('Cannot find the Web Studio Canvas closing section')
  return branch.slice(0, outerSectionEnd + '</section>'.length)
}

const extractInterfaceStudioCanvasBlock = () => {
  const apiBlock = extractApiWorkspaceBlock()
  const interfaceStart = apiBlock.indexOf('<section v-if="generationKind === \'interface\'" class="studio-interface-case-canvas">')
  const webStart = apiBlock.indexOf('<section v-else class="studio-case-design-canvas">', interfaceStart)

  if (interfaceStart === -1 || webStart === -1 || webStart <= interfaceStart) {
    throw new Error('Cannot isolate the interface Studio Canvas branch')
  }

  return apiBlock.slice(interfaceStart, webStart)
}

const extractComponentBlocks = (componentName: string) => {
  const blocks: string[] = []
  let start = appSource.indexOf(`<${componentName}`)

  while (start !== -1) {
    const end = appSource.indexOf('/>', start)
    if (end === -1) throw new Error(`Cannot close component block: ${componentName}`)
    blocks.push(appSource.slice(start, end + 2))
    start = appSource.indexOf(`<${componentName}`, end)
  }

  return blocks
}

describe('App shell structure', () => {
  it('uses responsive Studio Canvas drawers and the local-first offline notice', () => {
    expect(appSource).toContain("import WorkflowStateView from './components/workflow/WorkflowStateView.vue'")
    expect(appSource).toContain("import ResponsiveDrawer from './components/workflow/ResponsiveDrawer.vue'")
    expect(appSource).toContain("import InterfaceOperationTable from './components/workflow/InterfaceOperationTable.vue'")
    expect(appSource).toContain('data-testid="inspector-drawer-button"')
    expect(appSource).toContain('data-testid="context-pane-drawer-button"')
    expect(appSource).toContain('v-model="narrowNavigationVisible"')
    expect(appSource).toContain('class="studio-offline-notice"')
    expect(appSource).toContain(':sidebar-collapsed="isNarrowViewport ? !narrowNavigationVisible : railCollapsed"')
  })

  it('keeps rounded inner corners on cards inside every responsive drawer', () => {
    const drawerCardRule = extractCssBlock(studioCanvasStyles, '.el-drawer.studio-responsive-drawer .web-automation-scope-pane')
    expect(drawerCardRule).toContain('border-radius: var(--radius-lg);')
    expect(drawerCardRule).not.toContain('margin: 0 -20px -20px;')
    expect(studioCanvasStyles).toContain('.el-drawer.web-automation-drawer.is-web-environment .environment-editor')
    expect(studioCanvasStyles).toContain('border-radius: var(--radius-md);')
  })

  it('does not render the removed global workflow strip between the topbar and workspace', () => {
    expect(appSource).not.toContain("import GlobalFlowStrip from './components/shell/GlobalFlowStrip.vue'")
    expect(appSource).not.toContain('<template #flow>')
    expect(appSource).not.toContain('<GlobalFlowStrip')
    expect(appShellSource).not.toContain('studio-app-shell__flow')
    expect(studioCanvasStyles).not.toContain('.global-flow-strip')
  })

  it('keeps exactly one stage-specific primary command during delivery', () => {
    expect(appSource).toContain(':primary-action="caseDesignHeaderPrimaryAction"')
    expect(appSource).not.toContain('data-testid="confirm-delivery"')
    expect(appSource).not.toContain('@confirm="deliverGeneratedCases"')
  })

  it('reserves a workspace grid row when the sync conflict notice is its first child', () => {
    const workspaceClassIndex = appSource.indexOf('class="workspace"')
    const workspaceStart = appSource.lastIndexOf('<main', workspaceClassIndex)
    const workspaceEnd = appSource.indexOf(`<template v-if="isCaseWorkbenchNav">`, workspaceStart)
    const workspacePrefix = appSource.slice(workspaceStart, workspaceEnd)

    expect(workspacePrefix).toContain('<div v-if="workspaceSyncConflict" class="studio-sync-notice"')
    expect(studioCanvasStyles).toMatch(
      /\.workspace:has\(> \.studio-sync-notice\)\s*\{\s*grid-template-rows:\s*auto 58px auto 1fr;/
    )
  })

  it('derives the topbar sync state with conflicts taking priority', () => {
    expect(appSource).toContain("const workspaceSyncState = computed<'synced' | 'syncing' | 'conflict'>(() => {")
    expect(appSource).toContain("if (workspaceSyncConflict.value) return 'conflict'")
    expect(appSource).toContain("if (workspaceSyncLoading.value) return 'syncing'")
    expect(appSource).toContain(':sync-state="workspaceSyncState"')
  })

  it('keeps smart default breadcrumbs for interface and Web generation', () => {
    expect(appSource).toContain("if (activeNav.value !== 'api' || globalProjectLabel.value !== '默认项目') {")
    expect(appSource).toContain('return globalProjectLabel.value')
    expect(appSource).toContain("return '智能用例设计'")
    expect(appSource).toContain(':project-label="studioProjectLabel"')
  })

  it('keeps AI configuration out of the template settings view', () => {
    expect(settingsViewSource).not.toContain('<strong>AI 配置</strong>')
    expect(settingsViewSource).not.toContain('保存 AI 配置')
  })

  it('renders AI configuration form inside the dedicated AI config page', () => {
    expect(aiConfigViewSource).toContain('<h1>平台 AI</h1>')
    expect(aiConfigViewSource).toContain('保存变更')
    expect(aiConfigViewSource).toContain('data-testid="platform-ai-workbench"')
  })

  it('renders the Platform AI workbench with flat navigation scoped to the AI page', () => {
    const mainBlock = aiConfigViewSource

    // Flat local navigation is data-driven (v-for over platformAiPages), so assert the nav binding
    // rather than the rendered labels, plus the literal page content below.
    expect(mainBlock).toContain('platformAiPages')
    expect(mainBlock).toContain('保存变更')
    expect(mainBlock).toContain('连接测试')
    expect(mainBlock).toContain('data-testid="platform-ai-policies"')
    expect(mainBlock).toContain('data-testid="platform-ai-connections"')
    expect(mainBlock).toContain('data-testid="platform-ai-diagnostics"')
    expect(mainBlock).toContain('data-testid="platform-ai-rules"')
    expect(mainBlock).toContain('<ResponsiveDrawer v-model="connectionEditorOpen"')
    expect(mainBlock).not.toContain('AI 配置表单')
    // 总开关必须有独立入口；单连接启用不能替代全局启用。
    expect(mainBlock).toContain('data-testid="platform-ai-global-switch"')
    expect(mainBlock).toContain('v-model="aiSettingsForm.enabled"')
    expect(mainBlock).not.toContain('ai-provider-console')
    expect(appSource).not.toContain('shellContextVisible')
    expect(appSource).not.toContain('inlineContextVisible')
    expect(appSource).toContain("'is-workbench': isWorkbenchNav")
    expect(appSource).toContain('const isQualityWorkbenchNav = computed(() =>')
    expect(appSource).toContain("['reports', 'defects'].includes(activeNav.value)")
    expect(appSource).not.toContain('<template v-else-if="activeNav === \'reports\'">')
    expect(appSource).not.toContain('<template v-else-if="activeNav === \'defects\'">')
    expect(appSource).not.toContain('<DefectsView />')
    expect(appSource).not.toContain('<ReportsView />')
    expect(mainBlock).toContain('内置 / 已自定义')
    expect(mainBlock).toContain('写操作默认关闭')
    expect(mainBlock).toContain('保存规则配置')
    expect(mainBlock).toContain('刷新 Agent')
    // 设置中心与质量工作台共用同一 workbench 壳样式(内部分组导航已去掉,内容区独占全宽)
    expect(studioCanvasStyles).toContain('.workspace.is-workbench')
    expect(studioCanvasStyles).toContain('.workbench__content')
    expect(studioCanvasStyles).not.toContain('.workbench__nav')
    expect(studioCanvasStyles).toContain('.workbench__pane')
    expect(studioCanvasStyles).not.toContain('.settings-center')
    expect(mainBlock).toContain('新增 Agent')
    expect(mainBlock).toContain('新增 Skill')
    expect(mainBlock).toContain('Skill 路径')
    expect(mainBlock).toContain('关联 Skill')
    expect(mainBlock).toContain('编辑内置 Skill')
    expect(mainBlock).toContain('编辑 Skill')
    expect(mainBlock).toContain('恢复默认')
    expect(mainBlock).toContain('刷新 MCP')
    expect(mainBlock).toContain('recordId')

    // Agent/MCP 扩展属于平台 AI 页;模板与字段视图内不得出现
    expect(settingsViewSource).not.toContain('Agent配置')
    expect(settingsViewSource).not.toContain('MCP配置')
  })

  it('uses the Web Studio Canvas surface rhythm for Platform AI', () => {
    const workbenchRule = extractCssBlock(studioCanvasStyles, '.platform-ai-workbench')
    const toolbarRule = extractCssBlock(studioCanvasStyles, '.platform-ai-toolbar')
    const connectionRowRule = extractCssBlock(studioCanvasStyles, '.platform-ai-connection-row')
    const drawerRule = extractCssBlock(studioCanvasStyles, '.el-drawer.studio-responsive-drawer.platform-ai-drawer')

    expect(workbenchRule).toContain('grid-template-rows: 70px minmax(0, 1fr)')
    expect(toolbarRule).toContain('min-height: 52px')
    expect(connectionRowRule).toContain('border-left: 3px solid transparent')
    expect(drawerRule).toContain('max-width: calc(100vw - 16px)')
    // 平台 AI 页也吃全站 AI 助手青绿氛围(与 .workspace / .workbench__pane 同款)
    expect(workbenchRule).toContain('radial-gradient')
  })

  it('renders confirmed AI draft points in the Web generation workflow', () => {
    const mainBlock = extractApiWorkspaceBlock()

    expect(mainBlock).toContain('<TestPointParseStage')
    expect(mainBlock).toContain('<NaturalLanguageCaseGenerationStage')
    expect(mainBlock).toContain('<AutomationCompileStage')
    expect(mainBlock).toContain('@confirm-all="confirmAiDraftPoints(pendingAiDraftPoints, \'确认测试点\')"')
    expect(mainBlock).toContain('@compile="compileWebAutomationCases()"')
  })

  it('moves completed requirement analysis into the generation stage and persists its draft points', () => {
    expect(appSource).toContain('const completeAiRequirementAnalysis = (progressPrefix: string) => {')
    expect(appSource).toContain("if (draftPointCount > 0) activeCaseDesignStage.value = 'test-parse'")
    expect(appSource).toContain('persistCaseDesignDraft()')
    expect(appSource.match(/completeAiRequirementAnalysis\(/g)?.length).toBe(3)
  })

  it('renders requirement source actions inside the dedicated import stage', () => {
    expect(appSource).toContain("import RequirementImportStage from './components/workflow/RequirementImportStage.vue'")
    expect(appSource).toContain('<RequirementImportStage')
    expect(appSource).toContain('@pick-files="pickWebCaseFiles"')
    expect(appSource).toContain('@import-figma="promptFigmaDesignImport"')
    expect(appSource).not.toContain('@pick-open-api="pickOpenApiRequirementFiles"')
    expect(appSource).not.toContain('web-flow-card')
    expect(appSource).not.toContain('requirement-flow-steps')
  })

  it('removes the global workspace context column from the app shell', () => {
    expect(appSource).not.toContain("import WorkspaceContextPane from './components/shell/WorkspaceContextPane.vue'")
    expect(appSource).not.toContain('<WorkspaceContextPane')
    expect(appSource).not.toContain('<template #context>')
    expect(appSource).not.toContain(':context-visible=')
    expect(appShellSource).not.toContain('contextVisible')
    expect(appShellSource).not.toContain('has-context')
    expect(appShellSource).not.toContain('studio-app-shell__context')
    expect(studioCanvasStyles).not.toContain('.studio-app-shell.has-context')
    expect(studioCanvasStyles).not.toContain('.studio-app-shell__context')
  })

  it('keeps OpenAPI import in the interface workflow instead of the context pane', () => {
    expect(appSource).toContain('<InterfaceCaseContextPane')
    expect(appSource).not.toContain('@import-open-api="pickOpenApiFiles"')
    expect(appSource).not.toContain(':sources="interfaceCaseSources"')
    expect(appSource).not.toContain(':active-source-id="activeProjectId"')
    expect(appSource).toContain('@action="pickOpenApiFiles"')
    expect(appSource).toContain('<RequirementImportStage')
    expect(appSource).not.toContain('class="requirement-flow-head"')
  })

  it('renders imported OpenAPI operations in the interface workflow before case generation', () => {
    const interfaceBlock = extractInterfaceStudioCanvasBlock()
    const contextPaneBlocks = extractComponentBlocks('InterfaceCaseContextPane')

    expect(appSource).toContain('const interfaceOperationSelectionVisible = computed(() =>')
    expect(appSource).toContain('importedProjects.value.length > 0')
    expect(appSource).toContain('generatedCases.value.length === 0')
    expect(appSource).toContain("['import', 'generate'].includes(interfaceCaseWorkflow.value.activeStage)")
    expect(appSource).toContain('const interfaceOperationSelectionEmpty = computed(() =>')
    expect(appSource).not.toContain('const interfaceImportState = computed(() =>')
    expect(appSource).not.toContain("OpenAPI 已导入，继续配置生成策略")

    expect(interfaceBlock).toContain('v-if="interfaceCaseWorkflow.activeStage === \'import\' && importedProjects.length === 0"')
    expect(interfaceBlock).toContain('title="导入 OpenAPI 开始接口用例设计"')
    expect(interfaceBlock).toContain('description="导入后可筛选接口并配置生成策略"')
    expect(interfaceBlock).toContain('action-label="导入 OpenAPI"')
    expect(interfaceBlock).toContain('@action="pickOpenApiFiles"')
    expect(interfaceBlock).toContain('v-else-if="interfaceOperationSelectionEmpty"')
    expect(interfaceBlock).toContain('title="没有符合条件的接口"')
    expect(interfaceBlock).toContain('description="当前筛选条件没有匹配结果"')
    expect(interfaceBlock).toContain('@action="resetInterfaceOperationFilters"')
    expect(interfaceBlock).toContain('<InterfaceOperationTable')
    expect(interfaceBlock).toContain('v-else-if="interfaceOperationSelectionVisible"')
    expect(interfaceBlock).toContain(':operations="filteredOperations"')
    expect(interfaceBlock).toContain(':selected-ids="selectedOperationIds"')
    expect(interfaceBlock).toContain('@toggle-operation="toggleOperation"')
    expect(interfaceBlock).toContain('@select-filtered="selectFilteredOperations"')
    expect(interfaceBlock).toContain('@clear-selected="clearSelectedOperations"')
    expect(interfaceBlock.indexOf('<InterfaceOperationTable')).toBeLessThan(interfaceBlock.indexOf('<InterfaceCaseTable'))

    expect(appSource).toContain('const resetInterfaceOperationFilters = () => {')
    expect(appSource).toContain("importFilter.module = 'ALL'")
    expect(appSource).toContain("importFilter.method = 'ALL'")
    expect(appSource).toContain("importFilter.pathKeyword = ''")
    expect(appSource).toContain("importFilter.summaryKeyword = ''")

    expect(contextPaneBlocks).toHaveLength(1)
    for (const block of contextPaneBlocks) {
      expect(block).not.toContain(':operations=')
      expect(block).not.toContain(':selected-operation-ids=')
      expect(block).not.toContain('@toggle-operation=')
      expect(block).not.toContain('@select-filtered=')
      expect(block).not.toContain('@clear-selected=')
      expect(block).toContain(':module="importFilter.module"')
      expect(block).toContain(':granularity="generation.granularity"')
    }

    expect(appSource).toContain('const selectedOperationIdSet = computed(() => new Set(selectedOperationIds.value))')
    expect(appSource).toContain('(parsed.value?.operations || []).filter((operation) => selectedOperationIdSet.value.has(operation.id))')
    expect(appSource).toContain('selectedOperationIds.value = Array.from(new Set([')
    expect(appSource).toContain('...selectedOperationIds.value,')
    expect(appSource).toContain('...filteredOperations.value.map((operation) => operation.id)')
  })

  it('keeps generated interface cases visible across non-delivery workflow stages', () => {
    const interfaceBlock = extractInterfaceStudioCanvasBlock()

    expect(interfaceBlock).toContain("['import', 'generate', 'review'].includes(interfaceCaseWorkflow.activeStage) && filteredCases.length > 0")
    expect(interfaceBlock).toContain("['import', 'generate', 'review'].includes(interfaceCaseWorkflow.activeStage) && generatedCases.length > 0")
    expect(interfaceBlock).toContain('v-else-if="interfaceCaseWorkflow.activeStage === \'deliver\'"')
    expect(interfaceBlock).not.toMatch(/<InterfaceCaseDeliveryStage\s+v-else\s/)
  })

  it('renders interface and Web generation as shared Studio Canvas workflows', () => {
    const apiBlock = extractApiWorkspaceBlock()
    const interfaceStart = apiBlock.indexOf('<section v-if="generationKind === \'interface\'" class="studio-interface-case-canvas">')
    const webStart = apiBlock.indexOf('<section v-else class="studio-case-design-canvas">', interfaceStart)
    const interfaceBlock = apiBlock.slice(interfaceStart, webStart)
    const webBlock = apiBlock.slice(webStart)

    expect(interfaceStart).toBeGreaterThan(-1)
    expect(webStart).toBeGreaterThan(interfaceStart)
    expect(interfaceBlock).toContain('class="studio-interface-case-canvas"')
    expect(interfaceBlock).toContain('<WorkflowHeader')
    expect(interfaceBlock).toContain('<InterfaceCaseTable')
    expect(appSource).toContain('<InterfaceCaseContextPane')
    expect(appSource).toContain('<InterfaceReviewInspector')
    expect(interfaceBlock).toContain('<InterfaceCaseDeliveryStage')
    expect(interfaceBlock).toContain(':module-options="previewModules"')
    expect(interfaceBlock).toContain(':case-type-options="previewCaseTypes"')
    expect(interfaceBlock).toContain(':priority-options="previewPriorities"')
    expect(interfaceBlock).toContain(':review-status-options="previewReviewStatuses"')
    expect(interfaceBlock).toContain("['import', 'generate', 'review'].includes(interfaceCaseWorkflow.activeStage) && filteredCases.length > 0")
    expect(interfaceBlock).toContain('variant="filtered-empty"')
    expect(interfaceBlock).toContain('@action="resetInterfaceCaseFilters"')
    expect(interfaceBlock).toContain('data-testid="interface-context-pane-drawer-button"')
    expect(interfaceBlock).toContain('data-testid="interface-inspector-drawer-button"')
    expect(interfaceBlock).not.toContain('class="topbar"')
    expect(interfaceBlock).not.toContain('class="control-strip"')
    expect(interfaceBlock).not.toContain('class="main-grid"')
    expect(interfaceBlock).not.toContain('class="interface-upload-panel"')

    expect(webBlock).toContain('<WorkflowHeader')
    expect(webBlock).toContain('<RequirementImportStage')
    expect(webBlock).toContain('<NaturalLanguageCaseGenerationStage')
    expect(webBlock).toContain('<AutomationCompileStage')
    expect(webBlock).toContain('<CaseDeliveryStage')
    expect(appSource.match(/import WorkflowHeader from/g)).toHaveLength(1)
    expect(appSource).toContain(":title=\"interfaceContextVisible ? '接口与配置' : '需求与模块'\"")
    expect(appSource).toContain(":title=\"interfaceInspectorVisible ? 'AI 评审' : 'AI 质量评审'\"")
  })

  it('defines the approved interface Studio Canvas and compact layout styles', () => {
    expect(studioCanvasStyles).toContain('.studio-interface-case-canvas')
    expect(studioCanvasStyles).toContain('.studio-interface-case-canvas .interface-case-toolbar')
    expect(studioCanvasStyles).toContain('.studio-interface-case-canvas .interface-case-table')
    expect(studioCanvasStyles).toContain('.studio-app-shell__inspector .interface-review-inspector')
    expect(studioCanvasStyles).toContain('@media (max-width: 1100px)')

    const interfaceStylesStart = studioCanvasStyles.indexOf('.workspace.is-studio-interface-case')
    const compactStyles = extractCssBlock(studioCanvasStyles, '@media (max-width: 1100px)', interfaceStylesStart)
    expect(compactStyles).toContain('grid-template-columns: minmax(140px, 0.72fr) minmax(0, 1.8fr) auto;')
    expect(compactStyles).toContain('grid-column: 2;')
    expect(compactStyles).toContain('overflow-x: auto;')
    expect(compactStyles).not.toContain('grid-column: 1 / -1;')

    const desktopHeaderStyles = extractCssBlock(
      studioCanvasStyles,
      '.studio-interface-case-canvas > .studio-workflow-header',
      interfaceStylesStart
    )
    expect(desktopHeaderStyles).toContain('grid-template-columns: minmax(140px, 0.65fr) minmax(340px, 2fr) auto;')

    const desktopStageStyles = extractCssBlock(
      studioCanvasStyles,
      '.studio-interface-case-canvas .studio-workflow-step',
      interfaceStylesStart
    )
    expect(desktopStageStyles).toContain('min-width: max-content;')

    const desktopStageLabelStyles = extractCssBlock(
      studioCanvasStyles,
      '.studio-interface-case-canvas .studio-workflow-step > span',
      interfaceStylesStart
    )
    expect(desktopStageLabelStyles).toContain('overflow: visible;')
    expect(desktopStageLabelStyles).toContain('text-overflow: clip;')
    expect(desktopStageLabelStyles).not.toContain('text-overflow: ellipsis;')

    const narrowStyles = extractCssBlock(studioCanvasStyles, '@media (max-width: 600px)')
    expect(narrowStyles).toContain('grid-template-rows: 88px minmax(0, 1fr);')
    expect(narrowStyles).toContain('grid-template-rows: 41px minmax(0, 1fr);')
    expect(narrowStyles).toContain('grid-column: 1 / -1;')
    expect(narrowStyles).toContain('overflow-x: auto;')

    const toolbarStyles = extractCssBlock(
      studioCanvasStyles,
      '.studio-interface-case-canvas .studio-interface-case-canvas__stage .interface-case-toolbar'
    )
    expect(toolbarStyles).toContain('width: 100%;')
    expect(toolbarStyles).toContain('max-width: 100%;')
    expect(toolbarStyles).toContain('min-width: 0;')
    expect(toolbarStyles).toContain('overflow-x: auto;')
    expect(toolbarStyles).not.toContain('min-width: max-content;')
    expect(studioCanvasStyles).toContain('.studio-interface-case-canvas .studio-interface-case-canvas__stage .interface-case-toolbar > *')

    const operationTableStyles = extractCssBlock(
      studioCanvasStyles,
      '.studio-interface-case-canvas__stage > .interface-operation-table',
      interfaceStylesStart
    )
    expect(operationTableStyles).toContain('width: 100%;')
    expect(operationTableStyles).toContain('height: 100%;')
    expect(operationTableStyles).toContain('min-width: 0;')
    expect(operationTableStyles).toContain('min-height: 0;')

    const operationToolbarStyles = extractCssBlock(
      studioCanvasStyles,
      '.studio-interface-case-canvas .interface-operation-table__toolbar',
      interfaceStylesStart
    )
    expect(operationToolbarStyles).toContain('height: 48px;')
    expect(operationToolbarStyles).toContain('min-height: 48px;')
    expect(operationToolbarStyles).toContain('border-bottom: 1px solid var(--border-subtle);')

    const compactOperationTableStyles = extractCssBlock(
      compactStyles,
      '.studio-interface-case-canvas__stage > .interface-operation-table'
    )
    expect(compactOperationTableStyles).toContain('overflow: hidden;')

    const compactOperationGridStyles = extractCssBlock(
      compactStyles,
      '.studio-interface-case-canvas .interface-operation-table > .el-table.interface-operation-table__grid'
    )
    expect(compactOperationGridStyles).toContain('width: 100%;')
    expect(compactOperationGridStyles).toContain('min-width: 0;')

    const compactOperationScrollStyles = extractCssBlock(
      compactStyles,
      '.studio-interface-case-canvas .interface-operation-table .el-table__body-wrapper'
    )
    expect(compactOperationScrollStyles).toContain('.studio-interface-case-canvas .interface-operation-table .el-scrollbar__wrap')
    expect(compactOperationScrollStyles).toContain('overflow-x: auto;')

    const summaryNumberStyles = extractCssBlock(
      studioCanvasStyles,
      '.studio-interface-case-canvas .studio-interface-case-canvas__stage .interface-review-inspector__summary dd'
    )
    expect(summaryNumberStyles).toContain('font-weight: var(--font-weight-semibold);')

    const deliveryHeadingStyles = extractCssBlock(
      studioCanvasStyles,
      '.studio-interface-case-canvas .studio-interface-case-canvas__stage .interface-delivery-stage__header h2'
    )
    expect(deliveryHeadingStyles).toContain('font-weight: var(--font-weight-semibold);')

    const deliveryPrimaryStyles = extractCssBlock(
      studioCanvasStyles,
      '.studio-interface-case-canvas .studio-interface-case-canvas__stage .interface-delivery-stage__actions .interface-delivery-stage__primary'
    )
    expect(deliveryPrimaryStyles).toContain('box-shadow: none;')

    const contextHeaderStyles = extractCssBlock(
      studioCanvasStyles,
      '.el-drawer__body.el-drawer__body .studio-interface-context-pane .studio-interface-context-pane__header'
    )
    expect(contextHeaderStyles).toContain('color: var(--text-secondary);')
    expect(studioCanvasStyles).toContain(
      '.el-drawer__body.el-drawer__body .studio-interface-context-pane .studio-interface-context-pane__field > span'
    )
    expect(studioCanvasStyles).toContain(
      '.el-drawer__body.el-drawer__body .studio-interface-context-pane .studio-interface-context-pane__field > span'
    )

    const workflowStateActionStyles = extractCssBlock(
      studioCanvasStyles,
      '.studio-interface-case-canvas .studio-workflow-state__action',
      interfaceStylesStart
    )
    expect(workflowStateActionStyles).toContain('appearance: none;')
    expect(workflowStateActionStyles).toContain('min-height: 34px;')
    expect(workflowStateActionStyles).toContain('color: var(--brand-primary-contrast);')
    expect(workflowStateActionStyles).toContain('background: var(--brand-primary);')
    expect(workflowStateActionStyles).toContain('border-radius: var(--radius-control);')
    expect(workflowStateActionStyles).toContain('box-shadow: none;')
    expect(studioCanvasStyles).toContain('.studio-interface-case-canvas .studio-workflow-state__action:hover:not(:disabled)')
    expect(studioCanvasStyles).toContain('background: var(--brand-primary-hover);')
    expect(studioCanvasStyles).toContain('.studio-interface-case-canvas .studio-workflow-state__action:active:not(:disabled)')
    expect(studioCanvasStyles).toContain('background: var(--brand-primary-active);')
    expect(studioCanvasStyles).toContain('.studio-interface-case-canvas .studio-workflow-state__action:focus-visible')
    expect(studioCanvasStyles).toContain('outline: 3px solid var(--brand-primary-focus);')
  })

  it('removes the design case metric card above Web library cases', () => {
    const metricsStart = casesViewSource.indexOf('class="library-metrics"')
    const metricsEnd = casesViewSource.indexOf('<div class="case-panel library-panel">', metricsStart)
    const metricsBlock = casesViewSource.slice(metricsStart, metricsEnd)

    expect(metricsBlock).not.toContain('设计稿用例')
    expect(metricsBlock).toContain('已确认用例')
    expect(metricsBlock).toContain('已确认 Locator')
  })

  it('keeps Figma intake in the Web requirement workflow', () => {
    const mainBlock = extractApiWorkspaceBlock()

    expect(mainBlock).toContain('<RequirementImportStage')
    expect(mainBlock).toContain('@import-figma="promptFigmaDesignImport"')
    expect(appSource).toContain('const analyzeAiFigmaFile = async () => {')
    expect(appSource).toContain('const analyzeSelectedAiFigmaNode = async () => {')
  })

  it('renders AI review in the contextual inspector instead of a dialog', () => {
    expect(appSource).toContain("import AiReviewInspector from './components/workflow/AiReviewInspector.vue'")
    expect(appSource).toContain('<template #inspector>')
    expect(appSource).toContain('<AiReviewInspector')
    expect(appSource).not.toContain('class="ai-review-dialog"')
    expect(appSource).not.toContain('v-model="aiReviewDialogVisible"')
  })

  it('invalidates case review whenever generated review artifacts change', () => {
    expect(appSource).toContain('const invalidateCaseReview = () => {')
    expect(appSource).toContain('const compiledByPointId = new Map(generated.cases.map((testCase) => [testCase.metadata.agentSourcePointId, testCase]))')
    expect(appSource).toContain('const updatedCaseIds = new Set<string>()')
    expect(appSource).toContain('!generatedWebCases.value.some((item) => item.metadata.agentSourcePointId === testCase.metadata.agentSourcePointId)')
    expect(appSource).toContain('...generated.cases')
    expect(appSource).toContain('const removeGeneratedWebCase = (testCase: GeneratedWebAutomationCase) => {')
    expect(appSource.match(/@remove="removeGeneratedWebCase"/g)).toHaveLength(3)
    expect(appSource).toContain("invalidateCaseReview()\n    editVisible.value = false\n    ElMessage.success('已更新当前 Web 用例')")
    expect(appSource).toContain("if (activeCaseDesignStage.value === 'deliver' || activeCaseDesignStage.value === 'automation-compile') activeCaseDesignStage.value = 'review'")
  })

  it('keeps requirement-stage review outside the case-design workflow and guards review merges', () => {
    expect(appSource).toContain("if (stage === 'case') activeCaseDesignStage.value = 'review'")
    expect(appSource).not.toContain("activeAiReviewStage = 'requirement'; activeCaseDesignStage = 'review'")
    expect(appSource).toContain('const canMergeMultiAgentReview = computed(() =>')
    expect(appSource).toContain('&& aiReviewInsight.value.suggestions.length > 0')
    expect(appSource).toContain('if (!canMergeMultiAgentReview.value) {')
    expect(appSource).toContain(':can-merge="canMergeMultiAgentReview"')
  })

  it('confirms generated cases before review and advances completed review without reconfirming plans', () => {
    const primaryActionStart = appSource.indexOf('const handleCaseDesignPrimaryAction = async () => {')
    const primaryActionEnd = appSource.indexOf('const caseDesignStageKeys:', primaryActionStart)
    const primaryAction = appSource.slice(primaryActionStart, primaryActionEnd)
    const generationStart = primaryAction.indexOf("if (stage === 'case-generate')")
    const reviewStart = primaryAction.indexOf("if (stage === 'review')")
    const automationStart = primaryAction.indexOf("if (stage === 'automation-compile')")
    const generationAction = primaryAction.slice(generationStart, reviewStart)
    const reviewAction = primaryAction.slice(reviewStart, automationStart)

    expect(generationAction).toContain('await confirmAllWebCasePlanArtifacts(naturalPlanRunId.value)')
    expect(generationAction).toContain('invalidateCaseReview()')
    expect(generationAction).toContain("activeCaseDesignStage.value = 'review'")
    expect(generationAction).toContain('await startCaseAiReview()')
    expect(reviewAction).not.toContain('confirmAllWebCasePlanArtifacts')
    expect(reviewAction).toContain("activeCaseDesignStage.value = 'automation-compile'")
    expect(primaryAction).toContain('prepareCaseGenerationEntry()')
    expect(appSource).toContain('const prepareCaseGenerationEntry = () => {')
    expect(appSource).toContain('const isPlanFailure = currentRun?.mode === \'PLAN\'')
    expect(appSource).toContain('webCaseAgentRun.value?.projectId')
    expect(appSource).toContain('aiAnalysisRun.value?.projectId')
  })

  it('clears the previous agent run display when the compile flow (re)starts', () => {
    // 用户 2026-10-14：重试编译必须清掉上一次「素材取证 · 已阻塞」的运行展示。
    expect(appSource).toContain('重试编译时清理上一次「素材取证 · 已阻塞」的运行展示')
    const resetIndex = appSource.indexOf('webCaseAgentRun.value = undefined')
    expect(resetIndex).toBeGreaterThan(-1)
    const nearby = appSource.slice(Math.max(0, resetIndex - 500), resetIndex + 500)
    expect(nearby).toContain('webCaseAgentEvents.value = []')
    expect(nearby).toContain('正在创建 Agent 任务')
  })

  it('offers a blocklist-default network policy toggle for web environments', () => {
    expect(appSource).toContain('网络放行策略')
    expect(appSource).toContain('黑名单（默认：只拦截名单内的请求，其余放行）')
    expect(appSource).toContain('白名单（只放行名单内的请求，其余拦截）')
    expect(appSource).toContain("editingWebNetworkPolicyMode === 'blocklist' ? '拦截名单（每行一个）' : '放行名单（每行一个）'")
    expect(appSource).toContain('networkPolicyMode: webRuntime.networkPolicyMode')
  })

  it('persists the verified web login session and lets allowlist entries target exact request URLs', () => {
    // 用户实测两处缺口：验证会话不随环境持久化（刷新即丢）；白名单条目被折叠为 Origin，
    // 「带路径 URL = 精确放行（含 POST）」在界面文案里不可发现。
    expect(appSource).toContain('editingEnvironment.web.sessionId = webLoginSessionId.value')
    expect(appSource).toContain('webRuntime.sessionId || webLoginSessionId.value')
    expect(appSource).toContain("editingWebNetworkPolicyMode === 'blocklist' ? '拦截名单（每行一个）' : '放行名单（每行一个）'")
    expect(appSource).toContain('带路径的完整 URL 精确放行，含 POST')
    expect(appSource).toContain('clearWebLoginSession')
  })

  it('shows pending natural plans as reviewable cases and treats blocked PLAN runs as awaiting confirmation', () => {
    // 用户实测链路：生成完成后表格必须出现「待确认」用例，且 PLAN 的 blocked（等待人工确认）
    // 不能被当作失败——否则「确认用例」永不可达、页面永远为空。
    expect(appSource).toContain("artifact.artifactType === 'natural_web_case_plan'")
    expect(appSource).toContain("artifact.acceptanceState === 'PENDING_HUMAN_CONFIRMATION'")
    expect(appSource).toContain('testCase.metadata.artifactAcceptanceState = artifact.acceptanceState')
    expect(appSource).toContain("run.mode === 'PLAN' && run.status === 'blocked' && webCaseAgentNaturalPlanCount.value > 0")
    expect(appSource).toContain('条待确认用例，点击「确认用例」完成确认')
  })

  it('keeps automation compilation on the current stage and updates existing cases in place', () => {
    expect(appSource).toContain('const generateWebCasesWithAi = async (retryFailedAgentRun = false, preserveCurrentCases = false)')
    expect(appSource).toContain('if (!retryFailedAgentRun && !preserveCurrentCases)')
    expect(appSource).toContain("if (webCaseAgentRun.value?.mode !== 'COMPILE') invalidateCaseReview()")
    expect(appSource).toContain('await generateWebCasesWithAi(retryFailedAgentRun, true)')
    expect(appSource).toContain('await confirmAllWebCasePlanArtifacts(planRunId)')
    expect(appSource).toContain('缺少自然语言用例计划，请返回用例生成阶段重新确认用例')
  })

  it('adds an accepted review supplement to the left case list after AI merge', () => {
    expect(appSource).toContain('const mergeAcceptedReviewFinding = async')
    expect(appSource).toContain('buildNaturalLanguageCasesFromAgentArtifacts(')
    expect(appSource).toContain("if (decision === 'accepted') void mergeAcceptedReviewFinding")
  })

  it('removes the redundant current-case inspector block and binds review filters to option values', () => {
    expect(appSource).not.toContain('selected-case-title=')
    expect(appSource).toContain(':label="module.value" :value="module.value"')
    expect(appSource).toContain(':label="caseType.value" :value="caseType.value"')
  })

  it('keeps OpenAPI import out of the Web requirement-source workflow', () => {
    const canvasBlock = extractWebStudioCanvasBlock()

    expect(canvasBlock).toContain('<section v-else class="studio-case-design-canvas">')
    expect(canvasBlock).not.toContain('studio-interface-case-canvas')
    expect(canvasBlock).not.toContain('@pick-open-api')
    expect(canvasBlock).not.toContain('@pick-open-api="pickOpenApiFiles"')
    expect(appSource).not.toContain('const pickOpenApiRequirementFiles = async')
    expect(appSource).toContain('parseOpenApiDocument(JSON.parse(text), file.name)')
    expect(appSource).toContain("description: 'OpenAPI JSON'")
    expect(appSource).toContain("accept: { 'application/json': ['.json'] }")
    expect(appSource).toContain("? '.json,application/json'")
  })

  it('delegates Web automation to the recoverable workbench and removes legacy run drawers', () => {
    expect(appSource).toContain("import WebAutomationWorkspace from './components/automation/WebAutomationWorkspace.vue'")
    expect(appSource).toContain('<WebAutomationWorkspace')
    expect(appSource).toContain('<header v-if="automationKind === \'api\'" class="topbar automation-topbar">')
    expect(appSource).toContain("'is-web-automation': activeNav === 'automation' && automationKind === 'web'")
    expect(appSource).not.toContain('v-model="webRunDrawerVisible"')
    expect(appSource).not.toContain('class="automation-metric-grid"')
    expect(appSource).not.toContain('v-model="automationKind" size="small"')
    expect((appSource.match(/class="web-run-visual-drawer"/g) || [])).toHaveLength(0)
  })

  it('merges API and Web automation into the execution workbench shell', () => {
    expect(appSource).toContain("import ExecutionWorkbenchView from './components/views/ExecutionWorkbenchView.vue'")
    expect(appSource).toContain('<ExecutionWorkbenchView')
    expect(appSource).toContain('<template #api>')
    expect(appSource).toContain('<template #web>')
    expect(appSource).toContain(':automation-kind="automationKind"')
    // API 走标准顶栏网格契约,Web(自管全高布局)走 --full;kind 切换由父级 selectSidebarItem 统一处理
    expect(appSource).toContain("automationKind.value === 'api'")
    expect(studioCanvasStyles).toContain('.workspace.is-workbench:has(> .studio-sync-notice)')
    expect(studioCanvasStyles).toContain('.workbench__pane--full')
  })

  it('merges cases and the case-design canvas into the case workbench shell', () => {
    expect(appSource).toContain("import CaseWorkbenchView from './components/views/CaseWorkbenchView.vue'")
    expect(appSource).toContain('<CaseWorkbenchView')
    expect(appSource).toContain('<template #library>')
    expect(appSource).toContain('<template #canvas>')
    expect(appSource).toContain('<CaseWorkbenchView')
    // 壳判定:用例库(cases)+ 智能用例设计(api)同台;旧的 cases 独立分支已删
    expect(appSource).toContain("activeNav.value === 'cases' || activeNav.value === 'api'")
    expect(appSource).not.toContain(`<template v-else-if="activeNav === 'cases'">`)
    // api 不入 is-workbench:画布保留 .is-studio-* 修饰(不重排),壳在块级 workspace 内显式撑满
    expect(appSource).toContain("'is-studio-case-design': activeNav === 'api' && generationKind === 'web_automation'")
    expect(appSource).toContain("'is-studio-interface-case': activeNav === 'api' && generationKind === 'interface'")
    expect(studioCanvasStyles).toContain('.workspace.is-studio-case-design > .workbench {')
    // 内部分组导航已去掉:不再有 nav-group-title / nav-item--child 分组缩进样式
    expect(studioCanvasStyles).not.toContain('.workbench__nav-group-title')
    expect(studioCanvasStyles).not.toContain('.workbench__nav-item--child')
  })

  it('keeps Studio Canvas Web automation responsive, motion and contrast hooks in the shared stylesheet', () => {
    expect(studioCanvasStyles).toContain('@media (prefers-reduced-motion: reduce)')
    expect(studioCanvasStyles).toContain('@media (prefers-contrast: more)')
    expect(studioCanvasStyles).toContain('.web-automation-workspace')
    expect(studioCanvasStyles).toContain('.web-run-monitor-view')

    // 工作台固定四行:会话工具条不再吞掉 fr 行,主区(表格+侧栏)贴底撑满——修复中部大面积留白。
    const webWorkspaceFrameRule = extractCssBlock(studioCanvasStyles, '.web-automation-workspace {')
    expect(webWorkspaceFrameRule).toContain('grid-template-rows: auto auto auto minmax(0, 1fr)')
    // 实时监控模式单子项占满整格,与四行布局共存。
    expect(studioCanvasStyles).toContain('.web-automation-workspace > .web-run-monitor-view {')
    expect(studioCanvasStyles).toContain('grid-row: 1 / -1;')

    const webWorkspaceRule = extractCssBlock(studioCanvasStyles, '.workspace.is-web-automation')
    expect(webWorkspaceRule).toContain('grid-template-rows: minmax(0, 1fr)')
    expect(webWorkspaceRule).toContain('radial-gradient(720px 360px')
    expect(webWorkspaceRule).toContain('linear-gradient(180deg, #f7fbfa 0%, #eef7f4 100%)')
    expect(webAutomationScopeSource).toContain('grid-template-rows: auto auto auto')
    expect(webAutomationScopeSource).toContain('align-content: start')
    expect(webAutomationScopeSource).toContain('overflow-y: auto')
    expect(webAutomationScopeSource).toContain('border-radius: var(--radius-lg)')
    expect(webRunPreflightSource).toContain('align-content: start')
    expect(webRunPreflightSource).toContain('overflow-y: auto')
    expect(webRunPreflightSource).toContain('border-radius: var(--radius-lg)')
    expect(webRunSummarySource).toContain('align-content: start')
    expect(webRunSummarySource).toContain('overflow-y: auto')
    expect(webRunPreparationSource).toContain('web-run-preparation__field--environment')
    expect(webRunPreparationSource).toContain('web-run-preparation__field--browser')
    expect(webRunPreparationSource).toContain('web-run-preparation__readiness')

    const wideGridRule = extractCssBlock(studioCanvasStyles, '.web-automation-workspace__grid')
    expect(wideGridRule).toContain('grid-template-columns: minmax(0, 1fr) 292px')
    expect(wideGridRule).toContain('gap: 12px')
    expect(wideGridRule).toContain('background: transparent')
    expect(wideGridRule).toContain('border: 0')

    const panelSurfaceRule = extractCssBlock(studioCanvasStyles, '.web-automation-workspace .web-automation-scope-pane,')
    expect(panelSurfaceRule).toContain('linear-gradient(180deg')
    expect(panelSurfaceRule).toContain('var(--surface-raised)')
    expect(panelSurfaceRule).toContain('border-radius: 18px')
    expect(panelSurfaceRule).not.toContain('.web-run-preparation')
    expect(panelSurfaceRule).not.toContain('.web-run-monitor-view__header')
    expect(panelSurfaceRule).not.toContain('.web-run-monitor-view__live-area')
    expect(panelSurfaceRule).not.toContain('.web-run-monitor-view__timeline')
    const preparationRule = extractCssBlock(studioCanvasStyles, '.web-automation-workspace .web-run-preparation')
    expect(preparationRule).toContain('linear-gradient(180deg')
    expect(preparationRule).toContain('var(--surface-raised)')
    expect(preparationRule).toContain('border-radius: 18px')

    expect(studioCanvasStyles).toContain('.web-automation-workspace .web-automation-case-table')
    expect(studioCanvasStyles).not.toMatch(/border-right:\s*1px solid var\(--border-strong\)/)

    const webAutomationCss = studioCanvasStyles.slice(studioCanvasStyles.indexOf('/* Web automation Studio Canvas workbench. */'))
    expect(webAutomationCss).not.toContain('var(--accent-')
    for (const token of [
      '--color-success',
      '--color-success-soft',
      '--color-success-border',
      '--color-warning',
      '--color-warning-soft',
      '--color-warning-border',
      '--color-danger',
      '--color-danger-soft',
      '--color-danger-border'
    ]) {
      expect(webAutomationCss).toContain(`var(${token})`)
      expect(studioTokens).toContain(`${token}:`)
    }
  })

  it('wires source-local recovery without resetting unaffected workflow results', () => {
    const canvasBlock = extractWebStudioCanvasBlock()

    expect(canvasBlock).toContain('<section v-else class="studio-case-design-canvas">')
    expect(canvasBlock).not.toContain('<InterfaceCaseTable')
    expect(canvasBlock).toContain('@retry-source="retryRequirementSource"')
    expect(canvasBlock).toContain('@replace-source="replaceRequirementSource"')
    expect(canvasBlock).toContain('@remove-source="removeRequirementSource"')
    expect(appSource).toContain("await importWebCaseFiles([originalFile], { replaceName: name, kind, localRecovery: true })")
    expect(appSource).toContain('if (preserveWorkflow) return')
    expect(appSource).toContain('其他分析结果保持不变')
    expect(appSource).toContain(':multiple="!pendingRequirementSourcePick?.replaceName"')
    expect(appSource).toContain('const files = pickOptions?.replaceName ? selectedFiles.slice(0, 1) : selectedFiles')
    expect(appSource).toContain('pendingRequirementSourcePick.value = options\n  await nextTick()\n  webCaseFileInput.value?.click()')
  })

  it('clears stale generation scope state when the Web workflow resets', () => {
    expect(appSource).toContain('const resetCaseGenerationSelectionState = () => {')
    for (const reset of [
      'generationScopePointIds.value = []',
      'generationRetryPointIds.value = []',
      'selectedGeneratedWebCaseIds.value = []'
    ]) expect(appSource).toContain(reset)

    expect(appSource).toContain("mode: 'PLAN'")
    expect(appSource).toContain("mode: 'COMPILE'")
    expect(appSource).toContain('const clearGeneratedCaseWorkspace = () => {')
  })

  it('renders Web-specific runtime configuration instead of API auth fields in the environment drawer', () => {
    const drawerStart = appSource.indexOf('v-model="environmentVisible"')
    const drawerEnd = appSource.indexOf('v-model="automationLogVisible"', drawerStart)
    const drawerBlock = appSource.slice(drawerStart, drawerEnd)

    for (const label of ['Web 访问入口', '浏览器', '视口', 'Headless', '默认等待超时', '页面加载超时', '等待页面状态', '失败重试次数']) {
      expect(drawerBlock).toContain(label)
    }
    for (const label of ['认证方式', 'Bearer Token', '认证 Header', '自动登录', '新增 Header', '新增 Cookie']) {
      expect(drawerBlock).toContain(label)
    }
    expect(drawerBlock).not.toContain('<strong>高级登录态</strong>')
    expect(drawerBlock).toContain("environmentEditorKind === 'web'")
    expect(drawerBlock).toContain('editingEnvironment.web')
    expect(drawerBlock).toContain('Web 运行环境配置')
    expect(drawerBlock).toContain('class="web-automation-drawer"')
    expect(drawerBlock).toContain('environment-section--runtime')
    expect(drawerBlock).toContain('运行参数')
    expect(drawerBlock).toContain('浏览器、视口与执行等待策略')
    expect(drawerBlock).toContain('environment-section--lifecycle')
    expect(drawerBlock).toContain('environment-drawer-actions')
    expect(drawerBlock).toContain('@click="addExecutionEnvironment()"')
    expect(drawerBlock).toContain('@click="saveEditingEnvironment()"')

    const logDrawerStart = appSource.indexOf('v-model="automationLogVisible"')
    const logDrawerEnd = appSource.indexOf('v-model="jiraTemplateVisible"', logDrawerStart)
    expect(appSource.slice(logDrawerStart, logDrawerEnd)).toContain('web-automation-log-drawer')
    expect(appSource).toContain('class="web-automation-dialog"')
  })

  it('allows editing Web automation cases with runtime environment and browser settings', () => {
    const editStart = appSource.indexOf('v-model="editVisible"')
    const editEnd = appSource.indexOf('v-model="reportScreenshotPreviewVisible"', editStart)
    const editBlock = appSource.slice(editStart, editEnd)

    for (const label of ['运行环境', '应用访问地址', '浏览器', '视口', '等待策略', '截图策略', '失败重试次数']) {
      expect(editBlock).toContain(label)
    }
    expect(editBlock).toContain('applySelectedWebEnvironmentToEditRow')
    expect(editBlock).toContain('webExecutionEnvironmentLibrary.environments')
  })
})

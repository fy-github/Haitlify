import { describe, expect, it } from 'vitest'
import appSource from './App.vue?raw'

describe('Automation execution layout', () => {
  it('keeps the API topbar focused on the automation task without duplicate API labels', () => {
    const topbarStart = appSource.indexOf('class="topbar automation-topbar"')
    const topbarEnd = appSource.indexOf('</header>', topbarStart)
    const topbar = appSource.slice(topbarStart, topbarEnd)
    expect(topbar).not.toContain('API Automation')
    expect(topbar).not.toContain('<el-tag type="info" effect="plain">API</el-tag>')
    // 定稿：内联运行参数 chip 已移除，参数只留在运行配置抽屉；页级保留 环境配置 / 运行配置
    expect(topbar).not.toContain('automation-topbar__runtime')
    expect(topbar).toContain('\n              环境配置')
    expect(topbar).toContain('\n              运行配置')
    expect(topbar).not.toContain('从用例库生成')
    expect(topbar).toContain('{{ apiExecutionScopeLabel }}')
  })

  it('uses the shared automation config drawer structure', () => {
    const hasExecutionLayout =
      appSource.includes('class="automation-config-drawer__content"') &&
      appSource.includes('class="automation-config-drawer__grid"') &&
      appSource.includes('class="execution-control-group execution-control-group-env"') &&
      appSource.includes('class="automation-run-summary execution-summary-block"') &&
      appSource.includes('description="配置修改后立即生效，关闭抽屉即可返回列表。"')

    expect(hasExecutionLayout).toBe(true)
  })

  it('renders threshold summary as a stacked label and slider block', () => {
    const hasStackedThreshold =
      appSource.includes('class="execution-summary-threshold"') &&
      appSource.includes('class="execution-summary-threshold-head"') &&
      appSource.includes('class="execution-summary-threshold-body"') &&
      appSource.includes('class="threshold-value-tag"')

    expect(hasStackedThreshold).toBe(true)
  })

  it('does not render design-origin automation metric in the automation summary cards', () => {
    expect(appSource).not.toContain('<span>设计稿自动化</span>')
  })

  it('uses the shared execution-mode control only for api automation', () => {
    expect(appSource).not.toContain('v-model="webAutomationRunMode"')
    expect(appSource).toContain('class="execution-control-group execution-control-group-mode"')
    expect(appSource).toContain('class="api-run-count-stepper"')
    expect(appSource).toContain('@click="stepApiRunCount(-1)"')
    expect(appSource).toContain('@click="stepApiRunCount(1)"')
  })

  it('binds API and Web environment selection to independent libraries', () => {
    expect(appSource).toContain(':model-value="executionEnvironmentLibrary.activeEnvironmentId"')
    expect(appSource).toContain('v-for="environment in executionEnvironmentLibrary.environments"')
    expect(appSource).toContain('environmentId: webExecutionEnvironmentLibrary.value.activeEnvironmentId')
    expect(appSource).toContain('environments: webExecutionEnvironmentLibrary.value.environments.map')
    expect(appSource).toContain('@update-environment-id="updateWebAutomationEnvironment"')
    expect(appSource).not.toContain(':model-value="currentEnvironmentLibrary.activeEnvironmentId"')
  })

  it('keeps API progress on the page and Web progress in the workspace monitor', () => {
    expect(appSource).toContain('apiAutomationProgress')
    expect(appSource).toContain('<WebAutomationWorkspace')
    expect(appSource).toContain('activeWebAutomationWorkspaceRun')
    expect(appSource).toContain(':run="activeWebAutomationWorkspaceRun"')
    expect(appSource).not.toContain('const automationProgress = reactive')
  })

  it('keeps API and Web loop counts independent', () => {
    expect(appSource).toContain(':value="apiRunCountInput"')
    expect(appSource).toContain('@input="onApiRunCountInput"')
    expect(appSource).toContain('automationRunMode.value === \'concurrent\' ? automationConcurrencyLimit.value : apiAutomationLoopCount.value')
    expect(appSource).toContain('loopCount: webAutomationLoopCount.value')
    expect(appSource).toContain('@update-loop-count="updateWebAutomationLoopCount"')
  })

  it('keeps the API table wired in App via the extracted component while the Web branch delegates filtering to the workspace', () => {
    expect(appSource).toContain('<ApiAutomationCaseTable')
    expect(appSource).toContain('v-if="automationKind === \'api\'"')
    expect(appSource).toContain(':cases="filteredAutomationCases"')
    expect(appSource).toContain('@update:selection="automationSelection = $event"')
    expect(appSource).toContain(':table="webAutomationWorkspaceTable"')
    expect(appSource).not.toContain(':data="filteredAutomationCases"')
    expect(appSource).not.toContain('@selection-change="(rows: SavedAutomationCase[])')
    expect(appSource).not.toContain('class="case-filters compact-toolbar web-automation-filter-row"')
    expect(appSource).not.toContain('class="web-automation-inline-actions"')
  })
})

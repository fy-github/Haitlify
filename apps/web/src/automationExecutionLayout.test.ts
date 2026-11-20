import { describe, expect, it } from 'vitest'
import appSource from './App.vue?raw'
import apiControllerSource from './ui/useApiExecutionController.ts?raw'
import apiPageSource from './components/views/ApiExecutionPage.vue?raw'
import webPageSource from './components/views/WebExecutionPage.vue?raw'
import webModelSource from './ui/useWebWorkspaceModel.ts?raw'
import webPreparationSource from './ui/useWebPreparation.ts?raw'

describe('Automation execution layout', () => {
  it('keeps the API topbar focused on the automation task without duplicate API labels', () => {
    const topbarStart = apiPageSource.indexOf('class="topbar automation-topbar"')
    const topbarEnd = apiPageSource.indexOf('</header>', topbarStart)
    const topbar = apiPageSource.slice(topbarStart, topbarEnd)
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
      apiPageSource.includes('class="automation-config-drawer__content"') &&
      apiPageSource.includes('class="automation-config-drawer__grid"') &&
      apiPageSource.includes('class="execution-control-group execution-control-group-env"') &&
      apiPageSource.includes('class="automation-run-summary execution-summary-block"') &&
      apiPageSource.includes('description="配置修改后立即生效，关闭抽屉即可返回列表。"')

    expect(hasExecutionLayout).toBe(true)
  })

  it('renders threshold summary as a stacked label and slider block', () => {
    const hasStackedThreshold =
      apiPageSource.includes('class="execution-summary-threshold"') &&
      apiPageSource.includes('class="execution-summary-threshold-head"') &&
      apiPageSource.includes('class="execution-summary-threshold-body"') &&
      apiPageSource.includes('class="threshold-value-tag"')

    expect(hasStackedThreshold).toBe(true)
  })

  it('does not render design-origin automation metric in the automation summary cards', () => {
    expect(apiPageSource).not.toContain('<span>设计稿自动化</span>')
  })

  it('uses the shared execution-mode control only for api automation', () => {
    expect(apiPageSource).not.toContain('v-model="webAutomationRunMode"')
    expect(apiPageSource).toContain('class="execution-control-group execution-control-group-mode"')
    expect(apiPageSource).toContain('class="api-run-count-stepper"')
    expect(apiPageSource).toContain('@click="stepApiRunCount(-1)"')
    expect(apiPageSource).toContain('@click="stepApiRunCount(1)"')
  })

  it('binds API and Web environment selection to independent libraries', () => {
    expect(apiPageSource).toContain(':model-value="executionEnvironmentLibrary.activeEnvironmentId"')
    expect(apiPageSource).toContain('v-for="environment in executionEnvironmentLibrary.environments"')
    expect(webModelSource).toContain('environmentId: deps.webExecutionEnvironmentLibrary.value.activeEnvironmentId')
    expect(webModelSource).toContain('environments: deps.webExecutionEnvironmentLibrary.value.environments.map')
    expect(webPageSource).toContain('@update-environment-id="updateWebAutomationEnvironment"')
    expect(apiPageSource).not.toContain(':model-value="currentEnvironmentLibrary.activeEnvironmentId"')
  })

  it('keeps API progress on the page and Web progress in the workspace monitor', () => {
    expect(apiControllerSource).toContain('apiAutomationProgress')
    expect(webPageSource).toContain('<WebAutomationWorkspace')
    expect(webPageSource).toContain('activeWebAutomationWorkspaceRun')
    expect(webPageSource).toContain(':run="activeWebAutomationWorkspaceRun"')
    expect(apiControllerSource).not.toContain('const automationProgress = reactive')
  })

  it('keeps API and Web loop counts independent', () => {
    expect(apiPageSource).toContain(':value="apiRunCountInput"')
    expect(apiPageSource).toContain('@input="onApiRunCountInput"')
    expect(apiControllerSource).toContain('automationRunMode.value === \'concurrent\' ? automationConcurrencyLimit.value : apiAutomationLoopCount.value')
    expect(webPreparationSource).toContain('loopCount: deps.webAutomationLoopCount.value')
    expect(webPageSource).toContain('@update-loop-count="updateWebAutomationLoopCount"')
  })

  it('keeps the API table wired in App via the extracted component while the Web branch delegates filtering to the workspace', () => {
    expect(apiPageSource).toContain('<ApiAutomationCaseTable')
    expect(apiPageSource).toContain('v-if="automationKind === \'api\'"')
    expect(apiPageSource).toContain(':cases="filteredAutomationCases"')
    expect(apiPageSource).toContain('@update:selection="automationSelection = $event"')
    expect(webPageSource).toContain(':table="webAutomationWorkspaceTable"')
    expect(apiPageSource).not.toContain(':data="filteredAutomationCases"')
    expect(apiPageSource).not.toContain('@selection-change="(rows: SavedAutomationCase[])')
    expect(webPageSource).not.toContain('class="case-filters compact-toolbar web-automation-filter-row"')
    expect(webPageSource).not.toContain('class="web-automation-inline-actions"')
  })
})

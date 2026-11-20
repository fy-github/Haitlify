import { describe, expect, it } from 'vitest'
import casesViewSource from './components/views/CasesView.vue?raw'
import apiPageSource from './components/views/ApiExecutionPage.vue?raw'
import webPageSource from './components/views/WebExecutionPage.vue?raw'

describe('Case and automation action bar layout', () => {
  it('separates case-library filters from the tools row that carries batch actions', () => {
    // 已确认设计:筛选独占一条(cv-filter-strip);勾选后的批量操作并入 cv-list-tools,
    // 紧贴「显示列」左侧,不再单独起一条通栏
    expect(casesViewSource).toContain('class="cv-filter-strip"')
    expect(casesViewSource).toContain('class="cv-list-tools"')
    expect(casesViewSource).not.toContain('class="cv-selection-strip"')

    const toolsIndex = casesViewSource.indexOf('class="cv-list-tools"')
    const batchIndex = casesViewSource.indexOf('class="cv-batch-actions"')
    const columnsIndex = casesViewSource.indexOf('cv-columns-btn')
    expect(batchIndex).toBeGreaterThan(toolsIndex)
    expect(batchIndex).toBeLessThan(columnsIndex)
    const batchClose = casesViewSource.indexOf('</div>', batchIndex)
    const beforeColumns = casesViewSource.slice(batchClose + '</div>'.length, columnsIndex)
    expect(beforeColumns.trim()).toBe('<button type="button" class="cv-btn')
  })

  it('keeps API grouped actions in App and delegates Web automation to the workspace', () => {
    expect(apiPageSource).toContain('class="case-filters compact-toolbar automation-filter-row"')
    expect(apiPageSource).toContain('class="automation-control-row"')
    expect(apiPageSource).toContain('class="panel-action-bar automation-panel-actions"')
    expect(apiPageSource).toContain('class="panel-action-main"')
    expect(webPageSource).toContain('<WebAutomationWorkspace')
    expect(webPageSource).toContain(':table="webAutomationWorkspaceTable"')
    expect(webPageSource).not.toContain('web-automation-filter-row')
    expect(webPageSource).not.toContain('web-automation-inline-actions')
  })
})

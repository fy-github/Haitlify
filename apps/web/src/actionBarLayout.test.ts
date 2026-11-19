import { describe, expect, it } from 'vitest'
import appSource from './App.vue?raw'
import casesViewSource from './components/views/CasesView.vue?raw'

const extractNavBlocks = (navKey: string) => {
  const marker = `<template v-else-if="activeNav === '${navKey}'">`
  const blocks: string[] = []
  let start = appSource.indexOf(marker)

  if (start === -1) {
    throw new Error(`Cannot find template block for ${navKey}`)
  }

  while (start !== -1) {
    const nextIndex = appSource.indexOf(`<template v-else-if="activeNav === '`, start + marker.length)
    blocks.push(nextIndex === -1 ? appSource.slice(start) : appSource.slice(start, nextIndex))
    start = appSource.indexOf(marker, start + marker.length)
  }

  return blocks
}

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
    const automationBlocks = extractNavBlocks('automation')
    const hasGroupedActionRow = automationBlocks.some(
      (block) =>
        block.includes('class="case-filters compact-toolbar automation-filter-row"') &&
        block.includes('class="automation-control-row"') &&
        block.includes('class="panel-action-bar automation-panel-actions"') &&
        block.includes('class="panel-action-main"') &&
        block.includes('<WebAutomationWorkspace') &&
        block.includes(':table="webAutomationWorkspaceTable"')
    )

    expect(hasGroupedActionRow).toBe(true)
    expect(appSource).not.toContain('web-automation-filter-row')
    expect(appSource).not.toContain('web-automation-inline-actions')
  })
})

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
  it('separates case-library filters from grouped action rows', () => {
    expect(casesViewSource).toContain('class="case-filters compact-toolbar"')
    expect(casesViewSource).toContain('class="panel-action-bar"')
    expect(casesViewSource).toContain('class="panel-action-main"')
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

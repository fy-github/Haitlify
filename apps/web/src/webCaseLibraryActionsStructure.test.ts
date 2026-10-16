import { describe, expect, it } from 'vitest'
import appSource from './App.vue?raw'
import casesViewSource from './components/views/CasesView.vue?raw'

describe('Web case library actions', () => {
  it('enables edit and row-level automation conversion with a wide action column', () => {
    expect(casesViewSource).toContain('<el-table-column label="操作" width="280" fixed="right">')
    expect(casesViewSource).toContain('@click.stop="openWebEdit(row)"')
    expect(casesViewSource).toContain('@click.stop="convertWebLibraryCaseToAutomation(row)"')
    expect(casesViewSource).not.toContain('<el-button link disabled>编辑</el-button>')
  })

  it('uses active-kind conversion scope in the dialog', () => {
    expect(appSource).toContain('automationGenerationScope.projects')
    expect(appSource).toContain('automationGenerationScope.modules')
    expect(appSource).toContain('automationGenerationScope.cases.length')
  })
})

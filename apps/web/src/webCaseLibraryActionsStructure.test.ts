import { describe, expect, it } from 'vitest'
import appSource from './components/automation/AutomationConversionDialog.vue?raw'
import casesViewSource from './components/views/CasesView.vue?raw'

describe('Web case library actions', () => {
  it('enables edit and row-level automation conversion with a wide action column', () => {
    // 设计稿改为原生表格 + sticky 操作列,宽度由 .cv-actions-col 承载(210px)
    expect(casesViewSource).toContain('class="cv-actions-col" style="width: 210px"')
    expect(casesViewSource).toContain('@click="openWebEdit(row)"')
    expect(casesViewSource).toContain('@click="convertWebLibraryCaseToAutomation(row)"')
    expect(casesViewSource).not.toContain('<el-button link disabled>编辑</el-button>')
  })

  it('uses active-kind conversion scope in the dialog', () => {
    expect(appSource).toContain('automationGenerationScope.projects')
    expect(appSource).toContain('automationGenerationScope.modules')
    expect(appSource).toContain('automationGenerationScope.cases.length')
  })
})

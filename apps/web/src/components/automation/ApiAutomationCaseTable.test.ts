// 源结构测试:el-table 在 jsdom 不渲染表头/行(见 memory view-integration-tests),
// 故本组件用 ?raw 源文本断言列结构、emits 路由与单一来源 helper 引用。
import { describe, expect, it } from 'vitest'
import componentSource from './ApiAutomationCaseTable.vue?raw'

describe('ApiAutomationCaseTable', () => {
  it('renders the full API automation column set', () => {
    for (const column of [
      'type="selection"',
      'prop="id" label="自动化ID"',
      'prop="project" label="项目"',
      'prop="module" label="模块"',
      'prop="name" label="用例名称"',
      'prop="method" label="方法"',
      'prop="path" label="路径"',
      'prop="expectedStatus" label="期望状态"',
      'label="执行状态"',
      'label="实际状态"',
      'label="返回体一致度"',
      'label="耗时"',
      'prop="sourceCaseId" label="来源用例"',
      'label="操作"'
    ]) {
      expect(componentSource).toContain(column)
    }
  })

  it('keeps the shared case-table style hook so old-shell table tokens still apply', () => {
    expect(componentSource).toMatch(/class="case-table"/)
  })

  it('routes selection and row actions through emits', () => {
    expect(componentSource).toMatch(/@selection-change="emit\('update:selection', \$event\)"/)
    expect(componentSource).toMatch(/@click\.stop="emit\('openCurl', row\)"/)
    expect(componentSource).toMatch(/@click\.stop="emit\('openLog', row\)"/)
    expect(componentSource).toMatch(/emit\('transferDefect', row\)/)
    expect(componentSource).toMatch(/emit\('deleteCase', row\)/)
  })

  it('imports shared status label/tag helpers instead of re-deriving text', () => {
    expect(componentSource).toMatch(/import \{ automationStatusLabel, automationStatusTagType \} from '\.\.\/\.\.\/ui\/automationFormat'/)
    expect(componentSource).not.toMatch(/通过/)
  })

  it('scopes the transfer-defect action to failed or error rows via the shared result status', () => {
    expect(componentSource).toMatch(/resultStatus\(row\) === 'failed' \|\| resultStatus\(row\) === 'error'/)
    expect(componentSource).toMatch(/props\.resultMap\.get\(testCase\.id\)\?\.status \|\| 'pending'/)
  })
})

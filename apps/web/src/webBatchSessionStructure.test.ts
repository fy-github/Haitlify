import { describe, expect, it } from 'vitest'
import appSource from './components/automation/EnvironmentEditorPanel.vue?raw'
import monitorSource from './components/automation/WebRunMonitorView.vue?raw'
import webPageSource from './components/views/WebExecutionPage.vue?raw'

describe('Web batch session lifecycle UI', () => {
  it('configures ordered logout teardown steps in the Web environment dialog', () => {
    expect(appSource).toContain('退出登录后置条件')
    expect(appSource).toContain('editingEnvironment.web.logout.enabled')
    expect(appSource).toContain('editingEnvironment.web.logout.steps')
    expect(appSource).toContain('addWebLogoutStep')
    expect(appSource).toContain('moveWebLogoutStep')
    expect(appSource).toContain('removeWebLogoutStep')
  })

  it('routes setup, test, and teardown phases through the workspace monitor timeline', () => {
    expect(webPageSource).toContain('<WebAutomationWorkspace')
    expect(monitorSource).toContain("phase: 'setup', label: '环境准备'")
    expect(monitorSource).toContain("phase: 'test', label: '用例执行'")
    expect(monitorSource).toContain("phase: 'teardown', label: '环境清理'")
  })
})

import { describe, expect, it } from 'vitest'
import {
  WORKFLOW_STAGE_MARKS,
  WORKFLOW_STAGES,
  navKeyForStage,
  resolveNavDisplay,
  resolveSidebarActiveItemId,
  resolveSidebarItemById,
  sidebarGroupsForRole,
  stageForNavKey,
  studioSidebarGroups
} from './appSidebar'

describe('Studio Canvas sidebar model', () => {
  it('defines the Studio Canvas navigation groups as home + four workbenches + overview', () => {
    expect(studioSidebarGroups.map((group) => group.label)).toEqual([
      '首页',
      '用例工作台',
      '执行工作台',
      '质量工作台',
      '团队概览',
      '设置中心'
    ])

    expect(studioSidebarGroups.flatMap((group) => group.items.map((item) => item.label))).toEqual([
      '运行进度',
      '待我处理',
      '最近工作',
      'AI 助手',
      '接口用例',
      'Web 用例',
      'API 自动化',
      'Web 自动化',
      '环境配置',
      '执行报告',
      '缺陷管理',
      '项目概览',
      '模板字段',
      '用例类型',
      'Jira 配置',
      '用户管理'
    ])

    // 智能用例设计仍然下线：工作台只列出两个已保存用例库页面。
    expect(studioSidebarGroups[1].items.map(({ label, navKey }) => [label, navKey])).toEqual([
      ['接口用例', 'cases'], ['Web 用例', 'webCases']
    ])
  })

  it('maps flow navigation to its stage and back for the global flow strip', () => {
    // 用例库只是保存用例的地方,不是流程阶段
    expect(stageForNavKey('cases')).toBeUndefined()
    expect(stageForNavKey('webCases')).toBeUndefined()
    expect(stageForNavKey('api')).toBe('prepare')
    expect(stageForNavKey('automation')).toBe('execute')
    expect(stageForNavKey('reports')).toBe('verify')
    expect(stageForNavKey('defects')).toBe('deliver')
    expect(stageForNavKey('home')).toBeUndefined()
    expect(stageForNavKey('settings')).toBeUndefined()
    expect(stageForNavKey('assistant')).toBeUndefined()

    expect(navKeyForStage('prepare')).toBe('api')
    expect(navKeyForStage('execute')).toBe('automation')
    expect(navKeyForStage('verify')).toBe('reports')
    expect(navKeyForStage('deliver')).toBe('defects')
  })

  it('defines the four-stage global workflow in order', () => {
    expect(WORKFLOW_STAGES.map((stage) => stage.label)).toEqual(['准备', '执行', '验证', '交付'])
    expect(WORKFLOW_STAGES.map((stage) => stage.index)).toEqual([1, 2, 3, 4])
    expect(WORKFLOW_STAGE_MARKS).toEqual({ prepare: '①', execute: '②', verify: '③', deliver: '④' })
  })

  it('resolves sidebar activity for the current navigation context', () => {
    expect(resolveSidebarActiveItemId('home', 'api')).toBe('home')
    expect(resolveSidebarActiveItemId('cases', 'api')).toBe('case-library')
    expect(resolveSidebarActiveItemId('webCases', 'api')).toBe('web-case-library')
    expect(resolveSidebarActiveItemId('api', 'api', 'web_automation')).toBe('case-library')
    expect(resolveSidebarActiveItemId('api', 'api', 'interface')).toBe('case-library')
    expect(resolveSidebarActiveItemId('automation', 'api')).toBe('api-automation')
    expect(resolveSidebarActiveItemId('automation', 'web')).toBe('web-automation')
    expect(resolveSidebarActiveItemId('defects', 'api')).toBe('defects')
  })

  it('resolves the navigation display (label + icon) from the sidebar as single source', () => {
    expect(resolveNavDisplay('home', 'api').label).toBe('我的工作台')
    expect(resolveNavDisplay('automation', 'web').label).toBe('Web 自动化')
    expect(resolveNavDisplay('automation', 'api').label).toBe('API 自动化')
    expect(resolveNavDisplay('cases', 'api').label).toBe('接口用例')
    expect(resolveNavDisplay('webCases', 'api').label).toBe('Web 用例')
    expect(resolveNavDisplay('defects', 'api').label).toBe('缺陷管理')
    expect(resolveNavDisplay('reports', 'api').label).toBe('执行报告')
    expect(resolveNavDisplay('settings', 'api').label).toBe('模板字段')
    expect(resolveNavDisplay('assistant', 'api').label).toBe('AI 助手')
    expect(resolveNavDisplay('jiraConfig', 'api').label).toBe('Jira 配置')
  })

  it('resolves sidebar items by id including the jira-config entry', () => {
    expect(resolveSidebarItemById('jira-config')?.navKey).toBe('jiraConfig')
    expect(resolveSidebarItemById('templates')?.navKey).toBe('settings')
    expect(resolveSidebarItemById(undefined)).toBeUndefined()
  })

  it('carries explicit icon accent metadata instead of positional nth-child coloring', () => {
    // 首页 + 4 工作台各带一个 accent(执行工作台 cyan),用例工作台内子项接口用例 teal
    expect(studioSidebarGroups.map((group) => group.accent)).toEqual(['teal', 'teal', 'cyan', 'amber', 'info', 'info'])

    expect(studioSidebarGroups[1].items.every((item) => !item.children)).toBe(true)
  })

  it('keeps user-management admin-only when filtering by role', () => {
    expect(studioSidebarGroups.flat().length).toBeGreaterThan(0)
    const userManagement = studioSidebarGroups
      .flatMap((group) => group.items)
      .find((item) => item.id === 'user-management')
    expect(userManagement?.adminOnly).toBe(true)

    const adminGroups = sidebarGroupsForRole('ADMIN')
    expect(adminGroups.flatMap((group) => group.items.map((item) => item.label))).toContain('用户管理')

    const editorGroups = sidebarGroupsForRole('EDITOR')
    expect(editorGroups.flatMap((group) => group.items.map((item) => item.label))).not.toContain('用户管理')
    // 普通用户仍保留首页 + 全部业务导航(4 工作台)
    expect(editorGroups.map((group) => group.label)).toEqual(['首页', '用例工作台', '执行工作台', '质量工作台', '团队概览', '设置中心'])
    expect(editorGroups.find((group) => group.id === 'case-workbench')?.items.map((item) => item.label)).toEqual(['接口用例', 'Web 用例'])

    const viewerGroups = sidebarGroupsForRole('VIEWER')
    expect(viewerGroups.flatMap((group) => group.items.map((item) => item.label))).not.toContain('用户管理')
    expect(viewerGroups.find((group) => group.id === 'case-workbench')?.items.map((item) => item.label)).toEqual(['接口用例', 'Web 用例'])

    // 未登录(角色缺失)与 VIEWER 同等对待
    expect(sidebarGroupsForRole(undefined).flatMap((group) => group.items.map((item) => item.label))).not.toContain('用户管理')
  })
})

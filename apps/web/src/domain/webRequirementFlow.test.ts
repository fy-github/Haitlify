import { describe, expect, it } from 'vitest'
import {
  buildGeneratedCasesFromAiGeneratedCases,
  buildGeneratedCasesFromRequirementPoints,
  extractRequirementPointsFromDocuments
} from './webCaseImport'

describe('webRequirementFlow', () => {
  it('extracts requirement points from uploaded files before generation', () => {
    const result = extractRequirementPointsFromDocuments([
      {
        name: 'requirements.md',
        text: `
# Haitlify
## 模块: 登录
### 用例: 用户名密码登录成功
- 页面: 登录页
- 地址: /login
1. 打开 /login
2. 输入 #username = admin
`
      },
      {
        name: 'requirements.txt',
        text: `
项目: Haitlify
模块: 组织
页面: 组织页
测试点: 新建组织成功后出现在列表
`
      }
    ])

    expect(result.projectName).toBe('Haitlify')
    expect(result.points.map((point) => ({
      module: point.module,
      page: point.pageName,
      title: point.title,
      sourceFile: point.sourceFile
    }))).toEqual([
      {
        module: '登录',
        page: '登录页',
        title: '用户名密码登录成功',
        sourceFile: 'requirements.md'
      },
      {
        module: '组织',
        page: '组织页',
        title: '新建组织成功后出现在列表',
        sourceFile: 'requirements.txt'
      }
    ])
  })

  it('generates web cases from extracted requirement points only after analysis', () => {
    const generated = buildGeneratedCasesFromRequirementPoints([
      {
        project: 'Haitlify',
        module: '登录',
        pageName: '登录页',
        title: '用户名密码登录成功',
        sourceFile: 'requirements.md',
        sourceDocumentType: 'markdown'
      },
      {
        project: 'Haitlify',
        module: '组织',
        pageName: '组织页',
        title: '新建组织成功后出现在列表',
        sourceFile: 'requirements.txt',
        sourceDocumentType: 'text'
      }
    ])

    expect(generated.projectName).toBe('Haitlify')
    expect(generated.cases).toHaveLength(2)
    expect(generated.cases[0].row.title).toBe('用户名密码登录成功')
    expect(generated.cases[0].metadata.sourceFile).toBe('requirements.md')
    expect(generated.cases[1].row.title).toBe('新建组织成功后出现在列表')
    expect(generated.cases[1].metadata.sourceDocumentType).toBe('text')
  })

  it('generates template-aware web cases from AI generated case payloads', () => {
    const generated = buildGeneratedCasesFromAiGeneratedCases(
      [{
        sourcePointId: 'point-1',
        title: '登录成功进入工作台',
        module: '登录',
        caseKind: 'web_automation',
        pageName: '登录页',
        startUrl: '/login',
        browser: 'chrome',
        viewport: '1440x900',
        precondition: '环境登录流程已配置',
        objective: '验证正确账号密码可以登录',
        steps: 'goto /login\ninput #username = admin\nclick #submit\nassertVisible .dashboard',
        assertions: 'assertVisible .dashboard',
        expectedResult: '工作台可见',
        naturalSteps: '进入登录页\n输入正确账号密码\n点击登录',
        naturalExpectedResult: '进入工作台并展示导航',
        sourceFile: 'requirements.md',
        sourceText: '用户输入正确账号密码后进入工作台'
      }],
      [{
        pointId: 'point-1',
        runId: 'run-1',
        projectId: 1,
        sourceDocumentId: 'doc-1',
        sourceChunkId: 'chunk-1',
        title: '登录成功进入工作台',
        module: '登录',
        pageOrFeature: '登录页',
        caseKind: 'web_automation',
        testType: '功能',
        priority: 'P1',
        objective: '验证正确账号密码可以登录',
        steps: 'goto /login\ninput #username = admin\nclick #submit',
        expectedResult: 'assertVisible .dashboard',
        sourceFile: 'requirements.md',
        sourceText: '用户输入正确账号密码后进入工作台',
        confidenceScore: 0.9,
        inferred: false,
        reviewStatus: 'confirmed',
        metadata: {},
        createdAt: '2026-07-07T00:00:00Z',
        updatedAt: '2026-07-07T00:00:00Z'
      }],
      'Haitlify'
    )

    expect(generated.projectName).toBe('Haitlify')
    expect(generated.cases).toHaveLength(1)
    expect(generated.cases[0].row.precondition).toBe('环境登录流程已配置')
    expect(generated.cases[0].row.browser).toBe('chrome')
    expect(generated.cases[0].row.steps).toContain('goto /login')
    expect(generated.cases[0].row.assertions).toContain('assertVisible .dashboard')
    expect(generated.cases[0].row.naturalSteps).toContain('输入正确账号密码')
    expect(generated.cases[0].row.naturalExpectedResult).toBe('1. 进入工作台并展示导航')
    expect(generated.cases[0].metadata.steps.map((step) => step.action)).toEqual(['goto', 'input', 'click', 'assertVisible'])
  })

  it('uses a caller supplied offset when appending generated AI Web cases', () => {
    const generated = buildGeneratedCasesFromAiGeneratedCases([
      {
        sourcePointId: 'point-1', title: '登录成功', module: '登录', caseKind: 'web_automation', pageName: '登录页', startUrl: '/login', browser: 'chromium', viewport: '1440x900', precondition: '', objective: '', steps: 'goto /login\nassertVisible .dashboard', assertions: 'assertVisible .dashboard', expectedResult: '工作台可见', naturalSteps: '进入登录页', naturalExpectedResult: '工作台可见', sourceFile: 'requirements.md', sourceText: '登录成功'
      }
    ], [
      {
        pointId: 'point-1', runId: 'run-1', projectId: 1, sourceDocumentId: 'doc-1', sourceChunkId: 'chunk-1', title: '登录成功', module: '登录', pageOrFeature: '登录页', caseKind: 'web_automation', testType: '功能', priority: 'P1', objective: '', steps: 'goto /login', expectedResult: '工作台可见', sourceFile: 'requirements.md', sourceText: '登录成功', confidenceScore: 0.9, inferred: false, reviewStatus: 'confirmed', metadata: {}, createdAt: '2026-07-07T00:00:00Z', updatedAt: '2026-07-07T00:00:00Z'
      }
    ], 'Haitlify', 12)

    expect(generated.cases[0].id).toBe('WEB-AI-0013')
  })

  it('hydrates missing natural-language design from source draft points before rendering agent artifacts', () => {
    const generated = buildGeneratedCasesFromAiGeneratedCases([
      {
        sourcePointId: 'point-log-permission',
        title: '有日志权限角色可查看日志管理菜单及日志范围',
        module: '日志管理',
        caseKind: 'web_automation',
        pageName: '日志管理',
        startUrl: 'https://app-dev.example.test/',
        browser: 'chromium',
        viewport: '1440x900',
        precondition: '已存在有日志权限账号',
        objective: '验证有日志权限角色可查看日志管理菜单及日志范围',
        steps: 'goto https://app-dev.example.test/\nassertVisible text=日志管理',
        assertions: 'assertVisible text=日志管理',
        expectedResult: '展示日志管理菜单和可查看日志范围',
        naturalSteps: '',
        naturalExpectedResult: '',
        sourceFile: 'requirements.md',
        sourceText: '日志权限角色可查看日志管理菜单及日志范围'
      }
    ], [
      {
        pointId: 'point-log-permission',
        runId: 'run-1',
        projectId: 1,
        sourceDocumentId: 'doc-1',
        sourceChunkId: 'chunk-1',
        title: '有日志权限角色可查看日志管理菜单及日志范围',
        module: '日志管理',
        pageOrFeature: '日志管理',
        caseKind: 'web_automation',
        testType: '功能',
        priority: 'P0',
        objective: '验证有日志权限角色可查看日志管理菜单及日志范围',
        steps: '进入已登录首页\n查看日志管理菜单\n进入日志管理页面并查看日志范围',
        expectedResult: '日志管理菜单可见，且日志列表/范围信息与权限一致',
        sourceFile: 'requirements.md',
        sourceText: '日志权限角色可查看日志管理菜单及日志范围',
        confidenceScore: 0.9,
        inferred: false,
        reviewStatus: 'confirmed',
        metadata: {},
        createdAt: '2026-07-07T00:00:00Z',
        updatedAt: '2026-07-07T00:00:00Z'
      }
    ], 'Haitlify')

    expect(generated.cases[0].row.naturalSteps).toContain('查看日志管理菜单')
    expect(generated.cases[0].row.naturalExpectedResult).toContain('日志管理菜单可见')
  })

  it('rejects AI natural-language web steps instead of importing manual cases', () => {
    expect(() => buildGeneratedCasesFromAiGeneratedCases(
      [{
        sourcePointId: 'point-1',
        title: '用户中心页面可访问',
        module: '用户中心',
        caseKind: 'web_automation',
        pageName: '用户管理',
        startUrl: '/user-center',
        browser: 'chromium',
        viewport: '1440x900',
        precondition: '环境登录流程已完成',
        objective: '验证登录后可进入用户中心',
        steps: '1. 登录系统并进入用户中心任一页面，如“用户管理”。\nassertVisible .user-center',
        assertions: 'assertVisible .user-center',
        expectedResult: '页面正常展示',
        naturalSteps: '进入用户中心任一页面',
        naturalExpectedResult: '页面正常展示',
        sourceFile: 'requirements.md',
        sourceText: '用户中心页面可访问'
      }],
      [{
        pointId: 'point-1',
        runId: 'run-1',
        projectId: 1,
        sourceDocumentId: 'doc-1',
        sourceChunkId: 'chunk-1',
        title: '用户中心页面可访问',
        module: '用户中心',
        pageOrFeature: '用户管理',
        caseKind: 'web_automation',
        testType: '功能',
        priority: 'P1',
        objective: '验证登录后可进入用户中心',
        steps: '1. 登录系统并进入用户中心任一页面，如“用户管理”。',
        expectedResult: '页面正常展示',
        sourceFile: 'requirements.md',
        sourceText: '用户中心页面可访问',
        confidenceScore: 0.9,
        inferred: false,
        reviewStatus: 'confirmed',
        metadata: {},
        createdAt: '2026-07-07T00:00:00Z',
        updatedAt: '2026-07-07T00:00:00Z'
      }],
      'Haitlify'
    )).toThrow('point-1 包含不可执行步骤')
  })
})

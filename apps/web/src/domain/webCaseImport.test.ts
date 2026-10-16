import { describe, expect, it } from 'vitest'
import {
  importWebCaseDocument,
  importWebCaseDocuments,
  summarizeImportedWebCases,
  formatNaturalLanguageText
} from './webCaseImport'

describe('webCaseImport', () => {
  it('formats natural-language text with stable ordered numbering', () => {
    expect(formatNaturalLanguageText('进入页面\n2. 输入账号密码\n3、点击登录')).toBe('1. 进入页面\n2. 输入账号密码\n3. 点击登录')
  })

  it('normalizes generated natural-language expected results when building a Web case', async () => {
    const { buildGeneratedCasesFromAiGeneratedCases } = await import('./webCaseImport')
    const result = buildGeneratedCasesFromAiGeneratedCases([
      {
        sourcePointId: 'point-expected-order',
        title: '登录成功',
        module: '登录',
        caseKind: 'web_automation',
        pageName: '登录页',
        startUrl: '/login',
        browser: 'chromium',
        viewport: '1440x900',
        precondition: '',
        objective: '验证登录结果',
        steps: 'goto /login\nassertVisible .dashboard',
        assertions: 'assertVisible .dashboard',
        expectedResult: '1. 工作台可见\n2) 导航菜单展示',
        naturalSteps: '进入登录页',
        naturalExpectedResult: '1. 工作台可见\n2) 导航菜单展示',
        sourceFile: 'requirements.md',
        sourceText: '登录成功'
      }
    ], [], 'Haitlify')

    expect(result.cases[0].row.naturalExpectedResult).toBe('1. 工作台可见\n2. 导航菜单展示')
  })

  it('imports yaml web automation cases into structured generated cases', () => {
    const result = importWebCaseDocument({
      name: 'login.yaml',
      text: `
project: Haitlify
module: 登录
pageName: 登录页
startUrl: /login
browser: chromium
viewport: 1440x900
cases:
  - title: 用户名密码登录成功
    caseType: 正向
    steps:
      - action: goto
        targetType: url
        target: /login
      - action: input
        targetType: locator
        target: '#username'
        value: admin
      - action: click
        targetType: locator
        target: 'button[type=submit]'
      - action: assertVisible
        targetType: locator
        target: '.dashboard'
`
    })

    expect(result.projectName).toBe('Haitlify')
    expect(result.cases).toHaveLength(1)
    expect(result.cases[0].row.title).toBe('用户名密码登录成功')
    expect(result.cases[0].row.pageName).toBe('登录页')
    expect(result.cases[0].metadata.sourceDocumentType).toBe('yaml')
    expect(result.cases[0].metadata.steps.map((step) => step.action)).toEqual([
      'goto',
      'input',
      'click',
      'assertVisible'
    ])
  })

  it('imports markdown cases using headings and numbered steps', () => {
    const result = importWebCaseDocument({
      name: 'login.md',
      text: `
# Haitlify

## 模块: 登录

### 用例: 用户名密码登录成功
- 页面: 登录页
- 地址: /login
- 用例类型: 正向
- 浏览器: chromium
- 视口: 1440x900

1. 打开 /login
2. 输入 #username = admin
3. 输入 #password = secret
4. 点击 button[type=submit]
5. 断言可见 .dashboard
`
    })

    expect(result.projectName).toBe('Haitlify')
    expect(result.cases).toHaveLength(1)
    expect(result.cases[0].row.module).toBe('登录')
    expect(result.cases[0].row.startUrl).toBe('/login')
    expect(result.cases[0].metadata.sourceDocumentType).toBe('markdown')
    expect(result.cases[0].metadata.steps).toHaveLength(5)
  })

  it('does not turn an upload line into a runner action that cannot exist', () => {
    // 复核方 F6：前端词表曾保留 runner 未实现的 upload，导入后 runner 直接硬失败（且只有英文报错）。
    // 现在 upload 不属于任何动作词表，整行退化成人工步骤，不会被下发执行。
    const result = importWebCaseDocument({
      name: 'upload.md',
      text: `
# Haitlify

## 模块: 资料

### 用例: 上传附件
- 页面: 资料页
- 地址: /files
- 用例类型: 正向

1. 打开 /files
2. upload #attachment
`
    })

    const steps = result.cases[0].metadata.steps
    expect(steps.map((step) => step.action)).not.toContain('upload')
    const uploadStep = steps.find((step) => step.target.includes('upload'))
    expect(uploadStep?.action).toBe('manual')
    expect(uploadStep?.targetType).toBe('elementRef')
  })

  it('generates requirement cases from document, spreadsheet, json, and image inputs', () => {
    const result = importWebCaseDocuments([
      {
        name: 'requirements.pdf',
        text: '登录页\n测试点: 用户输入正确账号密码后进入工作台\n测试点: 密码错误时显示错误提示'
      },
      {
        name: 'requirements.xlsx',
        text: '项目,模块,页面,测试点\nHaitlify,项目管理,项目页,新建项目成功后出现在列表'
      },
      {
        name: 'requirements.json',
        text: JSON.stringify({
          project: 'Haitlify',
          module: '成员管理',
          pageName: '成员页',
          requirements: ['邀请成员成功', '移除成员需要二次确认']
        })
      },
      {
        name: 'login-flow.png',
        text: ''
      }
    ])

    expect(result.cases.map((testCase) => testCase.row.title)).toEqual([
      '用户输入正确账号密码后进入工作台',
      '密码错误时显示错误提示',
      '新建项目成功后出现在列表',
      '邀请成员成功',
      '移除成员需要二次确认',
      '根据图片 login-flow.png 提取需求测试点'
    ])
    expect(result.cases.map((testCase) => testCase.metadata.sourceDocumentType)).toEqual([
      'pdf',
      'pdf',
      'excel',
      'json',
      'json',
      'image'
    ])
  })

  it('imports multiple yaml and markdown files together', () => {
    const result = importWebCaseDocuments([
      {
        name: 'login.yaml',
        text: `
project: Haitlify
module: 登录
pageName: 登录页
startUrl: /login
cases:
  - title: 用户名密码登录成功
    steps:
      - action: goto
        targetType: url
        target: /login
`
      },
      {
        name: 'project.md',
        text: `
# Haitlify
## 模块: 项目
### 用例: 新建项目成功
- 页面: 项目页
- 地址: /projects
1. 打开 /projects
2. 点击 button.create-project
`
      }
    ])

    expect(result.projectName).toBe('Haitlify')
    expect(result.cases).toHaveLength(2)
    expect(result.cases.map((testCase) => testCase.row.title)).toEqual([
      '用户名密码登录成功',
      '新建项目成功'
    ])
  })

  it('summarizes imported web cases for preview filters and sidebar counts', () => {
    const result = importWebCaseDocuments([
      {
        name: 'login.yaml',
        text: `
project: Haitlify
module: 登录
pageName: 登录页
startUrl: /login
cases:
  - title: 用户名密码登录成功
    caseType: 正向
    steps:
      - action: goto
        targetType: url
        target: /login
`
      },
      {
        name: 'project.md',
        text: `
# Haitlify
## 模块: 项目
### 用例: 新建项目成功
- 页面: 项目页
- 地址: /projects
- 用例类型: 冒烟
1. 打开 /projects
`
      }
    ])

    const summary = summarizeImportedWebCases(result.cases)

    expect(summary.modules).toEqual([
      { value: '登录', count: 1 },
      { value: '项目', count: 1 }
    ])
    expect(summary.caseTypes).toEqual([
      { value: '正向', count: 1 },
      { value: '冒烟', count: 1 }
    ])
    expect(summary.pages).toEqual([
      { value: '登录页', count: 1 },
      { value: '项目页', count: 1 }
    ])
  })

  it('extracts requirement points from headings, numbered lists, and duplicate lines', () => {
    const result = importWebCaseDocument({
      name: 'requirements.txt',
      text: `
项目: Haitlify
模块: 登录与安全
页面: 登录页

一、账号登录
1. 用户输入正确账号密码后进入工作台
2. 用户连续输错密码 5 次后账号锁定

页面: 双因子验证
- 输入正确验证码后登录成功
- 输入正确验证码后登录成功
`
    })

    expect(result.projectName).toBe('Haitlify')
    expect(result.cases.map((testCase) => ({
      module: testCase.row.module,
      page: testCase.row.pageName,
      title: testCase.row.title
    }))).toEqual([
      {
        module: '登录与安全',
        page: '登录页',
        title: '用户输入正确账号密码后进入工作台'
      },
      {
        module: '登录与安全',
        page: '登录页',
        title: '用户连续输错密码 5 次后账号锁定'
      },
      {
        module: '登录与安全',
        page: '双因子验证',
        title: '输入正确验证码后登录成功'
      }
    ])
  })
})

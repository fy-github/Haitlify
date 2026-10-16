import { describe, expect, it } from 'vitest'
import {
  buildWebAutomationLibraryId,
  type GeneratedWebAutomationCase
} from './webAutomationCase'

const makeWebCase = (id: string): GeneratedWebAutomationCase => ({
  id,
  row: {
    caseId: id,
    project: 'Haitlify',
    module: '登录',
    pageName: '登录页',
    title: '用户名密码登录成功',
    caseType: '正向',
    precondition: '已打开浏览器',
    startUrl: '/login',
    browser: 'chromium',
    viewport: '1440x900',
    testData: '{"username":"admin"}',
    steps: '1. 打开登录页 2. 输入用户名密码 3. 点击登录',
    locators: '#username, #password, button[type=submit]',
    assertions: '登录成功后显示工作台',
    waitStrategy: 'networkidle',
    screenshotStrategy: 'on_failure',
    priority: 'P1',
    executionResult: '未执行',
    executor: '',
    executedAt: '',
    defectId: '',
    tags: '登录,Web自动化',
    sourceFile: 'login.yaml',
    remark: ''
  },
  metadata: {
    sourceFile: 'login.yaml',
    sourceDocumentType: 'yaml',
    pageName: '登录页',
    startUrl: '/login',
    browser: 'chromium',
    viewport: '1440x900',
    steps: [
      {
        id: 'step-1',
        name: '打开登录页',
        action: 'goto',
        targetType: 'url',
        target: '/login'
      },
      {
        id: 'step-2',
        name: '输入用户名',
        action: 'input',
        targetType: 'locator',
        target: '#username',
        value: 'admin'
      }
    ],
    locators: [
      {
        id: 'locator-1',
        name: '用户名输入框',
        locatorType: 'css',
        value: '#username'
      }
    ],
    assertions: [
      {
        id: 'assertion-1',
        name: '工作台可见',
        type: 'visible',
        target: '.dashboard'
      }
    ],
    variables: {
      password: 'secret'
    },
    runOrder: 1
  }
})

describe('webAutomationCase', () => {
  it('builds a stable library id from file, page, start url, and case type', () => {
    expect(buildWebAutomationLibraryId(makeWebCase('WEB-0001'))).toBe('login.yaml:登录页:/login:正向')
  })
})

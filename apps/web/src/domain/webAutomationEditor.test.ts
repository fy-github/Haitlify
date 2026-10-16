import { describe, expect, it } from 'vitest'
import {
  serializeWebAutomationSteps,
  syncWebAutomationMetadataFromRow,
  type EditableWebAutomationCase
} from './webAutomationEditor'

const makeEditableCase = (): EditableWebAutomationCase => ({
  id: 'WEB-0001',
  row: {
    caseId: 'WEB-0001',
    project: 'Haitlify',
    module: '登录',
    pageName: '登录页',
    title: '登录成功',
    caseType: '正向',
    precondition: '',
    startUrl: '/login',
    browser: 'chromium',
    viewport: '1440x900',
    testData: '{}',
    steps: 'goto /login\ninput #username = admin\nassertVisible .dashboard',
    locators: '#username\n.dashboard',
    assertions: 'assertVisible .dashboard',
    waitStrategy: 'networkidle',
    screenshotStrategy: 'on_failure',
    priority: 'P1',
    executionResult: '未执行',
    executor: '',
    executedAt: '',
    defectId: '',
    tags: '登录,web',
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
    steps: [],
    locators: [],
    assertions: [],
    variables: {},
    runOrder: 1
  }
})

describe('webAutomationEditor', () => {
  it('serializes structured steps into editor text', () => {
    expect(serializeWebAutomationSteps([
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
    ])).toBe('goto /login\ninput #username = admin')
  })

  it('syncs web row text fields back into metadata steps, locators, and assertions', () => {
    const next = syncWebAutomationMetadataFromRow(makeEditableCase())

    expect(next.metadata.pageName).toBe('登录页')
    expect(next.metadata.startUrl).toBe('/login')
    expect(next.metadata.steps.map((item) => item.action)).toEqual(['goto', 'input', 'assertVisible'])
    expect(next.metadata.locators.map((item) => item.value)).toEqual(['#username', '.dashboard'])
    expect(next.metadata.assertions.map((item) => item.target)).toEqual(['.dashboard'])
  })

  it('infers non-css locator types when syncing edited Web rows', () => {
    const editableCase = makeEditableCase()
    editableCase.row.locators = [
      'xpath=//button[normalize-space()="登录"]',
      '//main//button[1]',
      'text=用户管理',
      'role=button[name="登录"]',
      'testId=submit-login'
    ].join('\n')

    const next = syncWebAutomationMetadataFromRow(editableCase)

    expect(next.metadata.locators.map((item) => item.locatorType)).toEqual([
      'xpath',
      'xpath',
      'text',
      'role',
      'testId'
    ])
  })

  it('syncs natural-language row steps as manual notes instead of click locators', () => {
    const editableCase = makeEditableCase()
    editableCase.row.steps = '1. 登录系统并进入用户中心任一页面，如“用户管理”。\nassertVisible .user-center'

    const next = syncWebAutomationMetadataFromRow(editableCase)

    expect(next.metadata.steps.map((item) => ({
      action: item.action,
      targetType: item.targetType,
      target: item.target
    }))).toEqual([
      {
        action: 'manual',
        targetType: 'elementRef',
        target: '1. 登录系统并进入用户中心任一页面，如“用户管理”。'
      },
      {
        action: 'assertVisible',
        targetType: 'locator',
        target: '.user-center'
      }
    ])
  })
})

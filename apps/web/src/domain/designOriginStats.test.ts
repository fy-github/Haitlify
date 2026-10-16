import { describe, expect, it } from 'vitest'
import { summarizeDesignOriginStats } from './designOriginStats'
import type { SavedWebAutomationCase } from './webAutomationCase'
import type { SavedWebAutomationRunCase } from './webAutomationRepository'

const makeSavedWebCase = (id: string, design = false): SavedWebAutomationCase => ({
  id,
  libraryId: `Haitlify:设计稿交互:设计自动化:${id}`,
  savedAt: '2026-07-01T00:00:00.000Z',
  row: {
    caseId: id,
    project: 'Haitlify',
    module: design ? '设计稿交互' : '登录',
    pageName: '登录页',
    title: design ? '设计稿-主按钮跳转' : '用户名密码登录成功',
    caseType: design ? '设计自动化' : '正向',
    precondition: '',
    startUrl: '/login',
    browser: 'chromium',
    viewport: '1440x900',
    testData: '{}',
    steps: '1. 打开登录页',
    locators: '#username',
    assertions: '工作台可见',
    waitStrategy: 'networkidle',
    screenshotStrategy: 'on_failure',
    priority: 'P1',
    executionResult: '未执行',
    executor: '',
    executedAt: '',
    defectId: '',
    tags: design ? '设计稿,设计自动化' : '登录,web',
    sourceFile: 'figma-link',
    remark: design ? '来源于设计稿' : ''
  },
  metadata: {
    sourceFile: 'figma-link',
    sourceDocumentType: 'figma',
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

const makeSavedAutomation = (id: string, design = false): SavedWebAutomationRunCase => ({
  id,
  sourceCaseId: design ? 'WEB-DESIGN-0001' : 'WEB-0002',
  project: 'Haitlify',
  module: design ? '设计稿交互' : '登录',
  pageName: '登录页',
  name: design ? '设计稿-主按钮跳转' : '用户名密码登录成功',
  startUrl: '/login',
  browser: 'chromium',
  viewport: '1440x900',
  authenticationMode: 'environment',
  steps: [],
  runOrder: 1,
  mockOutcome: 'passed',
  automationLibraryId: `auto:${id}`,
  savedAt: '2026-07-01T00:00:00.000Z'
})

describe('designOriginStats', () => {
  it('summarizes design-origin and non-design web cases and automation cases', () => {
    const result = summarizeDesignOriginStats(
      [makeSavedWebCase('WEB-1', true), makeSavedWebCase('WEB-2', false)],
      [makeSavedAutomation('WEB-AUTO-1', true), makeSavedAutomation('WEB-AUTO-2', false)]
    )

    expect(result).toEqual({
      designLibraryCases: 1,
      otherLibraryCases: 1,
      designAutomationCases: 1,
      otherAutomationCases: 1
    })
  })
})

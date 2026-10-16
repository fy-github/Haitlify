import { describe, expect, it } from 'vitest'
import { designLocatorCards, figmaElementCandidateCards } from './designLocatorView'
import type { GeneratedWebAutomationCase } from './webAutomationCase'

describe('designLocatorView', () => {
  it('maps locator metadata into view cards', () => {
    const testCase: GeneratedWebAutomationCase = {
      id: 'WEB-DESIGN-0001',
      row: {
        caseId: 'WEB-DESIGN-0001',
        project: 'Haitlify',
        module: '设计稿交互',
        pageName: '设计稿流程',
        title: '设计稿-主按钮跳转到 Success / Home',
        caseType: '设计草案',
        precondition: '',
        startUrl: '/',
        browser: 'chromium',
        viewport: '1440x900',
        testData: '{}',
        steps: '',
        locators: '',
        assertions: '',
        waitStrategy: 'networkidle',
        screenshotStrategy: 'on_failure',
        priority: 'P1',
        executionResult: '未执行',
        executor: '',
        executedAt: '',
        defectId: '',
        tags: '设计稿,自动化草案',
        sourceFile: 'figma-link',
        remark: ''
      },
      metadata: {
        sourceFile: 'figma-link',
        sourceDocumentType: 'figma',
        pageName: '设计稿流程',
        startUrl: '/',
        browser: 'chromium',
        viewport: '1440x900',
        steps: [],
        locators: [
          {
            id: 'locator-1',
            name: 'Page / Login > Form / Login > Button / Primary',
            locatorType: 'text',
            value: 'role=button[name="Button / Primary"]',
            fallbackValue: 'text=Button / Primary | testid=button-primary | css=[data-design-path="page-login-form-login-button-primary"]',
            confidence: 'high',
            reason: '按钮组件优先使用 role=button，文本与设计路径作为回退候选'
          }
        ],
        assertions: [],
        variables: {},
        runOrder: 1
      }
    }

    expect(designLocatorCards(testCase)).toEqual([
      {
        id: 'locator-1',
        name: 'Page / Login > Form / Login > Button / Primary',
        primary: 'role=button[name="Button / Primary"]',
        fallback: [
          'text=Button / Primary',
          'testid=button-primary',
          'css=[data-design-path="page-login-form-login-button-primary"]'
        ],
        confidence: 'high',
        reason: '按钮组件优先使用 role=button，文本与设计路径作为回退候选',
        confirmed: false
      }
    ])
  })

  it('falls back to low confidence defaults when locator metadata is incomplete', () => {
    const testCase: GeneratedWebAutomationCase = {
      id: 'WEB-DESIGN-0002',
      row: {
        caseId: 'WEB-DESIGN-0002',
        project: 'Haitlify',
        module: '设计稿结构',
        pageName: '设计稿结构',
        title: '设计稿-表单层级与关键组件可见',
        caseType: '设计草案',
        precondition: '',
        startUrl: '/',
        browser: 'chromium',
        viewport: '1440x900',
        testData: '{}',
        steps: '',
        locators: '',
        assertions: '',
        waitStrategy: 'networkidle',
        screenshotStrategy: 'on_failure',
        priority: 'P1',
        executionResult: '未执行',
        executor: '',
        executedAt: '',
        defectId: '',
        tags: '设计稿,自动化草案',
        sourceFile: 'figma-link',
        remark: ''
      },
      metadata: {
        sourceFile: 'figma-link',
        sourceDocumentType: 'figma',
        pageName: '设计稿结构',
        startUrl: '/',
        browser: 'chromium',
        viewport: '1440x900',
        steps: [],
        locators: [
          {
            id: 'locator-2',
            name: 'Unknown / Element',
            locatorType: 'text',
            value: 'text=Unknown / Element'
          }
        ],
        assertions: [],
        variables: {},
        runOrder: 1
      }
    }

    expect(designLocatorCards(testCase)).toEqual([
      {
        id: 'locator-2',
        name: 'Unknown / Element',
        primary: 'text=Unknown / Element',
        fallback: [],
        confidence: 'low',
        reason: '待人工确认候选定位器',
        confirmed: false
      }
    ])
  })

  it('maps Figma element candidates into locator review cards', () => {
    expect(figmaElementCandidateCards([
      {
        candidateId: 'candidate-1',
        componentKey: 'button-primary',
        componentName: 'Button / Primary',
        nodeId: '1:2',
        nodeName: 'Submit Button',
        role: 'button',
        suggestedLocatorName: 'button-primary',
        status: 'confirmed',
        evidence: { fileKey: 'File123', nodeId: '1:2' }
      },
      {
        candidateId: 'candidate-2',
        componentKey: 'input-email',
        componentName: 'Input / Text Field',
        nodeId: '1:3',
        nodeName: 'Email Input',
        role: 'textbox',
        suggestedLocatorName: 'input-email',
        status: 'pending',
        evidence: { fileKey: 'File123', nodeId: '1:3' }
      }
    ])).toEqual([
      {
        id: 'candidate-1',
        name: 'Button / Primary',
        primary: 'role=button[name="button-primary"]',
        fallback: ['testid=button-primary', 'figma-node=1:2'],
        confidence: 'high',
        reason: 'Figma 组件 Button / Primary 已确认，可作为元素库候选',
        confirmed: true
      },
      {
        id: 'candidate-2',
        name: 'Input / Text Field',
        primary: 'role=textbox[name="input-email"]',
        fallback: ['testid=input-email', 'figma-node=1:3'],
        confidence: 'medium',
        reason: 'Figma 组件 Input / Text Field 待人工确认',
        confirmed: false
      }
    ])
  })
})

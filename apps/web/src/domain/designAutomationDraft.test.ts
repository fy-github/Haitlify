import { describe, expect, it } from 'vitest'
import {
  buildDesignAutomationDraftCases,
  finalizeDesignDraftCasesForLibrary
} from './designAutomationDraft'
import type { ParsedRequirementDocument } from './requirementDocumentRepository'

describe('designAutomationDraft', () => {
  it('builds structured web automation draft cases from figma design semantics', () => {
    const payload: ParsedRequirementDocument = {
      fileName: 'figma-link',
      documentType: 'figma',
      projectName: 'Haitlify',
      text: 'Figma 设计链接: https://www.figma.com/design/AbCdEf123456/Login-Page?node-id=12-34&m=dev',
      lineCount: 1,
      visualSummary: {
        width: 1440,
        height: 900,
        aspectRatio: 1.6,
        viewportHint: 'desktop',
        layoutHints: ['存在 Auto Layout 垂直流布局', '页面流: Button / Primary -> Success / Home -> Back / Link -> BACK'],
        styleHints: ['标题字重 700，字号 32px', '标题文本色 #1A1A1A'],
        componentHints: [
          'Page / Login > Form / Login > Button / Primary',
          'Page / Login > Form / Login > Input / Default',
          'Button / Primary --ON_CLICK--> Success / Home [SMART_ANIMATE 300ms]',
          'Card / Tips --ON_HOVER--> Tooltip / Help [MOVE_IN 200ms]',
          'Back / Link --ON_CLICK--> BACK',
          'Jump / FAQ --ON_CLICK--> Section / FAQ [SCROLL_TO 0ms]',
          'Card / Promo --ON_DRAG--> Drawer / Detail [MOVE_IN 250ms]',
          'Toast / Saved --AFTER_TIMEOUT--> null [DISSOLVE 500ms]',
          'Button / Close --ON_CLICK--> null [CLOSE_OVERLAY 0ms]'
        ],
        ocrStatus: 'available'
      },
      suggestedRequirementPoints: [
        '点击主按钮后以 SMART_ANIMATE 300ms 跳转到 Success / Home',
        '表单区、输入框、主按钮等关键组件层级与设计稿一致'
      ]
    }

    const result = buildDesignAutomationDraftCases(payload)

    expect(result).toHaveLength(8)
    expect(result[0].row.title).toBe('设计稿-主按钮跳转到 Success / Home')
    expect(result[0].metadata.steps.map((step) => `${step.action}:${step.target}`)).toEqual([
      'assertVisible:Page / Login > Form / Login > Button / Primary',
      'click:Page / Login > Form / Login > Button / Primary',
      'waitForUrl:Success / Home',
      'assertUrl:Success / Home'
    ])
    expect(result[0].metadata.locators[0].value).toBe('role=button[name="Button / Primary"]')
    expect(result[0].metadata.locators[0].fallbackValue).toBe('text=Button / Primary | testid=button-primary | css=[data-design-path="page-login-form-login-button-primary"]')
    expect(result[0].metadata.locators[0].confidence).toBe('high')
    expect(result[0].metadata.locators[0].reason).toBe('按钮组件优先使用 role=button，文本与设计路径作为回退候选')
    expect(result[1].row.title).toBe('设计稿-悬浮显示 Tooltip / Help')
    expect(result[1].metadata.steps.map((step) => `${step.action}:${step.target}`)).toEqual([
      'assertVisible:Card / Tips',
      'hover:Card / Tips',
      'waitForVisible:Tooltip / Help',
      'assertVisible:Tooltip / Help'
    ])
    expect(result[1].metadata.locators[0].value).toBe('role=tooltip[name="Card / Tips"]')
    expect(result[1].metadata.locators[0].fallbackValue).toBe('text=Card / Tips | testid=card-tips | css=[data-design-path="card-tips"]')
    expect(result[2].row.title).toBe('设计稿-点击返回上一层')
    expect(result[2].metadata.steps.map((step) => `${step.action}:${step.target}`)).toEqual([
      'assertVisible:Back / Link',
      'click:Back / Link',
      'assertHidden:当前页面'
    ])
    expect(result[2].metadata.locators[0].value).toBe('role=link[name="Back / Link"]')
    expect(result[2].metadata.locators[0].fallbackValue).toBe('text=Back / Link | testid=back-link | css=[data-design-path="back-link"]')
    expect(result[3].row.title).toBe('设计稿-点击滚动到 Section / FAQ')
    expect(result[3].metadata.steps.map((step) => `${step.action}:${step.target}`)).toEqual([
      'assertVisible:Jump / FAQ',
      'click:Jump / FAQ',
      'waitForVisible:Section / FAQ',
      'assertVisible:Section / FAQ'
    ])
    expect(result[3].metadata.locators[0].value).toBe('role=link[name="Jump / FAQ"]')
    expect(result[3].metadata.locators[0].fallbackValue).toBe('text=Jump / FAQ | testid=jump-faq | css=[data-design-path="jump-faq"]')
    expect(result[3].metadata.locators[0].confidence).toBe('high')
    expect(result[4].row.title).toBe('设计稿-拖拽打开 Drawer / Detail')
    expect(result[4].metadata.steps.map((step) => `${step.action}:${step.target}`)).toEqual([
      'assertVisible:Card / Promo',
      'hover:Card / Promo',
      'waitForVisible:Drawer / Detail',
      'assertVisible:Drawer / Detail'
    ])
    expect(result[4].metadata.locators[0].value).toBe('role=region[name="Card / Promo"]')
    expect(result[4].metadata.locators[0].fallbackValue).toBe('text=Card / Promo | testid=card-promo | css=[data-design-path="card-promo"]')
    expect(result[4].metadata.locators[2].value).toBe('role=dialog[name="Drawer / Detail"]')
    expect(result[4].metadata.locators[2].fallbackValue).toBe('text=Drawer / Detail | testid=drawer-detail | css=[data-design-path="drawer-detail"]')
    expect(result[5].row.title).toBe('设计稿-等待提示自动消失')
    expect(result[5].metadata.steps.map((step) => `${step.action}:${step.target}`)).toEqual([
      'assertVisible:Toast / Saved',
      'assertHidden:Toast / Saved'
    ])
    expect(result[5].metadata.locators[0].value).toBe('role=status[name="Toast / Saved"]')
    expect(result[5].metadata.locators[0].fallbackValue).toBe('text=Toast / Saved | testid=toast-saved | css=[data-design-path="toast-saved"]')
    expect(result[5].metadata.locators[0].confidence).toBe('medium')
    expect(result[5].metadata.locators[0].reason).toBe('提示类组件建议使用 role=status，文本与测试标识作为补充候选')
    expect(result[6].row.title).toBe('设计稿-关闭浮层')
    expect(result[6].metadata.steps.map((step) => `${step.action}:${step.target}`)).toEqual([
      'assertVisible:Button / Close',
      'click:Button / Close',
      'assertHidden:当前浮层'
    ])
    expect(result[6].metadata.locators[0].value).toBe('role=button[name="Button / Close"]')
    expect(result[6].metadata.locators[0].fallbackValue).toBe('text=Button / Close | testid=button-close | css=[data-design-path="button-close"]')
    expect(result[7].row.title).toBe('设计稿-表单层级与关键组件可见')
    expect(result[7].metadata.steps.map((step) => `${step.action}:${step.target}`)).toEqual([
      'assertVisible:Page / Login > Form / Login > Input / Default',
      'assertVisible:Page / Login > Form / Login > Button / Primary'
    ])
    expect(result[7].metadata.locators[0].value).toBe('role=textbox[name="Input / Default"]')
    expect(result[7].metadata.locators[0].fallbackValue).toBe('text=Input / Default | testid=input-default | css=[data-design-path="page-login-form-login-input-default"]')
    expect(result[7].metadata.locators[1].value).toBe('role=button[name="Button / Primary"]')
    expect(result[7].metadata.locators[1].fallbackValue).toBe('text=Button / Primary | testid=button-primary | css=[data-design-path="page-login-form-login-button-primary"]')
  })

  it('relabels design draft cases before saving them through automation handoff', () => {
    const payload: ParsedRequirementDocument = {
      fileName: 'figma-link',
      documentType: 'figma',
      projectName: 'Haitlify',
      text: 'Figma 设计链接',
      lineCount: 1,
      visualSummary: {
        width: 1440,
        height: 900,
        aspectRatio: 1.6,
        viewportHint: 'desktop',
        layoutHints: [],
        styleHints: [],
        componentHints: [
          'Page / Login > Form / Login > Button / Primary',
          'Button / Primary --ON_CLICK--> Success / Home [SMART_ANIMATE 300ms]'
        ],
        ocrStatus: 'available'
      },
      suggestedRequirementPoints: []
    }

    const [draft] = buildDesignAutomationDraftCases(payload)
    const [finalized] = finalizeDesignDraftCasesForLibrary([draft])

    expect(draft.row.caseType).toBe('设计草案')
    expect(finalized.row.caseType).toBe('设计自动化')
    expect(finalized.row.tags).toContain('设计自动化')
    expect(finalized.row.tags).not.toContain('设计草案')
  })
})

import { describe, expect, it } from 'vitest'
import { buildDesignAutomationDraftCases } from './designAutomationDraft'
import { generateWebAutomationCasesFromGeneratedCases } from './webAutomation'
import type { ParsedRequirementDocument } from './requirementDocumentRepository'

describe('designAutomationPipeline', () => {
  it('turns design semantics into executable web automation drafts', () => {
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
        layoutHints: ['页面流: Button / Primary -> Success / Home -> Back / Link -> BACK'],
        styleHints: [],
        componentHints: [
          'Page / Login > Form / Login > Button / Primary',
          'Button / Primary --ON_CLICK--> Success / Home [SMART_ANIMATE 300ms]'
        ],
        ocrStatus: 'available'
      },
      suggestedRequirementPoints: []
    }

    const generatedCases = buildDesignAutomationDraftCases(payload)
    const automationCases = generateWebAutomationCasesFromGeneratedCases(generatedCases)

    expect(automationCases).toHaveLength(1)
    expect(automationCases[0].name).toBe('设计稿-主按钮跳转到 Success / Home')
    expect(automationCases[0].steps.map((step) => `${step.action}:${step.target}`)).toEqual([
      'assertVisible:Page / Login > Form / Login > Button / Primary',
      'click:Page / Login > Form / Login > Button / Primary',
      'waitForUrl:Success / Home',
      'assertUrl:Success / Home'
    ])
  })
})

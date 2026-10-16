import { describe, expect, it } from 'vitest'
import {
  confirmLocatorCandidates,
  countPendingDesignLocators,
  hasPendingDesignLocators,
  summarizeDesignLocatorProgress
} from './designLocatorStatus'
import type { SavedWebAutomationCase } from './webAutomationCase'

const makeSavedDesignCase = (id: string, confirmed = false): SavedWebAutomationCase => ({
  id,
  libraryId: `Haitlify:设计稿交互:设计自动化:${id}`,
  savedAt: '2026-07-01T00:00:00.000Z',
  row: {
    caseId: id,
    project: 'Haitlify',
    module: '设计稿交互',
    pageName: '设计稿流程',
    title: '设计稿-主按钮跳转',
    caseType: '设计自动化',
    precondition: '',
    startUrl: '/design',
    browser: 'chromium',
    viewport: '1440x900',
    testData: '{}',
    steps: '1. assertVisible 按钮',
    locators: 'Button / Primary',
    assertions: '页面跳转成功',
    waitStrategy: 'networkidle',
    screenshotStrategy: 'on_failure',
    priority: 'P1',
    executionResult: '未执行',
    executor: '',
    executedAt: '',
    defectId: '',
    tags: '设计稿,设计自动化',
    sourceFile: 'figma-link',
    remark: '来源于设计稿'
  },
  metadata: {
    sourceFile: 'figma-link',
    sourceDocumentType: 'figma',
    pageName: '设计稿流程',
    startUrl: '/design',
    browser: 'chromium',
    viewport: '1440x900',
    steps: [],
    locators: [
      {
        id: 'locator-1',
        name: 'Button / Primary',
        locatorType: 'role',
        value: 'role=button[name="Button / Primary"]',
        confirmed
      }
    ],
    assertions: [],
    variables: {},
    runOrder: 1
  }
})

describe('designLocatorStatus', () => {
  it('detects pending locator confirmations on design-origin web cases', () => {
    const pending = makeSavedDesignCase('WEB-1', false)
    const confirmed = makeSavedDesignCase('WEB-2', true)

    expect(hasPendingDesignLocators([pending, confirmed])).toBe(true)
    expect(countPendingDesignLocators([pending, confirmed])).toBe(1)
  })

  it('confirms locator candidates in bulk for design-origin web cases', () => {
    const pending = makeSavedDesignCase('WEB-1', false)
    const updated = confirmLocatorCandidates([pending])

    expect(updated[0].metadata.locators[0].confirmed).toBe(true)
  })

  it('summarizes confirmation progress across design-origin web cases', () => {
    const pending = makeSavedDesignCase('WEB-1', false)
    const confirmed = makeSavedDesignCase('WEB-2', true)

    expect(summarizeDesignLocatorProgress([pending, confirmed])).toEqual({
      totalCases: 2,
      confirmedCases: 1,
      pendingCases: 1,
      totalLocators: 2,
      confirmedLocators: 1
    })
  })
})

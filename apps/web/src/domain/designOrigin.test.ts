import { describe, expect, it } from 'vitest'
import {
  collectFailedDesignOriginResults,
  summarizeFailedDesignOriginResults,
  isDesignOriginAutomationCase,
  isDesignOriginWebCase
} from './designOrigin'
import type { SavedWebAutomationRunCase } from './webAutomationRepository'
import type { WebAutomationRunReport } from './webAutomation'

describe('designOrigin', () => {
  it('detects design-origin web cases from case type, tags, or remark', () => {
    expect(isDesignOriginWebCase('设计自动化', '', '')).toBe(true)
    expect(isDesignOriginWebCase('正向', '设计稿,设计自动化', '')).toBe(true)
    expect(isDesignOriginWebCase('正向', '', '来源于设计稿')).toBe(true)
    expect(isDesignOriginWebCase('正向', '登录,web', '')).toBe(false)
  })

  it('detects design-origin automation cases from sourceCaseId or export target', () => {
    expect(isDesignOriginAutomationCase('WEB-DESIGN-0001')).toBe(true)
    expect(isDesignOriginAutomationCase('WEB-0001', 'design-draft')).toBe(true)
    expect(isDesignOriginAutomationCase('WEB-0001', 'apifox')).toBe(false)
  })

  it('collects failed design-origin automation results from the latest report', () => {
    const cases: SavedWebAutomationRunCase[] = [
      {
        id: 'WEB-AUTO-WEB-DESIGN-0001',
        sourceCaseId: 'WEB-DESIGN-0001',
        project: 'Haitlify',
        module: '设计稿交互',
        pageName: '设计稿流程',
        name: '设计稿-主按钮跳转',
        startUrl: '/design',
        browser: 'chromium',
        viewport: '1440x900',
        authenticationMode: 'environment',
        steps: [],
        runOrder: 1,
        mockOutcome: 'failed',
        automationLibraryId: 'design-1',
        savedAt: '2026-07-01T00:00:00.000Z'
      },
      {
        id: 'WEB-AUTO-WEB-0002',
        sourceCaseId: 'WEB-0002',
        project: 'Haitlify',
        module: '登录',
        pageName: '登录页',
        name: '登录成功',
        startUrl: '/login',
        browser: 'chromium',
        viewport: '1440x900',
        authenticationMode: 'environment',
        steps: [],
        runOrder: 2,
        mockOutcome: 'passed',
        automationLibraryId: 'normal-1',
        savedAt: '2026-07-01T00:00:00.000Z'
      }
    ]
    const report: WebAutomationRunReport = {
      mode: 'sequential',
      startedAt: '2026-07-01T00:00:00.000Z',
      endedAt: '2026-07-01T00:01:00.000Z',
      summary: {
        total: 2,
        passed: 1,
        failed: 1,
        error: 0
      },
      results: [
        {
          caseId: 'WEB-AUTO-WEB-DESIGN-0001',
          sourceCaseId: 'WEB-DESIGN-0001',
          name: '设计稿-主按钮跳转',
          module: '设计稿交互',
          pageName: '设计稿流程',
          startUrl: '/design',
          status: 'failed',
          durationMs: 100
        },
        {
          caseId: 'WEB-AUTO-WEB-0002',
          sourceCaseId: 'WEB-0002',
          name: '登录成功',
          module: '登录',
          pageName: '登录页',
          startUrl: '/login',
          status: 'passed',
          durationMs: 80
        }
      ]
    }

    expect(collectFailedDesignOriginResults(cases, report).map((item) => item.caseId)).toEqual([
      'WEB-AUTO-WEB-DESIGN-0001'
    ])
  })

  it('summarizes failed design-origin automation results for UI prompts', () => {
    const summary = summarizeFailedDesignOriginResults([
      {
        caseId: 'WEB-AUTO-WEB-DESIGN-0001',
        sourceCaseId: 'WEB-DESIGN-0001',
        name: '设计稿-主按钮跳转',
        module: '设计稿交互',
        pageName: '设计稿流程',
        startUrl: '/design',
        status: 'failed',
        durationMs: 100
      },
      {
        caseId: 'WEB-AUTO-WEB-DESIGN-0002',
        sourceCaseId: 'WEB-DESIGN-0002',
        name: '设计稿-提示浮层',
        module: '设计稿交互',
        pageName: '设计稿流程',
        startUrl: '/design',
        status: 'error',
        durationMs: 80
      }
    ])

    expect(summary).toEqual({
      total: 2,
      failed: 1,
      error: 1
    })
  })
})

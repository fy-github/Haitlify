import { describe, expect, it } from 'vitest'
import type { AutomationRunResult } from './apiAutomation'
import type { WebAutomationRunResult } from './webAutomation'
import {
  buildDefectDraftsFromAutomationResults,
  buildDefectDraftsFromWebAutomationResults,
  deleteDefectDrafts,
  deleteDefectDraft,
  exportDefectsToJiraCsv,
  listDefectDrafts,
  markDefectsAsExported,
  saveDefectDrafts,
  type DefectDraft
} from './defectRepository'
import { buildDefaultJiraCsvTemplate } from './jiraCsvTemplate'
import type { JiraCsvTemplateField } from './jiraCsvTemplate'

class MemoryStorage implements Storage {
  private values = new Map<string, string>()
  get length() { return this.values.size }
  clear(): void { this.values.clear() }
  getItem(key: string): string | null { return this.values.get(key) ?? null }
  key(index: number): string | null { return Array.from(this.values.keys())[index] ?? null }
  removeItem(key: string): void { this.values.delete(key) }
  setItem(key: string, value: string): void { this.values.set(key, value) }
}

const makeFailedResult = (caseId: string): AutomationRunResult => ({
  caseId,
  sourceCaseId: caseId.replace('AUTO-', 'API-'),
  name: '创建策略失败',
  module: '网络策略',
  method: 'POST',
  path: '/api/v4.0/network/app-protocols/advanced/rules',
  status: 'failed',
  request: {
    method: 'POST',
    url: 'https://192.168.101.10/api/v4.0/network/app-protocols/advanced/rules',
    headers: {
      Authorization: 'Bearer token'
    },
    body: {
      name: 'aaaaaaaaaaaaaaaa'
    },
    expectedStatus: 400,
    assertions: ['响应状态码为 400']
  },
  expectedStatus: 400,
  actualStatus: 200,
  durationMs: 88,
  errorMessage: '断言失败: 期望失败但接口返回成功',
  actualResponseBody: {
    code: 0,
    message: 'Success'
  }
})

const makeFailedWebResult = (caseId: string): WebAutomationRunResult => ({
  caseId,
  sourceCaseId: caseId.replace('WEB-AUTO-', ''),
  name: '登录失败',
  module: '登录',
  pageName: '登录页',
  startUrl: '/login/error',
  status: 'failed',
  durationMs: 120,
  errorMessage: '断言失败：工作台元素未出现'
})

describe('defectRepository', () => {
  it('builds defect drafts from failed automation results and deduplicates on save', () => {
    const storage = new MemoryStorage()
    const drafts = buildDefectDraftsFromAutomationResults([makeFailedResult('AUTO-1')], {
      projectName: 'IKOS',
      issueType: '故障'
    })

    expect(drafts[0]).toMatchObject({
      sourceCaseId: 'API-1',
      projectName: 'IKOS',
      issueType: '故障',
      status: 'pending_jira_export'
    })

    const first = saveDefectDrafts(storage, drafts)
    const second = saveDefectDrafts(storage, drafts)

    expect(first).toEqual({ inserted: 1, updated: 0, total: 1 })
    expect(second).toEqual({ inserted: 0, updated: 1, total: 1 })
    expect(listDefectDrafts(storage)).toHaveLength(1)
  })

  it('exports jira csv using the provided template columns and marks drafts as exported', () => {
    const storage = new MemoryStorage()
    const draft = buildDefectDraftsFromAutomationResults([makeFailedResult('AUTO-1')], {
      projectName: 'IKOS',
      issueType: '故障'
    })[0]
    saveDefectDrafts(storage, [draft])

    const csv = exportDefectsToJiraCsv([draft])
    expect(csv).toContain('"概要","问题类型","项目名称","优先级","经办人","描述","向外链接的问题（BLOCK）","自定义字段（ 故障分类）"')
    expect(csv).toContain('"IKOS"')
    expect(csv).toContain('请求地址: https://192.168.101.10/api/v4.0/network/app-protocols/advanced/rules')

    markDefectsAsExported(storage, [draft.defectId], 'csv')
    expect(listDefectDrafts(storage)[0].status).toBe('exported_jira_csv')
  })

  it('exports extra jira csv columns from jiraFieldValues using a field template', () => {
    const draft = {
      ...buildDefectDraftsFromAutomationResults([makeFailedResult('AUTO-1')], {
        projectName: 'IKOS',
        issueType: '故障'
      })[0],
      jiraFieldValues: {
        '自定义字段（影响版本）': 'v4.6.1',
        '自定义字段（发现阶段）': '自动化回归'
      }
    }

    const template: JiraCsvTemplateField[] = [
      ...buildDefaultJiraCsvTemplate(),
      {
        key: 'affectedVersion',
        header: '自定义字段（影响版本）',
        source: {
          kind: 'jiraFieldValue',
          fieldKey: '自定义字段（影响版本）'
        }
      },
      {
        key: 'detectedPhase',
        header: '自定义字段（发现阶段）',
        source: {
          kind: 'jiraFieldValue',
          fieldKey: '自定义字段（发现阶段）'
        }
      }
    ]

    const csv = exportDefectsToJiraCsv([draft], template)
    expect(csv).toContain('"自定义字段（影响版本）","自定义字段（发现阶段）"')
    expect(csv).toContain('"v4.6.1","自动化回归"')
  })

  it('deletes stored drafts one by one', () => {
    const storage = new MemoryStorage()
    const draft = buildDefectDraftsFromAutomationResults([makeFailedResult('AUTO-1')], {
      projectName: 'IKOS',
      issueType: '故障'
    })[0]
    saveDefectDrafts(storage, [draft])

    expect(deleteDefectDraft(storage, draft.defectId)).toBe(true)
    expect(listDefectDrafts(storage)).toEqual([])
  })

  it('deletes multiple stored drafts by id', () => {
    const storage = new MemoryStorage()
    const drafts = [
      buildDefectDraftsFromAutomationResults([makeFailedResult('AUTO-1')], {
        projectName: 'IKOS',
        issueType: '故障'
      })[0],
      buildDefectDraftsFromAutomationResults([makeFailedResult('AUTO-2')], {
        projectName: 'IKOS',
        issueType: '故障'
      })[0]
    ]
    saveDefectDrafts(storage, drafts)

    expect(deleteDefectDrafts(storage, [drafts[0].defectId])).toEqual({
      deleted: 1,
      remaining: 1
    })
    expect(listDefectDrafts(storage).map((draft) => draft.defectId)).toEqual([drafts[1].defectId])
  })

  it('builds defect drafts from failed web automation results with web-specific context', () => {
    const drafts = buildDefectDraftsFromWebAutomationResults([makeFailedWebResult('WEB-AUTO-1')], {
      projectName: 'Haitlify',
      issueType: '故障'
    })

    expect(drafts[0].summary).toContain('登录失败')
    expect(drafts[0].description).toContain('页面: 登录页')
    expect(drafts[0].description).toContain('访问地址: /login/error')
    expect(drafts[0].faultCategory).toBe('Web自动化失败')
  })
})

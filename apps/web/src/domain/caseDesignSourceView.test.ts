import { describe, expect, it } from 'vitest'
import { buildCaseDesignSourceView } from './caseDesignSourceView'

describe('caseDesignSourceView', () => {
  it('maps document, Figma, and OpenAPI sources', () => {
    expect(buildCaseDesignSourceView([
      { name: '退款需求.docx', kind: 'document', text: '需求', needsReattach: false },
      { name: 'figma-link.txt', kind: 'figma', text: 'https://figma.com/design/key', needsReattach: false },
      { name: 'schema.json', kind: 'openapi', text: '{}', needsReattach: false }
    ], {})).toEqual([
      { name: '退款需求.docx', kind: 'document', kindLabel: '文档', status: 'ready', detail: '已就绪' },
      { name: 'figma-link.txt', kind: 'figma', kindLabel: 'Figma', status: 'ready', detail: '已连接' },
      { name: 'schema.json', kind: 'openapi', kindLabel: 'OpenAPI', status: 'ready', detail: '已就绪' }
    ])
  })

  it('keeps errors and reattach requirements local to the affected source', () => {
    expect(buildCaseDesignSourceView([
      { name: 'large.docx', kind: 'document', text: '', needsReattach: true },
      { name: 'broken.pdf', kind: 'document', text: '', needsReattach: false }
    ], { 'broken.pdf': '文档解析失败' })).toEqual([
      { name: 'large.docx', kind: 'document', kindLabel: '文档', status: 'reattach', detail: '需要重新选择原文件' },
      { name: 'broken.pdf', kind: 'document', kindLabel: '文档', status: 'error', detail: '文档解析失败' }
    ])
  })
})

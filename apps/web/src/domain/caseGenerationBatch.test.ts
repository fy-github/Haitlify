import { describe, expect, it } from 'vitest'
import { chunkCaseGenerationPointIds, generatedWebCaseIdOffset } from './caseGenerationBatch'

describe('caseGenerationBatch', () => {
  it('keeps point ids stable, unique, and in request-sized batches', () => {
    expect(chunkCaseGenerationPointIds(['p-3', 'p-1', 'p-3', '', 'p-2'], 2)).toEqual([
      ['p-3', 'p-1'],
      ['p-2']
    ])
  })

  it('rejects an invalid batch size', () => {
    expect(() => chunkCaseGenerationPointIds(['p-1'], 0)).toThrow('批处理大小')
  })

  it('finds the next safe generated Web-case offset', () => {
    expect(generatedWebCaseIdOffset([
      { id: 'WEB-AI-0008' },
      { id: 'unrelated', row: { caseId: 'WEB-AI-0012' } },
      { id: 'WEB-AI-invalid' }
    ])).toBe(12)
  })
})

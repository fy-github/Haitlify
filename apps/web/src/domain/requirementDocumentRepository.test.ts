import { describe, expect, it, vi } from 'vitest'
import { parseRequirementDocumentWithBackend } from './requirementDocumentRepository'

describe('requirementDocumentRepository', () => {
  it('uploads a document and returns parsed text from backend', async () => {
    const fetchImpl = vi.fn(async (_input: string, init?: RequestInit) =>
      new Response(JSON.stringify({
        fileName: 'requirements.pdf',
        documentType: 'pdf',
        projectName: 'Haitlify',
        text: '项目: Haitlify\n测试点: 登录成功',
        lineCount: 2
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      }))

    const file = new File(['fake-bytes'], 'requirements.pdf', { type: 'application/pdf' })
    const result = await parseRequirementDocumentWithBackend(file, { fetchImpl })

    expect(fetchImpl).toHaveBeenCalledTimes(1)
    expect(fetchImpl.mock.calls[0]?.[0]).toBe('http://127.0.0.1:18080/api/requirement-documents/parse')
    expect(initBodyIsFormData(fetchImpl.mock.calls[0]?.[1]?.body)).toBe(true)
    expect(result.documentType).toBe('pdf')
    expect(result.projectName).toBe('Haitlify')
    expect(result.text).toContain('登录成功')
  })
})

const initBodyIsFormData = (body: BodyInit | null | undefined): boolean =>
  typeof FormData !== 'undefined' && body instanceof FormData

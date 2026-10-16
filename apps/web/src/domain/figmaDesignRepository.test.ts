import { describe, expect, it, vi } from 'vitest'
import { parseFigmaDesignWithBackend } from './figmaDesignRepository'

describe('figmaDesignRepository', () => {
  it('posts figma url to backend parse endpoint', async () => {
    const fetchImpl = vi.fn(async (_input: string, _init?: RequestInit) =>
      new Response(JSON.stringify({
        fileName: 'Login Page',
        documentType: 'figma',
        projectName: 'Login Page',
        text: 'Figma 设计链接: https://www.figma.com/design/AbCdEf123456/Login-Page?node-id=12-34&m=dev',
        lineCount: 1,
        suggestedRequirementPoints: ['布局结构、组件层级、间距与对齐符合 Figma 设计稿']
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      }))

    const result = await parseFigmaDesignWithBackend(
      'https://www.figma.com/design/AbCdEf123456/Login-Page?node-id=12-34&m=dev',
      { fetchImpl }
    )

    expect(fetchImpl).toHaveBeenCalledTimes(1)
    expect(fetchImpl.mock.calls[0]?.[0]).toBe('http://127.0.0.1:18080/api/requirement-documents/figma/parse')
    expect(fetchImpl.mock.calls[0]?.[1]).toMatchObject({ method: 'POST' })
    expect(result.documentType).toBe('figma')
    expect(result.text).toContain('Figma 设计链接')
  })
})

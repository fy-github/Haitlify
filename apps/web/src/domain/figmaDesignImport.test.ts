import { describe, expect, it } from 'vitest'
import {
  buildFigmaDesignDraftFromUrl,
  extractFigmaFileKeyAndNodeId
} from './figmaDesignImport'

describe('figmaDesignImport', () => {
  it('extracts file key and node id from figma url', () => {
    const result = extractFigmaFileKeyAndNodeId(
      'https://www.figma.com/design/AbCdEf123456/Login-Page?node-id=12-34&m=dev'
    )

    expect(result).toEqual({
      fileKey: 'AbCdEf123456',
      nodeId: '12:34'
    })
  })

  it('builds a draft import payload from figma url when api token is not configured', () => {
    const draft = buildFigmaDesignDraftFromUrl(
      'https://www.figma.com/design/AbCdEf123456/Login-Page?node-id=12-34&m=dev'
    )

    expect(draft.documentType).toBe('figma')
    expect(draft.text).toContain('Figma 设计链接')
    expect(draft.suggestedRequirementPoints).toContain('布局结构、组件层级、间距与对齐符合 Figma 设计稿')
    expect(draft.visualSummary?.componentHints).toContain('待通过 Figma API 提取组件树')
  })
})

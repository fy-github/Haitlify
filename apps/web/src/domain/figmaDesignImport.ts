import type { ParsedRequirementDocument, RequirementVisualSummary } from './requirementDocumentRepository'

export const extractFigmaFileKeyAndNodeId = (url: string): { fileKey: string; nodeId?: string } | null => {
  try {
    const parsed = new URL(url)
    if (!parsed.hostname.includes('figma.com')) return null
    const parts = parsed.pathname.split('/').filter(Boolean)
    const designIndex = parts.findIndex((part) => part === 'design' || part === 'file')
    const fileKey = designIndex >= 0 ? parts[designIndex + 1] : ''
    if (!fileKey) return null
    const rawNodeId = parsed.searchParams.get('node-id') || undefined
    return {
      fileKey,
      ...(rawNodeId ? { nodeId: rawNodeId.replace(/-/g, ':') } : {})
    }
  } catch {
    return null
  }
}

export const buildFigmaDesignDraftFromUrl = (url: string): ParsedRequirementDocument => {
  const meta = extractFigmaFileKeyAndNodeId(url)
  const visualSummary: RequirementVisualSummary = {
    width: 0,
    height: 0,
    aspectRatio: 0,
    viewportHint: 'unknown',
    layoutHints: ['待通过 Figma API 提取 Auto Layout 与 Frame 结构'],
    styleHints: ['待通过 Figma API 提取颜色、字体、圆角、阴影 token'],
    componentHints: ['待通过 Figma API 提取组件树'],
    ocrStatus: 'unavailable'
  }

  return {
    fileName: 'figma-link',
    documentType: 'figma',
    projectName: '默认项目',
    text: [
      `Figma 设计链接: ${url}`,
      meta?.fileKey ? `Figma File Key: ${meta.fileKey}` : '',
      meta?.nodeId ? `Figma Node ID: ${meta.nodeId}` : '',
      '当前未配置 Figma Token，已先生成设计解析草稿。'
    ].filter(Boolean).join('\n'),
    lineCount: 4,
    visualSummary,
    suggestedRequirementPoints: [
      '布局结构、组件层级、间距与对齐符合 Figma 设计稿',
      '颜色、字体、圆角、阴影等视觉样式符合设计规范',
      '组件状态与交互入口在设计稿覆盖的关键场景下表现一致'
    ]
  }
}

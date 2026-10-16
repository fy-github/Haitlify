import { describe, expect, it } from 'vitest'
import { buildRequirementImportText, type ParsedRequirementDocumentPayload } from './requirementDocumentPayload'

describe('requirementDocumentPayload', () => {
  it('combines parsed text and suggested requirement points for generation', () => {
    const payload: ParsedRequirementDocumentPayload = {
      fileName: 'requirements.png',
      documentType: 'image',
      projectName: '默认项目',
      text: '图片 OCR 未启用，请安装 tesseract 后重试: requirements.png',
      lineCount: 1,
      visualSummary: {
        width: 1440,
        height: 900,
        aspectRatio: 1.6,
        viewportHint: 'desktop',
        layoutHints: ['多栏桌面布局候选'],
        styleHints: ['浅色基调'],
        componentHints: ['导航区域', '主内容区'],
        ocrStatus: 'unavailable'
      },
      suggestedRequirementPoints: [
        '视觉样式与组件间距、对齐、层级符合设计稿',
        '桌面端主内容区布局稳定，核心操作区域无遮挡'
      ]
    }

    const text = buildRequirementImportText(payload)

    expect(text).toContain('图片 OCR 未启用，请安装 tesseract 后重试: requirements.png')
    expect(text).toContain('测试点: 视觉样式与组件间距、对齐、层级符合设计稿')
    expect(text).toContain('测试点: 桌面端主内容区布局稳定，核心操作区域无遮挡')
  })

  it('prefers structured design draft steps when design draft cases are provided', () => {
    const payload: ParsedRequirementDocumentPayload = {
      fileName: 'figma-link',
      documentType: 'figma',
      projectName: 'Haitlify',
      text: 'Figma 设计链接',
      lineCount: 1,
      suggestedRequirementPoints: [],
      designDraftCases: [
        {
          title: '设计稿-主按钮跳转到 Success / Home',
          module: '设计稿交互',
          pageName: '设计稿流程',
          caseType: '设计草案',
          steps: [
            'assertVisible Page / Login > Form / Login > Button / Primary',
            'click Page / Login > Form / Login > Button / Primary',
            'waitForUrl Success / Home',
            'assertUrl Success / Home'
          ]
        }
      ]
    }

    const text = buildRequirementImportText(payload)

    expect(text).toContain('### 用例: 设计稿-主按钮跳转到 Success / Home')
    expect(text).toContain('1. assertVisible Page / Login > Form / Login > Button / Primary')
    expect(text).toContain('4. assertUrl Success / Home')
  })
})

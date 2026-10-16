import type { ParsedRequirementDocument } from './requirementDocumentRepository'

export type ParsedRequirementDocumentPayload = ParsedRequirementDocument

export const buildRequirementImportText = (payload: ParsedRequirementDocumentPayload): string => {
  const lines: string[] = []
  if (payload.projectName) {
    lines.push(`项目: ${payload.projectName}`)
  }
  if (payload.text.trim()) {
    lines.push(payload.text.trim())
  }
  if (payload.visualSummary) {
    lines.push(`视觉布局: ${payload.visualSummary.layoutHints.join('、') || '待识别'}`)
    lines.push(`视觉样式: ${payload.visualSummary.styleHints.join('、') || '待识别'}`)
    lines.push(`组件线索: ${payload.visualSummary.componentHints.join('、') || '待识别'}`)
  }
  for (const draftCase of payload.designDraftCases || []) {
    lines.push(`### 用例: ${draftCase.title}`)
    lines.push(`- 模块: ${draftCase.module}`)
    lines.push(`- 页面: ${draftCase.pageName}`)
    lines.push(`- 用例类型: ${draftCase.caseType}`)
    draftCase.steps.forEach((step, index) => {
      lines.push(`${index + 1}. ${step}`)
    })
  }
  for (const point of payload.suggestedRequirementPoints || []) {
    lines.push(`测试点: ${point}`)
  }
  return dedupeLines(lines).join('\n')
}

const dedupeLines = (lines: string[]): string[] => {
  const seen = new Set<string>()
  const result: string[] = []
  for (const line of lines.map((item) => item.trim()).filter(Boolean)) {
    if (seen.has(line)) continue
    seen.add(line)
    result.push(line)
  }
  return result
}

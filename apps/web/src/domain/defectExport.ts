import type { DefectDraft } from './defectRepository'
import { validateDefectJson } from './defectEvidenceCodec'

export interface DefectListExportFilters {
  project: string
  status: string
  issueType: string
  priority: string
  assignee: string
  keyword: string
}

export interface DefectListJsonArtifact {
  kind: 'download'
  filename: string
  mimeType: 'application/json;charset=utf-8'
  content: string
}

/**
 * Build a lossless, JSON-only snapshot of the already frozen defect scope.
 * This is deliberately not the report credential sanitizer: defect evidence is
 * a local draft contract and must preserve additive evidence and explicit falsy
 * values. Sensitive request/auth data is outside DefectDraft's source shape.
 */
export const buildDefectListJsonArtifact = (
  drafts: DefectDraft[],
  filters: DefectListExportFilters,
  scope: 'selected' | 'filtered' = 'filtered',
  exportedAt = new Date().toISOString()
): DefectListJsonArtifact => {
  if (!drafts.length) throw new Error('当前没有可导出的缺陷草稿')
  const source = { drafts, filters, scope, exportedAt }
  validateDefectJson(source, 'defect-list-export')
  const snapshot = JSON.parse(JSON.stringify(source)) as {
    drafts: DefectDraft[]
    filters: DefectListExportFilters
    scope: string
    exportedAt: string
  }
  return {
    kind: 'download',
    filename: '缺陷草稿列表.json',
    mimeType: 'application/json;charset=utf-8',
    content: JSON.stringify({
      kind: 'ai-test-platform.defect-draft-list',
      version: 1,
      exportedAt: snapshot.exportedAt,
      scope: snapshot.scope,
      filters: snapshot.filters,
      drafts: snapshot.drafts
    }, null, 2)
  }
}

import { validateDefectJson } from './defectEvidenceCodec'
import { validateDefectDraftsForImport, type DefectDraft } from './defectRepository'
import { DefectPersistenceError } from './defectEvidenceError'

const IMPORT_KINDS = new Set(['ai-test-platform.defect-draft-list', 'ai-test-platform.defect-recovery'])

export const parseDefectImportArtifact = (text: string): DefectDraft[] => {
  let value: unknown
  try { value = JSON.parse(text) } catch { throw new DefectPersistenceError('invalid-data', 'confirmed-no-write', 'import', '导入文件不是有效 JSON') }
  validateDefectJson(value, 'import')
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new DefectPersistenceError('invalid-data', 'confirmed-no-write', 'import', '导入包格式无效')
  const root = value as Record<string, unknown>
  if (!IMPORT_KINDS.has(String(root.kind)) || root.version !== 1 || !Array.isArray(root.drafts)) {
    throw new DefectPersistenceError('incompatible-version', 'confirmed-no-write', 'import', '导入包类型或版本不支持')
  }
  return validateDefectDraftsForImport(root.drafts)
}

import type { WebAutomationRunResult, WebAutomationStepEvidence } from './webAutomation'
import { validateDefectJson } from './defectEvidenceCodec'
import { DefectPersistenceError, isDefectPersistenceError } from './defectEvidenceError'
import type { WebAutomationStep } from './webAutomationCase'

export interface DefectStepEvidence {
  stepId?: string; actionId?: string; actionType?: string; evidenceId?: string
  name?: string; action?: string; target?: string; status?: string
  screenshotPath?: string; pageUrl?: string; afterPageUrl?: string; capturedAt?: string
  before?: WebAutomationStepEvidence['before']; after?: WebAutomationStepEvidence['after']
  execution?: WebAutomationStepEvidence['execution']
  resolvedTarget?: Record<string, unknown> | string; locator?: Record<string, unknown> | string
  visible?: boolean; enabled?: boolean; receivesEvents?: boolean; unique?: boolean
  observationStatus?: 'passed' | 'failed'; failureType?: string
}
export interface DefectStepLog {
  stepId: string; phase?: string; name?: string; action?: string; target?: string; status?: string
  durationMs?: number; startedAt?: string; endedAt?: string; pageUrl?: string; errorMessage?: string; errorStack?: string
}
export interface DefectEvidence {
  evidenceVersion?: 1
  pageName?: string; startUrl?: string; durationMs?: number; steps?: WebAutomationStep[]
  failedStepId?: string; stepLogs?: DefectStepLog[]; stepEvidence?: DefectStepEvidence[]
  screenshotPath?: string; pageHtmlSnippet?: string; errorMessage?: string
  consoleErrors?: string[]; pageErrors?: string[]; failedRequests?: string[]
}
/** Historical reports can lack source page fields. They are not filled from display labels. */
export type DefectWebResultInput = Pick<WebAutomationRunResult, 'caseId' | 'sourceCaseId' | 'name' | 'module' | 'status'> & Omit<DefectEvidence, 'evidenceVersion'>
export const DEFECT_EVIDENCE_KEYS = ['pageName', 'startUrl', 'durationMs', 'steps', 'failedStepId', 'stepLogs', 'stepEvidence', 'screenshotPath', 'pageHtmlSnippet', 'errorMessage', 'consoleErrors', 'pageErrors', 'failedRequests'] as const
export const hasOwnEvidenceKey = (value: object, key: PropertyKey): boolean => Object.prototype.hasOwnProperty.call(value, key)

/** Source fields only; never collect request headers, authentication or execution environment. */
export const copyDefectEvidence = <T extends object>(source: T, operation = 'build-web', snapshot = true): Pick<T, Extract<keyof T, typeof DEFECT_EVIDENCE_KEYS[number]>> => {
  const selected: Record<string, unknown> = {}
  for (const key of DEFECT_EVIDENCE_KEYS) if (hasOwnEvidenceKey(source, key)) {
    const descriptor = Object.getOwnPropertyDescriptor(source, key)!
    if (!snapshot) { Object.defineProperty(selected, key, { ...descriptor, enumerable: true }); continue }
    if (!('value' in descriptor)) throw new DefectPersistenceError('invalid-data', 'confirmed-no-write', operation, '证据数据格式无效，未保存', `$.${key}`)
    selected[key] = descriptor.value
  }
  if (!snapshot) return selected as Pick<T, Extract<keyof T, typeof DEFECT_EVIDENCE_KEYS[number]>>
  try {
    validateDefectJson(selected, operation)
    return JSON.parse(JSON.stringify(selected))
  } catch (error) {
    if (isDefectPersistenceError(error)) throw error
    throw new DefectPersistenceError('invalid-data', 'confirmed-no-write', operation, '证据数据无法序列化，未保存')
  }
}

import { DefectPersistenceError } from './defectEvidenceError'
const own = (value: object, key: PropertyKey) => Object.prototype.hasOwnProperty.call(value, key)

const invalid = (operation: string, path: string): never => {
  throw new DefectPersistenceError('invalid-data', 'confirmed-no-write', operation, '证据数据格式无效，未保存', path)
}
export const requireEvidenceObject = (value: unknown, operation: string, path: string): Record<string, unknown> => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return invalid(operation, path)
  return value as Record<string, unknown>
}
/** Reject values JSON.stringify would silently erase/change, and never invoke getters/toJSON. */
export const validateDefectJson = (value: unknown, operation: string, path = '$', ancestors = new Set<object>()): void => {
  if (value === null || typeof value === 'string' || typeof value === 'boolean') return
  if (typeof value === 'number' && Number.isFinite(value)) return
  if (!value || typeof value !== 'object') return invalid(operation, path)
  if (ancestors.has(value)) return invalid(operation, path)
  const proto = Object.getPrototypeOf(value)
  if (!Array.isArray(value) && proto !== Object.prototype && proto !== null) return invalid(operation, path)
  ancestors.add(value)
  try {
    for (const key of Reflect.ownKeys(value)) {
      if (Array.isArray(value) && key === 'length') continue
      if (typeof key !== 'string') return invalid(operation, path)
      const descriptor = Object.getOwnPropertyDescriptor(value, key)!
      if (!descriptor.enumerable || !('value' in descriptor)) return invalid(operation, `${path}.${key}`)
      if (Array.isArray(value) && (!/^(0|[1-9]\d*)$/.test(key) || Number(key) >= value.length)) return invalid(operation, `${path}.${key}`)
      validateDefectJson(descriptor.value, operation, `${path}.${key}`, ancestors)
    }
    if (Array.isArray(value)) for (let i = 0; i < value.length; i++) if (!own(value, i)) return invalid(operation, `${path}.${i}`)
  } finally { ancestors.delete(value) }
}
const field = (record: Record<string, unknown>, key: string, type: 'string' | 'number' | 'boolean', op: string, path: string, required = false) => {
  if (!own(record, key)) { if (required) invalid(op, `${path}.${key}`); return }
  if (typeof record[key] !== type || (type === 'number' && !Number.isFinite(record[key]))) invalid(op, `${path}.${key}`)
}
const strings = (record: Record<string, unknown>, keys: string[], op: string, path: string, required = false) => keys.forEach(key => field(record, key, 'string', op, path, required))
const choice = (record: Record<string, unknown>, key: string, choices: string[], op: string, path: string) => {
  if (own(record, key) && !choices.includes(record[key] as string)) invalid(op, `${path}.${key}`)
}
const array = (record: Record<string, unknown>, key: string, op: string, path: string, each: (value: unknown, path: string) => void) => {
  if (!own(record, key)) return
  if (!Array.isArray(record[key])) return invalid(op, `${path}.${key}`)
  record[key].forEach((item, index) => each(item, `${path}.${key}.${index}`))
}
const flatStrings = ['stepId', 'actionId', 'actionType', 'evidenceId', 'name', 'action', 'target', 'status', 'screenshotPath', 'pageUrl', 'afterPageUrl', 'capturedAt']
const actions = ['manual', 'goto', 'click', 'input', 'select', 'hover', 'waitForVisible', 'waitForUrl', 'assertText', 'assertUrl', 'assertVisible', 'assertHidden', 'scrollIntoView', 'paginate', 'screenshot']
const runnerKeys = ['before', 'after', 'execution', 'resolvedTarget', 'locator', 'visible', 'enabled', 'receivesEvents', 'unique', 'observationStatus', 'failureType']
export type DefectEvidenceFormat = 'runner' | 'legacy-flat'
export const validateDefectEvidence = (value: object, op: string, format: DefectEvidenceFormat = 'runner', path = '$'): void => {
  const r = value as Record<string, unknown>
  strings(r, ['pageName', 'startUrl', 'failedStepId', 'screenshotPath', 'pageHtmlSnippet', 'errorMessage'], op, path)
  field(r, 'durationMs', 'number', op, path)
  for (const key of ['consoleErrors', 'pageErrors', 'failedRequests']) array(r, key, op, path, (item, p) => { if (typeof item !== 'string') invalid(op, p) })
  array(r, 'steps', op, path, (item, p) => {
    const step = requireEvidenceObject(item, op, p)
    strings(step, ['id', 'name', 'action', 'targetType', 'target'], op, p, true)
    strings(step, ['value', 'screenshot'], op, p); field(step, 'timeoutMs', 'number', op, p)
    choice(step, 'action', actions, op, p)
    choice(step, 'targetType', ['url', 'locator', 'elementRef'], op, p)
    choice(step, 'screenshot', ['none', 'on_failure', 'always'], op, p)
  })
  array(r, 'stepLogs', op, path, (item, p) => {
    const log = requireEvidenceObject(item, op, p)
    strings(log, ['stepId'], op, p, true)
    strings(log, ['phase', 'name', 'action', 'target', 'status', 'startedAt', 'endedAt', 'pageUrl', 'errorMessage', 'errorStack'], op, p)
    field(log, 'durationMs', 'number', op, p, format === 'runner')
    if (format === 'runner') {
      strings(log, ['name', 'action', 'target', 'status'], op, p, true)
      choice(log, 'status', ['passed', 'failed'], op, p)
      choice(log, 'phase', ['setup', 'test', 'teardown'], op, p)
      choice(log, 'action', actions, op, p)
    }
  })
  array(r, 'stepEvidence', op, path, (item, p) => {
    const ev = requireEvidenceObject(item, op, p)
    strings(ev, flatStrings, op, p)
    const isRunner = format === 'runner' || runnerKeys.some(key => own(ev, key))
    if (!isRunner) {
      if (!flatStrings.some(key => own(ev, key))) invalid(op, p)
      return
    }
    strings(ev, ['actionId', 'actionType', 'evidenceId'], op, p, true)
    for (const key of ['before', 'after']) strings(requireEvidenceObject(ev[key], op, `${p}.${key}`), ['pageUrl', 'pageKey', 'pageFingerprint'], op, `${p}.${key}`, true)
    const execution = requireEvidenceObject(ev.execution, op, `${p}.execution`)
    strings(execution, ['status'], op, `${p}.execution`, true)
    choice(execution, 'status', ['passed', 'failed'], op, `${p}.execution`)
    field(execution, 'durationMs', 'number', op, `${p}.execution`, true)
    for (const key of ['resolvedTarget', 'locator']) if (own(ev, key) && typeof ev[key] !== 'string') requireEvidenceObject(ev[key], op, `${p}.${key}`)
    for (const key of ['visible', 'enabled', 'receivesEvents', 'unique']) field(ev, key, 'boolean', op, p)
    strings(ev, ['observationStatus', 'failureType'], op, p)
    choice(ev, 'observationStatus', ['passed', 'failed'], op, p)
  })
}

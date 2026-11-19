import { describe, expect, it } from 'vitest'
import { buildDefectDraftsFromWebAutomationResults } from './defectRepository'
import { source, runner, options } from './defectEvidence.testFixtures'

const build = (patch: Record<string, unknown>) => buildDefectDraftsFromWebAutomationResults([{ ...source, ...patch } as typeof source], options)

describe('strict JSON evidence boundary', () => {
  it.each([
    ['missing execution', { ...runner, execution: undefined }],
    ['bad before', { ...runner, before: { pageUrl: '' } }],
    ['bad status', { ...runner, execution: { status: 'unknown', durationMs: 0 } }],
    ['bad duration', { ...runner, execution: { status: 'failed', durationMs: Infinity } }],
    ['bad known optional', { ...runner, visible: 0 }],
    ['bad nested target', { ...runner, resolvedTarget: { handle: () => 0 } }],
    ['unknown non-json', { ...runner, future: undefined }],
    ['unknown symbol', { ...runner, future: Symbol('x') }],
    ['unknown bigint', { ...runner, future: BigInt(1) }],
    ['unknown date', { ...runner, future: new Date() }],
    ['unknown sparse array', { ...runner, future: new Array(1) }],
    ['unknown object symbol key', { ...runner, future: { [Symbol('x')]: 1 } }],
    ['unknown non-enumerable key', { ...runner, future: Object.defineProperty({}, 'hidden', { value: 1 }) }],
    ['unknown proxy', { ...runner, future: new Proxy({}, { ownKeys() { throw new Error('secret proxy') } }) }],
    ['unknown getter', Object.defineProperty({ ...runner }, 'future', { enumerable: true, get() { throw new Error('must not execute getter') } })],
    ['null', null], ['array', []], ['empty flat', {}], ['flat not explicitly opted in', { name: '', action: '', target: '', status: '' }]
  ])('rejects %s, not silently omitting or projecting', (_name, item) => {
    expect(() => build({ stepEvidence: [item] })).toThrowError(expect.objectContaining({ code: 'invalid-data', outcome: 'confirmed-no-write', operation: 'build-web' }))
  })
  it.each([
    ['null scalar', { errorMessage: null }], ['bad diagnostics', { pageErrors: [false] }],
    ['bad step', { steps: [{ id: 's', action: 0 }] }], ['bad log', { stepLogs: [{ stepId: 0 }] }],
    ['bad step action', { steps: [{ ...source.steps[0], action: 'executeScript' }] }],
    ['bad log status', { stepLogs: [{ ...source.stepLogs[0], status: 'unknown' }] }],
    ['bad log phase', { stepLogs: [{ ...source.stepLogs[0], phase: 'unknown' }] }],
    ['missing required log fields', { stepLogs: [{ stepId: 's' }] }],
    ['explicit undefined', { screenshotPath: undefined }], ['nan', { durationMs: NaN }]
  ])('rejects %s at the complete result boundary', (_name, patch) => {
    expect(() => build(patch)).toThrowError(expect.objectContaining({ code: 'invalid-data', outcome: 'confirmed-no-write' }))
  })
  it('rejects cyclic unknown evidence without invoking a serializer', () => {
    const cycle: Record<string, unknown> = {}; cycle.self = cycle
    expect(() => build({ stepEvidence: [{ ...runner, cycle }] })).toThrowError(expect.objectContaining({ code: 'invalid-data' }))
  })
  it('rejects the entire build if a later result is invalid', () => {
    expect(() => buildDefectDraftsFromWebAutomationResults([source, { ...source, stepEvidence: [null] } as unknown as typeof source], options)).toThrowError(expect.objectContaining({ code: 'invalid-data' }))
  })
})

import { describe, expect, it } from 'vitest'
import { buildTaskInputs, canLaunch, inputLabel, listValueOf, pickerKindFor, setListValue, suggestTaskTypes } from './aiAssistantRouting'
import type { AiTaskDefinition } from './aiAssistantRepository'

const task = (taskType: string, requiredInputs: string[] = []): AiTaskDefinition => ({
  taskType,
  displayName: taskType,
  requiredInputs,
  evidenceTypes: [],
  toolAllowlist: [],
  humanGateRequired: true,
  artifactTarget: ''
})

const catalog: AiTaskDefinition[] = [
  task('requirement_analysis', ['documentIds', 'targetCaseKind']),
  task('testcase_generation', ['draftPointIds', 'caseKind']),
  task('bug_analysis', ['symptom']),
  task('log_triage', ['logExcerpt', 'timeRange']),
  task('sql_analysis', ['sqlText']),
  task('regression_planning', ['changeSummary']),
  task('report_draft', ['sourceRunIds']),
  task('prompt_evaluation', ['promptVersion', 'samples'])
]

describe('aiAssistantRouting', () => {
  it('ranks the keyword hits first while keeping the full catalog selectable', () => {
    const bugFirst = suggestTaskTypes('设备详情页偶发白屏，怀疑是空指针', catalog)
    expect(bugFirst[0]?.taskType).toBe('bug_analysis')
    expect(bugFirst).toHaveLength(catalog.length)

    const logFirst = suggestTaskTypes('看一下网关日志里的 502 超时', catalog)
    expect(logFirst[0]?.taskType).toBe('log_triage')
  })

  it('falls back to the catalog order when nothing matches', () => {
    const suggested = suggestTaskTypes('随便聊聊', catalog)
    expect(suggested.map((entry) => entry.taskType)).toEqual(catalog.map((entry) => entry.taskType))
  })

  it('labels known inputs in Chinese and never invents labels for unknown fields', () => {
    expect(inputLabel('symptom')).toBe('现象描述')
    expect(inputLabel('mysteryField')).toBe('mysteryField')
  })

  it('builds inputs with comma-split lists and drops empty values', () => {
    const requirement = catalog[0] as AiTaskDefinition
    const inputs = buildTaskInputs(requirement, {
      documentIds: 'doc-1, doc-2 ,doc-3',
      targetCaseKind: '  接口 ',
      mystery: 'ignored'
    })
    expect(inputs.documentIds).toEqual(['doc-1', 'doc-2', 'doc-3'])
    expect(inputs.targetCaseKind).toBe('接口')
    expect(inputs).not.toHaveProperty('mystery')

    const empty = buildTaskInputs(task('bug_analysis', ['symptom']), { symptom: '   ' })
    expect(empty).toEqual({})
  })

  it('maps list inputs to their directory pickers and round-trips values', () => {
    expect(pickerKindFor('documentIds')).toBe('documents')
    expect(pickerKindFor('sourceRunIds')).toBe('recent-runs')
    expect(pickerKindFor('draftPointIds')).toBe('draft-points')
    expect(pickerKindFor('symptom')).toBeUndefined()

    const values: Record<string, string> = { documentIds: 'doc-1, doc-2' }
    expect(listValueOf(values, 'documentIds')).toEqual(['doc-1', 'doc-2'])
    setListValue(values, 'documentIds', ['doc-3', ' ', 'doc-4'])
    expect(values.documentIds).toBe('doc-3,doc-4')
    expect(listValueOf(values, 'missing')).toEqual([])
  })

  it('keeps launch gated on an explicit task selection only', () => {
    expect(canLaunch('bug_analysis')).toBe(true)
    expect(canLaunch('   ')).toBe(false)
  })
})

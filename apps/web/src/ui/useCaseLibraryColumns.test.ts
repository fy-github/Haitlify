// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest'
import type { CaseField } from '../domain/caseTemplates'
import { interfaceCaseFields, webAutomationCaseFields } from '../domain/caseTemplates'
import {
  CASE_LIBRARY_COLUMN_STORAGE_PREFIX,
  caseLibraryPreferenceOwner,
  createCaseLibraryColumns,
  requiredLibraryColumnKeys
} from './useCaseLibraryColumns'

const field = (key: string, label: string, visible = true): CaseField => ({
  key,
  label,
  type: 'text',
  required: false,
  visible,
  exportable: true,
  aiGenerated: false
})

const create = (userId = 'user-a', storage: Storage | null = window.localStorage) =>
  createCaseLibraryColumns({
    storage,
    userId,
    fieldsFor: (kind) => (kind === 'interface' ? interfaceCaseFields : webAutomationCaseFields)
  })

describe('case library column preferences', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('starts from the readable default column set, with every other visible field addable', () => {
    const columns = create()
    // 列顺序仍是模板顺序,这里按集合比较默认列集合
    expect([...columns.appliedKeys('interface')].sort()).toEqual(
      ['caseId', 'title', 'project', 'module', 'caseType', 'priority', 'executionResult'].sort()
    )
    // 其余可见字段不是被白名单丢掉,而是可由个人偏好补回
    const offered = columns.selectableFields('interface').map((item) => item.key)
    expect(offered).toContain('method')
    expect(offered).toContain('path')
    expect(offered).toContain('remark')
    expect(offered).toHaveLength(interfaceCaseFields.filter((item) => item.visible).length)
  })

  it('only persists the applied category after Apply', () => {
    const columns = create()
    columns.openDialog('interface')
    columns.toggleDraft('project', false)
    expect(columns.appliedKeys('interface')).toContain('project')
    expect(localStorage.getItem(`${CASE_LIBRARY_COLUMN_STORAGE_PREFIX}:user-a:interface`)).toBeNull()

    columns.applyDraft()
    expect(columns.appliedKeys('interface')).not.toContain('project')
    expect(JSON.parse(localStorage.getItem(`${CASE_LIBRARY_COLUMN_STORAGE_PREFIX}:user-a:interface`) || '[]')).not.toContain('project')
    // Web 偏好独立:接口的改动不能顺手改掉 Web
    expect(localStorage.getItem(`${CASE_LIBRARY_COLUMN_STORAGE_PREFIX}:user-a:web_automation`)).toBeNull()
    expect(columns.appliedKeys('web_automation')).toContain('project')

    expect(create().appliedKeys('interface')).not.toContain('project')
  })

  it('discards the draft on Cancel', () => {
    const columns = create()
    columns.openDialog('interface')
    columns.toggleDraft('method', true)
    columns.toggleDraft('cases', true)
    columns.cancelDraft()

    expect(columns.appliedKeys('interface')).not.toContain('method')
    expect(columns.draftKeys('interface')).toEqual(columns.appliedKeys('interface'))
    expect(localStorage.getItem(`${CASE_LIBRARY_COLUMN_STORAGE_PREFIX}:user-a:interface`)).toBeNull()
  })

  it('keeps restore-default staged until Apply', () => {
    const columns = create()
    columns.openDialog('interface')
    columns.toggleDraft('project', false)
    columns.applyDraft()

    columns.openDialog('interface')
    columns.restoreDefaultDraft()
    expect(columns.draftKeys('interface')).toContain('project')
    expect(columns.appliedKeys('interface')).not.toContain('project')

    columns.applyDraft()
    expect(columns.appliedKeys('interface')).toContain('project')
  })

  it('always keeps required columns visible, on Apply and on load', () => {
    const columns = create()
    expect(requiredLibraryColumnKeys('interface')).toEqual(['caseId', 'title'])

    columns.openDialog('interface')
    columns.toggleDraft('title', false)
    expect(columns.draftKeys('interface')).toContain('title')

    localStorage.setItem(
      `${CASE_LIBRARY_COLUMN_STORAGE_PREFIX}:user-a:interface`,
      JSON.stringify(['module', 'remark', 'ghostColumn'])
    )
    const reloaded = create()
    expect(reloaded.appliedKeys('interface')).toEqual(expect.arrayContaining(['caseId', 'title']))
    expect(reloaded.appliedKeys('interface')).not.toContain('ghostColumn')
  })

  it('keeps each person’s preference separate', () => {
    const mine = create('user-a')
    mine.openDialog('interface')
    mine.toggleDraft('project', false)
    mine.applyDraft()

    expect(create('user-b').appliedKeys('interface')).toContain('project')
    expect(create('user-a').appliedKeys('interface')).not.toContain('project')
  })

  it('falls back to defaults when storage is unavailable or corrupt', () => {
    const throwing = {
      getItem: () => { throw new Error('denied') },
      setItem: () => { throw new Error('denied') },
      removeItem: () => { throw new Error('denied') }
    } as unknown as Storage
    const columns = create(undefined as unknown as string, throwing)
    expect(() => columns.openDialog('interface')).not.toThrow()
    expect(columns.appliedKeys('interface')).toContain('title')

    localStorage.setItem(`${CASE_LIBRARY_COLUMN_STORAGE_PREFIX}:user-a:interface`, '{not json')
    expect(create().appliedKeys('interface')).toContain('title')
  })

  it('ignores fields the template hides entirely', () => {
    const columns = createCaseLibraryColumns({
      storage: window.localStorage,
      userId: 'user-a',
      fieldsFor: (kind) => kind === 'interface'
        ? [field('caseId', '用例ID'), field('title', '用例标题'), field('remark', '备注', false)]
        : webAutomationCaseFields
    })
    expect(columns.selectableFields('interface').map((item) => item.key)).toEqual(['caseId', 'title'])
    expect(columns.visibleFields('interface').map((item) => item.key)).toEqual(['caseId', 'title'])
  })

  it('keys preferences by the authenticated workspace owner', () => {
    expect(caseLibraryPreferenceOwner(window.localStorage)).toBe('anonymous')

    window.localStorage.setItem('ai-test-platform.workspace.user%3A7.template-config', '{}')
    expect(caseLibraryPreferenceOwner(window.localStorage)).toBe('user:7')

    const columns = createCaseLibraryColumns({
      storage: window.localStorage,
      userId: caseLibraryPreferenceOwner(window.localStorage),
      fieldsFor: (kind) => (kind === 'interface' ? interfaceCaseFields : webAutomationCaseFields)
    })
    columns.openDialog('interface')
    columns.toggleDraft('project', false)
    columns.applyDraft()
    expect(window.localStorage.getItem(`${CASE_LIBRARY_COLUMN_STORAGE_PREFIX}:user%3A7:interface`)).toBeTruthy()
  })
})

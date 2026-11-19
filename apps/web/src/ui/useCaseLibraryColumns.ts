import { computed, reactive, ref } from 'vue'
import type { CaseField } from '../domain/caseTemplates'

export type LibraryColumnKind = 'interface' | 'web_automation'

/** 版本化的「个人 × 类别」可见列偏好;接口与 Web 各存一份,互不影响。 */
export const CASE_LIBRARY_COLUMN_STORAGE_PREFIX = 'ai-test-platform.case-library.columns.v1'

export interface CaseLibraryColumnsDeps {
  storage: Storage | null
  userId: string
  fieldsFor: (kind: LibraryColumnKind) => CaseField[]
}

/** ID 与标题是识别列,不允许被隐藏;详情里仍可见全部字段。 */
const REQUIRED_COLUMN_KEYS: Record<LibraryColumnKind, string[]> = {
  interface: ['caseId', 'title'],
  web_automation: ['caseId', 'title']
}

/**
 * 默认表格列 = 一屏可读的常用列(与设计稿「显示列 6」一致,接口表 1280px 宽)。
 * 其余可见字段不是被白名单丢弃:都能在「显示列」里按个人偏好补回。
 */
const DEFAULT_COLUMN_KEYS: Record<LibraryColumnKind, string[]> = {
  interface: ['caseId', 'title', 'project', 'module', 'caseType', 'priority', 'executionResult'],
  web_automation: ['caseId', 'title', 'project', 'module', 'pageName', 'caseType', 'startUrl', 'browser', 'viewport']
}

export const requiredLibraryColumnKeys = (kind: LibraryColumnKind): string[] =>
  [...REQUIRED_COLUMN_KEYS[kind]]

const storageKeyFor = (userId: string, kind: LibraryColumnKind): string =>
  `${CASE_LIBRARY_COLUMN_STORAGE_PREFIX}:${encodeURIComponent(userId || 'anonymous')}:${kind}`

const WORKSPACE_KEY_PREFIX = 'ai-test-platform.workspace.'

/**
 * 个人偏好的身份:优先复用工作区已激活的账号(工作区键前缀里带着 owner),
 * 未登录/未写入任何工作区数据时退化为 anonymous。只读扫描,不改动工作区存储。
 */
export const caseLibraryPreferenceOwner = (storage: Storage | null | undefined): string => {
  try {
    for (let index = 0; index < (storage?.length ?? 0); index += 1) {
      const key = storage?.key(index) || ''
      if (!key.startsWith(WORKSPACE_KEY_PREFIX)) continue
      const rest = key.slice(WORKSPACE_KEY_PREFIX.length)
      const separator = rest.indexOf('.')
      if (separator <= 0) continue
      const owner = decodeURIComponent(rest.slice(0, separator))
      if (owner) return owner
    }
  } catch {
    // 扫描失败按匿名处理,不影响页面。
  }
  return 'anonymous'
}

export const createCaseLibraryColumns = (deps: CaseLibraryColumnsDeps) => {
  const fieldsFor = (kind: LibraryColumnKind): CaseField[] => deps.fieldsFor(kind).filter((item) => item.visible)
  const defaultKeys = (kind: LibraryColumnKind): string[] =>
    normalize(kind, [...DEFAULT_COLUMN_KEYS[kind], ...requiredLibraryColumnKeys(kind)])
  const knownKeys = (kind: LibraryColumnKind): Set<string> => new Set(fieldsFor(kind).map((item) => item.key))

  /** 只接受模板里真实存在、当前可见的字段;必需列永远补齐。 */
  const normalize = (kind: LibraryColumnKind, keys: string[] | null | undefined): string[] => {
    const known = knownKeys(kind)
    const required = new Set(requiredLibraryColumnKeys(kind))
    const accepted = new Set((keys || []).filter((key) => known.has(key)))
    for (const key of required) accepted.add(key)
    return fieldsFor(kind).map((item) => item.key).filter((key) => accepted.has(key))
  }

  const read = (kind: LibraryColumnKind): string[] => {
    try {
      const raw = deps.storage?.getItem(storageKeyFor(deps.userId, kind))
      if (!raw) return defaultKeys(kind)
      const parsed: unknown = JSON.parse(raw)
      if (!Array.isArray(parsed)) return defaultKeys(kind)
      return normalize(kind, parsed.filter((item): item is string => typeof item === 'string'))
    } catch {
      return defaultKeys(kind)
    }
  }

  const write = (kind: LibraryColumnKind, keys: string[]): void => {
    try {
      deps.storage?.setItem(storageKeyFor(deps.userId, kind), JSON.stringify(keys))
    } catch {
      // 存储不可用时只在内存里生效,不阻塞界面。
    }
  }

  const applied = reactive<Record<LibraryColumnKind, string[]>>({
    interface: read('interface'),
    web_automation: read('web_automation')
  })
  const draft = ref<Record<LibraryColumnKind, string[]>>({
    interface: [...applied.interface],
    web_automation: [...applied.web_automation]
  })
  const activeKind = ref<LibraryColumnKind>('interface')
  const dialogVisible = ref(false)

  const appliedKeys = (kind: LibraryColumnKind): string[] => [...applied[kind]]
  const draftKeys = (kind: LibraryColumnKind): string[] => [...draft.value[kind]]
  const isRequired = (kind: LibraryColumnKind, key: string): boolean =>
    requiredLibraryColumnKeys(kind).includes(key)
  const isDraftVisible = (key: string): boolean => draft.value[activeKind.value].includes(key)
  const isDraftDirty = computed(() => {
    const current = draft.value[activeKind.value]
    const base = applied[activeKind.value]
    return current.length !== base.length || current.some((key, index) => key !== base[index])
  })

  /** 可选的全部可见字段(显示列对话框用),不是表格当前列。 */
  const selectableFields = (kind: LibraryColumnKind): CaseField[] => fieldsFor(kind)

  /** 已生效的字段,保持模板顺序;表格列从这一份派生。 */
  const visibleFields = (kind: LibraryColumnKind): CaseField[] => {
    const keys = new Set(applied[kind])
    return fieldsFor(kind).filter((item) => keys.has(item.key))
  }

  const openDialog = (kind: LibraryColumnKind): void => {
    activeKind.value = kind
    draft.value = { ...draft.value, [kind]: [...applied[kind]] }
    dialogVisible.value = true
  }

  const cancelDraft = (): void => {
    draft.value = { ...draft.value, [activeKind.value]: [...applied[activeKind.value]] }
    dialogVisible.value = false
  }

  const toggleDraft = (key: string, visible: boolean): void => {
    const kind = activeKind.value
    if (isRequired(kind, key) || !knownKeys(kind).has(key)) return
    const current = new Set(draft.value[kind])
    if (visible) current.add(key)
    else current.delete(key)
    draft.value = { ...draft.value, [kind]: normalize(kind, [...current]) }
  }

  const restoreDefaultDraft = (): void => {
    draft.value = { ...draft.value, [activeKind.value]: defaultKeys(activeKind.value) }
  }

  /** 只落当前类别的草稿;另一类别的偏好保持不变。 */
  const applyDraft = (): void => {
    const kind = activeKind.value
    applied[kind] = normalize(kind, draft.value[kind])
    write(kind, applied[kind])
    dialogVisible.value = false
  }

  return {
    dialogVisible,
    activeKind,
    isDraftDirty,
    openDialog,
    cancelDraft,
    toggleDraft,
    restoreDefaultDraft,
    applyDraft,
    draftKeys,
    appliedKeys,
    visibleFields,
    selectableFields,
    isRequired,
    isDraftVisible
  }
}

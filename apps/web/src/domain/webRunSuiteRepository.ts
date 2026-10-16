export const WEB_RUN_SUITE_STORAGE_KEY = 'ai-test-platform.web-run-suites.v1'

/** 阶段 7 增量 5：回归套件 = 一次「范围 + 过滤」快照（保存后可一键恢复筛选，不复制用例数据）。 */
export interface SavedWebRunSuite {
  name: string
  project: string
  module: string
  source: 'ALL' | 'design' | 'default'
  status: string
  keyword: string
  savedAt: string
}

const normalizeSuite = (value: unknown): SavedWebRunSuite | undefined => {
  if (!value || typeof value !== 'object') return undefined
  const record = value as Record<string, unknown>
  const name = typeof record.name === 'string' ? record.name.trim() : ''
  if (!name) return undefined
  const source = record.source === 'design' || record.source === 'default' ? record.source : 'ALL'
  return {
    name,
    project: String(record.project || 'ALL'),
    module: String(record.module || 'ALL'),
    source,
    status: String(record.status || 'ALL'),
    keyword: String(record.keyword || ''),
    savedAt: String(record.savedAt || '')
  }
}

const readSuites = (storage: Storage): SavedWebRunSuite[] => {
  try {
    const raw = storage.getItem(WEB_RUN_SUITE_STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.map(normalizeSuite).filter((item): item is SavedWebRunSuite => Boolean(item))
  } catch {
    return []
  }
}

const writeSuites = (storage: Storage, suites: SavedWebRunSuite[]) => {
  storage.setItem(WEB_RUN_SUITE_STORAGE_KEY, JSON.stringify(suites))
}

export const listWebRunSuites = (storage: Storage): SavedWebRunSuite[] =>
  readSuites(storage).sort((left, right) => right.savedAt.localeCompare(left.savedAt))

/** 同名覆盖（幂等保存），返回新列表。 */
export const saveWebRunSuite = (storage: Storage, suite: SavedWebRunSuite): SavedWebRunSuite[] => {
  const next = readSuites(storage).filter((item) => item.name !== suite.name)
  next.push({ ...suite, name: suite.name.trim() })
  writeSuites(storage, next)
  return listWebRunSuites(storage)
}

export const deleteWebRunSuite = (storage: Storage, name: string): SavedWebRunSuite[] => {
  writeSuites(storage, readSuites(storage).filter((item) => item.name !== name))
  return listWebRunSuites(storage)
}

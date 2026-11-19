import type { GeneratedInterfaceCase } from './caseGenerator'
import {
  deleteSavedCases,
  listSavedCases,
  replaceSavedCasesForProject,
  saveGeneratedCases,
  type SaveCasesResult,
  type SavedInterfaceCase
} from './caseRepository'
import {
  defaultTemplateConfig,
  loadTemplateConfigMeta,
  loadTemplateConfig,
  saveTemplateConfig,
  serializeTemplateTarget,
  type TemplateConfig
} from './templateConfigRepository'
import { joinBackendPath } from './backendBaseUrl'
import { authedFetch } from './http'

type FetchLike = (input: string, init?: RequestInit) => Promise<Response>

export interface PlatformBackendOptions {
  baseUrl?: string
  fetchImpl?: FetchLike
  conflictStrategy?: 'protectLocal' | 'preferBackend'
  throwOnError?: boolean
}

export class PlatformBackendRequestError extends Error {
  constructor(public readonly status: number, path: string) {
    super(`Backend request failed: ${status} (${path})`)
    this.name = 'PlatformBackendRequestError'
  }
}

export class PlatformBackendTransportError extends Error {
  public readonly originalError: unknown

  constructor(path: string, originalError: unknown) {
    super(`Backend transport failed (${path})`)
    this.name = 'PlatformBackendTransportError'
    this.originalError = originalError
  }
}

export interface BackendProject {
  id: number
  tenantId?: number | null
  code: string
  name: string
}

/** Read-only context discovery; unlike ensureBackendProject this never creates a project. */
export const listBackendProjects = async (options: PlatformBackendOptions = {}): Promise<BackendProject[]> => {
  const response = await requestJson<{ items: BackendProject[] }>(options, '/api/projects')
  if (!response || !Array.isArray(response.items) || response.items.some(item => !item || typeof item !== 'object')) {
    throw new Error('项目列表响应无效')
  }
  return response.items
}

export interface BackendSyncResult<T> {
  source: BackendSyncSource
  value?: T
}

type BackendSyncSource = 'backend' | 'local' | 'conflict'

export interface TemplateConfigSyncResult {
  source: BackendSyncSource
  config: TemplateConfig
}

export interface SaveCasesSyncResult {
  source: BackendSyncSource
  result: SaveCasesResult
}

export interface DeleteCasesSyncResult {
  source: BackendSyncSource
  deleted: number
}

export interface LoadSavedCasesSyncResult {
  source: BackendSyncSource
  cases: SavedInterfaceCase[]
}

const ensuringProjects = new Map<string, Promise<BackendProject>>()
const fetchIdentity = new WeakMap<FetchLike, number>()
let fetchIdentitySeed = 0

export const projectCodeFromName = (projectName: string): string => {
  const ascii = projectName
    .normalize('NFKD')
    .replace(/[^\w]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .toUpperCase()

  return ascii || 'PROJECT'
}

export const ensureBackendProject = async (
  projectName: string,
  options: PlatformBackendOptions = {}
): Promise<BackendProject> => {
  const cacheKey = `${options.baseUrl || ''}::${projectName}::${fetchCacheKey(options.fetchImpl)}`
  const pending = ensuringProjects.get(cacheKey)
  if (pending) return pending

  const task = ensureBackendProjectInternal(projectName, options)
  ensuringProjects.set(cacheKey, task)

  try {
    return await task
  } finally {
    ensuringProjects.delete(cacheKey)
  }
}

const ensureBackendProjectInternal = async (
  projectName: string,
  options: PlatformBackendOptions = {}
): Promise<BackendProject> => {
  const code = projectCodeFromName(projectName)
  const existing = await findBackendProject(projectName, code, options)
  if (existing) return existing

  try {
    return await requestJson<BackendProject>(options, '/api/projects', {
      method: 'POST',
      headers: jsonHeaders(),
      body: JSON.stringify({
        code,
        name: projectName,
        description: `${projectName} API test workspace`
      })
    })
  } catch (error) {
    if (options.throwOnError && (error instanceof PlatformBackendRequestError || error instanceof PlatformBackendTransportError)) throw error
    const created = await findBackendProject(projectName, code, options)
    if (created) return created
    throw new Error(`Backend project ensure failed: ${projectName}`)
  }
}

export const loadTemplateConfigWithBackend = async (
  storage: Storage,
  projectName: string,
  options: PlatformBackendOptions = {}
): Promise<TemplateConfigSyncResult> => {
  try {
    const project = await ensureBackendProject(projectName, options)
    const response = await requestJson<BackendTemplateConfig>(
      options,
      `/api/projects/${project.id}/case-template/interface`
    )
    const config = toTemplateConfig(response)
    const localConfig = loadTemplateConfig(storage)
    if (
      shouldProtectLocal(options)
      && isLocalNewer(loadTemplateConfigMeta(storage).updatedAt, response.updatedAt)
      && templateConfigFingerprint(localConfig) !== templateConfigFingerprint(config)
    ) {
      return { source: 'conflict', config: localConfig }
    }
    saveTemplateConfig(storage, config, { updatedAt: response.updatedAt })
    return { source: 'backend', config }
  } catch (error) {
    if (options.throwOnError && (error instanceof PlatformBackendRequestError || error instanceof PlatformBackendTransportError)) throw error
    return { source: 'local', config: loadTemplateConfig(storage) }
  }
}

export const saveTemplateConfigWithBackend = async (
  storage: Storage,
  projectName: string,
  config: TemplateConfig,
  options: PlatformBackendOptions = {}
): Promise<TemplateConfigSyncResult> => {
  saveTemplateConfig(storage, config)

  try {
    const project = await ensureBackendProject(projectName, options)
    const response = await requestJson<BackendTemplateConfig>(
      options,
      `/api/projects/${project.id}/case-template/interface`,
      {
        method: 'PUT',
        headers: jsonHeaders(),
        body: JSON.stringify(config)
      }
    )
    const savedConfig = toTemplateConfig(response)
    saveTemplateConfig(storage, savedConfig, { updatedAt: response.updatedAt })
    return { source: 'backend', config: savedConfig }
  } catch {
    return { source: 'local', config }
  }
}

export const saveGeneratedCasesWithBackend = async (
  storage: Storage,
  projectName: string,
  cases: GeneratedInterfaceCase[],
  options: PlatformBackendOptions = {}
): Promise<SaveCasesSyncResult> => {
  const localResult = saveGeneratedCases(storage, cases)

  try {
    const project = await ensureBackendProject(projectName, options)
    const savedCases = selectSavedCasesForGeneratedCases(storage, cases)
    const response = await requestJson<SaveCasesResult>(
      options,
      `/api/projects/${project.id}/interface-cases`,
      {
        method: 'POST',
        headers: jsonHeaders(),
        body: JSON.stringify({ cases: savedCases })
      }
    )
    return { source: 'backend', result: response }
  } catch {
    return { source: 'local', result: localResult }
  }
}

export const loadSavedCasesWithBackend = async (
  storage: Storage,
  projectName: string,
  options: PlatformBackendOptions = {}
): Promise<LoadSavedCasesSyncResult> => {
  try {
    const project = await ensureBackendProject(projectName, options)
    const response = await requestJson<BackendInterfaceCaseList>(
      options,
      `/api/projects/${project.id}/interface-cases`
    )
    if (response.items.length === 0) {
      return { source: 'local', cases: listSavedCases(storage) }
    }
    if (
      shouldProtectLocal(options)
      && isLocalNewer(latestSavedAt(projectSavedCases(storage, projectName)), latestSavedAt(response.items))
    ) {
      return { source: 'conflict', cases: listSavedCases(storage) }
    }

    replaceSavedCasesForProject(storage, projectName, response.items)
    return { source: 'backend', cases: listSavedCases(storage) }
  } catch (error) {
    if (options.throwOnError && (error instanceof PlatformBackendRequestError || error instanceof PlatformBackendTransportError)) throw error
    return { source: 'local', cases: listSavedCases(storage) }
  }
}

export const deleteSavedCasesWithBackend = async (
  storage: Storage,
  cases: SavedInterfaceCase[],
  options: PlatformBackendOptions = {}
): Promise<DeleteCasesSyncResult> => {
  const deleted = deleteSavedCases(storage, cases.map((testCase) => testCase.libraryId))
  if (cases.length === 0) return { source: 'local', deleted }

  try {
    for (const [projectName, projectCases] of groupSavedCasesByProject(cases)) {
      const project = await ensureBackendProject(projectName, options)
      await requestJson<{ deleted: number; total: number }>(
        options,
        `/api/projects/${project.id}/interface-cases/delete`,
        {
          method: 'POST',
          headers: jsonHeaders(),
          body: JSON.stringify({
            libraryIds: projectCases.map((testCase) => testCase.libraryId)
          })
        }
      )
    }
    return { source: 'backend', deleted }
  } catch {
    return { source: 'local', deleted }
  }
}

export const syncCaseOrderingWithBackend = async (
  storage: Storage,
  projectName: string,
  options: PlatformBackendOptions = {}
): Promise<BackendSyncResult<SaveCasesResult>> => {
  const projectCases = listSavedCases(storage).filter((testCase) => projectNameForSavedCase(testCase) === projectName)

  try {
    const project = await ensureBackendProject(projectName, options)
    const response = await requestJson<SaveCasesResult>(
      options,
      `/api/projects/${project.id}/interface-cases`,
      {
        method: 'POST',
        headers: jsonHeaders(),
        body: JSON.stringify({ cases: projectCases })
      }
    )
    return { source: 'backend', value: response }
  } catch {
    return { source: 'local' }
  }
}

export const syncSavedCaseProjectsWithBackend = async (
  storage: Storage,
  projectNames: string[],
  options: PlatformBackendOptions = {}
): Promise<BackendSyncResult<{ projects: number }>> => {
  const uniqueProjects = Array.from(new Set(projectNames.map((projectName) => projectName.trim()).filter(Boolean)))
  if (uniqueProjects.length === 0) return { source: 'local' }

  try {
    for (const projectName of uniqueProjects) {
      const project = await ensureBackendProject(projectName, options)
      const projectCases = listSavedCases(storage).filter((testCase) => projectNameForSavedCase(testCase) === projectName)
      await requestJson<SaveCasesResult>(
        options,
        `/api/projects/${project.id}/interface-cases`,
        {
          method: 'POST',
          headers: jsonHeaders(),
          body: JSON.stringify({ cases: projectCases })
        }
      )
    }
    return { source: 'backend', value: { projects: uniqueProjects.length } }
  } catch {
    return { source: 'local' }
  }
}

const selectSavedCasesForGeneratedCases = (
  storage: Storage,
  generatedCases: GeneratedInterfaceCase[]
): SavedInterfaceCase[] => {
  const libraryIds = new Set(generatedCases.map(buildLibraryId))
  return listSavedCases(storage).filter((savedCase) => libraryIds.has(savedCase.libraryId))
}

const buildLibraryId = (testCase: GeneratedInterfaceCase): string =>
  [
    testCase.metadata.sourceFile,
    testCase.metadata.sourceOperationId,
    testCase.row.path,
    testCase.row.caseType
  ].join(':')

const projectNameForSavedCase = (testCase: SavedInterfaceCase): string =>
  testCase.row.project || '默认项目'

const projectSavedCases = (storage: Storage, projectName: string): SavedInterfaceCase[] =>
  listSavedCases(storage).filter((testCase) => projectNameForSavedCase(testCase) === projectName)

const latestSavedAt = (cases: SavedInterfaceCase[]): string | undefined =>
  cases.reduce<string | undefined>((latest, testCase) => {
    if (!latest) return testCase.savedAt
    return Date.parse(testCase.savedAt) > Date.parse(latest) ? testCase.savedAt : latest
  }, undefined)

const groupSavedCasesByProject = (cases: SavedInterfaceCase[]): Map<string, SavedInterfaceCase[]> => {
  const groups = new Map<string, SavedInterfaceCase[]>()
  for (const testCase of cases) {
    const projectName = projectNameForSavedCase(testCase)
    groups.set(projectName, [...(groups.get(projectName) || []), testCase])
  }
  return groups
}

const toTemplateConfig = (response: BackendTemplateConfig): TemplateConfig => {
  const defaults = defaultTemplateConfig()
  return {
    schemaVersion: response.schemaVersion,
    interfaceFields: response.interfaceFields,
    fieldsByKind: response.fieldsByKind || {
      ...defaults.fieldsByKind,
      interface: response.interfaceFields
    },
    caseTypes: response.caseTypes,
    caseTemplates: response.caseTemplates || defaults.caseTemplates
  }
}

const templateConfigFingerprint = (config: TemplateConfig): string => JSON.stringify({
  interfaceFields: config.interfaceFields,
  fieldsByKind: config.fieldsByKind,
  caseTypes: config.caseTypes,
  caseTemplates: config.caseTemplates
})

const isLocalNewer = (localUpdatedAt?: string, backendUpdatedAt?: string): boolean => {
  if (!localUpdatedAt || !backendUpdatedAt) return false
  return Date.parse(localUpdatedAt) > Date.parse(backendUpdatedAt)
}

const shouldProtectLocal = (options: PlatformBackendOptions): boolean =>
  options.conflictStrategy !== 'preferBackend'

const findBackendProject = async (
  projectName: string,
  code: string,
  options: PlatformBackendOptions
): Promise<BackendProject | undefined> => {
  const projects = await requestJson<{ items: BackendProject[] }>(options, '/api/projects')
  return projects.items.find((project) => project.code === code || project.name === projectName)
}

const fetchCacheKey = (fetchImpl?: FetchLike): string => {
  if (!fetchImpl) return 'default'
  const cached = fetchIdentity.get(fetchImpl)
  if (cached) return String(cached)
  fetchIdentitySeed += 1
  fetchIdentity.set(fetchImpl, fetchIdentitySeed)
  return String(fetchIdentitySeed)
}

const requestJson = async <T>(
  options: PlatformBackendOptions,
  path: string,
  init?: RequestInit
): Promise<T> => {
  let response: Response
  try {
    response = await fetcher(options)(joinBackendPath(path, options.baseUrl), init)
  } catch (error) {
    throw new PlatformBackendTransportError(path, error)
  }
  if (!response.ok) {
    throw new PlatformBackendRequestError(response.status, path)
  }
  return await response.json() as T
}

const fetcher = (options: PlatformBackendOptions): FetchLike => {
  if (options.fetchImpl) return options.fetchImpl
  if (typeof fetch !== 'undefined') return authedFetch
  throw new Error('Fetch API is not available')
}

const jsonHeaders = (): HeadersInit => ({
  'Content-Type': 'application/json'
})

interface BackendTemplateConfig extends TemplateConfig {
  kind: 'interface'
  updatedAt?: string
}

interface BackendInterfaceCaseList {
  items: SavedInterfaceCase[]
  total: number
}

// Template-only transport: no storage, fallback, or synthetic confirmation time.
export class TemplateBackendContractError extends Error {
  constructor(message = '模板后端缺少有效 updatedAt，本地修改尚未确认') { super(message); this.name = 'TemplateBackendContractError' }
}
export const validTemplateTimestamp = (value: unknown): value is string =>
  typeof value === 'string' && value.trim() !== '' && !Number.isNaN(Date.parse(value))
export interface TemplateNetworkTransport {
  projectName: string
  fetch: FetchLike
  isCurrent: () => boolean
}
const templateRequestJson = async <T>(transport: TemplateNetworkTransport, assertWriter: () => void,
  path: string, init?: RequestInit): Promise<T> => {
  const gate = () => { assertWriter(); if (!transport.isCurrent()) throw new Error('Template workspace operation expired') }
  gate()
  let response: Response
  try {
    response = await transport.fetch(joinBackendPath(path), init)
  } catch (error) {
    throw new PlatformBackendTransportError(path, error)
  }
  gate()
  if (!response.ok) throw new PlatformBackendRequestError(response.status, path)
  gate()
  const value = await response.json() as T
  gate()
  return value
}
const templateProject = async (transport: TemplateNetworkTransport, gate: () => void): Promise<BackendProject> => {
  const projects = await templateRequestJson<{ items: BackendProject[] }>(transport, gate, '/api/projects')
  gate()
  if (!Array.isArray(projects?.items)) throw new TemplateBackendContractError('模板项目列表响应无效')
  const code = projectCodeFromName(transport.projectName)
  const existing = projects.items.find(p => p.name === transport.projectName || p.code === code)
  if (existing) return existing
  return templateRequestJson<BackendProject>(transport, gate, '/api/projects', {
    method: 'POST', headers: jsonHeaders(), body: JSON.stringify({ code, name: transport.projectName, description: `${transport.projectName} API test workspace` })
  })
}
export const fetchTemplateConfigFromBackend = async (transport: TemplateNetworkTransport, gate: () => void): Promise<{ config: TemplateConfig; updatedAt: string }> => {
  const project = await templateProject(transport, gate); gate()
  const value = await templateRequestJson<BackendTemplateConfig>(transport, gate, `/api/projects/${project.id}/case-template/interface`)
  gate()
  if (!validTemplateTimestamp(value?.updatedAt)) throw new TemplateBackendContractError()
  if (!Array.isArray(value.interfaceFields) || !Array.isArray(value.caseTypes)) throw new TemplateBackendContractError('模板配置响应无效')
  return { config: toTemplateConfig(value), updatedAt: value.updatedAt }
}
export const putTemplateConfigToBackend = async (transport: TemplateNetworkTransport, target: TemplateConfig,
  gate: () => void): Promise<{ updatedAt: string }> => {
  const project = await templateProject(transport, gate); gate()
  const value = await templateRequestJson<Partial<BackendTemplateConfig>>(transport, gate, `/api/projects/${project.id}/case-template/interface`, {
    method: 'PUT', headers: jsonHeaders(), body: JSON.stringify(target)
  })
  gate()
  if (!validTemplateTimestamp(value?.updatedAt)) throw new TemplateBackendContractError()
  if ('interfaceFields' in value || 'caseTypes' in value) {
    const canonical = (v: unknown): string => v !== null && typeof v === 'object'
      ? Array.isArray(v) ? `[${v.map(canonical).join(',')}]` : `{${Object.keys(v).sort().map(k=>`${JSON.stringify(k)}:${canonical((v as Record<string,unknown>)[k])}`).join(',')}}`
      : JSON.stringify(v)
    if (!Array.isArray(value.interfaceFields) || !Array.isArray(value.caseTypes)
      || canonical(JSON.parse(serializeTemplateTarget(toTemplateConfig(value as BackendTemplateConfig)))) !== canonical(JSON.parse(serializeTemplateTarget(target)))) {
      throw new TemplateBackendContractError('模板后端返回了不同配置，未确认本地镜像')
    }
  }
  return { updatedAt: value.updatedAt }
}

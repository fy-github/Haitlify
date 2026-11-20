import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { type GenerationGranularity } from '../domain/caseGenerator'
import { loadSavedCasesWithBackend, PlatformBackendRequestError, PlatformBackendTransportError, syncCaseOrderingWithBackend } from '../domain/platformBackendRepository'
import type { HomeRun, HomeWorkItem } from '../domain/homeWorkbench'
import { type ImportedOpenApiProject } from '../domain/openapiProjectRepository'
import type { ComputedRef } from 'vue'
import type { FetchLike } from '../domain/http'
import type { TemplateTransport } from './workspaceStorage'

interface WorkspaceSyncConflictNotice {
  projectName: string
  template: boolean
  cases: boolean
}

type HomeWorkspaceStatus = 'ready' | 'loading' | 'empty' | 'unavailable' | 'forbidden' | 'error'

interface Dependencies {
  activeProject: ComputedRef<ImportedOpenApiProject | undefined>
  libraryFilter: { project: string; module: string; caseType: string; source: "" | "design" | "default"; keyword: string; }
  beginWorkspaceOperation: (resource: string, templateLease?: boolean) => () => boolean
  workspaceStorage: { capture(current: () => boolean): Storage }
  workspaceFetch: (isCurrent: () => boolean) => FetchLike
  templateConfigModule: { loadBackend(transport: import('./workspaceStorage').TemplateTransport, options: { throwOnError: boolean }): Promise<unknown>; reconcile(mode: 'push-local'): Promise<unknown> }
  savedCases: { value: { libraryId: string; savedAt: string; id: string; row: { caseId: string; project: string; module: string; interfaceName: string; method: string; path: string; title: string; caseType: string; precondition: string; headers: string; pathParams: string; queryParams: string; body: string; steps: string; expectedResult: string; expectedResponseBody?: string | undefined; actualResponseBody?: string | undefined; assertions: string; priority: string; executionResult: string; executor: string; executedAt: string; defectId: string; tags: string; sourceFile: string; remark: string; }; metadata: { sourceSpecId: string; sourceFile: string; sourceOperationId: string; tag: string; method: string; path: string; baseUrlRef: string; environmentId: string; authProfileId: string; contentType: string; headers: Record<string, string>; pathParams: Record<string, string>; queryParams: Record<string, string>; body: unknown; expectedStatus: number; assertions: string[]; expectedBodyRules: string[]; expectedResponseBody?: unknown; variableExtractions: string[]; dependsOnCaseIds: string[]; runOrder: number; generationMeta: { granularity: GenerationGranularity; strategy: string; }; reviewMeta: { status: "\u5F85\u590D\u6838"; }; exportMeta: { apifoxReady: boolean; }; }; }[] }
  syncSelectedCaseTypes: () => void
  homeWorkItems: ComputedRef<HomeWorkItem[]>
  homeRuns: ComputedRef<HomeRun[]>
  confirmDangerAction: (title: string, message: string, confirmButtonText?: string) => Promise<boolean>
}

export function createWorkspaceSync(deps: Dependencies) {

const workspaceSyncConflict = ref<WorkspaceSyncConflictNotice>()

const workspaceSyncLoading = ref(false)

const workspaceSyncConflictText = computed(() => {
  const notice = workspaceSyncConflict.value
  if (!notice) return ''
  const scopes = [
    notice.template ? '模板配置' : '',
    notice.cases ? '用例库' : ''
  ].filter(Boolean)
  return `${notice.projectName} 的 ${scopes.join('、')} 已保留本地版本`
})

const workspaceSyncState = computed<'synced' | 'syncing' | 'conflict'>(() => {
  if (workspaceSyncConflict.value) return 'conflict'
  if (workspaceSyncLoading.value) return 'syncing'
  return 'synced'
})

const currentBackendProjectName = () =>
  deps.activeProject.value?.title
    || (deps.libraryFilter.project !== 'ALL' ? deps.libraryFilter.project : '默认项目')

const loadBackendBackedWorkspace = async (
  options: {
    conflictStrategy?: 'protectLocal' | 'preferBackend'
    projectName?: string
  } = {},
  isCurrent = deps.beginWorkspaceOperation('workspace')
) => {
  try {
    if (!isCurrent()) return
    homeWorkspaceStatus.value = 'loading'
    homeWorkspaceError.value = ''
    const storage = deps.workspaceStorage.capture(isCurrent)
    const fetchImpl = deps.workspaceFetch(isCurrent)
    const projectName = options.projectName || currentBackendProjectName()
    const [templateResult, caseResult] = await Promise.all([
      deps.templateConfigModule.loadBackend({ fetch: fetchImpl, projectName, isCurrent }, { throwOnError: true }),
      loadSavedCasesWithBackend(storage, projectName, {
        fetchImpl,
        conflictStrategy: options.conflictStrategy,
        throwOnError: true
      })
    ])
    if (!isCurrent()) return
    deps.savedCases.value = caseResult.cases
    deps.syncSelectedCaseTypes()
    workspaceSyncConflict.value =
      options.conflictStrategy === 'preferBackend'
        ? undefined
        : buildWorkspaceSyncConflictNotice(projectName, false, caseResult.source === 'conflict')
    homeWorkspaceStatus.value = deps.homeWorkItems.value.length || deps.homeRuns.value.length ? 'ready' : 'empty'
    return { templateResult, caseResult }
  } catch (error) {
    if (!isCurrent()) return
    const failure = homeWorkspaceFailure(error)
    homeWorkspaceStatus.value = failure.status
    homeWorkspaceError.value = failure.message
  }
}

const retryHomeWorkspace = () => {
  void loadBackendBackedWorkspace()
}

const homeWorkspaceFailure = (error: unknown): { status: HomeWorkspaceStatus; message: string } => {
  if (error instanceof PlatformBackendRequestError) {
    if (error.status === 403) return { status: 'forbidden', message: '当前账号没有权限查看工作台数据。' }
    if ([502, 503, 504].includes(error.status)) return { status: 'unavailable', message: '工作台服务暂时不可用，请稍后重试。' }
    return { status: 'error', message: '工作台加载失败，请重试。' }
  }
  if (error instanceof PlatformBackendTransportError || error instanceof TypeError) return { status: 'unavailable', message: '工作台服务暂时不可用，请稍后重试。' }
  return { status: 'error', message: '工作台加载失败，请重试。' }
}

const buildWorkspaceSyncConflictNotice = (
  projectName: string,
  template: boolean,
  cases: boolean
): WorkspaceSyncConflictNotice | undefined =>
  template || cases ? { projectName, template, cases } : undefined

const dismissWorkspaceSyncConflict = () => {
  workspaceSyncConflict.value = undefined
}

const pushLocalWorkspaceToBackend = async () => {
  const isCurrent = deps.beginWorkspaceOperation('workspace')
  if (!isCurrent()) return
  const storage = deps.workspaceStorage.capture(isCurrent)
  const fetchImpl = deps.workspaceFetch(isCurrent)
  const notice = workspaceSyncConflict.value
  if (!notice) return

  workspaceSyncLoading.value = true
  try {
    let synced = true
    if (notice.template) {
      const result = await deps.templateConfigModule.reconcile('push-local')
      if (!result) synced = false
    }
    if (notice.cases) {
      const result = await syncCaseOrderingWithBackend(storage, notice.projectName, { fetchImpl })
      if (!isCurrent()) return
      synced = synced && result.source === 'backend'
    }

    if (synced) {
      workspaceSyncConflict.value = undefined
      ElMessage.success('已将本地版本同步到后端')
    } else {
      ElMessage.warning('后端暂不可用，已继续保留本地版本')
    }
  } finally {
    if (isCurrent()) workspaceSyncLoading.value = false
  }
}

const reloadBackendWorkspace = async () => {
  const isCurrent = deps.beginWorkspaceOperation('workspace')
  if (!isCurrent()) return
  const notice = workspaceSyncConflict.value
  if (!notice) return

  const confirmed = await deps.confirmDangerAction(
    '使用后端数据',
    `确认用后端数据覆盖 ${notice.projectName} 当前较新的本地数据吗？`,
    '使用后端'
  )
  if (!confirmed || !isCurrent()) return

  workspaceSyncLoading.value = true
  try {
    await loadBackendBackedWorkspace({
      conflictStrategy: 'preferBackend',
      projectName: notice.projectName
    }, isCurrent)
    if (isCurrent()) ElMessage.success('已加载后端版本')
  } finally {
    if (isCurrent()) workspaceSyncLoading.value = false
  }
}

const homeWorkspaceStatus = ref<HomeWorkspaceStatus>('loading')

const homeWorkspaceError = ref('')

const reset = () => {
  workspaceSyncConflict.value = undefined
  workspaceSyncLoading.value = false
  homeWorkspaceStatus.value = 'loading'
  homeWorkspaceError.value = ''
}
return {
  reset,
  workspaceSyncConflict,
  workspaceSyncLoading,
  workspaceSyncConflictText,
  workspaceSyncState,
  currentBackendProjectName,
  loadBackendBackedWorkspace,
  retryHomeWorkspace,
  homeWorkspaceFailure,
  buildWorkspaceSyncConflictNotice,
  dismissWorkspaceSyncConflict,
  pushLocalWorkspaceToBackend,
  reloadBackendWorkspace,
  homeWorkspaceStatus,
  homeWorkspaceError,
}
}

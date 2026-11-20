import { computed, reactive, ref } from 'vue'
import type { ComputedRef, Ref, WritableComputedRef } from 'vue'
import {
  EXECUTION_ENV_LIBRARY_STORAGE_KEY,
  WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY,
  buildExecutionEnvironment,
  buildWebExecutionRuntime,
  defaultExecutionEnvironmentLibrary,
  defaultWebExecutionEnvironmentLibrary,
  defaultWebExecutionRuntimeConfig,
  loadExecutionEnvironmentLibrary,
  newSavedExecutionEnvironment,
  newSavedWebExecutionEnvironment,
  normalizeWebExecutionRuntimeConfig,
  saveExecutionEnvironmentLibrary,
  validateExecutionEnvironment,
  validateWebExecutionEnvironment,
  type BuiltWebExecutionRuntime,
  type ExecutionEnvironmentLibrary,
  type SavedExecutionEnvironment,
  type WebRuntimeBrowser
} from '../domain/executionEnvironmentRepository'
import type { ExecutionEnvironment } from '../domain/apiExecution'
import type { EnvironmentKind, NoticePort, OperationLease } from './appPorts'


export interface EnvironmentControllerOptions {
  storage?: Storage
  confirmDanger?: (title: string, message: string, confirmLabel?: string) => Promise<boolean>
  confirmDiscard?: (message: string) => Promise<boolean>
  beginOperation?: (resource: string) => OperationLease
  notify?: Partial<NoticePort>
}

export interface EnvironmentController {
  executionEnvironmentLibrary: Ref<ExecutionEnvironmentLibrary>
  webExecutionEnvironmentLibrary: Ref<ExecutionEnvironmentLibrary>
  editingEnvironment: SavedExecutionEnvironment
  editingWebAllowedRequestOriginsText: WritableComputedRef<string, string>
  editingWebNetworkPolicyMode: WritableComputedRef<'blocklist' | 'allowlist', 'blocklist' | 'allowlist'>
  environmentEditorTarget: Ref<EnvironmentKind>
  environmentEditorKind: ComputedRef<EnvironmentKind>
  currentEnvironmentLibrary: ComputedRef<ExecutionEnvironmentLibrary>
  activeApiExecutionEnvironment: ComputedRef<SavedExecutionEnvironment | undefined>
  activeWebExecutionEnvironment: ComputedRef<SavedExecutionEnvironment | undefined>
  runnableApiExecutionEnvironment: ComputedRef<ExecutionEnvironment | undefined>
  runnableWebRuntime: ComputedRef<BuiltWebExecutionRuntime | undefined>
  environmentDraftBaseline: ComputedRef<SavedExecutionEnvironment | undefined>
  environmentDraftDirty: ComputedRef<boolean>
  bindEditingEnvironment(environment: SavedExecutionEnvironment, kind: EnvironmentKind): void
  selectedEnvironmentOf(kind: EnvironmentKind): SavedExecutionEnvironment | undefined
  syncEnvironmentEditorToLibrary(): void
  discardEnvironmentDraft(): void
  captureDraftIntent(): OperationLease
  confirmDiscardDraft(message: string): Promise<boolean>
  runWithDraftGuard(message: string, action: () => void): void
  openEditor(environmentId?: string, kind?: EnvironmentKind): SavedExecutionEnvironment | undefined
  persistEnvironmentLibrary(kind?: EnvironmentKind): void
  selectExecutionEnvironment(environmentId: string, kind?: EnvironmentKind): void
  addEnvironment(kind?: EnvironmentKind): SavedExecutionEnvironment
  deleteEnvironment(environmentId: string, kind?: EnvironmentKind): Promise<boolean>
  saveEnvironment(kind?: EnvironmentKind): boolean
  reset(): void
  reload(): void
}

const cloneEnvironment = (environment: SavedExecutionEnvironment): SavedExecutionEnvironment =>
  JSON.parse(JSON.stringify(environment)) as SavedExecutionEnvironment

function stableStringify(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value) ?? 'null'
  if (Array.isArray(value)) return `[${value.map((item) => stableStringify(item)).join(',')}]`
  const entries = Object.entries(value as Record<string, unknown>)
    .filter(([, item]) => item !== undefined)
    .sort(([left], [right]) => left.localeCompare(right))
  return `{${entries.map(([key, item]) => `${JSON.stringify(key)}:${stableStringify(item)}`).join(',')}}`
}

function environmentComparable(environment: SavedExecutionEnvironment, kind: EnvironmentKind): string {
  const copy = cloneEnvironment(environment)
  delete (copy as { updatedAt?: string }).updatedAt
  if (kind === 'web') {
    copy.auth = { type: 'none' }
    copy.entries = []
    const web = normalizeWebExecutionRuntimeConfig(copy.web)
    web.cookieOrigins = []
    copy.web = web
  }
  return stableStringify(copy)
}

export const createEnvironmentController = (options: EnvironmentControllerOptions = {}): EnvironmentController => {
  const storage = options.storage ?? window.localStorage
  const notify = {
    info: options.notify?.info ?? (() => undefined),
    warning: options.notify?.warning ?? (() => undefined),
    error: options.notify?.error ?? (() => undefined)
  }
  const confirmDanger = options.confirmDanger ?? (async () => false)
  const confirmDiscard = options.confirmDiscard ?? (async () => false)
  const beginOperation = options.beginOperation ?? (() => () => true)

  const executionEnvironmentLibrary: Ref<ExecutionEnvironmentLibrary> = ref(loadExecutionEnvironmentLibrary(storage))
  const webExecutionEnvironmentLibrary: Ref<ExecutionEnvironmentLibrary> = ref(
    loadExecutionEnvironmentLibrary(storage, WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY)
  )
  const environmentEditorTarget = ref<EnvironmentKind>('api')
  const environmentEditorKind = computed(() => environmentEditorTarget.value)
  const editingEnvironment = reactive<SavedExecutionEnvironment>(cloneEnvironment(
    executionEnvironmentLibrary.value.environments.find((environment) =>
      environment.id === executionEnvironmentLibrary.value.activeEnvironmentId
    ) || defaultExecutionEnvironmentLibrary().environments[0]
  ))

  const editingWebAllowedRequestOriginsText = computed({
    get: () => (editingEnvironment.web?.allowedRequestOrigins || []).join('\n'),
    set: (value: string) => {
      if (!editingEnvironment.web) editingEnvironment.web = defaultWebExecutionRuntimeConfig()
      editingEnvironment.web.allowedRequestOrigins = value.split(/\r?\n/).map((item) => item.trim()).filter(Boolean)
    }
  })

  const editingWebNetworkPolicyMode = computed({
    get: () => editingEnvironment.web?.networkPolicyMode || 'blocklist',
    set: (value: 'blocklist' | 'allowlist') => {
      if (!editingEnvironment.web) editingEnvironment.web = defaultWebExecutionRuntimeConfig()
      editingEnvironment.web.networkPolicyMode = value === 'allowlist' ? 'allowlist' : 'blocklist'
    }
  })

  const currentEnvironmentLibrary = computed(() =>
    environmentEditorKind.value === 'web'
      ? webExecutionEnvironmentLibrary.value
      : executionEnvironmentLibrary.value
  )
  const activeApiExecutionEnvironment = computed(() =>
    executionEnvironmentLibrary.value.environments.find((environment) =>
      environment.id === executionEnvironmentLibrary.value.activeEnvironmentId
    )
  )
  const activeWebExecutionEnvironment = computed(() =>
    webExecutionEnvironmentLibrary.value.environments.find((environment) =>
      environment.id === webExecutionEnvironmentLibrary.value.activeEnvironmentId
    )
  )
  const runnableApiExecutionEnvironment = computed<ExecutionEnvironment | undefined>(() =>
    activeApiExecutionEnvironment.value ? buildExecutionEnvironment(activeApiExecutionEnvironment.value) : undefined
  )
  const runnableWebRuntime = computed<BuiltWebExecutionRuntime | undefined>(() =>
    activeWebExecutionEnvironment.value ? buildWebExecutionRuntime(activeWebExecutionEnvironment.value) : undefined
  )

  const bindEditingEnvironment = (environment: SavedExecutionEnvironment, kind: EnvironmentKind): void => {
    for (const key of Object.keys(editingEnvironment)) {
      delete (editingEnvironment as unknown as Record<string, unknown>)[key]
    }
    Object.assign(editingEnvironment, cloneEnvironment(environment))
    if (kind === 'web') {
      editingEnvironment.auth = editingEnvironment.auth || { type: 'none' }
      editingEnvironment.web = normalizeWebExecutionRuntimeConfig(editingEnvironment.web)
    }
  }

  const selectedEnvironmentOf = (kind: EnvironmentKind): SavedExecutionEnvironment | undefined => {
    const library = kind === 'web' ? webExecutionEnvironmentLibrary.value : executionEnvironmentLibrary.value
    return library.environments.find((environment) => environment.id === library.activeEnvironmentId)
      || library.environments[0]
  }

  const environmentDraftBaseline = computed(() =>
    currentEnvironmentLibrary.value.environments.find((environment) => environment.id === editingEnvironment.id)
  )
  const environmentDraftDirty = computed(() => {
    const baseline = environmentDraftBaseline.value
    if (!baseline) return false
    return environmentComparable(baseline, environmentEditorKind.value)
      !== environmentComparable(editingEnvironment, environmentEditorKind.value)
  })

  const discardEnvironmentDraft = (): void => {
    const target = environmentDraftBaseline.value || selectedEnvironmentOf(environmentEditorKind.value)
    if (target) bindEditingEnvironment(target, environmentEditorKind.value)
  }

  const syncEnvironmentEditorToLibrary = (): void => {
    const target = selectedEnvironmentOf(environmentEditorKind.value)
    if (target) bindEditingEnvironment(target, environmentEditorKind.value)
  }

  const captureDraftIntent = (): OperationLease => beginOperation('environmentDraft')
  const confirmDiscardDraft = (message: string): Promise<boolean> => confirmDiscard(message)

  const runWithDraftGuard = (message: string, action: () => void): void => {
    if (!environmentDraftDirty.value) {
      action()
      return
    }
    const isCurrent = captureDraftIntent()
    void confirmDiscardDraft(message).then((confirmed) => {
      if (!confirmed || !isCurrent()) return
      discardEnvironmentDraft()
      action()
    })
  }

  const openEditor = (
    environmentId?: string,
    kind: EnvironmentKind = environmentEditorKind.value
  ): SavedExecutionEnvironment | undefined => {
    environmentEditorTarget.value = kind
    const library = kind === 'web' ? webExecutionEnvironmentLibrary.value : executionEnvironmentLibrary.value
    const targetEnvironmentId = environmentId || library.activeEnvironmentId
    const environment = library.environments.find((item) => item.id === targetEnvironmentId)
      || library.environments[0]
      || (kind === 'web' ? defaultWebExecutionEnvironmentLibrary() : defaultExecutionEnvironmentLibrary()).environments[0]
    if (!environment) return undefined
    bindEditingEnvironment(environment, kind)
    return environment
  }

  const persistEnvironmentLibrary = (kind: EnvironmentKind = environmentEditorKind.value): void => {
    if (kind === 'web') {
      saveExecutionEnvironmentLibrary(
        storage,
        webExecutionEnvironmentLibrary.value,
        WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY
      )
      return
    }
    saveExecutionEnvironmentLibrary(storage, executionEnvironmentLibrary.value, EXECUTION_ENV_LIBRARY_STORAGE_KEY)
  }

  const selectExecutionEnvironment = (
    environmentId: string,
    kind: EnvironmentKind = environmentEditorKind.value
  ): void => {
    if (kind === 'web') {
      webExecutionEnvironmentLibrary.value = {
        ...webExecutionEnvironmentLibrary.value,
        activeEnvironmentId: environmentId
      }
    } else {
      executionEnvironmentLibrary.value = {
        ...executionEnvironmentLibrary.value,
        activeEnvironmentId: environmentId
      }
    }
    persistEnvironmentLibrary(kind)
  }

  const addEnvironment = (kind: EnvironmentKind = environmentEditorKind.value): SavedExecutionEnvironment => {
    const library = kind === 'web' ? webExecutionEnvironmentLibrary.value : executionEnvironmentLibrary.value
    const environment = kind === 'web'
      ? newSavedWebExecutionEnvironment(`Web 环境 ${library.environments.length + 1}`)
      : newSavedExecutionEnvironment(`环境 ${library.environments.length + 1}`)
    const updatedLibrary = {
      activeEnvironmentId: environment.id,
      environments: [...library.environments, environment]
    }
    if (kind === 'web') webExecutionEnvironmentLibrary.value = updatedLibrary
    else executionEnvironmentLibrary.value = updatedLibrary
    persistEnvironmentLibrary(kind)
    openEditor(environment.id, kind)
    return environment
  }

  const deleteEnvironment = async (
    environmentId: string,
    kind: EnvironmentKind = environmentEditorKind.value
  ): Promise<boolean> => {
    const library = kind === 'web' ? webExecutionEnvironmentLibrary.value : executionEnvironmentLibrary.value
    if (library.environments.length <= 1) {
      notify.warning('至少保留一个运行环境')
      return false
    }
    const target = library.environments.find((environment) => environment.id === environmentId)
    const confirmed = await confirmDanger('删除运行环境', `确认删除运行环境 ${target?.name || ''} 吗？`)
    if (!confirmed) return false
    const environments = library.environments.filter((environment) => environment.id !== environmentId)
    const updatedLibrary = {
      activeEnvironmentId: environments[0].id,
      environments
    }
    if (kind === 'web') webExecutionEnvironmentLibrary.value = updatedLibrary
    else executionEnvironmentLibrary.value = updatedLibrary
    persistEnvironmentLibrary(kind)
    openEditor(environments[0].id, kind)
    notify.info('已删除运行环境')
    return true
  }

  const saveEnvironment = (kind: EnvironmentKind = environmentEditorKind.value): boolean => {
    const validation = kind === 'web'
      ? validateWebExecutionEnvironment(editingEnvironment)
      : validateExecutionEnvironment(editingEnvironment)
    if (!validation.valid) {
      notify.warning(validation.message || '运行环境配置无效')
      return false
    }
    const updated = {
      ...cloneEnvironment(editingEnvironment),
      updatedAt: new Date().toISOString()
    }
    if (kind === 'web') {
      updated.web = normalizeWebExecutionRuntimeConfig(updated.web)
      updated.web.cookieOrigins = []
      updated.auth = { type: 'none' }
      updated.entries = []
    }
    const library = kind === 'web' ? webExecutionEnvironmentLibrary.value : executionEnvironmentLibrary.value
    const updatedLibrary = {
      activeEnvironmentId: updated.id,
      environments: library.environments.map((environment) =>
        environment.id === updated.id ? updated : environment
      )
    }
    if (kind === 'web') webExecutionEnvironmentLibrary.value = updatedLibrary
    else executionEnvironmentLibrary.value = updatedLibrary
    persistEnvironmentLibrary(kind)
    notify.info('运行环境已保存')
    return true
  }

  return {
    reset: () => {
      executionEnvironmentLibrary.value = defaultExecutionEnvironmentLibrary()
      webExecutionEnvironmentLibrary.value = defaultWebExecutionEnvironmentLibrary()
      syncEnvironmentEditorToLibrary()
    },
    reload: () => {
      executionEnvironmentLibrary.value = loadExecutionEnvironmentLibrary(storage)
      webExecutionEnvironmentLibrary.value = loadExecutionEnvironmentLibrary(storage, WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY)
      syncEnvironmentEditorToLibrary()
    },
    executionEnvironmentLibrary,
    webExecutionEnvironmentLibrary,
    editingEnvironment,
    editingWebAllowedRequestOriginsText,
    editingWebNetworkPolicyMode,
    environmentEditorTarget,
    environmentEditorKind,
    currentEnvironmentLibrary,
    activeApiExecutionEnvironment,
    activeWebExecutionEnvironment,
    runnableApiExecutionEnvironment,
    runnableWebRuntime,
    environmentDraftBaseline,
    environmentDraftDirty,
    bindEditingEnvironment,
    selectedEnvironmentOf,
    syncEnvironmentEditorToLibrary,
    discardEnvironmentDraft,
    captureDraftIntent,
    confirmDiscardDraft,
    runWithDraftGuard,
    openEditor,
    persistEnvironmentLibrary,
    selectExecutionEnvironment,
    addEnvironment,
    deleteEnvironment,
    saveEnvironment
  }
}

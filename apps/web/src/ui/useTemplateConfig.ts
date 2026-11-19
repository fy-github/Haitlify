import { computed, getCurrentScope, onScopeDispose, reactive, ref, watch } from 'vue'
import { getAuthSessionEpoch } from '../domain/http'
import { PlatformBackendRequestError, PlatformBackendTransportError } from '../domain/platformBackendRepository'
import { isWorkspaceStorage, type TemplateAuthority } from './workspaceStorage'
import { configFromRaw, templateFingerprintSync, templateHashSync, type TemplateOperationAdapter } from './templateOperationAdapter'
import { ElMessage } from 'element-plus'
import { missingDep } from './depGuard'
import {
  addCaseField,
  addCaseFieldByKind,
  addCaseTemplate,
  addCaseType,
  deleteCaseField,
  deleteCaseFieldByKind,
  deleteCaseTemplate,
  deleteCaseType,
  loadTemplateConfig,
  moveCaseField,
  moveCaseFieldByKind,
  resetTemplateConfig,
  saveTemplateConfig,
  TEMPLATE_CONFIG_META_STORAGE_KEY,
  TEMPLATE_CONFIG_STORAGE_KEY,
  templateKindOptions,
  updateCaseField,
  updateCaseFieldByKind,
  updateCaseTemplate,
  updateCaseType,
  readTemplateConfigRawPair,
  serializeTemplateTarget,
  markTemplateConfigPending,
  restoreTemplateConfigRawPair,
  removeTemplateConfigKeys,
  defaultTemplateConfig,
  type TemplateConfigRawPair,
  type TemplateConfig
} from '../domain/templateConfigRepository'
import type {
  CaseField,
  CaseKind,
  CaseTemplateDefinition,
  CaseTypeDefinition,
  FieldType
} from '../domain/caseTemplates'

export type TemplateAdopt = { authorityKey: string; writerSeq: number | null; adoptSeq: number } & (
  | { kind: 'backend'; config: TemplateConfig; rawPair: TemplateConfigRawPair | 'unreadable'; mirror: 'done' | 'failed'; confirmedUpdatedAt: string | null; error?: unknown }
  | { kind: 'import'; config: TemplateConfig; rawPair: TemplateConfigRawPair | 'unreadable'; mirror: 'done' | 'failed'; error?: unknown }
  | { kind: 'local'; config: TemplateConfig; rawPair: TemplateConfigRawPair | 'unreadable' }
  | { kind: 'conflict'; config: TemplateConfig }
  | { kind: 'clear'; config: TemplateConfig; rawPair?: TemplateConfigRawPair | 'unreadable' }
)
export interface TemplateConfigDeps {
  storage?: Storage
  identity?: () => TemplateAuthority | null
  operationAdapter?: TemplateOperationAdapter
  captureIntentCurrent?: () => () => boolean
  beginOperation?: () => () => boolean
  confirmDanger?: (title: string, message: string, confirmLabel?: string) => Promise<boolean>
  /**
   * 持久化模板配置并返回权威配置。
   *
   * 契约:成功(本地 + 后端都确认)才 resolve;任何降级(例如后端 500、离线)
   * 必须 reject,错误信息说明「仅保留在本地」。调用方若把降级当成 resolve,
   * 这里就会把它显示成「已自动保存」——那正是审计 F-05 的缺陷,不能再回来。
   */
  persistTemplateConfig?: (config: TemplateConfig, isCurrent?: () => boolean) => Promise<TemplateConfig>
  // Bridge to the generation workflow's selectedCaseTypeIds (owned by App.vue).
  getGenerationSelectedCaseTypes?: () => string[]
  setGenerationSelectedCaseTypes?: (ids: string[]) => void
}

/** 设计稿把「模板字段」和「用例类型」做成两个独立页面,保存条也各自独立。 */
export type TemplateSection = 'fields' | 'types'
export type TemplateSaveState = 'idle' | 'saving' | 'saved' | 'failed'

export interface TemplateSaveStatus {
  state: TemplateSaveState
  error: string
  savedAt: string
}

export interface FieldDraft {
  /** 打开抽屉时锁定的类别:抽屉打开后切换模板不会改变新增字段的落点。 */
  kind: CaseKind
  templateId?: string
  key: string
  label: string
  type: FieldType
  required: boolean
  visible: boolean
  exportable: boolean
  aiGenerated: boolean
  error: string
}

export interface CaseTypeDraft {
  id: string
  name: string
  appliesTo: CaseTypeDefinition['appliesTo']
  enabled: boolean
  error: string
}

const idPattern = /^[A-Za-z][A-Za-z0-9_]*$/
const caseKindScopes: Array<CaseTypeDefinition['appliesTo']> = [
  'all', 'interface', 'web_automation', 'app_automation', 'ai_test', 'functional', 'performance'
]

const clock = (): string => new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }).format()
const idleStatus = (): TemplateSaveStatus => ({ state: 'idle', error: '', savedAt: '' })
const cloneConfig = (config: TemplateConfig): TemplateConfig => JSON.parse(JSON.stringify(config)) as TemplateConfig
const errorText = (error: unknown): string => error instanceof Error ? error.message : String(error)

// Identity is explicit and never proven by writing a storage probe.


// Module-level singleton so App.vue (generation workflow + sidebar) and
// SettingsView.vue (template settings) share the same template config state.
let singleton: ReturnType<typeof createTemplateConfigState> | null = null

function createTemplateConfigState(deps: TemplateConfigDeps) {
  let adapter = deps.operationAdapter
  let identity = deps.identity?.() ?? null
  let localStorage = identity?.storage ?? deps.storage ?? window.localStorage
  let localWriterSeq = 0
  const localRecovery = reactive({ reason: null as string | null, phase: 'idle', blocked: false, partial: undefined as import('../domain/templateConfigRepository').TemplateWriteReport | undefined,
    identityUsable: true, owner: '', project: '', hasPending: false })
  const confirmDanger = async (title: string, message: string, label?: string): Promise<boolean> => {
    try { return await (deps.confirmDanger ?? (async () => missingDep('useTemplateConfig', 'confirmDanger')))(title,message,label) } catch { return false }
  }
  const persistTemplateConfig = (config: TemplateConfig, isCurrent?: () => boolean): Promise<TemplateConfig> => adapter ? adapter.save(config) : deps.persistTemplateConfig ? deps.persistTemplateConfig(config, isCurrent) : (async () => {
    if(deps.identity||isWorkspaceStorage(localStorage))return missingDep('useTemplateConfig','operationAdapter')
    const expected = readTemplateConfigRawPair(localStorage)
    const raw = serializeTemplateTarget(config)
    const report = markTemplateConfigPending(localStorage, { markerVersion: 1, operationId: crypto.randomUUID(), writerSeq: ++localWriterSeq,
      confirmedBaselineRaw: null, confirmedFingerprint: null, targetConfigRaw: raw, targetRawHash: templateHashSync(raw) },
      () => { if (!identityUsable() || disposed || (isCurrent && !isCurrent())) throw new Error('模板本地保存作用域已失效') }, expected)
    localRecovery.partial = report.ok ? undefined : report
    localRecovery.reason = report.ok ? 'baseline-unknown' : 'storage'
    localRecovery.blocked = true; localRecovery.hasPending = report.ok || report.completed.includes('pending')
    if (!report.ok) throw new Error(`本地模板未确认：缓存写入未完成（${report.failedAt}）`)
    throw new Error('模板仅保留本地，未确认到后端')
  })()
  let initialRaw: TemplateConfigRawPair | null = null
  try { initialRaw = readTemplateConfigRawPair(localStorage) } catch { /* visible unreadable status below */ }
  if (!adapter && (!initialRaw || initialRaw.pending !== null)) Object.assign(localRecovery, {
    reason: initialRaw ? 'baseline-unknown' : 'unreadable', blocked: true, hasPending: !!initialRaw?.pending
  })
  const templateConfig = ref<TemplateConfig>(configFromRaw(initialRaw?.config ?? null))
  const selectedTemplateId = ref('builtin-interface')
  const generationTemplateId = ref('builtin-interface')

  // 设计稿的保存条状态:自动保存必须如实反馈「保存中 / 已保存 / 失败」,失败可重试或回退。
  // 按页面分开(F-06):在「模板字段」保存过,不代表「用例类型」页也保存过。
  const saveStatus = reactive<Record<TemplateSection, TemplateSaveStatus>>({
    fields: idleStatus(),
    types: idleStatus()
  })
  let activeSection: TemplateSection = 'fields'
  /** 最近一次确认写入成功的配置,用于「放弃未保存修改」 */
  let savedSnapshot: TemplateConfig = cloneConfig(templateConfig.value)
  /**
   * 最近一次确认写入成功时 localStorage 里的原始字符串。放弃编辑要逐字节回滚,
   * 连 updatedAt 元数据一起还原,不能顺手盖一个新时间戳冒充「刚保存」。
   */
  let savedStorage: TemplateConfigRawPair | null = initialRaw?.pending && adapter ? adapter.pendingRestorePoint : initialRaw
  let localEpoch = getAuthSessionEpoch()
  const identityUsable = () => identity ? identity.state() === 'usable' : !deps.identity && !isWorkspaceStorage(localStorage) && localEpoch === getAuthSessionEpoch()
  const captureSavedStorage = () => { savedStorage = adapter?.rawPair ?? readTemplateConfigRawPair(localStorage) }
  if (!initialRaw || initialRaw.pending !== null || (deps.identity && !identity)) {
    for (const section of ['fields', 'types'] as const) Object.assign(saveStatus[section], { state: 'failed', error: !initialRaw ? '本地缓存不可读取' : '本地模板未确认' })
  }

  // 新增字段/新增类型的抽屉草稿:确认前不落库。
  const fieldDraft = ref<FieldDraft | null>(null)
  const caseTypeDraft = ref<CaseTypeDraft | null>(null)

  // The default guard also protects standalone callers. A dynamic storage facade
  // is safe only behind this guard; owner changes must advance the auth epoch.
  let disposed = false
  let operation = 0
  let revision = 0
  if (getCurrentScope()) onScopeDispose(() => { disposed = true })
  let internalUpdate = false
  const setWorking = (config: TemplateConfig) => { internalUpdate = true; templateConfig.value = config; internalUpdate = false }
  watch(templateConfig, () => { if (!internalUpdate) revision += 1 }, { deep: true, flush: 'sync' })
  let selectionRevision = 0
  watch([selectedTemplateId, generationTemplateId], () => { selectionRevision++ }, { flush: 'sync' })
  const beginOperation = () => {
    const epoch = getAuthSessionEpoch()
    const id = ++operation
    const externalCurrent = deps.beginOperation?.() ?? (() => true)
    return () => !disposed && epoch === getAuthSessionEpoch() && id === operation && externalCurrent()
  }
  let intentSeq = 0
  const captureIntent = () => {
    const epoch = getAuthSessionEpoch(), id = ++intentSeq, startedOperation = operation
    const capturedRevision = revision, capturedSelection = selectionRevision
    const externalCurrent = deps.captureIntentCurrent?.() ?? (() => true)
    const templateId = selectedTemplate.value?.id
    const kind = selectedTemplateKind.value
    const isCurrent = () => !disposed && epoch === getAuthSessionEpoch() && startedOperation === operation && id === intentSeq
    return { kind, templateId, isCurrent,
      canApply: () => isCurrent() && externalCurrent() && capturedSelection === selectionRevision && identityUsable() && revision === capturedRevision
        && selectedTemplate.value?.id === templateId && selectedTemplateKind.value === kind }
  }

  const interfaceFields = computed(() => templateConfig.value.interfaceFields)
  const webAutomationFields = computed(() => templateConfig.value.fieldsByKind.web_automation)
  const templateOptions = computed(() => templateKindOptions(templateConfig.value))
  const selectedTemplate = computed(() =>
    templateConfig.value.caseTemplates.find((template) => template.id === selectedTemplateId.value)
      || templateConfig.value.caseTemplates[0]
  )
  const selectedTemplateKind = computed<CaseKind>(() =>
    selectedTemplate.value?.kind || 'interface'
  )
  const selectedTemplateFields = computed(() =>
    templateConfig.value.fieldsByKind[selectedTemplateKind.value] || []
  )
  /** 用例类型管理覆盖全部类别;生成流程的选择仍只取 interface 适用的类型。 */
  const allCaseTypes = computed(() => templateConfig.value.caseTypes)
  const interfaceCaseTypes = computed(() =>
    templateConfig.value.caseTypes.filter((caseType) => caseType.appliesTo === 'interface' || caseType.appliesTo === 'all')
  )
  const enabledInterfaceCaseTypeIds = computed(
    () => new Set(interfaceCaseTypes.value.filter((caseType) => caseType.enabled).map((caseType) => caseType.id))
  )

  const fieldCellLabel = (key: string): string =>
    selectedTemplateFields.value.find((field) => field.key === key)?.label || key

  const isMultilineField = (type: FieldType): boolean => ['textarea', 'json', 'tags'].includes(type)

  const attempts = new Map<TemplateSection, number>()
  let attemptSeq = 0
  let writerRunning = false
  let writerLease: object | null = null
  const writerWaiters = new Map<object, Array<() => void>>()
  const startWriter = () => {
    const lease = {}
    writerLease = lease; writerRunning = true
    return lease
  }
  const settleWriter = (lease: object | null) => {
    if (!lease) return
    if (writerLease === lease) { writerLease = null; writerRunning = false }
    const waiters = writerWaiters.get(lease) ?? []
    writerWaiters.delete(lease)
    waiters.forEach(done => done())
  }
  const waitForWriter = () => {
    const lease = writerLease
    return lease ? new Promise<void>(resolve => writerWaiters.set(lease, [...(writerWaiters.get(lease) ?? []), resolve])) : Promise.resolve()
  }
  let queued = false
  let drainGeneration = 0
  const localAuthorityKey = crypto.randomUUID()
  const producerEnvelope = () => adapter?.producerEnvelope() ?? { authorityKey: localAuthorityKey, writerSeq: null, adoptSeq: drainGeneration }
  const invalidateWorkspace = () => {
    operation++; drainGeneration++; queued = false
    settleWriter(writerLease)
    barrier = null
    for (const [section] of attempts) Object.assign(saveStatus[section], { state: 'failed', error: 'workspace-superseded：模板工作区已失效' })
    attempts.clear()
    adapter?.invalidate()
  }
  const dispose = () => { disposed = true; invalidateWorkspace(); adapter?.dispose() }
  if (getCurrentScope()) onScopeDispose(dispose)
  let barrier: (() => Promise<void>) | null = null

  let explicitRetry = false
  const drainSave = () => {
    if (writerRunning || barrier || !queued) return
    queued = false
    const lease = startWriter()
    const generation = drainGeneration
    const isCurrent = beginOperation()
    const capturedRevision = revision
    const covered = new Map(attempts)
    const target = cloneConfig(templateConfig.value)
    const canWrite = () => isCurrent() && identityUsable()
    const pending = explicitRetry && adapter ? adapter.retrySave(target) : persistTemplateConfig(target, canWrite)
    explicitRetry = false
    void pending.then(saved => {
      if (!canWrite()) throw new Error('模板保存作用域已失效')
      if (revision === capturedRevision) setWorking(saved)
      savedSnapshot = cloneConfig(saved)
      captureSavedStorage()
      const working = JSON.parse(serializeTemplateTarget(templateConfig.value)) as TemplateConfig
      const confirmed = JSON.parse(serializeTemplateTarget(saved)) as TemplateConfig
      for (const section of ['fields', 'types'] as const) {
        const id = covered.get(section)
        if (attempts.has(section) && attempts.get(section) !== id) continue
        const sectionValue = (config: TemplateConfig) => section === 'types' ? config.caseTypes
          : [config.interfaceFields, config.fieldsByKind, config.caseTemplates]
        if (JSON.stringify(sectionValue(working)) !== JSON.stringify(sectionValue(confirmed))) {
          Object.assign(saveStatus[section], { state: 'failed', error: '当前修改未保存，请显式重试保存最新编辑', savedAt: '' })
        } else if (id !== undefined) {
          Object.assign(saveStatus[section], { state: 'saved', error: '', savedAt: clock() })
        }
        if (id !== undefined) attempts.delete(section)
      }
    }).catch((error: unknown) => {
      if (generation !== drainGeneration) return
      queued = false
      for (const [section] of attempts) Object.assign(saveStatus[section], { state: 'failed', error: errorText(error) })
      attempts.clear()
    }).finally(() => {
      settleWriter(lease)
      if (generation === drainGeneration) drainSave()
    })
  }
  const persist = (section: TemplateSection, _intentCurrent?: () => boolean) => {
    activeSection = section
    if (disposed || !identityUsable()) { rebaseToStorage(); return }
    if (!savedStorage) { Object.assign(saveStatus[section],{state:'failed',error:'本地缓存不可读取'});return }
    try { readTemplateConfigRawPair(localStorage) } catch {
      adapter?.markUnreadable()
      Object.assign(localRecovery, { reason: 'unreadable', blocked: true, identityUsable: identityUsable(), owner: identity?.owner ?? '' })
      Object.assign(saveStatus[section],{state:'failed',error:'本地缓存不可读取'});return
    }
    attempts.set(section, ++attemptSeq)
    saveStatus[section].state = 'saving'
    queued = true
    drainSave()
  }

  /** 所有页面的保存状态;调用方按 section 读取。 */
  const saveStatusFor = (section: TemplateSection): TemplateSaveStatus => saveStatus[section]

  /** 兼容旧调用方:当前(最近一次保存所在的)页面状态。 */
  const saveState = computed(() => saveStatus[activeSection].state)
  const saveError = computed(() => saveStatus[activeSection].error)
  const lastSavedAt = computed(() => saveStatus[activeSection].savedAt)

  /**
   * 工作区导入/加载/账号切换后的权威基线；不能继续沿用创建 hook 时的旧快照。
   *
   * `mirror: false` 用于「只是把内存对齐到当前 storage」的重基(owner 已换人时的兜底):
   * 此时持久层已经是权威值,不需要也不应该再写一次。
   */
  const adoptSavedConfig = (event: TemplateAdopt) => {
    const envelope = producerEnvelope()
    if (disposed || event.authorityKey !== envelope.authorityKey || event.writerSeq !== envelope.writerSeq || event.adoptSeq !== envelope.adoptSeq) return
    let accepts = false
    try {
      accepts = adapter ? adapter.acceptsAdoption(event) : event.kind === 'conflict' ? identityUsable() : identityUsable() && !!event.rawPair && event.rawPair !== 'unreadable'
        && JSON.stringify(event.rawPair) === JSON.stringify(readTemplateConfigRawPair(localStorage))
        && templateFingerprintSync(event.config) === templateFingerprintSync(configFromRaw(event.rawPair.config))
    } catch { /* malformed producer facts are never adopted */ }
    if (!accepts) {
      if (adapter && !adapter.matchesEnvelope(event)) return
      for (const k of ['fields','types'] as const) Object.assign(saveStatus[k],{state:'failed',error:'模板来源或镜像凭据无效，未采纳'})
      return
    }
    if (event.kind === 'conflict') return
    operation += 1
    drainGeneration += 1
    attempts.clear(); queued = false; settleWriter(writerLease)
    adapter?.supersedeAdoption()
    if (event.kind === 'clear') {
      setWorking(defaultTemplateConfig())
      fieldDraft.value = null; caseTypeDraft.value = null
      for (const section of ['fields', 'types'] as const) Object.assign(saveStatus[section], idleStatus())
    }
    if (event.kind === 'backend' && event.mirror === 'failed') {
      for (const section of ['fields', 'types'] as const) Object.assign(saveStatus[section], { state: 'failed', error: '服务器已保存，本地缓存未同步' })
      return
    }
    const config = (event.kind === 'local' || event.kind === 'import') && event.rawPair !== 'unreadable' ? configFromRaw(event.rawPair.config) : cloneConfig(event.config)
    templateConfig.value = config
    savedSnapshot = cloneConfig(config)
    savedStorage = event.kind === 'clear' ? event.rawPair === 'unreadable' ? null : event.rawPair ?? null : event.rawPair === 'unreadable' ? null : event.rawPair
    // A reload is a read of B, not confirmation of B or a new discard restore point.
    if (adapter && savedStorage?.pending !== null && savedStorage) savedStorage = adapter.pendingRestorePoint
    if (savedStorage) savedSnapshot = configFromRaw(savedStorage.config)
    for (const section of ['fields', 'types'] as const) {
      Object.assign(saveStatus[section], idleStatus())
      if (event.kind !== 'clear' && event.kind !== 'backend') Object.assign(saveStatus[section], { state: 'failed', error: '本地模板未确认到后端' })
    }
    fieldDraft.value = null; caseTypeDraft.value = null
  }

  const rebaseToStorage = () => {
    for (const section of ['fields', 'types'] as const) Object.assign(saveStatus[section], { state: 'failed', error: '模板工作区身份已失效，请切换工作区或重新登录' })
  }

  /** 设计稿的底部保存条:失败后手动重试,或回到最近一次已保存配置。 */
  const retrySave = (section: TemplateSection = activeSection) => { explicitRetry = true; persist(section) }
  const discardLocal = (section: TemplateSection = activeSection) => {
    // 跨 owner/epoch:手里那对确认字节属于上一个工作区,写回去就是污染别人的前缀。
    // 直接对齐当前 storage,一个字节都不写。
    if (!identityUsable()) {
      rebaseToStorage()
      return
    }
    const snapshot = cloneConfig(savedSnapshot)
    if (!savedStorage || writerRunning) { Object.assign(saveStatus[section], { state: 'failed', error: '本地缓存不可读取或保存仍在进行' }); return }
    const report = restoreTemplateConfigRawPair(localStorage, savedStorage, () => { if (!identityUsable()) throw new Error('identity lost') })
    if (!report.ok) {
      Object.assign(saveStatus[section], { state: 'failed', error: `本地回滚未完成（${report.failedAt === 'meta' ? '元数据' : '配置'}）：修改仍保留在当前界面` })
      return report
    }
    templateConfig.value = snapshot
    Object.assign(saveStatus[section], savedStorage.pending === null ? idleStatus() : { state: 'failed', error: '本地模板未确认', savedAt: '' })
    return report
  }

  const discardUnsaved = (section: TemplateSection = activeSection) => {
    if (!adapter) return discardLocal(section)
    const capturedRevision = revision, capturedIdentity = identity, generation = drainGeneration
    const current = () => !disposed && identity === capturedIdentity && identityUsable() && revision === capturedRevision && generation === drainGeneration
    const work = async () => {
      if (!await confirmDanger('放弃本轮本地编辑', '确认放弃所有模板字段和用例类型未保存编辑？')) return
      await waitForWriter()
      if (!current()) return
      queued = false; attempts.clear()
      try {
        const raw = await adapter!.restore(savedStorage)
        if (!current()) return
        setWorking(configFromRaw(raw.config)); savedSnapshot = cloneConfig(templateConfig.value); savedStorage = raw
        for (const k of ['fields','types'] as const) Object.assign(saveStatus[k], raw.pending === null ? idleStatus() : { state:'failed',error:'本地模板未确认',savedAt:'' })
      } catch(e) { if(current()) for(const k of ['fields','types'] as const) Object.assign(saveStatus[k],{state:'failed',error:errorText(e)}) }
    }
    barrier = work
    return work().finally(()=>{if(barrier===work)barrier=null;drainSave()})
  }
  const patchField = (key: string, patch: Partial<CaseField>) => {
    templateConfig.value = selectedTemplateKind.value === 'interface'
      ? updateCaseField(templateConfig.value, key, patch)
      : updateCaseFieldByKind(templateConfig.value, selectedTemplateKind.value, key, patch)
    persist('fields')
  }

  const fieldsOfKind = (kind: CaseKind): CaseField[] => templateConfig.value.fieldsByKind[kind] || []

  const openFieldDraft = () => {
    fieldDraft.value = {
      kind: selectedTemplateKind.value,
      templateId: selectedTemplate.value?.id,
      key: '',
      label: '',
      type: 'text',
      required: false,
      visible: true,
      exportable: true,
      aiGenerated: false,
      error: ''
    }
  }

  const cancelFieldDraft = () => { fieldDraft.value = null }

  const submitFieldDraft = (): boolean => {
    const draft = fieldDraft.value
    if (!draft) return false
    // 抽屉打开后换了 owner/epoch:草稿属于上一个工作区,不写、直接关掉并对齐当前 storage。
    if (!identityUsable()) {
      fieldDraft.value = null
      rebaseToStorage()
      return false
    }
    const key = draft.key.trim()
    const label = draft.label.trim()
    if (!key) { draft.error = '字段 Key 不能为空'; return false }
    if (!idPattern.test(key)) { draft.error = '字段 Key 需以字母开头，只能包含字母、数字和下划线'; return false }
    if (fieldsOfKind(draft.kind).some((field) => field.key === key)) {
      draft.error = `字段 Key「${key}」在该类别中已存在`
      return false
    }
    if (!label) { draft.error = '中文表头不能为空'; return false }
    const field: CaseField = {
      key,
      label,
      type: draft.type,
      required: draft.required,
      visible: draft.visible,
      exportable: draft.exportable,
      aiGenerated: draft.aiGenerated
    }
    templateConfig.value = draft.kind === 'interface'
      ? addCaseField(templateConfig.value, field)
      : addCaseFieldByKind(templateConfig.value, draft.kind, field)
    const target = draft.templateId
      ? templateConfig.value.caseTemplates.find((template) => template.id === draft.templateId)
      : undefined
    if (target && !target.builtIn && !target.fieldKeys.includes(key)) {
      templateConfig.value = updateCaseTemplate(templateConfig.value, target.id, {
        fieldKeys: [...target.fieldKeys, key]
      })
    }
    fieldDraft.value = null
    persist('fields')
    return true
  }

  const removeField = async (key: string) => {
    const intent = captureIntent()
    if (!intent.canApply()) return
    const confirmed = await confirmDanger('删除字段', `确认删除字段 ${fieldCellLabel(key)} 吗？`)
    if (!confirmed || !intent.canApply()) return
    templateConfig.value = intent.kind === 'interface'
      ? deleteCaseField(templateConfig.value, key)
      : deleteCaseFieldByKind(templateConfig.value, intent.kind, key)
    persist('fields', intent.isCurrent)
  }

  const moveField = (key: string, direction: -1 | 1) => {
    templateConfig.value = selectedTemplateKind.value === 'interface'
      ? moveCaseField(templateConfig.value, key, direction)
      : moveCaseFieldByKind(templateConfig.value, selectedTemplateKind.value, key, direction)
    persist('fields')
  }

  const createCaseTemplate = () => {
    const kind = selectedTemplateKind.value
    const sourceTemplate = selectedTemplate.value
    const template: CaseTemplateDefinition = {
      id: `custom_template_${crypto.randomUUID().replace(/-/g, '_')}`,
      name: `${sourceTemplate?.name || '自定义'}副本`,
      kind,
      builtIn: false,
      enabled: true,
      fieldKeys: selectedTemplateFields.value.map((field) => field.key)
    }
    templateConfig.value = addCaseTemplate(templateConfig.value, template)
    selectedTemplateId.value = template.id
    generationTemplateId.value = template.id
    persist('fields')
  }

  const renameCaseTemplate = (id: string, name: string) => {
    templateConfig.value = updateCaseTemplate(templateConfig.value, id, { name })
    persist('fields')
  }

  const removeCaseTemplate = async (template: CaseTemplateDefinition) => {
    const intent = captureIntent()
    const templateId = template.id
    if (!intent.canApply()) return
    const confirmed = await confirmDanger('删除用例模板', `确认删除模板 ${template.name} 吗？`)
    if (!confirmed || !intent.canApply()) return
    templateConfig.value = deleteCaseTemplate(templateConfig.value, templateId)
    selectedTemplateId.value = templateConfig.value.caseTemplates[0]?.id || 'builtin-interface'
    persist('fields', intent.isCurrent)
  }

  const syncSelectedCaseTypes = () => {
    const enabledIds = enabledInterfaceCaseTypeIds.value
    const current = deps.getGenerationSelectedCaseTypes?.() ?? []
    const next = current.filter((id) => enabledIds.has(id))
    for (const caseType of interfaceCaseTypes.value) {
      if (caseType.enabled && !next.includes(caseType.id)) {
        next.push(caseType.id)
      }
    }
    deps.setGenerationSelectedCaseTypes?.(next)
  }

  const patchCaseType = (id: string, patch: Partial<CaseTypeDefinition>) => {
    templateConfig.value = updateCaseType(templateConfig.value, id, patch)
    syncSelectedCaseTypes()
    persist('types')
  }

  const openCaseTypeDraft = () => {
    caseTypeDraft.value = { id: '', name: '', appliesTo: 'all', enabled: true, error: '' }
  }

  const cancelCaseTypeDraft = () => { caseTypeDraft.value = null }

  const submitCaseTypeDraft = (): boolean => {
    const draft = caseTypeDraft.value
    if (!draft) return false
    if (!identityUsable()) {
      caseTypeDraft.value = null
      rebaseToStorage()
      return false
    }
    const id = draft.id.trim()
    const name = draft.name.trim()
    if (!id) { draft.error = '类型 ID 不能为空'; return false }
    if (!idPattern.test(id)) { draft.error = '类型 ID 需以字母开头，只能包含字母、数字和下划线'; return false }
    if (templateConfig.value.caseTypes.some((caseType) => caseType.id === id)) {
      draft.error = `类型 ID「${id}」已存在`
      return false
    }
    if (!name) { draft.error = '类型名称不能为空'; return false }
    if (templateConfig.value.caseTypes.some((caseType) => caseType.name.trim().toLowerCase() === name.toLowerCase())) {
      draft.error = `类型名称「${name}」已存在`
      return false
    }
    if (!caseKindScopes.includes(draft.appliesTo)) { draft.error = '适用范围不合法'; return false }
    templateConfig.value = addCaseType(templateConfig.value, {
      id,
      name,
      appliesTo: draft.appliesTo,
      enabled: draft.enabled
    })
    syncSelectedCaseTypes()
    caseTypeDraft.value = null
    persist('types')
    return true
  }

  const removeCaseType = async (id: string) => {
    const intent = captureIntent()
    if (!intent.canApply()) return
    const caseTypeName = templateConfig.value.caseTypes.find((caseType) => caseType.id === id)?.name || id
    const confirmed = await confirmDanger('删除用例类型', `确认删除用例类型 ${caseTypeName} 吗？`)
    if (!confirmed || !intent.canApply()) return
    templateConfig.value = deleteCaseType(templateConfig.value, id)
    syncSelectedCaseTypes()
    persist('types', intent.isCurrent)
  }

  const resetConfig = async () => {
    const intent = captureIntent()
    if (!intent.canApply()) return
    let confirmed = false
    try { confirmed = await confirmDanger('恢复默认模板', '确认恢复默认用例模板配置吗？自定义的模板、字段和用例类型将被清除。') } catch { return }
    if (!confirmed || !intent.canApply()) return
    if (adapter) {
      const capturedRevision=revision, ownedIdentity=identity, generation=drainGeneration
      const current=()=>!disposed&&identity===ownedIdentity&&identityUsable()&&revision===capturedRevision&&generation===drainGeneration
      const work=async()=>{await waitForWriter();if(!current())return
        queued=false;attempts.clear()
        try{
          const report=await adapter!.reset(current)
          if(current()&&report.ok){setWorking(defaultTemplateConfig());savedSnapshot=cloneConfig(templateConfig.value);savedStorage=report.rawPair
            Object.assign(saveStatus.fields,idleStatus());Object.assign(saveStatus.types,idleStatus());fieldDraft.value=null;caseTypeDraft.value=null;ElMessage.success('已恢复默认模板')}
          return report
        }catch(e){if(current())for(const k of ['fields','types'] as const)Object.assign(saveStatus[k],{state:'failed',error:errorText(e)});return (e as {partial?:import('../domain/templateConfigRepository').TemplateWriteReport}).partial}
      }
      const ownedBarrier = async()=>{await work()}
      barrier=ownedBarrier
      try{return await work()}finally{if(barrier===ownedBarrier)barrier=null;drainSave()}
    }
    if (writerRunning || !savedStorage) { Object.assign(saveStatus.fields, {state:'failed',error:'保存仍在进行或本地缓存不可读取'}); return }
    const report = removeTemplateConfigKeys(localStorage, () => { if (!intent.canApply()) throw new Error('expired intent') })
    if (!report.ok) {
      for (const section of ['fields','types'] as const) Object.assign(saveStatus[section], { state:'failed', error: report.failedAt === 'meta' ? '配置已清除、元数据残留' : '本地配置清除未完成' })
      return report
    }
    templateConfig.value = defaultTemplateConfig()
    savedSnapshot = cloneConfig(templateConfig.value)
    savedStorage = report.rawPair
    Object.assign(saveStatus.fields, idleStatus()); Object.assign(saveStatus.types, idleStatus())
    deps.setGenerationSelectedCaseTypes?.(interfaceCaseTypes.value.filter(t => t.enabled).map(t => t.id))
    ElMessage.success('已恢复默认模板')
    return report
  }

  const bindWorkspace = (preserveAdapter = false) => {
    if (!preserveAdapter) invalidateWorkspace()
    identity = preserveAdapter ? adapter?.authority ?? deps.identity?.() ?? null : deps.identity?.() ?? null
    localStorage = identity?.storage ?? deps.storage ?? window.localStorage
    if (!preserveAdapter) adapter?.bind(identity)
    savedStorage = adapter ? adapter.rawPair : (() => { try { return readTemplateConfigRawPair(localStorage) } catch { return null } })()
    localEpoch = getAuthSessionEpoch()
    if (!adapter) Object.assign(localRecovery, { reason: !savedStorage ? 'unreadable' : savedStorage.pending !== null ? 'baseline-unknown' : null,
      blocked: !savedStorage || savedStorage.pending !== null, hasPending: !!savedStorage?.pending })
    adoptSavedConfig({ ...producerEnvelope(), kind: 'clear', config: configFromRaw(savedStorage?.config ?? null), rawPair: adapter?.recovery.reason === 'unreadable' ? 'unreadable' : savedStorage ?? undefined })
    if (adapter?.recovery.reason || savedStorage?.pending || !savedStorage) for (const section of ['fields','types'] as const) Object.assign(saveStatus[section], { state: 'failed', error: '本地模板未确认或无法读取' })
  }
  const loadBackend = async (transport?: import('./workspaceStorage').TemplateTransport, options: { throwOnError?: boolean } = {}) => {
    if (!adapter || disposed || writerRunning || barrier) return
    const capturedRevision = revision, generation = drainGeneration
    const lease = startWriter()
    try {
      const config = await adapter.load(transport)
      if (generation !== drainGeneration) return null
      if (revision === capturedRevision) adoptSavedConfig({ ...adapter.producerEnvelope(), kind: 'backend', config, mirror: 'done', rawPair: adapter.rawPair ?? 'unreadable', confirmedUpdatedAt: null })
      else { savedSnapshot = cloneConfig(config); captureSavedStorage() }
      return config
    } catch (e) {
      if (generation !== drainGeneration) return null
      queued = false
      for (const section of ['fields','types'] as const) Object.assign(saveStatus[section], { state: 'failed', error: errorText(e) })
      attempts.clear()
      if (options.throwOnError && (e instanceof PlatformBackendRequestError || e instanceof PlatformBackendTransportError || e instanceof TypeError)) throw e
      return null
    } finally {
      settleWriter(lease)
      if (generation === drainGeneration) drainSave()
    }
  }
  const reconcile = async (mode: 'push-local' | 'take-backend') => {
    if (!adapter) return null
    const startedRevision = revision, ownedIdentity = identity, startedGeneration = drainGeneration
    const current = () => !disposed && identity === ownedIdentity && identityUsable() && revision === startedRevision && drainGeneration === startedGeneration
    // Confirmation is intent-only; the barrier is armed only once the user accepts.
    const ask = async (summary?: string) => {
      const accepted=await confirmDanger(mode==='push-local'?'本地覆盖后端':'采用后端',summary??'确认丢弃所有模板字段、用例类型未保存编辑与草稿？',mode==='push-local'?'覆盖后端':'采用后端')
      if(!accepted||!current())return false
      barrier=work;await waitForWriter();return current()
    }
    const work=async():Promise<void>=>{}
    try {
      if(mode==='take-backend'&&!await ask())return null
      const config = mode === 'push-local'
        ? await adapter.pushLocal(cloneConfig(templateConfig.value), ask, current)
        : await adapter.takeBackend(async()=>true, current)
      if (config && current()) {queued=false;attempts.clear();adoptSavedConfig({ ...adapter.producerEnvelope(), kind: 'backend', config, rawPair: adapter.rawPair ?? 'unreadable', mirror: 'done', confirmedUpdatedAt: null })}
      return config
    } catch(e) {
      if (current()) for (const section of ['fields','types'] as const) Object.assign(saveStatus[section], { state: 'failed', error: errorText(e) })
      return null
    }finally{if(barrier===work)barrier=null;drainSave()}
  }
  const reloadBaseline = async () => {
    if (!adapter) return
    const intent = captureIntent()
    if (JSON.stringify(templateConfig.value) !== JSON.stringify(savedSnapshot) && !await confirmDanger('重新读取本地', '确认放弃当前内存编辑并重新读取本地模板？')) return
    if (!intent.canApply()) return
    try {
      const raw = adapter.reloadBaseline()
      adoptSavedConfig({ ...adapter.producerEnvelope(), kind: 'local', config: configFromRaw(raw.config), rawPair: raw })
    } catch(e) { for (const section of ['fields','types'] as const) Object.assign(saveStatus[section], { state: 'failed', error: errorText(e) }) }
  }
  const retryMirror = async () => {
    const ownedAdapter = adapter
    const ownedIdentity = identity
    const ownedOperation = operation
    const ownedGeneration = drainGeneration
    const ownedEnvelope = ownedAdapter?.producerEnvelope()
    const ownedReceiptId = ownedAdapter?.receipt?.receiptId
    const current = () => !disposed && adapter === ownedAdapter && identity === ownedIdentity && identityUsable()
      && operation === ownedOperation && drainGeneration === ownedGeneration
      && (!ownedAdapter || ownedAdapter.producerEnvelope().authorityKey === ownedEnvelope?.authorityKey
        && ownedAdapter.producerEnvelope().adoptSeq === ownedEnvelope?.adoptSeq)
    if (!ownedAdapter?.receipt || !ownedReceiptId || !current()) return
    try {
      await ownedAdapter.retryMirror(ownedReceiptId)
      if (!current()) return
      captureSavedStorage()
      if (!current()) return
      for (const section of ['fields','types'] as const) if (saveStatus[section].state === 'failed') saveStatus[section].error = '本地缓存已恢复，请显式重试保存最新编辑'
    } catch(e) {
      if (!current()) return
      for (const section of ['fields','types'] as const) Object.assign(saveStatus[section], { state: 'failed', error: errorText(e) })
    }
  }
  const importConfig = async (config: TemplateConfig) => {
    if (!adapter) return false
    const startedRevision=revision, owned=identity, generation=drainGeneration
    const current=()=>!disposed&&identity===owned&&identityUsable()&&revision===startedRevision&&generation===drainGeneration
    if (!await confirmDanger('导入模板配置', '确认丢弃所有未保存的模板字段和类型，导入本地（不会自动覆盖后端）？')) return false
    let applied = false
    const work=async()=>{
      await waitForWriter(); if(!current())return
      queued=false;attempts.clear()
      try {
        const imported=await adapter!.importConfig(config)
        if(current()){adoptSavedConfig({...adapter!.producerEnvelope(),kind:'import',config:imported,rawPair:adapter!.rawPair??'unreadable',mirror:'done'});applied=true}
      } catch(e){if(current())for(const k of ['fields','types'] as const)Object.assign(saveStatus[k],{state:'failed',error:errorText(e)});throw e}
    }
    barrier=work
    try {await work();return applied} finally{if(barrier===work)barrier=null;drainSave()}
  }
  const initialize = (next: TemplateConfigDeps) => {
    // Dependency replacement is a synchronous handoff, never a second shared controller.
    if (next.operationAdapter && next.operationAdapter === adapter && next.storage === deps.storage) return
    invalidateWorkspace()
    if (adapter !== next.operationAdapter) adapter?.dispose()
    deps = next
    adapter = next.operationAdapter
    bindWorkspace(true)
  }
  return {
    initialize,
    isDisposed: () => disposed,
    producerEnvelope,
    importConfig,
    reconcile,
    reloadBaseline,
    retryMirror,
    bindWorkspace,
    loadBackend,
    get recovery() { return adapter?.recovery ?? localRecovery },
    dispose,
    invalidateWorkspace,
    templateConfig,
    selectedTemplateId,
    generationTemplateId,
    interfaceFields,
    webAutomationFields,
    templateOptions,
    selectedTemplate,
    selectedTemplateKind,
    selectedTemplateFields,
    allCaseTypes,
    interfaceCaseTypes,
    enabledInterfaceCaseTypeIds,
    fieldCellLabel,
    isMultilineField,
    fieldDraft,
    caseTypeDraft,
    openFieldDraft,
    cancelFieldDraft,
    submitFieldDraft,
    openCaseTypeDraft,
    cancelCaseTypeDraft,
    submitCaseTypeDraft,
    patchField,
    removeField,
    moveField,
    saveStatus,
    saveStatusFor,
    adoptSavedConfig,
    saveState,
    saveError,
    lastSavedAt,
    retrySave,
    discardUnsaved,
    createCaseTemplate,
    renameCaseTemplate,
    removeCaseTemplate,
    syncSelectedCaseTypes,
    patchCaseType,
    removeCaseType,
    resetConfig
  }
}

export const createLocalTemplateConfig = (deps: TemplateConfigDeps = {}) => createTemplateConfigState(deps)

export const useTemplateConfig = (deps: TemplateConfigDeps = {}): ReturnType<typeof createTemplateConfigState> => {
  if (!singleton || singleton.isDisposed()) singleton = createTemplateConfigState(deps)
  else if (Object.keys(deps).length) singleton.initialize(deps)
  return singleton
}

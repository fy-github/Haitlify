import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { type ExecutionEnvironment } from '../domain/apiExecution'
import { defaultWebExecutionRuntimeConfig, newEnvironmentEntry, parseCurlEnvironmentImport, validateExecutionEnvironment, validateWebExecutionEnvironment, type BuiltWebExecutionRuntime, type EnvironmentEntryType, type SavedExecutionEnvironment } from '../domain/executionEnvironmentRepository'
import { type NavKey } from '../domain/appNavigation'
import type { WebAutomationAction, WebAutomationStep } from '../domain/webAutomationCase'
import type { Ref } from 'vue'
import type { ComputedRef } from 'vue'
import type { EnvironmentKind } from './appPorts'

type NavHistoryMode = 'push' | 'replace' | 'skip'

interface Dependencies {
  activeApiExecutionEnvironment: ComputedRef<SavedExecutionEnvironment | undefined>
  runnableApiExecutionEnvironment: ComputedRef<ExecutionEnvironment | undefined>
  activeWebExecutionEnvironment: ComputedRef<SavedExecutionEnvironment | undefined>
  runnableWebRuntime: ComputedRef<BuiltWebExecutionRuntime | undefined>
  editingEnvironment: SavedExecutionEnvironment
  environmentEditorKind: ComputedRef<EnvironmentKind>
  openEnvironmentEditorCore: (environmentId?: string, kind?: EnvironmentKind) => SavedExecutionEnvironment | undefined
  webEnvironmentVerification: Ref<{ status: "success" | "error"; message: string; detail?: string; } | undefined, { status: "success" | "error"; message: string; detail?: string; } | undefined>
  environmentEditorTarget: Ref<EnvironmentKind, EnvironmentKind>
  navigateToNav: (next: NavKey, mode?: NavHistoryMode, onNavigated?: () => void) => void
  runWithEnvironmentDraftGuard: (message: string, action: () => void) => void
  automationKind: Ref<"api" | "web", "api" | "web">
  addEnvironment: (kind?: EnvironmentKind) => SavedExecutionEnvironment
  deleteEnvironment: (environmentId: string, kind?: EnvironmentKind) => Promise<boolean>
  saveEnvironment: (kind?: EnvironmentKind) => boolean
}

export function createEnvironmentEditor(deps: Dependencies) {

const environmentVisible = ref(false)

const curlImportText = ref('')

function cancelEnvironmentEditing(): void {
  deps.runWithEnvironmentDraftGuard('取消编辑会丢弃当前未保存的环境修改。', () => {
    environmentVisible.value = false
  })
}

const resolveRunnableEnvironment = (): ExecutionEnvironment | undefined => {
  const validation = validateExecutionEnvironment(deps.activeApiExecutionEnvironment.value)
  if (!validation.valid) {
    ElMessage.warning(validation.message)
    environmentVisible.value = true
    openEnvironmentEditor(deps.activeApiExecutionEnvironment.value?.id, 'api')
    return undefined
  }
  return deps.runnableApiExecutionEnvironment.value
}

const resolveWebRuntimeEnvironment = (): BuiltWebExecutionRuntime | undefined => {
  const validation = validateWebExecutionEnvironment(deps.activeWebExecutionEnvironment.value)
  if (!validation.valid) {
    ElMessage.warning(validation.message)
    environmentVisible.value = true
    openEnvironmentEditor(deps.activeWebExecutionEnvironment.value?.id, 'web')
    return undefined
  }
  return deps.runnableWebRuntime.value
}

const webLogoutActionOptions: Array<{ label: string; value: WebAutomationAction }> = [
  { label: '点击元素', value: 'click' },
  { label: '打开地址', value: 'goto' },
  { label: '等待元素', value: 'waitForVisible' },
  { label: '等待地址', value: 'waitForUrl' },
  { label: '断言元素', value: 'assertVisible' },
  { label: '断言地址', value: 'assertUrl' }
]

const addWebLogoutStep = () => {
  if (!deps.editingEnvironment.web) deps.editingEnvironment.web = defaultWebExecutionRuntimeConfig()
  const index = deps.editingEnvironment.web.logout.steps.length
  deps.editingEnvironment.web.logout.steps.push({
    id: `logout-${Date.now()}-${index + 1}`,
    name: index === 0 ? '退出登录' : `退出登录步骤 ${index + 1}`,
    action: 'click',
    targetType: 'locator',
    target: ''
  })
}

const moveWebLogoutStep = (index: number, offset: -1 | 1) => {
  const steps = deps.editingEnvironment.web?.logout.steps
  if (!steps) return
  const targetIndex = index + offset
  if (targetIndex < 0 || targetIndex >= steps.length) return
  const [step] = steps.splice(index, 1)
  steps.splice(targetIndex, 0, step)
}

const removeWebLogoutStep = (index: number) => {
  deps.editingEnvironment.web?.logout.steps.splice(index, 1)
}

const updateWebLogoutStepAction = (step: WebAutomationStep, action: WebAutomationAction) => {
  step.action = action
  step.targetType = action === 'goto' || action === 'waitForUrl' || action === 'assertUrl'
    ? 'url'
    : 'locator'
}

const webLogoutTargetPlaceholder = (step: WebAutomationStep) =>
  step.targetType === 'url' ? '/#/login' : 'testid=logout 或 role=button[name="退出登录"]'

const openEnvironmentEditor = (
  environmentId?: string,
  kind: 'api' | 'web' = deps.environmentEditorKind.value
) => {
  deps.openEnvironmentEditorCore(environmentId, kind)
  curlImportText.value = ''
  deps.webEnvironmentVerification.value = undefined
  environmentVisible.value = true
}

/** 执行准备页 → 环境配置独立页：带上执行类别，并打开同一环境编辑器；返回时回到对应执行准备。 */
const openEnvironmentManagement = (kind: 'api' | 'web') => {
  deps.environmentEditorTarget.value = kind
  deps.navigateToNav('environments')
  openEnvironmentEditor(undefined, kind)
}

/** 环境页内切换 API / Web 类别:只切当前管理类别,不改另一个类别的数据;未保存先确认。 */
const switchEnvironmentManagementKind = (kind: 'api' | 'web') => {
  deps.runWithEnvironmentDraftGuard('切换环境类别会丢弃当前未保存的环境修改。', () => {
    deps.environmentEditorTarget.value = kind
    openEnvironmentEditor(undefined, kind)
  })
}

const selectEnvironmentFromPage = (environmentId: string) => {
  if (environmentId === deps.editingEnvironment.id) return
  deps.runWithEnvironmentDraftGuard('切换到目录里的其他环境会丢弃当前未保存的环境修改。', () => {
    openEnvironmentEditor(environmentId, deps.environmentEditorKind.value)
  })
}

/** 返回执行准备:保留筛选、选中与运行参数上下文;未保存先确认,取消则连页面状态都不动。 */
const returnToPreparationFromEnvironments = (kind: 'api' | 'web') => {
  deps.runWithEnvironmentDraftGuard('返回执行准备会丢弃当前未保存的环境修改。', () => {
    environmentVisible.value = false
    deps.automationKind.value = kind
    deps.navigateToNav('automation')
  })
}

const addExecutionEnvironment = (kind: 'api' | 'web' = deps.environmentEditorKind.value) => {
  const environment = deps.addEnvironment(kind)
  openEnvironmentEditor(environment.id, kind)
}

const deleteExecutionEnvironment = async (environmentId: string, kind: 'api' | 'web' = deps.environmentEditorKind.value) => {
  const deleted = await deps.deleteEnvironment(environmentId, kind)
  if (deleted) openEnvironmentEditor(undefined, kind)
}

const saveEditingEnvironment = (kind: 'api' | 'web' = deps.environmentEditorKind.value) => {
  if (deps.saveEnvironment(kind)) environmentVisible.value = false
}

const addEnvironmentEntry = (type: EnvironmentEntryType = 'header') => {
  deps.editingEnvironment.entries.push(newEnvironmentEntry(type))
}

const deleteEnvironmentEntry = (entryId: string) => {
  deps.editingEnvironment.entries = deps.editingEnvironment.entries.filter((entry) => entry.id !== entryId)
}

const importCurlToEnvironment = () => {
  const isWebEnvironment = deps.environmentEditorKind.value === 'web'
  const parsed = parseCurlEnvironmentImport(curlImportText.value, {
    target: isWebEnvironment ? 'web' : 'api'
  })
  const entries = parsed.entries
  if (entries.length === 0) {
    ElMessage.warning('未识别到 Header 或 Cookie')
    return
  }
  if (!isWebEnvironment && parsed.baseUrl) {
    deps.editingEnvironment.baseUrl = parsed.baseUrl
  }
  if (isWebEnvironment && parsed.baseUrl && !deps.editingEnvironment.baseUrl.trim()) {
    deps.editingEnvironment.baseUrl = parsed.baseUrl
  }
  if (isWebEnvironment && deps.editingEnvironment.web && parsed.requestOrigin) {
    const currentOrigins = deps.editingEnvironment.web.cookieOrigins || []
    deps.editingEnvironment.web.cookieOrigins = Array.from(new Set([...currentOrigins, parsed.requestOrigin]))
  }
  deps.editingEnvironment.auth = parsed.auth
  deps.editingEnvironment.entries.push(...entries)
  curlImportText.value = ''
  if (isWebEnvironment) {
    const baseUrlText = parsed.baseUrl
      ? `应用 Base URL ${deps.editingEnvironment.baseUrl.trim() === parsed.baseUrl ? '已使用' : '已保留'} ${parsed.baseUrl}`
      : '未从 curl 中识别到 Web 入口，已保留当前应用 Base URL'
    const requestOriginText = parsed.requestOrigin ? `；接口域名 ${parsed.requestOrigin} 未作为应用入口` : ''
    ElMessage.success(`已导入 ${entries.length} 个登录态参数，${baseUrlText}${requestOriginText}`)
    return
  }
  ElMessage.success(`已解析 ${entries.length} 个参数，并更新环境基础配置`)
}

const reset = () => {
  curlImportText.value = ''
  environmentVisible.value = false
}
return {
  reset,
  environmentVisible,
  curlImportText,
  cancelEnvironmentEditing,
  resolveRunnableEnvironment,
  resolveWebRuntimeEnvironment,
  webLogoutActionOptions,
  addWebLogoutStep,
  moveWebLogoutStep,
  removeWebLogoutStep,
  updateWebLogoutStepAction,
  webLogoutTargetPlaceholder,
  openEnvironmentEditor,
  openEnvironmentManagement,
  switchEnvironmentManagementKind,
  selectEnvironmentFromPage,
  returnToPreparationFromEnvironments,
  addExecutionEnvironment,
  deleteExecutionEnvironment,
  saveEditingEnvironment,
  addEnvironmentEntry,
  deleteEnvironmentEntry,
  importCurlToEnvironment,
}
}

import { computed, reactive, ref, watch } from 'vue'
import type { ComputedRef } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { defaultJiraConfig, loadJiraConfigFromPlatform, saveJiraConfigToPlatform, testJiraConfigConnection, type JiraConfig } from '../domain/jiraConfigRepository'
import { jiraConnectionSnapshot, jiraDraftSnapshot as jiraDraftSnapshotOf, jiraProjectJqlFromRows, jiraRowsFromProjectJql, validateJiraDraft, jiraTestTargetError, type JiraProjectJqlRow } from '../domain/jiraConfigDraft'

type JiraConfigTestResult = {
  state: 'idle' | 'success' | 'failed' | 'stale'
  message: string
  target: string
}

interface Dependencies {
  beginWorkspaceOperation: (resource: string, templateLease?: boolean) => () => boolean
  canEdit: ComputedRef<boolean>
  confirmDangerAction: (title: string, message: string, confirmButtonText?: string) => Promise<boolean>
}

export function createJiraSettings(deps: Dependencies) {

const jiraConfigForm = reactive<JiraConfig>(defaultJiraConfig())

const jiraConfigLoading = ref(false)

const jiraConfigReadState = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')

const jiraConfigTesting = ref(false)

const jiraProjectJqlRows = ref<JiraProjectJqlRow[]>([])

/** 连接测试的真实结果(供设置页状态块展示;空 result 表示未测试) */
const jiraConfigTestResult = ref<JiraConfigTestResult>({
  state: 'idle',
  message: '',
  target: ''
})

/** 已保存配置的快照(JSON),含 token 与 JQL 行;用于页内与离开守卫判断未保存修改 */
const jiraConfigSavedSnapshot = ref('')

/** 保存前的前端校验结果(页面内 .j-error 呈现) */
const jiraConfigValidationError = ref('')

/** 连接测试世代:参数一变或重新发起,旧响应不得再写状态 */
let jiraTestGeneration = 0

/** 当前草稿快照(含 token 与 JQL 行内输入) */
const jiraDraftSnapshot = computed(() => jiraDraftSnapshotOf(jiraConfigForm, jiraProjectJqlRows.value))

const jiraConfigDirty = computed(() =>
  Boolean(jiraConfigSavedSnapshot.value) && jiraDraftSnapshot.value !== jiraConfigSavedSnapshot.value
)

const applyJiraConfig = (config: JiraConfig, options: { resetTestResult?: boolean } = {}) => {
  Object.assign(jiraConfigForm, config)
  jiraConfigReadState.value = 'ready'
  jiraProjectJqlRows.value = jiraRowsFromProjectJql(config.projectJql)
  jiraConfigSavedSnapshot.value = jiraDraftSnapshotOf(config, jiraProjectJqlRows.value)
  jiraConfigValidationError.value = ''
  // 连接参数变化后,旧的测试结论不再适用;保存(参数未变)则保留结论
  if (options.resetTestResult !== false) {
    jiraTestGeneration += 1
    jiraConfigTestResult.value = { state: 'idle', message: '', target: '' }
  }
}

const syncJiraProjectJqlFormFromRows = () => {
  jiraConfigForm.projectJql = jiraProjectJqlFromRows(jiraProjectJqlRows.value)
}

/** 提交体:行数组是唯一来源,token/地址做去空格处理后再发请求 */
const currentJiraPayload = (): JiraConfig => ({
  ...jiraConfigForm,
  baseUrl: jiraConfigForm.baseUrl.trim(),
  username: jiraConfigForm.username.trim(),
  projectJql: jiraProjectJqlFromRows(jiraProjectJqlRows.value)
})

/** 丢弃未保存草稿前的统一确认(重新加载 / 离开本页共用同一套文案与语义) */
const confirmDiscardJiraDraft = async (message: string): Promise<boolean> => {
  if (!jiraConfigDirty.value) return true
  try {
    await ElMessageBox.confirm(message, '放弃未保存的 Jira 配置？', {
      confirmButtonText: '放弃修改',
      cancelButtonText: '继续编辑',
      type: 'warning',
      customClass: 'theme-confirm-box'
    })
    return true
  } catch {
    return false
  }
}

/**
 * Jira 侧所有「await 之后仍要落动作」的唯一 authority。
 * 发起时固定会话 epoch + 本资源操作序号,await 结束后用同一口径校验一次:
 * owner 切换/重新登录、或同一资源上有更新的操作,都让旧意图失效(不再每处补 flag)。
 */
function captureJiraIntent(): () => boolean {
  return deps.beginWorkspaceOperation('jiraConfig')
}

/** Jira 写动作(保存 / 连接测试 / 映射增删)的唯一门禁:只读角色一律拒绝;读(重新加载)不受限。 */
const rejectJiraWrite = (): boolean => {
  if (deps.canEdit.value) return false
  jiraConfigValidationError.value = '当前账号为只读角色，不能修改或测试 Jira 连接配置。'
  ElMessage.warning('当前账号为只读角色，不能修改或测试 Jira 连接配置。')
  return true
}

const addJiraProjectJqlRow = () => {
  if (rejectJiraWrite()) return
  jiraProjectJqlRows.value.push({ key: '', value: '' })
}

const removeJiraProjectJqlRow = async (index: number) => {
  if (rejectJiraWrite()) return
  const row = jiraProjectJqlRows.value[index]
  // 空行是未填写的草稿行，删除无数据损失；有内容的映射删除前需二次确认。
  if (row && (row.key.trim() || row.value.trim())) {
    const label = row.key.trim()
    const message = label ? `确认删除项目「${label}」的 JQL 映射吗？` : '确认删除这条 JQL 映射吗？'
    // 确认框跨 await:用发起时的行内容 + 会话 authority 判定,旧意图不得 splice 新 owner 的行
    const isCurrent = captureJiraIntent()
    const rowKey = row.key
    const rowValue = row.value
    const confirmed = await deps.confirmDangerAction('删除 JQL 映射', message)
    if (!confirmed || !isCurrent()) return
    const currentIndex = jiraProjectJqlRows.value.findIndex(
      (candidate) => candidate.key === rowKey && candidate.value === rowValue
    )
    if (currentIndex < 0) return
    jiraProjectJqlRows.value.splice(currentIndex, 1)
    syncJiraProjectJqlFormFromRows()
    return
  }
  jiraProjectJqlRows.value.splice(index, 1)
  syncJiraProjectJqlFormFromRows()
}

const loadJiraSettingsFromBackend = async () => {
  const isCurrent = deps.beginWorkspaceOperation('jiraConfig')
  if (!isCurrent()) return
  if (!await confirmDiscardJiraDraft('重新加载会丢弃当前未保存的连接设置与 JQL 映射。')) return
  if (!isCurrent()) return
  jiraConfigLoading.value = true
  jiraConfigReadState.value = 'loading'
  try {
    const config = await loadJiraConfigFromPlatform()
    if (isCurrent()) applyJiraConfig(config)
  } catch {
    // Preserve the form, but never present an unverified read as backend data.
    if (isCurrent()) jiraConfigReadState.value = 'error'
  } finally {
    if (isCurrent()) jiraConfigLoading.value = false
  }
}

const saveJiraSettings = async () => {
  if (rejectJiraWrite()) return
  const isCurrent = deps.beginWorkspaceOperation('jiraConfig')
  if (!isCurrent()) return
  const validation = validateJiraDraft(jiraConfigForm, jiraProjectJqlRows.value)
  if (validation) {
    jiraConfigValidationError.value = validation
    ElMessage.error(validation)
    return
  }
  jiraConfigValidationError.value = ''
  jiraConfigLoading.value = true
  try {
    // 发起时固定提交内容:保存期间的新编辑既不能被服务端回显覆盖,也不能被算作已保存
    const payload = currentJiraPayload()
    const submittedSnapshot = jiraDraftSnapshotOf(payload, jiraProjectJqlRows.value)
    const connectionBefore = jiraConnectionSnapshot(payload)
    const config = await saveJiraConfigToPlatform(payload)
    if (!isCurrent()) return
    jiraConfigReadState.value = 'ready'
    if (jiraDraftSnapshot.value === submittedSnapshot) {
      applyJiraConfig(config, {
        resetTestResult: jiraConnectionSnapshot(config) !== connectionBefore
      })
    } else {
      // 保留更新的草稿,只合并服务端的凭据/时间元数据;已保存基线 = 本次真正提交的内容
      jiraConfigSavedSnapshot.value = submittedSnapshot
      jiraConfigForm.credentialConfigured = config.credentialConfigured
      jiraConfigForm.maskedToken = config.maskedToken
      jiraConfigForm.updatedAt = config.updatedAt
    }
    ElMessage.success('已保存 Jira 配置')
  } catch (error) {
    if (isCurrent()) ElMessage.error(error instanceof Error ? error.message : '保存 Jira 配置失败')
  } finally {
    if (isCurrent()) jiraConfigLoading.value = false
  }
}

const testJiraConnection = async () => {
  if (rejectJiraWrite()) return
  const isCurrent = deps.beginWorkspaceOperation('jiraTest')
  if (!isCurrent()) return
  const validation = jiraTestTargetError(jiraConfigForm)
  if (validation) {
    jiraConfigValidationError.value = validation
    ElMessage.error(validation)
    return
  }
  jiraConfigValidationError.value = ''
  const payload = currentJiraPayload()
  const connection = jiraConnectionSnapshot(payload)
  const target = payload.baseUrl
  const generation = ++jiraTestGeneration
  jiraConfigTesting.value = true
  jiraConfigTestResult.value = { state: 'idle', message: '正在请求 Jira;请稍候。', target }
  try {
    const result = await testJiraConfigConnection(payload)
    if (!isCurrent() || generation !== jiraTestGeneration) return
    if (jiraConnectionSnapshot(jiraConfigForm) !== connection) {
      // 参数已改:只在测试目标仍然成立时保留结论,否则就是新地址的假结论
      jiraConfigTestResult.value = {
        state: 'stale',
        message: '连接参数已改变，原测试状态已失效；请按当前参数重新测试。',
        target
      }
      return
    }
    jiraConfigTestResult.value = {
      state: result.success ? 'success' : 'failed',
      message: result.message || (result.success ? '连接测试通过' : '连接测试未通过'),
      target
    }
    if (result.success) {
      ElMessage.success(result.message || '连接测试通过')
    } else {
      ElMessage.warning(result.message || '连接测试未通过')
    }
  } catch (error) {
    if (!isCurrent() || generation !== jiraTestGeneration) return
    jiraConfigTestResult.value = {
      state: 'failed',
      message: error instanceof Error ? error.message : '连接测试失败',
      target
    }
    ElMessage.error(error instanceof Error ? error.message : '连接测试失败')
  } finally {
    if (isCurrent() && generation === jiraTestGeneration) jiraConfigTesting.value = false
  }
}

watch(jiraDraftSnapshot, () => { jiraConfigValidationError.value = '' })
watch(() => jiraConnectionSnapshot(jiraConfigForm), (next, previous) => {
  if (next === previous) return
  const wasTesting = jiraConfigTesting.value
  jiraTestGeneration += 1
  jiraConfigTesting.value = false
  if (!wasTesting && !['success', 'failed'].includes(jiraConfigTestResult.value.state)) return
  jiraConfigTestResult.value = {
    state: 'stale', message: '连接参数已改变，原测试状态已失效；请按当前参数重新测试。',
    target: jiraConfigTestResult.value.target
  }
})
const reset = () => {
  applyJiraConfig({ ...defaultJiraConfig(), maskedToken: undefined, updatedAt: undefined })
  jiraConfigReadState.value = 'idle'
  jiraConfigLoading.value = false
  jiraConfigTesting.value = false
}
return {
  reset,
  jiraConfigForm,
  jiraConfigLoading,
  jiraConfigReadState,
  jiraConfigTesting,
  jiraProjectJqlRows,
  jiraConfigTestResult,
  jiraConfigSavedSnapshot,
  jiraConfigValidationError,
  get jiraTestGeneration() { return jiraTestGeneration }, set jiraTestGeneration(value: typeof jiraTestGeneration) { jiraTestGeneration = value },
  jiraDraftSnapshot,
  jiraConfigDirty,
  applyJiraConfig,
  syncJiraProjectJqlFormFromRows,
  currentJiraPayload,
  confirmDiscardJiraDraft,
  captureJiraIntent,
  rejectJiraWrite,
  addJiraProjectJqlRow,
  removeJiraProjectJqlRow,
  loadJiraSettingsFromBackend,
  saveJiraSettings,
  testJiraConnection,
}
}

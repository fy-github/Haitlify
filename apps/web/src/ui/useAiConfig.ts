import { computed, reactive, ref, shallowRef } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { missingDep } from './depGuard'
import {
  createAiProviderConfig,
  isAiConfigReady,
  loadAiConfig,
  normalizeAiConfig,
  providerPresets,
  saveAiConfig,
  type AiConfig,
  type AiProviderConfig,
  type AiProviderId
} from '../domain/aiConfigRepository'
import {
  createAiAgent,
  createAiSkill,
  deleteAiRunPolicyOverride,
  listAiAgents,
  listAiCallRecords,
  listAiMcpServers,
  listAiSkills,
  listAiRunPolicies,
  loadAiConfigFromPlatform,
  loadAiRules,
  restoreAiSkillDefault,
  saveAiConfigToPlatform,
  saveAiRules,
  saveAiRunPolicy,
  saveAiRunPolicyOverride,
  saveAiSkillOverride,
  testAiConfigConnection,
  updateAiSkill,
  type AiAgent,
  type AiCallRecord,
  type AiMcpServer,
  type AiRuleConfig,
  type AiRunPolicy,
  type AiRunPolicyList,
  type SaveAiRunPolicyRequest,
  type AiSkill
} from '../domain/aiPlatformRepository'
import { ensureBackendProject } from '../domain/platformBackendRepository'

export interface AiConfigDeps {
  confirmDanger?: (title: string, message: string, confirmLabel?: string) => Promise<boolean>
  // The active project name used when binding Agent assets to a backend project.
  getRequirementProjectName?: () => string
}

// Module-level singleton so App.vue (requirement-analysis environment check +
// sidebar context pane + disclosure banner) and AiConfigView.vue (platform AI
// workbench) share the same AI config state.
let singleton: ReturnType<typeof createAiConfigState> | null = null

function createAiConfigState(deps: AiConfigDeps) {
  const confirmDanger = deps.confirmDanger ?? (async () => missingDep('useAiConfig', 'confirmDanger'))

  const aiRunPolicies = shallowRef<AiRunPolicyList | null>(null)
  const aiRunPoliciesError = ref('')
  const aiPolicyDisclosureDismissed = ref(false)
  const aiRequirementPolicy = computed<AiRunPolicy | null>(
    () => aiRunPolicies.value?.items.find((policy) => policy.taskType === 'requirement_analysis') ?? null
  )
  const refreshAiRunPolicies = async () => {
    try {
      aiRunPolicies.value = await listAiRunPolicies()
      aiRunPoliciesError.value = ''
    } catch (error) {
      aiRunPolicies.value = null
      aiRunPoliciesError.value = error instanceof Error ? error.message : '加载运行策略失败'
    }
  }
  const saveAiRunPolicyEntry = async (policyId: string, request: SaveAiRunPolicyRequest) => {
    try {
      await saveAiRunPolicy(policyId, request)
      await refreshAiRunPolicies()
      ElMessage.success('已保存运行策略')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '保存运行策略失败')
    }
  }
  const saveAiRunPolicyOverrideEntry = async (taskType: string, request: SaveAiRunPolicyRequest) => {
    try {
      await saveAiRunPolicyOverride(taskType, request)
      await refreshAiRunPolicies()
      ElMessage.success('已保存任务覆盖策略')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '保存任务覆盖策略失败')
    }
  }
  const createAiRunPolicyOverride = (taskType: string) => saveAiRunPolicyOverrideEntry(taskType, { enabled: false, routes: [] })
  const deleteAiRunPolicyOverrideEntry = async (taskType: string) => {
    const confirmed = await confirmDanger('删除本任务覆盖', '确认删除当前任务的覆盖策略并恢复默认策略吗？')
    if (!confirmed) return
    try {
      await deleteAiRunPolicyOverride(taskType)
      await refreshAiRunPolicies()
      ElMessage.success('已删除本任务覆盖，恢复默认策略')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '删除本任务覆盖失败')
    }
  }

  const aiConfig = ref<AiConfig>(loadAiConfig(localStorage))
  const aiSettingsForm = reactive<AiConfig>(loadAiConfig(localStorage))

  const aiConfigActiveTab = ref('policies')
  type PlatformAiPage = 'policies' | 'connections' | 'diagnostics' | 'rules' | 'agentAssets'
  const platformAiPage = ref<PlatformAiPage>('policies')
  const platformAiPages: { key: PlatformAiPage; label: string }[] = [
    { key: 'policies', label: '运行策略' },
    { key: 'connections', label: '模型连接' },
    { key: 'diagnostics', label: '运行与诊断' },
    { key: 'rules', label: '规则与扩展' },
    { key: 'agentAssets', label: 'Agent 资产' }
  ]
  const agentAssetsProjectId = ref<number>()
  const prepareAgentAssetsProject = async () => {
    const projectName = deps.getRequirementProjectName?.() || '默认项目'
    try {
      const project = await ensureBackendProject(projectName)
      agentAssetsProjectId.value = project.id
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '绑定 Agent 项目失败')
    }
  }
  const platformAiStatusText = computed(() => (aiConfigReady.value ? '已就绪' : '需处理'))
  const connectionEditorOpen = ref(false)
  const connectionEditorProvider = computed(() => activeAiProvider.value)
  const rulesExtensionTab = ref<'rules' | 'agent' | 'skill' | 'mcp'>('rules')
  const rulesExtensionItems: { key: 'rules' | 'agent' | 'skill' | 'mcp'; label: string }[] = [
    { key: 'rules', label: '评审规则' },
    { key: 'agent', label: 'Agent' },
    { key: 'skill', label: 'Skill' },
    { key: 'mcp', label: 'MCP' }
  ]
  const openConnectionEditor = (providerId?: string) => {
    if (providerId) {
      const target = aiSettingsForm.providers.find((provider) => provider.id === providerId)
      if (target) selectAiProvider(target)
    }
    connectionEditorOpen.value = true
  }
  const connectionSearch = ref('')
  const filteredAiProviders = computed(() => {
    const keyword = connectionSearch.value.trim().toLowerCase()
    const providers = aiSettingsForm.providers
    if (!keyword) return providers
    return providers.filter((provider) =>
      `${provider.name || provider.providerId} ${provider.defaultModel || ''}`.toLowerCase().includes(keyword))
  })
  const connectionStatusLabel = (provider: AiProviderConfig): string => {
    if (!provider.enabled) return '已停用'
    const configured = provider.credentialConfigured || Boolean(provider.maskedApiKey) || Boolean(provider.apiKey)
    return configured ? '正常' : '未配置'
  }
  const connectionStatusClass = (provider: AiProviderConfig): string => {
    const label = connectionStatusLabel(provider)
    if (label === '正常') return 'is-ready'
    if (label === '已停用') return 'is-stopped'
    return 'is-attention'
  }
  const aiConfigLoading = ref(false)
  const aiConnectionTesting = ref(false)
  const aiRulesSaving = ref(false)
  const aiAgentsLoading = ref(false)
  const aiAgentSaving = ref(false)
  const aiSkillsLoading = ref(false)
  const aiSkillSaving = ref(false)
  const aiMcpLoading = ref(false)
  const aiCallRecordsLoading = ref(false)
  const aiAgentDialogVisible = ref(false)
  const aiSkillDialogVisible = ref(false)
  const aiSkillEditingId = ref('')
  const aiRulesForm = reactive({
    forcedHumanReview: true,
    sourceEvidenceRequired: true,
    inferredRequiresConfirmation: true,
    allowedCaseKinds: ['interface', 'web_automation']
  })
  const aiAgentForm = reactive({
    agentId: '',
    name: '',
    description: '',
    agentRole: 'custom',
    skillPath: '',
    skillIds: '',
    promptTemplate: '',
    enabled: true
  })
  const aiSkillForm = reactive({
    skillId: '',
    name: '',
    description: '',
    skillPath: '',
    promptTemplate: '',
    enabled: true
  })
  const aiAgents = shallowRef<AiAgent[]>([])
  const aiSkills = shallowRef<AiSkill[]>([])
  const aiMcpServers = shallowRef<AiMcpServer[]>([])
  const aiCallRecords = shallowRef<AiCallRecord[]>([])

  const aiAgentRows = computed(() => {
    const rows = aiAgents.value.length > 0 ? aiAgents.value : [{
      agentId: 'requirement-understanding',
      name: '需求测试点分析 Agent',
      description: '需求文档、规则、Skill、模板上下文',
      agentRole: 'requirement_understanding',
      promptTemplate: '',
      outputJsonSchema: {},
      allowedTools: [],
      enabled: true,
      builtIn: true,
      customized: false,
      version: 1,
      updatedAt: undefined
    } satisfies AiAgent]
    return rows.map((agent) => ({
      ...agent,
      badge: agent.builtIn ? '内置' : '自定义',
      customizedText: agent.builtIn && agent.customized ? '内置 / 已自定义' : agent.builtIn ? '内置' : '自定义',
      statusText: agent.enabled ? '启用' : '停用',
      skillPathText: skillPathFromAllowedTools(agent.allowedTools) || '-',
      skillIdsText: skillIdsFromAllowedTools(agent.allowedTools).join('、') || '-'
    }))
  })
  const aiSkillRows = computed(() => {
    const rows = aiSkills.value.length > 0 ? aiSkills.value : [{
      skillId: 'requirement-test-point-analysis',
      name: '需求测试点分析',
      description: '分析需求并生成测试点',
      tags: [],
      applicableCaseKinds: [],
      applicableFileTypes: [],
      promptTemplate: '',
      outputJsonSchema: {},
      availableTools: [],
      examples: [],
      skillPath: '',
      enabled: true,
      builtIn: true,
      customized: false,
      version: 1,
      updatedAt: undefined
    } satisfies AiSkill]
    return rows.map((skill) => ({
      ...skill,
      badge: skill.builtIn ? '内置' : '自定义',
      customizedText: skill.builtIn && skill.customized ? '内置 / 已自定义' : skill.builtIn ? '内置' : '自定义',
      skillPathText: skill.skillPath?.trim() || '-',
      statusText: skill.enabled ? '启用' : '停用'
    }))
  })
  const aiMcpRows = computed(() => {
    const rows = aiMcpServers.value.length > 0 ? aiMcpServers.value : [{
      serverId: 'context-mcp',
      name: '需求上下文 MCP',
      serverType: 'stdio / http',
      endpoint: '',
      authMode: 'none',
      availableTools: [],
      toolAllowlist: [],
      projectScope: {},
      writeOperationsAllowed: false,
      timeoutMs: 30000,
      enabled: false,
      auditEnabled: true,
      sandboxConfig: {},
      updatedAt: undefined
    } satisfies AiMcpServer]
    return rows.map((server) => ({
      ...server,
      type: server.serverType,
      writePolicy: server.writeOperationsAllowed ? '写操作已开启' : '写操作默认关闭',
      sandbox: JSON.stringify(server.sandboxConfig) === '{}' ? '项目目录沙箱' : '已配置沙箱',
      status: server.enabled ? '已启用' : '未启用'
    }))
  })
  const aiConfigReady = computed(() => isAiConfigReady(aiConfig.value))
  const showAiPolicyDisclosure = computed(() => {
    const policy = aiRequirementPolicy.value
    return Boolean(
      aiRunPoliciesError.value
      || !aiConfigReady.value
      || !policy
      || !policy.enabled
      || policy.routes.length === 0
      || policy.warnings.length > 0
    )
  })
  const activeAiProvider = computed(() =>
    aiSettingsForm.providers.find((provider) => provider.id === aiSettingsForm.activeProviderId)
      || aiSettingsForm.providers[0]
  )
  const enabledAiProviderCount = computed(() =>
    aiSettingsForm.providers.filter((provider) => provider.enabled).length
  )
  const activeAiProviderPreset = computed(() =>
    providerPresets.find((preset) => preset.providerId === activeAiProvider.value?.providerId)
  )
  const aiProviderStatusText = computed(() => {
    if (!activeAiProvider.value) return '请添加 Provider'
    if (!aiSettingsForm.enabled) return '总开关未启用'
    if (!activeAiProvider.value.enabled) return '当前 Provider 未启用'
    if (!activeAiProvider.value.baseURL.trim()) return '缺少 Base URL'
    if (!aiSettingsForm.activeModel.trim()) return '缺少激活模型'
    if (activeAiProvider.value.providerId !== 'ollama'
      && !activeAiProvider.value.apiKey.trim()
      && !activeAiProvider.value.credentialConfigured) {
      return '缺少 API Key'
    }
    return '配置已就绪'
  })

  const syncAiFormActiveProvider = () => {
    const activeProvider = activeAiProvider.value
    if (!activeProvider) return
    aiSettingsForm.provider = activeProvider.providerId
    aiSettingsForm.baseUrl = activeProvider.baseURL
    aiSettingsForm.model = aiSettingsForm.activeModel || activeProvider.defaultModel
    aiSettingsForm.apiKey = activeProvider.apiKey
    aiSettingsForm.credentialConfigured = activeProvider.credentialConfigured || activeProvider.apiKey.trim().length > 0
    aiSettingsForm.maskedApiKey = activeProvider.maskedApiKey
    aiSettingsForm.requestTimeoutMs = activeProvider.requestTimeoutMs || aiSettingsForm.requestTimeoutMs || 120000
  }

  const buildAiConfigFromForm = (): AiConfig => {
    syncAiFormActiveProvider()
    return normalizeAiConfig(aiSettingsForm)
  }

  const applyAiConfig = (config: AiConfig) => {
    const normalized = normalizeAiConfig(config)
    aiConfig.value = normalized
    Object.assign(aiSettingsForm, normalized)
    saveAiConfig(localStorage, normalized)
  }

  const addAiProvider = (providerId: AiProviderId = 'openai-compatible') => {
    const provider = createAiProviderConfig(providerId)
    aiSettingsForm.providers.push(provider)
    aiSettingsForm.activeProviderId = provider.id
    aiSettingsForm.activeModel = provider.defaultModel
    syncAiFormActiveProvider()
  }

  const addAiProviderFromCommand = (command: string | number | object) => {
    const providerId = String(command) as AiProviderId
    addAiProvider(providerId)
  }

  const selectAiProvider = (provider: AiProviderConfig) => {
    aiSettingsForm.activeProviderId = provider.id
    aiSettingsForm.activeModel = provider.defaultModel || ''
    syncAiFormActiveProvider()
  }

  const removeAiProvider = async (provider: AiProviderConfig) => {
    if (aiSettingsForm.providers.length <= 1) {
      ElMessage.warning('至少保留一个 AI Provider')
      return
    }
    const confirmed = await confirmDanger('删除 Provider', `确认删除 ${provider.name || provider.providerId} 吗？`)
    if (!confirmed) return
    aiSettingsForm.providers = aiSettingsForm.providers.filter((item) => item.id !== provider.id)
    if (aiSettingsForm.activeProviderId === provider.id) {
      const nextProvider = aiSettingsForm.providers.find((item) => item.enabled) || aiSettingsForm.providers[0]
      aiSettingsForm.activeProviderId = nextProvider?.id || ''
      aiSettingsForm.activeModel = nextProvider?.defaultModel || ''
    }
    syncAiFormActiveProvider()
  }

  const updateActiveAiProviderModel = () => {
    const provider = activeAiProvider.value
    if (!provider) return
    provider.defaultModel = aiSettingsForm.activeModel
    syncAiFormActiveProvider()
  }

  const updateActiveAiProviderPreset = () => {
    const provider = activeAiProvider.value
    if (!provider) return
    const preset = providerPresets.find((item) => item.providerId === provider.providerId)
    if (!preset) return
    provider.name = provider.name?.trim() ? provider.name : preset.name
    provider.baseURL = provider.baseURL?.trim() ? provider.baseURL : preset.baseURL
    provider.defaultModel = provider.defaultModel?.trim() ? provider.defaultModel : preset.defaultModel
    aiSettingsForm.activeModel = aiSettingsForm.activeModel?.trim()
      ? aiSettingsForm.activeModel
      : provider.defaultModel
    syncAiFormActiveProvider()
  }

  const saveAiSettings = async () => {
    aiConfigLoading.value = true
    try {
      const saved = await saveAiConfigToPlatform(buildAiConfigFromForm())
      applyAiConfig(saved)
      await refreshAiRunPolicies()
      ElMessage.success(aiConfigReady.value ? '已保存 AI 配置' : '已保存 AI 配置，请继续完善参数')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '保存 AI 配置失败')
    } finally {
      aiConfigLoading.value = false
    }
  }

  const loadAiSettingsFromBackend = async () => {
    aiConfigLoading.value = true
    try {
      applyAiConfig(await loadAiConfigFromPlatform())
      await refreshAiRunPolicies()
      ElMessage.success('已加载后端配置')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '加载后端配置失败')
    } finally {
      aiConfigLoading.value = false
    }
  }

  const testAiConnection = async () => {
    aiConnectionTesting.value = true
    try {
      const result = await testAiConfigConnection(buildAiConfigFromForm())
      if (result.success) {
        // T11：连接测试成功不再自动保存/启用——用户独立保存、验证、激活。
        ElMessage.success(result.message || '连接测试通过（未自动保存，请确认后手动保存）')
      } else {
        ElMessage.warning(result.message || '连接测试未通过')
      }
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '连接测试失败')
    } finally {
      aiConnectionTesting.value = false
    }
  }

  const applyAiRules = (rules: AiRuleConfig) => {
    aiRulesForm.forcedHumanReview = rules.forcedHumanReview
    aiRulesForm.sourceEvidenceRequired = rules.sourceEvidenceRequired
    aiRulesForm.inferredRequiresConfirmation = rules.inferredRequiresConfirmation
    aiRulesForm.allowedCaseKinds = rules.allowedCaseKinds.filter((item): item is string => typeof item === 'string')
  }

  const refreshAiRules = async () => {
    try {
      applyAiRules(await loadAiRules())
    } catch {
      // Keep local defaults when backend is unavailable.
    }
  }

  const saveAiRuleSettings = async () => {
    aiRulesSaving.value = true
    try {
      const saved = await saveAiRules({
        forcedHumanReview: aiRulesForm.forcedHumanReview,
        sourceEvidenceRequired: aiRulesForm.sourceEvidenceRequired,
        inferredRequiresConfirmation: aiRulesForm.inferredRequiresConfirmation,
        allowedCaseKinds: aiRulesForm.allowedCaseKinds,
        formalGenerationStatuses: ['confirmed', 'modified']
      })
      applyAiRules(saved)
      ElMessage.success('已保存规则配置')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '保存规则配置失败')
    } finally {
      aiRulesSaving.value = false
    }
  }

  const skillPathFromAllowedTools = (allowedTools: AiAgent['allowedTools']): string => {
    if (!allowedTools || typeof allowedTools !== 'object' || Array.isArray(allowedTools)) return ''
    const value = allowedTools.skillPath
    return typeof value === 'string' ? value : ''
  }

  const skillIdsFromAllowedTools = (allowedTools: AiAgent['allowedTools']): string[] => {
    if (!allowedTools || typeof allowedTools !== 'object' || Array.isArray(allowedTools)) return []
    const value = allowedTools.skillIds
    return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string' && item.trim().length > 0) : []
  }

  const parseSkillIds = (value: string): string[] =>
    Array.from(new Set(value.split(/[\s,，]+/).map((item) => item.trim()).filter(Boolean)))

  const resetAiAgentForm = () => {
    Object.assign(aiAgentForm, {
      agentId: '',
      name: '',
      description: '',
      agentRole: 'custom',
      skillPath: '',
      skillIds: '',
      promptTemplate: '',
      enabled: true
    })
  }

  const openCreateAiAgentDialog = () => {
    resetAiAgentForm()
    aiAgentDialogVisible.value = true
  }

  const refreshAiAgents = async () => {
    aiAgentsLoading.value = true
    try {
      aiAgents.value = await listAiAgents()
    } catch (error) {
      if (aiConfigActiveTab.value === 'agent') {
        ElMessage.error(error instanceof Error ? error.message : '加载 Agent 失败')
      }
    } finally {
      aiAgentsLoading.value = false
    }
  }

  const createCustomAiAgent = async () => {
    if (!aiAgentForm.agentId.trim() || !aiAgentForm.name.trim()) {
      ElMessage.warning('请填写 Agent ID 和名称')
      return
    }
    const skillIds = parseSkillIds(aiAgentForm.skillIds)
    const allowedTools: Record<string, string | string[]> = {}
    if (aiAgentForm.skillPath.trim()) allowedTools.skillPath = aiAgentForm.skillPath.trim()
    if (skillIds.length) allowedTools.skillIds = skillIds
    aiAgentSaving.value = true
    try {
      const created = await createAiAgent({
        agentId: aiAgentForm.agentId.trim(),
        name: aiAgentForm.name.trim(),
        description: aiAgentForm.description.trim(),
        agentRole: aiAgentForm.agentRole.trim() || 'custom',
        promptTemplate: aiAgentForm.promptTemplate.trim(),
        allowedTools,
        enabled: aiAgentForm.enabled
      })
      aiAgents.value = upsertAiAgent(created)
      aiAgentDialogVisible.value = false
      ElMessage.success('已新增 Agent')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '新增 Agent 失败')
    } finally {
      aiAgentSaving.value = false
    }
  }

  const upsertAiAgent = (agent: AiAgent): AiAgent[] => {
    const exists = aiAgents.value.some((item) => item.agentId === agent.agentId)
    if (!exists) return [...aiAgents.value, agent]
    return aiAgents.value.map((item) => item.agentId === agent.agentId ? agent : item)
  }

  const refreshAiSkills = async () => {
    aiSkillsLoading.value = true
    try {
      aiSkills.value = await listAiSkills()
    } catch (error) {
      if (aiConfigActiveTab.value === 'skills') {
        ElMessage.error(error instanceof Error ? error.message : '加载 Skill 失败')
      }
    } finally {
      aiSkillsLoading.value = false
    }
  }

  const upsertAiSkill = (skill: AiSkill): AiSkill[] => {
    const exists = aiSkills.value.some((item) => item.skillId === skill.skillId)
    if (!exists) return [...aiSkills.value, skill]
    return aiSkills.value.map((item) => item.skillId === skill.skillId ? skill : item)
  }

  const resetAiSkillForm = () => {
    aiSkillEditingId.value = ''
    Object.assign(aiSkillForm, {
      skillId: '',
      name: '',
      description: '',
      skillPath: '',
      promptTemplate: '',
      enabled: true
    })
  }

  const openCreateAiSkillDialog = () => {
    resetAiSkillForm()
    aiSkillDialogVisible.value = true
  }

  const openEditCustomAiSkillDialog = (skill: AiSkill) => {
    if (skill.builtIn) return
    aiSkillEditingId.value = skill.skillId
    Object.assign(aiSkillForm, {
      skillId: skill.skillId,
      name: skill.name,
      description: skill.description,
      skillPath: skill.skillPath || '',
      promptTemplate: skill.promptTemplate,
      enabled: skill.enabled
    })
    aiSkillDialogVisible.value = true
  }

  const saveCustomAiSkill = async () => {
    if (!aiSkillForm.skillId.trim() || !aiSkillForm.name.trim()) {
      ElMessage.warning('请填写 Skill ID 和名称')
      return
    }
    aiSkillSaving.value = true
    try {
      const payload = {
        skillId: aiSkillForm.skillId.trim(),
        name: aiSkillForm.name.trim(),
        description: aiSkillForm.description.trim(),
        skillPath: aiSkillForm.skillPath.trim(),
        promptTemplate: aiSkillForm.promptTemplate.trim(),
        enabled: aiSkillForm.enabled
      }
      const saved = aiSkillEditingId.value
        ? await updateAiSkill(aiSkillEditingId.value, payload)
        : await createAiSkill(payload)
      aiSkills.value = upsertAiSkill(saved)
      aiSkillDialogVisible.value = false
      ElMessage.success(aiSkillEditingId.value ? '已更新 Skill' : '已新增 Skill')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '保存 Skill 失败')
    } finally {
      aiSkillSaving.value = false
    }
  }

  const editBuiltInAiSkill = async (skill: AiSkill) => {
    try {
      const { value } = await ElMessageBox.prompt('编辑内置Skill提示词', '编辑内置Skill', {
        confirmButtonText: '保存',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputValue: skill.promptTemplate
      })
      if (!value?.trim()) return
      const updated = await saveAiSkillOverride(skill.skillId, {
        promptTemplate: value.trim(),
        enabled: skill.enabled
      })
      aiSkills.value = upsertAiSkill(updated)
      ElMessage.success('已保存 Skill 覆盖')
    } catch {
      return
    }
  }

  const restoreBuiltInAiSkill = async (skill: AiSkill) => {
    try {
      const restored = await restoreAiSkillDefault(skill.skillId)
      aiSkills.value = upsertAiSkill(restored)
      ElMessage.success('已恢复默认')
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '恢复默认失败')
    }
  }

  const refreshAiMcpServers = async () => {
    aiMcpLoading.value = true
    try {
      aiMcpServers.value = await listAiMcpServers()
    } catch (error) {
      if (aiConfigActiveTab.value === 'mcp') {
        ElMessage.error(error instanceof Error ? error.message : '加载 MCP 失败')
      }
    } finally {
      aiMcpLoading.value = false
    }
  }

  const refreshAiCallRecords = async () => {
    aiCallRecordsLoading.value = true
    try {
      aiCallRecords.value = (await listAiCallRecords()).items
    } catch (error) {
      if (aiConfigActiveTab.value === 'calls') {
        ElMessage.error(error instanceof Error ? error.message : '加载调用记录失败')
      }
    } finally {
      aiCallRecordsLoading.value = false
    }
  }

  const refreshAiPlatformConfig = async () => {
    try {
      const loaded = await loadAiConfigFromPlatform()
      aiConfig.value = {
        ...loaded,
        apiKey: aiSettingsForm.apiKey
      }
      Object.assign(aiSettingsForm, aiConfig.value)
      saveAiConfig(localStorage, aiConfig.value)
    } catch {
      aiConfig.value = normalizeAiConfig(aiSettingsForm)
    }
    await Promise.allSettled([
      refreshAiRules(),
      refreshAiAgents(),
      refreshAiSkills(),
      refreshAiMcpServers(),
      refreshAiCallRecords(),
      refreshAiRunPolicies()
    ])
  }

  return {
    aiConfig,
    aiSettingsForm,
    aiConfigReady,
    aiRunPolicies,
    aiRunPoliciesError,
    aiPolicyDisclosureDismissed,
    aiRequirementPolicy,
    showAiPolicyDisclosure,
    activeAiProvider,
    enabledAiProviderCount,
    activeAiProviderPreset,
    aiProviderStatusText,
    platformAiStatusText,
    platformAiPage,
    platformAiPages,
    agentAssetsProjectId,
    prepareAgentAssetsProject,
    connectionEditorOpen,
    connectionEditorProvider,
    rulesExtensionTab,
    rulesExtensionItems,
    openConnectionEditor,
    connectionSearch,
    filteredAiProviders,
    connectionStatusLabel,
    connectionStatusClass,
    aiConfigLoading,
    aiConnectionTesting,
    aiRulesSaving,
    aiAgentsLoading,
    aiAgentSaving,
    aiSkillsLoading,
    aiSkillSaving,
    aiMcpLoading,
    aiCallRecordsLoading,
    aiAgentDialogVisible,
    aiSkillDialogVisible,
    aiSkillEditingId,
    aiRulesForm,
    aiAgentForm,
    aiSkillForm,
    aiAgents,
    aiSkills,
    aiMcpServers,
    aiCallRecords,
    aiAgentRows,
    aiSkillRows,
    aiMcpRows,
    refreshAiRunPolicies,
    saveAiRunPolicyEntry,
    saveAiRunPolicyOverrideEntry,
    createAiRunPolicyOverride,
    deleteAiRunPolicyOverrideEntry,
    applyAiConfig,
    addAiProviderFromCommand,
    removeAiProvider,
    updateActiveAiProviderModel,
    updateActiveAiProviderPreset,
    saveAiSettings,
    loadAiSettingsFromBackend,
    testAiConnection,
    saveAiRuleSettings,
    refreshAiAgents,
    refreshAiSkills,
    refreshAiMcpServers,
    refreshAiCallRecords,
    refreshAiPlatformConfig,
    openCreateAiAgentDialog,
    createCustomAiAgent,
    openCreateAiSkillDialog,
    openEditCustomAiSkillDialog,
    saveCustomAiSkill,
    editBuiltInAiSkill,
    restoreBuiltInAiSkill
  }
}

export const useAiConfig = (deps: AiConfigDeps = {}): ReturnType<typeof createAiConfigState> => {
  if (!singleton) singleton = createAiConfigState(deps)
  return singleton
}

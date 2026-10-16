import {
  loadAiConfigFromPlatform,
  saveAiConfigToPlatform,
  type AiPlatformRepositoryOptions
} from './aiPlatformRepository'

export const AI_CONFIG_STORAGE_KEY = 'ai-test-platform.ai-config.v1'

export type AiProviderId =
  | 'openai-compatible'
  | 'openai'
  | 'anthropic'
  | 'google'
  | 'ollama'
  | 'openrouter'
  | 'qwen'
  | 'deepseek'
  | 'kimi'
  | 'zhipu'
  | 'doubao'
  | 'custom'

export interface AiProviderConfig {
  id: string
  providerId: AiProviderId
  name: string
  baseURL: string
  defaultModel: string
  apiKey: string
  enabled: boolean
  credentialConfigured?: boolean
  maskedApiKey?: string
  requestTimeoutMs?: number
  contextWindow?: number
  skipTLSVerify?: boolean
  customHeaders?: Record<string, string>
}

export interface AiConfig {
  enabled: boolean
  provider: AiProviderId
  baseUrl: string
  model: string
  apiKey: string
  credentialConfigured?: boolean
  maskedApiKey?: string
  requestTimeoutMs?: number
  providers: AiProviderConfig[]
  activeProviderId: string
  activeModel: string
  agentModelMap: Record<string, string>
  agentProviderMap: Record<string, string>
  commandTimeoutMs: number
  updatedAt?: string
}

export const defaultAiConfig = (): AiConfig => ({
  enabled: false,
  provider: 'openai-compatible',
  baseUrl: '',
  model: '',
  apiKey: '',
  credentialConfigured: false,
  requestTimeoutMs: 120000,
  providers: [{
    id: 'default',
    providerId: 'openai-compatible',
    name: 'OpenAI Compatible',
    baseURL: '',
    defaultModel: '',
    apiKey: '',
    enabled: false,
    credentialConfigured: false,
    requestTimeoutMs: 120000,
    customHeaders: {}
  }],
  activeProviderId: 'default',
  activeModel: '',
  agentModelMap: {},
  agentProviderMap: {},
  commandTimeoutMs: 120000
})

export const loadAiConfig = (storage: Storage): AiConfig => {
  const raw = storage.getItem(AI_CONFIG_STORAGE_KEY)
  if (!raw) return defaultAiConfig()

  try {
    const parsed = JSON.parse(raw) as Partial<AiConfig>
    return normalizeAiConfig({
      ...defaultAiConfig(),
      ...parsed,
      apiKey: ''
    })
  } catch {
    return defaultAiConfig()
  }
}

export const saveAiConfig = (storage: Storage, config: AiConfig): void => {
  const normalized = normalizeAiConfig(config)
  const sanitized: AiConfig = {
    ...normalized,
    providers: normalized.providers.map((provider) => ({
      ...provider,
      apiKey: '',
      credentialConfigured: provider.credentialConfigured || provider.apiKey.trim().length > 0
    })),
    apiKey: '',
    credentialConfigured: config.credentialConfigured || config.apiKey.trim().length > 0
  }
  storage.setItem(AI_CONFIG_STORAGE_KEY, JSON.stringify(sanitized))
}

export const isAiConfigReady = (config: AiConfig): boolean => {
  const normalized = normalizeAiConfig(config)
  const activeProvider = normalized.providers.find((provider) => provider.id === normalized.activeProviderId)
    || normalized.providers[0]
  return normalized.enabled
    && (!activeProvider || activeProvider.enabled)
    && normalized.baseUrl.trim().length > 0
    && normalized.model.trim().length > 0
    && (
      activeProvider?.providerId === 'ollama'
      || normalized.apiKey.trim().length > 0
      || normalized.credentialConfigured === true
    )
}

export const providerPresets: Array<{
  providerId: AiProviderId
  name: string
  baseURL: string
  defaultModel: string
  helper: string
}> = [
  { providerId: 'openai-compatible', name: 'OpenAI Compatible', baseURL: '', defaultModel: '', helper: '兼容 OpenAI Chat Completions 的自定义网关' },
  { providerId: 'openai', name: 'OpenAI', baseURL: 'https://api.openai.com/v1', defaultModel: 'gpt-4.1', helper: '官方 OpenAI 接入' },
  { providerId: 'openrouter', name: 'OpenRouter', baseURL: 'https://openrouter.ai/api/v1', defaultModel: '', helper: '聚合模型路由' },
  { providerId: 'deepseek', name: 'DeepSeek', baseURL: 'https://api.deepseek.com/v1', defaultModel: 'deepseek-chat', helper: 'DeepSeek OpenAI 兼容接口' },
  { providerId: 'qwen', name: 'Qwen', baseURL: 'https://dashscope.aliyuncs.com/compatible-mode/v1', defaultModel: 'qwen-plus', helper: '通义千问兼容接口' },
  { providerId: 'kimi', name: 'Kimi', baseURL: 'https://api.moonshot.cn/v1', defaultModel: 'moonshot-v1-8k', helper: 'Moonshot/Kimi 兼容接口' },
  { providerId: 'ollama', name: 'Ollama', baseURL: 'http://127.0.0.1:11434/v1', defaultModel: 'llama3.1', helper: '本地模型，通常不需要 API Key' },
  { providerId: 'custom', name: 'Custom Provider', baseURL: '', defaultModel: '', helper: '自定义协议兼容服务' }
]

export const createAiProviderConfig = (providerId: AiProviderId = 'openai-compatible'): AiProviderConfig => {
  const preset = providerPresets.find((item) => item.providerId === providerId) || providerPresets[0]
  const suffix = Date.now().toString(36)
  return {
    id: `${providerId}-${suffix}`,
    providerId: preset.providerId,
    name: preset.name,
    baseURL: preset.baseURL,
    defaultModel: preset.defaultModel,
    apiKey: '',
    enabled: true,
    credentialConfigured: false,
    requestTimeoutMs: 120000
  }
}

export const normalizeAiConfig = (config: Partial<AiConfig>): AiConfig => {
  const base = defaultAiConfig()
  const providers = Array.isArray(config.providers)
    ? config.providers.map(normalizeProvider).filter((provider) => provider.id)
    : []
  const legacyProvider = normalizeProvider({
    id: 'default',
    providerId: config.provider || 'openai-compatible',
    name: providerName(config.provider || 'openai-compatible'),
    baseURL: config.baseUrl || '',
    defaultModel: config.model || '',
    apiKey: config.apiKey || '',
    enabled: config.enabled ?? false,
    credentialConfigured: config.credentialConfigured,
    maskedApiKey: config.maskedApiKey,
    requestTimeoutMs: config.requestTimeoutMs
  })
  const hasLegacyConnectionFields = Boolean(config.baseUrl || config.model || config.apiKey)
  const onlyEmptyDefaultProvider = providers.length === 1
    && providers[0].id === 'default'
    && !providers[0].baseURL
    && !providers[0].defaultModel
    && !providers[0].apiKey
  const normalizedProviders = !providers.length || (onlyEmptyDefaultProvider && hasLegacyConnectionFields)
    ? [legacyProvider]
    : providers
  const activeProviderId = config.activeProviderId
    || normalizedProviders.find((provider) => provider.enabled)?.id
    || normalizedProviders[0]?.id
    || ''
  const activeProvider = normalizedProviders.find((provider) => provider.id === activeProviderId)
    || normalizedProviders[0]
    || legacyProvider
  const activeModel = config.activeModel || activeProvider.defaultModel || config.model || ''

  return {
    ...base,
    ...config,
    providers: normalizedProviders,
    activeProviderId,
    activeModel,
    provider: activeProvider.providerId,
    baseUrl: activeProvider.baseURL,
    model: activeModel,
    apiKey: activeProvider.apiKey || config.apiKey || '',
    credentialConfigured: activeProvider.credentialConfigured || config.credentialConfigured || false,
    maskedApiKey: activeProvider.maskedApiKey || config.maskedApiKey,
    requestTimeoutMs: activeProvider.requestTimeoutMs || config.requestTimeoutMs || 120000,
    agentModelMap: config.agentModelMap || {},
    agentProviderMap: config.agentProviderMap || {},
    commandTimeoutMs: config.commandTimeoutMs || config.requestTimeoutMs || 120000
  }
}

const normalizeProvider = (provider: Partial<AiProviderConfig>): AiProviderConfig => {
  const providerId = provider.providerId || 'openai-compatible'
  const preset = providerPresets.find((item) => item.providerId === providerId)
  return {
    id: provider.id || `provider-${providerId}`,
    providerId,
    name: provider.name || preset?.name || providerName(providerId),
    baseURL: provider.baseURL || preset?.baseURL || '',
    defaultModel: provider.defaultModel || preset?.defaultModel || '',
    apiKey: provider.apiKey || '',
    enabled: provider.enabled !== false,
    credentialConfigured: provider.credentialConfigured || false,
    maskedApiKey: provider.maskedApiKey,
    requestTimeoutMs: provider.requestTimeoutMs || 120000,
    contextWindow: provider.contextWindow,
    skipTLSVerify: provider.skipTLSVerify,
    customHeaders: provider.customHeaders || {}
  }
}

const providerName = (providerId: AiProviderId): string =>
  providerPresets.find((item) => item.providerId === providerId)?.name || 'Custom Provider'

export const loadAiConfigFromBackend = async (
  options: AiPlatformRepositoryOptions = {}
): Promise<AiConfig> =>
  loadAiConfigFromPlatform(options)

export const saveAiConfigToBackend = async (
  config: AiConfig,
  options: AiPlatformRepositoryOptions = {}
): Promise<AiConfig> =>
  saveAiConfigToPlatform(config, options)

// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { defaultAiConfig, normalizeAiConfig } from '../domain/aiConfigRepository'

vi.mock('element-plus', () => ({
  ElMessage: {
    success: vi.fn(),
    warning: vi.fn(),
    error: vi.fn(),
    info: vi.fn()
  },
  ElMessageBox: {
    prompt: vi.fn()
  }
}))

vi.mock('../domain/aiPlatformRepository', async () => {
  const actual = await vi.importActual<typeof import('../domain/aiPlatformRepository')>('../domain/aiPlatformRepository')
  return {
    ...actual,
    testAiConfigConnection: vi.fn(),
    saveAiConfigToPlatform: vi.fn(),
    loadAiConfigFromPlatform: vi.fn(),
    listAiRunPolicies: vi.fn()
  }
})

const loadUseAiConfig = async () => {
  vi.resetModules()
  const { useAiConfig } = await import('./useAiConfig')
  const platform = await import('../domain/aiPlatformRepository')
  return { useAiConfig, platform }
}

describe('useAiConfig', () => {
  it('persists a successful connection test so the platform becomes ready', async () => {
    localStorage.clear()
    const { useAiConfig, platform } = await loadUseAiConfig()
    const mockedPlatform = platform as unknown as {
      testAiConfigConnection: ReturnType<typeof vi.fn>
      saveAiConfigToPlatform: ReturnType<typeof vi.fn>
      listAiRunPolicies: ReturnType<typeof vi.fn>
    }

    mockedPlatform.testAiConfigConnection.mockResolvedValue({ success: true, message: '连接成功' })
    mockedPlatform.saveAiConfigToPlatform.mockImplementation(async (config: Parameters<typeof normalizeAiConfig>[0]) => normalizeAiConfig(config))
    mockedPlatform.listAiRunPolicies.mockResolvedValue({
      items: [{
        taskType: 'requirement_analysis',
        enabled: true,
        routes: [{ position: 0, connectionId: 'openai-main', model: 'gpt-4.1' }],
        warnings: []
      }]
    })

    const state = useAiConfig({ confirmDanger: async () => true })
    state.aiSettingsForm.enabled = true
    state.aiSettingsForm.activeProviderId = 'openai-main'
    state.aiSettingsForm.activeModel = ''
    state.aiSettingsForm.providers = [{
      ...defaultAiConfig().providers[0],
      id: 'openai-main',
      providerId: 'openai',
      name: '',
      baseURL: '',
      defaultModel: '',
      apiKey: 'sk-demo',
      enabled: true,
      credentialConfigured: true
    }]

    await state.testAiConnection()
    await nextTick()

    expect(mockedPlatform.testAiConfigConnection).toHaveBeenCalled()
    // T11：连接测试成功不得自动保存/启用。
    expect(mockedPlatform.saveAiConfigToPlatform).not.toHaveBeenCalled()
    expect(state.aiConfigReady.value).toBe(false)
    expect(state.showAiPolicyDisclosure.value).toBe(true)
  })

  it('does not auto-enable or persist the platform after a successful connection test', async () => {
    localStorage.clear()
    const { useAiConfig, platform } = await loadUseAiConfig()
    const mockedPlatform = platform as unknown as {
      testAiConfigConnection: ReturnType<typeof vi.fn>
      saveAiConfigToPlatform: ReturnType<typeof vi.fn>
      listAiRunPolicies: ReturnType<typeof vi.fn>
    }

    mockedPlatform.testAiConfigConnection.mockResolvedValue({ success: true, message: '连接成功' })
    mockedPlatform.saveAiConfigToPlatform.mockImplementation(async (config: Parameters<typeof normalizeAiConfig>[0]) => normalizeAiConfig(config))
    mockedPlatform.listAiRunPolicies.mockResolvedValue({
      items: [{
        taskType: 'requirement_analysis',
        enabled: true,
        routes: [{ position: 0, connectionId: 'openai-main', model: 'gpt-4.1' }],
        warnings: []
      }]
    })

    const state = useAiConfig({ confirmDanger: async () => true })
    state.aiSettingsForm.enabled = false
    state.aiSettingsForm.activeProviderId = 'openai-main'
    state.aiSettingsForm.activeModel = ''
    state.aiSettingsForm.providers = [{
      ...defaultAiConfig().providers[0],
      id: 'openai-main',
      providerId: 'openai',
      name: '',
      baseURL: '',
      defaultModel: '',
      apiKey: 'sk-demo',
      enabled: false,
      credentialConfigured: true
    }]

    await state.testAiConnection()
    await nextTick()

    // T11：测试成功 ≠ 保存/启用；启用状态保持用户原样，等待手动保存与激活。
    expect(state.aiSettingsForm.enabled).toBe(false)
    expect(state.aiSettingsForm.providers[0]?.enabled).toBe(false)
    expect(mockedPlatform.saveAiConfigToPlatform).not.toHaveBeenCalled()
  })
})

/**
 * T11：连接/模型档案编辑器状态——版本化注册表 + 有效配置溯源。
 *
 * 409（版本冲突）单独呈现为可操作提示，不与一般错误混用；保存成功后刷新注册表。
 */
import { ref } from 'vue'
import * as defaultRepository from '../domain/aiConfigurationRepository'
import type {
  AiConfigurationRepositoryOptions,
  ConnectionView,
  EffectiveOptionsResponse,
  ProfileView,
  RegisterConnectionRequest,
  RegisterProfileRequest
} from '../domain/aiConfigurationRepository'

type ConfigurationRepository = typeof defaultRepository

export const useAiConfiguration = (
  deps: { options?: AiConfigurationRepositoryOptions; repository?: ConfigurationRepository } = {}
) => {
  const repository = deps.repository ?? defaultRepository
  const options = deps.options ?? {}

  const connections = ref<ConnectionView[]>([])
  const profiles = ref<ProfileView[]>([])
  const effectiveOptions = ref<EffectiveOptionsResponse | null>(null)
  const configurationLoading = ref(false)
  const configurationSaving = ref(false)
  const configurationError = ref('')
  const configurationConflict = ref('')

  const describeError = (error: unknown): string => (error instanceof Error ? error.message : String(error))

  const loadConnections = async () => {
    configurationLoading.value = true
    configurationError.value = ''
    try {
      connections.value = await repository.fetchConnections(options)
    } catch (error) {
      configurationError.value = describeError(error)
    } finally {
      configurationLoading.value = false
    }
  }

  const loadProfiles = async () => {
    configurationLoading.value = true
    configurationError.value = ''
    try {
      profiles.value = await repository.fetchProfiles(options)
    } catch (error) {
      configurationError.value = describeError(error)
    } finally {
      configurationLoading.value = false
    }
  }

  const refreshConfiguration = async () => {
    await Promise.all([loadConnections(), loadProfiles()])
  }

  /** 保存连接：409 走 conflict 通道（版本冲突可操作提示），其余错误走 error。 */
  const saveConnection = async (request: RegisterConnectionRequest): Promise<ConnectionView | null> => {
    configurationSaving.value = true
    configurationConflict.value = ''
    configurationError.value = ''
    try {
      const saved = await repository.registerConnection(options, request)
      await loadConnections()
      return saved
    } catch (error) {
      const message = describeError(error)
      if (message.includes('409')) {
        configurationConflict.value = message
      } else {
        configurationError.value = message
      }
      return null
    } finally {
      configurationSaving.value = false
    }
  }

  const saveProfile = async (request: RegisterProfileRequest): Promise<ProfileView | null> => {
    configurationSaving.value = true
    configurationConflict.value = ''
    configurationError.value = ''
    try {
      const saved = await repository.registerProfile(options, request)
      await loadProfiles()
      return saved
    } catch (error) {
      const message = describeError(error)
      if (message.includes('409')) {
        configurationConflict.value = message
      } else {
        configurationError.value = message
      }
      return null
    } finally {
      configurationSaving.value = false
    }
  }

  const loadEffectiveOptions = async (taskType: string, profileId?: string) => {
    configurationLoading.value = true
    configurationError.value = ''
    try {
      effectiveOptions.value = await repository.fetchEffectiveOptions(options, taskType, profileId)
    } catch (error) {
      effectiveOptions.value = null
      configurationError.value = describeError(error)
    } finally {
      configurationLoading.value = false
    }
  }

  return {
    connections,
    profiles,
    effectiveOptions,
    configurationLoading,
    configurationSaving,
    configurationError,
    configurationConflict,
    loadConnections,
    loadProfiles,
    refreshConfiguration,
    saveConnection,
    saveProfile,
    loadEffectiveOptions
  }
}

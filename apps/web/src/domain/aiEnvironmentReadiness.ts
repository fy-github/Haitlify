import { isAiConfigReady, type AiConfig } from './aiConfigRepository'
import type { AiAnalysisLogEntry } from './aiAnalysisProgress'
import type { AiRunPolicy } from './aiPlatformRepository'

export interface AiEnvironmentReadiness {
  ready: boolean
  message: string
  logs: AiAnalysisLogEntry[]
}

const blockedWarningCodes = new Set([
  'missing_primary_route',
  'disabled_provider',
  'missing_credential',
  'unavailable_model',
  'missing_model'
])

const log = (id: string, kind: AiAnalysisLogEntry['kind'], text: string): AiAnalysisLogEntry => ({ id, kind, text })

export const checkAiEnvironmentReadiness = (
  config: AiConfig | null | undefined,
  policy: AiRunPolicy | null | undefined
): AiEnvironmentReadiness => {
  const logs: AiAnalysisLogEntry[] = [log('ai-environment-check-start', 'info', '正在检查 Platform AI 环境…')]

  if (!config || !isAiConfigReady(config)) {
    const reason = !config
      ? '平台 AI 配置未加载'
      : !config.enabled
        ? '平台 AI 未启用'
        : !config.baseUrl.trim()
          ? '模型连接缺少 Base URL'
          : !config.model.trim()
            ? '模型连接未配置模型'
            : '模型连接缺少凭据或当前连接不可用'
    logs.push(log('ai-environment-config-error', 'error', `Platform AI 配置未就绪：${reason}`))
    return { ready: false, message: `Platform AI 未就绪：${reason}`, logs }
  }

  const activeProvider = config.providers.find((provider) => provider.id === config.activeProviderId)
    || config.providers[0]
  logs.push(log(
    'ai-environment-config-ready',
    'info',
    `模型连接配置已就绪：${config.model}${activeProvider?.name ? `（${activeProvider.name}）` : ''}`
  ))

  if (!policy) {
    logs.push(log('ai-environment-policy-error', 'error', '需求分析运行策略未就绪：未找到需求分析策略'))
    return { ready: false, message: '需求分析运行策略未就绪：未找到需求分析策略', logs }
  }
  if (!policy.enabled) {
    logs.push(log('ai-environment-policy-disabled', 'error', '需求分析运行策略未就绪：策略已禁用'))
    return { ready: false, message: '需求分析运行策略未就绪：策略已禁用', logs }
  }

  const primaryRoute = policy.routes.find((route) => route.position === 0)
  const blockingWarning = policy.warnings.find((warning) => blockedWarningCodes.has(warning.code))
  if (!primaryRoute || blockingWarning) {
    const reason = blockingWarning?.message || '策略尚未配置主路由'
    logs.push(log('ai-environment-policy-error', 'error', `需求分析运行策略未就绪：${reason}`))
    return { ready: false, message: `需求分析运行策略未就绪：${reason}`, logs }
  }
  if (!primaryRoute.connectionId || !primaryRoute.model) {
    logs.push(log('ai-environment-route-error', 'error', '需求分析运行策略未就绪：主路由缺少模型连接或模型名称'))
    return { ready: false, message: '需求分析运行策略未就绪：主路由缺少模型连接或模型名称', logs }
  }

  const routedProvider = config.providers.find((provider) => provider.id === primaryRoute.connectionId)
  if (!routedProvider) {
    logs.push(log('ai-environment-route-provider-error', 'error', '需求分析运行策略未就绪：主路由引用的模型连接不存在'))
    return { ready: false, message: '需求分析运行策略未就绪：主路由引用的模型连接不存在', logs }
  }
  if (!routedProvider.enabled) {
    logs.push(log('ai-environment-route-provider-error', 'error', '需求分析运行策略未就绪：主路由模型连接已禁用'))
    return { ready: false, message: '需求分析运行策略未就绪：主路由模型连接已禁用', logs }
  }
  if (routedProvider.providerId !== 'ollama' && !routedProvider.credentialConfigured) {
    logs.push(log('ai-environment-route-provider-error', 'error', '需求分析运行策略未就绪：主路由模型连接缺少凭据'))
    return { ready: false, message: '需求分析运行策略未就绪：主路由模型连接缺少凭据', logs }
  }

  logs.push(log('ai-environment-policy-ready', 'success', `需求分析主路由已就绪：${primaryRoute.model}`))
  return {
    ready: true,
    message: 'Platform AI 环境已就绪',
    logs
  }
}

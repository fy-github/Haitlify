import { describe, expect, it } from 'vitest'
import { defaultAiConfig } from './aiConfigRepository'
import { checkAiEnvironmentReadiness } from './aiEnvironmentReadiness'
import type { AiRunPolicy } from './aiPlatformRepository'

const readyConfig = () => ({
  ...defaultAiConfig(),
  enabled: true,
  baseUrl: 'https://api.example.com/v1',
  model: 'gpt-5.4',
  apiKey: 'sk-test',
  credentialConfigured: true,
  providers: [{
    ...defaultAiConfig().providers[0],
    id: 'connection-1',
    baseURL: 'https://api.example.com/v1',
    defaultModel: 'gpt-5.4',
    apiKey: 'sk-test',
    credentialConfigured: true,
    enabled: true
  }],
  activeProviderId: 'connection-1',
  activeModel: 'gpt-5.4'
})

const policy = (overrides: Partial<AiRunPolicy> = {}): AiRunPolicy => ({
  policyId: 'requirement-analysis-default',
  scope: 'default',
  taskType: 'requirement_analysis',
  name: '需求分析默认策略',
  enabled: true,
  perCallTimeoutMs: 120000,
  wholeRunDeadlineMs: 900000,
  retryBudget: 1,
  maxInputChars: 100000,
  routes: [{ position: 0, connectionId: 'connection-1', model: 'gpt-5.4' }],
  warnings: [],
  ...overrides
})

describe('checkAiEnvironmentReadiness', () => {
  it('accepts a configured connection and primary route', () => {
    const result = checkAiEnvironmentReadiness(readyConfig(), policy())
    expect(result.ready).toBe(true)
    expect(result.logs.map((entry) => entry.text).join('\n')).toContain('主路由已就绪')
  })

  it('blocks a policy without a primary route', () => {
    const result = checkAiEnvironmentReadiness(readyConfig(), policy({ routes: [], warnings: [{ code: 'missing_primary_route', message: '策略尚未配置主路由' }] }))
    expect(result.ready).toBe(false)
    expect(result.message).toContain('策略尚未配置主路由')
    expect(result.logs[result.logs.length - 1]?.kind).toBe('error')
  })

  it('blocks disabled or incomplete AI configuration', () => {
    const result = checkAiEnvironmentReadiness({ ...readyConfig(), enabled: false }, policy())
    expect(result.ready).toBe(false)
    expect(result.message).toContain('平台 AI 未启用')
  })
})

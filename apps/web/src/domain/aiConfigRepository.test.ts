import { describe, expect, it, vi } from 'vitest'
import {
  defaultAiConfig,
  isAiConfigReady,
  loadAiConfigFromBackend,
  loadAiConfig,
  normalizeAiConfig,
  saveAiConfigToBackend,
  saveAiConfig,
  type AiConfig
} from './aiConfigRepository'

class MemoryStorage implements Storage {
  private values = new Map<string, string>()

  get length() {
    return this.values.size
  }
  clear(): void { this.values.clear() }
  getItem(key: string): string | null { return this.values.get(key) ?? null }
  key(index: number): string | null { return Array.from(this.values.keys())[index] ?? null }
  removeItem(key: string): void { this.values.delete(key) }
  setItem(key: string, value: string): void { this.values.set(key, value) }
}

describe('aiConfigRepository', () => {
  it('loads default ai config when storage is empty', () => {
    expect(loadAiConfig(new MemoryStorage())).toEqual(defaultAiConfig())
  })

  it('saves and reloads sanitized ai config summaries locally', () => {
    const storage = new MemoryStorage()
    const config: AiConfig = {
      ...defaultAiConfig(),
      enabled: true,
      provider: 'openai-compatible',
      baseUrl: 'https://api.example.com/v1',
      model: 'gpt-4.1',
      apiKey: 'sk-demo'
    }

    saveAiConfig(storage, config)

    expect(storage.getItem('ai-test-platform.ai-config.v1')).not.toContain('sk-demo')
    expect(loadAiConfig(storage)).toMatchObject({
      apiKey: '',
      credentialConfigured: true,
      requestTimeoutMs: 120000,
      activeProviderId: 'default',
      activeModel: 'gpt-4.1',
      providers: [expect.objectContaining({
        id: 'default',
        providerId: 'openai-compatible',
        baseURL: 'https://api.example.com/v1',
        defaultModel: 'gpt-4.1',
        apiKey: '',
        credentialConfigured: true
      })]
    })
    expect(isAiConfigReady(config)).toBe(true)
  })

  it('requires enabled flag, base url, model, and api key to be ready', () => {
    expect(isAiConfigReady(defaultAiConfig())).toBe(false)
    expect(isAiConfigReady({
      ...defaultAiConfig(),
      enabled: true,
      baseUrl: 'https://api.example.com/v1',
      model: 'gpt-4.1',
      credentialConfigured: true
    })).toBe(true)
  })

  it('fills provider preset defaults when normalizing provider-only configs', () => {
    const normalized = normalizeAiConfig({
      ...defaultAiConfig(),
      enabled: true,
      activeProviderId: 'openai-main',
      activeModel: '',
      providers: [{
        id: 'openai-main',
        providerId: 'openai' as const,
        name: '',
        baseURL: '',
        defaultModel: '',
        apiKey: 'sk-demo',
        enabled: true
      }]
    })

    expect(normalized.baseUrl).toBe('https://api.openai.com/v1')
    expect(normalized.model).toBe('gpt-4.1')
    expect(normalized.providers[0].defaultModel).toBe('gpt-4.1')
    expect(isAiConfigReady(normalized)).toBe(true)
  })

  it('loads and saves real AI config through backend helpers', async () => {
    const fetchImpl = vi.fn((input: string, init?: RequestInit) => {
      if (init?.method === 'PUT') {
        expect(input).toBe('http://127.0.0.1:18080/api/ai/config')
        expect(JSON.parse(String(init.body))).toMatchObject({
          enabled: true,
          providerType: 'openai-compatible',
          baseUrl: 'https://api.example.com/v1',
          model: 'gpt-4.1',
          apiKey: 'sk-demo',
          requestTimeoutMs: 120000,
          activeProviderId: 'default',
          activeModel: 'gpt-4.1',
          providers: [expect.objectContaining({
            id: 'default',
            providerId: 'openai-compatible',
            baseURL: 'https://api.example.com/v1',
            defaultModel: 'gpt-4.1',
            apiKey: 'sk-demo'
          })]
        })
      }
      return Promise.resolve(new Response(JSON.stringify({
        enabled: true,
        providerType: 'openai-compatible',
        baseUrl: 'https://api.example.com/v1',
        model: 'gpt-4.1',
        credentialConfigured: true,
        maskedApiKey: '****demo',
        requestTimeoutMs: 120000,
        updatedAt: '2026-07-03T10:00:00Z'
      }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      }))
    })

    const saved = await saveAiConfigToBackend({
      ...defaultAiConfig(),
      enabled: true,
      provider: 'openai-compatible',
      baseUrl: 'https://api.example.com/v1',
      model: 'gpt-4.1',
      apiKey: 'sk-demo'
    }, { fetchImpl })
    const loaded = await loadAiConfigFromBackend({ fetchImpl })

    expect(saved.apiKey).toBe('')
    expect(saved.maskedApiKey).toBe('****demo')
    expect(loaded.credentialConfigured).toBe(true)
    expect(fetchImpl).toHaveBeenCalledTimes(2)
  })
})

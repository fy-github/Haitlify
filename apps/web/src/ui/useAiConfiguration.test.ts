import { describe, expect, it, vi } from 'vitest'
import { useAiConfiguration } from './useAiConfiguration'

const stubRepository = (overrides: Record<string, unknown> = {}) => ({
  fetchConnections: vi.fn(async () => []),
  fetchProfiles: vi.fn(async () => []),
  registerConnection: vi.fn(),
  registerProfile: vi.fn(),
  fetchEffectiveOptions: vi.fn(),
  ...overrides
})

describe('useAiConfiguration', () => {
  it('loads registries and keeps loading state deterministic', async () => {
    const repository = stubRepository({
      fetchConnections: vi.fn(async () => [{ connectionId: 'conn-a', version: 1 }]),
      fetchProfiles: vi.fn(async () => [{ profileId: 'p-a', version: 1 }])
    })
    const state = useAiConfiguration({ repository: repository as never })

    await state.refreshConfiguration()

    expect(state.connections.value).toHaveLength(1)
    expect(state.profiles.value).toHaveLength(1)
    expect(state.configurationLoading.value).toBe(false)
    expect(state.configurationError.value).toBe('')
  })

  it('routes a 409 conflict to the conflict channel and leaves error empty', async () => {
    const repository = stubRepository({
      registerConnection: vi.fn(async () => {
        throw new Error('AI configuration request failed: 409 connection version conflict for conn-a: expected 1, actual 2')
      })
    })
    const state = useAiConfiguration({ repository: repository as never })

    const saved = await state.saveConnection({
      connectionId: 'conn-a',
      baseUrl: 'https://a.test/v2',
      credentialRef: 'alias:a',
      protocol: 'CHAT_COMPLETIONS',
      expectedVersion: 1
    })

    expect(saved).toBeNull()
    expect(state.configurationConflict.value).toContain('409')
    expect(state.configurationConflict.value).toContain('expected 1, actual 2')
    expect(state.configurationError.value).toBe('')
  })

  it('routes other failures to the error channel and clears conflict', async () => {
    const repository = stubRepository({
      registerProfile: vi.fn(async () => {
        throw new Error('AI configuration request failed: 400 unknown capability: BOGUS')
      })
    })
    const state = useAiConfiguration({ repository: repository as never })
    state.configurationConflict.value = 'stale conflict'

    await state.saveProfile({ profileId: 'p', connectionId: 'c', modelId: 'm' })

    expect(state.configurationError.value).toContain('unknown capability')
    expect(state.configurationConflict.value).toBe('')
  })

  it('reloads the registry after a successful save and surfaces effective options', async () => {
    const fetchConnections = vi.fn(async () => [])
    const repository = stubRepository({
      fetchConnections,
      registerConnection: vi.fn(async () => ({ connectionId: 'conn-a', version: 1 })),
      fetchEffectiveOptions: vi.fn(async () => ({ taskType: 'requirement_analysis', timeout: { value: 180000, source: 'task_override', sourceVersion: 'v', present: true }, declaredCapabilities: [], verificationJson: '{...}', taskTypes: [], notes: {} }))
    })
    const state = useAiConfiguration({ repository: repository as never })

    await state.saveConnection({ connectionId: 'conn-a', baseUrl: 'https://a.test/v1', credentialRef: 'alias:a', protocol: 'CHAT_COMPLETIONS' })
    expect(fetchConnections).toHaveBeenCalledTimes(1)

    await state.loadEffectiveOptions('requirement_analysis', 'p-a')
    expect(state.effectiveOptions.value?.timeout.source).toBe('task_override')
  })
})

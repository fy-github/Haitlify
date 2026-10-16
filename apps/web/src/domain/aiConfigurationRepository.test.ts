import { describe, expect, it, vi } from 'vitest'
import {
  fetchConnection,
  fetchConnections,
  fetchEffectiveOptions,
  fetchProfile,
  fetchProfiles,
  registerConnection,
  registerProfile
} from './aiConfigurationRepository'

const okJson = (payload: unknown) => ({ ok: true, status: 200, json: async () => payload })

const makeOptions = () => {
  const fetchMock = vi.fn(async () => okJson([]) as unknown as Response)
  return {
    fetchMock,
    options: { baseUrl: 'http://backend.local', fetchImpl: fetchMock as unknown as typeof fetch }
  }
}

describe('aiConfigurationRepository', () => {
  it('fetches the versioned connection list and a pinned version', async () => {
    const { fetchMock, options } = makeOptions()
    await fetchConnections(options)
    expect(fetchMock).toHaveBeenCalledWith('http://backend.local/api/ai/configuration/connections', undefined)

    await fetchConnection(options, 'conn a', 2)
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      'http://backend.local/api/ai/configuration/connections/conn%20a?version=2',
      undefined
    )
    await fetchConnection(options, 'conn-a')
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      'http://backend.local/api/ai/configuration/connections/conn-a',
      undefined
    )
  })

  it('registers a connection with only a credential reference in the body', async () => {
    const { fetchMock, options } = makeOptions()
    fetchMock.mockResolvedValue(
      okJson({
        connectionId: 'conn-a',
        version: 1,
        baseUrl: 'https://a.test/v1',
        credentialRef: 'alias:a',
        protocol: 'CHAT_COMPLETIONS',
        enabled: true,
        allowedProjectIds: [1]
      }) as unknown as Response
    )
    const created = await registerConnection(options, {
      connectionId: 'conn-a',
      baseUrl: 'https://a.test/v1',
      credentialRef: 'alias:a',
      protocol: 'CHAT_COMPLETIONS',
      enabled: true,
      allowedProjectIds: [1],
      expectedVersion: 0
    })

    expect(created.version).toBe(1)
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('http://backend.local/api/ai/configuration/connections')
    expect(init.method).toBe('POST')
    const body = JSON.parse(String(init.body)) as Record<string, unknown>
    expect(body.credentialRef).toBe('alias:a')
    expect(body.expectedVersion).toBe(0)
    // 敏感字段纪律：请求体不携带任何密钥材料字段。
    for (const forbidden of ['apiKey', 'api_key', 'token', 'secret', 'password']) {
      expect(Object.keys(body)).not.toContain(forbidden)
    }
  })

  it('registers a profile and reads pinned versions', async () => {
    const { fetchMock, options } = makeOptions()
    fetchMock.mockResolvedValue(
      okJson({
        profileId: 'p-1',
        version: 1,
        connectionId: 'conn-a',
        modelId: 'gpt-x',
        capabilities: ['TEXT'],
        parameterPolicyJson: '{}',
        verificationJson: '{"status":"UNKNOWN"}'
      }) as unknown as Response
    )
    await registerProfile(options, {
      profileId: 'p-1',
      connectionId: 'conn-a',
      modelId: 'gpt-x',
      capabilities: ['TEXT']
    })
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('http://backend.local/api/ai/configuration/profiles')
    expect(init.method).toBe('POST')

    await fetchProfile(options, 'p-1', 3)
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      'http://backend.local/api/ai/configuration/profiles/p-1?version=3',
      undefined
    )
    await fetchProfiles(options)
    expect(fetchMock).toHaveBeenNthCalledWith(3, 'http://backend.local/api/ai/configuration/profiles', undefined)
  })

  it('fetches effective options with provenance and optional profile scope', async () => {
    const { fetchMock, options } = makeOptions()
    fetchMock.mockResolvedValue(
      okJson({
        taskType: 'requirement_analysis',
        profileId: 'p-1',
        profileVersion: 2,
        timeout: { value: 180000, source: 'task_override', sourceVersion: 'policy@task_override', present: true },
        declaredCapabilities: ['TEXT'],
        verificationJson: '{"status":"UNKNOWN"}',
        taskTypes: ['requirement_analysis'],
        notes: {}
      }) as unknown as Response
    )
    const effective = await fetchEffectiveOptions(options, 'requirement_analysis', 'p 1')
    expect(effective.timeout.source).toBe('task_override')
    expect(fetchMock).toHaveBeenCalledWith(
      'http://backend.local/api/ai/configuration/effective-options?taskType=requirement_analysis&profileId=p%201',
      undefined
    )

    await fetchEffectiveOptions(options, 'bug_analysis')
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      'http://backend.local/api/ai/configuration/effective-options?taskType=bug_analysis',
      undefined
    )
  })

  it('surfaces backend conflict messages verbatim for actionable UI copy', async () => {
    const fetchMock = vi.fn(async () => ({
      ok: false,
      status: 409,
      json: async () => ({ message: 'connection version conflict for conn-a: expected 1, actual 2' })
    })) as unknown as typeof fetch
    const options = { baseUrl: 'http://backend.local', fetchImpl: fetchMock }

    await expect(
      registerConnection(options, {
        connectionId: 'conn-a',
        baseUrl: 'https://a.test/v2',
        credentialRef: 'alias:a',
        protocol: 'CHAT_COMPLETIONS',
        expectedVersion: 1
      })
    ).rejects.toThrow('409 connection version conflict for conn-a: expected 1, actual 2')
  })
})

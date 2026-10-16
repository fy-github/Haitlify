import { describe, expect, it, vi } from 'vitest'
import {
  buildRedactedDiagnostics,
  cancelAnalysisRun,
  cancelJob,
  fetchJobAudit,
  fetchRunEvents,
  fetchStoredCapabilityCheck,
  latestEventSeq,
  retryAnalysisRun,
  runCapabilityCheck,
  submitJob
} from './aiRuntimeRepository'

const okJson = (payload: unknown) => ({ ok: true, status: 200, json: async () => payload })

const makeOptions = () => {
  const fetchMock = vi.fn(async () => okJson({ items: [] }) as unknown as Response)
  return {
    fetchMock,
    options: { baseUrl: 'http://backend.local', fetchImpl: fetchMock as unknown as typeof fetch }
  }
}

describe('aiRuntimeRepository', () => {
  it('fetches run events with an afterSeq anchor for reconnect paging', async () => {
    const { fetchMock, options } = makeOptions()
    await fetchRunEvents(options, 'run-1', 42)
    expect(fetchMock).toHaveBeenCalledWith(
      'http://backend.local/api/ai/analysis-runs/run-1/events?afterSeq=42',
      undefined
    )
  })

  it('omits the afterSeq query entirely on a first full load', async () => {
    const { fetchMock, options } = makeOptions()
    await fetchRunEvents(options, 'run-1')
    expect(fetchMock).toHaveBeenCalledWith('http://backend.local/api/ai/analysis-runs/run-1/events', undefined)
  })

  it('passes job audit afterSeq through and surfaces backend error messages', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(okJson({ job: { jobId: 'job-1' }, nodes: [], attempts: [], events: [] }))
      .mockResolvedValueOnce({ ok: false, status: 404, json: async () => ({ message: 'job_not_found: job-9' }) })
    const options = { baseUrl: 'http://backend.local', fetchImpl: fetchMock as unknown as typeof fetch }
    const audit = await fetchJobAudit(options, 'job-1', 7)
    expect(fetchMock).toHaveBeenNthCalledWith(1, 'http://backend.local/api/ai/jobs/job-1?afterSeq=7', undefined)
    expect(audit.job.jobId).toBe('job-1')
    await expect(fetchJobAudit(options, 'job-9')).rejects.toThrow('job_not_found: job-9')
  })

  it('cancels a job with the requesting actor and cancels/retries analysis runs via POST', async () => {
    const { fetchMock, options } = makeOptions()
    fetchMock.mockResolvedValue(okJson({ outcome: 'REQUESTED' }) as unknown as Response)
    await cancelJob(options, 'job-1', 'qa user')
    expect(fetchMock).toHaveBeenCalledWith(
      'http://backend.local/api/ai/jobs/job-1/cancel?requestedBy=qa%20user',
      expect.objectContaining({ method: 'POST' })
    )
    await cancelAnalysisRun(options, 'run-2')
    expect(fetchMock).toHaveBeenCalledWith(
      'http://backend.local/api/ai/analysis-runs/run-2/cancel',
      expect.objectContaining({ method: 'POST' })
    )
    await retryAnalysisRun(options, 'run-2')
    expect(fetchMock).toHaveBeenCalledWith(
      'http://backend.local/api/ai/analysis-runs/run-2/retry',
      expect.objectContaining({ method: 'POST' })
    )
  })

  it('submits jobs with an idempotency key so retries cannot double-submit', async () => {
    const { fetchMock, options } = makeOptions()
    fetchMock.mockResolvedValue(okJson({ jobId: 'job-3' }) as unknown as Response)
    await submitJob(options, { jobType: 'multi_agent_run', projectId: 1, idempotencyKey: 'idem-1' })
    const call = fetchMock.mock.calls[fetchMock.mock.calls.length - 1] as unknown as [string, RequestInit]
    expect(call[0]).toBe('http://backend.local/api/ai/jobs')
    expect(call[1].method).toBe('POST')
    expect(JSON.parse(String(call[1].body))).toMatchObject({ jobType: 'multi_agent_run', idempotencyKey: 'idem-1' })
  })

  it('runs capability probes read-only unless persist is explicitly true', async () => {
    const { fetchMock, options } = makeOptions()
    fetchMock.mockResolvedValue(okJson({ profileId: 'p1', profileVersion: 2, overall: 'pass', probes: [] }) as unknown as Response)
    await runCapabilityCheck(options, { profileId: 'p1', profileVersion: 2 })
    let call = fetchMock.mock.calls[fetchMock.mock.calls.length - 1] as unknown as [string, RequestInit]
    expect(JSON.parse(String(call[1].body))).toEqual({ profileId: 'p1', profileVersion: 2, persist: false })
    await runCapabilityCheck(options, { profileId: 'p1', profileVersion: 2, persist: true })
    call = fetchMock.mock.calls[fetchMock.mock.calls.length - 1] as unknown as [string, RequestInit]
    expect(JSON.parse(String(call[1].body)).persist).toBe(true)
  })

  it('reads the stored capability report with freshness against the current config hash', async () => {
    const fetchMock = vi.fn(async () => okJson({ fresh: false, currentConfigHash: 'hash-2', report: null }) as unknown as Response)
    const options = { baseUrl: 'http://backend.local', fetchImpl: fetchMock as unknown as typeof fetch }
    const stored = await fetchStoredCapabilityCheck(options, 'p 1', 3)
    expect(fetchMock).toHaveBeenCalledWith(
      'http://backend.local/api/ai/capability-check?profileId=p%201&profileVersion=3',
      undefined
    )
    expect(stored.fresh).toBe(false)
  })

  it('computes the next afterSeq anchor from the event stream, tolerating missing sequence numbers', () => {
    expect(latestEventSeq([{ eventSeq: 3 } as never, { eventSeq: 11 } as never, {} as never])).toBe(11)
    expect(latestEventSeq([])).toBe(0)
  })

  it('redacts credentials from diagnostics before they can be copied', () => {
    const authorization = ['Author', 'ization'].join('')
    const authScheme = ['Bear', 'er'].join('')
    const apiKeyParam = ['api', '_key'].join('')
    const apiKeyLabel = ['api', 'Key'].join('')
    const syntheticOpenAiCanary = ['sk-', 'synthetic', 'redaction'].join('')
    const syntheticAwsCanary = ['AKIA', 'SYNTHETIC', 'CANARY'].join('')
    const text = buildRedactedDiagnostics({
      runId: 'run-1',
      model: 'gpt-4.1',
      providerType: 'openai',
      retryReason: `HTTP 401 with ${authorization}: ${authScheme} ${syntheticOpenAiCanary}`,
      detail: `call https://api.example.com/v1?${apiKeyParam}=synthetic-redaction-value&x=1 failed; ${apiKeyLabel}=synthetic-redaction-secondary`
    })
    expect(text).toContain('run: run-1')
    expect(text).toContain('model: gpt-4.1')
    expect(text).not.toContain(syntheticOpenAiCanary)
    expect(text).not.toContain('synthetic-redaction-value')
    expect(text).not.toContain('synthetic-redaction-secondary')
    expect(text).toContain('***')
    expect(text).toContain('x=1')
    expect(buildRedactedDiagnostics({ detail: `${syntheticAwsCanary} expired` })).not.toContain(syntheticAwsCanary)
  })
})

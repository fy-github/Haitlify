// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createTemplateOperationAdapter } from './templateOperationAdapter'
import { createWorkspaceStorage } from './workspaceStorage'
import { createLocalTemplateConfig } from './useTemplateConfig'
import {
  defaultTemplateConfig, readTemplateConfigRawPair, saveTemplateConfig, serializeTemplateTarget,
  updateCaseField, type TemplateConfig
} from '../domain/templateConfigRepository'

const timestamp = '2026-11-16T00:00:00.000Z'
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const label = (config: TemplateConfig) => config.interfaceFields.find(field => field.key === 'project')?.label
const controllers: ReturnType<typeof createLocalTemplateConfig>[] = []
afterEach(() => {
  controllers.splice(0).forEach(controller => controller.dispose())
  vi.useRealTimers()
  vi.restoreAllMocks()
})

async function setup() {
  localStorage.clear()
  const workspace = createWorkspaceStorage(localStorage)
  workspace.activate('deadline-fixture')
  const authority = workspace.captureStrict(() => true)!
  const A = defaultTemplateConfig()
  saveTemplateConfig(authority.storage, A, { updatedAt: timestamp })
  let remote = clone(A)
  let hold: 'GET' | 'PUT' | null = null
  let resolveHeld: (() => void) | null = null
  let rejectHeld: ((error: Error) => void) | null = null
  const requests: Array<{ method: string; url: string; body: TemplateConfig | null }> = []
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    const body = init?.body ? JSON.parse(String(init.body)) as TemplateConfig : null
    requests.push({ method, url, body })
    if (url === '/api/projects') return new Response(JSON.stringify({ items: [{ id: 1, name: 'P', code: 'P' }] }))
    if (url !== '/api/projects/1/case-template/interface' || !['GET', 'PUT'].includes(method)) throw Error('Unexpected fixture request')
    const responseConfig = method === 'GET' ? clone(remote) : body!
    if (method === hold) {
      hold = null
      await new Promise<void>((resolve, reject) => { resolveHeld = resolve; rejectHeld = reject })
    }
    if (method === 'PUT') remote = clone(body!)
    return new Response(JSON.stringify({ ...responseConfig, updatedAt: timestamp }))
  })
  const adapter = createTemplateOperationAdapter({ beginTransport: () => ({ fetch, projectName: 'P', isCurrent: () => true }) })
  adapter.bind(authority)
  await adapter.load()
  const controller = createLocalTemplateConfig({ storage: workspace.storage, identity: () => authority,
    operationAdapter: adapter, confirmDanger: async () => true })
  controllers.push(controller)
  return { A, workspace, authority, adapter, controller, requests,
    raw: () => readTemplateConfigRawPair(authority.storage),
    remote: () => clone(remote),
    setRemote: (config: TemplateConfig) => { remote = clone(config) },
    hold: (method: 'GET' | 'PUT') => { hold = method },
    release: () => { expect(resolveHeld).not.toBeNull(); resolveHeld!(); resolveHeld = null; rejectHeld = null },
    reject: () => { expect(rejectHeld).not.toBeNull(); rejectHeld!(Error('Fixture successor GET failed')); resolveHeld = null; rejectHeld = null },
    held: () => resolveHeld !== null,
    puts: () => requests.filter(request => request.method === 'PUT').map(request => request.body!) }
}

it.each(['GET', 'PUT'] as const)('a held %s released before the real deadline completes exactly once', async method => {
  const h = await setup()
  vi.useFakeTimers()
  h.hold(method)
  h.controller.patchField('project', { label: 'B' })
  await flushPromises()
  expect(h.held()).toBe(true)
  await vi.advanceTimersByTimeAsync(29999)
  expect(h.controller.saveStatus.fields.state).toBe('saving')
  h.release()
  await flushPromises()
  expect(h.controller.saveStatus.fields.state).toBe('saved')
  expect(h.puts()).toHaveLength(1)
  expect(h.raw().pending).toBeNull()
  await vi.advanceTimersByTimeAsync(2)
  expect(h.controller.saveStatus.fields.state).toBe('saved')
  expect(h.adapter.recovery.reason).toBeNull()
})

it.each(['GET', 'PUT'] as const)('a held %s past deadline is unknown and its late result cannot publish or resend', async method => {
  const h = await setup()
  vi.useFakeTimers()
  h.hold(method)
  h.controller.patchField('project', { label: 'B' })
  await flushPromises()
  expect(h.held()).toBe(true)
  await vi.advanceTimersByTimeAsync(30000)
  await flushPromises()
  expect(h.controller.saveStatus.fields.state).toBe('failed')
  expect(h.controller.saveStatus.fields.error).toContain('结果未知')
  expect(h.adapter.recovery).toMatchObject({ reason: 'unknown-result', blocked: true, phase: 'idle' })
  const raw = h.raw(), status = clone(h.controller.saveStatus), requestCount = h.requests.length
  expect(raw.pending).not.toBeNull()
  h.release()
  await flushPromises()
  expect(h.raw()).toEqual(raw)
  expect(h.controller.saveStatus).toEqual(status)
  expect(h.requests).toHaveLength(requestCount)
  expect(h.puts()).toHaveLength(method === 'PUT' ? 1 : 0)
  expect(label(h.remote())).toBe(method === 'PUT' ? 'B' : label(h.A))
})

it.each(['A', 'B', 'C'] as const)('unknown PUT requires an explicit GET and adopts observed %s without another PUT', async observed => {
  const h = await setup()
  vi.useFakeTimers()
  h.hold('PUT')
  h.controller.patchField('project', { label: 'B' })
  await flushPromises()
  expect(h.held()).toBe(true)
  await vi.advanceTimersByTimeAsync(30000)
  await flushPromises()
  h.release()
  await flushPromises()
  const expected = observed === 'A' ? h.A : updateCaseField(h.A, 'project', { label: observed })
  h.setRemote(expected)
  const calls = h.requests.length
  await h.controller.reconcile('take-backend')
  expect(h.requests.slice(calls).map(request => [request.method, request.url])).toEqual([
    ['GET', '/api/projects'], ['GET', '/api/projects/1/case-template/interface']
  ])
  expect(h.puts()).toHaveLength(1)
  expect(serializeTemplateTarget(h.controller.templateConfig.value)).toBe(serializeTemplateTarget(expected))
  expect(h.raw()).toEqual({ config: serializeTemplateTarget(expected), meta: JSON.stringify({ updatedAt: timestamp }), pending: null })
  expect(h.adapter.recovery.reason).toBeNull()
  expect(h.controller.saveStatus.fields.state).toBe('idle')
  expect(h.controller.saveStatus.types.state).toBe('idle')
})

it.each(['invalidate', 'dispose', 'owner'] as const)('%s cancels the active race immediately and fences an issued late PUT', async boundary => {
  const h = await setup()
  h.hold('PUT')
  h.controller.patchField('project', { label: 'B' })
  await flushPromises()
  expect(h.held()).toBe(true)
  if (boundary === 'owner') h.workspace.activate('other-fixture')
  if (boundary === 'dispose') h.controller.dispose()
  else h.controller.invalidateWorkspace()
  await flushPromises()
  expect(h.controller.saveStatus.fields.state).toBe('failed')
  expect(h.adapter.recovery.phase).toBe('idle')
  const raw = { ...localStorage }, status = clone(h.controller.saveStatus), calls = h.requests.length
  h.release()
  await flushPromises()
  expect({ ...localStorage }).toEqual(raw)
  expect(h.controller.saveStatus).toEqual(status)
  expect(h.requests).toHaveLength(calls)
  expect(h.puts()).toHaveLength(1)
  expect(label(h.remote())).toBe('B')
})

it.each(['invalidate', 'dispose', 'bind'] as const)('adapter %s settles the returned promise before its issued PUT returns', async boundary => {
  const h = await setup()
  vi.useFakeTimers()
  const cleared = vi.spyOn(globalThis, 'clearTimeout')
  h.hold('PUT')
  let outcome = 'pending'
  const operation = h.adapter.save(updateCaseField(h.A, 'project', { label: 'cancel B' }))
    .then(() => { outcome = 'resolved' }, () => { outcome = 'rejected' })
  await flushPromises()
  expect(h.held()).toBe(true)
  if (boundary === 'bind') h.adapter.bind(h.authority)
  else h.adapter[boundary]()
  await flushPromises()
  expect(outcome).toBe('rejected')
  expect(cleared).toHaveBeenCalled()
  const raw = h.raw(), recovery = clone(h.adapter.recovery), calls = h.requests.length
  await vi.advanceTimersByTimeAsync(30001)
  expect(h.adapter.recovery).toEqual(recovery)
  h.release()
  await operation
  await flushPromises()
  expect(h.raw()).toEqual(raw)
  expect(h.adapter.recovery).toEqual(recovery)
  expect(h.requests).toHaveLength(calls)
})

it('confirmed B advances discard baseline while a newer whole-config successor fails', async () => {
  const h = await setup()
  h.hold('PUT')
  h.controller.patchField('project', { label: 'confirmed B' })
  await flushPromises()
  expect(h.held()).toBe(true)
  h.controller.patchCaseType('positive', { name: 'unsaved D' })
  h.hold('GET')
  h.release()
  await flushPromises()
  expect(h.held()).toBe(true)
  const B = updateCaseField(h.A, 'project', { label: 'confirmed B' })
  expect(h.adapter.confirmed?.config).toEqual(B)
  expect(h.controller.saveStatus.fields.state).toBe('saved')
  expect(h.controller.saveStatus.types.state).toBe('saving')
  expect(h.controller.templateConfig.value.caseTypes[0].name).toBe('unsaved D')
  h.reject()
  await flushPromises()
  expect(h.controller.saveStatus.types.state).toBe('failed')
  expect(h.puts()).toEqual([B])
  const pending = JSON.parse(h.raw().pending!)
  expect(pending.confirmedBaselineRaw.config).toBe(serializeTemplateTarget(B))
  await h.controller.discardUnsaved()
  expect(h.raw()).toEqual({ config: serializeTemplateTarget(B), meta: JSON.stringify({ updatedAt: timestamp }), pending: null })
  expect(h.controller.templateConfig.value).toEqual(B)
  expect(h.puts()).toEqual([B])
})

it.each(['fields', 'types'] as const)('an unqueued newer %s revision is not labelled saved when the older writer completes', async section => {
  const h = await setup()
  h.hold('PUT')
  if (section === 'fields') h.controller.patchField('project', { label: 'writer B' })
  else h.controller.patchCaseType('positive', { name: 'writer B' })
  await flushPromises()
  expect(h.held()).toBe(true)
  if (section === 'fields') h.controller.templateConfig.value = updateCaseField(h.controller.templateConfig.value, 'project', { label: 'unqueued D' })
  else h.controller.templateConfig.value.caseTypes[0].name = 'unqueued D'
  h.release()
  await flushPromises()
  expect(section === 'fields' ? label(h.controller.templateConfig.value) : h.controller.templateConfig.value.caseTypes[0].name).toBe('unqueued D')
  expect(section === 'fields' ? label(h.adapter.confirmed!.config) : h.adapter.confirmed!.config.caseTypes[0].name).toBe('writer B')
  expect(h.controller.saveStatus[section].state).toBe('failed')
  expect(h.controller.saveStatus[section].error).toContain('未保存')
  expect(h.puts()).toHaveLength(1)
})

it.each(['fields', 'types'] as const)('an unqueued newer %s sibling does not falsify the confirmed section', async sibling => {
  const h = await setup()
  const saved = sibling === 'fields' ? 'types' : 'fields'
  h.hold('PUT')
  if (saved === 'fields') h.controller.patchField('project', { label: 'writer B' })
  else h.controller.patchCaseType('positive', { name: 'writer B' })
  await flushPromises()
  expect(h.held()).toBe(true)
  if (sibling === 'fields') h.controller.templateConfig.value = updateCaseField(h.controller.templateConfig.value, 'project', { label: 'unqueued D' })
  else h.controller.templateConfig.value.caseTypes[0].name = 'unqueued D'
  h.release()
  await flushPromises()
  expect(h.controller.saveStatus[saved].state).toBe('saved')
  expect(h.controller.saveStatus[sibling].state).toBe('failed')
  expect(h.controller.saveStatus[sibling].error).toContain('未保存')
  expect(h.puts()).toHaveLength(1)
})

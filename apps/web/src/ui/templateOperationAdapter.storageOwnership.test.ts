// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { createTemplateOperationAdapter } from './templateOperationAdapter'
import { createWorkspaceStorage } from './workspaceStorage'
import { defaultTemplateConfig, readTemplateConfigRawPair, saveTemplateConfig, updateCaseField,
  TEMPLATE_CONFIG_STORAGE_KEY as CONFIG, TEMPLATE_CONFIG_META_STORAGE_KEY as META,
  TEMPLATE_CONFIG_PENDING_STORAGE_KEY as PENDING } from '../domain/templateConfigRepository'

const adapters: ReturnType<typeof createTemplateOperationAdapter>[] = []
afterEach(() => { vi.restoreAllMocks(); adapters.splice(0).forEach(adapter => adapter.dispose()) })

it.each((['pending', 'mirror', 'restore', 'reset', 'import'] as const).flatMap(operation =>
  (['owner', 'adopt', 'dispose'] as const).map(boundary => ({ operation, boundary }))))('$operation cannot publish an old storage report after synchronous $boundary during its completed prefix', async ({ operation, boundary }) => {
  localStorage.clear()
  const workspace = createWorkspaceStorage(localStorage)
  workspace.activate('storage-A')
  const authority = workspace.captureStrict(() => true)!
  const A = defaultTemplateConfig(), B = updateCaseField(A, 'project', { label: 'B' })
  saveTemplateConfig(authority.storage, A, { updatedAt: '2026-11-16T00:00:00.000Z' })
  let remote = A
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === '/api/projects') return new Response(JSON.stringify({ items: [{ id: 1, name: 'P', code: 'P' }] }))
    if (init?.method === 'PUT') remote = JSON.parse(String(init.body))
    return new Response(JSON.stringify({ ...remote, updatedAt: '2026-11-16T00:00:00.000Z' }))
  })
  const adapter = createTemplateOperationAdapter({ beginTransport: () => ({ fetch, projectName: 'P', isCurrent: () => true }) })
  adapters.push(adapter); adapter.bind(authority); await adapter.load()
  const original = readTemplateConfigRawPair(authority.storage)
  if (operation === 'restore') await adapter.importConfig(B)
  let injected = false, configWrites = 0
  let nextRecovery: unknown, nextStorage: unknown
  const writes: string[] = []
  const trigger = (key: string) => {
    if (injected) return
    if (key === CONFIG) configWrites++
    const matches = operation === 'pending' || operation === 'import' ? key === PENDING
      : operation === 'mirror' ? key === CONFIG && configWrites === 2
      : operation === 'restore' ? key === META : key === CONFIG
    if (!matches) return
    injected = true
    if (boundary === 'owner') {
      workspace.activate('storage-B')
      const next = workspace.captureStrict(() => true)!
      saveTemplateConfig(next.storage, updateCaseField(A, 'project', { label: 'new owner baseline' }), { updatedAt: '2026-11-16T01:00:00.000Z' })
      adapter.bind(next)
    } else if (boundary === 'adopt') adapter.supersedeAdoption()
    else adapter.dispose()
    nextRecovery = JSON.parse(JSON.stringify(adapter.recovery))
    nextStorage = { ...localStorage }
  }
  const set = authority.storage.setItem.bind(authority.storage), remove = authority.storage.removeItem.bind(authority.storage)
  vi.spyOn(authority.storage, 'setItem').mockImplementation((key, value) => { writes.push(key); set(key, value); trigger(key) })
  vi.spyOn(authority.storage, 'removeItem').mockImplementation(key => { writes.push(key); remove(key); trigger(key) })
  const pending = operation === 'restore' ? adapter.restore(original) : operation === 'reset' ? adapter.reset(() => true)
    : operation === 'import' ? adapter.importConfig(B) : adapter.save(B)
  await pending.catch(() => null)
  expect(injected).toBe(true)
  expect(JSON.parse(JSON.stringify(adapter.recovery))).toEqual(nextRecovery)
  expect({ ...localStorage }).toEqual(nextStorage)
  expect(writes).toHaveLength(operation === 'mirror' ? 3 : 1)
})

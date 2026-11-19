// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createTemplateOperationAdapter } from './templateOperationAdapter'
import { createLocalTemplateConfig } from './useTemplateConfig'
import { createWorkspaceStorage } from './workspaceStorage'
import { defaultTemplateConfig, readTemplateConfigRawPair, saveTemplateConfig, updateCaseField, type TemplateConfig } from '../domain/templateConfigRepository'

const actions = ['delete-field', 'delete-type', 'delete-template', 'reset', 'import', 'push', 'take', 'discard', 'reload'] as const
const adapters: ReturnType<typeof createTemplateOperationAdapter>[] = []
afterEach(() => { vi.restoreAllMocks(); adapters.splice(0).forEach(adapter => adapter.dispose()) })
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value))

async function setup() {
  localStorage.clear()
  const workspace = createWorkspaceStorage(localStorage)
  workspace.activate('network-boundary-A')
  const authority = workspace.captureStrict(() => true)!
  const A = defaultTemplateConfig()
  A.caseTemplates.push({ id: 'custom-template', name: 'Custom', kind: 'interface', builtIn: false, enabled: true, fieldKeys: [] })
  saveTemplateConfig(authority.storage, A, { updatedAt: '2026-11-16T00:00:00.000Z' })
  let remote = clone(A)
  let flipOnRequest = false
  const requests: Array<{ method: string; url: string }> = []
  const flip = () => { if (!authority.state().includes('superseded')) workspace.activate('network-boundary-B') }
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    requests.push({ method: init?.method ?? 'GET', url })
    if (flipOnRequest) { flipOnRequest = false; flip() }
    if (url === '/api/projects') return new Response(JSON.stringify({ items: [{ id: 1, name: 'P', code: 'P' }] }))
    if (url !== '/api/projects/1/case-template/interface') throw Error(`Unexpected URL ${url}`)
    if (init?.method === 'PUT') remote = JSON.parse(String(init.body))
    return new Response(JSON.stringify({ ...remote, updatedAt: '2026-11-16T00:00:00.000Z' }))
  })
  const adapter = createTemplateOperationAdapter({ beginTransport: () => ({ fetch, projectName: 'P', isCurrent: () => true }) })
  adapters.push(adapter)
  adapter.bind(authority)
  await adapter.load()
  const controller = createLocalTemplateConfig({ storage: workspace.storage, identity: () => authority,
    operationAdapter: adapter, confirmDanger: async () => true })
  let storageWrites = 0
  const originalSet = authority.storage.setItem.bind(authority.storage)
  const originalRemove = authority.storage.removeItem.bind(authority.storage)
  vi.spyOn(authority.storage, 'setItem').mockImplementation((key, value) => { storageWrites += 1; originalSet(key, value); flip() })
  vi.spyOn(authority.storage, 'removeItem').mockImplementation(key => { storageWrites += 1; originalRemove(key); flip() })
  return { workspace, authority, A, adapter, controller, fetch, requests, flip, setFlipOnRequest: () => { flipOnRequest = true },
    storageWrites: () => storageWrites, raw: () => readTemplateConfigRawPair(authority.storage) }
}

it.each(actions)('%s aborts at the first network/write boundary after authority is superseded', async action => {
  const h = await setup()
  const before = { raw: h.raw(), config: clone(h.controller.templateConfig.value) }
  let pending: Promise<unknown>
  const requestsBefore = h.requests.length
  const storageBefore = { ...localStorage }
  if (action === 'delete-field') pending = h.controller.removeField('project')
  else if (action === 'delete-type') pending = h.controller.removeCaseType('positive')
  else if (action === 'delete-template') pending = h.controller.removeCaseTemplate(h.controller.templateConfig.value.caseTemplates.find(template => template.id === 'custom-template')!)
  else if (action === 'reset') pending = h.controller.resetConfig()
  else if (action === 'import') pending = h.controller.importConfig(updateCaseField(h.A, 'project', { label: 'imported B' }))
  else if (action === 'push' || action === 'take') { h.setFlipOnRequest(); pending = h.controller.reconcile(action === 'push' ? 'push-local' : 'take-backend') }
  else if (action === 'discard') {
    h.controller.templateConfig.value = updateCaseField(h.controller.templateConfig.value, 'project', { label: 'working B' })
    pending = Promise.resolve(h.controller.discardUnsaved())
  } else {
    h.controller.templateConfig.value = updateCaseField(h.controller.templateConfig.value, 'project', { label: 'working B' })
    h.flip()
    pending = h.controller.reloadBaseline()
  }
  await pending.catch(() => null)
  await flushPromises()
  expect(h.authority.state()).toBe('superseded')
  expect(h.requests.filter(request => request.method === 'PUT')).toHaveLength(0)
  expect(h.controller.saveStatus.fields.state).not.toBe('saving')
  expect(h.controller.saveStatus.types.state).not.toBe('saving')
  expect(h.requests.length - requestsBefore).toBeLessThanOrEqual(['push', 'take'].includes(action) ? 1 : 0)
  if (action === 'reload') expect({ ...localStorage }).toEqual(storageBefore)
  if (['delete-field', 'delete-type', 'delete-template', 'reset', 'import', 'discard'].includes(action)) {
    expect(h.storageWrites()).toBeLessThanOrEqual(1)
  }
})

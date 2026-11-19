// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createTemplateOperationAdapter } from './templateOperationAdapter'
import { createLocalTemplateConfig } from './useTemplateConfig'
import { createWorkspaceStorage } from './workspaceStorage'
import { defaultTemplateConfig, saveTemplateConfig, readTemplateConfigRawPair, updateCaseField,
  type TemplateConfig } from '../domain/templateConfigRepository'

const actions = ['delete-field', 'delete-type', 'delete-template', 'reset', 'import', 'push', 'take', 'discard', 'reload'] as const
const boundaries = ['cancel', 'revision', 'owner', 'dispose', 'adopt'] as const
const controllers: ReturnType<typeof createLocalTemplateConfig>[] = []
afterEach(() => { controllers.splice(0).forEach(controller => controller.dispose()); vi.restoreAllMocks() })
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value))
async function fixture() {
  localStorage.clear()
  const workspace = createWorkspaceStorage(localStorage)
  workspace.activate('confirmation-A')
  let authority = workspace.captureStrict(() => true)!
  const A = defaultTemplateConfig()
  A.interfaceFields.push({ key: 'custom_field', label: 'Custom', type: 'text', required: false, visible: true, exportable: true, aiGenerated: false })
  A.fieldsByKind.interface = clone(A.interfaceFields)
  A.caseTypes.push({ id: 'custom_type', name: 'Custom type', appliesTo: 'all', enabled: true })
  A.caseTemplates.push({ id: 'custom_template', name: 'Custom template', kind: 'interface', builtIn: false, enabled: true, fieldKeys: ['custom_field'] })
  saveTemplateConfig(authority.storage, A, { updatedAt: '2026-11-16T00:00:00.000Z' })
  let remote = clone(A), finishConfirmation!: (value: boolean) => void
  let holdPut = false, finishPut: (() => void) | null = null
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === '/api/projects') return new Response(JSON.stringify({ items: [{ id: 1, name: 'P', code: 'P' }] }))
    if (url !== '/api/projects/1/case-template/interface') throw Error('Unexpected fixture route')
    if (init?.method === 'PUT') {
      if (holdPut) { holdPut = false; await new Promise<void>(resolve => { finishPut = resolve }) }
      remote = JSON.parse(String(init.body))
    }
    return new Response(JSON.stringify({ ...remote, updatedAt: '2026-11-16T00:00:00.000Z' }))
  })
  const adapter = createTemplateOperationAdapter({ beginTransport: () => ({ fetch, projectName: 'P', isCurrent: () => true }) })
  adapter.bind(authority); await adapter.load()
  const confirm = vi.fn(() => new Promise<boolean>(resolve => { finishConfirmation = resolve }))
  const controller = createLocalTemplateConfig({ storage: workspace.storage, identity: () => authority,
    operationAdapter: adapter, confirmDanger: confirm })
  controllers.push(controller)
  // A direct working edit makes reload/discard meaningful without starting a save.
  controller.templateConfig.value = updateCaseField(controller.templateConfig.value, 'project', { label: 'unsaved local' })
  controller.openFieldDraft(); controller.openCaseTypeDraft()
  const snapshot = () => ({ config: clone(controller.templateConfig.value), status: clone(controller.saveStatus),
    fieldDraft: clone(controller.fieldDraft.value), typeDraft: clone(controller.caseTypeDraft.value),
    storage: { ...localStorage }, calls: fetch.mock.calls.length, recovery: clone(adapter.recovery) })
  const invoke = (action: typeof actions[number]) => {
    if (action === 'delete-field') return controller.removeField('custom_field')
    if (action === 'delete-type') return controller.removeCaseType('custom_type')
    if (action === 'delete-template') return controller.removeCaseTemplate(controller.templateConfig.value.caseTemplates.find(template => template.id === 'custom_template')!)
    if (action === 'reset') return controller.resetConfig()
    if (action === 'import') return controller.importConfig(updateCaseField(A, 'project', { label: 'import target' }))
    if (action === 'push' || action === 'take') return controller.reconcile(action === 'push' ? 'push-local' : 'take-backend')
    if (action === 'discard') return controller.discardUnsaved()
    return controller.reloadBaseline()
  }
  const change = (boundary: typeof boundaries[number]) => {
    if (boundary === 'revision') controller.templateConfig.value = updateCaseField(controller.templateConfig.value, 'project', { label: 'newer revision' })
    if (boundary === 'dispose') controller.dispose()
    if (boundary === 'owner') {
      workspace.activate('confirmation-B'); authority = workspace.captureStrict(() => true)!
      saveTemplateConfig(authority.storage, updateCaseField(A, 'project', { label: 'owner B' }), { updatedAt: '2026-11-16T01:00:00.000Z' })
      controller.bindWorkspace()
    }
    if (boundary === 'adopt') {
      const rawPair = adapter.rawPair!
      const next = JSON.parse(rawPair.config!) as TemplateConfig
      controller.adoptSavedConfig({ ...adapter.producerEnvelope(), kind: 'local', config: next, rawPair })
    }
    controller.openFieldDraft(); controller.openCaseTypeDraft()
  }
  return { controller, adapter, confirm, invoke, snapshot, change, finish: (value: boolean) => finishConfirmation(value), raw: () => readTemplateConfigRawPair(authority.storage),
    holdPut: () => { holdPut = true }, held: () => finishPut !== null, release: () => { expect(finishPut).not.toBeNull(); finishPut!(); finishPut = null } }
}

it.each(actions.flatMap(action => boundaries.map(boundary => ({ action, boundary }))))('$action / $boundary confirmation cannot mutate newer state or steal status', async ({ action, boundary }) => {
  const h = await fixture()
  const pending = Promise.resolve(h.invoke(action))
  await flushPromises()
  expect(h.confirm).toHaveBeenCalledOnce()
  h.change(boundary)
  const before = h.snapshot()
  h.finish(boundary !== 'cancel')
  await pending
  await flushPromises()
  expect(h.snapshot()).toEqual(before)
  expect(h.controller.saveStatus.fields.state).not.toBe('saving')
  expect(h.controller.saveStatus.types.state).not.toBe('saving')
})

it.each((['reset', 'import', 'take', 'discard'] as const).flatMap(action => (['revision', 'owner', 'dispose', 'adopt'] as const).map(boundary => ({ action, boundary }))))('$action / $boundary after confirmation waits for writer without applying stale intent', async ({ action, boundary }) => {
  const h = await fixture()
  h.holdPut()
  h.controller.patchField('project', { label: 'active B' })
  await flushPromises()
  expect(h.held()).toBe(true)
  const reset = vi.spyOn(h.adapter, 'reset'), imported = vi.spyOn(h.adapter, 'importConfig')
  const taking = vi.spyOn(h.adapter, 'takeBackend'), restored = vi.spyOn(h.adapter, 'restore')
  const pending = Promise.resolve(h.invoke(action))
  await flushPromises()
  expect(h.confirm).toHaveBeenCalledOnce()
  h.finish(true)
  await flushPromises()
  expect(h.held()).toBe(true)
  h.change(boundary)
  const nextConfig = clone(h.controller.templateConfig.value)
  const fieldDraft = clone(h.controller.fieldDraft.value), typeDraft = clone(h.controller.caseTypeDraft.value)
  h.release()
  await pending
  await flushPromises()
  expect(reset).not.toHaveBeenCalled()
  expect(imported).not.toHaveBeenCalled()
  expect(taking).not.toHaveBeenCalled()
  expect(restored).not.toHaveBeenCalled()
  expect(h.controller.templateConfig.value).toEqual(nextConfig)
  expect(h.controller.fieldDraft.value).toEqual(fieldDraft)
  expect(h.controller.caseTypeDraft.value).toEqual(typeDraft)
  expect(h.controller.saveStatus.fields.state).not.toBe('saving')
  expect(h.controller.saveStatus.types.state).not.toBe('saving')
})

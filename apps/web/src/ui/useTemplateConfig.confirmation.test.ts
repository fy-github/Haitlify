// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { ElMessage } from 'element-plus'
import { advanceAuthSessionEpoch } from '../domain/http'
import { defaultTemplateConfig, saveTemplateConfig, TEMPLATE_CONFIG_STORAGE_KEY, TEMPLATE_CONFIG_META_STORAGE_KEY, type TemplateConfig } from '../domain/templateConfigRepository'
import { createWorkspaceStorage } from './workspaceStorage'
import { useTemplateConfig } from './useTemplateConfig'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}
const actions: readonly string[] = ['resetConfig', 'removeField', 'removeCaseTemplate', 'removeCaseType']
const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.splice(0).forEach(w => w.unmount()); vi.restoreAllMocks() })

function setup(externalGuard = true) {
  localStorage.clear()
  const scope = createWorkspaceStorage(localStorage)
  scope.activate('user:A')
  const seed = (label: string) => {
    const config = defaultTemplateConfig()
    config.interfaceFields.push({ key: 'custom', label, type: 'text', required: false, visible: true, exportable: true, aiGenerated: false })
    config.fieldsByKind.interface = structuredClone(config.interfaceFields)
    config.caseTemplates.push({ id: 'custom-template', name: label, kind: 'interface', builtIn: false, enabled: true, fieldKeys: ['custom'] })
    config.caseTypes.push({ id: 'custom-type', name: label, appliesTo: 'interface', enabled: true })
    saveTemplateConfig(scope.storage, config, { updatedAt: label })
    return config
  }
  seed('A')
  let generationTypes = ['custom-type']
  let operation = 0
  const beginOperation = () => { const id = ++operation; return () => id === operation }
  const confirmation = deferred<boolean>()
  const confirm = vi.fn(() => confirmation.promise)
  const toast = vi.spyOn(ElMessage, 'success').mockImplementation(() => ({ close() {} }))
  const saved = deferred<TemplateConfig>()
  let delaySave = false
  const persist = vi.fn(async (config: TemplateConfig, isCurrent: () => boolean = () => true) => {
    const storage = scope.capture(isCurrent)
    const value = JSON.parse(JSON.stringify(config)) as TemplateConfig
    const pending = delaySave
    delaySave = false
    const result = pending ? await saved.promise : value
    saveTemplateConfig(storage, result, { updatedAt: 'persisted' })
    return result
  })
  let state!: ReturnType<typeof useTemplateConfig>
  const wrapper = mount(defineComponent({ setup() {
    state = useTemplateConfig({ storage: scope.storage, identity: () => scope.captureStrict(() => true),
      ...(externalGuard ? { beginOperation, captureIntentCurrent: () => { const captured = operation; return () => operation === captured } } : {}),
      confirmDanger: confirm, persistTemplateConfig: persist,
      getGenerationSelectedCaseTypes: () => generationTypes,
      setGenerationSelectedCaseTypes: ids => { generationTypes = ids }
    })
    return () => null
  } }))
  wrappers.push(wrapper)
  state.selectedTemplateId.value = 'custom-template'
  state.generationTemplateId.value = 'custom-template'
  const fingerprint = () => JSON.stringify({ config: state.templateConfig.value,
    selected: state.selectedTemplateId.value, generation: state.generationTemplateId.value, generationTypes,
    storage: Object.fromEntries(Object.keys(localStorage).sort().map(k => [k, localStorage.getItem(k)])),
    toasts: toast.mock.calls, persist: persist.mock.calls.map(([config]) => config) })
  const switchAccount = () => {
    advanceAuthSessionEpoch(); scope.activate('user:B')
    state.templateConfig.value = seed('B')
    state.selectedTemplateId.value = 'builtin-interface'; state.generationTemplateId.value = 'builtin-interface'
    generationTypes = ['B-selection']
    expect(scope.storage.getItem(TEMPLATE_CONFIG_STORAGE_KEY)).toContain('B')
    expect(scope.storage.getItem(TEMPLATE_CONFIG_META_STORAGE_KEY)).toContain('B')
  }
  const invoke = (action: string) => {
    if (action === 'removeField') return state.removeField('custom')
    if (action === 'removeCaseTemplate') return state.removeCaseTemplate(state.templateConfig.value.caseTemplates.find(t => t.id === 'custom-template')!)
    if (action === 'removeCaseType') return state.removeCaseType('custom-type')
    return state.resetConfig()
  }
  return { state, wrapper, confirmation, confirm, toast, persist, fingerprint, switchAccount, beginOperation, invoke, scope, saved,
    delaySave: () => { delaySave = true } }
}

it.each(['account', 'unmount', 'operation'])('late persistence cannot publish after %s', async boundary => {
  const h = setup(); h.delaySave()
  h.state.patchField('custom', { label: 'pending-save' })
  const old = JSON.parse(JSON.stringify(h.state.templateConfig.value)) as TemplateConfig
  expect(h.persist).toHaveBeenCalledOnce()
  if (boundary === 'account') h.switchAccount()
  if (boundary === 'unmount') h.wrapper.unmount()
  if (boundary === 'operation') h.beginOperation()
  if (boundary === 'edit') h.state.patchField('custom', { label: 'newer edit' })
  if (boundary === 'in-place-edit') h.state.templateConfig.value.interfaceFields[0].label = 'direct edit'
  if (boundary === 'template-switch') h.state.selectedTemplateId.value = 'builtin-interface'
  if (boundary === 'reset') { h.confirmation.resolve(true); await h.state.resetConfig() }
  await flushPromises()
  const before = h.fingerprint()
  h.saved.resolve(old); await flushPromises()
  expect(h.fingerprint()).toBe(before)
})

it.each(['edit','in-place-edit','template-switch','reset'])('active writer settles safely across %s without overwriting newer working edits',async boundary=>{
 const h=setup();h.delaySave();h.state.patchField('custom',{label:'pending-save'});const old=JSON.parse(JSON.stringify(h.state.templateConfig.value)) as TemplateConfig
 if(boundary==='edit')h.state.patchField('custom',{label:'newer edit'})
 if(boundary==='in-place-edit')h.state.templateConfig.value.interfaceFields[0].label='direct edit'
 if(boundary==='template-switch')h.state.selectedTemplateId.value='builtin-interface'
 if(boundary==='reset'){h.confirmation.resolve(false);await h.state.resetConfig()}
 h.saved.resolve(old);await flushPromises()
 if(boundary==='edit')expect(h.state.templateConfig.value.interfaceFields.find(f=>f.key==='custom')?.label).toBe('newer edit')
 if(boundary==='in-place-edit')expect(h.state.templateConfig.value.interfaceFields[0].label).toBe('direct edit')
 if(boundary==='template-switch')expect(h.state.selectedTemplateId.value).toBe('builtin-interface')
 expect(h.state.saveStatus.fields.state).not.toBe('saving')
 expect(h.scope.storage.getItem(TEMPLATE_CONFIG_STORAGE_KEY)).toContain(boundary==='edit'?'newer edit':'pending-save')
})
it('persists authoritative current result', async () => {
  const h = setup(); h.delaySave(); h.state.patchField('custom', { label: 'pending' })
  const saved = JSON.parse(JSON.stringify(h.state.templateConfig.value)) as TemplateConfig
  saved.interfaceFields[0].label = 'server canonical'
  h.saved.resolve(saved); await flushPromises()
  expect(h.state.templateConfig.value.interfaceFields[0].label).toBe('server canonical')
  expect(h.scope.storage.getItem(TEMPLATE_CONFIG_STORAGE_KEY)).toContain('server canonical')
})

it('freezes the template id even when caller mutates a detached argument', async () => {
  const h = setup()
  const target = { ...h.state.templateConfig.value.caseTemplates.find(t => t.id === 'custom-template')! }
  const pending = h.state.removeCaseTemplate(target)
  target.id = 'builtin-interface'
  h.confirmation.resolve(true); await pending; await flushPromises()
  expect(h.state.templateConfig.value.caseTemplates.some(t => t.id === 'custom-template')).toBe(false)
  expect(h.state.templateConfig.value.caseTemplates.some(t => t.id === 'builtin-interface')).toBe(true)
})

describe.each(actions)('%s confirmation intent', action => {
  it.each(['account', 'unmount', 'operation', 'edit', 'in-place-edit', 'template-switch', 'switch-away-back', 'new-confirmation'])('ignores stale %s without any side effects', async boundary => {
    const h = setup()
    const pending = h.invoke(action)
    expect(h.confirm).toHaveBeenCalledOnce()
    if (boundary === 'account') h.switchAccount()
    if (boundary === 'unmount') h.wrapper.unmount()
    if (boundary === 'operation') h.beginOperation()
    if (boundary === 'edit') h.state.patchField('custom', { label: 'new edit' })
    if (boundary === 'in-place-edit') h.state.templateConfig.value.interfaceFields[0].label = 'direct edit'
    if (boundary === 'template-switch') h.state.selectedTemplateId.value = 'builtin-web-automation'
    if (boundary === 'switch-away-back') { h.state.selectedTemplateId.value = 'builtin-interface'; h.state.selectedTemplateId.value = 'custom-template' }
    let newer: ReturnType<typeof h.state.resetConfig> | undefined
    if (boundary === 'new-confirmation') {
      h.confirm.mockImplementationOnce(async () => false)
      newer = h.state.resetConfig()
    }
    await flushPromises()
    const before = h.fingerprint()
    h.confirmation.resolve(true); await pending; await newer; await flushPromises()
    expect(h.fingerprint()).toBe(before)
  })
  it('cancel leaves config, both caches, selections, toasts and persistence unchanged', async () => {
    const h = setup(); const before = h.fingerprint(); const pending = h.invoke(action)
    h.confirmation.resolve(false); await pending; await flushPromises()
    expect(h.fingerprint()).toBe(before)
  })
  it('current confirmation succeeds on the captured target', async () => {
    const h = setup(); const pending = h.invoke(action)
    h.confirmation.resolve(true); await pending; await flushPromises()
    if (action === 'resetConfig') {
      expect(h.state.templateConfig.value).toEqual(defaultTemplateConfig())
      expect(h.scope.storage.getItem(TEMPLATE_CONFIG_STORAGE_KEY)).toBeNull()
      expect(h.scope.storage.getItem(TEMPLATE_CONFIG_META_STORAGE_KEY)).toBeNull()
      expect(h.toast).toHaveBeenCalledWith('已恢复默认模板')
      expect(h.persist).not.toHaveBeenCalled()
    } else {
      expect(h.persist).toHaveBeenCalledOnce()
      expect(h.scope.storage.getItem(TEMPLATE_CONFIG_META_STORAGE_KEY)).toContain('persisted')
      if (action === 'removeField') expect(h.state.templateConfig.value.interfaceFields.some(f => f.key === 'custom')).toBe(false)
      if (action === 'removeCaseTemplate') expect(h.state.templateConfig.value.caseTemplates.some(t => t.id === 'custom-template')).toBe(false)
      if (action === 'removeCaseType') expect(h.state.templateConfig.value.caseTypes.some(t => t.id === 'custom-type')).toBe(false)
    }
  })
  it.each(['account', 'epoch-only', 'unmount', 'new-confirmation'])('default guard rejects %s even without injected operation', async boundary => {
    const h = setup(false); const pending = h.invoke(action)
    if (boundary === 'account') h.switchAccount()
    if (boundary === 'epoch-only') advanceAuthSessionEpoch()
    if (boundary === 'unmount') h.wrapper.unmount()
    if (boundary === 'new-confirmation') {
      h.confirm.mockImplementationOnce(async () => false)
      await h.state.resetConfig()
    }
    const before = h.fingerprint(); h.confirmation.resolve(true); await pending; await flushPromises()
    expect(h.fingerprint()).toBe(before)
  })
})

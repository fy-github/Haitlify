// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest'
import { createTemplateOperationAdapter } from './templateOperationAdapter'
import { createWorkspaceStorage } from './workspaceStorage'
import { createLocalTemplateConfig } from './useTemplateConfig'
import { defaultTemplateConfig, readTemplateConfigRawPair, saveTemplateConfig, updateCaseField,
  TEMPLATE_CONFIG_STORAGE_KEY as CONFIG, TEMPLATE_CONFIG_PENDING_STORAGE_KEY as PENDING,
  type TemplateConfig } from '../domain/templateConfigRepository'

const controllers: ReturnType<typeof createLocalTemplateConfig>[] = []
afterEach(() => { controllers.splice(0).forEach(controller => controller.dispose()); vi.restoreAllMocks() })

it.each([
  [null, 'throw'], [null, 'mismatch'],
  ['  {broken original pending\n', 'throw'], ['  {broken original pending\n', 'mismatch']
] as const)('failed import readback restores exact original pending %s after %s', async (pending, failure) => {
  localStorage.clear()
  const workspace = createWorkspaceStorage(localStorage)
  workspace.activate('import-readback-fixture')
  const authority = workspace.captureStrict(() => true)!
  const A = defaultTemplateConfig()
  saveTemplateConfig(authority.storage, A, { updatedAt: '2026-11-16T00:00:00.000Z' })
  authority.storage.setItem(CONFIG, JSON.stringify(A, null, 2))
  if (pending !== null) authority.storage.setItem(PENDING, pending)
  const before = readTemplateConfigRawPair(authority.storage)
  const fetch = vi.fn(async () => { throw Error('Import must not use network') })
  const adapter = createTemplateOperationAdapter({ beginTransport: () => ({ fetch, projectName: 'P', isCurrent: () => true }) })
  adapter.bind(authority)
  const controller = createLocalTemplateConfig({ storage: workspace.storage, identity: () => authority,
    operationAdapter: adapter, confirmDanger: async () => true })
  controllers.push(controller)
  const originalGet = authority.storage.getItem.bind(authority.storage)
  const originalSet = authority.storage.setItem.bind(authority.storage)
  const writes: Array<{ key: string; value: string }> = []
  let injected = false
  vi.spyOn(authority.storage, 'setItem').mockImplementation((key, value) => {
    writes.push({ key, value }); originalSet(key, value)
  })
  vi.spyOn(authority.storage, 'getItem').mockImplementation(key => {
    if (!injected && writes.length === 2 && key === CONFIG) {
      injected = true
      if (failure === 'throw') throw Error('Transient final import readback failure')
      return 'transient readback mismatch'
    }
    return originalGet(key)
  })
  const B: TemplateConfig = updateCaseField(A, 'project', { label: 'imported B' })
  await expect(controller.importConfig(B)).rejects.toThrow('模板导入失败，已恢复导入前三键')
  expect(injected).toBe(true)
  expect(writes.slice(0, 2).map(write => write.key)).toEqual([PENDING, CONFIG])
  expect(readTemplateConfigRawPair(authority.storage)).toEqual(before)
  expect(controller.templateConfig.value).toEqual(A)
  expect(controller.saveStatus.fields.state).toBe('failed')
  expect(controller.saveStatus.types.state).toBe('failed')
  expect(adapter.recovery.partial).toMatchObject({ ok: false, failedAt: 'readback', completed: ['pending', 'config'], remaining: [] })
  expect(fetch).not.toHaveBeenCalled()
})

it('import rollback refuses a foreign pending marker instead of recapturing and overwriting it', async () => {
  localStorage.clear()
  const workspace = createWorkspaceStorage(localStorage)
  workspace.activate('import-ownership-fixture')
  const authority = workspace.captureStrict(() => true)!
  const A = defaultTemplateConfig()
  saveTemplateConfig(authority.storage, A, { updatedAt: '2026-11-16T00:00:00.000Z' })
  const before = readTemplateConfigRawPair(authority.storage)
  const fetch = vi.fn(async () => { throw Error('Import must not use network') })
  const adapter = createTemplateOperationAdapter({ beginTransport: () => ({ fetch, projectName: 'P', isCurrent: () => true }) })
  adapter.bind(authority)
  const originalSet = authority.storage.setItem.bind(authority.storage)
  const writes: string[] = []
  vi.spyOn(authority.storage, 'setItem').mockImplementation((key, value) => {
    writes.push(key)
    if (key === CONFIG) {
      originalSet(PENDING, '  foreign pending bytes\n')
      throw Error('Config write failed after an external marker change')
    }
    originalSet(key, value)
  })
  await expect(adapter.importConfig(updateCaseField(A, 'project', { label: 'B' }))).rejects.toThrow('模板导入失败且回滚未完成')
  expect(readTemplateConfigRawPair(authority.storage)).toEqual({ ...before, pending: '  foreign pending bytes\n' })
  expect(writes).toEqual([PENDING, CONFIG])
  expect(adapter.recovery.partial).toMatchObject({ ok: false, failedAt: 'ownership', completed: [], remaining: ['meta', 'config', 'pending'] })
  expect(fetch).not.toHaveBeenCalled()
  adapter.dispose()
})

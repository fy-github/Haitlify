// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  EXECUTION_ENV_LIBRARY_STORAGE_KEY,
  WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY,
  defaultWebExecutionRuntimeConfig,
  newSavedExecutionEnvironment,
  newSavedWebExecutionEnvironment
} from '../domain/executionEnvironmentRepository'
import { createEnvironmentController } from './useEnvironmentController'

const apiEnvironment = (id: string, name: string) => ({
  ...newSavedExecutionEnvironment(name),
  id,
  baseUrl: `https://${id}.example.test`
})

const webEnvironment = (id: string, name: string) => ({
  ...newSavedWebExecutionEnvironment(name),
  id,
  web: defaultWebExecutionRuntimeConfig()
})

const saveLibrary = (key: string, activeEnvironmentId: string, environments: unknown[]) => {
  localStorage.setItem(key, JSON.stringify({ activeEnvironmentId, environments }))
}

afterEach(() => {
  localStorage.clear()
  vi.restoreAllMocks()
})

describe('useEnvironmentController', () => {
  it('binds the editor to the selected API or Web environment', () => {
    saveLibrary(EXECUTION_ENV_LIBRARY_STORAGE_KEY, 'api-b', [apiEnvironment('api-a', 'API A'), apiEnvironment('api-b', 'API B')])
    saveLibrary(WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY, 'web-b', [webEnvironment('web-a', 'Web A'), webEnvironment('web-b', 'Web B')])

    const controller = createEnvironmentController({ storage: localStorage })

    expect(controller.executionEnvironmentLibrary.value.activeEnvironmentId).toBe('api-b')
    expect(controller.editingEnvironment.id).toBe('api-b')
    expect(controller.environmentDraftDirty.value).toBe(false)

    controller.openEditor(undefined, 'web')

    expect(controller.environmentEditorKind.value).toBe('web')
    expect(controller.editingEnvironment.id).toBe('web-b')
    expect(controller.editingEnvironment.web).toBeDefined()
    expect(controller.environmentDraftDirty.value).toBe(false)
  })

  it('saves only the selected API environment and clears draft dirty state', () => {
    const untouched = apiEnvironment('api-a', 'API A')
    const selected = apiEnvironment('api-b', 'API B')
    saveLibrary(EXECUTION_ENV_LIBRARY_STORAGE_KEY, selected.id, [untouched, selected])
    const controller = createEnvironmentController({ storage: localStorage })

    controller.editingEnvironment.name = 'API B saved'
    expect(controller.environmentDraftDirty.value).toBe(true)
    expect(controller.saveEnvironment('api')).toBe(true)

    const stored = JSON.parse(localStorage.getItem(EXECUTION_ENV_LIBRARY_STORAGE_KEY) || '{}')
    expect(stored.environments.find((item: { id: string }) => item.id === 'api-b').name).toBe('API B saved')
    expect(stored.environments.find((item: { id: string }) => item.id === 'api-a').name).toBe('API A')
    expect(controller.environmentDraftDirty.value).toBe(false)
  })

  it('does not switch or discard a dirty draft when confirmation is cancelled', async () => {
    const first = apiEnvironment('api-a', 'API A')
    const second = apiEnvironment('api-b', 'API B')
    saveLibrary(EXECUTION_ENV_LIBRARY_STORAGE_KEY, first.id, [first, second])
    const confirmDiscard = vi.fn(async () => false)
    const controller = createEnvironmentController({ storage: localStorage, confirmDiscard })

    controller.editingEnvironment.name = 'unsaved'
    controller.runWithDraftGuard('discard?', () => controller.openEditor('api-b', 'api'))
    await Promise.resolve()

    expect(confirmDiscard).toHaveBeenCalledWith('discard?')
    expect(controller.editingEnvironment.id).toBe('api-a')
    expect(controller.editingEnvironment.name).toBe('unsaved')
    expect(controller.environmentDraftDirty.value).toBe(true)
  })

  it('keeps API and Web active environment persistence independent', () => {
    const api = apiEnvironment('api-a', 'API A')
    const web = webEnvironment('web-a', 'Web A')
    saveLibrary(EXECUTION_ENV_LIBRARY_STORAGE_KEY, api.id, [api])
    saveLibrary(WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY, web.id, [web])
    const controller = createEnvironmentController({ storage: localStorage })

    const added = controller.addEnvironment('web')
    controller.selectExecutionEnvironment('web-a', 'web')

    expect(added.web).toBeDefined()
    expect(JSON.parse(localStorage.getItem(EXECUTION_ENV_LIBRARY_STORAGE_KEY) || '{}').activeEnvironmentId).toBe('api-a')
    expect(JSON.parse(localStorage.getItem(WEB_EXECUTION_ENV_LIBRARY_STORAGE_KEY) || '{}').activeEnvironmentId).toBe('web-a')
  })
})

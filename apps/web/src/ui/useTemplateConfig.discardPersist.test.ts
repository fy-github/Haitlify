// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { loadTemplateConfig, saveTemplateConfig, defaultTemplateConfig, type TemplateConfig } from '../domain/templateConfigRepository'
import { createLocalTemplateConfig as useTemplateConfig } from './useTemplateConfig'

const copy = (config: TemplateConfig): TemplateConfig => JSON.parse(JSON.stringify(config)) as TemplateConfig
const labelOf = (config: TemplateConfig) => config.interfaceFields.find(field => field.key === 'project')!.label

describe('discarding a failed save', () => {
  it('must not let the abandoned edit come back after a reload', async () => {
    localStorage.clear()
    saveTemplateConfig(localStorage, defaultTemplateConfig())
    const state = useTemplateConfig({
      storage: localStorage,
      // Mirrors App's local-first path: the edit reaches localStorage before the backend 500.
      persistTemplateConfig: async (config) => {
        saveTemplateConfig(localStorage, config)
        throw new Error('后端不可用')
      }
    })
    state.patchField('project', { label: '被放弃的编辑' })
    await flushPromises()
    expect(state.saveStatusFor('fields').state).toBe('failed')
    expect(labelOf(loadTemplateConfig(localStorage))).toBe('被放弃的编辑')

    state.discardUnsaved('fields')
    // In-memory revert alone is not enough: the persisted copy must be reverted too.
    expect(labelOf(loadTemplateConfig(localStorage))).toBe('项目')
    expect(labelOf(state.templateConfig.value)).toBe('项目')
  })

  it('keeps the last confirmed workspace config when discarding after a reload', async () => {
    localStorage.clear()
    saveTemplateConfig(localStorage, defaultTemplateConfig())
    const state = useTemplateConfig({
      storage: localStorage,
      persistTemplateConfig: async (config) => {
        saveTemplateConfig(localStorage, config)
        throw new Error('后端不可用')
      }
    })
    const loaded = copy(defaultTemplateConfig())
    loaded.interfaceFields.find(field => field.key === 'project')!.label = '服务器确认值'
    saveTemplateConfig(localStorage, loaded, { updatedAt: '2026-01-01T00:00:00Z' })
    state.adoptSavedConfig({ ...state.producerEnvelope(), kind: 'local', config: loaded, rawPair: { config: localStorage.getItem('ai-test-platform.template-config.v1'), meta: localStorage.getItem('ai-test-platform.template-config-meta.v1'), pending: null } })
    state.patchField('project', { label: '未同步' })
    await flushPromises()
    state.discardUnsaved('fields')
    expect(labelOf(loadTemplateConfig(localStorage))).toBe('服务器确认值')
  })
})

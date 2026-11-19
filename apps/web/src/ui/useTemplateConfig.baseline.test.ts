// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { defaultTemplateConfig, saveTemplateConfig, type TemplateConfig } from '../domain/templateConfigRepository'
import { createLocalTemplateConfig as useTemplateConfig } from './useTemplateConfig'

const copy = (config: TemplateConfig): TemplateConfig => JSON.parse(JSON.stringify(config)) as TemplateConfig

// The fallback reproduces App's current wholesale replacement after workspace load.
const adoptWorkspaceConfig = (state: ReturnType<typeof useTemplateConfig>, config: TemplateConfig) => {
  // Producer mirrors first; adoption consumes facts and performs zero writes.
  saveTemplateConfig(localStorage, config, { updatedAt: '2026-01-01T00:00:00Z' })
  state.adoptSavedConfig({ ...state.producerEnvelope(), kind: 'local', config, rawPair: { config: localStorage.getItem('ai-test-platform.template-config.v1'), meta: localStorage.getItem('ai-test-platform.template-config-meta.v1'), pending: null } })
}

function setup() {
  localStorage.clear()
  saveTemplateConfig(localStorage, defaultTemplateConfig())
  return useTemplateConfig({ storage: localStorage, persistTemplateConfig: async () => { throw new Error('offline') } })
}
const label = (config: TemplateConfig) => config.interfaceFields.find(field => field.key === 'project')!.label

describe('template authoritative workspace adoption', () => {
  it('discards failed edits to the loaded workspace rather than hook-creation data', async () => {
    const state = setup()
    const loaded = copy(defaultTemplateConfig())
    loaded.interfaceFields.find(field => field.key === 'project')!.label = '服务器已确认字段'
    adoptWorkspaceConfig(state, loaded)
    state.patchField('project', { label: '未同步编辑' })
    await flushPromises()
    expect(state.saveStatusFor('fields').state).toBe('failed')
    state.discardUnsaved('fields')
    expect(label(state.templateConfig.value)).toBe('服务器已确认字段')
  })

  it('authoritative context replacement invalidates old pending saves and clears old statuses', async () => {
    let resolve!: (config: TemplateConfig) => void
    localStorage.clear()
    const state = useTemplateConfig({ storage: localStorage, persistTemplateConfig: () => new Promise(ok => { resolve = ok }) })
    state.patchField('project', { label: '旧空间编辑' })
    const old = copy(state.templateConfig.value)
    const loaded = copy(defaultTemplateConfig())
    loaded.interfaceFields.find(field => field.key === 'project')!.label = '新空间字段'
    adoptWorkspaceConfig(state, loaded)
    resolve(old)
    await flushPromises()
    expect(label(state.templateConfig.value)).toBe('新空间字段')
    expect(state.saveStatusFor('fields').state).toBe('failed') // local is not backend-confirmed
    expect(state.saveStatusFor('types').state).toBe('failed')
  })
})

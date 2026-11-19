// @vitest-environment jsdom
// 模板设置保存状态机:失败必须如实反馈(含 App 侧的本地降级),重试与放弃都可达;
// 过期保存不得覆盖新意图;保存条按页面独立。
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import {
  defaultTemplateConfig,
  saveTemplateConfig,
  TEMPLATE_CONFIG_META_STORAGE_KEY,
  TEMPLATE_CONFIG_STORAGE_KEY,
  type TemplateConfig
} from '../domain/templateConfigRepository'
import { useTemplateConfig } from './useTemplateConfig'

const deferred = <T>() => {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((ok, fail) => { resolve = ok; reject = fail })
  return { promise, resolve, reject }
}
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T

afterEach(() => { vi.restoreAllMocks() })

function setup(persistImpl?: (config: TemplateConfig) => Promise<TemplateConfig>) {
  localStorage.clear()
  saveTemplateConfig(localStorage, defaultTemplateConfig(), { updatedAt: 'seed' })
  const persist = vi.fn(persistImpl ?? (async (config: TemplateConfig) => {
    saveTemplateConfig(localStorage, config, { updatedAt: 'confirmed' })
    return clone(config)
  }))
  const state = useTemplateConfig({
    storage: localStorage,
    confirmDanger: async () => true,
    persistTemplateConfig: persist,
    getGenerationSelectedCaseTypes: () => [],
    setGenerationSelectedCaseTypes: () => {}
  })
  return { state, persist }
}

type State = ReturnType<typeof useTemplateConfig>
const labelOf = (state: State, key = 'project'): string =>
  state.templateConfig.value.interfaceFields.find((field) => field.key === key)!.label

describe('template save state', () => {
  it('reports a rejected persist as failed and keeps the local edit', async () => {
    const { state } = setup(async () => { throw new Error('后端保存失败') })
    state.patchField('project', { label: '未保存名称' })
    await flushPromises()
    expect(state.saveStatusFor('fields').state).toBe('failed')
    expect(state.saveStatusFor('fields').error).toContain('后端保存失败')
    expect(state.saveStatusFor('fields').savedAt).toBe('')
    expect(labelOf(state)).toBe('未保存名称')
  })

  it('treats the App-side local degrade as a failure, never as saved', async () => {
    const { state } = setup(async () => { throw new Error('后端暂不可用，修改仅保留在本地') })
    state.patchField('project', { label: '仅本地' })
    await flushPromises()
    const status = state.saveStatusFor('fields')
    expect(status.state).toBe('failed')
    expect(status.error).toContain('仅保留在本地')
    expect(state.saveState.value).not.toBe('saved')
  })

  it('marks a resolved persist as saved with a timestamp', async () => {
    const { state } = setup()
    state.patchField('project', { label: '已保存名称' })
    await flushPromises()
    const status = state.saveStatusFor('fields')
    expect(status.state).toBe('saved')
    expect(status.error).toBe('')
    expect(status.savedAt).toMatch(/^\d{2}:\d{2}:\d{2}$/)
  })

  it('retries a failed save and reaches saved', async () => {
    let fail = true
    const { state, persist } = setup(async (config) => {
      if (fail) throw new Error('500')
      return clone(config)
    })
    state.patchField('project', { label: '失败后重试' })
    await flushPromises()
    expect(state.saveStatusFor('fields').state).toBe('failed')
    fail = false
    state.retrySave('fields')
    await flushPromises()
    expect(persist).toHaveBeenCalledTimes(2)
    expect(state.saveStatusFor('fields').state).toBe('saved')
  })

  it('discards a failed edit back to the last confirmed config', async () => {
    let fail = false
    const { state } = setup(async (config) => {
      if (fail) throw new Error('500')
      return clone(config)
    })
    state.patchField('project', { label: '已确认名称' })
    await flushPromises()
    fail = true
    state.patchField('project', { label: '失败名称' })
    await flushPromises()
    expect(state.saveStatusFor('fields').state).toBe('failed')
    expect(labelOf(state)).toBe('失败名称')
    state.discardUnsaved('fields')
    expect(labelOf(state)).toBe('已确认名称')
    expect(state.saveStatusFor('fields').state).toBe('idle')
    expect(state.saveStatusFor('fields').error).toBe('')
  })

  it('restores the confirmed value even after an in-place edit of the working copy', async () => {
    const { state } = setup()
    state.patchField('project', { label: '基线名称' })
    await flushPromises()
    state.templateConfig.value.interfaceFields.find((field) => field.key === 'project')!.label = '就地改名'
    state.discardUnsaved('fields')
    expect(labelOf(state)).toBe('基线名称')
  })

  it('keeps save status per section so the types page cannot show a fields save', async () => {
    const { state } = setup()
    state.patchField('project', { label: '字段页保存' })
    await flushPromises()
    expect(state.saveStatusFor('fields').state).toBe('saved')
    expect(state.saveStatusFor('types').state).toBe('idle')
    expect(state.saveStatusFor('types').savedAt).toBe('')
  })

  it('ignores an older attempt that settles after a newer one', async () => {
    const pending: Array<ReturnType<typeof deferred<TemplateConfig>>> = []
    const { state } = setup(() => {
      const entry = deferred<TemplateConfig>()
      pending.push(entry)
      return entry.promise
    })
    state.patchField('project', { label: '第一次' })
    state.patchField('project', { label: '第二次' })
    expect(pending.length).toBe(1)
    const second = clone(state.templateConfig.value)
    const first = defaultTemplateConfig()
    first.interfaceFields.find((field) => field.key === 'project')!.label = '第一次'
    pending[0].resolve(first)
    await flushPromises()
    expect(labelOf(state)).toBe('第二次')
    expect(state.saveStatusFor('fields').state).toBe('saving')
    expect(pending.length).toBe(2)
    pending[1].resolve(second)
    await flushPromises()
    expect(state.saveStatusFor('fields').state).toBe('saved')
  })

  it('does not let a late stale failure overwrite a newer success', async () => {
    const pending: Array<ReturnType<typeof deferred<TemplateConfig>>> = []
    const { state } = setup(() => {
      const entry = deferred<TemplateConfig>()
      pending.push(entry)
      return entry.promise
    })
    state.patchField('project', { label: '旧' })
    state.patchField('project', { label: '新' })
    expect(pending).toHaveLength(1)
    const latest = clone(state.templateConfig.value)
    pending[0].reject(new Error('旧请求失败'))
    await flushPromises()
    expect(state.saveStatusFor('fields').state).toBe('failed')
    expect(pending).toHaveLength(1) // A failed writer cannot drain its successor.
    state.retrySave('fields')
    pending[1].resolve(latest)
    await flushPromises()
    expect(state.saveStatusFor('fields').state).toBe('saved')
    expect(labelOf(state)).toBe('新')
    expect(state.saveStatusFor('fields').error).toBe('')
  })

  it('keeps the last confirmed timestamp when a later save fails', async () => {
    let fail = false
    const { state } = setup(async (config) => {
      if (fail) throw new Error('500')
      saveTemplateConfig(localStorage, config, { updatedAt: 'confirmed' })
      return clone(config)
    })
    state.patchField('project', { label: '第一次成功' })
    await flushPromises()
    const confirmedAt = state.saveStatusFor('fields').savedAt
    expect(confirmedAt).not.toBe('')
    fail = true
    state.patchField('project', { label: '第二次失败' })
    await flushPromises()
    expect(state.saveStatusFor('fields').state).toBe('failed')
    expect(state.saveStatusFor('fields').savedAt).toBe(confirmedAt)
    expect(localStorage.getItem(TEMPLATE_CONFIG_STORAGE_KEY)).toContain('第一次成功')
  })

  it('discard leaves both persisted caches untouched', async () => {
    let fail = false
    const { state } = setup(async (config) => {
      if (fail) throw new Error('500')
      saveTemplateConfig(localStorage, config, { updatedAt: 'confirmed' })
      return clone(config)
    })
    state.patchField('project', { label: '确认值' })
    await flushPromises()
    const keys = {
      config: localStorage.getItem(TEMPLATE_CONFIG_STORAGE_KEY),
      meta: localStorage.getItem(TEMPLATE_CONFIG_META_STORAGE_KEY)
    }
    fail = true
    state.patchField('project', { label: '失败值' })
    await flushPromises()
    state.discardUnsaved('fields')
    expect(localStorage.getItem(TEMPLATE_CONFIG_STORAGE_KEY)).toBe(keys.config)
    expect(localStorage.getItem(TEMPLATE_CONFIG_META_STORAGE_KEY)).toBe(keys.meta)
  })

  it('records the types section for type edits', async () => {
    const { state } = setup()
    state.patchCaseType('positive', { enabled: false })
    await flushPromises()
    expect(state.saveStatusFor('types').state).toBe('saved')
    expect(state.saveStatusFor('fields').state).toBe('idle')
  })
})

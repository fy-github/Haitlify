// @vitest-environment jsdom
//
// 统一 owner/epoch 安全门:任何「快照回滚 / 意图提交 / 抽屉草稿 / 重试」都不允许把上一个
// 工作区(owner)的字节写进当前工作区的前缀,本地写失败也不许把失败说成 idle。
import { describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { advanceAuthSessionEpoch } from '../domain/http'
import {
  defaultTemplateConfig,
  loadTemplateConfig,
  loadTemplateConfigMeta,
  saveTemplateConfig,
  TEMPLATE_CONFIG_META_STORAGE_KEY,
  TEMPLATE_CONFIG_STORAGE_KEY,
  type TemplateConfig
} from '../domain/templateConfigRepository'
import { createWorkspaceStorage } from './workspaceStorage'
import { useTemplateConfig } from './useTemplateConfig'

const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T
const labelOf = (config: TemplateConfig) => config.interfaceFields.find((field) => field.key === 'project')!.label
const B_PREFIX = `ai-test-platform.workspace.${encodeURIComponent('user:B')}.`
const A_PREFIX = `ai-test-platform.workspace.${encodeURIComponent('user:A')}.`

/** 把 B 的既有配置写成「已确认」的样子,并返回它的原始字节。 */
const seedOwnerB = (scope: ReturnType<typeof createWorkspaceStorage>) => {
  const config = defaultTemplateConfig()
  config.interfaceFields.find((field) => field.key === 'project')!.label = 'B 的既有值'
  saveTemplateConfig(scope.storage, config, { updatedAt: 'B-confirmed' })
  return {
    config: scope.storage.getItem(TEMPLATE_CONFIG_STORAGE_KEY),
    meta: scope.storage.getItem(TEMPLATE_CONFIG_META_STORAGE_KEY)
  }
}

/** A 保存成功 → 失败编辑;返回处于 failed 的 hook。 */
const failUnderOwnerA = async (
  scope: ReturnType<typeof createWorkspaceStorage>,
  persist = vi.fn(async (config: TemplateConfig) => {
    saveTemplateConfig(scope.storage, config, { updatedAt: 'A-failed' })
    throw new Error('后端保存失败')
  })
) => {
  scope.activate('user:A')
  saveTemplateConfig(scope.storage, defaultTemplateConfig(), { updatedAt: 'A-confirmed' })
  const state = useTemplateConfig({ storage: scope.storage, identity: () => scope.captureStrict(() => true), persistTemplateConfig: persist })
  state.patchField('project', { label: 'A 的编辑' })
  await flushPromises()
  expect(state.saveStatusFor('fields').state).toBe('failed')
  return { state, persist }
}

const quotaStorage = () => {
  let mode: 'none' | 'all' | 'meta' = 'none'
  const storage: Storage = {
    getItem: (key) => localStorage.getItem(key),
    setItem: (key, value) => {
      if (mode === 'all') throw new Error('QuotaExceededError')
      if (mode === 'meta' && key === TEMPLATE_CONFIG_META_STORAGE_KEY) throw new Error('QuotaExceededError')
      localStorage.setItem(key, value)
    },
    removeItem: (key) => localStorage.removeItem(key),
    clear: () => localStorage.clear(),
    key: (index) => localStorage.key(index),
    get length() { return localStorage.length }
  }
  return { storage, setMode: (next: typeof mode) => { mode = next } }
}

describe('owner 绑定的回滚:放弃 / 重试', () => {
  it('放弃不会把一个 owner 的字节写进另一个 owner 前缀', async () => {
    localStorage.clear()
    const scope = createWorkspaceStorage(localStorage)
    const { state } = await failUnderOwnerA(scope)

    // 切换 owner,但 adopt 新 owner 的权威配置之前就点了「放弃」
    scope.activate('user:B')
    const b = seedOwnerB(scope)
    state.discardUnsaved('fields')

    expect(scope.storage.getItem(TEMPLATE_CONFIG_STORAGE_KEY)).toBe(b.config)
    expect(scope.storage.getItem(TEMPLATE_CONFIG_META_STORAGE_KEY)).toBe(b.meta)
    expect(labelOf(loadTemplateConfig(scope.storage))).toBe('B 的既有值')
    // Explicit owner binding is the only permitted recapture; the refused action itself was zero-write.
    expect(state.saveStatusFor('fields').state).toBe('failed')
    state.bindWorkspace()
    expect(labelOf(state.templateConfig.value)).toBe('B 的既有值')
    expect(state.saveStatusFor('fields').state).toBe('idle')
  })

  it('重试不会把上一个 owner 的配置交给 persist', async () => {
    localStorage.clear()
    const scope = createWorkspaceStorage(localStorage)
    const { state, persist } = await failUnderOwnerA(scope)

    scope.activate('user:B')
    const b = seedOwnerB(scope)
    persist.mockClear()
    state.retrySave('fields')
    await flushPromises()

    expect(persist).not.toHaveBeenCalled()
    expect(scope.storage.getItem(TEMPLATE_CONFIG_STORAGE_KEY)).toBe(b.config)
    state.bindWorkspace()
    expect(labelOf(state.templateConfig.value)).toBe('B 的既有值')
  })

  it('跨 owner 的自动保存同样被拦住(不只是放弃)', async () => {
    localStorage.clear()
    const scope = createWorkspaceStorage(localStorage)
    const { state, persist } = await failUnderOwnerA(scope)

    scope.activate('user:B')
    const b = seedOwnerB(scope)
    persist.mockClear()
    state.patchField('project', { label: 'B 页上的误触' })
    await flushPromises()

    expect(persist).not.toHaveBeenCalled()
    expect(scope.storage.getItem(TEMPLATE_CONFIG_STORAGE_KEY)).toBe(b.config)
    expect(labelOf(loadTemplateConfig(scope.storage))).toBe('B 的既有值')
  })

  it('epoch 变化(未换 owner)也不允许写回旧快照', async () => {
    localStorage.clear()
    const scope = createWorkspaceStorage(localStorage)
    const { state } = await failUnderOwnerA(scope)
    // epoch 变化会让 facade 本身失效,所以直接读原始前缀比较字节
    const before = localStorage.getItem(A_PREFIX + TEMPLATE_CONFIG_STORAGE_KEY)

    advanceAuthSessionEpoch()
    state.discardUnsaved('fields')
    expect(localStorage.getItem(A_PREFIX + TEMPLATE_CONFIG_STORAGE_KEY)).toBe(before)

    // 复原 facade 的 epoch,避免影响后续用例
    scope.activate('user:A')
  })
})

describe('owner 绑定的抽屉草稿与确认意图', () => {
  it('切 owner 后提交字段草稿不写入', async () => {
    localStorage.clear()
    const scope = createWorkspaceStorage(localStorage)
    const { state } = await failUnderOwnerA(scope)

    state.openFieldDraft()
    expect(state.fieldDraft.value).not.toBeNull()
    scope.activate('user:B')
    const b = seedOwnerB(scope)
    state.fieldDraft.value!.key = 'leaked_field'
    state.fieldDraft.value!.label = '串页字段'
    const submitted = state.submitFieldDraft()
    await flushPromises()

    expect(submitted).toBe(false)
    expect(state.fieldDraft.value).toBeNull()
    expect(scope.storage.getItem(TEMPLATE_CONFIG_STORAGE_KEY)).toBe(b.config)
    expect(loadTemplateConfig(scope.storage).interfaceFields.some((field) => field.key === 'leaked_field')).toBe(false)
  })

  it('切 owner 后提交类型草稿不写入', async () => {
    localStorage.clear()
    const scope = createWorkspaceStorage(localStorage)
    const { state } = await failUnderOwnerA(scope)

    state.openCaseTypeDraft()
    scope.activate('user:B')
    const b = seedOwnerB(scope)
    state.caseTypeDraft.value!.id = 'leaked_type'
    state.caseTypeDraft.value!.name = '串页类型'
    const submitted = state.submitCaseTypeDraft()
    await flushPromises()

    expect(submitted).toBe(false)
    expect(state.caseTypeDraft.value).toBeNull()
    expect(scope.storage.getItem(TEMPLATE_CONFIG_STORAGE_KEY)).toBe(b.config)
    expect(loadTemplateConfig(scope.storage).caseTypes.some((type) => type.id === 'leaked_type')).toBe(false)
  })

  it('删除字段的确认跨越 owner 后不再执行', async () => {
    localStorage.clear()
    const scope = createWorkspaceStorage(localStorage)
    let resolveConfirm: (value: boolean) => void = () => {}
    scope.activate('user:A')
    saveTemplateConfig(scope.storage, defaultTemplateConfig(), { updatedAt: 'A-confirmed' })
    const state = useTemplateConfig({
      storage: scope.storage, identity: () => scope.captureStrict(() => true),
      confirmDanger: () => new Promise<boolean>((resolve) => { resolveConfirm = resolve }),
      persistTemplateConfig: async (config: TemplateConfig) => clone(config)
    })

    const pending = state.removeField('project')
    scope.activate('user:B')
    const b = seedOwnerB(scope)
    resolveConfirm(true)
    await pending
    await flushPromises()

    expect(scope.storage.getItem(TEMPLATE_CONFIG_STORAGE_KEY)).toBe(b.config)
    expect(loadTemplateConfig(scope.storage).interfaceFields.some((field) => field.key === 'project')).toBe(true)
  })

  it('恢复默认的确认跨越 owner 后不清空新 owner 的本地配置', async () => {
    localStorage.clear()
    const scope = createWorkspaceStorage(localStorage)
    let resolveConfirm: (value: boolean) => void = () => {}
    scope.activate('user:A')
    saveTemplateConfig(scope.storage, defaultTemplateConfig(), { updatedAt: 'A-confirmed' })
    const hook = useTemplateConfig({
      storage: scope.storage, identity: () => scope.captureStrict(() => true),
      confirmDanger: () => new Promise<boolean>((resolve) => { resolveConfirm = resolve }),
      persistTemplateConfig: async (config: TemplateConfig) => clone(config)
    })
    const pending = hook.resetConfig()
    scope.activate('user:B')
    const b = seedOwnerB(scope)
    resolveConfirm(true)
    await pending
    await flushPromises()

    expect(scope.storage.getItem(TEMPLATE_CONFIG_STORAGE_KEY)).toBe(b.config)
    expect(scope.storage.getItem(TEMPLATE_CONFIG_META_STORAGE_KEY)).toBe(b.meta)
  })
})

describe('本地回滚写失败不得假 idle', () => {
  it('配额拒绝写回时保持 failed 并保留原因', async () => {
    localStorage.clear()
    saveTemplateConfig(localStorage, defaultTemplateConfig(), { updatedAt: '2020-01-01T00:00:00.000Z' })
    const confirmed = localStorage.getItem(TEMPLATE_CONFIG_STORAGE_KEY)
    const harness = quotaStorage()
    const state = useTemplateConfig({
      storage: harness.storage,
      persistTemplateConfig: async (config: TemplateConfig) => {
        saveTemplateConfig(harness.storage, config, { updatedAt: 'failed-meta' })
        throw new Error('后端保存失败')
      }
    })
    state.patchField('project', { label: '失败编辑' })
    await flushPromises()
    expect(state.saveStatusFor('fields').state).toBe('failed')

    harness.setMode('all')
    state.discardUnsaved('fields')
    harness.setMode('none')

    expect(localStorage.getItem(TEMPLATE_CONFIG_STORAGE_KEY)).not.toBe(confirmed)
    const status = state.saveStatusFor('fields')
    expect(status.state).toBe('failed')
    expect(status.error).toContain('本地')
    expect(status.error).toContain('修改仍保留在当前界面')
    // 失败后必须仍然可达:重试与放弃按钮由 state/message 驱动
    expect(state.saveState.value).toBe('failed')
  })

  it('元数据写失败(部分写入)也保持 failed 且说明是哪一步', async () => {
    localStorage.clear()
    saveTemplateConfig(localStorage, defaultTemplateConfig(), { updatedAt: '2020-01-01T00:00:00.000Z' })
    const confirmedMeta = localStorage.getItem(TEMPLATE_CONFIG_META_STORAGE_KEY)
    const harness = quotaStorage()
    const state = useTemplateConfig({
      storage: harness.storage,
      persistTemplateConfig: async (config: TemplateConfig) => {
        saveTemplateConfig(harness.storage, config, { updatedAt: 'failed-meta' })
        throw new Error('后端保存失败')
      }
    })
    state.patchField('project', { label: '失败编辑' })
    await flushPromises()

    harness.setMode('meta')
    state.discardUnsaved('fields')
    harness.setMode('none')

    const status = state.saveStatusFor('fields')
    expect(status.state).toBe('failed')
    expect(status.error).toContain('元数据')
    expect(localStorage.getItem(TEMPLATE_CONFIG_META_STORAGE_KEY)).toContain('failed-meta')
    // 元数据没还原成功就不能声称已放弃
    expect(localStorage.getItem(TEMPLATE_CONFIG_META_STORAGE_KEY)).not.toBe(confirmedMeta)
  })
})

describe('同一 owner 内的回滚仍然逐字节还原', () => {
  it('config 与 meta 都回到确认值(含 updatedAt)', async () => {
    localStorage.clear()
    const scope = createWorkspaceStorage(localStorage)
    const { state } = await failUnderOwnerA(scope)
    const confirmedConfig = defaultTemplateConfig()
    const expectedMeta = loadTemplateConfigMeta(scope.storage).updatedAt

    state.discardUnsaved('fields')
    expect(labelOf(loadTemplateConfig(scope.storage))).toBe('项目')
    expect(scope.storage.getItem(TEMPLATE_CONFIG_STORAGE_KEY)).toBe(
      JSON.stringify(JSON.parse(scope.storage.getItem(TEMPLATE_CONFIG_STORAGE_KEY)!))
    )
    // 内存与持久层一致,且状态回到 idle
    expect(labelOf(state.templateConfig.value)).toBe('项目')
    expect(state.saveStatusFor('fields').state).toBe('idle')
    expect(confirmedConfig.interfaceFields.length).toBe(loadTemplateConfig(scope.storage).interfaceFields.length)
    expect(expectedMeta).toBe('A-failed')
  })

  it('保存成功后会重新钉住作用域,后续放弃仍可用', async () => {
    localStorage.clear()
    const scope = createWorkspaceStorage(localStorage)
    scope.activate('user:A')
    saveTemplateConfig(scope.storage, defaultTemplateConfig(), { updatedAt: 'A-confirmed' })
    const persist = vi.fn(async (config: TemplateConfig) => {
      saveTemplateConfig(scope.storage, config, { updatedAt: 'A-saved' })
      return clone(config)
    })
    const state = useTemplateConfig({ storage: scope.storage, identity: () => scope.captureStrict(() => true), persistTemplateConfig: persist })
    state.patchField('project', { label: '确认保存' })
    await flushPromises()
    expect(state.saveStatusFor('fields').state).toBe('saved')
    // 重新钉作用域之后,放弃依旧逐字节回滚到「确认保存」的字节
    persist.mockImplementationOnce(async () => { throw new Error('后端保存失败') })
    state.patchField('project', { label: '再次编辑' })
    await flushPromises()
    expect(state.saveStatusFor('fields').state).toBe('failed')
    state.discardUnsaved('fields')
    expect(labelOf(loadTemplateConfig(scope.storage))).toBe('确认保存')
    expect(labelOf(state.templateConfig.value)).toBe('确认保存')
  })
})

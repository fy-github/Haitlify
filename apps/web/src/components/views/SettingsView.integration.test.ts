// @vitest-environment jsdom
// View-level integration test: mounts the real SettingsView with ElementPlus.
// Dynamic per-field state lives in table rows that don't render in jsdom, so coverage
// is a mount smoke test against the header and section labels.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { defaultTemplateConfig, saveTemplateConfig } from '../../domain/templateConfigRepository'

const loadView = async () => {
  vi.resetModules()
  const { default: SettingsView } = await import('./SettingsView.vue')
  return SettingsView as unknown as { new (): unknown }
}

const mountView = (Component: { new (): unknown }) =>
  mount(Component as never, { global: { plugins: [ElementPlus] } })

describe('SettingsView integration', () => {
  it('mounts with seeded template config and shows the template settings sections', async () => {
    localStorage.clear()
    saveTemplateConfig(localStorage, defaultTemplateConfig())
    const wrapper = mountView(await loadView())
    expect(wrapper.find('h1').text()).toContain('模板配置')
    expect(wrapper.text()).toContain('新增模块')
    expect(wrapper.text()).toContain('接口用例字段设置')
  })
})

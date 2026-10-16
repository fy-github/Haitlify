// @vitest-environment jsdom
// View-level integration test: mounts the real AiConfigView with ElementPlus.
// Its dynamic state (model select, provider switches) is bound to el-select/el-switch
// that don't surface as text in jsdom, so coverage is a mount smoke test.
import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { defaultAiConfig, saveAiConfig } from '../../domain/aiConfigRepository'

const loadView = async () => {
  vi.resetModules()
  const { default: AiConfigView } = await import('./AiConfigView.vue')
  return AiConfigView as unknown as { new (): unknown }
}

const mountView = (Component: { new (): unknown }) =>
  mount(Component as never, { global: { plugins: [ElementPlus] } })

describe('AiConfigView integration', () => {
  it('offers a global switch on every tab and saves it only on explicit save', async () => {
    localStorage.clear()
    const View = await loadView()
    const { useAiConfig } = await import('../../ui/useAiConfig')
    const platform = await import('../../domain/aiPlatformRepository')
    const state = useAiConfig()
    const config = defaultAiConfig()
    config.enabled = false
    config.providers[0]!.enabled = true
    state.applyAiConfig(config)
    const save = vi.spyOn(platform, 'saveAiConfigToPlatform').mockImplementation(async (value) => value)
    vi.spyOn(platform, 'listAiRunPolicies').mockResolvedValue({ items: [] })
    const wrapper = mountView(View)
    try {
      const toggle = wrapper.get('[data-testid="platform-ai-global-switch"] [role="switch"]')
      expect(toggle.attributes('aria-checked')).toBe('false')
      expect(wrapper.text()).toContain('点击「保存变更」后生效')
      for (const page of state.platformAiPages) {
        state.platformAiPage.value = page.key
        await flushPromises()
        expect(wrapper.find('[data-testid="platform-ai-global-switch"]').exists()).toBe(true)
      }
      await toggle.trigger('click')
      expect(state.aiSettingsForm.enabled).toBe(true)
      expect(state.aiSettingsForm.providers[0]!.enabled).toBe(true)
      expect(save).not.toHaveBeenCalled()
      await wrapper.findAll('button').find((button) => button.text().includes('保存变更'))!.trigger('click')
      await flushPromises()
      expect(save).toHaveBeenLastCalledWith(expect.objectContaining({ enabled: true }))
      await toggle.trigger('click')
      await wrapper.findAll('button').find((button) => button.text().includes('保存变更'))!.trigger('click')
      await flushPromises()
      expect(save).toHaveBeenLastCalledWith(expect.objectContaining({ enabled: false }))
    } finally {
      wrapper.unmount()
      vi.restoreAllMocks()
    }
  })

  it('mounts with seeded config and shows the platform AI header', async () => {
    localStorage.clear()
    saveAiConfig(localStorage, { ...defaultAiConfig(), enabled: true, model: 'gpt-4.1', baseUrl: 'https://api.example.com' })
    const wrapper = mountView(await loadView())
    expect(wrapper.exists()).toBe(true)
    expect(wrapper.find('h1').text()).toContain('平台 AI')
    // 静态分节标签证明视图完整挂载。
    expect(wrapper.text()).toContain('运行与诊断')
  })
})

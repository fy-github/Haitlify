// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'

vi.mock('../../domain/jiraIssueRepository', () => ({ loadJiraIssues: vi.fn(async () => ({ items: [] })) }))

describe('defect JSON file picker', () => {
  afterEach(() => { vi.restoreAllMocks(); localStorage.clear() })

  it('uses an off-screen non-tabbable proxy invoked only by the visible import button', async () => {
    vi.resetModules()
    const { useDefects } = await import('../../ui/useDefects')
    useDefects({ storage: localStorage })
    const { default: View } = await import('./DefectsView.vue')
    const wrapper = mount(View, { global: { plugins: [ElementPlus] } })
    try {
      const input = wrapper.get<HTMLInputElement>('input[type="file"]')
      expect(input.classes()).toContain('file-input-proxy')
      expect(input.classes()).not.toContain('native-file-input')
      expect(input.attributes('tabindex')).toBe('-1')
      expect(input.attributes('aria-hidden')).toBe('true')
      const click = vi.spyOn(input.element, 'click')
      await wrapper.get('[data-action="import-defect-json"]').trigger('click')
      expect(click).toHaveBeenCalledTimes(1)
      expect(input.attributes('accept')).toBe('application/json,.json')
    } finally { wrapper.unmount() }
  })
})

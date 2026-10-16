// @vitest-environment jsdom
import { defineComponent, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { initialSidebarCollapsed, useViewportMode, viewportModeForWidth } from './useViewportMode'

describe('useViewportMode', () => {
  it('maps approved desktop breakpoints', () => {
    expect(viewportModeForWidth(1600)).toBe('wide')
    expect(viewportModeForWidth(1280)).toBe('standard')
    expect(viewportModeForWidth(1024)).toBe('compact')
    expect(viewportModeForWidth(800)).toBe('narrow')
  })
  it('defaults a first compact visit to collapsed without overriding a stored preference', () => {
    expect(initialSidebarCollapsed(null, 1024)).toBe(true)
    expect(initialSidebarCollapsed(null, 1280)).toBe(false)
    expect(initialSidebarCollapsed('false', 1024)).toBe(false)
    expect(initialSidebarCollapsed('true', 1600)).toBe(true)
  })
  it('reacts to window resize and removes the listener on unmount', async () => {
    const add = vi.spyOn(window, 'addEventListener')
    const remove = vi.spyOn(window, 'removeEventListener')
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1280 })
    const Harness = defineComponent({ setup: () => ({ mode: useViewportMode() }), template: '<span>{{ mode }}</span>' })
    const wrapper = mount(Harness)
    expect(wrapper.text()).toBe('standard')
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1024 })
    window.dispatchEvent(new Event('resize'))
    await nextTick()
    expect(wrapper.text()).toBe('compact')
    const resizeHandler = add.mock.calls.find(([name]) => name === 'resize')?.[1]
    wrapper.unmount()
    expect(remove).toHaveBeenCalledWith('resize', resizeHandler)
    add.mockRestore(); remove.mockRestore()
  })
})

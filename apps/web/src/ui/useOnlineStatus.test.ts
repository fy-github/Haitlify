// @vitest-environment jsdom
import { defineComponent, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { useOnlineStatus } from './useOnlineStatus'

describe('useOnlineStatus', () => {
  it('reacts to offline and online events and cleans up listeners', async () => {
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: true })
    const remove = vi.spyOn(window, 'removeEventListener')
    const Harness = defineComponent({ setup: () => ({ online: useOnlineStatus() }), template: '<span>{{ online }}</span>' })
    const wrapper = mount(Harness)
    expect(wrapper.text()).toBe('true')
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: false })
    window.dispatchEvent(new Event('offline'))
    await nextTick()
    expect(wrapper.text()).toBe('false')
    wrapper.unmount()
    expect(remove.mock.calls.some(([name]) => name === 'online')).toBe(true)
    expect(remove.mock.calls.some(([name]) => name === 'offline')).toBe(true)
    remove.mockRestore()
  })
})

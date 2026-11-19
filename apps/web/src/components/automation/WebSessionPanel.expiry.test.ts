// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import WebSessionPanel from './WebSessionPanel.vue'

const mounted: ReturnType<typeof mount>[] = []
const now = Date.parse('2026-11-17T00:00:00Z')
const setup = async (expiresAt?: number) => {
  vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] })
  vi.setSystemTime(now)
  const fetchImpl = vi.fn(async () => new Response(JSON.stringify({ sessions: [
    { sessionId: 'expiry-fixture', browser: 'chromium', state: 'READY_FOR_COMPILE', leased: false, expiresAt }
  ] }), { status: 200 }))
  vi.stubGlobal('fetch', fetchImpl)
  const wrapper = mount(WebSessionPanel, { props: { visible: true }, global: { plugins: [ElementPlus] } })
  mounted.push(wrapper)
  await flushPromises()
  return { wrapper, fetchImpl }
}
afterEach(() => {
  mounted.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('Web Session expiry display', () => {
  it.each([undefined, 0, now - 1, now] as const)('does not label absent or expired deadline %s as one minute remaining', async (deadline) => {
    const { wrapper } = await setup(deadline)
    const row = wrapper.get('[data-testid="web-session-row-expiry-fixture"]')
    expect(row.findAll('td').slice(-1)[0]?.text()).toBe(deadline === undefined ? '—' : '已过期')
  })

  it('updates an open countdown without another request and releases its clock on close', async () => {
    const { wrapper, fetchImpl } = await setup(now + 61_000)
    const expiry = () => wrapper.get('[data-testid="web-session-row-expiry-fixture"]').findAll('td').slice(-1)[0]?.text()
    expect(expiry()).toBe('2 分钟')
    const timerCount = vi.getTimerCount()
    await vi.advanceTimersByTimeAsync(2_000)
    expect(expiry()).toBe('1 分钟')
    await vi.advanceTimersByTimeAsync(60_000)
    expect(expiry()).toBe('已过期')
    expect(fetchImpl).toHaveBeenCalledTimes(1)
    await wrapper.setProps({ visible: false })
    expect(vi.getTimerCount()).toBeLessThan(timerCount)
  })

  it('releases the countdown clock when the component unmounts', async () => {
    const { wrapper } = await setup(now + 60_000)
    const before = vi.getTimerCount()
    expect(before).toBeGreaterThan(0)
    wrapper.unmount()
    mounted.splice(mounted.indexOf(wrapper), 1)
    expect(vi.getTimerCount()).toBeLessThan(before)
  })
})

// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import WebSessionPanel from './WebSessionPanel.vue'
import { advanceAuthSessionEpoch, setUnauthorizedHandler } from '../../domain/http'

const response = (sessionId: string) => new Response(JSON.stringify({ sessions: [
  { sessionId, browser: 'chromium', state: 'READY_FOR_COMPILE', leased: false }
] }), { status: 200 })
const mounted: ReturnType<typeof mount>[] = []
const setup = async () => {
  const requests: Array<{ resolve: (value: Response) => void; reject: (error: Error) => void; signal?: AbortSignal | null }> = []
  vi.stubGlobal('fetch', vi.fn((_url: string, init?: RequestInit) => new Promise<Response>((resolve, reject) => {
    requests.push({ resolve, reject, signal: init?.signal })
  })))
  const wrapper = mount(WebSessionPanel, { props: { visible: true }, global: { plugins: [ElementPlus] } })
  mounted.push(wrapper)
  await flushPromises()
  expect(requests).toHaveLength(1)
  return { wrapper, requests }
}

afterEach(() => {
  mounted.splice(0).forEach((wrapper) => wrapper.unmount())
  setUnauthorizedHandler(null)
  vi.unstubAllGlobals()
})

describe('Web Session request lifecycle', () => {
  it.each(['success', 'error'] as const)('does not let an older refresh %s replace the newest list', async (outcome) => {
    const { wrapper, requests } = await setup()
    await wrapper.get('[data-testid="web-session-refresh"]').trigger('click')
    expect(requests).toHaveLength(2)
    requests[1].resolve(response('newest-session'))
    await flushPromises()
    expect(wrapper.find('[data-testid="web-session-row-newest-session"]').exists()).toBe(true)
    if (outcome === 'success') requests[0].resolve(response('obsolete-session'))
    else requests[0].reject(new Error('obsolete failure'))
    await flushPromises()
    expect(wrapper.find('[data-testid="web-session-row-newest-session"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="web-session-row-obsolete-session"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="web-session-error"]').exists()).toBe(false)
  })

  it.each(['success', 'error'] as const)('does not clear newer loading when an obsolete request settles with %s', async (outcome) => {
    const { wrapper, requests } = await setup()
    await wrapper.get('[data-testid="web-session-refresh"]').trigger('click')
    if (outcome === 'success') requests[0].resolve(response('obsolete-session'))
    else requests[0].reject(new Error('obsolete failure'))
    await flushPromises()
    expect(wrapper.find('[data-testid="web-session-loading"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="web-session-count"]').text()).toContain('待确认')
    requests[1].resolve(response('newest-session'))
    await flushPromises()
    expect(wrapper.find('[data-testid="web-session-row-newest-session"]').exists()).toBe(true)
  })

  it.each(['success', 'error'] as const)('invalidates a closed generation before reopening and ignores its late %s', async (outcome) => {
    const { wrapper, requests } = await setup()
    await wrapper.setProps({ visible: false })
    expect(requests[0].signal?.aborted).toBe(true)
    await wrapper.setProps({ visible: true })
    await flushPromises()
    expect(requests).toHaveLength(2)
    requests[1].resolve(response('reopened-session'))
    await flushPromises()
    if (outcome === 'success') requests[0].resolve(response('closed-session'))
    else requests[0].reject(new Error('closed failure'))
    await flushPromises()
    expect(wrapper.find('[data-testid="web-session-row-reopened-session"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="web-session-error"]').exists()).toBe(false)
  })

  it('aborts a dismissed request before emitting the close update', async () => {
    const { wrapper, requests } = await setup()
    wrapper.findComponent({ name: 'ElDialog' }).vm.$emit('update:modelValue', false)
    expect(wrapper.emitted('update:visible')).toEqual([[false]])
    expect(requests[0].signal?.aborted).toBe(true)
  })

  it.each(['refresh', 'close', 'unmount'] as const)('prevents a late 401 from an obsolete %s request expiring the current UI', async (boundary) => {
    const unauthorized = vi.fn()
    setUnauthorizedHandler(unauthorized)
    const { wrapper, requests } = await setup()
    if (boundary === 'refresh') await wrapper.get('[data-testid="web-session-refresh"]').trigger('click')
    else if (boundary === 'close') await wrapper.setProps({ visible: false })
    else { wrapper.unmount(); mounted.splice(mounted.indexOf(wrapper), 1) }
    requests[0].resolve(new Response(JSON.stringify({ message: 'obsolete session' }), { status: 401 }))
    await flushPromises()
    expect(unauthorized).not.toHaveBeenCalled()
  })

  it('still routes a current request 401 to the shared unauthorized handler', async () => {
    const unauthorized = vi.fn()
    setUnauthorizedHandler(unauthorized)
    const { requests } = await setup()
    requests[0].resolve(new Response(JSON.stringify({ message: 'current session expired' }), { status: 401 }))
    await flushPromises()
    expect(unauthorized).toHaveBeenCalledTimes(1)
  })

  it.each(['success', 'error'] as const)('does not publish an obsolete authentication epoch %s before an explicit retry', async (outcome) => {
    const { wrapper, requests } = await setup()
    advanceAuthSessionEpoch()
    if (outcome === 'success') requests[0].resolve(response('prior-auth-session'))
    else requests[0].reject(new Error('prior auth failure'))
    await flushPromises()
    expect(wrapper.find('[data-testid="web-session-row-prior-auth-session"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="web-session-error"]').exists()).toBe(false)
    await wrapper.get('[data-testid="web-session-refresh"]').trigger('click')
    expect(requests).toHaveLength(2)
    requests[1].resolve(response('current-auth-session'))
    await flushPromises()
    expect(wrapper.find('[data-testid="web-session-row-current-auth-session"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="web-session-loading"]').exists()).toBe(false)
  })
})

// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import GenerationAdmissionForm from './GenerationAdmissionForm.vue'
import { setUnauthorizedHandler } from '../../domain/http'

const user = { id: 73, username: 'qa', displayName: 'QA', role: 'EDITOR', mustChangePassword: false }
const project = { id: 8, tenantId: 42, code: 'A', name: '项目 A' }
const fields = { requirementRef: 'jira:QA-1', requirementText: '验证订单列表', targetUrl: 'https://example.com/orders', targetRevision: 'r42' }
const fill = async (wrapper: VueWrapper) => {
  for (const [name, value] of Object.entries(fields)) await wrapper.get(`[name="${name}"]`).setValue(value)
}
const deferred = <T,>() => {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const reply = (body: Record<string, unknown>, id = 'generation-1') => new Response(JSON.stringify({
  id, requestId: body.requestId, projectId: body.projectId, state: 'WAITING_WORKER', createdAt: '2026-10-22T00:00:00Z'
}), { status: 202 })
const bodyOf = (init?: RequestInit) => JSON.parse(String(init?.body))

describe('GenerationAdmissionForm', () => {
  afterEach(() => { setUnauthorizedHandler(null); vi.unstubAllGlobals() })

  it.each([
    { user: null, project }, { user: { ...user, id: undefined }, project },
    { user: { ...user, id: 0 }, project }, { user, project: null },
    { user, project: { ...project, tenantId: undefined } },
    { user, project: { ...project, id: -1 } }
  ])('fails closed when trusted context is incomplete: %j', async props => {
    const fetcher = vi.fn()
    vi.stubGlobal('fetch', fetcher)
    const wrapper = mount(GenerationAdmissionForm, { props: { user: props.user, project: props.project } })
    try {
      await fill(wrapper)
      expect(wrapper.get('[role="alert"]').text()).toContain('上下文')
      expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
      await wrapper.get('form').trigger('submit')
      expect(fetcher).not.toHaveBeenCalled()
    } finally { wrapper.unmount() }
  })

  it('uses the existing authenticated cookie/CSRF transport', async () => {
    document.cookie = 'XSRF-TOKEN=generation-csrf; path=/'
    const fetcher = vi.fn(async (_url: string, init?: RequestInit) => reply(bodyOf(init)))
    vi.stubGlobal('fetch', fetcher)
    const wrapper = mount(GenerationAdmissionForm, { props: { user, project } })
    try {
      await fill(wrapper)
      await wrapper.get('form').trigger('submit'); await flushPromises()
      const init = fetcher.mock.calls[0]![1]!
      expect(init.credentials).toBe('same-origin')
      expect(new Headers(init.headers).get('X-XSRF-TOKEN')).toBe('generation-csrf')
    } finally {
      wrapper.unmount()
      document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
    }
  })

  it.each([400, 401, 403, 409, 503])('shows actionable HTTP %s rejection without echoing response secrets', async status => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ message: 'secret-snapshot' }), { status })))
    const wrapper = mount(GenerationAdmissionForm, { props: { user, project } })
    try {
      await fill(wrapper)
      await wrapper.get('form').trigger('submit'); await flushPromises()
      expect(wrapper.get('[role="alert"]').text()).toContain(String(status))
      expect(wrapper.text()).not.toContain('secret-snapshot')
      expect(wrapper.get('button[type="submit"]').text()).toContain('重试')
      expect(wrapper.find('[role="status"]').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })

  it.each(Object.keys(fields))('rotates requestId on %s edits, even when restored to the original value', async name => {
    const fetcher = vi.fn(async (_url: string, _init?: RequestInit): Promise<Response> => { throw new TypeError('network') })
    vi.stubGlobal('fetch', fetcher)
    const wrapper = mount(GenerationAdmissionForm, { props: { user, project } })
    try {
      await fill(wrapper)
      await wrapper.get('form').trigger('submit'); await flushPromises()
      const previous = bodyOf(fetcher.mock.calls[0]![1]).requestId
      await wrapper.get(`[name="${name}"]`).setValue(`${fields[name as keyof typeof fields]}changed`)
      await wrapper.get(`[name="${name}"]`).setValue(fields[name as keyof typeof fields])
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
      await wrapper.get('form').trigger('submit'); await flushPromises()
      expect(bodyOf(fetcher.mock.calls[1]![1]).requestId).not.toBe(previous)
    } finally { wrapper.unmount() }
  })

  it.each([
    ['blank requirement', 'requirementText', '   '],
    ['oversize reference', 'requirementRef', 'a'.repeat(513)],
    ['oversize requirement', 'requirementText', 'a'.repeat(65537)],
    ['oversize revision', 'targetRevision', 'a'.repeat(129)],
    ['oversize URL', 'targetUrl', 'https://example.com/' + 'a'.repeat(2048)],
    ['non HTTP URL', 'targetUrl', 'file:///etc/passwd'],
    ['URL credentials', 'targetUrl', 'https://user:secret@example.com/']
  ])('blocks %s without a request', async (_label, name, value) => {
    const fetcher = vi.fn()
    vi.stubGlobal('fetch', fetcher)
    const wrapper = mount(GenerationAdmissionForm, { props: { user, project } })
    try {
      await fill(wrapper)
      await wrapper.get(`[name="${name}"]`).setValue(value)
      expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
      await wrapper.get('form').trigger('submit'); await flushPromises()
      expect(fetcher).not.toHaveBeenCalled()
    } finally { wrapper.unmount() }
  })

  it.each(['project', 'actor', 'tenant', 'payload', 'cancel', 'unmount'])('invalidates pending work on %s and ignores late success/error/finally', async change => {
    for (const settles of ['success', 'error']) {
      const old = deferred<Response>()
      const current = deferred<Response>()
      const fetcher = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>()
        .mockImplementationOnce(() => old.promise).mockImplementationOnce(() => current.promise)
      vi.stubGlobal('fetch', fetcher)
      const wrapper = mount(GenerationAdmissionForm, { props: { user, project } })
      await fill(wrapper)
      await wrapper.get('form').trigger('submit')
      const first = bodyOf(fetcher.mock.calls[0]![1])
      if (change === 'project') await wrapper.setProps({ project: { ...project, id: 9 } })
      if (change === 'actor') await wrapper.setProps({ user: { ...user, id: 74, username: 'other' } })
      if (change === 'tenant') await wrapper.setProps({ project: { ...project, tenantId: 53 } })
      if (change === 'payload') await wrapper.get('[name="requirementText"]').setValue('changed')
      if (change === 'cancel') await wrapper.get('[data-testid="generation-cancel"]').trigger('click')
      if (change === 'unmount') wrapper.unmount()
      try {
        expect(fetcher.mock.calls[0]![1]?.signal?.aborted).toBe(true)
        if (change !== 'unmount') {
          expect(wrapper.find('[role="status"]').exists()).toBe(false)
          await fill(wrapper)
          await wrapper.get('form').trigger('submit')
          expect(fetcher).toHaveBeenCalledTimes(2)
          const nextId = bodyOf(fetcher.mock.calls[1]![1]).requestId
          if (change === 'cancel') expect(nextId).toBe(first.requestId)
          else expect(nextId).not.toBe(first.requestId)
        }
        if (settles === 'success') old.resolve(reply(first, 'stale-generation'))
        else old.reject(new Error('stale error'))
        await flushPromises()
        if (change !== 'unmount') {
          expect(wrapper.text()).not.toContain('stale')
          expect(wrapper.find('[role="alert"]').exists()).toBe(false)
          expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
          current.resolve(reply(bodyOf(fetcher.mock.calls[1]![1]), 'current-generation'))
          await flushPromises()
          expect(wrapper.text()).toContain('current-generation')
          expect(wrapper.get('button[type="submit"]').text()).not.toContain('正在')
        }
        expect(fetcher).toHaveBeenCalledTimes(change === 'unmount' ? 1 : 2)
      } finally { if (change !== 'unmount') wrapper.unmount() }
    }
  })

  it('keeps the new actor receipt when the aborted previous actor fetch resolves with a real 401', async () => {
    const old = deferred<Response>()
    const fetcher = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>()
      .mockImplementationOnce(() => old.promise)
      .mockImplementation(async (_url, init) => reply(bodyOf(init), 'current-actor-receipt'))
    vi.stubGlobal('fetch', fetcher)
    const expire = vi.fn()
    setUnauthorizedHandler(expire)
    const wrapper = mount(GenerationAdmissionForm, { props: { user, project } })
    try {
      await fill(wrapper); await wrapper.get('form').trigger('submit')
      const first = bodyOf(fetcher.mock.calls[0]![1])
      await wrapper.setProps({ user: { ...user, id: 74, username: 'new-actor' } })
      expect(fetcher.mock.calls[0]![1]?.signal?.aborted).toBe(true)
      await fill(wrapper); await wrapper.get('form').trigger('submit'); await flushPromises()
      expect(bodyOf(fetcher.mock.calls[1]![1]).requestId).not.toBe(first.requestId)
      expect(wrapper.get('[role="status"]').text()).toContain('current-actor-receipt')
      old.resolve(new Response('{}', { status: 401 })); await flushPromises()
      expect(expire).not.toHaveBeenCalled()
      expect(wrapper.get('[role="status"]').text()).toContain('current-actor-receipt')
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
      expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
    } finally { wrapper.unmount() }
  })

  it.each(['success', 'network failure'])('clears display after %s without rotating the unchanged admission key', async outcome => {
    const fetcher = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>()
      .mockImplementationOnce(async (_url, init) => {
        if (outcome === 'network failure') throw new TypeError('network')
        return reply(bodyOf(init))
      })
      .mockImplementation(async (_url, init) => reply(bodyOf(init)))
    vi.stubGlobal('fetch', fetcher)
    const wrapper = mount(GenerationAdmissionForm, { props: { user, project } })
    try {
      await fill(wrapper)
      await wrapper.get('form').trigger('submit'); await flushPromises()
      expect(wrapper.find(outcome === 'success' ? '[role="status"]' : '[role="alert"]').exists()).toBe(true)
      const first = bodyOf(fetcher.mock.calls[0]![1])
      await wrapper.get('[data-testid="generation-cancel"]').trigger('click')
      expect(wrapper.find('[role="status"]').exists()).toBe(false)
      expect(wrapper.find('[role="alert"]').exists()).toBe(false)
      expect(wrapper.text()).toContain('不会撤销服务端已受理的记录')
      await wrapper.get('form').trigger('submit'); await flushPromises()
      expect(fetcher).toHaveBeenCalledTimes(2)
      expect(bodyOf(fetcher.mock.calls[1]![1])).toEqual(first)
      expect(wrapper.text()).toContain('generation-1')
    } finally { wrapper.unmount() }
  })

  it('deduplicates concurrent submits and reuses the exact requestId after an uncertain network failure', async () => {
    const pending = deferred<Response>()
    const fetcher = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>()
      .mockImplementationOnce(() => pending.promise)
      .mockImplementation(async (_url, init) => reply(bodyOf(init)))
    vi.stubGlobal('fetch', fetcher)
    const wrapper = mount(GenerationAdmissionForm, { props: { user, project } })
    try {
      await fill(wrapper)
      const form = wrapper.get('form')
      const first = form.trigger('submit')
      const second = form.trigger('submit')
      await Promise.all([first, second])
      expect(fetcher).toHaveBeenCalledTimes(1)
      const firstBody = bodyOf(fetcher.mock.calls[0]![1])
      expect(firstBody.requestId).toMatch(/^[A-Za-z0-9._-]{1,64}$/)
      pending.reject(new TypeError('Failed to fetch'))
      await flushPromises()
      expect(wrapper.get('[role="alert"]').text()).toContain('未确认')
      await form.trigger('submit'); await flushPromises()
      expect(fetcher).toHaveBeenCalledTimes(2)
      expect(bodyOf(fetcher.mock.calls[1]![1])).toEqual(firstBody)
      expect(wrapper.text()).toContain('WAITING_WORKER')
    } finally { wrapper.unmount() }
  })
})

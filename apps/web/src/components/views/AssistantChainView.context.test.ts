// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import AssistantChainView from './AssistantChainView.vue'

const user = { id: 73, username: 'qa', displayName: 'QA', role: 'EDITOR', mustChangePassword: false }
const projects = [{ id: 8, tenantId: 42, code: 'A', name: '项目 A' }, { id: 9, tenantId: 53, code: 'B', name: '项目 B' }]

describe('AssistantChainView trusted context', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('fails closed with a visible error when project discovery returns malformed data', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => ({}) })))
    const wrapper = mount(AssistantChainView, { props: { user } })
    try {
      await flushPromises()
      expect(wrapper.text()).toContain('项目列表响应无效')
      expect(wrapper.findAll('button').find(button => button.text() === '新建草稿')!.attributes('disabled')).toBeDefined()
    } finally { wrapper.unmount() }
  })

  it.each([
    ['missing user', null, projects],
    ['missing actor id', { ...user, id: undefined }, projects],
    ['missing tenant', user, [{ ...projects[0], tenantId: undefined }]]
  ])('blocks submission for %s', async (_label, auth, items) => {
    const fetcher = vi.fn(async () => ({ ok: true, json: async () => ({ items }) }))
    vi.stubGlobal('fetch', fetcher)
    const wrapper = mount(AssistantChainView, { props: { user: auth } })
    try {
      await flushPromises()
      if (auth) await wrapper.get('[data-testid="assistant-project"]').setValue('8')
      const create = wrapper.findAll('button').find(button => button.text() === '新建草稿')!
      expect(create.attributes('disabled')).toBeDefined()
      expect(wrapper.get('[role="alert"]').text()).toContain('上下文')
    } finally { wrapper.unmount() }
  })

  it('resets confirmation on project selection and sends subsequent drafts with the new tenant', async () => {
    const fetcher = vi.fn(async (url: string, init?: RequestInit) => ({ ok: true, json: async () => {
      if (url.includes('/api/projects')) return { items: projects }
      if (url.endsWith('/confirm')) return { draftId: 'd', state: 'CONFIRMED' }
      return { draftId: 'd', state: 'DRAFT', ...JSON.parse(String(init?.body)) }
    } }))
    vi.stubGlobal('fetch', fetcher)
    const wrapper = mount(AssistantChainView, { props: { user } })
    const button = (label: string) => wrapper.findAll('button').find(item => item.text() === label)!
    try {
      await flushPromises()
      await wrapper.get('[data-testid="assistant-project"]').setValue('8')
      await button('新建草稿').trigger('click'); await flushPromises()
      await button('确认').trigger('click'); await flushPromises()
      expect(button('执行').attributes('disabled')).toBeUndefined()
      await wrapper.get('[data-testid="assistant-project"]').setValue('9')
      expect(button('执行').attributes('disabled')).toBeDefined()
      expect(wrapper.text()).not.toContain('CONFIRMED')
      await button('新建草稿').trigger('click'); await flushPromises()
      const calls = fetcher.mock.calls.filter(([url]) => url.endsWith('/drafts'))
      const call = calls[calls.length - 1]!
      expect(JSON.parse(String(call[1]?.body))).toMatchObject({ projectId: 9, tenantId: 53, createdBy: 73 })
      await wrapper.setProps({ user: null })
      expect(button('新建草稿').attributes('disabled')).toBeDefined()
      expect(wrapper.findAll('option')).toHaveLength(1)
    } finally { wrapper.unmount() }
  })

  it('uses the selected API project tenant and authenticated actor, not defaults or editable IDs', async () => {
    const fetcher = vi.fn(async (url: string, init?: RequestInit) => ({
      ok: true, json: async () => url.includes('/api/projects') ? { items: projects } : { draftId: 'd', state: 'DRAFT', ...JSON.parse(String(init?.body)) }
    }))
    vi.stubGlobal('fetch', fetcher)
    const wrapper = mount(AssistantChainView, { props: { user } })
    try {
      await flushPromises()
      expect(wrapper.find('[data-testid="assistant-project"]').exists()).toBe(true)
      await wrapper.get('[data-testid="assistant-project"]').setValue('8')
      const create = wrapper.findAll('button').find(button => button.text() === '新建草稿')!
      await create.trigger('click')
      await flushPromises()
      const call = fetcher.mock.calls.find(([url]) => url.includes('/api/assistant/drafts'))!
      expect(JSON.parse(String(call[1]?.body))).toMatchObject({ tenantId: 42, projectId: 8, createdBy: 73 })
      expect(wrapper.text()).toContain('开发链路')
      expect(wrapper.text()).not.toContain('租户 ID')
      expect(wrapper.text()).not.toContain('操作人 ID')
    } finally { wrapper.unmount() }
  })
})

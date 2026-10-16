// @vitest-environment jsdom
// View-level integration test: UserManagementView loads users through the HTTP layer on
// mount (listUsers -> authedFetch -> global fetch). The user table rows don't render in
// jsdom, so the assertions cover the mount + fetch chain and the header.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'

const users = [
  { username: 'admin', displayName: '管理员', role: 'ADMIN', enabled: true, mustChangePassword: false, createdAt: '2026-08-01T00:00:00Z' },
  { username: 'lify', displayName: 'Lify', role: 'EDITOR', enabled: true, mustChangePassword: false, createdAt: '2026-08-01T00:00:00Z' }
]

describe('UserManagementView integration', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('loads users through the HTTP layer on mount and renders the header', async () => {
    vi.resetModules()
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => users })
    vi.stubGlobal('fetch', fetchMock)
    const { default: UserManagementView } = await import('./UserManagementView.vue')
    const wrapper = mount(UserManagementView, {
      props: { currentUsername: 'admin' },
      global: { plugins: [ElementPlus] }
    })
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalled())
    expect(String(fetchMock.mock.calls[0][0])).toContain('/api/users')
    expect(wrapper.find('h1').text()).toContain('用户管理')
  })
})

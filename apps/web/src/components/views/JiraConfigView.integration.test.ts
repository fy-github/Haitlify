// @vitest-environment jsdom
// View-level integration test: JiraConfigView is fully props-driven (App.vue wires the
// form + actions), so the test renders it with mock handlers and verifies the wiring.
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import type { JiraConfig } from '../../domain/jiraConfigRepository'
import JiraConfigView from './JiraConfigView.vue'

interface Props {
  form: JiraConfig
  loading: boolean
  testing: boolean
  projectJqlRows: Array<{ key: string; value: string }>
  load: () => void
  save: () => void
  test: () => void
  addJqlRow: () => void
  removeJqlRow: (index: number) => void
  syncJqlForm: () => void
}

const baseForm = (): JiraConfig => ({
  enabled: true,
  baseUrl: '',
  authMode: 'service-account',
  username: '',
  token: '',
  credentialConfigured: false,
  projectJql: {}
})

const mountWithProps = (overrides: Partial<Props> = {}) => {
  const props: Props = {
    form: baseForm(),
    loading: false,
    testing: false,
    projectJqlRows: [],
    load: vi.fn(),
    save: vi.fn(),
    test: vi.fn(),
    addJqlRow: vi.fn(),
    removeJqlRow: vi.fn(),
    syncJqlForm: vi.fn(),
    ...overrides
  }
  return mount(JiraConfigView, { props, global: { plugins: [ElementPlus] } })
}

describe('JiraConfigView integration', () => {
  it('renders the header and the empty JQL state', () => {
    const wrapper = mountWithProps()
    expect(wrapper.find('h1').text()).toContain('Jira 配置')
    expect(wrapper.text()).toContain('还没有 JQL 映射，点击「新增映射」添加。')
  })

  it('renders one JQL row per projectJqlRows entry', () => {
    const wrapper = mountWithProps({
      projectJqlRows: [{ key: 'HAITLIFY_API', value: 'project = HAITLIFY order by created DESC' }]
    })
    expect(wrapper.findAll('.jira-config-jql__row').length).toBe(1)
  })

  it('invokes the toolbar action props on button clicks', async () => {
    const load = vi.fn()
    const save = vi.fn()
    const test = vi.fn()
    const addJqlRow = vi.fn()
    const wrapper = mountWithProps({ load, save, test, addJqlRow })
    const buttonByText = (text: string) =>
      wrapper.findAll('button').find((button) => button.text().includes(text))
    await buttonByText('重新加载')!.trigger('click')
    expect(load).toHaveBeenCalledTimes(1)
    await buttonByText('连接测试')!.trigger('click')
    expect(test).toHaveBeenCalledTimes(1)
    await buttonByText('保存配置')!.trigger('click')
    expect(save).toHaveBeenCalledTimes(1)
    await buttonByText('新增映射')!.trigger('click')
    expect(addJqlRow).toHaveBeenCalledTimes(1)
  })
})

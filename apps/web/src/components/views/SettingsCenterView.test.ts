// @vitest-environment jsdom
// 设置中心壳:内部分组导航已去掉(与主侧边栏重复),按 activeNav 分节渲染 4 个设置视图;
// 分组切换全部走主侧边栏 selectSidebarItem,activeNav 由父级传入。
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import type { JiraConfig } from '../../domain/jiraConfigRepository'
import { defaultTemplateConfig, saveTemplateConfig } from '../../domain/templateConfigRepository'
import type { NavKey } from '../../domain/appNavigation'

interface Props {
  activeNav: NavKey
  role?: string
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
  currentUsername: string
}

const baseJira = (): JiraConfig => ({
  enabled: true,
  baseUrl: '',
  authMode: 'service-account',
  username: '',
  token: '',
  credentialConfigured: false,
  projectJql: {}
})

const makeProps = (overrides: Partial<Props> = {}): Props => ({
  activeNav: 'settings',
  role: 'ADMIN',
  form: baseJira(),
  loading: false,
  testing: false,
  projectJqlRows: [],
  load: vi.fn(),
  save: vi.fn(),
  test: vi.fn(),
  addJqlRow: vi.fn(),
  removeJqlRow: vi.fn(),
  syncJqlForm: vi.fn(),
  currentUsername: 'admin',
  ...overrides
})

const mountView = async (props: Props) => {
  vi.resetModules()
  const { default: Component } = await import('./SettingsCenterView.vue')
  return mount(Component, { props, global: { plugins: [ElementPlus] } })
}

describe('SettingsCenterView', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('renders the template settings section from the settings nav', async () => {
    localStorage.clear()
    saveTemplateConfig(localStorage, defaultTemplateConfig())
    const wrapper = await mountView(makeProps({ activeNav: 'settings', role: 'ADMIN' }))
    expect(wrapper.find('h1').text()).toContain('模板字段')
    expect(wrapper.find('.workbench__pane').exists()).toBe(true)
  })

  it('renders the Jira config section from forwarded props', async () => {
    const wrapper = await mountView(makeProps({
      activeNav: 'jiraConfig',
      projectJqlRows: [{ key: 'HAITLIFY_API', value: 'project = HAITLIFY' }]
    }))
    expect(wrapper.find('h1').text()).toContain('Jira 配置')
    // 设计稿把映射从卡片行改成表格行
    expect(wrapper.findAll('.j-table tbody tr').length).toBe(1)
  })

  it('renders the user management section for admins only', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [] }))
    const admin = await mountView(makeProps({ activeNav: 'users', role: 'ADMIN' }))
    expect(admin.find('h1').text()).toContain('用户管理')

    const viewer = await mountView(makeProps({ activeNav: 'users', role: 'VIEWER' }))
    expect(viewer.find('.workbench__placeholder').exists()).toBe(true)
    expect(viewer.find('.workbench__pane--full').exists()).toBe(true)
  })
})

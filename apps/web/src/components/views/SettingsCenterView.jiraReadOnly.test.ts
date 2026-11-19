// @vitest-environment jsdom
// 复核轮 2(R14):只读角色进入 Jira 配置页时,写控件必须 disabled —— 由 SettingsCenterView
// 透传 canEdit 到 JiraConfigView,而不是各视图自己判断角色。
import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import type { JiraConfig } from '../../domain/jiraConfigRepository'

// 动态导入:与既有视图测试一致,避免单文件 tsc 对 .vue 模块声明的解析
const CENTER = './SettingsCenterView.vue'
const JIRA = './JiraConfigView.vue'

const form = (): JiraConfig => ({
  enabled: true,
  baseUrl: 'https://jira-a.example.test',
  authMode: 'service-account',
  username: 'reader',
  token: '',
  credentialConfigured: true,
  maskedToken: 'masked',
  projectJql: { APP: 'project = APP' }
})

const rows = () => [{ key: 'APP', value: 'project = APP' }]

const noop = () => undefined

const mountCenter = async (canEdit: boolean | undefined, role = 'VIEWER') => {
  const { default: SettingsCenterView } = await import(CENTER)
  const props: Record<string, unknown> = {
    activeNav: 'jiraConfig',
    role,
    form: form(),
    loading: false,
    testing: false,
    projectJqlRows: rows(),
    load: noop,
    save: noop,
    test: noop,
    addJqlRow: noop,
    removeJqlRow: noop,
    syncJqlForm: noop,
    savedSnapshot: '',
    validationError: '',
    currentUsername: 'viewer'
  }
  if (canEdit !== undefined) props.canEdit = canEdit
  const wrapper = mount(SettingsCenterView, { props, global: { plugins: [ElementPlus] }, attachTo: document.body })
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

const mounted: Array<ReturnType<typeof mount>> = []

const findByText = (text: string): HTMLButtonElement | undefined =>
  (Array.from(document.querySelectorAll('button')) as HTMLButtonElement[])
    .find((button) => (button.textContent || '').includes(text))

afterEach(() => {
  mounted.splice(0).forEach((wrapper) => {
    try {
      wrapper.unmount()
    } catch {
      // 已卸载的实例忽略
    }
  })
  document.body.innerHTML = ''
})

describe('Jira 配置页的只读门禁(R14)', () => {
  it('canEdit=false:保存/测试/新增映射/删除全部禁用,输入不可编辑', async () => {
    await mountCenter(false)
    expect((document.querySelector('.jira-head .el-button--primary') as HTMLButtonElement).disabled).toBe(true)
    expect(findByText('连接测试')?.disabled).toBe(true)
    expect(findByText('新增映射')?.disabled).toBe(true)
    expect((document.querySelector('.j-table .j-remove') as HTMLButtonElement).disabled).toBe(true)
    expect((document.querySelector('#jira-base-url') as HTMLInputElement).readOnly).toBe(true)
    expect((document.querySelector('.j-toggle input') as HTMLInputElement).disabled).toBe(true)
    // 读操作保留:重新加载不是写
    expect(findByText('重新加载')?.disabled).toBe(false)
  })

  it('canEdit=true(默认):写控件保持可用', async () => {
    await mountCenter(true, 'ADMIN')
    expect((document.querySelector('.jira-head .el-button--primary') as HTMLButtonElement).disabled).toBe(false)
    expect(findByText('连接测试')?.disabled).toBe(false)
    expect(findByText('新增映射')?.disabled).toBe(false)
    expect((document.querySelector('#jira-base-url') as HTMLInputElement).readOnly).toBe(false)
  })

  it('未传 canEdit 时保持既有行为(可编辑),避免既有调用方回归', async () => {
    await mountCenter(undefined, 'ADMIN')
    expect((document.querySelector('.jira-head .el-button--primary') as HTMLButtonElement).disabled).toBe(false)
  })

  it('JiraConfigView 自身也接受 canEdit(不依赖父视图判断)', async () => {
    const { default: JiraConfigView } = await import(JIRA)
    const wrapper = mount(JiraConfigView, {
      props: {
        form: form(),
        loading: false,
        testing: false,
        projectJqlRows: rows(),
        load: noop,
        save: noop,
        test: noop,
        addJqlRow: noop,
        removeJqlRow: noop,
        syncJqlForm: noop,
        canEdit: false
      },
      global: { plugins: [ElementPlus] },
      attachTo: document.body
    })
    mounted.push(wrapper)
    await flushPromises()
    expect((document.querySelector('.jira-head .el-button--primary') as HTMLButtonElement).disabled).toBe(true)
    expect(findByText('新增映射')?.disabled).toBe(true)
  })
})

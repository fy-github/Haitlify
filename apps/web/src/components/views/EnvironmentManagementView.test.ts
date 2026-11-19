// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import EnvironmentManagementView from './EnvironmentManagementView.vue'

const environments = [
  { id: 'env-api', name: '接口测试环境', baseUrl: 'https://api.fixture.test' },
  { id: 'env-web', name: 'Web 测试环境', baseUrl: 'https://web.fixture.test' }
]

const mountView = (props: Record<string, unknown> = {}) => mount(EnvironmentManagementView as never, {
  props: { kind: 'api', environments, activeEnvironmentId: 'env-api', ...props },
  global: { plugins: [ElementPlus] }
} as never)

describe('EnvironmentManagementView', () => {
  it('exposes API/Web category switching without implying other categories exist', async () => {
    const wrapper = mountView()
    expect(wrapper.find('h1').text()).toBe('环境配置')
    const kindButtons = wrapper.findAll('[data-testid="environment-kind-switch"] button')
    expect(kindButtons.map((button) => button.text())).toEqual(['API 环境', 'Web 环境'])
    await kindButtons[1].trigger('click')
    expect(wrapper.emitted('update:kind')?.[0]).toEqual(['web'])
  })

  it('filters the environment directory by search text', async () => {
    const wrapper = mountView()
    const items = () => wrapper.findAll('[data-testid="environment-directory-item"]')
    expect(items()).toHaveLength(2)
    await wrapper.find('input[data-testid="environment-search"]').setValue('web')
    expect(items()).toHaveLength(1)
    expect(items()[0].attributes('data-environment-id')).toBe('env-web')
    await wrapper.find('input[data-testid="environment-search"]').setValue('不存在的环境')
    expect(wrapper.find('[data-testid="environment-directory-empty"]').exists()).toBe(true)
  })

  it.each(['api', 'web'] as const)('shows the %s form directly without the storage-contract notice', (kind) => {
    const wrapper = mount(EnvironmentManagementView, {
      props: { kind, environments },
      slots: { default: '<div data-testid="form-host"></div>' }
    })
    const editor = wrapper.get('[data-testid="environment-page-editor"]')
    expect(editor.find('[data-testid="form-host"]').exists()).toBe(true)
    expect(editor.text()).not.toContain('环境表单与执行准备页共用同一编辑器与存储契约')
    wrapper.unmount()
  })

  it('selects an environment and returns to preparation with the current kind', async () => {
    const wrapper = mountView({ kind: 'web' })
    await wrapper.findAll('[data-testid="environment-directory-item"]')[0].trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual(['env-api'])
    await wrapper.find('[data-testid="environment-back-to-preparation"]').trigger('click')
    expect(wrapper.emitted('back')?.[0]).toEqual(['web'])
  })
})

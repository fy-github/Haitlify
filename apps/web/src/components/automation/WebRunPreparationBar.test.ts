// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it } from 'vitest'
import WebRunPreparationBar from './WebRunPreparationBar.vue'

const mountedWrappers: Array<{ unmount: () => void }> = []

afterEach(() => {
  mountedWrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})

const baseProps = () => ({
  environmentId: 'staging',
  environments: [{ value: 'staging', label: '预发布环境' }, { value: 'prod', label: '生产环境' }],
  browser: 'chromium',
  browsers: [
    { value: 'chromium', label: 'Chromium', disabled: false, recommendation: '推荐' },
    { value: 'firefox', label: 'Firefox', disabled: false, recommendation: '' },
    { value: 'webkit', label: 'WebKit', disabled: true, recommendation: '当前执行器未安装' }
  ],
  loopCount: 2,
  loginReadyLabel: '登录态已就绪',
  actionLabel: '执行 12 条用例',
  canExecute: true,
  blockingReason: '',
  compact: true,
  view: 'form' as const
})

const mountBar = (overrides = {}) => {
  const wrapper = mount(WebRunPreparationBar, {
    props: { ...baseProps(), ...overrides },
    global: { plugins: [ElementPlus] },
    attachTo: document.body
  })
  mountedWrappers.push(wrapper)
  return wrapper
}

describe('WebRunPreparationBar', () => {
  it('shows controlled preparation values, browser availability and readiness', () => {
    const wrapper = mountBar()
    const selects = wrapper.findAllComponents({ name: 'ElSelect' })
    const inputNumber = wrapper.getComponent({ name: 'ElInputNumber' })
    const browserOptions = wrapper.findAllComponents({ name: 'ElOption' })

    expect(selects[0].props('modelValue')).toBe('staging')
    expect(selects[1].props('modelValue')).toBe('chromium')
    expect(inputNumber.props('modelValue')).toBe(2)
    expect(wrapper.get('[aria-label="Web 执行环境"]').attributes('aria-label')).toBe('Web 执行环境')
    expect(wrapper.get('[aria-label="浏览器选择"]').attributes('aria-label')).toBe('浏览器选择')
    expect(browserOptions.map((option) => option.props('label'))).toEqual(['预发布环境', '生产环境', 'Chromium', 'Firefox', 'WebKit'])
    expect(browserOptions[4].props('disabled')).toBe(true)
    expect(browserOptions[4].attributes('title')).toBe('当前执行器未安装')
    expect(wrapper.get('[data-testid="execute-web-automation"]').text()).toContain('执行 12 条用例')
    wrapper.unmount()
  })

  it('emits normalized controlled changes and commands without mutating props', async () => {
    const wrapper = mountBar()
    const originalProps = { ...wrapper.props() }

    await wrapper.get('[aria-label="Web 执行环境"]').trigger('click')
    await wrapper.vm.$nextTick()
    const productionOption = Array.from(document.body.querySelectorAll('.el-select-dropdown__item'))
      .find((item) => item.textContent?.includes('生产环境')) as HTMLElement | undefined
    expect(productionOption).toBeDefined()
    productionOption?.click()
    await wrapper.vm.$nextTick()

    await wrapper.get('[aria-label="浏览器选择"]').trigger('click')
    await wrapper.vm.$nextTick()
    const firefoxOption = Array.from(document.body.querySelectorAll('.el-select-dropdown__item'))
      .find((item) => item.textContent?.includes('Firefox')) as HTMLElement | undefined
    expect(firefoxOption).toBeDefined()
    firefoxOption?.click()
    await wrapper.get('[aria-label="重复次数"]').setValue('99')
    await wrapper.get('[aria-label="编辑 Web 执行环境"]').trigger('click')
    await wrapper.get('[data-testid="execute-web-automation"]').trigger('click')

    expect(wrapper.emitted('updateEnvironmentId')).toEqual([['prod']])
    expect(wrapper.emitted('updateBrowser')).toEqual([['firefox']])
    expect(wrapper.emitted('updateLoopCount')).toEqual([[20]])
    expect(wrapper.emitted('editEnvironment')).toHaveLength(1)
    expect(wrapper.emitted('execute')).toHaveLength(1)
    expect(wrapper.props()).toMatchObject(originalProps)
    wrapper.unmount()
  })

  it('launches inline controls in launcher mode and keeps execution available', async () => {
    const wrapper = mountBar({ view: 'launcher' })

    expect(wrapper.text()).toContain('运行环境')
    expect(wrapper.text()).toContain('Chromium')
    expect(wrapper.find('[aria-label="Web 执行环境"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="浏览器选择"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="重复次数"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('登录会话')
    expect(wrapper.text()).not.toContain('无需自动登录')

    await wrapper.get('[data-testid="execute-web-automation"]').trigger('click')

    expect(wrapper.emitted('execute')).toHaveLength(1)
    wrapper.unmount()
  })

  it('allows the same normalized loop intent after a controlled prop update', async () => {
    const wrapper = mountBar()
    const input = wrapper.get('[aria-label="重复次数"]')

    await input.setValue('99')
    expect(wrapper.emitted('updateLoopCount')).toEqual([[20]])

    await wrapper.setProps({ loopCount: 3 })
    await wrapper.get('[aria-label="重复次数"]').setValue('99')

    expect(wrapper.emitted('updateLoopCount')).toEqual([[20], [20]])
    wrapper.unmount()
  })

  it('allows a later repeated normalized loop intent without parent acknowledgement', async () => {
    const wrapper = mountBar()
    const input = wrapper.get('[aria-label="重复次数"]')

    await input.setValue('99')
    await Promise.resolve()
    await input.setValue('99')
    await Promise.resolve()

    expect(wrapper.emitted('updateLoopCount')).toEqual([[20], [20]])
    wrapper.unmount()
  })

  it('keeps a blocking reason live region and prevents disabled execution', async () => {
    const wrapper = mountBar({ canExecute: false, blockingReason: '请选择至少一条可执行用例' })
    const reason = wrapper.get('[data-testid="web-execution-blocking-reason"]')

    expect(reason.attributes('aria-live')).toBe('polite')
    expect(reason.text()).toBe('请选择至少一条可执行用例')
    expect(wrapper.get('[data-testid="execute-web-automation"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="execute-web-automation"]').trigger('click')
    expect(wrapper.emitted('execute')).toBeUndefined()
    wrapper.unmount()
  })
})

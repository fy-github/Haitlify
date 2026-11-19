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
  loopCount: 2
})

const mountBar = (overrides: Record<string, unknown> = {}) => {
  const wrapper = mount(WebRunPreparationBar, {
    props: { ...baseProps(), ...overrides },
    global: { plugins: [ElementPlus] },
    attachTo: document.body
  })
  mountedWrappers.push(wrapper)
  return wrapper
}

const pickOption = async (wrapper: ReturnType<typeof mountBar>, label: string, optionText: string) => {
  await wrapper.get(`[aria-label="${label}"]`).trigger('click')
  await wrapper.vm.$nextTick()
  const option = Array.from(document.body.querySelectorAll('.el-select-dropdown__item'))
    .find((item) => item.textContent?.includes(optionText)) as HTMLElement | undefined
  expect(option).toBeDefined()
  option?.click()
  await wrapper.vm.$nextTick()
}

describe('WebRunPreparationBar', () => {
  it('renders only the runtime parameters used inside the 740px configuration drawer', () => {
    const wrapper = mountBar()

    expect(wrapper.findAllComponents({ name: 'ElSelect' }).map((select) => select.props('modelValue')))
      .toEqual(['staging', 'chromium'])
    expect(wrapper.find('[aria-label="Web 执行环境"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="浏览器选择"]').exists()).toBe(true)
    expect((wrapper.get('[aria-label="循环次数"]').element as HTMLInputElement).value).toBe('2')

    // 执行动作与阻塞原因已上移到页头 + 确认弹窗；参数表单不再内联执行按钮或摘要条。
    expect(wrapper.find('[data-testid="execute-web-automation"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="web-execution-blocking-reason"]').exists()).toBe(false)
    expect(wrapper.findAllComponents({ name: 'ElInputNumber' })).toHaveLength(0)
    expect(wrapper.text()).not.toContain('运行环境预发布环境')
  })

  it('keeps browser availability and emits controlled environment/browser changes', async () => {
    const wrapper = mountBar()
    const originalProps = { ...wrapper.props() }
    const browserOptions = wrapper.findAllComponents({ name: 'ElOption' })

    expect(browserOptions.map((option) => option.props('label')))
      .toEqual(['预发布环境', '生产环境', 'Chromium', 'Firefox', 'WebKit'])
    expect(browserOptions[4].props('disabled')).toBe(true)
    expect(browserOptions[4].attributes('title')).toBe('当前执行器未安装')

    await pickOption(wrapper, 'Web 执行环境', '生产环境')
    await pickOption(wrapper, '浏览器选择', 'Firefox')

    expect(wrapper.emitted('updateEnvironmentId')).toEqual([['prod']])
    expect(wrapper.emitted('updateBrowser')).toEqual([['firefox']])
    expect(wrapper.props()).toMatchObject(originalProps)
  })

  it('uses a −/输入/＋ stepper that disables at the boundaries', async () => {
    const wrapper = mountBar({ loopCount: 1 })

    expect(wrapper.get('[data-testid="loop-decrease"]').text()).toBe('−')
    expect(wrapper.get('[data-testid="loop-decrease"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="loop-increase"]').attributes('disabled')).toBeUndefined()

    await wrapper.setProps({ loopCount: 20 })
    expect(wrapper.get('[data-testid="loop-increase"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="loop-decrease"]').attributes('disabled')).toBeUndefined()

    await wrapper.setProps({ loopCount: 3 })
    await wrapper.get('[data-testid="loop-decrease"]').trigger('click')
    expect(wrapper.emitted('updateLoopCount')).toEqual([[2]])
    await wrapper.get('[data-testid="loop-increase"]').trigger('click')
    expect(wrapper.emitted('updateLoopCount')).toEqual([[2], [4]])
  })

  it.each([
    ['0'],
    ['21'],
    ['2.5'],
    ['abc'],
    ['']
  ])('rejects the invalid loop input %s instead of silently executing a normalized value', async (raw) => {
    const wrapper = mountBar()
    const input = wrapper.get('[data-testid="loop-input"]')

    await input.setValue(raw)

    expect(wrapper.get('[data-testid="loop-error"]').text()).toBe('循环次数需为 1–20 的整数')
    expect(wrapper.get('[data-testid="loop-error"]').attributes('role')).toBe('alert')
    expect(wrapper.emitted('updateLoopCount')).toBeUndefined()
    // 非法输入保持原样，供用户直接修正，不被静默改写为合法值。
    expect((input.element as HTMLInputElement).value).toBe(raw)
  })

  it('emits a valid directly typed loop count once and clears the error', async () => {
    const wrapper = mountBar()
    const input = wrapper.get('[data-testid="loop-input"]')

    await input.setValue('0')
    expect(wrapper.find('[data-testid="loop-error"]').exists()).toBe(true)

    await input.setValue('7')
    expect(wrapper.emitted('updateLoopCount')).toEqual([[7]])
    expect(wrapper.find('[data-testid="loop-error"]').exists()).toBe(false)

    await input.setValue('7')
    expect(wrapper.emitted('updateLoopCount')).toEqual([[7]])
  })

  it('keeps the typed loop value when another picker rerenders the form', async () => {
    const wrapper = mountBar()

    await wrapper.get('[data-testid="loop-input"]').setValue('9')
    await pickOption(wrapper, '浏览器选择', 'Firefox')
    await wrapper.setProps({ browser: 'firefox', loopCount: 9 })

    expect((wrapper.get('[data-testid="loop-input"]').element as HTMLInputElement).value).toBe('9')
    expect(wrapper.emitted('updateLoopCount')).toEqual([[9]])

    await wrapper.get('[data-testid="loop-increase"]').trigger('click')
    expect(wrapper.emitted('updateLoopCount')).toEqual([[9], [10]])
  })

  it('keeps an invalid typed value and its error while the environment picker changes', async () => {
    const wrapper = mountBar()

    await wrapper.get('[data-testid="loop-input"]').setValue('0')
    await pickOption(wrapper, 'Web 执行环境', '生产环境')
    await wrapper.setProps({ environmentId: 'prod' })

    expect(wrapper.emitted('updateEnvironmentId')).toEqual([['prod']])
    expect(wrapper.emitted('updateLoopCount')).toBeUndefined()
    expect((wrapper.get('[data-testid="loop-input"]').element as HTMLInputElement).value).toBe('0')
    expect(wrapper.find('[data-testid="loop-error"]').exists()).toBe(true)
  })

  it('reports loop-count validity so the drawer cannot apply an invalid value', async () => {
    const wrapper = mountBar()

    const lastValidity = () => {
      const emitted = wrapper.emitted('loopCountValidity') || []
      return emitted[emitted.length - 1]
    }

    await wrapper.get('[data-testid="loop-input"]').setValue('0')
    expect(lastValidity()).toEqual([false])

    await wrapper.get('[data-testid="loop-input"]').setValue('4')
    expect(lastValidity()).toEqual([true])
  })

  it('follows an applied loop value coming back from the parent', async () => {
    const wrapper = mountBar({ loopCount: 2 })

    await wrapper.setProps({ loopCount: 6 })

    expect((wrapper.get('[data-testid="loop-input"]').element as HTMLInputElement).value).toBe('6')
    expect(wrapper.emitted('updateLoopCount')).toBeUndefined()
  })
})

// @vitest-environment jsdom
import { mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import WebAutomationScopePane from './WebAutomationScopePane.vue'

const projects = [
  { value: 'cloud', label: '云平台', count: 12 },
  { value: 'console', label: '控制台', count: 7 }
]
const modules = [
  { value: 'home', label: '首页', count: 4 },
  { value: 'projects', label: '项目管理', count: 8 }
]

const wrappers: VueWrapper[] = []
const mountScope = (props = {}) => {
  const wrapper = mount(WebAutomationScopePane, {
    attachTo: document.body,
    props: { project: 'cloud', projects, module: 'home', modules, ...props },
    global: { plugins: [ElementPlus] }
  })
  wrappers.push(wrapper)
  return wrapper
}

afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('WebAutomationScopePane', () => {
  it('renders the controlled scope values, labels, and exact counts', async () => {
    const wrapper = mountScope()
    await wrapper.vm.$nextTick()
    await new Promise((resolve) => setTimeout(resolve, 0))
    await wrapper.vm.$nextTick()

    expect(wrapper.get('section').text()).toContain('执行范围')
    expect(wrapper.findAll('[aria-label="Web 自动化项目"]')).toHaveLength(1)
    expect(wrapper.text()).toContain('云平台')
    expect(wrapper.text()).toContain('首页')
    expect(wrapper.text()).toContain('项目管理')
    expect(wrapper.get('[data-testid="web-scope-module-home"]').attributes('aria-current')).toBe('true')
    expect(wrapper.get('[data-testid="web-scope-module-projects"]').attributes('aria-current')).toBeUndefined()
  })

  it('emits project and module updates through rendered controls without mutating props', async () => {
    const wrapper = mountScope()
    const originalProjects = structuredClone(projects)
    const originalModules = structuredClone(modules)

    await wrapper.get('[aria-label="Web 自动化项目"]').trigger('click')
    await wrapper.vm.$nextTick()
    const option = Array.from(document.body.querySelectorAll('.el-select-dropdown__item'))
      .find((item) => item.textContent?.includes('控制台')) as HTMLElement
    expect(option).toBeTruthy()
    option.click()
    await wrapper.vm.$nextTick()
    await wrapper.get('[data-testid="web-scope-module-projects"]').trigger('click')

    expect(wrapper.emitted('updateProject')?.[0]).toEqual(['console'])
    expect(wrapper.emitted('updateModule')?.[0]).toEqual(['projects'])
    expect(wrapper.props('project')).toBe('cloud')
    expect(wrapper.props('module')).toBe('home')
    expect(projects).toEqual(originalProjects)
    expect(modules).toEqual(originalModules)
  })

  it('enables a module tooltip from measured overflow and remeasures on resize', async () => {
    const wrapper = mountScope()
    await wrapper.vm.$nextTick()
    const label = wrapper.get('[data-testid="web-scope-module-projects"] span').element
    vi.spyOn(label, 'clientWidth', 'get').mockReturnValue(60)
    vi.spyOn(label, 'scrollWidth', 'get').mockReturnValue(120)

    window.dispatchEvent(new Event('resize'))
    await wrapper.vm.$nextTick()

    const tooltip = wrapper.findAllComponents({ name: 'ElTooltip' })
      .find((candidate) => candidate.props('content') === '项目管理')
    expect(tooltip?.props('disabled')).toBe(false)
    expect(wrapper.get('.web-automation-scope-pane__modules').attributes('role')).toBe('group')
    expect(wrapper.get('.web-automation-scope-pane__modules').attributes('aria-label')).toBe('Web 自动化模块选择')
  })

  it('remeasures replaced modules and container resize, then disconnects cleanly', async () => {
    const observers: Array<{ callback: ResizeObserverCallback; observed: Element[]; disconnects: number }> = []
    const observe = vi.fn()
    const disconnect = vi.fn()
    class ResizeObserverStub {
      private entry: { callback: ResizeObserverCallback; observed: Element[]; disconnects: number }
      constructor(callback: ResizeObserverCallback) {
        this.entry = { callback, observed: [], disconnects: 0 }
        observers.push(this.entry)
      }
      observe = (element: Element) => { this.entry.observed.push(element); observe(element) }
      disconnect = () => { this.entry.disconnects += 1; disconnect() }
      unobserve = vi.fn()
    }
    vi.stubGlobal('ResizeObserver', ResizeObserverStub)
    let overflow = true
    let widthReads = 0
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(function (this: HTMLElement) {
      if (this.textContent === '动态新增模块') { widthReads += 1; return 60 }
      return 100
    })
    vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockImplementation(function (this: HTMLElement) {
      return this.textContent === '动态新增模块' ? (overflow ? 120 : 40) : 80
    })
    const wrapper = mountScope()
    await wrapper.setProps({ modules: [...modules, { value: 'dynamic', label: '动态新增模块', count: 3 }] })
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()

    const tooltip = () => wrapper.findAllComponents({ name: 'ElTooltip' })
      .find((candidate) => candidate.props('content') === '动态新增模块')!
    expect(tooltip().props('disabled')).toBe(false)
    expect(observe).toHaveBeenCalledWith(wrapper.get('.web-automation-scope-pane__modules > div').element)

    overflow = false
    const moduleList = wrapper.get('.web-automation-scope-pane__modules > div').element
    const moduleObserver = observers.find(({ observed }) => observed.includes(moduleList))!
    const observedBeforeCallback = moduleObserver.observed.length
    const disconnectsBeforeCallback = moduleObserver.disconnects
    moduleObserver.callback([], {} as ResizeObserver)
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    expect(tooltip().props('disabled')).toBe(true)
    expect(moduleObserver.observed).toHaveLength(observedBeforeCallback)
    expect(moduleObserver.disconnects).toBe(disconnectsBeforeCallback)

    overflow = true
    const readsBeforeCountUpdate = widthReads
    await wrapper.setProps({ modules: [...modules, { value: 'dynamic', label: '动态新增模块', count: 30 }] })
    await wrapper.vm.$nextTick()
    await wrapper.vm.$nextTick()
    expect(widthReads).toBeGreaterThan(readsBeforeCountUpdate)
    expect(tooltip().props('disabled')).toBe(false)

    const disconnectsBeforeUnmount = moduleObserver.disconnects
    wrapper.unmount()
    const readsAfterUnmount = widthReads
    window.dispatchEvent(new Event('resize'))
    await Promise.resolve()
    expect(moduleObserver.disconnects).toBe(disconnectsBeforeUnmount + 1)
    expect(widthReads).toBe(readsAfterUnmount)
  })
})

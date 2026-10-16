// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it } from 'vitest'
import WebAutomationTaskHeader from './WebAutomationTaskHeader.vue'

const mountedWrappers: Array<{ unmount: () => void }> = []

afterEach(() => {
  mountedWrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})

const mountHeader = () => {
  const wrapper = mount(WebAutomationTaskHeader, {
    props: { executableCount: 12, moduleCount: 3, latestRunLabel: '最近执行：今天 10:24' },
    global: { plugins: [ElementPlus] },
    attachTo: document.body
  })
  mountedWrappers.push(wrapper)
  return wrapper
}

describe('WebAutomationTaskHeader', () => {
  it('renders a compact literal title and inline metadata without legacy navigation', () => {
    const wrapper = mountHeader()

    expect(wrapper.get('h1').text()).toBe('Web 自动化')
    expect(wrapper.text()).toContain('最近执行：今天 10:24')
    expect(wrapper.text()).not.toContain('API 自动化')
    expect(wrapper.find('.eyebrow').exists()).toBe(false)
    expect(wrapper.find('.metric-card').exists()).toBe(false)
    wrapper.unmount()
  })

  it('emits each command through user interaction without changing its props', async () => {
    const wrapper = mountHeader()
    const originalProps = { ...wrapper.props() }

    await wrapper.get('[data-testid="open-web-history"]').trigger('click')
    await wrapper.get('[data-testid="generate-web-cases"]').trigger('click')
    await wrapper.get('[data-testid="export-web-cases"]').trigger('click')
    await wrapper.vm.$nextTick()

    expect(wrapper.emitted('openHistory')).toHaveLength(1)
    expect(wrapper.emitted('generateCases')).toHaveLength(1)
    expect(wrapper.emitted('exportCases')).toHaveLength(1)
    expect(wrapper.props()).toMatchObject(originalProps)
    expect(wrapper.get('[data-testid="export-web-cases"]').text()).toContain('导出用例')
    wrapper.unmount()
  })
})

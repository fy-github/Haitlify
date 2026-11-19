// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import DataPagination from './DataPagination.vue'

const mountPager = (props: Record<string, unknown> = {}) =>
  mount(DataPagination, {
    props: { page: 1, pageSize: 50, total: 120, ...props }
  })

describe('DataPagination', () => {
  it('shows the total count and disables out-of-range directions', async () => {
    const wrapper = mountPager()
    expect(wrapper.get('.dp-count').text()).toBe('共 120 条')

    const buttons = wrapper.findAll('.dp-btn')
    expect(buttons[0].text()).toBe('上一页')
    expect(buttons[0].attributes('disabled')).toBeDefined()
    expect(buttons[buttons.length - 1].text()).toBe('下一页')
    expect(buttons[buttons.length - 1].attributes('disabled')).toBeUndefined()

    await buttons[0].trigger('click')
    expect(wrapper.emitted('update:page')).toBeUndefined()
  })

  it('emits the target page and marks the current one', async () => {
    const wrapper = mountPager({ page: 2 })
    const pageButtons = wrapper.findAll('.dp-btn').filter((button) => /^\d+$/.test(button.text()))
    expect(pageButtons.map((button) => button.text())).toEqual(['1', '2', '3'])

    const current = wrapper.get('.dp-btn.is-current')
    expect(current.text()).toBe('2')
    expect(current.attributes('aria-current')).toBe('page')

    await pageButtons[2].trigger('click')
    expect(wrapper.emitted('update:page')?.[0]).toEqual([3])
  })

  it('emits both page-size and page reset when the size changes', async () => {
    const wrapper = mountPager({ page: 3 })
    await wrapper.get('select').setValue('100')
    expect(wrapper.emitted('update:pageSize')?.[0]).toEqual([100])
    expect(wrapper.emitted('update:page')?.[0]).toEqual([1])
  })

  it('collapses long page ranges with an ellipsis', () => {
    const wrapper = mountPager({ page: 10, pageSize: 10, total: 300 })
    const labels = wrapper.findAll('.dp-btn').map((button) => button.text())
    expect(labels).toContain('1')
    expect(labels).toContain('30')
    expect(wrapper.findAll('.dp-ellipsis').length).toBe(2)
    expect(wrapper.get('.dp-hint').text()).toBe('第 10 / 30 页')
  })

  it('keeps a single page when the total is empty', () => {
    const wrapper = mountPager({ total: 0 })
    const pageButtons = wrapper.findAll('.dp-btn').filter((button) => /^\d+$/.test(button.text()))
    expect(pageButtons.map((button) => button.text())).toEqual(['1'])
    expect(wrapper.findAll('.dp-btn')[0].attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('.dp-btn')[wrapper.findAll('.dp-btn').length - 1].attributes('disabled')).toBeDefined()
  })
})

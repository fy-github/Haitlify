// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import DataPagination from './DataPagination.vue'

describe('DataPagination control closeout', () => {
  it('keeps every page number in one named wrapping-safe page group', async () => {
    const wrapper = mount(DataPagination, { props: { page: 4, pageSize: 50, total: 350 } })
    const pages = wrapper.get('[role="group"][aria-label="页码"]')
    expect(pages.findAll('button').map((button) => button.text())).toEqual(['1', '2', '3', '4', '5', '6', '7'])
    expect(pages.get('[aria-current="page"]').text()).toBe('4')
    await pages.findAll('button')[6].trigger('click')
    expect(wrapper.emitted('update:page')).toEqual([[7]])
    expect(wrapper.get('nav').attributes('aria-label')).toBe('分页')
    expect(wrapper.get('select').attributes('aria-label')).toBe('每页条数')
  })
})

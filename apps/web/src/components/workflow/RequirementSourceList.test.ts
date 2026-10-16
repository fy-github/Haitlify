// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import RequirementSourceList from './RequirementSourceList.vue'

describe('RequirementSourceList', () => {
  it('keeps retry, replace, and remove actions available in either presentation', async () => {
    const wrapper = mount(RequirementSourceList, { props: { sources: [{ name: 'broken.pdf', kind: 'document', kindLabel: '文档', status: 'error', detail: '读取失败' }] } })
    await wrapper.get('[data-testid="retry-source-broken.pdf"]').trigger('click')
    await wrapper.get('[data-testid="replace-source-broken.pdf"]').trigger('click')
    await wrapper.get('[data-testid="remove-source-broken.pdf"]').trigger('click')
    expect(wrapper.emitted('retry')).toEqual([['broken.pdf']])
    expect(wrapper.emitted('replace')).toEqual([['broken.pdf']])
    expect(wrapper.emitted('remove')).toEqual([['broken.pdf']])
  })
})

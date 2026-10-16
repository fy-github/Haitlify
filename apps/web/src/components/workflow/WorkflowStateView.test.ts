// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import WorkflowStateView from './WorkflowStateView.vue'

describe('WorkflowStateView', () => {
  it('uses alert semantics for errors and exposes a local retry', async () => {
    const wrapper = mount(WorkflowStateView, { props: { variant: 'error', title: '需求解析失败', description: '文档读取超时', actionLabel: '重试此项' } })
    expect(wrapper.attributes('role')).toBe('alert')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('action')).toHaveLength(1)
  })

  it('distinguishes first-use and filtered empty states', () => {
    const firstUse = mount(WorkflowStateView, { props: { variant: 'empty', title: '还没有需求来源', description: '添加文档、Figma 或 OpenAPI', actionLabel: '添加需求来源' } })
    const filtered = mount(WorkflowStateView, { props: { variant: 'filtered-empty', title: '没有符合条件的用例', description: '当前筛选条件没有匹配结果', actionLabel: '清除筛选' } })
    expect(firstUse.text()).toContain('添加需求来源')
    expect(filtered.text()).toContain('清除筛选')
  })

  it('announces offline local-first operation without presenting it as a blocking error', () => {
    const wrapper = mount(WorkflowStateView, { props: { variant: 'offline', title: '当前离线', description: '草稿仍会保存在本机，联网后可继续同步' } })
    expect(wrapper.attributes('role')).toBe('status')
    expect(wrapper.text()).toContain('草稿仍会保存在本机')
  })
})

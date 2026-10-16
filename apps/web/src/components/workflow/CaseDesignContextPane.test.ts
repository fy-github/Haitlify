// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import CaseDesignContextPane from './CaseDesignContextPane.vue'

describe('CaseDesignContextPane', () => {
  it('renders source status and emits module selection', async () => {
    const wrapper = mount(CaseDesignContextPane, {
      props: {
        sources: [{ name: '退款需求.md', kind: 'document', kindLabel: '文档', status: 'ready', detail: '已就绪' }],
        modules: [{ value: '退款', count: 8 }, { value: '订单', count: 4 }],
        activeModule: '退款', activeSource: '退款需求.md'
      }
    })
    expect(wrapper.text()).toContain('退款需求.md')
    await wrapper.get('button[data-source="退款需求.md"]').trigger('click')
    await wrapper.get('button[data-module="订单"]').trigger('click')
    expect(wrapper.emitted('selectSource')).toEqual([['退款需求.md']])
    expect(wrapper.emitted('selectModule')).toEqual([['订单']])
  })
})

// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import InterfaceCaseContextPane from './InterfaceCaseContextPane.vue'

const moduleOptions = [
  { value: 'ALL', label: '全部模块' },
  { value: '订单', label: '订单（1）' },
  { value: '用户', label: '用户（1）' }
]

const mountPane = (overrides: Partial<InstanceType<typeof InterfaceCaseContextPane>['$props']> = {}) =>
  mount(InterfaceCaseContextPane, {
    props: {
      module: 'ALL',
      moduleOptions,
      pathKeyword: '',
      summaryKeyword: '',
      method: 'ALL',
      templateId: 'standard',
      templateOptions: [{ value: 'standard', label: '标准模板' }, { value: 'smoke', label: '冒烟模板' }],
      granularity: 'basic',
      ...overrides
    },
    global: {
      stubs: {
        ElSegmented: {
          props: ['modelValue', 'options'],
          emits: ['update:modelValue'],
          template: `
            <div role="radiogroup" v-bind="$attrs">
              <button
                v-for="option in options"
                :key="option.value"
                type="button"
                :aria-label="option.label"
                :aria-pressed="modelValue === option.value"
                @click="$emit('update:modelValue', option.value)"
              >
                {{ option.label }}
              </button>
            </div>
          `
        }
      }
    }
  })

describe('InterfaceCaseContextPane', () => {
  it('renders context controls and emits filter and generation updates', async () => {
    const wrapper = mountPane()

    expect(wrapper.get('[aria-label="接口与生成配置"]').element.tagName).toBe('ASIDE')
    expect(wrapper.text()).toContain('筛选')
    expect(wrapper.text()).toContain('生成配置')
    expect(wrapper.text()).not.toContain('接口列表')
    expect(wrapper.text()).not.toContain('OpenAPI 来源')
    expect(wrapper.find('[data-testid="interface-import-button"]').exists()).toBe(false)
    expect(wrapper.find('[data-source-id]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="select-filtered-operations"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="clear-selected-operations"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="接口列表"]').exists()).toBe(false)
    expect(wrapper.get('select[aria-label="模块"]').text()).toContain('全部模块')
    expect(wrapper.get('select[aria-label="模块"]').text()).toContain('订单（1）')
    expect(wrapper.get('select[aria-label="模块"]').text()).toContain('用户（1）')
    expect(wrapper.get('[aria-label="生成粒度"]').classes()).toContain('studio-interface-context-pane__granularity')

    await wrapper.get('select[aria-label="模块"]').setValue('订单')
    await wrapper.get('input[aria-label="路径关键词"]').setValue('/api/orders')
    await wrapper.get('input[aria-label="摘要关键词"]').setValue('查询')
    await wrapper.get('select[aria-label="请求方法"]').setValue('GET')
    await wrapper.get('select[aria-label="用例模板"]').setValue('smoke')
    await wrapper.get('button[aria-label="风险"]').trigger('click')

    expect(wrapper.emitted('updateModule')).toEqual([['订单']])
    expect(wrapper.emitted('updatePathKeyword')).toEqual([['/api/orders']])
    expect(wrapper.emitted('updateSummaryKeyword')).toEqual([['查询']])
    expect(wrapper.emitted('updateMethod')).toEqual([['GET']])
    expect(wrapper.emitted('updateTemplateId')).toEqual([['smoke']])
    expect(wrapper.emitted('updateGranularity')).toEqual([['risk']])
    expect(wrapper.emitted('toggleOperation')).toBeUndefined()
    expect(wrapper.emitted('selectFiltered')).toBeUndefined()
    expect(wrapper.emitted('clearSelected')).toBeUndefined()
  })

  it('keeps only filters and generation configuration in the context pane', () => {
    const wrapper = mountPane()

    expect(wrapper.text()).toContain('筛选')
    expect(wrapper.text()).toContain('生成配置')
    expect(wrapper.text()).not.toContain('接口列表')
    expect(wrapper.text()).not.toContain('OpenAPI 来源')
    expect(wrapper.find('[data-testid="interface-import-button"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="接口列表"]').exists()).toBe(false)
  })
})

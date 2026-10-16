// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import ScreenshotDomCompareDialog from './ScreenshotDomCompareDialog.vue'

describe('ScreenshotDomCompareDialog', () => {
  it('renders the screenshot and the DOM snippet side by side', async () => {
    const wrapper = mount(ScreenshotDomCompareDialog, {
      props: {
        visible: true,
        title: '截图与 DOM 对照 · 登录成功',
        screenshotPath: 'http://127.0.0.1:9323/artifacts/shot.png',
        htmlSnippet: '<div id="app">页面片段</div>'
      },
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="screenshot-dom-compare"]').exists()).toBe(true)
    const image = wrapper.get('[data-testid="compare-screenshot"] img')
    expect(image.attributes('src')).toBe('http://127.0.0.1:9323/artifacts/shot.png')
    expect(wrapper.get('[data-testid="compare-dom"]').text()).toContain('<div id="app">页面片段</div>')
  })

  it('shows explicit empty states when a side has no data', async () => {
    const wrapper = mount(ScreenshotDomCompareDialog, {
      props: { visible: true },
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()
    expect(wrapper.get('[data-testid="compare-screenshot"]').text()).toContain('无截图')
    expect(wrapper.get('[data-testid="compare-dom"]').text()).toContain('无 DOM 片段')
  })
})

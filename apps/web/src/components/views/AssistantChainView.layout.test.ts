// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import AssistantChainView from './AssistantChainView.vue'
import assistantSource from './AssistantChainView.vue?raw'
import admissionSource from '../assistant/GenerationAdmissionForm.vue?raw'

const user = { id: 73, username: 'qa', displayName: 'QA', role: 'EDITOR', mustChangePassword: false }

describe('Assistant page UI containment', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('gives the mounted page one primary heading without claiming generation is delivered', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ items: [] }))))
    const wrapper = mount(AssistantChainView, { props: { user } })
    try {
      await flushPromises()
      expect(wrapper.findAll('h1')).toHaveLength(1)
      expect(wrapper.get('h1').text()).toBe('助手开发链路')
      expect(wrapper.text()).toContain('当前不是完整 AI 生成入口')
      expect(wrapper.text()).toContain('当前仅受理，不会触发模型，也不会生成草稿')
      expect(wrapper.text()).toContain('手工 JSON 调试')
    } finally { wrapper.unmount() }
  })

  it('uses the platform semantic tokens instead of undefined desktop-widget variables', () => {
    for (const source of [assistantSource, admissionSource]) {
      expect(source).not.toMatch(/var\(--(?:foreground|muted-foreground|border|card)\)/)
      expect(source).toContain('var(--border-default)')
      expect(source).toContain('var(--surface-raised)')
      expect(source).toContain('var(--text-primary)')
    }
  })

  it('bounds the grid and form controls and stacks the debug columns on narrow screens', () => {
    expect(assistantSource).toMatch(/\.chain\s*\{[^}]*min-width:\s*0[^}]*overflow-y:\s*auto/s)
    expect(assistantSource).toMatch(/\.chain__grid\s*\{[^}]*grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)/s)
    expect(assistantSource).toMatch(/@media\s*\(max-width:\s*768px\)\s*\{[^]*?\.chain__grid\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\)/)
    for (const source of [assistantSource, admissionSource]) {
      expect(source).toMatch(/input,\s*textarea[^}]*\{[^}]*min-width:\s*0[^}]*max-width:\s*100%/s)
    }
  })

  it('occupies the whole workspace grid instead of its legacy 58px header row', () => {
    expect(assistantSource).toMatch(/\.chain\s*\{[^}]*grid-row:\s*1\s*\/\s*-1[^}]*height:\s*100%/s)
  })
})

// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import { nextTick } from 'vue'
import RequirementImportStage from './RequirementImportStage.vue'

const mountStage = (props: InstanceType<typeof RequirementImportStage>['$props']) => mount(RequirementImportStage, { props, global: { plugins: [ElementPlus] } })

describe('RequirementImportStage', () => {
  it('renders Web requirement commands without an OpenAPI import', () => {
    const wrapper = mountStage({ sources: [{ name: '退款需求.docx', kind: 'document', kindLabel: '文档', status: 'ready', detail: '已就绪' }, { name: 'broken.pdf', kind: 'document', kindLabel: '文档', status: 'error', detail: '文档解析失败' }], analysisStatus: 'idle', progressPercentage: 0, progressText: '等待解析', errorMessage: '' })
    expect(wrapper.text()).toContain('上传需求文档'); expect(wrapper.text()).toContain('连接 Figma'); expect(wrapper.text()).not.toContain('导入 OpenAPI'); expect(wrapper.text()).toContain('broken.pdf'); expect(wrapper.text()).toContain('文档解析失败')
  })
  it('uses the approved requirement-source copy for the first-use state', () => {
    const wrapper = mountStage({ sources: [], analysisStatus: 'idle', progressPercentage: 0, progressText: '等待解析', errorMessage: '' })
    expect(wrapper.text()).toContain('添加需求文档、Figma 后开始解析')
    expect(wrapper.text()).not.toContain('Figma 或 OpenAPI')
    expect(wrapper.get('.studio-workflow-state__action').text()).toBe('添加需求来源')
  })
  it('emits local retry and remove commands', async () => {
    const wrapper = mountStage({ sources: [{ name: 'broken.pdf', kind: 'document', kindLabel: '文档', status: 'error', detail: '文档解析失败' }], analysisStatus: 'failed', progressPercentage: 0, progressText: '解析失败', errorMessage: '请求超时' })
    await wrapper.get('[data-testid="retry-source-broken.pdf"]').trigger('click'); await wrapper.get('[data-testid="replace-source-broken.pdf"]').trigger('click'); await wrapper.get('[data-testid="remove-source-broken.pdf"]').trigger('click')
    expect(wrapper.emitted('retrySource')).toEqual([['broken.pdf']]); expect(wrapper.emitted('replaceSource')).toEqual([['broken.pdf']]); expect(wrapper.emitted('removeSource')).toEqual([['broken.pdf']])
  })
  it('shows parsed content as parsing progresses', () => {
    const wrapper = mountStage({ sources: [{ name: '退款需求.md', kind: 'document', kindLabel: '文档', status: 'ready', detail: '已就绪' }], analysisStatus: 'running', progressPercentage: 45, progressText: '正在解析', errorMessage: '', completedSourceCount: 1, totalSourceCount: 2, parsedItems: [{ id: 'section-1', kindLabel: '章节', label: '退款状态回补' }, { id: 'figma-1', kindLabel: 'Figma', label: '退款详情页' }, { id: 'api-1', kindLabel: 'API', label: 'POST /refunds' }] })
    expect(wrapper.text()).toContain('退款状态回补'); expect(wrapper.text()).toContain('退款详情页'); expect(wrapper.text()).toContain('POST /refunds'); expect(wrapper.text()).toContain('1 / 2')
    expect(wrapper.get('[data-testid="parsed-items-list"]').classes()).toContain('studio-parsed-items__list')
  })
  it('locks requirement and Figma imports while parsing is running', async () => {
    const wrapper = mountStage({ sources: [{ name: '退款需求.md', kind: 'document', kindLabel: '文档', status: 'ready', detail: '已就绪' }], analysisStatus: 'running', progressPercentage: 45, progressText: '正在解析', errorMessage: '' })
    const upload = wrapper.get('[data-testid="requirement-source-upload"]')
    const figma = wrapper.get('[data-testid="requirement-source-figma"]')
    expect(upload.attributes('disabled')).toBeDefined()
    expect(figma.attributes('disabled')).toBeDefined()
    await upload.trigger('click')
    await figma.trigger('click')
    expect(wrapper.emitted('pickFiles')).toBeUndefined()
    expect(wrapper.emitted('importFigma')).toBeUndefined()
  })
  it('allows the active analysis to be cancelled without unlocking source imports', async () => {
    const wrapper = mountStage({ sources: [{ name: '退款需求.md', kind: 'document', kindLabel: '文档', status: 'ready', detail: '已就绪' }], analysisStatus: 'running', progressPercentage: 45, progressText: '正在等待模型响应', errorMessage: '' })
    await wrapper.get('[data-testid="cancel-ai-analysis"]').trigger('click')
    expect(wrapper.emitted('cancelAnalysis')).toEqual([[]])
    expect(wrapper.get('[data-testid="requirement-source-upload"]').attributes('disabled')).toBeDefined()
  })
  it('renders the streaming analysis log when entries are provided', () => {
    const wrapper = mountStage({ sources: [{ name: '退款需求.md', kind: 'document', kindLabel: '文档', status: 'ready', detail: '已就绪' }], analysisStatus: 'running', progressPercentage: 40, progressText: '正在分析', errorMessage: '', analysisLog: [{ id: 'ai-analysis-log-0', kind: 'info', text: '已将需求拆分为 3 个分析块' }, { id: 'ai-analysis-log-1', kind: 'call', text: '调用 gpt-4.1 · 1200ms · 800/120 tokens' }] })
    expect(wrapper.get('[data-testid="analysis-stream-log"]').text()).toContain('已将需求拆分为 3 个分析块')
    expect(wrapper.get('[data-testid="analysis-stream-log"]').text()).toContain('调用 gpt-4.1')
    expect(wrapper.get('[role="log"]').attributes('aria-live')).toBe('polite')
    expect(wrapper.get('.studio-analysis-log__toggle').text()).toContain('实时刷新中')
    expect(wrapper.find('[data-testid="analysis-stream-log"] svg').exists()).toBe(true)
  })
  it('keeps the streaming analysis log visible while analysis is running before entries arrive', () => {
    const wrapper = mountStage({ sources: [{ name: '退款需求.md', kind: 'document', kindLabel: '文档', status: 'ready', detail: '已就绪' }], analysisStatus: 'running', progressPercentage: 16, progressText: '正在创建 AI 分析任务', errorMessage: '', analysisLog: [] })
    const log = wrapper.get('[data-testid="analysis-stream-log"]')
    expect(log.text()).toContain('分析进度日志')
    expect(log.text()).toContain('实时刷新中')
    expect(log.text()).toContain('等待 AI 返回处理事件')
  })
  it('keeps the streaming analysis log scrolled to the latest entry as new lines arrive', async () => {
    const wrapper = mountStage({ sources: [{ name: '退款需求.md', kind: 'document', kindLabel: '文档', status: 'ready', detail: '已就绪' }], analysisStatus: 'running', progressPercentage: 40, progressText: '正在分析', errorMessage: '', analysisLog: [{ id: 'ai-analysis-log-0', kind: 'info', text: '准备分析' }] })
    const list = wrapper.get('.studio-analysis-log__list').element as HTMLElement
    Object.defineProperty(list, 'scrollHeight', { configurable: true, value: 480 })
    await wrapper.setProps({
      analysisLog: [
        { id: 'ai-analysis-log-0', kind: 'info', text: '准备分析' },
        { id: 'ai-analysis-log-1', kind: 'chunk', text: '正在分析第 1/3 块…' },
        { id: 'ai-analysis-log-2', kind: 'draft', text: '新增草稿测试点：退款成功' }
      ]
    })
    await nextTick()
    expect(list.scrollTop).toBe(480)
  })
  it('scrolls the streaming analysis log to the latest entry when reopened', async () => {
    const wrapper = mountStage({ sources: [{ name: '退款需求.md', kind: 'document', kindLabel: '文档', status: 'ready', detail: '已就绪' }], analysisStatus: 'running', progressPercentage: 40, progressText: '正在分析', errorMessage: '', analysisLog: [{ id: 'ai-analysis-log-0', kind: 'info', text: '准备分析' }, { id: 'ai-analysis-log-1', kind: 'draft', text: '新增草稿测试点：退款成功' }] })
    const list = wrapper.get('.studio-analysis-log__list').element as HTMLElement
    Object.defineProperty(list, 'scrollHeight', { configurable: true, value: 720 })
    await wrapper.get('.studio-analysis-log__toggle').trigger('click')
    list.scrollTop = 0
    await wrapper.get('.studio-analysis-log__toggle').trigger('click')
    await nextTick()
    expect(list.scrollTop).toBe(720)
  })
  it('omits the streaming analysis log when the analysis flow is idle and there are no entries', () => {
    const wrapper = mountStage({ sources: [], analysisStatus: 'idle', progressPercentage: 0, progressText: '等待上传', errorMessage: '' })
    expect(wrapper.find('[data-testid="analysis-stream-log"]').exists()).toBe(false)
  })
})

// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { computed, defineComponent, h, inject, provide, type ComputedRef } from 'vue'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi } from 'vitest'
import AiAssistantReviewPanel from './AiAssistantReviewPanel.vue'

const tableStubs = {
  ElTable: defineComponent({
    props: { data: { type: Array, default: () => [] } },
    setup(props, { slots }) {
      provide('registry-rows', computed(() => props.data as Array<Record<string, unknown>>))
      return () => h('table', slots.default?.() || [])
    }
  }),
  ElTableColumn: defineComponent({
    props: { prop: { type: String, default: '' }, label: { type: String, default: '' } },
    setup(props, { slots }) {
      const rows = inject<ComputedRef<Array<Record<string, unknown>>>>('registry-rows', computed(() => []))
      return () => h('div', rows.value.flatMap((row) =>
        slots.default ? (slots.default({ row }) as never[]) : [String(row[props.prop] ?? '')]
      ))
    }
  })
}

const artifact = (overrides: Record<string, unknown> = {}) => ({
  artifactId: 'artifact-1',
  runId: 'assistant-1',
  taskType: 'bug_analysis',
  projectId: 1,
  revision: 1,
  acceptanceState: 'PENDING_HUMAN_CONFIRMATION',
  status: 'waiting_review',
  reviewedBy: '',
  createdAt: '2026-10-12T08:42:44Z',
  ...overrides
})

const run = (overrides: Record<string, unknown> = {}) => ({
  runId: 'assistant-1',
  taskType: 'bug_analysis',
  projectId: 1,
  status: 'waiting_review',
  output: { summary: '导出内容' },
  violations: ['bug_analysis 必须给出至少一条假设'],
  artifactId: 'artifact-1',
  revision: 1,
  acceptanceState: 'PENDING_HUMAN_CONFIRMATION',
  ...overrides
})

const makeRepository = () => ({
  fetchAssistantArtifacts: vi.fn(async () => ({ artifacts: [artifact()], limit: 20 })),
  fetchAssistantRun: vi.fn(async () => run()),
  reviewAssistantArtifact: vi.fn(async () => run({
    acceptanceState: 'ACCEPTED', status: 'accepted'
  })),
  fetchAssistantArtifactExport: vi.fn(async () => ({
    artifactId: 'artifact-1', runId: 'assistant-1', taskType: 'bug_analysis', projectId: 1,
    revision: 1, acceptanceState: 'ACCEPTED', reviewedBy: 'qa-user',
    exportedAt: '2026-10-12T09:05:22Z', output: { summary: '导出内容' }, violations: []
  }))
})

const mountPanel = (props: Record<string, unknown> = {}) =>
  mount(AiAssistantReviewPanel, {
    props: {
      repository: makeRepository() as never,
      resolveProject: (async () => ({ id: 1 })) as never,
      ...props
    },
    global: { plugins: [ElementPlus], stubs: tableStubs }
  })

describe('AiAssistantReviewPanel', () => {
  it('lists artifacts for the resolved project on mount', async () => {
    const wrapper = mountPanel()
    await vi.waitFor(() => expect(wrapper.text()).toContain('bug_analysis'))
    const repository = (wrapper.props('repository') as unknown) as ReturnType<typeof makeRepository>
    expect(repository.fetchAssistantArtifacts).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('待评审')
  })

  it('loads the detail and keeps export disabled until the artifact is accepted', async () => {
    const wrapper = mountPanel()
    const repository = (wrapper.props('repository') as unknown) as ReturnType<typeof makeRepository>
    await vi.waitFor(() => expect(repository.fetchAssistantArtifacts).toHaveBeenCalled())
    await (wrapper.vm as unknown as { selectArtifact: (id: string) => Promise<void> }).selectArtifact('artifact-1')

    await vi.waitFor(() => expect(wrapper.find('[data-testid="review-detail"]').exists()).toBe(true))
    expect(wrapper.get('[data-testid="review-output"]').text()).toContain('导出内容')
    expect(wrapper.get('[data-testid="review-violations"]').text()).toContain('假设')
    expect(wrapper.get('[data-testid="review-export"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="review-approve"]').attributes('disabled')).toBeUndefined()
  })

  it('approves with the pinned revision and unlocks export afterwards', async () => {
    const wrapper = mountPanel()
    const repository = (wrapper.props('repository') as unknown) as ReturnType<typeof makeRepository>
    await vi.waitFor(() => expect(repository.fetchAssistantArtifacts).toHaveBeenCalled())
    await (wrapper.vm as unknown as { selectArtifact: (id: string) => Promise<void> }).selectArtifact('artifact-1')
    await vi.waitFor(() => expect(wrapper.find('[data-testid="review-detail"]').exists()).toBe(true))

    await wrapper.get('[data-testid="review-approve"]').trigger('click')
    await vi.waitFor(() => expect(repository.reviewAssistantArtifact).toHaveBeenCalled())
    const call = repository.reviewAssistantArtifact.mock.calls[0] as unknown[]
    expect(call[1]).toBe('artifact-1')
    expect((call[2] as Record<string, unknown>).decision).toBe('approve')
    expect((call[2] as Record<string, unknown>).revision).toBe(1)

    await vi.waitFor(() =>
      expect(wrapper.get('[data-testid="review-export"]').attributes('disabled')).toBeUndefined())
    await wrapper.get('[data-testid="review-export"]').trigger('click')
    await vi.waitFor(() => expect(repository.fetchAssistantArtifactExport).toHaveBeenCalled())
    const exportCall = repository.fetchAssistantArtifactExport.mock.calls[0] as unknown[]
    expect(exportCall[1]).toBe('artifact-1')
    expect(exportCall[2]).toBe(1)
    await vi.waitFor(() => expect(wrapper.find('[data-testid="review-download"]').exists()).toBe(true))
  })

  it('submits a JSON revision through change and follows the new artifact id', async () => {
    const repository = makeRepository()
    repository.reviewAssistantArtifact.mockResolvedValueOnce({
      runId: 'assistant-1', taskType: 'bug_analysis', projectId: 1, status: 'waiting_review',
      output: { summary: '修订后的输出' }, violations: [], artifactId: 'artifact-1-r2', revision: 2,
      acceptanceState: 'PENDING_HUMAN_CONFIRMATION'
    } as never)
    const wrapper = mountPanel({ repository: repository as never })
    await vi.waitFor(() => expect(repository.fetchAssistantArtifacts).toHaveBeenCalled())
    await (wrapper.vm as unknown as { selectArtifact: (id: string) => Promise<void> }).selectArtifact('artifact-1')
    await vi.waitFor(() => expect(wrapper.find('[data-testid="review-change-toggle"]').exists()).toBe(true))

    await wrapper.get('[data-testid="review-change-toggle"]').trigger('click')
    const textarea = wrapper.get('[data-testid="review-change-textarea"]')
    expect((textarea.element as HTMLTextAreaElement).value).toContain('导出内容')
    await textarea.setValue('{"summary": "修订后的输出"}')
    await wrapper.get('[data-testid="review-change-submit"]').trigger('click')

    await vi.waitFor(() => expect(repository.reviewAssistantArtifact).toHaveBeenCalled())
    const call = repository.reviewAssistantArtifact.mock.calls[0] as unknown[]
    expect(call[1]).toBe('artifact-1')
    expect((call[2] as Record<string, unknown>).decision).toBe('change')
    expect((call[2] as Record<string, unknown>).candidate).toEqual({ summary: '修订后的输出' })
    // 详情跟随新产物 id 与 v2；编辑器在评审+列表刷新完成后闭合（异步收尾，waitFor 而非即时断言）
    await vi.waitFor(() => expect(wrapper.get('[data-testid="review-detail"]').text()).toContain('v2'))
    await vi.waitFor(() => expect(wrapper.find('[data-testid="review-change-editor"]').exists()).toBe(false))
  })

  it('refuses an invalid JSON revision without calling the backend', async () => {
    const repository = makeRepository()
    const wrapper = mountPanel({ repository: repository as never })
    await vi.waitFor(() => expect(repository.fetchAssistantArtifacts).toHaveBeenCalled())
    await (wrapper.vm as unknown as { selectArtifact: (id: string) => Promise<void> }).selectArtifact('artifact-1')
    await vi.waitFor(() => expect(wrapper.find('[data-testid="review-change-toggle"]').exists()).toBe(true))

    await wrapper.get('[data-testid="review-change-toggle"]').trigger('click')
    await wrapper.get('[data-testid="review-change-textarea"]').setValue('{ not json')
    await wrapper.get('[data-testid="review-change-submit"]').trigger('click')

    await vi.waitFor(() => expect(wrapper.find('[data-testid="review-change-error"]').exists()).toBe(true))
    expect(wrapper.get('[data-testid="review-change-error"]').text()).toContain('合法 JSON')
    expect(repository.reviewAssistantArtifact).not.toHaveBeenCalled()
  })

  it('surfaces a stale-revision 409 as a conflict prompt instead of a generic error', async () => {
    const repository = makeRepository()
    const staleError = new Error(
      'AI assistant request failed: 409 artifact revision is stale: expected 1 but current is 2')
    ;(staleError as { status?: number }).status = 409
    repository.reviewAssistantArtifact.mockRejectedValueOnce(staleError as never)
    const wrapper = mountPanel({ repository: repository as never })
    await vi.waitFor(() => expect(repository.fetchAssistantArtifacts).toHaveBeenCalled())
    await (wrapper.vm as unknown as { selectArtifact: (id: string) => Promise<void> }).selectArtifact('artifact-1')
    await vi.waitFor(() => expect(wrapper.find('[data-testid="review-detail"]').exists()).toBe(true))

    await wrapper.get('[data-testid="review-approve"]').trigger('click')
    await vi.waitFor(() => expect(wrapper.find('[data-testid="review-conflict"]').exists()).toBe(true))
    expect(wrapper.get('[data-testid="review-conflict"]').text()).toContain('版本冲突')
    expect(wrapper.find('[data-testid="review-error"]').exists()).toBe(false)
  })
})

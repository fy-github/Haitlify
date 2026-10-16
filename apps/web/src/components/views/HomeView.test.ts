// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import HomeView from './HomeView.vue'
import type { AiTaskDefinition } from '../../domain/aiAssistantRepository'

const bugTask: AiTaskDefinition = {
  taskType: 'bug_analysis',
  displayName: '缺陷分析',
  requiredInputs: ['symptom'],
  evidenceTypes: [],
  toolAllowlist: [],
  humanGateRequired: true,
  artifactTarget: ''
}
const logTask: AiTaskDefinition = {
  ...bugTask,
  taskType: 'log_triage',
  displayName: '日志排查',
  requiredInputs: ['logExcerpt', 'timeRange']
}

const requirementTask: AiTaskDefinition = {
  ...bugTask,
  taskType: 'requirement_analysis',
  displayName: '需求分析',
  requiredInputs: ['documentIds', 'targetCaseKind']
}

const casegenTask: AiTaskDefinition = {
  ...bugTask,
  taskType: 'testcase_generation',
  displayName: '用例生成',
  requiredInputs: ['draftPointIds', 'caseKind']
}

const makeRepository = () => ({
  fetchAssistantCatalog: vi.fn(async () => ({ tasks: [logTask, bugTask, requirementTask, casegenTask] })),
  fetchAssistantDraftPoints: vi.fn(async () => ({
    items: [{ pointId: 'point-alpha', title: '登录成功跳转首页', moduleName: '认证', caseKind: 'interface',
      reviewStatus: 'confirmed', updatedAt: 'x' }],
    limit: 50
  })),
  fetchRoutingCandidates: vi.fn(async () => [
    { taskType: 'bug_analysis', displayName: '缺陷分析', missingInputs: [], evidenceReady: true, complete: true },
    { taskType: 'log_triage', displayName: '日志排查', missingInputs: ['logExcerpt'], evidenceReady: true, complete: false }
  ]),
  startAssistantRun: vi.fn(async () => ({
    runId: 'assistant-77', taskType: 'bug_analysis', projectId: 1, status: 'waiting_review',
    violations: ['bug_analysis 必须给出至少一条假设（hypotheses）'],
    artifactId: 'artifact-77', revision: 1, acceptanceState: 'PENDING_HUMAN_CONFIRMATION'
  })),
  fetchAssistantArtifacts: vi.fn(async () => ({ artifacts: [], limit: 20 })),
  fetchAssistantRun: vi.fn(),
  reviewAssistantArtifact: vi.fn(),
  fetchAssistantArtifactExport: vi.fn()
})

const makeDocumentRepository = () => ({
  fetchProjectSourceDocuments: vi.fn(async () => ({
    items: [{ documentId: 'doc-alpha', fileName: '需求说明书.docx', documentType: 'word', parserVersion: 'v1', createdAt: 'x' }]
  }))
})

const mountHome = () => {
  const repository = makeRepository()
  const documentRepository = makeDocumentRepository()
  const wrapper = mount(HomeView, {
    props: {
      assistantDeps: {
        repository: repository as never,
        documentRepository: documentRepository as never,
        resolveProject: (async () => ({ id: 1 })) as never
      }
    } as never,
    global: { plugins: [ElementPlus] }
  })
  return { wrapper, repository, documentRepository }
}

describe('HomeView', () => {
  it('renders the AI assistant header without stage cards', () => {
    const { wrapper } = mountHome()
    expect(wrapper.text()).toContain('AI 测试助手')
    expect(wrapper.findAll('.home-view__module')).toHaveLength(0)
    expect(wrapper.findAll('.home-view__stage')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('用例库')
    expect(wrapper.text()).not.toContain('Web 用例生成')
  })

  it('answers a bug description with a task draft that suggests bug_analysis', async () => {
    const { wrapper, repository } = mountHome()
    await vi.waitFor(() => expect(repository.fetchAssistantCatalog).toHaveBeenCalled())
    const input = wrapper.find('input[data-testid="home-assistant-input"]')
    await input.setValue('设备详情页偶发白屏，怀疑是空指针')
    await wrapper.find('[data-testid="home-assistant-send"]').trigger('click')

    expect(wrapper.text()).toContain('设备详情页偶发白屏')
    const draft = wrapper.find('[data-testid="home-draft"]')
    expect(draft.exists()).toBe(true)
    expect(wrapper.find('[data-testid="home-draft-input-symptom"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="home-draft-run"]').exists()).toBe(true)
  })

  it('runs the draft through the real launch path and shows the honest result', async () => {
    const { wrapper, repository } = mountHome()
    await vi.waitFor(() => expect(repository.fetchAssistantCatalog).toHaveBeenCalled())
    await wrapper.find('input[data-testid="home-assistant-input"]').setValue('设备详情页偶发白屏')
    await wrapper.find('[data-testid="home-assistant-send"]').trigger('click')

    await wrapper.find('[data-testid="home-draft-input-symptom"]').setValue('设备详情页偶发白屏')
    const listCallsBeforeRun = repository.fetchAssistantArtifacts.mock.calls.length
    await wrapper.find('[data-testid="home-draft-run"]').trigger('click')

    await vi.waitFor(() => expect(wrapper.find('[data-testid="home-run-result"]').exists()).toBe(true))
    const call = repository.startAssistantRun.mock.calls[0] as unknown[]
    expect((call[1] as Record<string, unknown>).inputs).toEqual({ symptom: '设备详情页偶发白屏' })
    expect(wrapper.get('[data-testid="home-run-result"]').text()).toContain('等待人工确认')
    expect(wrapper.get('[data-testid="home-run-violations"]').text()).toContain('假设')
    expect(wrapper.get('[data-testid="home-run-artifact"]').text()).toContain('artifact-77')
    // 产物产生后面板刷新（相对运行前 +1 次）
    await vi.waitFor(() =>
      expect(repository.fetchAssistantArtifacts.mock.calls.length).toBeGreaterThan(listCallsBeforeRun))
  })

  it('prechecks the draft and reports the backend readiness verdict inline', async () => {
    const { wrapper, repository } = mountHome()
    await vi.waitFor(() => expect(repository.fetchAssistantCatalog).toHaveBeenCalled())
    await wrapper.find('input[data-testid="home-assistant-input"]').setValue('设备详情页偶发白屏')
    await wrapper.find('[data-testid="home-assistant-send"]').trigger('click')
    await wrapper.find('[data-testid="home-draft-input-symptom"]').setValue('设备详情页偶发白屏')
    await wrapper.find('[data-testid="home-draft-precheck"]').trigger('click')

    await vi.waitFor(() => expect(wrapper.find('[data-testid="home-draft-precheck-result"]').exists()).toBe(true))
    const text = wrapper.get('[data-testid="home-draft-precheck-result"]').text()
    expect(text).toContain('预检通过')
    const call = repository.fetchRoutingCandidates.mock.calls[0] as unknown[]
    expect(call[1]).toEqual({ symptom: '设备详情页偶发白屏' })
  })

  it('shows the missing inputs the backend reports for an incomplete draft', async () => {
    const { wrapper, repository } = mountHome()
    ;(repository.fetchRoutingCandidates as ReturnType<typeof vi.fn>).mockResolvedValueOnce([
      { taskType: 'bug_analysis', displayName: '缺陷分析', missingInputs: ['symptom'], evidenceReady: true, complete: false }
    ])
    await vi.waitFor(() => expect(repository.fetchAssistantCatalog).toHaveBeenCalled())
    await wrapper.find('input[data-testid="home-assistant-input"]').setValue('设备详情页偶发白屏')
    await wrapper.find('[data-testid="home-assistant-send"]').trigger('click')
    await wrapper.find('[data-testid="home-draft-precheck"]').trigger('click')

    await vi.waitFor(() => expect(wrapper.find('[data-testid="home-draft-precheck-result"]').exists()).toBe(true))
    expect(wrapper.get('[data-testid="home-draft-precheck-result"]').text()).toContain('现象描述')
  })

  it('offers the document directory picker for tasks that take document ids', async () => {
    const { wrapper, repository, documentRepository } = mountHome()
    await vi.waitFor(() => expect(documentRepository.fetchProjectSourceDocuments).toHaveBeenCalled())
    await wrapper.find('input[data-testid="home-assistant-input"]').setValue('帮我分析一下这份需求文档')
    await wrapper.find('[data-testid="home-assistant-send"]').trigger('click')

    await vi.waitFor(() => expect(wrapper.find('[data-testid="home-draft-picker-documents"]').exists()).toBe(true))
    const selectText = wrapper.get('[data-testid="home-draft-picker-documents"]').text()
    expect(selectText === '' || selectText.includes('从项目文档目录中选择')).toBe(true)

    // 通过组件内部交互选择文档（el-select 多选）
    const picker = wrapper.find('[data-testid="home-draft-picker-documents"]')
    const select = picker.findComponent({ name: 'ElSelect' })
    expect(select.exists()).toBe(true)
    await select.vm.$emit('update:modelValue', ['doc-alpha'])
    await wrapper.find('[data-testid="home-draft-run"]').trigger('click')

    await vi.waitFor(() => expect(repository.startAssistantRun).toHaveBeenCalled())
    const call = repository.startAssistantRun.mock.calls[0] as unknown[]
    const inputs = (call[1] as Record<string, unknown>).inputs as Record<string, unknown>
    expect(inputs.documentIds).toEqual(['doc-alpha'])
  })

  it('offers the confirmed draft-point picker for the case generation draft', async () => {
    const { wrapper, repository } = mountHome()
    await vi.waitFor(() => expect(repository.fetchAssistantDraftPoints).toHaveBeenCalled())
    await wrapper.find('input[data-testid="home-assistant-input"]').setValue('帮我补齐一批接口用例')
    await wrapper.find('[data-testid="home-assistant-send"]').trigger('click')

    await vi.waitFor(() => expect(wrapper.find('[data-testid="home-draft-picker-draft-points"]').exists()).toBe(true))
    const picker = wrapper.find('[data-testid="home-draft-picker-draft-points"]')
    const select = picker.findComponent({ name: 'ElSelect' })
    await select.vm.$emit('update:modelValue', ['point-alpha'])
    await wrapper.find('[data-testid="home-draft-run"]').trigger('click')

    await vi.waitFor(() => expect(repository.startAssistantRun).toHaveBeenCalled())
    const call = repository.startAssistantRun.mock.calls[0] as unknown[]
    const inputs = (call[1] as Record<string, unknown>).inputs as Record<string, unknown>
    expect(inputs.draftPointIds).toEqual(['point-alpha'])
  })

  it('keeps an honest failure message when the launch is refused', async () => {
    const { wrapper, repository } = mountHome()
    await vi.waitFor(() => expect(repository.fetchAssistantCatalog).toHaveBeenCalled())
    repository.startAssistantRun.mockRejectedValueOnce(
      new Error('AI assistant request failed: 400 missing_inputs: symptom') as never)
    await wrapper.find('input[data-testid="home-assistant-input"]').setValue('看下这个缺陷')
    await wrapper.find('[data-testid="home-assistant-send"]').trigger('click')
    await wrapper.find('[data-testid="home-draft-run"]').trigger('click')

    await vi.waitFor(() => expect(wrapper.text()).toContain('运行未提交'))
    expect(wrapper.text()).toContain('missing_inputs')
    expect(wrapper.find('[data-testid="home-run-result"]').exists()).toBe(false)
  })
})

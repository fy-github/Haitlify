// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { useAssistantRunLauncher } from './useAssistantRunLauncher'
import type { AiTaskDefinition } from '../domain/aiAssistantRepository'

const task: AiTaskDefinition = {
  taskType: 'bug_analysis',
  displayName: '缺陷分析',
  requiredInputs: ['symptom'],
  evidenceTypes: [],
  toolAllowlist: [],
  humanGateRequired: true,
  artifactTarget: ''
}

const makeRepository = () => ({
  fetchAssistantCatalog: vi.fn(async () => ({
    tasks: [task, { ...task, taskType: 'log_triage', displayName: '日志排查', requiredInputs: ['logExcerpt', 'timeRange'] }]
  })),
  startAssistantRun: vi.fn(async () => ({
    runId: 'assistant-9', taskType: 'bug_analysis', projectId: 42, status: 'waiting_review',
    violations: [], artifactId: 'artifact-9', revision: 1, acceptanceState: 'PENDING_HUMAN_CONFIRMATION'
  })),
  fetchRoutingCandidates: vi.fn(async () => [
    { taskType: 'bug_analysis', displayName: '缺陷分析', missingInputs: [], evidenceReady: true, complete: true },
    { taskType: 'log_triage', displayName: '日志排查', missingInputs: ['logExcerpt', 'timeRange'], evidenceReady: true, complete: false }
  ])
})

describe('useAssistantRunLauncher', () => {
  it('loads the catalog and suggests ranked candidates', async () => {
    const repository = makeRepository()
    const launcher = useAssistantRunLauncher({
      repository: repository as never,
      resolveProject: (async () => ({ id: 42 })) as never
    })
    await launcher.loadCatalog()
    expect(launcher.catalog.value).toHaveLength(2)
    expect(launcher.suggest('接口白屏崩溃')[0]?.taskType).toBe('bug_analysis')
  })

  it('launches against the resolved project with built inputs', async () => {
    const repository = makeRepository()
    const launcher = useAssistantRunLauncher({
      repository: repository as never,
      resolveProject: (async () => ({ id: 42 })) as never
    })
    await launcher.loadCatalog()
    const run = await launcher.launch(task, { symptom: '设备详情页白屏' })

    const call = repository.startAssistantRun.mock.calls[0] as unknown[]
    expect((call[1] as Record<string, unknown>).taskType).toBe('bug_analysis')
    expect((call[1] as Record<string, unknown>).projectId).toBe(42)
    expect((call[1] as Record<string, unknown>).inputs).toEqual({ symptom: '设备详情页白屏' })
    expect(run?.runId).toBe('assistant-9')
    expect(launcher.launching.value).toBe(false)
  })

  it('loads the project document directory and recent runs for the pickers', async () => {
    const repository = makeRepository()
    ;(repository as Record<string, unknown>).fetchAssistantArtifacts = vi.fn(async () => ({
      artifacts: [{ artifactId: 'artifact-1', runId: 'assistant-1', taskType: 'bug_analysis', projectId: 42,
        revision: 1, acceptanceState: 'ACCEPTED', status: 'accepted', reviewedBy: 'qa', createdAt: 'x' }],
      limit: 10
    }))
    ;(repository as Record<string, unknown>).fetchAssistantDraftPoints = vi.fn(async () => ({
      items: [{ pointId: 'point-1', title: '登录跳转', moduleName: '认证', caseKind: 'interface',
        reviewStatus: 'confirmed', updatedAt: 'x' }],
      limit: 50
    }))
    const documents = {
      fetchProjectSourceDocuments: vi.fn(async () => ({
        items: [{ documentId: 'doc-1', fileName: '需求.docx', documentType: 'word', parserVersion: 'v1', createdAt: 'x' }]
      }))
    }
    const launcher = useAssistantRunLauncher({
      repository: repository as never,
      documentRepository: documents as never,
      resolveProject: (async () => ({ id: 42 })) as never
    })
    await launcher.loadDocuments()
    await launcher.loadRecentRuns()
    await launcher.loadDraftPoints()
    expect(documents.fetchProjectSourceDocuments).toHaveBeenCalledWith(42, {})
    expect(launcher.documents.value[0]?.documentId).toBe('doc-1')
    expect(launcher.recentRuns.value[0]?.runId).toBe('assistant-1')
    expect(launcher.draftPoints.value[0]?.pointId).toBe('point-1')
  })

  it('prechecks the draft inputs through the routing endpoint', async () => {
    const repository = makeRepository()
    const launcher = useAssistantRunLauncher({
      repository: repository as never,
      resolveProject: (async () => ({ id: 42 })) as never
    })
    const result = await launcher.precheck(task, { symptom: '设备详情页白屏' })

    const call = repository.fetchRoutingCandidates.mock.calls[0] as unknown[]
    expect(call[1]).toEqual({ symptom: '设备详情页白屏' })
    expect(result?.complete).toBe(true)
    expect(result?.missingInputs).toEqual([])
    // log_triage 未完成 ⇒ 不作为可运行替代；bug_analysis 自身被排除出替代列表
    expect(result?.alternatives).toEqual([])
    expect(launcher.prechecking.value).toBe(false)
  })

  it('keeps the honest backend error when the launch is refused', async () => {
    const repository = makeRepository()
    repository.startAssistantRun.mockRejectedValueOnce(
      new Error('AI assistant request failed: 400 missing_inputs: symptom') as never)
    const launcher = useAssistantRunLauncher({
      repository: repository as never,
      resolveProject: (async () => ({ id: 42 })) as never
    })
    const run = await launcher.launch(task, {})
    expect(run).toBeNull()
    expect(launcher.error.value).toContain('missing_inputs')
  })
})

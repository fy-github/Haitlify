/**
 * T12 对话入口状态：任务目录加载 + 显式选择后的真实运行。
 *
 * 关键语义：建议只是候选（关键词命中排序），运行必须由用户在运行卡里显式提交；
 * 缺失输入/未知任务由后端裁决并原样回显，前端不吞错、不预判结果。
 */
import { ref } from 'vue'
import * as defaultRepository from '../domain/aiAssistantRepository'
import * as defaultPlatformRepository from '../domain/aiPlatformRepository'
import { ensureBackendProject } from '../domain/platformBackendRepository'
import { buildTaskInputs, suggestTaskTypes } from '../domain/aiAssistantRouting'
import type {
  AiArtifactSummary,
  AiAssistantRepositoryOptions,
  AiAssistantRun,
  AiDraftPointSummary,
  AiTaskDefinition
} from '../domain/aiAssistantRepository'
import type { AiSourceDocumentSummary } from '../domain/aiPlatformRepository'

type AssistantRepository = typeof defaultRepository
type DocumentRepository = {
  fetchProjectSourceDocuments: typeof defaultPlatformRepository.fetchProjectSourceDocuments
}

export const useAssistantRunLauncher = (
  deps: {
    options?: AiAssistantRepositoryOptions
    repository?: AssistantRepository
    documentRepository?: DocumentRepository
    projectName?: string
    resolveProject?: (projectName: string) => Promise<{ id: number }>
  } = {}
) => {
  const repository = deps.repository ?? defaultRepository
  const documentRepository = deps.documentRepository ?? defaultPlatformRepository
  const options = deps.options ?? {}
  const projectName = deps.projectName ?? '默认项目'
  const resolveProject = deps.resolveProject ?? ((name: string) => ensureBackendProject(name))

  const catalog = ref<AiTaskDefinition[]>([])
  const documents = ref<AiSourceDocumentSummary[]>([])
  const recentRuns = ref<AiArtifactSummary[]>([])
  const draftPoints = ref<AiDraftPointSummary[]>([])
  const catalogLoading = ref(false)
  const prechecking = ref(false)
  const launching = ref(false)
  const error = ref('')

  const describeError = (value: unknown): string => (value instanceof Error ? value.message : String(value))

  const loadCatalog = async () => {
    catalogLoading.value = true
    error.value = ''
    try {
      const payload = await repository.fetchAssistantCatalog(options)
      catalog.value = payload.tasks
    } catch (value) {
      error.value = describeError(value)
    } finally {
      catalogLoading.value = false
    }
  }

  /** 项目文档目录：运行卡 documentIds 选择器数据源（只读，服务端已按项目隔离）。 */
  const loadDocuments = async () => {
    try {
      const project = await resolveProject(projectName)
      const listed = await documentRepository.fetchProjectSourceDocuments(project.id, options)
      documents.value = listed.items
    } catch (value) {
      error.value = describeError(value)
    }
  }

  /** 已确认/已修订的草稿测试点：运行卡 draftPointIds 选择器数据源（与 generateCases 准入一致）。 */
  const loadDraftPoints = async () => {
    try {
      const project = await resolveProject(projectName)
      const listed = await repository.fetchAssistantDraftPoints(options, project.id, 50)
      draftPoints.value = listed.items
    } catch (value) {
      error.value = describeError(value)
    }
  }

  /** 最近产物（取 runId）：运行卡 sourceRunIds 选择器数据源。 */
  const loadRecentRuns = async () => {
    try {
      const project = await resolveProject(projectName)
      const listed = await repository.fetchAssistantArtifacts(options, project.id, 10)
      recentRuns.value = listed.artifacts
    } catch (value) {
      error.value = describeError(value)
    }
  }

  /** 关键词建议排行（未命中时返回完整目录顺序，供用户显式改选）。 */
  const suggest = (text: string): AiTaskDefinition[] => suggestTaskTypes(text, catalog.value)

  /** 运行前预检：以当前草稿输入调用后端就绪路由，返回该任务的缺项裁决与输入已满足的替代候选。 */
  const precheck = async (
    task: AiTaskDefinition,
    rawValues: Record<string, string>
  ): Promise<{ taskType: string; missingInputs: string[]; complete: boolean; alternatives: string[] } | null> => {
    prechecking.value = true
    error.value = ''
    try {
      const candidates = await repository.fetchRoutingCandidates(options, buildTaskInputs(task, rawValues))
      const current = candidates.find((candidate) => candidate.taskType === task.taskType)
      if (!current) return null
      return {
        taskType: current.taskType,
        missingInputs: current.missingInputs ?? [],
        complete: Boolean(current.complete),
        alternatives: candidates
          .filter((candidate) => candidate.taskType !== task.taskType && candidate.complete)
          .map((candidate) => candidate.displayName)
          .slice(0, 3)
      }
    } catch (value) {
      error.value = describeError(value)
      return null
    } finally {
      prechecking.value = false
    }
  }

  const launch = async (task: AiTaskDefinition, rawValues: Record<string, string>): Promise<AiAssistantRun | null> => {
    launching.value = true
    error.value = ''
    try {
      const project = await resolveProject(projectName)
      const run = await repository.startAssistantRun(options, {
        taskType: task.taskType,
        projectId: project.id,
        inputs: buildTaskInputs(task, rawValues)
      })
      return run
    } catch (value) {
      error.value = describeError(value)
      return null
    } finally {
      launching.value = false
    }
  }

  return {
    catalog, documents, recentRuns, draftPoints, catalogLoading, launching, prechecking, error,
    loadCatalog, loadDocuments, loadRecentRuns, loadDraftPoints, suggest, precheck, launch
  }
}

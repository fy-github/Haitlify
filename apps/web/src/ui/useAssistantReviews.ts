/**
 * T12 评审闭环 UI 状态：近期产物列表 → 选中 → 运行详情 → 评审（revision 钉住）→ 导出（仅 ACCEPTED）。
 *
 * 与后端契约对齐：
 *   - 列表 projectId 必填（服务端 fail-closed），排序与 limit 均以服务端为准；
 *   - 评审携带 revision：过期即 409 stale_revision，单列呈现，不覆盖；
 *   - 导出的准入裁决一律来自后端（未接受 ⇒ 400 artifact_not_accepted），前端不自行放宽。
 */
import { ref } from 'vue'
import * as defaultRepository from '../domain/aiAssistantRepository'
import { ensureBackendProject } from '../domain/platformBackendRepository'
import type {
  AiArtifactExport,
  AiArtifactSummary,
  AiAssistantRepositoryOptions,
  AiAssistantRun
} from '../domain/aiAssistantRepository'

type AssistantRepository = typeof defaultRepository

export const useAssistantReviews = (
  deps: {
    options?: AiAssistantRepositoryOptions
    repository?: AssistantRepository
    projectName?: string
    resolveProject?: (projectName: string) => Promise<{ id: number }>
  } = {}
) => {
  const repository = deps.repository ?? defaultRepository
  const options = deps.options ?? {}
  const projectName = deps.projectName ?? '默认项目'
  const resolveProject = deps.resolveProject ?? ((name: string) => ensureBackendProject(name))

  const artifacts = ref<AiArtifactSummary[]>([])
  const selected = ref<AiAssistantRun | null>(null)
  const selectedArtifactId = ref('')
  const exported = ref<AiArtifactExport | null>(null)
  const listLoading = ref(false)
  const detailLoading = ref(false)
  const reviewing = ref(false)
  const exporting = ref(false)
  const error = ref('')
  const conflict = ref('')

  const describeError = (value: unknown): string => (value instanceof Error ? value.message : String(value))
  const isConflict = (value: unknown): boolean => {
    const status = (value as { status?: number } | null)?.status
    if (status === 409) return true
    return /409|stale_revision|conflict/i.test(describeError(value))
  }

  const loadArtifacts = async () => {
    listLoading.value = true
    error.value = ''
    try {
      const project = await resolveProject(projectName)
      const listed = await repository.fetchAssistantArtifacts(options, project.id)
      artifacts.value = listed.artifacts
    } catch (value) {
      error.value = describeError(value)
    } finally {
      listLoading.value = false
    }
  }

  const selectArtifact = async (artifactId: string) => {
    selectedArtifactId.value = artifactId
    selected.value = null
    exported.value = null
    detailLoading.value = true
    error.value = ''
    try {
      const item = artifacts.value.find((entry) => entry.artifactId === artifactId)
      if (!item) throw new Error(`artifact_not_found_in_list: ${artifactId}`)
      selected.value = await repository.fetchAssistantRun(options, item.runId)
    } catch (value) {
      error.value = describeError(value)
    } finally {
      detailLoading.value = false
    }
  }

  const review = async (decision: 'approve' | 'reject' | 'change', candidate?: unknown) => {
    if (!selected.value) return
    reviewing.value = true
    error.value = ''
    conflict.value = ''
    try {
      const updated = await repository.reviewAssistantArtifact(options, selectedArtifactId.value, {
        decision,
        revision: selected.value.revision,
        candidate
      })
      selected.value = updated
      // 改稿（change）会生成新 revision 的新 artifactId，详情必须跟随新产物而不是旧 id。
      if (updated.artifactId) {
        selectedArtifactId.value = updated.artifactId
      }
      await loadArtifacts()
    } catch (value) {
      if (isConflict(value)) conflict.value = describeError(value)
      else error.value = describeError(value)
    } finally {
      reviewing.value = false
    }
  }

  const exportArtifact = async () => {
    if (!selected.value) return
    exporting.value = true
    error.value = ''
    try {
      exported.value = await repository.fetchAssistantArtifactExport(
        options,
        selectedArtifactId.value,
        selected.value.revision
      )
    } catch (value) {
      if (isConflict(value)) conflict.value = describeError(value)
      else error.value = describeError(value)
    } finally {
      exporting.value = false
    }
  }

  return {
    artifacts,
    selected,
    selectedArtifactId,
    exported,
    listLoading,
    detailLoading,
    reviewing,
    exporting,
    error,
    conflict,
    loadArtifacts,
    selectArtifact,
    review,
    exportArtifact
  }
}

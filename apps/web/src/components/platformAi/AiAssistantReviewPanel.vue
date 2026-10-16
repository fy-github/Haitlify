<script setup lang="ts">
/**
 * T12 评审闭环 UI：近期产物 → 详情（输出/违规/接收状态）→ 评审（approve/reject，revision 钉住）→ 导出（仅 ACCEPTED）。
 * 视觉遵循设计 token：软卡 16px 圆角、20px 留白、12px 内衬与圆角表格框。
 */
import { onMounted, ref, watch } from 'vue'
import { ClipboardCheck, Download, RotateCcw, ShieldCheck, TriangleAlert } from '@lucide/vue'
import { useAssistantReviews } from '../../ui/useAssistantReviews'
import type { AiAssistantRepositoryOptions } from '../../domain/aiAssistantRepository'
import * as defaultRepository from '../../domain/aiAssistantRepository'

const props = withDefaults(defineProps<{
  options?: AiAssistantRepositoryOptions
  repository?: typeof defaultRepository
  projectName?: string
  resolveProject?: (projectName: string) => Promise<{ id: number }>
  /** 外部刷新信号：对话产生新产物后自增，触发列表重载。 */
  refreshToken?: number
}>(), { options: undefined, repository: undefined, projectName: '默认项目', resolveProject: undefined, refreshToken: 0 })

const {
  artifacts, selected, selectedArtifactId, exported,
  listLoading, detailLoading, reviewing, exporting,
  error, conflict,
  loadArtifacts, selectArtifact, review, exportArtifact
} = useAssistantReviews({
  options: props.options,
  repository: props.repository,
  projectName: props.projectName,
  resolveProject: props.resolveProject
})

defineExpose({ loadArtifacts, selectArtifact, review, exportArtifact })

const statusLabel = (status: string): string =>
  status === 'accepted' ? '已接受' : status === 'rejected' ? '已驳回' : '待评审'

const canExport = (): boolean => selected.value?.acceptanceState === 'ACCEPTED'

const changeOpen = ref(false)
const candidateText = ref('')
const changeError = ref('')

const openChange = () => {
  if (!selected.value) return
  candidateText.value = JSON.stringify(selected.value.output ?? null, null, 2)
  changeError.value = ''
  changeOpen.value = true
}

const submitChange = async () => {
  changeError.value = ''
  let candidate: unknown
  try {
    candidate = JSON.parse(candidateText.value)
  } catch {
    changeError.value = '改稿内容必须是合法 JSON——请基于原输出修改后提交。'
    return
  }
  await review('change', candidate)
  if (!conflict.value && !error.value) {
    changeOpen.value = false
  }
}

const downloadExport = () => {
  if (!exported.value) return
  try {
    const blob = new Blob([JSON.stringify(exported.value, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${exported.value.artifactId}.json`
    anchor.click()
    URL.revokeObjectURL(url)
  } catch {
    /* 无 DOM 环境（测试）时仅保留导出状态，不抛错 */
  }
}

onMounted(loadArtifacts)

watch(() => props.refreshToken, () => {
  void loadArtifacts()
})
</script>

<template>
  <section class="ai-review-panel" aria-label="产物评审与导出" data-testid="ai-review-panel">
    <header class="review-head">
      <ClipboardCheck :size="16" aria-hidden="true" />
      <strong>产物评审与导出</strong>
      <span class="review-hint">人工确认（approve）后方可导出；评审携带版本，过期即 409 冲突</span>
      <el-button size="small" :loading="listLoading" data-testid="review-refresh" @click="loadArtifacts">
        <RotateCcw :size="13" aria-hidden="true" />刷新
      </el-button>
    </header>

    <el-alert
      v-if="conflict"
      type="warning"
      :closable="false"
      data-testid="review-conflict"
      :title="`版本冲突：${conflict}——请刷新后基于最新版本重试`"
    />
    <el-alert v-if="error" type="error" :closable="false" data-testid="review-error" :title="error" />

    <div class="review-grid">
      <div class="registry-table-frame">
        <el-table
          :data="artifacts"
          class="platform-ai-table"
          highlight-current-row
          data-testid="review-table"
          @row-click="(row: any) => selectArtifact(row.artifactId)"
        >
          <el-table-column prop="taskType" label="任务" min-width="130" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <span class="platform-ai-status" :class="row.status === 'accepted' ? 'is-ok' : row.status === 'rejected' ? 'is-off' : ''">
                {{ statusLabel(row.status) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="版本" width="70"><template #default="{ row }">v{{ row.revision }}</template></el-table-column>
          <el-table-column prop="reviewedBy" label="评审人" min-width="100" />
        </el-table>
      </div>

      <div v-if="selected" class="review-detail" data-testid="review-detail">
        <div class="detail-head">
          <ShieldCheck :size="15" aria-hidden="true" />
          <strong>{{ selected.taskType }}</strong>
          <span class="platform-ai-status" :class="canExport() ? 'is-ok' : ''">{{ selected.acceptanceState }}</span>
          <span class="detail-version">v{{ selected.revision }}</span>
        </div>

        <div v-if="(selected.violations ?? []).length > 0" class="detail-violations" data-testid="review-violations">
          <TriangleAlert :size="14" aria-hidden="true" />
          <ul>
            <li v-for="(violation, index) in selected.violations" :key="index">{{ violation }}</li>
          </ul>
        </div>

        <pre class="detail-output" data-testid="review-output">{{ JSON.stringify(selected.output ?? null, null, 2) }}</pre>

        <div class="detail-actions">
          <el-button
            type="primary"
            size="small"
            :loading="reviewing"
            :disabled="selected.acceptanceState !== 'PENDING_HUMAN_CONFIRMATION'"
            data-testid="review-approve"
            @click="review('approve')"
          >通过</el-button>
          <el-button
            size="small"
            :loading="reviewing"
            :disabled="selected.acceptanceState !== 'PENDING_HUMAN_CONFIRMATION'"
            data-testid="review-reject"
            @click="review('reject')"
          >驳回</el-button>
          <el-button
            size="small"
            :disabled="selected.acceptanceState !== 'PENDING_HUMAN_CONFIRMATION'"
            data-testid="review-change-toggle"
            @click="openChange"
          >改稿</el-button>
          <el-button
            size="small"
            :disabled="!canExport()"
            :loading="exporting"
            data-testid="review-export"
            @click="exportArtifact"
          >
            <Download :size="13" aria-hidden="true" />导出已接受产物
          </el-button>
          <el-button v-if="exported" size="small" data-testid="review-download" @click="downloadExport">下载 JSON</el-button>
        </div>

        <div v-if="changeOpen" class="change-editor" data-testid="review-change-editor">
          <span class="change-label">改稿内容（合法 JSON，提交后生成新版本，旧版本保持不可变）</span>
          <el-input v-model="candidateText" type="textarea" :rows="8" data-testid="review-change-textarea" />
          <p v-if="changeError" class="change-error" data-testid="review-change-error">{{ changeError }}</p>
          <div class="change-actions">
            <el-button type="primary" size="small" :loading="reviewing" data-testid="review-change-submit" @click="submitChange">提交改稿</el-button>
            <el-button size="small" data-testid="review-change-cancel" @click="changeOpen = false">取消</el-button>
          </div>
        </div>
      </div>
      <p v-else-if="!detailLoading" class="review-empty" data-testid="review-empty">
        选择左侧一条产物查看输出、违规与评审动作。无产物时先到 AI 助手运行一次任务。
      </p>
    </div>
  </section>
</template>

<style scoped>
.ai-review-panel {
  display: grid;
  gap: 16px;
  margin-top: 24px;
  padding: 20px 22px 22px;
  border: 1px solid var(--border-subtle, #e2eeea);
  border-radius: var(--radius-xl, 16px);
  background: var(--surface-raised, #ffffff);
  box-shadow: var(--shadow-sm, 0 2px 8px rgba(15, 23, 42, 0.04));
}
.review-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.review-hint { color: var(--muted-foreground, #6b7280); font-size: 12px; line-height: 18px; margin-right: auto; }
.review-grid { display: grid; gap: 16px; }
.registry-table-frame {
  border: 1px solid var(--border-subtle, #e2eeea);
  border-radius: var(--radius-lg, 12px);
  overflow: hidden;
}
.review-detail {
  display: grid;
  gap: 12px;
  padding: 16px 18px;
  border: 1px solid var(--border-subtle, #e2eeea);
  border-radius: var(--radius-lg, 12px);
  background: var(--surface-panel, rgba(255, 255, 255, 0.92));
}
.detail-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.detail-version { color: var(--muted-foreground, #6b7280); font-size: 12px; }
.detail-violations { display: flex; gap: 8px; color: #b45309; font-size: 12px; line-height: 19px; }
.detail-violations ul { margin: 0; padding-left: 16px; }
.detail-output {
  margin: 0;
  padding: 12px 14px;
  border-radius: var(--radius-md, 10px);
  background: var(--surface-subtle, #ecf5f2);
  font-size: 12px;
  line-height: 18px;
  max-height: 280px;
  overflow: auto;
}
.detail-actions { display: flex; gap: 10px; flex-wrap: wrap; }
.review-empty { color: var(--muted-foreground, #6b7280); font-size: 13px; margin: 0; line-height: 20px; }
.change-editor {
  display: grid;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid var(--border-subtle, #e2eeea);
  border-radius: var(--radius-lg, 12px);
  background: var(--surface-subtle, #ecf5f2);
}
.change-label { font-size: 12px; line-height: 18px; color: var(--muted-foreground, #6b7280); }
.change-error { margin: 0; font-size: 12px; line-height: 18px; color: #b45309; }
.change-actions { display: flex; gap: 10px; }

:deep(.el-alert) { border-radius: var(--radius-lg, 12px); }
</style>

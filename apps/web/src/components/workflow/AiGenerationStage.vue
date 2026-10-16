<script setup lang="ts">
import { CircleAlert, CircleCheck, RefreshCcw, Sparkles, Square } from '@lucide/vue'
import type { WorkflowAsyncStatus } from '../../domain/caseDesignWorkflow'
import TwoPhaseIndicator from './TwoPhaseIndicator.vue'

const props = defineProps<{
  status: WorkflowAsyncStatus
  progressPercentage: number
  progressText: string
  errorMessage: string
  selectedDraftCount: number
  totalDraftCount: number
  pendingDraftCount?: number
  confirmingDrafts?: boolean
  cancellingGeneration?: boolean
  generatedCaseCount: number
  retryablePointCount?: number
  agentVisible?: boolean
}>()

const emit = defineEmits<{ generate: []; retry: []; cancel: [] }>()

const isRunning = () => props.status === 'running'
const hasPendingDrafts = () => (props.pendingDraftCount || 0) > 0
const canStartGeneration = () => props.selectedDraftCount > 0 || hasPendingDrafts()
</script>

<template>
  <section class="studio-ai-generation-stage" data-testid="ai-generation-stage">
    <header class="studio-stage-heading">
      <div>
        <strong>AI 生成用例</strong>
        <span>两阶段：先生成测试设计，再基于页面素材与浏览器证据落地为自动化用例</span>
      </div>
      <TwoPhaseIndicator class="studio-generation-stage__two-phase" />
      <el-button
        v-if="status === 'running'"
        type="danger"
        plain
        data-testid="generation-cancel"
        :loading="cancellingGeneration"
        :disabled="cancellingGeneration"
        @click="emit('cancel')"
      ><Square :size="14" />{{ cancellingGeneration ? '正在终止' : '终止生成' }}</el-button>
      <el-button
        v-else-if="status === 'failed'"
        type="primary"
        data-testid="generation-retry"
        :disabled="retryablePointCount === 0"
        @click="emit('retry')"
      ><RefreshCcw :size="15" />重试未完成批次</el-button>
      <el-button
        v-else
        type="primary"
        data-testid="generation-start"
        :loading="isRunning() || confirmingDrafts"
        :disabled="!canStartGeneration() || isRunning() || confirmingDrafts"
        @click="emit('generate')"
      ><Sparkles :size="15" />{{ confirmingDrafts ? '正在确认测试点' : generatedCaseCount > 0 ? '重新生成用例' : hasPendingDrafts() ? '确认并生成用例' : '生成用例' }}</el-button>
    </header>

    <div class="studio-generation-stage__summary">
      <span>已选择 {{ selectedDraftCount }} / {{ totalDraftCount }} 个确认测试点<span v-if="hasPendingDrafts()"> · {{ pendingDraftCount }} 个待确认</span></span>
      <span v-if="agentVisible">Agent 工作区会展示规划、素材命中、取证、编译和阻塞原因</span>
    </div>

    <div class="studio-generation-stage__body">
      <section class="studio-generation-stage__controls">
        <header><strong>生成范围</strong><span>确认后可随时调整</span></header>
        <slot name="controls" />
        <slot name="drafts" />
      </section>
      <section class="studio-generation-stage__results">
        <header><strong>已生成用例</strong><span>{{ generatedCaseCount }}</span></header>
        <div v-if="!agentVisible && (status === 'running' || status === 'failed' || generatedCaseCount > 0)" class="studio-generation-status" :class="`is-${status}`" role="status">
          <CircleCheck v-if="status === 'succeeded' || generatedCaseCount > 0" :size="17" />
          <CircleAlert v-else-if="status === 'failed'" :size="17" />
          <Sparkles v-else :size="17" />
          <div>
            <strong>{{ progressText }}</strong>
            <span v-if="errorMessage">{{ errorMessage }}</span>
            <el-progress v-else-if="status === 'running'" :percentage="progressPercentage" :show-text="false" />
          </div>
        </div>
        <slot name="results" />
      </section>
    </div>
  </section>
</template>

<style scoped>
.studio-generation-stage__two-phase {
  margin-left: auto;
}

.studio-generation-stage__summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 14px;
  justify-content: space-between;
  padding: 0 0 12px;
  color: var(--text-tertiary);
  font-size: 12px;
}

.studio-generation-stage__summary span:last-child {
  color: var(--brand-primary);
}

@media (max-width: 960px) {
  .studio-generation-stage__two-phase {
    margin-left: 0;
  }
  .studio-generation-stage__summary {
    align-items: flex-start;
    flex-direction: column;
    gap: 4px;
  }
}
</style>

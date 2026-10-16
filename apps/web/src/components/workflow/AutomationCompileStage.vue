<script setup lang="ts">
import type { WorkflowAsyncStatus } from '../../domain/caseDesignWorkflow'
import type { GeneratedWebAutomationCase } from '../../domain/webAutomationCase'
import WebCaseAgentWorkspace from './WebCaseAgentWorkspace.vue'
import GeneratedWebCaseTable from './GeneratedWebCaseTable.vue'

defineProps<{
  status: WorkflowAsyncStatus
  progressText: string
  cases: GeneratedWebAutomationCase[]
  selectedIds: string[]
  run?: import('../../domain/webCaseAgentRepository').WebCaseAgentRun
  events: import('../../domain/webCaseAgentRepository').WebCaseAgentEvent[]
  naturalPlanCount?: number
}>()

const emit = defineEmits<{
  compile: []
  cancel: []
  retry: []
  resume: []
  selectionChange: [ids: string[]]
  activate: [testCase: GeneratedWebAutomationCase]
  preview: [testCase: GeneratedWebAutomationCase]
  edit: [testCase: GeneratedWebAutomationCase]
  remove: [testCase: GeneratedWebAutomationCase]
  configureEnvironment: []
}>()

const isRunning = (status: WorkflowAsyncStatus) => status === 'running'
</script>

<template>
  <section class="studio-automation-compile-stage" data-testid="automation-compile-stage">
    <header class="studio-automation-compile-stage__toolbar">
      <div class="studio-automation-compile-stage__context">
        <span>待编译 {{ cases.length }} 条</span>
        <span :title="progressText">{{ progressText }}</span>
        <span v-if="naturalPlanCount" class="studio-automation-compile-stage__pending-plan">待确认计划 {{ naturalPlanCount }} 条</span>
      </div>
      <el-button plain data-testid="configure-web-environment" @click="emit('configureEnvironment')">配置 Web 环境</el-button>
      <el-button v-if="!run" type="primary" :disabled="!cases.length || isRunning(status)" @click="emit('compile')">开始编译自动化</el-button>
      <el-button v-else-if="run.status === 'failed' || run.status === 'blocked' || run.status === 'partial'" type="primary" :disabled="!cases.length || isRunning(status)" @click="emit('retry')">重试编译</el-button>
    </header>
    <div class="studio-automation-compile-stage__body" :class="{ 'has-agent-workspace': run || events.length }">
      <WebCaseAgentWorkspace v-if="run || events.length" :run="run" :events="events" :natural-plan-count="naturalPlanCount" :loading="isRunning(status)" @cancel="emit('cancel')" @retry="emit('retry')" @resume="emit('resume')" @open-details="() => undefined" />
      <GeneratedWebCaseTable v-if="cases.length" :cases="cases" :selected-ids="selectedIds" :project-id="run?.projectId" table-height="100%" selectable @selection-change="emit('selectionChange', $event)" @activate="emit('activate', $event)" @preview="emit('preview', $event)" @edit="emit('edit', $event)" @remove="emit('remove', $event)" />
      <el-empty v-else-if="status === 'idle'" description="质量评审完成后开始自动化编译" />
    </div>
  </section>
</template>

<style scoped>
.studio-automation-compile-stage { display: grid; height: calc(100% - 20px); min-height: 0; box-sizing: border-box; grid-template-rows: auto minmax(0, 1fr); margin: 14px 16px 6px; overflow: hidden; background: var(--surface-raised); border: 1px solid var(--border-default); border-radius: 8px; }
.studio-automation-compile-stage__toolbar { display: flex; min-width: 0; min-height: 56px; align-items: center; gap: 10px; padding: 10px 16px; border-bottom: 1px solid var(--border-subtle); }
.studio-automation-compile-stage__context { display: flex; min-width: 0; flex: 1; gap: 12px; color: var(--text-tertiary); font-size: 12px; }
.studio-automation-compile-stage__pending-plan { color: var(--color-warning); font-weight: 600; }
.studio-automation-compile-stage__body { display: grid; min-height: 0; overflow: hidden; }
.studio-automation-compile-stage__body.has-agent-workspace { grid-template-rows: auto minmax(0, 1fr); gap: 10px; padding: 10px 12px 0; }
.studio-automation-compile-stage__body > :deep(.studio-generated-case-table) { min-height: 0; height: 100%; margin: 0; overflow: auto; border-right: 0; border-bottom: 0; border-left: 0; border-radius: 0; }
.studio-automation-compile-stage__body.has-agent-workspace > :deep(.studio-generated-case-table) { border: 1px solid var(--border-subtle); border-bottom: 0; border-radius: 6px 6px 0 0; }
@media (max-width: 720px) { .studio-automation-compile-stage__toolbar { flex-wrap: wrap; align-content: center; }.studio-automation-compile-stage__context { flex-basis: 100%; }.studio-automation-compile-stage__toolbar .el-button { margin-left: 0; } }
</style>

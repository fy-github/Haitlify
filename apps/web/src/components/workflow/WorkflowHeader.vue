<script setup lang="ts">
export interface WorkflowHeaderStage {
  key: string
  label: string
  enabled: boolean
  status: 'pending' | 'active' | 'done' | 'error'
}

defineProps<{
  title: string
  saveLabel: string
  stages: WorkflowHeaderStage[]
  primaryAction: { label: string; disabled: boolean; loading: boolean }
  secondaryAction?: { label: string; disabled: boolean }
}>()

const emit = defineEmits<{
  navigateStage: [stage: string]
  primary: []
  secondary: []
}>()

const stageStatusLabel = (status: WorkflowHeaderStage['status']): string => ({
  pending: '未开始',
  active: '当前阶段',
  done: '已完成',
  error: '发生错误'
}[status])
</script>

<template>
  <header class="studio-workflow-header">
    <div class="studio-workflow-header__identity">
      <strong>{{ title }}</strong>
      <span v-if="saveLabel" class="studio-workflow-header__save-label" role="status" aria-live="polite" :title="saveLabel">{{ saveLabel }}</span>
    </div>
    <nav class="studio-workflow-header__steps" aria-label="测试设计流程">
      <button
        v-for="(stage, index) in stages"
        :key="stage.key"
        type="button"
        class="studio-workflow-step"
        :class="`is-${stage.status}`"
        :data-stage="stage.key"
        :disabled="!stage.enabled"
        :aria-current="stage.status === 'active' ? 'step' : undefined"
        :aria-label="`${stage.label}，${stageStatusLabel(stage.status)}`"
        @click="emit('navigateStage', stage.key)"
      >
        <span>{{ stage.status === 'done' ? '✓ ' : `${index + 1} ` }}{{ stage.label }}</span>
        <small class="studio-visually-hidden">{{ stageStatusLabel(stage.status) }}</small>
      </button>
    </nav>
    <div class="studio-workflow-header__actions">
      <el-button
        v-if="secondaryAction"
        plain
        data-testid="workflow-secondary-action"
        :disabled="secondaryAction.disabled"
        @click="emit('secondary')"
      >
        {{ secondaryAction.label }}
      </el-button>
      <el-button
        type="primary"
        data-testid="workflow-primary-action"
        :disabled="primaryAction.disabled"
        :loading="primaryAction.loading"
        @click="emit('primary')"
      >
        {{ primaryAction.label }}
      </el-button>
    </div>
  </header>
</template>

<script setup lang="ts">
import { Download, RotateCcwClock, Settings } from '@lucide/vue'

const props = withDefaults(defineProps<{
  executableCount: number
  moduleCount: number
  latestRunLabel: string
  executeLabel: string
  canExecute: boolean
}>(), {
  executeLabel: '执行',
  canExecute: false
})

// generateCases 是保留的历史事件契约：定稿把「从用例库生成」移出执行页顶部入口，
// 但父级监听与用例表空态链路仍在使用，因此继续声明、不再渲染快捷按钮。
const emit = defineEmits<{
  openHistory: []
  generateCases: []
  exportCases: []
  editEnvironment: []
  openRuntimeConfig: []
  execute: []
}>()

const execute = () => {
  if (props.canExecute) emit('execute')
}
</script>

<template>
  <header class="web-task-header web-ed-head">
    <div class="web-ed-head__identity">
      <h1 class="web-ed-head__title">Web 自动化</h1>
      <p class="web-ed-head__sub">选择执行范围，确认运行配置，再进入任务监控。</p>
      <p class="web-ed-head__meta"><span>{{ latestRunLabel }}</span></p>
    </div>

    <div class="web-task-header__actions web-ed-actions">
      <button type="button" class="web-ed-btn" data-testid="open-web-history" @click="emit('openHistory')">
        <RotateCcwClock :size="14" aria-hidden="true" />
        执行历史
      </button>
      <button type="button" class="web-ed-btn" data-testid="open-web-environment" @click="emit('editEnvironment')">
        环境配置
      </button>
      <button type="button" class="web-ed-btn" data-testid="open-web-runtime-config" @click="emit('openRuntimeConfig')">
        <Settings :size="14" aria-hidden="true" />
        运行配置
      </button>
      <button type="button" class="web-ed-btn" data-testid="export-web-cases" @click="emit('exportCases')">
        <Download :size="14" aria-hidden="true" />
        导出
      </button>
      <button
        type="button"
        class="web-ed-btn web-ed-btn--primary"
        data-testid="execute-web-automation"
        :disabled="!canExecute"
        @click="execute"
      >
        {{ executeLabel }}
      </button>
    </div>
  </header>
</template>

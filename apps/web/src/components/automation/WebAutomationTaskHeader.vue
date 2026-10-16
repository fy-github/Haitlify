<script setup lang="ts">
import { Download, RotateCcwClock, WandSparkles } from '@lucide/vue'

defineProps<{
  executableCount: number
  moduleCount: number
  latestRunLabel: string
}>()

const emit = defineEmits<{
  openHistory: []
  generateCases: []
  exportCases: []
}>()

</script>

<template>
  <header class="web-task-header">
    <div class="web-task-header__identity">
      <h1>Web 自动化</h1>
      <div class="web-task-header__metadata" aria-label="Web 自动化任务摘要">
        <span>{{ latestRunLabel }}</span>
      </div>
    </div>

    <div class="web-task-header__actions">
      <el-button data-testid="open-web-history" @click="emit('openHistory')">
        <RotateCcwClock :size="15" aria-hidden="true" />
        执行历史
      </el-button>
      <el-button data-testid="generate-web-cases" @click="emit('generateCases')">
        <WandSparkles :size="15" aria-hidden="true" />
        从用例库生成
      </el-button>
      <el-button data-testid="export-web-cases" @click="emit('exportCases')">
        <Download :size="15" aria-hidden="true" />
        导出用例
      </el-button>
    </div>
  </header>
</template>

<style scoped>
.web-task-header {
  display: flex;
  min-height: 56px;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 10px 14px;
  color: var(--text-regular);
  background: var(--surface-raised);
  border: 1px solid var(--border-regular);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-soft);
}
.web-task-header__identity { display: flex; min-width: 0; align-items: center; gap: 14px; }
.web-task-header h1 { flex: none; margin: 0; color: var(--text-primary); font-size: 16px; font-weight: var(--font-weight-semibold); letter-spacing: -0.01em; line-height: 22px; }
.web-task-header__metadata { display: flex; min-width: 0; flex-wrap: wrap; gap: 6px 8px; color: var(--text-secondary); font-size: 12px; line-height: 18px; }
.web-task-header__metadata span { white-space: nowrap; }
.web-task-header__actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 6px;
  padding: 3px;
  background: rgba(255, 255, 255, 0.58);
  border: 1px solid rgba(206, 225, 219, 0.82);
  border-radius: 14px;
  box-shadow: 0 1px 0 rgba(255, 255, 255, 0.78) inset;
}
.web-task-header__actions .el-button {
  min-height: 30px;
  margin: 0;
  padding: 0 10px;
  color: var(--text-regular);
  background: rgba(255, 255, 255, 0.84);
  border: 1px solid rgba(183, 210, 202, 0.82);
  border-radius: 11px;
  box-shadow: none;
  font-size: 12px;
  font-weight: 500;
  line-height: 1.2;
}
.web-task-header__actions .el-button :deep(span) { gap: 6px; }
.web-task-header__actions .el-button:hover,
.web-task-header__actions .el-button:focus {
  color: var(--brand-primary);
  background: var(--surface-raised);
  border-color: rgba(124, 190, 178, 0.78);
  box-shadow: 0 8px 20px rgba(15, 118, 110, 0.07);
}
@media (max-width: 760px) { .web-task-header { align-items: flex-start; flex-direction: column; gap: 8px; } .web-task-header__identity { align-items: flex-start; flex-direction: column; gap: 5px; } .web-task-header__actions { width: 100%; flex-wrap: wrap; } }
</style>

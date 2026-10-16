<script setup lang="ts">
defineProps<{ visible: boolean; title?: string; screenshotPath?: string; htmlSnippet?: string }>()
const emit = defineEmits<{ 'update:visible': [boolean] }>()
</script>

<template>
  <el-dialog
    :model-value="visible"
    :title="title || '截图与 DOM 对照'"
    width="min(980px, 94vw)"
    :close-on-click-modal="false"
    :teleported="false"
    @update:model-value="(value: boolean) => emit('update:visible', value)"
  >
    <div class="screenshot-dom-compare" data-testid="screenshot-dom-compare">
      <div class="screenshot-dom-compare__pane" data-testid="compare-screenshot">
        <strong>失败截图</strong>
        <a v-if="screenshotPath" :href="screenshotPath" target="_blank" rel="noreferrer">打开原图</a>
        <img v-if="screenshotPath" :src="screenshotPath" alt="失败截图" />
        <span v-else class="screenshot-dom-compare__empty">无截图</span>
      </div>
      <div class="screenshot-dom-compare__pane" data-testid="compare-dom">
        <strong>页面 DOM 片段</strong>
        <pre v-if="htmlSnippet">{{ htmlSnippet }}</pre>
        <span v-else class="screenshot-dom-compare__empty">无 DOM 片段</span>
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
.screenshot-dom-compare { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 12px; }
.screenshot-dom-compare__pane { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.screenshot-dom-compare__pane strong { color: var(--brand-text-secondary, #5f6b66); font-size: 12px; }
.screenshot-dom-compare__pane img { width: 100%; border: 1px solid rgba(183, 210, 202, 0.7); border-radius: 10px; }
.screenshot-dom-compare__pane pre { margin: 0; padding: 8px 10px; max-height: 420px; overflow: auto; background: rgba(15, 118, 110, 0.05); border-radius: 10px; font-size: 11.5px; white-space: pre-wrap; overflow-wrap: anywhere; }
.screenshot-dom-compare__empty { color: var(--brand-text-secondary, #5f6b66); font-size: 12px; }
@media (max-width: 720px) { .screenshot-dom-compare { grid-template-columns: minmax(0, 1fr); } }
</style>

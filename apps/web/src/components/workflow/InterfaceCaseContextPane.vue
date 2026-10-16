<script setup lang="ts">
import { Search } from '@lucide/vue'
import type { GenerationGranularity } from '../../domain/caseGenerator'
import type { HttpMethod } from '../../domain/openapiParser'

defineProps<{
  module: string
  moduleOptions: Array<{ value: string; label: string; count?: number }>
  pathKeyword: string
  summaryKeyword: string
  method: HttpMethod | 'ALL'
  templateId: string
  templateOptions: Array<{ value: string; label: string }>
  granularity: GenerationGranularity
}>()

const emit = defineEmits<{
  updateModule: [value: string]
  updatePathKeyword: [value: string]
  updateSummaryKeyword: [value: string]
  updateMethod: [value: HttpMethod | 'ALL']
  updateTemplateId: [value: string]
  updateGranularity: [value: GenerationGranularity]
}>()

const methodOptions: Array<HttpMethod | 'ALL'> = ['ALL', 'GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']
const granularityOptions = [
  { label: '基础', value: 'basic' },
  { label: '风险', value: 'risk' },
  { label: '完整', value: 'full' }
] satisfies Array<{ label: string; value: GenerationGranularity }>
</script>

<template>
  <aside class="studio-interface-context-pane" aria-label="接口与生成配置">
    <section class="studio-interface-context-pane__section">
      <header class="studio-interface-context-pane__header">
        <strong>筛选</strong>
      </header>

      <label class="studio-interface-context-pane__field">
        <span>模块</span>
        <select aria-label="模块" :value="module" @change="emit('updateModule', ($event.target as HTMLSelectElement).value)">
          <option v-for="option in moduleOptions" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
      </label>

      <label class="studio-interface-context-pane__field">
        <span>路径关键词</span>
        <div class="studio-interface-context-pane__search">
          <Search :size="14" aria-hidden="true" />
          <input
            aria-label="路径关键词"
            type="search"
            :value="pathKeyword"
            @input="emit('updatePathKeyword', ($event.target as HTMLInputElement).value)"
          >
        </div>
      </label>

      <label class="studio-interface-context-pane__field">
        <span>摘要关键词</span>
        <div class="studio-interface-context-pane__search">
          <Search :size="14" aria-hidden="true" />
          <input
            aria-label="摘要关键词"
            type="search"
            :value="summaryKeyword"
            @input="emit('updateSummaryKeyword', ($event.target as HTMLInputElement).value)"
          >
        </div>
      </label>

      <label class="studio-interface-context-pane__field">
        <span>请求方法</span>
        <select aria-label="请求方法" :value="method" @change="emit('updateMethod', ($event.target as HTMLSelectElement).value as HttpMethod | 'ALL')">
          <option v-for="option in methodOptions" :key="option" :value="option">
            {{ option === 'ALL' ? '全部方法' : option }}
          </option>
        </select>
      </label>
    </section>

    <section class="studio-interface-context-pane__section">
      <header class="studio-interface-context-pane__header">
        <strong>生成配置</strong>
      </header>

      <label class="studio-interface-context-pane__field">
        <span>用例模板</span>
        <select aria-label="用例模板" :value="templateId" @change="emit('updateTemplateId', ($event.target as HTMLSelectElement).value)">
          <option v-for="option in templateOptions" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
      </label>

      <div class="studio-interface-context-pane__field">
        <span>生成粒度</span>
        <el-segmented
          class="studio-interface-context-pane__granularity"
          aria-label="生成粒度"
          :model-value="granularity"
          :options="granularityOptions"
          @update:model-value="emit('updateGranularity', $event as GenerationGranularity)"
        />
      </div>
    </section>
  </aside>
</template>

<style scoped>
.studio-interface-context-pane {
  display: grid;
  height: 100%;
  min-height: 0;
  gap: 16px;
  padding: 14px 12px;
  overflow: hidden;
  color: var(--text-regular);
  background: var(--surface-subtle);
  font-size: 12px;
}

.studio-interface-context-pane__section {
  display: grid;
  min-width: 0;
  gap: 8px;
}

.studio-interface-context-pane__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.studio-interface-context-pane__header {
  color: var(--text-secondary);
}

.studio-interface-context-pane__header strong {
  color: var(--text-regular);
  font-weight: 600;
}

.studio-interface-context-pane select,
.studio-interface-context-pane input {
  font: inherit;
}

.studio-interface-context-pane__field {
  display: grid;
  min-width: 0;
  gap: 5px;
}

.studio-interface-context-pane__field > span {
  color: var(--text-secondary);
}

.studio-interface-context-pane__field select,
.studio-interface-context-pane__search {
  width: 100%;
  min-width: 0;
  height: 32px;
  border: 1px solid var(--border-default);
  border-radius: 6px;
  background: var(--surface-raised);
}

.studio-interface-context-pane__field select {
  padding: 0 8px;
  color: var(--text-primary);
}

.studio-interface-context-pane__search {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 8px;
}

.studio-interface-context-pane__search:focus-within {
  border-color: var(--brand-primary-border);
  box-shadow: 0 0 0 2px var(--brand-primary-soft);
}

.studio-interface-context-pane__search input {
  width: 100%;
  min-width: 0;
  border: 0;
  outline: 0;
}

.studio-interface-context-pane__granularity {
  width: 100%;
  height: 34px;
  min-height: 0;
  max-height: 34px;
  box-sizing: border-box;
  align-self: start;
  padding: 2px;
  border-radius: var(--radius-control);
}

.studio-interface-context-pane__granularity :deep(.el-segmented__group) {
  width: 100%;
  height: 100%;
  min-height: 0;
  flex-wrap: nowrap;
}

.studio-interface-context-pane__granularity :deep(.el-segmented__item) {
  min-width: 0;
  min-height: 0;
  flex: 1 1 0;
  padding: 0 6px;
  font-size: var(--font-size-xs);
}
</style>

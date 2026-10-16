<script setup lang="ts">
import { computed } from 'vue'
import type { ParsedOperation } from '../../domain/openapiParser'

const props = withDefaults(defineProps<{
  operations: ParsedOperation[]
  selectedIds: string[]
  loading?: boolean
}>(), { loading: false })

const emit = defineEmits<{
  toggleOperation: [operation: ParsedOperation, checked: boolean]
  selectFiltered: []
  clearSelected: []
}>()

const selectedIdSet = computed(() => new Set(props.selectedIds))

const methodClass = (method: string) => `is-${method.toLowerCase()}`

const onToggleOperation = (operation: ParsedOperation, event: Event) => {
  emit('toggleOperation', operation, (event.target as HTMLInputElement).checked)
}
</script>

<template>
  <section class="interface-operation-table" data-testid="interface-operation-table">
    <header class="interface-operation-table__toolbar">
      <div class="interface-operation-table__summary">
        <strong>接口列表</strong>
        <span>{{ operations.length }} 个接口</span>
        <span>已选 {{ selectedIds.length }} 个</span>
      </div>

      <div class="interface-operation-table__commands">
        <button
          type="button"
          data-testid="clear-interface-operation-selection"
          :disabled="selectedIds.length === 0"
          @click="emit('clearSelected')"
        >
          清空选择
        </button>
        <button
          type="button"
          data-testid="select-filtered-interface-operations"
          :disabled="operations.length === 0"
          @click="emit('selectFiltered')"
        >
          全选当前结果
        </button>
      </div>
    </header>

    <el-table
      class="interface-operation-table__grid"
      :data="operations"
      :aria-busy="loading"
      row-key="id"
      height="100%"
    >
      <el-table-column label="" width="48" fixed="left">
        <template #header>
          <span class="studio-visually-hidden">选择接口</span>
        </template>
        <template #default="{ row }">
          <input
            :aria-label="`选择接口 ${row.summary}`"
            type="checkbox"
            :checked="selectedIdSet.has(row.id)"
            @change="onToggleOperation(row, $event)"
          >
        </template>
      </el-table-column>
      <el-table-column prop="method" label="请求方法" width="108">
        <template #default="{ row }">
          <span class="interface-operation-table__method" :class="methodClass(row.method)">
            {{ row.method }}
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="module" label="模块" min-width="120" show-overflow-tooltip />
      <el-table-column prop="summary" label="接口名称" min-width="220" show-overflow-tooltip />
      <el-table-column prop="path" label="请求路径" min-width="260" show-overflow-tooltip />
    </el-table>
  </section>
</template>

<style scoped>
.interface-operation-table {
  display: grid;
  grid-template-rows: 48px minmax(0, 1fr);
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  color: var(--text-regular);
  background: var(--surface-panel);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-control);
}

.interface-operation-table__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
  padding: 0 12px;
  color: var(--text-secondary);
  font-size: var(--font-size-sm);
}

.interface-operation-table__summary {
  display: flex;
  gap: 10px;
  min-width: 0;
  align-items: center;
}

.interface-operation-table__summary strong {
  color: var(--text-primary);
  font-weight: 600;
}

.interface-operation-table__commands {
  display: flex;
  gap: 8px;
  align-items: center;
}

.interface-operation-table__commands button {
  min-height: 30px;
  padding: 0 9px;
  color: var(--text-regular);
  font: inherit;
  background: var(--surface-raised);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-control);
  cursor: pointer;
}

.interface-operation-table__commands button:disabled {
  color: var(--text-placeholder);
  cursor: not-allowed;
}

.interface-operation-table__commands button:focus-visible,
.interface-operation-table__grid :deep(input[type='checkbox']:focus-visible) {
  outline: 3px solid var(--brand-primary-focus);
  outline-offset: 2px;
}

.interface-operation-table__grid {
  min-width: 760px;
  color: var(--text-regular);
  font-size: var(--font-size-sm);
}

.interface-operation-table__grid :deep(.el-table__cell) {
  min-width: 0;
}

.interface-operation-table__grid :deep(.el-table__cell .cell) {
  min-width: 0;
}

.interface-operation-table__method {
  display: inline-flex;
  min-width: 50px;
  height: 22px;
  align-items: center;
  justify-content: center;
  padding: 0 7px;
  color: var(--text-secondary);
  background: var(--surface-subtle);
  border: 1px solid var(--border-subtle);
  border-radius: 5px;
  font-size: 10px;
  font-weight: var(--font-weight-semibold);
}

.interface-operation-table__method.is-get,
.interface-operation-table__method.is-head {
  color: var(--accent-teal);
  background: var(--accent-teal-soft);
  border-color: var(--accent-teal-border);
}

.interface-operation-table__method.is-post {
  color: var(--brand-primary);
  background: var(--brand-primary-subtle);
  border-color: var(--brand-primary-border);
}

.interface-operation-table__method.is-put,
.interface-operation-table__method.is-patch {
  color: var(--accent-amber);
  background: var(--accent-amber-soft);
  border-color: var(--accent-amber-border);
}

.interface-operation-table__method.is-delete {
  color: var(--accent-rose);
  background: var(--accent-rose-soft);
  border-color: var(--accent-rose-border);
}
</style>

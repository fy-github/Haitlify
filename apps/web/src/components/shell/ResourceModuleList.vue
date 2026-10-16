<script setup lang="ts">
export interface ResourceModuleOption {
  value: string
  count: number
}

defineProps<{
  modelValue: string
  modules: ResourceModuleOption[]
  allCount: number
  allLabel?: string
}>()

const emit = defineEmits<{
  select: [value: string]
}>()
</script>

<template>
  <div class="resource-list resource-list-bordered resource-list-fill">
    <div class="resource-section-title">模块</div>
    <button
      class="resource-item"
      :class="{ active: modelValue === 'ALL' }"
      @click="emit('select', 'ALL')"
    >
      <el-tooltip :content="allLabel || '全部模块'" placement="top">
        <span class="resource-item-label">{{ allLabel || '全部模块' }}</span>
      </el-tooltip>
      <b>{{ allCount }}</b>
    </button>
    <button
      v-for="module in modules"
      :key="module.value"
      class="resource-item"
      :class="{ active: modelValue === module.value }"
      @click="emit('select', module.value)"
    >
      <el-tooltip :content="module.value" placement="top">
        <span class="resource-item-label">{{ module.value }}</span>
      </el-tooltip>
      <b>{{ module.count }}</b>
    </button>
  </div>
</template>

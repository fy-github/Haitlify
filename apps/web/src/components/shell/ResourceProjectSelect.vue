<script setup lang="ts">
export interface ResourceProjectOption {
  value: string
  label: string
  count?: number
}

defineProps<{
  modelValue: string
  options: ResourceProjectOption[]
  allLabel?: string
  allCount?: number
}>()

const emit = defineEmits<{
  select: [value: string]
}>()
</script>

<template>
  <div class="resource-select-block">
    <div class="resource-section-title">项目</div>
    <el-select
      :model-value="modelValue"
      class="resource-select"
      filterable
      @change="(value: string) => emit('select', value)"
    >
      <el-option v-if="allLabel" :label="`${allLabel}（${allCount ?? 0}）`" value="ALL" />
      <el-option
        v-for="option in options"
        :key="option.value"
        :label="option.count != null ? `${option.label}（${option.count}）` : option.label"
        :value="option.value"
      />
    </el-select>
  </div>
</template>

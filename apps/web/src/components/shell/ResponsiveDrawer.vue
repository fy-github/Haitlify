<script setup lang="ts">
import { X } from '@lucide/vue'

withDefaults(defineProps<{
  modelValue: boolean
  title: string
  description?: string
  size?: string
  direction?: 'ltr' | 'rtl' | 'ttb' | 'btt'
}>(), { size: '360px', direction: 'rtl' })

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    class="studio-responsive-drawer"
    :title="title"
    :size="size"
    :direction="direction"
    :aria-label="title"
    :with-header="false"
    append-to-body
    modal
    close-on-press-escape
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="studio-responsive-drawer__header">
      <div class="studio-responsive-drawer__heading">
        <strong>{{ title }}</strong>
        <span v-if="description">{{ description }}</span>
      </div>
      <button type="button" :aria-label="`关闭${title}`" @click="emit('update:modelValue', false)">
        <X :size="16" aria-hidden="true" />
      </button>
    </div>
    <slot />
  </el-drawer>
</template>

<style>
.studio-responsive-drawer__header {
  display: flex;
  min-height: 52px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: -16px -20px 14px;
  padding: 0 16px 0 20px;
  color: var(--text-primary);
  background: var(--surface-panel);
  border-bottom: 1px solid var(--border-subtle);
}

.studio-responsive-drawer__heading {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.studio-responsive-drawer__header strong {
  font-size: 14px;
  font-weight: 600;
  line-height: 20px;
}

.studio-responsive-drawer__header span {
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.45;
}

.studio-responsive-drawer__header button {
  display: inline-grid;
  width: 32px;
  height: 32px;
  padding: 0;
  place-items: center;
  color: var(--text-regular);
  background: transparent;
  border: 0;
  border-radius: var(--radius-sm);
  cursor: pointer;
}

.studio-responsive-drawer__header button:hover {
  color: var(--brand-primary);
  background: var(--brand-primary-subtle);
}

.studio-responsive-drawer__header button:focus-visible {
  outline: 3px solid var(--brand-primary-focus);
  outline-offset: 1px;
}
</style>
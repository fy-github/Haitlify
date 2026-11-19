<script setup lang="ts">
/** One editor/form, two presentation surfaces. No cloned draft or duplicate handlers. */
defineProps<{ modelValue: boolean; inline: boolean; title: string; web: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
</script>

<template>
  <Teleport v-if="inline" defer to="#environment-inline-editor-host">
    <section class="environment-inline-surface environment-control-surface" :class="{ 'is-web-environment': web }" data-testid="environment-inline-editor" :aria-label="title">
      <slot />
    </section>
  </Teleport>
  <el-drawer v-else :model-value="modelValue" class="web-automation-drawer environment-control-surface" :class="{ 'is-web-environment': web }" size="860px" :title="title" @update:model-value="emit('update:modelValue', $event)">
    <slot />
  </el-drawer>
</template>

<style>
.environment-inline-surface { min-width: 0; height: auto; }
.environment-inline-surface > .environment-drawer { display: block; height: auto; min-height: 0; }
.environment-inline-surface .environment-list { display: none; }
.environment-inline-surface .environment-editor {
  grid-template-columns: minmax(0, 1fr);
  max-height: none;
  overflow: visible;
  padding: 18px 24px;
}
.environment-inline-surface .environment-drawer-actions { position: sticky; bottom: 0; background: #fff; padding: 12px 0; }
.environment-inline-surface:has(.web-logout-step-row) .environment-drawer-actions { position: static; margin-top: 12px; }
@media (max-width: 640px) { .environment-inline-surface .environment-editor { padding: 12px; } }
</style>

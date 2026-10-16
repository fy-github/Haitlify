<script setup lang="ts">
withDefaults(defineProps<{
  sidebarCollapsed: boolean
  sidebarVisible?: boolean
  inspectorVisible: boolean
}>(), { sidebarVisible: true })
</script>

<template>
  <div
    class="studio-app-shell"
    :class="{
      'is-sidebar-collapsed': sidebarCollapsed,
      'has-inspector': inspectorVisible
    }"
  >
    <div v-if="sidebarVisible" class="studio-app-shell__sidebar">
      <slot name="sidebar" />
    </div>

    <div class="studio-app-shell__body">
      <div class="studio-app-shell__topbar">
        <slot name="topbar" />
      </div>

      <div class="studio-app-shell__workspace">
        <div class="studio-app-shell__canvas">
          <slot />
        </div>
        <div v-if="inspectorVisible" class="studio-app-shell__inspector">
          <slot name="inspector" />
        </div>
      </div>
    </div>
  </div>
</template>

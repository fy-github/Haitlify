<script setup lang="ts">
import type { NavKey } from '../../domain/appNavigation'

const props = defineProps<{
  activeNav: NavKey
}>()
</script>

<template>
  <section class="workbench" aria-label="用例工作台">
    <div class="workbench__content">
      <div v-if="activeNav === 'cases'" class="workbench__pane">
        <slot name="library" />
      </div>
      <div v-else-if="activeNav === 'api'" class="workbench__pane workbench__pane--full">
        <slot name="canvas" />
      </div>
      <div v-else class="workbench__pane workbench__pane--full">
        <div class="workbench__placeholder">
          <strong>从左侧导航选择用例分组</strong>
          <span>用例库与智能用例设计同台联动</span>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import type { NavKey } from '../../domain/appNavigation'
import DefectsView from './DefectsView.vue'
import ReportsView from './ReportsView.vue'

const props = defineProps<{
  activeNav: NavKey
}>()
</script>

<template>
  <section class="workbench" aria-label="质量工作台">
    <div class="workbench__content">
      <div v-if="activeNav === 'reports'" class="workbench__pane">
        <ReportsView />
      </div>
      <div v-else-if="activeNav === 'defects'" class="workbench__pane">
        <DefectsView />
      </div>
      <div v-else class="workbench__pane workbench__pane--full">
        <div class="workbench__placeholder">
          <strong>从左侧导航选择质量分组</strong>
          <span>执行报告与缺陷管理同台联动</span>
        </div>
      </div>
    </div>
  </section>
</template>

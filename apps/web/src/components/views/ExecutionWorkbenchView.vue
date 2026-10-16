<script setup lang="ts">
import type { NavKey } from '../../domain/appNavigation'

const props = defineProps<{
  activeNav: NavKey
  /** API/Web 子视图切换依据;两项导航同 navKey('automation'),靠 kind 区分激活与内容 */
  automationKind?: 'api' | 'web'
}>()
</script>

<template>
  <section class="workbench" aria-label="执行工作台">
    <div class="workbench__content">
      <!-- API 自动化自带 topbar + 工作区,走标准顶栏网格契约 -->
      <div v-if="automationKind === 'api'" class="workbench__pane workbench__pane--api">
        <slot name="api" />
      </div>
      <!-- Web 自动化(WebAutomationWorkspace)自管全高布局,不套顶栏网格 -->
      <div v-else class="workbench__pane workbench__pane--full">
        <slot name="web" />
      </div>
    </div>
  </section>
</template>

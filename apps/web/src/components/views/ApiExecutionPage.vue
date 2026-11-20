<script setup lang="ts">
import type { NavKey } from '../../domain/appNavigation'

defineProps<{
  activeNav: NavKey
  mode: 'prepare' | 'monitor'
}>()
</script>

<template>
  <section class="api-execution-page" :data-mode="mode" aria-label="API 自动化执行">
    <slot name="monitor" />
    <slot name="preparation" />
  </section>
</template>

<script setup lang="ts">
import { TriangleAlert, Inbox, LoaderCircle, SearchX, WifiOff } from '@lucide/vue'
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  variant: 'loading' | 'empty' | 'filtered-empty' | 'error' | 'partial' | 'offline'
  title: string
  description: string
  actionLabel?: string
  progress?: number
}>(), { actionLabel: '', progress: 0 })

const emit = defineEmits<{ action: [] }>()

const icon = computed(() => ({
  loading: LoaderCircle,
  empty: Inbox,
  'filtered-empty': SearchX,
  error: TriangleAlert,
  partial: TriangleAlert,
  offline: WifiOff
}[props.variant]))
const role = computed(() => props.variant === 'error' ? 'alert' : 'status')
</script>

<template>
  <section class="studio-workflow-state" :class="`is-${variant}`" :role="role">
    <component :is="icon" :size="24" aria-hidden="true" />
    <strong>{{ title }}</strong>
    <p>{{ description }}</p>
    <el-progress v-if="variant === 'loading'" :percentage="progress" />
    <button v-if="actionLabel" type="button" class="studio-workflow-state__action" @click="emit('action')">{{ actionLabel }}</button>
  </section>
</template>

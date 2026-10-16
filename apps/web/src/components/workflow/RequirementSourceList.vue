<script setup lang="ts">
import { RefreshCcw, Trash2 } from '@lucide/vue'
import type { CaseDesignSourceView } from '../../domain/caseDesignSourceView'

defineProps<{ sources: CaseDesignSourceView[] }>()
const emit = defineEmits<{
  retry: [name: string]
  replace: [name: string]
  remove: [name: string]
}>()
</script>

<template>
  <aside class="studio-import-stage__sources" aria-label="本次任务来源">
    <header><strong>本次任务来源</strong><span>{{ sources.length }}</span></header>
    <div v-if="sources.length" class="studio-source-list">
      <article v-for="source in sources" :key="source.name" class="studio-source-item" :class="`is-${source.status}`">
        <span class="studio-source-item__kind">{{ source.kindLabel }}</span>
        <div><strong>{{ source.name }}</strong><span>{{ source.detail }}</span></div>
        <div class="studio-source-item__actions">
          <button v-if="source.status !== 'ready'" type="button" :data-testid="`retry-source-${source.name}`" :aria-label="`重试 ${source.name}`" @click="emit('retry', source.name)"><RefreshCcw :size="14" aria-hidden="true" /></button>
          <button v-if="source.status !== 'ready'" type="button" :data-testid="`replace-source-${source.name}`" :aria-label="`替换 ${source.name}`" @click="emit('replace', source.name)">替换</button>
          <button type="button" :data-testid="`remove-source-${source.name}`" :aria-label="`移除 ${source.name}`" @click="emit('remove', source.name)"><Trash2 :size="14" aria-hidden="true" /></button>
        </div>
      </article>
    </div>
    <div v-else class="studio-import-stage__source-empty">还没有添加需求来源</div>
  </aside>
</template>

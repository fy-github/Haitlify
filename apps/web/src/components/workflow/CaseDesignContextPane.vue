<script setup lang="ts">
import type { CaseDesignSourceView } from '../../domain/caseDesignSourceView'

defineProps<{
  sources: CaseDesignSourceView[]
  modules: Array<{ value: string; count: number }>
  activeModule: string
  activeSource: string
}>()

const emit = defineEmits<{
  selectSource: [source: string]
  selectModule: [module: string]
}>()
</script>

<template>
  <aside class="studio-case-context" aria-label="需求与模块上下文">
    <section>
      <header><strong>需求来源</strong><span>{{ sources.length }}</span></header>
      <div class="studio-case-context__sources">
        <button
          v-for="source in sources"
          :key="source.name"
          type="button"
          :data-source="source.name"
          :class="[`is-${source.status}`, { 'is-active': activeSource === source.name }]"
          :aria-pressed="activeSource === source.name"
          @click="emit('selectSource', source.name)"
        >
          <span>{{ source.kindLabel }}</span>
          <div><strong>{{ source.name }}</strong><small>{{ source.detail }}</small></div>
        </button>
      </div>
    </section>

    <section>
      <header><strong>模块</strong><span>{{ modules.length }}</span></header>
      <button type="button" data-module="ALL" :class="{ 'is-active': activeModule === 'ALL' }" :aria-pressed="activeModule === 'ALL'" @click="emit('selectModule', 'ALL')">
        <span>全部模块</span><b>{{ modules.reduce((total, module) => total + module.count, 0) }}</b>
      </button>
      <button v-for="module in modules" :key="module.value" type="button" :data-module="module.value" :class="{ 'is-active': activeModule === module.value }" :aria-pressed="activeModule === module.value" @click="emit('selectModule', module.value)">
        <span>{{ module.value }}</span><b>{{ module.count }}</b>
      </button>
    </section>
  </aside>
</template>

<style scoped>
.studio-case-context { height: 100%; padding: 13px 10px; overflow: auto; color: var(--text-secondary); background: var(--surface-subtle); font-size: 12px; }.studio-case-context section + section { margin-top: 20px; }.studio-case-context section > header { display: flex; align-items: center; justify-content: space-between; padding: 0 5px 7px; color: var(--text-secondary); border-bottom: 1px solid var(--surface-page); font-size: 11px; }.studio-case-context section > header strong { color: var(--text-regular); font-weight: 600; }.studio-case-context section > header span { color: var(--text-tertiary); }
.studio-case-context button { display: flex; width: 100%; min-width: 0; align-items: center; justify-content: space-between; gap: 7px; margin-top: 5px; padding: 8px; color: var(--text-tertiary); text-align: left; background: transparent; border: 1px solid transparent; border-radius: 6px; cursor: pointer; }.studio-case-context button:hover { background: var(--brand-primary-subtle); }.studio-case-context button.is-active { color: var(--brand-primary-active); background: linear-gradient(90deg, rgba(15, 118, 110, 0.08), rgba(255, 255, 255, 0.92)); border-color: rgba(124, 190, 178, 0.68); box-shadow: 0 6px 16px rgba(15, 118, 110, 0.05); }.studio-case-context button > span:first-child { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.studio-case-context button b { color: inherit; font-size: 10px; font-weight: 600; }
.studio-case-context__sources button { display: grid; grid-template-columns: 30px minmax(0, 1fr); justify-content: stretch; }.studio-case-context__sources button > span { display: grid; width: 28px; height: 24px; place-items: center; color: var(--brand-primary); background: var(--brand-primary-soft); border-radius: 5px; font-size: 9px; }.studio-case-context__sources button > div { display: grid; min-width: 0; gap: 2px; }.studio-case-context__sources strong, .studio-case-context__sources small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.studio-case-context__sources strong { color: var(--text-regular); font-size: 11px; font-weight: 600; }.studio-case-context__sources small { color: var(--text-secondary); font-size: 10px; }.studio-case-context__sources button.is-error > span { color: var(--color-danger); background: var(--surface-page); }
</style>

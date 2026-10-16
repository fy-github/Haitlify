<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

type ScopeOption = { value: string; label: string; count: number }

const props = defineProps<{
  project: string
  projects: ScopeOption[]
  module: string
  modules: ScopeOption[]
}>()

const emit = defineEmits<{
  updateProject: [value: string]
  updateModule: [value: string]
}>()

const moduleLabelElements = new Map<string, HTMLElement>()
const overflowingModules = ref(new Set<string>())
const moduleListElement = ref<HTMLElement>()
let moduleResizeObserver: ResizeObserver | undefined

const setModuleLabel = (value: string, element: unknown) => {
  if (element instanceof HTMLElement) moduleLabelElements.set(value, element)
  else moduleLabelElements.delete(value)
}

const measureModuleOverflow = () => {
  overflowingModules.value = new Set(
    [...moduleLabelElements].filter(([, element]) => element.scrollWidth > element.clientWidth).map(([value]) => value)
  )
}

const scheduleOverflowMeasurement = () => {
  void nextTick().then(() => {
    measureModuleOverflow()
  })
}

watch(
  () => props.modules.map(({ value, label, count }) => `${value}\u0000${label}\u0000${count}`).join('\u0001'),
  scheduleOverflowMeasurement
)

onMounted(() => {
  window.addEventListener('resize', measureModuleOverflow)
  if (typeof ResizeObserver !== 'undefined') {
    moduleResizeObserver = new ResizeObserver(scheduleOverflowMeasurement)
    if (moduleListElement.value) moduleResizeObserver.observe(moduleListElement.value)
  }
  scheduleOverflowMeasurement()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', measureModuleOverflow)
  moduleResizeObserver?.disconnect()
})
</script>

<template>
  <section class="web-automation-scope-pane">
    <h2>执行范围</h2>
    <div class="web-automation-scope-pane__project">
      <span>项目</span>
      <el-select
        :model-value="project"
        aria-label="Web 自动化项目"
        popper-class="web-automation-popper"
        @update:model-value="emit('updateProject', $event)"
      >
        <el-option
          v-for="option in projects"
          :key="option.value"
          :value="option.value"
          :label="option.label"
        />
      </el-select>
    </div>
    <div class="web-automation-scope-pane__modules" role="group" aria-label="Web 自动化模块选择">
      <span>模块</span>
      <div ref="moduleListElement">
        <el-tooltip
          v-for="option in modules"
          :key="option.value"
          :content="option.label"
          :disabled="!overflowingModules.has(option.value)"
        >
          <button
            type="button"
            :data-testid="`web-scope-module-${option.value}`"
            :aria-current="module === option.value ? 'true' : undefined"
            @click="emit('updateModule', option.value)"
          >
            <span :ref="(element) => setModuleLabel(option.value, element)">{{ option.label }}</span>
          </button>
        </el-tooltip>
      </div>
    </div>
  </section>
</template>

<style scoped>
.web-automation-scope-pane {
  display: grid;
  grid-template-rows: auto auto auto;
  align-content: start;
  gap: 14px;
  min-width: 0;
  min-height: 0;
  height: auto;
  padding: 16px 14px 14px;
  color: var(--text-regular);
  background: var(--surface-raised);
  border: 1px solid var(--border-regular);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-soft);
}

h2 { margin: 0; color: var(--text-primary); font-size: 12px; font-weight: 600; letter-spacing: -0.005em; line-height: 20px; }
.web-automation-scope-pane__project,
.web-automation-scope-pane__modules { display: grid; gap: 7px; color: var(--text-secondary); font-size: var(--font-size-xs); font-weight: 500; line-height: 16px; }
.web-automation-scope-pane__modules { min-height: 0; grid-template-rows: auto auto; }
.web-automation-scope-pane__project,
.web-automation-scope-pane__modules > div {
  padding: 0;
  background: transparent;
  border: 0;
}
.web-automation-scope-pane__modules > div { display: grid; min-height: 0; max-height: min(44vh, 420px); align-content: start; gap: 6px; overflow-y: auto; }
.web-automation-scope-pane__modules button {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  width: 100%;
  min-height: 30px;
  align-items: center;
  gap: 0;
  padding: 0 10px;
  color: var(--text-regular);
  text-align: left;
  background: rgba(255, 255, 255, 0.84);
  border: 1px solid rgba(210, 226, 221, 0.8);
  border-radius: 11px;
  cursor: pointer;
  font-size: 12px;
  font-weight: 400;
  line-height: 18px;
}
.web-automation-scope-pane__modules button span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.web-automation-scope-pane__modules button:hover { color: var(--brand-primary); background: var(--surface-raised); border-color: rgba(124, 190, 178, 0.8); box-shadow: 0 8px 20px rgba(15, 118, 110, 0.07); }
.web-automation-scope-pane__modules button[aria-current='true'] { color: var(--text-regular); background: linear-gradient(90deg, rgba(15, 118, 110, 0.08), rgba(244, 250, 248, 0.92)); border-color: rgba(210, 226, 221, 0.8); box-shadow: none; }
.web-automation-scope-pane :deep(.el-select__selected-item) { font-size: 12px; font-weight: 400; }
.web-automation-scope-pane :deep(.el-select__wrapper) { min-height: 30px; padding-inline: 9px; border-radius: var(--radius-md); background: rgba(255, 255, 255, 0.92); box-shadow: 0 0 0 1px rgba(183, 210, 202, 0.78) inset; }
button:focus-visible { outline: 3px solid var(--brand-primary-focus); outline-offset: 2px; }
</style>

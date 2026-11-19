<script setup lang="ts">
import { ref, watch } from 'vue'
import type { WebRunBrowserOption } from '../../domain/webAutomationRunView'

/**
 * Web 运行参数表单：只在 740px 右侧「运行配置」抽屉内使用。
 * 定稿把运行环境 / 执行浏览器 / 循环次数收进抽屉，页面上不再有内联参数条与执行按钮。
 * 循环次数用 − / 输入 / ＋ 步进：非法输入不静默归一化，而是留在输入框并给出错误。
 */
const props = defineProps<{
  environmentId: string
  /** baseUrl 供运行抽屉的只读「环境地址」按草稿所选环境展示,不是已应用环境的地址。 */
  environments: Array<{ value: string; label: string; baseUrl?: string }>
  browser: string
  browsers: WebRunBrowserOption[]
  loopCount: number
}>()

const emit = defineEmits<{
  updateEnvironmentId: [value: string]
  updateBrowser: [value: string]
  updateLoopCount: [value: number]
  loopCountValidity: [valid: boolean]
}>()

const MIN_LOOP_COUNT = 1
const MAX_LOOP_COUNT = 20
const LOOP_ERROR = '循环次数需为 1–20 的整数'

const loopText = ref(String(props.loopCount))
const loopError = ref('')

const parseLoopCount = (raw: string): number | undefined => {
  const trimmed = raw.trim()
  if (!/^\d+$/.test(trimmed)) return undefined
  const value = Number(trimmed)
  return value >= MIN_LOOP_COUNT && value <= MAX_LOOP_COUNT ? value : undefined
}

// 同一值重复输入只发一次意图，避免同一循环次数被反复提交。
let lastEmittedLoop: number | undefined
const emitLoop = (value: number) => {
  if (value === props.loopCount || value === lastEmittedLoop) return
  lastEmittedLoop = value
  emit('updateLoopCount', value)
}

// 应用后的值回写输入框；正在编辑的非法文本（解析结果不等于 prop）不会被覆盖。
watch(() => props.loopCount, (value) => {
  lastEmittedLoop = undefined
  if (parseLoopCount(loopText.value) !== value) loopText.value = String(value)
})

const onLoopInput = (event: Event) => {
  const raw = (event.target as HTMLInputElement).value
  loopText.value = raw
  const value = parseLoopCount(raw)
  if (value === undefined) {
    loopError.value = LOOP_ERROR
    emit('loopCountValidity', false)
    return
  }
  loopError.value = ''
  emit('loopCountValidity', true)
  emitLoop(value)
}

const stepLoop = (delta: number) => {
  const next = Math.min(MAX_LOOP_COUNT, Math.max(MIN_LOOP_COUNT, props.loopCount + delta))
  loopText.value = String(next)
  loopError.value = ''
  emit('loopCountValidity', true)
  emitLoop(next)
}
</script>

<template>
  <div class="web-ed-runtime-form" aria-label="Web 运行参数">
    <label class="web-ed-field">
      <span>运行环境</span>
      <el-select
        :model-value="environmentId"
        aria-label="Web 执行环境"
        popper-class="web-automation-popper"
        @update:model-value="(value: string) => emit('updateEnvironmentId', value)"
      >
        <el-option v-for="environment in environments" :key="environment.value" :value="environment.value" :label="environment.label" />
      </el-select>
    </label>

    <label class="web-ed-field">
      <span>执行浏览器</span>
      <el-select
        :model-value="browser"
        aria-label="浏览器选择"
        popper-class="web-automation-popper"
        @update:model-value="(value: string) => emit('updateBrowser', value)"
      >
        <el-option
          v-for="option in browsers"
          :key="option.value"
          :value="option.value"
          :label="option.label"
          :disabled="option.disabled"
          :data-browser-value="option.value"
          :aria-disabled="option.disabled"
          :title="option.recommendation || option.label"
        >
          <span>{{ option.label }}</span>
          <small v-if="option.recommendation">{{ option.recommendation }}</small>
        </el-option>
      </el-select>
    </label>

    <div class="web-ed-field">
      <span>循环次数</span>
      <div class="web-ed-stepper">
        <button
          type="button"
          data-testid="loop-decrease"
          aria-label="减少循环次数"
          :disabled="loopCount <= MIN_LOOP_COUNT"
          @click="stepLoop(-1)"
        >−</button>
        <input
          data-testid="loop-input"
          aria-label="循环次数"
          type="text"
          inputmode="numeric"
          autocomplete="off"
          :value="loopText"
          :aria-invalid="!!loopError"
          :aria-describedby="loopError ? 'web-ed-loop-error' : undefined"
          @input="onLoopInput"
        >
        <button
          type="button"
          data-testid="loop-increase"
          aria-label="增加循环次数"
          :disabled="loopCount >= MAX_LOOP_COUNT"
          @click="stepLoop(1)"
        >＋</button>
      </div>
      <small v-if="loopError" id="web-ed-loop-error" class="web-ed-stepper__error" data-testid="loop-error" role="alert">
        {{ loopError }}
      </small>
    </div>
  </div>
</template>

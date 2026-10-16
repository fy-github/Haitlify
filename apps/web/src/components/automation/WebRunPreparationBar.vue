<script setup lang="ts">
import { computed } from 'vue'
import { Settings } from '@lucide/vue'
import type { WebRunBrowserOption } from '../../domain/webAutomationRunView'

const props = withDefaults(defineProps<{
  environmentId: string
  environments: Array<{ value: string; label: string }>
  browser: string
  browsers: WebRunBrowserOption[]
  loopCount: number
  loginReadyLabel: string
  actionLabel: string
  canExecute: boolean
  blockingReason: string
  compact?: boolean
  view?: 'launcher' | 'form'
}>(), {
  compact: false,
  view: 'form'
})

const emit = defineEmits<{
  updateEnvironmentId: [value: string]
  updateBrowser: [value: string]
  updateLoopCount: [value: number]
  editEnvironment: []
  execute: []
}>()

let pendingLoopCount: number | undefined
let loopEmitQueued = false

const selectedEnvironmentLabel = computed(() =>
  props.environments.find((environment) => environment.value === props.environmentId)?.label || '未选择'
)

const selectedBrowserLabel = computed(() =>
  props.browsers.find((option) => option.value === props.browser)?.label || '未选择'
)


const updateLoopCount = (value: number | undefined) => {
  const numericValue = Number.isFinite(Number(value)) ? Number(value) : 1
  pendingLoopCount = Math.min(20, Math.max(1, numericValue))
  if (loopEmitQueued) return
  loopEmitQueued = true
  queueMicrotask(() => {
    const nextLoopCount = pendingLoopCount
    pendingLoopCount = undefined
    loopEmitQueued = false
    if (nextLoopCount !== undefined) emit('updateLoopCount', nextLoopCount)
  })
}

const execute = () => {
  if (props.canExecute) emit('execute')
}
</script>

<template>
  <section
    class="web-run-preparation"
    :class="{ 'web-run-preparation--launcher': view === 'launcher', 'web-run-preparation--form': view === 'form', 'is-compact': compact }"
    aria-label="Web 执行准备"
  >
    <template v-if="view === 'launcher'">
      <div class="web-run-preparation__launcher">
        <div class="web-run-preparation__launcher-summary">
          <span>运行环境</span>
          <strong>{{ selectedEnvironmentLabel }}</strong>
          <small>浏览器 {{ selectedBrowserLabel }} · 重复 {{ loopCount }} 次</small>
        </div>
        <div class="web-run-preparation__launcher-controls">
          <div class="web-run-preparation__controls web-run-preparation__controls--launcher">
            <label class="web-run-preparation__field web-run-preparation__field--environment">
              <span>执行环境</span>
              <el-select
                :model-value="environmentId"
                aria-label="Web 执行环境"
                popper-class="web-automation-popper"
                @update:model-value="(value: string) => emit('updateEnvironmentId', value)"
              >
                <el-option v-for="environment in environments" :key="environment.value" :value="environment.value" :label="environment.label" />
              </el-select>
            </label>

            <el-tooltip content="编辑 Web 执行环境" placement="top">
              <button type="button" class="web-run-preparation__icon-button" aria-label="编辑 Web 执行环境" title="编辑 Web 执行环境" @click="emit('editEnvironment')">
                <Settings :size="16" aria-hidden="true" />
              </button>
            </el-tooltip>

            <label class="web-run-preparation__field web-run-preparation__field--browser">
              <span>浏览器</span>
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

            <label class="web-run-preparation__field web-run-preparation__field--count">
              <span>重复次数</span>
              <el-input-number
                :model-value="loopCount"
                :min="1"
                :max="20"
                controls-position="right"
                aria-label="重复次数"
                @update:model-value="updateLoopCount"
              />
            </label>

          </div>
          <div class="web-run-preparation__execute">
            <el-button type="primary" data-testid="execute-web-automation" :disabled="!canExecute" @click="execute">
              {{ actionLabel }}
            </el-button>
            <p data-testid="web-execution-blocking-reason" aria-live="polite" aria-atomic="true">{{ blockingReason || '\u00a0' }}</p>
          </div>
        </div>
      </div>
    </template>

    <template v-else>
      <div class="web-run-preparation__controls">
        <label class="web-run-preparation__field web-run-preparation__field--environment">
          <span>执行环境</span>
          <el-select
            :model-value="environmentId"
            aria-label="Web 执行环境"
            popper-class="web-automation-popper"
            @update:model-value="(value: string) => emit('updateEnvironmentId', value)"
          >
            <el-option v-for="environment in environments" :key="environment.value" :value="environment.value" :label="environment.label" />
          </el-select>
        </label>

        <el-tooltip content="编辑 Web 执行环境" placement="top">
          <button type="button" class="web-run-preparation__icon-button" aria-label="编辑 Web 执行环境" title="编辑 Web 执行环境" @click="emit('editEnvironment')">
            <Settings :size="16" aria-hidden="true" />
          </button>
        </el-tooltip>

        <label class="web-run-preparation__field web-run-preparation__field--browser">
          <span>浏览器</span>
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

        <label class="web-run-preparation__field web-run-preparation__field--count">
          <span>重复次数</span>
          <el-input-number
            :model-value="loopCount"
            :min="1"
            :max="20"
            controls-position="right"
            aria-label="重复次数"
            @update:model-value="updateLoopCount"
          />
        </label>

      </div>

      <div class="web-run-preparation__actions">
        <div class="web-run-preparation__execute">
          <el-button type="primary" data-testid="execute-web-automation" :disabled="!canExecute" @click="execute">
            {{ actionLabel }}
          </el-button>
          <p data-testid="web-execution-blocking-reason" aria-live="polite" aria-atomic="true">{{ blockingReason || '\u00a0' }}</p>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.web-run-preparation {
  display: flex;
  min-height: 64px;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 10px 14px;
  background: var(--surface-raised);
  border: 1px solid var(--border-regular);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-soft);
}

.web-run-preparation--launcher {
  min-height: 0;
  padding-block: 9px;
}

.web-run-preparation__launcher {
  display: grid;
  width: 100%;
  min-width: 0;
  grid-template-columns: minmax(0, 1fr) minmax(620px, 1fr);
  grid-template-areas: 'summary controls';
  gap: 10px 14px;
  align-items: center;
}

.web-run-preparation__launcher-summary,
.web-run-preparation__launcher-controls {
  min-width: 0;
}

.web-run-preparation__launcher-summary {
  grid-area: summary;
}

.web-run-preparation__launcher-summary span {
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.3;
}

.web-run-preparation__launcher-summary strong {
  color: var(--text-primary);
  font-size: 12px;
  font-weight: 600;
  line-height: 1.35;
}

.web-run-preparation--launcher .web-run-preparation__launcher-summary {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: 8px;
}

.web-run-preparation--launcher .web-run-preparation__launcher-summary span,
.web-run-preparation--launcher .web-run-preparation__launcher-summary strong,
.web-run-preparation--launcher .web-run-preparation__launcher-summary small {
  white-space: nowrap;
}

.web-run-preparation__launcher-summary small {
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.35;
}

.web-run-preparation__launcher-controls {
  grid-area: controls;
  display: grid;
  align-items: end;
  justify-self: stretch;
  width: 100%;
  gap: 8px;
  grid-template-columns: minmax(0, 1fr) minmax(112px, 112px);
}

.web-run-preparation__controls,
.web-run-preparation__actions {
  display: flex;
  align-items: flex-end;
  gap: 8px;
}

.web-run-preparation__controls {
  min-width: 0;
  flex: 1 1 auto;
  flex-wrap: wrap;
}

.web-run-preparation__controls.web-run-preparation__controls--launcher {
  display: grid;
  width: 100%;
  min-width: 0;
  flex: none;
  align-items: end;
  gap: 8px;
  grid-template-columns: minmax(220px, 220px) 32px minmax(144px, 144px) minmax(86px, 86px);
}

.web-run-preparation--launcher .web-run-preparation__launcher-controls {
  width: 100%;
  justify-self: stretch;
  grid-template-columns: minmax(0, 1fr) minmax(112px, 112px);
}

.web-run-preparation__field {
  display: grid;
  min-width: 132px;
  gap: 5px;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 500;
  line-height: 14px;
}

.web-run-preparation__field--environment { min-width: 0; max-width: none; }
.web-run-preparation__field--browser { min-width: 0; max-width: none; }
.web-run-preparation__field--count { min-width: 0; max-width: none; }
.web-run-preparation__field :deep(.el-select),
.web-run-preparation__field :deep(.el-input-number) { width: 100%; }
.web-run-preparation__field :deep(.el-select__wrapper),
.web-run-preparation__field :deep(.el-input__wrapper) {
  min-height: 30px;
  padding-inline: 9px;
  border-radius: var(--radius-md);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 0 0 1px rgba(183, 210, 202, 0.78) inset;
}
.web-run-preparation__field--count :deep(.el-input-number) {
  width: 100%;
  min-height: 30px;
}
.web-run-preparation__field--count :deep(.el-input-number .el-input__wrapper) {
  min-height: 30px;
  padding-inline: 9px;
  border-radius: var(--radius-md);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 0 0 1px rgba(183, 210, 202, 0.78) inset;
}
.web-run-preparation__field :deep(.el-select__selected-item),
.web-run-preparation__field :deep(.el-input__inner) { font-size: 12px; font-weight: 400; }
.web-run-preparation__icon-button {
  display: grid;
  width: 30px;
  height: 30px;
  flex: none;
  place-items: center;
  padding: 0;
  color: var(--text-secondary);
  background: rgba(255, 255, 255, 0.84);
  border: 1px solid rgba(183, 210, 202, 0.82);
  border-radius: 11px;
  box-shadow: none;
  cursor: pointer;
}
.web-run-preparation__icon-button:hover { color: var(--brand-primary); background: var(--surface-raised); border-color: rgba(124, 190, 178, 0.8); box-shadow: 0 8px 20px rgba(15, 118, 110, 0.07); }
.web-run-preparation__icon-button:focus-visible { outline: 2px solid var(--brand-primary); outline-offset: 2px; }
.web-run-preparation__readiness {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  min-height: 0;
  padding: 0;
  color: var(--color-success);
  font-size: var(--font-size-xs);
  font-weight: 500;
  white-space: nowrap;
  background: transparent;
  border: 0;
}
.web-run-preparation__actions { flex: none; align-self: end; padding-top: 0; }
.web-run-preparation__actions .el-button { min-height: 30px; margin: 0; padding-inline: 10px; }
.web-run-preparation__actions .el-button :deep(span) { gap: 5px; }
.web-run-preparation__execute {
  position: relative;
  display: grid;
  width: 112px;
  min-width: 112px;
}
.web-run-preparation__execute .el-button--primary {
  width: 112px;
  min-width: 112px;
  min-height: 30px;
  padding-inline: 10px;
  background: linear-gradient(180deg, var(--brand-primary), var(--brand-primary-active));
  border-color: var(--brand-primary);
  border-radius: 11px;
  box-shadow: 0 10px 22px rgba(15, 118, 110, 0.18);
  font-size: 12px;
  font-weight: 600;
}
.web-run-preparation__execute .el-button--primary:not(.is-disabled):hover { box-shadow: 0 10px 22px rgba(15, 118, 110, 0.18); }
.web-run-preparation__execute p {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: -1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  clip-path: inset(50%);
  white-space: nowrap;
}

.web-run-preparation--form {
  display: grid;
  min-height: 0;
  gap: 12px;
  align-items: start;
}

.web-run-preparation--form .web-run-preparation__controls {
  display: grid;
  width: 100%;
  min-width: 0;
  grid-template-columns: minmax(168px, 1.25fr) 34px minmax(132px, 1fr) minmax(100px, 106px) minmax(132px, auto);
  align-items: end;
  gap: 10px;
  flex: none;
}

.web-run-preparation--form .web-run-preparation__field {
  min-width: 0;
  max-width: none;
}

.web-run-preparation--form .web-run-preparation__field--environment,
.web-run-preparation--form .web-run-preparation__field--browser,
.web-run-preparation--form .web-run-preparation__field--count {
  flex: none;
}

.web-run-preparation--form .web-run-preparation__readiness {
  min-width: 0;
  justify-self: start;
}

.web-run-preparation--form .web-run-preparation__actions {
  width: 100%;
  justify-content: flex-end;
}

.web-run-preparation--form .web-run-preparation__execute {
  width: 132px;
  min-width: 132px;
}

.web-run-preparation--form .web-run-preparation__execute .el-button--primary {
  width: 132px;
  min-width: 132px;
}

.web-run-preparation__field--count :deep(.el-input-number:focus-within),
.web-run-preparation__field--count :deep(.el-input-number .el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px rgba(183, 210, 202, 0.82) inset !important;
  outline: none !important;
}

.web-run-preparation__field--count :deep(.el-input-number:hover) {
  box-shadow:
    0 0 0 1px rgba(111, 170, 159, 0.74) inset,
    0 8px 20px rgba(15, 118, 110, 0.07);
}

.web-run-preparation__field--count :deep(.el-input-number__decrease),
.web-run-preparation__field--count :deep(.el-input-number__increase) {
  box-shadow: none;
}

@media (max-width: 920px) {
  .web-run-preparation { align-items: flex-end; flex-wrap: wrap; }
  .web-run-preparation__launcher {
    grid-template-columns: minmax(0, 1fr);
    grid-template-areas:
      'summary'
      'controls';
  }
  .web-run-preparation__launcher-controls { justify-self: start; width: 100%; }
  .web-run-preparation__actions { margin-left: auto; }
}

@media (max-width: 620px) {
  .web-run-preparation__launcher-controls { width: 100%; }
  .web-run-preparation__actions { width: 100%; flex-wrap: wrap; padding-top: 0; }
  .web-run-preparation__execute { flex: 1 1 150px; }
  .web-run-preparation__execute .el-button { width: 100%; }
  .web-run-preparation--form .web-run-preparation__controls {
    grid-template-columns: minmax(0, 1fr);
  }
  .web-run-preparation--form .web-run-preparation__actions {
    justify-content: stretch;
  }
  .web-run-preparation--form .web-run-preparation__execute,
  .web-run-preparation--form .web-run-preparation__execute .el-button {
    width: 100%;
    min-width: 0;
  }
}
</style>

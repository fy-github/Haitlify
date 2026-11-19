<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import './web-execution-design.css'
import type { WebAutomationRunStatus } from '../../domain/webAutomation'
import type { SavedWebAutomationRunCase } from '../../domain/webAutomationRepository'
import type { WebAutomationRunSnapshot } from '../../domain/webAutomationRunRepository'
import type { ReturnTypeOfBuildWebRunView, WebRunBrowserOption } from '../../domain/webAutomationRunView'
import type { WebAutomationPreflight } from '../../domain/webAutomationWorkspaceView'
import type { WebRunStartRequest } from '../../domain/webExecutionPreparation'
import WebAutomationCaseTable from './WebAutomationCaseTable.vue'
import type { WebCaseStatusFilter } from './WebAutomationCaseTable.vue'
import WebAutomationTaskHeader from './WebAutomationTaskHeader.vue'
import WebRunMonitorSummary from './WebRunMonitorSummary.vue'
import WebRunMonitorView from './WebRunMonitorView.vue'
import WebRunPreflightPanel from './WebRunPreflightPanel.vue'
import WebRunPreparationBar from './WebRunPreparationBar.vue'
import WebSessionPanel from './WebSessionPanel.vue'

interface WebAutomationWorkspaceProps {
  mode: 'workbench' | 'monitor'
  compact: boolean
  header: {
    executableCount: number
    moduleCount: number
    latestRunLabel: string
  }
  preparation: {
    environmentId: string
    environments: Array<{ value: string; label: string; baseUrl?: string }>
    browser: string
    browsers: WebRunBrowserOption[]
    loopCount: number
    loginReadyLabel: string
    actionLabel: string
    canExecute: boolean
    blockingReason: string
  }
  scope: {
    project: string
    projects: Array<{ value: string; label: string; count: number }>
    module: string
    modules: Array<{ value: string; label: string; count: number }>
  }
  table: {
    cases: SavedWebAutomationRunCase[]
    selectedIds: string[]
    source: 'ALL' | 'design' | 'default'
    status: WebCaseStatusFilter
    keyword: string
    resultStatusById: Record<string, WebAutomationRunStatus | 'pending'>
    emptyKind: 'none' | 'no-cases' | 'filtered'
  }
  preflight: WebAutomationPreflight
  statusMessage: string
  suites?: Array<{ name: string }>
  retryFailedCount?: number
  run?: {
    snapshot: WebAutomationRunSnapshot
    view: ReturnTypeOfBuildWebRunView
    connectionState: 'idle' | 'polling' | 'retrying' | 'interrupted'
    connectionLabel: string
    elapsed: string
    controlLoading: 'pause' | 'resume' | 'stop' | ''
  }
}

const props = defineProps<WebAutomationWorkspaceProps>()

const emit = defineEmits<{
  openHistory: []
  generateCases: []
  exportCases: []
  updateEnvironmentId: [value: string]
  updateBrowser: [value: string]
  updateLoopCount: [value: number]
  editEnvironment: []
  openContext: []
  execute: [request: WebRunStartRequest]
  updateProject: [value: string]
  updateModule: [value: string]
  toggleCase: [testCase: SavedWebAutomationRunCase, checked: boolean]
  selectFiltered: []
  clearSelected: []
  updateSource: [value: 'ALL' | 'design' | 'default']
  updateStatus: [value: WebCaseStatusFilter]
  updateKeyword: [value: string]
  clearFilters: []
  openLog: [payload: SavedWebAutomationRunCase | string]
  transferDefect: [payload: SavedWebAutomationRunCase | string]
  deleteCase: [testCase: SavedWebAutomationRunCase]
  retryCapabilities: []
  editLogin: []
  enterMonitor: []
  returnWorkbench: []
  pause: []
  resume: []
  stop: []
  rerun: []
  openReport: []
  saveSuite: []
  applySuite: [name: string]
  deleteSuite: [name: string]
  retryFailed: []
}>()

const sessionPanelVisible = ref(false)
const suiteSelection = ref('')

const MIN_LOOP_COUNT = 1
const MAX_LOOP_COUNT = 20
const LOOP_ERROR = '循环次数需为 1–20 的整数'

// 执行范围标签取真实范围：有选中按选中条数，否则按当前筛选条数（与 buildWebAutomationScope 一致）。
const executeLabel = computed(() => props.table.selectedIds.length > 0
  ? `执行选中 ${props.table.selectedIds.length} 条`
  : `执行筛选 ${props.preflight.effectiveCount} 条`)

const environmentLabel = computed(() =>
  props.preparation.environments.find((environment) => environment.value === props.preparation.environmentId)?.label || '未选择环境'
)

const browserLabel = computed(() =>
  props.preparation.browsers.find((option) => option.value === props.preparation.browser)?.label || '跟随用例'
)

const executionOccurrences = computed(() => props.preflight.effectiveCount * props.preparation.loopCount)

/* ── 运行配置抽屉：草稿 / 已应用 两态，关闭或 Esc 丢弃未应用修改 ─────────────── */
const runtimeConfigVisible = ref(false)
const runtimeError = ref('')
const runtimeLoopInvalid = ref(false)
const runtimeDraft = reactive({ environmentId: '', browser: '', loopCount: MIN_LOOP_COUNT })
const runtimeApplied = reactive({ environmentId: '', browser: '', loopCount: MIN_LOOP_COUNT })

const runtimeDirty = computed(() =>
  runtimeDraft.environmentId !== runtimeApplied.environmentId
  || runtimeDraft.browser !== runtimeApplied.browser
  || runtimeDraft.loopCount !== runtimeApplied.loopCount
)

const syncRuntimeState = () => {
  const applied = {
    environmentId: props.preparation.environmentId,
    browser: props.preparation.browser,
    loopCount: props.preparation.loopCount
  }
  Object.assign(runtimeApplied, applied)
  Object.assign(runtimeDraft, applied)
  runtimeError.value = ''
  runtimeLoopInvalid.value = false
}

// 环境地址取草稿所选环境自己的地址:草稿换成 B 就必须显示 B,不能沿用父级已应用环境的地址。
const draftEnvironmentBaseUrl = computed(() =>
  props.preparation.environments.find((environment) => environment.value === runtimeDraft.environmentId)?.baseUrl || ''
)

const openRuntimeConfig = () => {
  syncRuntimeState()
  runtimeConfigVisible.value = true
}

const applyRuntimeConfig = () => {
  if (runtimeLoopInvalid.value) {
    runtimeError.value = LOOP_ERROR
    return
  }
  if (!Number.isInteger(runtimeDraft.loopCount)
    || runtimeDraft.loopCount < MIN_LOOP_COUNT
    || runtimeDraft.loopCount > MAX_LOOP_COUNT) {
    runtimeError.value = LOOP_ERROR
    return
  }
  runtimeError.value = ''
  if (runtimeDraft.loopCount !== runtimeApplied.loopCount) emit('updateLoopCount', runtimeDraft.loopCount)
  if (runtimeDraft.environmentId !== runtimeApplied.environmentId) emit('updateEnvironmentId', runtimeDraft.environmentId)
  if (runtimeDraft.browser !== runtimeApplied.browser) emit('updateBrowser', runtimeDraft.browser)
  Object.assign(runtimeApplied, runtimeDraft)
  runtimeConfigVisible.value = false
}

const onLoopValidity = (valid: boolean) => {
  runtimeLoopInvalid.value = !valid
  runtimeError.value = valid ? '' : LOOP_ERROR
}

// 关闭（含 Esc / 点遮罩）即丢弃未应用草稿，并回到已应用的配置。
watch(runtimeConfigVisible, (visible) => {
  if (visible) return
  Object.assign(runtimeDraft, runtimeApplied)
  runtimeError.value = ''
  runtimeLoopInvalid.value = false
})

// 父级在别处更新配置（如环境编辑器保存）时，同步已应用基线。
watch(
  () => [props.preparation.environmentId, props.preparation.browser, props.preparation.loopCount],
  () => {
    if (!runtimeConfigVisible.value) syncRuntimeState()
  }
)

/* ── 执行确认：范围 / 环境 / 循环 + 完整执行前检查，确认后才 emit('execute') ── */
const confirmVisible = ref(false)

const requestExecute = () => {
  if (!props.preparation.canExecute) return
  confirmVisible.value = true
}

const cancelExecute = () => {
  confirmVisible.value = false
}

const confirmExecute = () => {
  if (!props.preparation.canExecute) return
  confirmVisible.value = false
  // 确认框展示的就是这些值:同一份内容作为快照交给父级,父级只发快照。
  emit('execute', {
    scopeLabel: executeLabel.value,
    selectedCaseIds: [...props.table.selectedIds],
    loopCount: props.preparation.loopCount,
    environmentId: props.preparation.environmentId,
    environmentName: environmentLabel.value,
    environmentBaseUrl: props.preparation.environments.find((environment) => environment.value === props.preparation.environmentId)?.baseUrl || '',
    browser: props.preparation.browser,
    mode: 'sequential'
  })
}

const blockingReason = computed(() => props.statusMessage || props.preparation.blockingReason)

type ExposedComponent = {
  $el?: Element
  focusMonitorEntry?: () => void
  getScrollElement?: () => HTMLElement | undefined
}

const monitorEntry = ref<ExposedComponent | Element>()
const tableRef = ref<ExposedComponent | Element>()

const componentElement = (component: ExposedComponent | Element | undefined): Element | undefined => {
  if (!component) return undefined
  if (component instanceof Element) return component
  return component.$el
}

const focusMonitorEntry = () => {
  const target = monitorEntry.value
  if (!target) return
  if (!(target instanceof Element) && typeof target.focusMonitorEntry === 'function') {
    target.focusMonitorEntry()
    return
  }

  const root = componentElement(target)
  const focusTarget = root?.querySelector<HTMLElement>('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])') ||
    (root instanceof HTMLElement && root.tabIndex >= 0 ? root : undefined)
  focusTarget?.focus()
}

const getTableScrollElement = (): HTMLElement | undefined => {
  const target = tableRef.value
  if (!target) return undefined
  if (!(target instanceof Element) && typeof target.getScrollElement === 'function') {
    return target.getScrollElement()
  }
  return componentElement(target)?.querySelector<HTMLElement>('.web-automation-case-table__scroll') || undefined
}

defineExpose({
  focusMonitorEntry,
  getTableScrollElement
})
</script>

<template>
  <section class="web-automation-workspace" :class="{ 'is-compact': compact }" data-testid="web-automation-workspace">
    <template v-if="mode === 'monitor'">
      <WebRunMonitorView
        v-if="run"
        v-bind="run"
        @return-workbench="emit('returnWorkbench')"
        @pause="emit('pause')"
        @resume="emit('resume')"
        @stop="emit('stop')"
        @rerun="emit('rerun')"
        @open-log="emit('openLog', $event)"
        @open-report="emit('openReport')"
        @transfer-defect="emit('transferDefect', $event)"
      />
    </template>

    <template v-else>
      <WebAutomationTaskHeader
        v-bind="header"
        :execute-label="executeLabel"
        :can-execute="preparation.canExecute"
        @open-history="emit('openHistory')"
        @edit-environment="emit('editEnvironment')"
        @open-runtime-config="openRuntimeConfig"
        @export-cases="emit('exportCases')"
        @execute="requestExecute"
      />

      <div class="web-automation-workspace__session-bar">
        <el-select
          v-if="suites && suites.length"
          v-model="suiteSelection"
          size="small"
          class="web-automation-workspace__suite-select"
          placeholder="回归套件"
          data-testid="web-run-suite-select"
          @change="(name: string) => name && emit('applySuite', name)"
        >
          <el-option v-for="suite in suites" :key="suite.name" :label="suite.name" :value="suite.name" />
        </el-select>
        <button type="button" class="web-automation-workspace__session-entry" data-testid="save-run-suite" @click="emit('saveSuite')">
          保存为套件
        </button>
        <button
          v-if="suiteSelection"
          type="button"
          class="web-automation-workspace__session-entry"
          data-testid="delete-run-suite"
          @click="emit('deleteSuite', suiteSelection)"
        >
          删除套件
        </button>
        <button
          v-if="(retryFailedCount || 0) > 0"
          type="button"
          class="web-automation-workspace__session-entry is-retry"
          data-testid="retry-failed-cases"
          @click="emit('retryFailed')"
        >
          重跑失败用例（{{ retryFailedCount }}）
        </button>
      </div>

      <div class="web-automation-workspace__grid" :class="{ 'is-compact': compact }">
        <WebAutomationCaseTable
          ref="tableRef"
          v-bind="table"
          :project="scope.project"
          :projects="scope.projects"
          :module="scope.module"
          :modules="scope.modules"
          @toggle-case="(testCase, checked) => emit('toggleCase', testCase, checked)"
          @select-filtered="emit('selectFiltered')"
          @clear-selected="emit('clearSelected')"
          @update-source="emit('updateSource', $event)"
          @update-project="emit('updateProject', $event)"
          @update-module="emit('updateModule', $event)"
          @update-status="emit('updateStatus', $event)"
          @update-keyword="emit('updateKeyword', $event)"
          @clear-filters="emit('clearFilters')"
          @generate-cases="emit('generateCases')"
          @open-log="emit('openLog', $event)"
          @transfer-defect="emit('transferDefect', $event)"
          @delete-case="emit('deleteCase', $event)"
        />
        <WebRunPreflightPanel
          v-if="!compact && !run"
          :preflight="preflight"
          :message="statusMessage"
          @retry-capabilities="emit('retryCapabilities')"
          @edit-environment="emit('editEnvironment')"
          @edit-login="emit('editLogin')"
          @clear-filters="emit('clearFilters')"
        />
        <WebRunMonitorSummary
          v-else-if="run"
          ref="monitorEntry"
          v-bind="run"
          @enter-monitor="emit('enterMonitor')"
          @rerun="emit('rerun')"
          @open-report="emit('openReport')"
        />
      </div>

      <el-drawer
        v-model="runtimeConfigVisible"
        class="web-ed-drawer"
        modal-class="web-ed-drawer-modal"
        :with-header="false"
        size="min(740px, calc(100vw - 48px))"
        direction="rtl"
      >
        <div class="web-ed-drawer__inner" data-testid="web-runtime-drawer">
          <header class="web-ed-drawer__head">
            <div>
              <p class="web-ed-kicker">WEB RUNTIME CONFIGURATION</p>
              <h2 class="web-ed-drawer__title">Web 运行配置</h2>
              <p class="web-ed-drawer__sub">环境、浏览器与登录 / Session 统一管理。</p>
            </div>
            <button
              type="button"
              class="web-ed-drawer__close"
              data-testid="close-web-runtime-drawer"
              aria-label="关闭运行配置"
              @click="runtimeConfigVisible = false"
            >×</button>
          </header>

          <div class="web-ed-drawer__body">
            <p class="web-ed-drawer__note">
              修改只有点击「应用配置」后才用于下一次执行。关闭或 Esc 放弃未应用的修改，不改变已启动任务的快照。
            </p>

            <WebRunPreparationBar
              v-if="runtimeConfigVisible"
              :environment-id="runtimeDraft.environmentId"
              :environments="preparation.environments"
              :browser="runtimeDraft.browser"
              :browsers="preparation.browsers"
              :loop-count="runtimeDraft.loopCount"
              @update-environment-id="(value: string) => (runtimeDraft.environmentId = value)"
              @update-browser="(value: string) => (runtimeDraft.browser = value)"
              @update-loop-count="(value: number) => (runtimeDraft.loopCount = value)"
              @loop-count-validity="onLoopValidity"
            />

            <section class="web-ed-drawer__section">
              <h3>环境地址 · 只读</h3>
              <p class="web-ed-drawer__readonly" data-testid="web-runtime-environment-address">{{ draftEnvironmentBaseUrl || '未配置环境地址' }}</p>
            </section>

            <section class="web-ed-drawer__section">
              <h3>执行方式</h3>
              <p class="web-ed-drawer__readonly">顺序执行 · 平台当前固定，不提供并发或按模块执行的开关。</p>
            </section>

            <section class="web-ed-drawer__section">
              <h3>登录与 Session</h3>
              <p>不展示或采集账号、密码等敏感信息。</p>
              <div class="web-ed-drawer__row">
                <span>{{ preparation.loginReadyLabel }}</span>
                <button type="button" class="web-ed-btn" data-testid="open-web-runtime-login" @click="emit('editEnvironment')">
                  查看环境登录配置 ↗
                </button>
              </div>
              <div class="web-ed-drawer__row">
                <div>
                  <strong>Session 状态</strong>
                  <p>由 Runner 会话接口只读展示，复用与销毁策略未开放。</p>
                </div>
                <button type="button" class="web-ed-btn" data-testid="open-session-panel" @click="sessionPanelVisible = true">
                  查看 Session ↗
                </button>
              </div>
            </section>

            <p v-if="runtimeError" class="web-ed-drawer__error" data-testid="web-runtime-error" role="alert">{{ runtimeError }}</p>
          </div>

          <footer class="web-ed-drawer__foot">
            <p>{{ runtimeDirty ? '有未应用的修改' : '配置与已应用值一致' }}</p>
            <button type="button" class="web-ed-btn web-ed-btn--primary" data-testid="apply-web-runtime-config" @click="applyRuntimeConfig">
              应用配置
            </button>
          </footer>
        </div>
      </el-drawer>

      <el-dialog
        v-model="confirmVisible"
        class="web-ed-confirm"
        data-testid="web-execute-confirm"
        width="min(520px, calc(100vw - 24px))"
        :show-close="false"
        :title="'确认本次执行'"
      >
        <div class="web-ed-confirm__body">
          <p class="web-ed-kicker">REVIEW BEFORE RUN</p>
          <p class="web-ed-confirm__note">
            确认后创建执行任务；进度与结果以监控页的真实快照为准，这里不声称后台已成功。
          </p>

          <dl class="web-ed-confirm__list">
            <div><dt>执行范围</dt><dd data-testid="web-confirm-scope">{{ executeLabel }}（{{ preflight.effectiveCount }} 条用例）</dd></div>
            <div><dt>运行环境</dt><dd>{{ environmentLabel }}</dd></div>
            <div><dt>执行浏览器</dt><dd>{{ browserLabel }}</dd></div>
            <div>
              <dt>循环次数</dt>
              <dd>循环 {{ preparation.loopCount }} 次 · {{ executionOccurrences }} 次执行</dd>
            </div>
            <div><dt>执行方式</dt><dd>顺序执行（平台当前固定）</dd></div>
          </dl>

          <h3 class="web-ed-confirm__title">执行前检查</h3>
          <ul class="web-ed-confirm__checks">
            <li v-for="item in preflight.items" :key="item.id" :class="`is-${item.status}`" :data-testid="`web-confirm-check-${item.id}`">
              <strong>{{ item.label }}</strong>
              <span>{{ item.detail }}</span>
            </li>
          </ul>

          <p v-if="runtimeDirty" class="web-ed-confirm__warn">
            运行配置抽屉里还有未应用的修改，本次确认使用已应用的配置。
          </p>
          <p v-if="blockingReason" class="web-ed-confirm__warn">{{ blockingReason }}</p>

          <div class="web-ed-confirm__actions">
            <button type="button" class="web-ed-btn" data-testid="cancel-web-execute" @click="cancelExecute">返回调整</button>
            <button
              type="button"
              class="web-ed-btn web-ed-btn--primary"
              data-testid="confirm-web-execute"
              :disabled="!preparation.canExecute"
              @click="confirmExecute"
            >确认并开始执行</button>
          </div>
        </div>
      </el-dialog>
    </template>
    <WebSessionPanel v-model:visible="sessionPanelVisible" />
  </section>
</template>

<style scoped>
.web-automation-workspace__session-bar { display: flex; align-items: center; justify-content: flex-end; gap: 8px; margin: 4px 0 6px; }
.web-automation-workspace__suite-select { width: 170px; }
.web-automation-workspace__session-entry.is-retry { color: #b45309; border-color: rgba(180, 83, 9, 0.35); }
.web-automation-workspace__session-entry.is-retry:hover { background: rgba(180, 83, 9, 0.06); }
.web-automation-workspace__session-entry { min-height: 26px; padding: 0 10px; color: var(--brand-primary, #0f766e); background: transparent; border: 1px solid rgba(183, 210, 202, 0.82); border-radius: 10px; cursor: pointer; font-size: 12px; }
.web-automation-workspace__session-entry:hover { background: rgba(15, 118, 110, 0.06); }
</style>

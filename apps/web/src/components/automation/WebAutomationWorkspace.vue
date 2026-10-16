<script setup lang="ts">
import { ref } from 'vue'
import type { WebAutomationRunStatus } from '../../domain/webAutomation'
import type { SavedWebAutomationRunCase } from '../../domain/webAutomationRepository'
import type { WebAutomationRunSnapshot } from '../../domain/webAutomationRunRepository'
import type { ReturnTypeOfBuildWebRunView, WebRunBrowserOption } from '../../domain/webAutomationRunView'
import type { WebAutomationPreflight } from '../../domain/webAutomationWorkspaceView'
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
    environments: Array<{ value: string; label: string }>
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

defineProps<WebAutomationWorkspaceProps>()

const emit = defineEmits<{
  openHistory: []
  generateCases: []
  exportCases: []
  updateEnvironmentId: [value: string]
  updateBrowser: [value: string]
  updateLoopCount: [value: number]
  editEnvironment: []
  openContext: []
  execute: []
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
        @open-history="emit('openHistory')"
        @generate-cases="emit('generateCases')"
        @export-cases="emit('exportCases')"
      />

      <WebRunPreparationBar
        v-bind="preparation"
        :compact="compact"
        view="launcher"
        @update-environment-id="emit('updateEnvironmentId', $event)"
        @update-browser="emit('updateBrowser', $event)"
        @update-loop-count="emit('updateLoopCount', $event)"
        @edit-environment="emit('editEnvironment')"
        @execute="emit('execute')"
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
        <button type="button" class="web-automation-workspace__session-entry" data-testid="open-session-panel" @click="sessionPanelVisible = true">
          Session 状态
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
          v-else-if="!compact && run"
          ref="monitorEntry"
          v-bind="run"
          @enter-monitor="emit('enterMonitor')"
          @rerun="emit('rerun')"
          @open-report="emit('openReport')"
        />
      </div>
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

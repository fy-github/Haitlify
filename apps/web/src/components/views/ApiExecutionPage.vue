<script setup lang="ts">
import { ref, toRefs } from 'vue'
import type { ApiExecutionPageModel } from '../../ui/apiExecutionPageModel'
import { Bug, FileText, Download, Play, Settings, Trash2 } from '@lucide/vue'
import ApiRunMonitorView from '../../components/automation/ApiRunMonitorView.vue'
import ResponsiveDrawer from '../../components/shell/ResponsiveDrawer.vue'
import ApiAutomationCaseTable from '../../components/automation/ApiAutomationCaseTable.vue'
const props = defineProps<{ model: ApiExecutionPageModel }>()
const { automationKind, apiRunMonitor, returnApiRunPreparation, openReportsModule, apiAutomationWorkspaceMode, filteredAutomationCases, canEdit, openEnvironmentManagement, automationConfigVisible, enterApiRunMonitor, apiAutomationRunning, apiRunConfirming, executeAllAutomationCases, apiExecutionScopeLabel, exportAutomationJson, automationCases, automationProjects, automationModules, automationFilter, switchAutomationProject, automationMethods, hasFilteredDesignOriginAutomationCases, webAutomationRunning, transferFilteredDesignAutomationResultsToDefects, failedDesignOriginSummary, designOriginFailureExpanded, exportDesignOriginSummary, activeAutomationRunSummary, deleteAutomationByScope, automationSelection, automationResultMap, defectDraftByReportCaseId, defectStatusLabel, defectStatusTagType, openAutomationCurlPreview, openAutomationLog, transferAutomationResultToDefect, deleteAutomationCase, executionEnvironmentLibrary, selectExecutionEnvironment, openEnvironmentEditor, responseBodyMatchThreshold, automationRunMode, apiRunCountValue, apiRunCountMin, stepApiRunCount, apiRunCountInput, onApiRunCountInput, commitApiRunCount, apiRunCountError } = toRefs(props.model)
const apiRunMonitorRef = ref<InstanceType<typeof ApiRunMonitorView>>()
const apiPreparationElement = ref<HTMLElement>()
const apiMonitorEntry = ref<HTMLElement>()
defineExpose({
  focusHeading: () => apiRunMonitorRef.value?.focusHeading(),
  preparationScroll: () => apiPreparationElement.value?.querySelector<HTMLElement>('.el-scrollbar__wrap'),
  focusMonitorEntry: () => apiMonitorEntry.value?.focus({ preventScroll: true })
})
</script>

<template>

        <ApiRunMonitorView
          v-if="automationKind === 'api' && apiRunMonitor"
          v-show="apiAutomationWorkspaceMode === 'monitor'"
          ref="apiRunMonitorRef"
          :monitor="apiRunMonitor"
          @return-preparation="returnApiRunPreparation"
          @open-report="openReportsModule"
        />
        <header v-if="automationKind === 'api'" v-show="apiAutomationWorkspaceMode === 'prepare'" class="topbar automation-topbar">
          <div class="automation-topbar__title">
            <h1>自动化</h1>
            <p class="automation-topbar__subtitle">
              准备 {{ filteredAutomationCases.length }} 条可用例；运行环境与参数在「运行配置」中维护。
            </p>
          </div>
          <div class="toolbar">
            <el-button :disabled="!canEdit" @click="openEnvironmentManagement('api')">
              <Settings :size="16" />
              环境配置
            </el-button>
            <el-button @click="automationConfigVisible = true">
              <Settings :size="16" />
              运行配置
            </el-button>
            <button v-if="apiRunMonitor" ref="apiMonitorEntry" type="button" class="api-preparation-monitor-entry" data-testid="api-monitor-entry" @click="enterApiRunMonitor">返回运行监控 ↗</button>
            <el-button v-if="automationKind === 'api'" type="primary" :loading="apiAutomationRunning || apiRunConfirming" :disabled="!canEdit || apiAutomationRunning || apiRunConfirming || filteredAutomationCases.length === 0" @click="executeAllAutomationCases">
              <Play :size="16" />
              {{ apiExecutionScopeLabel }}
            </el-button>
            <el-button @click="openReportsModule">
              <FileText :size="16" />
              查看报告
            </el-button>
            <el-button @click="exportAutomationJson">
              <Download :size="16" />
              导出用例
            </el-button>
          </div>
        </header>

        <section
          v-if="automationKind === 'api'"
          v-show="apiAutomationWorkspaceMode === 'prepare'"
          ref="apiPreparationElement"
          class="automation-workspace"
        >
          <div class="library-metrics automation-metrics">
            <div class="metric-card">
              <span>自动化用例数</span>
              <strong>{{ automationCases.length }}</strong>
            </div>
            <div class="metric-card">
              <span>项目数</span>
              <strong>{{ automationProjects.length }}</strong>
            </div>
            <div class="metric-card">
              <span>模块数</span>
              <strong>{{ automationModules.length }}</strong>
            </div>
            <div class="metric-card">
              <span>当前筛选</span>
              <strong>{{ filteredAutomationCases.length }}</strong>
            </div>
          </div>

            <div class="case-panel automation-panel">
              <div class="panel-head stacked automation-panel-head">
                <div class="automation-panel-title-row">
                  <strong>接口自动化用例</strong>
                  <span>当前 {{ filteredAutomationCases.length }} 条</span>
                </div>
                <div class="automation-control-row">
                  <div class="case-filters compact-toolbar automation-filter-row" data-kind="api">
                    <div class="filter-fields">
                      <el-select v-model="automationFilter.project" placeholder="项目" @change="switchAutomationProject">
                        <el-option label="全部项目" value="ALL" />
                        <el-option v-for="project in automationProjects" :key="project.value" :label="project.value" :value="project.value" />
                      </el-select>
                      <el-select v-model="automationFilter.module" placeholder="模块">
                        <el-option label="全部模块" value="ALL" />
                        <el-option v-for="module in automationModules" :key="module.value" :label="module.value" :value="module.value" />
                      </el-select>
                      <el-select v-model="automationFilter.method" placeholder="方法">
                        <el-option label="全部方法" value="ALL" />
                        <el-option v-for="method in automationMethods" :key="method" :label="method" :value="method" />
                      </el-select>
                      <el-input
                        class="web-automation-keyword"
                        v-model="automationFilter.keyword"
                        clearable
                        placeholder="搜索自动化用例 / 期望状态"
                      />
                    </div>
                  </div>
                  <div class="panel-action-bar automation-panel-actions">
                    <div class="panel-action-main">
                    <div
                      v-if="hasFilteredDesignOriginAutomationCases"
                      class="action-cluster action-cluster-accent"
                    >
                      <el-button
                        type="primary"
                        plain
                        :loading="automationKind === 'api' ? apiAutomationRunning : webAutomationRunning"
                        @click="executeAllAutomationCases"
                      >
                        <Play :size="16" />
                        执行设计稿来源
                      </el-button>
                      <el-button
                        type="warning"
                        plain
                        @click="transferFilteredDesignAutomationResultsToDefects"
                      >
                        <Bug :size="16" />
                        失败批量转缺陷
                      </el-button>
                      <button
                        v-if="failedDesignOriginSummary.total > 0"
                        type="button"
                        class="design-origin-failure-summary"
                        @click="designOriginFailureExpanded = !designOriginFailureExpanded"
                      >
                        失败 {{ failedDesignOriginSummary.failed }} · 错误 {{ failedDesignOriginSummary.error }}
                      </button>
                      <el-button
                        plain
                        @click="exportDesignOriginSummary"
                      >
                        <Download :size="16" />
                        导出设计稿报表
                      </el-button>
                    </div>
                    </div>
                    <div v-if="automationKind === 'api'" class="action-cluster action-cluster-danger">
                      <div class="summary-tags summary-tags-inline">
                        <el-tag type="info">总数 {{ activeAutomationRunSummary.total }}</el-tag>
                        <el-tag type="success">通过 {{ activeAutomationRunSummary.passed }}</el-tag>
                        <el-tag type="danger">失败 {{ activeAutomationRunSummary.failed }}</el-tag>
                        <el-tag type="warning">错误 {{ activeAutomationRunSummary.error }}</el-tag>
                      </div>
                      <el-button @click="deleteAutomationByScope({ project: automationFilter.project !== 'ALL' ? automationFilter.project : undefined, module: automationFilter.module !== 'ALL' ? automationFilter.module : undefined })">
                        <Trash2 :size="16" />
                        删除当前范围
                      </el-button>
                    </div>
                  </div>
                </div>
              </div>
            <ApiAutomationCaseTable
              v-if="automationKind === 'api'"
              :cases="filteredAutomationCases"
              :selection="automationSelection"
              :result-map="automationResultMap"
              :defect-draft-by-report-case-id="defectDraftByReportCaseId"
              :defect-status-label="defectStatusLabel"
              :defect-status-tag-type="defectStatusTagType"
              @update:selection="automationSelection = $event"
              @open-curl="openAutomationCurlPreview($event)"
              @open-log="openAutomationLog($event)"
              @transfer-defect="transferAutomationResultToDefect($event)"
              @delete-case="deleteAutomationCase($event)"
            />
          </div>
        </section>
        <ResponsiveDrawer
          v-model="automationConfigVisible"
          class="studio-responsive-drawer automation-config-drawer"
          title="API 运行配置"
          description="配置修改后立即生效，关闭抽屉即可返回列表。"
          size="min(92vw, 920px)"
        >
          <div class="automation-config-drawer__content">
            <div class="automation-config-drawer__grid">
              <div class="execution-control-group execution-control-group-env">
                <span>运行环境</span>
                <div class="execution-control-row">
                  <el-select
                    :model-value="executionEnvironmentLibrary.activeEnvironmentId"
                    class="environment-select"
                    @change="(value: string) => selectExecutionEnvironment(value, 'api')"
                  >
                    <el-option
                      v-for="environment in executionEnvironmentLibrary.environments"
                      :key="environment.id"
                      :label="environment.name"
                      :value="environment.id"
                    />
                  </el-select>
                  <el-button @click="openEnvironmentEditor(undefined, 'api')">
                    <Settings :size="16" />
                    环境配置
                  </el-button>
                </div>
              </div>
              <div class="automation-run-summary execution-summary-block">
                <div class="execution-summary-threshold">
                  <div class="execution-summary-threshold-head">
                    <span class="threshold-label">返回体一致度</span>
                    <el-tag class="threshold-value-tag" type="info">{{ responseBodyMatchThreshold }}%</el-tag>
                  </div>
                  <div class="execution-summary-threshold-body">
                    <el-slider
                      v-model="responseBodyMatchThreshold"
                      class="threshold-slider"
                      :min="0"
                      :max="100"
                      :step="5"
                      :format-tooltip="(value: number) => `${value}%`"
                    />
                  </div>
                </div>
              </div>
              <div class="execution-control-group execution-control-group-mode">
                <span>执行方式</span>
                <div class="execution-control-row">
                  <el-segmented
                    v-model="automationRunMode"
                    :options="[
                      { label: '顺序', value: 'sequential' },
                      { label: '并发', value: 'concurrent' },
                      { label: '按模块', value: 'module' }
                    ]"
                  />
                </div>
              </div>
              <div class="execution-control-group execution-control-group-count">
                <span>{{ automationRunMode === 'concurrent' ? '并发数' : '循环次数' }}</span>
                <div class="execution-control-row">
                  <div class="api-run-count-stepper" data-testid="api-run-count-stepper">
                    <button
                      type="button"
                      class="api-run-count-stepper__button"
                      aria-label="减少"
                      :disabled="apiRunCountValue <= apiRunCountMin"
                      @click="stepApiRunCount(-1)"
                    >−</button>
                    <input
                      :value="apiRunCountInput"
                      class="api-run-count-stepper__input"
                      type="text"
                      inputmode="numeric"
                      aria-label="循环次数"
                      data-testid="api-run-count-input"
                      @input="onApiRunCountInput"
                      @blur="commitApiRunCount()"
                    />
                    <button
                      type="button"
                      class="api-run-count-stepper__button"
                      aria-label="增加"
                      @click="stepApiRunCount(1)"
                    >+</button>
                  </div>
                  <small v-if="apiRunCountError" class="api-run-count-error" role="alert">{{ apiRunCountError }}</small>
                </div>
              </div>
            </div>
          </div>
        </ResponsiveDrawer>

</template>

<style scoped>
/* API 执行准备：页头副标题 + 循环次数步进（仅本页/本抽屉使用，不改全局样式） */
.automation-topbar__subtitle {
  margin: 4px 0 0;
  font-size: 11px;
  color: var(--ink-muted, #6b707a);
}

.api-run-count-stepper {
  display: inline-flex;
  align-items: stretch;
  min-height: 34px;
  border: 1px solid #e8ebee;
  border-radius: 20px;
  background: #fff;
  overflow: hidden;
}

.api-run-count-stepper__button {
  width: 34px;
  border: 0;
  background: transparent;
  color: #3f5a50;
  font-size: 15px;
  line-height: 1;
  cursor: pointer;
}

.api-run-count-stepper__button:disabled {
  color: #b7bfc6;
  cursor: not-allowed;
}

.api-run-count-stepper__button:focus-visible {
  background: #edf2f0;
}

.api-run-count-stepper__input {
  width: 56px;
  border: 0;
  border-inline: 1px solid #eef1f4;
  text-align: center;
  font-size: 12px;
  color: #303740;
  background: #fff;
}

.api-run-count-stepper__input:focus {
  outline: 0;
  background: #fafcfb;
}

.api-run-count-error {
  margin-left: 8px;
  font-size: 11px;
  color: var(--color-danger, #b4423f);
}

@media (max-width: 640px) {
  .automation-config-drawer .execution-control-group-count .execution-control-row {
    flex-wrap: wrap;
  }

  .automation-config-drawer .execution-control-group-count .api-run-count-stepper {
    flex: none;
  }

  .automation-config-drawer .execution-control-group-count .api-run-count-error {
    flex-basis: 100%;
    margin-left: 0;
  }
}

</style>

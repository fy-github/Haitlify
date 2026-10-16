<script setup lang="ts">
import { Archive, Download, FileText } from '@lucide/vue'
import { ref } from 'vue'
import { useReports } from '../../ui/useReports'
import { useDefects } from '../../ui/useDefects'
import ScreenshotDomCompareDialog from './ScreenshotDomCompareDialog.vue'

const {
  refreshAutomationReports,
  reportExportFormat,
  selectedReportProject,
  selectedProjectReportTotal,
  filteredAutomationReports,
  reportEnvironments,
  reportEnvironmentFilter,
  reportKeyword,
  selectedReportId,
  selectedAutomationReport,
  selectedReportSummaryItems,
  reportStatusFilter,
  filteredReportResults,
  selectedReportKind,
  reportWebStepFilter,
  reportScreenshotPreviewVisible,
  reportScreenshotPreviewUrl,
  exportAutomationReport,
  removeAutomationReport,
  openReportResultLog,
  transferReportResultToDefect,
  toggleReportHtmlSnippet,
  reportHtmlSnippetExpanded,
  openReportScreenshotPreview,
  closeReportScreenshotPreview,
  openReportScreenshotInNewWindow,
  filteredReportWebStepLogs,
  automationStatusLabel,
  automationStatusTagType,
  reportFailedStepId,
  reportHtmlSnippet,
  reportScreenshotPath
} = useReports()

const { defectStatusLabel, defectStatusTagType, defectDraftByReportCaseId } = useDefects()

const compareVisible = ref(false)
const compareTitle = ref('')
const compareScreenshot = ref('')
const compareDom = ref('')
const openCompare = (row: Parameters<typeof reportScreenshotPath>[0]) => {
  const name = (row as { name?: string }).name
  compareTitle.value = `截图与 DOM 对照 · ${name || row.caseId}`
  compareScreenshot.value = reportScreenshotPath(row)
  compareDom.value = reportHtmlSnippet(row)
  compareVisible.value = true
}
</script>

<template>
  <header class="topbar">
    <div>
      <span class="eyebrow">Automation Reports</span>
      <h1>执行报告</h1>
    </div>
    <div class="toolbar">
      <el-button @click="refreshAutomationReports">
        <Archive :size="16" />
        刷新
      </el-button>
      <el-select v-model="reportExportFormat" class="report-format-select">
        <el-option label="JSON" value="json" />
        <el-option label="HTML" value="html" />
        <el-option label="Markdown" value="md" />
        <el-option label="PDF" value="pdf" />
        <el-option label="CSV" value="csv" />
      </el-select>
      <el-button :disabled="!selectedAutomationReport" @click="selectedAutomationReport && exportAutomationReport(selectedAutomationReport, reportExportFormat)">
        <Download :size="16" />
        导出报告
      </el-button>
    </div>
  </header>

  <div v-if="selectedReportProject" class="report-filter-panel report-filter-panel--surface">
    <el-select v-model="reportEnvironmentFilter" placeholder="环境">
      <el-option label="全部环境" value="ALL" />
      <el-option v-for="environment in reportEnvironments" :key="environment" :label="environment" :value="environment" />
    </el-select>
    <el-input v-model="reportKeyword" clearable placeholder="搜索报告" />
  </div>

  <section class="reports-workspace" :class="{ 'project-selected': !!selectedReportProject }">
    <div v-if="selectedReportProject" class="report-list-panel">
      <div class="panel-head">
        <strong>历史报告</strong>
        <span>{{ filteredAutomationReports.length }} / {{ selectedProjectReportTotal }} 条</span>
      </div>
      <div class="report-list">
        <button
          v-for="report in filteredAutomationReports"
          :key="report.reportId"
          class="report-list-item"
          :class="{ active: selectedAutomationReport?.reportId === report.reportId }"
          @click="selectedReportId = report.reportId"
        >
          <div>
            <strong>{{ report.name }}</strong>
            <span>{{ report.project }} · {{ report.environmentName }}</span>
          </div>
          <div class="report-list-actions">
            <time>{{ report.createdAt.slice(0, 16).replace('T', ' ') }}</time>
            <el-button link type="danger" @click.stop="removeAutomationReport(report.reportId)">删除</el-button>
          </div>
        </button>
        <div v-if="filteredAutomationReports.length === 0" class="placeholder-pane compact">
          <FileText :size="28" />
          <strong>当前筛选没有匹配的报告</strong>
          <span>调整环境或关键词后再看一下</span>
        </div>
      </div>
    </div>

    <div class="report-detail-panel" v-if="selectedAutomationReport">
      <div class="panel-head stacked">
        <div>
          <strong>{{ selectedAutomationReport.name }}</strong>
          <span>{{ selectedAutomationReport.project }} · {{ selectedAutomationReport.mode }}</span>
        </div>
        <div class="automation-run-summary">
          <el-tag type="info">总数 {{ selectedAutomationReport.summary.total }}</el-tag>
          <el-tag type="success">通过 {{ selectedAutomationReport.summary.passed }}</el-tag>
          <el-tag type="danger">失败 {{ selectedAutomationReport.summary.failed }}</el-tag>
          <el-tag type="warning">错误 {{ selectedAutomationReport.summary.error }}</el-tag>
        </div>
      </div>

      <div class="report-meta-grid">
        <div
          v-for="item in selectedReportSummaryItems"
          :key="item.label"
          class="metric-card"
          :class="{ 'module-scope-card': item.label === '模块范围' }"
        >
          <span>{{ item.label }}</span>
          <strong :class="{ 'module-scope-text': item.label === '模块范围' }">{{ item.value }}</strong>
        </div>
      </div>

      <div class="report-filter-bar">
        <span>状态筛选</span>
        <el-select v-model="reportStatusFilter" class="report-status-filter">
          <el-option label="全部" value="ALL" />
          <el-option label="通过" value="passed" />
          <el-option label="失败" value="failed" />
          <el-option label="错误" value="error" />
        </el-select>
      </div>

      <el-table class="case-table" :data="filteredReportResults" height="100%" border>
        <el-table-column prop="caseId" label="自动化ID" width="130" fixed />
        <el-table-column prop="name" label="用例名称" min-width="240" />
        <el-table-column prop="module" label="模块" width="140" />
        <el-table-column v-if="selectedReportKind === 'web'" label="结果类型" width="100">
          <template #default>
            Web
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span :class="{ 'status-failed': row.status === 'failed', 'status-error': row.status === 'error' }">
              {{ row.status === 'passed' ? '通过' : row.status === 'failed' ? '失败' : '错误' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column v-if="selectedReportKind === 'api'" label="期望状态" width="100">
          <template #default="{ row }">
            {{ row.expectedStatus }}
          </template>
        </el-table-column>
        <el-table-column v-if="selectedReportKind === 'api'" label="实际状态" width="100">
          <template #default="{ row }">
            <span :class="{ 'status-mismatch': row.actualStatus !== row.expectedStatus }">
              {{ row.actualStatus ?? '-' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column v-if="selectedReportKind === 'web'" label="访问地址" min-width="240">
          <template #default="{ row }">
            <code>{{ row.path }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="durationMs" label="耗时(ms)" width="100" />
        <el-table-column v-if="selectedReportKind === 'web'" label="失败原因" min-width="260">
          <template #default="{ row }">
            {{ row.errorMessage || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="错误信息" min-width="260">
          <template #default="{ row }">
            <span class="error-message">{{ row.errorMessage || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="196" fixed="right">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button link @click="openReportResultLog(row.caseId)">日志</el-button>
              <el-button
                v-if="row.status === 'failed' || row.status === 'error'"
                link
                type="warning"
                @click="transferReportResultToDefect(row)"
              >
                转缺陷
              </el-button>
              <el-tag v-if="defectStatusLabel(row.caseId) !== '未转缺陷'" :type="defectStatusTagType(defectDraftByReportCaseId.get(row.caseId))" effect="light">
                {{ defectStatusLabel(row.caseId) }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="selectedReportKind === 'web' && filteredReportResults.length" class="report-web-steps-panel">
        <div class="panel-head">
          <strong>Web 步骤列表</strong>
          <span>用于快速查看步骤动作、目标和失败上下文</span>
        </div>
        <div class="report-filter-bar">
          <span>步骤筛选</span>
          <el-select v-model="reportWebStepFilter" class="report-status-filter">
            <el-option label="全部步骤" value="ALL" />
            <el-option label="只看失败步骤" value="FAILED" />
          </el-select>
        </div>
        <div class="report-web-step-list">
          <div v-for="row in filteredReportResults" :key="row.caseId" class="report-web-step-card">
            <div class="report-web-step-head">
              <strong>{{ row.name }}</strong>
              <el-tag :type="automationStatusTagType(row.status)" effect="light">
                {{ automationStatusLabel(row.status) }}
              </el-tag>
            </div>
            <div class="report-web-step-meta">
              <span>模块：{{ row.module }}</span>
              <span>地址：{{ row.path }}</span>
              <span>耗时：{{ row.durationMs }} ms</span>
            </div>
            <div class="report-web-step-error" v-if="row.errorMessage">
              {{ row.errorMessage }}
            </div>
            <div v-if="reportFailedStepId(row)" class="report-web-step-error">
              失败步骤：{{ reportFailedStepId(row) }}
            </div>
            <div v-if="reportScreenshotPath(row)" class="report-web-step-meta">
              <span>
                截图：
                <a :href="reportScreenshotPath(row)" target="_blank" rel="noreferrer">
                  {{ reportScreenshotPath(row) }}
                </a>
              </span>
            </div>
            <button
              v-if="reportScreenshotPath(row)"
              class="report-screenshot-preview"
              type="button"
              @click="openReportScreenshotPreview(reportScreenshotPath(row))"
            >
              <img :src="reportScreenshotPath(row)" alt="失败截图预览" />
            </button>
            <el-button
              v-if="reportScreenshotPath(row) || reportHtmlSnippet(row)"
              link
              data-testid="open-screenshot-dom-compare"
              @click="openCompare(row)"
            >
              截图/DOM 对照
            </el-button>
            <div v-if="reportHtmlSnippet(row)" class="report-web-step-meta">
              <el-button link @click="toggleReportHtmlSnippet(row.caseId)">
                {{ reportHtmlSnippetExpanded(row.caseId) ? '收起源码片段' : '展开源码片段' }}
              </el-button>
            </div>
            <pre
              v-if="reportHtmlSnippet(row) && reportHtmlSnippetExpanded(row.caseId)"
              class="report-html-snippet"
            >{{ reportHtmlSnippet(row) }}</pre>
            <div v-if="filteredReportWebStepLogs(row).length" class="report-web-step-items">
              <div
                v-for="step in filteredReportWebStepLogs(row)"
                :key="step.stepId"
                class="report-web-step-item"
              >
                <span class="report-web-step-action">{{ step.action }}</span>
                <code class="report-web-step-target">{{ step.target }}</code>
                <el-tag :type="automationStatusTagType(step.status)" effect="light">
                  {{ automationStatusLabel(step.status) }}
                </el-tag>
                <span>{{ step.durationMs }} ms</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
    <div v-else class="report-detail-panel report-detail-empty">
      <div class="placeholder-pane compact">
        <FileText :size="30" />
        <strong>{{ selectedReportProject ? '请选择一份历史报告' : '请先选择项目' }}</strong>
        <span>{{ selectedReportProject ? '左侧报告列表会展示该项目全部历史报告' : '选择项目后再查看该项目的历史报告与明细' }}</span>
      </div>
    </div>
  </section>

  <el-dialog v-model="reportScreenshotPreviewVisible" title="失败截图预览" width="960px" @closed="reportScreenshotPreviewUrl = ''">
    <img
      v-if="reportScreenshotPreviewUrl"
      class="report-screenshot-dialog-image"
      :src="reportScreenshotPreviewUrl"
      alt="失败截图大图"
    />
    <template #footer>
      <div class="drawer-actions">
        <el-button @click="closeReportScreenshotPreview">关闭</el-button>
        <el-button type="primary" @click="openReportScreenshotInNewWindow">
          新窗口打开
        </el-button>
      </div>
    </template>
  </el-dialog>

  <ScreenshotDomCompareDialog
    v-model:visible="compareVisible"
    :title="compareTitle"
    :screenshot-path="compareScreenshot"
    :html-snippet="compareDom"
  />
</template>

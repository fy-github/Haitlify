<script setup lang="ts">
import { Archive, Download, ArrowLeft } from '@lucide/vue'
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import DefectFailureBanner from '../defects/DefectFailureBanner.vue'
import { useReports } from '../../ui/useReports'
import ScreenshotDomCompareDialog from './ScreenshotDomCompareDialog.vue'
import type { AutomationRunResult } from '../../domain/apiAutomation'
import { reportResultKind, type ReportResultFilter } from '../../domain/reportView'

const reports = useReports()
const {
  refreshAutomationReports,
  reportExportFormat,
  selectedReportProject,
  selectedProjectReportTotal,
  filteredAutomationReports,
  reportProjects,
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
  exportFilteredAutomationReportsJson,
  removeAutomationReport,
  openReportResultLog,
  transferReportResultToDefect,
  openReportScreenshotPreview,
  closeReportScreenshotPreview,
  openReportScreenshotInNewWindow,
  filteredReportWebStepLogs,
  reportHtmlSnippet,
  reportScreenshotPath,
  reportTotals,
  reportOpenRequest,
  consumeReportOpenRequest
} = reports

const view = ref<'history' | 'report' | 'result'>('history')
const historyPage = ref(1)
const resultPage = ref(1)
const pageSize = 8
const historyScrollTop = ref(0)
const historyScrollLeft = ref(0)
const resultScrollTop = ref(0)
const resultScrollLeft = ref(0)
const detailOpenerId = ref<string>()
const historyOpenerId = ref<string>()
const selectedResult = ref<AutomationRunResult>()
const compareVisible = ref(false)
const compareTitle = ref('')
const compareScreenshot = ref('')
const compareDom = ref('')
const historyScrollEl = ref<HTMLElement>()
const resultsScrollEl = ref<HTMLElement>()

const resultPosition = computed(() => {
  const rows = filteredReportResults.value
  const index = rows.findIndex((row) => row.caseId === selectedResult.value?.caseId)
  return { index, total: rows.length }
})

const historyPages = computed(() => Math.max(1, Math.ceil(filteredAutomationReports.value.length / pageSize)))
const visibleReports = computed(() => {
  const start = (historyPage.value - 1) * pageSize
  return filteredAutomationReports.value.slice(start, start + pageSize)
})
const resultPages = computed(() => Math.max(1, Math.ceil(filteredReportResults.value.length / pageSize)))
const visibleResults = computed(() => {
  const start = (resultPage.value - 1) * pageSize
  return filteredReportResults.value.slice(start, start + pageSize)
})
const resultStatusLabel = (status: string): string =>
  status === 'passed' ? '通过' : status === 'failed' ? '失败' : '错误'
const formatTime = (value: string): string => value.slice(0, 16).replace('T', ' ')
const selectedTotals = computed(() => selectedAutomationReport.value ? reportTotals(selectedAutomationReport.value) : { total: 0, passed: 0, failed: 0, error: 0 })
const resultTitle = computed(() => selectedResult.value?.name || '')
const resultKindLabel = computed(() => selectedReportKind.value === 'web' ? 'Web 自动化' : 'API 自动化')

// 双轴滚动位置在离开页面时直接读取容器实际位置（不只依赖 scroll 事件，
// 否则程序化滚动或快速点击会丢失位置）。
const rememberHistoryScroll = (event?: Event) => {
  const target = (event?.currentTarget as HTMLElement | undefined) ?? historyScrollEl.value
  if (!target) return
  historyScrollTop.value = target.scrollTop
  historyScrollLeft.value = target.scrollLeft
}
const rememberResultScroll = (event?: Event) => {
  const target = (event?.currentTarget as HTMLElement | undefined) ?? resultsScrollEl.value
  if (!target) return
  resultScrollTop.value = target.scrollTop
  resultScrollLeft.value = target.scrollLeft
}
const focusByTestId = async (testId: string | undefined) => {
  if (!testId) return
  await nextTick()
  await nextTick()
  const target = Array.from(document.querySelectorAll<HTMLElement>('[data-testid]'))
    .find((element) => element.dataset.testid === testId)
  target?.focus({ preventScroll: true })
}

const selectReport = (reportId: string, openerId?: string) => {
  rememberHistoryScroll()
  selectedReportId.value = reportId
  reportStatusFilter.value = 'ALL'
  resultPage.value = 1
  resultScrollTop.value = 0
  resultScrollLeft.value = 0
  view.value = 'report'
  historyOpenerId.value = openerId || `open-report-${reportId}`
}
const openResult = (result: AutomationRunResult, openerId?: string) => {
  rememberResultScroll()
  selectedResult.value = result
  detailOpenerId.value = openerId || `result-details-${result.caseId}`
  view.value = 'result'
}
const backToHistory = async () => {
  view.value = 'history'
  await nextTick()
  const scroll = historyScrollEl.value
  if (scroll) {
    scroll.scrollTop = historyScrollTop.value
    scroll.scrollLeft = historyScrollLeft.value
  }
  await focusByTestId(historyOpenerId.value)
}
const backToReport = async () => {
  view.value = 'report'
  await nextTick()
  const scroll = resultsScrollEl.value
  if (scroll) {
    scroll.scrollTop = resultScrollTop.value
    scroll.scrollLeft = resultScrollLeft.value
  }
  await focusByTestId(detailOpenerId.value)
}
const stepResult = (delta: number) => {
  const rows = filteredReportResults.value
  const current = rows.findIndex((row) => row.caseId === selectedResult.value?.caseId)
  const next = rows[current + delta]
  if (!next) return
  selectedResult.value = next
  // 上一条/下一条不改变发起本次进入的按钮：返回时仍聚焦最初点击的那一条操作。
}
const changeResultFilter = (filter: ReportResultFilter) => {
  reportStatusFilter.value = filter
  resultPage.value = 1
  resultScrollTop.value = 0
}
const openCompare = (row: AutomationRunResult) => {
  compareTitle.value = `截图与 DOM 对照 · ${row.name || row.caseId}`
  compareScreenshot.value = reportScreenshotPath(row)
  compareDom.value = reportHtmlSnippet(row)
  compareVisible.value = true
}
const transferDefect = (row: AutomationRunResult) => transferReportResultToDefect(row)
const resultDescription = (row: AutomationRunResult) => row.errorMessage || (row.status === 'passed' ? '该结果已通过。' : '该结果未完成。')

// App 的「查看报告」入口：一次性把用户带到整份报告层，随后回到常规列表优先。
const openRequestedReport = () => {
  if (!consumeReportOpenRequest()) return
  if (selectedReportId.value) selectReport(selectedReportId.value)
}
onMounted(openRequestedReport)
watch(reportOpenRequest, (request) => {
  if (request) openRequestedReport()
})

watch(filteredAutomationReports, () => {
  if (historyPage.value > historyPages.value) historyPage.value = historyPages.value
})
watch(filteredReportResults, () => {
  if (resultPage.value > resultPages.value) resultPage.value = resultPages.value
})
watch(selectedReportId, () => {
  if (view.value === 'history') return
  reportStatusFilter.value = 'ALL'
})
</script>

<template>
  <DefectFailureBanner />
  <section v-if="view === 'history'" class="report-page report-history-page" data-testid="report-history" aria-label="历史执行报告">
    <header class="settings-page-head report-head">
      <div>
        <span class="settings-page-kicker">质量交付</span>
        <h1>执行报告</h1>
        <p>找到一次执行，定位异常，再核对结果证据。</p>
      </div>
      <div class="toolbar">
        <el-button class="report-action-button report-action-button--secondary report-format-button" @click="refreshAutomationReports"><Archive :size="14" />刷新</el-button>
        <el-button class="report-action-button report-action-button--secondary report-format-button" data-testid="export-report-list-json" :disabled="!filteredAutomationReports.length" @click="exportFilteredAutomationReportsJson"><Download :size="14" />导出当前列表 JSON</el-button>
      </div>
    </header>
    <div class="report-filter-panel" aria-label="报告历史筛选">
      <el-select v-model="selectedReportProject" class="report-project-select" aria-label="选择项目">
        <el-option v-for="project in reportProjects" :key="project.value" :label="`${project.value}（${project.count}）`" :value="project.value" />
      </el-select>
      <el-select v-model="reportEnvironmentFilter" class="report-env-select" aria-label="选择环境">
        <el-option label="全部环境" value="ALL" />
        <el-option v-for="environment in reportEnvironments" :key="environment" :label="environment" :value="environment" />
      </el-select>
      <label class="report-search"><el-input v-model="reportKeyword" clearable placeholder="搜索报告名称、编号或模块" aria-label="搜索报告名称" /></label>
      <span class="report-filter-count">{{ filteredAutomationReports.length }} / {{ selectedProjectReportTotal }} 份</span>
    </div>
    <section class="report-table-box report-history-box">
      <header class="r-panel-head"><h2>历史报告</h2><span>{{ filteredAutomationReports.length }} 份报告<span class="r-scroll-hint"> · 左右滑动查看完整列</span></span></header>
      <div ref="historyScrollEl" class="r-scroll" data-testid="history-scroll" tabindex="0" role="region" aria-label="历史报告表，可滚动" @scroll="rememberHistoryScroll">
        <table class="r-table history-table"><thead><tr><th>报告名称 / 编号</th><th>项目 / 环境</th><th>类型</th><th>执行结果</th><th>执行时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="report in visibleReports" :key="report.reportId">
              <td><button class="r-name" :data-testid="`open-report-${report.reportId}`" @click="selectReport(report.reportId, `open-report-${report.reportId}`)">{{ report.name }}</button><small>{{ report.reportId }}</small></td>
              <td>{{ report.project }}<small>{{ report.environmentName }}</small></td>
              <td><span class="r-tag">{{ reportResultKind(report) === 'web' ? 'Web 自动化' : 'API 自动化' }}</span></td>
              <td><div class="r-summary-inline"><b>{{ reportTotals(report).total }} 次执行</b><br>通过 {{ reportTotals(report).passed }} · 失败 {{ reportTotals(report).failed }} · 错误 {{ reportTotals(report).error }}</div></td>
              <td>{{ formatTime(report.createdAt) }}<small>执行快照</small></td>
              <td><div class="r-mini-actions"><button class="r-link" :data-testid="`open-report-link-${report.reportId}`" @click="selectReport(report.reportId, `open-report-link-${report.reportId}`)">查看报告 ↗</button><el-button class="report-action-button report-action-button--danger report-action-button--link" link type="danger" @click.stop="removeAutomationReport(report.reportId)">删除</el-button></div></td>
            </tr>
            <tr v-if="!visibleReports.length"><td colspan="6" class="r-empty"><h3>{{ selectedReportProject ? '没有匹配的执行报告' : '请先选择项目' }}</h3><p>{{ selectedReportProject ? '调整项目、环境或关键词。' : '选择项目后再查看历史报告与单条结果。' }}</p></td></tr>
          </tbody>
        </table>
      </div>
      <footer class="r-pagination"><span>共 {{ filteredAutomationReports.length }} 份 · 第 {{ historyPage }} / {{ historyPages }} 页</span><div><button :disabled="historyPage === 1" @click="historyPage--">上一页</button><span>{{ historyPage }} / {{ historyPages }}</span><button :disabled="historyPage === historyPages" @click="historyPage++">下一页</button></div></footer>
    </section>
    <footer class="r-footer"><span>列表行可删除单份报告；导出入口在整份报告页面。</span></footer>
  </section>

  <section v-else-if="view === 'report'" class="report-page report-detail-page" data-testid="report-detail" aria-label="整份报告">
    <header class="settings-page-head report-head">
      <div><button class="r-back report-action-button report-action-button--back" data-testid="back-to-history" @click="backToHistory"><ArrowLeft :size="14" />返回报告列表</button><h1>{{ selectedAutomationReport?.name }}</h1><p>{{ selectedAutomationReport?.reportId }} · {{ selectedAutomationReport?.project }} · {{ resultKindLabel }}</p></div>
      <div class="toolbar"><el-select v-model="reportExportFormat" class="report-format-select"><el-option label="JSON" value="json" /><el-option label="HTML" value="html" /><el-option label="Markdown" value="md" /><el-option label="PDF" value="pdf" /><el-option label="CSV" value="csv" /></el-select><el-button class="report-action-button report-action-button--secondary" :disabled="!selectedAutomationReport" @click="selectedAutomationReport && exportAutomationReport(selectedAutomationReport, reportExportFormat)"><Download :size="14" />导出报告</el-button><el-button class="report-action-button report-action-button--danger" type="danger" plain :disabled="!selectedAutomationReport" @click="selectedAutomationReport && removeAutomationReport(selectedAutomationReport.reportId)">删除</el-button></div>
    </header>
    <div v-if="selectedAutomationReport" class="report-overview">
      <div class="r-overview-title"><span>{{ selectedTotals.failed || selectedTotals.error ? '执行已结束 · 存在异常结果' : '执行已结束 · 全部通过' }}</span><small>统计基于整份报告，不随结果筛选改变</small></div>
      <div class="r-statistics"><div class="r-stat total" data-testid="report-stat-total"><strong>{{ selectedTotals.total }}</strong><span>执行总数</span></div><div class="r-stat passed" data-testid="report-stat-passed"><strong>{{ selectedTotals.passed }}</strong><span>通过</span></div><div class="r-stat failed" data-testid="report-stat-failed"><strong>{{ selectedTotals.failed }}</strong><span>失败</span></div><div class="r-stat error" data-testid="report-stat-error"><strong>{{ selectedTotals.error }}</strong><span>错误</span></div></div>
      <dl class="report-meta"><div v-for="item in selectedReportSummaryItems" :key="item.label"><dt>{{ item.label }}</dt><dd>{{ item.value }}</dd></div></dl>
    </div>
    <section v-if="selectedAutomationReport" class="report-table-box">
      <header class="r-panel-head"><h2>用例结果<span class="r-scroll-hint"> · 左右滑动查看完整列</span></h2><div class="result-filter" role="group" aria-label="结果快捷筛选"><button class="report-action-button report-action-button--filter" data-testid="result-filter-all" :aria-pressed="reportStatusFilter === 'ALL'" @click="changeResultFilter('ALL')">全部</button><button class="report-action-button report-action-button--filter" data-testid="result-filter-abnormal" :aria-pressed="reportStatusFilter === 'ABNORMAL'" @click="changeResultFilter('ABNORMAL')">失败与错误</button><button class="report-action-button report-action-button--filter" data-testid="result-filter-passed" :aria-pressed="reportStatusFilter === 'passed'" @click="changeResultFilter('passed')">通过</button><button class="report-action-button report-action-button--filter" data-testid="result-filter-failed" :aria-pressed="reportStatusFilter === 'failed'" @click="changeResultFilter('failed')">失败</button><button class="report-action-button report-action-button--filter" data-testid="result-filter-error" :aria-pressed="reportStatusFilter === 'error'" @click="changeResultFilter('error')">错误</button></div></header>
      <div ref="resultsScrollEl" class="r-scroll" data-testid="results-scroll" tabindex="0" role="region" aria-label="报告结果表，可滚动" @scroll="rememberResultScroll">
        <table class="r-table result-table"><thead><tr><th>自动化 ID</th><th>用例名称 / 模块</th><th>状态</th><th>{{ selectedReportKind === 'api' ? '期望 / 实际状态' : '页面地址' }}</th><th>耗时</th><th>操作</th></tr></thead><tbody>
          <tr v-for="row in visibleResults" :key="row.caseId"><td>{{ row.caseId }}</td><td><button class="r-name" :data-testid="`result-details-${row.caseId}`" @click="openResult(row, `result-details-${row.caseId}`)">{{ row.name }}</button><small>{{ row.module }}</small></td><td><span class="r-tag" :class="row.status">{{ resultStatusLabel(row.status) }}</span></td><td>{{ selectedReportKind === 'api' ? `${row.expectedStatus} / ${row.actualStatus ?? '-'}` : row.path }}</td><td>{{ row.durationMs }} ms</td><td><div class="r-mini-actions"><button class="r-link" :data-testid="`result-open-${row.caseId}`" @click="openResult(row, `result-open-${row.caseId}`)">查看详情</button><button class="r-link" :data-testid="`result-log-${row.caseId}`" @click="openReportResultLog(row.caseId)">日志</button><button v-if="row.status !== 'passed'" class="r-link" :data-testid="`defect-${row.caseId}`" @click="transferDefect(row)">转缺陷</button></div></td></tr>
          <tr v-if="!visibleResults.length"><td colspan="6" class="r-empty"><h3>当前没有此类结果</h3><p>报告总计保持不变，可恢复全部结果查看。</p><button class="r-link" @click="changeResultFilter('ALL')">查看全部结果</button></td></tr>
        </tbody></table>
      </div>
      <footer class="r-pagination"><span>当前 {{ filteredReportResults.length }} 条结果 · 报告 {{ selectedTotals.total }} 条</span><div><button :disabled="resultPage === 1" @click="resultPage--">上一页</button><span>{{ resultPage }} / {{ resultPages }}</span><button :disabled="resultPage === resultPages" @click="resultPage++">下一页</button></div></footer>
    </section>
    <footer class="r-footer"><span>失败与错误快捷筛选不改变整份报告统计。</span><span v-if="selectedReportKind === 'web'">Web 步骤、日志与截图/DOM 证据在独立结果详情页查看。</span></footer>
  </section>

  <section v-else class="report-page report-result-page" data-testid="report-result-detail" aria-label="单条执行结果详情">
    <header class="settings-page-head report-head">
      <div><button class="r-back report-action-button report-action-button--back" data-testid="back-to-report" @click="backToReport"><ArrowLeft :size="14" />返回整份报告</button><span class="settings-page-kicker">单条执行结果详情</span><h1>{{ resultTitle }}</h1><p>{{ selectedResult?.caseId }} · {{ selectedAutomationReport?.reportId }} · {{ resultKindLabel }}</p></div>
      <div class="toolbar"><el-button v-if="selectedResult && selectedResult.status !== 'passed'" class="report-action-button report-action-button--secondary" @click="transferDefect(selectedResult)">转缺陷</el-button></div>
    </header>
    <section v-if="selectedResult" class="result-summary"><span class="r-tag" :class="selectedResult.status">{{ resultStatusLabel(selectedResult.status) }}</span><span>{{ selectedResult.durationMs }} ms</span><span>{{ selectedAutomationReport?.project }} / {{ selectedResult.module }} · {{ selectedAutomationReport?.environmentName }}</span></section>
    <div class="r-scroll result-content" data-testid="result-content" tabindex="0" role="region" aria-label="单条结果详情内容">
      <section v-if="selectedResult" class="r-detail-section"><h2>执行上下文</h2><dl class="report-meta"><div><dt>用例</dt><dd>{{ selectedResult.name }}</dd></div><div><dt>{{ selectedReportKind === 'web' ? '页面' : '接口' }}</dt><dd>{{ selectedResult.path }}</dd></div><div><dt>执行结果</dt><dd>{{ resultDescription(selectedResult) }}</dd></div></dl></section>
      <section v-if="selectedResult && selectedReportKind === 'api'" class="r-detail-section"><h2>请求与响应</h2><pre class="r-code">{{ JSON.stringify({ request: selectedResult.request, response: selectedResult.responseBody ?? selectedResult.actualResponseBody, expectedStatus: selectedResult.expectedStatus, actualStatus: selectedResult.actualStatus }, null, 2) }}</pre></section>
      <section v-if="selectedResult && selectedReportKind === 'api'" class="r-detail-section"><h2>断言结果</h2><dl class="report-meta"><div><dt>期望 HTTP 状态</dt><dd>{{ selectedResult.expectedStatus }}</dd></div><div><dt>实际 HTTP 状态</dt><dd>{{ selectedResult.actualStatus ?? '无响应' }}</dd></div><div v-if="selectedResult.responseBodyMatch"><dt>响应体匹配</dt><dd>{{ selectedResult.responseBodyMatch.matched }} / {{ selectedResult.responseBodyMatch.expected }}</dd></div><div v-if="selectedResult.request.assertions.length"><dt>业务断言</dt><dd>{{ selectedResult.request.assertions.join('；') }}</dd></div></dl><p v-if="selectedResult.status === 'error'">执行错误：无有效响应，断言未完成，不能视为通过。</p></section>
      <section v-if="selectedResult && selectedReportKind === 'api'" class="r-detail-section"><h2>执行日志</h2><div v-if="selectedResult.stepLogs?.length" class="report-web-step-items"><div v-for="step in selectedResult.stepLogs" :key="step.stepId" class="report-web-step-item"><span>{{ step.name }}</span><code>{{ step.action }} {{ step.target }}</code><span class="r-tag" :class="step.status">{{ resultStatusLabel(step.status) }}</span><span>{{ step.durationMs }} ms</span></div></div><p v-else>该接口结果未记录步骤日志。</p><p v-if="selectedResult.errorMessage" class="report-web-step-error">{{ selectedResult.errorMessage }}</p></section>
      <section v-if="selectedResult && selectedReportKind === 'web'" class="r-detail-section"><h2>Web 步骤与日志</h2><div class="result-filter" role="group" aria-label="步骤筛选"><button class="report-action-button report-action-button--filter" :aria-pressed="reportWebStepFilter === 'ALL'" @click="reportWebStepFilter = 'ALL'">全部步骤</button><button class="report-action-button report-action-button--filter" :aria-pressed="reportWebStepFilter === 'FAILED'" @click="reportWebStepFilter = 'FAILED'">只看失败步骤</button></div><div v-if="filteredReportWebStepLogs(selectedResult).length" class="report-web-step-items"><div v-for="step in filteredReportWebStepLogs(selectedResult)" :key="step.stepId" class="report-web-step-item"><span>{{ step.name }}</span><code>{{ step.target }}</code><span class="r-tag" :class="step.status">{{ resultStatusLabel(step.status) }}</span><span>{{ step.durationMs }} ms</span></div></div><p v-else>当前筛选下没有步骤日志。</p><p v-if="selectedResult.errorMessage" class="report-web-step-error">{{ selectedResult.errorMessage }}</p><p v-if="selectedResult.failedStepId" class="report-web-step-error">失败步骤：{{ selectedResult.failedStepId }}</p></section>
      <section v-if="selectedResult && selectedReportKind === 'web'" class="r-detail-section"><h2>截图 / DOM 证据</h2><div class="r-evidence-grid"><div v-if="reportScreenshotPath(selectedResult)" class="report-web-step-shot"><img :src="reportScreenshotPath(selectedResult)" alt="执行截图" /><button class="r-link" @click="openReportScreenshotPreview(reportScreenshotPath(selectedResult))">放大预览</button></div><pre v-if="reportHtmlSnippet(selectedResult)" class="r-code">{{ reportHtmlSnippet(selectedResult) }}</pre><p v-if="!reportScreenshotPath(selectedResult) && !reportHtmlSnippet(selectedResult)">当前结果没有截图或 DOM 证据。</p></div><button v-if="reportScreenshotPath(selectedResult) || reportHtmlSnippet(selectedResult)" class="r-link" @click="openCompare(selectedResult)">截图 / DOM 对照</button></section>
    </div>
    <footer class="r-footer"><span>单条结果详情独立成页，返回时恢复整份报告的筛选、页码与滚动位置。</span><div class="result-neighbors"><button data-testid="previous-result" :disabled="resultPosition.index <= 0" @click="stepResult(-1)">上一条</button><span>{{ resultPosition.index + 1 }} / {{ resultPosition.total }} · 当前筛选</span><button data-testid="next-result" :disabled="resultPosition.index >= resultPosition.total - 1" @click="stepResult(1)">下一条</button></div></footer>
  </section>

  <el-dialog class="report-detail-dialog" v-model="reportScreenshotPreviewVisible" title="失败截图预览" width="960px" @closed="closeReportScreenshotPreview"><img v-if="reportScreenshotPreviewUrl" class="report-screenshot-dialog-image" :src="reportScreenshotPreviewUrl" alt="失败截图大图" /><template #footer><div class="drawer-actions"><el-button class="report-action-button report-action-button--secondary" @click="closeReportScreenshotPreview">关闭</el-button><el-button class="report-action-button report-action-button--primary" type="primary" @click="openReportScreenshotInNewWindow">新窗口打开</el-button></div></template></el-dialog>
  <ScreenshotDomCompareDialog class="report-detail-compare-dialog" v-model:visible="compareVisible" :title="compareTitle" :screenshot-path="compareScreenshot" :html-snippet="compareDom" />
</template>

<style src="../../styles/report-detail-controls.css"></style>

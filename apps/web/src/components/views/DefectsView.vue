<script setup lang="ts">
import { computed, nextTick, ref, watch, watchEffect } from 'vue'
import { Search, Settings, Plus } from '@lucide/vue'
import DefectFailureBanner from '../defects/DefectFailureBanner.vue'
import type { DefectIssueRecord } from '../../domain/defectView'
import { useDefects } from '../../ui/useDefects'
import { resolveDefectScreenshotPreview } from '../../domain/defectEvidencePreview'
import { copyDefectEvidence } from '../../domain/defectEvidence'

const {
  defectReadBlocked,
  defectSource,
  defectFilter,
  defectProjectOptions,
  defectFilterOptions,
  jiraIssues,
  filteredDefectIssues,
  pagedDefectIssues,
  defectPage,
  defectPageCount,
  defectPageSize,
  defectListContexts,
  defectSelection,
  activeDefectScope,
  recentlyTransferredDefectIds,
  jiraCsvTemplateDraft,
  jiraTemplateVisible,
  detailDefect,
  detailDefectId,
  openDefectDetail,
  closeDefectDetail,
  defectEditVisible,
  defectEditDirty,
  defectEditError,
  defectEditForm,
  defectDrafts,
  openDefectEdit,
  cancelDefectEdit,
  saveDefectEdit,
  removeDefect,
  removeScopedDefects,
  exportSelectedDefectsToJiraCsv,
  exportDefectListJson,
  defectImportPreview,
  beginDefectImport,
  importDefectJsonText,
  confirmDefectImport,
  cancelDefectImport,
  switchDefectSource,
  switchDefectProject,
  addJiraCsvTemplateField,
  updateJiraCsvTemplateField,
  deleteJiraCsvTemplateField,
  saveJiraCsvTemplateConfig,
  resetJiraCsvTemplateConfig,
  defectIssueStatusTagType,
  defectStatusText
} = useDefects()

const listScroll = ref<HTMLElement>()
const selectAllRef = ref<HTMLInputElement>()
const detailOpener = ref<HTMLElement>()
const moreFiltersOpen = ref(false)
const templateError = ref('')
const importInput = ref<HTMLInputElement>()
const importError = ref('')
const triggerImport = () => { importError.value = ''; importInput.value?.click() }
const onImportFile = async (event: Event) => {
  const file = (event.target as HTMLInputElement).files?.[0]
  ;(event.target as HTMLInputElement).value = ''
  if (!file) return
  const authority = beginDefectImport()
  try { await importDefectJsonText(await file.text(), authority) }
  catch { if (authority.isCurrent()) importError.value = '导入文件读取失败。' }
}

const isDraftView = computed(() => defectSource.value === 'draft')
const pageRows = computed(() => pagedDefectIssues.value)
const selectedIds = computed(() => new Set(defectSelection.value.map((draft) => draft.defectId)))
const pageSelectedCount = computed(() => pageRows.value.filter((row) => selectedIds.value.has(row.draft.defectId)).length)
const scopeCount = computed(() => activeDefectScope.value.length)
const firstRow = computed(() => filteredDefectIssues.value.length === 0 ? 0 : (defectPage.value - 1) * defectPageSize + 1)
const lastRow = computed(() => Math.min(defectPage.value * defectPageSize, filteredDefectIssues.value.length))

const statusOptions = computed(() => isDraftView.value
  ? [
      { value: 'ALL', label: '全部状态' },
      { value: 'pending_jira_export', label: '待导出 Jira' },
      { value: 'exported_jira_csv', label: '已导出 Jira CSV' },
      { value: 'created_in_jira', label: '已建 Jira' }
    ]
  : [{ value: 'ALL', label: '全部状态' }, ...Array.from(new Set(jiraIssues.value.map((issue) => issue.status)))
      .filter(Boolean)
      .map((status) => ({ value: status, label: status }))])

const statusTone = (row: DefectIssueRecord): string => {
  if (row.sourceKind === 'jira') return 'jira'
  const type = defectIssueStatusTagType(row.sourceKind, row.draft)
  return type === 'success' ? 'exported' : type === 'warning' ? 'pending' : 'created'
}

const toggleRow = (row: DefectIssueRecord, checked: boolean) => {
  if (row.sourceKind !== 'draft') return
  const next = defectSelection.value.filter((draft) => draft.defectId !== row.draft.defectId)
  if (checked) next.push(row.draft)
  defectSelection.value = next
  recentlyTransferredDefectIds.value = []
}

const togglePage = (checked: boolean) => {
  const pageDraftIds = new Set(pageRows.value.filter((row) => row.sourceKind === 'draft').map((row) => row.draft.defectId))
  const kept = defectSelection.value.filter((draft) => !pageDraftIds.has(draft.defectId))
  defectSelection.value = checked
    ? [...kept, ...pageRows.value.filter((row) => row.sourceKind === 'draft').map((row) => row.draft)]
    : kept
}

const clearSelection = () => { defectSelection.value = [] }

watchEffect(() => {
  if (selectAllRef.value) {
    selectAllRef.value.indeterminate = pageSelectedCount.value > 0 && pageSelectedCount.value < pageRows.value.length
  }
})

const resetFilters = () => {
  Object.assign(defectFilter, { project: 'ALL', status: 'ALL', issueType: 'ALL', priority: 'ALL', assignee: 'ALL', keyword: '' })
  defectSelection.value = []
}

const activeTabId = computed(() => `defect-tab-${defectSource.value}`)
const onSourceTabKeydown = (event: KeyboardEvent) => {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const buttons = Array.from((event.currentTarget as HTMLElement).querySelectorAll<HTMLElement>('[role="tab"]'))
  const current = buttons.indexOf(document.activeElement as HTMLElement)
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1
    : (current + (event.key === 'ArrowRight' ? 1 : -1) + buttons.length) % buttons.length
  buttons[next]?.focus()
  void changeSource(buttons[next].dataset.view === 'jira' ? 'jira' : 'draft')
}

const changeSource = async (source: 'draft' | 'jira') => {
  await switchDefectSource(source)
  await nextTick()
  restoreListScroll()
}

const persistListScroll = () => {
  // 列表卸载时浏览器会把滚动位置重置为 0,不能把这个事件写回来源上下文
  if (!listScroll.value || detailDefect.value) return
  const context = defectListContexts[defectSource.value]
  context.scrollTop = listScroll.value.scrollTop
  context.scrollLeft = listScroll.value.scrollLeft
}

const restoreListScroll = () => {
  const context = defectListContexts[defectSource.value]
  if (!listScroll.value) return
  listScroll.value.scrollTop = context.scrollTop
  listScroll.value.scrollLeft = context.scrollLeft
}

const openDetail = async (row: DefectIssueRecord, opener?: HTMLElement) => {
  persistListScroll()
  detailOpener.value = opener
  openDefectDetail(row)
  await nextTick()
  document.querySelector<HTMLElement>('[data-action="back-list"]')?.focus({ preventScroll: true })
}

const backToList = async () => {
  const opener = detailOpener.value
  if (!(await closeDefectDetail())) return
  await nextTick()
  restoreListScroll()
  const target = opener?.isConnected
    ? opener
    : document.querySelector<HTMLElement>(`[data-opener="${opener?.dataset.opener || ''}"]`)
  target?.focus({ preventScroll: true })
}

const beginEdit = () => {
  if (detailDefect.value?.sourceKind !== 'draft') return
  openDefectEdit(detailDefect.value.draft)
  void nextTick(() => document.querySelector<HTMLInputElement>('input[aria-label="概要"]')?.focus({ preventScroll: true }))
}

const saveEdit = () => { saveDefectEdit() }

const deleteDetailDraft = async () => {
  if (detailDefect.value?.sourceKind !== 'draft') return
  await removeDefect(detailDefect.value.draft)
}

const openTemplate = () => {
  templateError.value = ''
  jiraTemplateVisible.value = true
}

const submitTemplate = () => {
  templateError.value = ''
  const result = saveJiraCsvTemplateConfig()
  if (result === false) templateError.value = '至少启用一列，且表头与取值键不能为空。'
}

const addField = async () => {
  addJiraCsvTemplateField()
  await nextTick()
}

const resetTemplate = async () => {
  templateError.value = ''
  await resetJiraCsvTemplateConfig()
}

const rowOpenerId = (row: DefectIssueRecord): string => `action:${row.draft.defectId}`

// 转缺陷时写入的执行证据（可缺失：旧草稿没有这些字段，详情页只展示缺陷信息）。
const detailDraft = computed(() => (detailDefect.value?.sourceKind === 'draft' ? detailDefect.value.draft : undefined))
const evidenceStepLogs = computed(() => detailDraft.value?.stepLogs ?? [])
const evidenceItems = computed(() => detailDraft.value?.stepEvidence ?? [])
const evidenceScreenshot = computed(() => detailDraft.value?.screenshotPath || '')
const screenshotPreview = computed(() => resolveDefectScreenshotPreview(evidenceScreenshot.value))
const evidenceRaw = computed(() => detailDraft.value ? JSON.stringify(copyDefectEvidence(JSON.parse(JSON.stringify(detailDraft.value))), null, 2) : '')
const evidenceDom = computed(() => detailDraft.value?.pageHtmlSnippet || '')
const hasEvidence = computed(() => !!detailDraft.value && evidenceRaw.value !== '{}')

const brokenStepScreenshots = ref<Record<number, boolean>>({})
watch(evidenceItems, () => { brokenStepScreenshots.value = {} })
const screenshotBroken = ref(false)
watch(evidenceScreenshot, () => { screenshotBroken.value = false })

const stepTone = (log: { status?: string }): string => log.status === 'failed' || log.status === 'error' ? 'is-failed' : log.status === 'passed' ? 'is-passed' : 'is-unknown'
const evidenceLabel = (item: { stepId?: string; actionId?: string }): string => item.stepId || item.actionId || '—'

</script>

<template>
  <div class="defect-page">
    <DefectFailureBanner />
    <template v-if="!detailDefect">
      <section class="defect-page__list report-page" data-testid="defect-list" aria-label="缺陷列表">
        <header class="page-head">
          <div>
            <h1>缺陷管理</h1>
            <p>整理执行异常，核对缺陷草稿；已导出不等于已建单。</p>
          </div>
          <div class="head-actions">
            <button v-if="isDraftView" type="button" class="btn light" data-action="open-template" :disabled="defectReadBlocked" @click="openTemplate">
              <Settings :size="14" />
              导出字段配置
            </button>
            <button v-if="isDraftView" type="button" class="btn light" data-action="import-defect-json" :disabled="defectReadBlocked" @click="triggerImport">导入 JSON</button>
            <input ref="importInput" class="file-input-proxy" type="file" accept="application/json,.json" tabindex="-1" aria-hidden="true" @change="onImportFile">
          </div>
        </header>
        <p v-if="importError" class="api-error d-import-error" data-testid="defect-import-error">{{ importError }}</p>
        <div class="d-topline">
          <div class="r-tabs" role="tablist" aria-label="缺陷来源视图" @keydown="onSourceTabKeydown">
            <button id="defect-tab-draft" type="button" role="tab" data-view="draft" aria-controls="defect-source-panel" :aria-selected="isDraftView" :tabindex="isDraftView ? 0 : -1" :class="{ active: isDraftView }" @click="changeSource('draft')">
              缺陷草稿 <span>{{ defectDrafts.length }}</span>
            </button>
            <button id="defect-tab-jira" type="button" role="tab" data-view="jira" aria-controls="defect-source-panel" :aria-selected="!isDraftView" :tabindex="isDraftView ? -1 : 0" :class="{ active: !isDraftView }" @click="changeSource('jira')">
              Jira 问题 <span>{{ jiraIssues.length }}</span>
            </button>
          </div>
          <span class="d-origin">{{ isDraftView ? '本地草稿阶段 ≠ Jira 处理状态' : '未连接真实 Jira · 只读示例' }}</span>
        </div>

        <div id="defect-source-panel" class="d-source-panel" role="tabpanel" :aria-labelledby="activeTabId">
        <div class="d-filters">
          <label class="d-picker" aria-label="项目筛选">
            <select :value="defectFilter.project" @change="switchDefectProject(($event.target as HTMLSelectElement).value)">
              <option value="ALL">全部项目</option>
              <option v-for="project in defectProjectOptions" :key="project.value" :value="project.value">{{ project.value }} ({{ project.count }})</option>
            </select>
          </label>
          <label class="d-picker" aria-label="状态筛选">
            <select v-model="defectFilter.status">
              <option v-for="option in statusOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
            </select>
          </label>
          <label class="d-picker" aria-label="优先级筛选">
            <select v-model="defectFilter.priority">
              <option value="ALL">全部优先级</option>
              <option v-for="item in defectFilterOptions.priorityOptions" :key="item" :value="item">{{ item }}</option>
            </select>
          </label>
          <label class="r-search">
            <Search :size="15" aria-hidden="true" />
            <input v-model="defectFilter.keyword" type="search" aria-label="搜索缺陷" placeholder="搜索概要或编号" />
          </label>
          <button type="button" class="api-clear" data-action="more-filters" :aria-expanded="moreFiltersOpen" aria-controls="defect-extra-filters" @click="moreFiltersOpen = !moreFiltersOpen">更多筛选</button>
          <button type="button" class="api-clear" data-action="reset-filters" @click="resetFilters">重置</button>
        </div>

        <div v-show="moreFiltersOpen" id="defect-extra-filters" class="d-extra-filters">
          <label class="d-picker" aria-label="经办人筛选">
            <select v-model="defectFilter.assignee">
              <option value="ALL">全部经办人</option>
              <option v-for="item in defectFilterOptions.assigneeOptions" :key="item" :value="item">{{ item }}</option>
            </select>
          </label>
          <label class="d-picker" aria-label="问题类型筛选">
            <select v-model="defectFilter.issueType">
              <option value="ALL">全部类型</option>
              <option v-for="item in defectFilterOptions.issueTypeOptions" :key="item" :value="item">{{ item }}</option>
            </select>
          </label>
        </div>

        <section class="report-table-box">
          <header class="r-panel-head d-table-top">
            <div class="d-table-heading">
              <h2>{{ isDraftView ? '缺陷草稿' : 'Jira 问题 · 只读' }}</h2>
              <span>{{ filteredDefectIssues.length }} 条</span>
              <span class="d-scroll-hint d-list-hint">左右滑动表格查看更多列</span>
            </div>
            <div v-if="!isDraftView" class="d-table-actions">
              <button type="button" disabled title="Jira 视图不提供草稿导出或删除">导出选中/筛选</button>
              <button type="button" disabled class="d-danger-link" title="Jira 视图不提供草稿导出或删除">删除选中/筛选</button>
            </div>
            <div v-else class="d-table-actions">
              <div v-if="selectedIds.size" class="d-selection-note">
                <span>已选 {{ selectedIds.size }} 条</span>
                <button type="button" data-action="clear-selection" @click="clearSelection">取消选择</button>
              </div>
              <button type="button" data-action="export-scope" :disabled="defectReadBlocked || !scopeCount" @click="exportSelectedDefectsToJiraCsv">
                导出{{ selectedIds.size ? '已选' : '筛选' }} {{ scopeCount }} 条
              </button>
              <button type="button" data-action="export-json-scope" :disabled="defectReadBlocked || !scopeCount" @click="exportDefectListJson">
                JSON{{ selectedIds.size ? '已选' : '筛选' }} {{ scopeCount }} 条
              </button>
              <button type="button" data-action="delete-scope" class="d-danger-link" :disabled="defectReadBlocked || !scopeCount" @click="removeScopedDefects">
                删除{{ selectedIds.size ? '已选' : '筛选' }} {{ scopeCount }} 条
              </button>
            </div>
          </header>

          <div ref="listScroll" class="r-scroll" data-testid="defect-list-scroll" role="region" tabindex="0" aria-label="缺陷表格，可横向和纵向滚动" @scroll.passive="persistListScroll">
            <table class="r-table d-table">
              <thead>
                <tr>
                  <th class="d-check-cell">
                    <input
                      v-if="isDraftView"
                      ref="selectAllRef"
                      type="checkbox"
                      aria-label="选择当前页草稿"
                      :disabled="pageRows.length === 0"
                      :checked="pageRows.length > 0 && pageSelectedCount === pageRows.length"
                      @change="togglePage(($event.target as HTMLInputElement).checked)"
                    >
                    <template v-else>—</template>
                  </th>
                  <th>概要 / 编号</th>
                  <th>{{ isDraftView ? '草稿阶段' : 'Jira 状态' }}</th>
                  <th>项目 / 类型</th>
                  <th>优先级</th>
                  <th>经办人</th>
                  <th>{{ isDraftView ? '来源用例 / 记录' : 'Jira Key' }}</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in pageRows" :key="row.id" :class="{ selected: selectedIds.has(row.draft.defectId), 'is-recently-transferred': recentlyTransferredDefectIds.includes(row.draft.defectId) }">
                  <td class="d-check-cell">
                    <input
                      v-if="row.sourceKind === 'draft'"
                      type="checkbox"
                      :aria-label="`选择 ${row.draft.defectId}`"
                      :checked="selectedIds.has(row.draft.defectId)"
                      @change="toggleRow(row, ($event.target as HTMLInputElement).checked)"
                    >
                    <span v-else class="d-jira-check">只读</span>
                  </td>
                  <td>
                    <button type="button" class="r-name" :data-opener="rowOpenerId(row)" :title="row.summary" @click="openDetail(row, $event.currentTarget as HTMLElement)">
                      {{ row.summary }}
                    </button>
                    <small>{{ row.sourceKind === 'draft' ? row.draft.defectId : row.issueKey }}</small>
                  </td>
                  <td><span class="d-tag" :class="statusTone(row)">{{ row.displayStatus }}</span></td>
                  <td>{{ row.projectName }}<small>{{ row.issueType }}</small></td>
                  <td>{{ row.priority || '—' }}</td>
                  <td>{{ row.assignee || '—' }}</td>
                  <td>
                    <template v-if="row.sourceKind === 'draft'">{{ row.draft.sourceCaseId || '—' }}<small>{{ row.draft.sourceReportCaseId || '—' }}</small></template>
                    <template v-else>{{ row.issueKey }}</template>
                  </td>
                  <td>
                    <button type="button" class="r-link" :data-opener="rowOpenerId(row)" @click="openDetail(row, $event.currentTarget as HTMLElement)">查看详情 ↗</button>
                  </td>
                </tr>
                <tr v-if="pageRows.length === 0">
                  <td colspan="8" class="r-empty">
                    <h3>{{ defectReadBlocked ? '草稿读取失败，写入已停止' : `没有匹配的${isDraftView ? '草稿' : 'Jira 问题'}` }}</h3>
                    <p>调整筛选或搜索条件。</p>
                    <button type="button" class="r-link" @click="resetFilters">重置筛选</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <footer class="r-pagination">
            <span>共 {{ filteredDefectIssues.length }} 条{{ filteredDefectIssues.length ? ` · ${firstRow}–${lastRow} 条` : '' }}</span>
            <div>
              <span>{{ defectPage }} / {{ defectPageCount }}</span>
              <button type="button" data-action="prev-page" :disabled="defectPage === 1" @click="defectPage -= 1">上一页</button>
              <button type="button" data-action="next-page" :disabled="defectPage >= defectPageCount" @click="defectPage += 1">下一页</button>
            </div>
          </footer>
        </section>

        <footer class="r-footer">
          <span>{{ isDraftView ? '不勾选时针对当前筛选的全部草稿，不只当前页。' : 'Jira 记录只读；不能在此编辑、删除或导出为草稿。' }}</span>
          <span class="d-notice">{{ isDraftView ? '导出为 CSV 不会自动建 Jira 单' : '此视图不发起 Jira 写入' }}</span>
        </footer>
        <section v-if="defectImportPreview" class="d-import-preview" data-testid="defect-import-preview" role="dialog" aria-label="导入缺陷预览">
          <h2>导入预览</h2>
          <p>待导入 {{ defectImportPreview.drafts.length }} 条；不会覆盖已有同标识草稿。</p>
          <p v-if="defectImportPreview.conflicts.length" class="api-error">发现 {{ defectImportPreview.conflicts.length }} 条冲突，已禁止写入。</p>
          <p v-if="importError" class="api-error">{{ importError }}</p>
          <div class="drawer-actions">
            <button type="button" class="btn light" data-action="cancel-import" @click="cancelDefectImport">取消</button>
            <button type="button" class="btn primary" data-action="confirm-import" :disabled="defectImportPreview.conflicts.length > 0" @click="confirmDefectImport">确认导入</button>
          </div>
        </section>
        </div>
      </section>
    </template>

    <section v-else class="defect-page__detail report-page" data-testid="defect-detail" aria-label="缺陷详情">
      <header class="page-head">
        <div>
          <button type="button" class="r-back" data-action="back-list" @click="backToList">‹ 返回缺陷列表</button>
          <h1>{{ detailDefect.summary }}</h1>
          <p>{{ detailDefect.sourceKind === 'draft' ? detailDefect.draft.defectId : detailDefect.issueKey }} · {{ detailDefect.sourceKind === 'draft' ? '本地草稿' : 'Jira 只读记录' }}</p>
        </div>
        <div class="head-actions">
          <template v-if="detailDefect.sourceKind === 'draft' && !defectEditVisible">
            <button type="button" class="btn light" data-action="edit-draft" @click="beginEdit">编辑草稿</button>
            <button type="button" class="btn light" data-action="delete-one" @click="deleteDetailDraft">删除草稿</button>
          </template>
        </div>
      </header>

      <div class="result-summary">
        <span class="d-tag" :class="statusTone(detailDefect)">{{ detailDefect.displayStatus }}</span>
        <span>{{ detailDefect.projectName }} · {{ detailDefect.issueKey || '未关联 Jira Key' }}</span>
        <span>{{ detailDefect.sourceKind === 'draft' ? '草稿仅保存在本地' : 'Jira 问题只读' }}</span>
      </div>

      <div class="r-scroll d-detail-scroll" data-testid="defect-detail-scroll" tabindex="0" role="region" aria-label="缺陷详情与编辑表单">
        <div class="d-detail-grid">
          <section class="d-detail-main">
            <h2>缺陷信息</h2>
            <div class="d-form">
              <div class="wide">
                <label for="detail-summary">概要</label>
                <input v-if="defectEditVisible" id="detail-summary" v-model="defectEditForm.summary" aria-label="概要" required>
                <p v-else class="read-value">{{ detailDefect.summary || '—' }}</p>
              </div>
              <div>
                <label for="detail-project">项目名称</label>
                <input v-if="defectEditVisible" id="detail-project" v-model="defectEditForm.projectName" aria-label="项目名称">
                <p v-else class="read-value">{{ detailDefect.projectName || '—' }}</p>
              </div>
              <div>
                <label for="detail-type">问题类型</label>
                <input v-if="defectEditVisible" id="detail-type" v-model="defectEditForm.issueType" aria-label="问题类型">
                <p v-else class="read-value">{{ detailDefect.issueType || '—' }}</p>
              </div>
              <div>
                <label for="detail-priority">优先级</label>
                <input v-if="defectEditVisible" id="detail-priority" v-model="defectEditForm.priority" aria-label="优先级">
                <p v-else class="read-value">{{ detailDefect.priority || '—' }}</p>
              </div>
              <div>
                <label for="detail-assignee">经办人</label>
                <input v-if="defectEditVisible" id="detail-assignee" v-model="defectEditForm.assignee" aria-label="经办人">
                <p v-else class="read-value">{{ detailDefect.assignee || '—' }}</p>
              </div>
              <div>
                <label for="detail-fault">故障分类</label>
                <input v-if="defectEditVisible" id="detail-fault" v-model="defectEditForm.faultCategory" aria-label="故障分类">
                <p v-else class="read-value">{{ detailDefect.draft.faultCategory || '—' }}</p>
              </div>
              <div class="wide">
                <label for="detail-blocks">向外链接的问题（BLOCK）</label>
                <input v-if="defectEditVisible" id="detail-blocks" v-model="defectEditForm.blocksIssueKey" aria-label="向外链接的问题（BLOCK）">
                <p v-else class="read-value">{{ detailDefect.draft.blocksIssueKey || '—' }}</p>
              </div>
              <div class="wide">
                <label for="detail-description">描述</label>
                <textarea v-if="defectEditVisible" id="detail-description" v-model="defectEditForm.description" aria-label="描述" rows="6" />
                <p v-else class="read-value">{{ detailDefect.description || '—' }}</p>
              </div>
            </div>
            <p v-if="defectEditError" class="api-error" role="alert">{{ defectEditError }}</p>

            <section v-if="hasEvidence" class="d-evidence" data-testid="defect-evidence" aria-label="执行步骤与证据">
              <h2>Web 步骤与证据</h2>
              <p v-if="detailDraft?.failedStepId" class="d-evidence-failed" data-testid="defect-evidence-failed-step">
                失败步骤: {{ detailDraft.failedStepId }}
              </p>
              <p v-if="detailDraft?.pageName || detailDraft?.startUrl" class="d-evidence-meta">
                <span>{{ detailDraft?.pageName || '—' }}</span>
                <span>{{ detailDraft?.startUrl || '—' }}</span>
                <span v-if="typeof detailDraft?.durationMs === 'number'">耗时 {{ detailDraft.durationMs }} ms</span>
              </p>

              <div v-if="evidenceStepLogs.length" class="d-step-list">
                <div
                  v-for="log in evidenceStepLogs"
                  :key="log.stepId"
                  class="d-step-row"
                  :class="stepTone(log)"
                  data-testid="defect-step-row"
                >
                  <span class="d-step-id">{{ log.stepId }}</span>
                  <span class="d-step-action">{{ log.action || '—' }}</span>
                  <span class="d-step-target">{{ log.target || '—' }}</span>
                  <span class="d-step-status">{{ log.status || '—' }}</span>
                  <span v-if="typeof log.durationMs === 'number'" class="d-step-duration">{{ log.durationMs }} ms</span>
                  <span v-if="log.errorMessage" class="d-step-error">{{ log.errorMessage }}</span>
                </div>
              </div>

              <div v-if="evidenceItems.length" class="d-evidence-list">
                <div v-for="(item, index) in evidenceItems" :key="`${evidenceLabel(item)}-${index}`" class="d-evidence-item" data-testid="defect-evidence-item">
                  <span class="d-step-id">{{ evidenceLabel(item) }}</span>
                  <span>{{ item.actionType ?? item.action ?? '—' }}</span>
                  <span>{{ item.name ?? '—' }}</span>
                  <span>{{ item.target ?? '—' }}</span>
                  <span>执行：{{ item.execution?.status ?? item.status ?? '—' }}</span>
                  <span v-if="item.execution">耗时 {{ item.execution.durationMs }} ms</span>
                  <span v-if="item.observationStatus !== undefined">观察：{{ item.observationStatus }}</span>
                  <span v-if="item.failureType !== undefined">失败类型：{{ item.failureType }}</span>
                  <span v-if="item.capturedAt">{{ item.capturedAt }}</span>
                  <pre v-if="item.before || item.after || item.resolvedTarget !== undefined" class="d-evidence-raw">{{ JSON.stringify({ before: item.before, after: item.after, resolvedTarget: item.resolvedTarget }, null, 2) }}</pre>
                  <figure v-if="item.screenshotPath">
                    <img v-if="resolveDefectScreenshotPreview(item.screenshotPath).src && !brokenStepScreenshots[index]" :data-testid="`defect-step-screenshot-${index}`" :src="resolveDefectScreenshotPreview(item.screenshotPath).src" alt="步骤证据截图" loading="lazy" @error="brokenStepScreenshots[index] = true">
                    <p v-else :data-testid="`defect-step-screenshot-error-${index}`">{{ brokenStepScreenshots[index] ? '截图无法加载，原始证据已保留' : resolveDefectScreenshotPreview(item.screenshotPath).reason }}</p>
                    <figcaption>{{ item.screenshotPath }}</figcaption>
                  </figure>
                  <span v-if="item.pageUrl" class="d-evidence-url">{{ item.pageUrl }}</span>
                </div>
              </div>

              <figure v-if="evidenceScreenshot" class="d-evidence-shot">
                <img
                  v-if="screenshotPreview.src && !screenshotBroken"
                  data-testid="defect-evidence-screenshot"
                  :src="screenshotPreview.src"
                  alt="失败步骤截图"
                  loading="lazy"
                  @error="screenshotBroken = true"
                >
                <p v-else class="d-evidence-missing" data-testid="defect-evidence-screenshot-missing">
                  {{ screenshotBroken ? '截图无法加载' : screenshotPreview.reason }}：{{ evidenceScreenshot }}
                </p>
                <figcaption data-testid="defect-evidence-screenshot-path">截图：{{ evidenceScreenshot }}</figcaption>
              </figure>

              <p v-if="detailDraft?.evidenceVersion === undefined" class="d-notice">历史证据字段可能不完整；现有原始字段保持不变。</p>
              <details class="d-evidence-raw-panel">
                <summary>完整原始证据 JSON（纯文本）</summary>
                <pre class="d-evidence-raw" data-testid="defect-evidence-raw">{{ evidenceRaw }}</pre>
              </details>
              <template v-if="evidenceDom">
                <h3 class="d-evidence-subtitle">失败页面 DOM 片段</h3>
                <pre class="d-evidence-dom" data-testid="defect-evidence-dom">{{ evidenceDom }}</pre>
              </template>
            </section>
          </section>

          <aside class="d-trace">
            <h2>来源与追溯</h2>
            <dl>
              <div><dt>来源</dt><dd>{{ detailDefect.sourceKind === 'draft' ? '执行结果转草稿' : 'Jira 同步 · 只读' }}</dd></div>
              <div><dt>来源用例</dt><dd>{{ detailDefect.draft.sourceCaseId || '—' }}</dd></div>
              <div><dt>执行报告</dt><dd>{{ detailDefect.draft.sourceReportCaseId || '—' }}</dd></div>
              <div><dt>关联 Jira</dt><dd>{{ detailDefect.issueKey || '尚无关联' }}</dd></div>
              <div><dt>导出状态</dt><dd>{{ detailDefect.sourceKind === 'draft' ? defectStatusText(detailDefect.draft) : '不适用于 Jira 记录' }}</dd></div>
            </dl>
            <p>此处只展示本地关联，不伪造报告证据或 Jira 建单结果。</p>
          </aside>
        </div>
      </div>

      <footer class="d-edit-footer">
        <span :class="{ 'd-dirty': defectEditDirty }">{{ defectEditVisible ? (defectEditDirty ? '有未保存修改' : '编辑中') : '只读预览 · 草稿需要进入编辑后保存' }}</span>
        <div v-if="defectEditVisible">
          <button type="button" class="btn light" data-action="cancel-edit" @click="cancelDefectEdit">取消修改</button>
          <button type="button" class="btn primary" data-action="save-draft" @click="saveEdit">保存草稿</button>
        </div>
      </footer>
    </section>

    <el-drawer v-if="jiraTemplateVisible" v-model="jiraTemplateVisible" class="defect-template-drawer" size="740px" title="Jira 导出字段配置">
      <div class="d-template-body">
        <DefectFailureBanner />
        <div class="d-template-tools">
          <button type="button" class="btn light small-btn" data-action="add-field" @click="addField">
            <Plus :size="14" />
            新增字段
          </button>
          <button type="button" class="btn light small-btn" data-action="reset-fields" @click="resetTemplate">恢复默认</button>
        </div>
        <p class="d-scroll-hint">可横向滚动查看取值键与删除</p>
        <div class="d-template-scroll">
          <table class="r-table d-template-table">
            <thead>
              <tr><th>启用</th><th>表头</th><th>字段来源</th><th>取值键</th><th>操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="field in jiraCsvTemplateDraft" :key="field.key">
                <td>
                  <input type="checkbox" :aria-label="`启用 ${field.header}`" :checked="field.enabled !== false" @change="updateJiraCsvTemplateField(field.key, { enabled: ($event.target as HTMLInputElement).checked })">
                </td>
                <td>
                  <input type="text" :aria-label="`${field.header} 表头`" :value="field.header" @input="updateJiraCsvTemplateField(field.key, { header: ($event.target as HTMLInputElement).value })">
                  <small>{{ field.source.kind === 'draftField' ? '内置草稿字段' : 'Jira 自定义字段映射' }}</small>
                </td>
                <td><span class="d-chip">{{ field.source.kind === 'draftField' ? '内置字段' : '自定义字段' }}</span></td>
                <td>
                  <input
                    type="text"
                    :aria-label="`${field.header} 取值键`"
                    :value="field.source.fieldKey"
                    :disabled="field.source.kind === 'draftField'"
                    @input="updateJiraCsvTemplateField(field.key, { source: { kind: field.source.kind, fieldKey: ($event.target as HTMLInputElement).value } as typeof field.source })"
                  >
                </td>
                <td>
                  <button type="button" :disabled="field.custom !== true" @click="deleteJiraCsvTemplateField(field)">删除</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-if="templateError" class="api-error" role="alert">{{ templateError }}</p>
        <p class="d-notice">导出列映射与本地区域一致；内置字段与自定义映射使用各自命名空间，取值为空不会回退到内置字段。</p>
      </div>
      <template #footer>
        <div class="drawer-actions">
          <button type="button" class="btn light small-btn" data-action="cancel-template" @click="jiraTemplateVisible = false">取消</button>
          <button type="button" class="btn primary small-btn" data-action="save-template" @click="submitTemplate">保存配置</button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style src="../../styles/defect-design.css"></style>

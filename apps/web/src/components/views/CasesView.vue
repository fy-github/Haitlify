<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  Archive,
  ArrowDown,
  ArrowUp,
  Download,
  GitBranch,
  Search,
  Settings,
  SlidersHorizontal,
  Upload
} from '@lucide/vue'
import type { SavedInterfaceCase } from '../../domain/caseRepository'
import type { SavedWebAutomationCase } from '../../domain/webAutomationCase'
import { useCases } from '../../ui/useCases'
import { useTemplateConfig } from '../../ui/useTemplateConfig'
import {
  caseLibraryPreferenceOwner,
  createCaseLibraryColumns
} from '../../ui/useCaseLibraryColumns'
import DataPagination from '../ui/DataPagination.vue'

const {
  savedCases,
  savedWebCases,
  libraryCaseKind,
  libraryFilter,
  librarySelection,
  libraryPage,
  libraryPageSize,
  selectedCase,
  activeLibraryProjectOptions,
  activeLibraryModules,
  activeLibraryCaseTypes,
  activeLibraryTotal,
  designLocatorProgress,
  pagedLibraryCases,
  pagedWebLibraryCases,
  bulkEditableFields,
  hasFilteredDesignOriginWebLibraryCases,
  bulkEditVisible,
  bulkEditForm,
  bulkTargetCases,
  fieldAssignmentVisible,
  fieldAssignmentForm,
  jsonFieldAssignments,
  fieldCellLabel,
  refreshSavedCases,
  backupProjectData,
  onProjectDataSelected,
  exportSavedCsv,
  exportDesignOriginSummary,
  openAutomationGenerateDialog,
  openBulkEditDialog,
  openFieldAssignmentDialog,
  confirmFilteredDesignLocators,
  deleteSelectedLibraryCases,
  deleteLibraryCasesByScope,
  moveLibraryCase,
  moveWebLibraryCase,
  openEdit,
  openWebEdit,
  openCurlPreview,
  convertWebLibraryCaseToAutomation,
  deleteLibraryCase,
  deleteWebLibraryCase,
  confirmBulkEdit,
  selectFieldAssignment,
  confirmFieldAssignment,
  fieldCellText,
  isLibraryRowSelected,
  toggleLibraryRow,
  toggleCurrentPageSelection,
  currentPageSelectionState,
  clearLibrarySelection
} = useCases()

const { interfaceFields, webAutomationFields } = useTemplateConfig()

const isInterface = computed(() => libraryCaseKind.value === 'interface')
const columnKind = computed<'interface' | 'web_automation'>(() => (isInterface.value ? 'interface' : 'web_automation'))
const sourceCases = computed(() => (isInterface.value ? savedCases.value : savedWebCases.value))

/**
 * 个人 × 类别的可见列偏好(保持模板顺序):ID/标题强制可见,
 * 其余字段全部按偏好铺开,不再用常用列白名单截断;隐藏字段仍在详情里。
 */
const columns = createCaseLibraryColumns({
  storage: window.localStorage,
  userId: caseLibraryPreferenceOwner(window.localStorage),
  fieldsFor: (kind) => (kind === 'interface' ? interfaceFields.value : webAutomationFields.value)
})

/** 设计稿左目录:项目 → 模块两级,计数来自本地用例 */
const projectTree = computed(() => {
  const projects = new Map<string, { name: string; count: number; modules: Map<string, number> }>()
  for (const item of sourceCases.value) {
    const row = (item as { row?: { project?: string; module?: string } }).row
    const project = row?.project || '未分类'
    const module = row?.module || '未分类'
    const entry = projects.get(project) ?? { name: project, count: 0, modules: new Map<string, number>() }
    entry.count += 1
    entry.modules.set(module, (entry.modules.get(module) ?? 0) + 1)
    projects.set(project, entry)
  }
  return [...projects.values()]
    .sort((left, right) => left.name.localeCompare(right.name))
    .map((project) => ({
      name: project.name,
      count: project.count,
      modules: [...project.modules.entries()]
        .sort((left, right) => left[0].localeCompare(right[0]))
        .map(([name, count]) => ({ name, count }))
    }))
})

const expandedProject = ref('')
watch(() => libraryFilter.project, (project) => {
  expandedProject.value = project === 'ALL' ? '' : project
}, { immediate: true })

const selectProject = (project: string): void => {
  libraryFilter.project = project
  libraryFilter.module = 'ALL'
  libraryPage.value = 1
  expandedProject.value = project === 'ALL' ? '' : project
}

const selectModule = (project: string, module: string): void => {
  libraryFilter.project = project
  libraryFilter.module = module
  libraryPage.value = 1
}

const resetFilters = (): void => {
  libraryFilter.project = 'ALL'
  libraryFilter.module = 'ALL'
  libraryFilter.caseType = 'ALL'
  libraryFilter.keyword = ''
  libraryPage.value = 1
}

/** 表格区标题 = 目录范围(模块优先,带上级项目),根节点显式写「全部用例」 */
const scopeHeading = computed(() => {
  if (libraryFilter.module !== 'ALL') return libraryFilter.module
  if (libraryFilter.project !== 'ALL') return libraryFilter.project
  return '全部用例'
})
const scopeParent = computed(() => (libraryFilter.module !== 'ALL' ? libraryFilter.project : ''))

const selectionRows = computed(() => librarySelection.value as SavedInterfaceCase[])
const selectionCount = computed(() => selectionRows.value.length)

const isRowSelected = isLibraryRowSelected
const toggleRow = (row: SavedInterfaceCase | SavedWebAutomationCase, checked: boolean): void =>
  toggleLibraryRow(row, checked)
const clearSelection = (): void => clearLibrarySelection()

const formatSavedAt = (value: string): string => (value || '').slice(0, 16).replace('T', ' ')

const COLUMN_WIDTH: Record<string, number> = {
  title: 280,
  project: 140,
  module: 120,
  pageName: 150,
  caseType: 104,
  priority: 84,
  executionResult: 104,
  method: 96,
  path: 220,
  startUrl: 190,
  browser: 110,
  viewport: 110,
  interfaceName: 180
}
const columnWidth = (key: string): number => COLUMN_WIDTH[key] ?? 170

const dataColumns = computed(() => columns.visibleFields(columnKind.value).filter((field) => field.key !== 'caseId'))

/** pagedWebLibraryCases 的静态类型是联合类型(接口/Web),这里按当前 Web 视图收窄 */
const webRows = computed(() => pagedWebLibraryCases.value as unknown as SavedWebAutomationCase[])
const pageRows = computed<Array<SavedInterfaceCase | SavedWebAutomationCase>>(() =>
  isInterface.value ? pagedLibraryCases.value : webRows.value
)
const headerSelection = computed(() => currentPageSelectionState(pageRows.value))
const togglePageSelection = (checked: boolean): void => toggleCurrentPageSelection(pageRows.value, checked)

const openColumnsDialog = (): void => columns.openDialog(columnKind.value)

// 行点击仍记录当前行(原 el-table @row-click 行为),标题点击才是编辑入口
const selectRow = (row: SavedInterfaceCase | SavedWebAutomationCase): void => {
  selectedCase.value = row
}

const openRowEdit = (row: SavedInterfaceCase | SavedWebAutomationCase): void => {
  if (isInterface.value) openEdit(row as SavedInterfaceCase)
  else openWebEdit(row as SavedWebAutomationCase)
}

/** Web 行字段是可选结构,单元格取字符串时单独收口,避免把联合类型强转成接口行 */
const webCellText = (row: Record<string, unknown>, key: string): string => {
  const value = row[key]
  return value === undefined || value === null ? '' : String(value)
}

// 组合式对象的 ref 不在 setup 顶层,模板里不会自动解包,这里显式桥接
const columnsDialogVisible = computed({
  get: () => columns.dialogVisible.value,
  set: (value: boolean) => { columns.dialogVisible.value = value }
})
</script>

<template>
  <header class="settings-page-head cases-head">
    <div>
      <span class="settings-page-kicker">用例库</span>
      <h1>{{ isInterface ? '接口用例' : 'Web 用例' }}</h1>
      <p>用例按项目与模块归档；筛选与排序只影响当前视图，不改动用例内容。</p>
    </div>
    <div class="toolbar">
      <el-button @click="refreshSavedCases">
        <Archive :size="14" />
        刷新
      </el-button>
      <el-button @click="backupProjectData">
        <Download :size="14" />
        备份数据
      </el-button>
      <label class="cases-upload">
        <Upload :size="14" />
        <span>恢复数据</span>
        <input
          class="native-file-input"
          type="file"
          accept=".json,application/json"
          aria-label="恢复数据"
          @change="onProjectDataSelected"
        />
      </label>
      <el-button type="primary" @click="exportSavedCsv">
        <Download :size="14" />
        导出
      </el-button>
    </div>
  </header>

  <section class="cv-workspace" aria-label="用例库">
    <aside class="cv-directory" aria-label="用例目录">
      <div class="cv-dir-head">
        <h2>{{ isInterface ? '接口用例目录' : 'Web 用例目录' }}</h2>
      </div>
      <p class="cv-dir-note">按项目与模块组织；点击项目展开模块。</p>

      <button
        type="button"
        class="cv-tree-row"
        :class="{ active: libraryFilter.project === 'ALL' && libraryFilter.module === 'ALL' }"
        @click="selectProject('ALL')"
      >
        <span class="cv-tree-name">全部用例</span>
        <small class="cv-tree-count">{{ sourceCases.length }}</small>
      </button>

      <template v-for="project in projectTree" :key="project.name">
        <button
          type="button"
          class="cv-tree-row cv-tree-project"
          :class="{ active: libraryFilter.project === project.name && libraryFilter.module === 'ALL' }"
          @click="selectProject(project.name)"
        >
          <span class="cv-tree-name">{{ project.name }}</span>
          <small class="cv-tree-count">{{ project.count }}</small>
        </button>
        <template v-if="expandedProject === project.name">
          <button
            v-for="module in project.modules"
            :key="`${project.name}-${module.name}`"
            type="button"
            class="cv-tree-row cv-tree-module"
            :class="{ active: libraryFilter.project === project.name && libraryFilter.module === module.name }"
            @click="selectModule(project.name, module.name)"
          >
            <span class="cv-tree-name">{{ module.name }}</span>
            <small class="cv-tree-count">{{ module.count }}</small>
          </button>
        </template>
      </template>

      <div class="cv-dir-divider" aria-hidden="true" />
      <button type="button" class="cv-tree-row" @click="resetFilters">
        <span class="cv-tree-name">清除筛选</span>
      </button>
    </aside>

    <div class="cv-list">
      <div class="cv-list-top">
        <div class="cv-list-heading">
          <h2>{{ scopeHeading }}</h2>
          <span v-if="scopeParent" class="cv-scope-parent">{{ scopeParent }}</span>
          <span class="cv-scope-label">{{ activeLibraryTotal }} 条</span>
        </div>
        <!-- 批量操作紧贴「显示列」左侧,选中为空时收起,不另起通栏 -->
        <div class="cv-list-tools">
          <button v-if="isInterface" type="button" class="cv-btn" @click="openBulkEditDialog">
            <SlidersHorizontal :size="13" aria-hidden="true" />
            批量修改字段
          </button>
          <button v-if="isInterface" type="button" class="cv-btn" @click="openFieldAssignmentDialog">
            <Settings :size="13" aria-hidden="true" />
            字段赋值
          </button>
          <button type="button" class="cv-btn" @click="openAutomationGenerateDialog">
            <GitBranch :size="13" aria-hidden="true" />
            转自动化
          </button>
          <div v-if="selectionCount" class="cv-batch-actions" role="group" aria-label="已选用例批量操作">
            <strong class="cv-selected-count">已选 {{ selectionCount }} 条</strong>
            <button type="button" class="cv-btn" @click="exportSavedCsv">导出选中</button>
            <button type="button" class="cv-btn is-danger" @click="deleteSelectedLibraryCases">删除勾选</button>
            <button type="button" class="cv-btn cv-clear" @click="clearSelection">清除选择</button>
          </div>
          <button type="button" class="cv-btn cv-columns-btn" @click="openColumnsDialog">
            <Settings :size="13" aria-hidden="true" />
            显示列
            <span class="cv-column-count">{{ dataColumns.length + 1 }}</span>
          </button>
        </div>
      </div>

      <div class="cv-filter-strip">
        <label class="cv-search">
          <Search :size="14" aria-hidden="true" />
          <input
            v-model="libraryFilter.keyword"
            type="search"
            :placeholder="isInterface ? '搜索已保存用例' : '搜索 Web 自动化用例'"
            aria-label="搜索用例"
          />
        </label>
        <el-select v-model="libraryFilter.project" class="cv-chip" aria-label="项目筛选">
          <el-option label="全部项目" value="ALL" />
          <el-option v-for="project in activeLibraryProjectOptions" :key="project.value" :label="project.value" :value="project.value" />
        </el-select>
        <el-select v-model="libraryFilter.module" class="cv-chip" aria-label="模块筛选">
          <el-option label="全部模块" value="ALL" />
          <el-option v-for="module in activeLibraryModules" :key="module.value" :label="module.value" :value="module.value" />
        </el-select>
        <el-select v-model="libraryFilter.caseType" class="cv-chip" aria-label="用例类型筛选">
          <el-option label="全部类型" value="ALL" />
          <el-option v-for="caseType in activeLibraryCaseTypes" :key="caseType.value" :label="caseType.value" :value="caseType.value" />
        </el-select>
        <button type="button" class="cv-filter-reset" @click="resetFilters">重置</button>
      </div>

      <div v-if="!isInterface && hasFilteredDesignOriginWebLibraryCases" class="cv-notice-strip">
        <strong>设计稿来源：已确认 {{ designLocatorProgress.confirmedCases }} 条 · Locator {{ designLocatorProgress.confirmedLocators }}/{{ designLocatorProgress.totalLocators }}</strong>
        <button type="button" @click="confirmFilteredDesignLocators">批量确认 locator</button>
        <button type="button" @click="openAutomationGenerateDialog">设计稿转自动化</button>
        <button type="button" class="cv-clear" @click="exportDesignOriginSummary">导出设计稿报表</button>
      </div>

      <div class="cv-scroll" tabindex="0" role="region" aria-label="用例列表，可横向滚动">
        <table v-if="isInterface" class="cv-table">
          <thead>
            <tr>
              <th class="cv-check-col">
                <input
                  type="checkbox"
                  class="cv-checkbox"
                  :checked="headerSelection.checked"
                  :indeterminate.prop="headerSelection.indeterminate"
                  aria-label="全选当前页"
                  @change="togglePageSelection(($event.target as HTMLInputElement).checked)"
                />
              </th>
              <th class="cv-id-col">用例编号</th>
              <th
                v-for="field in dataColumns"
                :key="field.key"
                :style="{ width: `${columnWidth(field.key)}px` }"
              >{{ field.label }}</th>
              <th style="width: 150px">保存时间</th>
              <th class="cv-actions-col" style="width: 190px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in pagedLibraryCases"
              :key="row.libraryId"
              :class="{ 'is-selected': isRowSelected(row) }"
              @click="selectRow(row)"
            >
              <td class="cv-check-col">
                <input
                  type="checkbox"
                  class="cv-checkbox"
                  :checked="isRowSelected(row)"
                  :aria-label="`选择 ${row.row.caseId}`"
                  @change="toggleRow(row, ($event.target as HTMLInputElement).checked)"
                />
              </td>
              <td class="cv-id-col">{{ row.row.caseId }}</td>
              <td v-for="field in dataColumns" :key="field.key">
                <button
                  v-if="field.key === 'title'"
                  type="button"
                  class="cv-title"
                  data-action="edit"
                  :title="fieldCellText(row.row, field.key)"
                  @click="openRowEdit(row)"
                >{{ fieldCellText(row.row, field.key) }}</button>
                <template v-else>{{ fieldCellText(row.row, field.key) }}</template>
              </td>
              <td>{{ formatSavedAt(row.savedAt) }}</td>
              <td class="cv-actions-col">
                <div class="cv-row-actions">
                  <button type="button" class="cv-row-icon" aria-label="上移" title="上移" @click="moveLibraryCase(row, -1)">
                    <ArrowUp :size="13" />
                  </button>
                  <button type="button" class="cv-row-icon" aria-label="下移" title="下移" @click="moveLibraryCase(row, 1)">
                    <ArrowDown :size="13" />
                  </button>
                  <button type="button" class="cv-row-action" @click="openEdit(row)">编辑</button>
                  <button type="button" class="cv-row-action" @click="openCurlPreview(row)">cURL</button>
                  <button type="button" class="cv-row-action is-danger" @click="deleteLibraryCase(row)">删除</button>
                </div>
              </td>
            </tr>
            <tr v-if="!pagedLibraryCases.length">
              <td :colspan="dataColumns.length + 4" class="cv-empty">
                <strong>当前筛选没有用例</strong>
                调整项目、模块或关键词后再试，或在左侧选择「全部用例」。
              </td>
            </tr>
          </tbody>
        </table>

        <table v-else class="cv-table">
          <thead>
            <tr>
              <th class="cv-check-col">
                <input
                  type="checkbox"
                  class="cv-checkbox"
                  :checked="headerSelection.checked"
                  :indeterminate.prop="headerSelection.indeterminate"
                  aria-label="全选当前页"
                  @change="togglePageSelection(($event.target as HTMLInputElement).checked)"
                />
              </th>
              <th class="cv-id-col">用例编号</th>
              <th
                v-for="field in dataColumns"
                :key="field.key"
                :style="{ width: `${columnWidth(field.key)}px` }"
              >{{ field.label }}</th>
              <th style="width: 150px">保存时间</th>
              <th class="cv-actions-col" style="width: 210px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in webRows"
              :key="row.libraryId"
              :class="{ 'is-selected': isRowSelected(row) }"
              @click="selectRow(row)"
            >
              <td class="cv-check-col">
                <input
                  type="checkbox"
                  class="cv-checkbox"
                  :checked="isRowSelected(row)"
                  :aria-label="`选择 ${row.row.caseId}`"
                  @change="toggleRow(row, ($event.target as HTMLInputElement).checked)"
                />
              </td>
              <td class="cv-id-col">{{ row.row.caseId }}</td>
              <td v-for="field in dataColumns" :key="field.key">
                <button
                  v-if="field.key === 'title'"
                  type="button"
                  class="cv-title"
                  data-action="edit"
                  :title="row.row.title"
                  @click="openRowEdit(row)"
                >{{ row.row.title }}</button>
                <template v-else>{{ webCellText(row.row as unknown as Record<string, unknown>, field.key) }}</template>
              </td>
              <td>{{ formatSavedAt(row.savedAt) }}</td>
              <td class="cv-actions-col">
                <div class="cv-row-actions">
                  <button type="button" class="cv-row-icon" aria-label="上移" title="上移" @click="moveWebLibraryCase(row, -1)">
                    <ArrowUp :size="13" />
                  </button>
                  <button type="button" class="cv-row-icon" aria-label="下移" title="下移" @click="moveWebLibraryCase(row, 1)">
                    <ArrowDown :size="13" />
                  </button>
                  <button type="button" class="cv-row-action" @click="openWebEdit(row)">编辑</button>
                  <button type="button" class="cv-row-action" @click="convertWebLibraryCaseToAutomation(row)">转自动化</button>
                  <button type="button" class="cv-row-action is-danger" @click="deleteWebLibraryCase(row)">删除</button>
                </div>
              </td>
            </tr>
            <tr v-if="!webRows.length">
              <td :colspan="dataColumns.length + 4" class="cv-empty">
                <strong>当前筛选没有用例</strong>
                调整项目、模块或关键词后再试，或在左侧选择「全部用例」。
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <DataPagination
        v-model:page="libraryPage"
        v-model:page-size="libraryPageSize"
        :total="activeLibraryTotal"
        :page-sizes="[50, 100, 200]"
        label="用例列表分页"
      />

      <div class="cv-foot">
        <span>共 {{ activeLibraryTotal }} 条 · 每页 {{ libraryPageSize }} 条</span>
        <button type="button" @click="deleteLibraryCasesByScope">删除当前筛选</button>
      </div>
    </div>
  </section>

  <el-dialog v-model="columnsDialogVisible" class="case-columns-dialog" :title="`显示列 · ${isInterface ? '接口用例' : 'Web 用例'}`" width="520px">
    <div class="cv-columns-dialog">
      <p class="cv-columns-note">按个人偏好保存，接口与 Web 各自独立；已隐藏的字段仍可在详情里查看。</p>
      <label
        v-for="field in columns.selectableFields(columnKind)"
        :key="field.key"
        class="cv-columns-row"
      >
        <input
          type="checkbox"
          class="cv-checkbox"
          :data-column="field.key"
          :checked="columns.isDraftVisible(field.key)"
          :disabled="columns.isRequired(columnKind, field.key)"
          @change="columns.toggleDraft(field.key, ($event.target as HTMLInputElement).checked)"
        />
        <span>{{ field.label }}</span>
        <small v-if="columns.isRequired(columnKind, field.key)">必显示</small>
      </label>
    </div>
    <template #footer>
      <div class="drawer-actions case-columns-actions">
        <button type="button" class="cv-btn" data-action="restore" @click="columns.restoreDefaultDraft()">恢复默认</button>
        <button type="button" class="cv-btn" data-action="cancel" @click="columns.cancelDraft()">取消</button>
        <button type="button" class="cv-btn is-primary" data-action="apply" @click="columns.applyDraft()">应用</button>
      </div>
    </template>
  </el-dialog>

  <el-dialog v-model="bulkEditVisible" class="case-bulk-dialog" title="批量修改字段" width="620px">
    <div class="studio-dialog-body">
      <div class="studio-scope-line">
        <el-tag type="info">范围 {{ bulkTargetCases.length }} 条</el-tag>
        <span>优先处理已勾选用例；未勾选时处理当前筛选结果。</span>
      </div>
      <div class="studio-form-grid">
        <label>
          <span>匹配字段</span>
          <el-select v-model="bulkEditForm.matchField">
            <el-option
              v-for="field in bulkEditableFields"
              :key="field.key"
              :label="field.label"
              :value="field.key"
            />
          </el-select>
        </label>
        <label>
          <span>匹配方式</span>
          <el-segmented
            v-model="bulkEditForm.matchMode"
            :options="[
              { label: '包含', value: 'contains' },
              { label: '等于', value: 'equals' }
            ]"
          />
        </label>
        <label>
          <span>匹配值</span>
          <el-input v-model="bulkEditForm.matchValue" placeholder="只修改匹配到的用例" />
        </label>
        <label>
          <span>修改字段</span>
          <el-select v-model="bulkEditForm.targetField">
            <el-option
              v-for="field in bulkEditableFields"
              :key="field.key"
              :label="field.label"
              :value="field.key"
            />
          </el-select>
        </label>
        <label class="studio-form-wide">
          <span>修改为</span>
          <el-input v-model="bulkEditForm.targetValue" type="textarea" :rows="3" placeholder="新的字段值" />
        </label>
      </div>
    </div>
    <template #footer>
      <div class="drawer-actions case-bulk-actions">
        <el-button @click="bulkEditVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmBulkEdit">确认修改</el-button>
      </div>
    </template>
  </el-dialog>

  <el-dialog v-model="fieldAssignmentVisible" class="case-assignment-dialog" title="字段赋值" width="680px">
    <div class="studio-dialog-body">
      <div class="studio-scope-line">
        <el-tag type="info">范围 {{ bulkTargetCases.length }} 条</el-tag>
        <span>自动识别路径参数、查询参数、请求体中的 JSON 字段。</span>
      </div>
      <div class="studio-form-grid">
        <label class="studio-form-wide">
          <span>字段路径</span>
          <el-select
            :model-value="`${fieldAssignmentForm.fieldKey}:${fieldAssignmentForm.path}`"
            filterable
            placeholder="选择可赋值字段"
            @change="selectFieldAssignment"
          >
            <el-option
              v-for="item in jsonFieldAssignments"
              :key="`${item.fieldKey}:${item.path}`"
              :label="`${fieldCellLabel(item.fieldKey)} / ${item.path}（${item.count}）`"
              :value="`${item.fieldKey}:${item.path}`"
            />
          </el-select>
        </label>
        <label>
          <span>字段来源</span>
          <el-select v-model="fieldAssignmentForm.fieldKey">
            <el-option label="路径参数" value="pathParams" />
            <el-option label="查询参数" value="queryParams" />
            <el-option label="请求体" value="body" />
          </el-select>
        </label>
        <label>
          <span>字段名</span>
          <el-input v-model="fieldAssignmentForm.path" placeholder="例如 filter.owner.id" />
        </label>
        <label class="studio-form-wide">
          <span>赋值为</span>
          <el-input v-model="fieldAssignmentForm.value" type="textarea" :rows="3" placeholder="新的字段值" />
        </label>
      </div>
    </div>
    <template #footer>
      <div class="drawer-actions case-assignment-actions">
        <el-button @click="fieldAssignmentVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmFieldAssignment">确认赋值</el-button>
      </div>
    </template>
  </el-dialog>
</template>

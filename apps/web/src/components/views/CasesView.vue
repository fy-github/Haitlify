<script setup lang="ts">
import {
  Archive,
  ArrowDown,
  ArrowUp,
  CircleCheck,
  Download,
  GitBranch,
  Settings,
  SlidersHorizontal,
  Trash2,
  Upload
} from '@lucide/vue'
import type { SavedInterfaceCase } from '../../domain/caseRepository'
import { useCases } from '../../ui/useCases'

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
  caseKindOptions,
  pagedLibraryCases,
  pagedWebLibraryCases,
  visibleInterfaceFields,
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
  switchLibraryCaseKind,
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
  paginationTotalText,
  fieldColumnWidth,
  fieldCellText
} = useCases()
</script>

<template>
  <header class="topbar">
    <div>
      <h1>用例库</h1>
    </div>
    <div class="toolbar">
      <el-button @click="refreshSavedCases">
        <Archive :size="16" />
        刷新
      </el-button>
      <el-button @click="backupProjectData">
        <Download :size="16" />
        备份数据
      </el-button>
      <label class="upload-control">
        <Upload :size="16" />
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
        <Download :size="16" />
        导出
      </el-button>
    </div>
  </header>

  <section class="library-workspace">
    <div class="library-metrics">
      <div class="metric-card">
        <span>总用例</span>
        <strong>{{ libraryCaseKind === 'interface' ? savedCases.length : savedWebCases.length }}</strong>
      </div>
      <div class="metric-card">
        <span>项目数</span>
        <strong>{{ activeLibraryProjectOptions.length }}</strong>
      </div>
      <div class="metric-card">
        <span>模块数</span>
        <strong>{{ activeLibraryModules.length }}</strong>
      </div>
      <div class="metric-card">
        <span>当前筛选</span>
        <strong>{{ activeLibraryTotal }}</strong>
      </div>
      <div class="metric-card" v-if="libraryCaseKind === 'web_automation' && hasFilteredDesignOriginWebLibraryCases">
        <span>已确认用例</span>
        <strong>{{ designLocatorProgress.confirmedCases }}</strong>
      </div>
      <div class="metric-card" v-if="libraryCaseKind === 'web_automation' && hasFilteredDesignOriginWebLibraryCases">
        <span>已确认 Locator</span>
        <strong>{{ designLocatorProgress.confirmedLocators }} / {{ designLocatorProgress.totalLocators }}</strong>
      </div>
    </div>

    <div class="case-panel library-panel">
      <div class="panel-head stacked">
        <div>
          <strong>{{ libraryCaseKind === 'interface' ? '接口用例' : 'Web 用例' }}</strong>
        </div>
        <div class="case-filters compact-toolbar">
          <div class="filter-fields">
            <el-select v-model="libraryCaseKind" placeholder="用例类别" @change="switchLibraryCaseKind">
              <el-option
                v-for="item in caseKindOptions"
                :key="item.value"
                :label="item.enabled ? item.label : `${item.label}（待接入）`"
                :value="item.value"
                :disabled="!item.enabled"
              />
            </el-select>
            <el-select v-model="libraryFilter.project" placeholder="项目">
              <el-option label="全部项目" value="ALL" />
              <el-option
                v-for="project in activeLibraryProjectOptions"
                :key="project.value"
                :label="project.value"
                :value="project.value"
              />
            </el-select>
            <el-select v-model="libraryFilter.module" placeholder="模块">
              <el-option label="全部模块" value="ALL" />
              <el-option v-for="module in activeLibraryModules" :key="module.value" :label="module.value" :value="module.value" />
            </el-select>
            <el-select v-model="libraryFilter.caseType" placeholder="用例类型">
              <el-option label="全部类型" value="ALL" />
              <el-option
                v-for="caseType in activeLibraryCaseTypes"
                :key="caseType.value"
                :label="caseType.value"
                :value="caseType.value"
              />
            </el-select>
            <el-input
              v-model="libraryFilter.keyword"
              clearable
              :placeholder="libraryCaseKind === 'interface' ? '搜索已保存用例' : '搜索 Web 自动化用例'"
            />
          </div>
        </div>
        <div class="panel-action-bar">
          <div class="panel-action-main">
            <div class="action-cluster">
              <el-button @click="openAutomationGenerateDialog">
                <GitBranch :size="16" />
                转自动化
              </el-button>
              <el-button @click="openBulkEditDialog">
                <SlidersHorizontal :size="16" />
                批量修改字段
              </el-button>
              <el-button @click="openFieldAssignmentDialog">
                <Settings :size="16" />
                字段赋值
              </el-button>
            </div>
            <div v-if="libraryCaseKind === 'web_automation' && hasFilteredDesignOriginWebLibraryCases" class="action-cluster action-cluster-accent">
              <el-button
                type="primary"
                plain
                @click="confirmFilteredDesignLocators"
              >
                <CircleCheck :size="16" />
                批量确认 locator
              </el-button>
              <el-button
                type="primary"
                plain
                @click="openAutomationGenerateDialog"
              >
                <GitBranch :size="16" />
                设计稿转自动化
              </el-button>
              <el-button
                plain
                @click="exportDesignOriginSummary"
              >
                <Download :size="16" />
                导出设计稿报表
              </el-button>
            </div>
          </div>
          <div class="action-cluster action-cluster-danger">
            <el-button @click="deleteSelectedLibraryCases">
              <Trash2 :size="16" />
              删除勾选
            </el-button>
            <el-button type="danger" @click="deleteLibraryCasesByScope">
              <Trash2 :size="16" />
              删除当前筛选
            </el-button>
          </div>
        </div>
      </div>

      <div class="table-region">
        <el-table
          v-if="libraryCaseKind === 'interface'"
          class="case-table"
          :data="pagedLibraryCases"
          height="100%"
          border
          @row-click="selectedCase = $event"
          @selection-change="(rows: SavedInterfaceCase[]) => (librarySelection = rows)"
        >
          <el-table-column type="selection" width="48" fixed />
          <el-table-column
            v-for="field in visibleInterfaceFields"
            :key="field.key"
            :label="field.label"
            :width="fieldColumnWidth(field.key)"
            :min-width="field.key === 'title' ? 240 : undefined"
            :fixed="field.key === 'caseId' ? 'left' : false"
          >
            <template #default="{ row }">
              {{ fieldCellText(row.row, field.key) }}
            </template>
          </el-table-column>
          <el-table-column prop="savedAt" label="保存时间" width="190" />
          <el-table-column label="操作" width="214" fixed="right">
            <template #default="{ row }">
              <div class="table-actions">
                <el-button link aria-label="上移" title="上移" @click.stop="moveLibraryCase(row, -1)">
                  <ArrowUp :size="14" />
                </el-button>
                <el-button link aria-label="下移" title="下移" @click.stop="moveLibraryCase(row, 1)">
                  <ArrowDown :size="14" />
                </el-button>
                <el-button link @click.stop="openEdit(row)">编辑</el-button>
                <el-button link @click.stop="openCurlPreview(row)">cURL</el-button>
                <el-button link type="danger" @click.stop="deleteLibraryCase(row)">
                  <Trash2 :size="14" />
                </el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <el-table
          v-else
          class="case-table"
          :data="pagedWebLibraryCases"
          height="100%"
          border
        >
          <el-table-column prop="row.caseId" label="用例ID" width="120" fixed="left" />
          <el-table-column prop="row.module" label="模块" min-width="120" />
          <el-table-column prop="row.pageName" label="页面" min-width="150" />
          <el-table-column prop="row.title" label="用例标题" min-width="260" />
          <el-table-column prop="row.caseType" label="用例类型" width="120" />
          <el-table-column prop="row.startUrl" label="访问地址" min-width="180" />
          <el-table-column prop="row.browser" label="浏览器" width="110" />
          <el-table-column prop="row.viewport" label="视口" width="120" />
          <el-table-column prop="savedAt" label="保存时间" width="190" />
          <el-table-column label="操作" width="280" fixed="right">
            <template #default="{ row }">
              <div class="table-actions">
                <el-button link aria-label="上移" title="上移" @click.stop="moveWebLibraryCase(row, -1)">
                  <ArrowUp :size="14" />
                </el-button>
                <el-button link aria-label="下移" title="下移" @click.stop="moveWebLibraryCase(row, 1)">
                  <ArrowDown :size="14" />
                </el-button>
                <el-button link @click.stop="openWebEdit(row)">编辑</el-button>
                <el-button link type="primary" @click.stop="convertWebLibraryCaseToAutomation(row)">
                  转自动化
                </el-button>
                <el-button link type="danger" @click.stop="deleteWebLibraryCase(row)">
                  <Trash2 :size="14" />
                </el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div class="table-pagination">
        <span class="pagination-total">{{ paginationTotalText(activeLibraryTotal) }}</span>
        <el-pagination
          v-model:current-page="libraryPage"
          v-model:page-size="libraryPageSize"
          :page-sizes="[50, 100, 200]"
          :total="activeLibraryTotal"
          layout="sizes, prev, pager, next"
          :pager-count="7"
          prev-text="上一页"
          next-text="下一页"
        />
      </div>
    </div>
  </section>

  <el-dialog v-model="bulkEditVisible" title="批量修改字段" width="620px">
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
      <div class="drawer-actions">
        <el-button @click="bulkEditVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmBulkEdit">确认修改</el-button>
      </div>
    </template>
  </el-dialog>

  <el-dialog v-model="fieldAssignmentVisible" title="字段赋值" width="680px">
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
      <div class="drawer-actions">
        <el-button @click="fieldAssignmentVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmFieldAssignment">确认赋值</el-button>
      </div>
    </template>
  </el-dialog>
</template>

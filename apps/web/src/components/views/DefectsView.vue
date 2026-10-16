<script setup lang="ts">
import { Settings, Download, Trash2, Plus } from '@lucide/vue'
import type { DefectIssueRecord } from '../../domain/defectView'
import { useDefects } from '../../ui/useDefects'

const {
  jiraProjectName,
  jiraIssueType,
  jiraTemplateVisible,
  defectFilter,
  defectProjectOptions,
  defectFilterOptions,
  filteredDefectIssues,
  defectEditVisible,
  defectEditForm,
  jiraCsvTemplate,
  exportSelectedDefectsToJiraCsv,
  removeScopedDefects,
  handleDefectSelectionChange,
  defectIssueStatusTagType,
  isEditableDefectIssue,
  updateDefectField,
  openDefectEdit,
  removeDefect,
  saveDefectEdit,
  addJiraCsvTemplateField,
  resetJiraCsvTemplateConfig,
  updateJiraCsvTemplateField,
  deleteJiraCsvTemplateField,
  saveJiraCsvTemplateConfig,
  recentlyTransferredDefectIds
} = useDefects()

/** 报告「转缺陷」刚创建的草稿行:浅青高亮;用户勾选后由 useDefects 清空 */
const recentTransferRowClassName = ({ row }: { row: DefectIssueRecord }): string =>
  recentlyTransferredDefectIds.value.includes(row.draft.defectId) ? 'is-recently-transferred' : ''
</script>

<template>
  <header class="topbar">
    <div>
      <span class="eyebrow">Defects</span>
      <h1>缺陷</h1>
    </div>
    <div class="toolbar">
      <el-input v-model="jiraProjectName" placeholder="Jira项目名称" class="defect-meta-input" />
      <el-input v-model="jiraIssueType" placeholder="问题类型" class="defect-meta-input" />
      <el-button @click="jiraTemplateVisible = true">
        <Settings :size="16" />
        字段配置
      </el-button>
    </div>
  </header>

  <section class="library-workspace">
    <div class="case-panel library-panel">
      <div class="panel-head">
        <strong>Jira 导出草稿</strong>
      </div>
      <div class="compact-toolbar defect-toolbar">
        <div class="filter-fields defect-filter-fields">
          <el-select v-model="defectFilter.project" placeholder="项目" popper-class="defect-filter-popper">
            <el-option label="全部项目" value="ALL" />
            <el-option v-for="project in defectProjectOptions" :key="project.value" :label="`${project.value} (${project.count})`" :value="project.value" />
          </el-select>
          <el-select v-model="defectFilter.status" placeholder="状态" popper-class="defect-filter-popper">
            <el-option label="全部状态" value="ALL" />
            <el-option label="待导出 Jira" value="pending_jira_export" />
            <el-option label="已导出 Jira CSV" value="exported_jira_csv" />
            <el-option label="已建 Jira" value="created_in_jira" />
          </el-select>
          <el-select v-model="defectFilter.issueType" placeholder="问题类型" popper-class="defect-filter-popper">
            <el-option label="全部类型" value="ALL" />
            <el-option v-for="item in defectFilterOptions.issueTypeOptions" :key="item" :label="item" :value="item" />
          </el-select>
          <el-select v-model="defectFilter.priority" placeholder="优先级" popper-class="defect-filter-popper">
            <el-option label="全部优先级" value="ALL" />
            <el-option v-for="item in defectFilterOptions.priorityOptions" :key="item" :label="item" :value="item" />
          </el-select>
          <el-select v-model="defectFilter.assignee" placeholder="经办人" popper-class="defect-filter-popper">
            <el-option label="全部经办人" value="ALL" />
            <el-option v-for="item in defectFilterOptions.assigneeOptions" :key="item" :label="item" :value="item" />
          </el-select>
          <el-input v-model="defectFilter.keyword" clearable placeholder="搜索缺陷 / Jira Key / 来源记录" />
        </div>
        <div class="filter-actions defect-filter-actions">
          <el-button @click="jiraTemplateVisible = true">
            <Settings :size="16" />
            字段配置
          </el-button>
          <el-button type="primary" @click="exportSelectedDefectsToJiraCsv">
            <Download :size="16" />
            导出选中/筛选
          </el-button>
          <el-button type="danger" plain @click="removeScopedDefects">
            <Trash2 :size="16" />
            删除选中/筛选
          </el-button>
        </div>
      </div>
      <div class="table-region">
        <el-table
          class="case-table"
          :data="filteredDefectIssues"
          height="100%"
          border
          :row-class-name="recentTransferRowClassName"
          @selection-change="handleDefectSelectionChange"
        >
          <el-table-column type="selection" width="48" fixed />
          <el-table-column label="来源" width="110">
            <template #default="{ row }">
              <el-tag type="info" effect="light">
                {{ row.sourceLabel }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="150">
            <template #default="{ row }">
              <el-tag :type="defectIssueStatusTagType(row.sourceKind, row.draft)" effect="light">
                {{ row.displayStatus }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="概要" min-width="260">
            <template #default="{ row }">
              <el-input
                :model-value="row.summary"
                :disabled="!isEditableDefectIssue(row.sourceKind)"
                @change="(value: string) => updateDefectField(row.draft, { summary: value })"
              />
            </template>
          </el-table-column>
          <el-table-column label="项目名称" width="140">
            <template #default="{ row }">
              <el-input
                :model-value="row.projectName"
                :disabled="!isEditableDefectIssue(row.sourceKind)"
                @change="(value: string) => updateDefectField(row.draft, { projectName: value })"
              />
            </template>
          </el-table-column>
          <el-table-column label="问题类型" width="120">
            <template #default="{ row }">
              <el-input
                :model-value="row.issueType"
                :disabled="!isEditableDefectIssue(row.sourceKind)"
                @change="(value: string) => updateDefectField(row.draft, { issueType: value })"
              />
            </template>
          </el-table-column>
          <el-table-column label="优先级" width="100">
            <template #default="{ row }">
              <el-input
                :model-value="row.priority"
                :disabled="!isEditableDefectIssue(row.sourceKind)"
                @change="(value: string) => updateDefectField(row.draft, { priority: value })"
              />
            </template>
          </el-table-column>
          <el-table-column label="经办人" width="120">
            <template #default="{ row }">
              <el-input
                :model-value="row.assignee"
                :disabled="!isEditableDefectIssue(row.sourceKind)"
                @change="(value: string) => updateDefectField(row.draft, { assignee: value })"
              />
            </template>
          </el-table-column>
          <el-table-column label="Jira Key" width="130">
            <template #default="{ row }">
              {{ row.issueKey || '-' }}
            </template>
          </el-table-column>
          <el-table-column label="描述" min-width="300">
            <template #default="{ row }">
              <div class="table-text-block">
                {{ row.description || '-' }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="故障分类" width="150">
            <template #default="{ row }">
              <el-input
                :model-value="row.draft.faultCategory"
                :disabled="!isEditableDefectIssue(row.sourceKind)"
                @change="(value: string) => updateDefectField(row.draft, { faultCategory: value })"
              />
            </template>
          </el-table-column>
          <el-table-column prop="draft.sourceCaseId" label="来源用例" width="120">
            <template #default="{ row }">
              {{ row.draft.sourceCaseId }}
            </template>
          </el-table-column>
          <el-table-column prop="draft.sourceReportCaseId" label="执行记录" width="130">
            <template #default="{ row }">
              {{ row.draft.sourceReportCaseId }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="88" fixed="right">
            <template #default="{ row }">
              <div class="table-actions">
                <el-button
                  v-if="row.sourceKind === 'draft'"
                  link
                  @click="openDefectEdit(row.draft)"
                >
                  编辑
                </el-button>
                <el-button
                  v-if="row.sourceKind === 'draft'"
                  link
                  type="danger"
                  @click="removeDefect(row.draft)"
                >
                  删除
                </el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
  </section>

  <el-dialog v-model="defectEditVisible" title="编辑缺陷" width="680px">
    <div class="studio-form-grid">
      <label>
        <span>概要</span>
        <el-input v-model="defectEditForm.summary" />
      </label>
      <label>
        <span>项目名称</span>
        <el-input v-model="defectEditForm.projectName" />
      </label>
      <label>
        <span>问题类型</span>
        <el-input v-model="defectEditForm.issueType" />
      </label>
      <label>
        <span>优先级</span>
        <el-input v-model="defectEditForm.priority" />
      </label>
      <label>
        <span>经办人</span>
        <el-input v-model="defectEditForm.assignee" />
      </label>
      <label>
        <span>故障分类</span>
        <el-input v-model="defectEditForm.faultCategory" />
      </label>
      <label class="studio-form-wide">
        <span>描述</span>
        <el-input v-model="defectEditForm.description" type="textarea" :rows="5" />
      </label>
      <label class="studio-form-wide">
        <span>向外链接的问题（BLOCK）</span>
        <el-input v-model="defectEditForm.blocksIssueKey" />
      </label>
    </div>
    <template #footer>
      <div class="drawer-actions">
        <el-button @click="defectEditVisible = false">取消</el-button>
        <el-button type="primary" @click="saveDefectEdit">保存修改</el-button>
      </div>
    </template>
  </el-dialog>

  <el-drawer v-model="jiraTemplateVisible" class="defect-template-drawer" size="780px" title="Jira 导出字段配置">
    <section class="defect-template-drawer__body">
      <header class="defect-template-drawer__intro">
        <div>
          <strong>导出模板</strong>
          <span>控制 Jira CSV 的列顺序、名称与启用状态</span>
        </div>
        <div class="defect-template-drawer__actions">
          <el-button @click="addJiraCsvTemplateField">
            <Plus :size="14" />
            新增字段
          </el-button>
          <el-button @click="resetJiraCsvTemplateConfig">恢复默认</el-button>
        </div>
      </header>

      <section class="defect-template-drawer__table-card">
        <el-table class="case-table" :data="jiraCsvTemplate" height="100%" border>
          <el-table-column label="启用" width="76">
            <template #default="{ row }">
              <el-switch
                :model-value="row.enabled !== false"
                @change="(value: boolean) => updateJiraCsvTemplateField(row.key, { enabled: value })"
              />
            </template>
          </el-table-column>
          <el-table-column label="表头" min-width="220">
            <template #default="{ row }">
              <el-input
                :model-value="row.header"
                @change="(value: string) => updateJiraCsvTemplateField(row.key, { header: value })"
              />
            </template>
          </el-table-column>
          <el-table-column label="字段来源" width="140">
            <template #default="{ row }">
              <el-tag :type="row.source.kind === 'draftField' ? 'info' : 'success'" effect="light">
                {{ row.source.kind === 'draftField' ? '内置字段' : '自定义字段' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="取值键" min-width="240">
            <template #default="{ row }">
              <el-input
                :model-value="row.source.fieldKey"
                :disabled="row.source.kind === 'draftField'"
                @change="(value: string) => updateJiraCsvTemplateField(row.key, {
                  source: {
                    ...row.source,
                    fieldKey: value
                  }
                })"
              />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="92" fixed="right">
            <template #default="{ row }">
              <el-button
                link
                type="danger"
                :disabled="row.custom !== true"
                @click="deleteJiraCsvTemplateField(row)"
              >
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <footer class="defect-template-drawer__footer">
        <el-button @click="jiraTemplateVisible = false">取消</el-button>
        <el-button type="primary" @click="saveJiraCsvTemplateConfig">保存配置</el-button>
      </footer>
    </section>
  </el-drawer>
</template>

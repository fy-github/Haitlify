<script setup lang="ts">
import { computed } from 'vue'
import { ArrowLeft, ArrowRight, Archive, FileBraces, FileText, GitBranch, Plus } from '@lucide/vue'
import type { NavKey } from '../../domain/appNavigation'
import type { GenerationGranularity } from '../../domain/caseGenerator'
import type { HttpMethod } from '../../domain/openapiParser'
import type { CaseDesignSourceView } from '../../domain/caseDesignSourceView'
import {
  WORKFLOW_STAGES,
  WORKFLOW_STAGE_MARKS,
  navKeyForStage,
  stageForNavKey,
  type WorkflowStageId
} from '../../ui/navigation/appSidebar'
import InterfaceCaseContextPane from '../workflow/InterfaceCaseContextPane.vue'
import CaseDesignContextPane from '../workflow/CaseDesignContextPane.vue'
import ResourceProjectSelect, { type ResourceProjectOption } from './ResourceProjectSelect.vue'
import ResourceModuleList, { type ResourceModuleOption } from './ResourceModuleList.vue'

interface ImportedProject {
  id: string
  title: string
  operationCount: number
}

interface ProjectListItem {
  value: string
  count: number
}

interface ModuleListItem {
  name: string
  count: number
}

interface CaseTemplateItem {
  id: string
  name: string
  builtIn: boolean
}

const props = defineProps<{
  activeNav: NavKey
  interfaceContextVisible: boolean
  caseDesignContextVisible: boolean
  // 接口上下文(InterfaceCaseContextPane 转发)
  importFilterModule: string
  interfaceModuleOptions: Array<{ value: string; label: string; count?: number }>
  importFilterPathKeyword: string
  importFilterSummaryKeyword: string
  importFilterMethod: HttpMethod | 'ALL'
  generationTemplateId: string
  generationTemplateOptions: Array<{ value: string; label: string }>
  generationGranularity: GenerationGranularity
  // 用例设计上下文(CaseDesignContextPane 转发)
  caseDesignSourceView: CaseDesignSourceView[]
  caseDesignModuleOptions: Array<{ value: string; count: number }>
  caseDesignActiveModule: string
  activeCaseDesignSource: string
  // api 分支
  importedProjects: ImportedProject[]
  activeProjectId: string
  apiModules: ModuleListItem[]
  apiModuleTotal: number
  // cases 分支
  libraryProjects: ProjectListItem[]
  libraryCaseCount: number
  libraryModules: ResourceModuleOption[]
  libraryFilterModule: string
  libraryFilterProject: string
  // automation 分支
  automationKind: 'api' | 'web'
  automationCaseCount: number
  webAutomationCaseCount: number
  automationProjects: ProjectListItem[]
  automationModules: ResourceModuleOption[]
  automationProjectCaseCount: number
  webAutomationProjectCaseCount: number
  automationFilterModule: string
  automationFilterProject: string
  // settings 分支
  caseTemplates: CaseTemplateItem[]
  selectedTemplateId: string
  // aiConfig 分支
  aiConfigReady: boolean
  aiConfigProvider: string
  aiConfigModel: string
  // jiraConfig 分支
  jiraEnabled: boolean
  jiraBaseUrl: string
  jiraUsername: string
  // reports 分支
  reportCount: number
  lastReportAt: string
  reportProjects: ProjectListItem[]
  selectedReportProject: string
  // defects 分支
  defectProjects: ProjectListItem[]
  defectCaseCount: number
  defectFilterProject: string
}>()

const emit = defineEmits<{
  'update-import-module': [value: string]
  'update-import-path-keyword': [value: string]
  'update-import-summary-keyword': [value: string]
  'update-import-method': [value: HttpMethod | 'ALL']
  'update-generation-template-id': [value: string]
  'update-generation-granularity': [value: GenerationGranularity]
  'select-case-design-source': [value: string]
  'select-case-design-module': [value: string]
  'switch-imported-project': [value: string]
  'navigate-to-nav': [nav: NavKey]
  'switch-library-project': [value: string]
  'update-library-module': [value: string]
  'switch-automation-project': [value: string]
  'update-automation-module': [value: string]
  'open-automation-generate-dialog': []
  'create-case-template': []
  'update-selected-template': [value: string]
  'refresh-reports': []
  'switch-report-project': [value: string]
  'switch-defect-project': [value: string]
}>()

/** 各分支原始数组 → 通用组件选项形状(纯展示适配,不改状态) */
const apiProjectOptions = computed<ResourceProjectOption[]>(() =>
  props.importedProjects.map((project) => ({
    value: project.id,
    label: project.title,
    count: project.operationCount
  }))
)
const libraryProjectOptions = computed<ResourceProjectOption[]>(() =>
  props.libraryProjects.map((project) => ({ value: project.value, label: project.value, count: project.count }))
)
const automationProjectOptions = computed<ResourceProjectOption[]>(() =>
  props.automationProjects.map((project) => ({ value: project.value, label: project.value, count: project.count }))
)
const defectProjectOptions = computed<ResourceProjectOption[]>(() =>
  props.defectProjects.map((project) => ({ value: project.value, label: project.value, count: project.count }))
)
const apiModuleOptions = computed<ResourceModuleOption[]>(() =>
  props.apiModules.map((module) => ({ value: module.name, count: module.count }))
)

/** 当前导航对应流程阶段(非流程内导航返回 undefined → 流程链块隐藏) */
const currentStage = computed(() => stageForNavKey(props.activeNav))
const currentStageIndex = computed(() =>
  WORKFLOW_STAGES.findIndex((stage) => stage.id === currentStage.value)
)
const prevStage = computed<WorkflowStageId | undefined>(() =>
  currentStageIndex.value > 0 ? WORKFLOW_STAGES[currentStageIndex.value - 1].id : undefined
)
const nextStage = computed<WorkflowStageId | undefined>(() =>
  currentStageIndex.value >= 0 && currentStageIndex.value < WORKFLOW_STAGES.length - 1
    ? WORKFLOW_STAGES[currentStageIndex.value + 1].id
    : undefined
)

/** 阶段在链中的状态:已完成(小于当前) / 当前 / 后续 */
const stageStatus = (stage: WorkflowStageId): 'done' | 'active' | 'pending' => {
  const index = WORKFLOW_STAGES.findIndex((entry) => entry.id === stage)
  if (index === currentStageIndex.value) return 'active'
  return index < currentStageIndex.value ? 'done' : 'pending'
}

const stageLabel = (stage: WorkflowStageId): string =>
  WORKFLOW_STAGES.find((entry) => entry.id === stage)?.label ?? ''
const stageMark = (stage: WorkflowStageId): string => WORKFLOW_STAGE_MARKS[stage]
</script>

<template>
  <InterfaceCaseContextPane
    v-if="interfaceContextVisible"
    :module="importFilterModule"
    :module-options="interfaceModuleOptions"
    :path-keyword="importFilterPathKeyword"
    :summary-keyword="importFilterSummaryKeyword"
    :method="importFilterMethod"
    :template-id="generationTemplateId"
    :template-options="generationTemplateOptions"
    :granularity="generationGranularity"
    @update-module="emit('update-import-module', $event)"
    @update-path-keyword="emit('update-import-path-keyword', $event)"
    @update-summary-keyword="emit('update-import-summary-keyword', $event)"
    @update-method="emit('update-import-method', $event)"
    @update-template-id="emit('update-generation-template-id', $event)"
    @update-granularity="emit('update-generation-granularity', $event)"
  />
  <CaseDesignContextPane
    v-else-if="caseDesignContextVisible"
    :sources="caseDesignSourceView"
    :modules="caseDesignModuleOptions"
    :active-module="caseDesignActiveModule"
    :active-source="activeCaseDesignSource"
    @select-source="emit('select-case-design-source', $event)"
    @select-module="emit('select-case-design-module', $event)"
  />
  <aside v-else class="resource-pane">
    <div v-if="currentStage" class="pane-flow-chain" aria-label="测试流程链">
      <div class="pane-flow-chain__steps" role="list">
        <button
          v-for="stage in WORKFLOW_STAGES"
          :key="stage.id"
          type="button"
          class="pane-flow-chain__step"
          :class="`is-${stageStatus(stage.id)}`"
          role="listitem"
          :aria-label="`阶段 ${stage.index}：${stageLabel(stage.id)}${stageStatus(stage.id) === 'active' ? '（当前）' : ''}`"
          :title="`${stageMark(stage.id)} ${stageLabel(stage.id)}`"
          @click="emit('navigate-to-nav', navKeyForStage(stage.id))"
        >
          {{ stageMark(stage.id) }}
        </button>
      </div>
      <div class="pane-flow-chain__nav">
        <button
          v-if="prevStage"
          type="button"
          class="pane-flow-chain__nav-btn"
          :aria-label="`上一步：${stageLabel(prevStage)}`"
          @click="emit('navigate-to-nav', navKeyForStage(prevStage))"
        >
          <ArrowLeft :size="13" aria-hidden="true" />
          上一步 · {{ stageLabel(prevStage) }}
        </button>
        <span v-else class="pane-flow-chain__nav-spacer" aria-hidden="true"></span>
        <button
          v-if="nextStage"
          type="button"
          class="pane-flow-chain__nav-btn"
          :aria-label="`下一步：${stageLabel(nextStage)}`"
          @click="emit('navigate-to-nav', navKeyForStage(nextStage))"
        >
          下一步 · {{ stageLabel(nextStage) }}
          <ArrowRight :size="13" aria-hidden="true" />
        </button>
      </div>
    </div>

    <template v-if="activeNav === 'api'">
      <div class="pane-body">
        <ResourceProjectSelect
          v-if="importedProjects.length"
          :model-value="activeProjectId"
          :options="apiProjectOptions"
          @select="emit('switch-imported-project', $event)"
        />

        <ResourceModuleList
          :model-value="importFilterModule"
          :modules="apiModuleOptions"
          :all-count="apiModuleTotal"
          @select="emit('update-import-module', $event)"
        />
      </div>
    </template>

    <template v-else-if="activeNav === 'cases'">
      <div class="pane-body">
        <div class="pane-tools">
          <el-button class="full-button" type="primary" @click="emit('navigate-to-nav', 'api')">
            <FileBraces :size="16" />
            生成新用例
          </el-button>
        </div>

        <ResourceProjectSelect
          :model-value="libraryFilterProject"
          :options="libraryProjectOptions"
          all-label="全部项目"
          :all-count="libraryCaseCount"
          @select="emit('switch-library-project', $event)"
        />

        <ResourceModuleList
          :model-value="libraryFilterModule"
          :modules="libraryModules"
          :all-count="libraryCaseCount"
          @select="emit('update-library-module', $event)"
        />
      </div>
    </template>

    <template v-else-if="activeNav === 'automation'">
      <div class="pane-body">
        <div class="pane-tools">
          <el-button class="full-button" type="primary" @click="emit('open-automation-generate-dialog')">
            <GitBranch :size="16" />
            从用例库生成
          </el-button>
        </div>

        <ResourceProjectSelect
          :model-value="automationFilterProject"
          :options="automationProjectOptions"
          all-label="全部项目"
          :all-count="automationKind === 'api' ? automationCaseCount : webAutomationCaseCount"
          @select="emit('switch-automation-project', $event)"
        />

        <ResourceModuleList
          :model-value="automationFilterModule"
          :modules="automationModules"
          :all-count="automationKind === 'api' ? automationProjectCaseCount : webAutomationProjectCaseCount"
          @select="emit('update-automation-module', $event)"
        />
      </div>
    </template>

    <template v-else-if="activeNav === 'settings'">
      <div class="pane-body pane-body-scroll">
        <div class="pane-tools">
          <el-button class="full-button" type="primary" @click="emit('create-case-template')">
            <Plus :size="16" />
            新增自定义模板
          </el-button>
        </div>

        <div class="resource-list resource-list-bordered resource-list-fill">
          <div class="resource-section-title">用例模板</div>
          <button
            v-for="template in caseTemplates"
            :key="template.id"
            class="resource-item"
            :class="{ active: selectedTemplateId === template.id }"
            @click="emit('update-selected-template', template.id)"
          >
            <el-tooltip :content="template.name" placement="top">
              <span class="resource-item-label">{{ template.name }}</span>
            </el-tooltip>
            <el-tag size="small" :type="template.builtIn ? 'info' : 'success'">
              {{ template.builtIn ? '内置' : '自定义' }}
            </el-tag>
          </button>
        </div>
      </div>
    </template>

    <template v-else-if="activeNav === 'aiConfig'">
      <div class="pane-body pane-body-scroll">
        <div class="resource-summary">
          <span>AI 状态</span>
          <strong>{{ aiConfigReady ? '已就绪' : '待配置' }}</strong>
        </div>
        <div class="resource-summary">
          <span>Provider</span>
          <strong>{{ aiConfigProvider || '-' }}</strong>
        </div>
        <div class="resource-summary">
          <span>Model</span>
          <strong>{{ aiConfigModel || '-' }}</strong>
        </div>
      </div>
    </template>

    <template v-else-if="activeNav === 'jiraConfig'">
      <div class="pane-body pane-body-scroll">
        <div class="resource-summary">
          <span>Jira 状态</span>
          <strong>{{ jiraEnabled ? '已启用' : '未启用' }}</strong>
        </div>
        <div class="resource-summary">
          <span>连接地址</span>
          <strong>{{ jiraBaseUrl || '-' }}</strong>
        </div>
        <div class="resource-summary">
          <span>账号</span>
          <strong>{{ jiraUsername || '-' }}</strong>
        </div>
      </div>
    </template>

    <template v-else-if="activeNav === 'reports'">
      <div class="pane-body pane-body-scroll">
        <div class="pane-tools">
          <el-button class="full-button" @click="emit('refresh-reports')">
            <Archive :size="16" />
            刷新报告
          </el-button>
        </div>

        <div class="resource-summary">
          <span>报告总数</span>
          <strong>{{ reportCount }}</strong>
        </div>
        <div class="resource-summary">
          <span>最近一次</span>
          <strong>{{ lastReportAt }}</strong>
        </div>

        <div class="resource-list resource-list-bordered resource-list-fill">
          <div class="resource-section-title">项目</div>
          <button
            v-for="project in reportProjects"
            :key="project.value"
            class="resource-item"
            :class="{ active: selectedReportProject === project.value }"
            @click="emit('switch-report-project', project.value)"
          >
            <el-tooltip :content="project.value" placement="top">
              <span class="resource-item-label">{{ project.value }}</span>
            </el-tooltip>
            <b>{{ project.count }}</b>
          </button>
          <div v-if="reportProjects.length === 0" class="placeholder-pane compact report-project-placeholder">
            <FileText :size="30" />
            <strong>还没有执行报告</strong>
            <span>执行 API 自动化后会在这里沉淀项目报告</span>
          </div>
        </div>
      </div>
    </template>

    <template v-else-if="activeNav === 'defects'">
      <div class="pane-body pane-body-scroll">
        <ResourceProjectSelect
          :model-value="defectFilterProject"
          :options="defectProjectOptions"
          all-label="全部项目"
          :all-count="defectCaseCount"
          @select="emit('switch-defect-project', $event)"
        />
      </div>
    </template>

    <div v-else class="pane-body placeholder-pane">
      <FileText :size="32" />
      <strong>暂未配置上下文</strong>
      <span>当前页面暂无可筛选内容</span>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { Archive, ArrowDown, ArrowUp, Plus, Settings, SlidersHorizontal, Trash2 } from '@lucide/vue'
import { useTemplateConfig } from '../../ui/useTemplateConfig'
import type { CaseTypeDefinition, FieldType } from '../../domain/caseTemplates'

const {
  selectedTemplate,
  selectedTemplateFields,
  interfaceCaseTypes,
  createField,
  createCaseTemplate,
  createCaseType,
  resetConfig,
  renameCaseTemplate,
  removeCaseTemplate,
  moveField,
  patchField,
  removeField,
  patchCaseType,
  removeCaseType
} = useTemplateConfig()

const patchCaseTypeAppliesTo = (id: string, appliesTo: CaseTypeDefinition['appliesTo']) => patchCaseType(id, { appliesTo })
const patchCaseTypeName = (id: string, name: string) => patchCaseType(id, { name })
const patchCaseTypeEnabled = (id: string, enabled: boolean) => patchCaseType(id, { enabled })
const patchFieldType = (key: string, type: FieldType) => patchField(key, { type })
const patchFieldLabel = (key: string, label: string) => patchField(key, { label })
const patchFieldVisible = (key: string, visible: boolean) => patchField(key, { visible })
const patchFieldExportable = (key: string, exportable: boolean) => patchField(key, { exportable })
const patchFieldAiGenerated = (key: string, aiGenerated: boolean) => patchField(key, { aiGenerated })
const patchFieldRequired = (key: string, required: boolean) => patchField(key, { required })
</script>

<template>
  <header class="topbar">
    <div>
      <span class="eyebrow">Template Settings</span>
      <h1>模板配置</h1>
    </div>
    <div class="toolbar">
      <el-button @click="createField">
        <SlidersHorizontal :size="16" />
        新增字段
      </el-button>
      <el-button @click="createCaseTemplate">
        <Plus :size="16" />
        新增模块
      </el-button>
      <el-button @click="createCaseType">
        <Archive :size="16" />
        新增类型
      </el-button>
      <el-button type="primary" @click="resetConfig">
        <Settings :size="16" />
        恢复默认
      </el-button>
    </div>
  </header>

  <section class="settings-workspace">
    <div class="settings-panel">
      <div class="panel-head">
        <strong>{{ selectedTemplate?.name || '用例模板' }}字段设置</strong>
        <span>{{ selectedTemplate?.builtIn ? '内置模板' : '自定义模板' }} · 控制表格显示、导出和 AI 生成字段</span>
      </div>
      <div v-if="selectedTemplate && !selectedTemplate.builtIn" class="template-edit-row">
        <el-input
          :model-value="selectedTemplate.name"
          placeholder="模板名称"
          @change="(value: string) => selectedTemplate && renameCaseTemplate(selectedTemplate.id, value)"
        />
        <el-button type="danger" plain @click="selectedTemplate && removeCaseTemplate(selectedTemplate)">
          <Trash2 :size="16" />
          删除模板
        </el-button>
      </div>
      <el-table class="case-table" :data="selectedTemplateFields" height="438" border>
        <el-table-column label="排序" width="80" fixed>
          <template #default="{ row }">
            <div style="display:inline-flex;gap:0">
              <el-button link style="padding:4px 6px" aria-label="上移" title="上移" @click="moveField(row.key, -1)"><ArrowUp :size="14" /></el-button>
              <el-button link style="padding:4px 2px 4px 0;margin-left:-2px" aria-label="下移" title="下移" @click="moveField(row.key, 1)"><ArrowDown :size="14" /></el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="key" label="字段Key" width="150" />
        <el-table-column label="中文表头" min-width="160">
          <template #default="{ row }">
            <el-input :model-value="row.label" @change="(value: string) => patchFieldLabel(row.key, value)" />
          </template>
        </el-table-column>
        <el-table-column label="类型" width="130">
          <template #default="{ row }">
            <el-select :model-value="row.type" @change="(value: FieldType) => patchFieldType(row.key, value)">
              <el-option label="文本" value="text" />
              <el-option label="多行文本" value="textarea" />
              <el-option label="选择" value="select" />
              <el-option label="时间" value="datetime" />
              <el-option label="标签" value="tags" />
              <el-option label="JSON" value="json" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="显示" width="82">
          <template #default="{ row }">
            <el-switch :model-value="row.visible" @change="(value: boolean) => patchFieldVisible(row.key, value)" />
          </template>
        </el-table-column>
        <el-table-column label="导出" width="82">
          <template #default="{ row }">
            <el-switch :model-value="row.exportable" @change="(value: boolean) => patchFieldExportable(row.key, value)" />
          </template>
        </el-table-column>
        <el-table-column label="AI生成" width="92">
          <template #default="{ row }">
            <el-switch :model-value="row.aiGenerated" @change="(value: boolean) => patchFieldAiGenerated(row.key, value)" />
          </template>
        </el-table-column>
        <el-table-column label="必填" width="82">
          <template #default="{ row }">
            <el-switch :model-value="row.required" @change="(value: boolean) => patchFieldRequired(row.key, value)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="88" fixed="right">
          <template #default="{ row }">
            <el-button link type="danger" @click="removeField(row.key)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="settings-panel">
      <div class="panel-head">
        <strong>用例类型</strong>
        <span>控制生成时启用的类型和适用范围</span>
      </div>
      <el-table class="case-table" :data="interfaceCaseTypes" height="300" border>
        <el-table-column prop="id" label="类型ID" width="180" />
        <el-table-column label="类型名称" min-width="160">
          <template #default="{ row }">
            <el-input :model-value="row.name" @change="(value: string) => patchCaseTypeName(row.id, value)" />
          </template>
        </el-table-column>
        <el-table-column label="适用范围" width="140">
          <template #default="{ row }">
            <el-select :model-value="row.appliesTo" @change="(value: CaseTypeDefinition['appliesTo']) => patchCaseTypeAppliesTo(row.id, value)">
              <el-option label="全部" value="all" />
              <el-option label="接口用例" value="interface" />
              <el-option label="Web 用例" value="web_automation" />
              <el-option label="APP 用例" value="app_automation" />
              <el-option label="AI 用例" value="ai_test" />
              <el-option label="功能用例" value="functional" />
              <el-option label="性能用例" value="performance" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="90">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled" @change="(value: boolean) => patchCaseTypeEnabled(row.id, value)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="danger" @click="removeCaseType(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

  </section>
</template>

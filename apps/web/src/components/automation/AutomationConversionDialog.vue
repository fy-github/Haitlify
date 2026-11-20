<script setup lang="ts">
import { toRefs } from 'vue'
import type { AutomationConversionDialogModel } from '../../ui/automationConversionModel'

import type { SavedWebAutomationCase } from '../../domain/webAutomationCase'
import type { SavedInterfaceCase } from '../../domain/caseRepository'
import type { AutomationGenerationScope } from '../../domain/automationGenerationScope'
const props = defineProps<{ model: AutomationConversionDialogModel }>()
const { automationGenerateVisible, canEdit, confirmGenerateAutomationFromLibrary, automationGenerateProject, automationGenerationScope, automationGenerateModule } = toRefs(props.model)

</script>

<template>
<el-dialog v-model="automationGenerateVisible" class="web-automation-dialog case-conversion-dialog" title="从用例库生成自动化用例" width="520px">
      <div class="edit-form">
        <label>
          <span>项目</span>
          <el-select v-model="automationGenerateProject" placeholder="项目">
            <el-option label="全部项目" value="ALL" />
            <el-option
              v-for="project in automationGenerationScope.projects"
              :key="project.value"
              :label="`${project.value}（${project.count}）`"
              :value="project.value"
            />
          </el-select>
        </label>
        <label>
          <span>模块</span>
          <el-select v-model="automationGenerateModule" placeholder="模块">
            <el-option label="全部模块" value="ALL" />
            <el-option
              v-for="module in automationGenerationScope.modules"
              :key="module.value"
              :label="`${module.value}（${module.count}）`"
              :value="module.value"
            />
          </el-select>
        </label>
        <div class="request-summary">
          <span class="method-pill">范围</span>
          <code>将生成 {{ automationGenerationScope.cases.length }} 条自动化用例</code>
          <el-tag size="small">保存到自动化库</el-tag>
        </div>
      </div>
      <template #footer>
        <div class="drawer-actions">
          <el-button @click="automationGenerateVisible = false">取消</el-button>
          <el-button type="primary" :disabled="!canEdit" @click="confirmGenerateAutomationFromLibrary">确定生成</el-button>
        </div>
      </template>
    </el-dialog>
</template>

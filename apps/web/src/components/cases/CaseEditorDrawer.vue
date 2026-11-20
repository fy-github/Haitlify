<script setup lang="ts">
import { toRefs } from 'vue'
import type { CaseEditorDrawerModel } from '../../ui/caseEditorModel'
import ResponsiveDrawer from '../shell/ResponsiveDrawer.vue'
import type { GeneratedInterfaceCase } from '../../domain/caseGenerator'
import type { InterfaceCaseRow } from '../../domain/caseGenerator'
import type { GeneratedWebAutomationCase } from '../../domain/webAutomationCase'
import type { SavedWebAutomationCase } from '../../domain/webAutomationCase'
import type { SavedInterfaceCase } from '../../domain/caseRepository'
import type { ExecutionEnvironmentLibrary } from '../../domain/executionEnvironmentRepository'
import type { FieldType } from '../../domain/caseTemplates'
const props = defineProps<{ model: CaseEditorDrawerModel }>()
const { editVisible, selectedCase, visibleEditableFields, isMultilineField, editRow, saveEdit, webEditRow, webExecutionEnvironmentLibrary, applySelectedWebEnvironmentToEditRow } = toRefs(props.model)

</script>

<template>
<ResponsiveDrawer v-model="editVisible" class="case-editor-drawer" :size="selectedCase && 'pageName' in selectedCase.row ? '820px' : '520px'" title="编辑用例">
      <div class="edit-form" v-if="selectedCase && !('pageName' in selectedCase.row)">
        <el-form label-position="top">
          <el-form-item v-for="field in visibleEditableFields" :key="field.key" :label="field.label">
            <el-input
              v-if="isMultilineField(field.type)"
              v-model="editRow[field.key as keyof InterfaceCaseRow]"
              type="textarea"
              :rows="field.key === 'steps' ? 4 : 3"
            />
            <el-input v-else v-model="editRow[field.key as keyof InterfaceCaseRow]" />
          </el-form-item>
        </el-form>
        <div class="drawer-actions">
          <el-button @click="editVisible = false">取消</el-button>
          <el-button type="primary" @click="saveEdit">保存修改</el-button>
        </div>
      </div>
      <div class="edit-form" v-else>
        <el-form label-position="top">
          <div class="web-edit-section">
            <strong>运行配置</strong>
            <div class="web-edit-grid">
              <el-form-item label="运行环境">
                <div class="inline-form-control">
                  <el-select v-model="webEditRow.environmentId" placeholder="选择 Web 运行环境">
                    <el-option
                      v-for="environment in webExecutionEnvironmentLibrary.environments"
                      :key="environment.id"
                      :label="environment.name"
                      :value="environment.id"
                    />
                  </el-select>
                  <el-button @click="applySelectedWebEnvironmentToEditRow">应用运行环境默认值</el-button>
                </div>
              </el-form-item>
              <el-form-item label="应用访问地址">
                <el-input v-model="webEditRow.startUrl" placeholder="/login 或完整 URL" />
              </el-form-item>
              <el-form-item label="浏览器">
                <el-select v-model="webEditRow.browser">
                  <el-option label="Chromium" value="chromium" />
                  <el-option label="Google Chrome" value="chrome" />
                  <el-option label="Microsoft Edge" value="msedge" />
                  <el-option label="Firefox" value="firefox" />
                  <el-option label="WebKit" value="webkit" />
                </el-select>
              </el-form-item>
              <el-form-item label="视口">
                <el-input v-model="webEditRow.viewport" placeholder="1440x900" />
              </el-form-item>
              <el-form-item label="等待策略">
                <el-select v-model="webEditRow.waitStrategy">
                  <el-option label="load" value="load" />
                  <el-option label="domcontentloaded" value="domcontentloaded" />
                  <el-option label="networkidle" value="networkidle" />
                </el-select>
              </el-form-item>
              <el-form-item label="截图策略">
                <el-select v-model="webEditRow.screenshotStrategy">
                  <el-option label="不截图" value="none" />
                  <el-option label="失败截图" value="on_failure" />
                  <el-option label="每步截图" value="always" />
                </el-select>
              </el-form-item>
              <el-form-item label="失败重试次数">
                <el-input-number v-model="webEditRow.retryCount" :min="0" :max="10" controls-position="right" />
              </el-form-item>
            </div>
          </div>
          <div class="web-edit-section">
            <strong>用例信息</strong>
            <div class="web-edit-grid">
              <el-form-item label="项目">
                <el-input v-model="webEditRow.project" />
              </el-form-item>
              <el-form-item label="模块">
                <el-input v-model="webEditRow.module" />
              </el-form-item>
              <el-form-item label="页面">
                <el-input v-model="webEditRow.pageName" />
              </el-form-item>
              <el-form-item label="用例标题">
                <el-input v-model="webEditRow.title" />
              </el-form-item>
              <el-form-item label="用例类型">
                <el-input v-model="webEditRow.caseType" />
              </el-form-item>
              <el-form-item label="优先级">
                <el-select v-model="webEditRow.priority">
                  <el-option label="P0" value="P0" />
                  <el-option label="P1" value="P1" />
                  <el-option label="P2" value="P2" />
                </el-select>
              </el-form-item>
            </div>
          </div>
          <div class="web-edit-section">
            <strong>测试设计</strong>
            <el-form-item label="自然语言步骤">
              <el-input
                v-model="webEditRow.naturalSteps"
                type="textarea"
                :rows="4"
                placeholder="按业务动作逐步描述，例如：进入日志管理页面；切换到权限控制；使用普通成员账号查看菜单"
              />
            </el-form-item>
            <el-form-item label="自然语言预期">
              <el-input
                v-model="webEditRow.naturalExpectedResult"
                type="textarea"
                :rows="3"
                placeholder="描述页面应出现或不应出现的元素、文案、顺序、权限结果"
              />
            </el-form-item>
          </div>
          <div class="web-edit-section">
            <strong>自动化脚本</strong>
            <el-form-item label="操作步骤">
              <el-input v-model="webEditRow.steps" type="textarea" :rows="6" />
            </el-form-item>
            <el-form-item label="元素定位">
              <el-input v-model="webEditRow.locators" type="textarea" :rows="4" />
            </el-form-item>
            <el-form-item label="断言规则">
              <el-input v-model="webEditRow.assertions" type="textarea" :rows="4" />
            </el-form-item>
          </div>
          <el-form-item label="备注">
            <el-input v-model="webEditRow.remark" type="textarea" :rows="3" />
          </el-form-item>
        </el-form>
        <div class="drawer-actions">
          <el-button @click="editVisible = false">取消</el-button>
          <el-button type="primary" @click="saveEdit">保存修改</el-button>
        </div>
      </div>
    </ResponsiveDrawer>
</template>

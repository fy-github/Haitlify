<script setup lang="ts">
import { computed } from 'vue'
import type { NavKey } from '../../domain/appNavigation'
import type { JiraConfig } from '../../domain/jiraConfigRepository'
import JiraConfigView from './JiraConfigView.vue'
import SettingsView from './SettingsView.vue'
import UserManagementView from './UserManagementView.vue'

const props = withDefaults(defineProps<{
  activeNav: NavKey
  /** 当前用户角色;非 ADMIN 时隐藏 adminOnly 的「用户管理」分节(与侧边栏可见性一致) */
  role?: string
  /**
   * 当前账号是否可写(与 App.canEdit 同源)。只读角色下 Jira 页的保存/测试/映射增删由
   * JiraConfigView 置灰、App 的 handler 再拒一次;重新加载(读)不受影响。
   */
  canEdit?: boolean
  // JiraConfigView 透传(App.vue 持有表单与动作)
  form: JiraConfig
  loading: boolean
  readState?: 'idle' | 'loading' | 'ready' | 'error'
  testing: boolean
  projectJqlRows: Array<{ key: string; value: string }>
  load: () => void
  save: () => void
  test: () => void
  addJqlRow: () => void
  removeJqlRow: (index: number) => void
  syncJqlForm: () => void
  /** 最近一次连接测试的真实结果(空表示未测试;stale = 参数已变,结论失效) */
  testResult?: { state: 'idle' | 'success' | 'failed' | 'stale'; message: string; target: string }
  /** 已保存配置快照,用于页内「未保存修改」判断 */
  savedSnapshot?: string
  /** 保存前的前端校验结果(空表示无错误) */
  validationError?: string
  // UserManagementView 透传
  currentUsername: string
}>(), {
  role: undefined,
  canEdit: true,
  testResult: () => ({ state: 'idle', message: '', target: '' }),
  savedSnapshot: '',
  validationError: ''
})

const isAdmin = computed(() => props.role === 'ADMIN')

const emit = defineEmits<{
  openSection: [section: 'fields' | 'types']
}>()

const templateSection = computed<'fields' | 'types'>(() => props.activeNav === 'caseTypes' ? 'types' : 'fields')
</script>

<template>
  <section class="workbench" aria-label="设置中心工作台">
    <div class="workbench__content">
      <!-- 设计稿 .content padding 26/24/18:页头与白色面板共用同一内边距,面板不贴边 -->
      <div v-if="activeNav === 'settings' || activeNav === 'caseTypes'" class="workbench__pane settings-pane">
        <SettingsView :section="templateSection" @open-section="emit('openSection', $event)" />
      </div>
      <div v-else-if="activeNav === 'jiraConfig'" class="workbench__pane">
        <JiraConfigView
          :can-edit="canEdit"
          :form="form"
          :loading="loading"
          :read-state="readState"
          :testing="testing"
          :project-jql-rows="projectJqlRows"
          :load="load"
          :save="save"
          :test="test"
          :add-jql-row="addJqlRow"
          :remove-jql-row="removeJqlRow"
          :sync-jql-form="syncJqlForm"
          :test-result="testResult"
          :saved-snapshot="savedSnapshot"
          :validation-error="validationError"
        />
      </div>
      <div v-else-if="activeNav === 'users' && isAdmin" class="workbench__pane">
        <UserManagementView :current-username="currentUsername" />
      </div>
      <div v-else class="workbench__pane workbench__pane--full">
        <div class="workbench__placeholder">
          <strong>从左侧导航选择设置分组</strong>
          <span>非管理员分组不可见</span>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { NavKey } from '../../domain/appNavigation'
import type { JiraConfig } from '../../domain/jiraConfigRepository'
import AiConfigView from './AiConfigView.vue'
import JiraConfigView from './JiraConfigView.vue'
import SettingsView from './SettingsView.vue'
import UserManagementView from './UserManagementView.vue'

const props = withDefaults(defineProps<{
  activeNav: NavKey
  /** 当前用户角色;非 ADMIN 时隐藏 adminOnly 的「用户管理」分节(与侧边栏可见性一致) */
  role?: string
  // JiraConfigView 透传(App.vue 持有表单与动作)
  form: JiraConfig
  loading: boolean
  testing: boolean
  projectJqlRows: Array<{ key: string; value: string }>
  load: () => void
  save: () => void
  test: () => void
  addJqlRow: () => void
  removeJqlRow: (index: number) => void
  syncJqlForm: () => void
  // UserManagementView 透传
  currentUsername: string
}>(), {
  role: undefined
})

const isAdmin = computed(() => props.role === 'ADMIN')
</script>

<template>
  <section class="workbench" aria-label="设置中心工作台">
    <div class="workbench__content">
      <div v-if="activeNav === 'aiConfig'" class="workbench__pane workbench__pane--full">
        <AiConfigView />
      </div>
      <div v-else-if="activeNav === 'settings'" class="workbench__pane">
        <SettingsView />
      </div>
      <div v-else-if="activeNav === 'jiraConfig'" class="workbench__pane">
        <JiraConfigView
          :form="form"
          :loading="loading"
          :testing="testing"
          :project-jql-rows="projectJqlRows"
          :load="load"
          :save="save"
          :test="test"
          :add-jql-row="addJqlRow"
          :remove-jql-row="removeJqlRow"
          :sync-jql-form="syncJqlForm"
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

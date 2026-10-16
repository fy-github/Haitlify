<script setup lang="ts">
import { PanelLeftClose, PanelLeftOpen, Search } from '@lucide/vue'

type SyncState = 'synced' | 'syncing' | 'conflict'

const props = withDefaults(defineProps<{
  sidebarCollapsed: boolean
  projectLabel: string
  sectionLabel: string
  searchValue: string
  searchPlaceholder?: string
  syncState?: SyncState
  userInitials?: string
  userDisplayName?: string
  taskStatusLabel?: string
}>(), {
  searchPlaceholder: '搜索项目、用例或任务',
  syncState: 'synced',
  userInitials: 'LY',
  userDisplayName: '',
  taskStatusLabel: ''
})

const emit = defineEmits<{
  toggleSidebar: []
  'update:searchValue': [value: string]
  openTask: []
  logout: []
  'change-password': []
}>()

const handleUserCommand = (command: string | number | object): void => {
  if (command === 'logout') emit('logout')
  if (command === 'change-password') emit('change-password')
}

const toggleLabel = () => props.sidebarCollapsed ? '展开侧边栏' : '收起侧边栏'

const syncStateLabel: Record<SyncState, string> = {
  synced: '已同步',
  syncing: '同步中',
  conflict: '同步冲突'
}
</script>

<template>
  <header class="studio-global-topbar">
    <button
      type="button"
      class="studio-icon-button"
      :aria-label="toggleLabel()"
      :title="toggleLabel()"
      @click="emit('toggleSidebar')"
    >
      <PanelLeftOpen v-if="sidebarCollapsed" :size="18" aria-hidden="true" />
      <PanelLeftClose v-else :size="18" aria-hidden="true" />
    </button>

    <nav class="studio-global-topbar__breadcrumb" aria-label="当前位置">
      <span>{{ projectLabel }}</span>
      <span class="studio-global-topbar__breadcrumb-separator" aria-hidden="true">/</span>
      <strong>{{ sectionLabel }}</strong>
    </nav>

    <label class="studio-global-topbar__search">
      <Search :size="16" aria-hidden="true" />
      <input
        :value="searchValue"
        type="search"
        :placeholder="searchPlaceholder"
        :aria-label="searchPlaceholder"
        @input="emit('update:searchValue', ($event.target as HTMLInputElement).value)"
      />
    </label>

    <button v-if="taskStatusLabel" type="button" class="studio-global-task-status" @click="emit('openTask')">
      {{ taskStatusLabel }}
    </button>
    <span v-if="taskStatusLabel" class="studio-visually-hidden" role="status">{{ taskStatusLabel }}</span>

    <span class="studio-sync-state" role="status">{{ syncStateLabel[props.syncState] }}</span>

    <el-dropdown v-if="userDisplayName" trigger="click" @command="handleUserCommand">
      <button
        type="button"
        class="studio-global-topbar__avatar"
        :title="`当前用户 ${userDisplayName}`"
        :aria-label="`当前用户 ${userDisplayName}`"
      >
        {{ userInitials }}
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item disabled>{{ userDisplayName }}</el-dropdown-item>
          <el-dropdown-item command="change-password">修改密码</el-dropdown-item>
          <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
    <span
      v-else
      class="studio-global-topbar__avatar"
      :title="`当前用户 ${userInitials}`"
      :aria-label="`当前用户 ${userInitials}`"
    >
      {{ userInitials }}
    </span>
  </header>
</template>

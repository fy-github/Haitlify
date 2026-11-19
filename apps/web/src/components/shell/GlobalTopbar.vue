<script setup lang="ts">
import { Bell, Bot, Menu, Search } from '@lucide/vue'
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import type { PrimaryArea, PrimaryAreaId } from '../../ui/navigation/appSidebar'

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
  primaryAreas?: readonly PrimaryArea[]
  activePrimaryId?: PrimaryAreaId
}>(), {
  searchPlaceholder: '搜索项目、用例或任务',
  syncState: 'synced',
  userInitials: 'LY',
  userDisplayName: '',
  taskStatusLabel: '',
  primaryAreas: () => [],
  activePrimaryId: undefined
})

const emit = defineEmits<{
  toggleSidebar: []
  'update:searchValue': [value: string]
  openTask: []
  logout: []
  'change-password': []
  selectPrimary: [area: PrimaryArea]
  openAssistant: []
}>()

const handleUserCommand = (command: string | number | object): void => {
  if (command === 'logout') emit('logout')
  if (command === 'change-password') emit('change-password')
}

const syncStateLabel: Record<SyncState, string> = {
  synced: '已同步',
  syncing: '同步中',
  conflict: '同步冲突'
}

// 设计稿顶栏右侧只有一个放大镜;点击后展开为输入框,避免挤占导航宽度
const searchOpen = ref(false)
const searchRoot = ref<HTMLElement | null>(null)
const searchInput = ref<HTMLInputElement | null>(null)
const searchToggle = ref<HTMLButtonElement | null>(null)
const notificationsOpen = ref(false)
const notificationRoot = ref<HTMLElement | null>(null)
const notificationPanel = ref<HTMLElement | null>(null)
const notificationToggle = ref<HTMLButtonElement | null>(null)
const openSearch = async (): Promise<void> => {
  notificationsOpen.value = false
  searchOpen.value = true
  await nextTick()
  searchInput.value?.focus()
}
const closeSearch = (restoreFocus = false): void => {
  searchOpen.value = false
  if (restoreFocus) searchToggle.value?.focus()
}
const openNotifications = async (): Promise<void> => {
  searchOpen.value = false
  notificationsOpen.value = true
  await nextTick()
  notificationPanel.value?.focus()
}
const closeNotifications = (restoreFocus = false): void => {
  notificationsOpen.value = false
  if (restoreFocus) notificationToggle.value?.focus()
}
const toggleNotifications = (): void => {
  if (notificationsOpen.value) {
    closeNotifications()
  } else {
    void openNotifications()
  }
}
const dismissSearch = (event: PointerEvent): void => {
  const target = event.target as Node
  if (!searchRoot.value?.contains(target)) closeSearch()
  if (!notificationRoot.value?.contains(target)) closeNotifications()
}
onMounted(() => document.addEventListener('pointerdown', dismissSearch))
onBeforeUnmount(() => document.removeEventListener('pointerdown', dismissSearch))
</script>

<template>
  <header class="studio-global-topbar">
    <button
      type="button"
      class="studio-global-topbar__mobile-menu"
      aria-label="打开主导航"
      title="打开主导航"
      @click="emit('toggleSidebar')"
    >
      <Menu :size="18" aria-hidden="true" />
    </button>

    <div class="studio-topbar__brand">
      <span class="studio-topbar__brand-mark" aria-hidden="true">h</span>
      <strong class="studio-topbar__brand-name">Haitlify</strong>
    </div>

    <nav v-if="primaryAreas.length" class="studio-primary-capsule" aria-label="主工作区域">
      <button
        v-for="area in primaryAreas"
        :key="area.id"
        type="button"
        :class="{ 'is-active': area.id === activePrimaryId }"
        :aria-current="area.id === activePrimaryId ? 'page' : undefined"
        @click="emit('selectPrimary', area)"
      >
        {{ area.label }}
      </button>
    </nav>

    <nav class="studio-global-topbar__breadcrumb" aria-label="当前位置">
      <span>{{ projectLabel }}</span>
      <span class="studio-global-topbar__breadcrumb-separator" aria-hidden="true">/</span>
      <strong>{{ sectionLabel }}</strong>
    </nav>

    <button v-if="taskStatusLabel" type="button" class="studio-global-task-status" @click="emit('openTask')">
      {{ taskStatusLabel }}
    </button>
    <span v-if="taskStatusLabel" class="studio-visually-hidden" role="status">{{ taskStatusLabel }}</span>
    <span class="studio-sync-state" role="status">{{ syncStateLabel[props.syncState] }}</span>

    <div class="studio-topbar__tools">
      <button type="button" class="studio-assistant-entry" @click="emit('openAssistant')">
        <Bot :size="17" aria-hidden="true" />
        AI 助手
      </button>

      <div
        ref="searchRoot"
        class="studio-global-topbar__search"
        :class="{ 'is-open': searchOpen }"
        @keydown.esc.prevent="closeSearch(true)"
      >
        <button
          ref="searchToggle"
          type="button"
          class="studio-global-topbar__search-toggle"
          aria-label="搜索"
          :aria-expanded="searchOpen"
          title="搜索"
          @click="searchOpen ? searchInput?.focus() : openSearch()"
        >
          <Search :size="17" aria-hidden="true" />
        </button>
        <input
          ref="searchInput"
          :value="searchValue"
          :tabindex="searchOpen ? 0 : -1"
          :aria-hidden="!searchOpen"
          type="search"
          :placeholder="searchPlaceholder"
          :aria-label="searchPlaceholder"
          :aria-describedby="searchOpen ? 'studio-global-search-status' : undefined"
          @focus="searchOpen = true"
          @keydown.enter.prevent="searchOpen = true"
          @input="emit('update:searchValue', ($event.target as HTMLInputElement).value)"
        />
        <span
          v-if="searchOpen"
          id="studio-global-search-status"
          data-testid="global-search-status"
          class="studio-global-topbar__search-status"
          role="status"
        >
          全局搜索暂未接入，请使用各模块筛选
        </span>
      </div>

      <div ref="notificationRoot" class="studio-global-topbar__notifications">
        <button
          ref="notificationToggle"
          type="button"
          class="studio-global-topbar__icon"
          data-testid="global-notifications-toggle"
          aria-label="通知"
          title="通知"
          :aria-expanded="notificationsOpen"
          aria-controls="studio-global-notifications-panel"
          @click="toggleNotifications"
        >
        <Bell :size="18" aria-hidden="true" />
        </button>
        <section
          v-if="notificationsOpen"
          id="studio-global-notifications-panel"
          ref="notificationPanel"
          class="studio-global-topbar__notifications-panel"
          role="dialog"
          aria-labelledby="studio-global-notifications-title"
          tabindex="-1"
          @keydown.esc.prevent.stop="closeNotifications(true)"
        >
          <div class="studio-global-topbar__notifications-heading">
            <h2 id="studio-global-notifications-title">通知</h2>
            <button type="button" aria-label="关闭通知面板" title="关闭通知面板" @click="closeNotifications(true)">×</button>
          </div>
          <p>通知数据源尚未接入，暂不展示通知</p>
        </section>
      </div>

      <el-dropdown
        v-if="userDisplayName"
        trigger="click"
        placement="bottom-end"
        popper-class="studio-account-popper"
        :show-arrow="false"
        @command="handleUserCommand"
      >
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
    </div>
  </header>
</template>

<style scoped>
.studio-global-topbar__search-status {
  position: absolute;
  top: calc(100% + 52px);
  right: 0;
  z-index: 41;
  display: block;
  width: min(280px, calc(100vw - 24px));
  padding: 8px 10px;
  color: #44515a;
  background: #fff;
  border: 1px solid #dbe3e5;
  border-radius: 9px;
  box-shadow: 0 8px 20px rgb(25 42 47 / 10%);
  font-size: 12px;
  line-height: 1.5;
  white-space: normal;
}

.studio-global-topbar__notifications {
  position: relative;
  flex: none;
}

.studio-global-topbar__notifications-panel {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  z-index: 40;
  width: min(280px, calc(100vw - 24px));
  padding: 14px 16px;
  color: #44515a;
  background: #fff;
  border: 1px solid #dbe3e5;
  border-radius: 12px;
  box-shadow: 0 8px 20px rgb(25 42 47 / 10%);
}

.studio-global-topbar__notifications-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.studio-global-topbar__notifications-heading h2 {
  margin: 0;
  color: #273238;
  font-size: 13px;
  font-weight: 650;
}

.studio-global-topbar__notifications-heading button {
  width: 24px;
  height: 24px;
  padding: 0;
  color: #626973;
  background: transparent;
  border: 0;
  border-radius: 50%;
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
}

.studio-global-topbar__notifications-heading button:hover {
  background: #f0f3f4;
}

.studio-global-topbar__notifications-panel p {
  margin: 10px 0 0;
  font-size: 12px;
  line-height: 1.5;
}

@media (max-width: 640px) {
  .studio-global-topbar__search-status,
  .studio-global-topbar__notifications-panel {
    right: -4px;
  }
}
</style>

<script setup lang="ts">
import { createAppWorkspace } from './ui/appWorkspace'
import AppOverlays from './components/AppOverlays.vue'
import AppRouteView from './components/AppRouteView.vue'
import { Download, Upload, X } from '@lucide/vue'
import AppShell from './components/shell/AppShell.vue'
import ResponsiveDrawer from './components/shell/ResponsiveDrawer.vue'
import LoginScreen from './components/auth/LoginScreen.vue'
import GlobalTopbar from './components/shell/GlobalTopbar.vue'
import PrimarySidebar from './components/shell/PrimarySidebar.vue'
import HelpDrawer from './components/shell/HelpDrawer.vue'
import DefectFailureBanner from './components/defects/DefectFailureBanner.vue'

const { presentation, testAccess } = createAppWorkspace()
defineExpose({ testAccess })
const { authStatus, onLoginSuccess, isNarrowViewport, narrowNavigationVisible, railCollapsed, sidebarGroups, activeSidebarItemId, sidebarBadges, isSettingsCenterNav, selectSidebarItem, expandStudioNavigation, helpVisible, globalSearch, studioProjectLabel, studioSectionLabel, workspaceSyncState, globalLongTaskLabel, currentUserInitials, currentUser, primaryAreas, activePrimaryId, toggleStudioNavigation, openActiveLongTask, selectPrimaryArea, openAssistantPage, handleLogout, openChangePasswordDialog, activeNav, generationKind, automationKind, isWorkbenchNav, templateConfigModule, workspaceSyncConflict, workspaceSyncConflictText, workspaceSyncLoading, pushLocalWorkspaceToBackend, reloadBackendWorkspace, dismissWorkspaceSyncConflict, isOnline, appRouteModel, caseEditorModel, curlPreviewModel, environmentEditorModel, webLoginDiscoveryModel, webLoginVerificationModel, executionLogModel, automationConversionModel, changePasswordModel, appRouteRef } = presentation
</script>

<template>
  <LoginScreen
    v-if="authStatus === 'unauthenticated'"
    @authenticated="onLoginSuccess"
  />
  <AppShell
    v-else-if="authStatus === 'authenticated'"
    :sidebar-collapsed="isNarrowViewport ? !narrowNavigationVisible : railCollapsed"
    :sidebar-visible="!isNarrowViewport"
    :inspector-visible="false"
  >
    <template #sidebar>
      <PrimarySidebar
        v-if="!isNarrowViewport"
        :groups="sidebarGroups"
        :active-item-id="activeSidebarItemId"
        :collapsed="railCollapsed"
        :badges="sidebarBadges"
        :settings-entry="!isSettingsCenterNav"
        @select="selectSidebarItem"
        @request-expand="expandStudioNavigation"
        @help="helpVisible = true"
      />
    </template>

    <template #topbar>
      <GlobalTopbar
        v-model:search-value="globalSearch"
        :sidebar-collapsed="isNarrowViewport ? !narrowNavigationVisible : railCollapsed"
        :project-label="studioProjectLabel"
        :section-label="studioSectionLabel"
        :sync-state="workspaceSyncState"
        :task-status-label="globalLongTaskLabel"
        :user-initials="currentUserInitials"
        :user-display-name="currentUser?.displayName || currentUser?.username || ''"
        :primary-areas="primaryAreas"
        :active-primary-id="activePrimaryId"
        @toggle-sidebar="toggleStudioNavigation"
        @open-task="openActiveLongTask"
        @select-primary="selectPrimaryArea"
        @open-assistant="openAssistantPage"
        @logout="handleLogout"
        @change-password="openChangePasswordDialog"
      />
    </template>


    <main
      class="workspace"
      :class="{
        'is-home': activeNav === 'home',
        'is-studio-case-design': activeNav === 'api' && generationKind === 'web_automation',
        'is-studio-interface-case': activeNav === 'api' && generationKind === 'interface',
        'is-web-automation': activeNav === 'automation' && automationKind === 'web',
        'is-environment-management': activeNav === 'environments',
        'is-workbench': isWorkbenchNav
      }"
    >
      <section v-if="templateConfigModule.recovery?.reason" data-template-recovery class="studio-sync-notice template-recovery-notice" data-message-level="warning" role="alert" aria-live="assertive">
        <div class="sync-conflict-copy"><strong>模板配置未确认</strong><span>{{ templateConfigModule.recovery.reason }} · {{ templateConfigModule.recovery.owner }} · {{ templateConfigModule.recovery.project }}</span></div>
        <div class="sync-conflict-actions">
          <div class="template-recovery-action-group" data-recovery-group="recover" role="group" aria-label="恢复与重试">
            <span class="template-recovery-action-group__label">恢复与重试</span>
            <button :disabled="templateConfigModule.recovery.phase !== 'idle' || !templateConfigModule.recovery.identityUsable" @click="templateConfigModule.reloadBaseline()">重新读取本地</button>
            <button :disabled="templateConfigModule.recovery.phase !== 'idle' || !templateConfigModule.recovery.identityUsable" @click="templateConfigModule.retryMirror()">重试本地镜像</button>
            <button :disabled="templateConfigModule.recovery.phase !== 'idle' || !templateConfigModule.recovery.identityUsable" @click="templateConfigModule.retrySave()">保留本地并重试保存</button>
          </div>
          <div class="template-recovery-action-group" data-recovery-group="decision" data-risk="destructive" role="group" aria-label="覆盖或丢弃本地修改">
            <span class="template-recovery-action-group__label">覆盖或丢弃本地修改</span>
            <button class="is-destructive" :disabled="templateConfigModule.recovery.phase !== 'idle' || !templateConfigModule.recovery.identityUsable" @click="templateConfigModule.reconcile('push-local')">本地覆盖后端</button>
            <button class="is-destructive" :disabled="templateConfigModule.recovery.phase !== 'idle' || !templateConfigModule.recovery.identityUsable" @click="templateConfigModule.reconcile('take-backend')">采用后端（丢弃本地）</button>
          </div>
        </div>
      </section>
      <DefectFailureBanner v-if="activeNav === 'automation'" />
      <div v-if="workspaceSyncConflict" class="studio-sync-notice" data-message-level="warning" role="status" aria-live="polite">
        <span class="sync-conflict-icon" aria-hidden="true">!</span>
        <div class="sync-conflict-copy">
          <strong>本地数据较新</strong>
          <span>{{ workspaceSyncConflictText }}</span>
          <span>推送本地会更新后端；使用后端会替换本地数据。</span>
        </div>
        <div class="sync-conflict-actions">
          <el-button size="small" data-sync-action="push-local" :loading="workspaceSyncLoading" @click="pushLocalWorkspaceToBackend">
            <Upload :size="14" />
            推送本地
          </el-button>
          <el-button size="small" plain data-sync-action="take-backend" data-risk="destructive" :loading="workspaceSyncLoading" @click="reloadBackendWorkspace">
            <Download :size="14" />
            使用后端
          </el-button>
          <el-button
            class="icon-button"
            text
            :disabled="workspaceSyncLoading"
            aria-label="关闭同步提示"
            title="关闭同步提示"
            @click="dismissWorkspaceSyncConflict"
          >
            <X :size="15" aria-hidden="true" />
          </el-button>
        </div>
      </div>
      <div v-if="!isOnline" class="studio-offline-notice" data-notice-level="info" data-message-level="info" role="status" aria-live="polite">
        <span class="studio-offline-notice__icon" aria-hidden="true">i</span>
        <strong>离线模式</strong>
        <span>当前离线；用例库本地数据保留，联网后可继续同步。</span>
      </div>
      <AppRouteView ref="appRouteRef" :model="appRouteModel" />    </main>
  </AppShell>

  <ResponsiveDrawer v-model="narrowNavigationVisible" class="studio-navigation-drawer" title="主导航" size="168px" direction="ltr">
    <PrimarySidebar
      :groups="sidebarGroups"
      :active-item-id="activeSidebarItemId"
      :collapsed="false"
      :badges="sidebarBadges"
      :settings-entry="!isSettingsCenterNav"
      @select="selectSidebarItem($event); narrowNavigationVisible = false"
      @help="narrowNavigationVisible = false; helpVisible = true"
    />
  </ResponsiveDrawer>

  <HelpDrawer v-if="authStatus === 'authenticated'" v-model="helpVisible" />

  <AppOverlays
    :case-editor="caseEditorModel"
    :curl-preview="curlPreviewModel"
    :environment-editor="environmentEditorModel"
    :login-discovery="webLoginDiscoveryModel"
    :login-verification="webLoginVerificationModel"
    :execution-log="executionLogModel"
    :automation-conversion="automationConversionModel"
    :change-password="changePasswordModel"
  />
</template>

<style scoped>
:global(.el-message.web-run-terminal-feedback) {
  z-index: 1999 !important;
  pointer-events: none;
}
</style>

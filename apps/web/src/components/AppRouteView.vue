<script setup lang="ts">
import { toRefs, ref } from 'vue'
import type { AppRouteViewModel } from '../ui/appRouteModel'
import WebExecutionPage from './views/WebExecutionPage.vue'
import { Plus, Settings } from '@lucide/vue'
import OverviewView from './views/OverviewView.vue'
import HomeView from './views/HomeView.vue'
import AssistantChainView from './views/AssistantChainView.vue'
import SettingsCenterView from './views/SettingsCenterView.vue'
import QualityWorkbenchView from './views/QualityWorkbenchView.vue'
import ExecutionWorkbenchView from './views/ExecutionWorkbenchView.vue'
import ApiExecutionPage from './views/ApiExecutionPage.vue'
import EnvironmentManagementView from './views/EnvironmentManagementView.vue'
import CasesView from './views/CasesView.vue'
import type { NavKey } from '../domain/appNavigation'
import type { NavDisplay } from '../ui/navigation/appSidebar'
import type { SavedExecutionEnvironment } from '../domain/executionEnvironmentRepository'
import type { ExecutionEnvironmentLibrary } from '../domain/executionEnvironmentRepository'
import type { NavHistoryMode } from '../ui/appPorts'
import type { EnvironmentKind } from '../ui/appPorts'
import type { HomeNavigateTarget } from '../domain/homeWorkbench'
import type { HomeProject } from '../domain/homeWorkbench'
import type { HomeRun } from '../domain/homeWorkbench'
import type { HomeWorkItem } from '../domain/homeWorkbench'
const props = defineProps<{ model: AppRouteViewModel }>()
const { isCaseWorkbenchNav, activeNav, homeRuns, homeWorkItems, homeProjects, homeWorkspaceStatus, homeWorkspaceError, handleHomeNavigate, retryHomeWorkspace, currentUser, overviewStatus, overviewError, overviewAvailabilityChecked, checkOverviewAvailability, isSettingsCenterNav, canEdit, jiraConfigForm, jiraConfigLoading, jiraConfigReadState, jiraConfigTesting, jiraProjectJqlRows, loadJiraSettingsFromBackend, saveJiraSettings, testJiraConnection, addJiraProjectJqlRow, removeJiraProjectJqlRow, syncJiraProjectJqlFormFromRows, jiraConfigTestResult, jiraConfigSavedSnapshot, jiraConfigValidationError, navigateToNav, isQualityWorkbenchNav, automationKind, apiExecutionPageModel, webExecutionPageModel, environmentEditorKind, currentEnvironmentLibrary, editingEnvironment, switchEnvironmentManagementKind, selectEnvironmentFromPage, returnToPreparationFromEnvironments, addExecutionEnvironment, openEnvironmentEditor, currentNav } = toRefs(props.model)
const homeViewRef = ref<InstanceType<typeof HomeView>>()
const apiExecutionPageRef = ref<InstanceType<typeof ApiExecutionPage>>()
const webAutomationWorkspaceRef = ref<InstanceType<typeof WebExecutionPage>>()
defineExpose({ homeViewRef, apiExecutionPageRef, webAutomationWorkspaceRef })
</script>

<template>
<template v-if="isCaseWorkbenchNav">
        <!-- A3-B2：五段式用例设计流程已下线（旧 AI 接口一并拆除），用例库保留只读/维护能力。 -->
        <CasesView :key="activeNav" />
      </template>

      <template v-else-if="activeNav === 'home'">
        <HomeView
          ref="homeViewRef"
          :runs="homeRuns"
          :items="homeWorkItems"
          :projects="homeProjects"
          :status="homeWorkspaceStatus"
          :error-message="homeWorkspaceError"
          @navigate="handleHomeNavigate"
          @retry="retryHomeWorkspace"
        />
      </template>

      <template v-else-if="activeNav === 'assistant'">
        <AssistantChainView
          :user="currentUser"
        />
      </template>

      <template v-else-if="activeNav === 'overview'">
        <OverviewView
          :status="overviewStatus"
          :error-message="overviewError"
          :availability-checked="overviewAvailabilityChecked"
          @retry="checkOverviewAvailability"
        />
      </template>

      <template v-else-if="isSettingsCenterNav">
        <SettingsCenterView
          :active-nav="activeNav"
          :role="currentUser?.role"
          :can-edit="canEdit"
          :form="jiraConfigForm"
          :loading="jiraConfigLoading"
          :read-state="jiraConfigReadState"
          :testing="jiraConfigTesting"
          :project-jql-rows="jiraProjectJqlRows"
          :load="loadJiraSettingsFromBackend"
          :save="saveJiraSettings"
          :test="testJiraConnection"
          :add-jql-row="addJiraProjectJqlRow"
          :remove-jql-row="removeJiraProjectJqlRow"
          :sync-jql-form="syncJiraProjectJqlFormFromRows"
          :test-result="jiraConfigTestResult"
          :saved-snapshot="jiraConfigSavedSnapshot"
          :validation-error="jiraConfigValidationError"
          :current-username="currentUser?.username || ''"
          @open-section="(section: 'fields' | 'types') => navigateToNav(section === 'types' ? 'caseTypes' : 'settings')"
        />
      </template>
      <template v-else-if="isQualityWorkbenchNav">
        <QualityWorkbenchView
          :active-nav="activeNav"
        />
      </template>

      <template v-else-if="activeNav === 'automation'">
        <ExecutionWorkbenchView
          :active-nav="activeNav"
          :automation-kind="automationKind"
        >
        <template #api>
          <ApiExecutionPage ref="apiExecutionPageRef" :model="apiExecutionPageModel" />
        </template>
        <template #web>
        <WebExecutionPage ref="webAutomationWorkspaceRef" :model="webExecutionPageModel" />
        </template>
        </ExecutionWorkbenchView>
      </template>

      <template v-else-if="activeNav === 'environments'">
        <EnvironmentManagementView
          :kind="environmentEditorKind"
          :environments="currentEnvironmentLibrary.environments"
          :active-environment-id="editingEnvironment.id"
          @update:kind="switchEnvironmentManagementKind"
          @select="selectEnvironmentFromPage"
          @back="returnToPreparationFromEnvironments"
        >
          <template #directory-actions>
            <div class="environment-page__directory-actions">
              <el-button type="primary" :disabled="!canEdit" @click="addExecutionEnvironment()">
                <Plus :size="14" />
                新增环境
              </el-button>
              <el-button :disabled="!canEdit" @click="openEnvironmentEditor(undefined, environmentEditorKind)">
                打开环境编辑器
              </el-button>
            </div>
          </template>
          <div id="environment-inline-editor-host" class="environment-inline-editor-host"></div>
        </EnvironmentManagementView>
      </template>

      <section v-else class="future-workspace">
        <component :is="currentNav.icon" :size="48" />
        <h1>{{ currentNav.label }}</h1>
        <el-tag type="info">后端后期补充</el-tag>
      </section>

</template>
<style scoped>
.environment-page__directory-actions { display: flex; flex-direction: column; gap: 8px; }
</style>

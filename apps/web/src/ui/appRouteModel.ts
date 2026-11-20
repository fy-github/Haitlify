import type { NavKey } from '../domain/appNavigation'
import type { NavDisplay } from './navigation/appSidebar'
import type { SavedExecutionEnvironment } from '../domain/executionEnvironmentRepository'
import type { ExecutionEnvironmentLibrary } from '../domain/executionEnvironmentRepository'
import type { NavHistoryMode } from './appPorts'
import type { EnvironmentKind } from './appPorts'
import type { HomeNavigateTarget } from '../domain/homeWorkbench'
import type { HomeProject } from '../domain/homeWorkbench'
import type { HomeRun } from '../domain/homeWorkbench'
import type { HomeWorkItem } from '../domain/homeWorkbench'
import type { ApiExecutionPageModel } from './apiExecutionPageModel'
import type { WebExecutionPageModel } from './webExecutionPageModel'
import type { OverviewStatus } from '../components/views/OverviewView.vue'

export interface AppRouteViewModel {
  isCaseWorkbenchNav: boolean
  activeNav: NavKey
  homeRuns: HomeRun[]
  homeWorkItems: HomeWorkItem[]
  homeProjects: HomeProject[]
  homeWorkspaceStatus: 'ready' | 'loading' | 'empty' | 'unavailable' | 'forbidden' | 'error'
  homeWorkspaceError: string
  handleHomeNavigate: (target: HomeNavigateTarget) => void
  retryHomeWorkspace: () => void
  currentUser: { id?: number | null | undefined; username: string; displayName: string; avatar?: string | undefined; role: string; mustChangePassword: boolean; } | null
  overviewStatus: OverviewStatus
  overviewError: string
  overviewAvailabilityChecked: boolean
  checkOverviewAvailability: () => Promise<void>
  isSettingsCenterNav: boolean
  canEdit: boolean
  jiraConfigForm: { enabled: boolean; baseUrl: string; authMode: string; username: string; token: string; credentialConfigured: boolean; maskedToken?: string | undefined; projectJql: Record<string, string>; updatedAt?: string | undefined; }
  jiraConfigLoading: boolean
  jiraConfigReadState: "ready" | "loading" | "error" | "idle"
  jiraConfigTesting: boolean
  jiraProjectJqlRows: { key: string; value: string; }[]
  loadJiraSettingsFromBackend: () => Promise<void>
  saveJiraSettings: () => Promise<void>
  testJiraConnection: () => Promise<void>
  addJiraProjectJqlRow: () => void
  removeJiraProjectJqlRow: (index: number) => Promise<void>
  syncJiraProjectJqlFormFromRows: () => void
  jiraConfigTestResult: { state: "idle" | "success" | "failed" | "stale"; message: string; target: string; }
  jiraConfigSavedSnapshot: string
  jiraConfigValidationError: string
  navigateToNav: (next: NavKey, mode?: NavHistoryMode, onNavigated?: () => void) => void
  isQualityWorkbenchNav: boolean
  automationKind: "api" | "web"
  apiExecutionPageModel: ApiExecutionPageModel
  webExecutionPageModel: WebExecutionPageModel
  environmentEditorKind: EnvironmentKind
  currentEnvironmentLibrary: ExecutionEnvironmentLibrary
  editingEnvironment: SavedExecutionEnvironment
  switchEnvironmentManagementKind: (kind: "api" | "web") => void
  selectEnvironmentFromPage: (environmentId: string) => void
  returnToPreparationFromEnvironments: (kind: "api" | "web") => void
  addExecutionEnvironment: (kind?: "api" | "web") => void
  openEnvironmentEditor: (environmentId?: string, kind?: "api" | "web") => void
  currentNav: NavDisplay
}

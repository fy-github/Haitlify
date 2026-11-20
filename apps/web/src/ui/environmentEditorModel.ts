import type { WebAutomationAction } from '../domain/webAutomationCase'
import type { WebAutomationStep } from '../domain/webAutomationCase'
import type { EnvironmentEntryType } from '../domain/executionEnvironmentRepository'
import type { SavedExecutionEnvironment } from '../domain/executionEnvironmentRepository'
import type { ExecutionEnvironmentLibrary } from '../domain/executionEnvironmentRepository'
import type { NavKey } from '../domain/appNavigation'
import type { EnvironmentKind } from './appPorts'
import type { AuthStatus } from './useAppSession'

export interface EnvironmentEditorPanelModel {
  authStatus: AuthStatus
  environmentVisible: boolean
  activeNav: NavKey
  environmentEditorKind: EnvironmentKind
  addExecutionEnvironment: (kind?: "api" | "web") => void
  currentEnvironmentLibrary: ExecutionEnvironmentLibrary
  editingEnvironment: SavedExecutionEnvironment
  openEnvironmentEditor: (environmentId?: string, kind?: "api" | "web") => void
  editingWebNetworkPolicyMode: "blocklist" | "allowlist"
  editingWebAllowedRequestOriginsText: string
  webLoginDiscoveryLoading: boolean
  canEdit: boolean
  startWebLoginDiscovery: () => Promise<void>
  webEnvironmentVerifying: boolean
  verifyEditingWebEnvironmentLogin: () => Promise<void>
  addWebLogoutStep: () => void
  updateWebLogoutStepAction: (step: WebAutomationStep, action: WebAutomationAction) => void
  webLogoutActionOptions: { label: string; value: WebAutomationAction; }[]
  webLogoutTargetPlaceholder: (step: WebAutomationStep) => "/#/login" | "testid=logout 或 role=button[name=\"退出登录\"]"
  moveWebLogoutStep: (index: number, offset: -1 | 1) => void
  removeWebLogoutStep: (index: number) => void
  webEnvironmentVerification: { status: "success" | "error"; message: string; detail?: string; } | undefined
  clearWebLoginSession: () => void
  curlImportText: string
  importCurlToEnvironment: () => void
  addEnvironmentEntry: (type?: EnvironmentEntryType) => void
  deleteEnvironmentEntry: (entryId: string) => void
  deleteExecutionEnvironment: (environmentId: string, kind?: "api" | "web") => Promise<void>
  cancelEnvironmentEditing: () => void
  saveEditingEnvironment: (kind?: "api" | "web") => void
}

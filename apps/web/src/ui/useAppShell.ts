import { computed, nextTick, ref } from 'vue'
import { type NavKey } from '../domain/appNavigation'
import type { HomeSection } from '../domain/homeWorkbench'
import { initialSidebarCollapsed, useViewportMode } from './useViewportMode'
import { useOnlineStatus } from './useOnlineStatus'
import { primaryAreaForNavKey, resolveNavDisplay, resolveSidebarActiveItemId, type SidebarBadges, type SidebarItem, type PrimaryArea } from './navigation/appSidebar'
import { selectImportedProject, type ImportedOpenApiProject } from '../domain/openapiProjectRepository'
import type { Ref } from 'vue'
import type { ComputedRef } from 'vue'
import type { HttpMethod } from '../domain/openapiParser'
import type { ApiRunMonitor } from '../domain/apiRunMonitor'

type NavHistoryMode = 'push' | 'replace' | 'skip'

interface Dependencies {
  importedProjects: Ref<{ id: string; title: string; sourceFile: string; version: string; operationCount: number; importedAt: string; document: { title: string; version: string; description: string; sourceFile: string; operations: { id: string; module: string; method: HttpMethod; path: string; summary: string; operationId: string; parameters: { name: string; in: "path" | "query" | "header" | "cookie"; required: boolean; type: string; description: string; }[]; requestBody?: { required: boolean; contentTypes: string[]; requiredFields: string[]; properties: Record<string, string>; example?: unknown; } | undefined; responseStatuses: string[]; expectedResponseBody?: unknown; }[]; }; }[], ImportedOpenApiProject[] | { id: string; title: string; sourceFile: string; version: string; operationCount: number; importedAt: string; document: { title: string; version: string; description: string; sourceFile: string; operations: { id: string; module: string; method: HttpMethod; path: string; summary: string; operationId: string; parameters: { name: string; in: "path" | "query" | "header" | "cookie"; required: boolean; type: string; description: string; }[]; requestBody?: { required: boolean; contentTypes: string[]; requiredFields: string[]; properties: Record<string, string>; example?: unknown; } | undefined; responseStatuses: string[]; expectedResponseBody?: unknown; }[]; }; }[]>
  activeProjectId: Ref<string, string>
  requirementAnalysisProjectName: Ref<string, string>
  activeNav: Ref<NavKey, NavKey>
  generationKind: Ref<"interface" | "web_automation", "interface" | "web_automation">
  automationKind: Ref<"api" | "web", "api" | "web">
  webAutomationRunning: Ref<boolean, boolean>
  apiAutomationRunning: Ref<boolean, boolean>
  apiAutomationProgress: { completed: number; total: number; runningCaseId: string; }
  activeWebRunId: Ref<string, string>
  navigateToNav: (next: NavKey, mode?: NavHistoryMode, onNavigated?: () => void) => void
  environmentVisible: Ref<boolean, boolean>
  applyNavChange: (next: NavKey, mode: NavHistoryMode) => void
  enterWebRunMonitor: () => void
  apiRunMonitor: Ref<ApiRunMonitor | undefined, ApiRunMonitor | undefined>
  enterApiRunMonitor: () => Promise<void>
  homeViewRef: ComputedRef<{ focusSection(section: HomeSection): void } | undefined>
}

export function createAppShell(deps: Dependencies) {

const savedCollapsed = window.localStorage.getItem('ai-test-platform.sidebar-collapsed')

const railCollapsed = ref(initialSidebarCollapsed(savedCollapsed, window.innerWidth))

const viewportMode = useViewportMode()

const isOnline = useOnlineStatus()

const isCompactDesktop = computed(() => viewportMode.value === 'compact' || viewportMode.value === 'narrow')

const isNarrowViewport = computed(() => viewportMode.value === 'narrow')

const narrowNavigationVisible = ref(false)

const helpVisible = ref(false)

const compactContextVisible = ref(false)

const compactInspectorVisible = ref(false)

const globalSearch = ref('')

const homeSection = ref<HomeSection>('runs')

const activeProject = computed(() => selectImportedProject(deps.importedProjects.value, deps.activeProjectId.value))

const globalProjectLabel = computed(() =>
  activeProject.value?.title || deps.requirementAnalysisProjectName.value || '默认项目'
)

const studioProjectLabel = computed(() => {
  if (deps.activeNav.value !== 'api' || globalProjectLabel.value !== '默认项目') {
    return globalProjectLabel.value
  }
  return '智能用例设计'
})

const studioSectionLabel = computed(() =>
  deps.activeNav.value === 'api' && deps.generationKind.value === 'web_automation'
    ? '测试设计'
    : currentNav.value.label
)

/** 设置中心工作台:设置导航(模板字段/用例类型/Jira 配置/用户管理)合并为单页 + 左分组导航 */
const isSettingsCenterNav = computed(() =>
  ['settings', 'caseTypes', 'jiraConfig', 'users'].includes(deps.activeNav.value)
)

/** 质量工作台:报告与缺陷导航合并为单页 + 左分组导航(转缺陷→缺陷高亮联动) */
const isQualityWorkbenchNav = computed(() =>
  ['reports', 'defects'].includes(deps.activeNav.value)
)

const isCaseLibraryNav = (nav: NavKey) => nav === 'cases' || nav === 'webCases'

const isCaseWorkbenchNav = computed(() =>
  isCaseLibraryNav(deps.activeNav.value)
)

/** 任一工作台壳(设置中心/质量工作台/执行工作台/用例工作台)打开时,workspace 收成单行网格由壳占满;
 * Web 自动化除外——它自管全高布局,仍走 .is-web-automation 工作区。 */
const isWorkbenchNav = computed(() =>
  isSettingsCenterNav.value
  || isQualityWorkbenchNav.value
  || deps.activeNav.value === 'overview'
  || isCaseWorkbenchNav.value
  || (deps.activeNav.value === 'automation' && deps.automationKind.value === 'api')
)

const globalLongTaskLabel = computed(() => {
  if (deps.webAutomationRunning.value) return 'Web 自动化执行中'
  if (deps.apiAutomationRunning.value) return `API 自动化 · ${deps.apiAutomationProgress.completed}/${deps.apiAutomationProgress.total}`
  return ''
})

const openActiveLongTask = () => {
  if (deps.webAutomationRunning.value && deps.activeWebRunId.value) {
    const runId = deps.activeWebRunId.value
    deps.navigateToNav('automation', 'push', () => {
      if (deps.activeWebRunId.value !== runId) return
      deps.environmentVisible.value = false
      deps.automationKind.value = 'web'
      deps.applyNavChange('automation', 'replace')
      deps.enterWebRunMonitor()
    })
    return
  }
  if (deps.apiAutomationRunning.value && deps.apiRunMonitor.value) {
    const monitor = deps.apiRunMonitor.value
    deps.navigateToNav('automation', 'push', () => {
      if (deps.apiRunMonitor.value !== monitor) return
      deps.environmentVisible.value = false
      deps.automationKind.value = 'api'
      deps.applyNavChange('automation', 'replace')
      void deps.enterApiRunMonitor()
    })
    return
  }
  deps.navigateToNav('automation')
}

const currentNav = computed(() => resolveNavDisplay(deps.activeNav.value, deps.automationKind.value, deps.generationKind.value))

const activeSidebarItemId = computed(() => {
  if (deps.activeNav.value === 'home') {
    return ({ runs: 'home', pending: 'home-pending', recent: 'home-recent' } as const)[homeSection.value]
  }
  return resolveSidebarActiveItemId(
    deps.activeNav.value,
    deps.automationKind.value,
    deps.generationKind.value
  )
})

/** 顶部一级工作区胶囊:当前 navKey 归属哪个工作区 */
const activePrimaryId = computed(() => primaryAreaForNavKey(deps.activeNav.value))

const sidebarBadges = computed<SidebarBadges>(() => ({}))

const toggleRail = () => {
  railCollapsed.value = !railCollapsed.value
  window.localStorage.setItem('ai-test-platform.sidebar-collapsed', String(railCollapsed.value))
}

const toggleStudioNavigation = () => {
  if (isNarrowViewport.value) {
    narrowNavigationVisible.value = !narrowNavigationVisible.value
    return
  }
  toggleRail()
}

const selectSidebarItem = (item: SidebarItem) => {
  if (item.caseDesignKind) {
    deps.generationKind.value = item.caseDesignKind
  }

  if (item.automationKind) {
    deps.automationKind.value = item.automationKind
  }

  if (item.navKey === 'home') {
    homeSection.value = item.homeSection || 'runs'
    deps.navigateToNav('home')
    void nextTick(() => deps.homeViewRef.value?.focusSection(homeSection.value))
    return
  }
  if (item.navKey) deps.navigateToNav(item.navKey)
}

/** 顶部一级工作区切换:先切子视图种类,再落到该工作区默认页面。 */
const selectPrimaryArea = (area: PrimaryArea) => {
  if (area.caseDesignKind) {
    deps.generationKind.value = area.caseDesignKind
  }

  if (area.automationKind) {
    deps.automationKind.value = area.automationKind
  }

  deps.navigateToNav(area.navKey)
}

const openAssistantPage = () => {
  deps.navigateToNav('assistant')
}

const expandStudioNavigation = () => {
  railCollapsed.value = false
  window.localStorage.setItem('ai-test-platform.sidebar-collapsed', 'false')
}

const reset = () => {
  globalSearch.value = ''
  helpVisible.value = false
}
return {
  reset,
  savedCollapsed,
  railCollapsed,
  viewportMode,
  isOnline,
  isCompactDesktop,
  isNarrowViewport,
  narrowNavigationVisible,
  helpVisible,
  compactContextVisible,
  compactInspectorVisible,
  globalSearch,
  homeSection,
  activeProject,
  globalProjectLabel,
  studioProjectLabel,
  studioSectionLabel,
  isSettingsCenterNav,
  isQualityWorkbenchNav,
  isCaseLibraryNav,
  isCaseWorkbenchNav,
  isWorkbenchNav,
  globalLongTaskLabel,
  openActiveLongTask,
  currentNav,
  activeSidebarItemId,
  activePrimaryId,
  sidebarBadges,
  toggleRail,
  toggleStudioNavigation,
  selectSidebarItem,
  selectPrimaryArea,
  openAssistantPage,
  expandStudioNavigation,
}
}

import type { Component } from 'vue'
import {
  BookOpenCheck,
  BookOpen,
  LayoutGrid,
  Monitor,
  Bot,
  Sparkles,
  Braces,
  Bug,
  ChartNoAxesCombined,
  Database,
  FilePenLine,
  MessageSquareText,
  MonitorCheck,
  MonitorCog,
  Play,
  Clock3,
  SlidersHorizontal,
  Users
} from '@lucide/vue'
import type { NavKey } from '../../domain/appNavigation'

export type SidebarItemId =
  | 'home'
  | 'home-pending'
  | 'home-recent'
  | 'case-library'
  | 'web-case-library'
  | 'api-automation'
  | 'web-automation'
  | 'environments'
  | 'reports'
  | 'defects'
  | 'templates'
  | 'jira-config'
  | 'user-management'
  | 'assistant-chain'
  | 'overview'
  | 'case-types'
  | 'help'

export type SidebarAccent = 'teal' | 'cyan' | 'amber' | 'info'

/** 全局工作台流程阶段:①准备(智能用例设计)→ ②执行(自动化)→ ③验证(报告)→ ④交付(缺陷)。
 * 用例库只是保存用例的地方,不是流程阶段;生成内置于智能用例设计画布。 */
export type WorkflowStageId = 'prepare' | 'execute' | 'verify' | 'deliver'

/** 阶段单一来源(顶栏流程条 + 上下文面板流程链共用)。index 为 1 基序号 */
export const WORKFLOW_STAGES: readonly { id: WorkflowStageId; index: number; label: string }[] = [
  { id: 'prepare', index: 1, label: '准备' },
  { id: 'execute', index: 2, label: '执行' },
  { id: 'verify', index: 3, label: '验证' },
  { id: 'deliver', index: 4, label: '交付' }
]

/** 阶段序号圆圈标记(中文序号惯例,非 emoji) */
export const WORKFLOW_STAGE_MARKS: Record<WorkflowStageId, string> = {
  prepare: '①',
  execute: '②',
  verify: '③',
  deliver: '④'
}

export interface SidebarItem {
  id: SidebarItemId
  label: string
  navKey?: NavKey
  icon: Component
  children?: readonly SidebarItem[]
  caseDesignKind?: 'interface' | 'web_automation'
  automationKind?: 'api' | 'web'
  homeSection?: 'runs' | 'pending' | 'recent'
  /** 图标强调色(语义 token 键名),替代旧 nth-child 位置染色 */
  accent?: SidebarAccent
  /** 仅 ADMIN 可见(用户管理等管理功能) */
  adminOnly?: boolean
}

/** 当前导航 → 流程阶段(仅流程内导航有阶段;用例库/首页/设置中心返回 undefined,流程条隐藏) */
export const stageForNavKey = (navKey: NavKey): WorkflowStageId | undefined => {
  switch (navKey) {
    case 'api': return 'prepare'
    case 'automation': return 'execute'
    case 'reports': return 'verify'
    case 'defects': return 'deliver'
    default: return undefined
  }
}

/** 流程阶段 → 目标工作台导航(流程条点击跳转;②执行保持当前 automationKind) */
export const navKeyForStage = (stage: WorkflowStageId): NavKey => {
  switch (stage) {
    case 'prepare': return 'api'
    case 'execute': return 'automation'
    case 'verify': return 'reports'
    case 'deliver': return 'defects'
  }
}

export interface SidebarGroup {
  id: 'home' | 'case-workbench' | 'execution-workbench' | 'quality-workbench' | 'settings-center' | 'overview'
  label: '首页' | '用例工作台' | '执行工作台' | '质量工作台' | '设置中心' | '团队概览'
  items: readonly SidebarItem[]
  /** 整组顶层项图标强调色 */
  accent?: SidebarAccent
}

export type SidebarBadges = Partial<Record<SidebarItemId, number | string>>

/** 顶部一级工作区导航(白色胶囊)。一个工作区对应一个侧边栏分组,点击落到该分组的第一项。 */
export type PrimaryAreaId = 'home' | 'cases' | 'execution' | 'quality' | 'overview' | 'settings'

export interface PrimaryArea {
  id: PrimaryAreaId
  label: string
  groupId: SidebarGroup['id']
  navKey: NavKey
  automationKind?: 'api' | 'web'
  caseDesignKind?: 'interface' | 'web_automation'
}

/** 顺序即顶部胶囊顺序。 */
export const primaryAreas: readonly PrimaryArea[] = [
  { id: 'home', label: '我的工作台', groupId: 'home', navKey: 'home' },
  { id: 'cases', label: '用例库', groupId: 'case-workbench', navKey: 'cases' },
  { id: 'execution', label: '测试执行', groupId: 'execution-workbench', navKey: 'automation', automationKind: 'api' },
  { id: 'quality', label: '质量交付', groupId: 'quality-workbench', navKey: 'reports' },
  { id: 'overview', label: '团队 / 项目概览', groupId: 'overview', navKey: 'overview' },
  { id: 'settings', label: '设置中心', groupId: 'settings-center', navKey: 'settings' }
]

const primaryAreaByNavKey: Record<NavKey, PrimaryAreaId> = {
  home: 'home',
  assistant: 'home',
  cases: 'cases',
  webCases: 'cases',
  api: 'cases',
  automation: 'execution',
  environments: 'execution',
  reports: 'quality',
  defects: 'quality',
  settings: 'settings',
  jiraConfig: 'settings',
  users: 'settings',
  overview: 'overview',
  caseTypes: 'settings'
}

/** 任一 navKey → 所属一级工作区;顶部胶囊据此高亮。 */
export const primaryAreaForNavKey = (navKey: NavKey): PrimaryAreaId => primaryAreaByNavKey[navKey]

export const studioSidebarGroups: readonly SidebarGroup[] = [
  {
    id: 'home',
    label: '首页',
    accent: 'teal',
    items: [
      { id: 'home', label: '运行进度', navKey: 'home', icon: Play, homeSection: 'runs' },
      { id: 'home-pending', label: '待我处理', navKey: 'home', icon: FilePenLine, homeSection: 'pending' },
      { id: 'home-recent', label: '最近工作', navKey: 'home', icon: Clock3, homeSection: 'recent' },
      { id: 'assistant-chain', label: 'AI 助手', navKey: 'assistant', icon: Sparkles }
    ]
  },
  {
    id: 'case-workbench',
    label: '用例工作台',
    accent: 'teal',
    items: [
      { id: 'case-library', label: '接口用例', navKey: 'cases', icon: BookOpenCheck },
      { id: 'web-case-library', label: 'Web 用例', navKey: 'webCases', icon: Monitor },
    ]
  },
  {
    id: 'execution-workbench',
    label: '执行工作台',
    accent: 'cyan',
    items: [
      { id: 'api-automation', label: 'API 自动化', navKey: 'automation', icon: Braces, automationKind: 'api' },
      { id: 'web-automation', label: 'Web 自动化', navKey: 'automation', icon: MonitorCog, automationKind: 'web' },
      { id: 'environments', label: '环境配置', navKey: 'environments', icon: SlidersHorizontal }
    ]
  },
  {
    id: 'quality-workbench',
    label: '质量工作台',
    accent: 'amber',
    items: [
      { id: 'reports', label: '执行报告', navKey: 'reports', icon: ChartNoAxesCombined },
      { id: 'defects', label: '缺陷管理', navKey: 'defects', icon: Bug }
    ]
  },
  {
    id: 'overview',
    label: '团队概览',
    accent: 'info',
    items: [
      { id: 'overview', label: '项目概览', navKey: 'overview', icon: MonitorCheck }
    ]
  },
  {
    id: 'settings-center',
    label: '设置中心',
    accent: 'info',
    items: [
      { id: 'templates', label: '模板字段', navKey: 'settings', icon: BookOpen },
      { id: 'case-types', label: '用例类型', navKey: 'caseTypes', icon: LayoutGrid },
      { id: 'jira-config', label: 'Jira 配置', navKey: 'jiraConfig', icon: SlidersHorizontal },
      { id: 'user-management', label: '用户管理', navKey: 'users', icon: Monitor, adminOnly: true }
    ]
  }
]

/** 按当前用户角色过滤导航:非 ADMIN 隐藏 adminOnly 项。ADMIN 为唯一"超级管理员"角色。 */
export const sidebarGroupsForRole = (
  role: string | undefined,
  groups: readonly SidebarGroup[] = studioSidebarGroups
): SidebarGroup[] => {
  if (role === 'ADMIN') return groups as SidebarGroup[]
  return groups
    .map((group) => ({ ...group, items: group.items.filter((item) => !item.adminOnly) }))
    .filter((group) => group.items.length > 0)
}

export const resolveSidebarActiveItemId = (
  activeNav: NavKey,
  automationKind: 'api' | 'web',
  caseDesignKind: 'interface' | 'web_automation' = 'web_automation'
): SidebarItemId | undefined => {
  if (activeNav === 'api') {
    return 'case-library'
  }

  if (activeNav === 'automation') {
    return automationKind === 'web' ? 'web-automation' : 'api-automation'
  }

  return studioSidebarGroups
    .flatMap((group) => group.items.flatMap((item) => [item, ...(item.children || [])]))
    .find((item) => item.navKey === activeNav)?.id
}

export const resolveSidebarItemById = (id: SidebarItemId | undefined): SidebarItem | undefined =>
  id
    ? studioSidebarGroups
        .flatMap((group) => group.items.flatMap((item) => [item, ...(item.children || [])]))
        .find((item) => item.id === id)
    : undefined

export interface NavDisplay {
  label: string
  icon: Component
}

/** 以侧边栏为唯一导航显示源:根据当前 navKey 反查 label + icon,兜底到智能用例设计 */
export const resolveNavDisplay = (
  navKey: NavKey,
  automationKind: 'api' | 'web',
  caseDesignKind: 'interface' | 'web_automation' = 'web_automation'
): NavDisplay => {
  if (navKey === 'home') return { label: '我的工作台', icon: Play }
  const item =
    resolveSidebarItemById(resolveSidebarActiveItemId(navKey, automationKind, caseDesignKind)) ??
    resolveSidebarItemById('case-library')
  return { label: item?.label ?? '用例库', icon: item?.icon ?? FilePenLine }
}

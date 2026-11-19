import type { NavKey } from './appNavigation'

/** Navigation is handled by App; supplied record identities must survive unchanged. */
export interface HomeNavigateTarget {
  navKey: NavKey
  automationKind?: 'api' | 'web'
  project?: string
  caseId?: string
  reportId?: string
  defectId?: string
  runId?: string
  monitor?: boolean
}

export interface HomeProject {
  value: string
  label: string
}

export type HomeSection = 'runs' | 'pending' | 'recent'

export type HomeRunState =
  | 'preparing' | 'queued' | 'running' | 'pause_requested' | 'paused'
  | 'stop_requested' | 'waiting_user' | 'completed' | 'failed' | 'stopped'

/** A real run snapshot, never a completed report masquerading as active work. */
export interface HomeRun {
  id: string
  name: string
  kind: 'api' | 'web'
  project: string
  environmentName?: string
  state: HomeRunState
  stage?: string
  updatedAt?: string
  completed?: number
  total?: number
  passed?: number
  failed?: number
  error?: number
  target: HomeNavigateTarget
}

export type HomeWorkKind = 'case' | 'report' | 'defect' | 'project' | 'task'
export type HomeWorkFilter = 'all' | HomeWorkKind
export type HomeTone = 'neutral' | 'success' | 'warning' | 'danger'

/** savedAt is a persisted timestamp, explicitly NOT a visit or assignment feed. */
export interface HomeWorkItem {
  id: string
  name: string
  project: string
  kind: HomeWorkKind
  savedAt?: string
  statusLabel?: string
  tone?: HomeTone
  context?: string
  pending?: {
    label: string
    actionLabel: string
    reason?: string
  }
  target: HomeNavigateTarget
}

export interface HomeWorkbenchInput {
  runs: readonly HomeRun[]
  items: readonly HomeWorkItem[]
  project?: string
  filter?: HomeWorkFilter
}

export interface HomeWorkbenchModel {
  runs: HomeRun[]
  recent: HomeWorkItem[]
  pending: HomeWorkItem[]
}

/** Pure display boundary: no storage, HTTP, singleton or synthetic business records. */
export function buildHomeWorkbench(input: HomeWorkbenchInput): HomeWorkbenchModel {
  const inProject = (entry: { project: string }) => !input.project || entry.project === input.project
  const scopedItems = input.items.filter(inProject)
  return {
    runs: input.runs.filter(inProject).filter((run) => !['completed', 'failed', 'stopped'].includes(run.state)),
    recent: scopedItems.filter((item) => Boolean(item.savedAt) && Number.isFinite(Date.parse(item.savedAt!)))
      .filter((item) => !input.filter || input.filter === 'all' || item.kind === input.filter)
      .sort((a, b) => Date.parse(b.savedAt!) - Date.parse(a.savedAt!)),
    pending: scopedItems.filter((item) => Boolean(item.pending))
  }
}

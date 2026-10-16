import type { AiDraftTestPoint } from './aiPlatformRepository'

export type AiDraftConfidenceFilter = 'ALL' | 'high' | 'medium' | 'low'

export interface AiDraftTestPointFilter {
  module?: string
  status?: string
  confidence?: AiDraftConfidenceFilter
  sourceDocumentId?: string
}

export interface AiDraftOption {
  value: string
  count: number
}

export interface AiDraftSourceOption extends AiDraftOption {
  label: string
}

export interface AiDraftTestPointGroup {
  module: string
  items: AiDraftTestPoint[]
}

export interface AiDraftTestPointView {
  items: AiDraftTestPoint[]
  groups: AiDraftTestPointGroup[]
  moduleOptions: AiDraftOption[]
  statusOptions: AiDraftOption[]
  sourceOptions: AiDraftSourceOption[]
  summary: {
    total: number
    confirmed: number
    modified: number
    rejected: number
    inferred: number
  }
}

export const buildAiDraftTestPointView = (
  points: AiDraftTestPoint[],
  filter: AiDraftTestPointFilter = {}
): AiDraftTestPointView => {
  const items = points.filter((point) => {
    if (filter.module && filter.module !== 'ALL' && point.module !== filter.module) return false
    if (filter.status && filter.status !== 'ALL' && point.reviewStatus !== filter.status) return false
    if (filter.sourceDocumentId && filter.sourceDocumentId !== 'ALL' && point.sourceDocumentId !== filter.sourceDocumentId) return false
    if (filter.confidence && filter.confidence !== 'ALL' && confidenceBucket(point.confidenceScore) !== filter.confidence) return false
    return true
  })

  return {
    items,
    groups: groupByModule(items),
    moduleOptions: summarizeBy(points, (point) => point.module),
    statusOptions: summarizeBy(points, (point) => point.reviewStatus),
    sourceOptions: summarizeSources(points),
    summary: {
      total: items.length,
      confirmed: items.filter((point) => point.reviewStatus === 'confirmed').length,
      modified: items.filter((point) => point.reviewStatus === 'modified').length,
      rejected: items.filter((point) => point.reviewStatus === 'rejected').length,
      inferred: items.filter((point) => point.inferred).length
    }
  }
}

export const confidenceBucket = (confidenceScore: number): Exclude<AiDraftConfidenceFilter, 'ALL'> => {
  if (confidenceScore >= 0.85) return 'high'
  if (confidenceScore >= 0.6) return 'medium'
  return 'low'
}

const groupByModule = (points: AiDraftTestPoint[]): AiDraftTestPointGroup[] => {
  const groups = new Map<string, AiDraftTestPoint[]>()
  for (const point of points) {
    const module = point.module || '未分组'
    groups.set(module, [...(groups.get(module) || []), point])
  }
  return Array.from(groups.entries()).map(([module, items]) => ({ module, items }))
}

const summarizeBy = (
  points: AiDraftTestPoint[],
  selector: (point: AiDraftTestPoint) => string
): AiDraftOption[] => Array.from(points.reduce<Map<string, number>>((summary, point) => {
  const value = selector(point) || '未设置'
  summary.set(value, (summary.get(value) || 0) + 1)
  return summary
}, new Map()).entries()).map(([value, count]) => ({ value, count }))

const summarizeSources = (points: AiDraftTestPoint[]): AiDraftSourceOption[] =>
  Array.from(points.reduce<Map<string, { label: string; count: number }>>((summary, point) => {
    const current = summary.get(point.sourceDocumentId)
    summary.set(point.sourceDocumentId, {
      label: current?.label || point.sourceFile || point.sourceDocumentId,
      count: (current?.count || 0) + 1
    })
    return summary
  }, new Map()).entries()).map(([value, source]) => ({
    value,
    label: source.label,
    count: source.count
  }))

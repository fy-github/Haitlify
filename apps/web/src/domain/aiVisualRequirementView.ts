import type { AiDraftTestPoint } from './aiPlatformRepository'
import { confidenceBucket } from './aiDraftTestPointView'

type JsonObject = { [key: string]: unknown }

export interface VisualRegionCoordinates {
  x: number
  y: number
  width: number
  height: number
}

export interface AiVisualEvidence {
  assetId: string
  regionId: string
  regionType: string
  coordinates?: VisualRegionCoordinates
  componentHint?: string
  styleHints: string[]
  interactionHints: string[]
}

export interface AiVisualRequirementRow {
  point: AiDraftTestPoint
  evidence: AiVisualEvidence
  coordinateLabel: string
  confidenceLabel: string
  inferredLabel: string
}

export interface AiVisualRequirementGroup {
  regionType: string
  items: AiVisualRequirementRow[]
}

export interface AiVisualRequirementView {
  items: AiVisualRequirementRow[]
  groups: AiVisualRequirementGroup[]
}

export const buildAiVisualRequirementView = (points: AiDraftTestPoint[]): AiVisualRequirementView => {
  const items = points.flatMap((point) => {
    const evidence = visualEvidenceForDraftPoint(point)
    if (!evidence) return []
    return [{
      point,
      evidence,
      coordinateLabel: evidence.coordinates ? formatVisualRegionCoordinates(evidence.coordinates) : '-',
      confidenceLabel: confidenceLabel(point.confidenceScore),
      inferredLabel: point.inferred ? '是' : '否'
    }]
  })

  return {
    items,
    groups: groupByRegionType(items)
  }
}

export const visualEvidenceForDraftPoint = (point: AiDraftTestPoint): AiVisualEvidence | null => {
  const metadata = isObject(point.metadata) ? point.metadata : {}
  const rawEvidence = isObject(metadata.visualEvidence) ? metadata.visualEvidence : null
  if (!rawEvidence) return null
  const assetId = stringValue(rawEvidence.assetId)
  const regionId = stringValue(rawEvidence.regionId)
  if (!assetId || !regionId) return null

  return {
    assetId,
    regionId,
    regionType: stringValue(rawEvidence.regionType) || 'unknown',
    coordinates: coordinatesValue(rawEvidence.coordinates),
    componentHint: stringValue(rawEvidence.componentHint),
    styleHints: stringArray(rawEvidence.styleHints),
    interactionHints: stringArray(rawEvidence.interactionHints)
  }
}

export const formatVisualRegionCoordinates = (coordinates: VisualRegionCoordinates): string =>
  `x${coordinates.x} y${coordinates.y} ${coordinates.width}x${coordinates.height}`

const groupByRegionType = (items: AiVisualRequirementRow[]): AiVisualRequirementGroup[] => {
  const groups = new Map<string, AiVisualRequirementRow[]>()
  for (const item of items) {
    const regionType = item.evidence.regionType || 'unknown'
    groups.set(regionType, [...(groups.get(regionType) || []), item])
  }
  return Array.from(groups.entries()).map(([regionType, rows]) => ({ regionType, items: rows }))
}

const confidenceLabel = (confidenceScore: number): string => {
  const bucket = confidenceBucket(confidenceScore)
  if (bucket === 'high') return '高'
  if (bucket === 'medium') return '中'
  return '低'
}

const coordinatesValue = (value: unknown): VisualRegionCoordinates | undefined => {
  if (!isObject(value)) return undefined
  return {
    x: numberValue(value.x),
    y: numberValue(value.y),
    width: numberValue(value.width),
    height: numberValue(value.height)
  }
}

const stringArray = (value: unknown): string[] => Array.isArray(value)
  ? value.map((item) => stringValue(item)).filter(Boolean)
  : []

const stringValue = (value: unknown): string => typeof value === 'string' ? value : ''

const numberValue = (value: unknown): number => typeof value === 'number' && Number.isFinite(value) ? value : 0

const isObject = (value: unknown): value is JsonObject => typeof value === 'object' && value !== null && !Array.isArray(value)

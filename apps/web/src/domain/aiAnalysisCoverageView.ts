import type { AiDraftTestPoint } from './aiPlatformRepository'

export interface AiAnalysisChunkProgress {
  plannedChunkId: string
  section: string
  status: string
  skipReason?: string | null
}

export interface AiUnclearQuestion {
  questionId: string
  severity: string
  questionText: string
  sourceRef: string
  status: string
  createdAt?: string | null
  updatedAt?: string | null
}

export interface AiAnalysisCoverageInput {
  draftPoints: AiDraftTestPoint[]
  chunks?: AiAnalysisChunkProgress[]
  warnings?: string[]
  unclearQuestions?: AiUnclearQuestion[]
}

export interface AiAnalysisCoverageRow {
  key: string
  module: string
  caseKind: string
  draftPointCount: number
}

export interface AiAnalysisWarningRow {
  key: string
  plannedChunkId: string
  section: string
  message: string
}

export interface AiAnalysisCoverageView {
  coverageRows: AiAnalysisCoverageRow[]
  warningRows: AiAnalysisWarningRow[]
  unclearQuestions: AiUnclearQuestion[]
  summary: {
    totalDraftPoints: number
    moduleCount: number
    warningCount: number
    unclearQuestionCount: number
  }
}

const severityRank: Record<string, number> = {
  critical: 0,
  high: 1,
  medium: 2,
  low: 3
}

export const buildAiAnalysisCoverageView = (input: AiAnalysisCoverageInput): AiAnalysisCoverageView => {
  const coverageRows = buildCoverageRows(input.draftPoints)
  const warningRows = [
    ...buildWarningRows(input.chunks || []),
    ...buildStringWarningRows(input.warnings || [])
  ]
  const unclearQuestions = [...(input.unclearQuestions || [])].sort(compareQuestions)

  return {
    coverageRows,
    warningRows,
    unclearQuestions,
    summary: {
      totalDraftPoints: input.draftPoints.length,
      moduleCount: new Set(coverageRows.map((row) => row.module)).size,
      warningCount: warningRows.length,
      unclearQuestionCount: unclearQuestions.length
    }
  }
}

const buildCoverageRows = (points: AiDraftTestPoint[]): AiAnalysisCoverageRow[] => {
  const rows = points.reduce<Map<string, AiAnalysisCoverageRow>>((summary, point) => {
    const module = point.module || '未分组'
    const caseKind = point.caseKind || '未设置'
    const key = `${module}|${caseKind}`
    const current = summary.get(key)
    summary.set(key, {
      key,
      module,
      caseKind,
      draftPointCount: (current?.draftPointCount || 0) + 1
    })
    return summary
  }, new Map())

  return Array.from(rows.values()).sort((left, right) =>
    left.module.localeCompare(right.module, 'zh-CN') || left.caseKind.localeCompare(right.caseKind, 'zh-CN')
  )
}

const buildWarningRows = (chunks: AiAnalysisChunkProgress[]): AiAnalysisWarningRow[] =>
  chunks
    .filter((chunk) => chunk.status === 'skipped')
    .map((chunk) => ({
      key: chunk.plannedChunkId,
      plannedChunkId: chunk.plannedChunkId,
      section: chunk.section || '未命名片段',
      message: chunk.skipReason || 'skipped'
    }))

const buildStringWarningRows = (warnings: string[]): AiAnalysisWarningRow[] =>
  warnings.map((warning, index) => ({
    key: `warning-${index}`,
    plannedChunkId: extractPlannedChunkId(warning) || `warning-${index + 1}`,
    section: '覆盖告警',
    message: warning
  }))

const extractPlannedChunkId = (warning: string): string => {
  const match = warning.match(/chunk\s+([^:\s]+)/i)
  return match?.[1] || ''
}

const compareQuestions = (left: AiUnclearQuestion, right: AiUnclearQuestion): number => {
  const rank = (severityRank[left.severity] ?? 4) - (severityRank[right.severity] ?? 4)
  if (rank !== 0) return rank
  return (left.createdAt || '').localeCompare(right.createdAt || '')
}

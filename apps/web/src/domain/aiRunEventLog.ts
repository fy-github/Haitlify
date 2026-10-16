/**
 * Maps the backend-owned normalized run event stream onto the live-panel log shape.
 *
 * The backend (deliverable 3) is the source of truth for the event stream; this module only
 * adapts {@link AiRunEvent} items to the {@link AiAnalysisLogEntry} shape the existing
 * `RequirementImportStage` live panel already renders (kind + text), so that component stays
 * unchanged. Summaries are the backend's short Chinese statements; a clock prefix gives the
 * `14:03:12  …` log cadence from the design.
 */
import type { AiAnalysisLogEntry, AiAnalysisLogKind } from './aiAnalysisProgress'
import type { AiRunEvent } from './aiPlatformRepository'

const kindFor = (event: AiRunEvent): AiAnalysisLogKind => {
  if (event.stage === 'run_succeeded' || event.stage === 'chunk_completed') {
    return event.stage === 'run_succeeded' ? 'success' : 'chunk'
  }
  if (event.stage === 'chunk_started') {
    return 'chunk'
  }
  if (event.stage === 'attempt_started' || event.stage === 'fallback_switched') {
    return 'call'
  }
  if (event.severity === 'error') {
    return 'error'
  }
  return 'info'
}

const clockSegment = (occurredAt?: string): string => {
  if (!occurredAt) return ''
  const parsed = new Date(occurredAt)
  if (Number.isNaN(parsed.getTime())) return ''
  return parsed.toLocaleTimeString('zh-CN', { hour12: false })
}

export const mapRunEventsToLog = (events: ReadonlyArray<AiRunEvent>): AiAnalysisLogEntry[] => {
  if (!Array.isArray(events) || events.length === 0) return []
  return events.map((event, index) => {
    const clock = clockSegment(event.occurredAt)
    const text = clock ? `${clock}  ${event.summary}` : event.summary
    return {
      // Append-only ordered stream → the array index is a stable key across polls.
      id: `ai-run-event-${index}`,
      kind: kindFor(event),
      text
    }
  })
}

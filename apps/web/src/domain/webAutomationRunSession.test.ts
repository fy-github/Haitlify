import { describe, expect, it } from 'vitest'
import {
  WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY,
  createWebRunPollCoordinator,
  resolveWebRunRecovery
} from './webAutomationRunSession'

describe('webAutomationRunSession', () => {
  it('prefers a monitor query run over the stored active run', () => {
    expect(resolveWebRunRecovery('?view=monitor&runId=query-run', 'stored-run')).toEqual({
      mode: 'monitor',
      runId: 'query-run'
    })
    expect(WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY).toBe('ai-test-platform.web-active-run.v1')
  })

  it('recovers a stored run into the workbench when no monitor query exists', () => {
    expect(resolveWebRunRecovery('', 'stored-run')).toEqual({ mode: 'workbench', runId: 'stored-run' })
    expect(resolveWebRunRecovery('?view=monitor', '')).toEqual({ mode: 'workbench', runId: '' })
  })

  it('allows only one polling owner for the same active run', () => {
    const coordinator = createWebRunPollCoordinator()
    const first = coordinator.start('run-1')
    const duplicate = coordinator.start('run-1')
    const replacement = coordinator.start('run-2')

    expect(first.shouldStart).toBe(true)
    expect(duplicate.shouldStart).toBe(false)
    expect(coordinator.isCurrent('run-1', first.token)).toBe(false)
    expect(replacement.shouldStart).toBe(true)
    expect(coordinator.isCurrent('run-2', replacement.token)).toBe(true)
    coordinator.stop('run-2')
    expect(coordinator.start('run-2').shouldStart).toBe(true)
  })
})

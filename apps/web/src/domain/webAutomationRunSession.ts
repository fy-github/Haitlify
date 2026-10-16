export const WEB_AUTOMATION_ACTIVE_RUN_SESSION_KEY = 'ai-test-platform.web-active-run.v1'

export const resolveWebRunRecovery = (search: string, storedRunId: string) => {
  const params = new URLSearchParams(search)
  const queryRunId = params.get('view') === 'monitor' ? params.get('runId')?.trim() || '' : ''
  return queryRunId
    ? { mode: 'monitor' as const, runId: queryRunId }
    : { mode: 'workbench' as const, runId: storedRunId.trim() }
}

export const createWebRunPollCoordinator = () => {
  let activeRunId = ''
  let token = 0
  return {
    start(runId: string) {
      if (activeRunId === runId) return { shouldStart: false, token }
      activeRunId = runId
      token += 1
      return { shouldStart: true, token }
    },
    isCurrent(runId: string, candidateToken: number) {
      return activeRunId === runId && token === candidateToken
    },
    stop(runId?: string) {
      if (runId && activeRunId !== runId) return
      activeRunId = ''
      token += 1
    }
  }
}

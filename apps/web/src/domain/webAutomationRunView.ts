import type {
  WebAutomationCapabilities,
  WebAutomationRunControls,
  WebAutomationRunSnapshot
} from './webAutomationRunRepository'

export interface WebRunTimelineItem {
  id: string
  name: string
  action: string
  phase: 'setup' | 'test' | 'teardown'
  status: 'passed' | 'failed' | 'running'
  durationMs?: number
  errorMessage?: string
}

export interface WebRunBrowserOption {
  value: string
  label: string
  disabled: boolean
  recommendation: string
}

export const buildWebRunView = (snapshot: WebAutomationRunSnapshot) => {
  const terminal = snapshot.status === 'completed' || snapshot.status === 'stopped' || snapshot.status === 'failed'
  const progressPercent = snapshot.progress.totalSteps > 0
    ? Math.min(100, Math.round(snapshot.progress.completedSteps / snapshot.progress.totalSteps * 100))
    : terminal ? 100 : 0
  const timeline: WebRunTimelineItem[] = snapshot.stepLogs.map((log) => ({
    id: log.stepId,
    name: log.name,
    action: log.action,
    phase: log.phase || 'test',
    status: log.status,
    durationMs: log.durationMs,
    errorMessage: log.errorMessage
  }))
  if (snapshot.currentStep && !snapshot.stepLogs.some((log) => log.stepId === snapshot.currentStep?.id)) {
    timeline.push({
      id: snapshot.currentStep.id,
      name: snapshot.currentStep.name,
      action: snapshot.currentStep.action,
      phase: snapshot.currentStep.phase,
      status: 'running'
    })
  }

  return {
    statusLabel: statusLabels[snapshot.status],
    statusType: statusTypes[snapshot.status],
    progressPercent,
    phaseLabel: snapshot.currentStep?.phase === 'setup'
      ? '环境准备'
      : snapshot.currentStep?.phase === 'teardown'
        ? '环境清理'
        : '用例执行',
    controls: terminal ? disabledControls : snapshot.controls,
    timeline
  }
}

export type ReturnTypeOfBuildWebRunView = ReturnType<typeof buildWebRunView>

export const buildBrowserOptions = (capabilities?: WebAutomationCapabilities): WebRunBrowserOption[] => [
  { value: 'follow-case', label: '跟随用例', disabled: false, recommendation: '' },
  ...(capabilities?.browsers || []).map((browser) => ({
    value: browser.id,
    label: browser.label,
    disabled: !browser.available,
    recommendation: browser.recommendation || browser.message || ''
  }))
]

const disabledControls: WebAutomationRunControls = {
  canPause: false,
  canResume: false,
  canStop: false
}

const statusLabels: Record<WebAutomationRunSnapshot['status'], string> = {
  queued: '等待执行',
  running: '执行中',
  pause_requested: '正在暂停',
  paused: '已暂停',
  stop_requested: '正在停止',
  stopped: '已停止',
  completed: '执行完成',
  failed: '执行异常'
}

const statusTypes: Record<WebAutomationRunSnapshot['status'], 'info' | 'primary' | 'warning' | 'success' | 'danger'> = {
  queued: 'info',
  running: 'primary',
  pause_requested: 'warning',
  paused: 'warning',
  stop_requested: 'warning',
  stopped: 'info',
  completed: 'success',
  failed: 'danger'
}

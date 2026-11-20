import { computed, reactive, ref, watch } from 'vue'
import type { ComputedRef, Ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import {
  buildApiRunSnapshot,
  parseApiLoopCount,
  resolveApiExecutionScopeLabel,
  type ApiRunSnapshot
} from '../domain/apiExecutionPreparation'
import {
  appendAutomationReport,
  buildSavedAutomationReportRecord,
  type SavedAutomationReport
} from '../domain/automationReportRepository'
import {
  completeApiRunMonitor,
  createApiRunMonitor,
  recordApiCaseResult,
  recordApiCaseStart,
  type ApiRunMonitor
} from '../domain/apiRunMonitor'
import {
  runAutomationCases,
  type AutomationRunMode,
  type AutomationRunReport
} from '../domain/apiAutomation'
import type { ExecutableRequest, ExecutionEnvironment } from '../domain/apiExecution'
import type { SavedAutomationCase } from '../domain/automationRepository'

export interface ApiExecutionControllerOptions {
  getAvailableCaseCount: () => number
  getFilteredCases?: () => SavedAutomationCase[]
  getCanEdit?: () => boolean
  getOwnerId?: () => number | undefined
  getEnvironment?: () => ExecutionEnvironment | undefined
  getEnvironmentName?: () => string
  beginOperation?: (resource: string) => () => boolean
  captureStorage?: (isCurrent: () => boolean) => Storage
  executeRequest?: (request: ExecutableRequest, isCurrent: () => boolean) => Promise<unknown>
  enterMonitor?: () => Promise<void> | void
  confirmSnapshot?: (message: string, title: string) => Promise<boolean>
  saveReport?: (report: SavedAutomationReport, storage: Storage) => void
  refreshReports?: () => void
  selectReport?: (project: string, reportId: string) => void
  notify?: {
    warning?: (message: string) => void
    success?: (message: string) => void
    error?: (message: string) => void
  }
}

export interface ApiExecutionController {
  automationRunMode: Ref<AutomationRunMode>
  automationConcurrencyLimit: Ref<number>
  apiAutomationLoopCount: Ref<number>
  responseBodyMatchThreshold: Ref<number>
  apiRunCountMin: number
  apiRunCountValue: ComputedRef<number>
  apiRunCountInput: Ref<string>
  apiRunCountError: Ref<string>
  apiRunSnapshot: Ref<ApiRunSnapshot | undefined>
  apiRunMonitor: Ref<ApiRunMonitor | undefined>
  apiAutomationWorkspaceMode: Ref<'prepare' | 'monitor'>
  apiRunConfirming: Ref<boolean>
  apiAutomationRunning: Ref<boolean>
  apiAutomationProgress: { completed: number; total: number; runningCaseId: string }
  automationSelection: Ref<SavedAutomationCase[]>
  automationRunReport: Ref<AutomationRunReport | undefined>
  apiRunGeneration: Ref<number>
  apiExecutionScopeLabel: ComputedRef<string>
  commitApiRunCount(raw?: string): boolean
  onApiRunCountInput(raw: string): boolean
  stepApiRunCount(delta: number): void
  nextRunGeneration(): number
  executeAllAutomationCases(): Promise<boolean>
  reset(): void
}

export const createApiExecutionController = (options: ApiExecutionControllerOptions): ApiExecutionController => {
  const automationRunMode = ref<AutomationRunMode>('sequential')
  const automationConcurrencyLimit = ref(8)
  const apiAutomationLoopCount = ref(1)
  const responseBodyMatchThreshold = ref(100)
  const apiRunCountMin = 1
  const apiRunCountValue = computed(() =>
    automationRunMode.value === 'concurrent' ? automationConcurrencyLimit.value : apiAutomationLoopCount.value
  )
  const apiRunCountInput = ref(String(apiRunCountValue.value))
  const apiRunCountError = ref('')
  const apiRunSnapshot = ref<ApiRunSnapshot>()
  const apiRunMonitor = ref<ApiRunMonitor>()
  const apiAutomationWorkspaceMode = ref<'prepare' | 'monitor'>('prepare')
  const apiRunConfirming = ref(false)
  const apiAutomationRunning = ref(false)
  const apiAutomationProgress = reactive({ completed: 0, total: 0, runningCaseId: '' })
  const automationSelection = ref<SavedAutomationCase[]>([])
  const automationRunReport = ref<AutomationRunReport>()
  const apiRunGeneration = ref(0)
  const apiExecutionScopeLabel = computed(() => resolveApiExecutionScopeLabel(
    automationSelection.value.length,
    options.getAvailableCaseCount()
  ))
  const notify = {
    warning: options.notify?.warning ?? (() => undefined),
    success: options.notify?.success ?? (() => undefined),
    error: options.notify?.error ?? (() => undefined)
  }
  const getFilteredCases = options.getFilteredCases ?? (() => [])
  const getCanEdit = options.getCanEdit ?? (() => false)
  const getOwnerId = options.getOwnerId ?? (() => undefined)
  const getEnvironment = options.getEnvironment ?? (() => undefined)
  const getEnvironmentName = options.getEnvironmentName ?? (() => '默认环境')
  const beginOperation = options.beginOperation ?? (() => () => false)
  const captureStorage = options.captureStorage ?? (() => { throw new Error('Workspace storage is not configured') })
  const executeRequest = options.executeRequest ?? (async () => { throw new Error('API request executor is not configured') })
  const enterMonitor = options.enterMonitor ?? (() => undefined)

  const syncApiRunCountInput = (): void => {
    apiRunCountInput.value = String(apiRunCountValue.value)
    apiRunCountError.value = ''
  }
  const applyApiRunCount = (value: number): void => {
    if (automationRunMode.value === 'concurrent') automationConcurrencyLimit.value = value
    else apiAutomationLoopCount.value = value
  }
  const commitApiRunCount = (raw = apiRunCountInput.value): boolean => {
    const parsed = parseApiLoopCount(raw)
    if (!parsed.valid) {
      apiRunCountError.value = parsed.message || '循环次数必须是正整数'
      return false
    }
    apiRunCountError.value = ''
    applyApiRunCount(parsed.value)
    apiRunCountInput.value = String(parsed.value)
    return true
  }
  const onApiRunCountInput = (raw: string): boolean => {
    apiRunCountInput.value = raw
    const parsed = parseApiLoopCount(raw)
    if (!parsed.valid) {
      apiRunCountError.value = parsed.message || '循环次数必须是正整数'
      return false
    }
    apiRunCountError.value = ''
    applyApiRunCount(parsed.value)
    return true
  }
  const stepApiRunCount = (delta: number): void => {
    commitApiRunCount()
    applyApiRunCount(Math.max(apiRunCountMin, apiRunCountValue.value + delta))
    apiRunCountInput.value = String(apiRunCountValue.value)
    apiRunCountError.value = ''
  }
  const nextRunGeneration = (): number => {
    apiRunGeneration.value += 1
    return apiRunGeneration.value
  }
  const confirmSnapshot = async (snapshot: ApiRunSnapshot): Promise<boolean> => {
    const modeLabel = snapshot.options.mode === 'sequential'
      ? '顺序'
      : snapshot.options.mode === 'concurrent' ? '并发' : '按模块'
    const message = [
      `执行范围：${snapshot.scopeLabel}（用例 ${snapshot.cases.length} 条）`,
      `运行环境：${snapshot.environmentName || '默认环境'} · ${snapshot.environment.baseUrl || '未配置地址'}`,
      `执行方式：${modeLabel} · 循环 ${snapshot.options.loopCount ?? 1} 次 · 并发 ${snapshot.options.concurrencyLimit ?? 8}`,
      `返回体一致度：${snapshot.options.responseBodyMatchThreshold ?? 100}%`,
      '',
      '确认后本次用例、环境与运行参数即锁定，准备页后续修改不会影响这一批执行。'
    ].join('\n')
    if (options.confirmSnapshot) return options.confirmSnapshot(message, '确认本次执行')
    try {
      await ElMessageBox.confirm(message, '确认本次执行', {
        confirmButtonText: '开始执行',
        cancelButtonText: '取消',
        type: 'info',
        customClass: 'api-execution-confirm'
      })
      return true
    } catch {
      return false
    }
  }
  const executeApiAutomationCases = async (snapshot: ApiRunSnapshot): Promise<boolean> => {
    if (!getCanEdit() || apiAutomationRunning.value) return false
    const currentOperation = beginOperation('apiRun')
    const generation = nextRunGeneration()
    const owner = getOwnerId()
    const isCurrent = () => currentOperation()
      && apiRunGeneration.value === generation
      && getOwnerId() === owner
    if (!isCurrent()) return false
    const casesToRun = snapshot.cases as SavedAutomationCase[]
    if (casesToRun.length === 0) {
      notify.warning('没有可执行的自动化用例')
      return false
    }

    apiAutomationRunning.value = true
    automationRunReport.value = undefined
    apiRunMonitor.value = createApiRunMonitor(snapshot)
    const monitor = apiRunMonitor.value
    const acceptsEvent = () => isCurrent() && apiRunMonitor.value === monitor && monitor.status === 'running'
    apiAutomationProgress.completed = 0
    apiAutomationProgress.total = monitor.rows.length
    apiAutomationProgress.runningCaseId = ''
    void enterMonitor()
    const reportStorage = captureStorage(isCurrent)
    const pendingRequests = new Set<Promise<unknown>>()
    try {
      const report = await runAutomationCases(
        casesToRun,
        snapshot.environment,
        snapshot.options,
        async (request) => {
          if (!acceptsEvent()) throw new Error('本次 API 执行上下文已失效')
          const pending = executeRequest(request, acceptsEvent)
          pendingRequests.add(pending)
          try {
            const response = await pending
            if (!acceptsEvent()) throw new Error('本次 API 执行上下文已失效')
            return response as { status: number; body?: unknown }
          } finally {
            pendingRequests.delete(pending)
          }
        },
        {
          onProgress: (progress) => {
            if (!acceptsEvent()) return
            apiAutomationProgress.completed = progress.completed
            apiAutomationProgress.total = progress.total
            apiAutomationProgress.runningCaseId = progress.runningCaseId || ''
            monitor.progress = { ...progress }
          },
          onCaseStart: (testCase) => {
            if (acceptsEvent()) recordApiCaseStart(monitor, testCase)
          },
          onResult: (result) => {
            if (acceptsEvent()) recordApiCaseResult(monitor, result)
          }
        }
      )
      if (!acceptsEvent()) return false
      completeApiRunMonitor(monitor, report)
      automationRunReport.value = report
      const reportRecord: SavedAutomationReport = buildSavedAutomationReportRecord(report, {
        reportId: `report-${Date.now()}`,
        name: `${snapshot.environmentName || '默认环境'}-${new Date().toLocaleString('zh-CN', { hour12: false })}`,
        project: casesToRun[0]?.project || '全部项目',
        environmentName: snapshot.environmentName || '默认环境',
        moduleScope: Array.from(new Set(casesToRun.map((testCase) => testCase.module))),
        createdAt: new Date().toISOString()
      })
      if (options.saveReport) options.saveReport(reportRecord, reportStorage)
      else appendAutomationReport(reportStorage, reportRecord)
      options.refreshReports?.()
      options.selectReport?.(reportRecord.project, reportRecord.reportId)
      notify.success(`执行完成：通过 ${report.summary.passed}，失败 ${report.summary.failed}，错误 ${report.summary.error}`)
      return true
    } catch (error) {
      if (!isCurrent()) return false
      monitor.status = 'error'
      monitor.endedAt = new Date().toISOString()
      monitor.errorMessage = error instanceof Error ? error.message : 'API 自动化执行失败'
      notify.error(monitor.errorMessage)
      return false
    } finally {
      await Promise.allSettled([...pendingRequests])
      if (isCurrent()) {
        if (monitor.status === 'error') {
          for (const row of monitor.rows) if (row.status === 'running' && !row.result) row.status = 'unreturned'
        }
        apiAutomationRunning.value = false
        apiAutomationProgress.runningCaseId = ''
      }
    }
  }
  const executeAllAutomationCases = async (): Promise<boolean> => {
    if (!getCanEdit() || apiAutomationRunning.value || apiRunConfirming.value) return false
    const isCurrent = beginOperation('apiRunStart')
    if (!isCurrent()) return false
    const casesToRun = automationSelection.value.length > 0
      ? automationSelection.value
      : getFilteredCases()
    if (casesToRun.length === 0) {
      notify.warning('没有可执行的自动化用例')
      return false
    }
    const loopValidation = parseApiLoopCount(String(apiAutomationLoopCount.value))
    if (!loopValidation.valid) {
      notify.warning(loopValidation.message || '循环次数必须是正整数')
      return false
    }
    const environment = getEnvironment()
    if (!environment) return false
    const snapshot = buildApiRunSnapshot({
      cases: casesToRun,
      environment,
      environmentName: getEnvironmentName(),
      options: {
        mode: automationRunMode.value,
        concurrencyLimit: automationConcurrencyLimit.value,
        responseBodyMatchThreshold: responseBodyMatchThreshold.value,
        loopCount: loopValidation.value
      },
      scopeLabel: apiExecutionScopeLabel.value,
      frozenAt: new Date().toISOString()
    })
    apiRunConfirming.value = true
    let confirmed = false
    try {
      confirmed = await confirmSnapshot(snapshot)
    } finally {
      if (isCurrent()) apiRunConfirming.value = false
    }
    if (!confirmed || !isCurrent()) return false
    apiRunSnapshot.value = snapshot
    return executeApiAutomationCases(snapshot)
  }
  const reset = (): void => {
    apiAutomationRunning.value = false
    apiRunConfirming.value = false
    apiRunSnapshot.value = undefined
    apiRunMonitor.value = undefined
    apiAutomationWorkspaceMode.value = 'prepare'
    Object.assign(apiAutomationProgress, { completed: 0, total: 0, runningCaseId: '' })
    automationRunReport.value = undefined
    automationSelection.value = []
    nextRunGeneration()
  }

  watch([automationRunMode, apiAutomationLoopCount, automationConcurrencyLimit], syncApiRunCountInput)

  return {
    automationRunMode,
    automationConcurrencyLimit,
    apiAutomationLoopCount,
    responseBodyMatchThreshold,
    apiRunCountMin,
    apiRunCountValue,
    apiRunCountInput,
    apiRunCountError,
    apiRunSnapshot,
    apiRunMonitor,
    apiAutomationWorkspaceMode,
    apiRunConfirming,
    apiAutomationRunning,
    apiAutomationProgress,
    automationSelection,
    automationRunReport,
    apiRunGeneration,
    apiExecutionScopeLabel,
    commitApiRunCount,
    onApiRunCountInput,
    stepApiRunCount,
    nextRunGeneration,
    executeAllAutomationCases,
    reset
  }
}

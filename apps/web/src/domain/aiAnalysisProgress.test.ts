import { describe, expect, it } from 'vitest'
import {
  applyAnalysisSnapshot,
  createAnalysisProgressState,
  isTerminalAnalysisStatus
} from './aiAnalysisProgress'

const run = (overrides: Partial<{
  status: string
  totalChunks: number
  completedChunks: number
  errorSummary: string | null
}> = {}) => ({
  status: 'running',
  totalChunks: 0,
  completedChunks: 0,
  errorSummary: null as string | null,
  ...overrides
})

const lastText = (log: { text: string }[]) => log[log.length - 1]?.text

describe('applyAnalysisSnapshot', () => {
  it('announces the chunk plan once and reports the in-progress chunk', () => {
    const state = createAnalysisProgressState()
    let view = applyAnalysisSnapshot(state, { run: run({ status: 'running', totalChunks: 3, completedChunks: 0 }), draftPoints: [], callRecords: [] })
    expect(view.log.map((entry) => entry.text)).toEqual([
      '已将需求拆分为 3 个分析块',
      '正在分析第 1/3 块…'
    ])
    // identical snapshot must not duplicate announcements
    view = applyAnalysisSnapshot(state, { run: run({ status: 'running', totalChunks: 3, completedChunks: 0 }), draftPoints: [], callRecords: [] })
    expect(view.log.length).toBe(2)
  })

  it('streams chunk completion and the next in-progress line from completedChunks deltas', () => {
    const state = createAnalysisProgressState()
    applyAnalysisSnapshot(state, { run: run({ status: 'running', totalChunks: 2, completedChunks: 0 }), draftPoints: [], callRecords: [] })
    const view = applyAnalysisSnapshot(state, { run: run({ status: 'running', totalChunks: 2, completedChunks: 1 }), draftPoints: [], callRecords: [] })
    expect(view.log.map((entry) => entry.text)).toEqual([
      '已将需求拆分为 2 个分析块',
      '正在分析第 1/2 块…',
      '第 1/2 块分析完成',
      '正在分析第 2/2 块…'
    ])
  })

  it('announces new draft points only for unseen ids and surfaces their titles', () => {
    const state = createAnalysisProgressState()
    let view = applyAnalysisSnapshot(state, { run: run({ status: 'running', totalChunks: 1, completedChunks: 0 }), draftPoints: [{ pointId: 'p1', title: '正常登录' }, { pointId: 'p2', title: '退出登录' }], callRecords: [] })
    expect(view.log.some((entry) => entry.text === '新增草稿测试点：正常登录、退出登录（累计 2）')).toBe(true)
    const lengthAfterFirst = view.log.length
    // same ids again → no new draft line appended
    view = applyAnalysisSnapshot(state, { run: run({ status: 'running', totalChunks: 1, completedChunks: 0 }), draftPoints: [{ pointId: 'p1', title: '正常登录' }, { pointId: 'p2', title: '退出登录' }], callRecords: [] })
    expect(view.log.length).toBe(lengthAfterFirst)
    // one new id → delta line with its title and updated total
    view = applyAnalysisSnapshot(state, { run: run({ status: 'running', totalChunks: 1, completedChunks: 0 }), draftPoints: [{ pointId: 'p1', title: '正常登录' }, { pointId: 'p2', title: '退出登录' }, { pointId: 'p3', title: '忘记密码' }], callRecords: [] })
    expect(view.log.some((entry) => entry.text === '新增草稿测试点：忘记密码（累计 3）')).toBe(true)
  })

  it('streams new call records once, including tokens, and surfaces failed calls as errors', () => {
    const state = createAnalysisProgressState()
    const records = [
      { recordId: 'c1', modelName: 'gpt-4.1', status: 'succeeded', durationMs: 1200, tokenUsageInput: 800, tokenUsageOutput: 120 },
      { recordId: 'c2', modelName: 'gpt-4.1', status: 'failed', durationMs: 500, errorMessage: '上游超时' }
    ]
    let view = applyAnalysisSnapshot(state, { run: run({ status: 'running', totalChunks: 2, completedChunks: 0 }), draftPoints: [], callRecords: records })
    expect(view.log.map((entry) => entry.text)).toContain('调用 gpt-4.1 · 1200ms · 800/120 tokens')
    expect(view.log.map((entry) => entry.text)).toContain('调用 gpt-4.1 失败：上游超时')
    // replaying the same records does not duplicate
    view = applyAnalysisSnapshot(state, { run: run({ status: 'running', totalChunks: 2, completedChunks: 0 }), draftPoints: [], callRecords: records })
    const callLines = view.log.filter((entry) => entry.kind === 'call' || entry.kind === 'error')
    expect(callLines.length).toBe(2)
  })

  it('omits the token segment when token usage is unavailable', () => {
    const state = createAnalysisProgressState()
    const view = applyAnalysisSnapshot(state, {
      run: run({ status: 'running', totalChunks: 1, completedChunks: 0 }),
      draftPoints: [],
      callRecords: [{ recordId: 'c1', modelName: 'gpt-4.1', status: 'succeeded', durationMs: 900 }]
    })
    expect(view.log.map((entry) => entry.text)).toContain('调用 gpt-4.1 · 900ms')
  })

  it('emits a single terminal line for succeeded/failed/cancelled', () => {
    const state = createAnalysisProgressState()
    applyAnalysisSnapshot(state, { run: run({ status: 'running', totalChunks: 2, completedChunks: 1 }), draftPoints: [{ pointId: 'p1' }, { pointId: 'p2' }], callRecords: [] })
    let view = applyAnalysisSnapshot(state, { run: run({ status: 'succeeded', totalChunks: 2, completedChunks: 2 }), draftPoints: [{ pointId: 'p1' }, { pointId: 'p2' }], callRecords: [] })
    expect(lastText(view.log)).toBe('分析完成，共生成 2 条草稿测试点')
    // second terminal snapshot must not duplicate
    view = applyAnalysisSnapshot(state, { run: run({ status: 'succeeded', totalChunks: 2, completedChunks: 2 }), draftPoints: [{ pointId: 'p1' }, { pointId: 'p2' }], callRecords: [] })
    expect(view.log.filter((entry) => entry.kind === 'success').length).toBe(1)

    const failedState = createAnalysisProgressState()
    view = applyAnalysisSnapshot(failedState, { run: run({ status: 'failed', totalChunks: 1, completedChunks: 0, errorSummary: 'LLM 网关超时' }), draftPoints: [], callRecords: [] })
    expect(lastText(view.log)).toBe('分析失败：LLM 网关超时')

    const cancelledState = createAnalysisProgressState()
    view = applyAnalysisSnapshot(cancelledState, { run: run({ status: 'cancelled', totalChunks: 1, completedChunks: 0 }), draftPoints: [], callRecords: [] })
    expect(lastText(view.log)).toBe('已取消分析任务')
  })

  it('maps progress percentage without exceeding the running band and reaches 100 on success', () => {
    const state = createAnalysisProgressState()
    let view = applyAnalysisSnapshot(state, { run: run({ status: 'queued' }), draftPoints: [], callRecords: [] })
    expect(view.percentage).toBe(35)
    view = applyAnalysisSnapshot(state, { run: run({ status: 'running', totalChunks: 4, completedChunks: 0 }), draftPoints: [], callRecords: [] })
    expect(view.percentage).toBe(40)
    view = applyAnalysisSnapshot(state, { run: run({ status: 'running', totalChunks: 4, completedChunks: 2 }), draftPoints: [], callRecords: [] })
    expect(view.percentage).toBeGreaterThanOrEqual(40)
    expect(view.percentage).toBeLessThanOrEqual(95)
    view = applyAnalysisSnapshot(state, { run: run({ status: 'running', totalChunks: 4, completedChunks: 4 }), draftPoints: [], callRecords: [] })
    expect(view.percentage).toBe(95)
    view = applyAnalysisSnapshot(state, { run: run({ status: 'succeeded', totalChunks: 4, completedChunks: 4 }), draftPoints: [], callRecords: [] })
    expect(view.percentage).toBe(100)
  })

  it('produces stable, monotonically increasing entry ids', () => {
    const state = createAnalysisProgressState()
    const view = applyAnalysisSnapshot(state, { run: run({ status: 'running', totalChunks: 1, completedChunks: 0 }), draftPoints: [{ pointId: 'p1' }], callRecords: [] })
    expect(view.log.map((entry) => entry.id)).toEqual(['ai-analysis-log-0', 'ai-analysis-log-1', 'ai-analysis-log-2'])
  })

  it('classifies terminal statuses', () => {
    expect(isTerminalAnalysisStatus('succeeded')).toBe(true)
    expect(isTerminalAnalysisStatus('failed')).toBe(true)
    expect(isTerminalAnalysisStatus('cancelled')).toBe(true)
    expect(isTerminalAnalysisStatus('running')).toBe(false)
    expect(isTerminalAnalysisStatus('queued')).toBe(false)
  })
})

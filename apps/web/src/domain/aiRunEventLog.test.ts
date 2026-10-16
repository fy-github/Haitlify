import { describe, expect, it } from 'vitest'
import { mapRunEventsToLog } from './aiRunEventLog'
import type { AiRunEvent } from './aiPlatformRepository'

const event = (over: Partial<AiRunEvent> = {}): AiRunEvent => ({
  runId: 'run-1',
  occurredAt: '2026-07-30T06:03:12Z',
  stage: 'run_started',
  severity: 'info',
  summary: '分析任务已启动',
  ...over
})

describe('mapRunEventsToLog', () => {
  it('maps backend events to log entries with a clock prefix and stable ids', () => {
    const log = mapRunEventsToLog([
      event({ stage: 'run_started', summary: '分析任务已启动' }),
      event({ stage: 'chunk_completed', summary: '分片 1/2 完成' }),
      event({ stage: 'run_succeeded', summary: '分析任务完成' })
    ])

    expect(log).toHaveLength(3)
    expect(log[0].id).toBe('ai-run-event-0')
    expect(log[1].id).toBe('ai-run-event-1')
    expect(log[0].text).toMatch(/\d\d:\d\d:\d\d  分析任务已启动/)
    expect(log.find((entry) => entry.kind === 'success')?.text).toContain('分析任务完成')
  })

  it('classifies error and fallback stages into the right log kinds', () => {
    const log = mapRunEventsToLog([
      event({ stage: 'attempt_started', summary: '调用主模型' }),
      event({ stage: 'fallback_switched', severity: 'warn', summary: '切换备用模型' }),
      event({ stage: 'attempt_failed', severity: 'error', summary: '调用失败' }),
      event({ stage: 'run_deadline_exceeded', severity: 'error', summary: '超过时限' })
    ])

    expect(log.map((entry) => entry.kind)).toEqual(['call', 'call', 'error', 'error'])
  })

  it('never embeds secrets — only backend summaries are surfaced', () => {
    const log = mapRunEventsToLog([
      event({ stage: 'attempt_started', summary: '调用模型 gpt-5.5（提供方 生产 OpenAI）' })
    ])
    const rendered = JSON.stringify(log)
    expect(rendered).not.toContain('sk-')
    expect(rendered).not.toContain('apiKey')
  })

  it('returns an empty list when there are no events', () => {
    expect(mapRunEventsToLog([])).toEqual([])
  })
})

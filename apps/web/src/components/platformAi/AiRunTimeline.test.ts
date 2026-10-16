// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import AiRunTimeline from './AiRunTimeline.vue'

const event = (overrides: Record<string, unknown>) => ({
  runId: 'run-1',
  occurredAt: '2026-10-10T10:00:00Z',
  stage: 'info',
  severity: 'info',
  summary: '',
  eventSeq: 1,
  ...overrides
}) as never

describe('AiRunTimeline', () => {
  it('distinguishes queued, calling, streaming, tool, retry and human stages', () => {
    const wrapper = mount(AiRunTimeline, {
      props: {
        events: [
          event({ stage: 'preflight', summary: '预检通过', eventSeq: 1 }),
          event({ stage: 'attempt_started', summary: '开始调用', model: 'gpt-4.1', eventSeq: 2 }),
          event({ stage: 'chunk_completed', summary: '片段完成', chunkIndex: 2, chunkTotal: 4, eventSeq: 3 }),
          event({ stage: 'tool_call_completed', summary: '工具返回', eventSeq: 4 }),
          event({ stage: 'fallback_switched', summary: '切换备用路由', retryReason: 'rate_limited', eventSeq: 5 }),
          event({ stage: 'waiting_human', summary: '等待确认', eventSeq: 6 })
        ]
      }
    })
    const kinds = ['queued', 'calling', 'streaming', 'tool', 'retry', 'human']
    for (const kind of kinds) {
      expect(wrapper.find(`[data-testid="ai-timeline-item-${kind}"]`).exists()).toBe(true)
    }
    expect(wrapper.text()).toContain('等待人工确认')
    expect(wrapper.text()).toContain('重试')
  })

  it('shows heartbeat as heartbeat and never as token progress', () => {
    const wrapper = mount(AiRunTimeline, {
      props: { events: [event({ stage: 'attempt_heartbeat', summary: '连接存活', eventSeq: 9 })] }
    })
    expect(wrapper.text()).toContain('心跳')
    expect(wrapper.text().toLowerCase()).not.toContain('token')
    expect(wrapper.text()).not.toMatch(/\d+%/)
  })

  it('renders the real model, shard counters and partial completion from backend facts only', () => {
    const wrapper = mount(AiRunTimeline, {
      props: {
        runStatus: 'running',
        events: [
          event({ stage: 'attempt_started', model: 'qwen-max', eventSeq: 1 }),
          event({ stage: 'chunk_completed', chunkIndex: 1, chunkTotal: 3, eventSeq: 2 }),
          event({ stage: 'chunk_completed', chunkIndex: 2, chunkTotal: 3, eventSeq: 3 })
        ]
      }
    })
    expect(wrapper.get('[data-testid="ai-timeline-model"]').text()).toContain('qwen-max')
    expect(wrapper.get('[data-testid="ai-timeline-shards"]').text()).toContain('2/3')
  })

  it('copies a redacted diagnostics summary instead of raw credential text', async () => {
    let copiedText = ''
    const writeText = vi.fn(async (text: string) => { copiedText = text })
    Object.assign(navigator, { clipboard: { writeText } })
    const wrapper = mount(AiRunTimeline, {
      props: {
        events: [event({
          stage: 'run_failed',
          severity: 'error',
          summary: 'HTTP 401 with Bearer sk-live-topsecret',
          retryReason: 'auth_denied',
          eventSeq: 4
        })]
      }
    })
    await wrapper.get('[data-testid="ai-timeline-copy"]').trigger('click')
    expect(writeText).toHaveBeenCalledTimes(1)
    expect(copiedText).not.toContain('sk-live-topsecret')
    expect(copiedText).toContain('auth_denied')
    expect(wrapper.text()).toContain('已复制')
  })

  it('renders an explicit empty state and orders events by eventSeq', () => {
    const empty = mount(AiRunTimeline, { props: { events: [] } })
    expect(empty.find('[data-testid="ai-timeline-empty"]').exists()).toBe(true)

    const wrapper = mount(AiRunTimeline, {
      props: {
        events: [
          event({ stage: 'run_succeeded', summary: '后到的事件', eventSeq: 9 }),
          event({ stage: 'preflight', summary: '先发生的事件', eventSeq: 1 })
        ]
      }
    })
    const text = wrapper.text()
    expect(text.indexOf('先发生的事件')).toBeLessThan(text.indexOf('后到的事件'))
  })
})

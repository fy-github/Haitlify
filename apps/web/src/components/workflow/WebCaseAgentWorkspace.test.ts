// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import WebCaseAgentWorkspace from './WebCaseAgentWorkspace.vue'
import { getWebCaseQualityRating, type WebCaseAgentEvent, type WebCaseAgentRun, type WebCaseQualityRating } from '../../domain/webCaseAgentRepository'

vi.mock('../../domain/webCaseAgentRepository', async (importOriginal) => {
  const original = await importOriginal<typeof import('../../domain/webCaseAgentRepository')>()
  return { ...original, getWebCaseQualityRating: vi.fn() }
})

const run: WebCaseAgentRun = {
  runId: 'run-1',
  projectId: 1,
  status: 'running',
  currentStage: 'GENERATION',
  requestedPointIds: ['point-1'],
  completedPointIds: [],
  blockedPointIds: [],
  ruleVersion: 1
}

const event: WebCaseAgentEvent = {
  eventId: 'evt-1',
  runId: 'run-1',
  sequenceNo: 1,
  occurredAt: '2026-08-06T09:52:48.297543Z',
  stage: 'EVIDENCE',
  eventType: 'stage_started',
  severity: 'info',
  summary: '已获得 1 个页面证据片段，命中 2 条素材',
  knowledgeHitIds: ['locator-1', 'locator-2']
}
const evidenceEventWithScreenshot: WebCaseAgentEvent = {
  ...event,
  eventId: 'evt-screenshot',
  details: {
    pageUrl: 'https://example.test/#/project',
    screenshotUrl: 'http://127.0.0.1:19323/artifacts/evidence.png',
    actionType: 'goto',
    actionTarget: '/#/project'
  }
}

const explorationEvent: WebCaseAgentEvent = {
  ...event,
  eventId: 'evt-exploration',
  summary: '第2/5轮 · 页面35 · 元素25 · 总分70 · 菜单搜索',
  details: {
    round: 2,
    maxRounds: 5,
    pageUrl: 'https://example.test/#/logs',
    pageScore: 35,
    elementScore: 25,
    stateScore: 10,
    totalScore: 70,
    actionType: 'menu-search',
    actionTarget: '日志',
    fingerprint: 'fp-1'
  }
}

describe('WebCaseAgentWorkspace', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('renders the execution progress without the retired two-phase strip, event time to seconds, and opens event details', async () => {
    const wrapper = mount(WebCaseAgentWorkspace, {
      props: { run, events: [event], naturalPlanCount: 1 },
      global: { plugins: [ElementPlus] }
    })

    const expectedTime = new Intl.DateTimeFormat('zh-CN', {
      year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
    }).format(new Date(event.occurredAt)).replace(/\//g, '-')
    expect(wrapper.get('time').text()).toBe(expectedTime)
    expect(wrapper.get('time').attributes('datetime')).toBe(event.occurredAt)
    expect(wrapper.get('.web-case-agent-event').attributes('title')).toBe(event.summary)
    // 用户 2026-10-14 指正：工作区里去掉两阶段说明栏与「测试设计」流水线卡（该阶段由前置流程完成，本页只读取已评审计划）。
    expect(wrapper.find('.two-phase-indicator').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('阶段 1 · 测试设计')
    expect(wrapper.text()).not.toContain('阶段 2 · 自动化落地')
    expect(wrapper.text()).not.toContain('测试设计')
    expect(wrapper.findAll('.web-case-agent-workspace__stages li')).toHaveLength(5)
    expect(wrapper.text()).toContain('素材取证')
    expect(wrapper.text()).toContain('自然语言设计 1')
    expect(wrapper.text()).toContain('素材命中 2')
    expect(wrapper.text()).toContain('素材 2')

    await wrapper.get('.web-case-agent-event').trigger('click')
    expect(wrapper.find('.web-case-agent-evidence-detail').exists()).toBe(true)
    expect(wrapper.text()).toContain('素材取证详情')
  })

  it('falls back to the summary for legacy events with null or empty details', () => {
    const legacy = mount(WebCaseAgentWorkspace, {
      props: { events: [{ ...event, details: null }] },
      global: { plugins: [ElementPlus] }
    })
    expect(legacy.get('.web-case-agent-event').attributes('title')).toBe(event.summary)

    const emptyDetails = mount(WebCaseAgentWorkspace, {
      props: { events: [{ ...event, details: {} }] },
      global: { plugins: [ElementPlus] }
    })
    expect(emptyDetails.get('.web-case-agent-event').attributes('title')).toBe(event.summary)
  })

  it('opens a visible error-details dialog for compilation failures', async () => {
    const compileFailure: WebCaseAgentEvent = {
      ...event,
      eventId: 'evt-compile-failure',
      stage: 'GENERATION',
      severity: 'error',
      summary: 'IllegalArgumentException: AI 生成失败: 上游响应的模型内容为空或不可解析',
      errorCode: 'web_case_compile_blocked',
      details: { category: 'action_failed', failureReason: '上游响应的模型内容为空或不可解析' }
    }
    const wrapper = mount(WebCaseAgentWorkspace, {
      props: { run: { ...run, status: 'blocked', currentStage: 'GENERATION' }, events: [compileFailure] },
      global: { plugins: [ElementPlus] }
    })

    await wrapper.get('.web-case-agent-event').trigger('click')

    expect(wrapper.text()).toContain('错误详情')
    expect(wrapper.text()).toContain('上游响应的模型内容为空或不可解析')
    expect(wrapper.text()).toContain('web_case_compile_blocked')
  })

  it('opens the latest evidence details when the evidence stage card is clicked', async () => {
    const wrapper = mount(WebCaseAgentWorkspace, {
      props: { run, events: [event], naturalPlanCount: 1 },
      global: { plugins: [ElementPlus] }
    })

    const evidenceStage = wrapper.findAll('.web-case-agent-workspace__stages li').find((item) => item.text().includes('素材取证'))
    expect(evidenceStage).toBeDefined()
    await evidenceStage!.trigger('click')
    expect(wrapper.find('.web-case-agent-evidence-detail').exists()).toBe(true)
  })

  it('shows the browser screenshot inside evidence details', async () => {
    const wrapper = mount(WebCaseAgentWorkspace, {
      props: { run, events: [evidenceEventWithScreenshot] },
      global: { plugins: [ElementPlus] }
    })
    await wrapper.get('.web-case-agent-event').trigger('click')
    expect(wrapper.get('.web-case-agent-evidence-detail__screenshot img').attributes('src')).toContain('evidence.png')
  })

  it('shows a compact manual takeover prompt and no standalone diagnosis card', () => {
    const blockedRun: WebCaseAgentRun = {
      ...run,
      status: 'blocked',
      currentStage: 'EVIDENCE',
      blockedPointIds: ['point-1'],
      errorSummary: '所有测试点都未产出可执行 Web 用例'
    }
    const errorEvent: WebCaseAgentEvent = {
      ...event,
      eventId: 'evt-blocked',
      severity: 'error',
      summary: '目标页面需要人工接管',
      errorCode: 'browser_evidence_blocked',
      knowledgeHitIds: [],
      details: {
        category: 'manual_required',
        failureReason: 'captcha_required token=secret locator=button[data-secret="raw"]',
        handoff: { required: true, mode: 'manual', reason: 'manual_required', resumeSupported: false }
      }
    }
    const wrapper = mount(WebCaseAgentWorkspace, {
      props: { run: blockedRun, events: [errorEvent] },
      global: { plugins: [ElementPlus] }
    })

    expect(wrapper.text()).toContain('阻塞 1')
    expect(wrapper.text()).toContain('人工复核')
    expect(wrapper.text()).toContain('需人工接管')
    expect(wrapper.text()).not.toContain('secret')
    expect(wrapper.find('.web-case-agent-workspace__diagnosis').exists()).toBe(false)
    expect(wrapper.find('.web-case-agent-workspace__stages .is-blocked').exists()).toBe(true)
  })

  it.each([
    ['login_failed', '需人工重新登录接管'],
    ['auth_context_lost', '需人工重新登录接管']
  ] as const)('maps %s to the same compact takeover prompt', (category, prompt) => {
    const wrapper = mount(WebCaseAgentWorkspace, {
      props: {
        events: [{
          ...event,
          eventId: `evt-${category}`,
          severity: 'error',
          summary: '需要人工处理',
          details: {
            category,
            retryable: false,
            failureReason: 'token=secret locator=button[data-token="secret"]',
            handoff: { required: true, mode: 'manual', reason: category, resumeSupported: false }
          }
        }]
      },
      global: { plugins: [ElementPlus] }
    })

    expect(wrapper.text()).toContain(prompt)
    expect(wrapper.text()).not.toContain('token=secret')
    expect(wrapper.text()).not.toContain('button[data-token')
  })

  it.each([
    ['navigation_failed', '页面跳转失败', '页面不匹配'],
    ['unsafe_action', '动作存在风险，未执行', '需人工接管'],
    ['locator_persistence', '定位器无法稳定命中', '需人工接管'],
    ['unsupported_action', '不支持该步骤动作', ''],
    ['pagination_not_changed', '翻页后列表未变化', ''],
    ['action_failed', '动作执行失败', '']
  ] as const)('renders runner category %s verbatim instead of folding it', (category, label, forbidden) => {
    // 复核方 F2：这是第四处折叠点 —— 前三处（PlaywrightRunClient / WebCaseAgentDispatcher /
    // webCaseAgentRepository）第 7 轮已修，工作台 UI 仍把 navigation_failed 折成「页面不匹配」、
    // 把 unsafe_action/locator_persistence 折成「DOM 不支持 · 需人工接管」。
    const wrapper = mount(WebCaseAgentWorkspace, {
      props: {
        events: [{
          ...event,
          eventId: `evt-category-${category}`,
          severity: 'error',
          summary: '执行失败',
          details: { category, failureReason: `${category} failed` }
        }]
      },
      global: { plugins: [ElementPlus] }
    })

    expect(wrapper.text()).toContain(label)
    if (forbidden) expect(wrapper.text()).not.toContain(forbidden)
  })

  it('shows latest exploration round, score, short page and action details', () => {
    const wrapper = mount(WebCaseAgentWorkspace, {
      props: { run, events: [explorationEvent] },
      global: { plugins: [ElementPlus] }
    })

    expect(wrapper.text()).toContain('探索第 2/5 轮')
    expect(wrapper.text()).toContain('总分 70')
    expect(wrapper.text()).toContain('#/logs')
    expect(wrapper.text()).toContain('菜单搜索')
    expect(wrapper.get('.web-case-agent-event').attributes('title')).toContain('https://example.test/#/logs')
  })

  it('shows the latest safe point failure diagnostics and redacts sensitive details', () => {
    const failureEvent: WebCaseAgentEvent = {
      ...explorationEvent,
      eventId: 'evt-failure',
      summary: '页面探索失败',
      details: {
        round: 3,
        maxRounds: 5,
        category: 'network_blocked',
        retryable: true,
        failureReason: '请求被网络策略阻断',
        safePageUrl: 'https://example.test/settings?token=secret#fragment-secret',
        scores: { page: 40, element: 12, total: 52 },
        locatorFingerprint: 'abc123',
        accessibleName: '保存',
        locator: '#password=top-secret'
      }
    }
    const wrapper = mount(WebCaseAgentWorkspace, {
      props: { run, events: [failureEvent] },
      global: { plugins: [ElementPlus] }
    })

    expect(wrapper.text()).toContain('未知请求被阻断')
    expect(wrapper.text()).toContain('可重试')
    expect(wrapper.text()).toContain('探索第 3/5 轮')
    expect(wrapper.text()).toContain('总分 52')
    expect(wrapper.text()).toContain('/settings')
    expect(wrapper.text()).not.toContain('secret')
    expect(wrapper.text()).not.toContain('top-secret')
    expect(wrapper.get('.web-case-agent-event').attributes('title')).toContain('请求被网络策略阻断')
    expect(wrapper.get('.web-case-agent-event').attributes('title')).not.toContain('password')
    expect(wrapper.get('.web-case-agent-event').attributes('title')).not.toContain('top-secret')
  })

  it('takes all top-level diagnostics from the latest point failure event', () => {
    const olderFailure: WebCaseAgentEvent = {
      ...explorationEvent,
      eventId: 'evt-failure-old',
      pointId: 'point-1',
      details: {
        round: 3,
        maxRounds: 5,
        category: 'network_blocked',
        retryable: true,
        scores: { total: 52 },
        safePageUrl: 'https://example.test/settings-a'
      }
    }
    const newerExploration: WebCaseAgentEvent = {
      ...explorationEvent,
      eventId: 'evt-exploration-new',
      pointId: 'point-1',
      details: {
        round: 4,
        maxRounds: 5,
        scores: { total: 99 },
        safePageUrl: 'https://example.test/settings-b'
      }
    }
    const wrapper = mount(WebCaseAgentWorkspace, {
      props: { run, events: [olderFailure, newerExploration] },
      global: { plugins: [ElementPlus] }
    })

    expect(wrapper.text()).toContain('未知请求被阻断')
    expect(wrapper.text()).toContain('探索第 3/5 轮')
    expect(wrapper.text()).toContain('总分 52')
    expect(wrapper.text()).toContain('/settings-a')
    expect(wrapper.text()).not.toContain('探索第 4/5 轮')
    expect(wrapper.text()).not.toContain('总分 99')
    expect(wrapper.text()).not.toContain('/settings-b')
  })

  it('sanitizes malformed URL-like and credential fragments in failure titles', () => {
    const unsafeFailure: WebCaseAgentEvent = {
      ...event,
      eventId: 'evt-unsafe-failure',
      details: {
        category: 'network_blocked',
        failureReason: 'app.internal/path?token=secret cookie={"session":"cookie-secret"} Authorization: Bearer header-secret password=pass-secret apiKey=key-secret secretKey=secret-key locator=button[data-secret="raw"]',
        safePageUrl: 'https://safe.example.test/settings',
        locatorFingerprint: 'fp-safe'
      }
    }
    const wrapper = mount(WebCaseAgentWorkspace, {
      props: { events: [unsafeFailure] },
      global: { plugins: [ElementPlus] }
    })

    const title = wrapper.get('.web-case-agent-event').attributes('title') || ''
    expect(title).toContain('fp-safe')
    expect(title).not.toContain('token=secret')
    expect(title).not.toContain('cookie-secret')
    expect(title).not.toContain('Bearer')
    expect(title).not.toContain('header-secret')
    expect(title).not.toContain('pass-secret')
    expect(title).not.toContain('key-secret')
    expect(title).not.toContain('secret-key')
    expect(title).not.toContain('button[data-secret="raw"]')
  })

  it('keeps a sanitized action target fallback for legacy events', () => {
    const wrapper = mount(WebCaseAgentWorkspace, {
      props: {
        events: [{
          ...event,
          details: { actionType: 'click', actionTarget: '#password=raw-secret' }
        }]
      },
      global: { plugins: [ElementPlus] }
    })

    expect(wrapper.text()).toContain('点击')
    expect(wrapper.text()).toContain('定位器')
    expect(wrapper.text()).not.toContain('raw-secret')
    expect(wrapper.get('.web-case-agent-event').attributes('title')).toContain('定位器')
  })

  it.each([
    ['locator_persistence', '定位器无法稳定命中'],
    ['ambiguous_locator', '定位器存在歧义，等待重新取证']
  ] as const)('uses a safe fallback message for %s', (category, message) => {
    const wrapper = mount(WebCaseAgentWorkspace, {
      props: {
        events: [{ ...event, details: { category, retryable: false, failureReason: '' } }]
      },
      global: { plugins: [ElementPlus] }
    })

    expect(wrapper.text()).toContain(message)
  })

  it('keeps long failure diagnostics constrained to the compact timeline row', () => {
    const wrapper = mount(WebCaseAgentWorkspace, {
      props: {
        events: [{
          ...event,
          details: {
            category: 'element_not_found',
            failureReason: 'x'.repeat(500),
            safePageUrl: 'https://example.test/' + 'a'.repeat(500)
          }
        }]
      },
      global: { plugins: [ElementPlus] }
    })

    const eventRow = wrapper.get('.web-case-agent-event')
    expect(eventRow.classes()).toContain('web-case-agent-event')
    expect(wrapper.get('.web-case-agent-event__failure').attributes('title')).toContain('x'.repeat(500))
    expect(wrapper.get('.web-case-agent-event__failure').attributes('style')).toBeUndefined()
  })

  it.each([
    ['running', '运行中'],
    ['queued', '排队中'],
    ['paused', '已暂停'],
    ['succeeded', '已完成'],
    ['failed', '失败'],
    ['blocked', '已阻塞'],
    ['cancelled', '已取消']
  ] as const)('renders Chinese header label for status %s (no raw DSL value)', (status, label) => {
    const wrapper = mount(WebCaseAgentWorkspace, {
      props: { run: { ...run, status }, events: [] },
      global: { plugins: [ElementPlus] }
    })

    expect(wrapper.text()).toContain(label)
    expect(wrapper.text()).not.toContain(status)
  })

  it('falls back to 空闲 in the header when no run is present', () => {
    const wrapper = mount(WebCaseAgentWorkspace, {
      props: { events: [] },
      global: { plugins: [ElementPlus] }
    })

    expect(wrapper.text()).toContain('空闲')
  })

  it('restores the existing rating when a succeeded run is mounted', async () => {
    const existing: WebCaseQualityRating = {
      ratingId: 'qr-1',
      runId: 'run-1',
      projectId: 1,
      overallScore: 8,
      dimensionScores: { coverage: 8, source_traceability: 7, testability: 8, duplicate_risk: 9, automation_readiness: 8, review_risk: 7 },
      comment: '整体可固化',
      baseline: true,
      createdAt: '2026-08-20T00:00:00Z'
    }
    vi.mocked(getWebCaseQualityRating).mockResolvedValue(existing)

    const wrapper = mount(WebCaseAgentWorkspace, {
      props: { run: { ...run, status: 'succeeded' }, events: [] },
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    expect(getWebCaseQualityRating).toHaveBeenCalledWith('run-1')
    expect(wrapper.text()).toContain('已固化基准 8/10')
  })

  it('does not fetch an existing rating while the run is still running', async () => {
    const wrapper = mount(WebCaseAgentWorkspace, {
      props: { run, events: [] },
      global: { plugins: [ElementPlus] }
    })
    await flushPromises()

    expect(getWebCaseQualityRating).not.toHaveBeenCalled()
  })
})

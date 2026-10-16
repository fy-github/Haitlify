import test from 'node:test'
import assert from 'node:assert/strict'
import { runPageExploration } from './page-explorer.mjs'
import { pageFingerprint } from './page-evidence.mjs'
import { __testables as pageLocatorDiscoveryTestables } from './page-locator-discovery.mjs'

test('changes strategy once and matches the second observation', async () => {
  const performedActions = []
  const observations = [
    { url: '#/project', fingerprint: 'home', elements: [] },
    {
      url: '#/open/threat-intelligence',
      title: '威胁情报',
      headings: ['威胁情报'],
      fingerprint: 'target',
      elements: [{ label: '停用', contextText: '即将到期' }]
    }
  ]

  const result = await runPageExploration({
    intent: {
      pageAliases: ['威胁情报'],
      elementTerms: ['停用'],
      stateTerms: ['即将到期']
    }
  }, {
    observe: async () => observations.shift(),
    chooseAction: async () => ({ type: 'menu-search', target: '威胁情报' }),
    performAction: async (action) => performedActions.push(action),
    now: () => 1
  })

  assert.equal(result.status, 'matched')
  assert.equal(result.rounds.length, 2)
  assert.deepEqual(result.rounds[0].action, { type: 'menu-search', target: '威胁情报' })
  assert.equal(result.finalObservation.fingerprint, 'target')
  assert.equal(result.scores.totalScore, 75)
  assert.deepEqual(result.navigationTrail, ['menu-search:威胁情报'])
  assert.deepEqual(performedActions, [{ type: 'menu-search', target: '威胁情报' }])
})

test('carries sourcePointId through exploration response and rounds', async () => {
  const result = await runPageExploration({
    sourcePointId: 'point-device-status',
    intent: { pageAliases: ['设备'], elementTerms: ['状态'] },
    budget: { minimumTotalScore: 40 }
  }, {
    observe: async () => ({
      url: '#/devices',
      fingerprint: 'devices',
      headings: ['设备'],
      elements: [{ locator: 'testId=device-status', label: '状态' }]
    }),
    now: () => 1
  })

  assert.equal(result.status, 'matched')
  assert.equal(result.sourcePointId, 'point-device-status')
  assert.equal(result.rounds[0].sourcePointId, 'point-device-status')
})

test('stops after two consecutive observations have the same relevant signature', async () => {
  let performedCount = 0
  const result = await runPageExploration({
    intent: { pageAliases: ['日志管理'], elementTerms: ['告警日志'] }
  }, {
    observe: async () => ({
      url: '#/project',
      fingerprint: 'same',
      elements: [{ label: '其他元素' }]
    }),
    chooseAction: async () => ({ type: 'scroll-page' }),
    performAction: async () => { performedCount += 1 },
    now: () => 1
  })

  assert.equal(result.status, 'exhausted')
  assert.equal(result.failureReason, 'observation_stagnated')
  assert.equal(result.rounds.length, 2)
  assert.equal(performedCount, 1)
})

test('ignores volatile failure state when detecting stagnant observations', async () => {
  let observedCount = 0
  let performedCount = 0
  const result = await runPageExploration({
    intent: { pageAliases: ['日志管理'], elementTerms: ['告警日志'] }
  }, {
    observe: async () => observedCount++ === 0
      ? {
          url: '#/logs',
          fingerprint: 'same',
          failureCategory: 'network_timeout',
          readiness: { ready: false },
          elements: [{ label: '其他元素' }]
        }
      : {
          url: '#/logs',
          fingerprint: 'same',
          failureCategory: 'data_loading',
          readiness: { ready: true },
          elements: [{ label: '其他元素' }]
        },
    chooseAction: async () => ({ type: 'scroll-page' }),
    performAction: async () => { performedCount += 1 },
    now: () => 1
  })

  assert.equal(result.status, 'exhausted')
  assert.equal(result.failureReason, 'observation_stagnated')
  assert.equal(result.rounds.length, 2)
  assert.equal(performedCount, 1)
})

test('treats stateTexts and stateText as part of the observation signature', async () => {
  let observationIndex = 0
  const result = await runPageExploration({
    intent: { pageAliases: ['日志管理'], elementTerms: ['告警日志'] },
    budget: { maxRounds: 2 }
  }, {
    observe: async () => observationIndex++ === 0
      ? {
          url: '#/logs',
          fingerprint: 'same',
          stateTexts: ['同步中'],
          elements: [{ label: '其他元素' }]
        }
      : {
          url: '#/logs',
          fingerprint: 'same',
          stateText: '已完成',
          elements: [{ label: '其他元素' }]
        },
    chooseAction: async () => ({ type: 'scroll-page' }),
    performAction: async () => {},
    now: () => 1
  })

  assert.equal(result.status, 'exhausted')
  assert.equal(result.failureReason, 'round_budget_exhausted')
  assert.equal(result.rounds.length, 2)
})

test('returns round_budget_exhausted after the configured number of rounds', async () => {
  let observationIndex = 0
  const result = await runPageExploration({
    intent: { pageAliases: ['日志管理'], elementTerms: ['告警日志'] },
    budget: { maxRounds: 2 }
  }, {
    observe: async () => ({ url: `#/page-${observationIndex}`, fingerprint: `fp-${observationIndex++}`, elements: [] }),
    chooseAction: async () => ({ type: 'scroll-page' }),
    performAction: async () => {},
    now: () => 1
  })

  assert.equal(result.status, 'exhausted')
  assert.equal(result.failureReason, 'round_budget_exhausted')
  assert.equal(result.rounds.length, 2)
})

test('keeps repair trail pending after a typed performAction failure', async () => {
  let performedCount = 0
  const result = await runPageExploration({
    budget: { maxRounds: 1 }
  }, {
    observe: async () => ({
      url: '#/retry',
      fingerprint: 'retry',
      elements: []
    }),
    chooseAction: async () => ({ type: 'dismiss-overlay' }),
    performAction: async () => {
      performedCount += 1
      const error = new Error('overlay is still moving')
      error.failureType = 'overlay_not_ready'
      throw error
    },
    now: () => 1
  })

  assert.equal(result.status, 'exhausted')
  assert.equal(result.failureReason, 'round_budget_exhausted')
  assert.equal(performedCount, 1)
  assert.deepEqual(result.repairTrail, [{ category: 'dismiss_safe_overlay', round: 1, changedFingerprint: null }])
})

test('records round fingerprint and bounded failure counters', async () => {
  let performedCount = 0
  const observation = {
    url: '#/retry',
    title: '重试',
    headings: ['重试'],
    fingerprint: 'retry',
    elements: [{ label: '业务字段' }]
  }
  const result = await runPageExploration({
    budget: { maxRounds: 1 }
  }, {
    observe: async () => observation,
    chooseAction: async () => ({ type: 'dismiss-overlay' }),
    performAction: async () => {
      performedCount += 1
      const error = new Error('overlay is still moving')
      error.failureType = 'overlay_not_ready'
      throw error
    },
    now: () => 1
  })

  assert.equal(performedCount, 1)
  assert.equal(result.rounds[0].pageFingerprint, pageFingerprint(observation))
  assert.equal(result.rounds[0].sameFailureCount, 1)
  assert.equal(result.rounds[0].reauthenticateCount, 0)
  assert.equal(result.rounds[0].remainingDurationMs, 90_000)
  assert.equal(result.sameFailureCount, 1)
  assert.equal(result.reauthenticateCount, 0)
})

test('returns duration_exhausted without observing after reaching the duration budget', async () => {
  let observationCount = 0
  const nowValues = [100, 110, 110]
  const result = await runPageExploration({ budget: { maxDurationMs: 10 } }, {
    observe: async () => { observationCount += 1; return {} },
    now: () => nowValues.shift()
  })

  assert.equal(result.status, 'exhausted')
  assert.equal(result.failureReason, 'duration_exhausted')
  assert.equal(result.rounds.length, 0)
  assert.equal(observationCount, 0)
})

test('blocks when the adapter cannot choose a safe action', async () => {
  let performedCount = 0
  const result = await runPageExploration({}, {
    observe: async () => ({}),
    chooseAction: async () => undefined,
    performAction: async () => { performedCount += 1 },
    now: () => 1
  })

  assert.equal(result.status, 'blocked')
  assert.equal(result.failureReason, 'no_safe_action')
  assert.equal(result.rounds.length, 1)
  assert.equal(performedCount, 0)
})

test('exhausts the same action type before performing beyond its failure budget', async () => {
  let observationIndex = 0
  let performedCount = 0
  const result = await runPageExploration({
    budget: { maxRounds: 5, maxSameFailureRetries: 2 }
  }, {
    observe: async () => ({
      url: `#/page-${observationIndex}`,
      fingerprint: `fp-${observationIndex++}`,
      elements: []
    }),
    chooseAction: async () => ({ type: 'scroll-page' }),
    performAction: async () => {
      performedCount += 1
      const error = new Error('scroll failed')
      error.failureType = 'scroll_failed'
      throw error
    },
    now: () => 1
  })

  assert.equal(result.status, 'exhausted')
  assert.equal(result.failureReason, 'same_failure_limit')
  assert.equal(result.rounds.length, 3)
  assert.deepEqual(result.rounds[2].action, { type: 'scroll-page' })
  assert.equal(performedCount, 2)
})

test('does not limit repeated successful actions by the failure budget', async () => {
  let observationIndex = 0
  let performedCount = 0
  const result = await runPageExploration({
    budget: { maxRounds: 4, maxSameFailureRetries: 1 }
  }, {
    observe: async () => ({
      url: `#/success-${observationIndex}`,
      fingerprint: `success-${observationIndex++}`,
      elements: []
    }),
    chooseAction: async () => ({ type: 'scroll-page' }),
    performAction: async () => { performedCount += 1 },
    now: () => 1
  })

  assert.equal(result.status, 'exhausted')
  assert.equal(result.failureReason, 'round_budget_exhausted')
  assert.equal(result.rounds.length, 4)
  assert.equal(performedCount, 4)
})

test('counts reauthenticate attempts before a successful login recovery', async () => {
  let observationIndex = 0
  let performedCount = 0
  const result = await runPageExploration({
    intent: { pageAliases: ['目标页面'], elementTerms: ['业务字段'], stateTerms: ['即将到期'] },
    budget: { maxRounds: 2, maxLoginRetries: 2 }
  }, {
    observe: async () => observationIndex++ === 0
      ? { url: '#/login', fingerprint: 'login', failureCategory: 'auth_context_lost', elements: [] }
      : { url: '#/target', title: '目标页面', headings: ['目标页面'], fingerprint: 'target', elements: [{ label: '业务字段', contextText: '即将到期' }] },
    chooseAction: async () => ({ type: 'reauthenticate', repairCategory: 'reauthenticate' }),
    performAction: async () => { performedCount += 1 },
    now: () => 1
  })

  assert.equal(result.status, 'matched')
  assert.equal(performedCount, 1)
  assert.equal(result.rounds[0].reauthenticateCount, 1)
  assert.equal(result.reauthenticateCount, 1)
})

test('normalizes string pagination budget fields before choosing a safe action', async () => {
  const result = await runPageExploration({
    budget: {
      maxRounds: 1,
      maxPaginationSteps: 'not-a-number'
    }
  }, {
    observe: async () => ({
      url: '#/next',
      fingerprint: 'next',
      hasNextPage: true,
      elements: []
    }),
    chooseAction: ({ observation, scores, intent, rounds, budget, lastFailureCategory }) =>
      pageLocatorDiscoveryTestables.chooseSafeAction({}, observation, scores, intent, {
        rounds,
        budget,
        lastFailureCategory
      }),
    now: () => 1
  })

  assert.equal(result.rounds[0].action.type, 'paginate')
})

test('uses adapter navigation trail and reports non-negative elapsed and remaining duration', async () => {
  let currentTime = 100
  const observations = [
    { url: '#/start', fingerprint: 'start', elements: [] },
    {
      url: '#/target',
      title: '威胁情报',
      headings: ['威胁情报'],
      fingerprint: 'target',
      elements: [{ label: '停用', contextText: '即将到期' }]
    }
  ]
  const result = await runPageExploration({
    intent: {
      pageAliases: ['威胁情报'],
      elementTerms: ['停用'],
      stateTerms: ['即将到期']
    },
    budget: { maxDurationMs: 100 }
  }, {
    observe: async () => observations.shift(),
    chooseAction: async () => ({ type: 'direct-navigation', target: '威胁情报' }),
    performAction: async () => { currentTime = 130 },
    navigationTrail: () => ['#/start', '#/target'],
    now: () => currentTime
  })

  assert.deepEqual(result.navigationTrail, ['#/start', '#/target'])
  assert.equal(result.durationMs, 30)
  assert.equal(result.remainingDurationMs, 70)
})

test('records typed performAction failures and retries them within the same failure budget', async () => {
  let observationIndex = 0
  let performedCount = 0
  const result = await runPageExploration({
    budget: { maxRounds: 5, maxSameFailureRetries: 2 }
  }, {
    observe: async () => ({
      url: `#/retry-${observationIndex}`,
      fingerprint: `retry-${observationIndex++}`,
      elements: []
    }),
    chooseAction: async () => ({ type: 'dismiss-overlay' }),
    performAction: async () => {
      performedCount += 1
      const error = new Error('overlay is still moving')
      error.failureType = 'overlay_not_ready'
      throw error
    },
    now: () => 1
  })

  assert.equal(result.status, 'exhausted')
  assert.equal(result.failureReason, 'same_failure_limit')
  assert.equal(performedCount, 2)
  assert.deepEqual(result.rounds.slice(0, 2).map((round) => round.actionFailure), [
    { failureType: 'overlay_not_ready', message: 'overlay is still moving' },
    { failureType: 'overlay_not_ready', message: 'overlay is still moving' }
  ])
  assert.deepEqual(result.navigationTrail, ['dismiss-overlay', 'dismiss-overlay', 'dismiss-overlay'])
})

test('rethrows performAction errors without a retryable failure type', async () => {
  const cancelled = new Error('cancelled by caller')
  cancelled.name = 'AbortError'
  let performedCount = 0

  await assert.rejects(runPageExploration({}, {
    observe: async () => ({ url: '#/start', fingerprint: 'start', elements: [] }),
    chooseAction: async () => ({ type: 'scroll-page' }),
    performAction: async () => { performedCount += 1; throw cancelled },
    now: () => 1
  }), (error) => error === cancelled)

  assert.equal(performedCount, 1)
})

test('uses safe defaults for missing request and adapter fields', async () => {
  const result = await runPageExploration()

  assert.equal(result.status, 'blocked')
  assert.equal(result.failureReason, 'no_safe_action')
  assert.equal(result.rounds.length, 1)
  assert.deepEqual(result.navigationTrail, [])
  assert.ok(result.durationMs >= 0)
  assert.ok(result.remainingDurationMs >= 0)
  assert.ok(result.remainingDurationMs <= 90_000)
})

test('preserves custom budget fields for adapter policy decisions', async () => {
  let receivedBudget
  const result = await runPageExploration({ budget: { customLimit: 7, maxRounds: 0 } }, {
    chooseAction: async ({ budget }) => { receivedBudget = budget; return undefined }
  })

  assert.equal(result.status, 'exhausted')
  assert.equal(result.failureReason, 'round_budget_exhausted')
  assert.equal(receivedBudget, undefined)

  const second = await runPageExploration({ budget: { customLimit: 7 } }, {
    chooseAction: async ({ budget }) => { receivedBudget = budget; return undefined }
  })
  assert.equal(second.status, 'blocked')
  assert.equal(receivedBudget.customLimit, 7)
})

test('does not treat same-label candidates from different business rows as stagnant', async () => {
  let observationIndex = 0
  const result = await runPageExploration({
    intent: { elementTerms: ['查看'] },
    budget: { maxRounds: 2 }
  }, {
    observe: async () => ({
      url: '#/records',
      fingerprint: 'records',
      elements: [{ label: '查看', rowContext: `业务行-${observationIndex++}`, contextText: '状态不同' }]
    }),
    chooseAction: async () => ({ type: 'scroll-page' }),
    performAction: async () => {},
    now: () => 1
  })

  assert.equal(result.failureReason, 'round_budget_exhausted')
  assert.equal(result.rounds.length, 2)
})

test('clamps adapter time to a finite monotonic value when readings go backwards or become invalid', async () => {
  const readings = [100, 130, Number.NaN, 110, Number.POSITIVE_INFINITY, 160]
  const result = await runPageExploration({ budget: { maxRounds: 1 } }, {
    observe: async () => ({}),
    chooseAction: async () => undefined,
    now: () => readings.shift()
  })

  assert.equal(result.status, 'blocked')
  assert.equal(result.durationMs, 30)
  assert.equal(result.remainingDurationMs, 89_970)
  assert.ok(Number.isFinite(result.durationMs))
  assert.ok(Number.isFinite(result.remainingDurationMs))
  assert.ok(result.durationMs >= 0)
  assert.ok(result.remainingDurationMs >= 0)
})

test('keeps internal action trail when navigationTrail callback fails', async () => {
  const result = await runPageExploration({ budget: { maxRounds: 1 } }, {
    observe: async () => ({}),
    chooseAction: async () => ({ type: 'scroll-page' }),
    performAction: async () => {},
    navigationTrail: async () => { throw new Error('trail unavailable') },
    now: () => 1
  })

  assert.equal(result.status, 'exhausted')
  assert.equal(result.failureReason, 'round_budget_exhausted')
  assert.deepEqual(result.navigationTrail, ['scroll-page'])
  assert.equal(result.trailError, 'trail unavailable')
})

test('records bounded repair trail and whether each action changed the page fingerprint', async () => {
  let index = 0
  const result = await runPageExploration({
    intent: { pageAliases: ['目标页'], elementTerms: ['业务字段'] },
    budget: { maxRounds: 3, minimumTotalScore: 40 }
  }, {
    observe: async () => index++ === 0
      ? { url: '#/start', fingerprint: 'start', elements: [] }
      : { url: '#/target', fingerprint: 'target', title: '目标页', headings: ['目标页'], elements: [{ label: '业务字段' }] },
    chooseAction: async () => ({ type: 'wait-ready' }),
    performAction: async () => {},
    now: () => 1
  })

  assert.equal(result.status, 'matched')
  assert.deepEqual(result.repairTrail, [{ category: 'wait_ready', round: 1, changedFingerprint: true }])
})

test('rejects an AI repair category outside the deterministic allowlist', async () => {
  const result = await runPageExploration({ intent: { elementTerms: ['业务字段'] } }, {
    observe: async () => ({ fingerprint: 'same', elements: [] }),
    chooseAction: async () => ({ category: 'execute_javascript' }),
    performAction: async () => { throw new Error('must not run') },
    now: () => 1
  })

  assert.equal(result.status, 'blocked')
  assert.equal(result.failureReason, 'unsafe_repair_category')
  assert.deepEqual(result.repairTrail, [])
})

import test from 'node:test'
import assert from 'node:assert/strict'
import {
  SAFE_REPAIR_ACTIONS,
  SAFE_REPAIR_CATEGORIES,
  SAFE_AI_REPAIR_CATEGORIES,
  isKnownRepairCategory,
  normalizeAiRepairSuggestionCategory,
  normalizeRepairActionType,
  normalizeRepairBudget,
  normalizeRepairCategory,
  shouldAttemptReauthenticate,
  shouldRetrySameFailure
} from './page-repair-policy.mjs'

test('exposes a closed repair action enum and category set', () => {
  assert.deepEqual(SAFE_REPAIR_ACTIONS, {
    WAIT_READY: 'wait-ready',
    REOBSERVE: 'reobserve',
    REAUTHENTICATE: 'reauthenticate',
    DISMISS_OVERLAY: 'dismiss-overlay',
    MENU_SEARCH: 'menu-search',
    EXPAND_NAVIGATION: 'expand-navigation',
    EXPAND_SECTION: 'expand-section',
    SCROLL_PAGE: 'scroll-page',
    SCROLL_CONTAINER: 'scroll-container',
    SWITCH_TAB: 'switch-tab',
    PAGINATE: 'paginate',
    RELOAD: 'reload',
    BACK: 'back',
    DIRECT_NAVIGATION: 'direct-navigation'
  })
  assert.deepEqual(SAFE_REPAIR_CATEGORIES, [
    'wait_ready',
    'dismiss_safe_overlay',
    'menu_search',
    'same_origin_navigation',
    'switch_readonly_tab',
    'scroll_container',
    'paginate',
    'reobserve',
    'reauthenticate'
  ])
  assert.deepEqual(SAFE_AI_REPAIR_CATEGORIES, [
    'wait_ready',
    'reobserve',
    'reauthenticate'
  ])
})

test('normalizes legacy repair actions and rejects unknown categories', () => {
  assert.equal(normalizeRepairCategory('wait-ready'), 'wait_ready')
  assert.equal(normalizeRepairCategory('dismiss-overlay'), 'dismiss_safe_overlay')
  assert.equal(normalizeRepairCategory('menu-search'), 'menu_search')
  assert.equal(normalizeRepairCategory('direct-navigation'), 'same_origin_navigation')
  assert.equal(normalizeRepairCategory('switch-tab'), 'switch_readonly_tab')
  assert.equal(normalizeRepairCategory('not-a-category'), undefined)
  assert.equal(isKnownRepairCategory('not-a-category'), false)
  assert.equal(normalizeAiRepairSuggestionCategory('wait_ready'), 'wait_ready')
  assert.equal(normalizeAiRepairSuggestionCategory('menu_search'), undefined)
  assert.equal(normalizeRepairActionType('menu_search'), 'menu-search')
  assert.equal(normalizeRepairActionType('same_origin_navigation'), 'direct-navigation')
  assert.equal(normalizeRepairActionType('not-a-category'), undefined)
})

test('normalizes repair budgets and bounded retry rules', () => {
  assert.deepEqual(normalizeRepairBudget({
    maxSameFailureRetries: '3',
    maxLoginRetries: 9,
    maxReloads: -1,
    maxPaginationSteps: '2'
  }), {
    maxSameFailureRetries: 3,
    maxLoginRetries: 9,
    maxReloads: 1,
    maxPaginationSteps: 2
  })
  assert.equal(shouldRetrySameFailure(1, { maxSameFailureRetries: 2 }), true)
  assert.equal(shouldRetrySameFailure(2, { maxSameFailureRetries: 2 }), false)
  assert.equal(shouldAttemptReauthenticate(0, { maxLoginRetries: 3 }, ''), true)
  assert.equal(shouldAttemptReauthenticate(1, { maxLoginRetries: 3 }, ''), true)
  assert.equal(shouldAttemptReauthenticate(2, { maxLoginRetries: 3 }, ''), true)
  assert.equal(shouldAttemptReauthenticate(3, { maxLoginRetries: 3 }, ''), false)
  assert.equal(shouldAttemptReauthenticate(0, { maxLoginRetries: 3 }, 'login_failed'), false)
  assert.equal(shouldAttemptReauthenticate(0, { maxLoginRetries: 2 }, ''), true)
  assert.equal(shouldAttemptReauthenticate(1, { maxLoginRetries: 2 }, ''), true)
  assert.equal(shouldAttemptReauthenticate(2, { maxLoginRetries: 2 }, ''), false)
})

const DEFAULT_REPAIR_BUDGET = Object.freeze({
  maxSameFailureRetries: 2,
  maxLoginRetries: 1,
  maxReloads: 1,
  maxPaginationSteps: 3
})

export const SAFE_REPAIR_ACTIONS = Object.freeze({
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

export const SAFE_REPAIR_CATEGORIES = Object.freeze([
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

export const SAFE_AI_REPAIR_CATEGORIES = Object.freeze([
  'wait_ready',
  'reobserve',
  'reauthenticate'
])

const REPAIR_CATEGORY_BY_ACTION = Object.freeze({
  'wait-ready': 'wait_ready',
  wait_ready: 'wait_ready',
  wait: 'wait_ready',
  'dismiss-overlay': 'dismiss_safe_overlay',
  dismiss_safe_overlay: 'dismiss_safe_overlay',
  'menu-search': 'menu_search',
  menu_search: 'menu_search',
  'expand-navigation': 'menu_search',
  'expand-section': 'menu_search',
  'direct-navigation': 'same_origin_navigation',
  navigate: 'same_origin_navigation',
  goto: 'same_origin_navigation',
  same_origin_navigation: 'same_origin_navigation',
  'switch-tab': 'switch_readonly_tab',
  switch_readonly_tab: 'switch_readonly_tab',
  'scroll-page': 'scroll_container',
  scroll: 'scroll_container',
  'scroll-container': 'scroll_container',
  scroll_container: 'scroll_container',
  paginate: 'paginate',
  reobserve: 'reobserve',
  reload: 'reobserve',
  back: 'same_origin_navigation',
  reauthenticate: 'reauthenticate'
})

const REPAIR_ACTION_BY_CATEGORY = Object.freeze({
  wait_ready: SAFE_REPAIR_ACTIONS.WAIT_READY,
  dismiss_safe_overlay: SAFE_REPAIR_ACTIONS.DISMISS_OVERLAY,
  menu_search: SAFE_REPAIR_ACTIONS.MENU_SEARCH,
  same_origin_navigation: SAFE_REPAIR_ACTIONS.DIRECT_NAVIGATION,
  switch_readonly_tab: SAFE_REPAIR_ACTIONS.SWITCH_TAB,
  scroll_container: SAFE_REPAIR_ACTIONS.SCROLL_CONTAINER,
  paginate: SAFE_REPAIR_ACTIONS.PAGINATE,
  reobserve: SAFE_REPAIR_ACTIONS.REOBSERVE,
  reauthenticate: SAFE_REPAIR_ACTIONS.REAUTHENTICATE
})

const toCount = (value, defaultValue) => {
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed >= 0 ? Math.floor(parsed) : defaultValue
}

export const normalizeRepairCategory = (value) => {
  const normalized = String(value || '').trim()
  if (SAFE_REPAIR_CATEGORIES.includes(normalized)) return normalized
  return REPAIR_CATEGORY_BY_ACTION[normalized]
}

export const normalizeRepairActionType = (value) => {
  const normalized = String(value || '').trim()
  if (Object.values(SAFE_REPAIR_ACTIONS).includes(normalized)) return normalized
  const category = normalizeRepairCategory(normalized)
  return category ? REPAIR_ACTION_BY_CATEGORY[category] : undefined
}

export const normalizeAiRepairSuggestionCategory = (value) => {
  const normalized = normalizeRepairCategory(value)
  return normalized && SAFE_AI_REPAIR_CATEGORIES.includes(normalized) ? normalized : undefined
}

export const isKnownRepairCategory = (value) => Boolean(normalizeRepairCategory(value))

export const normalizeRepairBudget = (rawBudget = {}) => {
  const source = rawBudget && typeof rawBudget === 'object' ? rawBudget : {}
  return {
    maxSameFailureRetries: toCount(source.maxSameFailureRetries, DEFAULT_REPAIR_BUDGET.maxSameFailureRetries),
    maxLoginRetries: toCount(source.maxLoginRetries, DEFAULT_REPAIR_BUDGET.maxLoginRetries),
    maxReloads: toCount(source.maxReloads, DEFAULT_REPAIR_BUDGET.maxReloads),
    maxPaginationSteps: toCount(source.maxPaginationSteps, DEFAULT_REPAIR_BUDGET.maxPaginationSteps)
  }
}

export const shouldRetrySameFailure = (sameFailureCount, rawBudget = {}) => {
  const budget = normalizeRepairBudget(rawBudget)
  return toCount(sameFailureCount, 0) < budget.maxSameFailureRetries
}

export const shouldAttemptReauthenticate = (reauthenticateCount, rawBudget = {}, lastFailureCategory = '') => {
  if (String(lastFailureCategory || '') === 'login_failed') return false
  const budget = normalizeRepairBudget(rawBudget)
  return toCount(reauthenticateCount, 0) < budget.maxLoginRetries
}
